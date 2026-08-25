package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeRed
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle
import com.revel.obdgauge.app.ui.theme.ThresholdSelectBlue
import com.revel.obdgauge.model.MeasurementUnit

// OBD-66/72: the gauge editor's controls — split out of GaugePicker.kt (own file, so that
// file stays under detekt's per-file function-count limit; a cohesive unit of its own, same
// reasoning `GaugeTileInteraction.kt`/`RearrangeMode.kt` already use for their own slices).
//
// OBD-77: this file no longer owns a single host. [EditorControls] — the style picker plus the
// threshold stepper/squares — is the reusable body; [GaugeEditorFace] is only ONE of its two
// hosts (the in-tile flip card's back face, driven by the gear on a focused `SwapPager` page,
// still tile-scaled through [rememberEditorScale]). The other, and the one the ⚙ rearrange badge
// now opens, is `GaugeEditorCard.kt`'s dashboard-level floating card, which feeds the SAME
// controls a fixed [roomyEditorScale] instead — the whole point of OBD-77 being that the editor's
// comfortable minimum size stops being negotiable against a 1×1 cell.

private const val SWAP_FRAME_BORDER_WIDTH_DP = 1
private const val THRESHOLD_SQUARE_CORNER_DP = 6

// Top keep-out for the flip-back gear ⚙ (GearButton is 28dp + 4dp padding, pinned TopStart over
// this face by SwapPagerCard). Content starts below it so the first chip row can't sit on the
// gear. Only the FLIP host passes it; the floating card has a real header instead.
private const val GEAR_CLEARANCE_DP = 34
private const val THRESHOLD_SQUARE_FILL_ALPHA = 0.85f
private const val THRESHOLD_SELECT_BORDER_DP = 3
private const val THRESHOLD_UNSELECT_BORDER_DP = 1

// OBD-77: the floating card's section captions ("STYLE" / "THRESHOLD") — dimmed, letter-spaced
// small caps, the instrument-panel idiom. Off in the cramped flip host, where a caption would
// cost more vertical space than it buys.
private const val CAPTION_ALPHA = 0.55f
private const val CAPTION_LETTER_SPACING_SP = 1.4f

// OBD-77: the gap BETWEEN captioned sections (the two-column card's gutter, and the stacked
// card's section separation), as a multiple of the scale's own within-a-section spacing.
private const val SECTION_GAP_FACTOR = 2

/**
 * OBD-66/72: the back of the in-tile flipped card, and — since OBD-77 — only that host. Wraps
 * [EditorControls] in the card's own tile-matching chrome (surface + primary hairline), scales
 * every control off this face's OWN measured size ([rememberEditorScale]) since a flip card is
 * stuck at whatever the tile is, and indents the first chip row past the flip-back gear ⚙.
 *
 * The ⚙ rearrange badge no longer routes here — it opens `GaugeEditorCard.kt`'s floating card
 * over the whole dashboard instead (OBD-77). What's left reaching this face is the gear on a
 * focused `SwapPager` candidate card, i.e. "I'm already browsing gauges, restyle this one."
 *
 * Threshold values are shown and stepped in the gauge's declared [unit] (FAHRENHEIT for the
 * temperature gauges this section is offered on), which is also the wire unit thresholds are
 * stored in — so no conversion is needed and the number matches the tile's own °F readout.
 */
@Composable
@Suppress("LongParameterList") // id/unit/thresholds/hasThresholds/style/onSetThreshold/onSetStyle — load-bearing.
internal fun GaugeEditorFace(
    id: String,
    unit: MeasurementUnit,
    thresholds: GaugeThresholds,
    hasThresholds: Boolean,
    style: GaugeRenderStyle,
    onSetThreshold: (GaugeThresholds) -> Unit,
    onSetStyle: (GaugeRenderStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    BoxWithConstraints(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .border(SWAP_FRAME_BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.primary, shape),
    ) {
        // OBD-72 (Taras, on-Pixel): every control scales off the editor face's own measured size
        // so the threshold row can't overflow + hide its lower half when the gauge is shrunk. A
        // verticalScroll backstop guarantees anything that still doesn't fit at the tiniest cell
        // stays reachable rather than clipped off-tile.
        val s = rememberEditorScale(min(maxWidth, maxHeight))
        EditorControls(
            id = id,
            unit = unit,
            thresholds = thresholds,
            hasThresholds = hasThresholds,
            style = style,
            onSetThreshold = onSetThreshold,
            onSetStyle = onSetStyle,
            layout = EditorLayout(scale = s, chipRowStartPadding = GEAR_CLEARANCE_DP.dp),
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(s.spacing),
        )
    }
}

/** Scaled sizes for every editor control, either derived from a host's measured size or fixed. */
internal data class EditorScale(
    val chipFont: TextUnit,
    val chipHPad: Dp,
    val chipVPad: Dp,
    val stepButton: Dp,
    val stepGlyph: TextUnit,
    val valueFont: TextUnit,
    val square: Dp,
    val spacing: Dp,
)

/**
 * OBD-77: how a given host wants [EditorControls] arranged. The two real instances are
 * `GaugeEditorFace`'s cramped tile-relative one (scale-only, plus the gear indent) and
 * `GaugeEditorCard`'s roomy one (captions on, and — in landscape, where a floating card has
 * width to spare but very little height — style and threshold side by side with the chips
 * stacked into a proper option list).
 */
internal data class EditorLayout(
    val scale: EditorScale,
    val chipRowStartPadding: Dp = 0.dp,
    val twoColumn: Boolean = false,
    val stackChips: Boolean = false,
    val showCaptions: Boolean = false,
)

@Composable
private fun rememberEditorScale(dim: Dp): EditorScale =
    EditorScale(
        chipFont = scaledTextSize(dim, EDITOR_CHIP_FONT_FRACTION, EDITOR_CHIP_FONT_MIN_SP, EDITOR_CHIP_FONT_MAX_SP),
        chipHPad = scaledDp(dim, EDITOR_CHIP_H_PAD_FRACTION, EDITOR_CHIP_H_PAD_MIN, EDITOR_CHIP_H_PAD_MAX),
        chipVPad = scaledDp(dim, EDITOR_CHIP_V_PAD_FRACTION, EDITOR_CHIP_V_PAD_MIN, EDITOR_CHIP_V_PAD_MAX),
        stepButton = scaledDp(dim, EDITOR_STEP_BUTTON_FRACTION, EDITOR_STEP_BUTTON_MIN, EDITOR_STEP_BUTTON_MAX),
        stepGlyph = scaledTextSize(dim, EDITOR_STEP_GLYPH_FRACTION, EDITOR_STEP_GLYPH_MIN_SP, EDITOR_STEP_GLYPH_MAX_SP),
        valueFont = scaledTextSize(dim, EDITOR_VALUE_FONT_FRACTION, EDITOR_VALUE_FONT_MIN_SP, EDITOR_VALUE_FONT_MAX_SP),
        square = scaledDp(dim, EDITOR_SQUARE_FRACTION, EDITOR_SQUARE_MIN, EDITOR_SQUARE_MAX),
        spacing = scaledDp(dim, EDITOR_SPACING_FRACTION, EDITOR_SPACING_MIN, EDITOR_SPACING_MAX),
    )

/**
 * OBD-77: the floating card's fixed, comfortable control sizes. Deliberately NOT
 * [rememberEditorScale] fed a large dimension: the whole point of lifting the editor out of the
 * tile is that its controls stop negotiating with the gauge's cell size at all, so these are
 * hand-picked touch-comfortable constants (44dp squares, 44dp stepper targets) rather than a
 * fraction of anything. See `GaugeTextScale.kt` for the tile-relative fractions the flip host
 * still uses.
 */
internal fun roomyEditorScale(): EditorScale =
    EditorScale(
        chipFont = ROOMY_CHIP_FONT_SP.sp,
        chipHPad = ROOMY_CHIP_H_PAD_DP.dp,
        chipVPad = ROOMY_CHIP_V_PAD_DP.dp,
        stepButton = ROOMY_STEP_BUTTON_DP.dp,
        stepGlyph = ROOMY_STEP_GLYPH_SP.sp,
        valueFont = ROOMY_VALUE_FONT_SP.sp,
        square = ROOMY_SQUARE_DP.dp,
        spacing = ROOMY_SPACING_DP.dp,
    )

private const val ROOMY_CHIP_FONT_SP = 15
private const val ROOMY_CHIP_H_PAD_DP = 16
private const val ROOMY_CHIP_V_PAD_DP = 10
private const val ROOMY_STEP_BUTTON_DP = 44
private const val ROOMY_STEP_GLYPH_SP = 28
private const val ROOMY_VALUE_FONT_SP = 34
private const val ROOMY_SQUARE_DP = 40
private const val ROOMY_SPACING_DP = 10

/**
 * The editor's actual content, host-agnostic: the OBD-72 style picker always, plus — when
 * [hasThresholds] — the OBD-66 threshold menu from Taras's original sketch (two stacked squares
 * on the right, YELLOW over RED, selecting which boundary is being edited with a blue frame; the
 * selected boundary's value as a large number with a `+`/`−` vertical stepper). Every step
 * persists immediately via [onSetThreshold], folding the single edited boundary into the gauge's
 * current [thresholds] so the other boundary survives (see `ThresholdEditing.kt`).
 *
 * Scale-bounds editing (`issues/OBD-72.md`'s "ideally user-adjustable" note) is still deferred —
 * the data model/persistence/seed are wired end to end ([GaugeScale], `AppSettings.scaleOverrides`)
 * but nothing here exposes min/max/tick editing yet.
 */
@Composable
@Suppress("LongParameterList") // one param per input the two sections need, plus the host's layout spec.
internal fun EditorControls(
    id: String,
    unit: MeasurementUnit,
    thresholds: GaugeThresholds,
    hasThresholds: Boolean,
    style: GaugeRenderStyle,
    onSetThreshold: (GaugeThresholds) -> Unit,
    onSetStyle: (GaugeRenderStyle) -> Unit,
    layout: EditorLayout,
    modifier: Modifier = Modifier,
) {
    val scale = layout.scale
    if (layout.twoColumn && hasThresholds) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(scale.spacing * SECTION_GAP_FACTOR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorSection("STYLE", layout.showCaptions, scale, Modifier.weight(1f)) {
                StylePickerRow(id = id, selected = style, onSelect = onSetStyle, layout = layout)
            }
            EditorSection("THRESHOLD", layout.showCaptions, scale, Modifier.weight(1f)) {
                ThresholdEditorRow(unit, thresholds, onSetThreshold, scale, Modifier.fillMaxWidth())
            }
        }
        return
    }
    // Captioned sections need visibly more air between them than the gap INSIDE a section (which
    // is the same `spacing`), or the caption reads as belonging to the block above it.
    val sectionGap = if (layout.showCaptions) scale.spacing * SECTION_GAP_FACTOR else scale.spacing
    Column(
        modifier = modifier,
        // Horizontal-center keeps the flip host's chips off its top-left gear ⚙; vertical-center
        // sits the group in the middle of the face when it fits (Taras: gear was obstructed).
        verticalArrangement = Arrangement.spacedBy(sectionGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EditorSection("STYLE", layout.showCaptions, scale, Modifier.fillMaxWidth()) {
            StylePickerRow(id = id, selected = style, onSelect = onSetStyle, layout = layout)
        }
        if (hasThresholds) {
            EditorSection("THRESHOLD", layout.showCaptions, scale, Modifier.fillMaxWidth()) {
                ThresholdEditorRow(unit, thresholds, onSetThreshold, scale)
            }
        }
    }
}

/** One captioned block of the roomy card; in the flip host [captioned] is false and this is a no-op wrapper. */
@Composable
private fun EditorSection(
    caption: String,
    captioned: Boolean,
    scale: EditorScale,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!captioned) {
        Box(modifier = modifier) { content() }
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(scale.spacing)) {
        Text(
            text = caption,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = CAPTION_LETTER_SPACING_SP.sp,
                ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = CAPTION_ALPHA),
        )
        content()
    }
}

/**
 * OBD-72: the style-picker row — three chips, one per [GaugeRenderStyle]. OBD-77: the selected
 * chip now also takes the theme's `primary` border, not just the translucent fill, so "which
 * style is live" survives the roomy card's larger, more widely spaced chips.
 */
@Composable
private fun StylePickerRow(
    id: String,
    selected: GaugeRenderStyle,
    onSelect: (GaugeRenderStyle) -> Unit,
    layout: EditorLayout,
    modifier: Modifier = Modifier,
) {
    val scale = layout.scale
    // start=GEAR_CLEARANCE indents only this top row past the flip host's gear ⚙ (top-left), so
    // the chips clear it WITHOUT costing the whole face a top strip — the vertical space that a
    // push-down stole from the threshold row below (Taras: "cohesively fit it all together when
    // smallest"). The floating card passes 0: it has a real header instead.
    val outer =
        modifier
            .fillMaxWidth()
            .padding(start = layout.chipRowStartPadding)
            .testTag("gauge-style-picker-$id")
    if (layout.stackChips) {
        // Two-column card: a narrow half-width column, so the three styles read as an option
        // list rather than a wrapped chip cloud.
        Column(modifier = outer, verticalArrangement = Arrangement.spacedBy(scale.spacing)) {
            STYLE_OPTIONS.forEach { (optionStyle, label) ->
                StyleChip(id, optionStyle, label, selected, scale, onSelect, Modifier.fillMaxWidth())
            }
        }
        return
    }
    // FlowRow (not Row): on a narrow 1-wide flip face the three chips can't fit side-by-side;
    // FlowRow wraps them onto extra lines (full labels intact) instead of overflowing the tile.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(scale.spacing, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(scale.spacing),
        modifier = outer,
    ) {
        STYLE_OPTIONS.forEach { (optionStyle, label) ->
            StyleChip(id, optionStyle, label, selected, scale, onSelect)
        }
    }
}

/** One style option chip — shared by [StylePickerRow]'s wrapped-row and stacked-column arrangements. */
@Composable
@Suppress("LongParameterList") // id/option/label/selected/scale/onSelect/modifier — all load-bearing.
private fun StyleChip(
    id: String,
    option: GaugeRenderStyle,
    label: String,
    selected: GaugeRenderStyle,
    scale: EditorScale,
    onSelect: (GaugeRenderStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSelected = option == selected
    EditChip(
        label = label,
        selected = isSelected,
        onClick = { onSelect(option) },
        borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        fillColor = MaterialTheme.colorScheme.primary,
        fontSize = scale.chipFont,
        horizontalPadding = scale.chipHPad,
        verticalPadding = scale.chipVPad,
        modifier = modifier.testTag("gauge-style-${option.name}-$id"),
    )
}

private val STYLE_OPTIONS: List<Pair<GaugeRenderStyle, String>> =
    listOf(
        GaugeRenderStyle.DIGITAL to "Digital",
        GaugeRenderStyle.NEEDLE to "Needle",
        GaugeRenderStyle.BAR_ARC to "Bar",
    )

/** OBD-66's original threshold menu, unchanged in geometry — now one section of [EditorControls]. */
@Composable
private fun ThresholdEditorRow(
    unit: MeasurementUnit,
    thresholds: GaugeThresholds,
    onSetThreshold: (GaugeThresholds) -> Unit,
    scale: EditorScale,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(ThresholdColor.YELLOW) }
    var displayValue by remember { mutableStateOf(initialDisplayThreshold(thresholds, selected, unit, unit)) }
    // Re-seed the stepper from the persisted band whenever the selected color (or an external edit)
    // changes — our own persists round-trip back to the same value, so this never clobbers a step.
    LaunchedEffect(selected, thresholds) {
        displayValue = initialDisplayThreshold(thresholds, selected, unit, unit)
    }
    val step: (Int) -> Unit = { steps ->
        val next = stepThreshold(displayValue, steps)
        displayValue = next
        onSetThreshold(thresholdsWithDisplayValue(thresholds, selected, next, unit, unit))
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scale.spacing),
    ) {
        // Left: the vertical +/number/- stepper for the selected boundary.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StepperButton(glyph = "＋", testTag = "gauge-threshold-plus", scale = scale, onClick = { step(1) })
            Text(
                text = formatGaugeValue(displayValue, unit),
                // OBD-77: the tiles' own bold numeric face (GaugeValueTextStyle), scaled off the
                // host — the boundary you are editing should read like the gauge readout it
                // governs, not like body copy.
                style = GaugeValueTextStyle.copy(fontSize = scale.valueFont),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-threshold-value"),
            )
            StepperButton(glyph = "−", testTag = "gauge-threshold-minus", scale = scale, onClick = { step(-1) })
        }
        // Right: the two color squares, YELLOW over RED.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(scale.spacing),
        ) {
            ThresholdSquare(
                fill = GaugeAmber,
                selected = selected == ThresholdColor.YELLOW,
                testTag = "gauge-threshold-square-yellow",
                size = scale.square,
                onClick = { selected = ThresholdColor.YELLOW },
            )
            ThresholdSquare(
                fill = GaugeRed,
                selected = selected == ThresholdColor.RED,
                testTag = "gauge-threshold-square-red",
                size = scale.square,
                onClick = { selected = ThresholdColor.RED },
            )
        }
    }
}

/** One tappable +/- stepper button on the threshold editor. */
@Composable
private fun StepperButton(
    glyph: String,
    testTag: String,
    scale: EditorScale,
    onClick: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            Modifier
                .size(scale.stepButton)
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = scale.stepGlyph),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One color square on the threshold editor: [fill]-tinted, framed blue when [selected]. */
@Composable
private fun ThresholdSquare(
    fill: Color,
    selected: Boolean,
    testTag: String,
    size: Dp,
    onClick: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val shape = RoundedCornerShape(THRESHOLD_SQUARE_CORNER_DP.dp)
    Box(
        modifier =
            Modifier
                .size(size)
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .background(fill.copy(alpha = THRESHOLD_SQUARE_FILL_ALPHA), shape)
                .border(
                    width = if (selected) THRESHOLD_SELECT_BORDER_DP.dp else THRESHOLD_UNSELECT_BORDER_DP.dp,
                    color = if (selected) ThresholdSelectBlue else MaterialTheme.colorScheme.outline,
                    shape = shape,
                ).testTag(testTag),
    )
}
