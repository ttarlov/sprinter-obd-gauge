// Review round-1 BLOCKER B1's fix (pickerShrinkContentCounterScale) pushed this file to 11
// tightly-related functions, one over detekt's TooManyFunctions default — all of them are the
// picker's shrink-animation primitives/chrome, cohesive by design; splitting further would spread
// one feature's mechanics across more files for no readability gain.
@file:Suppress("TooManyFunctions")

package com.revel.obdgauge.app.gauge

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import com.revel.obdgauge.model.PidDefinition
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.unit.lerp as lerpDp

// OBD-42's long-press gauge-swap carousel, refined by OBD-44 into a multitasking-switcher-style
// shrink — see DashboardScreen.kt's GaugeSlot for how a tile enters/leaves this, and
// GaugeCatalog.kt for candidateGaugesFor, the candidate list this reads.
//
// OBD-44: the crossfade-based `gaugePickerContentTransition`/`GaugePickerTile` pairing this file
// used to own is gone. There is now exactly ONE composable instance per tile rendering "the
// gauge" (GaugeTile/BoostTile in DashboardScreen.kt) across the whole picker lifecycle — it is
// never torn down and remounted as a different composable when picker mode opens or closes, only
// visually transformed (see `pickerShrinkLayer` below). That single-instance-never-swapped
// property is *why* the AC's "no content pop/crossfade" and "stays live mid-shrink" both hold:
// there is nothing to pop or crossfade, and the value Text is the same composition node
// reading the same live GaugeTileUiState the whole time. This file now owns only the *chrome*
// around that shrinking gauge (the frame background, caption, and the scrollable OTHER
// candidates) plus the shared shrink-transform primitives GaugeTile/BoostTile apply to
// themselves.

private const val PICKER_SHRINK_MS = 300
private const val PICKER_SHRINK_ELEVATION_DP = 6

/**
 * The picker-mode shrink/grow [AnimationSpec], scaled by the platform's
 * `Settings.Global.ANIMATOR_DURATION_SCALE` the same way the View animation system already
 * does automatically and Compose does not (a known Compose gap — Compose's own clock ignores
 * this setting unless an app checks it itself). Compose's `Animator duration scale = 0`
 * accessibility setting is what OBD-44's AC calls "animation-scale-0 devices": with the scale at
 * 0, this resolves to [snap] — [progress] itself jumps straight from 0 to 1 (or back) in a single
 * frame, with no intermediate *animated* values.
 *
 * Review round-1 N3: this does NOT guarantee the very first frame after that jump is already
 * fully transformed. [pickerShrinkLayer] separately depends on [GaugePickerChrome]'s ghost having
 * reported its position via `onGloballyPositioned` — a real layout pass, independent of this
 * spec — so even under `snap()` there can be exactly one frame where `progress == 1f` but the
 * transform hasn't picked up a target yet and briefly renders full-size. That frame is transient
 * (resolves on the very next frame, unconditionally) and is not what the AC's "no stuck mid-scale
 * composables" is guarding against — a composable left PERMANENTLY straddling two states. See
 * [pickerShrinkLayer]'s own KDoc for that gap's full explanation.
 */
@Composable
internal fun rememberPickerShrinkAnimationSpec(): AnimationSpec<Float> {
    val context = LocalContext.current
    val durationScale =
        remember(context) {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
    return remember(durationScale) {
        if (durationScale <= 0f) {
            snap()
        } else {
            tween(durationMillis = (PICKER_SHRINK_MS * durationScale).roundToInt(), easing = FastOutSlowInEasing)
        }
    }
}

/** Convenience wrapper: `progress` 0f (normal tile) → 1f (fully in picker/mini-card state). */
@Composable
internal fun rememberPickerShrinkProgress(
    isPicking: Boolean,
    label: String,
): Float {
    val progress by animateFloatAsState(
        targetValue = if (isPicking) 1f else 0f,
        animationSpec = rememberPickerShrinkAnimationSpec(),
        label = label,
    )
    return progress
}

/**
 * The corner-radius/background/border/elevation targets GaugeTile/BoostTile interpolate between
 * the full-tile look ([progress] 0) and the mini-card-in-the-carousel look ([progress] 1) — the
 * AC's "corner radius and elevation/shadow interpolate with the scale, so it reads as the same
 * surface changing depth, not a swap." [zoneColor] is whatever background color the *full* tile
 * would use (already includes GaugeTile's per-zone classification, or BoostTile's fixed neutral)
 * — the mini-card composite blends it with [MaterialTheme.colorScheme.surface] at
 * [MINI_CARD_ZONE_ALPHA], matching [GaugeMiniCard]'s own two-layer (surface + tinted overlay)
 * background exactly, just as one interpolated color instead of two stacked draws.
 *
 * [contentPadding] is deliberately NOT interpolated (review round-1 N5): it's a real
 * `Modifier.padding`, which affects this Box's own MEASURED size — animating it toward
 * [MINI_CARD_PADDING_DP] shrank the tile's reported layout height mid-animation (portrait's
 * height is content-driven), which visibly jumped every OTHER tile below it in the scrollable
 * column before the shrink even reached its target position. Held fixed at [TILE_PADDING_DP], the
 * OWN size GaugeSlot's outer `Box` reports to its parent never changes while picking — only the
 * [pickerShrinkLayer]/[pickerShrinkContentCounterScale] graphicsLayer transforms (paint-time only,
 * layout-inert) visually move/shrink the tile within that unchanged reserved space.
 *
 * Named without a `remember` prefix (review round-1 N7): every call recomputes fresh — [progress]
 * changes every animation frame, so memoizing this would never hit its cache and would only add
 * overhead; this is a plain derived-value function, not a `remember`-backed one.
 */
internal data class PickerShrinkVisuals(
    val shape: Shape,
    val backgroundColor: Color,
    val borderColor: Color,
    val borderWidth: Dp,
    val contentPadding: Dp,
    val elevation: Dp,
    val cornerRadius: Dp,
)

/**
 * OBD-46: [fullSize]/[targetBounds] (same inputs [pickerShrinkLayer] itself takes) let this
 * compute the settled ANISOTROPIC-compensated corner shape — see [AnisotropicRoundedCornerShape]'s
 * KDoc for why a plain single-radius [RoundedCornerShape] reads squashed/near-square once drawn
 * inside [pickerShrinkLayer]'s independent scaleX/scaleY transform. Both default to `null` so
 * pre-OBD-46 call sites (if any ever call this without them) fall back to the plain isotropic
 * shape unchanged — matching the one frame per long-press where [pickerShrinkLayer] itself hasn't
 * picked up a target yet either (see its KDoc), so there is no live anisotropy to compensate for.
 */
@Composable
internal fun pickerShrinkVisuals(
    zoneColor: Color,
    progress: Float,
    fullSize: Size? = null,
    targetBounds: Rect? = null,
): PickerShrinkVisuals {
    val cornerRadius = lerpDp(TILE_CORNER_RADIUS_DP.dp, MINI_CARD_CORNER_RADIUS_DP.dp, progress)
    val miniComposite = lerpColor(MaterialTheme.colorScheme.surface, zoneColor, MINI_CARD_ZONE_ALPHA)
    val backgroundColor = lerpColor(zoneColor.copy(alpha = TILE_BACKGROUND_ALPHA), miniComposite, progress)
    val borderColor = MaterialTheme.colorScheme.primary.copy(alpha = progress)
    val borderWidth = lerpDp(0.dp, MINI_CARD_CURRENT_BORDER_WIDTH_DP.dp, progress)
    val elevation = lerpDp(0.dp, PICKER_SHRINK_ELEVATION_DP.dp, progress)
    val scale = outerScaleFactors(progress, fullSize, targetBounds)
    val shape =
        if (scale == null) {
            RoundedCornerShape(cornerRadius)
        } else {
            AnisotropicRoundedCornerShape(cornerRadius / scale.x, cornerRadius / scale.y)
        }
    return PickerShrinkVisuals(
        shape,
        backgroundColor,
        borderColor,
        borderWidth,
        TILE_PADDING_DP.dp,
        elevation,
        cornerRadius,
    )
}

/**
 * The switcher-style shrink itself: a single [androidx.compose.ui.graphics.graphicsLayer]
 * transform that scales the WHOLE tile's OUTER SURFACE (background, border, shape, shadow — the
 * silhouette) from filling [fullSize] down to sitting exactly inside [targetBounds] (the
 * current-gauge mini-card's reserved slot in the carousel, measured by [GaugePickerChrome]'s
 * ghost — see its KDoc), translating and scaling around each rect's own center so the corners
 * land exactly on [targetBounds] at `progress == 1f`. [shape]/`clip=true` and `shadowElevation`
 * ride along on the same layer so the rounding and depth read as the same surface changing
 * shape, not a separate effect layered on top. This scale is ANISOTROPIC (independent
 * `scaleX`/`scaleY`) whenever [fullSize] and [targetBounds] don't share an aspect ratio (the
 * common case: the full tile is portrait-tall, the mini-card slot is landscape-wide) — fine for
 * a plain rounded-rect silhouette, but review round-1 BLOCKER B1 found it applied to the tile's
 * TEXT content too, reading as a vertically-crushed smear rather than a legible small card. The
 * fix (see [pickerShrinkContentCounterScale]) is to apply this transform ONLY to the outer
 * `Box`'s background/border modifiers, and counter-scale the content `Column` separately so its
 * own net scale stays uniform.
 *
 * No-ops (returns [this] unchanged) until both [fullSize] and [targetBounds] are known — the
 * first composition frame after a long-press, before the chrome's ghost has reported its
 * position via `onGloballyPositioned`, briefly renders the full tile un-transformed rather than
 * guessing a position; the transform picks up on the next frame, well within the ~300 ms shrink
 * (or, under `Settings.Global.ANIMATOR_DURATION_SCALE = 0`, the very next frame after the `snap`
 * — see [rememberPickerShrinkAnimationSpec]'s KDoc, review round-1 N3).
 */
internal fun Modifier.pickerShrinkLayer(
    progress: Float,
    fullSize: Size?,
    targetBounds: Rect?,
    shape: Shape,
    elevation: Dp,
): Modifier {
    val scale = outerScaleFactors(progress, fullSize, targetBounds) ?: return this
    val size = requireNotNull(fullSize)
    val bounds = requireNotNull(targetBounds)
    return this.graphicsLayer {
        scaleX = scale.x
        scaleY = scale.y
        val fullCenter = Offset(size.width / 2f, size.height / 2f)
        val targetCenter = bounds.center
        translationX = (targetCenter.x - fullCenter.x) * progress
        translationY = (targetCenter.y - fullCenter.y) * progress
        this.shape = shape
        clip = true
        shadowElevation = elevation.toPx()
    }
}

/**
 * Review round-1 BLOCKER B1's fix: counter-scales [pickerShrinkLayer]'s anisotropic OUTER
 * transform so CONTENT rendered inside it (the label/value/sparkline `Column`) gets a UNIFORM net
 * scale instead of inheriting the outer layer's full per-axis squash. Computes the same target
 * scale factors [pickerShrinkLayer] does, takes their `min` (so the content never overflows the
 * shrinking frame on either axis — letterboxed within it instead of cropped), and divides that
 * uniform target by the outer transform's OWN per-axis scale — since nested `graphicsLayer`s
 * compose multiplicatively, the net visual scale on this content is `outer × (uniform / outer) =
 * uniform` on both axes. Applied to the content `Column` only, NOT the outer `Box` (which keeps
 * using [pickerShrinkLayer] directly for its background/border/shape, so the surface's own
 * bounds/aspect still land exactly on [targetBounds] — pinned by
 * `GaugeSwapPickerTest`'s settled-aspect-ratio assertion).
 */
internal fun Modifier.pickerShrinkContentCounterScale(
    progress: Float,
    fullSize: Size?,
    targetBounds: Rect?,
): Modifier {
    val scale = outerScaleFactors(progress, fullSize, targetBounds) ?: return this
    val uniformScale = min(scale.x, scale.y)
    return this.graphicsLayer {
        scaleX = uniformScale / scale.x
        scaleY = uniformScale / scale.y
    }
}

/** True when non-null and both dimensions are positive — a usable shrink-transform source size. */
private fun Size?.isUsable(): Boolean = this != null && width > 0f && height > 0f

/**
 * The per-axis scale factors [pickerShrinkLayer]'s outer transform is driven by at [progress] —
 * the single source [pickerShrinkContentCounterScale]'s uniform counter-scale,
 * [pickerShrinkVisuals]'s [AnisotropicRoundedCornerShape] compensation, and [pickerShrinkBorder]'s
 * ring-width compensation all read, so all four always agree on the exact same numbers instead of
 * four independent (and possibly drifting) copies of this arithmetic. `null` before both
 * [fullSize] and [targetBounds] are known — the same guard [pickerShrinkLayer] itself no-ops on
 * (see its KDoc for why that single frame is transient and harmless).
 */
private fun outerScaleFactors(
    progress: Float,
    fullSize: Size?,
    targetBounds: Rect?,
): ScaleFactors? {
    if (progress <= 0f || targetBounds == null || !fullSize.isUsable()) {
        return null
    }
    val size = requireNotNull(fullSize)
    return ScaleFactors(
        x = lerp(1f, targetBounds.width / size.width, progress),
        y = lerp(1f, targetBounds.height / size.height, progress),
    )
}

private data class ScaleFactors(
    val x: Float,
    val y: Float,
)

/**
 * A rounded-rect [Shape] with INDEPENDENT x/y corner radii — [RoundedCornerShape] only takes a
 * single radius per corner, which is exactly what breaks once the shape is drawn INSIDE
 * [pickerShrinkLayer]'s anisotropic (independent scaleX/scaleY) `graphicsLayer`: a shape's
 * `clip`/outline is defined in this layer's own PRE-scale local coordinate space, then that whole
 * space gets squashed per-axis by the layer's `scaleX`/`scaleY` — so a nominally-round corner of
 * radius `r` renders as an ELLIPSE of semi-axes `r * scaleX` (horizontal) and `r * scaleY`
 * (vertical). In portrait, [pickerShrinkLayer]'s settled scale factors are wildly anisotropic
 * (≈0.52 × ≈0.18 — the full tile is portrait-tall, the mini-card slot it lands in is
 * landscape-wide), so a single 12dp target radius comes out as a ~6dp × ~2dp sliver: visually
 * near-square, not the intended rounding (OBD-46).
 *
 * The fix: choose [radiusX]/[radiusY] so that, AFTER the outer layer's own per-axis scale is
 * applied, the rendered corner is isotropic again — i.e. `radiusX * scaleX == radiusY * scaleY ==`
 * the intended visual radius. Callers ([pickerShrinkVisuals]) do this by dividing the intended
 * visual radius by each axis's own scale factor before constructing this shape; at `progress ==
 * 0f` both scale factors are `1f`, so [radiusX]/[radiusY] collapse back to the plain full-tile
 * radius — this shape is a strict superset of the isotropic case, not a special-cased override of
 * it.
 */
private class AnisotropicRoundedCornerShape(
    private val radiusX: Dp,
    private val radiusY: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val rx = with(density) { radiusX.toPx() }.coerceIn(0f, size.width / 2f)
        val ry = with(density) { radiusY.toPx() }.coerceIn(0f, size.height / 2f)
        return Outline.Rounded(RoundRect(Rect(Offset.Zero, size), CornerRadius(rx, ry)))
    }
}

/**
 * Draws [pickerShrinkVisuals]'s border as a filled even-odd RING (outer round-rect minus an inner
 * one) rather than [androidx.compose.foundation.border]'s single uniform-width stroke. Reasoning
 * mirrors [AnisotropicRoundedCornerShape]: [androidx.compose.foundation.border] draws its stroke
 * at a fixed width in [pickerShrinkLayer]'s own PRE-scale local space, so once that layer's
 * anisotropic `scaleX`/`scaleY` are applied, the RENDERED thickness differs by direction around
 * the shape (thicker on the axis with the larger scale factor, thinner on the smaller one) instead
 * of reading as one uniform border (OBD-46).
 *
 * [insetX]/[insetY] pre-compensate exactly like [AnisotropicRoundedCornerShape]'s own radii do —
 * divide the intended visual [borderWidth] by that axis's own scale factor — and, because the
 * outer boundary's per-axis radii were built the SAME way, subtracting each axis's inset from its
 * own radius keeps the inner boundary concentric with the outer one after the scale is applied
 * too: the ring comes out a uniform visual width all the way around, corners included, not just on
 * the four straight edges.
 *
 * Falls back to a plain [androidx.compose.foundation.border] using [visuals]'s own (isotropic)
 * [PickerShrinkVisuals.shape] whenever there's no live anisotropy to compensate for —
 * [PickerShrinkVisuals.borderWidth] is `0.dp` (progress `0f`, nothing to draw) or
 * [outerScaleFactors] itself returns `null` (the same single unmeasured frame [pickerShrinkLayer]
 * no-ops on — [pickerShrinkVisuals] reads that identical `null` and already falls back to a plain
 * [RoundedCornerShape] for [PickerShrinkVisuals.shape] itself in that case).
 */
internal fun Modifier.pickerShrinkBorder(
    progress: Float,
    fullSize: Size?,
    targetBounds: Rect?,
    visuals: PickerShrinkVisuals,
): Modifier {
    if (visuals.borderWidth <= 0.dp) {
        return this
    }
    val scale = outerScaleFactors(progress, fullSize, targetBounds)
    return if (scale == null) {
        this.border(visuals.borderWidth, visuals.borderColor, visuals.shape)
    } else {
        val radiusX = visuals.cornerRadius / scale.x
        val radiusY = visuals.cornerRadius / scale.y
        val insetX = visuals.borderWidth / scale.x
        val insetY = visuals.borderWidth / scale.y
        this.drawWithContent {
            drawContent()
            val rx = radiusX.toPx().coerceIn(0f, size.width / 2f)
            val ry = radiusY.toPx().coerceIn(0f, size.height / 2f)
            val insetXPx = insetX.toPx().coerceIn(0f, size.width / 2f)
            val insetYPx = insetY.toPx().coerceIn(0f, size.height / 2f)
            val outer = RoundRect(Rect(Offset.Zero, size), CornerRadius(rx, ry))
            val inner =
                RoundRect(
                    Rect(insetXPx, insetYPx, size.width - insetXPx, size.height - insetYPx),
                    CornerRadius((rx - insetXPx).coerceAtLeast(0f), (ry - insetYPx).coerceAtLeast(0f)),
                )
            val ring =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(outer)
                    addRoundRect(inner)
                }
            drawPath(ring, color = visuals.borderColor)
        }
    }
}

/**
 * The touch handling GaugeTile/BoostTile apply to themselves: normal [gaugeTileInteraction]
 * (long-press enters picker mode, tap passes through to dismiss some *other* tile's open picker)
 * while [isPicking] is false; once this tile IS the one picking, it switches to the same
 * plain-tap-only contract [GaugeMiniCard] uses for its own `isCurrent` card (tapping the
 * shrinking/settled gauge dismisses — no long-press semantics while picking, matching
 * pre-OBD-44 behavior exactly) and carries the mini-card's own testTag identity
 * (`gauge-picker-card-$id`/`-value`) instead of the normal tile's, so the settled shrink target
 * *is* the picker's "current" card as far as tests/TalkBack are concerned — never a second,
 * separately-tagged node.
 *
 * Review round-1 N6: the picking branch also carries the same `stateDescription`/`onClick`
 * semantics [gaugeTileInteraction] gives the normal branch (minus `onLongClick`, which has no
 * meaning while picking) — losing them entirely while shrinking/settled would be a real
 * accessibility regression relative to the normal tile, even though it matches what
 * [GaugeMiniCard]'s own `isCurrent` card has always done (an OBD-42 gap this doesn't newly
 * introduce, but is cheap to close here).
 */
@Composable
internal fun Modifier.pickerAwareInteraction(
    id: String,
    zone: ThresholdZone,
    isPicking: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
): Modifier =
    if (isPicking) {
        val currentOnTap by rememberUpdatedState(onTap)
        this
            .testTag("gauge-picker-card-$id")
            .semantics {
                stateDescription = zone.name.lowercase()
                onClick(label = null) {
                    currentOnTap()
                    true
                }
            }.pointerInput(id) { detectTapGestures(onTap = { currentOnTap() }) }
    } else {
        this.gaugeTileInteraction(id, zone, onLongPress, onTap)
    }

/** `gauge-$id-value` while normal, `gauge-picker-card-$id-value` once this tile is picking. */
internal fun pickerAwareValueTag(
    id: String,
    isPicking: Boolean,
): String = if (isPicking) "gauge-picker-card-$id-value" else "gauge-$id-value"

private const val PICKER_CAPTION = "Swap gauge"
private const val PICKER_FRAME_ALPHA = 0.55f
internal const val MINI_CARD_WIDTH_DP = 96
internal const val MINI_CARD_SPACING_DP = 8
private const val MINI_CARD_EDGE_PEEK_DP = 20
internal const val MINI_CARD_CORNER_RADIUS_DP = 12
internal const val MINI_CARD_PADDING_DP = 8
internal const val MINI_CARD_ZONE_ALPHA = 0.12f
private const val MINI_CARD_BORDER_WIDTH_DP = 1
internal const val MINI_CARD_CURRENT_BORDER_WIDTH_DP = 2
private const val SELECTED_RISE_SCALE = 1.15f
private const val SWAP_RISE_ANIMATION_MS = 220L
private const val CARD_SCALE_ANIMATION_MS = 150

/**
 * OBD-42's long-press swap carousel's *chrome* — the tile's own frame background, caption, and
 * the scrollable row of OTHER candidates (i.e. [candidates] excluding [currentId] — see
 * `GaugeSlot`'s KDoc in `DashboardScreen.kt`). Matches the owner's verbatim description: "the
 * current gauge sinks into the frame of itself... scroll side to side to pop in other gauges" —
 * "sinks into" is now literal (OBD-44): the current gauge itself is rendered elsewhere
 * (GaugeTile/BoostTile in `DashboardScreen.kt`, shrinking via [pickerShrinkLayer]), and this
 * composable's only job for it is reserving — and reporting, via [onCurrentSlotPositioned] — the
 * slot it lands in. That slot is an invisible (`alpha(0f)`, `clearAndSetSemantics {}` so it never
 * shows up to `onNodeWithTag` lookups or TalkBack) real [GaugeMiniCard] rendering [currentTile],
 * so its measured size matches whatever a real current-card would need (label + value + optional
 * stale line) exactly, not a guessed constant.
 *
 * [alpha] fades this whole chrome in/out alongside the shrinking gauge's own [progress] (the
 * caller passes the same value) — entering picker mode, the frame becomes visible as the gauge
 * shrinks into it; dismissing, the frame fades back out as the gauge grows back to fill the tile.
 *
 * Tapping any OTHER mini-card fires [onSelectCandidate] immediately (so persistence starts right
 * away) and locally animates that card rising to [SELECTED_RISE_SCALE] before calling [onDismiss]
 * — unchanged from OBD-42, and deliberately NOT given the OBD-44 shrink treatment: "selection of
 * a DIFFERENT candidate keeps the existing OBD-42 rise treatment" per `issues/OBD-44.md`. See
 * `GaugeTileGrid`'s KDoc in `DashboardScreen.kt` for why a true cross-fade into the *swapped*
 * tile isn't possible from inside this composable — `key(id)` tears it down first.
 *
 * [isPicking] gates candidate taps (review round-1 N2): this composable stays mounted for as
 * long as [alpha]'s backing `progress` is above 0, which spans the WHOLE reverse "grow back to
 * full tile" animation after a dismiss — [isPicking] itself, by contrast, flips to `false`
 * immediately. Without this gate, a candidate card tapped during that ~300 ms grow-back window
 * (fading out, but still fully composed and hit-testable) would fire a swap-select on a tile
 * that's already in the process of closing.
 */
@Composable
@Suppress("LongParameterList") // one param per input the carousel/dismiss/select wiring actually needs.
internal fun GaugePickerChrome(
    currentId: String,
    currentTile: GaugeTileUiState,
    candidates: List<PidDefinition>,
    tileFor: (String) -> GaugeTileUiState?,
    alpha: Float,
    isPicking: Boolean,
    onDismiss: () -> Unit,
    onSelectCandidate: (String) -> Unit,
    onCurrentSlotPositioned: (LayoutCoordinates) -> Unit,
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
    Box(
        modifier =
            modifier
                .alpha(alpha)
                .testTag("gauge-picker-$currentId")
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = PICKER_FRAME_ALPHA),
                    RoundedCornerShape(TILE_CORNER_RADIUS_DP.dp),
                ).padding(TILE_PADDING_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = PICKER_CAPTION, style = MaterialTheme.typography.labelMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MINI_CARD_SPACING_DP.dp),
                modifier = Modifier.fillMaxWidth().padding(start = MINI_CARD_EDGE_PEEK_DP.dp),
            ) {
                // The reserved "current gauge" slot — see this function's KDoc. Invisible and
                // untagged (tagged = false): the real, live gauge (GaugeTile/BoostTile) shrinks
                // to land visually on top of it and carries the gauge-picker-card-$id identity
                // itself once settled.
                GaugeMiniCard(
                    tile = currentTile,
                    isCurrent = true,
                    onClick = {},
                    tagged = false,
                    modifier =
                        Modifier
                            .width(MINI_CARD_WIDTH_DP.dp)
                            .alpha(0f)
                            .onGloballyPositioned(onCurrentSlotPositioned),
                )
                PickerCandidateCarousel(
                    currentId = currentId,
                    candidates = candidates,
                    tileFor = tileFor,
                    selectedId = selectedId,
                    onCandidateTapped = { id ->
                        if (isPicking && selectedId == null) {
                            selectedId = id
                            onSelectCandidate(id)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The scrollable row of OTHER candidate mini-cards, split out of [GaugePickerChrome] purely to
 * keep that function under detekt's `LongMethod` threshold. [selectedId]/[onCandidateTapped]
 * implement OBD-42's unchanged "confirm, rise, dismiss" swap-select beat — see
 * [GaugePickerChrome]'s KDoc.
 */
@Composable
@Suppress("LongParameterList") // one param per input the carousel/select wiring actually needs.
private fun PickerCandidateCarousel(
    currentId: String,
    candidates: List<PidDefinition>,
    tileFor: (String) -> GaugeTileUiState?,
    selectedId: String?,
    onCandidateTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(listState),
        horizontalArrangement = Arrangement.spacedBy(MINI_CARD_SPACING_DP.dp),
        contentPadding = PaddingValues(end = MINI_CARD_EDGE_PEEK_DP.dp),
        modifier = modifier.testTag("gauge-picker-carousel-$currentId"),
    ) {
        items(candidates, key = { it.id }) { pid ->
            val tile = tileFor(pid.id) ?: GaugeTileUiState.placeholder(pid.id, pid.label)
            val scale by animateFloatAsState(
                targetValue = if (pid.id == selectedId) SELECTED_RISE_SCALE else 1f,
                animationSpec = tween(CARD_SCALE_ANIMATION_MS),
                label = "gauge-picker-card-scale-${pid.id}",
            )
            GaugeMiniCard(
                tile = tile,
                isCurrent = false,
                onClick = { onCandidateTapped(pid.id) },
                modifier = Modifier.width(MINI_CARD_WIDTH_DP.dp).scale(scale),
            )
        }
    }
}

/**
 * One sunken mini gauge-card inside [GaugePickerChrome]'s carousel: label + live value (small),
 * tinted by the gauge's own threshold zone like a full [GaugeTile] — "so the picker feels
 * alive, not like a menu" (`issues/OBD-42.md`). [isCurrent] gets a brighter, thicker border so
 * "currently showing" reads at a glance among the other candidates.
 *
 * [tagged] defaults to true (every real candidate card, including the on-screen "OTHER
 * candidates" in the carousel). [GaugePickerChrome] passes `tagged = false` for its one other use
 * of this composable — the invisible ghost that only exists to measure the current gauge's
 * landing slot (see its KDoc): the real, live [GaugeTile]/[BoostTile] shrinking on top of it is
 * what carries the `gauge-picker-card-$id`/`-value` identity once settled (via
 * `pickerAwareInteraction`/`pickerAwareValueTag`), so the ghost must NOT also claim those tags —
 * `onNodeWithTag` requires exactly one match, and testTag ordering across a modifier chain on the
 * same node is not a reliable way to suppress just one of two competing claims, hence an explicit
 * flag rather than layering `clearAndSetSemantics {}` over this function's own internal tags.
 */
@Composable
internal fun GaugeMiniCard(
    tile: GaugeTileUiState,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tagged: Boolean = true,
) {
    val zone = zoneColor(tile.zone)
    val borderColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val borderWidth = if (isCurrent) MINI_CARD_CURRENT_BORDER_WIDTH_DP else MINI_CARD_BORDER_WIDTH_DP
    val currentOnClick by rememberUpdatedState(onClick)
    Box(
        modifier =
            modifier
                .then(if (tagged) Modifier.testTag("gauge-picker-card-${tile.id}") else Modifier)
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
                modifier = if (tagged) Modifier.testTag("gauge-picker-card-${tile.id}-value") else Modifier,
            )
            val staleText = tile.staleText
            if (tile.isStale && staleText != null) {
                Text(
                    text = staleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = GaugeStaleDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (tagged) Modifier.testTag("gauge-picker-card-${tile.id}-stale") else Modifier,
                )
            }
        }
    }
}
