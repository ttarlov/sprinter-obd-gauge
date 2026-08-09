# :core:ble

Android library. Depends on `:core:model` only. Hilt annotations are permitted here per
`DECISIONS.md` D2 (the only `core` module besides `:app`).

## Public surface

Empty at Sprint 0 beyond the module shell (namespace `com.revel.obdgauge.ble`, minSdk 26,
consumer ProGuard rules, an intentionally empty manifest). This module implements `ObdLink`
over Android BLE/GATT: permission flow, filtered scanner, GATT serial emulation with
fragmented-response reassembly, reconnect state machine, and the `connectedDevice`
foreground service. See `docs/01-build-plan.md` §2C (Agent 2C) for the full spec; work
starts in Sprint 2b (OBD-17…19).

## Known limitations

- No implementation yet — scaffold only.
- `AndroidManifest.xml` declares no permissions or components yet; `BLUETOOTH_SCAN` /
  `BLUETOOTH_CONNECT` and the foreground service declaration land with OBD-17/18/24.
- No protocol knowledge belongs here, ever — this module moves strings, nothing else
  (`docs/01-build-plan.md` §2C).
