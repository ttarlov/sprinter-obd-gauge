---
id: OBD-55
title: Trans-temp gauge goes live — record 21 30 byte 1, retire falsified X-Gauge, id swap
module: core/protocol
owner: protocol-agent
sprint: backlog
status: in-review
type: feature
hardware-verify: false
blocked-by: []
branch: protocol/55-trans-temp-live
---

## Feature
Ship the transmission-temperature gauge, identified on-vehicle 2026-08-13 (drive test,
docs/hardware/session-3-2026-08-13-transtemp.md): record `21 30` DATA BYTE 1,
**°C = 63 − raw**. This is the OBD-49 id-swap endgame — the falsified X-Gauge byte-0/byte-18
decode is retired and `PidIds.TRANS_TEMP` is reassigned to the KWP-record byte-1 channel,
so the dashboard trans tile reads a real value (currently blank via the DecodeFalsified
gate).

## Ground truth (session 3 — anchors for tests)
- Cold soak: byte1 raw 0x2D → 18°C (≈ ambient; the ONE independent anchor)
- Warm idle: raw 0x23 → 28°C
- Post-drive: raw 0x12 → 45°C, held steady 90s while coolant (byte 11) dropped 95→93°C
  (decoupled — proves not-coolant)
- byte 18 = engine-run status bitfield (NOT temp); byte 11 = coolant echo (raw−50, keep as
  the test-only anchor)

## The work (Tier A — displayed value, the charter's crown jewel)
1. `TcuRecordRegistry`: `TRANS_TEMP_BYTE` 18 → **1**; scaling `raw − 50` → **`63 − raw`**
   (new inverse formula — add `transTempCelsius` = `TRANS_TEMP_OFFSET(63) − raw`). Keep the
   byte-11 `tcuCoolantCelsius` (raw−50) coolant anchor decoder unchanged.
2. Reassign `PidIds.TRANS_TEMP`: retire `MercedesPidRegistry.transTemp` (X-Gauge byte-0,
   falsified) from `MercedesPidRegistry.all`; make the byte-1 KWP-record channel own
   `PidIds.TRANS_TEMP` so it is POLLED and DISPLAYED. Remove `PidIds.TRANS_TEMP` from
   `PidCatalog.FALSIFIED_DECODES` (no longer falsified — real byte found).
3. `verified = false` — the SLOPE is provisional (offset anchored on cold=ambient, slope
   assumed 1°C/count; high-temp behavior >60°C untested). Honest badge stays until a hot
   sample lands (OBD-51 residual). Document this precisely in the definition KDoc.
4. Update the captured fixtures/tests: the three session-1 records + the session-3 records
   as byte-exact fixtures asserting byte1→temp under 63−raw AND byte11→coolant under raw−50
   (the decoupling is the identification proof — pin it).
5. Pinned-test impact: retiring `MercedesPidRegistry.transTemp` touches the OBD-15 X-Gauge
   tests. Update with justification (the decode was FALSIFIED on-vehicle — the tests that
   pinned it as the trans channel now pin its retirement). Do NOT weaken the X-Gauge MTH
   machinery tests themselves if still used elsewhere; only its role as PidIds.TRANS_TEMP.

## Acceptance criteria
- [ ] Exact-arithmetic tests: 63−raw at the three anchors (0x2D→18, 0x23→28, 0x12→45) and
      boundary behavior at raw 63 (→0°C) documented; coolant-decoupling test (byte1 steady
      while byte11 moves) from the session-3 fixtures
- [ ] PidIds.TRANS_TEMP resolves to the byte-1 channel; polled; NOT in FALSIFIED_DECODES;
      verified=false
- [ ] Prod chain e2e: a 21 30 record → trans tile shows the 63−raw value with unverified
      badge (extend ProdChainEndToEndTest with a session-3 record)
- [ ] Frozen :core:model untouched (id string unchanged — reassignment is protocol-internal)
- [ ] Full gate green; MODULE.md updated (the decode is SOLVED-and-identified, scaling
      provisional)

## Out of scope
Slope refinement (needs >60°C sample, OBD-51 residual); shaft-speed gauges (21 31); the
app/protocol verified-flag duplication (OBD-53).
