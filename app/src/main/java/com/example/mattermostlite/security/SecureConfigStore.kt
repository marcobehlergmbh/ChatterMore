package com.example.mattermostlite.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.example.mattermostlite.background.BackgroundNotificationMode
import com.example.mattermostlite.background.BackgroundPollingSession
import com.example.mattermostlite.background.BackgroundPollingSettings
import com.example.mattermostlite.model.AuthenticatedServerConfig
import com.example.mattermostlite.model.RememberedConversation
import com.example.mattermostlite.model.RememberedServer
import com.example.mattermostlite.model.ServerConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        loadRememberedServer()
        if (preferences.contains(LEGACY_KEY_TOKEN) && !preferences.contains(KEY_PASSWORD)) {
            clearCredentials()
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    .deleteEntry(LEGACY_KEY_ALIAS)
            }
            return null
        }
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val team = preferences.getString(KEY_TEAM, null) ?: return null
        val channel = preferences.getString(KEY_CHANNEL, null) ?: return null
        val username = preferences.getString(KEY_USERNAME, null) ?: return null
        val encryptedPassword = preferences.getString(KEY_PASSWORD, null) ?: return null
        return runCatching {
            ServerConfig(baseUrl, team, channel, username, decrypt(encryptedPassword))
        }.getOrElse {
            clearCredentials()
            null
        }
    }

    fun save(config: ServerConfig) {
        preferences.edit {
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_TEAM, config.teamName)
            putString(KEY_CHANNEL, config.channelName)
            putString(KEY_USERNAME, config.username)
            putString(KEY_PASSWORD, encrypt(config.password))
            putRememberedServer(config)
        }
    }

    fun rememberServer(config: ServerConfig) {
        preferences.edit { putRememberedServer(config) }
    }

    fun rememberConversation(conversation: RememberedConversation) {
        preferences.edit {
            putString(KEY_LAST_CONVERSATION_BASE_URL, conversation.baseUrl)
            putString(KEY_LAST_CONVERSATION_TEAM_ID, conversation.teamId)
            putString(KEY_LAST_CONVERSATION_USER_ID, conversation.userId)
            putString(KEY_LAST_CONVERSATION_CHANNEL_ID, conversation.channelId)
        }
    }

    fun loadRememberedConversation(): RememberedConversation? {
        val baseUrl = preferences.getString(KEY_LAST_CONVERSATION_BASE_URL, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val teamId = preferences.getString(KEY_LAST_CONVERSATION_TEAM_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val userId = preferences.getString(KEY_LAST_CONVERSATION_USER_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        val channelId = preferences.getString(KEY_LAST_CONVERSATION_CHANNEL_ID, null)
            ?.takeIf(String::isNotBlank)
            ?: return null
        return RememberedConversation(baseUrl, teamId, userId, channelId)
    }

    fun loadRememberedServer(): RememberedServer? {
        val baseUrl = preferences.getString(KEY_LAST_BASE_URL, null)
            ?: preferences.getString(KEY_BASE_URL, null)
            ?: return null
        val team = preferences.getString(KEY_LAST_TEAM, null)
            ?: preferences.getString(KEY_TEAM, null)
            ?: return null
        val username = preferences.getString(KEY_LAST_USERNAME, null)
            ?: preferences.getString(KEY_USERNAME, null)
            ?: return null
        if (
            !preferences.contains(KEY_LAST_BASE_URL) ||
            !preferences.contains(KEY_LAST_TEAM) ||
            !preferences.contains(KEY_LAST_USERNAME)
        ) {
            preferences.edit {
                putString(KEY_LAST_BASE_URL, baseUrl)
                putString(KEY_LAST_TEAM, team)
                putString(KEY_LAST_USERNAME, username)
            }
        }
        return RememberedServer(baseUrl, team, username)
    }

    fun clearCredentials() {
        preferences.edit(commit = true) {
            remove(KEY_BASE_URL)
            remove(KEY_TEAM)
            remove(KEY_CHANNEL)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(LEGACY_KEY_TOKEN)
            remove(KEY_BACKGROUND_ENABLED)
            removeBackgroundSession()
        }
    }

    fun backgroundPollingSettings(): BackgroundPollingSettings = BackgroundPollingSettings(
        enabled = preferences.getBoolean(KEY_BACKGROUND_ENABLED, false),
        mode = preferences.getString(KEY_BACKGROUND_MODE, null)
            ?.let { runCatching { BackgroundNotificationMode.valueOf(it) }.getOrNull() }
            ?: BackgroundNotificationMode.MENTIONS,
        showPreview = preferences.getBoolean(KEY_BACKGROUND_PREVIEW, true),
    )

    fun setBackgroundPollingSettings(settings: BackgroundPollingSettings) {
        preferences.edit(commit = true) {
            putBoolean(KEY_BACKGROUND_ENABLED, settings.enabled)
            putString(KEY_BACKGROUND_MODE, settings.mode.name)
            putBoolean(KEY_BACKGROUND_PREVIEW, settings.showPreview)
            if (!settings.enabled) removeBackgroundSession()
        }
    }

    fun loadBackgroundPollingSession(): BackgroundPollingSession? {
        val baseUrl = preferences.getString(KEY_BACKGROUND_BASE_URL, null) ?: return null
        val team = preferences.getString(KEY_BACKGROUND_TEAM, null) ?: return null
        val channel = preferences.getString(KEY_BACKGROUND_CHANNEL, null) ?: return null
        val encryptedToken = preferences.getString(KEY_BACKGROUND_TOKEN, null) ?: return null
        val channelId = preferences.getString(KEY_BACKGROUND_CHANNEL_ID, null) ?: return null
        val userId = preferences.getString(KEY_BACKGROUND_USER_ID, null) ?: return null
        val username = preferences.getString(KEY_BACKGROUND_USERNAME, null) ?: return null
        return runCatching {
            BackgroundPollingSession(
                config = AuthenticatedServerConfig(
                    baseUrl = baseUrl,
                    teamName = team,
                    channelName = channel,
                    token = decrypt(encryptedToken),
                ),
                channelId = channelId,
                currentUserId = userId,
                currentUsername = username,
                checkpointPostId = preferences.getString(KEY_BACKGROUND_CHECKPOINT_ID, null),
                checkpointCreateAt = preferences.getLong(KEY_BACKGROUND_CHECKPOINT_AT, 0L),
            )
        }.getOrElse {
            clearBackgroundPollingSession()
            null
        }
    }

    fun saveBackgroundPollingSession(session: BackgroundPollingSession) {
        val previous = loadBackgroundPollingSession()
        val sameIdentity = previous?.sameIdentityAs(session) == true
        val checkpointPostId = if (sameIdentity) {
            newerCheckpoint(previous, session).checkpointPostId
        } else {
            session.checkpointPostId
        }
        val checkpointCreateAt = if (sameIdentity) {
            newerCheckpoint(previous, session).checkpointCreateAt
        } else {
            session.checkpointCreateAt
        }
        preferences.edit(commit = true) {
            putString(KEY_BACKGROUND_BASE_URL, session.config.baseUrl)
            putString(KEY_BACKGROUND_TEAM, session.config.teamName)
            putString(KEY_BACKGROUND_CHANNEL, session.config.channelName)
            putString(KEY_BACKGROUND_TOKEN, encrypt(session.config.token))
            putString(KEY_BACKGROUND_CHANNEL_ID, session.channelId)
            putString(KEY_BACKGROUND_USER_ID, session.currentUserId)
            putString(KEY_BACKGROUND_USERNAME, session.currentUsername)
            putString(KEY_BACKGROUND_CHECKPOINT_ID, checkpointPostId)
            putLong(KEY_BACKGROUND_CHECKPOINT_AT, checkpointCreateAt)
            putBoolean(KEY_BACKGROUND_AUTH_BLOCKED, false)
        }
    }

    fun advanceBackgroundCheckpoint(
        postId: String,
        createAt: Long,
        foregroundSync: Boolean,
    ): Boolean {
        if (postId.isBlank()) return false
        val currentId = preferences.getString(KEY_BACKGROUND_CHECKPOINT_ID, null)
        val currentAt = preferences.getLong(KEY_BACKGROUND_CHECKPOINT_AT, 0L)
        if (createAt < currentAt || (createAt == currentAt && currentId != null && postId <= currentId)) {
            return false
        }
        preferences.edit(commit = !foregroundSync) {
            putString(KEY_BACKGROUND_CHECKPOINT_ID, postId)
            putLong(KEY_BACKGROUND_CHECKPOINT_AT, createAt)
            if (foregroundSync) putLong(KEY_BACKGROUND_FOREGROUND_SYNC_AT, System.currentTimeMillis())
        }
        return true
    }

    fun lastForegroundSyncAt(): Long =
        preferences.getLong(KEY_BACKGROUND_FOREGROUND_SYNC_AT, 0L)

    fun isBackgroundAuthenticationBlocked(): Boolean =
        preferences.getBoolean(KEY_BACKGROUND_AUTH_BLOCKED, false)

    fun blockBackgroundAuthentication() {
        preferences.edit(commit = true) { putBoolean(KEY_BACKGROUND_AUTH_BLOCKED, true) }
    }

    fun clearBackgroundPollingSession() {
        preferences.edit(commit = true) { removeBackgroundSession() }
    }

    private fun android.content.SharedPreferences.Editor.putRememberedServer(config: ServerConfig) {
        putString(KEY_LAST_BASE_URL, config.baseUrl)
        putString(KEY_LAST_TEAM, config.teamName)
        putString(KEY_LAST_USERNAME, config.username)
    }

    private fun android.content.SharedPreferences.Editor.removeBackgroundSession() {
        remove(KEY_BACKGROUND_BASE_URL)
        remove(KEY_BACKGROUND_TEAM)
        remove(KEY_BACKGROUND_CHANNEL)
        remove(KEY_BACKGROUND_TOKEN)
        remove(KEY_BACKGROUND_CHANNEL_ID)
        remove(KEY_BACKGROUND_USER_ID)
        remove(KEY_BACKGROUND_USERNAME)
        remove(KEY_BACKGROUND_CHECKPOINT_ID)
        remove(KEY_BACKGROUND_CHECKPOINT_AT)
        remove(KEY_BACKGROUND_FOREGROUND_SYNC_AT)
        remove(KEY_BACKGROUND_AUTH_BLOCKED)
    }

    private fun BackgroundPollingSession.sameIdentityAs(other: BackgroundPollingSession): Boolean =
        config.baseUrl == other.config.baseUrl &&
            config.teamName == other.config.teamName &&
            config.channelName == other.config.channelName &&
            channelId == other.channelId &&
            currentUserId == other.currentUserId

    private fun newerCheckpoint(
        first: BackgroundPollingSession,
        second: BackgroundPollingSession,
    ): BackgroundPollingSession = if (
        second.checkpointCreateAt > first.checkpointCreateAt ||
        (
            second.checkpointCreateAt == first.checkpointCreateAt &&
                second.checkpointPostId.orEmpty() > first.checkpointPostId.orEmpty()
            )
    ) {
        second
    } else {
        first
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return listOf(cipher.iv, ciphertext)
            .joinToString(":") { Base64.encodeToString(it, Base64.NO_WRAP) }
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted password" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "connection"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TEAM = "team"
        const val KEY_CHANNEL = "channel"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_LAST_BASE_URL = "last_base_url"
        const val KEY_LAST_TEAM = "last_team"
        const val KEY_LAST_USERNAME = "last_username"
        const val KEY_LAST_CONVERSATION_BASE_URL = "last_conversation_base_url"
        const val KEY_LAST_CONVERSATION_TEAM_ID = "last_conversation_team_id"
        const val KEY_LAST_CONVERSATION_USER_ID = "last_conversation_user_id"
        const val KEY_LAST_CONVERSATION_CHANNEL_ID = "last_conversation_channel_id"
        const val KEY_BACKGROUND_ENABLED = "background_enabled"
        const val KEY_BACKGROUND_MODE = "background_mode"
        const val KEY_BACKGROUND_PREVIEW = "background_preview"
        const val KEY_BACKGROUND_BASE_URL = "background_base_url"
        const val KEY_BACKGROUND_TEAM = "background_team"
        const val KEY_BACKGROUND_CHANNEL = "background_channel"
        const val KEY_BACKGROUND_TOKEN = "background_token"
        const val KEY_BACKGROUND_CHANNEL_ID = "background_channel_id"
        const val KEY_BACKGROUND_USER_ID = "background_user_id"
        const val KEY_BACKGROUND_USERNAME = "background_username"
        const val KEY_BACKGROUND_CHECKPOINT_ID = "background_checkpoint_id"
        const val KEY_BACKGROUND_CHECKPOINT_AT = "background_checkpoint_at"
        const val KEY_BACKGROUND_FOREGROUND_SYNC_AT = "background_foreground_sync_at"
        const val KEY_BACKGROUND_AUTH_BLOCKED = "background_auth_blocked"
        const val KEY_ALIAS = "mattermost_lite_credentials"
        const val LEGACY_KEY_TOKEN = "token"
        const val LEGACY_KEY_ALIAS = "mattermost_lite_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
