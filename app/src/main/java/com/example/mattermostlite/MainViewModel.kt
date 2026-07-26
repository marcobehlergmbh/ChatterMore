package com.example.mattermostlite

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mattermostlite.background.BackgroundNotificationMode
import com.example.mattermostlite.background.BackgroundPollingScheduler
import com.example.mattermostlite.background.BackgroundPollingSession
import com.example.mattermostlite.background.BackgroundPollingSettings
import com.example.mattermostlite.background.BackgroundPollingWorker
import com.example.mattermostlite.data.ChannelRepository
import com.example.mattermostlite.data.AttachmentSelection
import com.example.mattermostlite.data.AttachmentStatus
import com.example.mattermostlite.data.DraftAttachment
import com.example.mattermostlite.data.IncomingShare
import com.example.mattermostlite.model.ConnectionState
import com.example.mattermostlite.model.AuthenticatedServerConfig
import com.example.mattermostlite.model.ChannelNotFoundException
import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedItem
import com.example.mattermostlite.model.LinkPreview
import com.example.mattermostlite.model.FeedMutation
import com.example.mattermostlite.model.MattermostException
import com.example.mattermostlite.model.MattermostFile
import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.model.MattermostUser
import com.example.mattermostlite.model.ConversationMutation
import com.example.mattermostlite.model.ConversationSummary
import com.example.mattermostlite.model.InitializedDirectory
import com.example.mattermostlite.model.MattermostTeam
import com.example.mattermostlite.model.RememberedConversation
import com.example.mattermostlite.model.ServerConfig
import com.example.mattermostlite.model.TeamNotFoundException
import com.example.mattermostlite.model.UploadCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileNotFoundException
import java.io.IOException

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
    private val _messageEdit = MutableStateFlow<MessageEditState?>(null)
    private val _composer = MutableStateFlow(ComposerState())
    private val _incomingShares = MutableStateFlow<List<IncomingShare>>(emptyList())
    private val _composerMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val uploadJobs = mutableMapOf<String, Job>()
    private val uploadSemaphore = Semaphore(MAX_CONCURRENT_UPLOADS)
    private var attachmentGeneration = 0
    private var benchmarkItems: List<FeedItem>? = null
    private var currentUserId: String? = null
    private var authenticatedConfig: AuthenticatedServerConfig? = null
    private var configuredChannel: MattermostChannel? = null
    private var authenticatedUser: MattermostUser? = null
    private var authenticatedTeam: MattermostTeam? = null
    private var loadingOlder = false
    private var conversationRequestGeneration = 0
    private var backgroundSettingsGeneration = 0
    private var lastReadyConversationScreen: ScreenState.Conversation? = null
    private var overviewScreen: ScreenState.Overview? = null
    private val overviewConversations = mutableListOf<ConversationSummary>()

    val screen: StateFlow<ScreenState> = _screen.asStateFlow()
    val messageEdit: StateFlow<MessageEditState?> = _messageEdit.asStateFlow()
    val composer: StateFlow<ComposerState> = _composer.asStateFlow()
    val incomingShares: StateFlow<List<IncomingShare>> = _incomingShares.asStateFlow()
    val composerMessages: SharedFlow<String> = _composerMessages.asSharedFlow()
    val mutations: SharedFlow<FeedMutation> = repository.mutations
    val conversationMutations: SharedFlow<ConversationMutation> = repository.conversationMutations
    val connectionState: StateFlow<ConnectionState> = repository.connectionState
    val refreshing: StateFlow<Boolean> = repository.refreshing
    val userPresence = repository.userPresence
    val mentionContext = repository.mentionContext
    val channelUsers = repository.channelUsers

    init {
        viewModelScope.launch {
            repository.uploadCapabilities.collect { capabilities ->
                _composer.update { it.copy(capabilities = capabilities) }
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
            }
        }
        viewModelScope.launch {
            repository.mutations.collect { mutation ->
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
        if (persist && container.configStore.backgroundPollingSettings().enabled) {
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
        viewModelScope.launch {
            runCatching {
                val normalized = container.api.normalizedConfig(config)
                repository.initialize(normalized) { directory ->
                    showInitializedDirectory(
                        directory = directory,
                        selectInitialScreen =
                            initializationGeneration == conversationRequestGeneration,
                        restoreRememberedConversation = false,
                    )
                }
            }
                .onSuccess { directory ->
                    val normalized = container.api.normalizedConfig(config)
                    showInitializedDirectory(
                        directory = directory,
                        selectInitialScreen =
                            initializationGeneration == conversationRequestGeneration,
                        restoreRememberedConversation = true,
                    )
                    if (persist) {
                        container.configStore.save(normalized)
                    } else {
                        container.configStore.rememberServer(normalized)
                    }
                }
                .onFailure { error ->
                    currentUserId = null
                    authenticatedConfig = null
                    configuredChannel = null
                    authenticatedUser = null
                    authenticatedTeam = null
                    overviewScreen = null
                    if (!persist) {
                        BackgroundPollingScheduler.cancel(container.context)
                        container.configStore.clearCredentials()
                    }
                    _screen.value = ScreenState.Setup(
                        config = config.copy(password = ""),
                        error = userMessage(error),
                    )
                }
        }
    }

    fun clearConnection() {
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

    fun enableBenchmarkFixture(count: Int) {
        if (!BuildConfig.BENCHMARK_FIXTURES) return
        benchmarkItems = (1..count.coerceIn(1, 2_000)).map { index ->
            FeedItem(
                key = "benchmark:$index",
                serverId = "benchmark-$index",
                pendingId = null,
                channelId = "benchmark",
                userId = "user-${index % 12}",
                author = "User ${index % 12}",
                message = if (index % 11 == 0) {
                    "A benchmark link preview at row $index https://example.com/releases/$index"
                } else if (index % 17 == 0) {
                    "@channel benchmark broadcast mention at row $index"
                } else if (index % 7 == 0) {
                    "A longer benchmark message at row $index that wraps across multiple lines to exercise text layout without Markdown or attachments."
                } else if (index % 13 == 0) {
                    "A benchmark message with render-time smileys at row $index :) <3"
                } else {
                    "Benchmark message $index"
                },
                createAt = 1_700_000_000_000L + index * 1_000L,
                updateAt = 1_700_000_000_000L + index * 1_000L,
                deliveryState = DeliveryState.SENT,
                linkPreviews = if (index % 11 == 0) {
                    listOf(
                        LinkPreview(
                            url = "https://example.com/releases/$index",
                            title = "Release notes $index",
                            description = "A compact benchmark preview without a network image.",
                            siteName = "Example",
                        ),
                    )
                } else {
                    emptyList()
                },
            )
        }
        val fixtureScreen = ScreenState.Conversation(
            id = "benchmark",
            title = "Performance fixture",
        )
        lastReadyConversationScreen = fixtureScreen
        _screen.value = fixtureScreen
    }

    fun openConversation(summary: ConversationSummary) {
        if (BuildConfig.BENCHMARK_FIXTURES || summary.id.isBlank()) return
        val requestGeneration = ++conversationRequestGeneration
        clearConversationBoundComposerState()
        _screen.value = ScreenState.Conversation(
            id = "loading:${summary.id}:$requestGeneration",
            title = summary.title,
            canNavigateBack = true,
            peerUserId = summary.peerUserId,
            loading = true,
        )
        viewModelScope.launch {
            runCatching { repository.openConversation(summary.id) }
                .onSuccess { conversation ->
                    if (requestGeneration != conversationRequestGeneration) return@onSuccess
                    if (conversation == null) {
                        overviewScreen?.let { _screen.value = it }
                        return@onSuccess
                    }
                    configuredChannel = conversation.channel
                    val screen = ScreenState.Conversation(
                        id = conversation.channelId,
                        title = conversation.title,
                        canNavigateBack = true,
                        peerUserId = (conversation as? com.example.mattermostlite.model.Conversation.DirectMessage)
                            ?.peer
                            ?.id,
                    )
                    lastReadyConversationScreen = screen
                    rememberSelectedConversation(conversation.channelId)
                    _screen.value = screen
                    synchronizeSelectedBackgroundSession()
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    if (requestGeneration != conversationRequestGeneration) return@onFailure
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
        viewModelScope.launch {
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

    private fun clearConversationBoundComposerState() {
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
        container.imageLoader.configure(directory.config)
        overviewConversations.clear()
        overviewConversations.addAll(directory.conversations)
        val overview = ScreenState.Overview(
            teamId = directory.team.id,
            teamTitle = directory.team.displayName.ifBlank { directory.team.name },
            conversations = overviewConversations,
        )
        overviewScreen = overview
        if (!selectInitialScreen) return
        _screen.value = overview
        if (!restoreRememberedConversation) return
        val rememberedChannelId = container.configStore.loadRememberedConversation()
            ?.channelIdFor(
                baseUrl = directory.config.baseUrl,
                teamId = directory.team.id,
                userId = directory.user.id,
            )
            ?: return
        directory.conversations
            .firstOrNull { it.id == rememberedChannelId }
            ?.let(::openConversation)
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
        val newest = repository.snapshot()
            .asSequence()
            .filter { it.channelId == channel.id && it.serverId != null }
            .maxWithOrNull(compareBy(FeedItem::createAt, FeedItem::serverId))
        container.configStore.saveBackgroundPollingSession(
            BackgroundPollingSession(
                config = config,
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

        fun formatBytes(bytes: Long): String {
            val mib = bytes / (1024.0 * 1024.0)
            return if (mib >= 10.0) "${mib.toInt()} MB" else "%.1f MB".format(mib)
        }
    }
}

private fun ScreenState.readyConversation(): ScreenState.Conversation? =
    (this as? ScreenState.Conversation)?.takeUnless { it.loading }

private fun FeedMutation.itemsForCheckpoint(): List<FeedItem> = when (this) {
    is FeedMutation.Replace -> items
    is FeedMutation.Prepend -> items
    is FeedMutation.Insert -> listOf(item)
    is FeedMutation.Update -> listOf(item)
    is FeedMutation.Remove -> emptyList()
}
