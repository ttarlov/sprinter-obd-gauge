---
issue: OBD-46
round: 1
reviewers: [orchestrator (D6 small track)]
verdict: approved
gate: green
reviewed-commit: 324363e
---

D6 small-track review of `ui/46-picker-corner-and-timing` (branch from develop be735ac).
Orchestrator diagnosis confirmed by the builder: the interpolated corner radius was drawn
in pickerShrinkLayer's pre-scale space, so the anisotropic scaleX/scaleY squashed it into
a near-square ellipse — the silhouette-level sibling of OBD-44's B1 content smear.

## Fix list

- [x] ✅ Corner parity: `AnisotropicRoundedCornerShape` with per-axis radii
      (target/scaleX, target/scaleY) so the post-scale corner is isotropic at EVERY
      progress value; collapses to the plain isotropic shape at progress 0 and in the
      single unmeasured-ghost frame (null scale → RoundedCornerShape fallback, matching
      pickerShrinkLayer's own no-op guard). Verified visually by the orchestrator: 3x
      crops of old vs new `gauge_picker_mode.png` — old current-card corners near-square,
      new corners match the candidate GaugeMiniCard's arc.
- [x] ✅ Border distortion (builder-caught, beyond brief, endorsed): Modifier.border's
      uniform pre-scale stroke rendered ≈1.04dp × ≈0.36dp (2.9:1) at the settled portrait
      scale — replaced with `pickerShrinkBorder`, an even-odd ring (outer RoundRect minus
      concentric inner, both per-axis-compensated) giving uniform visual width including
      around corners. Fallback to plain border when no anisotropy is live. Verified in the
      new reference: clean even ring.
- [x] ✅ Single source of truth: `outerScaleFactors()` now feeds layer, counter-scale,
      shape, and border — eliminates four drifting copies of the same arithmetic.
      Structural improvement beyond the brief; reduces future-regression surface.
- [x] ✅ Timing: PICKER_SHRINK_MS 220 → 300, easing + snap() branch untouched. Test file
      diff is comment-only (3 KDoc lines); the 12 OBD-42 tests byte-unmodified
      (orchestrator-diffed), 4 OBD-44 tests read the constant and stay green unweakened.
- [x] ✅ 16/16 GaugeSwapPickerTest green; full gate.sh PASS on the branch; Roborazzi
      reference re-recorded AFTER parity was verified, then re-verified stable.

No blocking findings. NIT (recorded): `pickerShrinkBorder` draws via drawWithContent on
top of content — same z-order as the Modifier.border it replaces; fine today, revisit only
if content ever needs to overlap the border edge.

Verdict: **approved** at 324363e. Merge target: develop (D5 channel).
