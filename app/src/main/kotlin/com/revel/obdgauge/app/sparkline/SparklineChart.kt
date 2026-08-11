package com.revel.obdgauge.app.sparkline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Duration

private const val STRIP_HEIGHT_DP = 28
private const val STROKE_WIDTH_DP = 2

/**
 * A gap wider than this between two consecutive points is rendered as a break (a lifted pen —
 * `moveTo` instead of `lineTo`) rather than an interpolated line: a stale/disconnected span
 * must never look like data was flowing continuously through it. Chosen as several multiples
 * of the fastest expected poll interval (4 Hz = 250 ms) so ordinary tick-to-tick jitter never
 * trips it, while an actual stale/disconnect gap (seconds+, since `SparklineHistoryHolder`
 * simply stops appending during one) always does.
 */
private val GAP_THRESHOLD: Duration = Duration.ofSeconds(2)

/**
 * `true` if [current] is far enough after [previous] to render as a break rather than an
 * interpolated line segment — see [GAP_THRESHOLD]. Extracted as a pure, Compose-free function
 * so the exclusive-boundary behavior (exactly [GAP_THRESHOLD] apart is *not* a gap) is directly
 * unit-testable without a Canvas/DrawScope.
 */
internal fun isSparklineGap(
    previous: SparklinePoint,
    current: SparklinePoint,
    threshold: Duration = GAP_THRESHOLD,
): Boolean = Duration.between(previous.timestamp, current.timestamp) > threshold

/**
 * Renders [points] (already downsampled — see [downsampleSparkline]; this composable does not
 * downsample itself) as a strip line chart auto-scaled to the data's own min/max value and
 * time span.
 *
 * No per-frame allocations in the draw path (OBD-20 AC): the [Path] is created once via
 * [remember] and [Path.reset] on every draw rather than reallocated; the [Stroke] and each
 * point's epoch-millis are hoisted out of the draw block into `remember`s keyed on the inputs
 * that actually change them, rather than recomputed (each `Duration.between(...).toMillis()`
 * call allocates a `Duration`) on every single draw pass.
 */
@Composable
fun SparklineChart(
    points: List<SparklinePoint>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val path = remember { Path() }
    val density = LocalDensity.current
    val stroke = remember(density) { Stroke(width = strokeWidthPx(density)) }
    val epochMillis = remember(points) { points.map { it.timestamp.toEpochMilli() } }

    Canvas(modifier = modifier.fillMaxWidth().height(STRIP_HEIGHT_DP.dp)) {
        path.reset()
        if (points.size < 2) return@Canvas

        val minValue = points.minOf { it.value }
        val maxValue = points.maxOf { it.value }
        val valueSpan = (maxValue - minValue).takeIf { it != 0.0 } ?: 1.0
        val minMillis = epochMillis.first()
        val timeSpanMillis = (epochMillis.last() - minMillis).takeIf { it != 0L } ?: 1L

        fun xOf(index: Int): Float = ((epochMillis[index] - minMillis).toFloat() / timeSpanMillis) * size.width

        fun yOf(point: SparklinePoint): Float =
            size.height - (((point.value - minValue) / valueSpan) * size.height).toFloat()

        var previousIndex = -1
        points.forEachIndexed { index, point ->
            val brokeStream = previousIndex >= 0 && isSparklineGap(points[previousIndex], point)
            if (previousIndex < 0 || brokeStream) {
                path.moveTo(xOf(index), yOf(point))
            } else {
                path.lineTo(xOf(index), yOf(point))
            }
            previousIndex = index
        }
        drawPath(path = path, color = color, style = stroke)
    }
}

private fun strokeWidthPx(density: Density): Float = with(density) { STROKE_WIDTH_DP.dp.toPx() }
