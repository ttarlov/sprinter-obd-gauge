---
issue: OBD-19
round: 1
reviewers: [rev-correctness+rev-platform combined (Tier B, doc 05 §5.5)]
verdict: approved
gate: green
reviewed-commit: a734b31
---

Tier-B review of ble/19-debug-console at e8328aa: **approved**, no blockers. Tier boundary
verified CLEAN (only new files under core/ble console/; zero modified existing production
files — no escalation). Release hygiene verified four ways: both release classpath greps
empty, ConsoleActivity absent from all prodRelease manifests, release dex has zero console/
ble strings, positive control confirms the debug merge works. In-flight double-fire pinned
(tryLock refusal + gated UI); scrollback proven arrival-ordered across timeouts and
disconnects; timeout visually distinct from empty response; permission flow correct
(registered pre-RESUMED, denial logs without loop); lifecycle/DI sane.

## Fix list

Reviewer items, fixed post-approval in a734b31 (orchestrator-authored, reviewer-specified,
verified by rerun — reviewed-commit points at the fix head; delta is those three items only):
- [x] M1 ✅ timestamps pinned to the injected clock in the round-trip test — the reviewer's
  surviving mutation (now() → Instant.now()) re-run and now FAILS
- [x] N1 ✅ ImeAction.Send wired through the same guarded send path as the button
- [x] N2 ✅ auto-scroll keyed on entries.lastOrNull() — survives history saturation
- N3 (partial bytes discarded on link timeout) — out of tier: needs :core:ble link changes.
  Carried forward to OBD-41 prep/OBD-23: partial garbage is exactly the diagnostic you want
  during discovery.

Test counts (reviewer-run): :core:ble 146/0 (11 console), :app demo 52/0, prod-debug 16/0;
assembleProdRelease green. Post-fix (orchestrator-run): all green, clock mutation killed.
