---
issue: OBD-63
round: 1
reviewers: [orchestrator small-track (D6) + real-device smoke]
verdict: approved
gate: green
reviewed-commit: 7ebc0ae
covers: [OBD-63]
---

Phase 2 of the resizable grid — the spanning renderer. Orchestrator adversarial review + a genuine
on-device smoke test on the Pixel (not just Robolectric).

## Fix list
- [x] ✅ **Isolated & additive.** `:app`/`issues` only. `GaugeSlot` call is byte-identical (relocated
      into `GaugeGrid`'s slot lambda, `Modifier.fillMaxSize()`); layout resolves as
      `gridLayout ?: GridMigration.fromGaugeOrder(gaugeOrder, columns)` repacked per orientation.
- [x] ✅ **Metrics correct & pure.** `GridMetrics` (cell size w/ floored height, span rects with
      absorbed gutters, content height for overflow) is Compose-free and unit-tested (11 tests).
- [x] ✅ **Picker/sparklines/grow preserved.** Each slot is a direct, un-wrapped child of one custom
      `Layout`, so `onGloballyPositioned` root-space bounds (OBD-42/44/47) are unchanged. Confirmed by
      the passing picker suites AND a real-device long-press that opened the swap carousel inside the grid.
- [x] ✅ **Real bug caught by the builder:** an unconditional `verticalScroll` swallowed inter-tile gap
      taps, breaking the picker's dismiss-scrim. Fixed to scroll only when rows genuinely overflow — so
      the common fitting cases add no pointer-intercepting scroll. Sound.
- [x] ✅ **On-device smoke (Pixel, demo build):** landscape 4-in-a-row (visually equivalent to the
      pre-grid dashboard), portrait 2×2, live sparklines + threshold colors, no clip/crash, swap picker
      works. Screenshots captured.
- [x] ✅ **Roborazzi:** `dashboard_landscape` byte-identical (default unchanged); `dashboard_portrait`
      intentionally now a 2×2; three new scenarios (2×1 wide, six-tile side-by-side, 2×2) recorded and
      eyeballed correct.
- [ ] 🖐 Phases 3+ (add/remove, resize, drag) still to come; this phase renders a persisted/migrated
      layout but has no UI yet to mutate it (default migration only, as scoped).

Gate: PASS (all seven). Metrics 11 tests + existing suites green.
