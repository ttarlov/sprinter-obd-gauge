package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBD-73 "dumb mode": boost's EXCITEMENT face mapping (`boostFaceShape` in `FaceGauge.kt`) — the
 * bug-eye scale and grin curvature as boost climbs. Boost is NEUTRAL (no threshold band), so this
 * is the one FACE gauge with no mood to read; the eyes ARE the readout, which makes the
 * value→eyeScale ramp load-bearing rather than decorative.
 *
 * Expected values come from the mapping's own `internal` constants ([BOOST_FACE_MAX_PSI],
 * [BOOST_EYE_SCALE_MIN]/[BOOST_EYE_SCALE_MAX], [BOOST_MOUTH_CURVE_MIN]/[BOOST_MOUTH_CURVE_MAX])
 * rather than re-typed numbers, so a deliberate retune moves the test with the source and only a
 * broken *shape* of the ramp (endpoints, clamping, direction) fails.
 */
class BoostFaceShapeTest {
    // The real seeded boost sweep: 0-25 psi, i.e. wider than the face's own 18 psi cap.
    private val boostScale = GaugeScaleDefaults.forId(PidIds.BOOST)

    @Test
    fun `boostFaceShape sits at the minimum eye and grin with no boost`() {
        val shape = boostFaceShape(0.0, boostScale)
        assertEquals(BOOST_EYE_SCALE_MIN, shape.eyeScale, 0f)
        assertEquals(BOOST_MOUTH_CURVE_MIN, shape.mouthCurve, 0f)
    }

    @Test
    fun `boostFaceShape is barely off the minimum at idle boost`() {
        // ~2 psi is idle/off-boost cruise: the eyes should be visibly at rest, not part-way bugged.
        val idle = boostFaceShape(IDLE_PSI, boostScale)
        val span = BOOST_EYE_SCALE_MAX - BOOST_EYE_SCALE_MIN
        assertTrue("idle eyeScale ${idle.eyeScale} should be above the floor", idle.eyeScale > BOOST_EYE_SCALE_MIN)
        assertTrue(
            "idle eyeScale ${idle.eyeScale} should be within the bottom fifth of the ramp",
            idle.eyeScale < BOOST_EYE_SCALE_MIN + span / 5f,
        )
    }

    @Test
    fun `boostFaceShape is exactly half way up the ramp at half the cap`() {
        val half = boostFaceShape(BOOST_FACE_MAX_PSI / 2.0, boostScale)
        assertEquals((BOOST_EYE_SCALE_MIN + BOOST_EYE_SCALE_MAX) / 2f, half.eyeScale, TOLERANCE)
        assertEquals((BOOST_MOUTH_CURVE_MIN + BOOST_MOUTH_CURVE_MAX) / 2f, half.mouthCurve, TOLERANCE)
    }

    @Test
    fun `boostFaceShape is fully bugged at the peak and stays capped above it`() {
        val peak = boostFaceShape(BOOST_FACE_MAX_PSI, boostScale)
        assertEquals(BOOST_EYE_SCALE_MAX, peak.eyeScale, TOLERANCE)
        assertEquals(BOOST_MOUTH_CURVE_MAX, peak.mouthCurve, TOLERANCE)
        // Past the cap (and past the scale's own 25 psi ceiling) the face can't get any more excited.
        assertEquals(BOOST_EYE_SCALE_MAX, boostFaceShape(BOOST_FACE_MAX_PSI + 1.0, boostScale).eyeScale, TOLERANCE)
        assertEquals(BOOST_EYE_SCALE_MAX, boostFaceShape(boostScale.max, boostScale).eyeScale, TOLERANCE)
        assertEquals(BOOST_EYE_SCALE_MAX, boostFaceShape(999.0, boostScale).eyeScale, TOLERANCE)
    }

    @Test
    fun `boostFaceShape clamps vacuum to the resting face`() {
        // The speed-density estimate can read below zero (manifold vacuum off-throttle).
        assertEquals(BOOST_EYE_SCALE_MIN, boostFaceShape(-5.0, boostScale).eyeScale, 0f)
        assertEquals(BOOST_MOUTH_CURVE_MIN, boostFaceShape(-5.0, boostScale).mouthCurve, 0f)
    }

    @Test
    fun `boostFaceShape grows monotonically with boost`() {
        val scales = listOf(0.0, 2.0, 6.0, 12.0, BOOST_FACE_MAX_PSI).map { boostFaceShape(it, boostScale).eyeScale }
        scales.zipWithNext { lower, higher ->
            assertTrue("eyeScale should not shrink as boost rises: $scales", higher > lower)
        }
    }

    @Test
    fun `boostFaceShape caps on the gauge's own ceiling when it is narrower than the face cap`() {
        // A user-narrowed boost scale (10 psi full sweep) should bug the eyes fully at ITS top end.
        val narrow = GaugeScale(min = 0.0, max = 10.0, tick = 5.0)
        assertEquals(BOOST_EYE_SCALE_MAX, boostFaceShape(10.0, narrow).eyeScale, TOLERANCE)
        assertEquals((BOOST_EYE_SCALE_MIN + BOOST_EYE_SCALE_MAX) / 2f, boostFaceShape(5.0, narrow).eyeScale, TOLERANCE)
    }

    @Test
    fun `boostFaceShape falls back to the resting face on a degenerate scale`() {
        // A zero/negative ceiling (a corrupted persisted override) must not divide by zero.
        assertEquals(BOOST_EYE_SCALE_MIN, boostFaceShape(8.0, GaugeScale(0.0, 0.0, 5.0)).eyeScale, 0f)
        assertEquals(BOOST_EYE_SCALE_MIN, boostFaceShape(8.0, GaugeScale(0.0, -5.0, 5.0)).eyeScale, 0f)
    }

    @Test
    fun `boostFaceShape is always a bug-eyed non-crying face with level brows`() {
        listOf(0.0, IDLE_PSI, BOOST_FACE_MAX_PSI, 999.0).forEach { value ->
            val shape = boostFaceShape(value, boostScale)
            assertTrue("boost face should always use the white-sclera bug eyes at $value", shape.bugEyes)
            assertFalse("boost is excitement, never tears, at $value", shape.teary)
            assertEquals("boost never wears the worried brow, at $value", 0f, shape.browTilt, 0f)
            // The grin only ever widens — a boost face is never frowning.
            assertTrue("boost mouth should stay a grin at $value", shape.mouthCurve >= BOOST_MOUTH_CURVE_MIN)
        }
    }

    private companion object {
        const val IDLE_PSI = 2.0
        const val TOLERANCE = 1e-4f
    }
}
