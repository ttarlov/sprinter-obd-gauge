---
id: OBD-51
title: Trans temp = 21 30 byte 1, ATF°C = 63 − signed(raw) — drive-test proof protocol
module: core/protocol
owner: protocol-agent
sprint: backlog
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: protocol/51-trans-temp-byte1
---

## Hypothesis (hardware sessions 1+2)
Record 21 30 data byte 1: ATF °C = 63 − raw, raw SIGNED (predicts wrap above 63°C:
80°C → raw 0xEF). Six samples over two days fit ±1° in the 18-45°C range (session-2 doc
§2). Byte 18 falsified (status bitfield); byte 11 = coolant echo; commercial app has no
real ATF source (packet-proven).

## 🖐 Hardware checklist (the proof — Taras, after any real drive)
- [ ] One `2130` snapshot immediately post-drive (console or traffic log): ATF should be
      65-90°C → byte 1 MUST read as a small negative signed value (0xF0-0xFF range)
      matching 63−raw. If it instead sits at a small positive value, the model is dead.
- [ ] Optional richer capture: OBD-48 traffic log during the drive itself

## After proof
Extraction + verified=true + retire the falsified X-Gauge spec + reassign
PidIds.TRANS_TEMP in one reviewed change (the OBD-49 endgame, byte 1 instead of byte 18).
Signed-wrap handling MUST be exact-arithmetic tested at the boundary (63°C ↔ raw 0/0xFF).

## Out of scope
Bytes 12-15 pairs (slip/current family, uncataloged).
