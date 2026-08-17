package com.revel.obdgauge.app.gauge.grid

import com.revel.obdgauge.app.settings.GaugeOrderEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBD-67 round-4 (device-verified — freeform must work in both orientations): the gauge-set sync
 * invariant every [GridLayoutSet] mutator maintains — every stored per-column-count layout must
 * agree on WHICH gauges are present, even though their positions/spans are independent per
 * orientation. Mirrors the reviewer's own device-verify plan: "add in portrait → appears in
 * landscape's layout; remove → gone from both."
 */
class GridLayoutSetTest {
    private fun idSets(layouts: Map<Int, GridLayout>): Set<Set<String>> = layouts.values.map { it.ids.toSet() }.toSet()

    @Test
    fun `ensureColumns seeds a missing column count by repacking an existing layout`() {
        val landscape = GridEngine.repack(4, listOf(GridPlacement("a", 0, 0), GridPlacement("b", 0, 0)))
        val layouts = mapOf(4 to landscape)

        val ensured = GridLayoutSet.ensureColumns(layouts, setOf(4, 2), fallbackOrder = emptyList())

        assertEquals(setOf(4, 2), ensured.keys)
        assertEquals(2, ensured.getValue(2).columns)
        assertEquals(setOf("a", "b"), ensured.getValue(2).ids.toSet()) // same gauge set, repacked
    }

    @Test
    fun `ensureColumns seeds from gaugeOrder when the map is entirely empty`() {
        val order = listOf(GaugeOrderEntry("a"), GaugeOrderEntry("b", visible = false), GaugeOrderEntry("c"))

        val ensured = GridLayoutSet.ensureColumns(emptyMap(), setOf(4, 2), fallbackOrder = order)

        assertEquals(setOf(4, 2), ensured.keys)
        // GridMigration.fromGaugeOrder only places VISIBLE entries — "b" stays out of both.
        assertEquals(setOf("a", "c"), ensured.getValue(4).ids.toSet())
        assertEquals(setOf("a", "c"), ensured.getValue(2).ids.toSet())
    }

    @Test
    fun `ensureColumns is idempotent once every required count is already present`() {
        val layouts =
            mapOf(
                4 to GridEngine.repack(4, listOf(GridPlacement("a", 0, 0))),
                2 to GridEngine.repack(2, listOf(GridPlacement("a", 0, 0))),
            )

        val ensured = GridLayoutSet.ensureColumns(layouts, setOf(4, 2), fallbackOrder = emptyList())

        assertEquals(layouts, ensured)
    }

    @Test
    fun `addEverywhere places the explicit cell in its own orientation and a free slot in every other`() {
        val layouts =
            mapOf(
                4 to GridEngine.repack(4, listOf(GridPlacement("a", 0, 0))),
                2 to GridEngine.repack(2, listOf(GridPlacement("a", 0, 0))),
            )

        // Add "new" in the 2-col (portrait) layout at an explicit cell...
        val added = GridLayoutSet.addEverywhere(layouts, "new", atColumns = 2, col = 1, row = 3)

        val portrait = added.getValue(2).placementFor("new")!!
        assertEquals(1, portrait.col)
        assertEquals(3, portrait.row)
        // ...and it must also appear in landscape's layout (the invariant), even though landscape
        // never got a chosen cell for it — addInFirstFreeSlot picks a sensible one there.
        assertTrue("new gauge must sync to every other stored layout", added.getValue(4).ids.contains("new"))
        assertEquals(idSets(added).size, 1) // both layouts now agree on the exact same gauge set
    }

    @Test
    fun `addAnywhereEverywhere (the plain edit-bar Add) places the gauge in every stored layout`() {
        val layouts =
            mapOf(
                4 to GridEngine.repack(4, listOf(GridPlacement("a", 0, 0))),
                2 to GridEngine.repack(2, listOf(GridPlacement("a", 0, 0))),
            )

        val added = GridLayoutSet.addAnywhereEverywhere(layouts, "new")

        assertTrue(added.getValue(4).ids.contains("new"))
        assertTrue(added.getValue(2).ids.contains("new"))
        assertEquals(idSets(added).size, 1)
    }

    @Test
    fun `removeEverywhere drops the gauge from every stored layout`() {
        val layouts =
            mapOf(
                4 to GridEngine.repack(4, listOf(GridPlacement("a", 0, 0), GridPlacement("b", 0, 0))),
                2 to GridEngine.repack(2, listOf(GridPlacement("a", 0, 0), GridPlacement("b", 0, 0))),
            )

        val removed = GridLayoutSet.removeEverywhere(layouts, "a")

        assertFalse(removed.getValue(4).ids.contains("a"))
        assertFalse(removed.getValue(2).ids.contains("a"))
        assertTrue(removed.getValue(4).ids.contains("b"))
        assertTrue(removed.getValue(2).ids.contains("b"))
    }

    @Test
    fun `replaceIdEverywhere renames the gauge in place in every stored layout, independent spans preserved`() {
        val layouts =
            mapOf(
                4 to GridEngine.repack(4, listOf(GridPlacement("old", 0, 0, colSpan = 2))),
                2 to GridEngine.repack(2, listOf(GridPlacement("old", 0, 0))), // NOT wide in portrait
            )

        val renamed = GridLayoutSet.replaceIdEverywhere(layouts, "old", "new")

        assertEquals(null, renamed.getValue(4).placementFor("old"))
        assertEquals(2, renamed.getValue(4).placementFor("new")!!.colSpan) // landscape's own span kept
        assertEquals(1, renamed.getValue(2).placementFor("new")!!.colSpan) // portrait's own span kept
    }
}
