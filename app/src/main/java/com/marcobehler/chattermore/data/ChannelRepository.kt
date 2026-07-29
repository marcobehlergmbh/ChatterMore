package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.ConnectionState
import com.marcobehler.chattermore.model.AuthenticatedServerConfig
import com.marcobehler.chattermore.model.Conversation
import com.marcobehler.chattermore.model.ConversationKind
import com.marcobehler.chattermore.model.ConversationMutation
import com.marcobehler.chattermore.model.ConversationSummary
import com.marcobehler.chattermore.model.DeliveryState
import com.marcobehler.chattermore.model.FeedItem
import com.marcobehler.chattermore.model.FeedMutation
import com.marcobehler.chattermore.model.MattermostChannel
import com.marcobehler.chattermore.model.MattermostFile
import com.marcobehler.chattermore.model.MattermostPost
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.model.InitializedDirectory
import com.marcobehler.chattermore.model.MentionContext
import com.marcobehler.chattermore.model.ServerConfig
import com.marcobehler.chattermore.model.SocketCursor
import com.marcobehler.chattermore.model.SocketSignal
import com.marcobehler.chattermore.model.UploadCapabilities
import com.marcobehler.chattermore.model.UserPresence
import com.marcobehler.chattermore.network.MattermostApi
import com.marcobehler.chattermore.network.MattermostSocket
import com.marcobehler.chattermore.ui.MessageLinkParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.RequestBody
import kotlin.math.min
import kotlin.random.Random

class ChannelRepository(
    private val api: MattermostApi,
    private val socket: MattermostSocket,
    private val scope: CoroutineScope,
    private val conversationCache: ConversationSummaryCache,
    unsupportedMessage: String,
) {
    private val feed = FeedStore(unsupportedMessage)
    private val presenceStore = PresenceStore()
    private val feedMutex = Mutex()
    private val olderMutex = Mutex()
    private val refreshMutex = Mutex()
    private val mentionMutex = Mutex()
    private val directoryMutex = Mutex()
    private val _mutations = MutableSharedFlow<FeedMutation>(extraBufferCapacity = 256)
    private val _conversationMutations =
        MutableSharedFlow<ConversationMutation>(extraBufferCapacity = 256)
    private val _connectionState = MutableStateFlow(ConnectionState.OFFLINE)
    private val _refreshing = MutableStateFlow(false)
    private val _uploadCapabilities = MutableStateFlow(UploadCapabilities())
    private val _userPresence = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    private val _mentionContext = MutableStateFlow(MentionContext())
    private val _channelUsers = MutableStateFlow<List<MattermostUser>>(emptyList())

    val mutations: SharedFlow<FeedMutation> = _mutations.asSharedFlow()
    val conversationMutations: SharedFlow<ConversationMutation> =
        _conversationMutations.asSharedFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    val uploadCapabilities: StateFlow<UploadCapabilities> = _uploadCapabilities.asStateFlow()
    val userPresence: StateFlow<Map<String, UserPresence>> = _userPresence.asStateFlow()
    val mentionContext: StateFlow<MentionContext> = _mentionContext.asStateFlow()
    val channelUsers: StateFlow<List<MattermostUser>> = _channelUsers.asStateFlow()

    private lateinit var config: AuthenticatedServerConfig
    private lateinit var activeConversation: Conversation
    private lateinit var currentUser: MattermostUser
    private lateinit var currentTeam: com.marcobehler.chattermore.model.MattermostTeam
    private val directoryById = linkedMapOf<String, ConversationSummary>()
    private var directoryOrder = emptyList<String>()
    private var directoryCacheKey: ConversationSummaryCache.Key? = null
    private var directoryCacheJob: Job? = null
    private val recentConversationPages =
        object : LinkedHashMap<String, CachedConversationPage>(8, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CachedConversationPage>,
            ): Boolean = size > MAX_RECENT_CONVERSATIONS
        }
    private var activeConversationGeneration = 0
    private var conversationSelected = false
    private var conversationSwitching = false
    @Volatile
    private var activeConversationAtLatest = false
    private var activeReadAckJob: Job? = null
    private val conversationGeneration = AtomicInteger()
    private val postArrivedDuringConversationSwitch = AtomicBoolean()
    private var initialized = false
    private var foreground = false
    private var olderExhausted = false
    private var socketJob: Job? = null
    private var socketCursor: SocketCursor? = null
    private var socketReady = CompletableDeferred<Unit>()
    private var presenceGeneration = 0
    private val inFlightPresence = mutableSetOf<Pair<Int, String>>()
    private val mentionUsersByUsername = mutableMapOf<String, MattermostUser>()
    private val negativeMentionUsernames = mutableSetOf<String>()
    private val inFlightMentionUsernames = mutableSetOf<String>()
    private val knownUsersById = mutableMapOf<String, MattermostUser>()
    private var mentionGeneration = 0
    private var channelUsersGeneration = 0

    suspend fun initialize(
        rawConfig: ServerConfig,
        onCachedDirectory: (InitializedDirectory) -> Unit = {},
    ): InitializedDirectory {
        stopSocket()
        initialized = false
        conversationGeneration.incrementAndGet()
        postArrivedDuringConversationSwitch.set(false)
        conversationSelected = false
        conversationSwitching = false
        activeConversationAtLatest = false
        presenceGeneration++
        socketCursor = null
        olderExhausted = false
        presenceStore.clear()
        _userPresence.value = emptyMap()
        _channelUsers.value = emptyList()
        channelUsersGeneration++
        _uploadCapabilities.value = UploadCapabilities()
        clearMentionContext()
        val (authenticatedConfig, authenticatedUser) = api.login(rawConfig)
        currentUser = authenticatedUser
        currentTeam = api.resolveTeam(authenticatedConfig)
        config = authenticatedConfig.copy(teamName = currentTeam.name)
        resetMentionContext(authenticatedUser)
        _channelUsers.value = listOf(authenticatedUser)
        directoryCacheKey = ConversationSummaryCache.Key(
            baseUrl = config.baseUrl,
            userId = currentUser.id,
            teamId = currentTeam.id,
        )
        val cached = directoryCacheKey
            ?.let { conversationCache.load(it) }
            .orEmpty()
        if (cached.isNotEmpty()) {
            replaceDirectory(cached, emit = false)
        }
        // The cache callback may synchronously start the remembered conversation.
        initialized = true
        if (foreground) startSocket()
        if (cached.isNotEmpty()) {
            onCachedDirectory(
                InitializedDirectory(currentTeam, currentUser, config, cached),
            )
        }

        val directory = runCatching {
            coroutineScope {
                val channelsRequest = async {
                    api.channelsForUser(config, currentUser.id, currentTeam.id)
                }
                val membershipsRequest = async {
                    api.channelMemberships(config, currentUser.id, currentTeam.id)
                }
                val capabilitiesRequest = async {
                    runCatching { api.uploadCapabilities(config) }
                        .getOrDefault(UploadCapabilities())
                }
                val channels = channelsRequest.await()
                val peerIds = directMessagePeerIds(channels, currentUser.id)
                val peers = if (peerIds.isEmpty()) emptyList() else api.users(config, peerIds)
                addMentionUsers(peers)
                _uploadCapabilities.value = capabilitiesRequest.await()
                buildConversationDirectory(
                    channels = channels,
                    memberships = membershipsRequest.await(),
                    currentUserId = currentUser.id,
                    users = peers,
                )
            }
        }.getOrElse { error ->
            if (cached.isNotEmpty()) {
                return InitializedDirectory(currentTeam, currentUser, config, cached)
            }
            throw error
        }
        replaceDirectory(directory, emit = cached.isNotEmpty())
        return InitializedDirectory(currentTeam, currentUser, config, directory)
    }

    suspend fun openConversation(channelId: String): Conversation? {
        if (!initialized || channelId.isBlank()) return null
        activeToken()?.conversation
            ?.takeIf { it.channelId == channelId }
            ?.let { return it }
        val summary = directoryMutex.withLock { directoryById[channelId] } ?: return null
        val generation = conversationGeneration.incrementAndGet()
        return switchConversation(generation) {
            when (summary.kind) {
                ConversationKind.CHANNEL -> Conversation.Channel(summary.channel)
                ConversationKind.DIRECT_MESSAGE -> {
                    val userId = summary.peerUserId
                    if (userId == null) {
                        Conversation.Channel(summary.channel)
                    } else {
                        val peer = knownUser(userId)
                            ?: summary.cachedPeer()
                            ?: api.users(config, listOf(userId)).singleOrNull()
                            ?: throw IllegalArgumentException("The selected user could not be found.")
                        addMentionUsers(listOf(peer))
                        Conversation.DirectMessage(summary.channel, peer)
                    }
                }
            }
        }
    }

    suspend fun openDirectMessage(userId: String): Conversation.DirectMessage? {
        if (!initialized || userId.isBlank()) return null
        activeToken()?.conversation
            ?.let { it as? Conversation.DirectMessage }
            ?.takeIf { it.peer.id == userId }
            ?.let { return it }

        val generation = conversationGeneration.incrementAndGet()
        return switchConversation(generation) {
            val peer = knownUser(userId)
                ?: api.users(config, listOf(userId)).singleOrNull()
                ?: throw IllegalArgumentException("The selected user could not be found.")
            addMentionUsers(listOf(peer))
            val directChannel = directoryMutex.withLock {
                directoryById.values.firstOrNull {
                    it.kind == ConversationKind.DIRECT_MESSAGE && it.peerUserId == peer.id
                }?.channel
            } ?: api.directChannel(config, currentUser.id, peer.id)
            upsertDirectConversation(directChannel, peer)
            Conversation.DirectMessage(directChannel, peer)
        } as? Conversation.DirectMessage
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value && initialized) startSocket() else if (!value) stopSocket()
    }

    fun leaveConversation() {
        conversationGeneration.incrementAndGet()
        activeConversationGeneration = 0
        conversationSelected = false
        conversationSwitching = false
        activeConversationAtLatest = false
        activeReadAckJob?.cancel()
        activeReadAckJob = null
        postArrivedDuringConversationSwitch.set(false)
        channelUsersGeneration++
        _channelUsers.value = if (::currentUser.isInitialized) listOf(currentUser) else emptyList()
    }

    suspend fun snapshot(): List<FeedItem> = feedMutex.withLock { feed.snapshot() }

    fun setActiveConversationAtLatest(value: Boolean) {
        val changed = activeConversationAtLatest != value
        activeConversationAtLatest = value
        if (!changed || !value || !conversationSelected) return
        scheduleActiveReadAcknowledgement()
    }

    suspend fun loadOlder() {
        if (olderExhausted || !olderMutex.tryLock()) return
        try {
            val token = activeToken() ?: return
            val visibleCursor = feedMutex.withLock { feed.oldestServerId() } ?: return
            var requestCursor = visibleCursor
            repeat(MAX_HISTORY_SCAN_PAGES) {
                val page = api.posts(config, token.conversation.channelId, before = requestCursor)
                if (!isActive(token)) return
                val posts = page.orderedPosts
                if (posts.isEmpty()) {
                    if (isActive(token)) olderExhausted = true
                    return
                }
                val profiles = fetchProfiles(posts)
                val result = feedMutex.withLock {
                    if (!isActiveLocked(token)) return@withLock null
                    feed.mergeOlder(posts, profiles) to feed.oldestServerId()
                } ?: return
                val (mutations, newVisibleCursor) = result
                if (newVisibleCursor != visibleCursor) {
                    emitAll(mutations)
                    scheduleMentionResolution(posts)
                    ensurePresence(posts.map(MattermostPost::userId))
                    return
                }

                // A page may contain only thread replies, which this single-channel feed hides.
                val nextRequestCursor = posts.minWithOrNull(POST_ORDER)?.id
                if (nextRequestCursor.isNullOrBlank() || nextRequestCursor == requestCursor) {
                    if (isActive(token)) olderExhausted = true
                    return
                }
                requestCursor = nextRequestCursor
            }
        } finally {
            olderMutex.unlock()
        }
    }

    suspend fun refreshLatest() {
        if (!initialized || !refreshMutex.tryLock()) return
        _refreshing.value = true
        try {
            val token = activeToken() ?: return
            var cursor = feedMutex.withLock { feed.newestServerId() }
            val seenCursors = mutableSetOf<String>()
            var pages = 0
            if (cursor != null) {
                while (pages++ < MAX_REFRESH_PAGES) {
                    val currentCursor = cursor ?: break
                    if (!seenCursors.add(currentCursor)) break
                    val page = api.posts(
                        config,
                        token.conversation.channelId,
                        after = currentCursor,
                    )
                    if (!isActive(token)) return
                    val posts = page.orderedPosts
                    val profiles = fetchProfiles(posts)
                    val mutations = feedMutex.withLock {
                        if (!isActiveLocked(token)) return@withLock null
                        feed.merge(posts, profiles)
                    } ?: return
                    emitAll(mutations)
                    scheduleMentionResolution(posts)
                    ensurePresence(posts.map(MattermostPost::userId))
                    val next = posts.maxWithOrNull(POST_ORDER)?.id
                    if (posts.size < PAGE_SIZE || next.isNullOrBlank() || next == currentCursor) break
                    cursor = next
                }
            }

            // Overlap the head page so a stale or deleted cursor cannot leave a gap.
            val head = api.posts(config, token.conversation.channelId)
            if (!isActive(token)) return
            val profiles = fetchProfiles(head.orderedPosts)
            val mutations = feedMutex.withLock {
                if (!isActiveLocked(token)) return@withLock null
                feed.merge(head.orderedPosts, profiles)
            } ?: return
            emitAll(mutations)
            scheduleMentionResolution(head.orderedPosts)
            ensurePresence(head.orderedPosts.map(MattermostPost::userId))
        } finally {
            _refreshing.value = false
            refreshMutex.unlock()
        }
    }

    suspend fun uploadFile(
        fileName: String,
        clientId: String,
        body: RequestBody,
        onProgress: (bytesWritten: Long, totalBytes: Long?) -> Unit,
        onBodyComplete: () -> Unit,
    ): MattermostFile {
        val token = activeToken() ?: throw CancellationException("No active conversation")
        return api.uploadFile(
            config,
            token.conversation.channelId,
            fileName,
            clientId,
            body,
            onProgress,
            onBodyComplete,
        )
    }

    suspend fun send(message: String, uploadedFiles: List<MattermostFile> = emptyList()): Boolean {
        if (message.isBlank() && uploadedFiles.isEmpty()) return false
        val token = activeToken() ?: return false
        val pendingId = UUID.randomUUID().toString()
        val mutation = feedMutex.withLock {
            if (!isActiveLocked(token)) return false
            feed.addPending(
                pendingId,
                token.conversation.channelId,
                currentUser,
                message,
                System.currentTimeMillis(),
                uploadedFiles,
            )
        }
        _mutations.emit(mutation)
        scheduleUnconfirmed(token, pendingId)
        return submitPending(token, pendingId, message, uploadedFiles.map(MattermostFile::id))
    }

    suspend fun edit(postId: String, message: String): Boolean {
        if (message.isBlank()) return false
        val token = activeToken() ?: return false
        val canEdit = feedMutex.withLock {
            if (!isActiveLocked(token)) return@withLock false
            feed.post(postId)?.let { item ->
                item.userId == currentUser.id && item.deliveryState == DeliveryState.SENT
            } == true
        }
        if (!canEdit) return false

        return try {
            val post = api.patchPost(config, postId, message)
            val mutations = feedMutex.withLock {
                if (!isActiveLocked(token)) return@withLock null
                feed.merge(listOf(post), listOf(currentUser))
            } ?: return true
            invalidateRecentPage(token.conversation.channelId)
            emitAll(mutations)
            scheduleMentionResolution(listOf(post))
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun retry(pendingId: String) {
        val token = activeToken() ?: return
        val pending = feedMutex.withLock {
            if (!isActiveLocked(token)) return
            val item = feed.pending(pendingId) ?: return
            val mutation = feed.setPendingState(pendingId, DeliveryState.OPTIMISTIC) ?: return
            Triple(mutation, item.message, item.fileIds)
        }
        _mutations.emit(pending.first)
        scheduleUnconfirmed(token, pendingId)
        submitPending(token, pendingId, pending.second, pending.third)
    }

    fun close() {
        stopSocket()
        directoryCacheJob?.cancel()
        directoryCacheJob = null
        activeReadAckJob?.cancel()
        activeReadAckJob = null
        initialized = false
        conversationSelected = false
        conversationSwitching = false
        conversationGeneration.incrementAndGet()
        postArrivedDuringConversationSwitch.set(false)
        presenceGeneration++
        channelUsersGeneration++
        presenceStore.clear()
        _userPresence.value = emptyMap()
        _channelUsers.value = emptyList()
        directoryById.clear()
        directoryOrder = emptyList()
        directoryCacheKey = null
        synchronized(recentConversationPages) { recentConversationPages.clear() }
        scope.launch { clearMentionContext() }
    }

    private suspend fun submitPending(
        token: ActiveToken,
        pendingId: String,
        message: String,
        fileIds: List<String>,
    ): Boolean = try {
        val post = api.createPost(
            config,
            token.conversation.channelId,
            message,
            pendingId,
            fileIds,
        )
        val mutations = feedMutex.withLock {
            if (!isActiveLocked(token)) return@withLock null
            feed.merge(listOf(post), listOf(currentUser))
        }
        if (mutations != null) {
            invalidateRecentPage(token.conversation.channelId)
            emitAll(mutations)
            scheduleMentionResolution(listOf(post))
        }
        true
    } catch (error: Exception) {
        feedMutex.withLock {
            if (!isActiveLocked(token)) return@withLock null
            feed.setPendingState(pendingId, DeliveryState.FAILED)
        }
            ?.let { _mutations.emit(it) }
        false
    }

    private fun scheduleUnconfirmed(token: ActiveToken, pendingId: String) {
        scope.launch {
            delay(UNCONFIRMED_GRACE_MS)
            feedMutex.withLock {
                if (!isActiveLocked(token)) return@withLock null
                feed.markUnconfirmed(pendingId)
            }
                ?.let { _mutations.emit(it) }
        }
    }

    private suspend fun switchConversation(
        generation: Int,
        resolve: suspend () -> Conversation,
    ): Conversation? {
        channelUsersGeneration++
        val usersGeneration = channelUsersGeneration
        conversationSwitching = true
        return try {
            val previousChannelId = feedMutex.withLock {
                if (::activeConversation.isInitialized) activeConversation.channelId else null
            }
            val target = resolve()
            if (generation != conversationGeneration.get()) return null
            val cached = recentPage(target)
            val posts: List<MattermostPost>
            val profiles: List<MattermostUser>
            if (cached == null) {
                val page = api.posts(config, target.channelId)
                if (generation != conversationGeneration.get()) return null
                posts = page.orderedPosts
                // Author hydration must not add another network round trip before first content.
                profiles = cachedProfiles(posts)
                cacheRecentPage(target.channelId, posts, profiles)
            } else {
                posts = cached.posts
                profiles = cached.profiles
            }
            if (generation != conversationGeneration.get()) return null

            feedMutex.withLock {
                if (generation != conversationGeneration.get()) return null
                olderExhausted = false
                activeConversation = target
                activeConversationGeneration = generation
                conversationSelected = true
                conversationSwitching = false
                activeConversationAtLatest = true
                val mutation = feed.replace(posts, profiles + currentUser).copy(
                    conversationId = target.channelId,
                )
                _mutations.emit(mutation)
            }
            scheduleMentionResolution(posts)
            ensurePresence(posts.map(MattermostPost::userId) + currentUser.id)
            if (cached == null) {
                scope.launch {
                    hydratePostProfiles(
                        generation = generation,
                        channelId = target.channelId,
                        posts = posts,
                    )
                }
            } else {
                scope.launch { refreshHeadSafely() }
            }

            when (target) {
                is Conversation.Channel -> scope.launch {
                    loadChannelUsers(
                        usersGeneration,
                        config,
                        target.channelId,
                        currentUser,
                    )
                }
                is Conversation.DirectMessage -> {
                    addMentionUsers(listOf(currentUser, target.peer))
                    if (generation == conversationGeneration.get()) {
                        _channelUsers.value = listOf(currentUser, target.peer)
                            .distinctBy(MattermostUser::id)
                            .sortedBy { it.username.lowercase(Locale.ROOT) }
                    }
                    ensurePresence(listOf(target.peer.id))
                }
            }
            // Read acknowledgement is independent of the already committed, visible feed.
            val readConfig = config
            val readUserId = currentUser.id
            scope.launch {
                markConversationRead(
                    channelId = target.channelId,
                    previousChannelId = previousChannelId,
                    requestConfig = readConfig,
                    requestUserId = readUserId,
                )
            }
            if (postArrivedDuringConversationSwitch.getAndSet(false)) {
                scope.launch { refreshHeadSafely() }
            }
            target
        } catch (error: Exception) {
            conversationSwitching = false
            restoreActiveConversationAfterFailedSwitch(generation)
            throw error
        }
    }

    private suspend fun restoreActiveConversationAfterFailedSwitch(generation: Int) {
        var restored = false
        feedMutex.withLock {
            if (
                generation == conversationGeneration.get() &&
                ::activeConversation.isInitialized
            ) {
                activeConversationGeneration = generation
                conversationSelected = true
                restored = true
            }
        }
        if (restored && postArrivedDuringConversationSwitch.getAndSet(false)) {
            scope.launch { refreshHeadSafely() }
        }
    }

    private suspend fun activeToken(): ActiveToken? = feedMutex.withLock {
        if (
            !initialized ||
            !conversationSelected ||
            !::activeConversation.isInitialized ||
            activeConversationGeneration != conversationGeneration.get()
        ) {
            null
        } else {
            ActiveToken(activeConversation, activeConversationGeneration)
        }
    }

    private suspend fun activeTokenForSocketPost(): ActiveToken? = feedMutex.withLock {
        if (
            !initialized ||
            !conversationSelected ||
            !::activeConversation.isInitialized ||
            activeConversationGeneration != conversationGeneration.get()
        ) {
            if (initialized && conversationSwitching) {
                postArrivedDuringConversationSwitch.set(true)
            }
            null
        } else {
            ActiveToken(activeConversation, activeConversationGeneration)
        }
    }

    private suspend fun isActive(token: ActiveToken): Boolean =
        feedMutex.withLock { isActiveLocked(token) }

    private fun isActiveLocked(token: ActiveToken): Boolean =
        initialized &&
            ::activeConversation.isInitialized &&
            token.generation == conversationGeneration.get() &&
            token.generation == activeConversationGeneration &&
            token.conversation.channelId == activeConversation.channelId

    private suspend fun knownUser(userId: String): MattermostUser? =
        mentionMutex.withLock { knownUsersById[userId] }

    private fun startSocket() {
        if (socketJob?.isActive == true) return
        socketReady = CompletableDeferred()
        socketJob = scope.launch {
            var attempts = 0
            while (isActive && foreground && initialized) {
                _connectionState.value = ConnectionState.CONNECTING
                try {
                    socket.signals(config, socketCursor).collect { signal ->
                        _connectionState.value = signal.connectionStateAfterReceipt()
                        if (signal is SocketSignal.Disconnected) {
                            throw signal.cause ?: IOException("WebSocket closed")
                        }
                        attempts = 0
                        if (!socketReady.isCompleted) socketReady.complete(Unit)
                        when (signal) {
                            SocketSignal.Opened -> Unit
                            is SocketSignal.Hello -> {
                                val oldConnectionId = socketCursor?.connectionId
                                socketCursor = SocketCursor(signal.connectionId, signal.sequence + 1)
                                if (!oldConnectionId.isNullOrBlank() && oldConnectionId != signal.connectionId) {
                                    scope.launch { refreshLatestSafely() }
                                }
                                scope.launch { refreshAllPresence() }
                            }

                            is SocketSignal.PostEvent -> handleSocketPost(signal)
                            is SocketSignal.StatusChange -> handleStatusChange(signal)
                            is SocketSignal.Sequence -> {
                                if (signal.sequence > 0L) {
                                    val expected = socketCursor?.nextSequence
                                    socketCursor = socketCursor?.copy(nextSequence = signal.sequence + 1)
                                    if (expected != null && expected != signal.sequence) {
                                        scope.launch { refreshLatestSafely() }
                                    }
                                }
                            }
                            is SocketSignal.Disconnected -> error("Handled before signal dispatch")
                        }
                    }
                } catch (_: Exception) {
                    _connectionState.value = ConnectionState.OFFLINE
                }
                if (!isActive || !foreground) break
                val baseDelay = min(30_000L, 3_000L shl attempts.coerceAtMost(3))
                attempts++
                delay(baseDelay + Random.nextLong(0, 750))
            }
        }
    }

    private fun stopSocket() {
        socketJob?.cancel()
        socketJob = null
        _connectionState.value = ConnectionState.OFFLINE
    }

    private suspend fun handleSocketPost(signal: SocketSignal.PostEvent) {
        val cursorUpdate = advanceSocketCursor(socketCursor, signal.sequence)
        socketCursor = cursorUpdate.cursor
        val token = activeTokenForSocketPost()
        val matchesActiveConversation = token != null &&
            signal.channelId == token.conversation.channelId &&
            signal.post.channelId == token.conversation.channelId
        if (!matchesActiveConversation) {
            val updated = updateInactiveConversation(signal)
            if (!updated || cursorUpdate.sequenceGap) scope.launch { refreshDirectorySafely() }
            return
        }
        token ?: return
        val mutations = feedMutex.withLock {
            if (!isActiveLocked(token)) return@withLock null
            feed.merge(listOf(signal.post))
        } ?: return
        invalidateRecentPage(token.conversation.channelId)
        emitAll(mutations)
        updateActiveConversation(signal)
        if (activeConversationAtLatest) scheduleActiveReadAcknowledgement()
        scheduleMentionResolution(listOf(signal.post))

        if (signal.post.fileIds.isNotEmpty() && signal.post.files.isEmpty() && signal.post.deleteAt == 0L) {
            scope.launch {
                runCatching { api.filesForPost(config, signal.post.id) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { files ->
                        emitAll(
                            feedMutex.withLock {
                                if (isActiveLocked(token) && feed.isCurrent(signal.post)) {
                                    feed.merge(listOf(signal.post.copy(files = files)))
                                } else {
                                    emptyList()
                                }
                            },
                        )
                    }
            }
        }

        val missing = feedMutex.withLock { feed.missingUserIds(listOf(signal.post)) }
        if (missing.isNotEmpty()) {
            scope.launch {
                runCatching { api.users(config, missing) }.getOrNull()?.let { profiles ->
                    addMentionUsers(profiles)
                    val profileMutations = feedMutex.withLock {
                        if (!isActiveLocked(token)) return@withLock null
                        feed.updateProfiles(profiles)
                    } ?: return@let
                    emitAll(profileMutations)
                }
            }
        }
        ensurePresence(listOf(signal.post.userId))
        if (cursorUpdate.sequenceGap) scope.launch {
            refreshLatestSafely()
            refreshDirectorySafely()
        }
    }

    private suspend fun replaceDirectory(
        conversations: List<ConversationSummary>,
        emit: Boolean,
    ) {
        directoryMutex.withLock {
            directoryById.clear()
            conversations.forEach { directoryById[it.id] = it }
            directoryOrder = conversations.map(ConversationSummary::id)
        }
        scheduleDirectoryCacheWrite()
        if (emit) _conversationMutations.emit(ConversationMutation.Replace(conversations))
    }

    private suspend fun updateInactiveConversation(signal: SocketSignal.PostEvent): Boolean {
        if (signal.event != "posted" || signal.post.deleteAt != 0L) {
            return directoryMutex.withLock { signal.channelId in directoryById }
        }
        var updated: ConversationSummary? = null
        directoryMutex.withLock {
            val current = directoryById[signal.channelId] ?: return@withLock
            updated = applyInactivePost(
                summary = current,
                post = signal.post,
                event = signal.event,
                currentUserId = currentUser.id,
            )
            directoryById[signal.channelId] = requireNotNull(updated)
        }
        val value = updated ?: return false
        scheduleDirectoryCacheWrite()
        _conversationMutations.emit(ConversationMutation.Update(value))
        return true
    }

    private suspend fun updateActiveConversation(signal: SocketSignal.PostEvent) {
        if (signal.event != "posted" || signal.post.deleteAt != 0L) return
        var updated: ConversationSummary? = null
        directoryMutex.withLock {
            val current = directoryById[signal.channelId] ?: return@withLock
            val lastPostAt = maxOf(current.lastPostAt, signal.post.createAt)
            updated = current.copy(
                channel = current.channel.copy(lastPostAt = lastPostAt),
                lastPostAt = lastPostAt,
                unread = if (activeConversationAtLatest) {
                    false
                } else {
                    current.unread || signal.post.userId != currentUser.id
                },
                mentionCount = if (activeConversationAtLatest) 0 else current.mentionCount,
            )
            directoryById[signal.channelId] = requireNotNull(updated)
        }
        updated?.let { _conversationMutations.emit(ConversationMutation.Update(it)) }
        scheduleDirectoryCacheWrite()
    }

    private suspend fun markConversationRead(
        channelId: String,
        previousChannelId: String?,
        requestConfig: AuthenticatedServerConfig,
        requestUserId: String,
    ) {
        if (config != requestConfig || currentUser.id != requestUserId) return
        val cleared: ConversationSummary
        directoryMutex.withLock {
            if (config != requestConfig || currentUser.id != requestUserId) return
            val current = directoryById[channelId] ?: return
            cleared = current.copy(unread = false, mentionCount = 0)
            directoryById[channelId] = cleared
        }
        _conversationMutations.emit(ConversationMutation.Update(cleared))
        scheduleDirectoryCacheWrite()
        runCatching {
            api.viewChannel(requestConfig, requestUserId, channelId, previousChannelId)
        }.onFailure {
            scope.launch { refreshDirectorySafely() }
        }
    }

    private fun scheduleActiveReadAcknowledgement() {
        activeReadAckJob?.cancel()
        activeReadAckJob = scope.launch {
            delay(ACTIVE_READ_ACK_DELAY_MS)
            if (!activeConversationAtLatest) return@launch
            val token = activeToken() ?: return@launch
            markConversationRead(
                channelId = token.conversation.channelId,
                previousChannelId = null,
                requestConfig = config,
                requestUserId = currentUser.id,
            )
        }
    }

    private suspend fun upsertDirectConversation(
        channel: MattermostChannel,
        peer: MattermostUser,
    ) {
        var summary = ConversationSummary(
            channel = channel.copy(type = channel.type.ifBlank { "D" }),
            kind = ConversationKind.DIRECT_MESSAGE,
            title = peer.displayName,
            peerUserId = peer.id,
            peerUsername = peer.username,
        )
        var replacement: List<ConversationSummary>? = null
        var updateOnly = false
        directoryMutex.withLock {
            val current = directoryById[summary.id]
            updateOnly = current != null
            if (current != null) {
                val lastPostAt = maxOf(current.lastPostAt, summary.lastPostAt)
                summary = summary.copy(
                    channel = summary.channel.copy(lastPostAt = lastPostAt),
                    lastPostAt = lastPostAt,
                    unread = current.unread,
                    mentionCount = current.mentionCount,
                )
            }
            directoryById[summary.id] = summary
            if (!updateOnly) {
                directoryOrder = directoryOrder + summary.id
                replacement = directoryOrder.mapNotNull(directoryById::get)
            }
        }
        scheduleDirectoryCacheWrite()
        if (updateOnly) {
            _conversationMutations.emit(ConversationMutation.Update(summary))
        } else {
            _conversationMutations.emit(ConversationMutation.Replace(replacement.orEmpty()))
        }
    }

    private suspend fun refreshDirectorySafely() {
        if (!initialized) return
        runCatching {
            coroutineScope {
                val channelsRequest = async {
                    api.channelsForUser(config, currentUser.id, currentTeam.id)
                }
                val membershipsRequest = async {
                    api.channelMemberships(config, currentUser.id, currentTeam.id)
                }
                val channels = channelsRequest.await()
                val peerIds = directMessagePeerIds(channels, currentUser.id)
                val peers = if (peerIds.isEmpty()) emptyList() else api.users(config, peerIds)
                addMentionUsers(peers)
                buildConversationDirectory(
                    channels,
                    membershipsRequest.await(),
                    currentUser.id,
                    peers,
                )
            }
        }.onSuccess { replaceDirectory(it, emit = true) }
    }

    private fun scheduleDirectoryCacheWrite() {
        val key = directoryCacheKey ?: return
        directoryCacheJob?.cancel()
        directoryCacheJob = scope.launch {
            delay(DIRECTORY_CACHE_WRITE_DELAY_MS)
            val snapshot = directoryMutex.withLock {
                directoryOrder.mapNotNull(directoryById::get)
            }
            conversationCache.save(key, snapshot)
        }
    }

    private fun recentPage(target: Conversation): CachedConversationPage? {
        val cached = synchronized(recentConversationPages) {
            recentConversationPages[target.channelId]
        } ?: return null
        val age = System.nanoTime() - cached.cachedAtNanos
        if (age > RECENT_CONVERSATION_TTL_NANOS) {
            invalidateRecentPage(target.channelId)
            return null
        }
        if (target.channel.lastPostAt > cached.lastPostAt) {
            invalidateRecentPage(target.channelId)
            return null
        }
        return cached
    }

    private fun cacheRecentPage(
        channelId: String,
        posts: List<MattermostPost>,
        profiles: List<MattermostUser>,
    ) {
        val page = CachedConversationPage(
            posts = posts,
            profiles = profiles,
            lastPostAt = posts.maxOfOrNull(MattermostPost::createAt) ?: 0L,
            cachedAtNanos = System.nanoTime(),
        )
        synchronized(recentConversationPages) { recentConversationPages[channelId] = page }
    }

    private fun invalidateRecentPage(channelId: String) {
        synchronized(recentConversationPages) { recentConversationPages.remove(channelId) }
    }

    private suspend fun handleStatusChange(signal: SocketSignal.StatusChange) {
        val expected = socketCursor?.nextSequence
        val sequenceGap = expected != null && signal.sequence != expected
        socketCursor = socketCursor?.copy(nextSequence = signal.sequence + 1)
        val isRelevant = presenceStore.isTracked(signal.userId) ||
            feedMutex.withLock { signal.userId in feed.userIds() }
        if (isRelevant && presenceStore.applyEvent(signal.userId, signal.status)) {
            _userPresence.value = presenceStore.snapshot()
        }
        if (sequenceGap) scope.launch {
            refreshLatestSafely()
            refreshAllPresence()
        }
    }

    private suspend fun fetchProfiles(posts: Collection<MattermostPost>): List<MattermostUser> {
        val requestedIds = posts.asSequence()
            .map(MattermostPost::userId)
            .filter(String::isNotBlank)
            .toSet()
        val (cached, missing) = mentionMutex.withLock {
            val cachedProfiles = requestedIds.mapNotNull(knownUsersById::get)
            cachedProfiles to requestedIds.filterNotTo(
                mutableSetOf(),
                knownUsersById::containsKey,
            )
        }
        val fetched = if (missing.isEmpty()) emptyList() else api.users(config, missing)
        addMentionUsers(fetched)
        return (cached + fetched).distinctBy(MattermostUser::id)
    }

    private suspend fun cachedProfiles(
        posts: Collection<MattermostPost>,
    ): List<MattermostUser> {
        val requestedIds = posts.asSequence()
            .map(MattermostPost::userId)
            .filter(String::isNotBlank)
            .toSet()
        return mentionMutex.withLock { requestedIds.mapNotNull(knownUsersById::get) }
    }

    private suspend fun hydratePostProfiles(
        generation: Int,
        channelId: String,
        posts: Collection<MattermostPost>,
    ) {
        val profiles = runCatching { fetchProfiles(posts) }.getOrNull() ?: return
        if (generation != conversationGeneration.get()) return
        synchronized(recentConversationPages) {
            val cached = recentConversationPages[channelId]
            if (cached != null) {
                recentConversationPages[channelId] = cached.copy(
                    profiles = (cached.profiles + profiles).distinctBy(MattermostUser::id),
                )
            }
        }
        val token = activeToken()
            ?.takeIf {
                it.generation == generation && it.conversation.channelId == channelId
            }
            ?: return
        val mutations = feedMutex.withLock {
            if (!isActiveLocked(token)) return@withLock emptyList()
            feed.updateProfiles(profiles)
        }
        emitAll(mutations)
    }

    private suspend fun refreshLatestSafely() {
        runCatching { refreshLatest() }
    }

    private suspend fun refreshHeadSafely() {
        runCatching {
            val token = activeToken() ?: return@runCatching
            val head = api.posts(config, token.conversation.channelId)
            if (!isActive(token)) return@runCatching
            val profiles = fetchProfiles(head.orderedPosts)
            val mutations = feedMutex.withLock {
                if (!isActiveLocked(token)) return@withLock null
                feed.merge(head.orderedPosts, profiles)
            } ?: return@runCatching
            emitAll(mutations)
            scheduleMentionResolution(head.orderedPosts)
            ensurePresence(head.orderedPosts.map(MattermostPost::userId))
        }
    }

    private fun ensurePresence(userIds: Collection<String>) {
        val missing = presenceStore.missingUserIds(userIds)
        if (missing.isNotEmpty()) scope.launch { refreshPresence(missing) }
    }

    private suspend fun refreshAllPresence() {
        if (!initialized) return
        val userIds = feedMutex.withLock { feed.userIds() }.toMutableSet()
        userIds += currentUser.id
        refreshPresence(userIds)
    }

    private suspend fun refreshPresence(userIds: Collection<String>) {
        val generation = presenceGeneration
        val requested = synchronized(inFlightPresence) {
            userIds.asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .filter { inFlightPresence.add(generation to it) }
                .toList()
        }
        if (requested.isEmpty()) return
        try {
            val requestConfig = config
            val startedAtRevision = presenceStore.beginSnapshot()
            val statuses = runCatching { api.userStatuses(requestConfig, requested) }.getOrNull() ?: return
            if (generation != presenceGeneration) return
            if (presenceStore.applySnapshot(requested, statuses, startedAtRevision)) {
                _userPresence.value = presenceStore.snapshot()
            }
        } finally {
            synchronized(inFlightPresence) {
                requested.forEach { inFlightPresence.remove(generation to it) }
            }
        }
    }

    private fun scheduleMentionResolution(posts: Collection<MattermostPost>) {
        if (posts.isEmpty()) return
        val messages = posts.asSequence()
            .filter { it.deleteAt == 0L && it.message.isNotBlank() }
            .map(MattermostPost::message)
            .toList()
        if (messages.isNotEmpty()) scope.launch { resolveMentionUsers(messages) }
    }

    private suspend fun resolveMentionUsers(messages: Collection<String>) {
        val candidates = messages.asSequence()
            .flatMap { MessageLinkParser.findMentionUsernames(it).asSequence() }
            .toSet()
        if (candidates.isEmpty()) return
        val generation: Int
        val requested = mentionMutex.withLock {
            generation = mentionGeneration
            candidates.asSequence()
                .filterNot(mentionUsersByUsername::containsKey)
                .filterNot(negativeMentionUsernames::contains)
                .filter { inFlightMentionUsernames.add(it) }
                .toList()
        }
        if (requested.isEmpty()) return

        val resolved = mutableListOf<MattermostUser>()
        var completed = true
        requested.chunked(MAX_MENTION_LOOKUP_USERNAMES).forEach { chunk ->
            val users = runCatching { api.usersByUsernames(config, chunk) }.getOrNull()
            if (users == null) {
                completed = false
            } else {
                resolved += users
            }
        }
        mentionMutex.withLock {
            requested.forEach(inFlightMentionUsernames::remove)
            if (generation != mentionGeneration) return@withLock
            resolved.forEach { user ->
                if (user.username.isNotBlank()) {
                    mentionUsersByUsername[user.username.lowercase(Locale.ROOT)] = user
                }
            }
            if (completed) {
                val found = resolved.mapTo(mutableSetOf()) { it.username.lowercase(Locale.ROOT) }
                requested.filterNot(found::contains).forEach(negativeMentionUsernames::add)
            }
            publishMentionContext()
        }
    }

    private suspend fun addMentionUsers(users: Collection<MattermostUser>) {
        if (users.isEmpty()) return
        mentionMutex.withLock {
            users.forEach { user ->
                if (user.id.isNotBlank()) knownUsersById[user.id] = user
                if (user.username.isNotBlank()) {
                    val username = user.username.lowercase(Locale.ROOT)
                    mentionUsersByUsername[username] = user
                    negativeMentionUsernames.remove(username)
                }
            }
            publishMentionContext()
        }
    }

    private suspend fun loadChannelUsers(
        generation: Int,
        requestConfig: AuthenticatedServerConfig,
        channelId: String,
        authenticatedUser: MattermostUser,
    ) {
        val users = runCatching {
            api.usersInChannel(requestConfig, channelId)
        }.getOrNull() ?: return
        val sortedUsers = (users + authenticatedUser).asSequence()
            .filter { it.username.isNotBlank() }
            .distinctBy { it.username.lowercase(Locale.ROOT) }
            .sortedBy { it.username.lowercase(Locale.ROOT) }
            .toList()
        mentionMutex.withLock {
            if (generation != channelUsersGeneration) return
            sortedUsers.forEach { user ->
                if (user.id.isNotBlank()) knownUsersById[user.id] = user
                val username = user.username.lowercase(Locale.ROOT)
                mentionUsersByUsername[username] = user
                negativeMentionUsernames.remove(username)
            }
            publishMentionContext()
            _channelUsers.value = sortedUsers
        }
        val token = activeToken()
            ?.takeIf { it.conversation.channelId == channelId }
            ?: return
        val mutations = feedMutex.withLock {
            if (!isActiveLocked(token)) return@withLock emptyList()
            feed.updateProfiles(sortedUsers)
        }
        emitAll(mutations)
    }

    private suspend fun resetMentionContext(user: MattermostUser) {
        mentionMutex.withLock {
            mentionGeneration++
            mentionUsersByUsername.clear()
            knownUsersById.clear()
            negativeMentionUsernames.clear()
            inFlightMentionUsernames.clear()
            if (user.username.isNotBlank()) {
                mentionUsersByUsername[user.username.lowercase(Locale.ROOT)] = user
            }
            if (user.id.isNotBlank()) knownUsersById[user.id] = user
            _mentionContext.value = MentionContext(
                currentUserId = user.id,
                currentUsername = user.username,
                usersByUsername = mentionUsersByUsername.toMap(),
            )
        }
    }

    private suspend fun clearMentionContext() {
        mentionMutex.withLock {
            mentionGeneration++
            mentionUsersByUsername.clear()
            knownUsersById.clear()
            negativeMentionUsernames.clear()
            inFlightMentionUsernames.clear()
            _mentionContext.value = MentionContext()
        }
    }

    private fun publishMentionContext() {
        _mentionContext.value = _mentionContext.value.copy(
            usersByUsername = mentionUsersByUsername.toMap(),
        )
    }

    private fun ConversationSummary.cachedPeer(): MattermostUser? {
        val userId = peerUserId?.takeIf(String::isNotBlank) ?: return null
        val username = peerUsername?.takeIf(String::isNotBlank) ?: return null
        return MattermostUser(
            id = userId,
            username = username,
            firstName = title.takeUnless { it == username }.orEmpty(),
        )
    }

    private suspend fun emitAll(mutations: Collection<FeedMutation>) {
        mutations.forEach { _mutations.emit(it) }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_REFRESH_PAGES = 50
        const val MAX_HISTORY_SCAN_PAGES = 50
        const val MAX_MENTION_LOOKUP_USERNAMES = 100
        const val UNCONFIRMED_GRACE_MS = 500L
        const val DIRECTORY_CACHE_WRITE_DELAY_MS = 250L
        const val ACTIVE_READ_ACK_DELAY_MS = 500L
        const val MAX_RECENT_CONVERSATIONS = 5
        const val RECENT_CONVERSATION_TTL_NANOS = 30_000_000_000L
        val POST_ORDER = compareBy<MattermostPost>({ it.createAt }, { it.id })
    }

    private data class ActiveToken(
        val conversation: Conversation,
        val generation: Int,
    )

    private data class CachedConversationPage(
        val posts: List<MattermostPost>,
        val profiles: List<MattermostUser>,
        val lastPostAt: Long,
        val cachedAtNanos: Long,
    )
}
