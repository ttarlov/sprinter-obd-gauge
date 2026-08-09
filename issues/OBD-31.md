---
id: OBD-31
title: Threshold alerts with hysteresis
module: app
owner: ui-agent
sprint: 4
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: ui/31-threshold-alerts
---

## Feature
Audible/notification alerts fire when a gauge crosses its configured threshold (e.g. EOT crossing 235), with hysteresis so a single excursion doesn't spam repeated alerts.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Alert (audible and/or notification) fires when a reading crosses a configured threshold
- [ ] Hysteresis prevents re-firing on noise around the threshold (fires once per excursion, not once per sample)
- [ ] Alert clears/resets once the reading returns below the threshold minus the hysteresis band
- [ ] Test covers hysteresis behavior explicitly — oscillating values near the threshold produce exactly one alert

## Self-test plan
Unit test with a synthetic reading stream oscillating around the threshold, asserting single-fire behavior.

## Out of scope
Threshold values themselves (configured via OBD-21/OBD-32); notification-channel setup polish beyond functional firing.
