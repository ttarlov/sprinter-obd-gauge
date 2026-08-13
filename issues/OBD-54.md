---
id: OBD-54
title: App quit on reopen at the van — no crash trace captured (repro needed)
module: app
owner: ui-agent
sprint: backlog
status: open
type: bug
hardware-verify: false
blocked-by: []
branch: app/54-launch-quit-repro
---

## Bug (Taras, 2026-08-13, at the van)
Reopened the prod app; it quit immediately. No trace in logcat crash or main buffers when
checked shortly after. Prod-only (demo never showed it).

## Suspected area
The connect-on-launch path (MainActivity.connectIfRemembered + FGS start). OBD-24 review B1
flagged a possible FGS-type SecurityException on fresh start that Robolectric can't see;
this may be it manifesting on-device, or a connect-race on a warm remembered link.

## Repro plan
- adb logcat -b crash,main,system with the app; cold launch, warm launch, launch-while-
  already-connected (the state at the van: console had just held the link).
- Check FGS startForeground path + LinkController connect race.
## Acceptance
- [ ] Reproduced with a captured stack; root cause identified
- [ ] Fixed + regression test (Robolectric where possible; documented if device-only)
