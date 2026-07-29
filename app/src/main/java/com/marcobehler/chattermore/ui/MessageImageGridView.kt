package com.marcobehler.chattermore.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.marcobehler.chattermore.model.FeedImage
import com.marcobehler.chattermore.network.MattermostImageLoader
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToInt

/** Draws an image message with one canvas-backed view instead of a nested RecyclerView. */
class MessageImageGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val gap = (4f * density).roundToInt()
    private val maxSingleHeight = (300f * density).roundToInt()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val placeholderPaint = Paint().apply { color = Color.argb(20, 128, 128, 128) }
    private val destinationRect = RectF()
    private val fittedRect = RectF()
    private val sourceRect = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var loader: MattermostImageLoader? = null
    private var images: List<FeedImage> = emptyList()
    private var bitmaps: Array<Bitmap?> = emptyArray()
    private val requests = mutableListOf<MattermostImageLoader.ImageRequest>()
    private var generation = 0
    private var downX = 0f
    private var downY = 0f
    private var clickIndex = 0
    var onImageClick: ((FeedImage) -> Unit)? = null

    fun setImages(loader: MattermostImageLoader, images: List<FeedImage>) {
        if (this.loader === loader && this.images == images) {
            if (isAttachedToWindow) startMissingLoads()
            return
        }
        cancelLoads()
        generation++
        this.loader = loader
        this.images = images
        isClickable = images.isNotEmpty()
        bitmaps = arrayOfNulls(images.size)
        contentDescription = images.joinToString { it.name.ifBlank { "Image" } }
        requestLayout()
        invalidate()
        if (isAttachedToWindow) startMissingLoads()
    }

    fun clearImages() {
        if (images.isEmpty()) return
        cancelLoads()
        generation++
        images = emptyList()
        isClickable = false
        bitmaps = emptyArray()
        contentDescription = null
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMissingLoads()
    }

    override fun onDetachedFromWindow() {
        cancelLoads()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = when (images.size) {
            0 -> 0
            1 -> {
                val image = images[0]
                val aspect = if (image.width > 0 && image.height > 0) {
                    image.width.toFloat() / image.height
                } else {
                    DEFAULT_ASPECT
                }
                min(maxSingleHeight, (width / aspect).roundToInt())
            }
            else -> {
                val tileWidth = (width - gap) / 2
                val rows = (images.size + 1) / 2
                rows * tileWidth + (rows - 1) * gap
            }
        }
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (images.isEmpty()) return
        if (images.size == 1) {
            destinationRect.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRect(destinationRect, placeholderPaint)
            bitmaps[0]?.let { drawFitCenter(canvas, it, destinationRect) }
            return
        }

        val tileWidth = (width - gap) / 2
        var index = 0
        while (index < images.size) {
            val column = index % 2
            val row = index / 2
            val left = column * (tileWidth + gap)
            val top = row * (tileWidth + gap)
            destinationRect.set(
                left.toFloat(),
                top.toFloat(),
                (left + tileWidth).toFloat(),
                (top + tileWidth).toFloat(),
            )
            canvas.drawRect(destinationRect, placeholderPaint)
            bitmaps[index]?.let { drawCenterCrop(canvas, it, destinationRect) }
            index++
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (images.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    isPressed = false
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasClick = isPressed
                isPressed = false
                if (wasClick) {
                    imageIndexAt(event.x, event.y)?.let {
                        clickIndex = it
                        performClick()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        val image = images.getOrNull(clickIndex) ?: images.firstOrNull() ?: return false
        onImageClick?.invoke(image)
        return true
    }

    private fun imageIndexAt(x: Float, y: Float): Int? {
        if (images.size == 1) return 0
        val tileWidth = (width - gap) / 2
        val stride = tileWidth + gap
        val column = (x / stride).toInt()
        val row = (y / stride).toInt()
        if (column !in 0..1 || x - column * stride >= tileWidth || y - row * stride >= tileWidth) {
            return null
        }
        return (row * 2 + column).takeIf { it in images.indices }
    }

    private fun startMissingLoads() {
        val imageLoader = loader ?: return
        if (requests.isNotEmpty()) return
        val loadGeneration = generation
        images.forEachIndexed { index, image ->
            if (bitmaps[index] != null) return@forEachIndexed
            requests += imageLoader.load(image) { bitmap ->
                if (generation == loadGeneration && index < bitmaps.size) {
                    bitmaps[index] = bitmap
                    invalidate()
                }
            }
        }
    }

    private fun cancelLoads() {
        requests.forEach(MattermostImageLoader.ImageRequest::cancel)
        requests.clear()
    }

    private fun drawFitCenter(canvas: Canvas, bitmap: Bitmap, bounds: RectF) {
        val scale = min(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        fittedRect.set(
            bounds.centerX() - drawWidth / 2f,
            bounds.centerY() - drawHeight / 2f,
            bounds.centerX() + drawWidth / 2f,
            bounds.centerY() + drawHeight / 2f,
        )
        canvas.drawBitmap(bitmap, null, fittedRect, bitmapPaint)
    }

    private fun drawCenterCrop(canvas: Canvas, bitmap: Bitmap, destination: RectF) {
        val sourceAspect = bitmap.width.toFloat() / bitmap.height
        val destinationAspect = destination.width() / destination.height()
        if (sourceAspect > destinationAspect) {
            val sourceWidth = (bitmap.height * destinationAspect).roundToInt()
            val left = (bitmap.width - sourceWidth) / 2
            sourceRect.set(left, 0, left + sourceWidth, bitmap.height)
        } else {
            val sourceHeight = (bitmap.width / destinationAspect).roundToInt()
            val top = (bitmap.height - sourceHeight) / 2
            sourceRect.set(0, top, bitmap.width, top + sourceHeight)
        }
        canvas.drawBitmap(bitmap, sourceRect, destination, bitmapPaint)
    }

    private companion object {
        const val DEFAULT_ASPECT = 4f / 3f
    }
}
