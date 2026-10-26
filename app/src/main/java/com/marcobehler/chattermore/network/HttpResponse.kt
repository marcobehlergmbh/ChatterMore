package com.marcobehler.chattermore.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/** The body is still a live network stream when OkHttp calls onResponse. */
internal suspend fun <T> Call.readResponse(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        if (!continuation.isActive) return
                        Result.success(read(it))
                    }
                } catch (error: Exception) {
                    Result.failure(error)
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }
