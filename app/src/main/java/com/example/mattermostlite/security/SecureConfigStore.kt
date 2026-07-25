package com.example.mattermostlite.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
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
        preferences.edit {
            remove(KEY_BASE_URL)
            remove(KEY_TEAM)
            remove(KEY_CHANNEL)
            remove(KEY_USERNAME)
            remove(KEY_PASSWORD)
            remove(LEGACY_KEY_TOKEN)
        }
    }

    private fun android.content.SharedPreferences.Editor.putRememberedServer(config: ServerConfig) {
        putString(KEY_LAST_BASE_URL, config.baseUrl)
        putString(KEY_LAST_TEAM, config.teamName)
        putString(KEY_LAST_USERNAME, config.username)
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
        const val KEY_ALIAS = "mattermost_lite_credentials"
        const val LEGACY_KEY_TOKEN = "token"
        const val LEGACY_KEY_ALIAS = "mattermost_lite_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
