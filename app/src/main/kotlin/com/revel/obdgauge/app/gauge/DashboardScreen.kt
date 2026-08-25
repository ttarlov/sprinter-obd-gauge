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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.gauge.grid.Cell
import com.revel.obdgauge.app.gauge.grid.CellRect
import com.revel.obdgauge.app.gauge.grid.CellSize
import com.revel.obdgauge.app.gauge.grid.GaugeGrid
import com.revel.obdgauge.app.gauge.grid.GridEngine
import com.revel.obdgauge.app.gauge.grid.GridLayout
import com.revel.obdgauge.app.gauge.grid.GridMetrics
import com.revel.obdgauge.app.gauge.grid.GridMigration
import com.revel.obdgauge.app.gauge.grid.GridPlacement
import com.revel.obdgauge.app.recording.RecordingState
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeGreen
import com.revel.obdgauge.app.ui.theme.GaugeNeutral
import com.revel.obdgauge.app.ui.theme.GaugeRed
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle
import com.revel.obdgauge.app.ui.theme.ObdGaugeTheme
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.unit.min as dpMin

// internal (not private): GaugePicker.kt's SwapPagerCard reuses these so each in-tile swap page
// matches the real tile's own corner radius/padding/background exactly — "still THAT tile"
// (issues/OBD-42.md).
internal const val TILE_CORNER_RADIUS_DP = 16
internal const val TILE_PADDING_DP = 16
internal const val TILE_BACKGROUND_ALPHA = 0.18f
private const val TILE_SPACING_DP = 12

// Drag drop-target hysteresis margin, as a fraction of the smaller cell dimension — see
// recomputeDropTarget's KDoc in GaugeTileGrid. OBD-67 round-9 device-verified fix: was 0.2f: user
// report ("takes a bit of work for it to snap into the new spot") plus round-9's switch to
// center-anchored resolution (see recomputeDropTarget) both push toward a smaller dead zone — the
// center-anchor is the bigger lever (it fixes multi-cell spans directly), so this only needed a
// modest trim, not removal: 0.2 was originally sized to kill the round-4 44%-of-frames flip-
// thrashing bug, and cutting it too far risks bringing that back.
private const val HYSTERESIS_FRACTION = 0.12f

// OBD-64: add-palette / edit-bar chrome.
private const val ADD_PALETTE_SCRIM_ALPHA = 0.6f
private const val ADD_PALETTE_ELEVATION_DP = 6

// OBD-63: grid width per orientation. Landscape (dash-mount primary) is 4 wide so the migrated
// default (4 core gauges, 1×1) fills one row exactly like the pre-grid dashboard; portrait is 2
// wide so tiles stay legible when stacked.
private const val GRID_COLUMNS_LANDSCAPE = 4
private const val GRID_COLUMNS_PORTRAIT = 2

// OBD-68 (was "the ONE canonical column count the single persisted grid was always stored at,
// repacked per orientation at render time" under OBD-62/64/67 — see the round-4 pivot note in
// `issues/OBD-67.md`): freeform placement persists a layout PER column count instead
// (`AppSettings.gridLayoutsByColumns`), so there are now two required counts, not one. Both stay
// `internal` (not `private`, unlike GRID_COLUMNS_LANDSCAPE/PORTRAIT above, which they otherwise
// duplicate) because `DashboardViewModel.kt` — a different file, same package — needs them to seed
// and address both stored layouts. GRID_CANONICAL_COLUMNS keeps its name/value (still landscape's
// count, still what a caller needing "just one representative layout" — e.g. `placedIds`'s
// gauge-set queries, where any stored layout's id set is equally valid — reaches for by default).
internal const val GRID_CANONICAL_COLUMNS = 4
internal const val GRID_PORTRAIT_COLUMNS = 2

// Gear glyph for the settings entry point — plain text/emoji, matching this codebase's
// icon-free style (no material-icons dependency).
private const val SETTINGS_GLYPH = "⚙"

/** OBD-68: which empty cell, in which orientation's layout, a "＋" add-palette open targets. */
private data class AddAtTarget(
    val cell: Cell,
    val columns: Int,
)

/**
 * OBD-77: which gauge the floating editor card is open for, and the tile's own root-space rect at
 * the moment its ⚙ badge was tapped — the start bounds the card grows out of (and collapses back
 * into). Captured from the tile's actual laid-out bounds rather than recomputed from
 * `GridMetrics.placementRect`: the tile's rect on SCREEN also depends on the header row's height,
 * the grid's padding, and the current scroll offset, all of which `boundsInRoot()` already
 * accounts for and none of which the overlay should have to re-derive.
 */
private data class EditorTarget(
    val id: String,
    val startBounds: Rect,
)

/**
 * OBD-77: a `GaugeSlot`'s last laid-out root-space rect. A plain, deliberately NON-observable
 * holder — see the `slotBounds` comment in `GaugeSlot` for why writing this from
 * `onGloballyPositioned` must not recompose anything.
 */
private class SlotBounds {
    var value: Rect = Rect.Zero
}

/**
 * OBD-67 round-12: a pending optimistic grid mutation — [layout] is what a drag-drop or a resize
 * ALREADY computed synchronously (see [GaugeDashboard]'s own KDoc on `optimisticGrid` for why),
 * shown immediately for [columns] while the matching async persist (`onMoveGauge`/
 * `onResizeGauge`) is still in flight. One shared slot for both mutations (rather than two
 * independent ones, as round-9's drag-only version had) so a drag and a resize in quick
 * succession can never go stale relative to each other — the second mutation always reads
 * whatever the first one just optimistically applied, not a not-yet-confirmed persisted value.
 */
private data class OptimisticGrid(
    val columns: Int,
    val layout: GridLayout,
)

// OBD-66: the danger-zone pulse. A tile whose live value is at/above its RED threshold breathes
// its red background/border between these alphas on a ~1s reverse loop — attention-grabbing at a
// glance off a dash mount without the strobe of a hard on/off flash.
// internal (not private): OBD-70's RecordingControls.kt reuses this period for the recording
// indicator's own red-dot pulse, so both "something needs attention" pulses on this dashboard
// breathe at the same rate — visual consistency, not a coincidence.
internal const val DANGER_PULSE_PERIOD_MS = 900
private const val DANGER_PULSE_MIN_ALPHA = 0.18f
private const val DANGER_PULSE_MAX_ALPHA = 0.55f
private const val DANGER_PULSE_BORDER_WIDTH_DP = 2

// OBD-70: how often the Record control's mm:ss elapsed timer refreshes while recording.
private const val RECORDING_ELAPSED_TICK_MS = 1_000L

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
 * @param gridLayoutsByColumns OBD-68 (was a single `GridLayout?` under OBD-63/67 — see
 *   `issues/OBD-67.md`'s round-4 pivot note): the persisted per-column-count spanning-grid layouts
 *   (`AppSettings.gridLayoutsByColumns`), keyed by column count — landscape and portrait persist
 *   independent freeform arrangements. Empty (or missing an entry for a given column count) is
 *   handled by deriving from [gaugeOrder] via [GridMigration.fromGaugeOrder], so a fresh/old
 *   install renders exactly as before. Positions and spans of tiles come from here (per
 *   orientation); [gaugeOrder] still drives visibility and the swap picker's candidate list.
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
// LongMethod/CyclomaticComplexMethod: the Box hosts the scrim, tile grid, edit-bar, and
// add-palette overlays — one screen's worth of sibling overlays whose shared picker/add/rearrange
// state must live in this single scope (OBD-64, OBD-67).
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
fun GaugeDashboard(
    uiState: DashboardUiState,
    modifier: Modifier = Modifier,
    gaugeOrder: List<GaugeOrderEntry> = DEFAULT_GAUGE_ORDER,
    gridLayoutsByColumns: Map<Int, GridLayout> = emptyMap(),
    thresholds: Map<String, GaugeThresholds> = ThresholdConfig.seed,
    // OBD-72: per-gauge render style + scale bounds — both keyed by gauge id, the same shape
    // [thresholds] already is. Missing/default renders GaugeRenderStyle.DIGITAL against
    // GaugeScaleDefaults.seed, so a caller that doesn't pass these (every pre-OBD-72 call site,
    // every preview) renders exactly as before.
    renderStyles: Map<String, GaugeRenderStyle> = emptyMap(),
    scales: Map<String, GaugeScale> = GaugeScaleDefaults.seed,
    onSettingsClick: () -> Unit = {},
    onSwapGauge: (oldId: String, newId: String) -> Unit = { _, _ -> },
    onAddGauge: (id: String) -> Unit = {},
    onRemoveGauge: (id: String) -> Unit = {},
    onResizeGauge: (id: String, colSpan: Int, rowSpan: Int, columns: Int) -> Unit = { _, _, _, _ -> },
    onSetThreshold: (id: String, thresholds: GaugeThresholds) -> Unit = { _, _ -> },
    onSetRenderStyle: (id: String, style: GaugeRenderStyle) -> Unit = { _, _ -> },
    onMoveGauge: (id: String, col: Int, row: Int, columns: Int) -> Unit = { _, _, _, _ -> },
    onAddGaugeAt: (id: String, col: Int, row: Int, columns: Int) -> Unit = { _, _, _, _ -> },
    onConnect: (() -> Unit)? = null,
    dangerPulseEnabled: Boolean = true,
    // OBD-70: the Record control's state and actions — see RecordingControls.kt/RecordControl.
    // Idle default so a caller that doesn't pass any of this (every pre-OBD-70 call site, every
    // preview) renders exactly as before.
    recordingState: RecordingState = RecordingState.Idle,
    onStartRecording: () -> Unit = {},
    onStopRecording: () -> Unit = {},
    // Test/screenshot seam, same idea as dangerPulseEnabled: production ticks off the real wall
    // clock, a Roborazzi reference overrides this to a fixed value so the elapsed mm:ss it
    // renders never depends on how long the test itself took to run.
    elapsedClock: () -> Long = { System.currentTimeMillis() },
) {
    // OBD-64/68: the ids actually placed on the grid — the single source of truth for which
    // gauges show, which the add-palette and swap-picker candidate lists both key off. ANY stored
    // orientation's layout is equally valid for this SET-level query (GridLayoutSet's sync
    // invariant guarantees every stored layout agrees on which ids are present, even though
    // positions/spans differ) — prefers the canonical/landscape entry, falls back to whichever is
    // present, then to a migration of `gaugeOrder` only when nothing is stored at all (old call
    // sites / previews).
    val canonicalLayout =
        remember(gridLayoutsByColumns, gaugeOrder) {
            gridLayoutsByColumns[GRID_CANONICAL_COLUMNS]
                ?: gridLayoutsByColumns.values.firstOrNull()
                ?: GridMigration.fromGaugeOrder(gaugeOrder, GRID_CANONICAL_COLUMNS)
        }
    val placedIds = canonicalLayout.ids
    val addableGauges = remember(placedIds) { addableGaugesFor(placedIds.toSet()) }
    // OBD-68: which column count is actually on screen right now — reported up via SideEffect from
    // inside the BoxWithConstraints below (the only place orientation is known), read here by
    // PickerEditBar's resize/placement wiring, which lives OUTSIDE that scope as a sibling overlay.
    // Defaults to landscape/canonical, matching the dash-mount primary orientation.
    var currentColumns by remember { mutableIntStateOf(GRID_CANONICAL_COLUMNS) }

    // OBD-67 round-9 device-verified fix, round-12 generalized: a drag-drop or a resize commits
    // are both async (onMoveGauge/onResizeGauge → ViewModel → DataStore → StateFlow → this
    // composable's own `gridLayoutsByColumns` prop) — device report (round 9): "the animation
    // goes back to the gauge's ORIGINAL place and then snaps into the new place, instead of
    // snapping right as you release it." `optimisticGrid` closes that gap for BOTH mutations now:
    // whichever one just committed computes the SAME pure result the ViewModel will (GridEngine's
    // own `dropAt`/`resizeWithPush`) synchronously and stores it here, rendered immediately in
    // place of the not-yet-caught-up persisted layout — the async persist just confirms what's
    // already on screen. Round 9 first built this INSIDE `GaugeTileGrid`, drag-only; round 10's
    // regression (a resize disappearing behind a stale drag snapshot) was exactly two independent
    // optimistic layers able to go stale relative to each other — this single shared slot at the
    // `GaugeDashboard` level, used by both the drag commit (threaded down into `GaugeTileGrid` via
    // `onOptimisticGrid`) and the resize commit (`PickerEditBar`'s `onResize` below), means a
    // rapid drag-then-resize (or the reverse) always builds on whatever the FIRST one just
    // applied, never a stale pre-confirmation value. Cleared unconditionally on the next real
    // `gridLayoutsByColumns` change, whatever it turns out to be — round 10's own fix for why an
    // exact-match clear condition could get stuck forever.
    var optimisticGrid by remember { mutableStateOf<OptimisticGrid?>(null) }
    LaunchedEffect(gridLayoutsByColumns) {
        optimisticGrid = null
    }
    val effectiveGridLayoutsByColumns =
        optimisticGrid?.let { gridLayoutsByColumns + (it.columns to it.layout) } ?: gridLayoutsByColumns

    // OBD-70: the Record button's accidental-trigger guard, and the live clock its elapsed mm:ss
    // ticks off of. The ticking effect is gated on dangerPulseEnabled — the same "off for a
    // stable single frame" test seam OBD-66's own danger pulse uses — so a Roborazzi reference
    // renders elapsedClock()'s value exactly once and never drifts between record/verify runs.
    var showRecordConfirm by remember { mutableStateOf(false) }
    var recordingNowMillis by remember { mutableLongStateOf(elapsedClock()) }
    LaunchedEffect(recordingState, dangerPulseEnabled) {
        if (recordingState is RecordingState.Recording && dangerPulseEnabled) {
            while (isActive) {
                delay(RECORDING_ELAPSED_TICK_MS)
                recordingNowMillis = elapsedClock()
            }
        }
    }
    val recordingElapsedMillis =
        (recordingState as? RecordingState.Recording)
            ?.let { recording -> (recordingNowMillis - recording.startedAtMillis).coerceAtLeast(0) }
            ?: 0L

    var pickerTileId by remember { mutableStateOf<String?>(null) }
    var showAddPalette by remember { mutableStateOf(false) }
    // OBD-68: which empty cell (and which orientation's layout it belongs to) opened the
    // add-palette, if that's how it was opened (as opposed to the edit-bar's "＋ Add" button,
    // which sets showAddPalette instead and adds wherever there's room in every stored layout —
    // see AddGaugePalette's onAdd below for how the two dispatch).
    var addAtCell by remember { mutableStateOf<AddAtTarget?>(null) }
    // OBD-67: rearrange mode — long-press (any tile, or the container-level detector below on a
    // gap) flips this on; pickerTileId is no longer SET by long-press, only by the ⇄ badge the
    // mode shows on each tile (see GaugeTileGrid/GaugeSlot).
    var rearrangeMode by remember { mutableStateOf(false) }
    // OBD-77: the ⚙ badge no longer routes through pickerTileId at all — swap and edit are now
    // fully independent states, so opening the editor can never disturb (or be disturbed by) the
    // in-tile swap carousel. `editorClosing` keeps the overlay composed through its collapse
    // animation; GaugeEditorOverlay's own onCollapsed is what finally clears editorTarget.
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
    var editorClosing by remember { mutableStateOf(false) }
    val dismissPicker: () -> Unit = { pickerTileId = null }
    val dismissEditor: () -> Unit = { if (editorTarget != null) editorClosing = true }
    val dismissAddPalette: () -> Unit = {
        showAddPalette = false
        addAtCell = null
    }
    val exitRearrange: () -> Unit = { rearrangeMode = false }
    BackHandler(enabled = editorTarget != null, onBack = dismissEditor)
    BackHandler(enabled = pickerTileId != null, onBack = dismissPicker)
    BackHandler(enabled = showAddPalette || addAtCell != null, onBack = dismissAddPalette)
    // Only armed once nothing more specific (an open swap picker, the floating editor, the add
    // palette) is showing — so back closes those layers first and exits the whole mode last,
    // without depending on BackHandler registration order between this and the three above.
    BackHandler(
        enabled =
            rearrangeMode &&
                pickerTileId == null &&
                editorTarget == null &&
                !showAddPalette &&
                addAtCell == null,
        onBack = exitRearrange,
    )

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
        // OBD-77: same self-heal for the floating editor — a gauge removed (or swapped out) from
        // under an open editor would otherwise leave a card editing an id that is no longer on
        // the board, with an armed scrim and BackHandler over it.
        if (editorTarget?.id?.let { it !in placedIds } == true) {
            editorTarget = null
            editorClosing = false
        }
        // OBD-67: an empty board has nothing left to rearrange — leaving the mode on would strand
        // the Done button/backdrop/scrim over a blank grid.
        if (rearrangeMode && placedIds.isEmpty()) {
            rearrangeMode = false
        }
    }
    // OBD-64: if a mutation (or an add) leaves nothing addable, the edit bar's Add button hides —
    // close a palette left open rather than showing an empty sheet with a live-armed back handler.
    LaunchedEffect(addableGauges.isEmpty()) {
        if (addableGauges.isEmpty()) {
            showAddPalette = false
            addAtCell = null
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            // OBD-67 round-2 review fix (device-verified CRITICAL): this used to be a single
            // ALWAYS-MOUNTED full-screen Box handling both long-press-to-enter AND tap-to-exit —
            // on a real device that made drag-to-move completely dead. detectTapGestures's own
            // press/movement/long-press-vs-cancel tracking runs on EVERY touch this Box's bounds
            // contain (which, at fillMaxSize(), is every touch on the whole dashboard, tile
            // bodies included) regardless of what its onTap/onLongPress callbacks eventually did
            // — the internal `if (rearrangeMode...)` guards only gated the ACTION, not whether
            // this detector's own gesture-recognition machinery ran and raced a tile's own
            // detectDragGestures for the same finger movement. Compose does not guarantee a
            // "topmost sibling wins" ordering between two independent, unrelated pointerInput
            // subtrees the way occlusion would in a view system, so this was starving every drag
            // attempt of the movement deltas its own onDragStart needed.
            //
            // Fix: this Box now ONLY ever exists while NOT already in rearrange mode, so it can
            // never coexist with a tile's own drag pointerInput at all — dragEnabled (which is
            // what attaches detectDragGestures to a tile) requires rearrangeMode to already be
            // true, and this detector is gone by the time that's the case. Long-press-anywhere
            // still works (a tile's own gaugeTileInteraction independently covers long-pressing a
            // TILE; this covers a GAP). Tap-to-exit moved to per-empty-cell boxes in
            // `GaugeTileGrid` (see its KDoc) — small, non-overlapping-with-any-tile hit targets by
            // construction, so they can never re-introduce this same race.
            if (!rearrangeMode) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .testTag("gauge-rearrange-scrim")
                            .pointerInput(Unit) {
                                detectTapGestures(onLongPress = { rearrangeMode = true })
                            },
                )
            }
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
                    // OBD-67 round-8 device-verified fix: rearrange mode's below-fold "＋" empty
                    // cells (see EmptyCellAddButton below) became practically unreachable once
                    // round 5 made tiles full-viewport-height in rearrange mode — a vertical swipe
                    // ANYWHERE on a tile now starts a drag (that's the feature working correctly),
                    // so there is no gesture left to scroll down to them; the user's own words were
                    // "no real way to scroll down to add another gauge unless I make one of the
                    // current gauges smaller." This top-bar button is the always-reachable primary
                    // path: it reuses the exact same `showAddPalette = true` (no target cell) route
                    // the OBD-64 edit-bar's own "＋ Add" chip already uses, which — see
                    // AddGaugePalette's onAdd dispatch below — lands the new gauge via plain
                    // `onAddGauge(id)`, i.e. wherever GridEngine finds room in EVERY stored
                    // orientation's layout (not a specific cell), so it's reachable and synced
                    // regardless of which orientation is on screen when it's tapped. Below-fold
                    // "＋" cells stay as the secondary "add at this exact spot" path, unchanged.
                    // Same always-composed/alpha/invisibleToUser treatment as the Done button
                    // immediately below (and for the identical reason — see that fix's own KDoc):
                    // a conditionally-composed button here would reintroduce exactly the header-
                    // height variance round 6 eliminated, just gated on a different condition.
                    val canAddGauge = rearrangeMode && addableGauges.isNotEmpty()
                    TextButton(
                        onClick = { showAddPalette = true },
                        enabled = canAddGauge,
                        modifier =
                            Modifier
                                .testTag("rearrange-add-button")
                                .alpha(if (canAddGauge) 1f else 0f)
                                .semantics { if (!canAddGauge) invisibleToUser() },
                    ) {
                        Text(text = "＋ Add", style = MaterialTheme.typography.titleMedium)
                    }
                    // OBD-67 round-6 device-verified fix: the explicit exit alongside back and
                    // tap-outside — shown only while in rearrange mode, per the issue spec. But
                    // conditionally COMPOSING it (the round-5-and-earlier approach) meant this Row
                    // held a different number of children, and thus gave ConnectionBanner's
                    // weight(1f) share a different width, in and out of rearrange mode. Squeezed
                    // narrower, the banner's message Text (no maxLines/ellipsis of its own — see
                    // ConnectionBanner's round-6 fix) would soft-wrap — device-observed as "Retry"
                    // stacking into single characters — growing this Row taller and pushing the
                    // grid BoxWithConstraints below down, shrinking its viewport height and thus
                    // (via GridMetrics.cellSize's fill-the-viewport math) every tile, by a further
                    // ~5% on top of round 5's fix. The button is now ALWAYS composed — same slot,
                    // same width, every frame — and only its visibility/interactivity toggle, so
                    // ConnectionBanner's available width (and this Row's height) can never depend
                    // on rearrangeMode at all.
                    TextButton(
                        onClick = exitRearrange,
                        enabled = rearrangeMode,
                        modifier =
                            Modifier
                                .testTag("rearrange-done-button")
                                .alpha(if (rearrangeMode) 1f else 0f)
                                .semantics { if (!rearrangeMode) invisibleToUser() },
                    ) {
                        Text(text = "Done", style = MaterialTheme.typography.titleMedium)
                    }
                    // OBD-70: next to the gear, per this issue's spec.
                    RecordControl(
                        state = recordingState,
                        elapsedMillis = recordingElapsedMillis,
                        onTapIdle = { showRecordConfirm = true },
                        onTapRecording = onStopRecording,
                    )
                    TextButton(onClick = onSettingsClick, modifier = Modifier.testTag("settings-button")) {
                        Text(text = SETTINGS_GLYPH, style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (showRecordConfirm) {
                    RecordConfirmDialog(
                        onConfirm = {
                            showRecordConfirm = false
                            onStartRecording()
                        },
                        onDismiss = { showRecordConfirm = false },
                    )
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val viewportHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
                    val isLandscape = maxWidth >= maxHeight
                    // OBD-68: reports this frame's orientation up to `currentColumns` (declared
                    // above, read by PickerEditBar's resize/placement wiring outside this scope).
                    // Same trivial one-line formula GaugeTileGrid computes for itself right below —
                    // safe to duplicate (unlike CellSize, there's no room for the two to drift).
                    SideEffect { currentColumns = if (isLandscape) GRID_CANONICAL_COLUMNS else GRID_PORTRAIT_COLUMNS }
                    CompositionLocalProvider(LocalDangerPulseEnabled provides dangerPulseEnabled) {
                        GaugeTileGrid(
                            isLandscape = isLandscape,
                            uiState = uiState,
                            gaugeOrder = gaugeOrder,
                            gridLayoutsByColumns = effectiveGridLayoutsByColumns,
                            placedIds = placedIds.toSet(),
                            pickerTileId = pickerTileId,
                            thresholds = thresholds,
                            renderStyles = renderStyles,
                            scales = scales,
                            rearrangeMode = rearrangeMode,
                            editorOpen = editorTarget != null,
                            viewportHeightPx = viewportHeightPx,
                            onLongPress = { rearrangeMode = true },
                            onDismissPicker = dismissPicker,
                            onSelectCandidate = { oldId, newId -> onSwapGauge(oldId, newId) },
                            onSetThreshold = onSetThreshold,
                            onSetRenderStyle = onSetRenderStyle,
                            onOpenSwap = { id -> pickerTileId = id },
                            // OBD-77: ⚙ opens the dashboard-level floating editor card, growing
                            // out of the tile's own bounds — NOT the in-tile flip card any more.
                            onOpenThreshold = { id, bounds ->
                                editorClosing = false
                                editorTarget = EditorTarget(id, bounds)
                            },
                            onRemoveGauge = onRemoveGauge,
                            onMoveGauge = { id, col, row, columns -> onMoveGauge(id, col, row, columns) },
                            onRequestAddAt = { col, row, columns -> addAtCell = AddAtTarget(Cell(col, row), columns) },
                            onOptimisticGrid = { columns, layout -> optimisticGrid = OptimisticGrid(columns, layout) },
                        )
                    }
                }
            }
            // OBD-64: the resize/Remove/Add edit bar for the tile being picked — BUTTONS (tapped,
            // not swiped), so they stay in a compact bar floating over the bottom of the dashboard
            // rather than joining the in-tile swap pager (OBD-65). The pager owns the swap itself;
            // this bar owns size/remove/add. A floating overlay (not the vertical flow) keeps the
            // picked tile full-size so its pager fills the whole slot.
            // OBD-68: the CURRENT orientation's own layout for the picking tile's placement —
            // spans (and positions) are independent per orientation now, so this can no longer
            // read the SET-level `canonicalLayout` above (which may be a different orientation
            // entirely). Falls back to canonicalLayout only if this orientation's layout somehow
            // isn't stored yet (belt-and-suspenders; the ViewModel eager-seeds both). Reads
            // `effectiveGridLayoutsByColumns` (round-12), not the raw prop, so the chip row's own
            // `currentColSpan`/`currentRowSpan` reflect a just-applied optimistic resize (or a
            // just-dropped drag) immediately, not whatever was true before the async persist.
            val pickingId = pickerTileId
            val currentOrientationLayout = effectiveGridLayoutsByColumns[currentColumns] ?: canonicalLayout
            val pickingPlacement = pickingId?.let(currentOrientationLayout::placementFor)
            if (pickingId != null && pickingPlacement != null) {
                PickerEditBar(
                    currentId = pickingId,
                    currentColSpan = pickingPlacement.colSpan,
                    currentRowSpan = pickingPlacement.rowSpan,
                    canAdd = addableGauges.isNotEmpty(),
                    onResize = { colSpan, rowSpan ->
                        // OBD-67 round-12 (user decision — see GridEngine.resizeWithPush's KDoc):
                        // pushes any tile in the way instead of silently rejecting. Applied
                        // OPTIMISTICALLY and SYNCHRONOUSLY, same as a drag's own commit (see
                        // `optimisticGrid`'s KDoc above) — the async `onResizeGauge` call just
                        // confirms what's already rendered by the time this returns.
                        optimisticGrid =
                            OptimisticGrid(
                                currentColumns,
                                GridEngine.resizeWithPush(currentOrientationLayout, pickingId, colSpan, rowSpan),
                            )
                        onResizeGauge(pickingId, colSpan, rowSpan, currentColumns)
                    },
                    onRemove = {
                        dismissPicker()
                        onRemoveGauge(pickingId)
                    },
                    onAdd = { showAddPalette = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if ((showAddPalette || addAtCell != null) && addableGauges.isNotEmpty()) {
                val target = addAtCell
                AddGaugePalette(
                    addable = addableGauges,
                    tileFor = uiState::tileFor,
                    onAdd = { id ->
                        // OBD-68: the edit-bar's "＋ Add" button (showAddPalette, no cell) still
                        // drops the gauge wherever there's room in every stored layout; a
                        // rearrange-mode "＋" tap (addAtCell set) places it at that exact cell in
                        // its own orientation's layout instead (and syncs to the others — see
                        // DashboardViewModel.addGaugeAt).
                        if (target != null) {
                            onAddGaugeAt(id, target.cell.col, target.cell.row, target.columns)
                        } else {
                            onAddGauge(id)
                        }
                        dismissAddPalette()
                    },
                    onDismiss = dismissAddPalette,
                )
            }
            // OBD-77: the floating gauge editor — LAST child of this Box, so its scrim is
            // hit-tested before (and therefore covers) every layer beneath it.
            val editing = editorTarget
            if (editing != null) {
                val pid = GAUGE_CATALOG_BY_ID[editing.id]
                GaugeEditorOverlay(
                    id = editing.id,
                    label = pid?.label ?: editing.id,
                    startBounds = editing.startBounds,
                    unit = pid?.unit ?: MeasurementUnit.FAHRENHEIT,
                    thresholds = thresholds[editing.id] ?: GaugeThresholds(),
                    // Same rule the in-tile editor face uses: the threshold section is only
                    // offered on a temperature-kind gauge; everything else gets style only.
                    hasThresholds = pid?.unit?.kind() == UnitKind.TEMPERATURE,
                    style = renderStyles[editing.id] ?: GaugeRenderStyle.DIGITAL,
                    expanded = !editorClosing,
                    onSetThreshold = { next -> onSetThreshold(editing.id, next) },
                    onSetStyle = { next -> onSetRenderStyle(editing.id, next) },
                    onDismiss = dismissEditor,
                    onCollapsed = {
                        editorTarget = null
                        editorClosing = false
                    },
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
 *
 * OBD-67 additions to the grid host: while [rearrangeMode], owns the [DragController], the
 * hoisted [ScrollState] `GaugeGrid` scrolls with (needed for autoscroll and the overlay's
 * screen-space conversion — see `RearrangeMode.kt`'s KDoc), the measured [CellSize] `GaugeGrid`
 * reports back via `onCellMeasured` (the single source every hit-test/animation/overlay
 * calculation below shares), and the floating dragged-tile overlay itself. All of that is inert
 * when [rearrangeMode] is `false`, so the non-rearrange render path is exactly what it was before
 * this feature.
 *
 * Freeform placement (the model this settled on — see `issues/OBD-67.md`'s pivot note for why
 * the original ordered-reflow spec didn't ship): [layout] renders directly, with an extra
 * `minRows` reserved for one spare empty row so there's always somewhere to drop/add into
 * (`GaugeGrid`'s own `minRows` param); every empty cell across that extent gets a "＋" add
 * affordance ([EmptyCellAddButton], via [onRequestAddAt]); the drag itself resolves a drop
 * cell/validity pair ([GridEngine.canDrop]) rather than a live-reflowing preview, and commits
 * through [onMoveGauge] calling [GridEngine.dropAt] (move, or a same-footprint swap, or a
 * snap-back no-op).
 */
@Composable
// one param/step per input+phase the rearrange wiring below needs.
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun GaugeTileGrid(
    isLandscape: Boolean,
    uiState: DashboardUiState,
    gaugeOrder: List<GaugeOrderEntry>,
    gridLayoutsByColumns: Map<Int, GridLayout>,
    placedIds: Set<String>,
    pickerTileId: String?,
    thresholds: Map<String, GaugeThresholds>,
    renderStyles: Map<String, GaugeRenderStyle>,
    scales: Map<String, GaugeScale>,
    rearrangeMode: Boolean,
    editorOpen: Boolean,
    viewportHeightPx: Float,
    onLongPress: () -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (oldId: String, newId: String) -> Unit,
    onSetThreshold: (id: String, thresholds: GaugeThresholds) -> Unit,
    onSetRenderStyle: (id: String, style: GaugeRenderStyle) -> Unit,
    onOpenSwap: (id: String) -> Unit,
    onOpenThreshold: (id: String, startBounds: Rect) -> Unit,
    onRemoveGauge: (id: String) -> Unit,
    onMoveGauge: (id: String, col: Int, row: Int, columns: Int) -> Unit,
    onRequestAddAt: (col: Int, row: Int, columns: Int) -> Unit,
    onOptimisticGrid: (columns: Int, layout: GridLayout) -> Unit,
) {
    val columns = if (isLandscape) GRID_COLUMNS_LANDSCAPE else GRID_COLUMNS_PORTRAIT
    // OBD-68 (per-orientation persistence — see `issues/OBD-67.md`'s round-4 pivot note): each
    // orientation now has its OWN stored layout, looked up directly by `columns` — no repack for
    // EITHER orientation's own persisted data (the round-3 "repack destroys freeform positions"
    // bug this fixed for landscape only now no longer needs the conditional at all, since portrait
    // has its own real entry too). withColumns only runs as a one-time UI-level fallback if the
    // ViewModel's eager seed (both counts) somehow hasn't landed yet — a cold-start race, not the
    // steady state. OBD-67 round-12: `gridLayoutsByColumns` is `GaugeDashboard`'s own
    // `effectiveGridLayoutsByColumns` — ALREADY merged with any pending optimistic drag/resize
    // override — so `layout` here needs no optimistic layer of its own anymore; see
    // `optimisticGrid`'s KDoc on `GaugeDashboard` for why that moved up a level.
    val layout =
        remember(gridLayoutsByColumns, gaugeOrder, columns) {
            gridLayoutsByColumns[columns] ?: run {
                val seedSource = gridLayoutsByColumns.values.firstOrNull()
                if (seedSource !=
                    null
                ) {
                    GridEngine.withColumns(seedSource, columns)
                } else {
                    GridMigration.fromGaugeOrder(gaugeOrder, columns)
                }
            }
        }
    val density = LocalDensity.current
    val spacingPx = with(density) { TILE_SPACING_DP.dp.toPx() }

    val dragController = rememberDragController()
    val scrollState = rememberScrollState()
    var cell by remember { mutableStateOf(CellSize(0f, 0f)) }

    // OBD-67 round-8: auto-scroll to reveal a newly added gauge when it lands below the fold —
    // the header's new "＋ Add" button (and the below-fold "＋" cells) hand off to
    // `onAddGauge`/`onAddGaugeAt`, which are fire-and-forget calls up to the ViewModel; this is
    // the only place that can react once the new id actually lands back in `layout`. Tracks the
    // previous frame's id set; `null` means "not initialized yet" so the app's own initial id set
    // on first composition is never mistaken for a just-added gauge. Keyed on the SET (not the
    // List `layout.ids` itself) so a pure reorder — same ids, different order, e.g. after a drag —
    // never retriggers this: only an actual membership change does.
    var previousIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(layout.ids.toSet()) {
        val currentIds = layout.ids.toSet()
        val previous = previousIds
        if (previous != null && cell.height > 0f) {
            // Only one gauge can be added per palette selection; if more than one id somehow
            // appeared in the same frame, scrolling to any one of them is harmless and not worth
            // picking among.
            val newPlacement = (currentIds - previous).firstOrNull()?.let(layout::placementFor)
            if (newPlacement != null) {
                val rect = GridMetrics.placementRect(newPlacement, cell, spacingPx)
                scrollState.animateScrollTo(rect.top.roundToInt().coerceAtLeast(0))
            }
        }
        previousIds = currentIds
    }

    // Only one tile can ever be grabbed at a time, and never while a badge-opened picker is
    // showing (the picker's own pager owns horizontal drags on that tile instead) or the OBD-77
    // floating editor is up (its scrim claims taps, but a raw drag would otherwise still reach a
    // tile underneath and start moving the board behind the card).
    val dragEnabled = rearrangeMode && pickerTileId == null && !editorOpen
    LaunchedEffect(dragEnabled) {
        // Leaving rearrange mode, or a badge opening a picker mid-drag, must never strand a
        // floating tile with nothing left to commit or cancel it.
        if (!dragEnabled) dragController.end()
    }

    val draggedId = dragController.draggedId
    // OBD-68: one extra row of empty cells beyond whatever's placed, so there's always somewhere
    // to drop/add into even when the board is packed solid — only reserved in rearrange mode (the
    // normal dashboard should never show a mysteriously taller blank row).
    val visibleRows = if (rearrangeMode) layout.rows + 1 else layout.rows

    // Round-4 review fix (device trace: the old ordered model's targetIndex flipped 1057 times
    // across 9 drags — ~44% of every recompute call — even with the stale-closure bug fixed,
    // because raw per-frame cellAt() has no dead zone: a finger held near a cell boundary, which a
    // careful slow drag invites, flips every time sub-pixel jitter crosses the line). stickyCell
    // is the last cell recomputeDropTarget actually resolved to; while the finger stays within
    // that cell's own rect EXPANDED by a small margin, recompute is a no-op — only a genuine move
    // past the boundary re-resolves and can change the drop target. Keyed on draggedId so each new
    // drag starts with a clean slate. Still relevant under OBD-68's freeform model: the hysteresis
    // is about raw touch jitter, independent of what model interprets the resolved cell.
    var stickyCell by remember(draggedId) { mutableStateOf<Cell?>(null) }

    // OBD-68 (freeform placement — replaces OBD-67's ordered targetIndex/previewLayout reflow
    // before it shipped; see DashboardViewModel.moveGauge's KDoc for the full pivot rationale):
    // resolves the dragged tile's drop cell from where its floating overlay's own CENTER
    // currently sits, then GridEngine.canDrop decides the live highlight's valid/invalid style.
    // Nothing else moves during the drag itself — there is no more preview layout to recompute,
    // only this one cell+validity pair.
    //
    // OBD-67 round-9 device-verified fix: the probe point used to be the tile's own TOP-LEFT
    // corner (`origin`, below) — device report: "takes a bit of work for it to snap into the new
    // spot... for a large tile this makes the user fight" the target. Top-left anchoring means a
    // spanning tile's own top-left corner has to precisely cross into a target cell's own
    // top-left origin — a small, easy-to-miss threshold relative to how far the finger travels
    // for anything wider than 1×1. The tile's own visual CENTER is a far more forgiving target
    // that tracks where the tile visually IS (the round-9 ask's own words), and is a no-op change
    // for the common 1×1 case — center and top-left resolve the same cell almost identically,
    // this just re-anchors the boundary-crossing threshold to feel more natural rather than
    // changing which cell wins.
    fun recomputeDropTarget() {
        val id = dragController.draggedId ?: return
        if (cell.width <= 0f || cell.height <= 0f) return
        val placement = layout.placementFor(id)
        val origin = dragController.fingerContentPos - dragController.grabOffset
        val spanWidthPx = placement?.let { cell.width * it.colSpan + spacingPx * (it.colSpan - 1) } ?: cell.width
        val spanHeightPx = placement?.let { cell.height * it.rowSpan + spacingPx * (it.rowSpan - 1) } ?: cell.height
        val probe = Offset(origin.x + spanWidthPx / 2f, origin.y + spanHeightPx / 2f)
        val margin = HYSTERESIS_FRACTION * min(cell.width, cell.height)
        val sticky = stickyCell
        val stillSticky =
            sticky != null &&
                run {
                    val stickyRect =
                        GridMetrics.placementRect(GridPlacement("", sticky.col, sticky.row), cell, spacingPx)
                    probe.x >= stickyRect.left - margin &&
                        probe.x <= stickyRect.left + stickyRect.width + margin &&
                        probe.y >= stickyRect.top - margin &&
                        probe.y <= stickyRect.top + stickyRect.height + margin
                }
        if (!stillSticky) {
            val at = GridMetrics.cellAt(probe.x, probe.y, cell, spacingPx, columns)
            stickyCell = at
            val valid = GridEngine.canDrop(layout, id, at.col, at.row)
            dragController.updateDropTarget(at, valid)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GaugeGrid(
            columns = columns,
            layout = layout,
            spacing = TILE_SPACING_DP.dp,
            modifier = Modifier.fillMaxSize().padding(TILE_SPACING_DP.dp),
            rearrangeMode = rearrangeMode,
            minRows = visibleRows,
            scrollState = scrollState,
            onCellMeasured = { cell = it },
            // OBD-67 round-5 fix: the picker's "tap outside dismisses" scrim is a sibling that
            // relies on unclaimed taps falling through this grid to reach it (see GaugeGrid's
            // onBackgroundTap KDoc) — that fallthrough breaks once the grid needs to scroll, so
            // this closes the gap directly at the source. Idempotent when no picker is open
            // (dismissPicker just re-sets pickerTileId to the null it already is).
            onBackgroundTap = onDismissPicker,
        ) { id ->
            // OBD-68: `layout` directly — nothing live-reflows during a drag anymore, so the
            // Layout always hard-places every id (dragged one included, though its slot renders as
            // a ghost) at its persisted rect. animatePlacement below still matters for the ONE
            // remaining case a tile's rect can change without ITS OWN drag: the displaced tile in
            // a committed swap glides to its new cell instead of teleporting.
            val targetRect =
                layout.placementFor(id)?.let { GridMetrics.placementRect(it, cell, spacingPx) }
                    ?: CellRect(0f, 0f, 0f, 0f)
            GaugeSlot(
                id = id,
                uiState = uiState,
                placedIds = placedIds,
                isPicking = pickerTileId == id,
                thresholds = thresholds,
                renderStyles = renderStyles,
                scales = scales,
                rearrangeMode = rearrangeMode,
                dragEnabled = dragEnabled,
                isDragged = id == draggedId,
                targetRect = targetRect,
                onLongPress = onLongPress,
                onDismissPicker = onDismissPicker,
                onSelectCandidate = { newId -> onSelectCandidate(id, newId) },
                onSetThreshold = onSetThreshold,
                onSetRenderStyle = onSetRenderStyle,
                onDragStart = { local ->
                    val placement = layout.placementFor(id)
                    if (placement != null) {
                        val rect = GridMetrics.placementRect(placement, cell, spacingPx)
                        dragController.start(
                            id = id,
                            contentPos = Offset(rect.left, rect.top) + local,
                            grabOffset = local,
                        )
                    }
                },
                onDrag = { delta ->
                    dragController.moveBy(delta)
                    recomputeDropTarget()
                },
                onDragEnd = {
                    val id0 = dragController.draggedId
                    val target = dragController.dropCell
                    if (id0 != null && target != null) {
                        // OBD-67 round-9, lifted to GaugeDashboard's shared optimisticGrid in
                        // round-12: apply the drop OPTIMISTICALLY and SYNCHRONOUSLY — the exact
                        // same GridEngine.dropAt the ViewModel's own moveGauge will compute (see
                        // optimisticGrid's KDoc on GaugeDashboard for why the async round-trip
                        // alone produces a visible "flick back to origin" on release, and why this
                        // now reports up through onOptimisticGrid rather than a local var here).
                        onOptimisticGrid(columns, GridEngine.dropAt(layout, id0, target.col, target.row))
                        onMoveGauge(id0, target.col, target.row, columns)
                    }
                    dragController.end()
                },
                onDragCancel = { dragController.end() },
                onRemove = { onRemoveGauge(id) },
                onOpenSwap = { onOpenSwap(id) },
                onOpenThreshold = { bounds -> onOpenThreshold(id, bounds) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // OBD-68: every empty cell across the visible extent (visibleRows, the +1 spare row
        // included) gets a "＋" add affordance — freeform's persistent empty cells are now
        // first-class, not just packing slack. One small Box PER EMPTY CELL, positioned via the
        // exact same GridMetrics math the backdrop's own dashed drop-target squares use, so these
        // can never physically overlap a tile's own bounds — the same non-overlap-by-construction
        // discipline that fixed the round-2 device blocker (see GaugeDashboard's KDoc), now reused
        // for a different affordance. Hidden entirely mid-drag: the drop highlight below takes
        // over that visual real estate while dragging, and there's no "add here" meaning to a cell
        // the user is actively about to drop a tile onto.
        if (rearrangeMode && pickerTileId == null && !dragController.isDragging) {
            Box(modifier = Modifier.fillMaxSize().padding(TILE_SPACING_DP.dp)) {
                for (row in 0 until visibleRows) {
                    for (col in 0 until layout.columns) {
                        if (layout.placementAt(col, row) != null) continue
                        key("add-$col-$row") {
                            val rect = GridMetrics.placementRect(GridPlacement("add", col, row), cell, spacingPx)
                            EmptyCellAddButton(
                                onTap = { onRequestAddAt(col, row, columns) },
                                modifier =
                                    Modifier
                                        .offset {
                                            IntOffset(
                                                rect.left.roundToInt(),
                                                (rect.top - scrollState.value).roundToInt(),
                                            )
                                        }.size(
                                            with(density) { rect.width.toDp() },
                                            with(density) { rect.height.toDp() },
                                        ),
                            )
                        }
                    }
                }
            }
        }

        val floatingId = draggedId
        val floatingPlacement = floatingId?.let(layout::placementFor)
        if (floatingId != null && floatingPlacement != null) {
            val rect = GridMetrics.placementRect(floatingPlacement, cell, spacingPx)
            // Non-scrolling sibling: content-space → screen-space needs scrollState subtracted
            // explicitly here (unlike everything drawn inside GaugeGrid's own scrolled node,
            // which gets that for free) — the "Scroll conversion" risk this issue calls out.
            val topLeftContent = dragController.fingerContentPos - dragController.grabOffset
            val topLeftScreen = topLeftContent - Offset(0f, scrollState.value.toFloat())

            // OBD-68: the live drop-target highlight, drawn UNDER the floating tile at whatever
            // cell it currently resolves to — freeform's replacement for the old neighbor-reflow
            // preview (nothing else moves during the drag anymore). Sized to the DRAGGED tile's
            // own footprint, not a bare 1x1, so a wide tile's highlight matches its real footprint.
            val dropCell = dragController.dropCell
            if (dropCell != null) {
                val dropRect =
                    GridMetrics.placementRect(
                        GridPlacement(
                            "drop",
                            dropCell.col,
                            dropCell.row,
                            floatingPlacement.colSpan,
                            floatingPlacement.rowSpan,
                        ),
                        cell,
                        spacingPx,
                    )
                val validColor = MaterialTheme.colorScheme.primary
                val invalidColor = MaterialTheme.colorScheme.error
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(dropRect.left.roundToInt(), (dropRect.top - scrollState.value).roundToInt())
                            }.size(with(density) { dropRect.width.toDp() }, with(density) { dropRect.height.toDp() })
                            .dropTargetTreatment(dragController.dropValid, validColor, invalidColor)
                            .testTag("gauge-drop-target"),
                )
            }

            val glow = MaterialTheme.colorScheme.primary
            Box(
                modifier =
                    Modifier
                        .offset { IntOffset(topLeftScreen.x.roundToInt(), topLeftScreen.y.roundToInt()) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .draggedTileTreatment(glow)
                        .testTag("gauge-drag-overlay-$floatingId"),
            ) {
                GaugeSlotTileBody(
                    tile = resolveTile(floatingId, uiState),
                    style = renderStyles[floatingId] ?: GaugeRenderStyle.DIGITAL,
                    scale = scales[floatingId] ?: GaugeScaleDefaults.forId(floatingId),
                    gaugeThresholds = thresholds[floatingId] ?: GaugeThresholds(),
                )
            }
        }

        // Round-2 review minor fix: viewportHeightPx (param) is GaugeDashboard's OUTER
        // BoxWithConstraints height, measured before GaugeGrid's own `Modifier.padding
        // (TILE_SPACING_DP.dp)` is applied — but fingerContentPos (and every placementRect it's
        // built from) lives in GaugeGrid's INNER, already-padding-inset content-space. Subtracting
        // the padding here aligns both to the same origin, so the edge bands land where the
        // padded content actually ends, not the outer container.
        val innerViewportHeightPx = viewportHeightPx - 2 * spacingPx
        RearrangeAutoscroll(
            controller = dragController,
            scrollState = scrollState,
            viewportHeightPx = innerViewportHeightPx,
            recomputeTarget = ::recomputeDropTarget,
        )
    }
}

/** Resolves [id] to a real tile from [uiState], or the catalog placeholder — shared by every render site. */
private fun resolveTile(
    id: String,
    uiState: DashboardUiState,
): GaugeTileUiState =
    uiState.tileFor(id) ?: GaugeTileUiState.placeholder(
        id,
        GAUGE_CATALOG_BY_ID[id]?.label ?: id,
        verified = GAUGE_CATALOG_BY_ID[id]?.verified ?: false,
    )

/**
 * Renders one grid slot: while [isPicking], the tile's content becomes an in-place [SwapPager]
 * (OBD-65) that fills the slot's own bounds — the current gauge plus the swap candidates in the
 * stable [candidateGaugesFor] ribbon (over [placedIds]), opened centered on the current gauge
 * (OBD-66) — so swiping to another gauge works at any tile size and the pager (not the tile's tap
 * detector) owns the horizontal drag. Tapping a candidate page persists the swap via
 * [onSelectCandidate]; tapping the current gauge's page or outside dismisses. Only the ⇄ badge
 * reaches this path — OBD-77 moved the ⚙ badge off it entirely ([onOpenThreshold] now reports this
 * slot's own root-space bounds up so the floating editor card can grow out of them).
 *
 * When NOT picking, [rearrangeMode] selects between two non-picking looks:
 * - **Off** (unchanged from OBD-65): dispatches to [BoostTile]/[GaugeTile] as the normal
 *   interactive tile, whose long-press ([onLongPress]) now enters rearrange mode instead of
 *   opening the picker directly (OBD-67 re-homed that).
 * - **On**: the same [BoostTile]/[GaugeTile] visuals but non-interactive (`interactive = false` —
 *   see [GaugeTile]'s KDoc), jiggling ([Modifier.rearrangeJiggle]), animating into any reflowed
 *   position ([Modifier.animatePlacement]), and — when [dragEnabled] — draggable
 *   ([androidx.compose.foundation.gestures.detectDragGestures], wired to [onDragStart]/[onDrag]/
 *   [onDragEnd]/[onDragCancel]). The gesture lives on its own OUTER `Box`, one level up from the
 *   tile body: that node's modifier chain depends only on [dragEnabled] (stable for a drag's whole
 *   duration) and never on [isDragged], so its shape is provably invariant across the flip that
 *   happens the instant a drag starts. [Modifier.rearrangeJiggle]/[Modifier.animatePlacement]/the
 *   dimmed ghost look ([Modifier.dragGhostTreatment]) all live on the INNER child instead — round-2
 *   folded them onto the SAME chain as the gesture, on the theory that Compose's `NodeChain` diffing
 *   would keep the `pointerInput` element in the common subsequence across that flip; a real-device
 *   trace proved it doesn't (those modifiers `if (!enabled) return this`, i.e. literally drop an
 *   element from the chain when [isDragged] flips, which was enough to make Compose treat the whole
 *   chain as structurally different and tear down the gesture's coroutine mid-drag). Splitting the
 *   nodes removes that coupling entirely rather than relying on chain-diffing behavior to hold.
 *   [RearrangeBadges]/[UnverifiedBadgeOverlay] are conditional SIBLINGS of the outer gesture Box,
 *   not gates around which body gets called; the real floating tile is a separate overlay
 *   `GaugeTileGrid` draws once, above the whole grid, which this slot's still-attached gesture
 *   keeps feeding via [onDragStart]/[onDrag] regardless of what this slot itself is showing.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // one param per input GaugeSlot's dispatch/callbacks actually need.
private fun GaugeSlot(
    id: String,
    uiState: DashboardUiState,
    placedIds: Set<String>,
    isPicking: Boolean,
    thresholds: Map<String, GaugeThresholds>,
    renderStyles: Map<String, GaugeRenderStyle>,
    scales: Map<String, GaugeScale>,
    rearrangeMode: Boolean,
    dragEnabled: Boolean,
    isDragged: Boolean,
    targetRect: CellRect,
    onLongPress: () -> Unit,
    onDismissPicker: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onSetThreshold: (id: String, thresholds: GaugeThresholds) -> Unit,
    onSetRenderStyle: (id: String, style: GaugeRenderStyle) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onRemove: () -> Unit,
    onOpenSwap: () -> Unit,
    onOpenThreshold: (startBounds: Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // MINOR M3: placeholder fallback — DashboardUiState.Loading only seeds the four core tiles.
    // B8 (round-1 review): `?: false`, not `?: true` — an id absent from GAUGE_CATALOG entirely
    // is exactly the "cannot vouch for it" case PidCatalog.isVerified treats as unverified.
    val tile = resolveTile(id, uiState)
    // OBD-72: this tile's own render style/scale/thresholds, resolved once here and threaded into
    // whichever body below actually renders (the non-picking tile, or the picker's own live pages
    // via SwapPager's own per-id lookups).
    val gaugeStyle = renderStyles[id] ?: GaugeRenderStyle.DIGITAL
    val gaugeScale = scales[id] ?: GaugeScaleDefaults.forId(id)
    val gaugeThresholds = thresholds[id] ?: GaugeThresholds()

    // OBD-77: this slot's own laid-out rect in root space, kept for the ⚙ badge to hand to the
    // floating editor as its grow-from bounds. Deliberately a PLAIN holder, not snapshot state:
    // `onGloballyPositioned` fires on every layout pass, and writing observable state from there
    // would recompose this slot (and, via the jiggle/drag layers, potentially re-layout it) on a
    // loop. Nothing reads it except the badge's onClick, at which point the latest layout has
    // already run.
    val slotBounds = remember { SlotBounds() }
    // propagateMinConstraints = true: makes the child (tile or pager) fill this Box exactly.
    Box(
        modifier = modifier.onGloballyPositioned { slotBounds.value = it.boundsInRoot() },
        propagateMinConstraints = true,
    ) {
        if (isPicking) {
            // candidateGaugesFor returns the stable GAUGE_CATALOG ribbon; SwapPager opens centered
            // on the current gauge at its ribbon slot (OBD-66).
            val pages = remember(id, placedIds) { candidateGaugesFor(id, placedIds) }
            SwapPager(
                slotId = id,
                pages = pages,
                tileFor = uiState::tileFor,
                thresholds = thresholds,
                renderStyles = renderStyles,
                onDismiss = onDismissPicker,
                onSelect = onSelectCandidate,
                onSetThreshold = onSetThreshold,
                onSetRenderStyle = onSetRenderStyle,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (rearrangeMode) {
            // Round-3 review CRITICAL fix (device-verified via a logcat trace against a
            // real finger drag): the round-2 approach — folding rearrangeJiggle/animatePlacement/
            // the ghost's extra modifier onto the SAME chain as dragModifier's pointerInput — was
            // wrong despite round-2's NodeChain analysis concluding the pointer element would
            // survive. On device, isDragged flipping true still cancelled the in-flight gesture
            // ~15-20ms after every onDragStart, independent of finger movement (one trace had a
            // near-zero rawDelta and still cancelled). rearrangeJiggle/animatePlacement each
            // `if (!enabled) return this`, so when `enabled` (=!isDragged) flips false they DROP an
            // element from the chain entirely — a structural change to everything positioned after
            // them, including dragModifier's pointerInput — and that was apparently enough for
            // Compose to treat it as a different chain and tear down/recreate the pointerInput node
            // rather than reuse it, cancelling detectDragGestures's coroutine mid-flight.
            //
            // Fix: split the gesture host onto its own OUTER node whose modifier chain depends
            // ONLY on [dragEnabled] — stable for a drag's whole duration, since it only changes on
            // mode exit or a badge opening a picker (both already end the drag via the
            // `LaunchedEffect(dragEnabled)` above) — and NEVER on [isDragged]. jiggle/
            // animatePlacement/the ghost move to a separate INNER child, so their structural
            // changes affect a DIFFERENT LayoutNode's chain and can never touch the gesture node's.
            // Round-4 review fix (device trace: target-index thrashing 44% of frames, and
            // initialIndex frozen at a stale value across commits): `Modifier.pointerInput(id)`'s
            // key is `id` alone, which never changes across a tile's whole rearrange-mode session —
            // so once `detectDragGestures`'s coroutine actually launches (the first drag on this
            // tile), it is NEVER relaunched for later drags on the same tile, and its own
            // `awaitEachGesture` loop keeps calling the EXACT onDragStart/onDrag/onDragEnd/
            // onDragCancel closures it captured at that first launch — forever. Those closures
            // (built in GaugeTileGrid) close over `layout`, which is a plain `val` reassigned to a
            // NEW object every time `gridLayout`/`gaugeOrder`/columns changes (i.e. after every
            // commit) — so every DRAG AFTER THE FIRST was reading `initialIndex`/hit-testing against
            // whatever `layout` looked like at the very first drag, not the current one. That
            // explains both symptoms: initialIndex staying frozen across commits, and thrashing
            // where a stale layout's cell/span data disagrees with what's actually rendered under
            // the finger. `rememberUpdatedState` is this codebase's own established fix for exactly
            // this class of bug (see `gaugeTileInteraction`'s `currentOnTap`/`currentOnLongPress`) —
            // it makes the long-lived gesture always dispatch to the LATEST lambda GaugeSlot was
            // most recently called with, which itself closes over GaugeTileGrid's freshest state.
            val currentOnDragStart by rememberUpdatedState(onDragStart)
            val currentOnDrag by rememberUpdatedState(onDrag)
            val currentOnDragEnd by rememberUpdatedState(onDragEnd)
            val currentOnDragCancel by rememberUpdatedState(onDragCancel)
            val gestureModifier =
                if (dragEnabled) {
                    Modifier.pointerInput(id) {
                        detectDragGestures(
                            onDragStart = { offset -> currentOnDragStart(offset) },
                            onDrag = { change, amount ->
                                change.consume()
                                currentOnDrag(amount)
                            },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragCancel() },
                        )
                    }
                } else {
                    Modifier
                }
            Box(modifier = Modifier.fillMaxSize().then(gestureModifier)) {
                val bodyModifier =
                    Modifier
                        .fillMaxSize()
                        .rearrangeJiggle(id, enabled = !isDragged)
                        .animatePlacement(targetRect, enabled = !isDragged)
                        .then(if (isDragged) Modifier.dragGhostTreatment() else Modifier)
                GaugeSlotTileBody(
                    tile,
                    bodyModifier,
                    style = gaugeStyle,
                    scale = gaugeScale,
                    gaugeThresholds = gaugeThresholds,
                )
            }
            if (!isDragged) {
                UnverifiedBadgeOverlay(
                    tile = tile,
                    settled = true,
                    rawFrame = uiState.rawFrames[id],
                    modifier = Modifier.matchParentSize(),
                )
                if (dragEnabled) {
                    // OBD-72: every catalog gauge now has an editor (at minimum the style picker
                    // — GaugeEditorFace's own hasThresholds flag, not this, decides whether the
                    // threshold squares/stepper section ALSO shows), so the gear badge shows for
                    // any real GAUGE_CATALOG id, not just the temperature-kind gauges that used to
                    // gate it.
                    RearrangeBadges(
                        id = id,
                        editable = GAUGE_CATALOG_BY_ID[id] != null,
                        onRemove = onRemove,
                        onSwap = onOpenSwap,
                        onThreshold = { onOpenThreshold(slotBounds.value) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            GaugeSlotTileBody(
                tile,
                Modifier,
                interactive = true,
                onLongPress = onLongPress,
                onTap = onDismissPicker,
                style = gaugeStyle,
                scale = gaugeScale,
                gaugeThresholds = gaugeThresholds,
            )
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
 * The BoostTile-vs-GaugeTile dispatch shared by every render site that just needs the visual tile
 * body: the normal (non-rearrange) tile, rearrange mode's jiggling/draggable tile and its ghost,
 * and `GaugeTileGrid`'s floating drag overlay. [interactive] threads straight to
 * [GaugeTile]/[BoostTile]'s own param — `false` for every OBD-67 render site above, since those
 * supply their own external interaction (a drag gesture, or nothing at all for the ghost/overlay).
 */
@Composable
@Suppress("LongParameterList") // tile/modifier/interactive/onLongPress/onTap/style/scale/thresholds — all load-bearing.
private fun GaugeSlotTileBody(
    tile: GaugeTileUiState,
    modifier: Modifier = Modifier,
    interactive: Boolean = false,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    style: GaugeRenderStyle = GaugeRenderStyle.DIGITAL,
    scale: GaugeScale = GaugeScaleDefaults.forId(tile.id),
    gaugeThresholds: GaugeThresholds = GaugeThresholds(),
) {
    if (tile.id == PidIds.BOOST) {
        BoostTile(tile, modifier, onLongPress, onTap, interactive, style, scale, gaugeThresholds)
    } else {
        GaugeTile(tile, modifier, onLongPress, onTap, interactive, style, scale, gaugeThresholds)
    }
}

/**
 * One temp/numeric tile: label, large value, threshold-colored background, stale treatment.
 * [onLongPress] enters rearrange mode (OBD-67 re-homed the old direct-to-picker
 * behavior); [onTap] dismisses some *other* tile's open picker (see [gaugeTileInteraction]).
 * [interactive] `false` (rearrange mode's own render path — see `GaugeSlot`) skips wiring
 * [gaugeTileInteraction]'s own gesture, since the caller supplies its own drag detector instead;
 * the tile's `testTag`/zone semantics stay attached either way.
 *
 * OBD-72: [style] picks which body renders inside this SAME outer shell — the threshold-tinted,
 * danger-pulsing background/border above is unconditional, so the "coloring is sacred across every
 * style" contract holds for free; only the inner content (digital column vs. needle vs. bar-arc)
 * changes. [scale]/[gaugeThresholds] feed the needle/bar-arc bodies' own zone-arc/segment coloring
 * (`NeedleGaugeBody`/`BarArcGaugeBody`) and are unused by the [GaugeRenderStyle.DIGITAL] default.
 */
@Composable
@Suppress("LongParameterList") // state/modifier/onLongPress/onTap/interactive/style/scale/thresholds — load-bearing.
fun GaugeTile(
    state: GaugeTileUiState,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    interactive: Boolean = true,
    style: GaugeRenderStyle = GaugeRenderStyle.DIGITAL,
    scale: GaugeScale = GaugeScaleDefaults.forId(state.id),
    gaugeThresholds: GaugeThresholds = GaugeThresholds(),
) {
    val zoneColor = zoneColor(state.zone)
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    // OBD-66: at/above the danger (RED) threshold the tile pulses its red fill + border.
    val pulseActive = state.zone == ThresholdZone.RED && LocalDangerPulseEnabled.current
    val bgAlpha = dangerPulseAlpha(pulseActive, state.id)
    Box(
        modifier =
            modifier
                .gaugeTileInteraction(state.id, state.zone, onLongPress, onTap, interactive)
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
        when (style) {
            GaugeRenderStyle.DIGITAL -> DigitalGaugeBody(state)
            GaugeRenderStyle.NEEDLE ->
                NeedleGaugeBody(state, scale, gaugeThresholds, modifier = Modifier.fillMaxSize())
            GaugeRenderStyle.BAR_ARC ->
                BarArcGaugeBody(state, scale, gaugeThresholds, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * [GaugeRenderStyle.DIGITAL]'s body — label, big value, optional stale text — extracted out of
 * [GaugeTile] (LongMethod) rather than trimmed down, since every line here is genuinely part of
 * that one style's content.
 *
 * OBD-72 device fix: every text size here is derived from this composable's own measured
 * dimension ([BoxWithConstraints]) via [scaledTextSize] rather than a fixed `sp` — see
 * `GaugeTextScale.kt`'s file KDoc for why (this was a pre-existing bug, not new in OBD-72).
 */
@Composable
private fun DigitalGaugeBody(state: GaugeTileUiState) {
    BoxWithConstraints {
        val dim = dpMin(maxWidth, maxHeight)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.label,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontSize = scaledTextSize(dim, LABEL_FONT_FRACTION, LABEL_FONT_MIN_SP, LABEL_FONT_MAX_SP),
                    ),
                modifier = Modifier.testTag("gauge-${state.id}-label"),
            )
            val valueColor = if (state.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onBackground
            Text(
                text = state.valueText,
                style =
                    GaugeValueTextStyle.copy(
                        fontSize = scaledTextSize(dim, VALUE_FONT_FRACTION, VALUE_FONT_MIN_SP, VALUE_FONT_MAX_SP),
                    ),
                color = valueColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-value"),
            )
            state.staleText?.let { staleText ->
                Text(
                    text = staleText,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontSize = scaledTextSize(dim, STALE_FONT_FRACTION, STALE_FONT_MIN_SP, STALE_FONT_MAX_SP),
                        ),
                    color = GaugeStaleDim,
                    modifier = Modifier.testTag("gauge-${state.id}-stale"),
                )
            }
        }
    }
}

/**
 * Boost tile: same shell as [GaugeTile] plus the [BoostArc] sweep indicator for
 * [GaugeRenderStyle.DIGITAL] — see its KDoc for [onLongPress]/[onTap]/[style]/[scale]/
 * [gaugeThresholds]. [GaugeRenderStyle.NEEDLE]/[GaugeRenderStyle.BAR_ARC] reuse the exact same
 * generic bodies [GaugeTile] does — boost's own [GaugeScaleDefaults] entry (0–25 psi) and NEUTRAL
 * [GaugeThresholds] (see `ThresholdConfig.seed`) drive them the same way any other gauge's would,
 * so the "Est." unverified badge (drawn as a sibling overlay in `GaugeSlot`, unaffected by this
 * dispatch) carries onto them exactly as it does today onto the digital tile.
 */
@Composable
@Suppress("LongParameterList") // state/modifier/onLongPress/onTap/interactive/style/scale/thresholds — load-bearing.
private fun BoostTile(
    state: GaugeTileUiState,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    interactive: Boolean = true,
    style: GaugeRenderStyle = GaugeRenderStyle.DIGITAL,
    scale: GaugeScale = GaugeScaleDefaults.forId(state.id),
    gaugeThresholds: GaugeThresholds = GaugeThresholds(),
) {
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    Box(
        modifier =
            modifier
                .gaugeTileInteraction(state.id, state.zone, onLongPress, onTap, interactive)
                .background(GaugeNeutral.copy(alpha = TILE_BACKGROUND_ALPHA), shape)
                .padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (style) {
            GaugeRenderStyle.DIGITAL -> BoostDigitalBody(state)
            GaugeRenderStyle.NEEDLE ->
                NeedleGaugeBody(state, scale, gaugeThresholds, modifier = Modifier.fillMaxSize())
            GaugeRenderStyle.BAR_ARC ->
                BarArcGaugeBody(state, scale, gaugeThresholds, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * [BoostTile]'s [GaugeRenderStyle.DIGITAL] body — label, the [BoostArc] sweep, big value —
 * extracted out of [BoostTile] (LongMethod) rather than trimmed down, same reasoning
 * as [DigitalGaugeBody]. OBD-72 device fix: label/value text sizes scale off this composable's own
 * measured dimension via [scaledTextSize] — see `GaugeTextScale.kt`'s file KDoc. [BoostArc] itself
 * keeps its pre-existing fixed 120dp (OBD-10) — untouched by either OBD-72 or this device fix, not
 * part of either report.
 */
@Composable
private fun BoostDigitalBody(state: GaugeTileUiState) {
    BoxWithConstraints {
        val dim = dpMin(maxWidth, maxHeight)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.label,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontSize = scaledTextSize(dim, LABEL_FONT_FRACTION, LABEL_FONT_MIN_SP, LABEL_FONT_MAX_SP),
                    ),
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
                style =
                    GaugeValueTextStyle.copy(
                        fontSize = scaledTextSize(dim, VALUE_FONT_FRACTION, VALUE_FONT_MIN_SP, VALUE_FONT_MAX_SP),
                    ),
                color = valueColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-${state.id}-value"),
            )
        }
    }
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
