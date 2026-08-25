package com.revel.obdgauge.app.gauge

/**
 * OBD-72: which visual language a gauge tile renders in. [DIGITAL] (numeric value, the
 * pre-OBD-72 look) stays the default for every gauge — [NEEDLE] and [BAR_ARC] are opt-in per
 * gauge, chosen through the gear editor ([GaugeEditorFace] in `GaugePicker.kt`) and persisted in
 * [com.revel.obdgauge.app.settings.AppSettings.renderStyles].
 */
enum class GaugeRenderStyle {
    /** The original numeric tile: label, big value, optional stale text. */
    DIGITAL,

    /** A round dial with a swept needle, tick scale, and colored threshold zone arcs. */
    NEEDLE,

    /** A segmented LED-style bar arcing around a big central digital readout. */
    BAR_ARC,
}
