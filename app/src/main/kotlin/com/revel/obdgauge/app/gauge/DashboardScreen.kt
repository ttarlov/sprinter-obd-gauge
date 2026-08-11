package com.revel.obdgauge.app.gauge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
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

// internal (not private): GaugePicker.kt's GaugePickerTile reuses these so the picker frame
// matches the tile's own corner radius/padding exactly — "still THAT tile" (issues/OBD-42.md).
internal const val TILE_CORNER_RADIUS_DP = 16
internal const val TILE_PADDING_DP = 16
private const val TILE_SPACING_DP = 12
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
 * ### OBD-42: in-place gauge swap
 * Long-pressing a tile puts *that tile only* into picker mode — the rest of the dashboard stays
 * live, per `issues/OBD-42.md`'s verbatim UX intent. Picker-mode state ([pickerTileId] below)
 * lives here rather than inside any one tile, because dismissing it has three paths and only two
 * of them are local to the picking tile itself (tapping its own current-gauge mini-card, and the
 * swap-select path) — the other two (a tap *outside* the tile, and the system back gesture) need
 * a view of the whole dashboard, which only this composable has.
 *
 * @param gaugeOrder which gauges show and in what order (OBD-21); defaults to the dashboard's
 *   original hardcoded order/visibility so a caller that doesn't pass settings renders exactly
 *   as before OBD-21. OBD-42's picker also reads this to compute each tile's swap candidates
 *   (`candidateGaugesFor` in `GaugeCatalog.kt`) — excluding whatever's already visible elsewhere.
 * @param sparklines per-gauge-id rolling history (OBD-20), each a [StateFlow] rather than a
 *   plain `List` — collected only by the leaf [GaugeSparklineStrip], never read here or by
 *   [GaugeTile]/[BoostTile] themselves, so a 4 Hz sparkline tick recomposes only that one leaf
 *   instead of this whole composable. See `SparklineHistoryHolder`'s KDoc and
 *   `SparklineRecompositionTest`. A missing/absent id renders no sparkline for that tile.
 * @param onSettingsClick invoked by the gear button; the caller (here, `MainActivity`) owns
 *   navigation — this composable has no nav-library dependency, per the codebase's minimal
 *   style.
 * @param onSwapGauge OBD-42: invoked `(oldId, newId)` the moment a picker candidate is tapped —
 *   the caller is expected to persist it via the same `gaugeOrder` path OBD-21's settings screen
 *   uses (`DashboardViewModel.swapGauge`/`AppSettings.withGaugeSwapped`). Picker-mode dismissal
 *   itself is entirely local UI state; this callback only ever fires for an actual swap, never
 *   for a dismiss-without-choosing.
 */
@Composable
@Suppress("LongParameterList") // one param per input this composable's layout/picker wiring actually needs.
fun GaugeDashboard(
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
    gaugeOrder: List<GaugeOrderEntry> = DEFAULT_GAUGE_ORDER,
    sparklines: Map<String, StateFlow<List<SparklinePoint>>> = emptyMap(),
    onSettingsClick: () -> Unit = {},
    onSwapGauge: (oldId: String, newId: String) -> Unit = { _, _ -> },
) {
    val visibleIds = gaugeOrder.filter { it.visible }.map { it.id }

    var pickerTileId by remember { mutableStateOf<String?>(null) }
    val dismissPicker: () -> Unit = { pickerTileId = null }
    BackHandler(enabled = pickerTileId != null, onBack = dismissPicker)

    // Review round-1 M1: a completed swap replaces the picking id's gaugeOrder entry, which
    // tears that id's key(id)-scoped subtree down (GaugeTileGrid's KDoc) — including
    // GaugePickerTile's own LaunchedEffect, before its delayed onDismiss() ever runs. Left
    // unhandled, pickerTileId stays pinned to an id no longer on screen forever: the invisible
    // scrim + armed BackHandler above would eat the next back press/outside-tap for no visible
    // reason, and if that id ever comes back into gaugeOrder (e.g. a second swap restores it)
    // its tile would mount already in picker mode. Self-heal here instead of relying on the
    // torn-down composable's own cleanup — this runs whenever the visible id set changes and
    // clears pickerTileId the instant it's no longer valid, independent of GaugePickerTile's
    // 220 ms local rise animation (which stays purely cosmetic, not load-bearing for this).
    LaunchedEffect(visibleIds) {
        if (pickerTileId != null && pickerTileId !in visibleIds) {
            pickerTileId = null
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (pickerTileId != null) {
                // "Tap outside the tile" dismiss path (AC). Every tile fully covers its own
                // bounds with its own pointer input (see GaugeTile/BoostTile's onTap below), so
                // this full-size scrim is only ever reachable through the gaps between tiles /
                // the banner+gear row — i.e. genuinely "outside". Invisible: the rest of the
                // dashboard must stay visually untouched, this is purely a hit target.
                val currentDismiss by rememberUpdatedState(dismissPicker)
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag("gauge-picker-scrim")
                            .pointerInput(Unit) { detectTapGestures(onTap = { currentDismiss() }) },
                )
            }
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ConnectionBanner(uiState.connection, modifier = Modifier.weight(1f))
                    TextButton(onClick = onSettingsClick, modifier = Modifier.testTag("settings-button")) {
                        Text(text = SETTINGS_GLYPH, style = MaterialTheme.typography.titleLarge)
                    }
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    GaugeTileGrid(
                        isLandscape = maxWidth >= maxHeight,
                        visibleIds = visibleIds,
                        uiState = uiState,
                        sparklines = sparklines,
                        gaugeOrder = gaugeOrder,
                        pickerTileId = pickerTileId,
                        onLongPress = { id -> pickerTileId = id },
                        onDismissPicker = dismissPicker,
                        onSelectCandidate = { oldId, newId -> onSwapGauge(oldId, newId) },
                    )
                }
            }
        }
    }
}

/**
 * The landscape single-[Row] / portrait scrollable-[Column] tile layout, extracted out of
 * [GaugeDashboard] so that composable's own body stays focused on Surface/scrim/picker-state
 * plumbing. Every [visibleIds] entry is `key`ed by its own id (unchanged from pre-OBD-42
 * behavior) — this is what gives OBD-21's reorder-in-settings its stable per-gauge composition
 * identity as a tile moves position. A swap changes *which* id occupies a position, which is a
 * *different* id — Compose tears down that key's subtree and mounts the new id fresh, which is
 * why OBD-42's swap-select transition is a picker-local animation (see `GaugePicker.kt`) rather
 * than a cross-fade spanning the old and new ids: there is no single composable instance alive
 * across that boundary to animate.
 */
@Composable
@Suppress("LongParameterList") // one param per input the per-tile GaugeSlot calls below actually need.
private fun GaugeTileGrid(
    isLandscape: Boolean,
    visibleIds: List<String>,
    uiState: DashboardUiState,
    sparklines: Map<String, StateFlow<List<SparklinePoint>>>,
    gaugeOrder: List<GaugeOrderEntry>,
    pickerTileId: String?,
    onLongPress: (String) -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (oldId: String, newId: String) -> Unit,
) {
    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxSize().padding(TILE_SPACING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp),
        ) {
            visibleIds.forEach { id ->
                key(id) {
                    GaugeSlot(
                        id = id,
                        uiState = uiState,
                        sparkline = sparklines[id],
                        gaugeOrder = gaugeOrder,
                        isPicking = pickerTileId == id,
                        onLongPress = { onLongPress(id) },
                        onDismissPicker = onDismissPicker,
                        onSelectCandidate = { newId -> onSelectCandidate(id, newId) },
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    } else {
        // No fixed tile height here: each tile sizes to its own content (label + value, or
        // label + arc + value for boost). Combined with the scroll below, this is what
        // guarantees portrait never clips regardless of font scale or screen height.
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
                    GaugeSlot(
                        id = id,
                        uiState = uiState,
                        sparkline = sparklines[id],
                        gaugeOrder = gaugeOrder,
                        isPicking = pickerTileId == id,
                        onLongPress = { onLongPress(id) },
                        onDismissPicker = onDismissPicker,
                        onSelectCandidate = { newId -> onSelectCandidate(id, newId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * Dispatches to [BoostTile] for the boost id (arc + neutral color), [GaugeTile] otherwise — or,
 * when [isPicking] is true, to [GaugePickerTile] (OBD-42's long-press swap carousel).
 * [AnimatedContent] cross-fades/scales between "normal tile" and "picker mode" so entering and
 * dismissing-without-selecting both read as the gauge sinking into, and rising back out of, its
 * own frame — see `GaugePicker.kt`'s `gaugePickerContentTransition`. A swap-select is *not*
 * animated across this boundary; see [GaugeTileGrid]'s KDoc for why.
 */
@Composable
@Suppress("LongParameterList") // one param per input GaugeSlot's dispatch/callbacks actually need.
private fun GaugeSlot(
    id: String,
    uiState: DashboardUiState,
    sparkline: StateFlow<List<SparklinePoint>>?,
    gaugeOrder: List<GaugeOrderEntry>,
    isPicking: Boolean,
    onLongPress: () -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // MINOR M3: fall back to a placeholder (like GaugePickerTile's own mini-cards already do)
    // rather than skipping this slot's render entirely — DashboardUiState.Loading only carries
    // the four core placeholders (extraTiles is empty by default), so a tile freshly swapped to
    // a non-core id (e.g. rpm) would otherwise render as a gap in the layout for every frame
    // before the first real reading arrives.
    val tile = uiState.tileFor(id) ?: GaugeTileUiState.placeholder(id, GAUGE_CATALOG_BY_ID[id]?.label ?: id)
    AnimatedContent(
        targetState = isPicking,
        modifier = modifier,
        transitionSpec = { gaugePickerContentTransition() },
        label = "gauge-picker-$id",
    ) { picking ->
        if (picking) {
            GaugePickerTile(
                currentId = id,
                candidates = remember(id, gaugeOrder) { candidateGaugesFor(id, gaugeOrder) },
                tileFor = uiState::tileFor,
                onDismiss = onDismissPicker,
                onSelectCandidate = onSelectCandidate,
            )
        } else if (id == PidIds.BOOST) {
            BoostTile(tile, sparkline, onLongPress = onLongPress, onTap = onDismissPicker)
        } else {
            GaugeTile(tile, sparkline, onLongPress = onLongPress, onTap = onDismissPicker)
        }
    }
}

/** One temp/numeric tile: label, large value, threshold-colored background, stale treatment, optional sparkline. */
@Composable
fun GaugeTile(
    state: GaugeTileUiState,
    sparkline: StateFlow<List<SparklinePoint>>? = null,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
) {
    val zoneColor = zoneColor(state.zone)
    Box(
        modifier =
            modifier
                .gaugeTileInteraction(state.id, state.zone, onLongPress, onTap)
                .background(
                    zoneColor.copy(alpha = TILE_BACKGROUND_ALPHA),
                    RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp),
                ).padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("gauge-${state.id}-label"),
            )
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

/**
 * Boost tile: same shell as [GaugeTile] plus the [BoostArc] sweep indicator — see its KDoc for
 * [onLongPress]/[onTap].
 */
@Composable
private fun BoostTile(
    state: GaugeTileUiState,
    sparkline: StateFlow<List<SparklinePoint>>?,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .gaugeTileInteraction(state.id, state.zone, onLongPress, onTap)
                .background(
                    GaugeNeutral.copy(alpha = TILE_BACKGROUND_ALPHA),
                    RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp),
                ).padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("gauge-${state.id}-label"),
            )
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
