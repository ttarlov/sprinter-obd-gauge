---
issue: OBD-59
round: 1
reviewers: [orchestrator (close-out; safe-direction — removes a falsified displayed value)]
verdict: approved
gate: green
reviewed-commit: 617ea08
---

Orchestrator close-out. Removes the on-vehicle-falsified trans-temp value (byte 1 jumps at
operating RPM — docs/hardware/session-4). Safe direction (blank, not a wrong number).

## Fix list
- [x] ✅ Trans blanked via the DecodeFalsified gate: PidIds.TRANS_TEMP re-added to
      FALSIFIED_DECODES with field-falsified evidence; availabilityOf → DecodeFalsified;
      applyPoll gate keeps it neither framed/sent nor stored → tile shows "—". Not a
      verified=false value — unavailable.
- [x] ✅ byte-1 63−raw decode retired (transTempRecord.parse now refuses/throws, like
      computed boost); the aliased anchor + decoupling tests removed with justification
      (the decode they pinned is falsified on-vehicle).
- [x] ✅ Record machinery KEPT for OBD-51 re-ID: KwpRecordSpec/Parser/Requester + the
      21 30 spec + framing/reassembly tests intact (re-anchored on raw bytes, not the
      dead decode). This is the right call — OBD-51 needs to poll 21 30 to find the real
      byte.
- [x] ✅ Diff confined to 8 trans files (orchestrator-verified); boost/oil/coolant/rpm/
      MAF/IAT/scaling and frozen :core:model all untouched.
- [x] ✅ Mutation (remove TRANS_TEMP from the gate) → 3 tests fail. Gate is load-bearing.
- [x] ✅ :app e2e flipped to assert no trans value / "—"; tile keeps unavailable render.
      Gate PASS.

Verdict: **approved** at 617ea08. Merge target: main. Trans temp returns to honest-unknown
until OBD-51's logged full-record identification earns it back.
