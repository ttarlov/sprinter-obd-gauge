package com.revel.obdgauge.app.sparkline

import java.time.Duration
import java.time.Instant

/** One data point in a gauge's rolling history: a value at a point in time. */
data class SparklinePoint(
    val timestamp: Instant,
    val value: Double,
)

/**
 * Bounded in-memory rolling history for one gauge's sparkline (OBD-20): a ring buffer that
 * keeps at most [window] worth of samples, trimmed on every [add]/[trim] call — it never grows
 * unbounded even under a fast, long-running poll loop.
 *
 * Pure Kotlin, no Compose/Android dependency, so it's plain-JVM-testable (window trim,
 * downsample determinism, bound enforcement at 4 Hz for the full 5-minute window — see
 * `SparklineBufferTest`).
 *
 * Not thread-safe: callers (here, [SparklineHistoryHolder]) are responsible for confining
 * access to a single coroutine, the same discipline the rest of this module's StateFlow-based
 * state already follows.
 */
class SparklineBuffer(
    private val window: Duration = DEFAULT_WINDOW,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val samples = ArrayDeque<SparklinePoint>()

    /** Current buffer contents, oldest first. Never longer than [capacity]. */
    val points: List<SparklinePoint>
        get() = samples.toList()

    /**
     * Appends [point] and trims anything now outside [window]. The window is measured relative
     * to [point]'s own timestamp (not wall-clock "now") — the buffer's notion of "now" is
     * always its most recently added sample, so this works identically whether it's fed by a
     * real clock or a scripted/virtual one (as `FakeVehicleDataSource`'s scenarios are).
     *
     * A no-op if [point]'s timestamp matches the most recently added sample's: `onReadings`
     * (see `SparklineHistoryHolder`) runs on every combine tick — including ones triggered by a
     * settings edit or a connection-state change with no new reading at all — so without this
     * guard, the *same* reading would be appended once per unrelated tick, inflating the buffer
     * with duplicate points at an identical x-position instead of once per actual new sample.
     */
    fun add(point: SparklinePoint) {
        if (samples.lastOrNull()?.timestamp == point.timestamp) {
            // Still prune the window: a reading whose timestamp stops advancing must not
            // defer trimming until staleness (round-2 NIT).
            trim(point.timestamp)
            return
        }
        samples.addLast(point)
        trim(point.timestamp)
    }

    /**
     * Drops samples older than [window] relative to [now], and separately enforces [capacity]
     * — a hard cap independent of the time window, so a burst of same-timestamp or
     * out-of-order samples can never grow the buffer past a fixed memory bound even before the
     * time-window trim below catches up.
     */
    fun trim(now: Instant) {
        val cutoff = now.minus(window)
        while (samples.isNotEmpty() && samples.first().timestamp.isBefore(cutoff)) {
            samples.removeFirst()
        }
        while (samples.size > capacity) {
            samples.removeFirst()
        }
    }

    companion object {
        val DEFAULT_WINDOW: Duration = Duration.ofMinutes(WINDOW_MINUTES)

        // 4 Hz x 300 s = 1200 samples for the nominal window; doubled for headroom against
        // bursty or out-of-order timestamps before the time-window trim above catches up.
        const val DEFAULT_CAPACITY = 2_400

        private const val WINDOW_MINUTES = 5L
    }
}

/** Pixel budget for a rendered sparkline strip, regardless of the underlying sample rate. */
const val DEFAULT_MAX_SPARKLINE_POINTS = 120

/**
 * Downsamples [points] to at most [maxPoints] by picking evenly spaced indices — deterministic
 * (the same input always produces the same output, verified by `SparklineBufferTest`) and
 * O([maxPoints]) regardless of how large [points] is, which is what keeps this cheap to run on
 * every 4 Hz update. Always keeps the first and last point so the visible trend's endpoints
 * never silently move.
 */
fun downsampleSparkline(
    points: List<SparklinePoint>,
    maxPoints: Int = DEFAULT_MAX_SPARKLINE_POINTS,
): List<SparklinePoint> {
    if (maxPoints <= 1 || points.size <= maxPoints) return points
    val step = points.size.toDouble() / maxPoints
    val lastIndex = points.size - 1
    return List(maxPoints) { i ->
        val index = if (i == maxPoints - 1) lastIndex else (i * step).toInt().coerceIn(0, lastIndex)
        points[index]
    }
}
