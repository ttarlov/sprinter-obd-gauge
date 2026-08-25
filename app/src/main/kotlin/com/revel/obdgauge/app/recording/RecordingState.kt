package com.revel.obdgauge.app.recording

import java.io.File

/**
 * OBD-70: what [Recorder] is doing right now, published to the UI via [RecordingBridge]. Plain
 * Kotlin (no Android types beyond [java.io.File], which is fine on a plain JVM) so it's usable
 * from `app/src/test` without Robolectric.
 */
sealed interface RecordingState {
    /** No session in progress — the dashboard's Record control shows the idle "Rec" button. */
    data object Idle : RecordingState

    /**
     * A session is actively writing to [file], one row per second since [startedAtMillis].
     * [rowCount] is how many data rows have been appended so far (excludes the header) — the
     * indicator can show it, though the v1 UI only surfaces the mm:ss elapsed timer.
     */
    data class Recording(
        val startedAtMillis: Long,
        val file: File,
        val rowCount: Int,
    ) : RecordingState
}
