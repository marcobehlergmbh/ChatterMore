package com.example.mattermostlite.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewportTest {
    @Test
    fun `fit state centers an image without cropping`() {
        val transform = ImageViewport.fromState(
            viewWidth = 400,
            viewHeight = 400,
            imageWidth = 1_000,
            imageHeight = 500,
            state = ImageZoomState(),
        )

        assertFloatEquals(0.4f, transform.scale)
        assertFloatEquals(0f, transform.translationX)
        assertFloatEquals(100f, transform.translationY)
    }

    @Test
    fun `zoom keeps the touched image point under the gesture focus`() {
        val fitted = ImageViewport.fromState(
            viewWidth = 400,
            viewHeight = 400,
            imageWidth = 1_000,
            imageHeight = 500,
            state = ImageZoomState(),
        )

        val zoomed = ImageViewport.zoomAround(
            viewWidth = 400,
            viewHeight = 400,
            imageWidth = 1_000,
            imageHeight = 500,
            current = fitted,
            zoom = 2f,
            focusX = 100f,
            focusY = 200f,
        )

        assertFloatEquals(0.8f, zoomed.scale)
        assertFloatEquals(-100f, zoomed.translationX)
        assertFloatEquals(0f, zoomed.translationY)
    }

    @Test
    fun `pan stops at image edges`() {
        val zoomed = ImageViewport.fromState(
            viewWidth = 400,
            viewHeight = 400,
            imageWidth = 1_000,
            imageHeight = 500,
            state = ImageZoomState(zoom = 2f),
        )

        val panned = ImageViewport.pan(
            viewWidth = 400,
            viewHeight = 400,
            imageWidth = 1_000,
            imageHeight = 500,
            current = zoomed,
            deltaX = 10_000f,
            deltaY = -10_000f,
        )

        assertFloatEquals(0f, panned.translationX)
        assertFloatEquals(0f, panned.translationY)
    }

    @Test
    fun `normalized viewport restores after screen rotation`() {
        val originalState = ImageZoomState(
            zoom = 3f,
            centerXFraction = 0.7f,
            centerYFraction = 0.5f,
        )
        val landscape = ImageViewport.fromState(
            viewWidth = 800,
            viewHeight = 400,
            imageWidth = 1_000,
            imageHeight = 800,
            state = originalState,
        )
        val savedState = ImageViewport.toState(
            viewWidth = 800,
            viewHeight = 400,
            imageWidth = 1_000,
            imageHeight = 800,
            transform = landscape,
        )

        val portrait = ImageViewport.fromState(
            viewWidth = 400,
            viewHeight = 800,
            imageWidth = 1_000,
            imageHeight = 800,
            state = savedState,
        )
        val restoredState = ImageViewport.toState(
            viewWidth = 400,
            viewHeight = 800,
            imageWidth = 1_000,
            imageHeight = 800,
            transform = portrait,
        )

        assertFloatEquals(originalState.zoom, restoredState.zoom)
        assertFloatEquals(originalState.centerXFraction, restoredState.centerXFraction)
        assertFloatEquals(originalState.centerYFraction, restoredState.centerYFraction)
    }

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.0001)
    }
}
