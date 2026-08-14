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

    @Test
    fun `placementAt finds the owner of a spanned cell`() {
        val layout = GridEngine.repack(4, listOf(GridPlacement("big", 0, 0, colSpan = 2, rowSpan = 2)))

        assertEquals("big", layout.placementAt(1, 1)?.id)
        assertNull(layout.placementAt(3, 3))
    }
}
