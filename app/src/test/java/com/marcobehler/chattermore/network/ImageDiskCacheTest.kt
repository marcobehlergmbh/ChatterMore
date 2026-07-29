package com.marcobehler.chattermore.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageDiskCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `cached bytes survive a new cache instance`() {
        val directory = temporaryFolder.newFolder("images")
        val bytes = byteArrayOf(1, 2, 3, 4)
        ImageDiskCache(directory, 1024).write("server|image", bytes)

        val reopened = ImageDiskCache(directory, 1024)

        assertArrayEquals(bytes, reopened.read("server|image"))
    }

    @Test
    fun `entries larger than the bounded cache are not stored`() {
        val cache = ImageDiskCache(temporaryFolder.newFolder("bounded"), 3)

        cache.write("large", byteArrayOf(1, 2, 3, 4))

        assertNull(cache.read("large"))
    }

    @Test
    fun `least recently used files are trimmed to the size bound`() {
        val directory = temporaryFolder.newFolder("trimmed")
        val cache = ImageDiskCache(directory, 5)

        cache.write("first", byteArrayOf(1, 2, 3))
        cache.write("second", byteArrayOf(4, 5, 6))

        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 5)
    }
}
