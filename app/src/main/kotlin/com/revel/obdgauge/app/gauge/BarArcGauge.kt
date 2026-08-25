package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.app.ui.theme.GaugeTrackNeutral
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle

// OBD-72: the LED bar-arc render style — a segmented illuminated bar sweeping GAUGE_SWEEP_ANGLE_DEG
// around a big central digital readout, matching docs/ui-refs/OBD-72-led-bar-arc-style.png's
// retro Intellitronix look. Segment count/lit-count/color math lives in GaugeRenderMath.kt
// (Canvas-free, unit tested there); this file is purely the drawing.

private const val DIAL_INSET_FRACTION = 0.1f
private const val SEGMENT_STROKE_WIDTH_DP = 9

/**
 * The segmented ring itself — [barArcSegmentCount] segments, each lit ([litSegmentCount]) or dim
 * ([GaugeTrackNeutral]), colored per its own threshold band ([segmentZone]) when lit. No label or
 * center value — [BarArcGaugeBody] overlays those so this stays a reusable "just the ring" primitive.
 */
@Composable
fun BarArcGauge(
    value: Double,
    scale: GaugeScale,
    thresholds: GaugeThresholds,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dialSize = min(maxWidth, maxHeight)
        val compact = dialSize < GAUGE_COMPACT_SIZE_DP.dp
        val totalSegments = barArcSegmentCount(compact)
        val lit = remember(scale, value, totalSegments) { litSegmentCount(scale, value, totalSegments) }
        Canvas(modifier = Modifier.size(dialSize)) {
            val insetPx = size.minDimension * DIAL_INSET_FRACTION
            val arcSize = Size(size.width - insetPx * 2, size.height - insetPx * 2)
            val topLeft = Offset(insetPx, insetPx)
            val strokePx = SEGMENT_STROKE_WIDTH_DP.dp.toPx()
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Butt)
            for (index in 0 until totalSegments) {
                val color =
                    if (index < lit) {
                        zoneColor(segmentZone(scale, thresholds, index, totalSegments))
                    } else {
                        GaugeTrackNeutral
                    }
                drawArc(
                    color = color,
                    startAngle = segmentStartAngleDeg(index, totalSegments),
                    sweepAngle = segmentSweepDeg(totalSegments),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = stroke,
                )
            }
        }
    }
}

/**
 * [BarArcGauge] plus the gauge's big center value and label underneath it — what
 * `GaugeTile`/`BoostTile` actually render for [GaugeRenderStyle.BAR_ARC]. Reuses the exact
 * `gauge-<id>-label`/`gauge-<id>-value` testTags the [GaugeRenderStyle.DIGITAL] body uses, since
 * only one style is ever composed for a given tile at a time.
 *
 * OBD-72 device fix: value/label text sizes scale off this composable's own measured dimension
 * ([BoxWithConstraints]) via [scaledTextSize] — see `GaugeTextScale.kt`'s file KDoc. The segmented
 * ring itself already scaled correctly before this fix, being entirely Canvas-drawn off the same
 * measured size — it was only the overlay Text that stayed fixed-sp.
 */
@Composable
internal fun BarArcGaugeBody(
    state: GaugeTileUiState,
    scale: GaugeScale,
    thresholds: GaugeThresholds,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dim = min(maxWidth, maxHeight)
        BarArcGauge(
            value = state.rawValue,
            scale = scale,
            thresholds = thresholds,
            modifier = Modifier.fillMaxSize(),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val valueColor = if (state.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onBackground
            Text(
                text = state.valueText,
                style =
                    GaugeValueTextStyle.copy(
                        fontSize = scaledTextSize(dim, VALUE_FONT_FRACTION, VALUE_FONT_MIN_SP, VALUE_FONT_MAX_SP),
                    ),
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-value"),
            )
            Text(
                text = state.label,
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = scaledTextSize(dim, LABEL_FONT_FRACTION, LABEL_FONT_MIN_SP, LABEL_FONT_MAX_SP),
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-label"),
            )
        }
    }
}
