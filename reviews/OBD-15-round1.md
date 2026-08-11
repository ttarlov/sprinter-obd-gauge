---
issue: OBD-15
round: 1
issues-covered: [OBD-15, OBD-16]
reviewers: [rev-correctness, rev-arch]
verdict: approved
gate: green
reviewed-commit: d94ab99
---

Tier-A round 1 on `protocol/15-16-mode22-boost` (rev-platform n/a: pure JVM). rev-arch:
all six checks script-verified clean by the orchestrator (isolation, contracts, deps,
purity, MODULE.md currency, commit hygiene — 4/4 Role trailers). rev-correctness: APPROVE,
no blocker, at 84e1245 with 2 MAJORs + 8 NITs.

**The load-bearing result:** the MTH decode was independently re-derived in exact rational
arithmetic — mul 9 / div 5 / adder −58 (two's complement), k = 50 exactly as an integer,
0/256 round-trip mismatches, coolant control case verified present and load-bearing in tests.
No arithmetic error anywhere. The residual assumption (display UNIT, °F) is now named in
MODULE.md as OBD-22's to settle. 14 mutations: 12 killed, 1 real survivor (→ M2), 1
equivalent.

## Fix list
Fixed pre-merge in d94ab99 (orchestrator-authored; reviewer-specified; M2's test authored
by the reviewer and handed over):
- [x] M1 ✅ (MAJOR) failed header restore left same-cycle standard PIDs addressed to 7E1 —
  retry now runs before each standard poll (no-op when clear). First test iteration was
  mutation-tested and found NOT to bite (the in-request failed attempt satisfied it);
  rewritten with a transient single-fault so only the intra-cycle successful retry passes.
  Mutation re-run: reverting the retry FAILS the suite.
- [x] M2 ✅ (MAJOR, verification gap) start()'s join had no biting test (single-threaded
  TestScheduler can't exhibit the race). The reviewer's validated Dispatchers.Default latch
  probe added as RealVehicleDataSourceJoinTest; join-removal mutation FAILS it; suite stable
  across repeat runs.
- [x] N1 ✅ unit-assumption sentence in MODULE.md (control case licenses formats, not unit)
- [x] N2 ✅ every-mode-22-unverified registry assertion
- [x] N5 ✅ restorePending KDoc rationale corrected (cancellation, not timeout)
- [x] N7 ✅ dependency-expansion note (boost publishes map+baro) in MODULE.md
- [x] N8 ✅ cycle overflow removed (modulo)
- N3 (spurious per-cycle restore on ATSH-rejecting clones): accepted, deferred — behavior
  change without hardware data; revisit at OBD-22 with real clone behavior.
- N4 (AC-5 deviation: verified flag surfaced via PidCatalog.isVerified, not frozen Reading):
  accepted as better-than-AC; OBD-27's brief must reference PidCatalog. Noted in issue file
  at merge.
- N6 (dual representation of mode-22 wire facts): accepted, consistency-asserted by test;
  candidate cleanup at OBD-22.

## Reviewer-verified (at 84e1245; fixes re-verified by orchestrator at d94ab99)
RXF provably outside the correctness path; ATCRA from ISO 15765-4 with escape hatch tested
end-to-end; restore-failure remembered/retried/surfaced; lifecycle contract three-sided;
cycle-0 full poll; boost at 101/81/69 kPa + vacuum + anti-fixed-offset test; wave-1 90 tests
untouched and green; full repo suite 574/0 at review time, 189 in :core:protocol after fixes.
Mutation table in reviewer transcript (a–m).
