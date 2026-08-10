---
issue: OBD-11
round: 2
issues-covered: [OBD-11, OBD-12]
reviewers: [rev-correctness+rev-platform combined (Tier B)]
verdict: approved
gate: green
reviewed-commit: 0c5f412
---

Round-2 delta verification of fix commit 6acb407 + doc-only follow-up 0c5f412 (same
fresh-context Tier-B reviewer, resumed for the deltas only).

## Fix list
- [x] M1 ✅ verified — RestartAnchoredDataSource is the injected VehicleDataSource, so
  WhileSubscribed restarts flow through the override; source and clock are the same
  @Singleton and cannot desync; Instant.now() zone-free. Delayed-start() test is a fair
  simulation of the real restart path.
- [x] M2 ✅ verified — DataSourceModuleClockTest exercises the real DI providers;
  mutation (epochSinceStartClock → systemUTC) fails both tests. 47 demo tests green.
- [x] N2 ✅ verified; N3 ✅ verified fixed in 0c5f412 (caught un-fixed in round 2).
- [x] R2-1 ✅ verified — MODULE.md now describes the shipped decorator design, its
  regression suite, and the class in the flavor section; superseded design removed.
- N1 accepted-as-disclosed (round 1).

## Reviewer-verified
Round-2 run at 6acb407: testDemoDebugUnitTest 47/0, testProdDebugUnitTest 11/0,
verifyRoborazziDemoDebug + assembleProdDebug green, mutation re-run bites, tree clean.
0c5f412 delta read-verified as documentation-only.
