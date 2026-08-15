package com.revel.obdgauge.app.gauge.grid

/**
 * The pure, platform-free logic for the resizable gauge grid — no Compose, no Android, no state.
 * Every operation is a total function from one [GridLayout] to the next, which is what makes the
 * whole feature's correctness unit-testable before a single tile is drawn (`GridEngineTest`).
 *
 * ## Repack is the one primitive
 * Phases 1–3 position tiles by **repack**: walk the placements in their packing order and drop
 * each into the first free cell scanning left-to-right, top-to-bottom, at its own span. Add,
 * remove, resize and reorder are all "edit the ordered list, then repack", so positions are always
 * a deterministic function of `(columns, order, spans)` — a bigger tile bumps the ones after it
 * down, gaps never strand, and there is exactly one place the packing rule lives.
 *
 * ## Positional primitives, for later
 * [canPlace] and [moveTo] set an **explicit** cell without repacking — the drag/resize phases
 * (OBD-63+) build on them so a dropped tile stays exactly where it was dropped. They are here now,
 * tested, so the model that persists is the one those phases need — no migration later.
 */
@Suppress("TooManyFunctions") // cohesive pure-engine primitives (repack + its derived ops); splitting adds no clarity.
object GridEngine {
    /**
     * Recomputes every placement's `(col, row)` from the packing order and spans, ignoring the
     * stored positions. Spans are clamped to `[1, columns]` (col) and `[1, ∞)` (row) so a corrupt
     * or over-wide span can never wedge the packer. The result is always a valid layout: in bounds,
     * non-overlapping, ids in the input order.
     */
    fun repack(
        columns: Int,
        ordered: List<GridPlacement>,
    ): GridLayout {
        require(columns >= 1) { "grid needs at least one column, was $columns" }
        val occupied = HashSet<Pair<Int, Int>>()
        val placed = ArrayList<GridPlacement>(ordered.size)
        for (p in ordered) {
            val colSpan = p.colSpan.coerceIn(1, columns)
            val rowSpan = p.rowSpan.coerceAtLeast(1)
            val (col, row) = firstFreeSlot(occupied, columns, colSpan, rowSpan)
            val settled = p.copy(col = col, row = row, colSpan = colSpan, rowSpan = rowSpan)
            placed.add(settled)
            occupied.addAll(settled.cells())
        }
        return GridLayout(columns, placed)
    }

    /** Appends [id] at [colSpan]×[rowSpan] into the first free slot; a no-op if [id] is already placed. */
    fun addInFirstFreeSlot(
        layout: GridLayout,
        id: String,
        colSpan: Int = 1,
        rowSpan: Int = 1,
    ): GridLayout {
        if (layout.placementFor(id) != null) return layout
        return repack(layout.columns, layout.placements + GridPlacement(id, 0, 0, colSpan, rowSpan))
    }

    /** Removes [id] and repacks the rest; a no-op if [id] is absent. */
    fun remove(
        layout: GridLayout,
        id: String,
    ): GridLayout = repack(layout.columns, layout.placements.filterNot { it.id == id })

    /**
     * Renames the placement currently holding [oldId] to [newId] **in place** — keeping its exact
     * `(col, row, colSpan, rowSpan)`, no repack. This is OBD-42's "swap" as a grid op (OBD-64): the
     * tile stays the same size and position, only which gauge it shows changes. A no-op if [oldId]
     * is absent or equals [newId]. Unlike [remove]+[addInFirstFreeSlot], nothing else moves.
     */
    fun replaceId(
        layout: GridLayout,
        oldId: String,
        newId: String,
    ): GridLayout {
        if (oldId == newId || layout.placementFor(oldId) == null) return layout
        return layout.copy(
            placements = layout.placements.map { if (it.id == oldId) it.copy(id = newId) else it },
        )
    }

    /** Changes [id]'s span and repacks; a no-op if [id] is absent. Spans are clamped by [repack]. */
    fun resize(
        layout: GridLayout,
        id: String,
        colSpan: Int,
        rowSpan: Int,
    ): GridLayout =
        repack(
            layout.columns,
            layout.placements.map { if (it.id == id) it.copy(colSpan = colSpan, rowSpan = rowSpan) else it },
        )

    /** Moves [id] to packing-index [toIndex] (clamped) and repacks; a no-op if [id] is absent. */
    fun reorder(
        layout: GridLayout,
        id: String,
        toIndex: Int,
    ): GridLayout {
        val current = layout.placements.indexOfFirst { it.id == id }
        if (current < 0) return layout
        val without = layout.placements.toMutableList()
        val moved = without.removeAt(current)
        without.add(toIndex.coerceIn(0, without.size), moved)
        return repack(layout.columns, without)
    }

    /** Sets the grid width to [columns] and repacks everything into it; ≥ 1 column required. */
    fun withColumns(
        layout: GridLayout,
        columns: Int,
    ): GridLayout = repack(columns, layout.placements)

    /**
     * True if [id]'s span, placed with its top-left at ([col], [row]), stays in bounds and covers
     * no cell owned by a *different* placement. The explicit-placement test the drag phases gate on.
     */
    fun canPlace(
        layout: GridLayout,
        id: String,
        col: Int,
        row: Int,
    ): Boolean {
        val target = layout.placementFor(id)?.copy(col = col, row = row) ?: return false
        return target.withinBounds(layout.columns) && layout.placements.none { it.id != id && it.overlaps(target) }
    }

    /**
     * Places [id] at an explicit ([col], [row]) **without** repacking (others stay put), or returns
     * null if it would not fit ([canPlace] is false). The primitive the drag/resize phases use to
     * honor "dropped exactly here".
     */
    fun moveTo(
        layout: GridLayout,
        id: String,
        col: Int,
        row: Int,
    ): GridLayout? {
        if (!canPlace(layout, id, col, row)) return null
        return layout.copy(
            placements = layout.placements.map { if (it.id == id) it.copy(col = col, row = row) else it },
        )
    }

    private fun firstFreeSlot(
        occupied: Set<Pair<Int, Int>>,
        columns: Int,
        colSpan: Int,
        rowSpan: Int,
    ): Pair<Int, Int> {
        var row = 0
        while (true) {
            for (col in 0..columns - colSpan) {
                if (fitsAt(occupied, col, row, colSpan, rowSpan)) return col to row
            }
            row++
        }
    }

    private fun fitsAt(
        occupied: Set<Pair<Int, Int>>,
        col: Int,
        row: Int,
        colSpan: Int,
        rowSpan: Int,
    ): Boolean {
        for (r in row until row + rowSpan) {
            for (c in col until col + colSpan) {
                if (c to r in occupied) return false
            }
        }
        return true
    }
}
