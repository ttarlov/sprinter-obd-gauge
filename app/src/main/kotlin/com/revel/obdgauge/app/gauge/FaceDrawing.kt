package com.revel.obdgauge.app.gauge

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.util.lerp
import com.revel.obdgauge.app.ui.theme.GaugeFaceInk
import com.revel.obdgauge.app.ui.theme.GaugeFaceSweat
import com.revel.obdgauge.app.ui.theme.GaugeFaceWhite
import com.revel.obdgauge.app.ui.theme.GaugeNeutral

// OBD-73 "dumb mode": the Canvas primitive behind every face — [drawFace] and the per-feature
// strokes it delegates to. Split out of FaceGauge.kt (which keeps the composables and the
// value->FaceShape mappers) so that file stays under detekt's per-file function-count limit, the
// same reasoning GaugeEditorFace.kt already uses for its own slice of GaugePicker.kt.
//
// Nothing here knows WHY the face looks the way it does: it is handed a [FaceShape] and draws it.
// Every dimension is a fraction of the DrawScope's own measured size, which is the whole reason
// these are hand-drawn at all — a platform emoji glyph would render as tofu on the Garmin's
// Android 6, and would not scale with the tile the way the needle/bar-arc styles do.

private const val FACE_RADIUS_FRACTION = 0.40f
private const val FACE_OUTLINE_ALPHA = 0.22f
private const val FACE_OUTLINE_WIDTH_FRACTION = 0.012f
private const val EYE_RADIUS_FRACTION = 0.12f
private const val EYE_OFFSET_X_FRACTION = 0.40f
private const val EYE_OFFSET_Y_FRACTION = 0.18f
private const val BUG_PUPIL_FRACTION = 0.48f
private const val BROW_Y_ABOVE_EYE_FRACTION = 0.32f
private const val BROW_LENGTH_FRACTION = 0.34f
private const val BROW_WIDTH_FRACTION = 0.075f
private const val MOUTH_WIDTH_FRACTION = 0.95f
private const val MOUTH_Y_BELOW_CENTER_FRACTION = 0.34f
private const val MOUTH_WIDTH_STROKE_FRACTION = 0.10f
private const val TEAR_RADIUS_FRACTION = 0.09f
private const val TEAR_DROP_BELOW_EYE_FRACTION = 0.30f

// OBD-73 sweat (temperature flush): the first bead appears once the face is meaningfully warm, a
// second (opposite temple) once it's hot. Bead radius grows a little with the heat.
private const val SWEAT_ONE_THRESHOLD = 0.12f
private const val SWEAT_TWO_THRESHOLD = 0.55f
private const val SWEAT_RADIUS_MIN_FRACTION = 0.08f
private const val SWEAT_RADIUS_MAX_FRACTION = 0.13f
private const val SWEAT_TEMPLE_X_FRACTION = 0.62f
private const val SWEAT_TEMPLE_Y_FRACTION = 0.52f

// One bead's silhouette, as multiples of its body radius: a point well above the body (TIP), pinched
// in at the shoulders, bulging at the belly, round at the bottom — the classic cartoon droplet.
private const val SWEAT_DROP_TIP_FRACTION = 1.7f
private const val SWEAT_DROP_SHOULDER_FRACTION = 0.3f
private const val SWEAT_DROP_BELLY_FRACTION = 0.7f

/** Draws [shape] centred in this DrawScope, with [faceColor] as the head's fill. */
internal fun DrawScope.drawFace(
    shape: FaceShape,
    faceColor: Color,
) {
    val d = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)
    val faceR = d * FACE_RADIUS_FRACTION
    val ink = GaugeFaceInk

    drawCircle(color = faceColor, radius = faceR, center = center)
    drawCircle(
        color = ink.copy(alpha = FACE_OUTLINE_ALPHA),
        radius = faceR,
        center = center,
        style = Stroke(width = d * FACE_OUTLINE_WIDTH_FRACTION),
    )

    val eyeY = center.y - faceR * EYE_OFFSET_Y_FRACTION
    val leftEye = Offset(center.x - faceR * EYE_OFFSET_X_FRACTION, eyeY)
    val rightEye = Offset(center.x + faceR * EYE_OFFSET_X_FRACTION, eyeY)
    drawEyes(shape, leftEye, rightEye, faceR, d)
    drawBrows(shape.browTilt, leftEye, rightEye, faceR)
    drawMouth(shape.mouthCurve, center, faceR)
    drawTears(shape.teary, leftEye, rightEye, faceR)
    drawSweat(shape.sweat, center, faceR)
}

/** Plain ink dots, or — when [FaceShape.bugEyes] — white sclera + pupil that grows with the value. */
private fun DrawScope.drawEyes(
    shape: FaceShape,
    leftEye: Offset,
    rightEye: Offset,
    faceR: Float,
    d: Float,
) {
    val ink = GaugeFaceInk
    val eyeR = faceR * EYE_RADIUS_FRACTION * shape.eyeScale
    if (!shape.bugEyes) {
        drawCircle(color = ink, radius = eyeR, center = leftEye)
        drawCircle(color = ink, radius = eyeR, center = rightEye)
        return
    }
    // White sclera + dark pupil = a bulging cartoon "bug eye" that reads as it grows.
    listOf(leftEye, rightEye).forEach { eye ->
        drawCircle(color = GaugeFaceWhite, radius = eyeR, center = eye)
        drawCircle(
            color = ink.copy(alpha = FACE_OUTLINE_ALPHA),
            radius = eyeR,
            center = eye,
            style = Stroke(d * FACE_OUTLINE_WIDTH_FRACTION),
        )
        drawCircle(color = ink, radius = eyeR * BUG_PUPIL_FRACTION, center = eye)
    }
}

/** The worried brows — outer end low, inner (toward centre) end raised by [browTilt]. */
private fun DrawScope.drawBrows(
    browTilt: Float,
    leftEye: Offset,
    rightEye: Offset,
    faceR: Float,
) {
    if (browTilt <= 0f) return
    val browY = leftEye.y - faceR * BROW_Y_ABOVE_EYE_FRACTION
    val half = faceR * BROW_LENGTH_FRACTION / 2f
    val tilt = faceR * browTilt
    val browWidth = faceR * BROW_WIDTH_FRACTION
    drawLine(
        GaugeFaceInk,
        Offset(leftEye.x - half, browY + tilt / 2f),
        Offset(leftEye.x + half, browY - tilt / 2f),
        browWidth,
        StrokeCap.Round,
    )
    drawLine(
        GaugeFaceInk,
        Offset(rightEye.x + half, browY + tilt / 2f),
        Offset(rightEye.x - half, browY - tilt / 2f),
        browWidth,
        StrokeCap.Round,
    )
}

/** One quadratic stroke: [mouthCurve] positive bows the middle down (smile), negative up (frown). */
private fun DrawScope.drawMouth(
    mouthCurve: Float,
    center: Offset,
    faceR: Float,
) {
    val mouthY = center.y + faceR * MOUTH_Y_BELOW_CENTER_FRACTION
    val mouthHalf = faceR * MOUTH_WIDTH_FRACTION / 2f
    val mouth =
        Path().apply {
            moveTo(center.x - mouthHalf, mouthY)
            quadraticBezierTo(center.x, mouthY + faceR * mouthCurve, center.x + mouthHalf, mouthY)
        }
    drawPath(
        mouth,
        color = GaugeFaceInk,
        style = Stroke(width = faceR * MOUTH_WIDTH_STROKE_FRACTION, cap = StrokeCap.Round),
    )
}

/** The crying face's two tears, hanging below the eyes. */
private fun DrawScope.drawTears(
    teary: Boolean,
    leftEye: Offset,
    rightEye: Offset,
    faceR: Float,
) {
    if (!teary) return
    val tearR = faceR * TEAR_RADIUS_FRACTION
    val drop = faceR * TEAR_DROP_BELOW_EYE_FRACTION
    drawCircle(color = GaugeNeutral, radius = tearR, center = Offset(leftEye.x, leftEye.y + drop))
    drawCircle(color = GaugeNeutral, radius = tearR, center = Offset(rightEye.x, rightEye.y + drop))
}

/** The continuous heat tell: one temple bead once warm, a second once hot, both growing with [sweat]. */
private fun DrawScope.drawSweat(
    sweat: Float,
    center: Offset,
    faceR: Float,
) {
    if (sweat <= SWEAT_ONE_THRESHOLD) return
    val beadR = faceR * lerp(SWEAT_RADIUS_MIN_FRACTION, SWEAT_RADIUS_MAX_FRACTION, sweat)
    val tx = faceR * SWEAT_TEMPLE_X_FRACTION
    val ty = center.y - faceR * SWEAT_TEMPLE_Y_FRACTION
    // First bead beads on the right temple; a second joins on the left once it's really hot.
    drawSweatDrop(Offset(center.x + tx, ty), beadR)
    if (sweat > SWEAT_TWO_THRESHOLD) {
        drawSweatDrop(Offset(center.x - tx, ty), beadR)
    }
}

/** A little teardrop bead (pointed top, round bottom) at [c] with body radius [r]. */
private fun DrawScope.drawSweatDrop(
    c: Offset,
    r: Float,
) {
    val tip = c.y - r * SWEAT_DROP_TIP_FRACTION
    val drop =
        Path().apply {
            moveTo(c.x, tip)
            cubicTo(
                c.x + r,
                c.y - r * SWEAT_DROP_SHOULDER_FRACTION,
                c.x + r,
                c.y + r * SWEAT_DROP_BELLY_FRACTION,
                c.x,
                c.y + r,
            )
            cubicTo(
                c.x - r,
                c.y + r * SWEAT_DROP_BELLY_FRACTION,
                c.x - r,
                c.y - r * SWEAT_DROP_SHOULDER_FRACTION,
                c.x,
                tip,
            )
            close()
        }
    drawPath(drop, color = GaugeFaceSweat)
}
