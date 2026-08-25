package com.revel.obdgauge.app.recording

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure display formatting for the Recordings list — same "no Android/Robolectric needed" split
// GaugeFormatting.kt already follows for the dashboard's own gauge values.

private val SESSION_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US)
private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = BYTES_PER_KB * 1024L
private const val SECONDS_PER_MINUTE = 60
private const val MINUTES_PER_HOUR = 60

/** `Aug 18, 2026 10:07 PM`, in [zone] (defaulting to the device's own). */
fun formatSessionDate(
    startedAt: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = SESSION_DATE_FORMATTER.format(startedAt.atZone(zone))

/**
 * `12:34` (mm:ss) or `1:02:34` (h:mm:ss) once past an hour. A still-recording session (`endedAt
 * == null`) shows "Recording…" instead — the Recordings list only reads a session's OWN entry
 * (updated on stop), so this is reachable only in the brief window a listing loads mid-session.
 */
fun formatSessionDuration(
    startedAt: Instant,
    endedAt: Instant?,
): String {
    if (endedAt == null) return "Recording…"
    val totalSeconds = Duration.between(startedAt, endedAt).seconds.coerceAtLeast(0)
    val hours = totalSeconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
    val minutes = (totalSeconds / SECONDS_PER_MINUTE) % MINUTES_PER_HOUR
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/** `842 B` / `12.4 KB` / `3.1 MB`. */
fun formatFileSize(bytes: Long): String =
    when {
        bytes < BYTES_PER_KB -> "$bytes B"
        bytes < BYTES_PER_MB -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB.toDouble())
        else -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB.toDouble())
    }

private const val MILLIS_PER_SECOND = 1_000L
private const val ELAPSED_SECONDS_PER_MINUTE = 60

/** `mm:ss` for the dashboard's live recording indicator — [elapsedMillis] is always `>= 0`. */
fun formatElapsedRecording(elapsedMillis: Long): String {
    val totalSeconds = (elapsedMillis / MILLIS_PER_SECOND).coerceAtLeast(0)
    val minutes = totalSeconds / ELAPSED_SECONDS_PER_MINUTE
    val seconds = totalSeconds % ELAPSED_SECONDS_PER_MINUTE
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
