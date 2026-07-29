package com.marcobehler.chattermore.data

import com.marcobehler.chattermore.model.ConnectionState
import com.marcobehler.chattermore.model.SocketCursor
import com.marcobehler.chattermore.model.SocketSignal

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
