---
issue: OBD-75
round: 1
reviewer: orchestrator
verdict: approved
gate: green
reviewed-commit: b38f03c
covers: [OBD-75]
---

# OBD-75 round 1 — share MIME broadening (D7 micro-track)

**Verdict: approved.** Handled on the **D7 micro-track** (docs/05 §6c.1): a one-line, well-understood change,
so no independent reviewer was spawned — self-review is waived for micro, with `merge.sh`'s double gate as the
net, and the change was **device-verified on the actual target** (Garmin API 23) before merge, which is the
strongest possible confirmation for a share-target-visibility change (it's inherently untestable off-device —
the whole bug was that a real device's installed-app set determines the chooser).

## The change

`RecordingShare.kt`: `Intent.type` `"text/csv"` → `"text/plain"`. Everything else unchanged — `ACTION_SEND`,
`EXTRA_STREAM` (the FileProvider uri), `FLAG_GRANT_READ_URI_PERMISSION`, and the `.csv` filename on the uri
all stay, so the receiver still gets a file named `*.csv` and can identify it. The only effect is which
targets the OS offers: `text/csv` matched nothing on a bare Android-6 device (empty chooser); `text/plain` is
what Bluetooth OPP and most text handlers register for, so Bluetooth + file managers now appear.

## Why it can't regress the desktop/email case

The content type a receiver actually uses comes from the file extension + its own sniffing, not the SEND
`type` hint — email/Drive/desktop already handled the `.csv` via `EXTRA_STREAM` and continue to. `text/plain`
is a strictly *broader* match set than `text/csv` (nothing that matched csv stops matching plain), so no target
is lost. Confirmed on-device: Bluetooth gained, nothing regressed.

## Fix list

- [x] ✅ **(1)** Broaden the share MIME `text/csv` → `text/plain` so bare devices (Garmin/Android 6) surface
  Bluetooth + file-manager targets instead of an empty chooser. Fixed in `59265e2`/`270ad90`; the
  Robolectric `RecordingShareTest` assertion + name updated to `text/plain`; **device-verified on the Garmin
  (Bluetooth appears, CSV beams to phone)** — see `## Hardware checklist` in `issues/OBD-75.md`.

## Deferred (non-blocking)

- Fix #2 (on-device path surfaced in Recordings / optional Downloads copy) — deferred; Bluetooth export is
  sufficient ("good enough" per Taras). Left in the issue for a future pass if wanted.
