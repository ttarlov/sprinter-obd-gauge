package com.revel.obdgauge.app.gauge.grid

import kotlin.math.max

/** A measured cell's pixel size — the unit from which every spanning tile's box is built. */
data class CellSize(
    val width: Float,
    val height: Float,
)

/** A tile's pixel rectangle on the grid: top-left corner and span-expanded size. */
data class CellRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * OBD-63: the pure, Compose-free pixel math behind the spanning grid renderer ([GaugeGrid]). Kept
 * here — plain `Float` in, plain data out — so the spanning arithmetic (cell size, per-placement
 * rects, content height for >4 rows) is unit-testable without a Compose harness, the same
 * platform-free discipline [GridEngine] follows for the model.
 *
 * ## The layout rule
 * A cell is `cellWidth × cellHeight`; a placement of `colSpan × rowSpan` covers that many cells
 * **plus** the [spacing] gutters it swallows between them. Columns divide the available width
 * exactly ([cellWidth]); rows fill the available height but never shrink below [minCellHeight], so
 * a handful of rows fill the viewport (landscape's single row looks like today's dashboard) while
 * many rows keep a usable height and let a scroll parent handle the overflow ([contentHeight]).
 */
object GridMetrics {
    /** `(width − gutters) / columns`. The exact width one grid column occupies. */
    fun cellWidth(
        totalWidth: Float,
        columns: Int,
        spacing: Float,
    ): Float {
        require(columns >= 1) { "grid needs at least one column, was $columns" }
        return (totalWidth - spacing * (columns - 1)) / columns
    }

    /**
     * The cell size for a grid of [columns]×[rows] inside [totalWidth]×[totalHeight]. Height fills
     * the viewport (`(height − gutters) / rows`) but is floored at [minCellHeight]: past the row
     * count that fits, cells stop shrinking and the grid grows taller than the viewport (scroll
     * territory). [rows] `≤ 0` (an empty layout) yields a full-height cell — nothing to place.
     */
    @Suppress("LongParameterList") // width/height/columns/rows/spacing/min are all irreducible inputs.
    fun cellSize(
        totalWidth: Float,
        totalHeight: Float,
        columns: Int,
        rows: Int,
        spacing: Float,
        minCellHeight: Float,
    ): CellSize {
        val width = cellWidth(totalWidth, columns, spacing)
        val height =
            if (rows <= 0) {
                totalHeight
            } else {
                max(minCellHeight, (totalHeight - spacing * (rows - 1)) / rows)
            }
        return CellSize(width, height)
    }

    /**
     * The pixel rect for [placement], given a measured [cell]. Origin is `col*(cellW+spacing)` /
     * `row*(cellH+spacing)`; a span's size is its cells plus the interior gutters it absorbs.
     */
    fun placementRect(
        placement: GridPlacement,
        cell: CellSize,
        spacing: Float,
    ): CellRect =
        CellRect(
            left = placement.col * (cell.width + spacing),
            top = placement.row * (cell.height + spacing),
            width = cell.width * placement.colSpan + spacing * (placement.colSpan - 1),
            height = cell.height * placement.rowSpan + spacing * (placement.rowSpan - 1),
        )

    /** Total grid height for [rows] of [cell] with [spacing] gutters; `0` for an empty grid. */
    fun contentHeight(
        rows: Int,
        cell: CellSize,
        spacing: Float,
    ): Float = if (rows <= 0) 0f else cell.height * rows + spacing * (rows - 1)
}
