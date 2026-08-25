package com.revel.obdgauge.app.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/** Plain-JUnit tests for `SessionIndex.kt` — the hand-rolled `logs/index.json` codec. */
class SessionIndexTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val started = Instant.parse("2026-08-18T22:07:58Z")
    private val ended = Instant.parse("2026-08-18T22:22:58Z")

    @Test
    fun `encoding an empty list is a valid empty JSON array`() {
        assertEquals("[]", encodeSessionIndex(emptyList()))
    }

    @Test
    fun `an in-progress entry round-trips with a null endedAt`() {
        val entry = SessionIndexEntry("obdlog_2026-08-18_2207.csv", started, endedAt = null, rows = 0, pidCount = 5)

        val decoded = decodeSessionIndex(encodeSessionIndex(listOf(entry)))

        assertEquals(listOf(entry), decoded)
    }

    @Test
    fun `a completed entry round-trips exactly, including multi-entry lists`() {
        val first = SessionIndexEntry("obdlog_2026-08-18_2207.csv", started, ended, rows = 900, pidCount = 5)
        val second = SessionIndexEntry("obdlog_2026-08-19_0800.csv", ended, null, rows = 12, pidCount = 5)

        val decoded = decodeSessionIndex(encodeSessionIndex(listOf(first, second)))

        assertEquals(listOf(first, second), decoded)
    }

    @Test
    fun `a filename containing a quote round-trips via escaping`() {
        val entry = SessionIndexEntry("weird\"file.csv", started, null, rows = 0, pidCount = 1)

        val decoded = decodeSessionIndex(encodeSessionIndex(listOf(entry)))

        assertEquals(listOf(entry), decoded)
    }

    @Test
    fun `decoding malformed text yields no entries rather than throwing`() {
        assertTrue(decodeSessionIndex("not json at all").isEmpty())
    }

    @Test
    fun `addOrUpdateEntry appends a new file`() {
        val entry = SessionIndexEntry("a.csv", started, null, rows = 0, pidCount = 5)

        val result = addOrUpdateEntry(emptyList(), entry)

        assertEquals(listOf(entry), result)
    }

    @Test
    fun `addOrUpdateEntry replaces the existing row for the same file, in place`() {
        val startEntry = SessionIndexEntry("a.csv", started, null, rows = 0, pidCount = 5)
        val other = SessionIndexEntry("b.csv", started, null, rows = 0, pidCount = 5)
        val stopEntry = SessionIndexEntry("a.csv", started, ended, rows = 900, pidCount = 5)

        val afterStart = addOrUpdateEntry(listOf(other), startEntry)
        val afterStop = addOrUpdateEntry(afterStart, stopEntry)

        assertEquals(listOf(other, stopEntry), afterStop)
    }

    @Test
    fun `readSessionIndex on a fresh directory returns empty, not a crash`() {
        assertTrue(readSessionIndex(tempFolder.newFolder("logs")).isEmpty())
    }

    @Test
    fun `writeSessionIndex then readSessionIndex round-trips through a real file`() {
        val logsDir = tempFolder.newFolder("logs")
        val entry = SessionIndexEntry("obdlog_2026-08-18_2207.csv", started, ended, rows = 900, pidCount = 5)

        writeSessionIndex(logsDir, listOf(entry))
        val readBack = readSessionIndex(logsDir)

        assertEquals(listOf(entry), readBack)
    }

    @Test
    fun `updateSessionIndex applies the transform and persists the result`() {
        val logsDir = tempFolder.newFolder("logs")
        val entry = SessionIndexEntry("a.csv", started, null, rows = 0, pidCount = 5)

        val returned = updateSessionIndex(logsDir) { existing -> addOrUpdateEntry(existing, entry) }

        assertEquals(listOf(entry), returned)
        assertEquals(listOf(entry), readSessionIndex(logsDir))
    }

    // Round-1 review, finding 2: Recorder (service/IO thread) and RecordingsViewModel.delete
    // (main thread) both mutate logs/index.json — a bare read-modify-write on each side can lose
    // the other's write. This drives real concurrent writers at updateSessionIndex directly (not
    // through Recorder/RecordingsViewModel, which would need Robolectric/coroutines scaffolding
    // just to get two threads racing) and asserts every one of their entries survives — the
    // property the shared indexLock exists to guarantee.
    @Test
    fun `concurrent updateSessionIndex calls from multiple threads never lose an update`() {
        val logsDir = tempFolder.newFolder("logs")
        val writerCount = 20

        val threads =
            (0 until writerCount).map { i ->
                Thread {
                    val entry = SessionIndexEntry("session-$i.csv", started, null, rows = i, pidCount = 5)
                    updateSessionIndex(logsDir) { existing -> addOrUpdateEntry(existing, entry) }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val finalIndex = readSessionIndex(logsDir)
        assertEquals(writerCount, finalIndex.size)
        assertEquals((0 until writerCount).map { "session-$it.csv" }.toSet(), finalIndex.map { it.file }.toSet())
    }
}
