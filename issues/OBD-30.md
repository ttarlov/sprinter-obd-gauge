---
id: OBD-30
title: Export CSV and GPX
module: app
owner: telemetry-agent
sprint: 4
status: open
type: feature
hardware-verify: false
blocked-by: [OBD-28]
branch: telemetry/30-export-csv-gpx
---

## Feature
Export a logged drive as CSV (readings) and GPX (track) via the Storage Access Framework.

## Contract surface
None expected.

## Acceptance criteria
- [ ] CSV export includes all logged readings with timestamps, in a format that opens cleanly in a spreadsheet
- [ ] GPX export includes the logged track (location/elevation) and opens correctly in a standard maps app
- [ ] Export uses SAF (user picks destination), no raw filesystem paths
- [ ] Round-trip test: exported CSV re-imported/parsed matches the original logged values

## Self-test plan
CSV round-trip parse test; manual GPX-opens-in-maps-app check.

## Out of scope
Drive logging itself (OBD-28); chart rendering (OBD-29).
