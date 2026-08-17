package com.revel.obdgauge.app.gauge

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeRed
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle
import com.revel.obdgauge.app.ui.theme.ThresholdSelectBlue
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidDefinition
import kotlinx.coroutines.delay

// OBD-65 (in-tile pager): long-pressing a tile turns its content into a HorizontalPager INSIDE the
// tile's own frame — page 0 the current gauge, following pages the swap candidates — so swapping
// works at any tile size (the fix for the cramped 1×1 carousel) and the pager owns horizontal drags
// (the fix for the drag being eaten by the tile's tap detector). This file owns that pager, the
// OBD-64 edit-bar controls, and the add-palette's mini-card.
//
// Presentation (Taras's sketch): the tile's frame stays (tinted background + border); inside it a
// carousel whose centered card is ~75% of the frame with the neighbour candidates peeking at the
// edges — signalling "swipe for more". Entering pick mode the current gauge zooms OUT of its
// full-tile size into that ~75% card as the frame fades in; selecting a candidate zooms it IN to
// fill the tile before the swap persists. The old OBD-44/46/47 exact-bounds anisotropic shrink is
// NOT reproduced — a clean scale/pop reads as "zoom out to browse, pop in to select". See
// DashboardScreen.kt's GaugeSlot for how a tile enters/leaves pick mode, and GaugeCatalog.kt for
// candidateGaugesFor (the page list this reads).

internal const val MINI_CARD_WIDTH_DP = 96
internal const val MINI_CARD_SPACING_DP = 8
private const val MINI_CARD_CORNER_RADIUS_DP = 12
private const val MINI_CARD_PADDING_DP = 8
private const val MINI_CARD_ZONE_ALPHA = 0.12f
private const val MINI_CARD_BORDER_WIDTH_DP = 1
private const val SWAP_PAGE_CURRENT_BORDER_WIDTH_DP = 2

// The centered card takes this fraction of the frame's width; the remaining (1 - fraction) is split
// evenly as left/right contentPadding, which is where the neighbour cards peek. Halved = the peek
// on each side. Down to a 1×1 landscape cell this still leaves a readable card plus a visible peek.
private const val SWAP_CARD_FRACTION = 0.75f
private const val SWAP_PEEK_FRACTION = (1f - SWAP_CARD_FRACTION) / 2f
private const val SWAP_PAGE_SPACING_DP = 8
private const val SWAP_FRAME_PADDING_DP = 8
private const val SWAP_FRAME_BORDER_WIDTH_DP = 1
private const val SWAP_FRAME_ALPHA = 0.5f
private const val SWAP_POP_MS = 220
private const val SWAP_SELECT_POP_SCALE = 1.35f

// OBD-66: the gear + 3D-flip threshold editor on the focused pager card.
private const val FLIP_HALFWAY_DEG = 90f
private const val FLIP_BACK_DEG = 180f

// Perspective for the Y-axis flip — smaller = more pronounced 3D foreshortening. Density-scaled
// inside graphicsLayer so it reads the same across screen densities.
private const val FLIP_CAMERA_DISTANCE = 12f
private const val GEAR_GLYPH = "⚙"
private const val GEAR_SIZE_DP = 28
private const val GEAR_PADDING_DP = 4
private const val THRESHOLD_SQUARE_SIZE_DP = 30
private const val THRESHOLD_SQUARE_SPACING_DP = 8
private const val THRESHOLD_SQUARE_CORNER_DP = 6
private const val THRESHOLD_SQUARE_FILL_ALPHA = 0.85f
private const val THRESHOLD_SELECT_BORDER_DP = 3
private const val THRESHOLD_UNSELECT_BORDER_DP = 1
private const val THRESHOLD_STEP_BUTTON_DP = 34
private const val THRESHOLD_EDITOR_PADDING_DP = 8

/**
 * OBD-65: the in-tile swap pager. `GaugeSlot` renders THIS in place of the normal, interactive
 * `GaugeTile`/`BoostTile` while a tile is being picked, filling the tile's own bounds at whatever
 * size it is (1×1, 2×1, 2×2). It is a tile FRAME (tinted background + border — Taras's sketch)
 * wrapping a [HorizontalPager]:
 *
 * - **The pages** are the current gauge plus the swap candidates, in the stable `GAUGE_CATALOG`
 *   ribbon order ([candidateGaugesFor]) — NOT current-first.
 * - The pager **opens centered on the current gauge** (`initialPage` = its ribbon index), so gauges
 *   keep consistent spatial positions: earlier-ribbon candidates are a LEFT swipe, later ones RIGHT.
 *
 * The centered card is [SWAP_CARD_FRACTION] (~75%) of the frame width, with the neighbours peeking
 * at the edges ([SWAP_PEEK_FRACTION] each side, as horizontal `contentPadding`) to signal "swipe
 * for more". A [HorizontalPager] snaps natively and, crucially, consumes the horizontal drag — so
 * it reliably wins the swipe that the old in-cell carousel lost to the tile's own
 * `detectTapGestures`. Tapping a candidate card calls [onSelect] (persist the swap); tapping the
 * current gauge's card (identity, not page index) calls [onDismiss]. A tap OUTSIDE the tile still
 * hits `GaugeDashboard`'s scrim.
 *
 * [initiallyShowThreshold] (OBD-67): the ⚙ badge's trigger — when `true`, seeds the pager's
 * initially-focused card (the current gauge's own page) straight to its flipped threshold face,
 * so the badge opens directly onto the editor rather than requiring a second gear tap. Trigger-only:
 * every other card, and every re-focus after this initial one, still starts unflipped as before.
 *
 * ### Pop animation
 * On enter, an [Animatable] `enter` runs 0→1 once: the pager content scales from `1/SWAP_CARD_FRACTION`
 * (so the centered card starts ≈ full-tile, a continuation of the gauge that was just there) down to
 * 1 (the settled ~75% card), while the frame's background/border fade in — "zoom out to browse". On
 * selecting a candidate, that card scales up to [SWAP_SELECT_POP_SCALE] (fills the frame) over
 * [SWAP_POP_MS] before [onSelect] fires the swap and pick-mode dismisses — "pop in to select".
 */
@Composable
// LongParameterList: one param per input the pager's page list / select / dismiss wiring needs.
// LongMethod: the frame + enter/select pop animations + pager are one cohesive presentation unit;
// splitting the page lambda out would spread the pop-scale state across another function for no gain.
@Suppress("LongParameterList", "LongMethod")
internal fun SwapPager(
    slotId: String,
    pages: List<PidDefinition>,
    tileFor: (String) -> GaugeTileUiState?,
    thresholds: Map<String, GaugeThresholds>,
    onDismiss: () -> Unit,
    onSelect: (id: String) -> Unit,
    onSetThreshold: (id: String, thresholds: GaugeThresholds) -> Unit,
    initiallyShowThreshold: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Enter "zoom out" — plays once per pick (slotId is stable for the life of this pager).
    val enter = remember(slotId) { Animatable(0f) }
    LaunchedEffect(slotId) { enter.animateTo(1f, tween(SWAP_POP_MS)) }
    val contentScale = lerp(1f / SWAP_CARD_FRACTION, 1f, enter.value)
    val frameProgress = enter.value

    // Select "pop in" — the tapped candidate scales up to fill, then the swap fires + dismisses.
    var selectedId by remember(slotId) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedId) {
        selectedId?.let { id ->
            delay(SWAP_POP_MS.toLong())
            currentOnSelect(id)
        }
    }
    // During the select pop the frame chrome (bg + border) and every non-selected page fade to 0,
    // so the chosen card grows to fill the tile on a clean background with no frame/neighbour ghost
    // (the transient bug). 1 while browsing, so the settled look is unchanged.
    val selectFade by animateFloatAsState(
        targetValue = if (selectedId != null) 0f else 1f,
        animationSpec = tween(SWAP_POP_MS),
        label = "gauge-swap-frame-fade-$slotId",
    )

    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    // OBD-66: [pages] is the stable GAUGE_CATALOG ribbon (candidateGaugesFor), so open the pager
    // centered on the current gauge at its natural ribbon slot — earlier gauges are a LEFT swipe,
    // later ones a RIGHT swipe. The current gauge is therefore no longer necessarily page 0.
    val currentIndex = remember(pages, slotId) { pages.indexOfFirst { it.id == slotId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = currentIndex, pageCount = { pages.size })
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(
                        alpha = SWAP_FRAME_ALPHA * frameProgress * selectFade,
                    ),
                    shape,
                ).border(
                    SWAP_FRAME_BORDER_WIDTH_DP.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = frameProgress * selectFade),
                    shape,
                ).padding(SWAP_FRAME_PADDING_DP.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val peek = maxWidth * SWAP_PEEK_FRACTION
            HorizontalPager(
                state = pagerState,
                contentPadding = PaddingValues(horizontal = peek),
                pageSpacing = SWAP_PAGE_SPACING_DP.dp,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .testTag("gauge-swap-pager-$slotId")
                        .graphicsLayer {
                            scaleX = contentScale
                            scaleY = contentScale
                        },
            ) { page ->
                val pid = pages[page]
                val tile = tileFor(pid.id) ?: GaugeTileUiState.placeholder(pid.id, pid.label, verified = pid.verified)
                val isSelected = pid.id == selectedId
                val popScale by animateFloatAsState(
                    targetValue = if (isSelected) SWAP_SELECT_POP_SCALE else 1f,
                    animationSpec = tween(SWAP_POP_MS),
                    label = "gauge-swap-pop-${pid.id}",
                )
                // Every page but the selected one fades out during the pop (the outgoing gauge and
                // any peeking neighbour), so only the chosen card is visible as it fills the tile.
                val pageAlpha by animateFloatAsState(
                    targetValue = if (selectedId != null && !isSelected) 0f else 1f,
                    animationSpec = tween(SWAP_POP_MS),
                    label = "gauge-swap-alpha-${pid.id}",
                )
                // The gear/flip threshold editor lives only on the centered (focused) card, and only
                // for a gauge that HAS thresholds (temperature). While a select-pop is in flight the
                // gear is suppressed so the flip can't race the swap.
                val isFocused = page == pagerState.currentPage && selectedId == null
                val editable = GAUGE_CATALOG_BY_ID[pid.id]?.unit?.kind() == UnitKind.TEMPERATURE
                // Identity, not page index: the current gauge's card dismisses; any OTHER gauge's
                // card selects/swaps. OBD-66's stable ribbon means the current gauge isn't page 0.
                val isCurrentGauge = pid.id == slotId
                SwapPagerCard(
                    tile = tile,
                    isCurrent = isCurrentGauge,
                    isFocused = isFocused,
                    editable = editable,
                    unit = GAUGE_CATALOG_BY_ID[pid.id]?.unit ?: MeasurementUnit.FAHRENHEIT,
                    thresholds = thresholds[pid.id] ?: GaugeThresholds(),
                    scale = popScale,
                    alpha = pageAlpha,
                    initialFlipped = initiallyShowThreshold && isCurrentGauge && editable,
                    onClick = {
                        when {
                            isCurrentGauge -> currentOnDismiss()
                            selectedId == null -> selectedId = pid.id
                        }
                    },
                    onSetThreshold = { next -> onSetThreshold(pid.id, next) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * One gauge card inside [SwapPager] — the same threshold-tinted look as a real [GaugeTile] (label +
 * big value), filling its ~75%-of-the-frame page so it reads as "this gauge in this slot," not a
 * menu row. The current-gauge page (page 0) gets a highlighted border so "what's showing now" is
 * obvious as you swipe onto/off it. [scale] is the select "pop in" factor (1 normally); [alpha]
 * hides this card during another card's pop (1 normally). A plain [detectTapGestures] (not
 * `clickable`) keeps the per-page value testTag independently addressable; the [HorizontalPager]
 * parent handles the horizontal drag, so a tap here (no movement) is unambiguously a select/dismiss.
 */
@Composable
@Suppress("LongParameterList") // every input drives the card's look, focus/flip, or persistence.
private fun SwapPagerCard(
    tile: GaugeTileUiState,
    isCurrent: Boolean,
    isFocused: Boolean,
    editable: Boolean,
    unit: MeasurementUnit,
    thresholds: GaugeThresholds,
    scale: Float,
    alpha: Float,
    onClick: () -> Unit,
    onSetThreshold: (GaugeThresholds) -> Unit,
    initialFlipped: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    // OBD-66: tapping the gear flips THIS card over its Y axis to reveal the threshold editor.
    // Duration matches the select-pop (SWAP_POP_MS ≈ 220ms) so the flip flows with the pop feel.
    // OBD-67: initialFlipped seeds this straight to the back face — the ⚙ badge's trigger.
    var flipped by remember(tile.id) { mutableStateOf(initialFlipped) }
    // A card that scrolls out of focus (or a select-pop starting) flips back to its gauge face, so
    // exactly one editor face — and one set of `gauge-threshold-*` testTags — is ever live.
    LaunchedEffect(isFocused, editable) {
        if (!isFocused || !editable) flipped = false
    }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) FLIP_BACK_DEG else 0f,
        animationSpec = tween(SWAP_POP_MS),
        label = "gauge-threshold-flip-${tile.id}",
    )
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }.testTag("gauge-swap-page-${tile.id}"),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = rotation
                        cameraDistance = FLIP_CAMERA_DISTANCE * density
                    },
        ) {
            if (rotation <= FLIP_HALFWAY_DEG) {
                SwapPagerFace(
                    tile = tile,
                    isCurrent = isCurrent,
                    onClick = { if (!flipped) currentOnClick() },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Counter-rotate the back so its content isn't mirrored once past 90°.
                Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = FLIP_BACK_DEG }) {
                    ThresholdEditorFace(
                        unit = unit,
                        thresholds = thresholds,
                        onSetThreshold = onSetThreshold,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (isFocused && editable) {
            GearButton(
                onClick = { flipped = !flipped },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .testTag("gauge-threshold-gear-${tile.id}"),
            )
        }
    }
}

/** The gauge-face front of a [SwapPagerCard]: the same threshold-tinted label + big value as a real tile. */
@Composable
private fun SwapPagerFace(
    tile: GaugeTileUiState,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val zone = zoneColor(tile.zone)
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    Box(
        modifier =
            modifier
                .pointerInput(tile.id) { detectTapGestures(onTap = { currentOnClick() }) }
                .background(zone.copy(alpha = TILE_BACKGROUND_ALPHA), shape)
                .then(
                    if (isCurrent) {
                        Modifier.border(SWAP_PAGE_CURRENT_BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.primary, shape)
                    } else {
                        Modifier
                    },
                ).padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = tile.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-swap-page-${tile.id}-label"),
            )
            Text(
                text = tile.valueText,
                style = GaugeValueTextStyle,
                color = if (tile.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-swap-page-${tile.id}-value"),
            )
        }
    }
}

/** The small gear affordance in a focused card's upper-left corner — a discrete tap, never a swipe. */
@Composable
private fun GearButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            modifier
                .padding(GEAR_PADDING_DP.dp)
                .size(GEAR_SIZE_DP.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = GEAR_GLYPH, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * OBD-66: the back of the flipped card — the threshold menu from Taras's sketch. Two stacked
 * squares on the right (YELLOW over RED) select which boundary is being edited (a blue frame marks
 * the selection); the selected boundary's value shows as a large number with a `+`/`−` vertical
 * stepper. Every step persists immediately via [onSetThreshold], folding the single edited boundary
 * into the gauge's current [thresholds] so the other boundary survives (see `ThresholdEditing.kt`).
 *
 * Values are shown and stepped in the gauge's declared [unit] (FAHRENHEIT for the temperature
 * gauges this editor is offered on), which is also the wire unit the thresholds are stored in — so
 * no conversion is needed and the number matches the tile's own °F readout.
 */
@Composable
private fun ThresholdEditorFace(
    unit: MeasurementUnit,
    thresholds: GaugeThresholds,
    onSetThreshold: (GaugeThresholds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
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
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .border(SWAP_FRAME_BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.primary, shape)
                .padding(THRESHOLD_EDITOR_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: the vertical +/number/- stepper for the selected boundary.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StepperButton(glyph = "＋", testTag = "gauge-threshold-plus", onClick = { step(1) })
            Text(
                text = formatGaugeValue(displayValue, unit),
                // titleLarge (not the tile's huge GaugeValueTextStyle): the editor's card is only
                // ~75% of a tile, which can be a small 1×1 cell — a big number ellipsizes to "2…"
                // there. This still reads as the focal "large number" beside the small squares.
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-threshold-value"),
            )
            StepperButton(glyph = "−", testTag = "gauge-threshold-minus", onClick = { step(-1) })
        }
        // Right: the two color squares, YELLOW over RED.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(THRESHOLD_SQUARE_SPACING_DP.dp),
        ) {
            ThresholdSquare(
                fill = GaugeAmber,
                selected = selected == ThresholdColor.YELLOW,
                testTag = "gauge-threshold-square-yellow",
                onClick = { selected = ThresholdColor.YELLOW },
            )
            ThresholdSquare(
                fill = GaugeRed,
                selected = selected == ThresholdColor.RED,
                testTag = "gauge-threshold-square-red",
                onClick = { selected = ThresholdColor.RED },
            )
        }
    }
}

/** One tappable +/- stepper button on the threshold editor's back face. */
@Composable
private fun StepperButton(
    glyph: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            Modifier
                .size(THRESHOLD_STEP_BUTTON_DP.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** One color square on the threshold editor: [fill]-tinted, framed blue when [selected]. */
@Composable
private fun ThresholdSquare(
    fill: Color,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val shape = RoundedCornerShape(THRESHOLD_SQUARE_CORNER_DP.dp)
    Box(
        modifier =
            Modifier
                .size(THRESHOLD_SQUARE_SIZE_DP.dp)
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .background(fill.copy(alpha = THRESHOLD_SQUARE_FILL_ALPHA), shape)
                .border(
                    width = if (selected) THRESHOLD_SELECT_BORDER_DP.dp else THRESHOLD_UNSELECT_BORDER_DP.dp,
                    color = if (selected) ThresholdSelectBlue else MaterialTheme.colorScheme.outline,
                    shape = shape,
                ).testTag(testTag),
    )
}

/** Size presets the picker offers, each `(colSpan × rowSpan)` with a compact label. */
internal val SIZE_PRESETS: List<Triple<String, Int, Int>> =
    listOf(
        Triple("1×1", 1, 1),
        Triple("2×1", 2, 1),
        Triple("1×2", 1, 2),
        Triple("2×2", 2, 2),
    )

private const val EDIT_CHIP_CORNER_RADIUS_DP = 8
private const val EDIT_CHIP_BORDER_WIDTH_DP = 1
private const val EDIT_CHIP_H_PADDING_DP = 10
private const val EDIT_CHIP_V_PADDING_DP = 6
private const val EDIT_CHIP_SELECTED_ALPHA = 0.22f

/**
 * OBD-64: the row of resize chips (1×1 / 2×1 / 1×2 / 2×2), a Remove control, and — when [canAdd] —
 * an "＋ Add" control for the tile being picked. Rendered by `GaugeDashboard` (`DashboardScreen.kt`)
 * as a compact bar over the dashboard while a tile is in pick mode: these are BUTTONS (tapped, not
 * swiped), so they stay in a bar rather than joining the in-tile swap pager. Each chip calls
 * [onResize] with its span; the chip matching the tile's current ([currentColSpan]×[currentRowSpan])
 * is highlighted. [onAdd] opens the add palette; the control is hidden when [canAdd] is false (every
 * catalog gauge already placed). Plain [detectTapGestures] taps (not `clickable`) so no
 * semantics-merge boundary folds the per-chip testTags — the same discipline the rest of this file
 * follows.
 */
@Composable
@Suppress("LongParameterList") // one param per span/callback the chips + remove/add controls need.
internal fun PickerEditControls(
    currentId: String,
    currentColSpan: Int,
    currentRowSpan: Int,
    canAdd: Boolean,
    onResize: (colSpan: Int, rowSpan: Int) -> Unit,
    onRemove: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MINI_CARD_SPACING_DP.dp),
        modifier = modifier.testTag("gauge-edit-controls-$currentId"),
    ) {
        if (canAdd) {
            EditChip(
                label = "＋ Add",
                selected = false,
                onClick = onAdd,
                borderColor = MaterialTheme.colorScheme.primary,
                fillColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("gauge-edit-add"),
            )
        }
        SIZE_PRESETS.forEach { (label, colSpan, rowSpan) ->
            EditChip(
                label = label,
                selected = colSpan == currentColSpan && rowSpan == currentRowSpan,
                onClick = { onResize(colSpan, rowSpan) },
                borderColor = MaterialTheme.colorScheme.outline,
                fillColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("gauge-resize-${colSpan}x$rowSpan-$currentId"),
            )
        }
        EditChip(
            label = "✕",
            selected = false,
            onClick = onRemove,
            borderColor = MaterialTheme.colorScheme.error,
            fillColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("gauge-remove-$currentId"),
        )
    }
}

/** One tappable chip in [PickerEditControls] — a bordered, optionally filled rounded label. */
@Composable
@Suppress("LongParameterList") // label/selected/onClick/colors/modifier are all load-bearing.
private fun EditChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    borderColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val shape = RoundedCornerShape(EDIT_CHIP_CORNER_RADIUS_DP.dp)
    val fill = if (selected) fillColor.copy(alpha = EDIT_CHIP_SELECTED_ALPHA) else Color.Transparent
    Box(
        modifier =
            modifier
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .background(fill, shape)
                .border(width = EDIT_CHIP_BORDER_WIDTH_DP.dp, color = borderColor, shape = shape)
                .padding(horizontal = EDIT_CHIP_H_PADDING_DP.dp, vertical = EDIT_CHIP_V_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * A small live gauge-card: label + value, tinted by the gauge's own threshold zone — "so it feels
 * alive, not like a menu" (`issues/OBD-42.md`). OBD-64's add-palette (`DashboardScreen.kt`) uses it
 * for each addable gauge; the caller supplies its own outer `testTag`. Fixed [MINI_CARD_WIDTH_DP]
 * wide, content-driven height.
 */
@Composable
internal fun GaugeMiniCard(
    tile: GaugeTileUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = zoneColor(tile.zone)
    val currentOnClick by rememberUpdatedState(onClick)
    val shape = RoundedCornerShape(MINI_CARD_CORNER_RADIUS_DP.dp)
    Box(
        modifier =
            modifier
                // Plain pointerInput, not clickable — see GaugeTile's KDoc in DashboardScreen.kt
                // for why: clickable's semantics merge boundary would fold this card's own
                // -value testTag into its parent, breaking independent lookups.
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .background(MaterialTheme.colorScheme.surface, shape)
                .background(zone.copy(alpha = MINI_CARD_ZONE_ALPHA), shape)
                .border(MINI_CARD_BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(MINI_CARD_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = tile.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tile.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
