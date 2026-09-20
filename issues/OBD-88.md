---
id: OBD-88
title: Filter computed instantMpg id out of the delegated poll list (drop cosmetic UnknownPid log)
module: app
owner: ui-agent
sprint: telemetry
status: open
type: bug
hardware-verify: false
blocked-by: []
branch: feat/88-instantmpg-poll-filter
---

## What (from OBD-87 review N1, 2026-09-19)

`instantMpg` is a computed channel synthesized at the `InstantMpgDataSource` app-layer decorator, not a
wire PID. But it's in `GAUGE_CATALOG`, so `ActivePollSet.activePids()` includes it and
`DashboardViewModel.start(...)` passes it down to `RealVehicleDataSource`, which doesn't know it (only
`PidIds.BOOST` has the "don't log unknown" carve-out) → **one cosmetic `PollEvent.UnknownPid("instantMpg")`
logged per session start.** Purely a logcat blemish — the id is correctly dropped from the actual wire
poll list (`mapNotNull` on `PidCatalog.byId`), no functional impact.

## Fix (in-`:app`, no `:core:protocol` change)
Have `InstantMpgDataSource.start(...)` filter its own `INSTANT_MPG_PID_ID` out of the pid list it
delegates downstream (it's the layer that synthesizes the id, so it should also strip it before the inner
source sees it). The existing pass-through test should still pass; add one asserting the delegated list
excludes `instantMpg`.

## Priority
Low / non-blocking cosmetic cleanup. Also fine to batch with OBD-85 (grid-test bounds) as a small
tidy-up wave.
