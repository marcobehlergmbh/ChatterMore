package com.marcobehler.chattermore.background

import com.marcobehler.chattermore.model.AuthenticatedServerConfig
import com.marcobehler.chattermore.model.MattermostUser

enum class BackgroundNotificationMode {
    MENTIONS,
    ALL,
}

data class BackgroundPollingSettings(
    val enabled: Boolean = false,
    val mode: BackgroundNotificationMode = BackgroundNotificationMode.MENTIONS,
    val showPreview: Boolean = true,
)

data class BackgroundPollingSession(
    val config: AuthenticatedServerConfig,
    val channelId: String,
    val currentUserId: String,
    val currentUsername: String,
    val checkpointPostId: String? = null,
    val checkpointCreateAt: Long = 0L,
) {
    fun refreshed(config: AuthenticatedServerConfig, user: MattermostUser): BackgroundPollingSession? =
        if (this.config.baseUrl != config.baseUrl || currentUserId != user.id) null else copy(
            config = this.config.copy(token = config.token),
            currentUsername = user.username,
        )
}

data class NotificationTarget(
    val baseUrl: String,
    val userId: String,
    val channelId: String,
    val postId: String,
) {
    fun matches(config: AuthenticatedServerConfig, currentUserId: String): Boolean =
        baseUrl == config.baseUrl && userId == currentUserId &&
            channelId.isNotBlank() && postId.isNotBlank()
}

