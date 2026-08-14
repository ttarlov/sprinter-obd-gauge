---
issue: OBD-61b
round: 1
reviewers: [orchestrator micro-track (D7)]
verdict: approved
gate: green
reviewed-commit: aea9a4a
covers: [OBD-61b]
---

Micro-track manifest fix. Verified directly against the merged manifest.

## Fix list
- [x] ✅ Root cause confirmed: a BLE dependency imposes `maxSdkVersion="30"` on ACCESS_FINE_LOCATION;
      the app's uncapped declaration did not override it, so GPS was dead on Android 12+.
- [x] ✅ Fix verified against the built artifact: `:app:processProdDebugManifest` emits
      `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` with NO
      maxSdkVersion after adding `xmlns:tools` + `tools:remove="android:maxSdkVersion"`.
- [x] ✅ Scope is one manifest declaration; no logic/test churn. Gate green at aea9a4a.

Gate: PASS (all seven checks) at aea9a4a.
