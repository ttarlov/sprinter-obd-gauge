package com.revel.obdgauge.app.recording

import android.content.Context
import java.io.File

/**
 * `context.getExternalFilesDir("logs")` — needs **no runtime permission on any API level**
 * (API-23 safe, see this issue's Garmin constraints) and is visible over USB-MTP, unlike
 * `getFilesDir()`. Falls back to internal storage only in the rare case external storage is
 * genuinely unavailable (removable media missing) — a degraded-but-working recorder beats a
 * crash; the USB-pull promise just doesn't hold in that fallback case.
 *
 * The one shared definition [com.revel.obdgauge.app.service.ObdConnectionService] (writing) and
 * `RecordingsViewModel` (reading/sharing/deleting) both call, so they can never disagree about
 * where the logs live.
 */
fun logsDir(context: Context): File = context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")
