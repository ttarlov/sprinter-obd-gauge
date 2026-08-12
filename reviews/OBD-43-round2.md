---
issue: OBD-43
round: 2
reviewers: [orchestrator (close-out of Tier-A round 1 by rev-correctness Opus)]
verdict: approved
gate: green
reviewed-commit: 098058d
covers: [OBD-43, OBD-49]
---

Round-1 (rev-correctness Opus, at c87f07c): APPROVED on correctness — all 5 mandated
mutations killed, three captured records independently hand-derived and byte-matched,
availability axis judged the strongest part of the branch. Findings: 2 doc-contract
MAJORs (PollEvent KDoc described a nonexistent event stream; dangling type name ×2),
2 MINORs (over-long-CF reassembly shift; loose 0xD3 window), 2 NITs — plus the accepted
closing argument: gate the falsified X-Gauge trans decode TODAY, not at the id swap.

Round-2 (orchestrator-seeded edits, builder-completed at 098058d):

## Fix list

- [x] ✅ MAJOR-1/2: PollEvent.ChannelAvailabilityChanged KDoc now describes actual
      behavior (once per session, plan time, non-Available only, no recovery event);
      dangling `ChannelUnavailable` refs fixed in both files.
- [x] ✅ Falsified-decode gate: `ChannelAvailability.DecodeFalsified(evidence)` +
      `PidCatalog.FALSIFIED_DECODES` (TRANS_TEMP, evidence cites the 2026-08-12 capture
      and the byte-18 correction) + applyPoll entry guard — never sent, never stored,
      announced once at plan time. Mutation (gate disabled) KILLED by exactly the two new
      gate tests. Distinctness from UnsupportedByVehicle pinned by its own test.
- [x] ✅ MINOR-1: up-front consecutive-frame validation (all frames checked before any
      byte is appended — a small correctness gain over the seeded per-frame check);
      over-long CF refused, new fixture test.
- [x] ✅ MINOR-2: 0xD3 window tightened to 82.5..83.5.
- [x] ✅ NIT-1/d2: rejected-ATSH restore now pinned (ATCRA + ATSH7DF asserted, restore
      Pending false).
- [x] ✅ Pinned-test surgery: FOUR scheduler tests repointed at a synthetic
      `pipelineProbe` channel via an internal `extraChannels` constructor seam (public
      API unchanged, :app cannot reach it). Each carries a written justification; all
      four assert scheduler behavior that is spec-agnostic — coverage strength preserved,
      verified by the mutation re-runs (byte-18 offset and boost-zero mutations still
      killed by 9 and 5 tests respectively).
- [x] ✅ MODULE.md: gate section + id-swap endgame; known-limitations now states the
      correct fact (no trans-temp reading at all, pending 🖐 cold-start).
- NIT-2 (foreign-id 7F typed MalformedHex) recorded, not actioned — carried forward.

Verdict: **approved** at 098058d. 247 tests, gate PASS. Merge target: main.
🖐 remaining on OBD-49 (cold-start capture) — the issue merges with its hardware-verify
checklist noting the pending proof; the id swap ships as its own reviewed change after.
