package com.revel.obdgauge.app.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import com.revel.obdgauge.app.ui.theme.GaugeFaceHot
import com.revel.obdgauge.app.ui.theme.GaugeFaceYellow
import com.revel.obdgauge.app.ui.theme.GaugeStaleDim
import androidx.compose.ui.graphics.lerp as lerpColor

// OBD-73 "dumb mode": hand-drawn faces (NOT platform emoji glyphs — the Garmin's Android 6 would
// render 😊 as tofu). Two flavours share the one drawing primitive [FaceCanvas]:
//   • temperature gauges → a MOOD face: threshold band → happy/concerned/sad/crying (FaceExpression).
//   • boost → an EXCITEMENT face: the eyes bug out + the grin grows as boost climbs (Taras's ask).
// All geometry is a fraction of the Canvas's own measured size, so a face scales with its tile like
// the needle/bar-arc do. The value→shape maths are pure (FaceExpression.kt / the mappers below);
// the strokes themselves live in FaceDrawing.kt (detekt's per-file function count).

private const val LABEL_TOP_GAP_FRACTION = 0.02f

// Mouth curvature as a fraction of the face radius: +bows the middle DOWN (a smile ∪), −bows it UP
// (a frown ∩). Concerned is a whisper of a frown; crying the deepest.
private const val MOUTH_CURVE_HAPPY = 0.36f
private const val MOUTH_CURVE_CONCERNED = -0.08f
private const val MOUTH_CURVE_SAD = -0.30f
private const val MOUTH_CURVE_CRYING = -0.44f

// Worried-brow tilt (inner ends raised) as a fraction of the face radius — grows with the mood.
private const val BROW_TILT_CONCERNED = 0.06f
private const val BROW_TILT_SAD = 0.11f
private const val BROW_TILT_CRYING = 0.13f

// Boost excitement mapping: fraction 0 (no boost) → 1 (full boost, capped at [BOOST_FACE_MAX_PSI],
// a realistic on-boost peak so the eyes are fully bugged by the time it's actually shoving).
// `internal` (not private) for BoostFaceShapeTest — these four are the mapping's contract, so the
// test asserts against them by name rather than re-typing the numbers.
internal const val BOOST_FACE_MAX_PSI = 18.0
internal const val BOOST_EYE_SCALE_MIN = 1.0f
internal const val BOOST_EYE_SCALE_MAX = 2.3f
internal const val BOOST_MOUTH_CURVE_MIN = 0.12f
internal const val BOOST_MOUTH_CURVE_MAX = 0.58f

/**
 * Every knob [FaceCanvas] needs — decoupled from WHY, so temp-mood and boost-excitement share it.
 * `internal` so `FaceDrawing.kt` can consume it and [boostFaceShape] can be unit-tested; the two
 * mappers below are still the only things that construct one.
 */
internal data class FaceShape(
    val eyeScale: Float,
    val bugEyes: Boolean,
    val mouthCurve: Float,
    val browTilt: Float,
    val teary: Boolean,
    val sweat: Float = 0f,
)

private fun FaceExpression.toShape(): FaceShape =
    when (this) {
        FaceExpression.HAPPY -> FaceShape(1f, bugEyes = false, MOUTH_CURVE_HAPPY, 0f, teary = false)
        FaceExpression.CONCERNED -> FaceShape(1f, bugEyes = false, MOUTH_CURVE_CONCERNED, BROW_TILT_CONCERNED, false)
        FaceExpression.SAD -> FaceShape(1f, bugEyes = false, MOUTH_CURVE_SAD, BROW_TILT_SAD, teary = false)
        FaceExpression.CRYING -> FaceShape(1f, bugEyes = false, MOUTH_CURVE_CRYING, BROW_TILT_CRYING, teary = true)
    }

/**
 * OBD-73: boost value → a bug-eyed, growing-grin excitement face. `value` is in psi. The cap is
 * the LOWER of [BOOST_FACE_MAX_PSI] and the gauge's own sweep ceiling, so a user-narrowed boost
 * scale still bugs the eyes fully at its own top end. `internal` for BoostFaceShapeTest.
 */
internal fun boostFaceShape(
    value: Double,
    scale: GaugeScale,
): FaceShape {
    val cap = minOf(BOOST_FACE_MAX_PSI, scale.max)
    val frac = if (cap > 0.0) (value / cap).coerceIn(0.0, 1.0).toFloat() else 0f
    return FaceShape(
        eyeScale = lerp(BOOST_EYE_SCALE_MIN, BOOST_EYE_SCALE_MAX, frac),
        bugEyes = true,
        mouthCurve = lerp(BOOST_MOUTH_CURVE_MIN, BOOST_MOUTH_CURVE_MAX, frac),
        browTilt = 0f,
        teary = false,
    )
}

/** The face, drawn to fill its box. [faceColor] is dimmed when the reading is stale. */
@Composable
private fun FaceCanvas(
    shape: FaceShape,
    faceColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dim = min(maxWidth, maxHeight)
        Canvas(modifier = Modifier.size(dim).testTag(testTag)) {
            drawFace(shape, faceColor)
        }
    }
}

/**
 * [GaugeRenderStyle.FACE] on a temperature gauge — a MOOD face (threshold band → expression) filling
 * the tile with the label beneath, no number (the whole point of "dumb mode"). A stale reading dims
 * to a neutral grey concerned look rather than asserting a mood off old data.
 */
@Composable
internal fun FaceGaugeBody(
    state: GaugeTileUiState,
    thresholds: GaugeThresholds,
    modifier: Modifier = Modifier,
) {
    if (state.isStale) {
        FaceTile(state.id, state.label, FaceExpression.CONCERNED.toShape(), GaugeStaleDim, modifier)
        return
    }
    // OBD-73: the mouth/brows/tears snap at the threshold bands (discrete expression), while the
    // flush (yellow → hot red) and the sweat beads ramp continuously with the heat — so the face
    // visibly "warms up" between mood changes rather than only at the boundaries.
    val expression = faceExpression(thresholds, state.rawValue)
    val heat = faceHeat(thresholds, state.rawValue)
    val faceColor = lerpColor(GaugeFaceYellow, GaugeFaceHot, heat)
    FaceTile(state.id, state.label, expression.toShape().copy(sweat = heat), faceColor, modifier)
}

/**
 * [GaugeRenderStyle.FACE] on boost — an EXCITEMENT face whose eyes bug out and grin widens with the
 * boost reading. Boost is NEUTRAL (no thresholds), so it never gets the mood face.
 */
@Composable
internal fun BoostFaceBody(
    state: GaugeTileUiState,
    scale: GaugeScale,
    modifier: Modifier = Modifier,
) {
    val shape = if (state.isStale) FaceExpression.CONCERNED.toShape() else boostFaceShape(state.rawValue, scale)
    val faceColor = if (state.isStale) GaugeStaleDim else GaugeFaceYellow
    FaceTile(state.id, state.label, shape, faceColor, modifier)
}

/** Shared tile shell: the face filling the tile, the gauge label beneath. */
@Composable
private fun FaceTile(
    id: String,
    label: String,
    shape: FaceShape,
    faceColor: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val dim = min(maxWidth, maxHeight)
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FaceCanvas(
                shape = shape,
                faceColor = faceColor,
                testTag = "gauge-$id-face",
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelMedium.copy(
                        fontSize = scaledTextSize(dim, LABEL_FONT_FRACTION, LABEL_FONT_MIN_SP, LABEL_FONT_MAX_SP),
                    ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = dim * LABEL_TOP_GAP_FRACTION).testTag("gauge-$id-label"),
            )
        }
    }
}
