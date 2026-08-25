---
issue: OBD-70
round: 1
reviewer: rev-platform
verdict: approved
gate: green
reviewed-commit: 9f84187
covers: [OBD-70]
---

# OBD-70 review — round 1 (rev-platform, combined lens)

Bottom line: this is strong, well-decomposed work. Module boundary is clean, the OBD-69 idle
inhibit is correct and directly tested in both directions, `ActivePollSet` is a genuine single
source of truth with sound revert ordering, the CSV engine is pure and locale-safe, the index codec
degrades gracefully on corruption, and the FileProvider/manifest/API-23 story checks out. There is
**one real defect the gate cannot catch**: the `Recorder`'s buffered writer and session fields are
mutated and read across two threads (main-thread `stop()`/`onDestroy` vs the IO-dispatcher ticker)
with no synchronization and no IO-error handling, so a stop that lands during a write, or any write
failure (storage ejected/full — a case `LogsDir` itself contemplates), can throw uncaught through
`serviceScope` and crash the foreground service mid-drive. That one is worth a second round; the
rest are minor/nits.

Note: `issues/OBD-70.md` is `hardware-verify: true`. Nothing in this review clears that — the Garmin
device-verify (Record → drive → Stop → Recordings → Share/USB-pull → gauges recover) remains Taras's
acceptance gate regardless of any reviewer verdict.

---

## BLOCKER / MAJOR

### 1. `Recorder` shares its writer + session state across threads with no synchronization or IO-error guard → crash-mid-drive on the target device
`app/src/main/kotlin/com/revel/obdgauge/app/recording/Recorder.kt:92-98` (ticker), `:101-109`
(`appendRow`), `:114-129` (`stop`); `ObdConnectionService.kt:257` (`onDestroy` → `recorder?.stop()`);
`serviceScope` = `CoroutineScope(SupervisorJob() + Dispatchers.Default)` with **no
`CoroutineExceptionHandler`** (`ObdConnectionService.kt:136`).

The ticker body runs on `ioDispatcher` (`Dispatchers.IO` in prod — `Recorder.kt:56, 93`). `start()`,
`stop()`, and `onDestroy` all run on the **main** thread (`RecordingBridge.start/stop` ←
`DashboardViewModel.startRecording/stopRecording` ← Compose `onClick`; `onDestroy` is a lifecycle
callback). So `writer`, `sessionFile`, `rowCount`, and `columns` are written on main and read/written
on IO with no `synchronized`/lock/`@Volatile`. Three concrete failures:

- **Concurrent close vs write.** `stop()` calls `writer?.close()` (`Recorder.kt:119`) on main while
  `appendRow()` may be mid-`write()`/`newLine()`/`flush()` on IO (`Recorder.kt:104-106`). `job?.cancel()`
  is cooperative — it does **not** interrupt an in-flight `flush()`. `BufferedWriter` is not
  thread-safe; a close racing a write yields `IOException("Stream closed")` or a torn final row. The
  `flush()`-every-tick does a real syscall to the underlying `FileOutputStream`, so the window is
  milliseconds each second, not microseconds — over real drives (and OS-triggered `onTaskRemoved`/
  low-memory `onDestroy`, which fire at arbitrary times) this is a *when*, not an *if*.
- **`sessionFile!!` NPE race.** `appendRow` guards on `writer` (`Recorder.kt:102`) but then reads
  `sessionFile!!` at `:108`. If `stop()` runs `sessionFile = null` (`:127`) between the guard and that
  line → NPE.
- **Unguarded write failure.** `appendRow` has no `try/catch`. On the Garmin (removable/emulated
  external storage — `LogsDir.kt` explicitly falls back when it's "genuinely unavailable"), an eject
  or disk-full mid-drive throws `IOException` from `write`/`flush`.

In all three, the exception escapes `scope.launch(ioDispatcher){…}`. `serviceScope` has a
`SupervisorJob` but **no exception handler**, so the throwable reaches the thread's default uncaught
handler → the app/foreground service crashes mid-recording. The Robolectric test `ticks append rows
and stop flushes` (`ObdConnectionServiceTest.kt:328`) does exercise a cross-thread stop, but it only
*probabilistically* misses the window; the pure `RecorderTest` runs everything on one virtual-time
dispatcher and cannot surface this at all. Hence "gate green" says nothing here.

**Fix (pick one, lowest-friction first):**
- Guard every writer/session mutation-and-use behind one lock: wrap the tail of `start()` (writer
  setup), the whole of `appendRow()`, and the writer/field teardown in `stop()` in
  `synchronized(lock)` (a private `val lock = Any()`). This serializes close-vs-write and closes the
  `sessionFile!!` window. **And** wrap `appendRow`'s IO in `try/catch (e: IOException)` that stops the
  session gracefully (best-effort flush, publish `Idle`, finalize the index) instead of letting it
  escape. Optionally also give `serviceScope` a `CoroutineExceptionHandler` as a backstop.
- Capture `sessionFile` into a local before the `!!` (`val file = sessionFile ?: return`) regardless,
  so the state read is never a bare `!!` on a field another thread can null.

---

## MINOR

### 2. `index.json` is a read-modify-write with no locking across two writers → lost update
`RecordingsViewModel.delete` (`RecordingsViewModel.kt:71-76`, main/UI thread) does
`readSessionIndex → filterNot → writeSessionIndex`. `Recorder.writeIndexEntry`
(`Recorder.kt:131-146`, service thread) does the same read-modify-write on start/stop. Both fully
overwrite `logs/index.json` via `writeText`. The Recordings screen is reachable from Settings *while a
recording is active* (the indicator lives on the dashboard, recording continues in the service), so a
`delete` interleaving a start/stop `writeIndexEntry` can clobber the other's entry (e.g. delete reads
[A, B-in-progress], writes [A-minus]; recorder's stop then rewrites B and either resurrects the
deleted row or drops the in-progress one). Probability is low, blast radius is one stale/missing list
row (not data loss of the CSV itself). Fix: funnel index writes through a single serialized owner, or
gate both behind the same lock as finding 1; at minimum note the hazard in code.

### 3. Internal-storage fallback path is not exposed by `file_paths.xml` → Share crashes in the degraded case
`LogsDir.kt:...` falls back to `File(context.filesDir, "logs")` when `getExternalFilesDir` is null.
`file_paths.xml` only declares `<external-files-path name="logs" path="logs/"/>`. If a recording is
written to the internal fallback and the user taps Share, `FileProvider.getUriForFile`
(`RecordingShare.kt:23`) throws `IllegalArgumentException("Failed to find configured root…")` —
uncaught in `RecordingsViewModel.share` → crash, rather than a graceful "can't share, pull over USB."
The `LogsDir` KDoc acknowledges the USB promise doesn't hold in the fallback but not that Share
itself would crash. Fix: add a matching `<files-path name="logs_internal" path="logs/"/>` root, or
wrap `share()` in a try/catch that surfaces the on-device path instead.

---

## NITS

### 4. No system-`BackHandler` on the nested Recordings screen
`SettingsRoute` swaps to `RecordingsRoute` via local `showRecordings` state (`SettingsScreen.kt:...`)
with only an on-screen "< Back" button (`RecordingsScreen.kt:53`). Hardware/nav-bar back on the
Garmin (which has one) exits the app rather than stepping Recordings → Settings → Dashboard. This
matches the app's existing no-`BackHandler` idiom (Settings itself behaves the same today), so it's
**not a regression** — flagging only as a consistency note for a future nav pass.

### 5. Minute-resolution filename collision
`buildFilename` (`CsvEngine.kt:96-99`) is minute-resolution; two sessions started in the same minute
collide (second overwrites first's header, index keeps one row). The KDoc calls this out and it's a
non-pattern for a manual Record button. Fine as-is; noting for completeness.

### 6. frontend-design skill not invoked (design-polish note, NOT a gate)
Per the task brief: the author reused the existing plain Material3 idiom (hand-rolled `TextButton`
Record control, standard `AlertDialog`s, plain list rows) instead of invoking
`frontend-design:frontend-design` for the rally-graphic aesthetic. This is a deliberate,
internally-consistent choice and is explicitly **not** a correctness blocker — surfacing it so Taras
can decide whether the Recordings screen / indicator get a design pass later.

---

## Fix list (must-fix before approve)

- [x] ✅ **(1, blocker/major)** Serialize `Recorder`'s writer + session-field access across the
  main-thread `stop()`/`onDestroy` and the IO-dispatcher ticker (single lock around `start` tail /
  `appendRow` / `stop`), AND wrap `appendRow`'s IO in a `try/catch (IOException)` that finalizes the
  session gracefully instead of throwing uncaught through the handler-less `serviceScope`. Capture
  `sessionFile` into a local rather than `!!` on the field. — **Fixed in 349a896** (see Round 2).
- [x] ✅ **(2, minor)** Close the `index.json` read-modify-write lost-update window between
  `RecordingsViewModel.delete` and `Recorder.writeIndexEntry` (serialize writes or share the lock),
  or document the hazard if accepted. — **Fixed in 349a896** (shared `updateSessionIndex`/`indexLock`).
- [x] ✅ **(3, minor)** Make Share not crash when a recording lives in the internal-storage fallback:
  add a `files-path` root to `file_paths.xml` or guard `RecordingsViewModel.share`. — **Fixed in
  349a896** (`<files-path name="logs_internal" path="logs/"/>`).

Nits 4–6 are non-blocking; author's discretion. Nit 5 (minute-resolution filename) was also fixed in
349a896 (now `yyyy-MM-dd_HHmmss`).

---

## Round 2 — delta review of 349a896

Scope: `git diff c092e36..349a896`. Gate PASS; `:app:test` demo 416 / prod 340, 0 failures (8 new
tests). **Verdict: approved.** The round-1 blocker is genuinely fixed and both minors are closed. One
new minor defect was introduced by the fix (below) — non-blocking, logged as a follow-up. Verdict
covers correctness only; `hardware-verify: true` remains Taras's gate (the Garmin drive-and-pull
acceptance) and is **not** cleared by this approval.

### Re-verified fixed

- **(1) Recorder thread-safety + IO error path — FIXED and correct.** A single `private val lock`
  now guards every writer/session-field mutation-and-use: `start()`'s field setup
  (`Recorder.kt:104-110`), `appendRow()`'s write (`:168-185`), and the shared `finalize()` teardown
  (`:221-231`). `sessionFile` is captured into a local (`:170`, `:222`) — no bare `!!` a racing
  `stop()` could null. `appendRow` and the new `openAndWriteHeader` both `catch (IOException)` and
  route to a graceful `finalize()`/`return null` (stay `Idle`) instead of throwing uncaught. `job`
  is `@Volatile` and `finalize()` now nulls it (`:229`), fixing the round-1-noted `isRecording`
  stuck-true-after-error bug. `serviceScope` gained a `CoroutineExceptionHandler` backstop
  (`ObdConnectionService.kt:143`, `:382-386`) that logs and keeps the service alive.
  - **Lock discipline checked (the lead's specific concerns):** the monitor is never held across a
    suspension point — `delay(tickInterval)` is in the `while` loop, outside `appendRow`'s
    `synchronized` block. `lock` (Recorder) and `indexLock` (SessionIndex) are never nested: `start`
    and `finalize` both close the `synchronized(lock)` block *before* calling `updateSessionIndex`,
    and `appendRow` calls `finalize()` only after its own `synchronized`/try exits. So no lock-order
    deadlock; `stop()` on main at worst briefly blocks on `lock` for the duration of one bounded
    `flush()`, which is the intended mutual exclusion.
  - **`finalize()` idempotence checked:** double-entry (a concurrent `stop()` racing an IO-error
    finalize) is safe — the two serialize on `lock`; the first captures `sessionFile` and nulls it,
    so exactly one call sees a non-null file and does the single index update, and `writer` is
    already `null` on the second call so no double flush/close. Poll-set revert + `Idle` publish are
    idempotent.
  - **`writerFactory` seam is prod-safe:** it defaults to `{ it.bufferedWriter() }` and
    `ObdConnectionService` constructs `Recorder(` (`:194`) without overriding it — only `RecorderTest`
    substitutes the throwing writer. No test-only path reaches production.
  - **`CoroutineExceptionHandler` masks nothing it shouldn't:** it only receives genuinely uncaught
    throwables from `serviceScope`'s direct children (never `CancellationException`), and `appendRow`
    already catches its own `IOException`, so the handler is a pure backstop that logs via `Log.e`
    (visible in logcat) rather than silently swallowing. Appropriate for a foreground service that
    must not crash mid-drive.
  - **Tests prove it:** `an IOException on a tick's write finalizes the session instead of throwing`
    (relies on `runTest`'s own uncaught-exception detection — reaching the assertions is proof
    nothing escaped) and `an IOException opening the file at start leaves the session Idle`
    (asserts never-published `Recording`, no index entry, `startCallCount == 0` so the poll set never
    widened).
- **(2) index.json lost-update — FIXED.** All mutations route through `SessionIndex.updateSessionIndex`,
  which does `read → transform → write` under a process-wide top-level `private val indexLock`
  (`SessionIndex.kt:118-133`). Recorder start/finalize and `RecordingsViewModel.delete` all use it;
  the lock covers the *whole* read-modify-write, not just the write. `indexLock` is a top-level
  property → one instance per classloader, and the app is single-process (no `android:process` on the
  service), so the ViewModel (main thread) and Recorder (service IO thread) share the same object. New
  `concurrent updateSessionIndex calls from multiple threads never lose an update` spins 20 real
  threads and asserts all 20 survive.
- **(3) FileProvider fallback — FIXED.** `<files-path name="logs_internal" path="logs/"/>` added,
  matching `LogsDir`'s `File(context.filesDir, "logs")` fallback so Share degrades gracefully instead
  of throwing `IllegalArgumentException`.
- **(nit 5) filename — FIXED.** `yyyy-MM-dd_HHmmss` (second resolution); test updated.

### New — introduced by the round-2 fix (minor, non-blocking)

- **(7, minor) `openAndWriteHeader` leaks the opened writer on a header-write failure.**
  `Recorder.kt:145-156`: `try { writerFactory(file).apply { …write header… } } catch (IOException) { null }`.
  If `writerFactory(file)` *succeeds* (in production `file.bufferedWriter()` opens the
  `FileOutputStream` eagerly, creating the file) but a subsequent `write`/`newLine`/`flush` throws,
  the opened writer reference is lost in the `catch` and never closed — a leaked file descriptor
  (until GC finalizes `FileOutputStream`) plus a stray empty `obdlog_*.csv` with no index entry. The
  new test doesn't surface this: its `SwitchableThrowingWriter` defers the real `target.writer()`
  behind `by lazy` and throws in `write()` before touching it, so nothing is actually opened on the
  throwing path. Trigger (storage full/ejected exactly at the Record tap) is rare and the app still
  degrades gracefully (stays `Idle`, no crash), so this does not block. Fix when convenient: close the
  writer in the `catch` (e.g. open into a local, and `runCatching { it.close() }` before returning
  `null`).

---

## Round 3 — delta review of 25be53a

Scope: `git diff 349a896..25be53a`. Gate PASS; `:app:test` demo 417 / prod 341, 0 failures (1 new
test). **Verdict: still approved.** Round-2 finding 7 (the `openAndWriteHeader` fd/stray-file leak) is
now fixed. `reviewed-commit` bumped to `25be53a` so it equals the branch head (merge is
staleness-clean). `hardware-verify: true` unchanged — still Taras's gate, not cleared here.

- **(7) header-write leak — FIXED and correct.** `openAndWriteHeader` (`Recorder.kt:141-171`) is now
  two `try` blocks: open (`writerFactory(file)`; on `IOException` → `return null`, nothing to close)
  then header-write, whose `catch (IOException)` does `runCatching { bufferedWriter.close() }` +
  `runCatching { file.delete() }` before returning `null`. No secondary throw escapes (both wrapped)
  and no double-close: on failure the writer is never handed back to `start()` (the `?: return` at
  `:102` bails before any field assignment / ticker launch / index write / poll-set widen), so
  `finalize()` never sees it. Even if `BufferedWriter.close()` re-throws while flushing its buffer, its
  own try-with-resources still closes the underlying `EagerlyOpenedThrowingWriter` (releasing the fd),
  and the `runCatching` swallows the re-throw.
- **Test genuinely exercises the leak path.** `an IOException writing the header closes the writer and
  deletes the stray file` uses the new `EagerlyOpenedThrowingWriter`, which opens the real underlying
  writer **in its constructor** (not `by lazy` like `SwitchableThrowingWriter`) and then throws on
  every write/flush — so a real file/fd exists at the moment the header write fails, and the test
  asserts `logsDir.listFiles { obdlog_* }` is empty afterward. This is exactly the gap I noted the
  round-2 test couldn't cover.
- **No regression to round-2 discipline.** The change is confined to `openAndWriteHeader`, which runs
  entirely **before** any `lock` acquisition, field assignment, or ticker launch in `start()`. The
  `lock`/`finalize`/`appendRow`/`indexLock` code is untouched — the round-2 thread-safety guarantees
  stand.

Finding 7 checked off. No remaining findings; all round-1 and round-2 items resolved.

---

## Verified this round (no change needed)

- **Module boundary** — `grep` for `com.revel.obdgauge.protocol.*` across `app/src/main/` and
  `app/src/demo/`: CLEAN. The only prod references are the four pre-existing ones
  (`SpeedCorrectionDataSource`, `DataSourceModule`, `DisplayUnitDataSource`) plus the sanctioned
  `app/src/prod/.../di/LoggablePidsModule.kt` returning `PidCatalog.definitions`. The `main/` recorder
  sees only `List<PidDefinition>` via the `@LoggablePids` qualifier.
- **OBD-69 idle inhibit** — `checkIdleAndMaybeStop` (`ObdConnectionService.kt:280-292`) short-circuits
  `false` iff `recording || !idle`. (a) a live recording can never idle-stop — verified + tested
  (`an active recording inhibits the idle watchdog`). (b) NO regression when not recording — the
  `recording` term is purely additive; `idle-stop resumes once the recording bridge returns to Idle`
  and the pre-existing `the idle watchdog stops the service…` both prove the normal stop still fires.
  (c) wake lock stays fresh: a real recording implies non-empty readings emissions →
  `applyReadingsToIdleSignal` → `refreshWakeLock` still reached (unchanged path). (d) stop-vs-tick
  race: after `stop()` publishes `Idle`, the next watchdog tick evaluates `idle` against a
  `lastDataAtMillis` kept recent by the just-ended data flow, so no spurious immediate stop — and if
  data was genuinely stale, stopping post-recording is correct.
- **Poll-set single source of truth** — all three `dataSource.start(pids)` call sites read
  `activePollSet.activePids()`, never a `GAUGE_CATALOG` literal: `DashboardViewModel.onStart`
  (`DashboardViewModel.kt:124`), `ConnectionServiceController.start` + its Ready-edge restart
  (`ConnectionServiceController.kt:93, 104`), and `Recorder.start/stop` (`Recorder.kt:89, 123`). The
  screen-off→on resubscribe-during-recording race is closed (resubscribe re-requests the widened
  union). Revert ordering is sound: `stop()` sets `recordingPids = empty` *then* re-issues
  `start(activePids())`, so the final call always wins with `GAUGE_CATALOG` (`RecorderTest`:
  `dataSource.startCallCount == 2`). `ActivePollSet` is thread-safe (state held in a
  `MutableStateFlow`, `activePids()` is a pure read + `pollUnion`).
- **Prod widening exercised flavor-independently** — `pollUnion` is unit-tested with `extra`
  containing ids **not** in `base` (`CsvEngineTest`: `pollUnion appends new extras after the base set,
  deduplicated`), proving the widen path regardless of demo's subset. The item-4 gap is not present.
- **CSV engine purity + locale safety** — `csvRow`/`csvHeaderLines`/`buildFilename`/`pollUnion` are
  pure, Android-free, take an explicit `ZoneId`. Values are `Double.toString()`/`Long.toString()`
  (locale-independent, always `.` decimal), timestamps via `ISO_OFFSET_DATE_TIME`, all
  `RecordingsFormatting` uses `String.format(Locale.US, …)`. No comma-decimal landmine. No CSV cell
  can contain a comma (ids, ISO timestamps, numbers); the label/unit legend lives only on `#` comment
  lines, `read_csv(comment='#')`-safe. UTF-8 (`File.bufferedWriter()` default) carries `°F` fine.
- **Recorder flush cadence** — `flush()` every tick → a crash loses ≤ ~1 s. `onDestroy` flushes/closes
  and writes the index with `endedAt` before `serviceScope.cancel()` (tested: `onDestroy flushes and
  stops a recording still in progress`). `stop()` is idempotent.
- **`index.json` codec robustness** — hand-rolled regex decoder skips malformed/truncated objects
  rather than throwing (`decoding malformed text yields no entries`); a partial write (crash
  mid-`writeText`) leaves only complete `{…}` objects matchable, so a truncated tail is silently
  dropped, not fatal. Round-trips its shape incl. quote-escaped filenames.
- **FileProvider + share** — authority `${applicationId}.fileprovider` (correct across
  demo/prod/`.dev`), `exported="false"` + `grantUriPermissions="true"`, `file_paths.xml`
  `external-files-path` matches `getExternalFilesDir("logs")`. `buildShareIntent` = `ACTION_SEND` +
  `text/csv` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` (tested; uri resolves to a `content`
  scheme on API 34). (See finding 3 for the internal-fallback edge.)
- **API-23 safety** — no `java.nio.file`/`Files`/`Paths`/adaptive-icon APIs in the new code; all time
  handling is `java.time` (desugared) with explicit `Locale.US` formatters. `buildConfig = true` is
  used for real (`VERSION_NAME`/`FLAVOR`/`APPLICATION_ID` in the CSV header + channel derivation),
  not gratuitous.
- **UI** — start-confirm `AlertDialog` present (start-only; stop is one tap), delete-confirm
  `AlertDialog` present, recording indicator driven by `StateFlow<RecordingState>` with the OBD-66
  pulse idiom, Record control added to the existing hand-rolled top `Row` without disturbing the
  rearrange "Done"/gear layout. Roborazzi refs added for idle button, confirm dialog, indicator, and
  Recordings empty/populated.

---

## Round 4 — delta review of 4f9cbef

Scope: `git diff 25be53a..4f9cbef` (two fixes from a real-device test on a Pixel API 34 over adb —
both caught things the host-JVM unit tests + Robolectric structurally cannot). Gate PASS; `:app:test`
demo 421 / prod 345, 0 failures. **Verdict: still approved.** No new defects; two non-blocking
follow-up observations below. `reviewed-commit` bumped to `4f9cbef` (== branch head → merge
staleness-clean). `hardware-verify: true` unchanged — Taras is re-confirming the on-device header now,
and this approval does **not** clear that gate.

### (A) Device-only crash fix — `840db1e` (orchestrator-authored; independent rev-platform sign-off)

`SessionIndex.kt:76` `OBJECT_PATTERN` `"\\{[^{}]*}"` → `"\\{[^{}]*\\}"`. Verified independently (I did
not author this):
- **Semantically identical.** The pattern still means: literal `{`, then zero-or-more non-brace chars,
  then literal `}` — a flat JSON object. `\}` is an explicit literal close; the braces inside the
  `[^{}]` character class remain literals on **both** engines (no escape needed there). The `-`/match
  set is unchanged. So the decoder matches exactly what it did on OpenJDK, now also on Android's ICU
  engine. The root cause is real: a bare `}` is a lenient literal on OpenJDK's `java.util.regex` but
  ICU4C treats it as a stray quantifier terminator → `PatternSyntaxException` at class-init →
  `ExceptionInInitializerError` on the first `index.json` touch (every Record tap).
- **(b) No other regex has the same hazard.** Grepped `Regex(`/`Pattern.compile`/`.toRegex()` across
  `app/src/main`, `app/src/prod`, `app/src/demo`: only two regexes exist, both in `SessionIndex.kt`.
  `FIELD_PATTERN` (`:77`) contains no braces and no `{n,m}` quantifier — only `\w \" \\ \d`,
  alternation, a non-capturing group, `*`/`+` — all ICU-legal. Clean.

### (B) CSV unit-label + rounding fix — `4f9cbef` (builder-authored)

- **(a) The legend rule EXACTLY mirrors the value's conversion — consistent by construction, all 6
  channels.** `legendEntry` now resolves the unit as `GAUGE_CATALOG_BY_ID[id]?.unit ?: pid.unit`
  (`CsvEngine.kt`). This is the *same* map and the *same* fallback that `DisplayUnitDataSource.toDeclaredUnits`
  (prod) uses to pick the conversion target: `to = GAUGE_CATALOG_BY_ID[id]?.unit ?: return@mapValues reading`.
  Walk the cases: (i) channel ∈ `GAUGE_CATALOG` → value is converted to `GAUGE_CATALOG_BY_ID[id].unit`
  and the legend names that same unit (coolant/oil/trans → °F, boost → PSI); (ii) channel ∉
  `GAUGE_CATALOG` → value passes through unconverted in its protocol unit, and the legend falls back to
  `pid.unit`, which in prod IS the `PidCatalog` (protocol) unit since `loggablePids = PidCatalog.definitions`
  (baro → kPa); (iii) `GAUGE_CATALOG_BY_ID[id]` present but `from == to` (e.g. rpm RPM↔RPM) → value
  unchanged, legend names the same unit. In every case legend == the value's actual unit. Because both
  sides read the one `GAUGE_CATALOG_BY_ID` map, they cannot drift even if a KDoc elsewhere is stale
  (the `DisplayUnitDataSource` class KDoc still lists `speed` as pass-through, but `speed` joined
  `GAUGE_CATALOG` in OBD-61 so it is in fact converted — the fix is correct regardless because it keys
  off the same map the conversion does).
  - **`speed`, the one channel converted outside `DisplayUnitDataSource`, still matches.**
    `GAUGE_CATALOG` includes `SPEED_PID_DEFINITION` (declared `MPH`). `SpeedCorrectionDataSource`
    (outermost, what the Recorder reads) applies a **unitless** GPS multiplier (`speed.value * factor`),
    so it does not change the unit — the value stays MPH, and the legend names MPH. Match.
- **(c) The value can't drift out from under the static label when the user toggles units.**
  `DisplayUnitDataSource` converts once, at the prod DI seam, to `GAUGE_CATALOG`'s *declared* unit —
  **not** the user's OBD-21 display-unit preference. That preference is applied downstream at gauge
  render time (`UnitConversion`, reading the declared unit as `from`), and never writes back into the
  `StateFlow<Map<String,Reading>>` the Recorder observes (the injected chain is Real → DisplayUnit →
  SpeedCorrection; none of the three reads `AppSettings.units`). So the CSV always logs, and the header
  always labels, `GAUGE_CATALOG`'s fixed declared units regardless of what the user has toggled on
  screen — no possible header/value mismatch. (Observation for Taras, not a defect: this means a CSV is
  always in °F/PSI/mph even if you've switched the dashboard to °C — the header stays truthful, but if
  you'd rather the CSV follow the on-screen unit that's a product change, out of scope here.)
- **(b) Rounding + blank handling are safe.** `formatCsvValue` = `String.format(Locale.US, "%.2f", value)`
  — locale pinned, so no comma-decimal landmine can corrupt the delimiter (same discipline as the
  timestamps). Blank/absent stays blank: `readings[id]?.value?.let(::formatCsvValue).orEmpty()`
  short-circuits to `""` for an absent id — it does **not** become `0.00`. A stale reading still carries
  its last value, so it formats normally (matches spec). Tests pin `93.33`, whole-number `225.00`, the
  converted-channel legend (`°F` not `°C`), and the fallback channel (`kPa`).

### Follow-up observations (non-blocking)

- **(8) The hand-rolled regex JSON codec is a latent host-JVM blind spot.** The gate cannot catch this
  class of bug: Robolectric runs on the host JVM and uses OpenJDK's `java.util.regex`, **not** Android's
  ICU engine — so neither plain JUnit nor Robolectric would ever have flagged the bare-brace divergence;
  only an on-device/emulator instrumented run did. The current patterns are now correct and device-
  verified, so this does not block, but any *future* regex added to `SessionIndex.kt` carries the same
  invisible risk. Worth a follow-up: either replace the regex codec with an engine-independent parser
  (a small manual tokenizer, or `kotlinx.serialization`), or add one `androidTest` smoke test that loads
  these patterns on the real Android engine. Flagging so the blind spot is on the record.

Both round-4 fixes verified correct. No blocker/major/minor defects this round; observation 8 is a
follow-up, not a gate.
