package com.revel.obdgauge.app.recording

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class RecordingsFormattingTest {
    private val started = Instant.parse("2026-08-18T22:07:58Z")

    @Test
    fun `formatSessionDuration renders mm-ss under an hour`() {
        val ended = started.plusSeconds(754) // 12m34s

        assertEquals("12:34", formatSessionDuration(started, ended))
    }

    @Test
    fun `formatSessionDuration renders h-mm-ss past an hour`() {
        val ended = started.plusSeconds(3754) // 1h02m34s

        assertEquals("1:02:34", formatSessionDuration(started, ended))
    }

    @Test
    fun `formatSessionDuration shows Recording for a still-open session`() {
        assertEquals("Recording…", formatSessionDuration(started, endedAt = null))
    }

    @Test
    fun `formatFileSize renders bytes, kilobytes, and megabytes`() {
        assertEquals("842 B", formatFileSize(842))
        assertEquals("12.4 KB", formatFileSize(12_700))
        assertEquals("3.1 MB", formatFileSize(3_250_000))
    }

    @Test
    fun `formatElapsedRecording renders mm-ss and never goes negative`() {
        assertEquals("00:00", formatElapsedRecording(0))
        assertEquals("00:05", formatElapsedRecording(5_000))
        assertEquals("02:03", formatElapsedRecording(123_000))
    }
}
