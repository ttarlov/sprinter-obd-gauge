package com.revel.obdgauge.app.gauge

import org.junit.Assert.assertEquals
import org.junit.Test

/** OBD-72's pure value→angle (needle) and value→segment (bar-arc) mapping. */
class GaugeRenderMathTest {
    private val scale = GaugeScale(min = 0.0, max = 100.0, tick = 25.0)

    // --- needleAngleDegrees ---

    @Test
    fun `needleAngleDegrees spans the shared sweep at the ends and midpoint`() {
        assertEquals(GAUGE_SWEEP_START_DEG, needleAngleDegrees(scale, 0.0), 0f)
        assertEquals(GAUGE_SWEEP_START_DEG + GAUGE_SWEEP_ANGLE_DEG, needleAngleDegrees(scale, 100.0), 0f)
        assertEquals(GAUGE_SWEEP_START_DEG + GAUGE_SWEEP_ANGLE_DEG / 2f, needleAngleDegrees(scale, 50.0), 0f)
    }

    @Test
    fun `needleAngleDegrees clamps an out-of-range value to the nearest end of the sweep`() {
        assertEquals(GAUGE_SWEEP_START_DEG, needleAngleDegrees(scale, -500.0), 0f)
        assertEquals(GAUGE_SWEEP_START_DEG + GAUGE_SWEEP_ANGLE_DEG, needleAngleDegrees(scale, 9_999.0), 0f)
    }

    // --- thresholdZoneSpans ---

    @Test
    fun `thresholdZoneSpans is one NEUTRAL span covering the whole sweep when both boundaries are unset`() {
        val spans = thresholdZoneSpans(scale, GaugeThresholds())
        assertEquals(listOf(ThresholdZoneSpan(0f, 1f, ThresholdZone.NEUTRAL)), spans)
    }

    @Test
    fun `thresholdZoneSpans splits green amber red at the threshold boundaries`() {
        val thresholds = GaugeThresholds(greenMax = 40.0, redMin = 60.0)
        val spans = thresholdZoneSpans(scale, thresholds)
        assertEquals(
            listOf(
                ThresholdZoneSpan(0f, 0.4f, ThresholdZone.GREEN),
                ThresholdZoneSpan(0.4f, 0.6f, ThresholdZone.AMBER),
                ThresholdZoneSpan(0.6f, 1f, ThresholdZone.RED),
            ),
            spans,
        )
    }

    @Test
    fun `thresholdZoneSpans with only a red boundary has no green span`() {
        val thresholds = GaugeThresholds(redMin = 60.0)
        val spans = thresholdZoneSpans(scale, thresholds)
        assertEquals(
            listOf(
                ThresholdZoneSpan(0f, 0.6f, ThresholdZone.AMBER),
                ThresholdZoneSpan(0.6f, 1f, ThresholdZone.RED),
            ),
            spans,
        )
    }

    @Test
    fun `thresholdZoneSpans with only a green boundary has no red span`() {
        val thresholds = GaugeThresholds(greenMax = 40.0)
        val spans = thresholdZoneSpans(scale, thresholds)
        assertEquals(
            listOf(
                ThresholdZoneSpan(0f, 0.4f, ThresholdZone.GREEN),
                ThresholdZoneSpan(0.4f, 1f, ThresholdZone.AMBER),
            ),
            spans,
        )
    }

    // --- litSegmentCount ---

    @Test
    fun `litSegmentCount at the ends and midpoint of the sweep`() {
        assertEquals(0, litSegmentCount(scale, 0.0, totalSegments = 20))
        assertEquals(20, litSegmentCount(scale, 100.0, totalSegments = 20))
        assertEquals(10, litSegmentCount(scale, 50.0, totalSegments = 20))
    }

    @Test
    fun `litSegmentCount clamps out-of-range values and a non-positive segment count`() {
        assertEquals(0, litSegmentCount(scale, -50.0, totalSegments = 20))
        assertEquals(20, litSegmentCount(scale, 500.0, totalSegments = 20))
        assertEquals(0, litSegmentCount(scale, 50.0, totalSegments = 0))
    }

    // --- segmentZone ---

    @Test
    fun `segmentZone classifies each segment's sweep-midpoint value`() {
        val thresholds = GaugeThresholds(greenMax = 40.0, redMin = 60.0)
        // 10 segments over 0..100: segment 0 spans [0,10) mid=5 (green),
        // segment 4 spans [40,50) mid=45 (amber), segment 9 spans [90,100) mid=95 (red).
        assertEquals(ThresholdZone.GREEN, segmentZone(scale, thresholds, index = 0, totalSegments = 10))
        assertEquals(ThresholdZone.AMBER, segmentZone(scale, thresholds, index = 4, totalSegments = 10))
        assertEquals(ThresholdZone.RED, segmentZone(scale, thresholds, index = 9, totalSegments = 10))
    }

    @Test
    fun `segmentZone is NEUTRAL for a NEUTRAL gauge and for a non-positive segment count`() {
        assertEquals(ThresholdZone.NEUTRAL, segmentZone(scale, GaugeThresholds(), index = 0, totalSegments = 10))
        val thresholds = GaugeThresholds(greenMax = 40.0, redMin = 60.0)
        assertEquals(ThresholdZone.NEUTRAL, segmentZone(scale, thresholds, index = 0, totalSegments = 0))
    }

    // --- segment angle geometry ---

    @Test
    fun `segmentStartAngleDeg starts the sweep at GAUGE_SWEEP_START_DEG and advances by span plus gap`() {
        val total = 10
        val gap = 2f
        assertEquals(GAUGE_SWEEP_START_DEG, segmentStartAngleDeg(0, total, gap), 0f)
        val span = segmentSweepDeg(total, gap)
        assertEquals(GAUGE_SWEEP_START_DEG + (span + gap), segmentStartAngleDeg(1, total, gap), 1e-4f)
    }

    @Test
    fun `segmentSweepDeg times totalSegments plus the gaps between them fills the full sweep`() {
        val total = 20
        val gap = BAR_ARC_SEGMENT_GAP_DEG
        val span = segmentSweepDeg(total, gap)
        assertEquals(GAUGE_SWEEP_ANGLE_DEG, span * total + gap * (total - 1), 1e-3f)
    }

    @Test
    fun `segment angle helpers are safe for a non-positive segment count`() {
        assertEquals(GAUGE_SWEEP_START_DEG, segmentStartAngleDeg(0, 0), 0f)
        assertEquals(0f, segmentSweepDeg(0), 0f)
    }

    @Test
    fun `barArcSegmentCount picks the compact or default segment count`() {
        assertEquals(BAR_ARC_SEGMENTS_DEFAULT, barArcSegmentCount(compact = false))
        assertEquals(BAR_ARC_SEGMENTS_COMPACT, barArcSegmentCount(compact = true))
    }
}
