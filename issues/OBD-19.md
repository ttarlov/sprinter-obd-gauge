---
id: OBD-19
title: Debug console screen
module: core/ble
owner: ble-agent
sprint: 2
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: ble/19-debug-console
---

## Feature
A debug-build-only console screen exposes a raw AT-command REPL over the live GATT link, for direct hardware interaction during bring-up.

## Contract surface
None expected on frozen `:core:model` contracts. Note: this screen lives under `:app` even though owned by ble-agent, since `/app/` is nominally ui-agent's `OWNERSHIP` path — flagged here for the merge-agent's module-isolation check as an intentional cross-boundary exception (debug-only tooling, not a UI feature).

## Acceptance criteria
- [ ] Debug-build-only screen (not present in release/demo/prod release builds)
- [ ] Text input sends raw AT commands over the live `ObdLink`/GATT bridge
- [ ] Response text displayed as received, including partial/fragmented responses
- [ ] Screen is reachable without any prior app setup beyond BLE permission grant

## Self-test plan
Manual verification is the acceptance test — human types ATZ and sees the dongle banner. This is the designated Sprint 2 hardware demo; no automated test substitutes for it.

## Out of scope
Init state machine wiring (already covered by OBD-13 — this screen bypasses it for raw REPL access); production debug tooling polish.
