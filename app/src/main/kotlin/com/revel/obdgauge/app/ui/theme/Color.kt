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

// OBD-27: unverified-PID badge. Deliberately its own hue, not a reuse of GaugeAmber/GaugeRed —
// this badge answers "is this number proven," an axis completely orthogonal to "is this value
// normal" (the green/amber/red threshold vocabulary above). Reusing amber/red here would read
// as a threshold warning on a channel that might currently be sitting GREEN, which is exactly
// the confusion a truth-marker badge must not create.
//
// Round-1 review fix (B9 MINOR — reviews/OBD-24-round1.md): the original 0xFF8B7EC8 measured
// 3.56:1 contrast against white glyph text, under WCAG AA's 4.5:1 minimum for normal-size text.
// This darker violet's WCAG relative luminance is ~0.099 (sRGB->linear per spec), giving
// (1.0+0.05)/(0.099+0.05) ≈ 7.0:1 against GaugeUnverifiedBadgeContent — comfortably AA-compliant
// with headroom, while staying visually the same hue family (still clearly not green/amber/red).
val GaugeUnverifiedBadge = Color(0xFF5A4E99)
val GaugeUnverifiedBadgeContent = Color(0xFFFFFFFF)
