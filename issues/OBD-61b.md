---
id: OBD-61b
title: Fix — ACCESS_FINE_LOCATION capped at maxSdkVersion=30 disables GPS speed on Android 12+
module: app
owner: orchestrator
sprint: ad-hoc
status: merged
type: bug
hardware-verify: false
blocked-by: []
branch: fix/61b-location-maxsdk
---

## Bug (caught in OBD-61 merge verification, before install)
The merged prod manifest declared `ACCESS_FINE_LOCATION` with `maxSdkVersion="30"` — inherited from
a BLE dependency's manifest (the legacy pre-Android-12 "BT scan needs location" rule). The app's own
uncapped declaration did not override it (manifest-merger keeps the library cap unless explicitly
removed). Effect: on Android 12+ (SDK 31+) — i.e. every phone this ships to, including the Pixel —
the GPS permission is not requestable, so the OBD-61 speed-correction feature would be silently dead.
Gate did not catch it (no test exercises the merged manifest's SDK ceiling on a real device).

## Fix
`app/src/main/AndroidManifest.xml`: declare `xmlns:tools` and add
`tools:remove="android:maxSdkVersion"` to the `ACCESS_FINE_LOCATION` uses-permission, stripping the
inherited cap. Verified: the merged prod manifest now emits
`<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` with no maxSdkVersion.

## Done when
Gate green; merged manifest uncapped; van APK rebuilt at merge head.
