---
id: OBD-56
title: MAF (0166) + IAT (0168) real-sensor PIDs — the survey missed them
module: core/protocol
owner: protocol-agent
sprint: boost-wave
status: in-progress
type: feature
hardware-verify: true
blocked-by: []
branch: protocol/56-maf-iat-pids
---

## Feature
Add MAF and IAT as real gauges, decoded from the extended sensor PIDs this van DOES answer
(the standard 0110/010F are unsupported, but 0166/0168 are not — research
docs/hardware/research-2026-08-13-boost-inference.md).
- MAF: PID 0166 sensor A = (256·B + C)/32 g/s. Capture anchor: `01 01 C7` → 14.2 g/s.
- IAT: PID 0168 sensor 1 = A2 − 40 °C (2nd data byte). Capture anchor: `01 54 00` → 44°C.
  Note 0168 returns non-standard padded bytes (multi-frame) — parse only the standard
  sensor-1 field; log the rest.

## Acceptance criteria
- [ ] Exact-arithmetic scaling tests on the captured anchors
- [ ] Dual-bank/padded response framing handled (0166 5-byte, 0168 multi-frame) with the
      captured bytes as fixtures
- [ ] verified=false pending 🖐 throttle-sweep confirmation (MAF rises with load, IAT ≈
      ambient+soak) — one short drive/rev confirms both
- [ ] These also become MAP-inference inputs for OBD-57

## Out of scope
Estimated boost (OBD-57); the 0168 padded-byte identification (separate probe).
