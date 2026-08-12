---
id: OBD-49
title: KWP 21 30 trans-temp channel — TCU record requester + byte-18 extraction (unverified)
module: core/protocol
owner: protocol-agent
sprint: 3
status: in-review
type: feature
hardware-verify: true
blocked-by: []
branch: protocol/43-49-pids-kwp
---

## Feature
The protocol layer can request the 722.9 TCU's KWP `21 30` record (physical addressing
`7E1`, positive response `61 30`, 26-byte multi-frame) and extract trans temp:
data byte 18, scaling raw−50 °C. Ships `verified=false` until the cold-start capture
(🖐 below) proves the offset.

## Ground truth (hardware session 2026-08-12 — docs/hardware/session-2026-08-12.md)
- `ATSH7E1` + `220543` → `7F 22 11` — UDS 22 FALSIFIED on this TCU (default session)
- `ATSH7E1` + `2130` → CONFIRMED. Captured records (headers on, raw frames):
  - warm idle:  `7E9 10 1A 61 30 00 13 00 00 / 7E9 21 00 00 00 08 04 00 DD /
    7E9 22 8E FF F3 FF F3 00 00 / 7E9 23 86 18 00 08 00 00 FF`
  - post-stall: `... 22 8D FF F6 FF F6 00 00 / 23 86 10 00 08 00 00 FF`
  - post-drive: `... 61 30 00 12 00 FF ... 22 93 FF FF FF FF 00 00 / 23 86 00 00 08 00 00 FF`
- Byte 11 tracks engine coolant via raw−50 (92/91/97°C across the session) — sanity anchor
- Byte 18 (`0x86` = 84°C) = trans-temp candidate; consistent with 722.9 thermostatic
  regulation; NOT yet movement-proven

## Acceptance criteria
- [ ] Request/response cycle: header set/restore discipline (as Mode22Requester), 61 30
      validation, negative-response (7F 21 xx) typed handling, multi-frame reassembly
      against the captured raw-frame fixtures above (exact bytes as test fixtures)
- [ ] Extraction: data byte 18, °C = raw − 50; PidDefinition `verified = false`
- [ ] Byte-11 coolant cross-check decoder (same record, raw−50) as a consistency probe —
      NOT displayed; test-only anchor
- [ ] Fixture tests for all three captured records + the 7F 22 11 negative
- [ ] 🖐 Hardware checklist (cold-start capture, Taras): byte 18 ≈ ambient+50 when cold,
      climbs toward 0x86 warm → flip verified=true in a follow-up

## Out of scope
Uncatalogued record fields (bytes 13/15 pairs, state fields); oil temp; MAP DID discovery.
