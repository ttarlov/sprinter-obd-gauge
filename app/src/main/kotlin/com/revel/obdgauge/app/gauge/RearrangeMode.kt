// OBD-68 (freeform placement) added the drop-target highlight and the empty-cell "＋" add button
// to this file's existing rearrange-mode primitives, nudging it past detekt's per-file function
// count — they're all one cohesive interaction/animation layer for the SAME mode, so suppressing
// here (as DashboardScreen.kt already does for its own chrome) keeps it in one place rather than
// scattering it across files for the counter's sake.
@file:Suppress("TooManyFunctions")

package com.revel.obdgauge.app.gauge

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.gauge.grid.Cell
import com.revel.obdgauge.app.gauge.grid.CellRect
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * OBD-67: rearrange mode's interaction/animation primitives — [DragController] (the remembered
 * drag state), the reflow animation ([animatePlacement]), the jiggle affordance
 * ([rearrangeJiggle]), the per-tile action badges ([RearrangeBadges]), and drag autoscroll
 * ([RearrangeAutoscroll]). `DashboardScreen.kt` owns the mode's own on/off state and wires these
 * primitives into [GaugeDashboard]/`GaugeTileGrid` — this file stays free of that wiring so the
 * pieces here stay independently reasoned about (and, for the pure [autoscrollDelta], testable).
 *
 * ## The mode's one color language
 * Every rearrange-mode affordance — the grid backdrop and empty-cell drop targets
 * (`GaugeGrid.kt`), the ⇄ badge, and the dragged tile's glow below — is drawn in the theme's own
 * `primary` (an ice-blue that reads as chrome/UI, not data). That is deliberate: this mode has its
 * own "you're moving something" signal, kept visually separate from the green/amber/red
 * threshold vocabulary the tiles themselves speak, the same reasoning `ThresholdSelectBlue`
 * already applies to the gear editor's selection frame. × (destructive) and ⚙ (opens the existing
 * threshold editor) get their own theme colors (`error`, `secondary`) so the three badges read as
 * three distinct actions at a glance, not one undifferentiated cluster.
 */
private const val REFLOW_ANIM_MS = 220

private const val JIGGLE_ROTATION_DEG = 1.6f
private const val JIGGLE_PERIOD_MS = 140
private const val JIGGLE_PHASE_MODULUS = 2

private const val BADGE_SIZE_DP = 26
private const val BADGE_BORDER_WIDTH_DP = 1
private const val BADGE_BG_ALPHA = 0.92f
private const val BADGE_CLUSTER_SPACING_DP = 4
private const val BADGE_INSET_DP = 6

private const val DRAG_SCALE = 1.06f
private const val DRAG_SHADOW_ELEVATION_DP = 12
private const val DRAG_GLOW_BORDER_WIDTH_DP = 1.5f
private const val GHOST_ALPHA = 0.28f

private const val AUTOSCROLL_BAND_PX = 96f
private const val AUTOSCROLL_SPEED_PX_PER_FRAME = 14f

private const val DROP_HIGHLIGHT_VALID_ALPHA = 0.28f
private const val DROP_HIGHLIGHT_INVALID_ALPHA = 0.14f
private const val DROP_HIGHLIGHT_BORDER_WIDTH_DP = 2f
private const val DROP_HIGHLIGHT_BORDER_ALPHA = 0.7f

private const val ADD_CELL_GLYPH = "＋"
private const val ADD_CELL_ALPHA = 0.55f

/**
 * Whether [rearrangeJiggle] actually animates. Defaults on; Robolectric tests that render
 * rearrange mode flip it off for the same reason [LocalDangerPulseEnabled] exists — an
 * always-running `rememberInfiniteTransition` hangs `waitForIdle()`, so a still board is the
 * right thing to assert against and screenshot.
 */
val LocalRearrangeJiggleEnabled = staticCompositionLocalOf { true }

/**
 * The remembered state of one in-progress drag-to-move: which tile ([draggedId]), the finger's
 * running position in the grid's own content-space ([fingerContentPos] — scroll-invariant, see
 * this class's KDoc on [moveBy]), and the freeform drop target that position currently resolves
 * to ([dropCell], [dropValid]) via `GridMetrics.cellAt` + `GridEngine.canDrop` (OBD-68 — replaced
 * OBD-67's packing-index/`targetIndexAt` before it shipped; see `DashboardViewModel.moveGauge`'s
 * KDoc for the ordered→freeform pivot). A plain `@Stable` holder (not a data class) because its
 * fields are independently mutated piecemeal by the drag gesture and the autoscroll loop, not
 * replaced wholesale.
 */
@Stable
internal class DragController {
    var draggedId by mutableStateOf<String?>(null)
        private set
    var fingerContentPos by mutableStateOf(Offset.Zero)
        private set

    /**
     * Where within the dragged tile's own bounds the finger grabbed it (tile-local, set once at
     * [start]) — the floating overlay renders at `fingerContentPos - grabOffset` so the tile stays
     * anchored under the finger at the point it was picked up, rather than snapping to be centered
     * on it.
     */
    var grabOffset by mutableStateOf(Offset.Zero)
        private set

    /**
     * The top-left cell the dragged tile's own footprint currently resolves to, or `null` before
     * the first resolve.
     */
    var dropCell by mutableStateOf<Cell?>(null)
        private set

    /**
     * Whether dropping at [dropCell] right now would do something ([GridEngine.canDrop]) — the
     * live highlight's style.
     */
    var dropValid by mutableStateOf(false)
        private set

    val isDragging: Boolean get() = draggedId != null

    /** Begins a drag: [id] is now floating, grabbed at [contentPos] (content-space). */
    fun start(
        id: String,
        contentPos: Offset,
        grabOffset: Offset,
    ) {
        draggedId = id
        fingerContentPos = contentPos
        this.grabOffset = grabOffset
        dropCell = null
        dropValid = false
    }

    /**
     * Integrates a movement of [delta] pixels into the tracked content-space finger position.
     * [delta] is deliberately just a raw pixel vector, usable for BOTH a real finger movement
     * (`onDrag`'s scroll-invariant `dragAmount`) and an autoscroll tick (content shifting under a
     * stationary finger) — see `RearrangeAutoscroll`'s KDoc for why the same accumulator serves
     * both without the scroll offset ever needing to be subtracted or added explicitly.
     */
    fun moveBy(delta: Offset) {
        fingerContentPos += delta
    }

    fun updateDropTarget(
        cell: Cell,
        valid: Boolean,
    ) {
        dropCell = cell
        dropValid = valid
    }

    /** Ends the drag, dragging or not — the safe call for both a commit and a cancel. */
    fun end() {
        draggedId = null
        fingerContentPos = Offset.Zero
        grabOffset = Offset.Zero
        dropCell = null
        dropValid = false
    }
}

@Composable
internal fun rememberDragController(): DragController = remember { DragController() }

/**
 * The settle animation for a tile whose position changed WITHOUT it being the one dragged — under
 * OBD-68's freeform model that only happens on a committed SWAP (the displaced tile jumps to the
 * dragged tile's old cell the instant the persisted grid updates; nothing live-reflows during the
 * drag itself anymore, see `DashboardViewModel.moveGauge`'s KDoc). Applied inside the same
 * `key(id)` wrapper `GaugeGrid` already keys every tile with. The custom `Layout` in
 * `GaugeGrid.kt` has no `animateItemPlacement` — it always hard-places each child at its current
 * [targetRect] — so this fakes the animation on top: it tracks the last rect this id rendered at,
 * and on a change draws the *delta* between old and new as a decaying [Modifier.offset], letting
 * the Layout's own hard placement land instantly while the visual glide plays out over
 * [REFLOW_ANIM_MS]. [enabled] is `false` for the dragged tile itself (finger-driven — animating it
 * here would fight the drag) and while there is no active target change to animate (rearrange mode
 * off, or nothing has moved yet).
 */
@Composable
internal fun Modifier.animatePlacement(
    targetRect: CellRect,
    enabled: Boolean,
): Modifier {
    if (!enabled) return this
    val target = Offset(targetRect.left, targetRect.top)
    var previousTarget by remember { mutableStateOf(target) }
    val delta = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    LaunchedEffect(target) {
        val jump = previousTarget - target
        previousTarget = target
        if (jump != Offset.Zero) {
            delta.snapTo(jump)
            delta.animateTo(Offset.Zero, animationSpec = tween(REFLOW_ANIM_MS))
        }
    }
    return this.offset { IntOffset(delta.value.x.roundToInt(), delta.value.y.roundToInt()) }
}

/**
 * The home-screen "jiggle" affordance: a small, continuous ±[JIGGLE_ROTATION_DEG] rotation on a
 * fast reverse loop. [id]'s hash picks one of two opposite phases so a whole board of tiles
 * doesn't rotate in visible lockstep — a subtler "alive" read, same idea as
 * [dangerPulseAlpha]'s per-id label. The infinite transition is created only while
 * [enabled] (rearrange mode on, [LocalRearrangeJiggleEnabled] true, and not the dragged tile —
 * see [animatePlacement]'s KDoc for why the dragged tile opts out of ambient motion), so no tile
 * keeps a frame clock busy outside the mode.
 */
@Composable
internal fun Modifier.rearrangeJiggle(
    id: String,
    enabled: Boolean,
): Modifier {
    if (!enabled || !LocalRearrangeJiggleEnabled.current) return this
    val transition = rememberInfiniteTransition(label = "gauge-jiggle-$id")
    val direction = if (id.hashCode() % JIGGLE_PHASE_MODULUS == 0) -1f else 1f
    val angle by transition.animateFloat(
        initialValue = -JIGGLE_ROTATION_DEG * direction,
        targetValue = JIGGLE_ROTATION_DEG * direction,
        animationSpec = infiniteRepeatable(tween(JIGGLE_PERIOD_MS), RepeatMode.Reverse),
        label = "gauge-jiggle-angle-$id",
    )
    return this.graphicsLayer { rotationZ = angle }
}

/**
 * The floating dragged tile's own treatment — scaled up, elevated, and ringed in the mode's
 * primary glow so it unmistakably reads as "this is the one being moved," layered over the grid
 * backdrop and the reflowing neighbours. Applied to the overlay sibling `DashboardScreen.kt`
 * renders on top of `GaugeGrid` while dragging (see `GaugeTileGrid`'s KDoc there for why that has
 * to be a second composition of the same tile rather than the in-place one being reparented).
 */
internal fun Modifier.draggedTileTreatment(glowColor: Color): Modifier =
    this
        .graphicsLayer {
            scaleX = DRAG_SCALE
            scaleY = DRAG_SCALE
            shadowElevation = DRAG_SHADOW_ELEVATION_DP.dp.toPx()
        }.border(DRAG_GLOW_BORDER_WIDTH_DP.dp, glowColor)

/** The dimmed placeholder left in a dragged tile's home slot while it floats as an overlay. */
internal fun Modifier.dragGhostTreatment(): Modifier = this.graphicsLayer { alpha = GHOST_ALPHA }

/**
 * OBD-68: the live drop-target highlight — a filled, bordered rect at the dragged tile's own
 * footprint size, drawn under the floating tile at whatever cell it currently resolves to
 * ([DragController.dropCell]). [valid] (mirrors [DragController.dropValid], itself
 * `GridEngine.canDrop`) switches between the mode's own "valid move" primary tint and a
 * lower-alpha error tint reading as "won't drop here, this will snap back" — freeform's
 * replacement for OBD-67's neighbor-reflow preview, which no longer exists (nothing else moves
 * during a freeform drag; see `DashboardViewModel.moveGauge`'s KDoc for why).
 */
internal fun Modifier.dropTargetTreatment(
    valid: Boolean,
    validColor: Color,
    invalidColor: Color,
): Modifier {
    val color = if (valid) validColor else invalidColor
    val alpha = if (valid) DROP_HIGHLIGHT_VALID_ALPHA else DROP_HIGHLIGHT_INVALID_ALPHA
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    return this
        .background(color.copy(alpha = alpha), shape)
        .border(DROP_HIGHLIGHT_BORDER_WIDTH_DP.dp, color.copy(alpha = DROP_HIGHLIGHT_BORDER_ALPHA), shape)
}

/**
 * OBD-68: one empty grid cell's "＋" add affordance — rendered for every cell not covered by a
 * placement while in rearrange mode ([GaugeGrid]'s own backdrop already dashes the cell's outline
 * as a drop target; this sits on top of that, centering the glyph). Tapping opens the add-palette
 * targeted at that exact cell (the caller already knows which `(col, row)` this instance is for,
 * having positioned it there — see `GaugeTileGrid`'s KDoc).
 *
 * OBD-67 round-9 device-verified fix: was `detectTapGestures`, which consumes the down/up it
 * recognizes as part of claiming the gesture as its own — standard, correct behavior for a tap
 * target in isolation, but this button is a CHILD of `GaugeGrid`'s scrollable content, and a
 * consumed touch never reaches that ancestor `Modifier.verticalScroll` to be recognized as a
 * scroll. Device report: "scrolling up/down the grid is limited to the space between gauges. I
 * want to scroll anywhere, including the empty space where a new gauge could be" — the empty
 * cells (there are a lot of them, per-cell, across the whole spare row) were exactly where a
 * swipe-to-scroll attempt was most likely to land and get eaten. [detectNonConsumingTap] below
 * recognizes the identical tap gesture but never consumes anything until (and unless) it settles
 * on "yes, this was a tap" at release — a genuine swipe is free to keep moving completely
 * unclaimed the whole time, so `verticalScroll` sees it and takes it the instant its own slop is
 * exceeded, same as a swipe starting in an inter-tile gutter already could.
 */
@Composable
internal fun EmptyCellAddButton(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        modifier =
            modifier
                .testTag("gauge-rearrange-add")
                .pointerInput(Unit) { detectNonConsumingTap(onTap = { currentOnTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = ADD_CELL_GLYPH,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = ADD_CELL_ALPHA),
        )
    }
}

/**
 * A tap detector that recognizes press-then-release-within-slop exactly like
 * [detectTapGestures], but — unlike it — never calls [androidx.compose.ui.input.pointer.PointerInputChange.consume]
 * on the down or on any move, only on the final up and only once it's clear the gesture stayed
 * within [ViewConfiguration.touchSlop][androidx.compose.ui.platform.ViewConfiguration.touchSlop]
 * of the starting point. A scrolling ancestor watching the same touch sequence (e.g. `GaugeGrid`'s
 * `Modifier.verticalScroll`, a leaf-to-root MAIN-pass sibling further up the chain) never sees a
 * change this detector has already claimed, so it stays free to recognize and consume a genuine
 * swipe as its own drag — see [EmptyCellAddButton]'s KDoc for the device report this fixes.
 */
private suspend fun PointerInputScope.detectNonConsumingTap(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation()
        if (up != null) {
            val dx = up.position.x - down.position.x
            val dy = up.position.y - down.position.y
            val slop = viewConfiguration.touchSlop
            if (dx * dx + dy * dy <= slop * slop) {
                up.consume()
                onTap()
            }
        }
    }
}

/**
 * OBD-67: rearrange mode's ×/⇄/⚙ badges for one tile — × (remove) at the top-start corner, ⇄
 * (swap) and, when [editable], ⚙ (threshold) clustered at the top-end corner. Each badge is its
 * own top-drawn [Box] with an independent `pointerInput { detectTapGestures }`: since these are
 * later siblings than the tile's own draggable body in the same parent `Box`, Compose dispatches
 * pointer events to them FIRST (later-placed = hit-tested first), so a badge tap is consumed here
 * and never reaches — and so never starts — the body's drag gesture.
 *
 * Round-2 review fix: positioned with a small INWARD [BADGE_INSET_DP] padding from the tile's own
 * corner, not an outward negative-offset overlap (the classic "app icon delete badge" look this
 * used in round 1). That look needs generous empty space around each icon to read cleanly; this
 * grid packs tiles edge-to-edge with only a [TILE_SPACING_DP]-ish gutter between them, so an
 * outward overlap had nowhere to go — it spilled into the header row above the top tiles and past
 * the screen edge on the outermost column, which is what round-1 device testing caught. Insetting
 * keeps every badge fully within its own tile's bounds on every tile, at any grid position.
 */
@Composable
@Suppress("LongParameterList") // id/editable/onRemove/onSwap/onThreshold/modifier all load-bearing.
internal fun RearrangeBadges(
    id: String,
    editable: Boolean,
    onRemove: () -> Unit,
    onSwap: () -> Unit,
    onThreshold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnRemove by rememberUpdatedState(onRemove)
    val currentOnSwap by rememberUpdatedState(onSwap)
    val currentOnThreshold by rememberUpdatedState(onThreshold)
    Box(modifier = modifier.fillMaxSize().padding(BADGE_INSET_DP.dp)) {
        RearrangeBadge(
            glyph = "✕",
            borderColor = MaterialTheme.colorScheme.error,
            testTag = "gauge-rearrange-remove-$id",
            onClick = { currentOnRemove() },
            modifier = Modifier.align(Alignment.TopStart),
        )
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            BadgeCluster(id, editable, currentOnThreshold, currentOnSwap)
        }
    }
}

@Composable
private fun BadgeCluster(
    id: String,
    editable: Boolean,
    onThreshold: () -> Unit,
    onSwap: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(BADGE_CLUSTER_SPACING_DP.dp)) {
        if (editable) {
            RearrangeBadge(
                glyph = "⚙",
                borderColor = MaterialTheme.colorScheme.secondary,
                testTag = "gauge-rearrange-threshold-$id",
                onClick = onThreshold,
            )
        }
        RearrangeBadge(
            glyph = "⇄",
            borderColor = MaterialTheme.colorScheme.primary,
            testTag = "gauge-rearrange-swap-$id",
            onClick = onSwap,
        )
    }
}

/** One circular badge: a translucent glass chip bordered in [borderColor], its [glyph] centered. */
@Composable
private fun RearrangeBadge(
    glyph: String,
    borderColor: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val shape = CircleShape
    Box(
        modifier =
            modifier
                .size(BADGE_SIZE_DP.dp)
                .testTag(testTag)
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = BADGE_BG_ALPHA), shape)
                .border(BADGE_BORDER_WIDTH_DP.dp, borderColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * The autoscroll pixel delta for one animation frame: [AUTOSCROLL_SPEED_PX_PER_FRAME] toward
 * whichever edge the finger (at [fingerScreenY], viewport-space) sits within
 * [AUTOSCROLL_BAND_PX] of, or `0f` when it isn't near an edge or that direction has nothing left
 * to scroll ([scrollValue]/[scrollMax], `ScrollState`'s own bounds). Pure so the edge-detection
 * rule is unit-testable without a `LaunchedEffect`/frame clock.
 */
internal fun autoscrollDelta(
    fingerScreenY: Float,
    viewportHeightPx: Float,
    scrollValue: Int,
    scrollMax: Int,
): Float =
    when {
        fingerScreenY < AUTOSCROLL_BAND_PX && scrollValue > 0 -> -AUTOSCROLL_SPEED_PX_PER_FRAME
        fingerScreenY > viewportHeightPx - AUTOSCROLL_BAND_PX && scrollValue < scrollMax ->
            AUTOSCROLL_SPEED_PX_PER_FRAME
        else -> 0f
    }

/**
 * Drives autoscroll for as long as [controller] has an active drag: each frame, if the finger
 * (converted to viewport-space as `fingerContentPos.y - scrollState.value` — the overlay's own
 * "Scroll conversion" per this file's KDoc) sits in an edge band, scrolls [scrollState] by
 * [autoscrollDelta] and folds the ACTUAL consumed scroll back into [controller]'s content-space
 * finger position (content moved under a stationary finger is exactly as much a change in "what's
 * under the finger" as the finger itself moving — seeAlso [DragController.moveBy]), then calls
 * [recomputeTarget] so the drop target/highlight follows live rather than only updating on the
 * next real touch move.
 */
@Composable
internal fun RearrangeAutoscroll(
    controller: DragController,
    scrollState: ScrollState,
    viewportHeightPx: Float,
    recomputeTarget: () -> Unit,
) {
    val currentRecompute by rememberUpdatedState(recomputeTarget)
    val draggedId = controller.draggedId
    LaunchedEffect(draggedId, viewportHeightPx) {
        if (draggedId == null) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val fingerScreenY = controller.fingerContentPos.y - scrollState.value
            val delta = autoscrollDelta(fingerScreenY, viewportHeightPx, scrollState.value, scrollState.maxValue)
            if (delta != 0f) {
                val consumed = scrollState.scrollBy(delta)
                if (consumed != 0f) {
                    controller.moveBy(Offset(0f, consumed))
                    currentRecompute()
                }
            }
        }
    }
}
