package com.example.mattermostlite.data

import com.example.mattermostlite.model.ConnectionState
import com.example.mattermostlite.model.AuthenticatedServerConfig
import com.example.mattermostlite.model.DeliveryState
import com.example.mattermostlite.model.FeedItem
import com.example.mattermostlite.model.FeedMutation
import com.example.mattermostlite.model.MattermostChannel
import com.example.mattermostlite.model.MattermostFile
import com.example.mattermostlite.model.MattermostPost
import com.example.mattermostlite.model.MattermostUser
import com.example.mattermostlite.model.MentionContext
import com.example.mattermostlite.model.ServerConfig
import com.example.mattermostlite.model.SocketCursor
import com.example.mattermostlite.model.SocketSignal
import com.example.mattermostlite.model.UploadCapabilities
import com.example.mattermostlite.model.UserPresence
import com.example.mattermostlite.network.MattermostApi
import com.example.mattermostlite.network.MattermostSocket
import com.example.mattermostlite.ui.MessageLinkParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.Locale
import okhttp3.RequestBody
import kotlin.math.min
import kotlin.random.Random

class ChannelRepository(
    private val api: MattermostApi,
    private val socket: MattermostSocket,
    private val scope: CoroutineScope,
    unsupportedMessage: String,
) {
    private val feed = FeedStore(unsupportedMessage)
    private val presenceStore = PresenceStore()
    private val feedMutex = Mutex()
    private val olderMutex = Mutex()
    private val refreshMutex = Mutex()
    private val mentionMutex = Mutex()
    private val _mutations = MutableSharedFlow<FeedMutation>(extraBufferCapacity = 256)
    private val _connectionState = MutableStateFlow(ConnectionState.OFFLINE)
    private val _refreshing = MutableStateFlow(false)
    private val _uploadCapabilities = MutableStateFlow(UploadCapabilities())
    private val _userPresence = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    private val _mentionContext = MutableStateFlow(MentionContext())
    private val _channelUsers = MutableStateFlow<List<MattermostUser>>(emptyList())

    val mutations: SharedFlow<FeedMutation> = _mutations.asSharedFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    val uploadCapabilities: StateFlow<UploadCapabilities> = _uploadCapabilities.asStateFlow()
    val userPresence: StateFlow<Map<String, UserPresence>> = _userPresence.asStateFlow()
    val mentionContext: StateFlow<MentionContext> = _mentionContext.asStateFlow()
    val channelUsers: StateFlow<List<MattermostUser>> = _channelUsers.asStateFlow()

    private lateinit var config: AuthenticatedServerConfig
    private lateinit var channel: MattermostChannel
    private lateinit var currentUser: MattermostUser
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
    private var mentionGeneration = 0
    private var channelUsersGeneration = 0

    suspend fun initialize(
        rawConfig: ServerConfig,
    ): Triple<MattermostChannel, MattermostUser, AuthenticatedServerConfig> {
        stopSocket()
        initialized = false
        presenceGeneration++
        socketCursor = null
        olderExhausted = false
        presenceStore.clear()
        _userPresence.value = emptyMap()
        _channelUsers.value = emptyList()
        channelUsersGeneration++
        val usersGeneration = channelUsersGeneration
        _uploadCapabilities.value = UploadCapabilities()
        clearMentionContext()
        val (authenticatedConfig, authenticatedUser) = api.login(rawConfig)
        config = authenticatedConfig
        currentUser = authenticatedUser
        resetMentionContext(authenticatedUser)
        _channelUsers.value = listOf(authenticatedUser)
        channel = api.resolveChannel(config)
        _uploadCapabilities.value = runCatching { api.uploadCapabilities(config) }
            .getOrDefault(UploadCapabilities())
        initialized = true
        if (foreground) startSocket()
        withTimeoutOrNull(3_000) { socketReady.await() }

        val page = api.posts(config, channel.id)
        val posts = page.orderedPosts
        val profiles = fetchProfiles(posts)
        refreshPresence(posts.map(MattermostPost::userId) + currentUser.id)
        val mutation = feedMutex.withLock { feed.replace(posts, profiles + currentUser) }
        _mutations.emit(mutation)
        scheduleMentionResolution(posts)
        scope.launch {
            loadChannelUsers(usersGeneration, config, channel.id, currentUser)
        }
        return Triple(channel, currentUser, config)
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value && initialized) startSocket() else if (!value) stopSocket()
    }

    suspend fun snapshot(): List<FeedItem> = feedMutex.withLock { feed.snapshot() }

    suspend fun loadOlder() {
        if (olderExhausted || !olderMutex.tryLock()) return
        try {
            val visibleCursor = feedMutex.withLock { feed.oldestServerId() } ?: return
            var requestCursor = visibleCursor
            repeat(MAX_HISTORY_SCAN_PAGES) {
                val page = api.posts(config, channel.id, before = requestCursor)
                val posts = page.orderedPosts
                if (posts.isEmpty()) {
                    olderExhausted = true
                    return
                }
                val profiles = fetchProfiles(posts)
                val (mutations, newVisibleCursor) = feedMutex.withLock {
                    feed.mergeOlder(posts, profiles) to feed.oldestServerId()
                }
                if (newVisibleCursor != visibleCursor) {
                    emitAll(mutations)
                    scheduleMentionResolution(posts)
                    ensurePresence(posts.map(MattermostPost::userId))
                    return
                }

                // A page may contain only thread replies, which this single-channel feed hides.
                val nextRequestCursor = posts.minWithOrNull(POST_ORDER)?.id
                if (nextRequestCursor.isNullOrBlank() || nextRequestCursor == requestCursor) {
                    olderExhausted = true
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
            var cursor = feedMutex.withLock { feed.newestServerId() }
            val seenCursors = mutableSetOf<String>()
            var pages = 0
            if (cursor != null) {
                while (pages++ < MAX_REFRESH_PAGES) {
                    val currentCursor = cursor ?: break
                    if (!seenCursors.add(currentCursor)) break
                    val page = api.posts(config, channel.id, after = currentCursor)
                    val posts = page.orderedPosts
                    val profiles = fetchProfiles(posts)
                    emitAll(feedMutex.withLock { feed.merge(posts, profiles) })
                    scheduleMentionResolution(posts)
                    ensurePresence(posts.map(MattermostPost::userId))
                    val next = posts.maxWithOrNull(POST_ORDER)?.id
                    if (posts.size < PAGE_SIZE || next.isNullOrBlank() || next == currentCursor) break
                    cursor = next
                }
            }

            // Overlap the head page so a stale or deleted cursor cannot leave a gap.
            val head = api.posts(config, channel.id)
            val profiles = fetchProfiles(head.orderedPosts)
            emitAll(feedMutex.withLock { feed.merge(head.orderedPosts, profiles) })
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
    ): MattermostFile = api.uploadFile(
        config,
        channel.id,
        fileName,
        clientId,
        body,
        onProgress,
        onBodyComplete,
    )

    suspend fun send(message: String, uploadedFiles: List<MattermostFile> = emptyList()): Boolean {
        if (message.isBlank() && uploadedFiles.isEmpty()) return false
        val pendingId = UUID.randomUUID().toString()
        val mutation = feedMutex.withLock {
            feed.addPending(
                pendingId,
                channel.id,
                currentUser,
                message,
                System.currentTimeMillis(),
                uploadedFiles,
            )
        }
        _mutations.emit(mutation)
        scheduleUnconfirmed(pendingId)
        return submitPending(pendingId, message, uploadedFiles.map(MattermostFile::id))
    }

    suspend fun edit(postId: String, message: String): Boolean {
        if (message.isBlank()) return false
        val canEdit = feedMutex.withLock {
            feed.post(postId)?.let { item ->
                item.userId == currentUser.id && item.deliveryState == DeliveryState.SENT
            } == true
        }
        if (!canEdit) return false

        return try {
            val post = api.patchPost(config, postId, message)
            emitAll(feedMutex.withLock { feed.merge(listOf(post), listOf(currentUser)) })
            scheduleMentionResolution(listOf(post))
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun retry(pendingId: String) {
        val pending = feedMutex.withLock {
            val item = feed.pending(pendingId) ?: return
            val mutation = feed.setPendingState(pendingId, DeliveryState.OPTIMISTIC) ?: return
            Triple(mutation, item.message, item.fileIds)
        }
        _mutations.emit(pending.first)
        scheduleUnconfirmed(pendingId)
        submitPending(pendingId, pending.second, pending.third)
    }

    fun close() {
        stopSocket()
        initialized = false
        presenceGeneration++
        channelUsersGeneration++
        presenceStore.clear()
        _userPresence.value = emptyMap()
        _channelUsers.value = emptyList()
        scope.launch { clearMentionContext() }
    }

    private suspend fun submitPending(
        pendingId: String,
        message: String,
        fileIds: List<String>,
    ): Boolean = try {
        val post = api.createPost(config, channel.id, message, pendingId, fileIds)
        emitAll(feedMutex.withLock { feed.merge(listOf(post), listOf(currentUser)) })
        scheduleMentionResolution(listOf(post))
        true
    } catch (error: Exception) {
        feedMutex.withLock { feed.setPendingState(pendingId, DeliveryState.FAILED) }
            ?.let { _mutations.emit(it) }
        false
    }

    private fun scheduleUnconfirmed(pendingId: String) {
        scope.launch {
            delay(UNCONFIRMED_GRACE_MS)
            feedMutex.withLock { feed.markUnconfirmed(pendingId) }
                ?.let { _mutations.emit(it) }
        }
    }

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
        if (signal.channelId != channel.id || signal.post.channelId != channel.id) return
        val expected = socketCursor?.nextSequence
        val sequenceGap = expected != null && signal.sequence != expected
        socketCursor = socketCursor?.copy(nextSequence = signal.sequence + 1)
        emitAll(feedMutex.withLock { feed.merge(listOf(signal.post)) })
        scheduleMentionResolution(listOf(signal.post))

        if (signal.post.fileIds.isNotEmpty() && signal.post.files.isEmpty() && signal.post.deleteAt == 0L) {
            scope.launch {
                runCatching { api.filesForPost(config, signal.post.id) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { files ->
                        emitAll(
                            feedMutex.withLock {
                                if (feed.isCurrent(signal.post)) {
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
                    emitAll(feedMutex.withLock { feed.updateProfiles(profiles) })
                }
            }
        }
        ensurePresence(listOf(signal.post.userId))
        if (sequenceGap) scope.launch { refreshLatestSafely() }
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
        val missing = feedMutex.withLock { feed.missingUserIds(posts) }
        val profiles = if (missing.isEmpty()) emptyList() else api.users(config, missing)
        addMentionUsers(profiles)
        return profiles
    }

    private suspend fun refreshLatestSafely() {
        runCatching { refreshLatest() }
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
                val username = user.username.lowercase(Locale.ROOT)
                mentionUsersByUsername[username] = user
                negativeMentionUsernames.remove(username)
            }
            publishMentionContext()
            _channelUsers.value = sortedUsers
        }
    }

    private suspend fun resetMentionContext(user: MattermostUser) {
        mentionMutex.withLock {
            mentionGeneration++
            mentionUsersByUsername.clear()
            negativeMentionUsernames.clear()
            inFlightMentionUsernames.clear()
            if (user.username.isNotBlank()) {
                mentionUsersByUsername[user.username.lowercase(Locale.ROOT)] = user
            }
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

    private suspend fun emitAll(mutations: Collection<FeedMutation>) {
        mutations.forEach { _mutations.emit(it) }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_REFRESH_PAGES = 50
        const val MAX_HISTORY_SCAN_PAGES = 50
        const val MAX_MENTION_LOOKUP_USERNAMES = 100
        const val UNCONFIRMED_GRACE_MS = 500L
        val POST_ORDER = compareBy<MattermostPost>({ it.createAt }, { it.id })
    }
}
