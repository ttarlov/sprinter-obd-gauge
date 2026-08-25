---
id: OBD-75
title: Log export on the Garmin — broaden share MIME (surface Bluetooth) + make the USB path first-class
module: app
owner: ui-agent
sprint: telemetry
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: feat/75-log-export
---

## What (observed on the Garmin, 2026-08-24)

OBD-70 logging works on the Garmin Overlander (records a valid CSV), but **tapping Share opens an empty
chooser** — no targets. This is the anticipated Android-6 limitation (the share sheet lists only apps
installed on the device, and the Overlander has nothing registered for `text/csv`), NOT a bug. But Share is
useless on Taras's PRIMARY device, so the "get the data off" path needs to actually work there.

## Fixes

1. **Broaden the share MIME type.** OBD-70 uses `type = "text/csv"`, which is narrow — most Android-6
   share handlers register for `text/plain` or `*/*`, not `text/csv`. Switching to `text/plain` (or `*/*`)
   should surface **Bluetooth share** (Garmin units typically have BT → beam the CSV to a phone — a real
   WIRELESS path off the Overlander) and any file-manager target, without breaking the desktop/email case
   (`.csv` extension + `EXTRA_STREAM` still carry the format). Keep `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_
   PERMISSION`. Cheap, highest-value change — try `text/plain` first, confirm targets appear on the Garmin.
2. **Make the USB/on-device path first-class** (the guaranteed fallback when the chooser is still thin):
   - Surface the exact on-device file path in the Recordings screen (or a "how to get logs off" line), so
     it's findable over USB-MTP without guessing (`Android/data/com.revel.obdgauge.app/files/logs/`).
   - Optional (weigh the cost): also drop a copy in a plainly-visible shared location (e.g. `Downloads/` via
     `MediaStore` / `Environment.DIRECTORY_DOWNLOADS`) so a file manager sees it at top level. On minSdk 23
     this needs `WRITE_EXTERNAL_STORAGE` (a runtime permission — added prompt + API-23 handling), so gate it
     behind a setting or only do it on export, don't make it the default write path. App-specific
     `getExternalFilesDir` stays the primary (no permission).

## Testing

- Robolectric: assert the share `Intent` type is the broadened value + still carries `EXTRA_STREAM` +
  grant flag; FileProvider uri resolves.
- Device (🖐 Taras — the real gate): on the **Garmin**, Share now shows at least Bluetooth / a file manager;
  a CSV successfully beams to the phone over BT and opens; the on-device path is discoverable for USB pull.

## Out of scope

- Cloud upload / auto-sync (still explicitly out — OBD-70's boundary).
- A built-in web server / self-hosted download from the app.
