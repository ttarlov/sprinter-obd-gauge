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
import androidx.compose.runtime.Composable
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
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeGreen
import com.revel.obdgauge.app.ui.theme.GaugeNeutral
import com.revel.obdgauge.app.ui.theme.GaugeRed
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState

private const val TILE_CORNER_RADIUS_DP = 16
private const val TILE_SPACING_DP = 12
private const val TILE_PADDING_DP = 16

/**
 * The full gauge dashboard. Landscape (a phone on a dash mount, the primary target) lays the
 * four tiles out in a single row; portrait stacks them in a scrollable column so content
 * never clips regardless of screen height (OBD-10 AC: "portrait doesn't crash or clip").
 *
 * Orientation is read from this composable's own measured [BoxWithConstraints] bounds, not
 * [androidx.compose.ui.platform.LocalConfiguration]'s device screen size — this dashboard can
 * be hosted in a container narrower than the full device screen (e.g. multi-window, or a
 * future embedded placement), and layout should follow the space it's actually given.
 */
@Composable
fun GaugeDashboard(
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
) {
    val tiles = listOf(uiState.coolant, uiState.transTemp, uiState.oilTemp)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isLandscape = maxWidth >= maxHeight
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(TILE_SPACING_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp),
                ) {
                    tiles.forEach { tile -> GaugeTile(tile, modifier = Modifier.weight(1f).fillMaxSize()) }
                    BoostTile(uiState.boost, modifier = Modifier.weight(1f).fillMaxSize())
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
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(TILE_SPACING_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp),
                ) {
                    tiles.forEach { tile ->
                        GaugeTile(tile, modifier = Modifier.fillMaxWidth())
                    }
                    BoostTile(uiState.boost, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** One temp/numeric tile: label, large value, threshold-colored background, stale treatment. */
@Composable
fun GaugeTile(
    state: GaugeTileUiState,
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
        }
    }
}

/** Boost tile: same shell as [GaugeTile] plus the [BoostArc] sweep indicator. */
@Composable
private fun BoostTile(
    state: GaugeTileUiState,
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
        }
    }
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
