package com.marcobehler.chattermore.ui

import kotlin.math.min

data class ImageZoomState(
    val zoom: Float = 1f,
    val centerXFraction: Float = 0.5f,
    val centerYFraction: Float = 0.5f,
)

internal data class ImageTransform(
    val baseScale: Float,
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
)

internal object ImageViewport {
    fun fromState(
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        state: ImageZoomState,
    ): ImageTransform {
        val baseScale = min(
            viewWidth.toFloat() / imageWidth,
            viewHeight.toFloat() / imageHeight,
        )
        val scale = baseScale * state.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val translationX = viewWidth / 2f -
            state.centerXFraction.coerceIn(0f, 1f) * imageWidth * scale
        val translationY = viewHeight / 2f -
            state.centerYFraction.coerceIn(0f, 1f) * imageHeight * scale
        return clamp(
            viewWidth,
            viewHeight,
            imageWidth,
            imageHeight,
            ImageTransform(baseScale, scale, translationX, translationY),
        )
    }

    fun zoomAround(
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        current: ImageTransform,
        zoom: Float,
        focusX: Float,
        focusY: Float,
    ): ImageTransform {
        val imageFocusX = (focusX - current.translationX) / current.scale
        val imageFocusY = (focusY - current.translationY) / current.scale
        val scale = current.baseScale * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        return clamp(
            viewWidth,
            viewHeight,
            imageWidth,
            imageHeight,
            ImageTransform(
                baseScale = current.baseScale,
                scale = scale,
                translationX = focusX - imageFocusX * scale,
                translationY = focusY - imageFocusY * scale,
            ),
        )
    }

    fun pan(
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        current: ImageTransform,
        deltaX: Float,
        deltaY: Float,
    ): ImageTransform = clamp(
        viewWidth,
        viewHeight,
        imageWidth,
        imageHeight,
        current.copy(
            translationX = current.translationX + deltaX,
            translationY = current.translationY + deltaY,
        ),
    )

    fun toState(
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        transform: ImageTransform,
    ): ImageZoomState = ImageZoomState(
        zoom = (transform.scale / transform.baseScale).coerceIn(MIN_ZOOM, MAX_ZOOM),
        centerXFraction = (
            (viewWidth / 2f - transform.translationX) / transform.scale / imageWidth
            ).coerceIn(0f, 1f),
        centerYFraction = (
            (viewHeight / 2f - transform.translationY) / transform.scale / imageHeight
            ).coerceIn(0f, 1f),
    )

    private fun clamp(
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        transform: ImageTransform,
    ): ImageTransform {
        val scaledWidth = imageWidth * transform.scale
        val scaledHeight = imageHeight * transform.scale
        val translationX = if (scaledWidth <= viewWidth) {
            (viewWidth - scaledWidth) / 2f
        } else {
            transform.translationX.coerceIn(viewWidth - scaledWidth, 0f)
        }
        val translationY = if (scaledHeight <= viewHeight) {
            (viewHeight - scaledHeight) / 2f
        } else {
            transform.translationY.coerceIn(viewHeight - scaledHeight, 0f)
        }
        return transform.copy(translationX = translationX, translationY = translationY)
    }

    const val MIN_ZOOM = 1f
    const val MAX_ZOOM = 5f
}
