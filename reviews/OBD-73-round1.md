---
issue: OBD-73
round: 1
reviewer: rev-platform
verdict: approved
gate: green
reviewed-commit: c36a42f
covers: [OBD-73]
hardware-verify: true
---

# OBD-73 — dumb-mode face gauges — round 1

**Verdict: approved.** Zero blockers, zero majors. A cleanly-fenced new render style: all the
value→shape maths are pure and now well-tested, the drawing is Canvas-only (the whole point — no
platform-emoji tofu on the Garmin's Android 6), and it composes with the existing style/threshold
system without touching any of it. Gate GREEN (I re-ran `tools/gate.sh` → PASS on all 7). Device-
approved by Taras on the Pixel.

## What I verified (correctness-first)

1. **Pure mood mapping** (`FaceExpression.kt`, `FaceExpressionTest`). Traced by hand:
   - `faceExpression` classifies through the existing `GaugeThresholds.classify`, then splits GREEN
     into HAPPY vs CONCERNED at `greenMax − (redMin − greenMax)` — i.e. the "concerned" approach band
     is exactly the amber band's own width below the yellow line, so tightly-spaced thresholds fret
     sooner and loose ones later, with no magic constant. Tests pin all four moods on the REAL seeded
     thresholds AND synthetic ones, plus NEUTRAL→HAPPY and the yellow-only (no red) case (never frets
     early).
   - `faceHeat` ramps 0 at the concern-start → 1 at the danger line, `coerceIn`-clamped, and steps
     safely on a zero-width/inverted ramp or a missing boundary (→0). Endpoints, midpoint, clamp, and
     both degenerate cases are tested. This is the continuous flush+sweat driver, decoupled from the
     discrete expression — so the face reddens smoothly while the mouth/brows snap at boundaries,
     exactly as intended.
2. **Boost excitement mapping** (`boostFaceShape`, `BoostFaceShapeTest`). `eyeScale`/`mouthCurve`
   lerp from rest→max over `value / min(BOOST_FACE_MAX_PSI, scale.max)`, clamped `[0,1]`. Tests pin:
   rest at 0 boost, barely-off at idle, exact midpoint at half the cap, fully-bugged + capped at/above
   the peak, vacuum clamps to rest, **monotonic** growth, the gauge's own narrower ceiling wins, a
   degenerate scale falls back to rest, and it's always a bug-eyed non-crying level-browed face. The
   `cap = min(BOOST_FACE_MAX_PSI, scale.max)` guard means a user-narrowed boost scale still bugs the
   eyes fully at its own top — nice touch, and tested.
3. **Only offered where it means something.** The picker gates FACE on `hasThresholds || id ==
   PidIds.BOOST`, so rpm/speed never get a face that would be stuck HAPPY/NEUTRAL forever. Dispatch is
   symmetric: `GaugeTile` FACE→`FaceGaugeBody` (mood), `BoostTile` FACE→`BoostFaceBody` (bug-eye). A
   FACE somehow set on a neutral non-boost gauge degrades to a permanent HAPPY (no crash) — acceptable
   and unreachable via the UI.
4. **Persistence is free + safe.** `GaugeRenderStyle.FACE` rides the existing `GaugeRenderCodec`
   untouched: `valueOf` + `runCatching` already drops an unknown token → DIGITAL, so an older build
   reading a `FACE` value silently falls back rather than crashing. No codec change, no migration.
5. **Threshold coloring stays sacred.** The tile's zone tint/pulse sits above the `when(style)`
   dispatch and is untouched, so a FACE tile still tints/pulses green/amber/red around the face; the
   face's own colour (mood flush / boost yellow) is independent and never fights it. Confirmed the
   dispatch edit is purely additive.
6. **Canvas-only, tile-relative.** Every face dimension is a fraction of the Canvas's measured
   `minDimension` (`FaceDrawing.kt`), so faces scale with the tile like needle/bar-arc and carry no
   font/emoji dependency — the Garmin-API-23 tofu risk is designed out, not worked around.
7. **Stale handling.** A stale reading dims to a neutral grey concerned face rather than asserting a
   mood (or a bug-eye) off old data — both bodies handle it.

## Minor (non-blocking)

- **M1 — Garmin render not yet device-confirmed.** The faces are Canvas-drawn *specifically* so
  API 23 can't tofu them, and that's sound in principle, but it hasn't been eyeballed on the
  Overlander yet. Non-gating per Taras; will confirm on the next Garmin build (noted on the issue's
  hardware checklist as the one unchecked item).
- **M2 — full crying/red state is bench-only-forceable.** On the demo it's reachable only by driving
  the value into the red band (or lowering thresholds); the Roborazzi `gauge_face_danger` ref pins its
  appearance so a regression would still be caught. No action.

## Fix list

_None._ ✅ No blocking or major findings. Approving. The post-build additions (unit tests for the
mood/heat/boost mappings, six Roborazzi refs across cool→warm→hot→danger + boost low/high, the
`FaceDrawing.kt` split for detekt's per-file limit, and widening the boost mapping to `internal` for
its test) are all gate-green.

## Tests

- Pure JUnit: `FaceExpressionTest` (6 mood/heat cases + boundaries + degenerates), `BoostFaceShapeTest`
  (9 cases incl. monotonicity + cap precedence + degenerate scale).
- Roborazzi: `gauge_face_{cool,warm,hot,danger}_landscape`, `gauge_face_hot_portrait`,
  `gauge_face_boost_low_landscape` — the flush/sweat/tears progression and the boost bug-out, plus the
  four editor references now carrying the Face chip. `verifyRoborazzi` green.

## Hardware checklist

Device gate is Taras's (`hardware-verify: true`). On the Pixel (demo-dev), 2026-08-25:

- [x] Temp FACE shows the mood + flushes orange and sweats as the (demo) temp climbs (Coolant
  concerned + sweat bead at ~208°, Oil happy at 210°). **Taras: "haha that looks amazing!"**
- [x] Boost FACE bug-eyes grow with boost; friend's red/sweat idea added. **Taras: "all looks good."**
- [ ] Garmin (API 23) Canvas-face render — next Garmin build (M1). Not gating this merge per Taras.
