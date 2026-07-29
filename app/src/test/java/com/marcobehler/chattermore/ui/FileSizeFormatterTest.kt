package com.marcobehler.chattermore.ui

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileSizeFormatterTest {
    @Test
    fun `formats byte sizes for compact attachment metadata`() {
        assertEquals("0 B", FileSizeFormatter.format(0, Locale.US))
        assertEquals("842 B", FileSizeFormatter.format(842, Locale.US))
        assertEquals("1.5 KB", FileSizeFormatter.format(1_500, Locale.US))
        assertEquals("12.4 MB", FileSizeFormatter.format(12_400_000, Locale.US))
        assertEquals("1.2 GB", FileSizeFormatter.format(1_234_000_000, Locale.US))
    }

    @Test
    fun `localizes decimal separator and hides unknown sizes`() {
        assertEquals("1,5 KB", FileSizeFormatter.format(1_500, Locale.GERMANY))
        assertNull(FileSizeFormatter.format(-1, Locale.US))
    }
}
