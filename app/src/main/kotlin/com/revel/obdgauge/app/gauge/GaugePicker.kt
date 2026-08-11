package com.revel.obdgauge.app.gauge

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.model.PidDefinition
import kotlinx.coroutines.delay

// OBD-42's long-press gauge-swap carousel — see DashboardScreen.kt's GaugeSlot for how a tile
// enters/leaves this, and GaugeCatalog.kt for candidateGaugesFor, the candidate list this reads.

private const val PICKER_TRANSITION_MS = 220
private const val PICKER_SINK_SCALE = 0.85f

/**
 * [androidx.compose.animation.AnimatedContent]'s `transitionSpec` for [GaugeSlot]'s normal-tile
 * ⇄ picker-mode swap: a fade+scale toward [PICKER_SINK_SCALE], read together as the gauge
 * sinking into its own frame going in, and rising back out coming back — the owner's literal
 * description (`issues/OBD-42.md`) of entering/leaving picker mode.
 */
internal fun gaugePickerContentTransition(): ContentTransform =
    (
        fadeIn(animationSpec = tween(PICKER_TRANSITION_MS)) +
            scaleIn(initialScale = PICKER_SINK_SCALE, animationSpec = tween(PICKER_TRANSITION_MS))
    ).togetherWith(
        fadeOut(animationSpec = tween(PICKER_TRANSITION_MS)) +
            scaleOut(targetScale = PICKER_SINK_SCALE, animationSpec = tween(PICKER_TRANSITION_MS)),
    )

private const val PICKER_CAPTION = "Swap gauge"
private const val PICKER_FRAME_ALPHA = 0.55f
private const val MINI_CARD_WIDTH_DP = 96
private const val MINI_CARD_SPACING_DP = 8
private const val MINI_CARD_EDGE_PEEK_DP = 20
private const val MINI_CARD_CORNER_RADIUS_DP = 12
private const val MINI_CARD_PADDING_DP = 8
private const val MINI_CARD_ZONE_ALPHA = 0.12f
private const val MINI_CARD_BORDER_WIDTH_DP = 1
private const val MINI_CARD_CURRENT_BORDER_WIDTH_DP = 2
private const val SELECTED_RISE_SCALE = 1.15f
private const val SWAP_RISE_ANIMATION_MS = 220L
private const val CARD_SCALE_ANIMATION_MS = 150

/**
 * OBD-42's long-press swap carousel: the tile's own frame, containing a horizontal, snapping
 * carousel of sunken mini gauge-cards — [currentId] first (its own mini-card doubles as a
 * dismiss affordance, see below), then [candidates] (already excludes [currentId] and whatever's
 * visible on another tile — see `candidateGaugesFor` in `GaugeCatalog.kt`). Matches the owner's
 * verbatim description: "the current gauge sinks into the frame of itself... scroll side to
 * side to pop in other gauges."
 *
 * Tapping the *current* mini-card dismisses — one of the three no-op dismiss paths, alongside
 * the back gesture and tapping outside the tile (both wired in [GaugeDashboard]). Tapping any
 * other mini-card fires [onSelectCandidate] immediately (so persistence starts right away) and
 * locally animates that card rising to [SELECTED_RISE_SCALE] before calling [onDismiss] — a
 * stand-in for "the chosen card rising/expanding to fill the frame" (`issues/OBD-42.md`'s stated
 * ideal). A true cross-fade into the *swapped* tile isn't possible from inside this composable:
 * the swap changes which id occupies this dashboard position, and `GaugeTileGrid`'s `key(id)`
 * (see its KDoc) tears this exact instance down the moment that happens — there is no live
 * composable spanning "before" and "after" to animate between. This "confirm, rise, dismiss"
 * beat is the closest same-instance approximation available; in practice the real tile appears
 * right behind it once the `gaugeOrder` write round-trips, typically well under
 * [SWAP_RISE_ANIMATION_MS] (known limitation if that round trip is unusually slow — see
 * `app/MODULE.md`).
 */
@Composable
@Suppress("LongParameterList") // one param per input the carousel/dismiss/select wiring actually needs.
internal fun GaugePickerTile(
    currentId: String,
    candidates: List<PidDefinition>,
    tileFor: (String) -> GaugeTileUiState?,
    onDismiss: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keyed on currentId so a stray recomposition never carries a stale "mid-swap" flag into a
    // freshly-opened picker for a different tile (each tile's picker is its own composable
    // instance anyway, per key(id), but this also protects the case where currentId's *own*
    // identity is reused across a dismiss-then-reopen without a full remount).
    var selectedId by remember(currentId) { mutableStateOf<String?>(null) }
    LaunchedEffect(selectedId) {
        if (selectedId != null) {
            delay(SWAP_RISE_ANIMATION_MS)
            onDismiss()
        }
    }
    val listState = rememberLazyListState()
    Box(
        modifier =
            modifier
                .testTag("gauge-picker-$currentId")
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = PICKER_FRAME_ALPHA),
                    RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp),
                ).padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = PICKER_CAPTION, style = MaterialTheme.typography.labelMedium)
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState),
                horizontalArrangement = Arrangement.spacedBy(MINI_CARD_SPACING_DP.dp),
                contentPadding = PaddingValues(horizontal = MINI_CARD_EDGE_PEEK_DP.dp),
                modifier = Modifier.fillMaxWidth().testTag("gauge-picker-carousel-$currentId"),
            ) {
                items(candidates, key = { it.id }) { pid ->
                    val isCurrent = pid.id == currentId
                    val tile = tileFor(pid.id) ?: GaugeTileUiState.placeholder(pid.id, pid.label)
                    val scale by animateFloatAsState(
                        targetValue = if (pid.id == selectedId) SELECTED_RISE_SCALE else 1f,
                        animationSpec = tween(CARD_SCALE_ANIMATION_MS),
                        label = "gauge-picker-card-scale-${pid.id}",
                    )
                    GaugeMiniCard(
                        tile = tile,
                        isCurrent = isCurrent,
                        onClick = {
                            if (selectedId == null) {
                                if (isCurrent) {
                                    onDismiss()
                                } else {
                                    selectedId = pid.id
                                    onSelectCandidate(pid.id)
                                }
                            }
                        },
                        modifier = Modifier.width(MINI_CARD_WIDTH_DP.dp).scale(scale),
                    )
                }
            }
        }
    }
}

/**
 * One sunken mini gauge-card inside [GaugePickerTile]'s carousel: label + live value (small),
 * tinted by the gauge's own threshold zone like a full [GaugeTile] — "so the picker feels
 * alive, not like a menu" (`issues/OBD-42.md`). [isCurrent] gets a brighter, thicker border so
 * "currently showing" reads at a glance among the other candidates.
 */
@Composable
private fun GaugeMiniCard(
    tile: GaugeTileUiState,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = zoneColor(tile.zone)
    val borderColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val borderWidth = if (isCurrent) MINI_CARD_CURRENT_BORDER_WIDTH_DP else MINI_CARD_BORDER_WIDTH_DP
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            modifier
                .testTag("gauge-picker-card-${tile.id}")
                // Plain pointerInput, not clickable — see GaugeTile's KDoc in DashboardScreen.kt
                // for why: clickable's semantics merge boundary would fold this card's own
                // -value testTag into its parent, breaking independent lookups.
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnClick() }) }
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(MINI_CARD_CORNER_RADIUS_DP.dp))
                .background(
                    zone.copy(alpha = MINI_CARD_ZONE_ALPHA),
                    RoundedCornerShape(MINI_CARD_CORNER_RADIUS_DP.dp),
                ).border(borderWidth.dp, borderColor, RoundedCornerShape(MINI_CARD_CORNER_RADIUS_DP.dp))
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
            // NIT: honor isStale (dim + a "last seen" marker) like GaugeTile already does — a
            // mini-card shouldn't assert a number more confidently than the real dashboard tile
            // would for the same reading.
            Text(
                text = tile.valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tile.isStale) GaugeStaleDim else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("gauge-picker-card-${tile.id}-value"),
            )
            val staleText = tile.staleText
            if (tile.isStale && staleText != null) {
                Text(
                    text = staleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = GaugeStaleDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("gauge-picker-card-${tile.id}-stale"),
                )
            }
        }
    }
}
