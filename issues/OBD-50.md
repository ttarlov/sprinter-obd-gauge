---
id: OBD-50
title: Oil temp via standard 015C + live-verified PID catalog additions (fuel, ambient, voltage, torque)
module: core/protocol
owner: protocol-agent
sprint: backlog
status: in-progress
type: feature
hardware-verify: false
blocked-by: []
branch: protocol/50-verified-pid-additions
---

## Feature
Registry gains the PIDs live-verified in hardware session 2 (docs/hardware/
session-2026-08-13.md §5), all standard mode 01, all captured answering on the van:
- **oilTemp = 015C (A−40 °C), verified=true — closes OBD-35.** The dashboard's oil tile
  goes from permanently-blank to real.
- fuelLevel 012F (A×100/255 %), fuelRate 015E ((A×256+B)/20 L/h), ambientTemp 0146
  (A−40), moduleVoltage 0142 ((A×256+B)/1000 V), accelPedal 0149, demand/actual torque
  0161/0162 (A−125 %), all verified=true with the session's captured values as fixture
  anchors.
- SLOW poll priority for all (none are boost-rate signals).

## Acceptance criteria
- [ ] Exact-arithmetic scaling tests anchored on the session's written-down values
      (89°C, 42.7%, 1.15 L/h, 20°C, 14.05V, 5%/11%)
- [ ] oilTemp definition replaces the OBD-35 unknown; dashboard oil tile reads it via
      the existing prod chain with no :app changes beyond catalog verified-flags
- [ ] 0140 bitmap probed in the e2e fixture for support-map completeness
- [ ] Existing tests green; MODULE.md PID survey table updated

## Out of scope
New dashboard tiles for fuel/ambient/etc. (that's the gauge-catalog follow-up with OBD-43's
load/TPS); trans temp (OBD-51); boost (OBD-52).
