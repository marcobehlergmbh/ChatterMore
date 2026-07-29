package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.ConnectionState
import com.marcobehler.chattermore.model.SocketCursor
import com.marcobehler.chattermore.model.SocketSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class SocketConnectionStateTest {
    @Test
    fun `socket open and inbound events prove the connection is live`() {
        assertEquals(ConnectionState.CONNECTED, SocketSignal.Opened.connectionStateAfterReceipt())
        assertEquals(
            ConnectionState.CONNECTED,
            SocketSignal.Sequence(7).connectionStateAfterReceipt(),
        )
    }

    @Test
    fun `disconnect signal reports offline`() {
        assertEquals(
            ConnectionState.OFFLINE,
            SocketSignal.Disconnected(null).connectionStateAfterReceipt(),
        )
    }

    @Test
    fun `post sequences advance even when the post belongs to another channel`() {
        val update = advanceSocketCursor(
            SocketCursor(connectionId = "connection", nextSequence = 7),
            sequence = 7,
        )

        assertEquals(SocketCursor("connection", 8), update.cursor)
        assertEquals(false, update.sequenceGap)
    }

    @Test
    fun `sequence gaps are detected while still advancing the cursor`() {
        val update = advanceSocketCursor(
            SocketCursor(connectionId = "connection", nextSequence = 7),
            sequence = 9,
        )

        assertEquals(SocketCursor("connection", 10), update.cursor)
        assertEquals(true, update.sequenceGap)
    }
}
