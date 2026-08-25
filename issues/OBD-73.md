---
id: OBD-73
title: "Dumb mode" — hand-drawn face gauges (temp mood + boost bug-eyes)
module: app
owner: ui-agent
sprint: grid-feature
status: merged
type: feature
hardware-verify: true
blocked-by: []
branch: feat/73-dumb-mode
---

## What shipped (Taras, on-Pixel, 2026-08-25)

"Dumb mode" as a **per-gauge render style** (`GaugeRenderStyle.FACE`), the 4th chip in the OBD-72/77
style picker — NOT the originally-sketched global settings toggle. A gauge set to FACE shows a
**hand-drawn face** (no number) that reacts to the reading. Two flavours:

- **Temperature gauges → a MOOD face.** The gauge's own threshold bands drive the expression:
  - GREEN, comfortably cool → **happy** 🙂
  - GREEN but nearing the yellow line → **concerned** 😟
  - AMBER (caution) → **sad** 🙁
  - RED (danger) → **crying** 😭 (tears under the eyes)
  On top of the discrete expression, the face **flushes yellow → hot-red and sweats** *continuously*
  with a `faceHeat` 0→1 ramp (Taras's friend's idea): the flush + sweat beads scale smoothly with
  temperature (one bead when warm, a second when hot), so a gauge visibly heats up between mood
  changes rather than only at the boundaries.
- **Boost → an EXCITEMENT face.** Boost is NEUTRAL (no thresholds), so instead of a mood it gets
  **bug-eyes**: white sclera + pupils that grow (1.0×→2.3×) and a grin that widens as boost climbs
  toward a realistic on-boost peak (~18 psi). Stays yellow (excitement, not heat).

### Why hand-drawn, not emoji
The Garmin Overlander is **Android 6 (API 23)** — a platform emoji glyph (😊) renders as a **tofu
box** there. Every face is drawn with Compose `Canvas`, all geometry a fraction of the tile's own
measured size, so it renders identically on the Garmin AND scales with the tile like the
needle/bar-arc styles do.

## Design / where it lives

- `GaugeRenderStyle.FACE` (enum) — persists through the existing `GaugeRenderCodec` unchanged
  (`valueOf` + runCatching already drops an unknown token → DIGITAL, so it's backward/forward safe).
- `FaceExpression.kt` — **pure, Compose-free**: `faceExpression(thresholds, value)` (band → mood) and
  `faceHeat(thresholds, value)` (continuous flush/sweat ramp). Unit-testable as plain JUnit.
- `FaceGauge.kt` — the Canvas drawing + `FaceGaugeBody` (temp mood) / `BoostFaceBody` (bug-eye),
  sharing one `FaceCanvas`/`drawFace` primitive fed an explicit `FaceShape` (eyeScale, bugEyes,
  mouthCurve, browTilt, teary, sweat) so temp-mood and boost-excitement decouple cleanly.
- Dispatch: `GaugeTile` FACE → `FaceGaugeBody`; `BoostTile` FACE → `BoostFaceBody`.
- Picker: FACE chip offered only when it means something — `showFace = hasThresholds || id == BOOST`.
  rpm/speed never see it.
- Colours: `GaugeFaceYellow`/`GaugeFaceInk`/`GaugeFaceWhite`/`GaugeFaceHot`/`GaugeFaceSweat`.
- **Threshold coloring stays sacred**: the tile's own zone tint/pulse (above the `when(style)`
  dispatch) is untouched, so a FACE tile still tints/pulses green/amber/red around the face; the face
  colour itself is independent (mood/heat), never fighting it.

## Testing

- Pure JUnit (`app/src/test/.../gauge/`): `faceExpression` band boundaries (happy/concerned/sad/crying
  incl. the concern-approach band width = amber-band width), `faceHeat` endpoints/clamp (0 at concern
  start, 1 at danger, degenerate thresholds), and the boost eye-scale/mouth mapping (idle vs peak,
  cap at BOOST_FACE_MAX_PSI).
- Roborazzi (`app/src/testDemo/.../GaugeRenderStyleScreenshotTest.kt`): the temp face at cool/warm/
  hot/danger (flush + sweat + tears progression) and the boost face at low/high boost (eye bug-out).
- Device (🖐 Taras — the real gate): faces render on the Pixel; flush + sweat ramp with temp; boost
  eyes grow. `hardware-verify: true`.

## Hardware checklist

Device-verified by Taras on the **Pixel (demo-dev)**, 2026-08-25. **PASS.**

- [x] Temp gauge → FACE shows the mood (Coolant concerned + sweat bead at ~208°, Oil happy at 210°),
  and flushes orange + sweats as the (demo) temp climbs. **Taras: "haha that looks amazing!"**
- [x] Boost → FACE shows bug-eyes that grow with boost. **Taras: friend's red/sweat idea added, "all
  looks good."**
- [ ] Garmin (API 23) render of the Canvas faces — to confirm on the next Garmin build (no tofu; the
  whole reason they're Canvas-drawn). Not gating this merge per Taras.

## Out of scope

- A global "everything goes dumb" toggle (this is per-gauge, set all temps to FACE for the same effect).
- Faces on rpm/speed (only temp mood + boost excitement have a meaningful mapping today).
- Animated transitions between moods (the flush/eye-scale already move continuously with the value).
