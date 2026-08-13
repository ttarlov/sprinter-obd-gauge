---
id: OBD-58
title: MeasurementUnit +GRAMS_PER_SECOND/LITERS_PER_HOUR/VOLTS — live MAF/fuel-rate/voltage, boost goes Available
module: core/model
owner: orchestrator
sprint: boost-wave
status: merged
type: contract-change
hardware-verify: false
blocked-by: [OBD-56]
branch: model/58-unit-additions
---

## Contract change (D9 sign-off — orchestrator, 2026-08-13)
Add three ADDITIVE members to the frozen `MeasurementUnit`: GRAMS_PER_SECOND,
LITERS_PER_HOUR, VOLTS. Then wire the channels that were scaling-only (behind this exact
gap) into live StandardPidSpec channels, and unblock live boost.

## Work
1. :core:model: MeasurementUnit gains the 3 members. Nothing else in the frozen contract
   changes. (Type: contract-change — D9 in DECISIONS.md is the sign-off.)
2. :core:protocol: MAF (0166, g/s) becomes a live channel (was PendingUnitContract from
   OBD-56/57); fuelRate (015E, L/h) and moduleVoltage (0142, V) become live channels (were
   scaling-only from OBD-50). Boost's MAF dependency flips PendingUnitContract → live →
   boost availability = Available on the van (still verified=false / "Est." until the VE
   calibration drive).
3. :app: handle the unit-render ripple — any exhaustive `when(MeasurementUnit)` (formatter)
   gains g/s, L/h, V branches. These are INPUT channels (MAF/fuelRate/voltage) not yet
   dashboard tiles, so rendering is only needed where the enum is matched exhaustively; keep
   it minimal and correct.

## Acceptance criteria
- [ ] MeasurementUnit +3 members; no existing member/semantic touched; frozen otherwise
- [ ] MAF/fuelRate/voltage are live polled channels with their real units
- [ ] Boost availability flips to Available (MAF now live); verified=false "Est." preserved
- [ ] :app compiles + renders the new units where exhaustively matched; gate green
- [ ] Tier-A review (contract change + it feeds the boost displayed value)

## Out of scope
Boost VE calibration (OBD-57 residual); phone-baro provider (OBD-57b); making MAF/fuel/
voltage into dashboard tiles (gauge-catalog follow-up).
