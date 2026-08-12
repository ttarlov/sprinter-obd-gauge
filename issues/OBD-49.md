---
id: OBD-49
title: KWP 21 30 trans-temp channel — TCU record requester + byte-18 extraction (unverified)
module: core/protocol
owner: protocol-agent
sprint: 3
status: merged
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
- [x] Request/response cycle: header set/restore discipline (as Mode22Requester), 61 30
      validation, negative-response (7F 21 xx) typed handling, multi-frame reassembly
      against the captured raw-frame fixtures above (exact bytes as test fixtures)
- [x] Extraction: data byte 18, °C = raw − 50; PidDefinition `verified = false`
- [x] Byte-11 coolant cross-check decoder (same record, raw−50) as a consistency probe —
      NOT displayed; test-only anchor
- [x] Fixture tests for all three captured records + the 7F 22 11 negative
- [ ] 🖐 Hardware checklist (cold-start capture, Taras): byte 18 ≈ ambient+50 when cold,
      climbs toward 0x86 warm → flip verified=true in a follow-up

## Notes for review
- Fixtures are byte-exact in `TcuRecordCaptures` (test sources). The issue elides the unchanged
  `10`/`21` frames of the post-stall and post-drive records as `…`; those are reconstructed from
  the warm-idle record and carry bytes 4-10 only, which **no assertion reads**.
- Header set/restore discipline was **extracted** into an internal `HeaderScope` shared by
  `Mode22Requester` and the new `KwpRecordRequester` — behaviour-preserving, existing
  `Mode22RequesterTest` green unchanged. Two copies of that restore logic would be two places for
  it to rot, and the failure mode is silent (every standard gauge stops).
- **Round 2 (review closing argument, accepted):** the falsified X-Gauge decode is now STOPPED,
  not deferred to the id swap. `ChannelAvailability.DecodeFalsified` +
  `PidCatalog.FALSIFIED_DECODES` + a `RealVehicleDataSource.applyPoll` gate mean `transTemp` is
  never framed, never sent and never stored — only announced once at plan time. Distinct from
  `UnsupportedByVehicle`: that one is still polled for discoverability, this one is not, because
  what the byte means is already known. The id swap remains the endgame after the cold-start
  proof, but nothing wrong reaches a gauge in the meantime.

## Out of scope
Uncatalogued record fields (bytes 13/15 pairs, state fields); oil temp; MAP DID discovery.

## Hardware checklist (session 1, 2026-08-12 — Taras at the van, engine running)
- [x] `ATSH7E1` + `220543` → `7F 22 11` observed (UDS hypothesis falsified on hardware)
- [x] `ATSH7E1` + `2130` → positive `61 30` 26-byte record; three captures committed
      (warm idle / post-stall / post-drive) — byte 11 tracked engine coolant 92→91→97 °C
      across the session under raw−50
- [ ] 🖐 COLD-START capture (Taras, any cold morning, 30s before driving): byte 18 ≈
      ambient+50 and climbing → flips verified=true + triggers the id-swap change
