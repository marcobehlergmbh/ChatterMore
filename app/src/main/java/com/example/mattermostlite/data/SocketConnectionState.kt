package com.example.mattermostlite.data

import com.example.mattermostlite.model.ConnectionState
import com.example.mattermostlite.model.SocketSignal

internal fun SocketSignal.connectionStateAfterReceipt(): ConnectionState =
    if (this is SocketSignal.Disconnected) ConnectionState.OFFLINE else ConnectionState.CONNECTED
