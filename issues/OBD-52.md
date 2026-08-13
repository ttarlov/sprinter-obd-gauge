---
id: OBD-52
title: Boost/MAP discovery — extended-session UDS sweep at engine ECU (charter decision)
module: core/protocol
owner: orchestrator
sprint: backlog
status: in-progress
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

## ✅ CHARTER DECISION (Taras, 2026-08-13): targeted-first, sweep-on-failure
"Let's tighten it to the targeted test first. If we don't get anything out of it we move
to full sweep." Approved scope, in order:
1. TARGETED: `10 03` at 7E0 → immediately `22 20 C4` (ScanGauge's NCV3 3.0L boost DID)
   → read → session lapses naturally. One probe, ten seconds.
2. IF NRC persists: full read-only 22-DID sweep at 7E0 under 10 03 (researched ranges
   first: 20xx, 80xx, B0xx), engine idling, parked.
Always: 7E0 only, never the TCU; reads only; no security access, no writes, no routines,
no DTC ops. Session-21-at-engine sweep (default session, no session change) remains
approved implicitly as pure read.

## Original blocked framing (superseded)
- Scope if approved: `10 03` at 7E0 only (never the TCU), read-only 22-xx-xx sweeps of
  researched ranges (20xx, 80xx), engine idling, parked, session drops back on timeout
  naturally; no writes, no routines, no DTC clearing, ever.
- Risk assessment: 10 03 is what every commercial scan tool sends; it changes no
  persistent state. The charter line exists for deliberateness, not because 10 03 is
  dangerous. Decision recorded here either way.

## If approved
Parked discovery session: sweep candidates, identify MAP (idle ≈ baro 82 kPa, blips with
throttle), commit fixtures, wire boost's MissingInputs → Available.

## Research update (2026-08-13, docs/hardware/research-2026-08-13-boost.md)
Open-source hunt came back STRONG NEGATIVE: no working Mercedes-badged OM642 boost
request exists publicly; likely security-access gated beyond 10 03 (Torque's own dev
failed the analogous hunt over 18 months). REVISED plan: (1) FIRST run the free
service-21 sweep at 7E0 in default session (community never tried it; no charter change;
next van minute). (2) 10 03 decision de-prioritized — likely insufficient alone.
(3) If 21 fails: Xentry-session sniff (big adventure, new decision) or aftermarket
sensor; boost tile stays honestly unavailable meanwhile.
