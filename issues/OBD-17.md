---
id: OBD-17
title: BLE permissions and scanner
module: core/ble
owner: ble-agent
sprint: 2
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: ble/17-18-scanner-gatt-bridge
---

## Feature
Android 12+ BLE runtime permission flow plus a filtered scanner with a remembered-device fast path.

## Contract surface
None expected.

## Acceptance criteria
- [ ] Runtime permission request flow implemented for Android 12+ (BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
- [ ] Scanner filters by known service UUID candidates (OBD dongle profile) rather than scanning all BLE devices
- [ ] Remembered-device fast path: if a previously-bonded/known device is available, skip full scan and connect directly
- [ ] State machine (permission → scan → found/not-found) is unit-tested
- [ ] Permission-denial paths emit a typed `LinkState.Error`, not a crash or silent hang

## Self-test plan
Unit tests against a fake permission/scan state machine; denial-path tests assert typed error emission.

## Out of scope
GATT connection/serial bridge (OBD-18); debug console (OBD-19); reconnect backoff logic (OBD-23).
