---
issue: OBD-25
round: 1
reviewers: [rev-correctness scoped Tier A (Opus) — displayed-value paths + ownership pin; orchestrator close-out]
verdict: approved
gate: green
reviewed-commit: 354a49f
---

Scoped Tier-A review of `ui/25-prod-di-wiring` at 89a96fa: the DisplayUnitDataSource
conversion seam (the newest place a wrong number can be born), the restart-on-Ready
ownership pin, and the e2e test's honesty. Verdict changes-requested on ONE MEDIUM;
fixed by orchestrator arbitration (mutation-verified) in the follow-up commit.

## Fix list

- [x] ✅ MEDIUM (the finding): boost kPa→PSI had ZERO coverage — the seam-bypass-for-boost
      mutation survived all 372 app tests. Latent (no MAP on this van) but the exact
      charter failure once the mode-22 MAP DID lands: 20 kPa cruise rendering "20.0 PSI",
      arc pegged. Fixed: two orchestrator-authored tests (82 kPa → 11.893 PSI; −20 kPa →
      −2.901 PSI pinning the vacuum sign); bypass mutation re-injected → KILLED; pristine
      re-verified. MODULE.md gains the flapping-degrades-to-blank note.
- [x] ✅ Arithmetic hand-verified exact by the reviewer: 94°C→201.2°F (test constant
      exact); kPa→PSI pure scale, no offset (correct for a differential); rounding happens
      exactly ONCE at format time; metric round trip asserted end-to-end.
- [x] ✅ Asymmetry hunt clean: PROTOCOL_UNITS derived not hand-listed (new channels
      auto-covered); thresholds classify against post-seam values in the same unit space;
      staleness/timestamps preserved; guard test proven non-vacuous (bites from either
      alignment direction).
- [x] ✅ Ownership pin real: reverting the trigger to the failure edge → 1 becomes 41
      (the measured storm, exactly). Flapping probe: bounded by successful connects,
      start() touches no radio, worst case ~1 restart/s; degrades to blank not stale —
      the safe direction. PollKeepAlive default-inactive pinned via the 3-arg-constructor
      test.
- [x] ✅ e2e honesty: every constant anchored on the session doc's written-down values;
      boost "—" assertion fails on "0". Mutation ledger: a/b/c/d1/e/f1 all KILLED
      (7/4/1/4/17/5 tests); d2 was the finding, now killed.
- Non-blocking notes recorded: wasReady-seeding nit (pinned as deliberate — one redundant
  init per service start); assertEquals-overload cosmetic nit; connectIfRemembered
  launch-time auto-connect flagged for eyes at the van session (permission- and
  remembered-guarded; correctness fine).

Verdict: **approved**. Merge target: main. 🖐 device ACs open: fresh-install permission
flow, van connect, screen-off keep-alive with the real source — all land at the next van
session (OBD-26 territory).
