package com.example.mattermostlite.data

import com.example.mattermostlite.model.ConnectionState
import com.example.mattermostlite.model.SocketCursor
import com.example.mattermostlite.model.SocketSignal

internal fun SocketSignal.connectionStateAfterReceipt(): ConnectionState =
    if (this is SocketSignal.Disconnected) ConnectionState.OFFLINE else ConnectionState.CONNECTED

internal data class SocketCursorUpdate(
    val cursor: SocketCursor?,
    val sequenceGap: Boolean,
)

internal fun advanceSocketCursor(cursor: SocketCursor?, sequence: Long): SocketCursorUpdate {
    if (cursor == null || sequence <= 0L) return SocketCursorUpdate(cursor, false)
    return SocketCursorUpdate(
        cursor = cursor.copy(nextSequence = sequence + 1),
        sequenceGap = cursor.nextSequence != sequence,
    )
}
