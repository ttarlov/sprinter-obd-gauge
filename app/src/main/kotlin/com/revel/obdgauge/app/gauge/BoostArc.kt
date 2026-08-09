package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.ui.theme.GaugeNeutral
import com.revel.obdgauge.app.ui.theme.GaugeTrackNeutral

/** Boost gauge's fixed display range, per OBD-10's spec: −2..+18 PSI. */
const val BOOST_RANGE_MIN = -2.0
const val BOOST_RANGE_MAX = 18.0

private const val ARC_SIZE_DP = 120
private const val ARC_STROKE_WIDTH_DP = 10
private const val ARC_START_ANGLE = 135f
private const val ARC_SWEEP_ANGLE = 270f

/**
 * Sweep fraction (`0f..1f`) for [psi] within [BOOST_RANGE_MIN]..[BOOST_RANGE_MAX], clamping
 * out-of-range values to the nearest end. Pulled out of [BoostArc] so the arc math is
 * unit-testable without a Compose/Canvas dependency.
 */
fun boostArcFraction(psi: Double): Float =
    ((psi.coerceIn(BOOST_RANGE_MIN, BOOST_RANGE_MAX) - BOOST_RANGE_MIN) / (BOOST_RANGE_MAX - BOOST_RANGE_MIN))
        .toFloat()

/**
 * Sweep/arc indicator for the boost tile, spanning [BOOST_RANGE_MIN]..[BOOST_RANGE_MAX] PSI.
 * Boost is threshold-neutral (see [ThresholdConfig]) so this always renders in the same
 * neutral color — position on the arc, not color, is the signal.
 */
@Composable
fun BoostArc(
    psi: Double,
    modifier: Modifier = Modifier,
) {
    val fraction = boostArcFraction(psi)
    Canvas(modifier = modifier.size(ARC_SIZE_DP.dp)) {
        val strokeWidthPx = ARC_STROKE_WIDTH_DP.dp.toPx()
        val inset = strokeWidthPx / 2
        val arcSize = Size(size.width - strokeWidthPx, size.height - strokeWidthPx)
        val topLeft = Offset(inset, inset)
        val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)

        drawArc(
            color = GaugeTrackNeutral,
            startAngle = ARC_START_ANGLE,
            sweepAngle = ARC_SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
        drawArc(
            color = GaugeNeutral,
            startAngle = ARC_START_ANGLE,
            sweepAngle = ARC_SWEEP_ANGLE * fraction,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
        )
    }
}
