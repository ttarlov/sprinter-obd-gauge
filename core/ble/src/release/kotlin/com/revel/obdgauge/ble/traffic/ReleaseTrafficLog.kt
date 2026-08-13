package com.revel.obdgauge.ble.traffic

/**
 * Release builds capture nothing (OBD-48).
 *
 * The debug-variant twin of this file (`src/debug/.../LogcatTrafficLog.kt`) holds the logcat
 * sink, the `ObdTraffic` tag and the formatter. Neither the class nor the tag exists in the
 * release variant of this module at all — that is the fence, and it is checked by dex/AAR
 * inspection rather than trusted.
 */
internal fun defaultTrafficLog(): TrafficLog = TrafficLog.NONE
