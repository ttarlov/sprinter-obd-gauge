package com.revel.obdgauge.app.gauge.grid

import com.revel.obdgauge.app.settings.GaugeOrderEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** OBD-62: migrating the linear OBD-21 gauge order into a grid renders identically until resized. */
class GridMigrationTest {
    @Test
    fun `visible gauges become 1x1 placements in reading order`() {
        val order =
            listOf(
                GaugeOrderEntry("coolant"),
                GaugeOrderEntry("oil"),
                GaugeOrderEntry("trans"),
                GaugeOrderEntry("boost"),
            )

        val grid = GridMigration.fromGaugeOrder(order, columns = 4)

        assertEquals(listOf("coolant", "oil", "trans", "boost"), grid.ids)
        grid.placements.forEach {
            assertEquals("unit width", 1, it.colSpan)
            assertEquals("unit height", 1, it.rowSpan)
        }
        assertEquals(0 to 0, grid.placementFor("coolant")!!.let { it.col to it.row })
        assertEquals(3 to 0, grid.placementFor("boost")!!.let { it.col to it.row })
    }

    @Test
    fun `hidden entries are dropped, not placed`() {
        val order = listOf(GaugeOrderEntry("coolant"), GaugeOrderEntry("oil", visible = false), GaugeOrderEntry("rpm"))

        val grid = GridMigration.fromGaugeOrder(order, columns = 4)

        assertEquals(listOf("coolant", "rpm"), grid.ids)
    }
}
