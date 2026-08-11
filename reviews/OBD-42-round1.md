---
issue: OBD-42
round: 1
reviewers: [rev-correctness+rev-platform combined (Tier B)]
verdict: approved
gate: green
reviewed-commit: 4ebd924
---

Consolidated 2-round Tier-B review of `ui/42-gauge-swap-carousel`.

**Round 1** (at 255982d): changes-requested — 2 MAJOR, 1 MINOR, 4 NIT. Swap correctness
(the lying-gauge risk) held from the start: four-surface test bites on value/unit/coloring
independently, single-shared-code-path claim verified, persistence durable across process
death, dismiss paths structurally sound. MAJORs: stale pickerTileId after a swap (eaten back
press + phantom picker reopen — key(id) teardown cancels the dismiss effect) and the codec
slot-count backfill deferring gauge-resurrection to OBD-43's catalog addition (proven by
forward-simulation). 4/4 injected mutations killed; 2 probes + 1 simulation exposed the gaps.

**Round 2** (fixes by ui-agent at eefcbb8): approved with 1 new MINOR + 2 NITs. M1 self-heal
verified (both probes pass; reviewer endorsed the viewModel-driven reopen test as the only
non-self-masking construction). M2 backfill removal verified structurally (resurrection
impossible); rewritten OBD-21 tests judged net-stronger; the "picker is the discovery
surface" story judged coherent, with the noted trade that existing installs can't grow tile
count (follow-up idea recorded: GaugesSection listing absent catalog gauges). M3 + all NITs
(a11y actions without merge boundary, mini-card stale treatment, doc lines) verified.

**Round-2 items, fixed pre-merge in e1bf060/4ebd924 (orchestrator-authored,
reviewer-specified):**
- [x] MINOR ✅ all-unknown persisted order → zero-tile unrecoverable dashboard; ifEmpty now
  after reconciliation + round-trip regression test
- [x] NIT ✅ vacuous M2 test hardened with a short-order case that reaches the retired
  heuristic's branch
- [x] NIT ✅ reconcileGaugeOrder's prod-wrong default catalog param dropped

## Fix list
- [x] M1 ✅ (mutation e: reverting self-heal fails 2 tests)
- [x] M2 ✅ (mutation g: reinstating heuristic fails 2 tests; catalog+1 simulation clean)
- [x] M3 ✅ (mutation f bites) · a11y/stale/doc NITs ✅ · round-2 trio ✅

Final: 140 demo / 99 prod tests, verifyRoborazzi + assembleProdRelease green, classpath
clean, gate PASS. Carried forward: mini-card stale rendering unasserted (cosmetic);
GaugesSection-lists-absent-gauges as the future tile-count-growth path.
