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

/** A grid cell address — the inverse of [GridPlacement]'s `(col, row)`, for a point in content-space. */
data class Cell(
    val col: Int,
    val row: Int,
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

    /**
     * OBD-67: the inverse of [placementRect] — a content-space point `(x, y)` to the `(col, row)`
     * cell it falls in, for drag hit-testing. Each cell's pitch is `cell.width/height + spacing`;
     * a point landing in the gutter between two cells attributes to the cell it trails (matches
     * [placementRect]'s own left/top-anchored math, so a point just inside a placement's rect
     * always round-trips to that placement's cell). [col] is clamped to `[0, columns-1]` and
     * [row] floored at `0` so a finger dragged past an edge still resolves to a valid, in-bounds
     * cell rather than a col/row [GridEngine] would reject.
     */
    fun cellAt(
        x: Float,
        y: Float,
        cell: CellSize,
        spacing: Float,
        columns: Int,
    ): Cell {
        require(columns >= 1) { "grid needs at least one column, was $columns" }
        val col = (x / (cell.width + spacing)).toInt().coerceIn(0, columns - 1)
        val row = (y / (cell.height + spacing)).toInt().coerceAtLeast(0)
        return Cell(col, row)
    }
}
