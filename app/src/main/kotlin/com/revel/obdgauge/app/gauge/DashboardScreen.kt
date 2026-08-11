package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.sparkline.SparklineChart
import com.revel.obdgauge.app.sparkline.SparklinePoint
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeGreen
import com.revel.obdgauge.app.ui.theme.GaugeNeutral
import com.revel.obdgauge.app.ui.theme.GaugeRed
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidIds
import kotlinx.coroutines.flow.StateFlow

private const val TILE_CORNER_RADIUS_DP = 16
private const val TILE_SPACING_DP = 12
private const val TILE_PADDING_DP = 16
private const val SPARKLINE_TOP_PADDING_DP = 4

// Gear glyph for the settings entry point — plain text/emoji, matching this codebase's
// icon-free style (no material-icons dependency).
private const val SETTINGS_GLYPH = "⚙"

/**
 * The full gauge dashboard. Landscape (a phone on a dash mount, the primary target) lays the
 * visible tiles out in a single row; portrait stacks them in a scrollable column so content
 * never clips regardless of screen height (OBD-10 AC: "portrait doesn't crash or clip").
 * [ConnectionBanner] (OBD-11) sits above the tiles in both orientations, driven by
 * [DashboardUiState.connection]; a settings entry point (OBD-21) sits alongside it.
 *
 * Orientation is read from this composable's own measured [BoxWithConstraints] bounds, not
 * [androidx.compose.ui.platform.LocalConfiguration]'s device screen size — this dashboard can
 * be hosted in a container narrower than the full device screen (e.g. multi-window, or a
 * future embedded placement), and layout should follow the space it's actually given.
 *
 * @param gaugeOrder which gauges show and in what order (OBD-21); defaults to the dashboard's
 *   original hardcoded order/visibility so a caller that doesn't pass settings renders exactly
 *   as before OBD-21.
 * @param sparklines per-gauge-id rolling history (OBD-20), each a [StateFlow] rather than a
 *   plain `List` — collected only by the leaf [GaugeSparklineStrip], never read here or by
 *   [GaugeTile]/[BoostTile] themselves, so a 4 Hz sparkline tick recomposes only that one leaf
 *   instead of this whole composable. See `SparklineHistoryHolder`'s KDoc and
 *   `SparklineRecompositionTest`. A missing/absent id renders no sparkline for that tile.
 * @param onSettingsClick invoked by the gear button; the caller (here, `MainActivity`) owns
 *   navigation — this composable has no nav-library dependency, per the codebase's minimal
 *   style.
 */
@Composable
fun GaugeDashboard(
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
    gaugeOrder: List<GaugeOrderEntry> = DEFAULT_GAUGE_ORDER,
    sparklines: Map<String, StateFlow<List<SparklinePoint>>> = emptyMap(),
    onSettingsClick: () -> Unit = {},
) {
    val visibleIds = gaugeOrder.filter { it.visible }.map { it.id }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ConnectionBanner(uiState.connection, modifier = Modifier.weight(1f))
                TextButton(onClick = onSettingsClick, modifier = Modifier.testTag("settings-button")) {
                    Text(text = SETTINGS_GLYPH, style = MaterialTheme.typography.titleLarge)
                }
            }
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val isLandscape = maxWidth >= maxHeight
                if (isLandscape) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(TILE_SPACING_DP.dp),
                        horizontalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp),
                    ) {
                        visibleIds.forEach { id ->
                            key(id) {
                                GaugeSlot(id, uiState, sparklines[id], modifier = Modifier.weight(1f).fillMaxSize())
                            }
                        }
                    }
                } else {
                    // No fixed tile height here: each tile sizes to its own content (label +
                    // value, or label + arc + value for boost). Combined with the scroll below,
                    // this is what guarantees portrait never clips regardless of font scale or
                    // screen height.
                    Column(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(TILE_SPACING_DP.dp),
                        verticalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp),
                    ) {
                        visibleIds.forEach { id ->
                            key(id) {
                                GaugeSlot(id, uiState, sparklines[id], modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dispatches to [BoostTile] for the boost id (arc + neutral color), [GaugeTile] otherwise. */
@Composable
private fun GaugeSlot(
    id: String,
    uiState: DashboardUiState,
    sparkline: StateFlow<List<SparklinePoint>>?,
    modifier: Modifier = Modifier,
) {
    val tile = uiState.tileFor(id) ?: return
    if (id == PidIds.BOOST) {
        BoostTile(tile, sparkline, modifier)
    } else {
        GaugeTile(tile, sparkline, modifier)
    }
}

/** One temp/numeric tile: label, large value, threshold-colored background, stale treatment, optional sparkline. */
@Composable
fun GaugeTile(
    state: GaugeTileUiState,
    sparkline: StateFlow<List<SparklinePoint>>? = null,
    modifier: Modifier = Modifier,
) {
    val zoneColor = zoneColor(state.zone)
    Box(
        modifier =
            modifier
                .testTag("gauge-${state.id}")
                .semantics { stateDescription = state.zone.name.lowercase() }
                .background(zoneColor.copy(alpha = TILE_BACKGROUND_ALPHA), RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp))
                .padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = state.label, style = MaterialTheme.typography.titleMedium)
            val valueColor = if (state.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onBackground
            Text(
                text = state.valueText,
                style = GaugeValueTextStyle,
                color = valueColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-value"),
            )
            state.staleText?.let { staleText ->
                Text(
                    text = staleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = GaugeStaleDim,
                    modifier = Modifier.testTag("gauge-${state.id}-stale"),
                )
            }
            sparkline?.let { flow ->
                GaugeSparklineStrip(
                    id = state.id,
                    flow = flow,
                    color = zoneColor,
                    modifier = Modifier.fillMaxWidth().padding(top = SPARKLINE_TOP_PADDING_DP.dp),
                )
            }
        }
    }
}

/** Boost tile: same shell as [GaugeTile] plus the [BoostArc] sweep indicator. */
@Composable
private fun BoostTile(
    state: GaugeTileUiState,
    sparkline: StateFlow<List<SparklinePoint>>?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .testTag("gauge-${state.id}")
                .semantics { stateDescription = state.zone.name.lowercase() }
                .background(
                    GaugeNeutral.copy(alpha = TILE_BACKGROUND_ALPHA),
                    RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp),
                ).padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = state.label, style = MaterialTheme.typography.titleMedium)
            BoostArc(psi = state.rawValue)
            val valueColor = if (state.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onBackground
            Text(
                text = state.valueText,
                style = GaugeValueTextStyle,
                color = valueColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-value"),
            )
            sparkline?.let { flow ->
                GaugeSparklineStrip(
                    id = state.id,
                    flow = flow,
                    color = GaugeNeutral,
                    modifier = Modifier.fillMaxWidth().padding(top = SPARKLINE_TOP_PADDING_DP.dp),
                )
            }
        }
    }
}

/**
 * Leaf composable that collects [flow] itself (via [collectAsStateWithLifecycle]) — the only
 * place in this file that reads a sparkline flow's *value*. [GaugeTile]/[BoostTile]/
 * [GaugeDashboard] all pass the `StateFlow` reference through untouched, so a new point never
 * triggers their recomposition, only this leaf's — see `SparklineHistoryHolder`'s KDoc and
 * `SparklineRecompositionTest` (OBD-20 AC).
 */
@Composable
private fun GaugeSparklineStrip(
    id: String,
    flow: StateFlow<List<SparklinePoint>>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val points by flow.collectAsStateWithLifecycle()
    SparklineChart(points = points, color = color, modifier = modifier.testTag("gauge-$id-sparkline"))
}

private const val TILE_BACKGROUND_ALPHA = 0.18f

/** Zone→theme-color mapping backing each tile's background. Internal so tests can pin it. */
internal fun zoneColor(zone: ThresholdZone): Color =
    when (zone) {
        ThresholdZone.GREEN -> GaugeGreen
        ThresholdZone.AMBER -> GaugeAmber
        ThresholdZone.RED -> GaugeRed
        ThresholdZone.NEUTRAL -> GaugeNeutral
    }

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true, widthDp = 720, heightDp = 360)
@Composable
private fun GaugeDashboardLandscapePreview() {
    ObdGaugeTheme {
        GaugeDashboard(previewUiState())
    }
}

@Suppress("UnusedPrivateMember")
@Preview(showBackground = true, widthDp = 360, heightDp = 720)
@Composable
private fun GaugeDashboardPortraitPreview() {
    ObdGaugeTheme {
        GaugeDashboard(previewUiState())
    }
}

private fun previewUiState() =
    DashboardUiState(
        coolant =
            GaugeTileUiState(
                "coolant",
                "Coolant",
                "225°F",
                ThresholdZone.AMBER,
                isStale = false,
                staleText = null,
                rawValue = 225.0,
            ),
        transTemp =
            GaugeTileUiState(
                "transTemp",
                "Trans",
                "215°F",
                ThresholdZone.AMBER,
                isStale = false,
                staleText = null,
                rawValue = 215.0,
            ),
        oilTemp =
            GaugeTileUiState(
                "oilTemp",
                "Oil",
                "240°F",
                ThresholdZone.AMBER,
                isStale = false,
                staleText = null,
                rawValue = 240.0,
            ),
        boost =
            GaugeTileUiState(
                "boost",
                "Boost",
                "8.0 PSI",
                ThresholdZone.NEUTRAL,
                isStale = false,
                staleText = null,
                rawValue = 8.0,
            ),
        connection = LinkState.Ready,
    )
