package com.revel.obdgauge.app.gauge.grid

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Default floor for a cell's height — below this, rows stop shrinking and the grid scrolls. */
private val DEFAULT_MIN_CELL_HEIGHT: Dp = 96.dp

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
 */
@Composable
@Suppress("LongParameterList") // columns/layout/spacing/modifier/minCellHeight/slotContent all load-bearing.
fun GaugeGrid(
    columns: Int,
    layout: GridLayout,
    spacing: Dp,
    modifier: Modifier = Modifier,
    minCellHeight: Dp = DEFAULT_MIN_CELL_HEIGHT,
    slotContent: @Composable (id: String) -> Unit,
) {
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.toPx() }
    val minCellHeightPx = with(density) { minCellHeight.toPx() }
    val rows = layout.rows
    val placements = layout.placements

    BoxWithConstraints(modifier) {
        // Captured while height is still bounded: the source of truth for "fill the viewport".
        // Inside the scrollable Layout below, the vertical constraint is Infinity and useless for
        // this — see the composable KDoc.
        val viewportHeightPx =
            if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else minCellHeightPx

        // Scroll ONLY when the rows can't fit even floored at minCellHeight — i.e. content will
        // genuinely overflow. When they fit, cells fill the viewport and content == viewport, so a
        // scroll node would be dead weight AND would swallow taps in the inter-tile gaps that the
        // OBD-42 picker's dismiss-scrim (a sibling drawn behind this grid) must still receive. So
        // the common cases (default 1-row landscape / 2-row portrait, and any layout that fits) add
        // no pointer-intercepting scroll; only a truly tall grid does, where scrolling is the point.
        val needsScroll = rows * minCellHeightPx + spacingPx * (rows - 1) > viewportHeightPx
        val scrollModifier = if (needsScroll) Modifier.verticalScroll(rememberScrollState()) else Modifier

        Layout(
            modifier = scrollModifier,
            content = {
                layout.ids.forEach { id ->
                    key(id) { slotContent(id) }
                }
            },
        ) { measurables, constraints ->
            val widthPx = constraints.maxWidth
            val cell =
                GridMetrics.cellSize(
                    totalWidth = widthPx.toFloat(),
                    totalHeight = viewportHeightPx,
                    columns = columns,
                    rows = rows,
                    spacing = spacingPx,
                    minCellHeight = minCellHeightPx,
                )
            val totalHeightPx = GridMetrics.contentHeight(rows, cell, spacingPx).roundToInt()

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
