package com.revel.obdgauge.app.sparkline

import com.revel.obdgauge.model.Reading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Owns one [SparklineBuffer] per gauge id and republishes a downsampled snapshot to a
 * per-id [StateFlow] whenever new readings arrive (OBD-20).
 *
 * Deliberately NOT folded into
 * [DashboardUiState][com.revel.obdgauge.app.gauge.DashboardUiState]: that's a single immutable
 * object collected once at the top of the dashboard, so embedding sparkline points there would
 * recompose the *entire* dashboard tree on every 4 Hz tick. Instead each gauge id gets its own
 * `StateFlow`, so a leaf composable can `collectAsStateWithLifecycle()` on just its own id's
 * flow — Compose's recomposition scoping then confines a tick to that one leaf, never its
 * ancestors. See `app/MODULE.md`'s "Sparklines" section and `SparklineRecompositionTest`.
 *
 * A stale or missing reading is deliberately NOT added to its buffer (only [SparklineBuffer.trim]
 * runs) — the resulting time gap in the buffer is exactly what [SparklineChart] detects to
 * render a break instead of interpolating across a disconnect.
 */
class SparklineHistoryHolder(
    ids: List<String>,
    private val maxPoints: Int = DEFAULT_MAX_SPARKLINE_POINTS,
) {
    private val buffers: Map<String, SparklineBuffer> = ids.associateWith { SparklineBuffer() }
    private val flows: Map<String, MutableStateFlow<List<SparklinePoint>>> =
        ids.associateWith { MutableStateFlow(emptyList()) }

    private val empty: StateFlow<List<SparklinePoint>> =
        MutableStateFlow<List<SparklinePoint>>(
            emptyList(),
        ).asStateFlow()

    /** Read-only per-id flow of downsampled sparkline points; empty until data arrives for [id]. */
    fun flowFor(id: String): StateFlow<List<SparklinePoint>> = flows[id]?.asStateFlow() ?: empty

    /** Feeds one [readings] emission into every known id's buffer and republishes its flow. */
    fun onReadings(
        readings: Map<String, Reading>,
        now: Instant,
    ) {
        buffers.forEach { (id, buffer) ->
            val reading = readings[id]
            if (reading != null && !reading.stale) {
                buffer.add(SparklinePoint(reading.timestamp, reading.value))
            } else {
                buffer.trim(now)
            }
            flows[id]?.value = downsampleSparkline(buffer.points, maxPoints)
        }
    }
}
