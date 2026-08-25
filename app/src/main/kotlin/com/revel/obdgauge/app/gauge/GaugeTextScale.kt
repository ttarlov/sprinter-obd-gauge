package com.revel.obdgauge.app.gauge

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// OBD-72 device fix (Taras, on-Pixel): every text element inside a gauge tile — the digital
// value/label/stale text, the needle's center label+value AND its Canvas-drawn tick labels, the
// bar-arc's center value+label — now scales with the TILE'S OWN measured size instead of a fixed
// sp. Device report: shrinking a NEEDLE gauge to 1×1 shrank the dial correctly (it's Canvas-drawn
// off the measured size already) but left the value number pinned at its fixed sp, "swallowing"
// the now-tiny tile — and per Taras, the plain DIGITAL tile has had the exact same bug since
// before OBD-72 ("we had that issue previously where none of the numbers scaled"). Every
// `*GaugeBody` composable (`DashboardScreen.kt`'s `DigitalGaugeBody`/`BoostTile`,
// `NeedleGauge.kt`'s `NeedleGaugeBody`, `BarArcGauge.kt`'s `BarArcGaugeBody`) measures its own
// tile dimension (`BoxWithConstraints`) and derives every `TextUnit` from it via [scaledTextSize],
// using the shared fraction/clamp constants below so a gauge looks consistent across all three
// render styles at the same tile size.

/**
 * A font size (px) [fraction] of [dimensionPx], clamped to `[minPx, maxPx]`. Pure/testable — no
 * Compose dependency — the same split `GaugeFormatting.kt`/`UnitConversion.kt` use for their math.
 */
fun scaledFontSizePx(
    dimensionPx: Float,
    fraction: Float,
    minPx: Float,
    maxPx: Float,
): Float = (dimensionPx * fraction).coerceIn(minPx, maxPx)

/**
 * [scaledFontSizePx] as a [TextUnit], for a Compose call site holding a measured [dimension] —
 * typically [androidx.compose.foundation.layout.BoxWithConstraints]'s own
 * `min(maxWidth, maxHeight)`. [min]/[max] stay in `sp` (not raw px) so the clamp itself still
 * respects the device's font-scale accessibility setting.
 */
@Composable
fun scaledTextSize(
    dimension: Dp,
    fraction: Float,
    min: TextUnit,
    max: TextUnit,
): TextUnit {
    val density = LocalDensity.current
    return with(density) {
        scaledFontSizePx(dimension.toPx(), fraction, min.toPx(), max.toPx()).toSp()
    }
}

// The big numeric readout (GaugeValueTextStyle's fixed 34sp before this fix). 0.20 of a typical
// ~160dp 1×1 landscape cell lands close to that pre-existing 34sp, so a default-sized tile looks
// essentially unchanged; a 2×2 tile grows toward the ceiling, a squeezed cell shrinks toward the
// floor rather than overflowing it.
internal const val VALUE_FONT_FRACTION = 0.20f
internal val VALUE_FONT_MIN_SP = 16.sp
internal val VALUE_FONT_MAX_SP = 48.sp

// The gauge title (titleMedium's fixed ~16sp before this fix).
internal const val LABEL_FONT_FRACTION = 0.10f
internal val LABEL_FONT_MIN_SP = 10.sp
internal val LABEL_FONT_MAX_SP = 20.sp

// The NEEDLE style's value sits inside the dial's NARROW open bottom gap — not the full tile width
// the DIGITAL/BAR_ARC value gets — so it scales smaller: at the digital 0.20 fraction "208°F"
// overflows the gap, clips its own "F" at the tile edge, and collides with the bottom ticks on a
// 1×1 (Taras, on-Pixel). Lower fraction + lower ceiling keeps it inside the gap at every size.
internal const val NEEDLE_VALUE_FONT_FRACTION = 0.135f
internal val NEEDLE_VALUE_FONT_MIN_SP = 12.sp
internal val NEEDLE_VALUE_FONT_MAX_SP = 30.sp

// The DIGITAL style's "last seen Xs ago" stale line — smaller than the label, same idea.
internal const val STALE_FONT_FRACTION = 0.065f
internal val STALE_FONT_MIN_SP = 9.sp
internal val STALE_FONT_MAX_SP = 14.sp

/**
 * A [Dp] size that is [fraction] of a measured [dimension], clamped to `[min, max]` — the dp
 * counterpart of [scaledTextSize] for non-text controls that must scale with the tile.
 *
 * OBD-72 (Taras, on-Pixel): the flip-card gauge editor's controls — the style chips, the
 * threshold +/- steppers, and the color squares — were fixed dp, so on a shrunk gauge the
 * threshold row overflowed the tiny face and its lower half (the value, the `−`, the red square)
 * was clipped off-tile and unreachable. Sizing them off the editor face's own measured dimension
 * keeps every control fully visible and tappable from the smallest cell up to a 2×2.
 */
fun scaledDp(
    dimension: Dp,
    fraction: Float,
    min: Dp,
    max: Dp,
): Dp = (dimension * fraction).coerceIn(min, max)

// --- OBD-72 flip-card editor control scaling (fractions of the editor face's min dimension) ---

// Style-picker chips (Digital/Needle/Bar): label text + inner padding.
internal const val EDITOR_CHIP_FONT_FRACTION = 0.085f
internal val EDITOR_CHIP_FONT_MIN_SP = 8.sp
internal val EDITOR_CHIP_FONT_MAX_SP = 14.sp
internal const val EDITOR_CHIP_H_PAD_FRACTION = 0.05f
internal val EDITOR_CHIP_H_PAD_MIN = 5.dp
internal val EDITOR_CHIP_H_PAD_MAX = 12.dp
internal const val EDITOR_CHIP_V_PAD_FRACTION = 0.03f
internal val EDITOR_CHIP_V_PAD_MIN = 3.dp
internal val EDITOR_CHIP_V_PAD_MAX = 7.dp

// Threshold +/- stepper buttons: square touch target + glyph.
internal const val EDITOR_STEP_BUTTON_FRACTION = 0.20f
internal val EDITOR_STEP_BUTTON_MIN = 24.dp
internal val EDITOR_STEP_BUTTON_MAX = 40.dp
internal const val EDITOR_STEP_GLYPH_FRACTION = 0.14f
internal val EDITOR_STEP_GLYPH_MIN_SP = 14.sp
internal val EDITOR_STEP_GLYPH_MAX_SP = 26.sp

// Threshold selected-boundary value number.
internal const val EDITOR_VALUE_FONT_FRACTION = 0.14f
internal val EDITOR_VALUE_FONT_MIN_SP = 13.sp
internal val EDITOR_VALUE_FONT_MAX_SP = 26.sp

// Threshold color squares (YELLOW over RED).
internal const val EDITOR_SQUARE_FRACTION = 0.17f
internal val EDITOR_SQUARE_MIN = 16.dp
internal val EDITOR_SQUARE_MAX = 30.dp

// Inter-control spacing (chip gaps, row/square gaps, face padding).
internal const val EDITOR_SPACING_FRACTION = 0.05f
internal val EDITOR_SPACING_MIN = 3.dp
internal val EDITOR_SPACING_MAX = 8.dp
