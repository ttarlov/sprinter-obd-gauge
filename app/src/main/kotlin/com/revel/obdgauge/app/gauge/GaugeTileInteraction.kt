package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/**
 * The touch/a11y interaction every gauge tile shares — [GaugeTile] and [BoostTile]
 * (`DashboardScreen.kt`) each apply their own background/padding on top of this. Extracted into
 * its own file (not just its own function) so neither tile composable trips detekt's
 * `LongMethod`/`TooManyFunctions` — a side benefit of also de-duplicating what was, until review
 * round-1's NIT pass, two copies of the same block.
 *
 * Plain [pointerInput]/[detectTapGestures], not `clickable`/`combinedClickable`: those force a
 * semantics merge boundary (`mergeDescendants = true`), which would fold a tile's own child
 * testTags (`gauge-<id>-value`/`-stale`/`-sparkline`) into one merged node and break every
 * existing `onNodeWithTag` lookup on them — confirmed by trying it. Trade-off: no automatic
 * ripple, acceptable for a dash-mount app. The `onClick`/`onLongClick` **semantics** actions
 * added below (review round-1 NIT) don't carry that same risk — merging is a property of the
 * `clickable` *modifier* family specifically, not of exposing semantics actions on their own —
 * and are what make this long-press-to-swap interaction discoverable to TalkBack at all; without
 * them the feature was entirely invisible to accessibility services even though touch already
 * worked.
 *
 * @param onLongPress OBD-42: enters picker mode for this tile — reachable on every tile
 *   ([GaugeSlot] wires this on every id, boost included via [BoostTile]).
 * @param onTap OBD-42: fired on a plain tap. A no-op while no tile is picking; while a
 *   *different* tile is picking, this is how tapping "outside" it (onto another live tile)
 *   dismisses that picker — see [GaugeDashboard]'s scrim for the rest of "outside".
 */
@Composable
internal fun Modifier.gaugeTileInteraction(
    id: String,
    zone: ThresholdZone,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    return testTag("gauge-$id")
        .semantics {
            stateDescription = zone.name.lowercase()
            onClick(label = null) {
                currentOnTap()
                true
            }
            onLongClick(label = "Swap gauge") {
                currentOnLongPress()
                true
            }
        }.pointerInput(Unit) {
            detectTapGestures(
                onTap = { currentOnTap() },
                onLongPress = { currentOnLongPress() },
            )
        }
}
