package com.revel.obdgauge.app.gauge

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

// internal (not private): GaugePicker.kt's GaugePickerChrome/pickerShrink* helpers reuse these so
// the picker frame and the OBD-44 shrink target both match the tile's own corner
// radius/padding/background exactly — "still THAT tile" (issues/OBD-42.md).
internal const val TILE_CORNER_RADIUS_DP = 16
internal const val TILE_PADDING_DP = 16
internal const val TILE_BACKGROUND_ALPHA = 0.18f
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
    // MINOR M3: fall back to a placeholder (like GaugePickerChrome's own mini-cards already do)
    // rather than skipping this slot's render entirely — DashboardUiState.Loading only carries
    // the four core placeholders (extraTiles is empty by default), so a tile freshly swapped to
    // a non-core id (e.g. rpm) would otherwise render as a gap in the layout for every frame
    // before the first real reading arrives.
    val tile = uiState.tileFor(id) ?: GaugeTileUiState.placeholder(id, GAUGE_CATALOG_BY_ID[id]?.label ?: id)
    val progress = rememberPickerShrinkProgress(isPicking, label = "gauge-picker-shrink-$id")

    // The live GaugeTile/BoostTile's OWN (pre-transform) layout coordinates — both the "fullSize"
    // pickerShrinkLayer shrinks FROM and the coordinate-space anchor used to convert the chrome's
    // ghost position into a Rect this tile's own graphicsLayer transform can target. Deliberately
    // NOT reset to null when isPicking flips false: GaugePickerChrome keeps rendering (and
    // re-reporting the ghost's position) for as long as progress > 0f below, which spans the
    // whole reverse "grow back to full tile" animation on dismiss — pickerShrinkLayer needs a
    // valid target the entire time or the grow-back would snap instead of animating.
    var tileCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var currentSlotBounds by remember { mutableStateOf<Rect?>(null) }

    // `modifier` (carrying RowScope.weight()+fillMaxSize in landscape, or fillMaxWidth alone in
    // portrait — see GaugeTileGrid) lives on THIS Box, exactly where AnimatedContent used to
    // receive it pre-OBD-44: Row/Column only sees weight() on their own direct child, so it must
    // land on whatever GaugeSlot's composition roots at. GaugeTile/BoostTile below gets no size
    // modifier of its own (just the onGloballyPositioned decorator) and so inherits this Box's
    // resolved constraints — filling it under landscape's tight weight-driven constraints, or
    // driving its height from content under portrait's width-only ones — the same sizing
    // behavior AnimatedContent had. That parity needs propagateMinConstraints = true (Box's
    // default is false): Box normally LOOSENS a plain child's min constraints to 0, which would
    // leave GaugeTile sized to wrap its own content instead of filling the weighted landscape
    // slot (AnimatedContent isn't a Box and doesn't do this loosening). In portrait this Box's
    // own incoming minHeight is already 0 (fillMaxWidth only, inside a scrolling Column), so
    // propagating it changes nothing there — GaugeTile still sizes to its own content height.
    // GaugePickerChrome, by contrast, DOES use Modifier.matchParentSize(): its own
    // caption+carousel content must never influence this Box's resolved size, only fill whatever
    // GaugeTile/BoostTile already determined it to be.
    Box(modifier = modifier, propagateMinConstraints = true) {
        if (progress > 0f) {
            GaugePickerChrome(
                currentId = id,
                currentTile = tile,
                candidates =
                    remember(id, gaugeOrder) {
                        candidateGaugesFor(id, gaugeOrder).filterNot { it.id == id }
                    },
                tileFor = uiState::tileFor,
                alpha = progress,
                onDismiss = onDismissPicker,
                onSelectCandidate = onSelectCandidate,
                isPicking = isPicking,
                onCurrentSlotPositioned = { slotCoordinates ->
                    // Review round-1 N1: both coordinates must still be attached to the layout
                    // tree — a callback can fire after either side has been torn down (e.g. this
                    // tile's own dismiss racing the chrome's teardown), and `localPositionOf` on
                    // a detached `LayoutCoordinates` throws rather than returning a stale value.
                    tileCoordinates?.let { anchor ->
                        if (anchor.isAttached && slotCoordinates.isAttached) {
                            val topLeft = anchor.localPositionOf(slotCoordinates, Offset.Zero)
                            currentSlotBounds = Rect(topLeft, slotCoordinates.size.toSize())
                        }
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        val fullSize = tileCoordinates?.size?.toSize()
        val liveModifier = Modifier.onGloballyPositioned { tileCoordinates = it }
        if (id == PidIds.BOOST) {
            BoostTile(
                tile,
                sparkline,
                onLongPress = onLongPress,
                onTap = onDismissPicker,
                isPicking = isPicking,
                shrinkProgress = progress,
                fullSize = fullSize,
                targetBounds = currentSlotBounds,
                modifier = liveModifier,
            )
        } else {
            GaugeTile(
                tile,
                sparkline,
                onLongPress = onLongPress,
                onTap = onDismissPicker,
                isPicking = isPicking,
                shrinkProgress = progress,
                fullSize = fullSize,
                targetBounds = currentSlotBounds,
                modifier = liveModifier,
            )
        }
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
            BoostArc(psi = state.rawValue)
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
