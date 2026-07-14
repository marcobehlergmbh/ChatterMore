package com.example.mattermostlite.data

import com.example.mattermostlite.model.ConnectionState
import com.example.mattermostlite.model.SocketSignal
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
}
