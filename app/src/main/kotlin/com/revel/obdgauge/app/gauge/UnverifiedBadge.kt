package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.ui.theme.GaugeUnverifiedBadge
import com.revel.obdgauge.app.ui.theme.GaugeUnverifiedBadgeContent

// B9 MINOR (round-1 review): the visible glyph stays small/subtle (22dp — "subtle but
// unambiguous" per the OBD-27 brief), but the CLICKABLE area is widened to 48dp, Android's own
// minimum recommended touch target — a Box's `clickable` has no built-in minimum-size behavior
// the way a Material `IconButton` does, so the pre-round-1 badge was tap-target non-compliant.
private const val BADGE_TOUCH_TARGET_DP = 48
private const val BADGE_VISUAL_SIZE_DP = 22
private const val BADGE_MARGIN_DP = 6
private const val BADGE_GLYPH = "?"
private const val BADGE_A11Y_LABEL = "Unverified reading — tap for raw response"

/**
 * OBD-27's truth marker: a small, subtle-but-unambiguous badge shown on a gauge tile whose
 * backing [com.revel.obdgauge.model.PidDefinition] is `verified == false` — a hypothesis number,
 * not one confirmed against real hardware (see `docs/hardware/session-2026-08-12.md`: oil/trans
 * temp ship unverified). Tapping it opens [RawResponseDialog].
 *
 * ### UX decision: its own tap target, not the tile's existing tap/long-press
 * `GaugeTileInteraction.kt`/`GaugePicker.kt` already give every tile a plain tap (dismiss-picker
 * affordance) and a long-press (OBD-42's swap picker) — both load-bearing, both reviewed. Rather
 * than overload either (which the brief explicitly leaves to this issue's judgment: "long-press
 * menu or info affordance"), this badge is its own small, independently-clickable info
 * affordance layered on TOP of (a sibling of, in [GaugeSlot]) the tile rather than nested inside
 * its `pointerInput`/`clickable` subtree — sibling Composables in a `Box` get fully independent
 * gesture detection with no risk of fighting `pickerAwareInteraction`'s `detectTapGestures`, so
 * this can't regress OBD-42/44/47's recently-settled shrink/grow/picker interactions. It also
 * only renders while the tile is fully settled (`GaugeSlot` gates this on `progress == 0f &&
 * !isGrowingIn`) so it never overlaps the picker chrome or a mid-shrink/grow tile.
 *
 * ### Round-1 review fixes (B9/B10 MINORs)
 * The outer `Box` is the full [BADGE_TOUCH_TARGET_DP] tap target and carries every semantic
 * (testTag, `contentDescription`, [Role.Button], `clickable`) with `mergeDescendants = true` —
 * without that, TalkBack was reading the bare `"?"` [Text] child as its own separate node instead
 * of announcing the badge's actual [BADGE_A11Y_LABEL]. The visible violet circle is a plain inner
 * `Box` at [BADGE_VISUAL_SIZE_DP], centered, carrying no semantics of its own (the parent already
 * owns all of them). B12 (recorded, not fixed this round): widening the tap target to 48dp
 * necessarily widens the corner area that can intercept a "tap outside to dismiss the picker"
 * gesture aimed at a *different*, settled, unverified tile — accepted as the right trade because
 * a11y touch-target compliance is a harder requirement than that edge case, and tapping the badge
 * by "accident" still surfaces useful information (the raw viewer) rather than a dead tap; the
 * picker itself stays open and dismissible by the next tap or the back gesture.
 */
@Composable
internal fun UnverifiedBadge(
    id: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(BADGE_TOUCH_TARGET_DP.dp)
                .testTag("gauge-$id-unverified-badge")
                .semantics(mergeDescendants = true) {
                    contentDescription = BADGE_A11Y_LABEL
                    role = Role.Button
                }.clickable(onClickLabel = BADGE_A11Y_LABEL, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(BADGE_VISUAL_SIZE_DP.dp).background(GaugeUnverifiedBadge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = BADGE_GLYPH,
                color = GaugeUnverifiedBadgeContent,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * OBD-27's raw-response viewer: what was sent, what came back, and when — see
 * [RawFrameUiState]'s KDoc for exactly what "raw response" means at this layer (and what it
 * deliberately doesn't claim to show). Content-only [AlertDialog]; dismissible by [onDismiss]
 * (close button, scrim tap, or back — all standard [AlertDialog] behavior, nothing custom).
 */
@Composable
internal fun RawResponseDialog(
    state: RawFrameUiState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("raw-viewer-${state.id}"),
        title = { Text("${state.label} — raw response") },
        text = {
            Column {
                Text(
                    text = "Request: ${state.requestSummary}",
                    modifier = Modifier.testTag("raw-viewer-${state.id}-request"),
                )
                Text(
                    text = "Value: ${state.valueText}",
                    modifier = Modifier.testTag("raw-viewer-${state.id}-value"),
                )
                Text(
                    text = state.capturedText ?: "No reading received yet",
                    modifier = Modifier.testTag("raw-viewer-${state.id}-captured"),
                )
                if (!state.verified) {
                    Text(
                        text = "Unverified: this PID has not been confirmed against real hardware.",
                        modifier = Modifier.testTag("raw-viewer-${state.id}-unverified-note"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("raw-viewer-${state.id}-close")) {
                Text("Close")
            }
        },
    )
}

/**
 * OBD-27: [GaugeSlot]'s single call site for the badge + viewer pair — extracted into its own
 * function (and, deliberately, this file rather than `DashboardScreen.kt`) because `GaugeSlot`
 * was already at detekt's LongMethod/CyclomaticComplexMethod thresholds before this issue; same
 * "own file for its own function" call `GaugeTileInteraction.kt` made for the same reason.
 *
 * Owns the "should the badge even show" decision itself ([tile]'s `verified` flag AND [settled],
 * `GaugeSlot`'s `progEff == 0f` renamed for a caller with no picker-animation context of its
 * own) — so the call site in `GaugeSlot` is one unconditional expression, not an `if`, keeping
 * that already-large function's branching down. A SIBLING of the tile (not nested inside it) —
 * see [UnverifiedBadge]'s KDoc for why, and why [settled] matters.
 *
 * [modifier] is expected to be `Modifier.matchParentSize()` (the same trick `GaugePickerChrome`
 * uses in `GaugeSlot`) — computed at the CALL SITE, where `BoxScope` is in scope, and passed in
 * as a plain parameter, exactly how `GaugeSlot` already hands `GaugePickerChrome` its own
 * `Modifier.matchParentSize()`; `matchParentSize` is a `BoxScope` extension, so it can't be
 * constructed inside this function's own body. This (rather than relying on `GaugeSlot`'s outer
 * `propagateMinConstraints = true` `Box` directly) matters because that flag makes every DIRECT
 * child inherit that Box's own (usually tile-filling) MIN constraints, which would silently blow
 * a plain `Modifier.size(22.dp)` badge up to full-tile size — hit-testing it over the whole tile
 * and swallowing taps meant for the tile underneath (caught by `GaugeSwapPickerTest`'s "tapping
 * a different live tile" dismiss case during development). `matchParentSize` sizes to the tile's
 * ALREADY-measured size without propagating a tight min into what it wraps, so `align`/`size`
 * inside behave normally.
 */
@Composable
internal fun UnverifiedBadgeOverlay(
    tile: GaugeTileUiState,
    settled: Boolean,
    rawFrame: RawFrameUiState?,
    modifier: Modifier = Modifier,
) {
    if (tile.verified || !settled) return
    var showRawViewer by remember(tile.id) { mutableStateOf(false) }
    Box(modifier = modifier) {
        UnverifiedBadge(
            id = tile.id,
            onClick = { showRawViewer = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(BADGE_MARGIN_DP.dp),
        )
    }
    if (showRawViewer) {
        rawFrame?.let { RawResponseDialog(state = it, onDismiss = { showRawViewer = false }) }
    }
}
