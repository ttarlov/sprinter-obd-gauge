---
issue: OBD-66
round: 1
reviewers: [orchestrator + Taras UI acceptance]
verdict: approved
gate: green
reviewed-commit: eb9fc08
covers: [OBD-66]
---

Per-gauge threshold editor (gear + 3D flip), pulsing danger zone, researched seed defaults, and a
stable-ribbon swap-carousel ordering fix (folded in). Renders verified against Taras's sketch;
Taras approved the UI ("looks great... good to merge"). Functionality is covered by the gate's
unit/UI tests; the flip/pulse are device-only visuals eyeballed via Roborazzi renders.

## Fix list
- [x] ✅ **Threshold editor matches the sketch**: gear in the focused pager card's upper-left → 3D
      Y-flip (~220ms, matches the select-pop) → back face with yellow-over-red squares (blue frame on
      selected) + a large value with `＋`/`−` stepper. Verified in `gauge_threshold_editor.png`.
- [x] ✅ **Persists to the existing model**: yellow→`greenMax`, red→`redMin` (redInclusive), written to
      `thresholdOverrides` via `SettingsRepository.update{}` — the SAME map the settings screen uses, so
      both paths stay consistent. Pure logic in `ThresholdEditing.kt`, unit-tested (step/clamp, mapping,
      seed table). Live tile recolor flows through `effectiveThresholds()` — test-covered.
- [x] ✅ **Pulsing danger zone**: a live RED tile pulses its red fill/border (~0.9s reverse infinite
      transition), created only on a RED tile, gated by `LocalDangerPulseEnabled` (false in tests so
      `waitForIdle`/screenshots stay deterministic). Verified in `gauge_danger_zone.png`.
- [x] ✅ **Researched seed defaults** (loaded Revel/Sprinter): coolant 215/225, transTemp 215/240,
      oilTemp 245/260 °F (redInclusive). Confirmed the temp gauges are declared FAHRENHEIT so the raw
      numbers are correct.
- [x] ✅ **Stable-ribbon carousel ordering** (Taras-reported, folded in): `candidateGaugesFor` returns
      current+candidates in fixed `GAUGE_CATALOG` order; `SwapPager` opens at the current gauge's ribbon
      index (not page 0). Dismiss/select key off gauge **identity**, not page 0. Earlier-ribbon gauges
      are a left-swipe, later ones a right-swipe — positions stay consistent across swaps. Tested
      (`GaugeCatalogTest` stable-order + left-of-current; `GaugeSwapPickerTest` opens-focused-on-current).
- [x] ✅ **Isolation & non-regression**: `:app`/`issues` only; OBD-64 edit bar/add/resize/remove, OBD-65
      swipe pager, sparklines, and the settings-screen threshold path all intact. Gate green.
- [ ] 🖐 Taras to feel the flip/pulse/ordering live at leisure (approved the look; functionality is
      test-covered). Boost accuracy is a separate open item (no OBD MAP source on this van).

Gate: PASS (all seven) at eb9fc08. Flip/pulse motion is device-only (Robolectric can't render frames);
structure/persistence covered by tests.
