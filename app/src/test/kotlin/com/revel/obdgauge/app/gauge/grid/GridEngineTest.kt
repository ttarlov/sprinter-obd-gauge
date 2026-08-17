package com.revel.obdgauge.app.gauge.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBD-62: the resizable-grid engine's correctness, in plain JVM (no Compose/Android). Repack
 * determinism and its derived operations are the foundation the render/gesture phases build on, so
 * this suite is where the packing rules are actually pinned.
 */
class GridEngineTest {
    private fun tiles(vararg ids: String): List<GridPlacement> = ids.map { GridPlacement(it, 0, 0) }

    private fun at(
        layout: GridLayout,
        id: String,
    ): Pair<Int, Int> = layout.placementFor(id)!!.let { it.col to it.row }

    /** Asserts a layout is valid: in bounds, unique ids, and no two placements overlap. */
    private fun assertValid(layout: GridLayout) {
        assertEquals("ids unique", layout.ids, layout.ids.distinct())
        layout.placements.forEach { assertTrue("$it in bounds", it.withinBounds(layout.columns)) }
        for (i in layout.placements.indices) {
            for (j in i + 1 until layout.placements.size) {
                assertFalse(
                    "${layout.placements[i]} overlaps ${layout.placements[j]}",
                    layout.placements[i].overlaps(layout.placements[j]),
                )
            }
        }
    }

    @Test
    fun `repack of an empty layout is empty`() {
        assertEquals(emptyList<GridPlacement>(), GridEngine.repack(4, emptyList()).placements)
    }

    @Test
    fun `four unit tiles fill a 4-column row, the fifth wraps`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c", "d", "e"))

        assertEquals(0 to 0, at(layout, "a"))
        assertEquals(1 to 0, at(layout, "b"))
        assertEquals(2 to 0, at(layout, "c"))
        assertEquals(3 to 0, at(layout, "d"))
        assertEquals(0 to 1, at(layout, "e"))
        assertValid(layout)
    }

    @Test
    fun `first free slot is chosen top-most then left-most`() {
        // A 2-wide tile then units: the wide one takes cols 0-1 of row 0, the next unit goes to col 2.
        val layout =
            GridEngine.repack(
                4,
                listOf(GridPlacement("wide", 0, 0, colSpan = 2), GridPlacement("x", 0, 0), GridPlacement("y", 0, 0)),
            )

        assertEquals(0 to 0, at(layout, "wide"))
        assertEquals(2 to 0, at(layout, "x"))
        assertEquals(3 to 0, at(layout, "y"))
        assertValid(layout)
    }

    @Test
    fun `a 2x2 leaves the rest of its rows for smaller tiles`() {
        val layout =
            GridEngine.repack(
                4,
                listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)) + tiles("a", "b", "c", "d"),
            )

        assertEquals(0 to 0, at(layout, "big"))
        assertEquals(2 to 0, at(layout, "a"))
        assertEquals(3 to 0, at(layout, "b"))
        assertEquals(2 to 1, at(layout, "c"))
        assertEquals(3 to 1, at(layout, "d"))
        assertValid(layout)
    }

    @Test
    fun `resizing a tile bumps the ones after it and stays valid`() {
        val start = GridEngine.repack(4, tiles("a", "b", "c", "d"))

        val resized = GridEngine.resize(start, "a", colSpan = 2, rowSpan = 1)

        assertEquals(0 to 0, at(resized, "a")) // a now spans cols 0-1
        assertEquals(2 to 0, at(resized, "b"))
        assertEquals(3 to 0, at(resized, "c"))
        assertEquals(0 to 1, at(resized, "d")) // pushed to the next row
        assertValid(resized)
    }

    @Test
    fun `an over-wide span is clamped to the column count`() {
        val layout = GridEngine.repack(4, listOf(GridPlacement("a", 0, 0, colSpan = 9)))

        assertEquals(4, layout.placementFor("a")!!.colSpan)
        assertValid(layout)
    }

    @Test
    fun `add drops into the first free slot and is a no-op for a duplicate id`() {
        val base = GridEngine.repack(4, listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)))

        val added = GridEngine.addInFirstFreeSlot(base, "s", colSpan = 1, rowSpan = 1)
        assertEquals(2 to 0, at(added, "s"))

        val again = GridEngine.addInFirstFreeSlot(added, "s")
        assertEquals(added, again) // duplicate id changes nothing
        assertValid(added)
    }

    @Test
    fun `remove closes the gap by repacking, and is a no-op for an absent id`() {
        val start = GridEngine.repack(4, tiles("a", "b", "c", "d", "e"))

        val removed = GridEngine.remove(start, "a")
        assertEquals(0 to 0, at(removed, "b"))
        assertEquals(3 to 0, at(removed, "e")) // e slid up into the freed row-0 slot

        assertEquals(removed, GridEngine.remove(removed, "nope"))
        assertValid(removed)
    }

    @Test
    fun `replaceId renames a placement in place, keeping its cell and span`() {
        val start =
            GridEngine.repack(
                4,
                listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)) + tiles("a", "b"),
            )
        val bigBefore = start.placementFor("big")!!

        val replaced = GridEngine.replaceId(start, "big", "huge")

        assertNull("old id is gone", replaced.placementFor("big"))
        val huge = replaced.placementFor("huge")!!
        // Same cell, same span — nothing repacked, only the id changed.
        assertEquals(bigBefore.copy(id = "huge"), huge)
        // The other tiles didn't move either.
        assertEquals(start.placementFor("a"), replaced.placementFor("a"))
        assertEquals(start.placementFor("b"), replaced.placementFor("b"))
        assertValid(replaced)
    }

    @Test
    fun `replaceId is a no-op for an absent old id or a self-swap`() {
        val start = GridEngine.repack(4, tiles("a", "b"))

        assertEquals(start, GridEngine.replaceId(start, "nope", "x"))
        assertEquals(start, GridEngine.replaceId(start, "a", "a"))
    }

    @Test
    fun `reorder moves a tile in packing order`() {
        val start = GridEngine.repack(4, tiles("a", "b", "c", "d"))

        val reordered = GridEngine.reorder(start, "d", toIndex = 0)

        assertEquals(listOf("d", "a", "b", "c"), reordered.ids)
        assertEquals(0 to 0, at(reordered, "d"))
        assertValid(reordered)
    }

    @Test
    fun `withColumns repacks into a narrower grid`() {
        val wide = GridEngine.repack(4, tiles("a", "b", "c", "d"))

        val narrow = GridEngine.withColumns(wide, 2)

        assertEquals(2, narrow.columns)
        assertEquals(0 to 0, at(narrow, "a"))
        assertEquals(1 to 0, at(narrow, "b"))
        assertEquals(0 to 1, at(narrow, "c"))
        assertEquals(1 to 1, at(narrow, "d"))
        assertValid(narrow)
    }

    @Test
    fun `canPlace respects bounds and the other tiles`() {
        val layout = GridEngine.repack(4, tiles("a", "b")) // a@(0,0) b@(1,0)

        assertTrue("an empty cell in-bounds is placeable", GridEngine.canPlace(layout, "a", col = 0, row = 3))
        assertFalse("onto another tile is not", GridEngine.canPlace(layout, "a", col = 1, row = 0))
        assertFalse("out of bounds is not", GridEngine.canPlace(layout, "a", col = 4, row = 0))
        assertFalse("an unknown id is not", GridEngine.canPlace(layout, "ghost", col = 0, row = 2))
    }

    @Test
    fun `moveTo sets an explicit cell without repacking, or refuses`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c"))

        val moved = GridEngine.moveTo(layout, "a", col = 0, row = 5)!!
        assertEquals(0 to 5, at(moved, "a"))
        assertEquals(1 to 0, at(moved, "b")) // others untouched — no repack
        assertValid(moved)

        assertNull("a colliding move is refused", GridEngine.moveTo(layout, "a", col = 1, row = 0))
    }

    // OBD-67 round-10 device-verified fix: `resizeInPlace` is the freeform-correct replacement for
    // `resize` above (which repacks and reshuffles every OTHER placement too — see its own
    // updated KDoc for the device regression that caused). These pin the same "no repack, others
    // untouched" contract [moveTo] already has, just for a span change instead of a position
    // change.
    @Test
    fun `resizeInPlace changes only the target's span, others untouched`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c", "d")) // a@(0,0) b@(1,0) c@(2,0) d@(3,0)

        // a grows downward into the always-empty row below — nothing else occupies row 1.
        val resized = GridEngine.resizeInPlace(layout, "a", colSpan = 1, rowSpan = 2)

        val a = resized.placementFor("a")!!
        assertEquals(0, a.col)
        assertEquals(0, a.row)
        assertEquals(1, a.colSpan)
        assertEquals(2, a.rowSpan)
        assertEquals(1 to 0, at(resized, "b")) // untouched — no repack
        assertEquals(2 to 0, at(resized, "c"))
        assertEquals(3 to 0, at(resized, "d"))
        assertValid(resized)
    }

    @Test
    fun `resizeInPlace rejects a span that collides with another tile, keeping the current one`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c")) // a@(0,0) b@(1,0) c@(2,0)

        // 2 wide collides with b, immediately to a's right.
        val result = GridEngine.resizeInPlace(layout, "a", colSpan = 2, rowSpan = 1)

        assertEquals(layout, result)
    }

    @Test
    fun `resizeInPlace rejects a span that would grow past the column count`() {
        // Built directly (not via repack, which would re-derive its own position) so "a" sits
        // explicitly in the last column — no room to grow wider without exceeding the grid.
        val layout = GridLayout(columns = 4, placements = listOf(GridPlacement("a", col = 3, row = 0)))

        val result = GridEngine.resizeInPlace(layout, "a", colSpan = 2, rowSpan = 1)

        assertEquals(layout, result)
    }

    @Test
    fun `resizeInPlace is a no-op for an absent id`() {
        val layout = GridEngine.repack(4, tiles("a"))

        assertEquals(layout, GridEngine.resizeInPlace(layout, "ghost", colSpan = 2, rowSpan = 2))
    }

    // OBD-67 round-12 (user decision — see GridEngine.resizeWithPush's own KDoc for the full
    // "correct but silent" diagnosis behind this): resizeWithPush is resizeInPlace's PUSH sibling
    // — the same span change, but a colliding occupant relocates to a free slot instead of
    // rejecting the whole resize.
    @Test
    fun `resizeWithPush displaces a colliding occupant to a free slot`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c", "d")) // a@(0,0) b@(1,0) c@(2,0) d@(3,0)

        // a grows to 2 wide, colliding with b immediately to its right.
        val result = GridEngine.resizeWithPush(layout, "a", colSpan = 2, rowSpan = 1)

        val a = result.placementFor("a")!!
        assertEquals(0, a.col)
        assertEquals(0, a.row)
        assertEquals(2, a.colSpan)
        // c/d were never in the way — untouched.
        assertEquals(2 to 0, at(result, "c"))
        assertEquals(3 to 0, at(result, "d"))
        // b displaced, not resized: row 0 is now solid (a spans cols 0-1, c/d hold 2-3), so b
        // lands at the start of a new row.
        val b = result.placementFor("b")!!
        assertEquals(0, b.col)
        assertEquals(1, b.row)
        assertEquals(1, b.colSpan)
        assertValid(result)
    }

    @Test
    fun `resizeWithPush leaves everyone else untouched when growing into empty space`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c", "d"))

        // a grows downward into the always-empty row below — nothing else occupies row 1.
        val result = GridEngine.resizeWithPush(layout, "a", colSpan = 1, rowSpan = 2)

        val a = result.placementFor("a")!!
        assertEquals(0, a.row)
        assertEquals(2, a.rowSpan)
        assertEquals(1 to 0, at(result, "b")) // untouched — nothing to push
        assertEquals(2 to 0, at(result, "c"))
        assertEquals(3 to 0, at(result, "d"))
        assertValid(result)
    }

    @Test
    fun `resizeWithPush shifts a right-edge tile left instead of rejecting a widen`() {
        // Built directly (not via repack) so "a" sits explicitly in the last column.
        val layout = GridLayout(columns = 4, placements = listOf(GridPlacement("a", col = 3, row = 0)))

        val result = GridEngine.resizeWithPush(layout, "a", colSpan = 2, rowSpan = 1)

        val a = result.placementFor("a")!!
        assertEquals(2, a.col) // shifted left just far enough: 2 + 2 == 4 columns
        assertEquals(2, a.colSpan)
        assertValid(result)
    }

    @Test
    fun `resizeWithPush shifts and pushes together when widening a right-edge tile that's occupied`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c", "d")) // a@(0,0) b@(1,0) c@(2,0) d@(3,0)

        // d, at the last column, grows to 2 wide — shifts left to col 2, colliding with c.
        val result = GridEngine.resizeWithPush(layout, "d", colSpan = 2, rowSpan = 1)

        val d = result.placementFor("d")!!
        assertEquals(2, d.col)
        assertEquals(2, d.colSpan)
        assertEquals(0 to 0, at(result, "a")) // never in the way — untouched
        assertEquals(1 to 0, at(result, "b"))
        // c was at col 2 — pushed out; row 0 is now solid (a, b, d), so c starts a new row.
        val c = result.placementFor("c")!!
        assertEquals(0, c.col)
        assertEquals(1, c.row)
        assertValid(result)
    }

    @Test
    fun `resizeWithPush rejects a span wider than the grid itself — no shift can fix that`() {
        val layout = GridEngine.repack(4, tiles("a"))

        // 5 wide in a 4-column grid: no position, shifted or otherwise, could ever fit it.
        val result = GridEngine.resizeWithPush(layout, "a", colSpan = 5, rowSpan = 1)

        assertEquals(layout, result)
    }

    @Test
    fun `resizeWithPush never produces an overlap, even displacing multiple tiles at once`() {
        // A 2x2 tile grown into a fully packed 4-column row displaces BOTH of its right-hand
        // neighbors at once.
        val layout = GridEngine.repack(4, tiles("a", "b", "c", "d"))

        val result = GridEngine.resizeWithPush(layout, "a", colSpan = 3, rowSpan = 1)

        assertEquals(3, result.placementFor("a")!!.colSpan)
        assertValid(result) // assertValid's own overlap check is the real assertion here
    }

    @Test
    fun `resizeWithPush is a no-op for an absent id`() {
        val layout = GridEngine.repack(4, tiles("a"))

        assertEquals(layout, GridEngine.resizeWithPush(layout, "ghost", colSpan = 2, rowSpan = 2))
    }

    @Test
    fun `placementAt finds the owner of a spanned cell`() {
        val layout = GridEngine.repack(4, listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)))

        assertEquals("big", layout.placementAt(1, 1)?.id)
        assertNull(layout.placementAt(3, 3))
    }

    // --- OBD-67: targetIndexAt, the finger→packing-index half of drag-to-move -----------------

    @Test
    fun `targetIndexAt over each tile resolves to that tile's own packing index`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c", "d"))

        assertEquals(0, GridEngine.targetIndexAt(layout, draggedId = "a", col = 0, row = 0))
        assertEquals(1, GridEngine.targetIndexAt(layout, draggedId = "a", col = 1, row = 0))
        assertEquals(2, GridEngine.targetIndexAt(layout, draggedId = "a", col = 2, row = 0))
        assertEquals(3, GridEngine.targetIndexAt(layout, draggedId = "a", col = 3, row = 0))
    }

    @Test
    fun `targetIndexAt over an empty or past-the-content cell resolves to the placement count`() {
        val layout = GridEngine.repack(4, tiles("a", "b"))

        assertEquals(layout.placements.size, GridEngine.targetIndexAt(layout, draggedId = "a", col = 3, row = 0))
        assertEquals(layout.placements.size, GridEngine.targetIndexAt(layout, draggedId = "a", col = 0, row = 9))
    }

    @Test
    fun `targetIndexAt over the dragged tile's own cell resolves to its own index`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c"))

        assertEquals(1, GridEngine.targetIndexAt(layout, draggedId = "b", col = 1, row = 0))
    }

    @Test
    fun `targetIndexAt is span-aware, resolving any cell of a wide tile to its one index`() {
        val layout =
            GridEngine.repack(
                4,
                listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)) + tiles("a", "b"),
            )
        val bigIndex = layout.placements.indexOfFirst { it.id == "big" }

        assertEquals(bigIndex, GridEngine.targetIndexAt(layout, draggedId = "a", col = 0, row = 0))
        assertEquals(bigIndex, GridEngine.targetIndexAt(layout, draggedId = "a", col = 1, row = 1))
    }

    @Test
    fun `targetIndexAt with an absent dragged id resolves to the placement count`() {
        val layout = GridEngine.repack(4, tiles("a", "b"))

        assertEquals(layout.placements.size, GridEngine.targetIndexAt(layout, draggedId = "ghost", col = 0, row = 0))
    }

    /**
     * OBD-67's single-index-commit correctness depends on [repack] preserving the input order it's
     * handed — `reorder` computes [toIndex] against whatever order is CURRENT when the drag
     * commits, and that commit always lands on the canonical (4-col) layout regardless of which
     * orientation's repacked grid the drag was actually performed on. This locks that a reorder
     * commutes with a column-count repack: doing the move first (at 4 cols) then repacking to 2,
     * or repacking to 2 first then doing the same move, land on the identical layout — so an index
     * read off a 2-col on-screen grid is safe to replay against the persisted 4-col one.
     */
    @Test
    fun `reorder then withColumns equals withColumns then reorder — index-stability across repack`() {
        val canonical =
            GridEngine.repack(
                4,
                listOf(GridPlacement("big", 0, 0, colSpan = 2)) + tiles("a", "b", "c"),
            )

        val moveFirst = GridEngine.withColumns(GridEngine.reorder(canonical, "c", toIndex = 0), columns = 2)
        val repackFirst = GridEngine.reorder(GridEngine.withColumns(canonical, columns = 2), "c", toIndex = 0)

        assertEquals(moveFirst, repackFirst)
        assertValid(moveFirst)
    }

    // --- OBD-68: freeform placement — dropAt/canDrop/swapPositions/addAt ----------------------
    // Replaces the ordered-reflow model above for the drag-drop UI: device testing found ordered
    // reflow left-packs with no gaps, so it can never place a tile side-to-side into open space or
    // hold a persistent empty cell. reorder/targetIndexAt/repack stay — still correct, still used
    // by remove/resize/withColumns — just no longer what a drag's drop-commit calls.

    @Test
    fun `dropAt moves a tile into an empty cell that fits, everything else stays put`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c")) // a(0,0) b(1,0) c(2,0)

        val dropped = GridEngine.dropAt(layout, "a", col = 3, row = 2)

        assertEquals(3 to 2, at(dropped, "a"))
        assertEquals(1 to 0, at(dropped, "b")) // untouched, no repack
        assertEquals(2 to 0, at(dropped, "c"))
        assertValid(dropped)
    }

    @Test
    fun `dropAt swaps two same-footprint tiles when the target is exactly occupied`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c")) // a(0,0) b(1,0) c(2,0)

        val dropped = GridEngine.dropAt(layout, "a", col = 2, row = 0) // onto c's exact 1x1 cell

        assertEquals(2 to 0, at(dropped, "a"))
        assertEquals(0 to 0, at(dropped, "c")) // c displaced to a's old origin
        assertEquals(1 to 0, at(dropped, "b")) // uninvolved, untouched
        assertValid(dropped)
    }

    @Test
    fun `dropAt swaps two same-span wide tiles, keeping each one's own span`() {
        val layout =
            GridEngine.repack(
                4,
                listOf(GridPlacement("wide1", 0, 0, colSpan = 2), GridPlacement("wide2", 2, 0, colSpan = 2)),
            )

        val dropped = GridEngine.dropAt(layout, "wide1", col = 2, row = 0)

        val wide1 = dropped.placementFor("wide1")!!
        val wide2 = dropped.placementFor("wide2")!!
        assertEquals(2 to 0, wide1.col to wide1.row)
        assertEquals(2, wide1.colSpan)
        assertEquals(0 to 0, wide2.col to wide2.row)
        assertEquals(2, wide2.colSpan)
        assertValid(dropped)
    }

    @Test
    fun `dropAt snaps back (no-op) when the target spans more than one occupant`() {
        // b(1,0) and c(2,0) are both 1x1; a 2-wide "wide" dropped onto (1,0) would cover BOTH —
        // not a single clean occupant to swap with, so no-op (not a multi-tile reshuffle).
        val layout =
            GridLayout(
                4,
                listOf(
                    GridPlacement("wide", 0, 3, colSpan = 2),
                    GridPlacement("b", 1, 0),
                    GridPlacement("c", 2, 0),
                ),
            )

        val dropped = GridEngine.dropAt(layout, "wide", col = 1, row = 0)

        assertEquals(layout, dropped)
    }

    @Test
    fun `dropAt snaps back on a size-mismatched collision instead of swapping`() {
        val layout =
            GridEngine.repack(4, listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)) + tiles("a"))
        // "a" is 1x1; dropping it onto big's 2x2 origin covers only part of big's footprint.
        val dropped = GridEngine.dropAt(layout, "a", col = 0, row = 0)

        assertEquals(layout, dropped)
    }

    @Test
    fun `dropAt is a no-op for an absent id`() {
        val layout = GridEngine.repack(4, tiles("a", "b"))

        assertEquals(layout, GridEngine.dropAt(layout, "ghost", col = 3, row = 3))
    }

    @Test
    fun `canDrop mirrors dropAt's move-or-swap-or-nothing decision`() {
        val layout = GridEngine.repack(4, tiles("a", "b", "c"))

        assertTrue("empty cell that fits", GridEngine.canDrop(layout, "a", col = 3, row = 2))
        assertTrue("exact-footprint swap target", GridEngine.canDrop(layout, "a", col = 2, row = 0))
        assertFalse("absent id", GridEngine.canDrop(layout, "ghost", col = 0, row = 5))

        val spanned =
            GridEngine.repack(4, listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)) + tiles("a2"))
        assertFalse("size-mismatched partial overlap", GridEngine.canDrop(spanned, "a2", col = 0, row = 0))
    }

    @Test
    fun `swapPositions trades origins and keeps each id's own span, no-op if either id is absent`() {
        val layout =
            GridEngine.repack(
                4,
                listOf(GridPlacement("wide", 0, 0, colSpan = 2)) + tiles("a"),
            )

        val swapped = GridEngine.swapPositions(layout, "wide", "a")

        val wide = swapped.placementFor("wide")!!
        assertEquals(2 to 0, wide.col to wide.row)
        assertEquals(2, wide.colSpan) // span survives the swap
        assertEquals(0 to 0, at(swapped, "a"))
        assertValid(swapped)

        assertEquals(layout, GridEngine.swapPositions(layout, "ghost", "a"))
        assertEquals(layout, GridEngine.swapPositions(layout, "a", "ghost"))
    }

    @Test
    fun `addAt places a new id at an explicit empty cell without repacking`() {
        val layout = GridEngine.repack(4, tiles("a", "b"))

        val added = GridEngine.addAt(layout, "new", col = 3, row = 5)

        assertEquals(3 to 5, at(added, "new"))
        assertEquals(0 to 0, at(added, "a")) // untouched
        assertValid(added)
    }

    @Test
    fun `addAt is a no-op for a duplicate id, an out-of-bounds cell, or a collision`() {
        val layout = GridEngine.repack(4, tiles("a", "b"))

        assertEquals(layout, GridEngine.addAt(layout, "a", col = 3, row = 5)) // already placed
        assertEquals(layout, GridEngine.addAt(layout, "new", col = 4, row = 0)) // col 4 is out of bounds
        assertEquals(layout, GridEngine.addAt(layout, "new", col = 0, row = 0)) // collides with "a"
    }
}
