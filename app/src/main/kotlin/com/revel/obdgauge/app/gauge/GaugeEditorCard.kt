package com.revel.obdgauge.app.gauge

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.revel.obdgauge.model.MeasurementUnit
import androidx.compose.ui.unit.min as dpMin

// OBD-77 (Taras, on-Pixel): the gauge editor used to be the BACK FACE of the in-tile flip card,
// so it was permanently the size of whatever tile it belonged to — on a 1×1 the style chips +
// threshold stepper + colour squares simply have no comfortable arrangement, and OBD-72's
// tile-relative shrink + scroll backstop only made "cramped" survivable, never cohesive.
//
// The ⚙ rearrange badge now opens THIS instead: a dashboard-level floating card that grows out of
// the tile's own on-screen rect, over a dimmed scrim, at a fixed comfortable size, and collapses
// back into that rect on Done / back / tap-outside. The spatial "this is THAT gauge" connection
// survives (it comes out of, and returns to, the tile) while the editor's size stops being a
// function of the gauge's cell. The ⇄ swap badge is untouched — it still opens the in-tile
// `SwapPager` carousel, whose focused card keeps its own gear/flip editor (`GaugeEditorFace`).
//
// ## Visual language
// Deliberately of-a-piece with the tiles rather than a Material dialog: the same dark
// `surface` ground the dashboard uses, lifted by a whisper of the theme's ice-blue `primary`
// (the mode's own chrome colour — see `RearrangeMode.kt`'s "one colour language" note) as both a
// tint and a hairline border, a slightly larger corner radius than a tile (a bigger surface earns
// a bigger radius), and a real elevation shadow so it reads as floating ABOVE the grid rather
// than replacing it. Inside, dimmed letter-spaced small-caps section captions — the instrument
// panel idiom — separate STYLE from THRESHOLD; the boundary number itself is set in the tiles'
// own bold `GaugeValueTextStyle`, so the thing you are editing looks like the readout it governs.

private const val EDITOR_SCRIM_ALPHA = 0.68f

// Matches SWAP_POP_MS (the in-tile swap pop) so every "this tile is doing something" motion on
// this dashboard runs at the same tempo.
private const val EDITOR_GROW_MS = 220

private const val EDITOR_CARD_MAX_WIDTH_DP = 340
private const val EDITOR_CARD_MAX_WIDTH_WIDE_DP = 520
private const val EDITOR_CARD_MARGIN_DP = 20
private const val EDITOR_CARD_CORNER_DP = 20
private const val EDITOR_CARD_PADDING_DP = 18
private const val EDITOR_CARD_ELEVATION_DP = 24
private const val EDITOR_CARD_TINT_ALPHA = 0.07f
private const val EDITOR_CARD_BORDER_ALPHA = 0.35f
private const val EDITOR_CARD_BORDER_DP = 1
private const val EDITOR_DIVIDER_ALPHA = 0.18f
private const val EDITOR_DIVIDER_HEIGHT_DP = 1
private const val EDITOR_HEADER_GAP_DP = 12

/**
 * The `graphicsLayer` values that map the editor card's resting rect onto the tile it grew out
 * of. Pure data so [editorGrowTransform] stays unit-testable without a Compose harness — the same
 * platform-free discipline `GridMetrics` follows for the grid's own pixel math.
 */
internal data class EditorGrowTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
)

private val IDENTITY_GROW = EditorGrowTransform(1f, 1f, 0f, 0f)

/**
 * The grow-from-the-tile interpolation, at [progress] `0f` (fully collapsed onto [start]) to `1f`
 * (the card at its resting [rest] bounds). Both rects are in the SAME coordinate space (root), so
 * any constant offset between the tile's host and the overlay's host cancels out and never has to
 * be reasoned about.
 *
 * Assumes `TransformOrigin.Center`: at `p`, the rendered rect is `rest` scaled about its own
 * centre and translated by the centre delta, which at `p = 0` is exactly [start] and at `p = 1` is
 * exactly [rest]. A degenerate [rest] (not measured yet) or [start] (a tile whose bounds were
 * never captured) yields the identity — the card then simply fades in where it rests rather than
 * collapsing to a point at the screen's origin.
 */
internal fun editorGrowTransform(
    start: Rect,
    rest: Rect,
    progress: Float,
): EditorGrowTransform {
    val degenerate =
        rest.width <= 0f || rest.height <= 0f || start.width <= 0f || start.height <= 0f
    if (degenerate) return IDENTITY_GROW
    val p = progress.coerceIn(0f, 1f)
    return EditorGrowTransform(
        scaleX = lerp(start.width / rest.width, 1f, p),
        scaleY = lerp(start.height / rest.height, 1f, p),
        translationX = lerp(start.center.x - rest.center.x, 0f, p),
        translationY = lerp(start.center.y - rest.center.y, 0f, p),
    )
}

/**
 * OBD-77's floating gauge editor: a dimmed full-screen scrim with the editor card centred over it,
 * animating out of [startBounds] (the tile's own root-space rect, captured by `GaugeSlot` when the
 * ⚙ badge was tapped) on open and back into it on close.
 *
 * [expanded] is the host's "should this be open" flag rather than the presence of the composable
 * itself: `GaugeDashboard` keeps this composed through the collapse and drops it only when
 * [onCollapsed] fires, which is the only way an exit animation can play at all. [onDismiss] is
 * every user-driven close path (scrim tap, Done, back) and just flips that flag.
 *
 * The card's resting rect is measured on an untransformed parent node — the `graphicsLayer` lives
 * on the child — so `boundsInRoot()` can never feed its own transform back into itself.
 */
@Composable
// LongParameterList: one param per input the card's content, motion, and dismissal need.
// LongMethod: the scrim, the open/close animation, and the measured resting bounds the grow
// transform is built from are one cohesive presentation unit — the same call `SwapPager`'s own
// KDoc makes for its enter/select pops.
@Suppress("LongParameterList", "LongMethod")
internal fun GaugeEditorOverlay(
    id: String,
    label: String,
    startBounds: Rect,
    unit: MeasurementUnit,
    thresholds: GaugeThresholds,
    hasThresholds: Boolean,
    style: GaugeRenderStyle,
    expanded: Boolean,
    onSetThreshold: (GaugeThresholds) -> Unit,
    onSetStyle: (GaugeRenderStyle) -> Unit,
    onDismiss: () -> Unit,
    onCollapsed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnCollapsed by rememberUpdatedState(onCollapsed)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        progress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(EDITOR_GROW_MS, easing = FastOutSlowInEasing),
        )
        if (!expanded) currentOnCollapsed()
    }
    var restBounds by remember { mutableStateOf(Rect.Zero) }
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = EDITOR_SCRIM_ALPHA * progress.value))
                // Tap-outside dismiss. The card below is a later sibling, so it is hit-tested
                // first and its own no-op tap detector keeps a tap on the card's chrome from
                // falling through to here.
                .pointerInput(Unit) { detectTapGestures(onTap = { currentOnDismiss() }) }
                .testTag("gauge-editor-scrim"),
        contentAlignment = Alignment.Center,
    ) {
        // Landscape (the dash-mount primary) gives a floating card plenty of width and almost no
        // height, so it gets a wider card laid out in two columns; portrait keeps the single
        // stacked column. See EditorControls.
        val twoColumn = maxWidth > maxHeight && hasThresholds
        val margin = EDITOR_CARD_MARGIN_DP.dp
        // Only the two-column arrangement earns the extra width; a style-only card (rpm, boost,
        // speed) is three chips and a header, and stretching it to 520dp would just be a wide
        // band of empty surface.
        val cardWidth =
            dpMin(
                maxWidth - margin * 2,
                if (twoColumn) EDITOR_CARD_MAX_WIDTH_WIDE_DP.dp else EDITOR_CARD_MAX_WIDTH_DP.dp,
            )
        val cardMaxHeight = maxHeight - margin * 2
        Box(
            modifier =
                Modifier
                    .width(cardWidth)
                    .onGloballyPositioned { restBounds = it.boundsInRoot() },
        ) {
            val grow = editorGrowTransform(startBounds, restBounds, progress.value)
            EditorCard(
                id = id,
                label = label,
                unit = unit,
                thresholds = thresholds,
                hasThresholds = hasThresholds,
                style = style,
                twoColumn = twoColumn,
                onSetThreshold = onSetThreshold,
                onSetStyle = onSetStyle,
                onDone = { currentOnDismiss() },
                modifier =
                    Modifier
                        .graphicsLayer {
                            transformOrigin = TransformOrigin.Center
                            scaleX = grow.scaleX
                            scaleY = grow.scaleY
                            translationX = grow.translationX
                            translationY = grow.translationY
                            alpha = progress.value
                        }.heightIn(max = cardMaxHeight),
            )
        }
    }
}

/** The floating card itself: header row, hairline divider, then the shared [EditorControls]. */
@Composable
@Suppress("LongParameterList") // gauge identity + the two persistence callbacks + the layout flag.
private fun EditorCard(
    id: String,
    label: String,
    unit: MeasurementUnit,
    thresholds: GaugeThresholds,
    hasThresholds: Boolean,
    style: GaugeRenderStyle,
    twoColumn: Boolean,
    onSetThreshold: (GaugeThresholds) -> Unit,
    onSetStyle: (GaugeRenderStyle) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(EDITOR_CARD_CORNER_DP.dp)
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier =
            modifier
                .shadow(EDITOR_CARD_ELEVATION_DP.dp, shape)
                .background(MaterialTheme.colorScheme.surface, shape)
                .background(primary.copy(alpha = EDITOR_CARD_TINT_ALPHA), shape)
                .border(EDITOR_CARD_BORDER_DP.dp, primary.copy(alpha = EDITOR_CARD_BORDER_ALPHA), shape)
                // Swallows taps that land on the card's own chrome so they never reach the
                // dismissing scrim underneath.
                .pointerInput(Unit) { detectTapGestures {} }
                .padding(EDITOR_CARD_PADDING_DP.dp)
                .testTag("gauge-editor-card-$id"),
        verticalArrangement = Arrangement.spacedBy(EDITOR_HEADER_GAP_DP.dp),
    ) {
        EditorHeader(label = label, onDone = onDone)
        EditorDivider(color = primary)
        EditorControls(
            id = id,
            unit = unit,
            thresholds = thresholds,
            hasThresholds = hasThresholds,
            style = style,
            onSetThreshold = onSetThreshold,
            onSetStyle = onSetStyle,
            layout =
                EditorLayout(
                    scale = roomyEditorScale(),
                    twoColumn = twoColumn,
                    stackChips = twoColumn,
                    showCaptions = true,
                ),
            // weight(fill = false): the card wraps its content, but if a short landscape screen
            // can't give it everything, the controls take the remaining height and scroll rather
            // than the card overflowing off-screen (the OBD-72 backstop, kept).
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
        )
    }
}

/** Gauge name on the left, the explicit Done exit on the right — the same idiom as rearrange mode's own Done. */
@Composable
private fun EditorHeader(
    label: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("gauge-editor-title"),
        )
        TextButton(onClick = onDone, modifier = Modifier.testTag("gauge-editor-done")) {
            Text(text = "Done", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun EditorDivider(color: Color) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(EDITOR_DIVIDER_HEIGHT_DP.dp)
                .background(color.copy(alpha = EDITOR_DIVIDER_ALPHA)),
    )
}
