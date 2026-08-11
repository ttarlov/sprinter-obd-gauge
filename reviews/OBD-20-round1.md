---
issue: OBD-20
round: 1
issues-covered: [OBD-20, OBD-21]
reviewers: [rev-correctness+rev-platform combined (Tier B)]
verdict: approved
gate: green
reviewed-commit: 334fbae
---

Consolidated record of 4 review rounds on `ui/20-21-sparklines-settings` (single Tier-B
reviewer context throughout; delta-only rounds 2-4).

**Round 1** (at 987e42d): changes-requested — 0 blockers, 6 MAJORs, 11 NITs. Highlights: 3
surviving mutations (wire-unit classification unguarded; assembled-screen settings wiring
untestable due to a MISDIAGNOSED Robolectric limitation — actually off-screen nodes needing
performScrollTo; recomposition guard counting the wrong scope); 2 real data-path defects
(duplicate sparkline points from the 3-way combine; blank threshold field committing null →
permanently amber). Also verified round-trip unit exactness, DataStore corruption fallback,
live-recolor end-to-end, and recomposition isolation itself.

**Round 2** (fixes by ui-agent continuation, at 7dd54cf): 5/6 verified fixed, all 3 round-1
mutations killed — but the M6 fix introduced a regression: derived text snapped back after
clearance, retyping PREPENDED (clear+225 → stored 225220.0, silently green).

**Round 3** (orchestrator arbitration fix, at 48f9ffb): clearance regression fixed with
focus-guarded local text — but the reviewer disproved the design's premise: Compose
clickable takes no focus, so a mid-edit unit toggle left °F digits parsing as °C (3821°F
green-max). New MAJOR.

**Round 4** (orchestrator fix, at 334fbae): **approved.** Split LaunchedEffect (unit changes
re-derive unconditionally; focus guard only on wireValue) + clearFocus() on unit/reset
buttons. Reviewer probes: mid-edit toggle re-derives correctly, commits interpret the unit
the user SEES, reset-while-focused fixed, round-2 fix intact, mutation bites the mechanism.

## Fix list
- [x] M1 ✅ wire-unit classification test (mutation c killed)
- [x] M2 ✅ assembled-screen tests w/ performScrollTo; MODULE.md myth corrected (mutation d killed)
- [x] M3 ✅ counting-modifier recomposition guard (mutation e killed; count question judged sound)
- [x] M4 ✅ timestamp dedupe + SparklineHistoryHolderTest (5 cases) + trim-on-duplicate NIT
- [x] M5 ✅ gaugeOrder reconciliation both directions + DEFAULT_GAUGE_ORDER future-proofed
- [x] M6 ✅ blank commits nothing — through two regression cycles to the final focus/unit-safe design
- [x] NITs: gap predicate tested, Celsius 1-decimal, draw-path allocations hoisted, key(id)
- Skipped w/ reasons (round-2 report): scenario-driven sparkline test, SettingsViewModelTest,
  keystroke debounce, retryWhen, keep-screen-on wiring test

Final: 106 demo / 70 prod tests green; verifyRoborazzi + assembleProdRelease green; 9 total
mutations across rounds all killed.
