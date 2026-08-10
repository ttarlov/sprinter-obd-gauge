---
issue: OBD-17
round: 3
issues-covered: [OBD-17, OBD-18]
reviewers: [rev-correctness+rev-platform combined (round-2 context, delta-only)]
verdict: approved
gate: green
reviewed-commit: 44542a2
---

Round-3 confirmation of the round-2 arbitration fixes (`44542a2`). Verdict: **approved.**

## Fix list
- [x] B2 ✅ verified — reviewer's probes are committed verbatim as regression tests asserting
  exactly what the probes measured (2 clients not 3, loser closed, winner tracked, zero scans
  on a stale ticket; disconnect wins, nothing scans). Mutation F (ungate Direct fallback)
  kills both. Post-scan and post-handshake gates + release-before-track close every stale-work
  path.
- [x] R2-1 ✅ verified — quiet-window expiry reproduces the reviewer's probes 3+4; the
  settle gate short-circuits so the common case pays nothing.
- [x] R2-2 ✅ verified — mutation E re-run at 44542a2: FAILS. The virtual-time cost assertion
  pins the guard.

## Residual, accepted with corrected diagnosis
The generation-check pair around `transports.create()` guards one window (the `lastAddress()`
suspension in connect); create() is not a suspension point, so the checks are redundant with
each other — the ENTRY check is the load-bearing half, the post-create one the equivalent
mutant (reviewer's probing, inverting the round-2 record's framing). Worst case if both were
gone: a stale attempt tears down the incumbent — never a leak. NIT carried forward: a
suspend-gate knob on FakeRememberedDeviceStore would make the entry check killable
(candidate for the OBD-19 wave or OBD-23).

## Reviewer-verified (round 3)
Full delta reviewed; 135/0 via --rerun-tasks (XML-parsed); detekt + ktlint clean with the two
justified @Suppress; 4 mutations (E, F, G, H) applied and reverted; worktree clean.
