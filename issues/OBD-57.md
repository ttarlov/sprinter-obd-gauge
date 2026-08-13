---
id: OBD-57
title: Estimated boost via speed-density (MAF+IAT+RPM+baro) — PRODUCT DECISION
module: core/protocol
owner: orchestrator
sprint: backlog
status: blocked
type: feature
hardware-verify: true
blocked-by: [OBD-56]
branch: none
---

## Situation
Direct boost is dead over OBD (on-vehicle confirmed). But boost is COMPUTABLE by
speed-density from PIDs the van answers:
  MAP kPa = MAF(g/s)·IAT(K)·34.44 / (VE·2.987·RPM);  Est.boost = MAP − BARO
(research: docs/hardware/research-2026-08-13-boost-inference.md). Inputs land with OBD-56.

## 🖐 PRODUCT DECISION FOR TARAS (the charter question)
The whole project refuses to show plausible-but-wrong numbers. An inferred boost is an
APPROXIMATION, not a measurement. Ship it, or keep the boost tile honestly blank?
- IF SHIP: labeled "Est. Boost (calc)", unverified badge, flat VE=0.90 first, then a
  VE-calibration drive (log MAF/IAT/RPM/baro/torque vs a known boost reference — MB WOT
  spec ~21-22 psi, or a mechanical gauge) to tighten VE(RPM). Honest because clearly marked
  computed, not measured.
- IF NOT: boost tile stays — (typed unavailable), which it does correctly today.

## If approved
Build the computed-channel (like boost-from-MAP already is, but MAP itself computed);
ChannelAvailability flips MissingInputs→Available once OBD-56 lands MAF+IAT; VE-calibration
drive to refine.
