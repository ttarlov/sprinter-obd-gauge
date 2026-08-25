package com.revel.obdgauge.app.recording

import com.revel.obdgauge.app.service.ActivePollSet
import com.revel.obdgauge.app.service.RecordingVehicleDataSource
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PollPriority
import com.revel.obdgauge.model.Reading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.Writer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

/**
 * Plain-JUnit orchestration tests for [Recorder] — a temp directory stands in for
 * `getExternalFilesDir("logs")`, [RecordingVehicleDataSource] (reused from
 * `ConnectionServiceControllerTest`, same `app/src/test` compile unit) stands in for the real
 * `VehicleDataSource`, and the tick loop's `delay(1s)` runs on `runTest`'s own virtual clock —
 * this suite runs instantly, no Robolectric needed. Robolectric only re-enters to pin this wired
 * for real inside `ObdConnectionService` — see `ObdConnectionServiceTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecorderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val zone = ZoneOffset.UTC
    private val fixedInstant = Instant.parse("2026-08-18T22:07:58Z")
    private val clock = Clock.fixed(fixedInstant, zone)

    private val coolant =
        PidDefinition(
            id = "coolant",
            label = "Coolant",
            unit = MeasurementUnit.FAHRENHEIT,
            request = ObdRequest.StandardPid(mode = 1, pid = 5),
            parse = { 0.0 },
            pollPriority = PollPriority.SLOW,
        )
    private val rpm =
        PidDefinition(
            id = "rpm",
            label = "RPM",
            unit = MeasurementUnit.RPM,
            request = ObdRequest.StandardPid(mode = 1, pid = 0x0C),
            parse = { 0.0 },
            pollPriority = PollPriority.FAST,
        )
    private val loggablePids = listOf(coolant, rpm)

    private fun csvFileIn(logsDir: File): File = logsDir.listFiles { file -> file.name.startsWith("obdlog_") }!!.first()

    @Test
    fun `start writes the header and an in-progress index entry, and widens the poll set`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val activePollSet = ActivePollSet()
            val recorder = buildRecorder(logsDir, dataSource, activePollSet)

            recorder.start()

            val csvFile = csvFileIn(logsDir)
            val lines = csvFile.readLines()
            assertEquals("# sprinter-obd-gauge log v1", lines[0])
            assertEquals("timestamp,elapsed_ms,coolant,rpm", lines[5])

            val index = readSessionIndex(logsDir)
            assertEquals(1, index.size)
            assertEquals(csvFile.name, index[0].file)
            assertEquals(null, index[0].endedAt)
            assertEquals(0, index[0].rows)
            assertEquals(2, index[0].pidCount)

            assertEquals(loggablePids, activePollSet.recordingPids.value)
            assertEquals(1, dataSource.startCallCount)
            recorder.stop()
        }

    @Test
    fun `a repeat start while already recording is a no-op`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val recorder = buildRecorder(logsDir, dataSource, ActivePollSet())

            recorder.start()
            recorder.start()

            assertEquals(1, logsDir.listFiles { file -> file.name.startsWith("obdlog_") }!!.size)
            recorder.stop()
        }

    @Test
    fun `each tick appends one row and reports the growing row count`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val states = mutableListOf<RecordingState>()
            val recorder = buildRecorder(logsDir, dataSource, ActivePollSet(), states)

            recorder.start()
            dataSource.setReadings(mapOf("coolant" to Reading("coolant", 190.0, fixedInstant, stale = false)))
            advanceTimeBy(2_500)
            runCurrent()

            val dataLines = csvFileIn(logsDir).readLines().drop(HEADER_LINE_COUNT)
            assertEquals(2, dataLines.size)
            assertTrue(dataLines.all { it.contains("190.00") })
            val recordingStates = states.filterIsInstance<RecordingState.Recording>()
            assertEquals(2, recordingStates.last().rowCount)
            recorder.stop()
        }

    @Test
    fun `stop flushes and closes the file, updates the index, and reverts the poll set`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val activePollSet = ActivePollSet()
            val states = mutableListOf<RecordingState>()
            val recorder = buildRecorder(logsDir, dataSource, activePollSet, states)

            recorder.start()
            advanceTimeBy(3_000)
            runCurrent()
            recorder.stop()

            val index = readSessionIndex(logsDir)
            assertEquals(1, index.size)
            assertEquals(3, index[0].rows)
            assertNotNull(index[0].endedAt)

            assertTrue(activePollSet.recordingPids.value.isEmpty())
            assertEquals(2, dataSource.startCallCount) // once on start, once on stop's revert.
            assertEquals(RecordingState.Idle, states.last())
        }

    @Test
    fun `stop while idle is a safe no-op`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val recorder = buildRecorder(logsDir, dataSource, ActivePollSet())

            recorder.stop()

            assertEquals(0, dataSource.startCallCount)
            assertFalse(recorder.isRecording)
        }

    @Test
    fun `stopping cancels the ticker - no further rows append after stop`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val recorder = buildRecorder(logsDir, dataSource, ActivePollSet())

            recorder.start()
            advanceTimeBy(1_000)
            runCurrent()
            recorder.stop()
            val rowsAtStop = csvFileIn(logsDir).readLines().drop(HEADER_LINE_COUNT).size

            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(rowsAtStop, csvFileIn(logsDir).readLines().drop(HEADER_LINE_COUNT).size)
        }

    // ---- Round-1 review, finding 1: appendRow's IOException path must finalize, never throw ----

    @Test
    fun `an IOException on a tick's write finalizes the session instead of throwing`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val activePollSet = ActivePollSet()
            val states = mutableListOf<RecordingState>()
            val throwingWriter = SwitchableThrowingWriter()
            val recorder =
                buildRecorder(
                    logsDir,
                    dataSource,
                    activePollSet,
                    states,
                    writerFactory = { file -> BufferedWriter(throwingWriter.also { it.target = file }) },
                )

            recorder.start() // header write succeeds — throwingWriter isn't failing yet.
            assertTrue(recorder.isRecording)
            throwingWriter.shouldThrow = true

            // The tick's own write/flush now throws — if this escaped uncaught, runTest's own
            // uncaught-exception detection would fail this test on its own; reaching the
            // assertions below at all is already proof nothing escaped.
            advanceTimeBy(1_000)
            runCurrent()

            assertFalse(recorder.isRecording)
            assertEquals(RecordingState.Idle, states.last())
            val entry = readSessionIndex(logsDir).first()
            assertNotNull(entry.endedAt)
            assertTrue(activePollSet.recordingPids.value.isEmpty())

            // A stop() after an already-error-finalized session is the same idempotent no-op as
            // any other post-session stop — must not throw or double-finalize.
            recorder.stop()
            assertEquals(1, readSessionIndex(logsDir).size)
        }

    @Test
    fun `an IOException opening the file at start leaves the session Idle, not half-started`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val states = mutableListOf<RecordingState>()
            val alwaysThrows =
                SwitchableThrowingWriter().apply { shouldThrow = true }
            val recorder =
                buildRecorder(
                    logsDir,
                    dataSource,
                    ActivePollSet(),
                    states,
                    writerFactory = { file -> BufferedWriter(alwaysThrows.also { it.target = file }) },
                )

            recorder.start()

            assertFalse(recorder.isRecording)
            assertTrue(states.isEmpty()) // never even published Recording.
            assertTrue(readSessionIndex(logsDir).isEmpty())
            assertEquals(0, dataSource.startCallCount) // never widened the poll set.
        }

    // Round-2 review, finding 7: the case above (`alwaysThrows`) never actually opens a real
    // file/fd — `SwitchableThrowingWriter.real` is `by lazy` and every write/flush short-circuits
    // on `shouldThrow` before ever touching it, so that test can't prove a leak was fixed. This
    // one opens a REAL file eagerly (in the writerFactory lambda, before any write/flush call),
    // then fails on the header write itself — proving openAndWriteHeader's catch actually closes
    // the writer and deletes the stray file, not just returns null.
    @Test
    fun `an IOException writing the header closes the writer and deletes the stray file`() =
        runTest {
            val logsDir = tempFolder.newFolder("logs")
            val dataSource = RecordingVehicleDataSource()
            val states = mutableListOf<RecordingState>()
            val recorder =
                buildRecorder(
                    logsDir,
                    dataSource,
                    ActivePollSet(),
                    states,
                    writerFactory = { file -> BufferedWriter(EagerlyOpenedThrowingWriter(file)) },
                )

            recorder.start()

            assertFalse(recorder.isRecording)
            assertTrue(states.isEmpty())
            // The stray file must be gone, not left behind as an empty/partial CSV.
            assertTrue(logsDir.listFiles { file -> file.name.startsWith("obdlog_") }.orEmpty().isEmpty())
        }

    private fun TestScope.buildRecorder(
        logsDir: File,
        dataSource: RecordingVehicleDataSource,
        activePollSet: ActivePollSet,
        states: MutableList<RecordingState> = mutableListOf(),
        writerFactory: (File) -> BufferedWriter = { it.bufferedWriter() },
    ) = Recorder(
        dataSource = dataSource,
        activePollSet = activePollSet,
        loggablePids = loggablePids,
        logsDir = logsDir,
        scope = backgroundScope,
        clock = clock,
        appVersionName = "0.1.0-test",
        flavor = "demo",
        channel = "main",
        onStateChanged = { states.add(it) },
        tickInterval = 1.seconds,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        writerFactory = writerFactory,
    )

    /**
     * A [Writer] that writes through to a real file (so header content written before
     * [shouldThrow] flips is genuinely on disk, matching production behavior) until switched to
     * throwing [IOException] on demand — simulating storage going away mid-session without
     * depending on [BufferedWriter]'s internal buffering/flush-batching behavior for timing.
     */
    private class SwitchableThrowingWriter : Writer() {
        @Volatile
        var shouldThrow = false
        lateinit var target: File
        private val real by lazy { target.writer() }

        override fun write(
            cbuf: CharArray,
            off: Int,
            len: Int,
        ) {
            if (shouldThrow) throw IOException("simulated storage failure")
            real.write(cbuf, off, len)
        }

        override fun flush() {
            if (shouldThrow) throw IOException("simulated storage failure")
            real.flush()
        }

        override fun close() {
            runCatching { real.close() }
        }
    }

    /**
     * Opens a genuinely real underlying [Writer] on [target] **eagerly**, in the constructor —
     * unlike [SwitchableThrowingWriter]'s `by lazy` — then throws [IOException] on every
     * write/flush, unconditionally. Simulates `writerFactory` succeeding (a real fd/file exists)
     * but the header content itself failing to write, for round-2 review finding 7's leak test.
     */
    private class EagerlyOpenedThrowingWriter(
        target: File,
    ) : Writer() {
        private val real = target.writer()

        override fun write(
            cbuf: CharArray,
            off: Int,
            len: Int,
        ): Nothing = throw IOException("simulated storage failure")

        override fun flush(): Nothing = throw IOException("simulated storage failure")

        override fun close() {
            runCatching { real.close() }
        }
    }

    private companion object {
        const val HEADER_LINE_COUNT = 6
    }
}
