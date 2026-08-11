---
id: OBD-16
title: Computed boost channel
module: core/protocol
owner: protocol-agent
sprint: 2
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: protocol/15-16-mode22-boost
---

## Feature
A computed boost channel (MAP minus live baro) with a FAST/SLOW poll-priority scheduler.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Boost = MAP − baro, computed from the two standard PIDs (OBD-14)
- [ ] Correct across three baro fixtures: sea level, 6k ft, 10k ft
- [ ] FAST/SLOW scheduler assigns poll priority per PID (e.g. MAP/RPM fast, baro/IAT slow) and is configurable
- [ ] Scheduler ordering is unit-tested (asserts poll sequence matches priority assignment over a simulated tick sequence)

## Self-test plan
Unit tests with the three baro fixtures asserting correct boost value; scheduler-ordering test.

## Out of scope
Real-world poll-rate tuning against hardware latency (Sprint 3); UI display of boost (OBD-10 already covers the tile — this issue only provides the value).
