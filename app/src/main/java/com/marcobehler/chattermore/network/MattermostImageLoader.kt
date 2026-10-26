package com.marcobehler.chattermore.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import com.marcobehler.chattermore.model.FeedImage
import com.marcobehler.chattermore.model.AuthenticatedServerConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val linkPreviewTargetSize: Int
    private val memoryCache: LruCache<String, Bitmap>
    private val diskCache = ImageDiskCache(File(context.filesDir, "image_cache"), MAX_DISK_CACHE_BYTES)
    private val diskExecutor = Executors.newFixedThreadPool(DISK_CACHE_THREADS)
    private val videoFrameExecutor = Executors.newFixedThreadPool(VIDEO_FRAME_THREADS)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val inFlight = mutableMapOf<String, PendingLoad>()
    private val failedVideoFrames = mutableSetOf<String>()
    private val videoFrameRetryAt = mutableMapOf<String, Long>()
    private val imageClient = client.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = MAX_NETWORK_REQUESTS
                maxRequestsPerHost = MAX_NETWORK_REQUESTS
            },
        )
        .build()
    private val videoFrameClient = imageClient.newBuilder()
        .callTimeout(VIDEO_FRAME_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
        linkPreviewTargetSize = (LINK_PREVIEW_SIZE_DP * metrics.density).roundToInt().coerceAtLeast(1)
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
            inFlight.values.forEach { it.call?.cancel() }
            inFlight.clear()
            failedVideoFrames.clear()
            videoFrameRetryAt.clear()
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
            diskKey = "${requestConfig.baseUrl}|avatar|$userId",
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
            diskKey = "youtube|$videoId",
            url = url,
            authorization = null,
            accept = "image/*",
            decodeWidth = targetWidth,
            decodeHeight = targetHeight,
            result = result,
        )
    }

    fun loadLinkPreviewImage(imageUrl: String, result: (Bitmap?) -> Unit): ImageRequest {
        val url = imageUrl.toHttpUrlOrNull()
            ?.takeIf { it.scheme == "https" && it.username.isBlank() && it.password.isBlank() }
            ?: return emptyResult(result)
        val requestConfig = config
        val serverUrl = requestConfig?.baseUrl?.toHttpUrlOrNull()
        val authorization = if (
            requestConfig != null &&
            serverUrl != null &&
            url.scheme == serverUrl.scheme &&
            url.host == serverUrl.host &&
            url.port == serverUrl.port
        ) {
            "Bearer ${requestConfig.token}"
        } else {
            null
        }
        return loadUrl(
            key = "link-preview|$imageUrl",
            diskKey = "link-preview|$imageUrl",
            url = url,
            authorization = authorization,
            accept = "image/*",
            decodeWidth = linkPreviewTargetSize,
            decodeHeight = linkPreviewTargetSize,
            maxEncodedBytes = MAX_LINK_PREVIEW_ENCODED_BYTES,
            requireImageContentType = true,
            result = result,
        )
    }

    /**
     * Mattermost only renders previews for images, so video thumbnails are grabbed from the file
     * itself. New frames are only fetched on unmetered networks and within a fixed byte budget;
     * frames already on disk are shown everywhere.
     */
    fun loadVideoThumbnail(fileId: String, result: (Bitmap?) -> Unit): ImageRequest {
        val requestConfig = config ?: return emptyResult(result)
        val url = videoFrameUrl(requestConfig, fileId) ?: return emptyResult(result)
        val key = videoFrameKey(requestConfig, fileId)
        if (isVideoFrameSkipped(key)) return emptyResult(result)
        val authorization = "Bearer ${requestConfig.token}"
        return loadCached(key, key, targetWidth, targetHeight, result) {
            val pending = synchronized(lock) { inFlight[key] } ?: return@loadCached
            if (isNetworkMetered()) {
                complete(key, null, pending)
                return@loadCached
            }
            videoFrameExecutor.execute {
                extractVideoFrame(key, pending, url, authorization, targetWidth, targetHeight)
            }
        }
    }

    /** Whether a missing thumbnail would be fetched now, so callers can reserve space for it. */
    fun canFetchVideoThumbnail(fileId: String): Boolean {
        val requestConfig = config ?: return false
        if (videoFrameUrl(requestConfig, fileId) == null) return false
        return !isVideoFrameSkipped(videoFrameKey(requestConfig, fileId)) && !isNetworkMetered()
    }

    private fun videoFrameUrl(requestConfig: AuthenticatedServerConfig, fileId: String): okhttp3.HttpUrl? {
        if (fileId.isBlank()) return null
        return requestConfig.baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/v4/files")
            .addPathSegment(fileId)
            .build()
            .takeIf { it.scheme == "https" }
    }

    private fun videoFrameKey(requestConfig: AuthenticatedServerConfig, fileId: String) =
        "${requestConfig.baseUrl}|video-frame|$fileId"

    private fun isVideoFrameSkipped(key: String): Boolean = synchronized(lock) {
        key in failedVideoFrames || (videoFrameRetryAt[key] ?: 0L) > SystemClock.elapsedRealtime()
    }

    // Treats "can't tell" as metered: thumbnails are a nicety, mobile data isn't.
    private fun isNetworkMetered(): Boolean = connectivity?.isActiveNetworkMetered != false

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
            diskKey = "${requestConfig.baseUrl}|file|${image.fileId}|${endpoint.orEmpty()}",
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
        diskKey: String,
        url: okhttp3.HttpUrl,
        authorization: String?,
        accept: String,
        decodeWidth: Int,
        decodeHeight: Int,
        maxEncodedBytes: Int = MAX_ENCODED_BYTES,
        requireImageContentType: Boolean = false,
        result: (Bitmap?) -> Unit,
    ): ImageRequest = loadCached(key, diskKey, decodeWidth, decodeHeight, result) {
        startNetworkLoad(
            key = key,
            diskKey = diskKey,
            url = url,
            authorization = authorization,
            accept = accept,
            decodeWidth = decodeWidth,
            decodeHeight = decodeHeight,
            maxEncodedBytes = maxEncodedBytes,
            requireImageContentType = requireImageContentType,
        )
    }

    /** Serves [key] from memory or disk, falling back to [fetch], which must end in [complete]. */
    private fun loadCached(
        key: String,
        diskKey: String,
        decodeWidth: Int,
        decodeHeight: Int,
        result: (Bitmap?) -> Unit,
        fetch: () -> Unit,
    ): ImageRequest {
        synchronized(lock) {
            memoryCache.get(key)?.let {
                result(it)
                return EMPTY_REQUEST
            }
        }

        val subscription = Subscription(key, result)
        var startLoad = false
        synchronized(lock) {
            memoryCache.get(key)?.let {
                result(it)
                return EMPTY_REQUEST
            }
            val pending = inFlight[key]
            if (pending != null) {
                pending.subscriptions += subscription
            } else {
                inFlight[key] = PendingLoad(null, mutableSetOf(subscription))
                startLoad = true
            }
        }
        if (startLoad) {
            diskExecutor.execute {
                val cachedBytes = diskCache.read(diskKey)
                val cachedBitmap = cachedBytes?.let { decodeSampled(it, decodeWidth, decodeHeight) }
                if (cachedBitmap != null) {
                    complete(key, cachedBitmap)
                } else {
                    if (cachedBytes != null) diskCache.remove(diskKey)
                    fetch()
                }
            }
        }
        return subscription
    }

    private fun startNetworkLoad(
        key: String,
        diskKey: String,
        url: okhttp3.HttpUrl,
        authorization: String?,
        accept: String,
        decodeWidth: Int,
        decodeHeight: Int,
        maxEncodedBytes: Int,
        requireImageContentType: Boolean,
    ) {
        val call = imageClient.newCall(
            Request.Builder()
                .url(url)
                .apply { authorization?.let { header("Authorization", it) } }
                .header("Accept", accept)
                .build(),
        )
        synchronized(lock) {
            val pending = inFlight[key] ?: return
            pending.call = call
        }
        call.enqueue(
            ImageCallback(
                key,
                diskKey,
                decodeWidth,
                decodeHeight,
                maxEncodedBytes,
                requireImageContentType,
            ),
        )
    }

    /**
     * Only [pending] may be completed: after configure() or a cancel-and-rebind it has been
     * replaced, and a result fetched for it would leak into a load it doesn't belong to.
     */
    private fun extractVideoFrame(
        key: String,
        pending: PendingLoad,
        url: okhttp3.HttpUrl,
        authorization: String,
        decodeWidth: Int,
        decodeHeight: Int,
    ) {
        val isCurrent = { synchronized(lock) { inFlight[key] === pending } }
        // Every subscriber may have scrolled away while this sat in the queue.
        if (!isCurrent()) return
        val source = RangeMediaDataSource(
            client = videoFrameClient,
            url = url,
            authorization = authorization,
            byteBudget = VIDEO_FRAME_BYTE_BUDGET,
            isWanted = isCurrent,
        )
        val frame = try {
            grabVideoFrame(source, decodeWidth, decodeHeight)
        } catch (_: Exception) {
            null
        } finally {
            source.close()
        }
        if (frame != null) {
            val bytes = ByteArrayOutputStream().use { output ->
                frame.compress(Bitmap.CompressFormat.JPEG, VIDEO_FRAME_JPEG_QUALITY, output)
                output.toByteArray()
            }
            diskCache.write(key, bytes)
            complete(key, frame, pending)
            return
        }
        if (source.cancelled) return
        synchronized(lock) {
            if (inFlight[key] !== pending) return
            if (source.transientFailure) {
                videoFrameRetryAt[key] = SystemClock.elapsedRealtime() + VIDEO_FRAME_RETRY_DELAY_MS
            } else {
                // Undecodable, over budget, or rejected by the server: don't try again this session.
                failedVideoFrames += key
            }
        }
        complete(key, null, pending)
    }

    private fun emptyResult(result: (Bitmap?) -> Unit): ImageRequest {
        result(null)
        return EMPTY_REQUEST
    }

    private fun complete(key: String, bitmap: Bitmap?, expected: PendingLoad? = null) {
        val subscriptions = synchronized(lock) {
            val pending = inFlight[key] ?: return
            if (expected != null && pending !== expected) return
            inFlight.remove(key)
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
        private val diskKey: String,
        private val decodeWidth: Int,
        private val decodeHeight: Int,
        private val maxEncodedBytes: Int,
        private val requireImageContentType: Boolean,
    ) : Callback {
        override fun onFailure(call: Call, e: IOException) = complete(key, null)

        override fun onResponse(call: Call, response: Response) {
            val bytes = try {
                response.use {
                    if (!response.isSuccessful || response.request.url.scheme != "https") return@use null
                    if (requireImageContentType && response.body.contentType()?.type != "image") {
                        return@use null
                    }
                    response.body.readBoundedBytes(maxEncodedBytes)
                }
            } catch (_: IOException) {
                null
            }
            if (bytes == null) {
                complete(key, null)
                return
            }
            diskExecutor.execute {
                diskCache.write(diskKey, bytes)
                complete(key, decodeSampled(bytes, decodeWidth, decodeHeight))
            }
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
                    pending.call?.cancel()
                }
            }
        }
    }

    private inner class PendingLoad(
        var call: Call?,
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
        const val LINK_PREVIEW_SIZE_DP = 96
        const val MAX_MEMORY_CACHE_KB = 32 * 1_024
        const val MAX_DISK_CACHE_BYTES = 128L * 1_024L * 1_024L
        const val DISK_CACHE_THREADS = 2
        const val VIDEO_FRAME_THREADS = 2
        const val VIDEO_FRAME_CALL_TIMEOUT_SECONDS = 30L
        const val VIDEO_FRAME_BYTE_BUDGET = 8L * 1_024L * 1_024L
        const val VIDEO_FRAME_RETRY_DELAY_MS = 60_000L
        const val VIDEO_FRAME_TIME_MS = 1_000L
        const val VIDEO_FRAME_JPEG_QUALITY = 85
        const val MAX_ENCODED_BYTES = 24 * 1_024 * 1_024
        const val MAX_LINK_PREVIEW_ENCODED_BYTES = 6 * 1_024 * 1_024
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

        fun grabVideoFrame(source: MediaDataSource, targetWidth: Int, targetHeight: Int): Bitmap? {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source)
                // Skip ahead a little: the very first frame is often a black fade-in.
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                val timeUs = min(VIDEO_FRAME_TIME_MS, durationMs / 3) * 1_000L
                return retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetWidth,
                    targetHeight,
                )
            } finally {
                runCatching { retriever.release() }
            }
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
