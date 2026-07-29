package com.marcobehler.chattermore.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {
    private val transformMatrix = Matrix()
    private var transform: ImageTransform? = null
    private var zoomState = ImageZoomState()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = hasImage()

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = transform ?: return false
                val requestedZoom = current.zoom * detector.scaleFactor
                applyTransform(
                    ImageViewport.zoomAround(
                        width,
                        height,
                        imageWidth,
                        imageHeight,
                        current,
                        requestedZoom,
                        detector.focusX,
                        detector.focusY,
                    ),
                )
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scaleDetector.isInProgress) return false
                val currentTransform = transform ?: return false
                if (currentTransform.zoom <= ImageViewport.MIN_ZOOM) return false
                applyTransform(
                    ImageViewport.pan(
                        width,
                        height,
                        imageWidth,
                        imageHeight,
                        currentTransform,
                        -distanceX,
                        -distanceY,
                    ),
                )
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                val current = transform ?: return false
                if (current.zoom > ImageViewport.MIN_ZOOM) {
                    zoomState = ImageZoomState()
                    applyZoomState()
                } else {
                    applyTransform(
                        ImageViewport.zoomAround(
                            width,
                            height,
                            imageWidth,
                            imageHeight,
                            current,
                            DOUBLE_TAP_ZOOM,
                            event.x,
                            event.y,
                        ),
                    )
                }
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean = performClick()
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun setZoomableBitmap(bitmap: Bitmap) {
        super.setImageBitmap(bitmap)
        applyZoomState()
    }

    fun restoreZoomState(state: ImageZoomState) {
        zoomState = state
        applyZoomState()
    }

    fun currentZoomState(): ImageZoomState = zoomState

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        applyZoomState()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasImage()) return super.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(
            scaleDetector.isInProgress || transform?.zoom.orDefault() > ImageViewport.MIN_ZOOM,
        )
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun applyZoomState() {
        if (!hasImage() || width == 0 || height == 0) return
        applyTransform(
            ImageViewport.fromState(
                width,
                height,
                imageWidth,
                imageHeight,
                zoomState,
            ),
        )
    }

    private fun applyTransform(value: ImageTransform) {
        transform = value
        transformMatrix.setScale(value.scale, value.scale)
        transformMatrix.postTranslate(value.translationX, value.translationY)
        imageMatrix = transformMatrix
        zoomState = ImageViewport.toState(
            width,
            height,
            imageWidth,
            imageHeight,
            value,
        )
    }

    private fun hasImage(): Boolean =
        drawable != null && imageWidth > 0 && imageHeight > 0 && width > 0 && height > 0

    private val imageWidth: Int
        get() = drawable?.intrinsicWidth ?: 0

    private val imageHeight: Int
        get() = drawable?.intrinsicHeight ?: 0

    private val ImageTransform.zoom: Float
        get() = scale / baseScale

    private fun Float?.orDefault(): Float = this ?: ImageViewport.MIN_ZOOM

    private companion object {
        const val DOUBLE_TAP_ZOOM = 2.5f
    }
}
