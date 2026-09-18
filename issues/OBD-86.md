---
id: OBD-86
title: Surface engine load (PID 0104) as a selectable dashboard gauge
module: app
owner: ui-agent
sprint: telemetry
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: feat/86-engine-load-gauge
---

## What (Taras, 2026-09-18)

> "Do we have a PID identified for engine load?" → Yes. Wire it up as a gauge.

Engine load is already **identified, decoded, and verified** at the protocol layer — standard PID
`0104`, `A × 100 / 255` %, confirmed answering on this van (~56% observed, `PidRegistry.engineLoad`,
`ProtocolPidIds.ENGINE_LOAD`). It is deliberately **"decoded but never displayed"**: `GAUGE_CATALOG` is
pinned and `engineLoad` (with `throttle`/`map`/`iat`) has no dashboard tile, so the app doesn't poll or
show it. This issue only **surfaces** it — no protocol/identification work needed.

## Scope (small — pure `:app` wiring)

- **Add engine load to `GAUGE_CATALOG`** (`app/src/main/kotlin/com/revel/obdgauge/app/gauge/GaugeCatalog.kt`)
  as a **swap-only** gauge — mirror the `rpm`/`speed` pattern (in `GAUGE_CATALOG` but NOT `DASHBOARD_PIDS`,
  so it's not a default tile but is offered by the swap carousel and the "+" add-palette). Add an app-side
  `ENGINE_LOAD_PID_DEFINITION` mirroring `:core:protocol`'s `engineLoad` spec, exactly like
  `SPEED_PID_DEFINITION` mirrors `ProtocolPidIds.SPEED`, and add the `testProd` **parity assertion** that
  pins the app id == `ProtocolPidIds.ENGINE_LOAD` so they can't drift.
- **Display:** plain **NEUTRAL** readout (no threshold bands — like boost), unit **%**, sensible gauge
  range **0–100**. Value already flows: `DisplayUnitDataSource` passes `engineLoad` through untouched
  (it's a unitless %), so no unit-conversion change.
- **Polling:** being in `GAUGE_CATALOG` makes `DashboardViewModel.start(GAUGE_CATALOG.activePids())` poll
  it automatically (FAST-priority per the registry) — no poll-loop change.

## The gotcha — tests that use engineLoad as the "not in catalog" example
`engineLoad` is currently the canonical **decoded-but-undisplayed** example in tests. Adding it to the
catalog breaks those premises — update them to use a channel that is STILL undisplayed (`throttle`,
`map`, or `iat`):
- `app/src/testProd/kotlin/.../datasource/DisplayUnitDataSourceTest.kt` (~L95–113): asserts
  `ProtocolPidIds.ENGINE_LOAD !in GAUGE_CATALOG_BY_ID` and uses it as a passthrough example — swap to
  `throttle`/`map`.
- `app/src/test/kotlin/.../settings/SettingsCodecTest.kt` (~L307–322): builds a "five-gauge catalog +
  ENGINE_LOAD" as an out-of-catalog reconcile case — swap the out-of-catalog id to a still-undisplayed one.
- Update `app/MODULE.md` notes (L155/L173/L924): engine load is now displayed; the decoded-but-not-
  displayed set becomes `throttle`/`map`/`iat`.

## Testing
- The new parity test (app engine-load id == `ProtocolPidIds.ENGINE_LOAD`).
- Existing swap/add-palette + catalog tests still green with engine load now present; the moved
  out-of-catalog test cases (above) green against their new channel.
- Roborazzi: if a swap-carousel/add-palette or catalog screenshot ref includes the gauge list, record the
  new engine-load entry (`recordRoborazziDemoDebug`).
- `tools/gate.sh` green.
- Device (🖐 Taras, non-gating smoke): add the Engine Load tile via "+"/swap → shows live % on the van.
  `hardware-verify: false` (PID already verified; this is UI surfacing).

## Out of scope
- Threshold bands / amber-red load zones (neutral readout for now; trivial follow-up if wanted).
- Surfacing `throttle`/`map`/`iat` (still decoded-but-undisplayed by choice).
- Any protocol/decode change (engine load is already verified).
</content>
