package com.marcobehler.chattermore.background

import com.marcobehler.chattermore.model.AuthenticatedServerConfig

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
)

