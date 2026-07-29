package com.marcobehler.chattermore.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.marcobehler.chattermore.MainActivity
import com.marcobehler.chattermore.R
import com.marcobehler.chattermore.model.MattermostException
import com.marcobehler.chattermore.model.MattermostPost
import com.marcobehler.chattermore.model.MattermostUser
import com.marcobehler.chattermore.network.MattermostApi
import com.marcobehler.chattermore.security.SecureConfigStore
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class BackgroundPollingWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    private val store = SecureConfigStore(appContext)
    private val api: MattermostApi
        get() = backgroundApi

    override suspend fun doWork(): Result {
        val settings = store.backgroundPollingSettings()
        if (!settings.enabled || AppVisibility.isForeground) return Result.success()
        if (store.isBackgroundAuthenticationBlocked()) return Result.success()
        val lastForegroundSyncAt = store.lastForegroundSyncAt()
        if (
            lastForegroundSyncAt > 0L &&
            System.currentTimeMillis() - lastForegroundSyncAt in 0 until RECENT_FOREGROUND_SYNC_MS
        ) {
            return Result.success()
        }

        return try {
            var authenticatedThisRun = false
            var session = store.loadBackgroundPollingSession() ?: run {
                authenticatedThisRun = true
                authenticate() ?: return Result.success()
            }
            val posts = try {
                postsAfterCheckpoint(session)
            } catch (error: MattermostException) {
                when (error.statusCode) {
                    401 -> {
                        if (authenticatedThisRun) throw error
                        session = authenticate() ?: return Result.retry()
                        postsAfterCheckpoint(session)
                    }
                    400, 404 -> {
                        establishBaseline(session)
                        return Result.success()
                    }
                    else -> throw error
                }
            }
            if (posts.isEmpty()) return Result.success()

            val newest = BackgroundPollPlanner.newest(posts) ?: return Result.success()
            val candidates = BackgroundPollPlanner.notificationCandidates(
                posts = posts,
                currentUserId = session.currentUserId,
                currentUsername = session.currentUsername,
                mode = settings.mode,
            )

            // Commit before alerting. A process death may suppress one alert, but it cannot
            // produce a duplicate alert on the next run.
            store.advanceBackgroundCheckpoint(
                postId = newest.id,
                createAt = newest.createAt,
                foregroundSync = false,
            )
            if (candidates.isNotEmpty() && notificationPermissionGranted()) {
                val visibleCandidates = candidates.takeLast(MAX_MESSAGES_IN_NOTIFICATION)
                val users = try {
                    api.users(
                        session.config,
                        visibleCandidates
                            .map(MattermostPost::userId)
                            .filter(String::isNotBlank)
                            .toSet(),
                    ).associateBy(MattermostUser::id)
                } catch (_: IOException) {
                    emptyMap()
                } catch (_: MattermostException) {
                    emptyMap()
                }
                showNotification(session, candidates, users, settings.showPreview)
            }
            Result.success()
        } catch (error: MattermostException) {
            when {
                error.statusCode == 401 || error.statusCode == 403 -> {
                    store.blockBackgroundAuthentication()
                    showReconnectNotification()
                    Result.success()
                }
                error.statusCode == 429 || error.statusCode >= 500 -> Result.retry()
                else -> Result.success()
            }
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private suspend fun authenticate(): BackgroundPollingSession? {
        val loginConfig = store.load() ?: return null
        val (authenticatedConfig, user) = api.login(loginConfig)
        val channel = api.resolveChannel(authenticatedConfig)
        val refreshed = BackgroundPollingSession(
            config = authenticatedConfig,
            channelId = channel.id,
            currentUserId = user.id,
            currentUsername = user.username,
        )
        store.saveBackgroundPollingSession(refreshed)
        return store.loadBackgroundPollingSession() ?: refreshed
    }

    private suspend fun postsAfterCheckpoint(session: BackgroundPollingSession): List<MattermostPost> {
        var cursor = session.checkpointPostId
        if (cursor.isNullOrBlank()) {
            establishBaseline(session)
            return emptyList()
        }

        val collected = LinkedHashMap<String, MattermostPost>()
        repeat(MAX_PAGES_PER_RUN) {
            val page = api.posts(
                config = session.config,
                channelId = session.channelId,
                after = cursor,
                perPage = PAGE_SIZE,
            )
            page.orderedPosts.forEach { collected[it.id] = it }
            val newest = BackgroundPollPlanner.newest(page.orderedPosts)
            if (
                page.orderedPosts.size < PAGE_SIZE ||
                newest == null ||
                newest.id == cursor
            ) {
                return collected.values.toList()
            }
            cursor = newest.id
        }
        return collected.values.toList()
    }

    private suspend fun establishBaseline(session: BackgroundPollingSession) {
        val head = api.posts(
            config = session.config,
            channelId = session.channelId,
            perPage = 1,
        )
        BackgroundPollPlanner.newest(head.orderedPosts)?.let {
            store.advanceBackgroundCheckpoint(it.id, it.createAt, foregroundSync = false)
        }
    }

    private fun notificationPermissionGranted(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()

    private fun showNotification(
        session: BackgroundPollingSession,
        posts: List<MattermostPost>,
        users: Map<String, MattermostUser>,
        showPreview: Boolean,
    ) {
        createNotificationChannel(applicationContext)
        val visiblePosts = posts.takeLast(MAX_MESSAGES_IN_NOTIFICATION)
        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName(applicationContext.getString(R.string.you)).build(),
        )
            .setConversationTitle("#${session.config.channelName}")
            .setGroupConversation(true)
        visiblePosts.forEach { post ->
            val author = users[post.userId]?.displayName ?: applicationContext.getString(R.string.someone)
            style.addMessage(
                BackgroundPollPlanner.messageText(post, showPreview),
                post.createAt,
                Person.Builder().setName(author).setKey(post.userId).build(),
            )
        }

        val latestPost = visiblePosts.last()
        val tapIntent = Intent(applicationContext, MainActivity::class.java)
            .putExtra(EXTRA_NOTIFICATION_POST_ID, latestPost.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            session.channelId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicNotification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(
                applicationContext.resources.getQuantityString(
                    R.plurals.new_message_count,
                    posts.size,
                    posts.size,
                ),
            )
            .build()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("#${session.config.channelName}")
            .setContentText(BackgroundPollPlanner.messageText(latestPost, showPreview))
            .setStyle(style)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setNumber(posts.size)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(notificationId(session.channelId), notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked between the check and notify().
        }
    }

    private fun showReconnectNotification() {
        if (!notificationPermissionGranted()) return
        createNotificationChannel(applicationContext)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            RECONNECT_NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.background_checks_paused))
            .setContentText(applicationContext.getString(R.string.open_app_to_reconnect))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext)
                .notify(RECONNECT_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission may have been revoked between the check and notify().
        }
    }

    companion object {
        const val EXTRA_NOTIFICATION_POST_ID = "notification_post_id"
        const val CHANNEL_ID = "background_messages"
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES_PER_RUN = 3
        private const val MAX_MESSAGES_IN_NOTIFICATION = 5
        private const val RECENT_FOREGROUND_SYNC_MS = 2 * 60 * 1_000L
        private const val RECONNECT_NOTIFICATION_ID = 0x4D4D5243
        private val backgroundApi: MattermostApi by lazy {
            MattermostApi(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build(),
            )
        }

        private fun notificationId(channelId: String): Int =
            0x4D4D0000 xor channelId.hashCode()

        fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.background_notifications_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.background_notifications_channel_description)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
            )
        }

        fun cancelNotification(context: Context, channelId: String) {
            NotificationManagerCompat.from(context).cancel(notificationId(channelId))
            NotificationManagerCompat.from(context).cancel(RECONNECT_NOTIFICATION_ID)
        }
    }
}
