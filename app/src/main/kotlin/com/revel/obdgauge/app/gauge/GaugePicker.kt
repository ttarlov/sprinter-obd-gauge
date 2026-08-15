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
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.app.ui.theme.GaugeValueTextStyle
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

/**
 * OBD-65: the in-tile swap pager. `GaugeSlot` renders THIS in place of the normal, interactive
 * `GaugeTile`/`BoostTile` while a tile is being picked, filling the tile's own bounds at whatever
 * size it is (1×1, 2×1, 2×2). It is a tile FRAME (tinted background + border — Taras's sketch)
 * wrapping a [HorizontalPager]:
 *
 * - **Page 0** is the current gauge ([pages]'s first entry, which `candidateGaugesFor` puts there),
 *   so you start on what's in the slot.
 * - **Following pages** are the swap candidates.
 *
 * The centered card is [SWAP_CARD_FRACTION] (~75%) of the frame width, with the neighbours peeking
 * at the edges ([SWAP_PEEK_FRACTION] each side, as horizontal `contentPadding`) to signal "swipe
 * for more". A [HorizontalPager] snaps natively and, crucially, consumes the horizontal drag — so
 * it reliably wins the swipe that the old in-cell carousel lost to the tile's own
 * `detectTapGestures`. Tapping a candidate card calls [onSelect] (persist the swap); tapping page 0
 * (the current gauge) calls [onDismiss]. A tap OUTSIDE the tile still hits `GaugeDashboard`'s scrim.
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
    onDismiss: () -> Unit,
    onSelect: (id: String) -> Unit,
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
    val pagerState = rememberPagerState(pageCount = { pages.size })
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
                SwapPagerCard(
                    tile = tile,
                    isCurrent = page == 0,
                    scale = popScale,
                    alpha = pageAlpha,
                    onClick = {
                        when {
                            page == 0 -> currentOnDismiss()
                            selectedId == null -> selectedId = pid.id
                        }
                    },
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
@Suppress("LongParameterList") // tile/isCurrent/scale/alpha/onClick/modifier are all load-bearing.
private fun SwapPagerCard(
    tile: GaugeTileUiState,
    isCurrent: Boolean,
    scale: Float,
    alpha: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val zone = zoneColor(tile.zone)
    val shape = RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp)
    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }.testTag("gauge-swap-page-${tile.id}")
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
