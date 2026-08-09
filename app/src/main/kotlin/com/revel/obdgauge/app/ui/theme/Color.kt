package com.revel.obdgauge.app.ui.theme

import androidx.compose.ui.graphics.Color

val DarkPrimary = Color(0xFF9ECAFF)
val DarkOnPrimary = Color(0xFF00325B)
val DarkSecondary = Color(0xFFBBC7DB)
val DarkBackground = Color(0xFF10131A)
val DarkSurface = Color(0xFF10131A)

val LightPrimary = Color(0xFF0061A4)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightSecondary = Color(0xFF535F70)
val LightBackground = Color(0xFFFDFCFF)
val LightSurface = Color(0xFFFDFCFF)

// Threshold-zone palette for gauge tiles (OBD-10). Tuned for legibility on the dark
// dash-mount surface above: saturated enough to read at arm's length, not neon. Sourced
// from a config table (`ThresholdConfig`), never hardcoded per gauge — see gauge/ThresholdZone.kt.
val GaugeGreen = Color(0xFF4CAF6D)
val GaugeAmber = Color(0xFFE0A526)
val GaugeRed = Color(0xFFE0524A)
val GaugeNeutral = Color(0xFF9ECAFF)
val GaugeTrackNeutral = Color(0xFF3A4150)
val GaugeStaleDim = Color(0xFF6B7280)
