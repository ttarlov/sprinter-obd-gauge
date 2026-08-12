---
id: OBD-27
title: Unverified-PID UX
module: app
owner: ui-agent
sprint: 3
status: changes-requested
type: feature
hardware-verify: false
blocked-by: []
branch: ui/24-27-service-badge
---

## Feature
A visual badge and raw-response viewer for unverified PIDs, so a failed or unconfirmed mode-22 gauge informs the driver instead of silently showing a plausible-looking wrong number.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Gauges backed by a PID with `unverified == true` (per OBD-15's flag) show a visually distinct badge/treatment
- [ ] Tapping an unverified gauge opens a raw-response viewer showing the last raw frame received for that PID
- [ ] Verified gauges show no badge (normal treatment)
- [ ] Compose test covers both states (verified vs unverified rendering) and the tap-to-view-raw interaction

## Self-test plan
Compose UI test asserting badge presence/absence by the `unverified` flag and raw-viewer content on tap.

## Out of scope
Actually verifying PIDs (that's OBD-26, a hardware/human activity — this issue only builds the UX for whatever verification state exists).

## Note from OBD-15 (2026-08-11)
The unverified flag is surfaced via `PidCatalog.isVerified(id)` in :core:protocol (the frozen `Reading` contract carries no such field — documented AC deviation, reviewer-accepted). Build the badge against that.
