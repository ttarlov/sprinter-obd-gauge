package com.revel.obdgauge.app.sparkline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pure, Compose-free coverage of [isSparklineGap]'s 2 s boundary — see `SparklineChart.kt`. */
class SparklineGapTest {
    private val t0 = Instant.EPOCH

    @Test
    fun `a gap just under the 2s threshold is not a break`() {
        val previous = SparklinePoint(t0, 1.0)
        val current = SparklinePoint(t0.plusMillis(1_999), 2.0)

        assertFalse(isSparklineGap(previous, current))
    }

    @Test
    fun `a gap exactly at the 2s threshold is not a break (exclusive boundary)`() {
        val previous = SparklinePoint(t0, 1.0)
        val current = SparklinePoint(t0.plusSeconds(2), 2.0)

        assertFalse(isSparklineGap(previous, current))
    }

    @Test
    fun `a gap just over the 2s threshold is a break`() {
        val previous = SparklinePoint(t0, 1.0)
        val current = SparklinePoint(t0.plusMillis(2_001), 2.0)

        assertTrue(isSparklineGap(previous, current))
    }
}
