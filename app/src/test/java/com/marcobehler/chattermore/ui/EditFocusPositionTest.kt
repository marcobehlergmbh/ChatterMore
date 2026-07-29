package com.marcobehler.chattermore.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EditFocusPositionTest {
    @Test
    fun `keeps the tapped row at the same position when it still fits`() {
        assertEquals(
            180,
            editFocusTopOffset(
                originalTop = 180,
                itemHeight = 96,
                viewportHeight = 480,
                paddingTop = 8,
                paddingBottom = 8,
            ),
        )
    }

    @Test
    fun `moves the tapped row only enough to remain visible above the keyboard`() {
        assertEquals(
            276,
            editFocusTopOffset(
                originalTop = 410,
                itemHeight = 96,
                viewportHeight = 380,
                paddingTop = 8,
                paddingBottom = 8,
            ),
        )
    }

    @Test
    fun `preserves a row that was already partially clipped at the top`() {
        assertEquals(
            -24,
            editFocusTopOffset(
                originalTop = -24,
                itemHeight = 120,
                viewportHeight = 380,
                paddingTop = 8,
                paddingBottom = 8,
            ),
        )
    }

    @Test
    fun `keyboard inset rounding can only move the row in one direction`() {
        val first = nextEditFocusTop(previousTargetTop = 280, calculatedTargetTop = 276)
        val roundedBack = nextEditFocusTop(previousTargetTop = first, calculatedTargetTop = 278)
        val nextShrink = nextEditFocusTop(previousTargetTop = roundedBack, calculatedTargetTop = 274)

        assertEquals(276, first)
        assertEquals(276, roundedBack)
        assertEquals(274, nextShrink)
    }
}
