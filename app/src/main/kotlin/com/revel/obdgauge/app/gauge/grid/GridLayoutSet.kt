package com.revel.obdgauge.app.gauge.grid

import com.revel.obdgauge.app.settings.GaugeOrderEntry

/**
 * OBD-67 round-4 (device-verified — freeform must work in BOTH orientations, not just landscape):
 * a freeform arrangement is inherently tied to a column count — landscape (4 columns) and portrait
 * (2 columns) are different canvases, and a single position set can't serve both without a lossy
 * translation (exactly the portrait limitation flagged and now resolved). So the dashboard persists
 * one [GridLayout] **per column count** (`AppSettings.gridLayoutsByColumns`) instead of one
 * canonical layout repacked per orientation at render time.
 *
 * Positions/spans are edited per-orientation — arranging landscape doesn't touch portrait's
 * layout and vice versa (`GridEngine.dropAt`/`resize`/`addAt` each apply to exactly ONE stored
 * layout). But the SET of gauge ids must stay identical across every stored layout: you never want
 * a gauge present on the dash-mount landscape grid but silently missing in portrait. This object is
 * the pure, tested layer that keeps that invariant — every add/remove/swap here touches every
 * layout in the map, never just one.
 */
object GridLayoutSet {
    /**
     * Ensures a [GridLayout] exists for every count in [columnCounts], seeding any missing one by
     * repacking an ARBITRARY existing entry into that column count (any entry works: the gauge-set
     * invariant this file maintains means every stored layout already has the same ids) — or, if
     * [layouts] is empty entirely (a fresh install, or migrating a pre-OBD-68 install that never
     * had a grid), by [GridMigration.fromGaugeOrder]. Idempotent: re-running with the same
     * [columnCounts] on an already-complete map returns it unchanged.
     *
     * This is also the single-layout→multi-layout migration path, for free: a pre-OBD-68 install's
     * persisted single [GridLayout] decodes (see `GridLayoutCodec.decodeMap`) as a one-entry map
     * keyed by whatever column count it was stored at (canonical/landscape) — calling this with
     * both required counts seeds the missing orientation from that one entry exactly the same way
     * it would seed any other gap, no separate migration code needed.
     */
    fun ensureColumns(
        layouts: Map<Int, GridLayout>,
        columnCounts: Set<Int>,
        fallbackOrder: List<GaugeOrderEntry>,
    ): Map<Int, GridLayout> {
        val missing = columnCounts.filterNot { it in layouts }
        if (missing.isEmpty()) return layouts
        val seedSource = layouts.values.firstOrNull()
        val seeded =
            missing.associateWith { columns ->
                if (seedSource !=
                    null
                ) {
                    GridEngine.withColumns(seedSource, columns)
                } else {
                    GridMigration.fromGaugeOrder(fallbackOrder, columns)
                }
            }
        return layouts + seeded
    }

    /**
     * Adds [id] at an explicit cell in the [atColumns] layout ([GridEngine.addAt] — the "＋"
     * empty-cell flow's per-orientation placement) and, to keep the gauge-set invariant, into
     * EVERY other stored layout too via [GridEngine.addInFirstFreeSlot] (a sensible free slot,
     * since those orientations have no user-chosen cell for it). A no-op for any layout [id] is
     * already placed on (both engine primitives already guard that individually).
     */
    @Suppress("LongParameterList") // layouts/id/atColumns/col/row/colSpan/rowSpan are all irreducible inputs.
    fun addEverywhere(
        layouts: Map<Int, GridLayout>,
        id: String,
        atColumns: Int,
        col: Int,
        row: Int,
        colSpan: Int = 1,
        rowSpan: Int = 1,
    ): Map<Int, GridLayout> =
        layouts.mapValues { (columns, layout) ->
            if (columns == atColumns) {
                GridEngine.addAt(layout, id, col, row, colSpan, rowSpan)
            } else {
                GridEngine.addInFirstFreeSlot(layout, id, colSpan, rowSpan)
            }
        }

    /**
     * The plain "＋ Add" edit-bar flow (no chosen cell, OBD-64) — adds [id] wherever there's room
     * in EVERY stored layout via [GridEngine.addInFirstFreeSlot]. Unlike [addEverywhere] there's no
     * per-orientation "explicit cell" special case, since the caller never picked one.
     */
    fun addAnywhereEverywhere(
        layouts: Map<Int, GridLayout>,
        id: String,
    ): Map<Int, GridLayout> = layouts.mapValues { (_, layout) -> GridEngine.addInFirstFreeSlot(layout, id) }

    /** Removes [id] from every stored layout ([GridEngine.remove], each repacked closed). */
    fun removeEverywhere(
        layouts: Map<Int, GridLayout>,
        id: String,
    ): Map<Int, GridLayout> = layouts.mapValues { (_, layout) -> GridEngine.remove(layout, id) }

    /**
     * Renames [oldId] to [newId] in place ([GridEngine.replaceId] — same cell/span, only the id
     * changes) in every stored layout. The swap picker changes WHICH gauge occupies a slot, so it
     * is exactly as much a gauge-set change as add/remove and needs the same sync.
     */
    fun replaceIdEverywhere(
        layouts: Map<Int, GridLayout>,
        oldId: String,
        newId: String,
    ): Map<Int, GridLayout> = layouts.mapValues { (_, layout) -> GridEngine.replaceId(layout, oldId, newId) }
}
