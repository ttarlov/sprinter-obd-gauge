---
id: OBD-46
title: Picker current-card corner rounding squashed by outer scale + slow shrink slightly
module: app
owner: ui-agent
sprint: adhoc
track: small
status: in-review
type: feature
hardware-verify: false
blocked-by: []
branch: ui/46-picker-corner-and-timing
---

## Bug (Taras, on-device 2026-08-12)
In picker mode the two mini-cards don't match: the candidate card (real `GaugeMiniCard`)
has proper rounded corners; the current card — the shrunken live tile — shows visibly
squarer/sharper corners. Both must have the same rounding.

## Root cause (orchestrator diagnosis — verify, then fix)
`pickerShrinkLayer` applies an ANISOTROPIC scale (independent scaleX/scaleY) to the tile's
outer surface. The interpolated `RoundedCornerShape` from `pickerShrinkVisuals` is drawn
BEFORE that scale, so the corner radius gets multiplied by the per-axis scale factors
(≈0.52 × ≈0.18 in portrait) — a nominally-matching radius renders as a small squashed
ellipse, reading as near-square. OBD-44's B1 counter-scale fixed the CONTENT; the
silhouette's corners were left distorted.

## Fix requirements
1. **Corner parity:** the settled current card's visible rounding must match
   `GaugeMiniCard`'s. Because the outer scale is anisotropic, a plain `RoundedCornerShape`
   cannot compensate (single radius per corner). Use a shape with per-axis corner radii —
   e.g. a custom `Shape` building a `RoundRect` with `CornerRadius(rx, ry)` where
   `rx = target / outerScaleX`, `ry = target / outerScaleY` (interpolate from the full-tile
   radius at progress 0 to the compensated mini radius at 1). Border stroke width has the
   same distortion — compensate or accept if visually negligible (state which in the PR
   report; the settled border must not look thicker on one axis).
2. **Timing:** slow the shrink slightly — `PICKER_SHRINK_MS` 220 → 300, easing unchanged.
   The reduced-motion `snap()` branch and its tests must be untouched; if any test
   hard-codes 220, parameterize against the constant, don't re-tune assertions.

## Acceptance criteria
- [ ] Settled picker frame: current card and candidate card have visually identical corner
      rounding (Roborazzi `gauge_picker_mode.png` re-recorded and eyeballed — the corner
      arcs match; this reference is the pin)
- [ ] Mid-animation corners never look LESS rounded than the full tile's (no square-corner
      flash) — eyeball from the shape math, no new test required
- [ ] `PICKER_SHRINK_MS = 300`; all existing OBD-44 tests green without weakening
      (mid-flight and snap tests read the constant, not a literal)
- [ ] Full `tools/gate.sh` PASS; 12 original OBD-42 tests byte-unmodified

## Self-test plan
Existing GaugeSwapPickerTest suite as regression net; Roborazzi re-record for the corner
fix only after visual parity confirmed.

## Out of scope
Easing/spring changes (separate feel round if Taras wants), carousel physics, elevation.

## Process (D6 small track)
Builder agent in worktree; orchestrator reviews (targeted verification, no spawned
reviewer). Merge target: develop (MERGE_TARGET_BRANCH=develop), dev APK on merge.
