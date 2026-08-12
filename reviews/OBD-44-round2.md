---
issue: OBD-44
round: 2
reviewers: [orchestrator (D6 close-out of a Tier-B round-1 by rev-correctness+rev-platform Opus)]
verdict: approved
gate: green
reviewed-commit: 581b8c0
---

Round-2 verification of the ui-agent's fixes to the round-1 findings (1 BLOCKER, 3 MAJOR,
5 MINOR, 2 NIT). Round 1 was a spawned Tier-B Opus review; round 2 verified by the
orchestrator per D6 (applied retroactively to this wave's tail — the fix list was
reviewer-specified, so close-out verification, not fresh review, was the remaining work).

## Fix list

- [x] ✅ **B1 (BLOCKER, anisotropic squash)** — fixed via `pickerShrinkContentCounterScale`:
      outer surface keeps the anisotropic layer (silhouette lands exactly on the slot),
      content Column gets a nested counter-scale so its net scale is uniform
      `min(sx, sy)` (letterboxed, never cropped). Math independently verified: nested
      graphicsLayers compose multiplicatively, `outer × (uniform/outer) = uniform` per
      axis. Pinned by the new settled-aspect test (compares against a REAL GaugeMiniCard
      in the same carousel, not hand-computed pixels). `gauge_picker_mode.png`
      re-recorded after the fix, per the round-1 requirement.
- [x] ✅ **M1 (vacuous reduced-motion test)** — rewritten to drive the press manually
      (down + polled mainClock advance against the root) and assert the shrink is already
      settled the instant the picker-card identity appears. The builder's FIRST fix
      attempt was itself vacuous (longClick()'s gesture advances the clock past any real
      tween) — caught by the builder's own mutation re-run, which is the discipline
      working. Orchestrator independently re-injected mutation B in its true form (fixed
      220ms tween, NOT scale-multiplied — the naive `if(false)` variant is behaviorally
      inert because a 0×-scaled tween IS a snap) → killed by exactly this test; pristine
      suite green after cp-restore, tree clean.
- [x] ✅ **M2 (mid-shrink test at ~97.5%)** — now advances +30ms from
      mainClock.currentTime captured after the gesture, and `assertMidFlight` requires
      bounds strictly between full and settled on BOTH height (scale) and vertical center
      (translation — kills zero-translation mutation D independently of scale). Drift now
      fails loudly. KDoc corrected.
- [x] ✅ **M3 (single-surface unpinned)** — `LocalGaugeTileMountProbe` (no-op
      CompositionLocal, production never overrides) fires once per mount via
      `remember {}`; new test asserts constant mount count across long-press + dismiss.
      Builder's ledger: `key(isPicking)` mutation E now killed by this test directly.
- [x] ✅ **N1** — `isAttached` guard on both coordinates before `localPositionOf`.
- [x] ✅ **N2** — `GaugePickerChrome` takes `isPicking`; candidate taps gated on it, so
      the grow-back window can no longer fire a swap the user already dismissed.
- [x] ✅ **N5** — contentPadding no longer interpolated; tile layout size constant while
      picking (transform is paint-time only), portrait column no longer jumps.
- [x] ✅ **N6** — picking branch carries stateDescription/onClick semantics parity.
- [x] ✅ **N7** — renamed to `pickerShrinkVisuals` with rationale (per-frame inputs make
      memoization a pure loss).
- [x] ✅ **N3 (declined, rationale accepted)** — the 1-frame ghost-position gap is a
      layout-pass dependency, not an animation-timing bug; KDoc corrected to stop
      overclaiming instead. Transient by construction (resolves next frame,
      unconditionally); recorded as a known limitation in MODULE.md.
- [x] ✅ **N4 (declined, rationale accepted)** — label/stale/sparkline tags intentionally
      keep full-tile identity; no test or TalkBack behavior depends on the split, and full
      parity would drop the label tag entirely while picking (out of scope). Recorded.

**Mutation ledger (round 2):** B killed (orchestrator-verified live), D killed (builder:
M2's position-delta assertion), E killed (builder: M3 mount-probe test). Round-1 survivors:
zero.

**Gate:** PASS at 581b8c0 (assembleDebug, test 144/144, ktlint, detekt, verifyRoborazzi,
assembleDemoDebug, module-isolation). 12 original OBD-42 tests remain byte-unmodified.

Verdict: **approved** at 581b8c0. Merge target: `develop` (first feature through the D5
ad-hoc channel). Taras's on-device feel verdict on the shrink remains the acceptance gate
for promotion to main.
