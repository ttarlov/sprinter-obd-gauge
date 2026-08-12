---
id: OBD-43
title: Standard PIDs — engine load (0104) and throttle position (0111)
module: core/protocol
owner: protocol-agent
sprint: backlog
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: protocol/43-49-pids-kwp
---

## Feature
Two additional standard mode-01 PIDs in the registry so OBD-42's swap catalog has real
engine-load and TPS gauges: 0104 (calculated load, A×100/255 %) and 0111 (throttle
position, A×100/255 %).

## Contract surface
None. Registry additions per the OBD-14 pattern (vendored/SAE cross-checked scaling,
boundary tests, PERCENT unit).

## Acceptance criteria
- [x] Both PIDs in PidRegistry with SAE-verified scaling + boundary tests (0x00→0, 0xFF→100)
- [x] Parser fixtures; property tests still green (plausible-range table extended to both)
- [x] Ids follow the PidIds naming convention; PollPriority chosen and justified (FAST — both are
      pedal-rate signals; a SLOW load reading beside a live boost needle would disagree with it)

## Delivered beyond the brief (from the 2026-08-12 survey)
- Registry truth: coolant/rpm/load/throttle/speed/baro live-confirmed; `010B` MAP and `010F` IAT
  captured `NO DATA` and recorded as NOT SUPPORTED on this vehicle.
- `ChannelAvailability` + `PidCatalog.availabilityOf` + `PollEvent.ChannelAvailabilityChanged`:
  a second axis alongside `PidDefinition.verified`, so the MAP-less boost channel degrades to a
  typed unavailable state naming its missing input — never a silent 0 PSI.
- `0111` diesel caveat documented and pinned by a regression test (~83 % at warm idle is the
  intake flap, not the pedal).

## Self-test plan
Same harness as OBD-14: FakeObdLink transcripts + exact-value tests.

## Out of scope
UI (OBD-42); fake-source channels for these (add when demo needs them).
