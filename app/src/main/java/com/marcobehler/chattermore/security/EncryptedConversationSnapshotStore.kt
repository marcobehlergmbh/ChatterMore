package com.marcobehler.chattermore.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.marcobehler.chattermore.data.ConversationSnapshot
import com.marcobehler.chattermore.data.ConversationSnapshotCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedConversationSnapshotStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    fun load(): ConversationSnapshot? = runCatching {
        val encrypted = file.readFully()
        require(encrypted.size in 1..MAX_ENCRYPTED_BYTES)
        requireNotNull(
            ConversationSnapshotCodec.decode(decrypt(encrypted).toString(Charsets.UTF_8)),
        )
    }.getOrElse {
        clear()
        null
    }

    suspend fun save(snapshot: ConversationSnapshot) = withContext(Dispatchers.IO) {
        val encoded = ConversationSnapshotCodec.encodeBounded(snapshot, MAX_PLAINTEXT_BYTES)
            ?: return@withContext
        val encrypted = encrypt(encoded.toByteArray(Charsets.UTF_8))
        if (encrypted.size > MAX_ENCRYPTED_BYTES) return@withContext
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() {
        file.delete()
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(HEADER_BYTES + cipher.iv.size + ciphertext.size)
            .put(FORMAT_VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(payload)
        require(buffer.get() == FORMAT_VERSION)
        val ivLength = buffer.get().toInt() and 0xff
        require(ivLength in 12..32 && buffer.remaining() > ivLength)
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
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
        const val FILE_NAME = "last_conversation.snapshot"
        const val KEY_ALIAS = "chattermore_conversation_snapshot"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_PLAINTEXT_BYTES = 512 * 1024
        const val MAX_ENCRYPTED_BYTES = MAX_PLAINTEXT_BYTES + 128
        const val HEADER_BYTES = 2
        val FORMAT_VERSION: Byte = 1
        val AAD = "chattermore:last-conversation:v1".toByteArray(Charsets.UTF_8)
    }
}
