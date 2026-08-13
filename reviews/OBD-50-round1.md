---
issue: OBD-50
round: 1
reviewers: [orchestrator (scoped Tier-A: new displayed-value scalings, mutation-verified)]
verdict: approved
gate: green
reviewed-commit: 168e7a8
---

Orchestrator close-out review of the OBD-50 verified-PID additions. These compute
displayed values → Tier-A discipline applies; verified by hand-derivation + mutation
rather than a spawned reviewer (surgical scope, same pattern as OBD-25's seam close-out).

## Fix list

- [x] ✅ Eight scalings hand-verified against SAE J1979 AND the live session-2 anchors:
      oilTemp 015C A−40 (0x81=89°C, closes OBD-35), fuelLevel 012F A×100/255 (0x6D=42.7%),
      ambientTemp 0146 A−40 (0x3C=20°C), accelPedal 0149 A×100/255 (0x0D=5.1%),
      demandTorque/actualTorque 0161/0162 A−125 signed (0x82=5%, 0x88=11%),
      fuelRate 015E (256A+B)/20 (0x0017=1.15 L/h), moduleVoltage 0142 (256A+B)/1000
      (0x36E2=14.05V). All exact.
- [x] ✅ Mutation-verified (orchestrator-run, cp restore, tree clean after each): torque
      offset 125→124 KILLED; fuel-rate divisor 20→21 KILLED. Anchors bite.
- [x] ✅ oilTemp wired to the id the dashboard oil tile already requests (PidIds.OIL_TEMP)
      — value flows through the prod chain with the app catalog only mirroring the
      verified flag. OBD-35 closed.
- [x] ✅ Frozen-contract discipline held: fuelRate/moduleVoltage have NO registry entry
      because MeasurementUnit lacks L/h and V — the builder did NOT touch :core:model,
      pinned the arithmetic ahead of the unit landing, and reported it. Correct call.
- [x] ✅ 0140-bitmap AC left unchecked with reason (no raw 0140 hex in the session doc —
      refused to invent a bitmap byte). Correct refusal.
- [x] ✅ 247 → 262 protocol tests; gate PASS.

## ⚠️ Process finding: module-isolation violation (accepted under arbitration, root cause filed)

protocol-agent's branch edited 12 ui-agent/orchestrator-owned paths (role-scoped
`module-isolation.sh protocol-agent` FAILS; gate.sh's sanity-parse does not catch this).
Content review: EVERY :app edit is the forced, mechanical cascade of ONE semantic change —
oilTemp `verified` false→true. DashboardPids flag flip; Loading/preview placeholders;
KDoc example-moves (oil→trans as the "unverified example") in DashboardUiState/GaugePicker/
UnverifiedBadge/ServiceNotificationState; two test files updating the same illustrative
example; three Roborazzi re-records where the oil badge correctly vanished. No behavioral
:app change beyond the flag; no scope creep. Verified line-by-line.

**Root cause (not the agent's fault):** the app-side DASHBOARD_PIDS duplicates
PidCatalog's verified flag (a pre-Phase-4 placeholder, flagged at OBD-25 review B8). A
protocol verified-flag change CANNOT be made without the app mirror also flipping, so this
specific change is unsplittable across the module boundary today. Accepted this once.
**Follow-up filed: OBD-53** — derive the app badge from PidCatalog.isVerified across the
now-existing prod :core:protocol dependency, eliminating the duplication so protocol
verified-flag changes stop forcing :app edits.

Verdict: **approved** at 168e7a8. Merge target: main.
