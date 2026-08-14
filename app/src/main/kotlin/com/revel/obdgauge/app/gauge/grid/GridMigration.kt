package com.revel.obdgauge.app.gauge.grid

import com.revel.obdgauge.app.settings.GaugeOrderEntry

/**
 * Bridges the pre-grid world ([GaugeOrderEntry] list — OBD-21's linear "which gauges, in what
 * order, visible?") to a [GridLayout].
 *
 * Used two ways: to seed a first grid for a user who has only ever had the linear layout (their
 * persisted [GridLayout] is absent), and as the default when nothing is stored at all. Every
 * *visible* gauge becomes a 1×1 placement in its existing reading order — so the migrated grid
 * renders identically to today's single-row/column dashboard until the user resizes something.
 * Hidden entries are dropped: on the grid, "not shown" is "not placed".
 */
object GridMigration {
    fun fromGaugeOrder(
        order: List<GaugeOrderEntry>,
        columns: Int,
    ): GridLayout =
        GridEngine.repack(
            columns,
            order.filter { it.visible }.map { GridPlacement(id = it.id, col = 0, row = 0) },
        )
}
