---
id: OBD-50
title: Oil temp via standard 015C + live-verified PID catalog additions (fuel, ambient, voltage, torque)
module: core/protocol
owner: protocol-agent
sprint: backlog
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: protocol/50-verified-pid-additions
---

## Feature
Registry gains the PIDs live-verified in hardware session 2 (docs/hardware/
session-2026-08-13.md §5), all standard mode 01, all captured answering on the van:
- **oilTemp = 015C (A−40 °C), verified=true — closes OBD-35.** The dashboard's oil tile
  goes from permanently-blank to real.
- fuelLevel 012F (A×100/255 %), fuelRate 015E ((A×256+B)/20 L/h), ambientTemp 0146
  (A−40), moduleVoltage 0142 ((A×256+B)/1000 V), accelPedal 0149, demand/actual torque
  0161/0162 (A−125 %), all verified=true with the session's captured values as fixture
  anchors.
- SLOW poll priority for all (none are boost-rate signals).

## Acceptance criteria
- [x] Exact-arithmetic scaling tests anchored on the session's written-down values
      (89°C, 42.7%, 1.15 L/h, 20°C, 14.05V, 5%/11%)
- [x] oilTemp definition replaces the OBD-35 unknown; dashboard oil tile reads it via
      the existing prod chain with no :app changes beyond catalog verified-flags
- [ ] 0140 bitmap probed in the e2e fixture for support-map completeness — **NOT DONE**.
      No raw `0140` hex was captured in `docs/hardware/session-2026-08-13.md` §5 (only
      `015C`/`012F`/`015E`/`0146`/`0142`/`0161`/`0162`/`0163`/`015D`/`0151` values, no bitmap
      byte). Fabricating a bitmap byte to satisfy this bullet would be exactly the
      plausible-but-wrong-number failure this module exists to refuse, so it is left open
      pending a real `0140` capture.
- [x] Existing tests green; MODULE.md PID survey table updated

## What shipped
- `PidRegistry` gains six entries, all `verified = true`, all `SLOW`: `oilTemp` (`015C`),
  `fuelLevel` (`012F`), `ambientTemp` (`0146`), `accelPedal` (`0149`), `demandTorque` (`0161`),
  `actualTorque` (`0162`).
- `oilTemp` is wired to `PidIds.OIL_TEMP` — the id `:app`'s `DashboardPids.kt` dashboard oil
  tile already requests. The value flows with zero `:app` change (framing/scaling always come
  from `PidCatalog`, never from `:app`'s placeholder `PidDefinition`); the one `:app` edit made
  is flipping that catalog's `verified` flag from `false` to `true` so the badge stops claiming
  a hypothesis that closed. That flip cascaded into fixing every test that hard-coded oilTemp as
  the "unverified" example (`DashboardUiStateTest`, `ProdChainEndToEndTest`,
  `UnverifiedBadgeTest` — the latter's illustrative "unverified" examples now use `transTemp`,
  the one remaining mode-22 hypothesis) and re-recording three Roborazzi references that no
  longer show the oil tile's badge.
- **Frozen-contract blocker (not a `:core:model` edit made):** `015E` (fuel rate, L/h) and
  `0142` (module voltage, V) are both live-verified and both scaled
  (`VendoredSaeScaling.fuelRateLitersPerHour`/`moduleVoltageVolts`, tested against the session's
  1.15 L/h / 14.05 V anchors) but have **no `PidRegistry` entry** — neither unit exists on
  `MeasurementUnit` and this module does not extend that frozen contract on its own authority.
  Needs a reviewed `:core:model` change (`LITERS_PER_HOUR`, `VOLTS`) before they can become
  `StandardPidSpec`s.

## Out of scope
New dashboard tiles for fuel/ambient/etc. (that's the gauge-catalog follow-up with OBD-43's
load/TPS); trans temp (OBD-51); boost (OBD-52).
