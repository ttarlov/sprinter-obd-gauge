---
id: OBD-40
title: User-defined PID gauges (add gauges from inside the app)
module: app
owner: ui-agent
sprint: backlog
status: open
type: feature
hardware-verify: false
blocked-by: [OBD-15, OBD-21, OBD-27]
branch: ui/40-user-defined-pids
---

## Feature
A user can define a new gauge from inside the app — entering an X-Gauge-style code
(TXD/RXF/RXD/MTH or the app's header/filter/request form), a label, unit, and thresholds —
and it appears as a live tile without a code change.

## Contract surface
None expected. `ObdRequest.Mode22` already models header/rxFilter/request as data;
`PidDefinition` is constructible at runtime. Parsing/scaling from user-entered
RXD/MTH-style byte-extraction + linear-scaling input needs a small interpreter in
`:core:protocol` — coordinate with protocol-agent; if it wants a new shared type, that is a
contract-change gate first.

## Acceptance criteria
- [ ] "Add gauge" flow in settings: code entry (X-Gauge TXD/RXF/RXD/MTH format AND raw
      header/filter/request form), label, unit, poll priority, optional thresholds
- [ ] User-defined definitions persist (DataStore, same store as OBD-21 settings) and
      survive app restart
- [ ] User-defined gauges render as normal tiles, ordered/removable like built-ins (OBD-21)
- [ ] Every user-defined gauge is born `unverified` and carries the OBD-27 badge +
      raw-response viewer until manually marked trusted by the user
- [ ] Malformed codes produce a clear inline error, never a crash or a silent dead tile
- [ ] Seed catalog: the OBD-41 database (once it exists) is offered as a picker of known
      codes so users start from mapped ones instead of typing blind

## Self-test plan
JVM tests for the code-entry parser (property test: never throws on garbage input);
Compose test adding a gauge against `FakeVehicleDataSource` with a scripted extra channel;
persistence round-trip test.

## Out of scope
Discovering which codes are valid for a given vehicle (that is OBD-41); any CAN write
operations — user-defined requests are read-only mode-01/mode-22 queries.
