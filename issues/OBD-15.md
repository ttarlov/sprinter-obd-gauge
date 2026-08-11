---
id: OBD-15
title: Mode-22 Mercedes PID support
module: core/protocol
owner: protocol-agent
sprint: 2
status: merged
type: feature
hardware-verify: false
blocked-by: [OBD-9]
branch: protocol/15-16-mode22-boost
---

## Feature
Mode-22 Mercedes PID support — header control (ATSH/ATCRA), request framing, byte extraction, and MTH-style scaling — with a trans-temp definition ported from proven X-Gauge code, flagged `unverified` until hardware-checked.

## Contract surface
None expected.

## Acceptance criteria
- [ ] ATSH/ATCRA header-control commands issued correctly before mode-22 requests
- [ ] Request framing and byte-extraction logic implemented generically enough to support additional mode-22 PIDs later
- [ ] Trans-temp PID definition ported from proven X-Gauge scaling code, with the source cited in code comments
- [ ] Trans-temp request/parse round-trip verified against a synthetic transcript matching the X-Gauge reference behavior
- [ ] Every mode-22 PID definition carries an `unverified` flag by default, surfaced through to `Reading` for UI consumption (OBD-27)

## Self-test plan
Unit test replays a synthetic mode-22 transcript (header set → request → framed response → parsed value) and asserts the parsed trans-temp matches the expected scaled value.

## Out of scope
Real hardware capture/verification (OBD-22, OBD-26); oil-temp mode-22 discovery for the 5-speed OM642 (unscheduled, OBD-35).
