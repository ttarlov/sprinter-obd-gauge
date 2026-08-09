---
id: OBD-32
title: Threshold preset profiles
module: app
owner: ui-agent
sprint: 4
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: ui/32-threshold-presets
---

## Feature
Threshold preset profiles, shipping "Loaded Revel — summer" as the default, with user overrides persisted separately from the preset.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Preset profile mechanism supports named threshold tables, selectable by the user
- [ ] "Loaded Revel — summer" ships as the default preset, applying the worry-threshold table from the build plan
- [ ] Applying a preset sets all thresholds at once
- [ ] User edits to individual thresholds (via OBD-21) persist independently of the active preset (not silently overwritten if the preset is reapplied)

## Self-test plan
Test applies the default preset and asserts all threshold values match the documented table; test edits one threshold post-preset and confirms it survives a settings reload.

## Out of scope
Building additional presets beyond the shipped default; per-vehicle profile switching.
