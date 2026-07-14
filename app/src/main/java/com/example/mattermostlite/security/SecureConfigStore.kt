package com.example.mattermostlite.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.example.mattermostlite.model.ServerConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        val baseUrl = preferences.getString(KEY_BASE_URL, null) ?: return null
        val team = preferences.getString(KEY_TEAM, null) ?: return null
        val channel = preferences.getString(KEY_CHANNEL, null) ?: return null
        val encryptedToken = preferences.getString(KEY_TOKEN, null) ?: return null
        return runCatching {
            ServerConfig(baseUrl, team, channel, decrypt(encryptedToken))
        }.getOrElse {
            clear()
            null
        }
    }

    fun save(config: ServerConfig) {
        preferences.edit {
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_TEAM, config.teamName)
            putString(KEY_CHANNEL, config.channelName)
            putString(KEY_TOKEN, encrypt(config.token))
        }
    }

    fun clear() {
        preferences.edit { clear() }
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
        require(parts.size == 2) { "Invalid encrypted token" }
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
        const val KEY_TOKEN = "token"
        const val KEY_ALIAS = "mattermost_lite_token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
