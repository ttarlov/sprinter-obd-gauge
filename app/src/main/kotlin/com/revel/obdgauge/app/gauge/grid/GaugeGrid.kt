package com.revel.obdgauge.app.gauge.grid

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

/** Default floor for a cell's height — below this, rows stop shrinking and the grid scrolls. */
private val DEFAULT_MIN_CELL_HEIGHT: Dp = 96.dp

// OBD-67: the rearrange-mode grid backdrop — a technical, targeting-overlay line weight rather
// than a decorative one, drawn in the theme's own primary (the "you're in move mode" hue this
// feature reuses everywhere: backdrop, empty-cell drop targets, the dragged tile's glow, the ⇄
// badge) so it never gets confused with the green/amber/red threshold vocabulary the tiles
// themselves speak. See RearrangeMode.kt's file KDoc for the rest of that palette.
private const val BACKDROP_LINE_WIDTH_DP = 1f
private const val BACKDROP_LINE_ALPHA = 0.14f
private const val BACKDROP_EMPTY_FILL_ALPHA = 0.06f
private const val BACKDROP_EMPTY_BORDER_ALPHA = 0.35f
private const val BACKDROP_EMPTY_DASH_ON_DP = 6f
private const val BACKDROP_EMPTY_DASH_OFF_DP = 5f
private const val BACKDROP_EMPTY_BORDER_WIDTH_DP = 1f

/**
 * OBD-63: renders a [GridLayout] as a spanning, widget-style grid — the Phase-2 replacement for the
 * old flat landscape `Row` / portrait `Column`. Each placement is measured to its span box
 * (`colSpan × rowSpan` cells plus the gutters between them) and placed at its `(col, row)` origin,
 * so tiles can sit side-by-side, run wide (2×1) or fill a big square (2×2), and the grid can hold
 * more than four gauges. All pixel arithmetic lives in [GridMetrics] (pure, unit-tested); this
 * composable is only the Compose glue that feeds it real constraints and places the children.
 *
 * ## Filling vs. scrolling
 * Cell width divides the available width exactly across [columns]. Cell height is computed from the
 * viewport height captured by the outer [BoxWithConstraints] (bounded), **not** from the inner
 * [Layout]'s constraints — the [verticalScroll] wrapper hands the Layout an unbounded height, which
 * is exactly what lets the content grow past the viewport and scroll instead of clipping. For a
 * layout whose rows fit, cells fill the viewport (the default 4-tile / 1-row landscape grid looks
 * like today's dashboard); once rows exceed what fits at [minCellHeight], the grid overflows and
 * the scroll takes over. So >4 rows never clip.
 *
 * ## Why a raw [Layout] and not nested rows
 * Every slot is a normally-measured, normally-placed child of one custom [Layout]. That is what
 * keeps the OBD-42/44/47 swap picker working untouched: each [slotContent] still measures its own
 * on-screen bounds via `onGloballyPositioned` (in root space), and a spanning grid child reports
 * those bounds exactly as a `Row`/`Column` child did. This renderer adds no wrapper node around a
 * slot — the id's own composable is the direct measurable — so grow-origin/shrink geometry is
 * unaffected.
 *
 * @param slotContent renders one tile for the given id. [GaugeGrid] owns the `key(id)` that gives
 *   each gauge a stable composition identity across reorder/swap (see `GaugeTileGrid`'s KDoc), so
 *   this lambda must render a single node for [id] and not re-key it.
 * @param rearrangeMode OBD-67: draws the grid backdrop (cell lines + dashed empty-cell drop
 *   targets) behind the scrolled content when `true`. `false` (the default) renders exactly as
 *   before this feature — every existing caller/screenshot is unaffected.
 * @param scrollState OBD-67: hoisted out of the [rememberScrollState] this composable otherwise
 *   creates for itself, so a caller driving drag autoscroll (`RearrangeMode.kt`) shares the exact
 *   [ScrollState] this grid scrolls with, rather than fighting an internal one it can't reach.
 *   `null` (the default) preserves the pre-OBD-67 behavior of owning its own.
 * @param onCellMeasured OBD-67: reports the same [CellSize] this composable places its own
 *   children with, the instant it's known — the single source of geometry the backdrop above,
 *   drag hit-testing, and the reflow animation all key off, rather than each re-deriving it and
 *   risking drift. A no-op default costs nothing for every caller that doesn't need it.
 * @param minRows OBD-68: the visible grid is at least this many rows tall even past
 *   [layout]'s own occupied extent — freeform rearrange mode's "always one spare empty row to
 *   drop/add into" (see `GaugeTileGrid`'s KDoc). `0` (the default) preserves pre-OBD-68 behavior:
 *   exactly [layout]'s own row count, for every caller that doesn't pass this.
 * @param onBackgroundTap OBD-67 round-5 fix: fires for a tap anywhere in the grid's own bounds
 *   that no child (a tile, an empty-cell target) already consumed — i.e. a gutter or genuinely
 *   empty patch of the grid itself. Exists because `DashboardScreen`'s "tap outside the tile to
 *   dismiss the picker" scrim is a SIBLING of this composable that relies on unclaimed touches
 *   falling through this Layout's own bounds to reach it underneath; that fallthrough silently
 *   broke the instant this Layout gained a REAL pointer-input owner of its own — [needsScroll]
 *   becoming true (see that val's KDoc) attaches [verticalScroll], and once this node has any
 *   pointer input, Compose's hit-test stops there and never reaches the sibling scrim beneath, no
 *   matter what that pointer input does with the event. A no-op default costs nothing for every
 *   caller (like the plain, non-scrolling dashboard) that never needed the sibling-scrim fallback
 *   to begin with.
 */
@Composable
// LongParameterList: columns/layout/spacing/modifier/minCellHeight/slotContent all load-bearing.
// LongMethod: OBD-67 added the hoisted cell/backdrop/scrollState wiring right where the geometry
// it shares is computed — splitting it out would separate the "single source of geometry" this
// composable's own KDoc calls out from the one place that computation happens.
@Suppress("LongParameterList", "LongMethod")
fun GaugeGrid(
    columns: Int,
    layout: GridLayout,
    spacing: Dp,
    modifier: Modifier = Modifier,
    minCellHeight: Dp = DEFAULT_MIN_CELL_HEIGHT,
    rearrangeMode: Boolean = false,
    minRows: Int = 0,
    scrollState: ScrollState? = null,
    onCellMeasured: (CellSize) -> Unit = {},
    onBackgroundTap: () -> Unit = {},
    slotContent: @Composable (id: String) -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.toPx() }
    val minCellHeightPx = with(density) { minCellHeight.toPx() }
    // OBD-67 round-5 device-verified fix: `rows` used to feed BOTH the cell-SIZING calc below AND
    // the visible/scrollable extent, so `minRows`' spare "+" row (rearrange-mode only) inflated the
    // divisor `cellSize` fills-the-viewport-height against — every tile rendered ~30% shorter the
    // instant rearrange mode added that one extra row, and snapped back on exit. Occupied-tile size
    // must be invariant to rearrange mode, so the two are now split: [sizingRows] (always the
    // OCCUPIED extent, `layout.rows` — identical in and out of rearrange mode, never touched by
    // `minRows`) drives `cellSize`, so cell dimensions can't change just from entering the mode.
    // [visibleRows] (the `minRows`-widened extent) still drives everything about how much of the
    // page exists to scroll through — content height, the scroll-need check, and the backdrop's own
    // line/empty-cell extent — so the spare row (and any other empty cells) render at that SAME
    // fixed cell size, simply extending below the occupied content and scrolling into view instead
    // of shrinking everything to fit.
    val sizingRows = layout.rows
    val visibleRows = max(layout.rows, minRows)
    val placements = layout.placements
    val backdropLineColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier) {
        // Captured while height is still bounded: the source of truth for "fill the viewport".
        // Inside the scrollable Layout below, the vertical constraint is Infinity and useless for
        // this — see the composable KDoc.
        val viewportHeightPx =
            if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else minCellHeightPx

        // OBD-67: hoisted up from inside the Layout's own measure block (where it's still also
        // computed, necessarily — the Layout is what actually sizes/places children) so backdrop
        // drawing and onCellMeasured's callers see the IDENTICAL geometry the Layout uses, from
        // this one calculation, rather than a second copy that could drift from it. Safe to hoist:
        // this BoxWithConstraints's own `constraints.maxWidth` is the same bounded width that
        // reaches the inner Layout unchanged (only height differs, made unbounded by the scroll
        // wrapper below — see the composable KDoc's "Filling vs. scrolling" section).
        val cell =
            GridMetrics.cellSize(
                totalWidth = constraints.maxWidth.toFloat(),
                totalHeight = viewportHeightPx,
                columns = columns,
                rows = sizingRows,
                spacing = spacingPx,
                minCellHeight = minCellHeightPx,
            )
        SideEffect { onCellMeasured(cell) }

        // Scroll ONLY when the VISIBLE rows (occupied + any minRows spare), laid out at the cell
        // size actually in use, genuinely overflow the viewport. This MUST use the real `cell`
        // (from sizingRows) rather than the bare `minCellHeightPx` floor: since round 5 split sizing
        // from visibility, `cell.height` can be far larger than the floor (e.g. sizingRows=1 fills
        // the whole viewport height), so a floor-based estimate can under-count the true content
        // height and disagree with `contentHeight` below — which is what actually gets reported as
        // this Layout's height. That mismatch (needsScroll=false while content > viewport) is what
        // caused the round-5-regression: content silently overflowed with no scroll node to reach it,
        // clipping/misplacing badges. Reusing the exact same `contentHeight` formula here guarantees
        // the two can never disagree. (Provably equivalent to the old floor-based check whenever
        // sizingRows == visibleRows, i.e. outside rearrange mode — see commit message.)
        val visibleContentHeightPx = GridMetrics.contentHeight(visibleRows, cell, spacingPx)
        val needsScroll = visibleContentHeightPx > viewportHeightPx
        val effectiveScrollState = scrollState ?: rememberScrollState()
        val scrollModifier = if (needsScroll) Modifier.verticalScroll(effectiveScrollState) else Modifier
        // OBD-67: drawn behind the SCROLLED node (inside scrollModifier's own chain, not the outer
        // BoxWithConstraints) so the lines scroll in lockstep with the tiles — no manual scroll
        // offset math here, unlike the drag overlay (a non-scrolling sibling) that needs it.
        val backdropModifier =
            if (rearrangeMode) {
                Modifier.drawBehind {
                    drawRearrangeBackdrop(layout, visibleRows, cell, spacingPx, backdropLineColor)
                }
            } else {
                Modifier
            }
        // OBD-67 round-5 fix (see onBackgroundTap's KDoc): a plain tap detector alongside
        // scrollModifier on this SAME node. `detectTapGestures`'s `awaitFirstDown` requires an
        // UNCONSUMED down by default, so any child (tile/empty-cell) that already consumes its own
        // tap makes this a no-op for that touch — it only ever fires for a tap that reaches this
        // Layout without any child claiming it, i.e. exactly the "background" gap/gutter taps the
        // sibling picker-scrim used to catch via fallthrough before verticalScroll started owning
        // this node's hit-testing.
        val currentOnBackgroundTap by rememberUpdatedState(onBackgroundTap)
        val backgroundTapModifier =
            Modifier.pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnBackgroundTap() })
            }

        Layout(
            modifier = scrollModifier.then(backgroundTapModifier).then(backdropModifier),
            content = {
                layout.ids.forEach { id ->
                    key(id) { slotContent(id) }
                }
            },
        ) { measurables, constraints ->
            val widthPx = constraints.maxWidth
            // Reuses the SAME value `needsScroll` was computed from above, rather than a second
            // `contentHeight` call — the two must never be able to drift apart (see that val's KDoc).
            val totalHeightPx = visibleContentHeightPx.roundToInt()

            // measurables are emitted in layout.ids order, which is placements order (ids =
            // placements.map { id }), so index i aligns a measurable with its placement.
            val placed =
                measurables.mapIndexedNotNull { i, measurable ->
                    val placement = placements.getOrNull(i) ?: return@mapIndexedNotNull null
                    val rect = GridMetrics.placementRect(placement, cell, spacingPx)
                    val placeable =
                        measurable.measure(
                            Constraints.fixed(
                                width = rect.width.roundToInt().coerceAtLeast(0),
                                height = rect.height.roundToInt().coerceAtLeast(0),
                            ),
                        )
                    Triple(placeable, rect.left.roundToInt(), rect.top.roundToInt())
                }

            layout(widthPx, totalHeightPx.coerceAtLeast(0)) {
                placed.forEach { (placeable, left, top) -> placeable.place(left, top) }
            }
        }
    }
}

/**
 * OBD-67: the rearrange-mode backdrop — full grid lines at every cell boundary, plus a dashed
 * drop-target treatment on any cell in `[0, columns) × [0, rows)` not already covered by a
 * placement. [lineColor] is the same primary hue the drag overlay's glow and the ⇄ badge use (see
 * `RearrangeMode.kt`'s file KDoc) — one "you're in move mode" signal, everywhere it shows up.
 * [rows] is the caller's visible row extent (OBD-68: [GaugeGrid]'s own `minRows`-widened count,
 * not necessarily [layout]'s own occupied extent), so the backdrop's lines/empty-cell squares
 * cover the same spare row the freeform "＋" add cells render into.
 */
private fun DrawScope.drawRearrangeBackdrop(
    layout: GridLayout,
    rows: Int,
    cell: CellSize,
    spacing: Float,
    lineColor: Color,
) {
    val columns = layout.columns
    if (columns <= 0 || rows <= 0) return
    val pitchX = cell.width + spacing
    val pitchY = cell.height + spacing
    val contentWidth = columns * cell.width + spacing * (columns - 1)
    val contentHeight = rows * cell.height + spacing * (rows - 1)
    val lineWidthPx = BACKDROP_LINE_WIDTH_DP.dp.toPx()
    val gridLineColor = lineColor.copy(alpha = BACKDROP_LINE_ALPHA)

    for (col in 0..columns) {
        val x = (col * pitchX - spacing / 2).coerceIn(0f, contentWidth)
        drawLine(gridLineColor, Offset(x, 0f), Offset(x, contentHeight), lineWidthPx)
    }
    for (row in 0..rows) {
        val y = (row * pitchY - spacing / 2).coerceIn(0f, contentHeight)
        drawLine(gridLineColor, Offset(0f, y), Offset(contentWidth, y), lineWidthPx)
    }

    val dashEffect =
        PathEffect.dashPathEffect(
            floatArrayOf(BACKDROP_EMPTY_DASH_ON_DP.dp.toPx(), BACKDROP_EMPTY_DASH_OFF_DP.dp.toPx()),
        )
    val emptyBorderWidthPx = BACKDROP_EMPTY_BORDER_WIDTH_DP.dp.toPx()
    for (row in 0 until rows) {
        for (col in 0 until columns) {
            if (layout.placementAt(col, row) != null) continue
            val rect = GridMetrics.placementRect(GridPlacement("", col, row), cell, spacing)
            drawRect(
                color = lineColor.copy(alpha = BACKDROP_EMPTY_FILL_ALPHA),
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
            )
            drawRect(
                color = lineColor.copy(alpha = BACKDROP_EMPTY_BORDER_ALPHA),
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = Stroke(width = emptyBorderWidthPx, pathEffect = dashEffect),
            )
        }
    }
}
