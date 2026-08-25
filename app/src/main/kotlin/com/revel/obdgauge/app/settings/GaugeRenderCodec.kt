package com.revel.obdgauge.app.settings

import com.revel.obdgauge.app.gauge.GaugeRenderStyle
import com.revel.obdgauge.app.gauge.GaugeScale

// OBD-72: [AppSettings.renderStyles]/[AppSettings.scaleOverrides] encoding, split out of
// SettingsCodec.kt (own file, per `GridLayoutCodec.kt`'s precedent — "the format lives next to
// what it encodes" — and to keep SettingsCodec.kt under detekt's per-file function-count limit).
// Same "worst case is a silent reset, never a crash" discipline as the rest of `SettingsCodec.kt`
// — internal, not private, so SettingsCodec.kt's decode/encodeAppSettings can call these.

private const val ENTRY_SEPARATOR = ";"
private const val FIELD_SEPARATOR = ":"
private const val LAYOUT_SEPARATOR = "|"
private const val RENDER_STYLE_FIELD_COUNT = 2
private const val SCALE_FIELD_COUNT = 4

/** "id:STYLE_NAME;id:STYLE_NAME" — the same shape gauge order's own id:field encoding uses. */
internal fun encodeRenderStyles(styles: Map<String, GaugeRenderStyle>): String =
    styles.entries.joinToString(ENTRY_SEPARATOR) { (id, style) -> "$id$FIELD_SEPARATOR${style.name}" }

internal fun decodeRenderStyles(raw: String): Map<String, GaugeRenderStyle> =
    raw
        .split(ENTRY_SEPARATOR)
        .filter { it.isNotBlank() }
        .mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != RENDER_STYLE_FIELD_COUNT) return@mapNotNull null
            val style = runCatching { GaugeRenderStyle.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
            parts[0] to style
        }.toMap()

/**
 * "id:min:max:tick|id:min:max:tick" — the same field-count-guard/never-throw discipline threshold
 * overrides use ([SettingsCodec.kt]'s own threshold codec), with `|` between entries since none
 * of a scale's three numeric fields can ever collide with it.
 */
internal fun encodeScaleOverrides(scales: Map<String, GaugeScale>): String =
    scales.entries.joinToString(LAYOUT_SEPARATOR) { (id, scale) ->
        listOf(id, scale.min, scale.max, scale.tick).joinToString(FIELD_SEPARATOR)
    }

internal fun decodeScaleOverrides(raw: String): Map<String, GaugeScale> =
    raw
        .split(LAYOUT_SEPARATOR)
        .filter { it.isNotBlank() }
        .mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != SCALE_FIELD_COUNT) return@mapNotNull null
            val scale =
                GaugeScale(
                    min = parts[1].toDoubleOrNull() ?: return@mapNotNull null,
                    max = parts[2].toDoubleOrNull() ?: return@mapNotNull null,
                    tick = parts[3].toDoubleOrNull() ?: return@mapNotNull null,
                )
            parts[0] to scale
        }.toMap()
