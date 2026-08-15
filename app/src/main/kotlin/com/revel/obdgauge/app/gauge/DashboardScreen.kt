// OBD-64 adds the add-cell, add-palette, and picker edit-bar composables to this dashboard file,
// nudging it past detekt's per-file function count — they're all one screen's cohesive chrome, so
// suppressing here (as GaugePicker.kt already does for its own picker primitives) keeps the feature
// readable in one place rather than scattering it across files for the counter's sake.
@file:Suppress("TooManyFunctions")

package com.revel.obdgauge.app.gauge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
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

// internal (not private): GaugePicker.kt's GaugePickerChrome/pickerShrink* helpers reuse these so
// the picker frame and the OBD-44 shrink target both match the tile's own corner
// radius/padding/background exactly — "still THAT tile" (issues/OBD-42.md).
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
    onSettingsClick: () -> Unit = {},
    onSwapGauge: (oldId: String, newId: String) -> Unit = { _, _ -> },
    onAddGauge: (id: String) -> Unit = {},
    onRemoveGauge: (id: String) -> Unit = {},
    onResizeGauge: (id: String, colSpan: Int, rowSpan: Int) -> Unit = { _, _, _ -> },
    onConnect: (() -> Unit)? = null,
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
                    GaugeTileGrid(
                        isLandscape = maxWidth >= maxHeight,
                        uiState = uiState,
                        sparklines = sparklines,
                        gaugeOrder = gaugeOrder,
                        gridLayout = gridLayout,
                        placedIds = placedIds,
                        pickerTileId = pickerTileId,
                        onLongPress = { id -> pickerTileId = id },
                        onDismissPicker = dismissPicker,
                        onSelectCandidate = { oldId, newId -> onSwapGauge(oldId, newId) },
                    )
                }
            }
            // OBD-64: the whole edit surface for the tile being picked — resize chips, Remove, and
            // Add — a compact bar over the dashboard, NOT crammed into the narrow in-slot swap
            // chrome (which can't fit them without clipping in a 4-column landscape tile). The
            // in-slot swap carousel (OBD-42/44) is untouched. Keeping Add here (rather than as an
            // always-visible "+" grid cell) is what keeps the normal, non-editing dashboard clean:
            // it renders exactly the placed gauges, at full height.
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
 * OBD-47: [growOrigins] is what lets the freshly-mounted id still animate in like a continuation
 * of the tap that caused it, despite that teardown/remount — it's a plain `remember`ed map (not
 * `key(id)`-scoped, so it's the SAME instance across a swap, unlike anything declared inside one
 * tile's own subtree) from an incoming gauge id to the on-screen rect its swap should grow FROM.
 * `GaugeSlot` writes into it (via [recordGrowOrigin]) the instant a candidate is tapped —
 * synchronously, in the same call as the `onSelectCandidate` that eventually mutates
 * [gaugeOrder] — so the entry is always present by the time (this frame or several frames later,
 * depending on how fast persistence round-trips) that id's own `GaugeSlot` actually mounts; and
 * reads out of it (via [consumeGrowOrigin], a read-and-remove) exactly once at that mount. See
 * `GaugeSlot`'s KDoc for the rest of the grow-in mechanics.
 */
@Composable
@Suppress("LongParameterList") // one param per input the per-tile GaugeSlot calls below actually need.
private fun GaugeTileGrid(
    isLandscape: Boolean,
    uiState: DashboardUiState,
    sparklines: Map<String, StateFlow<List<SparklinePoint>>>,
    gaugeOrder: List<GaugeOrderEntry>,
    gridLayout: GridLayout?,
    placedIds: List<String>,
    pickerTileId: String?,
    onLongPress: (String) -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (oldId: String, newId: String) -> Unit,
) {
    // OBD-47: plain (non-snapshot) map — writes only ever need to be visible to the READ that
    // happens inside a later `remember(id) { }` at that same id's own mount, never to drive
    // recomposition on their own, so there is nothing a SnapshotStateMap would buy here.
    val growOrigins = remember { mutableMapOf<String, Rect>() }
    val recordGrowOrigin: (id: String, boundsInRoot: Rect) -> Unit = { id, bounds -> growOrigins[id] = bounds }
    val consumeGrowOrigin: (id: String) -> Rect? = { id -> growOrigins.remove(id) }

    val columns = if (isLandscape) GRID_COLUMNS_LANDSCAPE else GRID_COLUMNS_PORTRAIT
    val layout =
        remember(gridLayout, gaugeOrder, columns) {
            val base = gridLayout ?: GridMigration.fromGaugeOrder(gaugeOrder, columns)
            GridEngine.withColumns(base, columns)
        }
    // OBD-64: `placedIds` (the persisted set) drives the swap candidate math. The normal grid
    // renders exactly the placed gauges — no synthetic "+" cell — so the non-editing dashboard is
    // the clean Phase-2 look (4 tiles → one full-height landscape row). Add lives behind long-press
    // in the edit bar (see GaugeDashboard).
    val placedIdSet = remember(placedIds) { placedIds.toSet() }

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
            placedIds = placedIdSet,
            isPicking = pickerTileId == id,
            onLongPress = { onLongPress(id) },
            onDismissPicker = onDismissPicker,
            onSelectCandidate = { newId -> onSelectCandidate(id, newId) },
            recordGrowOrigin = recordGrowOrigin,
            consumeGrowOrigin = consumeGrowOrigin,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Dispatches to [BoostTile] for the boost id (arc + neutral color), [GaugeTile] otherwise — and,
 * when [isPicking] is true, layers [GaugePickerChrome] (OBD-42's carousel of OTHER candidates)
 * behind it. Unlike the pre-OBD-44 version, there is no [AnimatedContent] swap between "normal
 * tile" and "picker mode" here: exactly ONE [GaugeTile]/[BoostTile] instance is composed for
 * [id], always — OBD-44's AC ("single surface, no content pop/crossfade... stays live mid-
 * shrink") requires that the composable rendering the live value is never torn down and remounted
 * across the picker-mode boundary. What changes is purely visual: [progress] (0 normal → 1 fully
 * picking) drives that one instance's own [pickerShrinkLayer] transform from filling this whole
 * slot down to sitting inside [currentSlotBounds] — the picker chrome's reserved slot for the
 * current gauge, measured via `onGloballyPositioned` and converted into this Box's own local
 * coordinate space (via [outerCoordinates]) so the two composables agree on where "the mini-card
 * position" actually is on screen. A swap-select (picking a DIFFERENT candidate) is *not*
 * animated across the tile-identity boundary; see [GaugeTileGrid]'s KDoc for why — that path
 * keeps OBD-42's local "rise" treatment entirely inside [GaugePickerChrome].
 *
 * ### OBD-47: swap-in grow animation
 * A tap in [GaugePickerChrome]'s carousel doesn't just call [onSelectCandidate] — it fires
 * [recordGrowOrigin] first (via `onSelectCandidate` below wrapping both), stashing the tapped
 * candidate's own on-screen rect (or, if that's somehow unavailable, this slot's current-card
 * ghost rect — see [currentSlotBoundsInRoot] below — never a pop) in [GaugeTileGrid]'s
 * `growOrigins` registry, keyed by the id about to be swapped IN. That id's own `GaugeSlot`
 * instance — a fresh composable mount, `key(id)`-torn-down-and-rebuilt, per this function's own
 * KDoc above — reads it back exactly once via [consumeGrowOrigin] at its own `remember(id) { }`,
 * i.e. once per mount, never replayed by a later recomposition of the SAME id (its own long-press
 * included) or corrupted by some OTHER slot's unrelated swap (registry keys are per-id).
 *
 * That captured rect is in Compose-ROOT space (survives the source subtree's teardown, unlike a
 * live `LayoutCoordinates` reference, which [GaugePickerChrome]'s own KDoc explains further) —
 * [growTargetBoundsInLocalSpace] converts it into THIS tile's own local frame once its
 * [tileCoordinates] are known, the same frame [pickerShrinkLayer] itself needs. [growAnimatable]
 * then plays [progress] 1→0 over it using the IDENTICAL [rememberPickerShrinkAnimationSpec] (same
 * 300 ms, same easing, same animator-scale-0 `snap()`) the long-press shrink uses — driving the
 * SAME [pickerShrinkLayer]/[pickerShrinkContentCounterScale]/[pickerShrinkVisuals]/
 * [pickerShrinkBorder] stack [GaugeTile]/[BoostTile] already apply for the shrink direction, just
 * fed a different (progress, targetBounds) pair while a grow is in flight. [GaugePickerChrome]
 * itself is NOT rendered during this — its own mount condition stays keyed to [progress]
 * (renamed nowhere; still exactly [isPicking]'s shrink progress), which is `0f` the whole time a
 * freshly-mounted tile is only growing, never picking.
 *
 * Long-press during grow-in is IGNORED (not queued): [effectiveOnLongPress] below no-ops while
 * [isGrowingIn] is true, so a long-press that lands mid-grow is simply swallowed — the tile
 * finishes growing to full size first; the user can long-press again once it's settled. Chosen
 * over queueing because queueing would mean a picker opening on its own some ~300 ms after an
 * input the user may not even remember giving, which reads as the UI acting unprompted; ignoring
 * it costs nothing but a repeat tap.
 */
@Composable
@Suppress("LongParameterList") // one param per input GaugeSlot's dispatch/callbacks actually need.
private fun GaugeSlot(
    id: String,
    uiState: DashboardUiState,
    sparkline: StateFlow<List<SparklinePoint>>?,
    placedIds: Set<String>,
    isPicking: Boolean,
    onLongPress: () -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    recordGrowOrigin: (id: String, boundsInRoot: Rect) -> Unit,
    consumeGrowOrigin: (id: String) -> Rect?,
    modifier: Modifier = Modifier,
) {
    // MINOR M3: placeholder fallback — see GaugeSlot's own KDoc file history (OBD-42/44 review
    // rounds) for why: DashboardUiState.Loading only seeds the four core tiles.
    // B8 (round-1 review): `?: false`, not `?: true` — an id absent from GAUGE_CATALOG entirely
    // is exactly the "cannot vouch for it" case PidCatalog.isVerified treats as unverified; this
    // now matches GaugeTileUiState's own default (see its KDoc).
    val tile =
        uiState.tileFor(id) ?: GaugeTileUiState.placeholder(
            id,
            GAUGE_CATALOG_BY_ID[id]?.label ?: id,
            verified = GAUGE_CATALOG_BY_ID[id]?.verified ?: false,
        )
    val progress = rememberPickerShrinkProgress(isPicking, label = "gauge-picker-shrink-$id")

    // fullSize/currentSlotBounds: this tile's own coordinates and the chrome ghost's, converted
    // into them — unchanged OBD-44/46 machinery. currentSlotBoundsInRoot/incomingGrowOrigin/
    // growProgress are OBD-47's own additions — all explained in this function's own KDoc above.
    var tileCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var currentSlotBounds by remember { mutableStateOf<Rect?>(null) }
    var currentSlotBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
    val incomingGrowOrigin = remember(id) { consumeGrowOrigin(id) }
    val growProgress = rememberGaugeGrowInProgress(id, incomingGrowOrigin)
    val isGrowingIn = growProgress > 0f

    // propagateMinConstraints = true: makes GaugeTile/BoostTile below fill this Box exactly like
    // the pre-OBD-44 AnimatedContent used to (Box otherwise loosens a plain child's constraints).
    Box(modifier = modifier, propagateMinConstraints = true) {
        if (progress > 0f) {
            GaugePickerChrome(
                currentId = id,
                currentTile = tile,
                candidates =
                    remember(id, placedIds) {
                        candidateGaugesFor(id, placedIds).filterNot { it.id == id }
                    },
                tileFor = uiState::tileFor,
                alpha = progress,
                onDismiss = onDismissPicker,
                // Fallback to the current-card ghost's own root-space rect if the tapped
                // candidate somehow never reported its own bounds — never a pop (this fn's KDoc).
                onSelectCandidate = { newId, tappedBoundsInRoot ->
                    recordGrowOrigin(newId, tappedBoundsInRoot ?: currentSlotBoundsInRoot ?: Rect.Zero)
                    onSelectCandidate(newId)
                },
                isPicking = isPicking,
                onCurrentSlotPositioned = { slotCoordinates ->
                    val (localBounds, rootBounds) = currentSlotBoundsFrom(tileCoordinates, slotCoordinates)
                    localBounds?.let { currentSlotBounds = it }
                    rootBounds?.let { currentSlotBoundsInRoot = it }
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        val fullSize = tileCoordinates?.size?.toSize()
        val growTargetBounds = growTargetBoundsInLocalSpace(incomingGrowOrigin, tileCoordinates)
        val liveMod = Modifier.onGloballyPositioned { tileCoordinates = it }
        // Either/or, never a blend (this fn's KDoc): isPicking stays false — so `progress` stays
        // 0 — for as long as isGrowingIn is true, since long-presses are ignored while growing.
        val progEff = if (isGrowingIn) growProgress else progress
        val boundsEff = if (isGrowingIn) growTargetBounds else currentSlotBounds
        val pressEff: () -> Unit = if (isGrowingIn) ({}) else onLongPress
        if (id == PidIds.BOOST) {
            BoostTile(tile, sparkline, liveMod, pressEff, onDismissPicker, isPicking, progEff, fullSize, boundsEff)
        } else {
            GaugeTile(tile, sparkline, liveMod, pressEff, onDismissPicker, isPicking, progEff, fullSize, boundsEff)
        }

        UnverifiedBadgeOverlay(
            tile = tile,
            settled = progEff == 0f,
            rawFrame = uiState.rawFrames[id],
            modifier = Modifier.matchParentSize(),
        )
    }
}

/**
 * Review round-1 M3: a test-only hook, no-op by default (production code never overrides it —
 * `LocalGaugeTileMountProbe.current` is only ever assigned in `GaugeSwapPickerTest`). `GaugeTile`/
 * `BoostTile` fire it exactly once per `remember { }` — i.e. once per composition MOUNT, never on
 * a plain in-place recomposition — so a test can assert the count stays at 1 across a long-press +
 * dismiss cycle, directly pinning OBD-44's core architectural guarantee (`GaugeSlot`'s KDoc):
 * this composable is never torn down and remounted across the picker-mode boundary. Guards
 * against a regression as narrow as wrapping either tile's call site in `key(isPicking) { }`,
 * which `waitForIdle()`-based value/tag assertions alone would not catch (the value would still
 * eventually read correctly after a fresh remount — only the *liveness-during-the-animation* and
 * *never-recreated* properties would be lost).
 */
internal val LocalGaugeTileMountProbe = compositionLocalOf<() -> Unit> { {} }

/**
 * One temp/numeric tile: label, large value, threshold-colored background, stale treatment,
 * optional sparkline. OBD-44: also the single composable instance that plays the picker-mode
 * shrink/grow for its own tile — see [GaugeSlot]'s KDoc. [isPicking]/[shrinkProgress]/[fullSize]/
 * [targetBounds] are all `false`/`0f`/`null`/`null` by default so every pre-OBD-44 call site
 * (including [DashboardScreenshotTest]'s two references, which never enter picker mode) renders
 * byte-identical to before: [pickerShrinkLayer] and the picker-aware tag/interaction switch below
 * both no-op whenever [shrinkProgress] is `0f`.
 */
@Composable
@Suppress("LongParameterList") // one param per OBD-44 shrink input, all defaulted for non-picker call sites.
fun GaugeTile(
    state: GaugeTileUiState,
    sparkline: StateFlow<List<SparklinePoint>>? = null,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    isPicking: Boolean = false,
    shrinkProgress: Float = 0f,
    fullSize: Size? = null,
    targetBounds: Rect? = null,
) {
    val mountProbe = LocalGaugeTileMountProbe.current
    remember { mountProbe() }
    val zoneColor = zoneColor(state.zone)
    val visuals = pickerShrinkVisuals(zoneColor, shrinkProgress, fullSize, targetBounds)
    Box(
        modifier =
            modifier
                .pickerShrinkLayer(shrinkProgress, fullSize, targetBounds, visuals.shape, visuals.elevation)
                .pickerAwareInteraction(state.id, state.zone, isPicking, onLongPress, onTap)
                .background(visuals.backgroundColor, visuals.shape)
                .pickerShrinkBorder(
                    progress = shrinkProgress,
                    fullSize = fullSize,
                    targetBounds = targetBounds,
                    visuals = visuals,
                ).padding(visuals.contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Review round-1 BLOCKER B1: counter-scales pickerShrinkLayer's anisotropic outer
            // squash so this content renders uniformly (no smear) — see its KDoc.
            modifier = Modifier.pickerShrinkContentCounterScale(shrinkProgress, fullSize, targetBounds),
        ) {
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
                modifier = Modifier.testTag(pickerAwareValueTag(state.id, isPicking)),
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
 * [onLongPress]/[onTap] and the OBD-44 shrink params.
 */
@Composable
@Suppress("LongParameterList") // one param per OBD-44 shrink input, all defaulted for non-picker call sites.
private fun BoostTile(
    state: GaugeTileUiState,
    sparkline: StateFlow<List<SparklinePoint>>?,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    isPicking: Boolean = false,
    shrinkProgress: Float = 0f,
    fullSize: Size? = null,
    targetBounds: Rect? = null,
) {
    val mountProbe = LocalGaugeTileMountProbe.current
    remember { mountProbe() }
    val visuals = pickerShrinkVisuals(GaugeNeutral, shrinkProgress, fullSize, targetBounds)
    Box(
        modifier =
            modifier
                .pickerShrinkLayer(shrinkProgress, fullSize, targetBounds, visuals.shape, visuals.elevation)
                .pickerAwareInteraction(state.id, state.zone, isPicking, onLongPress, onTap)
                .background(visuals.backgroundColor, visuals.shape)
                .pickerShrinkBorder(
                    progress = shrinkProgress,
                    fullSize = fullSize,
                    targetBounds = targetBounds,
                    visuals = visuals,
                ).padding(visuals.contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.pickerShrinkContentCounterScale(shrinkProgress, fullSize, targetBounds),
        ) {
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
                modifier = Modifier.testTag(pickerAwareValueTag(state.id, isPicking)),
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
                            isCurrent = false,
                            onClick = { onAdd(pid.id) },
                            tagged = false,
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
