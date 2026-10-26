package com.marcobehler.chattermore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marcobehler.chattermore.background.NotificationTarget
import com.marcobehler.chattermore.background.BackgroundNotificationMode
import com.marcobehler.chattermore.background.BackgroundPollingScheduler
import com.marcobehler.chattermore.background.BackgroundPollingSession
import com.marcobehler.chattermore.background.BackgroundPollingSettings
import com.marcobehler.chattermore.background.BackgroundPollingWorker
import com.marcobehler.chattermore.data.ChannelRepository
import com.marcobehler.chattermore.data.AttachmentSelection
import com.marcobehler.chattermore.data.AttachmentStatus
import com.marcobehler.chattermore.data.DraftAttachment
import com.marcobehler.chattermore.data.IncomingShare
import com.marcobehler.chattermore.data.MessageSearch
import com.marcobehler.chattermore.model.ConnectionState
import com.marcobehler.chattermore.model.AuthenticatedServerConfig
import com.marcobehler.chattermore.model.ChannelNotFoundException
import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedItem
import com.marcobehler.chattermore.model.LinkPreview
import com.marcobehler.chattermore.model.FeedMutation
import com.marcobehler.chattermore.model.MattermostException
import com.marcobehler.chattermore.model.MattermostFile
import com.marcobehler.chattermore.model.MattermostChannel
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.MessageSearchResult
import com.marcobehler.chattermore.model.ConversationMutation
import com.marcobehler.chattermore.model.ConversationSummary
import com.marcobehler.chattermore.model.InitializedDirectory
import com.marcobehler.chattermore.model.MattermostTeam
import com.marcobehler.chattermore.model.RememberedConversation
import com.marcobehler.chattermore.model.ServerConfig
import com.marcobehler.chattermore.model.TeamNotFoundException
import com.marcobehler.chattermore.model.UploadCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale

sealed interface ScreenState {
    data object Loading : ScreenState

    data class Setup(
        val config: ServerConfig? = null,
        val connecting: Boolean = false,
        val error: String? = null,
    ) : ScreenState

    data class Overview(
        val teamId: String,
        val teamTitle: String,
        val conversations: List<ConversationSummary>,
    ) : ScreenState

    data class Conversation(
        val id: String,
        val title: String,
        val canNavigateBack: Boolean = false,
        val peerUserId: String? = null,
        val loading: Boolean = false,
    ) : ScreenState
}

data class MessageEditState(
    val postId: String,
    val originalMessage: String,
    val draftMessage: String,
    val submitting: Boolean = false,
    val failed: Boolean = false,
)

data class ComposerState(
    val attachments: List<DraftAttachment> = emptyList(),
    val capabilities: UploadCapabilities = UploadCapabilities(),
    val resolvingCount: Int = 0,
) {
    val readyFiles: List<MattermostFile>
        get() = attachments.mapNotNull { (it.status as? AttachmentStatus.Ready)?.file }

    val hasBlockingAttachments: Boolean
        get() = resolvingCount > 0 || attachments.any { it.status !is AttachmentStatus.Ready }
}

data class MessageSearchUiState(
    val active: Boolean = false,
    val query: String = "",
    val results: List<MessageSearchResult> = emptyList(),
    val searching: Boolean = false,
    val showingLocalResults: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val canLoadMore: Boolean = false,
    val openingPostId: String? = null,
)

class MainViewModel(
    private val container: AppContainer,
    unsupportedMessage: String,
) : ViewModel() {
    private val repository = ChannelRepository(
        api = container.api,
        socket = container.socket,
        scope = viewModelScope,
        conversationCache = container.conversationCache,
        unsupportedMessage = unsupportedMessage,
    )
    private val _screen = MutableStateFlow<ScreenState>(ScreenState.Loading)
    private val _notificationTarget = MutableStateFlow<NotificationTarget?>(null)
    private val _notificationPostId = MutableStateFlow<String?>(null)
    private var openingNotification: NotificationTarget? = null
    val notificationTarget = _notificationTarget.asStateFlow()
    val notificationPostId = _notificationPostId.asStateFlow()
    private val _messageEdit = MutableStateFlow<MessageEditState?>(null)
    private val _composer = MutableStateFlow(ComposerState())
    private val _incomingShares = MutableStateFlow<List<IncomingShare>>(emptyList())
    private val _incomingShareDestinations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    private val _messageSearch = MutableStateFlow(MessageSearchUiState())
    private val _composerMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val uploadJobs = mutableMapOf<String, Job>()
    private var messageSearchJob: Job? = null
    private var messageJumpJob: Job? = null
    private var messageSearchGeneration = 0
    private val searchUsersById = mutableMapOf<String, MattermostUser>()
    private val messageSearchCache =
        object : LinkedHashMap<MessageSearchCacheKey, CachedMessageSearch>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<MessageSearchCacheKey, CachedMessageSearch>,
            ): Boolean = size > MAX_CACHED_SEARCHES
        }
    private val uploadSemaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
    private val benchmarkConnectionState = MutableStateFlow(ConnectionState.CONNECTED)
    private val benchmarkUserPresence =
        MutableStateFlow<Map<String, com.marcobehler.chattermore.model.UserPresence>>(emptyMap())
    private val benchmarkMentionContext =
        MutableStateFlow(com.marcobehler.chattermore.model.MentionContext())
    private val benchmarkChannelUsers = MutableStateFlow<List<MattermostUser>>(emptyList())
    private var attachmentGeneration = 0
    private var benchmarkItems: List<FeedItem>? = null
    private var currentUserId: String? = null
    private var authenticatedConfig: AuthenticatedServerConfig? = null
    private var configuredChannel: MattermostChannel? = null
    private var authenticatedUser: MattermostUser? = null
    private var authenticatedTeam: MattermostTeam? = null
    private var loadingOlder = false
    private var loadingNewer = false
    private var connectionGeneration = 0
    private var connectionJob: Job? = null
    private var conversationRequestGeneration = 0
    private var backgroundSettingsGeneration = 0
    private var lastReadyConversationScreen: ScreenState.Conversation? = null
    private var overviewScreen: ScreenState.Overview? = null
    private val overviewConversations = mutableListOf<ConversationSummary>()

    val screen: StateFlow<ScreenState> = _screen.asStateFlow()
    val messageEdit: StateFlow<MessageEditState?> = _messageEdit.asStateFlow()
    val composer: StateFlow<ComposerState> = _composer.asStateFlow()
    val incomingShares: StateFlow<List<IncomingShare>> = _incomingShares.asStateFlow()
    val incomingShareDestinations = _incomingShareDestinations.asStateFlow()
    val messageSearch: StateFlow<MessageSearchUiState> = _messageSearch.asStateFlow()
    val composerMessages: SharedFlow<String> = _composerMessages.asSharedFlow()
    val mutations: SharedFlow<FeedMutation> = repository.mutations
    val conversationMutations: SharedFlow<ConversationMutation> = repository.conversationMutations
    val connectionState: StateFlow<ConnectionState> =
        if (BuildConfig.BENCHMARK_FIXTURES) {
            benchmarkConnectionState.asStateFlow()
        } else {
            repository.connectionState
        }
    val refreshing: StateFlow<Boolean> = repository.refreshing
    val userPresence =
        if (BuildConfig.BENCHMARK_FIXTURES) benchmarkUserPresence.asStateFlow()
        else repository.userPresence
    val mentionContext =
        if (BuildConfig.BENCHMARK_FIXTURES) benchmarkMentionContext.asStateFlow()
        else repository.mentionContext
    val channelUsers =
        if (BuildConfig.BENCHMARK_FIXTURES) benchmarkChannelUsers.asStateFlow()
        else repository.channelUsers

    init {
        viewModelScope.launch {
            repository.uploadCapabilities.collect { capabilities ->
                _composer.update { it.copy(capabilities = capabilities) }
            }
        }
        viewModelScope.launch {
            repository.channelUsers.collect { users ->
                users.forEach { searchUsersById[it.id] = it }
            }
        }
        viewModelScope.launch {
            repository.conversationMutations.collect { mutation ->
                overviewScreen ?: return@collect
                when (mutation) {
                    is ConversationMutation.Replace -> {
                        overviewConversations.clear()
                        overviewConversations.addAll(mutation.conversations)
                    }
                    is ConversationMutation.Update -> {
                        val index = overviewConversations.indexOfFirst {
                            it.id == mutation.conversation.id
                        }
                        if (index < 0) {
                            overviewConversations += mutation.conversation
                        } else {
                            overviewConversations[index] = mutation.conversation
                        }
                    }
                }
                _incomingShareDestinations.value = overviewConversations.toList()
            }
        }
        viewModelScope.launch {
            repository.mutations.collect { mutation ->
                configuredChannel?.id?.let(::invalidateMessageSearchCache)
                if (!container.configStore.backgroundPollingSettings().enabled) return@collect
                val channelId = configuredChannel?.id ?: return@collect
                mutation.itemsForCheckpoint()
                    .asSequence()
                    .filter { it.channelId == channelId }
                    .filter { it.serverId != null }
                    .maxWithOrNull(compareBy(FeedItem::createAt, FeedItem::serverId))
                    ?.let { item ->
                        container.configStore.advanceBackgroundCheckpoint(
                            postId = requireNotNull(item.serverId),
                            createAt = item.createAt,
                            foregroundSync = true,
                        )
                    }
            }
        }
        if (BuildConfig.BENCHMARK_FIXTURES) {
            _screen.value = ScreenState.Setup()
        } else {
            val stored = container.configStore.load()
            if (stored == null) {
                BackgroundPollingScheduler.reconcile(container.context)
                _screen.value = ScreenState.Setup(
                    config = container.configStore.loadRememberedServer()?.asLoginConfig(),
                )
            } else {
                connect(stored, persist = false)
            }
        }
    }

    fun connect(config: ServerConfig, persist: Boolean = true) {
        if ((_screen.value as? ScreenState.Setup)?.connecting == true) return
        val connectionAttempt = ++connectionGeneration
        connectionJob?.cancel()
        closeMessageSearch()
        messageSearchCache.clear()
        searchUsersById.clear()
        if (
            persist &&
            container.configStore.backgroundPollingSettings().enabled &&
            !isRememberedAccount(config)
        ) {
            backgroundSettingsGeneration++
            BackgroundPollingScheduler.cancel(container.context)
            container.configStore.setBackgroundPollingSettings(
                container.configStore.backgroundPollingSettings().copy(enabled = false),
            )
        }
        conversationRequestGeneration++
        val initializationGeneration = conversationRequestGeneration
        clearAttachments()
        _messageEdit.value = null
        _screen.value = ScreenState.Setup(config = config, connecting = true)
        connectionJob = viewModelScope.launch {
            runCatching {
                val normalized = container.api.normalizedConfig(config)
                repository.initialize(normalized) { directory ->
                    if (connectionAttempt != connectionGeneration) return@initialize
                    showInitializedDirectory(
                        directory = directory,
                        selectInitialScreen =
                            initializationGeneration == conversationRequestGeneration,
                        restoreRememberedConversation = true,
                    )
                }
            }
                .onSuccess { directory ->
                    if (connectionAttempt != connectionGeneration) return@onSuccess
                    val normalized = container.api.normalizedConfig(config).copy(
                        teamName = directory.config.teamName,
                    )
                    showInitializedDirectory(
                        directory = directory,
                        selectInitialScreen =
                            initializationGeneration == conversationRequestGeneration,
                        restoreRememberedConversation = true,
                    )
                    if (_notificationTarget.value != null && openingNotification == null) failNotification()
                    if (persist) {
                        container.configStore.save(normalized)
                    } else {
                        container.configStore.rememberServer(normalized)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (connectionAttempt != connectionGeneration) return@onFailure
                    currentUserId = null
                    authenticatedConfig = null
                    configuredChannel = null
                    authenticatedUser = null
                    authenticatedTeam = null
                    overviewScreen = null
                    // A failed automatic reconnect is often transient, so keep the saved
                    // credentials and let the user retry without re-entering the password.
                    _screen.value = ScreenState.Setup(
                        config = if (persist) config.copy(password = "") else config,
                        error = userMessage(error),
                    )
                }
        }
    }

    private fun isRememberedAccount(config: ServerConfig): Boolean {
        val remembered = container.configStore.loadRememberedServer() ?: return false
        val normalized = runCatching { container.api.normalizedConfig(config) }.getOrNull()
            ?: return false
        return normalized.baseUrl == remembered.baseUrl &&
            normalized.username.equals(remembered.username, ignoreCase = true)
    }

    fun clearConnection() {
        clearNotification()
        connectionGeneration++
        connectionJob?.cancel()
        connectionJob = null
        closeMessageSearch()
        backgroundSettingsGeneration++
        clearAttachments()
        repository.close()
        conversationRequestGeneration++
        currentUserId = null
        authenticatedConfig = null
        configuredChannel = null
        authenticatedUser = null
        authenticatedTeam = null
        overviewScreen = null
        lastReadyConversationScreen = null
        _messageEdit.value = null
        container.imageLoader.configure(null)
        BackgroundPollingScheduler.cancel(container.context)
        container.configStore.clearCredentials()
        _screen.value = ScreenState.Setup(
            config = container.configStore.loadRememberedServer()?.asLoginConfig(),
        )
    }

    fun setForeground(foreground: Boolean) {
        repository.setForeground(foreground)
    }

    fun backgroundPollingSettings(): BackgroundPollingSettings =
        container.configStore.backgroundPollingSettings()

    fun setBackgroundPollingPreferences(
        mode: BackgroundNotificationMode,
        showPreview: Boolean,
    ) {
        val current = container.configStore.backgroundPollingSettings()
        container.configStore.setBackgroundPollingSettings(
            current.copy(mode = mode, showPreview = showPreview),
        )
    }

    fun setBackgroundPollingEnabled(enabled: Boolean): Boolean {
        val generation = ++backgroundSettingsGeneration
        if (!enabled) {
            val current = container.configStore.backgroundPollingSettings()
            BackgroundPollingScheduler.cancel(container.context)
            container.configStore.setBackgroundPollingSettings(current.copy(enabled = false))
            return true
        }
        val config = authenticatedConfig ?: return false
        val channel = configuredChannel ?: return false
        val user = authenticatedUser ?: return false
        viewModelScope.launch {
            synchronizeBackgroundSession(config, channel, user)
            if (generation != backgroundSettingsGeneration) return@launch
            val current = container.configStore.backgroundPollingSettings()
            container.configStore.setBackgroundPollingSettings(current.copy(enabled = true))
            BackgroundPollingScheduler.schedule(container.context)
        }
        return true
    }

    fun enableBenchmarkFixture(count: Int, mode: String? = null) {
        if (!BuildConfig.BENCHMARK_FIXTURES) return
        val fixture = BenchmarkFixtures.create(mode, count)
        benchmarkItems = fixture.items
        benchmarkConnectionState.value = ConnectionState.CONNECTED
        benchmarkUserPresence.value = fixture.presence
        benchmarkMentionContext.value = fixture.mentionContext
        benchmarkChannelUsers.value = fixture.users
        currentUserId = fixture.mentionContext.currentUserId
        container.configStore.setBackgroundPollingSettings(fixture.backgroundSettings)

        val screen = fixture.screen
        if (screen is ScreenState.Overview) {
            overviewConversations.clear()
            overviewConversations.addAll(screen.conversations)
            _incomingShareDestinations.value = overviewConversations.toList()
            overviewScreen = screen
            lastReadyConversationScreen = null
        } else if (screen is ScreenState.Conversation) {
            lastReadyConversationScreen = screen
        }
        _screen.value = screen
    }

    fun openNotification(target: NotificationTarget) {
        if (_notificationTarget.value == target) return
        _notificationTarget.value = target
        _notificationPostId.value = null
        openingNotification = null
        if (!tryOpenNotification() && authenticatedConfig != null && connectionJob?.isActive != true) {
            failNotification()
        }
    }

    fun notificationDisplayed(postId: String) {
        if (_notificationPostId.value == postId) clearNotification()
    }

    private fun clearNotification() {
        _notificationTarget.value = null
        _notificationPostId.value = null
        openingNotification = null
    }

    private fun failNotification() {
        clearNotification()
        _composerMessages.tryEmit("That notification's conversation or message is no longer available for this account.")
    }

    private fun tryOpenNotification(): Boolean {
        val target = _notificationTarget.value ?: return false
        if (openingNotification == target) return true
        val config = authenticatedConfig ?: return false
        val userId = currentUserId ?: return false
        if (!target.matches(config, userId)) {
            failNotification()
            return false
        }
        val summary = overviewConversations.firstOrNull { it.id == target.channelId } ?: return false
        openingNotification = target
        openConversation(summary, target)
        return true
    }

    fun openConversation(summary: ConversationSummary) = openConversation(summary, null)

    private fun openConversation(summary: ConversationSummary, notification: NotificationTarget?) {
        if (BuildConfig.BENCHMARK_FIXTURES || summary.id.isBlank()) return
        val requestGeneration = ++conversationRequestGeneration
        if (notification != null && _screen.value.readyConversation()?.id == summary.id) {
            closeMessageSearch()
        } else {
            clearConversationBoundComposerState(preserveNotification = notification != null)
        }
        _screen.value = ScreenState.Conversation(
            id = "loading:${summary.id}:$requestGeneration",
            title = summary.title,
            canNavigateBack = true,
            peerUserId = summary.peerUserId,
            loading = true,
        )
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { repository.openConversation(summary.id) }
                .onSuccess { conversation ->
                    if (requestGeneration != conversationRequestGeneration) return@onSuccess
                    if (conversation == null) {
                        overviewScreen?.let { _screen.value = it }
                        if (notification != null) failNotification()
                        return@onSuccess
                    }
                    configuredChannel = conversation.channel
                    val screen = ScreenState.Conversation(
                        id = conversation.channelId,
                        title = conversation.title,
                        canNavigateBack = true,
                        peerUserId = (conversation as? com.marcobehler.chattermore.model.Conversation.DirectMessage)
                            ?.peer
                            ?.id,
                    )
                    lastReadyConversationScreen = screen
                    rememberSelectedConversation(conversation.channelId)
                    _screen.value = screen
                    synchronizeSelectedBackgroundSession()
                    if (notification != null) {
                        try {
                            val loaded = repository.snapshot().any { it.serverId == notification.postId } ||
                                repository.loadMessageContext(notification.channelId, notification.postId)
                            if (requestGeneration != conversationRequestGeneration) return@onSuccess
                            if (loaded) _notificationPostId.value = notification.postId else failNotification()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            if (requestGeneration == conversationRequestGeneration) failNotification()
                        }
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (requestGeneration != conversationRequestGeneration) return@onFailure
                    if (notification != null) failNotification()
                    overviewScreen?.let { _screen.value = it }
                    _composerMessages.emit(
                        error.message ?: "The conversation could not be opened.",
                    )
                }
        }
    }

    fun openDirectMessage(userId: String, title: String) {
        if (BuildConfig.BENCHMARK_FIXTURES || userId.isBlank()) return
        val current = _screen.value as? ScreenState.Conversation ?: return
        if (!current.loading && current.peerUserId == userId) return
        val fallback = lastReadyConversationScreen ?: current.takeUnless { it.loading }
        val requestGeneration = ++conversationRequestGeneration
        clearConversationBoundComposerState()
        _screen.value = ScreenState.Conversation(
            id = "loading-dm:$requestGeneration",
            title = title.ifBlank { userId },
            canNavigateBack = true,
            peerUserId = userId,
            loading = true,
        )
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching { repository.openDirectMessage(userId) }
                .onSuccess { conversation ->
                    if (requestGeneration != conversationRequestGeneration) return@onSuccess
                    if (conversation == null) {
                        _screen.value = fallback ?: overviewScreen ?: return@onSuccess
                        return@onSuccess
                    }
                    val screen = ScreenState.Conversation(
                        id = conversation.channelId,
                        title = conversation.title,
                        canNavigateBack = true,
                        peerUserId = conversation.peer.id,
                    )
                    lastReadyConversationScreen = screen
                    configuredChannel = conversation.channel
                    rememberSelectedConversation(conversation.channelId)
                    _screen.value = screen
                    synchronizeSelectedBackgroundSession()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (requestGeneration != conversationRequestGeneration) return@onFailure
                    _screen.value = fallback ?: overviewScreen ?: return@onFailure
                    _composerMessages.emit(
                        when (error) {
                            is MattermostException -> when (error.statusCode) {
                                403 -> "This account is not allowed to start direct messages."
                                404 -> "The selected user is no longer available."
                                else -> error.message
                            }
                            is IOException -> "The direct message could not be opened. Check the connection."
                            else -> error.message ?: "The direct message could not be opened."
                        },
                    )
                }
        }
    }

    fun returnToOverview() {
        if (BuildConfig.BENCHMARK_FIXTURES) return
        val overview = overviewScreen ?: return
        conversationRequestGeneration++
        clearConversationBoundComposerState()
        repository.leaveConversation()
        _screen.value = overview
    }

    suspend fun snapshot(): List<FeedItem> = benchmarkItems ?: repository.snapshot()

    fun openMessageSearch() {
        if (screen.value.readyConversation() == null) return
        messageSearchJob?.cancel()
        messageJumpJob?.cancel()
        messageSearchGeneration++
        _messageSearch.value = MessageSearchUiState(active = true)
    }

    fun closeMessageSearch() {
        messageSearchJob?.cancel()
        messageSearchJob = null
        messageJumpJob?.cancel()
        messageJumpJob = null
        messageSearchGeneration++
        _messageSearch.value = MessageSearchUiState()
    }

    fun updateMessageSearchQuery(query: String) {
        if (!_messageSearch.value.active) return
        messageSearchJob?.cancel()
        messageJumpJob?.cancel()
        messageJumpJob = null
        val generation = ++messageSearchGeneration
        val normalizedQuery = query.trim()
        if (!MessageSearch.isSearchable(normalizedQuery)) {
            _messageSearch.value = MessageSearchUiState(
                active = true,
                query = query,
            )
            return
        }

        _messageSearch.value = MessageSearchUiState(
            active = true,
            query = query,
            searching = true,
        )
        messageSearchJob = viewModelScope.launch {
            val localTerms = MessageSearch.highlightTerms(normalizedQuery)
            val localResults = withContext(Dispatchers.Default) {
                snapshot().asReversed()
                    .asSequence()
                    .filter { it.serverId != null }
                    .filter { MessageSearch.locallyMatches(it.message, localTerms) }
                    .take(MAX_LOCAL_SEARCH_RESULTS)
                    .map { item ->
                        MessageSearchResult(
                            postId = requireNotNull(item.serverId),
                            channelId = item.channelId,
                            rootId = "",
                            userId = item.userId,
                            author = item.author,
                            message = item.message,
                            createAt = item.createAt,
                            fileCount = item.fileIds.size,
                            matchTerms = localTerms,
                        )
                    }
                    .toList()
            }
            if (generation != messageSearchGeneration) return@launch
            _messageSearch.value = _messageSearch.value.copy(
                results = localResults,
                showingLocalResults = localResults.isNotEmpty(),
            )

            if (BuildConfig.BENCHMARK_FIXTURES) {
                _messageSearch.value = _messageSearch.value.copy(searching = false)
                return@launch
            }
            delay(SEARCH_DEBOUNCE_MS)
            if (generation != messageSearchGeneration) return@launch

            val channel = configuredChannel ?: return@launch finishSearchWithUnavailableState()
            val config = authenticatedConfig ?: return@launch finishSearchWithUnavailableState()
            val team = authenticatedTeam ?: return@launch finishSearchWithUnavailableState()
            val key = MessageSearchCacheKey(
                channelId = channel.id,
                query = normalizedQuery.lowercase(Locale.ROOT),
            )
            messageSearchCache[key]?.let { cached ->
                if (generation == messageSearchGeneration) {
                    _messageSearch.value = _messageSearch.value.copy(
                        results = cached.results,
                        searching = false,
                        showingLocalResults = false,
                        error = null,
                        page = cached.page,
                        canLoadMore = cached.canLoadMore,
                    )
                }
                return@launch
            }

            runCatching {
                searchServerPage(
                    config = config,
                    teamId = team.id,
                    channel = channel,
                    query = normalizedQuery,
                    page = 0,
                )
            }.onSuccess { result ->
                if (generation != messageSearchGeneration) return@onSuccess
                messageSearchCache[key] = result
                _messageSearch.value = _messageSearch.value.copy(
                    results = result.results,
                    searching = false,
                    showingLocalResults = false,
                    error = null,
                    page = result.page,
                    canLoadMore = result.canLoadMore,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (generation != messageSearchGeneration) return@onFailure
                _messageSearch.value = _messageSearch.value.copy(
                    searching = false,
                    error = searchMessage(error),
                )
            }
        }
    }

    fun loadMessageSearchResult(result: MessageSearchResult) {
        val state = _messageSearch.value
        if (!state.active || state.openingPostId != null) return
        val channel = configuredChannel
        val targetPostId = MessageSearch.targetPostId(result)
        if (
            channel == null ||
            result.channelId != channel.id ||
            targetPostId.isBlank() ||
            BuildConfig.BENCHMARK_FIXTURES
        ) {
            _messageSearch.value = state.copy(
                error = "That message is unavailable in this conversation.",
            )
            return
        }

        messageSearchJob?.cancel()
        messageSearchJob = null
        messageSearchGeneration++
        _messageSearch.value = state.copy(
            searching = false,
            showingLocalResults = false,
            error = null,
            openingPostId = targetPostId,
        )
        messageJumpJob?.cancel()
        messageJumpJob = viewModelScope.launch {
            try {
                if (!repository.loadMessageContext(channel.id, targetPostId)) {
                    failMessageJump(
                        targetPostId,
                        "That message is no longer available in this conversation.",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failMessageJump(targetPostId, messageJumpMessage(error))
            }
        }
    }

    fun loadMoreMessageSearchResults() {
        val state = _messageSearch.value
        if (
            !state.active ||
            state.searching ||
            state.openingPostId != null ||
            !state.canLoadMore ||
            !MessageSearch.isSearchable(state.query)
        ) {
            return
        }
        val channel = configuredChannel ?: return
        val config = authenticatedConfig ?: return
        val team = authenticatedTeam ?: return
        val query = state.query.trim()
        val generation = ++messageSearchGeneration
        messageSearchJob?.cancel()
        _messageSearch.value = state.copy(searching = true, error = null)
        messageSearchJob = viewModelScope.launch {
            runCatching {
                searchServerPage(
                    config = config,
                    teamId = team.id,
                    channel = channel,
                    query = query,
                    page = state.page + 1,
                )
            }.onSuccess { next ->
                if (generation != messageSearchGeneration) return@onSuccess
                val existingIds = state.results.mapTo(mutableSetOf(), MessageSearchResult::postId)
                val newResults = next.results.filterNot { it.postId in existingIds }
                val combined = state.results + newResults
                val canLoadMore = next.canLoadMore && newResults.isNotEmpty()
                val cached = CachedMessageSearch(
                    results = combined,
                    page = next.page,
                    canLoadMore = canLoadMore,
                )
                messageSearchCache[
                    MessageSearchCacheKey(channel.id, query.lowercase(Locale.ROOT))
                ] = cached
                _messageSearch.value = state.copy(
                    results = combined,
                    searching = false,
                    showingLocalResults = false,
                    page = next.page,
                    canLoadMore = canLoadMore,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (generation != messageSearchGeneration) return@onFailure
                _messageSearch.value = state.copy(
                    searching = false,
                    error = searchMessage(error),
                )
            }
        }
    }

    fun loadOlder() {
        if (loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            try {
                repository.loadOlder()
            } catch (_: Exception) {
                // A later scroll can retry transient history failures.
            } finally {
                loadingOlder = false
            }
        }
    }

    fun loadNewer() {
        if (loadingNewer) return
        loadingNewer = true
        viewModelScope.launch {
            try {
                repository.loadNewer()
            } catch (_: Exception) {
                // A later scroll can retry transient history failures.
            } finally {
                loadingNewer = false
            }
        }
    }

    fun refreshLatest() {
        viewModelScope.launch { runCatching { repository.refreshLatest() } }
    }

    fun setConversationAtLatest(value: Boolean) {
        repository.setActiveConversationAtLatest(value)
    }

    fun addAttachments(selections: List<AttachmentSelection>): Boolean {
        if (
            selections.isEmpty() ||
            screen.value.readyConversation() == null ||
            _messageEdit.value != null
        ) {
            selections.forEach(container.attachmentDataSource::discard)
            return false
        }
        if (!_composer.value.capabilities.enabled) {
            selections.forEach(container.attachmentDataSource::discard)
            _composerMessages.tryEmit("File uploads are disabled on this Mattermost server.")
            return false
        }
        val available = MAX_ATTACHMENTS -
            _composer.value.attachments.size -
            _composer.value.resolvingCount
        if (available <= 0) {
            selections.forEach(container.attachmentDataSource::discard)
            _composerMessages.tryEmit("A message can contain up to $MAX_ATTACHMENTS attachments.")
            return false
        }
        if (selections.size > available) {
            selections.drop(available).forEach(container.attachmentDataSource::discard)
            _composerMessages.tryEmit("Only the first $available selected files were added.")
        }
        val accepted = selections.take(available)
        _composer.update { it.copy(resolvingCount = it.resolvingCount + accepted.size) }
        val generation = attachmentGeneration
        viewModelScope.launch {
            accepted.forEach { selection ->
                try {
                    inspectAndUpload(selection, generation)
                } finally {
                    if (generation == attachmentGeneration) {
                        _composer.update {
                            it.copy(resolvingCount = (it.resolvingCount - 1).coerceAtLeast(0))
                        }
                    }
                }
            }
        }
        return true
    }

    fun enqueueIncomingShare(share: IncomingShare) {
        _incomingShares.update { queued -> queued + share }
    }

    fun selectIncomingShareDestination(id: String, destination: ConversationSummary) {
        if (_incomingShares.value.firstOrNull()?.id != id) return
        if (overviewConversations.none { it.id == destination.id }) return
        _incomingShares.update { queued ->
            queued.map { share ->
                if (share.id == id) share.copy(destinationChannelId = destination.id) else share
            }
        }
        if (_screen.value.readyConversation()?.id != destination.id) {
            openConversation(destination)
        }
    }

    fun consumeIncomingShare(id: String): Boolean {
        if (_incomingShares.value.none { it.id == id }) return false
        _incomingShares.update { queued -> queued.filterNot { it.id == id } }
        return true
    }

    fun discardAttachments() {
        clearAttachments()
    }

    fun removeAttachment(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        val removed = _composer.value.attachments.firstOrNull { it.localId == localId } ?: return
        _composer.update { state ->
            state.copy(attachments = state.attachments.filterNot { it.localId == localId })
        }
        container.attachmentDataSource.release(removed.source)
    }

    fun retryAttachment(localId: String) {
        val attachment = _composer.value.attachments.firstOrNull { it.localId == localId } ?: return
        if (attachment.status !is AttachmentStatus.Failed) return
        updateAttachment(localId) { it.copy(status = AttachmentStatus.Queued) }
        uploadAttachment(localId)
    }

    fun send(message: String): Boolean {
        val composer = _composer.value
        if (
            screen.value.readyConversation() == null ||
            composer.hasBlockingAttachments ||
            (message.isBlank() && composer.readyFiles.isEmpty())
        ) {
            return false
        }
        val files = composer.readyFiles
        composer.attachments.forEach { container.attachmentDataSource.release(it.source) }
        _composer.value = composer.copy(attachments = emptyList())
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { repository.send(message, files) }
        return true
    }

    fun beginEdit(item: FeedItem): Boolean {
        val postId = item.serverId ?: return false
        if (
            item.deliveryState != DeliveryState.SENT ||
            item.userId != currentUserId ||
            _messageEdit.value?.submitting == true ||
            _composer.value.attachments.isNotEmpty() ||
            _composer.value.resolvingCount > 0
        ) {
            return false
        }
        if (_messageEdit.value?.postId != postId) {
            _messageEdit.value = MessageEditState(
                postId = postId,
                originalMessage = item.message,
                draftMessage = item.message,
            )
        }
        return true
    }

    fun cancelEdit() {
        if (_messageEdit.value?.submitting != true) _messageEdit.value = null
    }

    fun updateEditDraft(message: String) {
        val edit = _messageEdit.value ?: return
        if (!edit.submitting && edit.draftMessage != message) {
            _messageEdit.value = edit.copy(draftMessage = message, failed = false)
        }
    }

    fun submitEdit(message: String): Boolean {
        val edit = _messageEdit.value ?: return false
        if (message.isBlank() || edit.submitting) return false
        if (message == edit.originalMessage) {
            _messageEdit.value = null
            return true
        }

        _messageEdit.value = edit.copy(submitting = true, failed = false)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val succeeded = repository.edit(edit.postId, message)
            val current = _messageEdit.value
            if (current?.postId != edit.postId) return@launch
            _messageEdit.value = if (succeeded) {
                null
            } else {
                current.copy(submitting = false, failed = true)
            }
        }
        return true
    }

    fun retry(pendingId: String) {
        viewModelScope.launch { repository.retry(pendingId) }
    }

    override fun onCleared() {
        clearAttachments()
        repository.close()
    }

    private fun uploadAttachment(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        val job = viewModelScope.launch {
            try {
                uploadSemaphore.withPermit {
                    val source = _composer.value.attachments
                        .firstOrNull { it.localId == localId }
                        ?.source
                        ?: return@withPermit
                    updateAttachment(localId) {
                        it.copy(status = AttachmentStatus.Uploading(0L, source.sizeBytes))
                    }
                    var lastProgressAt = 0L
                    var lastProgressPercent = -1
                    val body = container.attachmentDataSource.requestBody(source)
                    val file = repository.uploadFile(
                        fileName = source.name,
                        clientId = localId,
                        body = body,
                        onProgress = { written, total ->
                            val now = System.nanoTime()
                            val requestComplete = total != null && written >= total
                            val percent = total
                                ?.takeIf { it > 0L }
                                ?.let { (written.toDouble() * 100.0 / it).toInt().coerceIn(0, 99) }
                            val shouldReport = written == 0L ||
                                now - lastProgressAt >= PROGRESS_INTERVAL_NANOS ||
                                (percent != null && percent > lastProgressPercent)
                            if (!requestComplete && shouldReport) {
                                lastProgressAt = now
                                if (percent != null) lastProgressPercent = percent
                                updateAttachment(localId) {
                                    it.copy(status = AttachmentStatus.Uploading(written, total))
                                }
                            }
                        },
                        onBodyComplete = {
                            updateAttachment(localId) {
                                it.copy(status = AttachmentStatus.Finalizing)
                            }
                        },
                    )
                    updateAttachment(localId) { it.copy(status = AttachmentStatus.Ready(file)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                updateAttachment(localId) {
                    it.copy(status = AttachmentStatus.Failed(attachmentMessage(error)))
                }
            } finally {
                uploadJobs.remove(localId, coroutineContext[Job])
            }
        }
        uploadJobs[localId] = job
    }

    private fun updateAttachment(localId: String, transform: (DraftAttachment) -> DraftAttachment) {
        _composer.update { state ->
            state.copy(
                attachments = state.attachments.map {
                    if (it.localId == localId) transform(it) else it
                },
            )
        }
    }

    private suspend fun inspectAndUpload(selection: AttachmentSelection, generation: Int) {
        val source = try {
            container.attachmentDataSource.inspect(selection)
        } catch (error: Exception) {
            container.attachmentDataSource.discard(selection)
            _composerMessages.emit(error.message ?: "The selected file could not be opened.")
            return
        }
        if (generation != attachmentGeneration || _screen.value.readyConversation() == null) {
            container.attachmentDataSource.release(source)
            return
        }
        if (_composer.value.attachments.any { it.source.uri == source.uri }) {
            if (source.temporary) container.attachmentDataSource.release(source)
            _composerMessages.emit("${source.name} is already attached.")
            return
        }
        val limit = _composer.value.capabilities.maxFileSizeBytes
        if (limit != null && source.sizeBytes != null && source.sizeBytes > limit) {
            container.attachmentDataSource.release(source)
            _composerMessages.emit(
                "${source.name} is too large. This server allows up to ${formatBytes(limit)}.",
            )
            return
        }
        val attachment = DraftAttachment(source = source)
        _composer.update { it.copy(attachments = it.attachments + attachment) }
        uploadAttachment(attachment.localId)
    }

    private fun clearAttachments() {
        attachmentGeneration++
        uploadJobs.values.forEach(Job::cancel)
        uploadJobs.clear()
        _composer.value.attachments.forEach { container.attachmentDataSource.release(it.source) }
        _composer.update { it.copy(attachments = emptyList(), resolvingCount = 0) }
    }

    private fun clearConversationBoundComposerState(preserveNotification: Boolean = false) {
        if (!preserveNotification) clearNotification()
        closeMessageSearch()
        clearAttachments()
        _messageEdit.value = null
    }

    private fun showInitializedDirectory(
        directory: InitializedDirectory,
        selectInitialScreen: Boolean,
        restoreRememberedConversation: Boolean,
    ) {
        currentUserId = directory.user.id
        authenticatedConfig = directory.config
        authenticatedUser = directory.user
        authenticatedTeam = directory.team
        searchUsersById[directory.user.id] = directory.user
        container.imageLoader.configure(directory.config)
        overviewConversations.clear()
        overviewConversations.addAll(directory.conversations)
        _incomingShareDestinations.value = overviewConversations.toList()
        val overview = ScreenState.Overview(
            teamId = directory.team.id,
            teamTitle = directory.team.displayName.ifBlank { directory.team.name },
            conversations = overviewConversations,
        )
        overviewScreen = overview
        if (tryOpenNotification()) return
        if (_notificationTarget.value != null) {
            if (selectInitialScreen) _screen.value = overview
            return
        }
        if (!selectInitialScreen) return
        val remembered = if (restoreRememberedConversation) {
            val rememberedChannelId = container.configStore.loadRememberedConversation()
                ?.channelIdFor(
                    baseUrl = directory.config.baseUrl,
                    teamId = directory.team.id,
                    userId = directory.user.id,
                )
            directory.conversations.firstOrNull { it.id == rememberedChannelId }
        } else {
            null
        }
        if (remembered == null) {
            _screen.value = overview
        } else {
            openConversation(remembered)
        }
    }

    private fun rememberSelectedConversation(channelId: String) {
        if (channelId.isBlank()) return
        val config = authenticatedConfig ?: return
        val team = authenticatedTeam ?: return
        val user = authenticatedUser ?: return
        container.configStore.rememberConversation(
            RememberedConversation(
                baseUrl = config.baseUrl,
                teamId = team.id,
                userId = user.id,
                channelId = channelId,
            ),
        )
    }

    private fun synchronizeSelectedBackgroundSession() {
        if (!container.configStore.backgroundPollingSettings().enabled) return
        val config = authenticatedConfig ?: return
        val channel = configuredChannel ?: return
        val user = authenticatedUser ?: return
        viewModelScope.launch { synchronizeBackgroundSession(config, channel, user) }
    }

    private suspend fun synchronizeBackgroundSession(
        config: AuthenticatedServerConfig,
        channel: MattermostChannel,
        user: MattermostUser,
    ) {
        val settingsGeneration = backgroundSettingsGeneration
        val accountGeneration = connectionGeneration
        val newest = repository.snapshot()
            .asSequence()
            .filter { it.channelId == channel.id && it.serverId != null }
            .maxWithOrNull(compareBy(FeedItem::createAt, FeedItem::serverId))
        if (settingsGeneration != backgroundSettingsGeneration || accountGeneration != connectionGeneration ||
            authenticatedConfig != config || configuredChannel?.id != channel.id
        ) return
        container.configStore.saveBackgroundPollingSession(
            BackgroundPollingSession(
                config = config.copy(channelName = channel.displayName.ifBlank { channel.name }),
                channelId = channel.id,
                currentUserId = user.id,
                currentUsername = user.username,
                checkpointPostId = newest?.serverId,
                checkpointCreateAt = newest?.createAt ?: 0L,
            ),
        )
        BackgroundPollingWorker.cancelNotification(container.context, channel.id)
        newest?.serverId?.let {
            container.configStore.advanceBackgroundCheckpoint(
                postId = it,
                createAt = newest.createAt,
                foregroundSync = true,
            )
        }
    }

    private suspend fun searchServerPage(
        config: AuthenticatedServerConfig,
        teamId: String,
        channel: MattermostChannel,
        query: String,
        page: Int,
    ): CachedMessageSearch {
        val response = container.api.searchPosts(
            config = config,
            teamId = teamId,
            terms = MessageSearch.scopedTerms(query, channel),
            page = page,
            perPage = SEARCH_PAGE_SIZE,
        )
        val posts = response.orderedPosts.filter { it.channelId == channel.id }
        val missingUserIds = posts.asSequence()
            .map { it.userId }
            .filter(String::isNotBlank)
            .filterNot(searchUsersById::containsKey)
            .distinct()
            .toList()
        if (missingUserIds.isNotEmpty()) {
            runCatching { container.api.users(config, missingUserIds) }
                .getOrDefault(emptyList())
                .forEach { user ->
                    searchUsersById[user.id] = user
                }
        }
        val authorNames = posts.associate { post ->
            post.userId to searchUsersById[post.userId]?.displayName
        }
        val fallbackTerms = MessageSearch.highlightTerms(query)
        val results = withContext(Dispatchers.Default) {
            posts.map { post ->
                MessageSearchResult(
                    postId = post.id,
                    channelId = post.channelId,
                    rootId = post.rootId,
                    userId = post.userId,
                    author = authorNames[post.userId]
                        ?: "Unknown user",
                    message = post.message,
                    createAt = post.createAt,
                    fileCount = post.fileIds.size,
                    matchTerms = MessageSearch.highlightTerms(
                        query,
                        response.matches[post.id].orEmpty(),
                    ).ifEmpty { fallbackTerms },
                )
            }
        }
        return CachedMessageSearch(
            results = results,
            page = page,
            canLoadMore = response.order.size >= SEARCH_PAGE_SIZE,
        )
    }

    private fun finishSearchWithUnavailableState() {
        _messageSearch.value = _messageSearch.value.copy(
            searching = false,
            error = "Search is unavailable until the conversation reconnects.",
        )
    }

    private fun invalidateMessageSearchCache(channelId: String) {
        messageSearchCache.keys.removeAll { it.channelId == channelId }
    }

    private fun searchMessage(error: Throwable): String = when (error) {
        is MattermostException -> when (error.statusCode) {
            400 -> "The server could not understand these search terms."
            401 -> "The session expired. Reconnect before searching."
            403 -> "This account is not allowed to search this conversation."
            429 -> "Search is temporarily rate-limited. Try again shortly."
            else -> error.message
        }
        is IOException -> "Couldn’t search the server. Check the connection and try again."
        else -> error.message ?: "Search failed. Try again."
    }

    private fun failMessageJump(postId: String, message: String) {
        _messageSearch.update { state ->
            if (!state.active || state.openingPostId != postId) state else state.copy(
                openingPostId = null,
                error = message,
            )
        }
    }

    private fun messageJumpMessage(error: Throwable): String = when (error) {
        is MattermostException -> when (error.statusCode) {
            401 -> "The session expired. Reconnect before opening this message."
            403 -> "This account can no longer access that message."
            404 -> "That message is no longer available."
            429 -> "Message loading is temporarily rate-limited. Try again shortly."
            else -> error.message
        }
        is IOException -> "Couldn’t load that message. Check the connection and try again."
        else -> error.message ?: "Couldn’t load that message. Try again."
    }

    private fun attachmentMessage(error: Throwable): String = when (error) {
        is MattermostException -> when (error.statusCode) {
            403 -> "This account is not allowed to upload files."
            413 -> "The server rejected this file because it is too large."
            501 -> "File storage is unavailable on this server."
            else -> error.message
        }
        is FileNotFoundException -> "The selected file is no longer available."
        is IOException -> "Upload failed. Check the connection and try again."
        else -> error.message ?: "Upload failed. Try again."
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is IllegalArgumentException -> error.message ?: "Invalid connection settings"
        is TeamNotFoundException -> error.message ?: "The team could not be found."
        is ChannelNotFoundException -> error.message ?: "The channel could not be found."
        is MattermostException -> when (error.statusCode) {
            401 -> "The username or password is incorrect."
            403 -> "This account cannot access or post to the channel."
            404 -> "The requested Mattermost resource could not be found."
            429 -> "The server rate limit was reached. Try again shortly."
            else -> error.message
        }
        is IOException -> "The server could not be reached. Check the URL, TLS certificate, and network."
        else -> error.message ?: "Connection failed"
    }

    class Factory(
        private val container: AppContainer,
        private val unsupportedMessage: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(container, unsupportedMessage) as T
    }

    private companion object {
        const val MAX_ATTACHMENTS = 10
        const val MAX_CONCURRENT_UPLOADS = 2
        const val PROGRESS_INTERVAL_NANOS = 100_000_000L
        const val SEARCH_DEBOUNCE_MS = 250L
        const val SEARCH_PAGE_SIZE = 50
        const val MAX_LOCAL_SEARCH_RESULTS = 40
        const val MAX_CACHED_SEARCHES = 12

        fun formatBytes(bytes: Long): String {
            val mib = bytes / (1024.0 * 1024.0)
            return if (mib >= 10.0) "${mib.toInt()} MB" else "%.1f MB".format(mib)
        }
    }
}

private data class MessageSearchCacheKey(
    val channelId: String,
    val query: String,
)

private data class CachedMessageSearch(
    val results: List<MessageSearchResult>,
    val page: Int,
    val canLoadMore: Boolean,
)

private fun ScreenState.readyConversation(): ScreenState.Conversation? =
    (this as? ScreenState.Conversation)?.takeUnless { it.loading }

private fun FeedMutation.itemsForCheckpoint(): List<FeedItem> = when (this) {
    is FeedMutation.Replace -> items
    is FeedMutation.Prepend -> items
    is FeedMutation.PageInsert -> items
    is FeedMutation.PageWindow -> items
    is FeedMutation.Insert -> listOf(item)
    is FeedMutation.Update -> listOf(item)
    is FeedMutation.Remove -> emptyList()
}
