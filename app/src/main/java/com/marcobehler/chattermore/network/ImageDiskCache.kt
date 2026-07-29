package com.marcobehler.chattermore.network

import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class ImageDiskCache(
    private val directory: File,
    private val maxBytes: Long,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    @Synchronized
    fun read(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null
        if (file.length() <= 0L || file.length() > maxBytes) {
            file.delete()
            return null
        }
        return runCatching {
            file.readBytes().also { file.setLastModified(System.currentTimeMillis()) }
        }.getOrNull()
    }

    @Synchronized
    fun write(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size.toLong() > maxBytes) return
        if (!directory.exists() && !directory.mkdirs()) return
        val target = fileFor(key)
        val temporary = File(directory, "${target.name}.tmp")
        runCatching {
            temporary.outputStream().buffered().use { it.write(bytes) }
            if (target.exists() && !target.delete()) throw IOException("Could not replace cached image")
            if (!temporary.renameTo(target)) throw IOException("Could not store cached image")
            target.setLastModified(System.currentTimeMillis())
            trimToSize()
        }.onFailure { temporary.delete() }
    }

    @Synchronized
    fun remove(key: String) {
        fileFor(key).delete()
    }

    private fun trimToSize() {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.sortedBy(File::lastModified)
            ?: return
        var total = files.sumOf(File::length)
        for (file in files) {
            if (total <= maxBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, name + EXTENSION)
    }

    private companion object {
        const val EXTENSION = ".image"
    }
}
