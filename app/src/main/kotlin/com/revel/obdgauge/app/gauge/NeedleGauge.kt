package com.revel.obdgauge.app.gauge

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.revel.obdgauge.app.ui.theme.GaugeNeedleAccent
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.app.ui.theme.GaugeTrackNeutral
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle
import kotlin.math.cos
import kotlin.math.sin

// OBD-72: the analog-needle render style — a round dial, swept needle, tick scale, and colored
// threshold-zone bands, matching docs/ui-refs/OBD-72-analog-needle-style.png's TunerStudio look.
// All value->angle/value->span math lives in GaugeRenderMath.kt/GaugeScale.kt (Canvas-free, unit
// tested there); this file is purely the drawing.

private const val DIAL_INSET_FRACTION = 0.1f
private const val TRACK_STROKE_WIDTH_DP = 3
private const val ZONE_ARC_STROKE_WIDTH_DP = 7
private const val TICK_STROKE_WIDTH_DP = 2
private const val TICK_LENGTH_DP = 9
private const val TICK_LABEL_RADIUS_FRACTION = 0.72f
private const val NEEDLE_STROKE_WIDTH_DP = 3
private const val NEEDLE_LENGTH_FRACTION = 0.7f
private const val HUB_RADIUS_DP = 5
private const val NEEDLE_READOUT_BOTTOM_PAD_DP = 4

// Side inset so the value can never touch the dial's left/right edge even at its widest.
private const val NEEDLE_READOUT_SIDE_PAD_DP = 6

// OBD-72 device fix: tick labels are Canvas-drawn (nativeCanvas/Paint, not a Text composable), so
// they scale off the dial's own measured Canvas size (size.minDimension, px) rather than a Density
// conversion of a fixed sp — see GaugeTextScale.kt's file KDoc. The min/max clamp stays in sp so
// the floor/ceiling still respects the device's font-scale accessibility setting.
private const val TICK_LABEL_FONT_FRACTION = 0.06f
private val TICK_LABEL_MIN_SP = 8.sp
private val TICK_LABEL_MAX_SP = 16.sp

/**
 * The dial itself — background track, threshold-colored zone bands ([thresholdZoneSpans]), tick
 * marks (+ labels, unless [compact]-sized), and the swept needle ([needleAngleDegrees]). No label
 * or value text — [NeedleGaugeBody] overlays those on top so this stays a reusable, self-
 * contained "just the dial" primitive.
 */
@Composable
// LongMethod: track + zone bands + ticks/labels + needle are one cohesive Canvas drawing pass
// sharing the same center/radius/topLeft geometry computed once at the top — splitting it into
// several functions would mean passing that geometry (and the DrawScope itself) around for no
// clarity gain, the same tradeoff BoostArc's own single-Canvas-block style already makes.
@Suppress("LongMethod")
fun NeedleGauge(
    value: Double,
    scale: GaugeScale,
    thresholds: GaugeThresholds,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dialSize = min(maxWidth, maxHeight)
        val compact = dialSize < GAUGE_COMPACT_SIZE_DP.dp
        val zoneSpans = remember(scale, thresholds) { thresholdZoneSpans(scale, thresholds) }
        val ticks = remember(scale, compact) { scale.displayTicks(compact) }
        val needleAngle = needleAngleDegrees(scale, value)
        val density = LocalDensity.current
        val labelArgb = remember { GaugeStaleDim.toArgb() }
        Canvas(modifier = Modifier.size(dialSize)) {
            val insetPx = size.minDimension * DIAL_INSET_FRACTION
            val arcSize = Size(size.width - insetPx * 2, size.height - insetPx * 2)
            val topLeft = Offset(insetPx, insetPx)
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = arcSize.minDimension / 2f

            drawArc(
                color = GaugeTrackNeutral,
                startAngle = GAUGE_SWEEP_START_DEG,
                sweepAngle = GAUGE_SWEEP_ANGLE_DEG,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = TRACK_STROKE_WIDTH_DP.dp.toPx(), cap = StrokeCap.Butt),
            )
            zoneSpans.forEach { span ->
                if (span.zone == ThresholdZone.NEUTRAL) return@forEach
                drawArc(
                    color = zoneColor(span.zone),
                    startAngle = GAUGE_SWEEP_START_DEG + GAUGE_SWEEP_ANGLE_DEG * span.startFraction,
                    sweepAngle = GAUGE_SWEEP_ANGLE_DEG * (span.endFraction - span.startFraction),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = ZONE_ARC_STROKE_WIDTH_DP.dp.toPx(), cap = StrokeCap.Butt),
                )
            }
            val labelPaint =
                if (compact) {
                    null
                } else {
                    val minPx = with(density) { TICK_LABEL_MIN_SP.toPx() }
                    val maxPx = with(density) { TICK_LABEL_MAX_SP.toPx() }
                    Paint().apply {
                        color = labelArgb
                        textSize = scaledFontSizePx(size.minDimension, TICK_LABEL_FONT_FRACTION, minPx, maxPx)
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.DEFAULT
                        isAntiAlias = true
                    }
                }
            ticks.forEach { tickValue ->
                val angleRad = Math.toRadians(needleAngleDegrees(scale, tickValue).toDouble())
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()
                val outer = Offset(center.x + radius * cosA, center.y + radius * sinA)
                val inner =
                    Offset(
                        center.x + (radius - TICK_LENGTH_DP.dp.toPx()) * cosA,
                        center.y + (radius - TICK_LENGTH_DP.dp.toPx()) * sinA,
                    )
                drawLine(
                    color = GaugeStaleDim,
                    start = inner,
                    end = outer,
                    strokeWidth = TICK_STROKE_WIDTH_DP.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                labelPaint?.let { paint ->
                    val labelRadius = radius * TICK_LABEL_RADIUS_FRACTION
                    drawContext.canvas.nativeCanvas.drawText(
                        formatTickLabel(tickValue),
                        center.x + labelRadius * cosA,
                        center.y + labelRadius * sinA,
                        paint,
                    )
                }
            }
            val needleAngleRad = Math.toRadians(needleAngle.toDouble())
            val needleEnd =
                Offset(
                    center.x + radius * NEEDLE_LENGTH_FRACTION * cos(needleAngleRad).toFloat(),
                    center.y + radius * NEEDLE_LENGTH_FRACTION * sin(needleAngleRad).toFloat(),
                )
            drawLine(
                color = GaugeNeedleAccent,
                start = center,
                end = needleEnd,
                strokeWidth = NEEDLE_STROKE_WIDTH_DP.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawCircle(color = GaugeNeedleAccent, radius = HUB_RADIUS_DP.dp.toPx(), center = center)
        }
    }
}

/**
 * [NeedleGauge] plus the gauge's label (top) and formatted value (bottom) overlaid on the dial —
 * what `GaugeTile`/`BoostTile` actually render for [GaugeRenderStyle.NEEDLE]. Reuses the exact
 * `gauge-<id>-label`/`gauge-<id>-value` testTags the [GaugeRenderStyle.DIGITAL] body uses, since
 * only one style is ever composed for a given tile at a time.
 *
 * OBD-72 device fix: label/value text sizes scale off this composable's own measured dimension
 * ([BoxWithConstraints]) via [scaledTextSize] — see `GaugeTextScale.kt`'s file KDoc. The dial
 * itself (needle/ticks/zone arcs) already scaled correctly before this fix, being entirely
 * Canvas-drawn off the same measured size — it was only the overlay Text that stayed fixed-sp.
 */
@Composable
internal fun NeedleGaugeBody(
    state: GaugeTileUiState,
    scale: GaugeScale,
    thresholds: GaugeThresholds,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dim = min(maxWidth, maxHeight)
        NeedleGauge(
            value = state.rawValue,
            scale = scale,
            thresholds = thresholds,
            modifier = Modifier.fillMaxSize(),
        )
        // Value + label BOTH stacked in the dial's open bottom gap (the 270° sweep goes over the
        // TOP, so the needle never enters the bottom-center — it stays collision-free there). The
        // old layout pinned the label to TopCenter, where it overlapped the top arc, the top ticks,
        // and the edit-mode badges (Taras: "no components inside gauges should touch"). Keeping the
        // whole top of the dial clear fixes all three at once.
        val valueColor = if (state.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onBackground
        val valueFontSize =
            scaledTextSize(dim, NEEDLE_VALUE_FONT_FRACTION, NEEDLE_VALUE_FONT_MIN_SP, NEEDLE_VALUE_FONT_MAX_SP)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = NEEDLE_READOUT_SIDE_PAD_DP.dp)
                    .padding(bottom = NEEDLE_READOUT_BOTTOM_PAD_DP.dp),
        ) {
            Text(
                text = state.valueText,
                style = GaugeValueTextStyle.copy(fontSize = valueFontSize),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-value"),
            )
            Text(
                text = state.label,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontSize = scaledTextSize(dim, LABEL_FONT_FRACTION, LABEL_FONT_MIN_SP, LABEL_FONT_MAX_SP),
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-label"),
            )
        }
    }
}

private fun formatTickLabel(value: Double): String =
    if (value == Math.floor(value)) value.toLong().toString() else value.toString()
