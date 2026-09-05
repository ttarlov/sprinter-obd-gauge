---
issue: OBD-79
round: 1
reviewer: rev-correctness
verdict: approved
gate: green
reviewed-commit: 08038b8
covers: [OBD-79]
hardware-verify: false
---

# OBD-79 — maintenance tracker foundation + manual tracker — round 1

**Verdict: approved.** Zero blockers, zero majors. This is a cleanly-fenced second section of the
app: a pure, well-tested interval/status computation; a Room catalog/record store that seeds once
and never clobbers edits; five additive, backward-compatible DataStore mileage fields behind a
single `currentOdometerMiles` accessor; and a stateless list/detail UI wired the same
Route/Screen/ViewModel way Settings already is. No OBD/BLE/notification scope leaked in. Gate
**GREEN** — I re-ran `tools/gate.sh` myself (not the builder's paste): PASS on all 7 steps
(`assembleDebug`, `test`, `ktlintCheck`, `detekt`, `verifyRoborazzi`, `assembleDemoDebug`,
`module-isolation`).

## What I verified (correctness-first)

1. **Interval/status math is correct across every branch** (`maintenance/MaintenanceStatus.kt`).
   Traced `forItem` by hand:
   - `!enabled` → DISABLED short-circuits everything; `latestRecord == null` → NEVER_SERVICED
     (even a no-interval KNOWN_ISSUE watch item — "never logged" is the surfaced fact); both
     branches return `NONE` trigger. `MaintenanceStatus.kt:79-85`.
   - `milesUntil = record.odometer + intervalMiles − current`; `daysUntil = (record.date +
     intervalMonths) − today` via `LocalDate.plusMonths` (real calendar months, not 30-day
     approximation). `:94-100`.
   - `levelFor`: `remaining <= 0` → OVERDUE, `<= window` → DUE_SOON, else OK. Due-today / due-at-
     exact-mile reads OVERDUE — a defensible product choice, not an off-by-one.
   - Whichever-nearer: independent per-trigger levels, overall = higher `severity` (OVERDUE 2 >
     DUE_SOON 1 > OK 0); miles-only/time-only set the other level `null` and pick the present
     trigger; a **severity tie deterministically prefers MILES** (`severity(days) > severity(miles)`
     is the only path to TIME). `:107-129`. Traced the reverse case (miles overdue, time fine →
     MILES) and the tie case (both DUE_SOON → MILES) — both match.
   - Tests **pin real boundaries**, not just happy paths: miles 200-remaining=DUE_SOON /
     -500=OVERDUE with exact `milesUntil` asserted; a 12-month time-overdue; both whichever-first
     directions; the MILES tie-break with `daysUntil==20`/`milesUntil==300` both asserted; all
     three never-serviced variants incl. the logged-watch-item→OK-with-NONE case. `MaintenanceStatusTest.kt`.
2. **Room seeds exactly once and never clobbers edits.** Guard is an **empty-table check**
   (`dao.serviceItemCount() == 0`), not a flag — `RoomMaintenanceRepository.kt:25-29`. Items are
   never deletable in the UI (only records cascade-delete), so the table can't re-empty and
   re-seed. `MaintenanceRepositoryTest` proves both: re-open (fresh repo over same DB) → no dupes,
   AND a user edit to a seeded item survives a second `seedIfNeeded()`. `latestRecords()`'s
   INNER-JOIN-on-MAX(date) is correct and its arbitrary same-day tie-break is immaterial (status
   reads only date+odometer). `MaintenanceCategory` persists as its Room-native enum-name string;
   the update-then-reload equality test (which includes `category`) passes, confirming round-trip.
   `version = 1`, `exportSchema = false`, **no `fallbackToDestructiveMigration`** — correct for a
   brand-new table with no shipped install to migrate. Room `2.7.2` is compatible with Kotlin
   `2.1.21` / KSP `2.1.21-2.0.2` / AGP `8.13.2` — proven by the green build, not just asserted.
3. **`@MaintenanceClock` is real and necessary — claim VERIFIED.** The demo flavor's unqualified
   `provideClock` returns `source.epochSinceStartClock()`
   (`src/demo/.../di/DataSourceModule.kt:60`) — an epoch-since-start fake aligned to the fake
   replay timeline, so "today" would be ~1970 and every countdown wrong. Prod's is
   `Clock.systemDefaultZone()`. Maintenance's own `@MaintenanceClock` binding lives in the
   **flavor-common** `MaintenanceModule` (`src/main`), so both variants get a real wall clock and
   neither collides with the unqualified `Clock` (distinct qualifier → distinct Hilt key). Both
   flavor graphs compiled (`assembleDebug` + `assembleDemoDebug` both green). Testability is
   **not** reintroduced: the status math takes `today: LocalDate` directly (pure tests supply a
   fixed date), and the ViewModel injects `Clock` by constructor, so a VM test can still pin it.
4. **DataStore fields are additive and backward-compatible** (`AppSettings.kt`, `SettingsCodec.kt`).
   Five new fields, each decoded with its own `?: default` (the odometer step split into
   `applyOdometerAnchor` only to stay under detekt's complexity cap); `accumulatedSinceAnchorMiles`
   also guards `isFinite()`. The pre-existing `empty preferences decode to AppSettings defaults`
   test means an **existing persisted settings blob with none of these keys decodes cleanly to the
   defaults** — no decode break for current installs. New round-trip test asserts all five fields
   plus `currentOdometerMiles`; default-on-missing test asserts all five plus the accessor → 0.
   `currentOdometerMiles = odometerAnchorMiles` only (OBD-80's accumulated term plumbed, defaulted,
   unused) — the clean single-accessor swap the issue requires.
5. **Module isolation clean.** `grep` for `core.ble` / `core.protocol` / `core.testing` (and the
   `com.revel.obdgauge.{ble,protocol,testing}` package forms) across all new maintenance source +
   the touched `AppSettings.kt`/`SettingsCodec.kt`: **zero matches**. Room deps are
   `:app`-local (`implementation(room.runtime/ktx)`, `ksp(room.compiler)`). `module-isolation`
   gate step PASS.
6. **The ~40 re-recorded dashboard PNGs are a legitimate, expected shift.** The wrench `TextButton`
   was added to the always-visible header `Row` beside the gear (`DashboardScreen.kt:552-555`),
   where `ConnectionBanner` holds `weight(1f)` and the buttons are fixed-width. A new permanent
   fixed-width sibling necessarily narrows the banner's weight share — the *exact* mechanism the
   file's own OBD-67 round-6 KDoc documents (`:520-533`) as rippling into every dashboard
   reference. Expected layout change, not a masked regression; new `maintenance_list.png` /
   `maintenance_detail.png` are net-new refs. `verifyRoborazzi` green.
7. **Scope is honestly bounded; nav is clean.** No OBD read, no notifications. `MainActivity`
   promotes `showSettings: Boolean` → `enum Screen { DASHBOARD, SETTINGS, MAINTENANCE }`; `when` is
   exhaustive over all three, each route's `onBack` returns to DASHBOARD, no dead/unreachable state
   (`MainActivity.kt:170-202`). List↔detail is local `mutableStateOf<Long?>` in `MaintenanceRoute`.
8. **722.9 seed is intended, flagged, one-tap-editable.** `Ncv3Om642Seed.kt` seeds the trans item
   with the 722.9 assumption in `specNotes` (`SPEC_TRANS`, ⚠️-flagged, per Open item #1); fuel
   filter and 4x4 driveline items carry their low-confidence/RWD notes too. Every field is
   editable on the detail screen. Correct per the issue.

## Notes (non-blocking, no action required)

- **N1 — every keystroke commits to the DB.** `EditableItemFields`/`OdometerReadout` call
  `onSave`/`onSetOdometer` on each `onValueChange`, so editing a part-number string writes a Room
  row (or a DataStore txn) per character. Functionally correct and a deliberate match of
  `SettingsScreen.kt`'s existing hand-rolled `ThresholdField` idiom (referenced in the KDoc). Not
  worth diverging from the established pattern here. ✅ acceptable.
- **N2 — system-back from Maintenance/Settings has no `BackHandler`.** Neither `MaintenanceRoute`
  nor the pre-existing `SettingsRoute` registers one, so the Android back gesture falls through to
  the Activity default rather than returning to the dashboard; the in-screen `< Back` affordance is
  the intended path. This exactly mirrors the already-shipped, already-approved `SettingsRoute`
  behavior — OBD-79 introduces no regression and the issue's nav requirement (enum promotion,
  onBack, no dead states) is met. ✅ acceptable / consistent with the established pattern.
- **N3 — DUE_SOON window edges (exactly 500 mi / 30 days) aren't individually pinned.** Tests hit
  values comfortably inside each band (200 mi, 20 days) rather than the exact `<= window` boundary.
  The classifier is trivial and every OK↔DUE_SOON↔OVERDUE transition is covered; adding the two
  exact-edge cases would be belt-and-suspenders, not a gap that could hide a bug. ✅ acceptable.

## Fix list

- ✅ Interval/status math (miles-only, time-only, whichever-first both directions, MILES tie-break,
  all never-serviced variants, disabled, overdue) — correct and boundary-pinned by tests.
- ✅ Room seed-once guard (empty-table check) — no dupes on re-open, no clobber of user edits; both
  proven by test. `version=1` / no destructive-migration is correct for a new table.
- ✅ `@MaintenanceClock` qualifier — verified the demo fake-clock hazard is real, binding is
  flavor-common and correct on both flavors, status/ViewModel remain testable.
- ✅ DataStore 5 additive mileage fields + `currentOdometerMiles` accessor — per-field
  default-on-missing, real round-trip test, backward-compatible decode of existing blobs.
- ✅ Module isolation — no `:core:ble`/`:core:protocol`/`:core:testing` imports anywhere in new code.
- ✅ Re-recorded dashboard PNGs — legitimate header-width shift, not a masked regression.
- ✅ Scope + nav — no OBD/BLE/notification leak; `Screen` enum promotion clean, no dead states.
- ✅ 722.9 vs 722.6 seed — intended, ⚠️-flagged low-confidence, one-tap editable.
- ✅ N1/N2/N3 above — non-blocking observations, no change required.

## Tests

- Pure JUnit: `MaintenanceStatusTest` (10 cases, boundary-pinned), `SettingsCodecTest` OBD-79
  round-trip + default-on-missing.
- Robolectric: `MaintenanceRepositoryTest` (CRUD + seed-once + no-clobber + latest-per-item) against
  an in-memory Room DB.
- Roborazzi: `maintenance_list.png` (chips across OK/due-soon/overdue/never-serviced/disabled, real
  `forItem` output) + `maintenance_detail.png` (editable part numbers). `verifyRoborazzi` green.

## Hardware checklist

`hardware-verify: false` — no OBD/BLE path in this issue; the issue's device smoke (dashboard →
Maintenance, log/edit/persist, set odometer, colors) is a non-gating 🖐 Taras check, not required
for merge.
