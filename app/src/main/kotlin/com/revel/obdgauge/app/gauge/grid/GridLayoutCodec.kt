package com.revel.obdgauge.app.gauge.grid

/**
 * OBD-62: compact, dependency-free string serialization for a [GridLayout], co-located with the
 * model rather than in `SettingsCodec` so the format lives next to what it encodes. Format:
 * `columns#id:col:row:colSpan:rowSpan;id:col:row:colSpan:rowSpan`. [decode] returns null on
 * anything malformed — the same "worst case is a silent reset, never a crash" discipline the rest
 * of the settings codec follows.
 */
internal object GridLayoutCodec {
    private const val HEADER_SEPARATOR = "#"
    private const val ENTRY_SEPARATOR = ";"
    private const val FIELD_SEPARATOR = ":"

    // OBD-68: separates multiple encoded layouts (encodeMap/decodeMap) — distinct from every
    // separator a single layout's own encoding uses, so it can never collide with real data
    // (gauge ids are alphanumeric PID names, everything else here is an integer).
    private const val LAYOUT_SEPARATOR = "|"

    // Field order within one `id:col:row:colSpan:rowSpan` placement entry.
    private const val FIELD_ID = 0
    private const val FIELD_COL = 1
    private const val FIELD_ROW = 2
    private const val FIELD_COL_SPAN = 3
    private const val FIELD_ROW_SPAN = 4
    private const val PLACEMENT_FIELD_COUNT = 5

    fun encode(layout: GridLayout): String =
        layout.columns.toString() + HEADER_SEPARATOR +
            layout.placements.joinToString(ENTRY_SEPARATOR) { p ->
                listOf(p.id, p.col, p.row, p.colSpan, p.rowSpan).joinToString(FIELD_SEPARATOR)
            }

    fun decode(raw: String): GridLayout? =
        runCatching {
            val header = raw.split(HEADER_SEPARATOR, limit = 2)
            val columns = header[0].toInt().also { require(it >= 1) }
            val placements =
                header.getOrElse(1) { "" }.split(ENTRY_SEPARATOR).filter { it.isNotBlank() }.map { entry ->
                    val fields = entry.split(FIELD_SEPARATOR)
                    require(fields.size == PLACEMENT_FIELD_COUNT)
                    require(fields[FIELD_ID].isNotBlank())
                    GridPlacement(
                        id = fields[FIELD_ID],
                        col = fields[FIELD_COL].toInt(),
                        row = fields[FIELD_ROW].toInt(),
                        colSpan = fields[FIELD_COL_SPAN].toInt(),
                        rowSpan = fields[FIELD_ROW_SPAN].toInt(),
                    )
                }
            GridLayout(columns, placements)
        }.getOrNull()

    /**
     * OBD-68: encodes a `Map<columns, GridLayout>` — one [encode]d layout per entry, joined by
     * [LAYOUT_SEPARATOR]. Each layout already embeds its own `columns` in its own header, so the
     * map's keys don't need to be written separately; [decodeMap] rebuilds them from that.
     */
    fun encodeMap(layouts: Map<Int, GridLayout>): String = layouts.values.joinToString(LAYOUT_SEPARATOR) { encode(it) }

    /**
     * The inverse of [encodeMap] — `null` if ANY entry fails to decode (same "worst case is a
     * silent reset, never a crash" discipline as [decode]). A string with no [LAYOUT_SEPARATOR] at
     * all (a pre-OBD-68 single-layout encoding, or a blank/absent value) decodes as a single- or
     * zero-entry map — the free migration path: an old install's one persisted layout becomes a
     * one-entry map keyed by whatever `columns` it was stored at, and `GridLayoutSet.ensureColumns`
     * seeds the other orientation from it the same way it would seed any other gap.
     */
    fun decodeMap(raw: String): Map<Int, GridLayout>? {
        if (raw.isBlank()) return emptyMap()
        val decoded = raw.split(LAYOUT_SEPARATOR).filter { it.isNotBlank() }.map { entry -> decode(entry) }
        return if (decoded.any { it == null }) null else decoded.filterNotNull().associateBy { it.columns }
    }
}
