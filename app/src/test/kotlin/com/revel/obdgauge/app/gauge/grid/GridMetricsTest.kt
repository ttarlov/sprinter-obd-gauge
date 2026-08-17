package com.revel.obdgauge.app.gauge.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OBD-63: the pure pixel math behind [GaugeGrid]'s spanning renderer — cell sizing, per-placement
 * rects, spanning arithmetic, and the >4-row content height that drives scroll. No Compose here;
 * that is the whole point of keeping the math in [GridMetrics].
 */
class GridMetricsTest {
    private companion object {
        const val EPS = 0.001f
    }

    @Test
    fun `cell width divides available width minus gutters across columns`() {
        // 400 wide, 4 cols, 10 gutter → (400 - 30) / 4 = 92.5
        assertEquals(92.5f, GridMetrics.cellWidth(totalWidth = 400f, columns = 4, spacing = 10f), EPS)
    }

    @Test
    fun `single column is the full width`() {
        assertEquals(400f, GridMetrics.cellWidth(totalWidth = 400f, columns = 1, spacing = 10f), EPS)
    }

    @Test
    fun `cell height fills the viewport when rows fit`() {
        // 1 row, 300 tall → the whole height (matches today's single-row landscape look)
        val cell = GridMetrics.cellSize(400f, 300f, columns = 4, rows = 1, spacing = 10f, minCellHeight = 96f)
        assertEquals(300f, cell.height, EPS)
        assertEquals(92.5f, cell.width, EPS)
    }

    @Test
    fun `two rows split the viewport height minus gutter`() {
        // 2 rows, 300 tall, 10 gutter → (300 - 10) / 2 = 145 each
        val cell = GridMetrics.cellSize(400f, 300f, columns = 4, rows = 2, spacing = 10f, minCellHeight = 96f)
        assertEquals(145f, cell.height, EPS)
    }

    @Test
    fun `cell height is floored at minCellHeight when too many rows to fit`() {
        // 6 rows into 300 tall would be ~48 each; floored to the 96 minimum so tiles stay usable
        val cell = GridMetrics.cellSize(400f, 300f, columns = 2, rows = 6, spacing = 10f, minCellHeight = 96f)
        assertEquals(96f, cell.height, EPS)
    }

    @Test
    fun `many rows produce a content height that exceeds the viewport (scroll territory)`() {
        val rows = 6
        val cell = GridMetrics.cellSize(400f, 300f, columns = 2, rows = rows, spacing = 10f, minCellHeight = 96f)
        val contentHeight = GridMetrics.contentHeight(rows, cell, spacing = 10f)
        // 6 * 96 + 5 * 10 = 626 > 300 viewport → the grid must scroll, never clip
        assertEquals(626f, contentHeight, EPS)
        assertTrue("content taller than viewport", contentHeight > 300f)
    }

    @Test
    fun `empty layout yields zero content height`() {
        val cell = GridMetrics.cellSize(400f, 300f, columns = 4, rows = 0, spacing = 10f, minCellHeight = 96f)
        assertEquals(0f, GridMetrics.contentHeight(rows = 0, cell = cell, spacing = 10f), EPS)
    }

    @Test
    fun `placement rect for a 1x1 tile is one cell at its cell origin`() {
        val cell = CellSize(width = 100f, height = 80f)
        // col 2, row 1 → left = 2*(100+10)=220, top = 1*(80+10)=90
        val rect = GridMetrics.placementRect(GridPlacement("t", col = 2, row = 1), cell, spacing = 10f)
        assertEquals(220f, rect.left, EPS)
        assertEquals(90f, rect.top, EPS)
        assertEquals(100f, rect.width, EPS)
        assertEquals(80f, rect.height, EPS)
    }

    @Test
    fun `a 2x1 wide tile absorbs the interior gutter`() {
        val cell = CellSize(width = 100f, height = 80f)
        // 2 cols wide → 2*100 + 1*10 gutter = 210 wide, still one row tall
        val rect = GridMetrics.placementRect(GridPlacement("t", 0, 0, colSpan = 2, rowSpan = 1), cell, spacing = 10f)
        assertEquals(0f, rect.left, EPS)
        assertEquals(210f, rect.width, EPS)
        assertEquals(80f, rect.height, EPS)
    }

    @Test
    fun `a 2x2 tile spans two cells each way plus their gutters`() {
        val cell = CellSize(width = 100f, height = 80f)
        val rect = GridMetrics.placementRect(GridPlacement("t", 1, 1, colSpan = 2, rowSpan = 2), cell, spacing = 10f)
        assertEquals(110f, rect.left, EPS) // 1*(100+10)
        assertEquals(90f, rect.top, EPS) // 1*(80+10)
        assertEquals(210f, rect.width, EPS) // 2*100 + 10
        assertEquals(170f, rect.height, EPS) // 2*80 + 10
    }

    @Test
    fun `adjacent placements tile without overlap or gap beyond the spacing`() {
        val cell = CellSize(width = 100f, height = 80f)
        val a = GridMetrics.placementRect(GridPlacement("a", 0, 0), cell, spacing = 10f)
        val b = GridMetrics.placementRect(GridPlacement("b", 1, 0), cell, spacing = 10f)
        // b starts exactly one cell + one gutter right of a's left edge
        assertEquals(a.left + a.width + 10f, b.left, EPS)
    }

    // --- OBD-67: cellAt, placementRect's inverse -----------------------------------------------

    @Test
    fun `cellAt resolves a point inside a cell to that cell`() {
        val cell = CellSize(width = 100f, height = 80f)
        // col 2, row 1's rect starts at (220, 90) — a point well inside it.
        assertEquals(Cell(2, 1), GridMetrics.cellAt(x = 250f, y = 120f, cell, spacing = 10f, columns = 4))
    }

    @Test
    fun `cellAt attributes a gutter point to the cell it trails`() {
        val cell = CellSize(width = 100f, height = 80f)
        // Col 0 occupies [0,100); the gutter [100,110) precedes col 1's cell at 110 — still col 0.
        assertEquals(0, GridMetrics.cellAt(x = 105f, y = 5f, cell, spacing = 10f, columns = 4).col)
        assertEquals(1, GridMetrics.cellAt(x = 110f, y = 5f, cell, spacing = 10f, columns = 4).col)
    }

    @Test
    fun `cellAt clamps col to the grid width and floors row at zero`() {
        val cell = CellSize(width = 100f, height = 80f)
        assertEquals(0, GridMetrics.cellAt(x = -50f, y = -50f, cell, spacing = 10f, columns = 4).col)
        assertEquals(0, GridMetrics.cellAt(x = -50f, y = -50f, cell, spacing = 10f, columns = 4).row)
        assertEquals(3, GridMetrics.cellAt(x = 10_000f, y = 5f, cell, spacing = 10f, columns = 4).col)
    }

    @Test
    fun `cellAt resolves the interior of a spanning placement's second cell, not its origin`() {
        val cell = CellSize(width = 100f, height = 80f)
        val big = GridPlacement("big", col = 1, row = 1, colSpan = 2, rowSpan = 2)
        val rect = GridMetrics.placementRect(big, cell, spacing = 10f)
        // A point in the span's bottom-right cell resolves to (2, 2), not big's own (1, 1) origin —
        // GridEngine.targetIndexAt is what maps a spanning interior back to the owning placement.
        val point = Cell(2, 2)
        assertTrue("point lies within big's rect", rect.left + rect.width > 0 && rect.top + rect.height > 0)
        assertEquals(point, GridMetrics.cellAt(x = 250f, y = 200f, cell, spacing = 10f, columns = 4))
    }

    @Test
    fun `cellAt round-trips placementRect's origin for every placed tile`() {
        val cell = CellSize(width = 100f, height = 80f)
        val spacing = 10f
        val layout = GridEngine.repack(4, listOf(GridPlacement("a", 0, 0), GridPlacement("b", 0, 0)))
        layout.placements.forEach { placement ->
            val rect = GridMetrics.placementRect(placement, cell, spacing)
            // A point just inside the rect's top-left corner must resolve back to this cell.
            val resolved = GridMetrics.cellAt(rect.left + 1f, rect.top + 1f, cell, spacing, layout.columns)
            assertEquals(Cell(placement.col, placement.row), resolved)
        }
    }
}
