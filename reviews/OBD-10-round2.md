---
issue: OBD-10
round: 2
reviewers: [rev-correctness, rev-platform]
verdict: approved
gate: green
reviewed-commit: 970fff4
---

Round-2 delta review (fix commits `f9de200`/`a67956e`/`bddb685` + responses, reviewed at
`471c594`). Combined-lens round by a single fresh Opus reviewer, orchestrator-sanctioned
under budget wind-down; context isolation from the author preserved (§5.4).
`reviewed-commit` is the mechanical post-review rebase of `471c594` onto main (§6.4):
`git diff 471c594 970fff4` shows only main's tools/merge.sh fix — no branch content changed.

Verdict: **approved.** Every BLOCKER/MAJOR verified fixed; four source mutations run to
prove the new tests bite (zoneColor swap, eager-start restore, stop removal → all fail the
suite; see below for the one that didn't).

## Fix list
- [x] B1 ✅ verified — red state rendered from hand-built readings; stateDescription "red" asserted on two tiles
- [x] M2 ✅ verified — zoneColor internal + per-zone test; mutation-proved (GREEN/AMBER swap fails)
- [x] M3 ✅ verified — boostArcFraction extracted; −5/−2/0/8/18/25 incl. both clamp ends; boost tile asserted
- [x] M4 ✅ verified — "last seen 42s ago" asserted; fails if stale branch deleted
- [x] M5 ✅ verified — portrait + landscape qualifier tests assert all four tiles displayed; portrait proves the scrollable Column branch
- [x] M6 ✅ verified — collectAsStateWithLifecycle + explicit lifecycle-runtime-compose dep
- [x] M7 ✅ verified — onStart/onCompletion upstream of stateIn; both halves mutation-proved
- [x] M8 ✅ verified — explicit dark SystemBarStyle both bars
- [x] M9 ✅ verified — grep clean; only di/DataSourceModule.kt references :core:testing
- [x] M1/M10 ✅ verified via rebase — gate runs verifyRoborazzi (refs load-bearing, 2 compared/unchanged); releaseRuntimeClasspath grep empty
- [x] N1-N5 ✅ all verified fixed

## Round-2 notes (non-blocking, carried forward)
- [NIT] M7's `stopCallCount == 0` assertion doesn't pin the 5 s window (STOP_TIMEOUT_MILLIS=0
  mutation stayed green). Not a product defect.
- [NIT] `WhileSubscribed` restart semantics create an implicit restart-idempotence contract on
  `VehicleDataSource.start()`/`stop()` (double-stop, re-start). FakeVehicleDataSource is safe;
  pin it in the interface KDoc before the real implementation — scoped to OBD-12/OBD-25.

## Verified items (reviewer-run)
- 38 tests, 0 failures (BoostArc 6, Screen 7, Screenshot 2, ViewModel 4, Formatting 5,
  ThresholdConfig 14) via `--rerun-tasks`; JUnit XML parsed for real counts
- verifyRoborazziDebug: 2 screenshots compared (not recorded), both unchanged
- Four source mutations run + reverted; three fail the suite as intended
- M9/M10 greps run; rebase ancestry checked (main ancestor of head)
