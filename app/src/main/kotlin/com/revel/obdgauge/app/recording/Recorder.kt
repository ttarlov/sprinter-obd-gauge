package com.revel.obdgauge.app.recording

import com.revel.obdgauge.app.service.ActivePollSet
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-70's recording session engine — owned by (constructed once inside)
 * [com.revel.obdgauge.app.service.ObdConnectionService.onCreate], on [scope] = the service's own
 * `serviceScope`, so a session survives screen-off and config changes exactly as the poll loop
 * already does, and is torn down (flushed, closed) on the service's `onDestroy` the same way
 * [com.revel.obdgauge.app.service.ConnectionServiceController] is.
 *
 * Deliberately Android-free (only [java.io.File]/[java.time.Clock], both plain JVM types) so it's
 * directly unit-testable in `app/src/test` against a temp directory and a hand-rolled
 * [VehicleDataSource] double — no Robolectric needed for the orchestration itself; Robolectric
 * only re-enters the picture to pin this running for real inside the service (lifecycle,
 * `BuildConfig` wiring, the idle-watchdog inhibit — see `ObdConnectionServiceTest`).
 *
 * Formatting is never inline here — every line this class writes comes from [csvRow]/
 * [csvHeaderLines]/[buildFilename] in `CsvEngine.kt`, the pure functions this issue's spec calls
 * out as the unit-tested surface. This class is the impure glue: a coroutine ticker, a buffered
 * writer, and the two [ActivePollSet]/index side effects a start or stop must also perform.
 *
 * [start]/[stop] are idempotent by the same discipline as [VehicleDataSource]'s own contract and
 * [com.revel.obdgauge.app.service.ConnectionServiceController]'s: a repeat [start] while already
 * recording, or a [stop] while idle, is a safe no-op rather than a duplicate session or a double
 * close.
 *
 * ### Round-1 review fix: cross-thread writer access
 * [start]/[stop] run on the **main** thread (Compose `onClick` → `RecordingBridge`, and
 * `ObdConnectionService.onDestroy`); the ticker body in [start]'s `launch` runs on [ioDispatcher]
 * (`Dispatchers.IO` in production). Without synchronization, [stop]'s `writer.close()` could race
 * an in-flight tick's `write`/`flush` (`Job.cancel()` is cooperative — it does not interrupt a
 * blocking IO call already in progress), and a tick reading [sessionFile] after [stop] nulled it
 * would NPE. [lock] serializes every writer/session-field mutation-and-use across both threads —
 * [start]'s field setup, [appendRow]'s write, and [finalize]'s close all hold it, so a stop always
 * either happens strictly before or strictly after an in-flight tick, never mid-write. [job]
 * itself is `@Volatile` rather than lock-guarded: it's a single reference read-then-written, never
 * a compound check-then-act, so volatility alone gives [isRecording] a correct cross-thread read.
 *
 * An [IOException] mid-tick (storage ejected/full — a case [logsDir]'s caller, `LogsDir.kt`,
 * already contemplates as a fallback trigger) is caught in [appendRow] and finalizes the session
 * gracefully (best-effort flush/close, index updated, `Idle` published) instead of throwing
 * uncaught through `serviceScope`'s handler-less `SupervisorJob` and crashing the foreground
 * service mid-drive. The same guard wraps the initial header write in [start], for the symmetric
 * "storage already full when Record is tapped" case.
 */
@Suppress("LongParameterList", "TooManyFunctions") // one field per genuinely independent collaborator/config value.
class Recorder(
    private val dataSource: VehicleDataSource,
    private val activePollSet: ActivePollSet,
    private val loggablePids: List<PidDefinition>,
    private val logsDir: File,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val appVersionName: String,
    private val flavor: String,
    private val channel: String,
    private val onStateChanged: (RecordingState) -> Unit,
    private val tickInterval: Duration = 1.seconds,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    // Test seam (round-1 review: "a fake writer that throws on write is enough") — production
    // never overrides this; RecorderTest substitutes a writer that starts throwing IOException
    // mid-session to prove appendRow's error path finalizes cleanly instead of crashing.
    private val writerFactory: (File) -> BufferedWriter = { it.bufferedWriter() },
) {
    // Guards writer/sessionFile/columns/rowCount/startedAtMillis — see this class's KDoc.
    private val lock = Any()

    @Volatile
    private var job: Job? = null
    private var writer: BufferedWriter? = null
    private var columns: List<String> = emptyList()
    private var startedAtMillis: Long = 0L
    private var sessionFile: File? = null
    private var rowCount = 0

    val isRecording: Boolean get() = job != null

    /** The confirmed Record-button tap. A no-op if a session is already in progress. */
    fun start() {
        if (isRecording) return
        val now = clock.instant()
        val cols = loggablePids.map { it.id }
        logsDir.mkdirs()
        val file = File(logsDir, buildFilename(now, clock.zone))
        val bufferedWriter = openAndWriteHeader(file, cols, now) ?: return

        synchronized(lock) {
            columns = cols
            writer = bufferedWriter
            sessionFile = file
            startedAtMillis = now.toEpochMilli()
            rowCount = 0
        }

        updateSessionIndex(logsDir) { existing ->
            addOrUpdateEntry(
                existing,
                SessionIndexEntry(file.name, now, endedAt = null, rows = 0, pidCount = cols.size),
            )
        }
        activePollSet.setRecordingPids(loggablePids)
        dataSource.start(activePollSet.activePids())

        onStateChanged(RecordingState.Recording(startedAtMillis, file, rowCount = 0))
        job =
            scope.launch(ioDispatcher) {
                while (isActive) {
                    delay(tickInterval)
                    if (!appendRow()) break
                }
            }
    }

    /**
     * Opens [file] and writes its header, or `null` (staying `Idle`, nothing to finalize) if
     * either throws [IOException] — the "storage already full/ejected when Record is tapped"
     * mirror of [appendRow]'s mid-tick guard.
     *
     * Round-2 review (finding 7): [writerFactory] opens eagerly (`File.bufferedWriter()` in
     * production), so if it succeeds but the header write/flush *then* throws, the earlier
     * single-`try` version discarded the already-open [BufferedWriter] without closing it — a
     * leaked fd (until GC) plus a stray, empty/partial CSV left in [logsDir]. The two-stage `try`
     * below keeps a reference to whatever [writerFactory] handed back, so the second catch can
     * close it and delete [file] before returning to `Idle`.
     *
     * `SwallowedException` is suppressed deliberately, not logged: [Recorder] stays Android-free
     * (no `android.util.Log`, see its class KDoc), and the recovery IS the graceful "stay Idle"
     * return, not a rethrow.
     */
    @Suppress("SwallowedException")
    private fun openAndWriteHeader(
        file: File,
        cols: List<String>,
        now: Instant,
    ): BufferedWriter? {
        val bufferedWriter =
            try {
                writerFactory(file)
            } catch (ioException: IOException) {
                return null // never opened — nothing to close.
            }
        return try {
            csvHeaderLines(cols, loggablePids, now, appVersionName, flavor, channel, clock.zone).forEach { line ->
                bufferedWriter.write(line)
                bufferedWriter.newLine()
            }
            bufferedWriter.flush()
            bufferedWriter
        } catch (ioException: IOException) {
            runCatching { bufferedWriter.close() }
            runCatching { file.delete() }
            null
        }
    }

    /**
     * Appends one row under [lock]. Returns `true` to keep ticking, `false` once the session has
     * been finalized — either because [writer]/[sessionFile] were already cleared by a racing
     * [stop] (a defensive no-op; [stop] itself already finalized) or because this tick's write hit
     * an [IOException], in which case [finalize] runs here before returning.
     */
    @Suppress("SwallowedException") // see openAndWriteHeader's KDoc note — same rationale here.
    private fun appendRow(): Boolean {
        val published =
            try {
                synchronized(lock) {
                    val bufferedWriter = writer
                    val file = sessionFile
                    if (bufferedWriter == null || file == null) {
                        // Defensive only — a racing stop()/finalize() already cleared these, so
                        // there is nothing left to finalize here; falling through to the null
                        // branch below still re-asserts Idle exactly like a genuine error would,
                        // which is a harmless no-op per finalize's own idempotence contract.
                        null
                    } else {
                        val row = csvRow(dataSource.readings.value, columns, clock.instant(), startedAtInstant())
                        bufferedWriter.write(row)
                        bufferedWriter.newLine()
                        bufferedWriter.flush()
                        rowCount++
                        RecordingState.Recording(startedAtMillis, file, rowCount)
                    }
                }
            } catch (ioException: IOException) {
                null
            }
        if (published == null) {
            finalize(clock.instant())
            return false
        }
        onStateChanged(published)
        return true
    }

    private fun startedAtInstant(): Instant = Instant.ofEpochMilli(startedAtMillis)

    /** The recording indicator's one-tap stop action. A no-op if nothing is recording. */
    fun stop() {
        if (!isRecording) return
        job?.cancel()
        finalize(clock.instant())
    }

    /**
     * Guarded teardown shared by [stop] and [appendRow]'s error path: under [lock], best-effort
     * flush/close the writer and capture the fields the index update/final published state need,
     * then reset session state to idle — **including [job]**, so [isRecording] reports `false`
     * the moment a session ends however it ended, not only via an explicit [stop] call (round-1
     * review fix: the error path used to leave the stale [job] reference behind, so a mid-tick
     * IOException finalized everything else but [isRecording] kept reporting `true`). Safe to
     * call twice in a row (e.g. a genuine concurrent [stop] racing an IO-error finalize) — the
     * second call finds [sessionFile] already `null` and just re-publishes `Idle`/re-asserts the
     * reverted poll set, both idempotent.
     */
    private fun finalize(endedAt: Instant) {
        var finishedFile: File? = null
        var finishedRows = 0
        var finishedPidCount = 0
        synchronized(lock) {
            finishedFile = sessionFile
            finishedRows = rowCount
            finishedPidCount = columns.size
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
            sessionFile = null
            job = null
            columns = emptyList()
        }

        val file = finishedFile
        if (file != null) {
            updateSessionIndex(logsDir) { existing ->
                addOrUpdateEntry(
                    existing,
                    SessionIndexEntry(file.name, startedAtInstant(), endedAt, finishedRows, finishedPidCount),
                )
            }
        }
        activePollSet.setRecordingPids(emptyList())
        dataSource.start(activePollSet.activePids())
        onStateChanged(RecordingState.Idle)
    }
}
