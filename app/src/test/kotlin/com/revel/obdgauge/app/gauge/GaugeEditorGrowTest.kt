package com.revel.obdgauge.app.gauge

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OBD-77: the pure grow-from-the-tile interpolation behind the floating editor card's open/close
 * motion. Asserted here rather than through a Compose harness for the same reason `GridMetrics`'
 * own pixel math is — the endpoints (`p = 0` lands exactly on the tile, `p = 1` exactly on the
 * resting card) are the contract that makes the animation read as "this card IS that tile," and
 * they're plain arithmetic.
 */
class GaugeEditorGrowTest {
    private val tile = Rect(left = 100f, top = 200f, right = 200f, bottom = 260f)
    private val card = Rect(left = 40f, top = 60f, right = 440f, bottom = 360f)

    @Test
    fun `at progress zero the card maps exactly onto the tile it grew out of`() {
        val t = editorGrowTransform(tile, card, 0f)
        // A center-origin scale of (tileW/cardW, tileH/cardH) plus the center delta reproduces the
        // tile's own rect, which is what "expand in place" means visually.
        assertEquals(tile.width / card.width, t.scaleX, TOLERANCE)
        assertEquals(tile.height / card.height, t.scaleY, TOLERANCE)
        assertEquals(tile.center.x - card.center.x, t.translationX, TOLERANCE)
        assertEquals(tile.center.y - card.center.y, t.translationY, TOLERANCE)
    }

    @Test
    fun `at progress one the card sits untransformed at its resting bounds`() {
        val t = editorGrowTransform(tile, card, 1f)
        assertEquals(1f, t.scaleX, TOLERANCE)
        assertEquals(1f, t.scaleY, TOLERANCE)
        assertEquals(0f, t.translationX, TOLERANCE)
        assertEquals(0f, t.translationY, TOLERANCE)
    }

    @Test
    fun `halfway is the midpoint of both the scale and the offset`() {
        val t = editorGrowTransform(tile, card, 0.5f)
        assertEquals((tile.width / card.width + 1f) / 2f, t.scaleX, TOLERANCE)
        assertEquals((tile.center.y - card.center.y) / 2f, t.translationY, TOLERANCE)
    }

    @Test
    fun `progress is clamped, so an overshooting spring can never invert the card`() {
        assertEquals(editorGrowTransform(tile, card, 1f), editorGrowTransform(tile, card, 1.4f))
        assertEquals(editorGrowTransform(tile, card, 0f), editorGrowTransform(tile, card, -0.3f))
    }

    @Test
    fun `an unmeasured card falls back to the identity instead of collapsing to a point`() {
        // Rest bounds are Rect.Zero for exactly one frame, before the card's first layout reports
        // them — dividing by that would send the card to the screen origin at scale 0/NaN.
        val t = editorGrowTransform(tile, Rect.Zero, 0f)
        assertEquals(1f, t.scaleX, TOLERANCE)
        assertEquals(0f, t.translationX, TOLERANCE)
    }

    @Test
    fun `a tile with no captured bounds simply fades in at rest`() {
        val t = editorGrowTransform(Rect.Zero, card, 0f)
        assertEquals(1f, t.scaleX, TOLERANCE)
        assertEquals(1f, t.scaleY, TOLERANCE)
        assertEquals(0f, t.translationY, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
