---
id: OBD-59
title: Re-gate trans temp to unavailable — byte-1 decode FALSIFIED in the field
module: core/protocol
owner: protocol-agent
sprint: boost-wave
status: open
type: bug
hardware-verify: false
blocked-by: []
branch: protocol/59-transtemp-regate
---

## Bug (on-vehicle 2026-08-13, docs/hardware/session-4-2026-08-13-transtemp-FALSIFIED.md)
The OBD-55 trans-temp decode (record 21 30 byte 1, 63−raw) is WRONG — byte 1 jumps
frame-to-frame at operating RPM (39→08→04→1C in seconds = 6→55→59→35°C). It's a dynamic
signal, not temperature. Identification was aliased from sparse quiet-idle snapshots. The
gauge shows a jumping wrong number when connected to a running engine.

## Fix (safe direction — remove the wrong value)
Re-gate PidIds.TRANS_TEMP to unavailable so the tile shows "—" (not a wrong number):
- Add PidIds.TRANS_TEMP back to a not-published gate. Reuse the DecodeFalsified machinery
  (rename its evidence appropriately, or add an "unidentified" availability). It must be
  NOT sent / NOT stored → tile blank, honest.
- Retire the byte-1 transTempCelsius decode (63−raw). Keep the record REQUESTER + parser
  (we still need to poll 21 30 to identify the real byte later) but do not publish a
  TRANS_TEMP value from it.
- verified stays false; availability = a typed "not-yet-identified" (or DecodeFalsified
  with field-falsified evidence).

## Acceptance criteria
- [ ] Trans tile shows "—" (no value published) — prod e2e updated to assert no trans value
- [ ] OBD-55's byte-1 anchor tests removed/retired with justification (decode falsified
      on-vehicle); the record parser/requester machinery stays for OBD-51's re-ID
- [ ] Boost/oil/coolant/rpm unaffected; gate green
- [ ] Tier-A review (it changes a displayed value — to blank, the safe direction)

## Out of scope
Re-identifying the real trans-temp byte — that's OBD-51 reframed (logged full-record ID
from a warmup+drive). This issue only removes the wrong number.
