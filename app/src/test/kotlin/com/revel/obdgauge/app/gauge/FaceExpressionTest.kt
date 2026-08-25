package com.revel.obdgauge.app.gauge

import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OBD-73 "dumb mode": the pure value→mood ([faceExpression]) and value→flush ([faceHeat]) mapping.
 *
 * Both are exercised against the REAL seeded thresholds (`ThresholdConfig.seed`) as well as a
 * synthetic band, because the "concerned" approach band and the heat ramp are both defined
 * *relative to the amber band's own width* (`redMin − greenMax`) rather than by a magic constant —
 * so a gauge with tight caution→danger spacing (coolant, 215→225) must fret over a narrower
 * approach than a loosely-spaced one (trans, 215→240), and that's the behavior worth pinning.
 */
class FaceExpressionTest {
    // ThresholdConfig.seed: coolant green <215 / amber 215-225 / red >=225 °F.
    private val coolant = ThresholdConfig.seed.getValue(PidIds.COOLANT)

    // ThresholdConfig.seed: trans green <215 / amber 215-240 / red >=240 °F (a 25-wide amber band).
    private val trans = ThresholdConfig.seed.getValue(PidIds.TRANS_TEMP)

    // ThresholdConfig.seed: oil green <245 / amber 245-260 / red >=260 °F.
    private val oil = ThresholdConfig.seed.getValue(PidIds.OIL_TEMP)

    // Synthetic 40/60 band (amber 20 wide, so the concerned approach starts at 20) — the same
    // fixture shape GaugeRenderMathTest uses, and a check that nothing here is coolant-specific.
    private val synthetic = GaugeThresholds(greenMax = 40.0, redMin = 60.0)

    // --- faceExpression: the four bands ---

    @Test
    fun `faceExpression is HAPPY well below the concern approach line`() {
        // Coolant's approach band starts at 215 - (225 - 215) = 205.
        assertEquals(FaceExpression.HAPPY, faceExpression(coolant, 180.0))
        assertEquals(FaceExpression.HAPPY, faceExpression(coolant, 204.9))
        // Trans's wider amber band (215-240) means it frets from 190 — 200 is still happy there
        // even though the same reading on coolant would not be.
        assertEquals(FaceExpression.HAPPY, faceExpression(trans, 189.9))
        assertEquals(FaceExpression.HAPPY, faceExpression(oil, 229.9))
        assertEquals(FaceExpression.HAPPY, faceExpression(synthetic, 19.9))
    }

    @Test
    fun `faceExpression is CONCERNED across the amber-wide approach band below the yellow line`() {
        // [greenMax - (redMin - greenMax), greenMax) — inclusive at the bottom, up to but not
        // including the yellow line itself (which is already amber, i.e. SAD).
        assertEquals(FaceExpression.CONCERNED, faceExpression(coolant, 205.0))
        assertEquals(FaceExpression.CONCERNED, faceExpression(coolant, 210.0))
        assertEquals(FaceExpression.CONCERNED, faceExpression(coolant, 214.9))
        assertEquals(FaceExpression.CONCERNED, faceExpression(trans, 190.0))
        assertEquals(FaceExpression.CONCERNED, faceExpression(oil, 230.0))
        assertEquals(FaceExpression.CONCERNED, faceExpression(synthetic, 20.0))
        assertEquals(FaceExpression.CONCERNED, faceExpression(synthetic, 39.9))
    }

    @Test
    fun `faceExpression is SAD in the amber band`() {
        // The seed's green band is exclusive, so a reading sitting exactly on the yellow line is
        // already amber (ThresholdConfig's own contract) — and therefore already sad, not concerned.
        assertEquals(FaceExpression.SAD, faceExpression(coolant, 215.0))
        assertEquals(FaceExpression.SAD, faceExpression(coolant, 220.0))
        assertEquals(FaceExpression.SAD, faceExpression(coolant, 224.9))
        assertEquals(FaceExpression.SAD, faceExpression(trans, 239.9))
        assertEquals(FaceExpression.SAD, faceExpression(oil, 250.0))
        assertEquals(FaceExpression.SAD, faceExpression(synthetic, 40.0))
        // Synthetic's red line is EXCLUSIVE (no redInclusive), unlike the seed's — 60.0 is amber.
        assertEquals(FaceExpression.SAD, faceExpression(synthetic, 60.0))
    }

    @Test
    fun `faceExpression is CRYING at and above the danger line`() {
        // The seed makes red inclusive, so sitting exactly on the danger line already cries —
        // matching the tile's own "pulse at/above danger" contract.
        assertEquals(FaceExpression.CRYING, faceExpression(coolant, 225.0))
        assertEquals(FaceExpression.CRYING, faceExpression(coolant, 260.0))
        assertEquals(FaceExpression.CRYING, faceExpression(trans, 240.0))
        assertEquals(FaceExpression.CRYING, faceExpression(oil, 260.0))
        assertEquals(FaceExpression.CRYING, faceExpression(synthetic, 60.1))
    }

    @Test
    fun `faceExpression is HAPPY for a NEUTRAL gauge with no thresholds`() {
        // Boost's seed row is an empty band — NEUTRAL, so there is no mood to read off it (its
        // FACE style is the separate bug-eye excitement face, not this one).
        assertEquals(FaceExpression.HAPPY, faceExpression(GaugeThresholds(), 0.0))
        assertEquals(FaceExpression.HAPPY, faceExpression(GaugeThresholds(), 9_999.0))
        assertEquals(FaceExpression.HAPPY, faceExpression(ThresholdConfig.seed.getValue(PidIds.BOOST), 18.0))
    }

    @Test
    fun `faceExpression never frets early when only a yellow line is set`() {
        // No red line means no amber band to mirror, so the approach band is zero-wide: the face
        // stays happy right up to the yellow line rather than fretting across the whole green zone.
        val yellowOnly = GaugeThresholds(greenMax = 40.0)
        assertEquals(FaceExpression.HAPPY, faceExpression(yellowOnly, 0.0))
        assertEquals(FaceExpression.HAPPY, faceExpression(yellowOnly, 39.9))
        assertEquals(FaceExpression.SAD, faceExpression(yellowOnly, 40.0))
    }

    // --- faceHeat: the continuous flush/sweat ramp ---

    @Test
    fun `faceHeat is 0 at and below the concern start`() {
        assertEquals(0f, faceHeat(coolant, 205.0), 0f)
        assertEquals(0f, faceHeat(coolant, 180.0), 0f)
        assertEquals(0f, faceHeat(coolant, -40.0), 0f)
        assertEquals(0f, faceHeat(synthetic, 20.0), 0f)
    }

    @Test
    fun `faceHeat is half way up the ramp at the yellow line`() {
        // The ramp spans concern-start → danger, and the yellow line sits exactly in its middle
        // (the approach band mirrors the amber band's width by construction).
        assertEquals(0.5f, faceHeat(coolant, 215.0), TOLERANCE)
        assertEquals(0.5f, faceHeat(trans, 215.0), TOLERANCE)
        assertEquals(0.5f, faceHeat(oil, 245.0), TOLERANCE)
        assertEquals(0.5f, faceHeat(synthetic, 40.0), TOLERANCE)
        // A quarter of the way in, for a point that isn't an endpoint or a boundary.
        assertEquals(0.25f, faceHeat(coolant, 210.0), TOLERANCE)
    }

    @Test
    fun `faceHeat is 1 at the danger line and clamps above it`() {
        assertEquals(1f, faceHeat(coolant, 225.0), 0f)
        assertEquals(1f, faceHeat(coolant, 400.0), 0f)
        assertEquals(1f, faceHeat(trans, 240.0), 0f)
        assertEquals(1f, faceHeat(synthetic, 60.0), 0f)
        assertEquals(1f, faceHeat(synthetic, 1_000.0), 0f)
    }

    @Test
    fun `faceHeat is 0 for a gauge missing either boundary`() {
        assertEquals(0f, faceHeat(GaugeThresholds(), 300.0), 0f)
        assertEquals(0f, faceHeat(GaugeThresholds(greenMax = 40.0), 300.0), 0f)
        assertEquals(0f, faceHeat(GaugeThresholds(redMin = 60.0), 300.0), 0f)
    }

    @Test
    fun `faceHeat degrades to a step for a zero-width or inverted ramp`() {
        // greenMax == redMin: no amber band, so no ramp to walk — the flush snaps on at danger
        // instead of dividing by zero.
        val touching = GaugeThresholds(greenMax = 100.0, redMin = 100.0)
        assertEquals(0f, faceHeat(touching, 99.9), 0f)
        assertEquals(1f, faceHeat(touching, 100.0), 0f)
        // Inverted (a hand-edited persisted override could produce it): still a clean 0/1 step.
        val inverted = GaugeThresholds(greenMax = 100.0, redMin = 90.0)
        assertEquals(0f, faceHeat(inverted, 50.0), 0f)
        assertEquals(1f, faceHeat(inverted, 95.0), 0f)
    }

    private companion object {
        const val TOLERANCE = 1e-4f
    }
}
