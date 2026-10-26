package com.marcobehler.chattermore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPrefetchPolicyTest {
    @Test
    fun `threshold is twenty percent rounded up`() {
        assertEquals(20, HistoryPrefetchPolicy.threshold(100))
        assertEquals(400, HistoryPrefetchPolicy.threshold(2_000))
        assertEquals(2, HistoryPrefetchPolicy.threshold(7))
    }

    @Test
    fun `prefetch starts only after crossing older boundary threshold`() {
        assertFalse(HistoryPrefetchPolicy.shouldPrefetch(21, 100))
        assertTrue(HistoryPrefetchPolicy.shouldPrefetch(20, 100))
        assertTrue(HistoryPrefetchPolicy.shouldPrefetch(5, 100))
        assertFalse(HistoryPrefetchPolicy.shouldPrefetch(-1, 100))
    }

    @Test
    fun `prefetch starts symmetrically before newer boundary`() {
        assertFalse(HistoryPrefetchPolicy.shouldPrefetchNewer(78, 100))
        assertTrue(HistoryPrefetchPolicy.shouldPrefetchNewer(79, 100))
        assertTrue(HistoryPrefetchPolicy.shouldPrefetchNewer(99, 100))
        assertFalse(HistoryPrefetchPolicy.shouldPrefetchNewer(-1, 100))
        assertFalse(HistoryPrefetchPolicy.shouldPrefetchNewer(0, 0))
    }
}
