// OBD-64 adds the add-cell, add-palette, and picker edit-bar composables to this dashboard file,
// nudging it past detekt's per-file function count — they're all one screen's cohesive chrome, so
// suppressing here (as GaugePicker.kt already does for its own picker primitives) keeps the feature
// readable in one place rather than scattering it across files for the counter's sake.
@file:Suppress("TooManyFunctions")

package com.revel.obdgauge.app.gauge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revel.obdgauge.app.gauge.grid.GaugeGrid
import com.revel.obdgauge.app.gauge.grid.GridEngine
import com.revel.obdgauge.app.gauge.grid.GridLayout
import com.revel.obdgauge.app.gauge.grid.GridMigration
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
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import kotlinx.coroutines.flow.StateFlow

// internal (not private): GaugePicker.kt's SwapPagerCard reuses these so each in-tile swap page
// matches the real tile's own corner radius/padding/background exactly — "still THAT tile"
// (issues/OBD-42.md).
internal const val TILE_CORNER_RADIUS_DP = 16
internal const val TILE_PADDING_DP = 16
internal const val TILE_BACKGROUND_ALPHA = 0.18f
private const val TILE_SPACING_DP = 12
private const val SPARKLINE_TOP_PADDING_DP = 4

// OBD-64: add-palette / edit-bar chrome.
private const val ADD_PALETTE_SCRIM_ALPHA = 0.6f
private const val ADD_PALETTE_ELEVATION_DP = 6

// OBD-63: grid width per orientation. Landscape (dash-mount primary) is 4 wide so the migrated
// default (4 core gauges, 1×1) fills one row exactly like the pre-grid dashboard; portrait is 2
// wide so tiles stay legible when stacked. GridEngine.withColumns repacks the resolved layout into
// whichever applies for the current orientation.
private const val GRID_COLUMNS_LANDSCAPE = 4
private const val GRID_COLUMNS_PORTRAIT = 2

// OBD-64: the column count the PERSISTED grid is always stored at (see DashboardViewModel's eager
// seed + mutations). Equals landscape so the migrated default fills one row exactly like the
// pre-grid dashboard; DashboardScreen repacks this canonical layout into each orientation's width.
internal const val GRID_CANONICAL_COLUMNS = 4

// Gear glyph for the settings entry point — plain text/emoji, matching this codebase's
// icon-free style (no material-icons dependency).
private const val SETTINGS_GLYPH = "⚙"

// OBD-66: the danger-zone pulse. A tile whose live value is at/above its RED threshold breathes
// its red background/border between these alphas on a ~1s reverse loop — attention-grabbing at a
// glance off a dash mount without the strobe of a hard on/off flash.
private const val DANGER_PULSE_PERIOD_MS = 900
private const val DANGER_PULSE_MIN_ALPHA = 0.18f
private const val DANGER_PULSE_MAX_ALPHA = 0.55f
private const val DANGER_PULSE_BORDER_WIDTH_DP = 2

/**
 * OBD-66: whether the danger-zone RED pulse animates. Defaults on (the real dashboard). Tests that
 * render a RED tile flip it off so the otherwise-never-idle `rememberInfiniteTransition` can't hang
 * `waitForIdle()` — the pulse is a device-only visual, so a static red tile is the right thing to
 * assert against (and to screenshot). See `GaugeTile`.
 */
val LocalDangerPulseEnabled = staticCompositionLocalOf { true }

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
 * @param gridLayout OBD-63: the persisted spanning-grid layout (`AppSettings.gridLayout`), or
 *   `null` when none is stored yet — in which case one is derived from [gaugeOrder] via
 *   [GridMigration.fromGaugeOrder], so a fresh/old install renders exactly as before. Positions
 *   and spans of tiles come from here; [gaugeOrder] still drives visibility and the swap picker's
 *   candidate list.
 * @param sparklines per-gauge-id rolling history (OBD-20), each a [StateFlow] rather than a
 *   plain `List` — collected only by the leaf [GaugeSparklineStrip], never read here or by
 *   [GaugeTile]/[BoostTile] themselves, so a 4 Hz sparkline tick recomposes only that one leaf
 *   instead of this whole composable. See `SparklineHistoryHolder`'s KDoc and
 *   `SparklineRecompositionTest`. A missing/absent id renders no sparkline for that tile.
 * @param onSettingsClick invoked by the gear button; the caller (here, `MainActivity`) owns
 *   navigation — this composable has no nav-library dependency, per the codebase's minimal
 *   style.
 * @param onConnect OBD-25: the connect/retry action shown in [ConnectionBanner]. `null` (the
 *   default, and what the `demo` flavor passes — it has no link) renders the pre-OBD-25 banner
 *   exactly, which is why this change leaves both dashboard screenshots byte-identical. See
 *   [ConnectionBanner]'s KDoc for which link states show a button and why the busy ones don't.
 * @param onSwapGauge OBD-42: invoked `(oldId, newId)` the moment a picker candidate is tapped —
 *   the caller is expected to persist it via the same `gaugeOrder` path OBD-21's settings screen
 *   uses (`DashboardViewModel.swapGauge`/`AppSettings.withGaugeSwapped`). Picker-mode dismissal
 *   itself is entirely local UI state; this callback only ever fires for an actual swap, never
 *   for a dismiss-without-choosing.
 */
@Composable
// LongParameterList: one param per input this composable's layout/picker/edit wiring needs.
// LongMethod: the Box hosts the scrim, tile grid, edit-bar, and add-palette overlays — one screen's
// worth of sibling overlays whose shared picker/add state must live in this single scope (OBD-64).
@Suppress("LongParameterList", "LongMethod")
fun GaugeDashboard(
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
    gaugeOrder: List<GaugeOrderEntry> = DEFAULT_GAUGE_ORDER,
    gridLayout: GridLayout? = null,
    sparklines: Map<String, StateFlow<List<SparklinePoint>>> = emptyMap(),
    thresholds: Map<String, GaugeThresholds> = ThresholdConfig.seed,
    onSettingsClick: () -> Unit = {},
    onSwapGauge: (oldId: String, newId: String) -> Unit = { _, _ -> },
    onAddGauge: (id: String) -> Unit = {},
    onRemoveGauge: (id: String) -> Unit = {},
    onResizeGauge: (id: String, colSpan: Int, rowSpan: Int) -> Unit = { _, _, _ -> },
    onSetThreshold: (id: String, thresholds: GaugeThresholds) -> Unit = { _, _ -> },
    onConnect: (() -> Unit)? = null,
    dangerPulseEnabled: Boolean = true,
) {
    // OBD-64: the ids actually placed on the (canonical) grid — the single source of truth for
    // which gauges show, which the add-palette and swap-picker candidate lists both key off. Falls
    // back to a migration of `gaugeOrder` only when no grid is passed (old call sites / previews).
    val canonicalLayout =
        remember(gridLayout, gaugeOrder) {
            gridLayout ?: GridMigration.fromGaugeOrder(gaugeOrder, GRID_CANONICAL_COLUMNS)
        }
    val placedIds = canonicalLayout.ids
    val addableGauges = remember(placedIds) { addableGaugesFor(placedIds.toSet()) }

    var pickerTileId by remember { mutableStateOf<String?>(null) }
    var showAddPalette by remember { mutableStateOf(false) }
    val dismissPicker: () -> Unit = { pickerTileId = null }
    val dismissAddPalette: () -> Unit = { showAddPalette = false }
    BackHandler(enabled = pickerTileId != null, onBack = dismissPicker)
    BackHandler(enabled = showAddPalette, onBack = dismissAddPalette)

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
    LaunchedEffect(placedIds) {
        if (pickerTileId != null && pickerTileId !in placedIds) {
            pickerTileId = null
        }
    }
    // OBD-64: if a mutation (or an add) leaves nothing addable, the edit bar's Add button hides —
    // close a palette left open rather than showing an empty sheet with a live-armed back handler.
    LaunchedEffect(addableGauges.isEmpty()) {
        if (addableGauges.isEmpty()) {
            showAddPalette = false
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
                    ConnectionBanner(uiState.connection, modifier = Modifier.weight(1f), onConnect = onConnect)
                    TextButton(onClick = onSettingsClick, modifier = Modifier.testTag("settings-button")) {
                        Text(text = SETTINGS_GLYPH, style = MaterialTheme.typography.titleLarge)
                    }
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    CompositionLocalProvider(LocalDangerPulseEnabled provides dangerPulseEnabled) {
                        GaugeTileGrid(
                            isLandscape = maxWidth >= maxHeight,
                            uiState = uiState,
                            sparklines = sparklines,
                            gaugeOrder = gaugeOrder,
                            gridLayout = gridLayout,
                            placedIds = placedIds.toSet(),
                            pickerTileId = pickerTileId,
                            thresholds = thresholds,
                            onLongPress = { id -> pickerTileId = id },
                            onDismissPicker = dismissPicker,
                            onSelectCandidate = { oldId, newId -> onSwapGauge(oldId, newId) },
                            onSetThreshold = onSetThreshold,
                        )
                    }
                }
            }
            // OBD-64: the resize/Remove/Add edit bar for the tile being picked — BUTTONS (tapped,
            // not swiped), so they stay in a compact bar floating over the bottom of the dashboard
            // rather than joining the in-tile swap pager (OBD-65). The pager owns the swap itself;
            // this bar owns size/remove/add. A floating overlay (not the vertical flow) keeps the
            // picked tile full-size so its pager fills the whole slot.
            val pickingId = pickerTileId
            val pickingPlacement = pickingId?.let(canonicalLayout::placementFor)
            if (pickingId != null && pickingPlacement != null) {
                PickerEditBar(
                    currentId = pickingId,
                    currentColSpan = pickingPlacement.colSpan,
                    currentRowSpan = pickingPlacement.rowSpan,
                    canAdd = addableGauges.isNotEmpty(),
                    onResize = { colSpan, rowSpan -> onResizeGauge(pickingId, colSpan, rowSpan) },
                    onRemove = {
                        dismissPicker()
                        onRemoveGauge(pickingId)
                    },
                    onAdd = { showAddPalette = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (showAddPalette && addableGauges.isNotEmpty()) {
                AddGaugePalette(
                    addable = addableGauges,
                    tileFor = uiState::tileFor,
                    onAdd = { id ->
                        onAddGauge(id)
                        dismissAddPalette()
                    },
                    onDismiss = dismissAddPalette,
                )
            }
        }
    }
}

/**
 * OBD-63: the spanning-grid tile layout — the Phase-2 replacement for the pre-grid landscape
 * single-`Row` / portrait scrollable-`Column`, extracted out of [GaugeDashboard] so that
 * composable's own body stays focused on Surface/scrim/picker-state plumbing. Tiles are positioned
 * by their [com.revel.obdgauge.app.gauge.grid.GridPlacement] (col/row/colSpan/rowSpan) via
 * [GaugeGrid], which lets gauges sit side-by-side, run wide/tall, and exceed four in count.
 *
 * The layout to render is resolved here: the persisted [gridLayout] if present, otherwise one
 * migrated from [gaugeOrder] ([GridMigration.fromGaugeOrder]) — then repacked into the current
 * orientation's column count ([GridEngine.withColumns]). With no persisted grid (the Phase-2
 * reality — nothing writes one yet), this is always the migration, so a swap that rewrites
 * [gaugeOrder] re-derives a fresh layout with the new id in the same cell: the picker path is
 * unchanged. Each tile is `key`ed by its id *inside* [GaugeGrid] (see its KDoc) — the same stable
 * per-gauge composition identity the pre-grid layout gave, which OBD-47's grow-in and OBD-44's
 * "never remounted across picker mode" both depend on.
 *
 * OBD-65: swapping is now an in-tile [SwapPager] (see `GaugeSlot`) — the picked tile's content
 * becomes a pager of candidate gauges, tapping a candidate page persists the swap via
 * [onSelectCandidate]. The pre-OBD-65 shrink-into-a-carousel animation and its grow-in origin
 * registry are retired (the tile no longer shrinks into a chrome), so this dispatch is plain: each
 * id's [GaugeSlot] renders either the live tile or, while picking, the pager. [placedIds] feeds the
 * pager's candidate list.
 */
@Composable
@Suppress("LongParameterList") // one param per input the per-tile GaugeSlot calls below actually need.
private fun GaugeTileGrid(
    isLandscape: Boolean,
    uiState: DashboardUiState,
    sparklines: Map<String, StateFlow<List<SparklinePoint>>>,
    gaugeOrder: List<GaugeOrderEntry>,
    gridLayout: GridLayout?,
    placedIds: Set<String>,
    pickerTileId: String?,
    thresholds: Map<String, GaugeThresholds>,
    onLongPress: (String) -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (oldId: String, newId: String) -> Unit,
    onSetThreshold: (id: String, thresholds: GaugeThresholds) -> Unit,
) {
    val columns = if (isLandscape) GRID_COLUMNS_LANDSCAPE else GRID_COLUMNS_PORTRAIT
    val layout =
        remember(gridLayout, gaugeOrder, columns) {
            val base = gridLayout ?: GridMigration.fromGaugeOrder(gaugeOrder, columns)
            GridEngine.withColumns(base, columns)
        }

    GaugeGrid(
        columns = columns,
        layout = layout,
        spacing = TILE_SPACING_DP.dp,
        modifier = Modifier.fillMaxSize().padding(TILE_SPACING_DP.dp),
    ) { id ->
        GaugeSlot(
            id = id,
            uiState = uiState,
            sparkline = sparklines[id],
            placedIds = placedIds,
            isPicking = pickerTileId == id,
            thresholds = thresholds,
            onLongPress = { onLongPress(id) },
            onDismissPicker = onDismissPicker,
            onSelectCandidate = { newId -> onSelectCandidate(id, newId) },
            onSetThreshold = onSetThreshold,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Renders one grid slot: while [isPicking], the tile's content becomes an in-place [SwapPager]
 * (OBD-65) that fills the slot's own bounds — the current gauge plus the swap candidates in the
 * stable [candidateGaugesFor] ribbon (over [placedIds]), opened centered on the current gauge
 * (OBD-66) — so swiping to another gauge works at any tile size and the pager (not the tile's tap
 * detector) owns the horizontal drag. Tapping a candidate page persists the swap via
 * [onSelectCandidate]; tapping the current gauge's page or outside dismisses.
 * When NOT picking it dispatches to [BoostTile] (arc + neutral color) for the boost id, [GaugeTile]
 * otherwise — the normal interactive tile whose long-press ([onLongPress]) enters pick mode.
 *
 * OBD-65 retired the pre-existing shrink-into-a-mini-card chrome (OBD-44/46) and the grow-in origin
 * registry (OBD-47): the tile no longer shrinks into a carousel, it is simply replaced by the pager
 * while picking and restored after, so none of that transform machinery is needed. The swap itself
 * is a clean instant replace (the pager's own snap/swipe carries the motion).
 */
@Composable
@Suppress("LongParameterList") // one param per input GaugeSlot's dispatch/callbacks actually need.
private fun GaugeSlot(
    id: String,
    uiState: DashboardUiState,
    sparkline: StateFlow<List<SparklinePoint>>?,
    placedIds: Set<String>,
    isPicking: Boolean,
    thresholds: Map<String, GaugeThresholds>,
    onLongPress: () -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onSetThreshold: (id: String, thresholds: GaugeThresholds) -> Unit,
    modifier: Modifier = Modifier,
) {
    // MINOR M3: placeholder fallback — DashboardUiState.Loading only seeds the four core tiles.
    // B8 (round-1 review): `?: false`, not `?: true` — an id absent from GAUGE_CATALOG entirely
    // is exactly the "cannot vouch for it" case PidCatalog.isVerified treats as unverified.
    val tile =
        uiState.tileFor(id) ?: GaugeTileUiState.placeholder(
            id,
            GAUGE_CATALOG_BY_ID[id]?.label ?: id,
            verified = GAUGE_CATALOG_BY_ID[id]?.verified ?: false,
        )

    // propagateMinConstraints = true: makes the child (tile or pager) fill this Box exactly.
    Box(modifier = modifier, propagateMinConstraints = true) {
        if (isPicking) {
            // candidateGaugesFor returns the stable GAUGE_CATALOG ribbon; SwapPager opens centered
            // on the current gauge at its ribbon slot (OBD-66).
            val pages = remember(id, placedIds) { candidateGaugesFor(id, placedIds) }
            SwapPager(
                slotId = id,
                pages = pages,
                tileFor = uiState::tileFor,
                thresholds = thresholds,
                onDismiss = onDismissPicker,
                onSelect = onSelectCandidate,
                onSetThreshold = onSetThreshold,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            if (id == PidIds.BOOST) {
                BoostTile(tile, sparkline, Modifier, onLongPress, onDismissPicker)
            } else {
                GaugeTile(tile, sparkline, Modifier, onLongPress, onDismissPicker)
            }
            UnverifiedBadgeOverlay(
                tile = tile,
                settled = true,
                rawFrame = uiState.rawFrames[id],
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * One temp/numeric tile: label, large value, threshold-colored background, stale treatment,
 * optional sparkline. [onLongPress] enters pick mode for this tile; [onTap] dismisses some *other*
 * tile's open picker (see [gaugeTileInteraction]).
 */
@Composable
fun GaugeTile(
    state: GaugeTileUiState,
    sparkline: StateFlow<List<SparklinePoint>>? = null,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
) {
    val zoneColor = zoneColor(state.zone)
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    // OBD-66: at/above the danger (RED) threshold the tile pulses its red fill + border.
    val pulseActive = state.zone == ThresholdZone.RED && LocalDangerPulseEnabled.current
    val bgAlpha = dangerPulseAlpha(pulseActive, state.id)
    Box(
        modifier =
            modifier
                .gaugeTileInteraction(state.id, state.zone, onLongPress, onTap)
                .background(zoneColor.copy(alpha = bgAlpha), shape)
                .then(
                    if (pulseActive) {
                        Modifier.border(DANGER_PULSE_BORDER_WIDTH_DP.dp, GaugeRed.copy(alpha = bgAlpha), shape)
                    } else {
                        Modifier
                    },
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
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    Box(
        modifier =
            modifier
                .gaugeTileInteraction(state.id, state.zone, onLongPress, onTap)
                .background(GaugeNeutral.copy(alpha = TILE_BACKGROUND_ALPHA), shape)
                .padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("gauge-${state.id}-label"),
            )
            // OBD-64: the arc yields vertical space to the value. In a tall tile the column's
            // content fits, so weight has no leftover to claim and the arc stays its natural 120dp
            // (pre-OBD-64 look, unchanged). In a SHORT tile — which now happens whenever the grid
            // holds >4 gauges / the "+" cell adds a row — the label + value are measured first and
            // the arc's box shrinks (clipped, `fill = false`) instead of squeezing the value text
            // to zero height, so the PSI number is always readable.
            Box(
                modifier = Modifier.weight(1f, fill = false).clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                BoostArc(psi = state.rawValue)
            }
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

/**
 * OBD-64: the add palette — a scrim + a small sheet listing every gauge not yet on the grid
 * ([addable]), each a live [GaugeMiniCard] (so it "feels alive," matching the swap picker) tagged
 * `gauge-add-option-<id>`. Tapping one calls [onAdd]; tapping the scrim (or the system back
 * gesture, handled by the caller) calls [onDismiss]. The sheet swallows its own taps so a tap on a
 * card's gap doesn't fall through to the dismiss scrim.
 */
@Composable
private fun AddGaugePalette(
    addable: List<PidDefinition>,
    tileFor: (String) -> GaugeTileUiState?,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag("gauge-add-palette-scrim")
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = ADD_PALETTE_SCRIM_ALPHA))
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = ADD_PALETTE_ELEVATION_DP.dp,
            modifier =
                Modifier
                    .testTag("gauge-add-palette")
                    .padding(TILE_SPACING_DP.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        ) {
            Column(
                modifier = Modifier.padding(TILE_PADDING_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "Add gauge", style = MaterialTheme.typography.titleMedium)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(TILE_SPACING_DP.dp),
                    contentPadding = PaddingValues(top = TILE_SPACING_DP.dp),
                ) {
                    items(addable, key = { it.id }) { pid ->
                        val tile =
                            tileFor(pid.id) ?: GaugeTileUiState.placeholder(pid.id, pid.label, verified = pid.verified)
                        GaugeMiniCard(
                            tile = tile,
                            onClick = { onAdd(pid.id) },
                            modifier = Modifier.width(MINI_CARD_WIDTH_DP.dp).testTag("gauge-add-option-${pid.id}"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * OBD-64: the bottom edit bar shown while a tile is in picker mode — the resize chips, Remove, and
 * (when [canAdd]) an Add control for [currentId], hosted here (over the dashboard) rather than
 * inside the narrow in-slot swap chrome. Swallows its own background taps so tapping the bar's gaps
 * doesn't fall through to the dismiss scrim beneath it.
 */
@Composable
@Suppress("LongParameterList") // one param per span/callback the bar forwards to PickerEditControls.
private fun PickerEditBar(
    currentId: String,
    currentColSpan: Int,
    currentRowSpan: Int,
    canAdd: Boolean,
    onResize: (colSpan: Int, rowSpan: Int) -> Unit,
    onRemove: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = ADD_PALETTE_ELEVATION_DP.dp,
        modifier =
            modifier
                .padding(TILE_SPACING_DP.dp)
                .testTag("gauge-edit-bar")
                .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        PickerEditControls(
            currentId = currentId,
            currentColSpan = currentColSpan,
            currentRowSpan = currentRowSpan,
            canAdd = canAdd,
            onResize = onResize,
            onRemove = onRemove,
            onAdd = onAdd,
            modifier = Modifier.padding(horizontal = TILE_PADDING_DP.dp, vertical = MINI_CARD_SPACING_DP.dp),
        )
    }
}

/**
 * OBD-66: the tile background alpha, breathing between [DANGER_PULSE_MIN_ALPHA] and
 * [DANGER_PULSE_MAX_ALPHA] on a ~1s reverse loop when [active], otherwise the static
 * [TILE_BACKGROUND_ALPHA]. The infinite transition is created ONLY when [active] — an
 * always-running transition would keep every tile's frame clock busy (and hang `waitForIdle`),
 * which is why the caller gates [active] on both the RED zone and [LocalDangerPulseEnabled].
 */
@Composable
private fun dangerPulseAlpha(
    active: Boolean,
    id: String,
): Float {
    if (!active) return TILE_BACKGROUND_ALPHA
    val transition = rememberInfiniteTransition(label = "gauge-danger-pulse-$id")
    val alpha by transition.animateFloat(
        initialValue = DANGER_PULSE_MIN_ALPHA,
        targetValue = DANGER_PULSE_MAX_ALPHA,
        animationSpec = infiniteRepeatable(tween(DANGER_PULSE_PERIOD_MS), RepeatMode.Reverse),
        label = "gauge-danger-pulse-alpha-$id",
    )
    return alpha
}

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
                verified = true,
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
                verified = false,
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
                verified = true,
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
                verified = true,
            ),
        connection = LinkState.Ready,
    )
