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
}
