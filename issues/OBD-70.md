---
id: OBD-70
title: PID data logging — record all mapped PIDs to a CSV on device, review + share from settings
module: app
owner: ui-agent
sprint: telemetry
status: open
type: feature
hardware-verify: true
blocked-by: []
branch: feat/70-pid-logging
---

## What

Add on-device data logging. A **Record** button in the dashboard top row (next to the ⚙ gear) starts a
logging session that, once per second, snapshots every mapped PID into a **wide CSV** file on device.
Pressing Record pops a **confirmation dialog** first (so it can't be triggered by accident); once
recording, the button becomes a live **recording indicator** (red dot + elapsed timer) and one tap stops
it. A new **Recordings** screen (reached from Settings) lists saved sessions with date/duration/size and,
next to each, a **Share** button that fires the Android share sheet (email / Drive / Bluetooth / whatever
the device has) plus a **Delete**. Files also live in a plainly reachable folder so they can be pulled
over USB when the tablet has no share targets.

Primary target is the **Garmin Overlander (Android 6.0.1 / API 23)** — see [[garmin_overlander_sideload]] —
so every API used must be API-23-safe (this lands on `main` minSdk 26 but gets backported to the
`garmin-overlander-api23` branch, minSdk 23).

## Why

Taras wants to capture real driving telemetry to a structured file he can pull off the tablet and analyze
(spot patterns across coolant / oil / trans / boost / load / RPM over time). Wide CSV, one row per second,
opens directly in Excel / Sheets / pandas (`read_csv(comment='#')`) and its aligned columns are exactly
the shape you want for cross-PID pattern analysis. The app has no file export or share path today — this
builds it.

## Locked decisions (from Taras)

- **Format:** **wide CSV, 1 Hz.** One row per second; columns = `timestamp, elapsed_ms,` then one column
  per mapped PID holding its latest value. `#`-commented self-describing header lines on top.
- **What gets logged:** **the full mapped set.** While a recording is active, widen the poll set from the
  6 dashboard channels to the whole mapped catalog; revert on stop. (Accepts that gauges refresh slower
  during a recording — that trade-off is chosen intentionally.)
- **Getting it off the device:** **share sheet + USB fallback.** Build the `ACTION_SEND` chooser AND
  always leave files in a folder reachable over USB-MTP.
- **Scope:** **one issue** — record button + confirm + indicator, recorder in the foreground service,
  Recordings screen (list + share + delete). One Garmin device-verify.

## Implementation

Three cohesive parts. Keep the recorder engine (CSV serialization, index) **pure and unit-testable** —
do NOT bury formatting logic inside coroutines or Android classes.

### A. The recorder engine (owned by the foreground service)

Recording must survive screen-off and config changes, so it lives in / is owned by
`app/src/main/kotlin/com/revel/obdgauge/app/service/ObdConnectionService.kt` (which already `@Inject`s
`VehicleDataSource` at `:91` and holds the wake lock). The service already keeps the CPU awake while data
flows; OBD-69 (if/when merged) refreshes the wake lock on each sample, so an active recording — which by
definition has data flowing — keeps the lock fresh. **Cross-issue seam:** if OBD-69's idle watchdog lands,
an active recording MUST inhibit the idle-stop (a live recording counts as activity). Note this in code
so the two features don't fight; OBD-70 and OBD-69 may merge close together.

- **Data source of truth:** observe the injected `VehicleDataSource.readings`
  (`StateFlow<Map<String, Reading>>`, `core/model/.../VehicleDataSource.kt:17`). Values at the injected
  (top-of-chain) source are **already display-unit-converted** (°F / PSI / mph) and speed-corrected
  (`RealVehicleDataSource → DisplayUnitDataSource → SpeedCorrectionDataSource`) — so the CSV records
  display units, and the header legend takes each column's unit from `PidDefinition.unit`. On the `demo`
  flavor the source is `FakeVehicleDataSource`, so logging is fully testable without a van.
- **`Reading` shape** (`core/model/.../Reading.kt:16`): `{ id, value: Double, timestamp: Instant,
  stale: Boolean }`. No unit/label — resolve those by `id` against `PidCatalog.byId(id)` at write time.
  `Reading` is a frozen Phase-0 contract — do NOT add fields to it. Resolve label/unit for a column by
  joining `Reading.id` against the **injected `loggablePids`** (each is a `PidDefinition` with id/label/unit)
  — NOT by calling `PidCatalog` (that's `:core:protocol`, off-limits in `main/`).
- **Column set = "all mapped PIDs" — delivered via DI, NOT by importing `:core:protocol`.** ⚠️ **Module
  boundary:** `:app` **main** source set may depend only on `:core:model` + `:core:testing`; it must NOT
  import `com.revel.obdgauge.protocol.*` (the recorder lives in `main/`, so it can't touch `PidCatalog`).
  The full mapped set is injected as a `loggablePids: List<PidDefinition>` (or a tiny `LoggablePidSet`
  wrapper) provided **per flavor**, mirroring the existing flavor-split in `DisplayUnitDataSource`:
  - `app/src/prod/.../di/` → `@Provides` returns **`PidCatalog.definitions`** (standard 18 mode-01 + Mercedes
    mode-22 + trans KWP record + computed `boost`). This is the **one sanctioned `:core:protocol`
    reference** — it lives in `app/src/prod/`, which already depends on `:core:protocol` and already uses
    `PidCatalog.definitions` (`app/src/prod/.../datasource/DisplayUnitDataSource.kt:86`), so it is
    path-owned by ui-agent and passes module-isolation. Explicitly authorized exception to the
    "model+testing only" rule, scoped to this provider.
  - `app/src/demo/.../di/` → `@Provides` returns the set `FakeVehicleDataSource` actually emits, so demo
    logging produces a well-formed CSV (Roborazzi + manual demo testing without a van).
  - The recorder (`main/`) `@Inject`s `loggablePids` and iterates it for both the CSV columns (id/label/unit)
    and the poll-widening below. Column order fixed at session start, written to the header. A PID absent
    from the readings map that tick → **blank cell** (documents unavailability, e.g. MAP on this van).
- **Widen the poll set while recording:** today `DashboardViewModel` calls
  `dataSource.start(GAUGE_CATALOG)` (6 channels) on subscribe. While recording, poll the union
  `GAUGE_CATALOG ∪ loggablePids`, then revert to `GAUGE_CATALOG` on stop — the app passes the injected
  `PidDefinition`s straight to `start()` (no protocol ids constructed app-side). **Coordination hazard —
  there are now two callers of `start(pids)`** (the dashboard and the recorder). Introduce a single source
  of truth for the active poll set (the service owns it and polls the **union** of "dashboard request" ∪
  "recording request", restoring `GAUGE_CATALOG` when recording stops). Do NOT let the recorder's `start()`
  and the dashboard's `start()` clobber each other. Note: the 1 Hz CSV cadence is independent of poll rate —
  the ticker snapshots the latest known value regardless of how fast each PID refreshes.
- **The write loop:** a coroutine on an IO dispatcher with `delay(1000)`; each tick reads
  `readings.value`, builds one row `[ISO-8601 local timestamp, elapsed_ms, <value per column or blank>]`,
  appends via a buffered writer (flush periodically so a crash loses ≤ a second or two). Keep the
  **row-building a pure function** `fun csvRow(readings: Map<String,Reading>, columns: List<String>, now, startedAt): String`
  so it's unit-tested exactly.
- **Self-describing header** (written once at session start; all lines `#`-prefixed so `read_csv(comment='#')`
  skips them):
  ```
  # sprinter-obd-gauge log v1
  # started: 2026-08-18T22:07:58-06:00
  # app: <versionName> (flavor=<demo|prod>, channel=<…>)
  # sample_hz: 1
  # columns: coolant_c=Coolant(°F), rpm=RPM, trans_c=Trans(°F), boost_psi=Boost est(PSI), …
  timestamp,elapsed_ms,coolant_c,oil_c,rpm,trans_c,boost_psi,speed_mph,iat_c,maf,load,throttle,baro,fuel,…
  ```
  Column names: use PID ids (stable, analysis-friendly); the `# columns:` legend maps id → label(unit).
- **Files:** `context.getExternalFilesDir("logs")` — **no runtime permission on any API level** (works on
  API 23), and reachable by FileProvider + visible over USB-MTP. Filename from start time, e.g.
  `obdlog_2026-08-18_2207.csv`. Use the codebase's existing time-formatting approach and confirm it's
  **API-23 safe** (the app already uses `java.time.Instant`, so core-library desugaring is on for the
  Garmin branch — match it; do not introduce an API-26-only formatter without desugaring).
- **Recording state** exposed as a `StateFlow<RecordingState>` off the service/controller for the UI:
  `Idle` | `Recording(startedAtMillis, file, rowCount)`. The top-bar button and indicator observe this;
  it drives the elapsed timer + row count.
- **Session index** (so the Recordings list needn't re-read big CSVs): maintain a small
  `logs/index.json` (or a dedicated metadata DataStore file — give it a **distinct** `preferencesDataStoreFile`
  name to avoid the settings-store collision noted in `SettingsModule.kt`) recording per session
  `{ file, startedAt, endedAt, rows, pidCount }`, updated on start and on stop. The list screen reads this.

### B. The Record button + confirmation + indicator (dashboard top row)

The gear is a hand-rolled `TextButton` in a `Row` (`app/src/main/kotlin/com/revel/obdgauge/app/gauge/DashboardScreen.kt:462`,
glyph `SETTINGS_GLYPH` at `:138`), NOT a Material3 `TopAppBar`. Add the Record control to that same row.

- **Idle → tap Record:** show an `AlertDialog` confirmation ("Start recording all PIDs?" / Start · Cancel).
  Confirm → `onStartRecording()`; Cancel → dismiss. (Only **start** is confirmed — the accidental-trigger
  guard; **stop is one-tap**.)
- **Recording:** the control becomes an indicator — pulsing red dot + `mm:ss` elapsed (and optionally row
  count) — tap to `onStopRecording()`. Reuse the project's existing pulse/animation idiom (the threshold
  danger-zone pulse from OBD-66) for visual consistency.
- Wire `onStartRecording` / `onStopRecording` as hoisted `DashboardScreen` params, backed in
  `MainActivity`/the service controller by the recorder start/stop; the button's state comes from the
  observed `RecordingState`.
- **Invoke `frontend-design:frontend-design`** for the button/indicator + confirm-dialog + Recordings-list
  visual design so it matches the app's rally-graphic aesthetic (see [[design_aesthetic]]).

### C. Recordings screen (list + share + delete) — reached from Settings

Nav is a plain state swap, no nav library (`MainActivity.kt:48` KDoc — `mutableStateOf<Screen>`). Extend it:
add a `Recordings` destination, either as a new arm of the `if (showSettings) …` swap or nested under
`SettingsRoute` (which already takes `onBack`). Add a "Recordings" entry to `SettingsScreen`
(`app/src/main/kotlin/com/revel/obdgauge/app/settings/SettingsScreen.kt`) that opens it.

- **List:** read `logs/index.json` → rows of `{ start date/time, duration, size, pidCount }`, newest first;
  empty state when none. Each row: **Share** and **Delete**.
- **Share:** requires a FileProvider (none exists today — see below). On tap, build
  `FileProvider.getUriForFile(...)` → `Intent(ACTION_SEND)` with `type = "text/csv"`,
  `putExtra(EXTRA_STREAM, uri)`, `addFlags(FLAG_GRANT_READ_URI_PERMISSION)`, wrapped in
  `Intent.createChooser(...)`. This yields email-attach / Drive / Bluetooth targets **that exist on the
  device**. On the Garmin the chooser may be sparse — that's expected; the USB-reachable folder is the
  guaranteed fallback. Optionally surface the on-device path in the UI so it's findable over USB.
- **Delete:** confirm, remove file + index entry.

### D. FileProvider / manifest (new — nothing exists today)

`app/src/main/AndroidManifest.xml` declares no `<provider>` and the app does no raw-file I/O. Add:
- `<provider android:name="androidx.core.content.FileProvider"
   android:authorities="${applicationId}.fileprovider" android:exported="false"
   android:grantUriPermissions="true">` with a `<meta-data>` pointing at
- `app/src/main/res/xml/file_paths.xml` exposing the `logs` external-files path
  (`<external-files-path name="logs" path="logs/" />`).
  Authority via `${applicationId}` so it's correct across `demo`/`prod`/`.dev` flavors + channel suffix.

## Garmin / API-23 constraints (call out — this backports to minSdk 23)

- `getExternalFilesDir` needs **no** runtime storage permission on any API level. ✓
- `FileProvider` + `ACTION_SEND` + `FLAG_GRANT_READ_URI_PERMISSION` all work on API 23. ✓
- Compose runs on 23 (garmin branch already ships it). ✓
- **Time/formatting must be API-23-safe** — the garmin branch relies on core-library desugaring for
  `java.time`; match the existing approach, no un-desugared API-26 calls.
- The **share sheet only lists apps installed on the device** — the Overlander may have few/none, hence the
  USB-reachable folder is mandatory, not optional.
- `demo` shares the prod `applicationId` → installing a demo build clobbers the van build (existing gotcha).

## Testing

- **Pure unit** (`app/src/test/...`, plain JUnit): `csvRow(...)` exact output incl. blank cells for absent
  PIDs, stale-value handling, ISO timestamp + elapsed_ms; header/legend builder; column-order stability;
  filename generator; `logs/index.json` add/update-on-stop; the union poll-set computation
  (dashboard ∪ recording, restore on stop).
- **Robolectric** (`app/src/testDemo/...`, extend the `ObdConnectionService` tests): recorder lifecycle —
  start creates the file + header, ticks append rows, stop flushes/closes + updates the index + reverts the
  poll set to `GAUGE_CATALOG`; assert the share `Intent` is built correctly (action/type/flags/EXTRA_STREAM);
  FileProvider uri resolves. Recording against `FakeVehicleDataSource` produces a well-formed CSV.
- **Roborazzi** (`app/src/testDemo/screenshots/`): new refs for the **recording indicator** in the top row,
  the **confirm dialog**, and the **Recordings screen** (empty + populated). Record via
  `recordRoborazziDemoDebug`; gate verifies.
- **Device (🖐 Taras — the real acceptance, `hardware-verify: true`):** on the **Garmin** — tap Record →
  confirm dialog → recording indicator with a running timer; drive a few minutes; Stop; open
  Settings → Recordings → the session is listed with sane duration/size; tap **Share** and record what
  targets actually appear; pull the CSV over USB-MTP from the logs folder; open it and confirm columns +
  values are sane (coolant/rpm/trans track reality, blank columns only where a PID is truly unavailable);
  confirm gauges still update (slower) **while** recording and go snappy again **after** stop.

## Done when

Gate green; the recorder engine, index, and poll-set-union logic are unit-tested; recorder lifecycle +
share-intent are Robolectric-tested; Roborazzi refs for the indicator / dialog / Recordings screen exist;
and **Taras confirms on the Garmin** that a drive produces a valid, analyzable CSV, it's listed under
Recordings, it can be shared and/or pulled over USB, and gauges recover after stopping
(`hardware-verify: true` — never auto-closed).

## Out of scope (v1)

- Configurable sample rate / choosing which PIDs to log (v1 = fixed 1 Hz, full mapped set).
- Auto-start/stop logging on trip detection (manual button only).
- In-app charting/analysis of recordings (analysis happens off-device in Excel/pandas).
- Cloud upload / auto-sync (share sheet + USB only).
- Long-format / JSON export (wide CSV only).
