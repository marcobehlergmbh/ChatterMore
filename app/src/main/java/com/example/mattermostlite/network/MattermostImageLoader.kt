package com.example.mattermostlite.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.example.mattermostlite.model.FeedImage
import com.example.mattermostlite.model.AuthenticatedServerConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

/** Small, bounded image pipeline specialized for authenticated Mattermost previews. */
class MattermostImageLoader(
    context: Context,
    client: OkHttpClient,
) {
    @Volatile
    private var config: AuthenticatedServerConfig? = null
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val targetWidth: Int
    private val targetHeight: Int
    private val fullscreenTargetWidth: Int
    private val fullscreenTargetHeight: Int
    private val avatarTargetSize: Int
    private val memoryCache: LruCache<String, Bitmap>
    private val inFlight = mutableMapOf<String, PendingLoad>()
    private val imageClient = client.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = MAX_NETWORK_REQUESTS
                maxRequestsPerHost = MAX_NETWORK_REQUESTS
            },
        )
        .build()

    init {
        val metrics = context.resources.displayMetrics
        val horizontalPadding = (32f * metrics.density).roundToInt()
        targetWidth = min(
            metrics.widthPixels - horizontalPadding,
            (MAX_IMAGE_WIDTH_DP * metrics.density).roundToInt(),
        ).coerceAtLeast(1)
        targetHeight = (MAX_IMAGE_HEIGHT_DP * metrics.density).roundToInt().coerceAtLeast(1)
        fullscreenTargetWidth = metrics.widthPixels.coerceAtLeast(1)
        fullscreenTargetHeight = metrics.heightPixels.coerceAtLeast(1)
        avatarTargetSize = (AVATAR_SIZE_DP * metrics.density).roundToInt().coerceAtLeast(1)
        val cacheKilobytes = min(
            MAX_MEMORY_CACHE_KB,
            (Runtime.getRuntime().maxMemory() / 8L / 1_024L).toInt(),
        )
        memoryCache = object : LruCache<String, Bitmap>(cacheKilobytes.coerceAtLeast(1)) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.allocationByteCount / 1_024).coerceAtLeast(1)
        }
    }

    fun configure(config: AuthenticatedServerConfig?) {
        val previous = this.config
        if (previous == config) return
        this.config = config
        synchronized(lock) {
            inFlight.values.forEach { it.call.cancel() }
            inFlight.clear()
            memoryCache.evictAll()
        }
    }

    fun load(image: FeedImage, result: (Bitmap?) -> Unit): ImageRequest {
        val endpoint = if (image.hasPreviewImage) "preview" else "thumbnail"
        return load(image, endpoint, "feed", targetWidth, targetHeight, result)
    }

    fun loadFullscreen(image: FeedImage, result: (Bitmap?) -> Unit): ImageRequest {
        val endpoint = if (image.hasPreviewImage) "preview" else null
        return load(
            image,
            endpoint,
            "fullscreen",
            fullscreenTargetWidth,
            fullscreenTargetHeight,
            result,
        )
    }

    fun loadAvatar(userId: String, result: (Bitmap?) -> Unit): ImageRequest {
        val requestConfig = config ?: return emptyResult(result)
        if (userId.isBlank()) return emptyResult(result)
        val url = requestConfig.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v4/users")
            .addPathSegment(userId)
            .addPathSegment("image")
            .build()
        return loadUrl(
            key = "${requestConfig.baseUrl}|avatar|$userId",
            url = url,
            authorization = "Bearer ${requestConfig.token}",
            accept = "image/*",
            decodeWidth = avatarTargetSize,
            decodeHeight = avatarTargetSize,
            result = result,
        )
    }

    fun loadYouTubeThumbnail(videoId: String, result: (Bitmap?) -> Unit): ImageRequest {
        if (videoId.isBlank()) return emptyResult(result)
        val url = "https://i.ytimg.com".toHttpUrl().newBuilder()
            .addPathSegment("vi")
            .addPathSegment(videoId)
            .addPathSegment("hqdefault.jpg")
            .build()
        return loadUrl(
            key = "youtube|$videoId",
            url = url,
            authorization = null,
            accept = "image/*",
            decodeWidth = targetWidth,
            decodeHeight = targetHeight,
            result = result,
        )
    }

    fun mediaSource(fileId: String): MattermostMediaSource? {
        val requestConfig = config ?: return null
        if (fileId.isBlank()) return null
        val url = requestConfig.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v4/files")
            .addPathSegment(fileId)
            .build()
        return MattermostMediaSource(
            uri = Uri.parse(url.toString()),
            headers = mapOf("Authorization" to "Bearer ${requestConfig.token}"),
        )
    }

    private fun load(
        image: FeedImage,
        endpoint: String?,
        cacheVariant: String,
        decodeWidth: Int,
        decodeHeight: Int,
        result: (Bitmap?) -> Unit,
    ): ImageRequest {
        val requestConfig = config ?: return emptyResult(result)
        val key = "${requestConfig.baseUrl}|$cacheVariant|${image.fileId}"
        val url = requestConfig.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v4/files")
            .addPathSegment(image.fileId)
            .apply { endpoint?.let(::addPathSegment) }
            .build()
        return loadUrl(
            key = key,
            url = url,
            authorization = "Bearer ${requestConfig.token}",
            accept = "image/*",
            decodeWidth = decodeWidth,
            decodeHeight = decodeHeight,
            result = result,
        )
    }

    private fun loadUrl(
        key: String,
        url: okhttp3.HttpUrl,
        authorization: String?,
        accept: String,
        decodeWidth: Int,
        decodeHeight: Int,
        result: (Bitmap?) -> Unit,
    ): ImageRequest {
        synchronized(lock) {
            memoryCache.get(key)?.let {
                result(it)
                return EMPTY_REQUEST
            }
        }

        val subscription = Subscription(key, result)
        var callToStart: Call? = null
        synchronized(lock) {
            memoryCache.get(key)?.let {
                result(it)
                return EMPTY_REQUEST
            }
            val pending = inFlight[key]
            if (pending != null) {
                pending.subscriptions += subscription
            } else {
                val call = imageClient.newCall(
                    Request.Builder()
                        .url(url)
                        .apply { authorization?.let { header("Authorization", it) } }
                        .header("Accept", accept)
                        .build(),
                )
                inFlight[key] = PendingLoad(call, mutableSetOf(subscription))
                callToStart = call
            }
        }
        callToStart?.enqueue(ImageCallback(key, decodeWidth, decodeHeight))
        return subscription
    }

    private fun emptyResult(result: (Bitmap?) -> Unit): ImageRequest {
        result(null)
        return EMPTY_REQUEST
    }

    private fun complete(key: String, bitmap: Bitmap?) {
        val subscriptions = synchronized(lock) {
            val pending = inFlight.remove(key) ?: return
            if (bitmap != null) memoryCache.put(key, bitmap)
            pending.subscriptions.toList()
        }
        mainHandler.post {
            subscriptions.forEach { subscription ->
                if (subscription.active.compareAndSet(true, false)) {
                    subscription.result(bitmap)
                }
            }
        }
    }

    private inner class ImageCallback(
        private val key: String,
        private val decodeWidth: Int,
        private val decodeHeight: Int,
    ) : Callback {
        override fun onFailure(call: Call, e: IOException) = complete(key, null)

        override fun onResponse(call: Call, response: Response) {
            val bitmap = response.use {
                if (!response.isSuccessful) return@use null
                val bytes = response.body.readBoundedBytes(MAX_ENCODED_BYTES) ?: return@use null
                decodeSampled(bytes, decodeWidth, decodeHeight)
            }
            complete(key, bitmap)
        }
    }

    private inner class Subscription(
        private val key: String,
        val result: (Bitmap?) -> Unit,
    ) : ImageRequest {
        val active = AtomicBoolean(true)

        override fun cancel() {
            if (!active.compareAndSet(true, false)) return
            synchronized(lock) {
                val pending = inFlight[key] ?: return
                pending.subscriptions.remove(this)
                if (pending.subscriptions.isEmpty()) {
                    inFlight.remove(key)
                    pending.call.cancel()
                }
            }
        }
    }

    private inner class PendingLoad(
        val call: Call,
        val subscriptions: MutableSet<Subscription>,
    )

    fun interface ImageRequest {
        fun cancel()
    }

    private companion object {
        const val MAX_NETWORK_REQUESTS = 4
        const val MAX_IMAGE_WIDTH_DP = 400
        const val MAX_IMAGE_HEIGHT_DP = 300
        const val AVATAR_SIZE_DP = 40
        const val MAX_MEMORY_CACHE_KB = 32 * 1_024
        const val MAX_ENCODED_BYTES = 24 * 1_024 * 1_024
        val EMPTY_REQUEST = ImageRequest {}

        fun decodeSampled(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (
                bounds.outWidth / (sampleSize * 2) >= targetWidth &&
                bounds.outHeight / (sampleSize * 2) >= targetHeight
            ) {
                sampleSize *= 2
            }
            val sampledWidth = (bounds.outWidth / sampleSize).coerceAtLeast(1)
            val sampledHeight = (bounds.outHeight / sampleSize).coerceAtLeast(1)
            val scale = minOf(
                1f,
                targetWidth.toFloat() / sampledWidth,
                targetHeight.toFloat() / sampledHeight,
            )
            return BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    if (scale < 1f) {
                        inScaled = true
                        inDensity = sampledWidth
                        inTargetDensity = (sampledWidth * scale).roundToInt().coerceAtLeast(1)
                    }
                },
            )
        }

        fun okhttp3.ResponseBody.readBoundedBytes(limit: Int): ByteArray? {
            val length = contentLength()
            if (length > limit) return null
            val initialSize = when {
                length in 1..limit.toLong() -> length.toInt()
                else -> 8 * 1_024
            }
            return byteStream().use { input ->
                val output = ByteArrayOutputStream(initialSize)
                val buffer = ByteArray(16 * 1_024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) return null
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }
}

data class MattermostMediaSource(
    val uri: Uri,
    val headers: Map<String, String>,
)
