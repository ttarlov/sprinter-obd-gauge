---
id: OBD-79
title: Maintenance tracker + suggester — foundation & manual tracker
module: app
owner: ui-agent
sprint: maintenance
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: feat/79-maintenance-tracker
---

## What (Taras, 2026-09-04)

> "I want the app to also be a maintenance tracker and suggester… a section where I enter maintenance
> records like oil changes, tire rotations, air filter, fuel filter… see how many miles till next
> maintenance… enter details like oil type and part numbers… pre-filled based on research but
> adjustable."

A **second section** of the app for vehicle maintenance. This issue delivers the **whole feature working
on manual odometer entry** — no OBD dependency, so it's low-risk pure-app work. The **automatic
mileage advance from OBD** is a separate follow-on (**OBD-80**) that layers onto the manual anchor this
issue establishes. Full approved plan: `~/.claude/plans/rosy-skipping-eich.md`.

Push "service due" notifications are explicitly **deferred** (OBD-81) — this issue is **in-app
color-coded status + countdowns only** (Taras's decision).

## Why the mileage is manual here (context for OBD-80)

Deep research (committed context below) established: there is **no true-odometer read** over the cheap
ELM327 on this NCV3 — the lifetime odometer lives in the instrument cluster behind the EIS gateway,
reachable only via undocumented Mercedes proprietary addressing. Standard PID `01A6` (odometer) came in
~2019 and returns NO DATA here; PID `0131` (distance since codes cleared) **does** answer and is the
OBD-80 auto-advance signal. So the source of truth is a **manual odometer anchor** (this issue), which
OBD-80 auto-advances between entries. Build OBD-79 so the odometer value is read from a single
`currentOdometerMiles` accessor that OBD-80 can later back with the OBD estimate.

## Scope (build all of this)

### Persistence — Room (NEW dependency)
- Add `room-runtime`, `room-ktx`, `ksp(room-compiler)` to `gradle/libs.versions.toml` + `app/build.gradle.kts`
  (KSP plugin already applied for Hilt). Room is for the growing/queryable/editable data; keep it out of
  the DataStore `AppSettings` blob.
- **`ServiceItem` entity** (the catalog): `id`, `name`, `category` (ROUTINE / SCHEDULED / DRIVELINE /
  CONSUMABLE / KNOWN_ISSUE), `intervalMiles: Int?`, `intervalMonths: Int?`, `partNumbers: List<String>`
  (editable), `specNotes: String` (oil type / capacity / torque / fluid spec / confidence flags),
  `enabled: Boolean`, `isSeed: Boolean`.
- **`MaintenanceRecord` entity** (a logged event): `id`, `serviceItemId`, `performedDateEpochDay`,
  `odometerMiles: Int`, `notes: String?`, `costCents: Int?`, `partsUsed: String?`.
- **`MaintenanceDao`** returning `Flow<List<…>>`; **`MaintenanceRepository`** interface + Room-backed
  impl + Hilt `@Provides` for DB/DAO. Mirror `SettingsRepository` / `DataStoreSettingsRepository` /
  `settings/di/SettingsModule.kt` shape exactly (interface exposes Flows + suspend mutators).

### Mileage state — DataStore (additive `AppSettings` fields)
Small numbers, frequent tiny writes → DataStore, not Room. Additive fields on `AppSettings` +
`SettingsCodec` keys (per-field default-on-missing, matching the existing codec discipline):
`odometerAnchorMiles: Int`, `anchorAtEpochMillis: Long`, `anchorRefDistanceKm: Int` (the `0131` value at
anchor time — written by OBD-80, defaulted 0 here), `accumulatedSinceAnchorMiles: Double`,
`lastManualEntryEpochMillis: Long`. Expose `currentOdometerMiles` = `odometerAnchorMiles` for now (OBD-80
adds the accumulated term). Manual "Set odometer" writes `odometerAnchorMiles` + stamps
`lastManualEntryEpochMillis`.

### Seed catalog — `maintenance/seed/Ncv3Om642Seed.kt`
Code-defined default list, inserted **once** on first DB create (guard on empty table / a
`maintenance_seeded` DataStore flag). Every field editable in the UI; part numbers pre-filled. Seed the
following (Mercedes A-numbers; **flag low-confidence items in `specNotes`**):

| Item | Interval | Category | Part # (seed) | Spec notes |
|---|---|---|---|---|
| Engine oil + filter | 10k mi / 12 mo | ROUTINE | filter `A6421800009` | MB 229.51/.52, 5W-30, ~13 L; cap torque 25 Nm |
| Engine air filter | 20k mi / 24 mo | ROUTINE | `A0000903751` | — |
| Cabin filter | 20k mi / 24 mo | ROUTINE | `A0008300418` (dash) / `A0018358747` (roof A/C) | verify dash vs roof-A/C airbox |
| Fuel filter | 20k mi | SCHEDULED | `A6420920301` family | ⚠️ LOW-CONFIDENCE — verify connector (3 vs 5-pin, heated?) |
| Trans fluid + filter | ~40k mi | SCHEDULED | 722.9 service kit | ⚠️ **seed 722.9: MB 236.15/236.17** — confirm 722.9 vs 722.6 (see Open items) |
| Coolant | 60k mi / 60 mo | SCHEDULED | — | MB 325.0 (blue, ≤2014-era), ~12 L, 50/50 |
| Brake fluid | 24 mo (time only) | SCHEDULED | — | DOT4 MB 331.0 LV |
| Serpentine belt + tensioner | 60–100k mi | SCHEDULED | belt `A0029934296`, tens. `A6422001370` | do idlers with belt |
| Rear diff fluid | ~40k mi | DRIVELINE | `A0019898303` family | 75W-85 MB 235.7, ~2.2–2.8 L |
| Front diff fluid (4x4) | ~40k mi | DRIVELINE | — | 75W-90 MB 235.8/.9, ~0.85 L — 4x4 only |
| Transfer case (4x4) | ~40k mi | DRIVELINE | — | ATF MB 236.12/.14, ~1.0 L — 4x4 only |
| DEF / AdBlue | ~10k mi refill | CONSUMABLE | — | ISO 22241, ~22 L tank |
| Glow plugs | condition (~60–100k) | SCHEDULED | `A0011596601` (7V ceramic) / NGK CZ303 | post-6/2012 = ceramic; do NOT fit steel |
| Oil-cooler seals | condition (known issue) | KNOWN_ISSUE | Viton `A6421880580` | valley reseal; signature OM642 leak — a watch item, no interval |
| Tire rotation | 5–7.5k mi | ROUTINE | — | — |

### UI — new Maintenance section (invoke `frontend-design:frontend-design`)
- **Nav:** promote `MainActivity.DashboardOrSettings()`'s `showSettings: Boolean` to
  `enum Screen { DASHBOARD, SETTINGS, MAINTENANCE }`; add a plain-text entry affordance on the dashboard
  chrome (match the existing "⚙" `TextButton` — **no icon dependency**); render `MaintenanceRoute(onBack=…)`
  wiring `MaintenanceViewModel` via `viewModel()`. Follow the `SettingsRoute` / nested `RecordingsRoute`
  Screen⁄Route split (stateless `MaintenanceScreen(state, callbacks)` + Hilt `…Route` wrapper).
- **List screen:** service items grouped by category, each with a **status chip + countdown** ("Oil
  change · 3,200 mi / 4 mo", colored `GaugeGreen` OK / `GaugeAmber` due-soon / `GaugeRed` overdue; a
  never-serviced item reads "log one"). Current odometer shown at top with a "Set odometer" affordance.
- **Detail screen:** edit the item (intervals, `partNumbers`, `specNotes`) + the record history for that
  item + a "Log service" action (date, odometer prefilled to current, notes, optional cost).
- **Set-odometer** entry (number field → writes the manual anchor).
- Theme: reuse `MaterialTheme.colorScheme.*` + `GaugeGreen/Amber/Red`; forceDark inherited.

### Status / interval math — pure, unit-tested
`maintenance/MaintenanceStatus.kt` (Compose-free): given a `ServiceItem` + its latest record +
`currentOdometerMiles` + today: `milesUntil = record.odometer + intervalMiles − current`;
`daysUntil = record.date + intervalMonths − today`; **status keys off whichever trigger is nearer**
(handle miles-only, time-only, never-serviced, overdue, disabled). Return a small `MaintenanceStatus`
(level + the driving trigger + human countdown parts).

## Testing

- **Pure JUnit (`app/src/test/`):** `MaintenanceStatus` (miles-only / time-only / whichever-first /
  never-serviced / overdue / disabled boundaries); `MaintenanceRepository` CRUD against an in-memory
  Room DB (`Room.inMemoryDatabaseBuilder`, `allowMainThreadQueries` in test); seed inserts once (re-open
  DB → no dupes); `AppSettings` mileage-field codec round-trip + default-on-missing.
- **Roborazzi (`app/src/testDemo/`):** `maintenance_list.png` (status chips across OK/due/overdue) +
  `maintenance_detail.png` (editable part numbers). Record via `recordRoborazziDemoDebug`; gate verifies.
- **Device (🖐 Taras, non-gating smoke):** dashboard → Maintenance; seed list renders; log a service;
  edit a part number and it persists; set odometer; countdowns/colors correct. `hardware-verify: false`
  (no OBD path in this issue).

## Interaction with OBD-80

OBD-80 (auto-mileage) is the follow-on: it decodes PID `0131`, adds the `MileageTracker` in
`ObdConnectionService`, and backs `currentOdometerMiles` with the OBD estimate + "≈ (est.)" UX. Keep the
odometer read behind one accessor so OBD-80 is a clean swap; do not build any OBD/link coupling here.

## Out of scope

- OBD/automatic mileage (OBD-80).
- Push "service due" notifications (OBD-81).
- Cluster-odometer reverse-engineering (future spike; gateway-isolated, undocumented DID).

## Open items (resolve at the seed step)

1. **722.9 vs 722.6 trans spec.** Repo hardware capture says this van is a 2018 NCV3 with **722.9**
   (→ ATF 236.15/236.17); Taras answered NCV3 (722.6 → 236.14 implied); STATUS.md carries the same open
   question. **Seed 722.9** (matches the van's captured data) with a `specNotes` flag; editable in one tap.
2. **Factory 4x4 vs RWD** — front-diff + transfer-case items apply only to 4x4. Seed them `enabled=true`
   but note "4x4 only — disable if RWD" in `specNotes`.
3. **Fuel-filter part** low-confidence — seed `A6420920301` family with the "verify connector" note.
</content>
