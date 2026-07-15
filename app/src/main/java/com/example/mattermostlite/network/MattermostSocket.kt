package com.example.mattermostlite.network

import com.example.mattermostlite.model.AuthenticatedServerConfig
import com.example.mattermostlite.model.SocketCursor
import com.example.mattermostlite.model.SocketSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean

class MattermostSocket(
    private val client: OkHttpClient,
) {
    fun signals(
        config: AuthenticatedServerConfig,
        cursor: SocketCursor?,
    ): Flow<SocketSignal> = callbackFlow {
        val closed = AtomicBoolean(false)
        val url = config.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v4/websocket")
            .apply {
                cursor?.let {
                    addQueryParameter("connection_id", it.connectionId)
                    addQueryParameter("sequence_number", it.nextSequence.toString())
                }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                trySend(SocketSignal.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseSignal(text)?.let { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (closed.compareAndSet(false, true)) {
                    trySend(SocketSignal.Disconnected(null))
                    close()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed.compareAndSet(false, true)) {
                    trySend(SocketSignal.Disconnected(t))
                    close(t)
                }
            }
        }
        val socket = client.newWebSocket(request, listener)
        awaitClose {
            closed.set(true)
            socket.close(1000, "App left foreground")
        }
    }

    internal fun parseSignal(text: String): SocketSignal? = runCatching {
        val envelope = mattermostJson.parseToJsonElement(text).jsonObject
        val event = envelope.string("event")
        val sequence = envelope.long("seq")
        when (event) {
            "hello" -> {
                val data = envelope["data"]?.jsonObject ?: return null
                SocketSignal.Hello(
                    connectionId = data.string("connection_id"),
                    sequence = sequence,
                )
            }

            "posted", "post_edited", "post_deleted" -> {
                val data = envelope["data"]?.jsonObject ?: return null
                val encodedPost = data["post"]?.jsonPrimitive?.content.orEmpty()
                if (encodedPost.isBlank()) return null
                val post = parsePost(encodedPost)
                val broadcastChannel = envelope["broadcast"]
                    ?.jsonObject
                    ?.string("channel_id")
                    .orEmpty()
                SocketSignal.PostEvent(
                    event = event,
                    post = post,
                    channelId = broadcastChannel.ifBlank { post.channelId },
                    sequence = sequence,
                )
            }

            "status_change" -> {
                val data = envelope["data"]?.jsonObject ?: return null
                val userId = data.string("user_id")
                if (userId.isBlank()) return null
                SocketSignal.StatusChange(
                    userId = userId,
                    status = data.string("status"),
                    sequence = sequence,
                )
            }

            else -> SocketSignal.Sequence(sequence)
        }
    }.getOrNull()
}
