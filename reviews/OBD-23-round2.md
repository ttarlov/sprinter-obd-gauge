---
issue: OBD-23
round: 2
reviewers: [orchestrator (close-out of Tier-A round 1)]
verdict: approved
gate: green
reviewed-commit: af3108c
covers: [OBD-23, OBD-48]
---

Round-2 close-out of the round-1 fix list (reviews/OBD-23-round1.md).

## Fix list

- [x] ✅ B1 exception boundary: try/catch in the attempt body (CancellationException
      rethrown; all else → typed Error off Scanning + reschedule-or-disarm-loudly) PLUS
      CoroutineExceptionHandler on linkScope as outer net — and the builder widened it to
      the attended connect() path after its own test threw straight through the direct
      call. Layer-by-layer mutation-verified: catch removed → KILLED ×2, handler removed
      → KILLED ×1 (by a test asserting what the handler uniquely does, after the naive
      test survived), both → ×3. Orchestrator diff-verified the boundary shape.
- [x] ✅ M1 forget bumps generation in the same dispatcher block; mid-attempt forget can
      no longer re-remember. KILLED ×1.
- [x] ✅ m2 pre-scan stale gate (+ scan-count test); m3 `retrying` StateFlow (2 tests,
      LinkState untouched); m4 BluetoothOff recoverable ("retry unless provably futile",
      adapter-comeback test; the test asserting the old bug was removed with
      justification). All mutation-verified.
- [x] ✅ m1 + NITs 1-2 comment fixes; NIT-3 declined with recorded rationale (GATT-client
      leak risk from cancelling mid-connectGatt outweighs one discarded sweep) — accepted.
- [x] ✅ All reviewer mutations (a)-(f) re-run at af3108c: killed. (e) blocking-sink gap
      remains accepted-LOW and is now written into MODULE.md as a prerequisite for the
      future file-sink work. Builder's compile-error-mutation self-catch noted with
      approval — a mutation that doesn't compile proves nothing, and it re-ran honestly.
- [x] ✅ 186 → 194 tests; gate PASS; OBD-48 debug fence re-verified at AAR byte level.
- Bonus fix accepted: reconnect log vocabulary de-collided ("reconnect scheduled:
  attempt N of M in W") — caught by a failing assertion.

Verdict: **approved** at af3108c. Merge target: main.
