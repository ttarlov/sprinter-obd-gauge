package com.revel.obdgauge.app.sparkline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SparklineBufferTest {
    @Test
    fun `points older than the window are trimmed when a newer point arrives`() {
        val buffer = SparklineBuffer(window = Duration.ofMinutes(5))
        val start = Instant.EPOCH

        buffer.add(SparklinePoint(start, 1.0))
        buffer.add(SparklinePoint(start.plusSeconds(60), 2.0))
        // Six minutes after the first point: it must be trimmed, the second (5 min old) kept.
        buffer.add(SparklinePoint(start.plusSeconds(360), 3.0))

        assertEquals(listOf(2.0, 3.0), buffer.points.map { it.value })
    }

    @Test
    fun `trim without a new point also prunes stale entries`() {
        val buffer = SparklineBuffer(window = Duration.ofMinutes(5))
        val start = Instant.EPOCH
        buffer.add(SparklinePoint(start, 1.0))

        buffer.trim(start.plusSeconds(301))

        assertTrue(buffer.points.isEmpty())
    }

    @Test
    fun `a point exactly at the window boundary is kept`() {
        val buffer = SparklineBuffer(window = Duration.ofMinutes(5))
        val start = Instant.EPOCH
        buffer.add(SparklinePoint(start, 1.0))

        buffer.trim(start.plusSeconds(300))

        assertEquals(1, buffer.points.size)
    }

    @Test
    fun `bound enforcement at 4 Hz for the full 5-minute window`() {
        val buffer = SparklineBuffer(window = Duration.ofMinutes(5))
        val start = Instant.EPOCH
        val fourHzIntervalMs = 250L
        val fiveMinutesOfSamples = (5 * 60 * 1000 / fourHzIntervalMs).toInt() // 1200

        repeat(fiveMinutesOfSamples) { i ->
            buffer.add(SparklinePoint(start.plusMillis(i * fourHzIntervalMs), i.toDouble()))
        }

        // All 1200 samples fall inside the 5-minute window relative to the last one added, so
        // none should have been trimmed by the window — but the buffer must never exceed a
        // fixed memory bound regardless of how long polling continues.
        assertTrue("expected <=1200 points, got ${buffer.points.size}", buffer.points.size <= fiveMinutesOfSamples)

        // Ten minutes' worth more (well past the window) must never grow the buffer unbounded.
        val tenMoreMinutes = fiveMinutesOfSamples * 2
        repeat(tenMoreMinutes) { i ->
            val index = fiveMinutesOfSamples + i
            buffer.add(SparklinePoint(start.plusMillis(index * fourHzIntervalMs), index.toDouble()))
        }
        assertTrue(
            "expected <=1200 points after sustained polling, got ${buffer.points.size}",
            buffer.points.size <= fiveMinutesOfSamples + 1,
        )
    }

    @Test
    fun `adding a point with the same timestamp as the last one is a no-op`() {
        val buffer = SparklineBuffer(window = Duration.ofMinutes(5))
        val start = Instant.EPOCH
        buffer.add(SparklinePoint(start, 1.0))

        buffer.add(SparklinePoint(start, 1.0))
        buffer.add(SparklinePoint(start, 1.0))

        assertEquals(1, buffer.points.size)
    }

    @Test
    fun `downsample keeps at most maxPoints and always includes the first and last point`() {
        val start = Instant.EPOCH
        val points = (0 until 1200).map { i -> SparklinePoint(start.plusMillis(i * 250L), i.toDouble()) }

        val downsampled = downsampleSparkline(points, maxPoints = 120)

        assertTrue(downsampled.size <= 120)
        assertEquals(points.first(), downsampled.first())
        assertEquals(points.last(), downsampled.last())
    }

    @Test
    fun `downsample is deterministic`() {
        val start = Instant.EPOCH
        val points = (0 until 733).map { i -> SparklinePoint(start.plusMillis(i * 250L), i.toDouble()) }

        val first = downsampleSparkline(points, maxPoints = 120)
        val second = downsampleSparkline(points, maxPoints = 120)

        assertEquals(first, second)
    }

    @Test
    fun `downsample is a no-op when input is already within budget`() {
        val start = Instant.EPOCH
        val points = (0 until 50).map { i -> SparklinePoint(start.plusMillis(i * 250L), i.toDouble()) }

        assertEquals(points, downsampleSparkline(points, maxPoints = 120))
    }

    @Test
    fun `downsample cost is bounded regardless of input size`() {
        val start = Instant.EPOCH
        // Far more than any real 5-minute-at-4Hz window would ever hold, to prove the output
        // size is a function of maxPoints, not of input size.
        val hugePointCount = 50_000
        val points = (0 until hugePointCount).map { i -> SparklinePoint(start.plusMillis(i * 10L), i.toDouble()) }

        val downsampled = downsampleSparkline(points, maxPoints = 120)

        assertEquals(120, downsampled.size)
    }
}
