---
issue: OBD-17
round: 2
issues-covered: [OBD-17, OBD-18]
reviewers: [rev-correctness+rev-platform combined (fresh context)]
verdict: changes-requested
gate: green
reviewed-commit: 3b8c6c1
---

Round-2 delta verification of the round-1 fixes. Verdict: **changes-requested** — B1/M1-M9/
N1-N6 all verified fixed (mutations A/B/C/D re-run and killed), but:

- **B2 partial:** generation gated only *publishes*, not *work*. Reviewer probes proved an
  overlapping connect on the remembered fast path opens a THIRD transport and orphans the
  tracked one, and disconnect() mid-Direct-handshake leaves a live radio + an unwanted scan.
- **R2-1 (new MAJOR):** responseDebt/writeAckDebt never expire — one lost response (dongle
  reset, truncated frame) re-arms the debt forever: a healthy dongle's every answer eaten,
  link permanently dead while showing Ready.
- **R2-2 (new MAJOR):** the `commandOnTheWire` invariant had no test bite — booking debt
  unconditionally survived all 131 tests.

## Arbitration resolution (escalation ladder, docs/05 §10.2 #3)
Round 2 with an unresolved blocker → orchestrator arbitration. Fixes orchestrator-authored
in `44542a2` (wave in budget wind-down; reviewer-specified fixes, surgical):
- B2 ✅ — stale attempts do no work: entry/post-create/post-handshake generation checks in
  openSession, post-scan check in scanAndConnect, gated Direct→scan fallback,
  release-before-track. Regression tests: overlap-on-remembered (exactly 2 clients, winner
  tracked, loser closed, no scan), disconnect-mid-Direct (no scan, client closed,
  Disconnected wins).
- R2-1 ✅ — settleDebts(): one quiet window (BleConfig.debtQuietWindow, 300 ms), completed
  early via a gate the payment handlers signal; unpaid debts presumed lost and cleared.
  Test: never-arriving response costs one window, link recovers.
- R2-2 ✅ — virtual-time test: refused write books no debt, next command pays no quiet
  window (fails under the unconditional-booking mutant).
- Reviewer nits ✅ — CancellationException rethrow in store writes; terminated-callback
  ordering comment.

## Mutation evidence (all reverted, suite green after each)
| Mutation | Result |
|---|---|
| E — book debt unconditionally (R2-2's survivor) | **FAILS** |
| never clear expired debts | **FAILS** |
| remove Direct-fallback generation gate | **FAILS** |
| remove openSession ENTRY generation check | survives — assessed near-equivalent: the entry
check guards only the lastAddress() suspension window; every reachable-harm path (post-scan,
post-handshake, fallback) is independently gated and mutation-killed. Accepted as
defense-in-depth, noted honestly rather than pinned with an exotic interleaving test. |

## Fix list
- [x] B2 ✅ verified by regression tests + mutations (round-3 confirmation pending)
- [x] R2-1 ✅ · R2-2 ✅ · nits ✅
Final state: 135 tests, 0 failures; ktlint + detekt clean.
