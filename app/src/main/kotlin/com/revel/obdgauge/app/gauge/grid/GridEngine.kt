package com.revel.obdgauge.app.gauge.grid

import kotlin.math.min

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
     * OBD-67: the packing index (position in [GridLayout.placements]) a drag ending at
     * ([col], [row]) should commit to via [reorder] — the finger→index half of drag-to-move.
     * Span-aware via [GridLayout.placementAt]: hovering anywhere over a 2×2 tile targets that
     * tile's own index, not just its top-left cell. An empty or past-the-content cell targets
     * [GridLayout.placements]'s size (append at the end — [reorder] clamps to that anyway). A
     * [draggedId] no longer on [layout] is a degenerate drag state; this returns the same
     * append-at-end default rather than resolving a stale finger position.
     */
    fun targetIndexAt(
        layout: GridLayout,
        draggedId: String,
        col: Int,
        row: Int,
    ): Int {
        if (layout.placementFor(draggedId) == null) return layout.placements.size
        val hit = layout.placementAt(col, row)
        return if (hit != null) layout.placements.indexOfFirst { it.id == hit.id } else layout.placements.size
    }

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
     * OBD-67 round-10 device-verified fix: resizes [id] to [colSpan]×[rowSpan] **in place** — its
     * `(col, row)` origin never moves, and every OTHER placement stays exactly where it is (no
     * repack). [resize] (above) was the pre-freeform primitive: correct for the ordered-reflow
     * model that pivoted away before shipping (`issues/OBD-67.md`'s pivot notes), but never
     * updated when `DashboardViewModel.resizeGauge` kept calling it after the freeform pivot —
     * device report: "resizing of the gauges does not work anymore... the chip visibly selects...
     * [but] the tile is still 1×1." `repack()` doesn't just resize [id]; it re-derives EVERY
     * placement's `(col, row)` from scratch via shelf-packing, silently reshuffling every other
     * tile too (confirmed: a single resize pushed two unrelated tiles into a new row) — the exact
     * "positions are absolute and user-placed" violation [dropAt] already solves for moves, now
     * fixed here the same way for spans.
     *
     * Rejects (returns [layout] unchanged — the placement keeps its current span) if the new span
     * would grow past [GridLayout.columns] or collide with another placement, e.g. a right-column
     * 1×1 asking for 2× width with no column to grow into. No separate "did it apply" return value
     * — same snap-back-on-reject contract [dropAt] already has, and the caller's own UI (the size
     * chips) already reflects whatever the CURRENT placement's real span is, so a rejected resize
     * is silently a no-op rather than a size that visually "took" but didn't.
     */
    fun resizeInPlace(
        layout: GridLayout,
        id: String,
        colSpan: Int,
        rowSpan: Int,
    ): GridLayout {
        val current = layout.placementFor(id) ?: return layout
        val candidate = current.copy(colSpan = colSpan.coerceAtLeast(1), rowSpan = rowSpan.coerceAtLeast(1))
        val fits =
            candidate.withinBounds(layout.columns) &&
                layout.placements.none { it.id != id && it.overlaps(candidate) }
        return if (fits) {
            layout.copy(placements = layout.placements.map { if (it.id == id) candidate else it })
        } else {
            layout
        }
    }

    /**
     * OBD-67 round-12 device-verified (user decision): resizes [id] to [colSpan]×[rowSpan],
     * PUSHING any placement that ends up in the way to a free slot instead of rejecting the
     * resize ([resizeInPlace] above's silent-no-op-on-collision was diagnosed as technically
     * correct but device-invisible: "1×1 and 2×1 work... 1×2 or 2×2 don't" turned out to be a
     * genuine, silent rejection in a packed layout — correct, but reads as broken with no
     * feedback).
     *
     * OBD-67 round-13 device-verified (user decision, refining round-12): a widened span that
     * would run off the right edge no longer rejects outright either — [id]'s own `col` SHIFTS
     * LEFT just far enough for the new span to fit within [GridLayout.columns] (`newCol =
     * min(col, columns - colSpan)`, always `>= 0` here since [colSpan] is already guarded
     * `<= columns` below), then pushes whatever's in the way of the shifted footprint exactly
     * like any other collision. User's own words: "I can resize any way I want, but only when
     * the gauge is on the LEFT side. If a gauge is in the RIGHT column it only resizes up/down,
     * not side to side" — a right-column tile asking to grow wider now extends into the space
     * actually available (to its left) rather than doing nothing. The ONLY resize still hard
     * rejected: [colSpan] itself wider than the whole grid ([GridLayout.columns]) — a span no
     * shift could ever fit, impossible via the current size chips (max 2×2) but a real
     * mathematical edge this function still has to honor. rowSpan stays unbounded (the grid
     * scrolls), unaffected by any of this — only colSpan/column position needed the shift.
     *
     * Displaced placements relocate via the SAME first-free-slot scan
     * [addInFirstFreeSlot]/[repack] use — top-left to bottom-right, extending into new rows as
     * needed — processed in their original packing order so the result is deterministic. [id]
     * itself only grows and (per round-13) may shift left; nothing OTHER than a
     * genuinely-displaced placement's position ever changes, and every displaced placement keeps
     * its own existing span, only its `(col, row)` moves.
     */
    fun resizeWithPush(
        layout: GridLayout,
        id: String,
        colSpan: Int,
        rowSpan: Int,
    ): GridLayout {
        val current = layout.placementFor(id)
        val clampedColSpan = colSpan.coerceAtLeast(1)
        val clampedRowSpan = rowSpan.coerceAtLeast(1)
        if (current == null || clampedColSpan > layout.columns) return layout

        val newCol = min(current.col, layout.columns - clampedColSpan)
        val target = current.copy(col = newCol, colSpan = clampedColSpan, rowSpan = clampedRowSpan)
        val displaced = layout.placements.filter { it.id != id && it.overlaps(target) }
        val settled = layout.placements.filterNot { it.id == id || it in displaced }.toMutableList()
        settled.add(target)
        val occupied = HashSet<Pair<Int, Int>>()
        settled.forEach { occupied.addAll(it.cells()) }
        displaced.forEach { d ->
            val (col, row) = firstFreeSlot(occupied, layout.columns, d.colSpan, d.rowSpan)
            val relocated = d.copy(col = col, row = row)
            settled.add(relocated)
            occupied.addAll(relocated.cells())
        }
        // Preserve the original packing order — GridLayout.ids/tab order depends on it, and
        // nothing here has a reason to reorder placements, only relocate a displaced few.
        val byId = settled.associateBy { it.id }
        return layout.copy(placements = layout.placements.map { byId.getValue(it.id) })
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

    /**
     * OBD-68 (freeform placement — the ordered-reflow model [reorder]/[targetIndexAt] built for
     * OBD-67 was replaced by this before shipping, per device testing: ordered reflow left-packs
     * with no gaps, so side-to-side placement into open space and persistent empty cells were
     * both impossible by design). True if dropping [id] at ([col], [row]) would do SOMETHING —
     * either it fits outright ([canPlace]) or the target is a clean swap ([singleMatchingOccupant]
     * finds exactly one other placement whose own origin+span exactly matches the footprint [id]
     * would occupy there). The live drag highlight's valid/invalid style reads this without
     * committing anything.
     */
    fun canDrop(
        layout: GridLayout,
        id: String,
        col: Int,
        row: Int,
    ): Boolean {
        val placement = layout.placementFor(id) ?: return false
        return canPlace(layout, id, col, row) ||
            singleMatchingOccupant(layout, id, col, row, placement.colSpan, placement.rowSpan) != null
    }

    /**
     * OBD-68: resolves a drag-drop of [id] to top-left cell ([col], [row]), in priority order —
     * see [canDrop]'s KDoc for the model this replaced and why:
     * 1. **Move**: if [id]'s own span fits there with every OTHER placement staying exactly where
     *    it is, that's the whole result ([moveTo]).
     * 2. **Swap**: else, if the target footprint is occupied by exactly one other placement whose
     *    own origin+span exactly matches [id]'s footprint there, the two trade positions
     *    ([swapPositions]) — same-size tiles change places, nothing else moves.
     * 3. **No-op (snap-back)**: else — empty-but-too-small, partial overlap, more than one
     *    occupant, or a size mismatch — [layout] is returned unchanged. Deliberately NOT
     *    attempting any multi-tile reshuffle; that is the hard freeform-2D case this model
     *    intentionally does not solve.
     */
    fun dropAt(
        layout: GridLayout,
        id: String,
        col: Int,
        row: Int,
    ): GridLayout {
        val placement = layout.placementFor(id) ?: return layout
        return moveTo(layout, id, col, row)
            ?: singleMatchingOccupant(layout, id, col, row, placement.colSpan, placement.rowSpan)
                ?.let { occupant -> swapPositions(layout, id, occupant.id) }
            ?: layout
    }

    /**
     * Trades [idA]'s and [idB]'s `(col, row)` — each keeps its own span, only their origins swap.
     * Safe without a fresh overlap check: [dropAt] only calls this once [singleMatchingOccupant]
     * has already proven the two footprints are identical, so exchanging origins can't introduce
     * a collision that wasn't already ruled out. A no-op if either id is absent.
     */
    fun swapPositions(
        layout: GridLayout,
        idA: String,
        idB: String,
    ): GridLayout {
        val a = layout.placementFor(idA)
        val b = layout.placementFor(idB)
        if (a == null || b == null) return layout
        return layout.copy(
            placements =
                layout.placements.map {
                    when (it.id) {
                        idA -> it.copy(col = b.col, row = b.row)
                        idB -> it.copy(col = a.col, row = a.row)
                        else -> it
                    }
                },
        )
    }

    /**
     * OBD-68: places a not-yet-placed [id] at an explicit ([col], [row]) — the "＋" empty-cell
     * add flow's mutator, the freeform counterpart to [addInFirstFreeSlot]'s "first free slot"
     * placement. A no-op (unchanged [layout]) if [id] is already placed, the span would go out of
     * bounds, or it would overlap an existing placement — the caller only ever offers a "＋" over a
     * cell it already knows is empty, but this stays defensive rather than trusting that.
     */
    @Suppress("LongParameterList") // layout/id/col/row/colSpan/rowSpan are all irreducible inputs.
    fun addAt(
        layout: GridLayout,
        id: String,
        col: Int,
        row: Int,
        colSpan: Int = 1,
        rowSpan: Int = 1,
    ): GridLayout {
        val candidate = GridPlacement(id, col, row, colSpan, rowSpan)
        val fits =
            layout.placementFor(id) == null &&
                candidate.withinBounds(layout.columns) &&
                layout.placements.none { it.overlaps(candidate) }
        return if (fits) layout.copy(placements = layout.placements + candidate) else layout
    }

    /**
     * The single OTHER placement (never [excludingId] itself) whose own `(col, row, colSpan,
     * rowSpan)` exactly matches the [colSpan]×[rowSpan] footprint anchored at ([col], [row]) — the
     * "clean swap" test [dropAt]/[canDrop] gate on. `null` for an empty footprint, a partial
     * overlap, more than one occupant, or an occupant whose own footprint doesn't exactly match
     * (a same-*position*-different-*size* collision is a snap-back, not a swap).
     */
    @Suppress("LongParameterList") // layout/excludingId/col/row/colSpan/rowSpan are all irreducible inputs.
    private fun singleMatchingOccupant(
        layout: GridLayout,
        excludingId: String,
        col: Int,
        row: Int,
        colSpan: Int,
        rowSpan: Int,
    ): GridPlacement? {
        val footprint = GridPlacement(excludingId, col, row, colSpan, rowSpan)
        val occupant =
            layout.placements.singleOrNull { it.id != excludingId && it.overlaps(footprint) } ?: return null
        val exactMatch =
            occupant.col == col && occupant.row == row && occupant.colSpan == colSpan && occupant.rowSpan == rowSpan
        return if (exactMatch) occupant else null
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
