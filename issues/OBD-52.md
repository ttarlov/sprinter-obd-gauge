---
id: OBD-52
title: Boost/MAP discovery — extended-session UDS sweep at engine ECU (charter decision)
module: core/protocol
owner: orchestrator
sprint: backlog
status: blocked
type: process
hardware-verify: true
blocked-by: []
branch: none
---

## Situation (session 2)
Engine ECU (7E0) SPEAKS UDS-22 (`7F 22 31` = out-of-range, not unsupported) but serves
none of the researched DIDs (8032/8010/20C4) in the DEFAULT session. Commercial app
(packet-proven) makes NO boost attempt at all on this van. The remaining path is an
extended diagnostic session (`10 03`) + DID sweep at 7E0 — routine for scan tools, but a
UDS SESSION CHANGE, which the standing read-only charter excludes.

## 🖐 BLOCKED ON TARAS: explicit charter amendment decision
- Scope if approved: `10 03` at 7E0 only (never the TCU), read-only 22-xx-xx sweeps of
  researched ranges (20xx, 80xx), engine idling, parked, session drops back on timeout
  naturally; no writes, no routines, no DTC clearing, ever.
- Risk assessment: 10 03 is what every commercial scan tool sends; it changes no
  persistent state. The charter line exists for deliberateness, not because 10 03 is
  dangerous. Decision recorded here either way.

## If approved
Parked discovery session: sweep candidates, identify MAP (idle ≈ baro 82 kPa, blips with
throttle), commit fixtures, wire boost's MissingInputs → Available.
