package com.revel.obdgauge.app.gauge.grid

/**
 * One gauge's placement on the dashboard grid: which cell its top-left corner sits in
 * ([col], [row], both 0-based) and how many cells it spans ([colSpan], [rowSpan], both ≥ 1).
 *
 * A 1×1 placement is a single cell — today's tile. A 2×1 spans two columns in one row (a wide
 * boost tile); a 2×2 is a big square. The grid is column-bounded (see [GridLayout.columns]) but
 * grows downward without limit, exactly like an Android widget grid.
 *
 * Positions are **stored**, not only derived, so the later drag/resize phases can set an explicit
 * cell without a data migration. Phases 1–3 compute them with [GridEngine]'s repack, but the model
 * is already shaped for "drop it exactly here".
 */
data class GridPlacement(
    val id: String,
    val col: Int,
    val row: Int,
    val colSpan: Int = 1,
    val rowSpan: Int = 1,
) {
    /** Every (col, row) cell this placement covers. */
    fun cells(): List<Pair<Int, Int>> =
        buildList {
            for (r in row until row + rowSpan) {
                for (c in col until col + colSpan) {
                    add(c to r)
                }
            }
        }

    /** True if this and [other] cover any common cell. */
    fun overlaps(other: GridPlacement): Boolean {
        val colsDisjoint = col + colSpan <= other.col || other.col + other.colSpan <= col
        val rowsDisjoint = row + rowSpan <= other.row || other.row + other.rowSpan <= row
        return !(colsDisjoint || rowsDisjoint)
    }

    /** True if the whole span stays within `[0, columns)` horizontally and `row ≥ 0` vertically. */
    fun withinBounds(columns: Int): Boolean =
        col >= 0 && row >= 0 && colSpan >= 1 && rowSpan >= 1 && col + colSpan <= columns
}

/**
 * The dashboard layout: a fixed number of [columns] and the gauges placed on it, in **packing
 * order** (the order repack walks them, which is also their reading/tab order). Row count is
 * implicit — as many as the placements reach.
 *
 * Invariants a *valid* layout holds (enforced by [GridEngine], asserted by its tests): every
 * placement is within bounds, no two overlap, and ids are unique. The raw data class does not
 * enforce them — construct through [GridEngine] rather than by hand in production.
 */
data class GridLayout(
    val columns: Int,
    val placements: List<GridPlacement>,
) {
    /** Ids in packing order. */
    val ids: List<String> get() = placements.map { it.id }

    /** One past the last occupied row — the grid's current height in cells. */
    val rows: Int get() = placements.maxOfOrNull { it.row + it.rowSpan } ?: 0

    /** The placement whose span covers ([col], [row]), or null if that cell is empty. */
    fun placementAt(
        col: Int,
        row: Int,
    ): GridPlacement? =
        placements.firstOrNull {
            it.col <= col &&
                col < it.col + it.colSpan &&
                it.row <= row &&
                row < it.row + it.rowSpan
        }

    fun placementFor(id: String): GridPlacement? = placements.firstOrNull { it.id == id }

    companion object {
        /** An empty layout with the given column count. */
        fun empty(columns: Int): GridLayout = GridLayout(columns, emptyList())
    }
}
