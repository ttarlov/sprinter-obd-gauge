---
issue: OBD-86
round: 1
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: 9158629
covers: [OBD-86]
hardware-verify: false
---

# OBD-86 review — round 1 (rev-correctness, combined lens)

Bottom line: **approved.** This is a small, disciplined swap-only mirror addition, built exactly
on the `SPEED_PID_DEFINITION`/OBD-61 template. The app-side `ENGINE_LOAD_PID_DEFINITION` matches
`:core:protocol`'s `PidRegistry.engineLoad` on every axis that matters (id, PID `0104`, unit,
poll priority), is pinned by a real `testProd` parity assertion, lands only in `GAUGE_CATALOG`
(never `DASHBOARD_PIDS`), carries no threshold-seed entry, and the tests that used `engineLoad`
as the canonical "decoded but undisplayed" example were correctly moved onto `throttle`, which I
independently confirmed is still absent from the catalog. Gate is green on a from-scratch clean
rebuild, not a cached paste. No protocol/decode change, no new dependency, diff is 100% under
`/app/`.

`issues/OBD-86.md` is `hardware-verify: false` — PID `0104` was already verified at the protocol
layer (~56% observed on this van, `docs/hardware/session-2026-08-12.md`); this issue is pure UI
surfacing. Taras's device smoke (add the Engine Load tile via "+", confirm live %) remains the
real acceptance but does not gate the merge.

---

## Gate (re-run from scratch, not trusted)

Ran `tools/gate.sh` twice: once as pasted-equivalent, then again after `./gradlew clean` to rule
out a stale/cached false-green:

```
PASS  assembleDebug
PASS  test
PASS  ktlintCheck
PASS  detekt
PASS  verifyRoborazzi
PASS  assembleDemoDebug
PASS  module-isolation (sanity)
GATE: PASS
```

Also force-reran the specific new/changed test classes with `--rerun-tasks` (bypassing
Gradle's UP-TO-DATE cache entirely) to confirm they actually execute and pass, not just report
cached green:

```
GaugeCatalogProdParityTest   1 test,  0 failures
DisplayUnitDataSourceTest    8 tests, 0 failures
GaugeCatalogTest            18 tests, 0 failures
ProdChainEndToEndTest        8 tests, 0 failures
```

No sign of the known flaky `ObdConnectionServiceTest` (OBD-71 territory) in either run — clean
both times, no re-run-in-isolation needed.

---

## What I verified

- **The mirror is faithful — id, PID, unit, and priority all match the protocol source of truth.**
  `ENGINE_LOAD_PID_ID = "engineLoad"` (`GaugeCatalog.kt:82`) equals `ProtocolPidIds.ENGINE_LOAD`
  (`ProtocolPidIds.kt:56`), pinned by a real assertion, not a comment:
  `GaugeCatalogProdParityTest.kt:16` — `assertEquals(ProtocolPidIds.ENGINE_LOAD, ENGINE_LOAD_PID_ID)`.
  `ENGINE_LOAD_PID_DEFINITION` (`GaugeCatalog.kt:98-107`) declares `request = StandardPid(mode=1,
  pid=0x04)` (→ `0104`), `unit = MeasurementUnit.PERCENT`, `verified = true` — all matching
  `PidRegistry.engineLoad` (`core/protocol/.../PidRegistry.kt:269-278`: `pid = ENGINE_LOAD_PID`
  = `0x04`, `unit = PERCENT`, `parse = VendoredSaeScaling.percent(...)` i.e. `A×100/255`).
  **Poll priority: `PollPriority.FAST` on both sides** (`GaugeCatalog.kt:104` vs.
  `PidRegistry.kt:276`) — I checked this explicitly against your instruction to confirm it
  mirrors the protocol rather than a guess, since `issues/OBD-86.md`'s own "Polling" section
  text says "SLOW-priority per the registry," which is simply wrong — the registry is FAST, and
  the builder correctly matched the *code*, not the issue file's incorrect prose. Worth a
  one-line fix to the issue file for posterity, but not something that should block or reflect on
  this branch.
  The app-side placeholder `parse = { ENGINE_LOAD_UNUSED_PARSE_RESULT }` (always `0.0`) is not a
  decode bug — it's the established pattern for every swap-only catalog entry (`RPM_PID_DEFINITION`,
  `SPEED_PID_DEFINITION` do the same, per their own KDoc: "the real decode lives in
  `:core:protocol`"; `FakeVehicleDataSource` replays by id and never calls this lambda in demo,
  and prod's real decode comes from `PidRegistry` via `RealVehicleDataSource`). Consistent, not new
  risk.

- **Swap-only, not a default tile — confirmed both ways.** `GAUGE_CATALOG = DASHBOARD_PIDS +
  RPM_PID_DEFINITION + SPEED_PID_DEFINITION + ENGINE_LOAD_PID_DEFINITION` (`GaugeCatalog.kt:114-115`);
  grepped `DASHBOARD_PIDS` and confirmed engine load is not in it. `GaugeCatalogTest.kt:45-49`
  (new) asserts `ENGINE_LOAD_PID_ID !in DASHBOARD_PIDS_BY_ID` and `... in GAUGE_CATALOG_BY_ID`
  directly. `candidateGaugesFor`/`addableGaugesFor` tests updated to include it in the expected
  swap/add-palette sets (`GaugeCatalogTest.kt:166,183,193`).

- **Neutral % readout — no threshold coloring leak.** `ThresholdConfig.seed` (`ThresholdConfig.kt:73`)
  has no `engineLoad` entry; `classify` defaults absent ids to `NEUTRAL`
  (`ThresholdConfig.kt:94`), pinned by the new
  `` `OBD-86 engine load is declared in PERCENT, verified, and classifies NEUTRAL` `` test
  (`GaugeCatalogTest.kt:51-64`). Gauge sweep range: `GaugeScaleDefaults.seed` (`GaugeScale.kt:34-41`)
  has no entry either, so `GaugeScaleDefaults.forId` (`GaugeScale.kt:47`) falls back to the generic
  `FALLBACK` scale (`0.0–100.0`, tick `10.0`) — exactly the "sensible 0–100 range" the issue calls
  for, and consistent with how boost/speed behave before they get a seeded scale.

- **The moved out-of-catalog tests are genuinely still valid.** `DisplayUnitDataSourceTest.kt`'s
  "channels the app has no gauge for" case now uses `ProtocolPidIds.THROTTLE` instead of
  `ENGINE_LOAD` (`DisplayUnitDataSourceTest.kt:99-113`), asserting `THROTTLE !in
  GAUGE_CATALOG_BY_ID` — I grepped `app/src/main/kotlin/.../gauge/` for any `throttle`/`THROTTLE`
  reference and found none outside an unrelated OS-scan-throttle comment in
  `DashboardViewModel.kt`, confirming throttle really is still absent from the catalog, not just
  assumed. Same check for `SettingsCodecTest.kt`'s synthetic "5th catalog entry" stand-in, renamed
  `ENGINE_LOAD` → `UNDISPLAYED_CHANNEL` and repointed at `id = "throttle"`
  (`SettingsCodecTest.kt:392-405`) — the test's actual assertions (`reconciled.any { it.id ==
  UNDISPLAYED_CHANNEL.id }` must stay false) are unchanged in shape, only the stand-in id moved,
  which is exactly right since the premise ("this id isn't real catalog membership") still holds
  for throttle but no longer holds for engineLoad.

- **Catalog-count assertions — updated correctly, nothing weakened.** `GaugeCatalogTest.kt:17-20`'s
  set-equality assertion grew to include `ENGINE_LOAD_PID_ID`; the stable-ribbon ordering tests
  (`GaugeCatalogTest.kt:166-186`) were updated with the new trailing index and an added assertion
  that engine load's candidate index sits *after* the current gauge (consistent with it being
  appended last to `GAUGE_CATALOG`). `GaugeSwapPickerTest.kt`'s page-count doc comments and
  addable-set expectations were updated in the same spirit — I compared old vs. new set contents
  line by line; every assertion got strictly more specific (added an id), none got dropped or
  loosened.

- **Roborazzi — only `gauge_add_palette.png` re-recorded, and that's the right and only shift.**
  I read `GaugePickerScreenshotTest.kt`: `gauge_picker_mode.png` captures the swap pager
  immediately after opening it on `coolant` — the pager starts on the *current* gauge's page, so
  a third catalog-only candidate existing elsewhere in the ribbon doesn't change what's rendered
  in that first frame. `gauge_add_palette.png`, by contrast, renders the full addable-gauges list
  as mini-cards (`add palette open, landscape` test) — that set grew from `{rpm, speed}` to
  `{rpm, speed, engineLoad}`, so a re-record there is exactly expected and nothing else should
  have changed. `verifyRoborazzi` is green against the single re-recorded ref, confirming no other
  screenshot silently drifted.

- **No protocol/decode change, docs accurate, isolation clean.** `git diff main...HEAD --name-only`
  touches only `app/MODULE.md` and files under `app/src/...` — no `core/protocol` or `core/ble`
  file in the diff, no `build.gradle*` change, `module-isolation` gate PASS. `app/MODULE.md`'s
  three updated passages (display-unit passthrough list, e2e test description, "decoded but never
  displayed" list) all correctly read `throttle`/`map`/`iat` as the remaining undisplayed set and
  credit engine load's move to OBD-86 — matches the code.

## Fix list

- ✅ No blockers, no majors.
- ✅ (nit, non-blocking) `issues/OBD-86.md`'s "Polling" section says engine load is "SLOW-priority
  per the registry" — the registry actually has it as `FAST` (`PidRegistry.kt:276`), and the
  builder correctly implemented `FAST` rather than the issue text's stated value. Worth a one-line
  fix to the issue file so it doesn't mislead a future reader; not a reason to hold this branch.

No required changes. Merge when the queue reaches it.
