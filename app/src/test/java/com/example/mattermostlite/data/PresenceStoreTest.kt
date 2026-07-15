package com.example.mattermostlite.data

import com.example.mattermostlite.model.MattermostUserStatus
import com.example.mattermostlite.model.UserPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceStoreTest {
    @Test
    fun `snapshot assigns offline when the server omits a requested user`() {
        val store = PresenceStore()
        val revision = store.beginSnapshot()

        assertTrue(
            store.applySnapshot(
                requestedUserIds = listOf("active", "missing"),
                statuses = listOf(MattermostUserStatus("active", "online")),
                startedAtRevision = revision,
            ),
        )

        assertEquals(UserPresence.ACTIVE, store.snapshot()["active"])
        assertEquals(UserPresence.OFFLINE, store.snapshot()["missing"])
        assertTrue(store.missingUserIds(listOf("active", "missing", "new")).contains("new"))
    }

    @Test
    fun `websocket event wins over an older REST snapshot`() {
        val store = PresenceStore()
        val revision = store.beginSnapshot()

        assertTrue(store.applyEvent("user-1", "online"))
        assertFalse(
            store.applySnapshot(
                requestedUserIds = listOf("user-1"),
                statuses = listOf(MattermostUserStatus("user-1", "offline")),
                startedAtRevision = revision,
            ),
        )

        assertEquals(UserPresence.ACTIVE, store.snapshot()["user-1"])
    }

    @Test
    fun `dnd and out of office are represented as away`() {
        val store = PresenceStore()

        assertTrue(store.applyEvent("dnd", "dnd"))
        assertTrue(store.applyEvent("ooo", "ooo"))

        assertEquals(UserPresence.AWAY, store.snapshot()["dnd"])
        assertEquals(UserPresence.AWAY, store.snapshot()["ooo"])
    }
}
