package com.revel.obdgauge.ble.traffic

import android.util.Log

/**
 * The OBD-48 sink: every TX line, every RX line and every link transition to logcat under
 * `ObdTraffic`, in order, with millisecond stamps.
 *
 * ```
 * adb logcat -c && adb logcat -s ObdTraffic
 * ```
 *
 * ### Debug source set, deliberately
 * This file lives in `src/debug/` — not behind a `BuildConfig.DEBUG` branch — because the app is
 * built with `isMinifyEnabled = false`, so a runtime branch would leave the sink class, the tag
 * string and every formatting literal sitting in the release dex. Variant source sets make the
 * absence structural: the release variant of `:core:ble` compiles [defaultTrafficLog] from
 * `src/release/` instead and this class does not exist in it. Same fence OBD-19 used for the
 * console, verified the same way.
 */
internal class LogcatTrafficLog : TrafficLog {
    override fun record(entry: TrafficEntry) {
        Log.i(TAG, TrafficFormat.format(entry, System.currentTimeMillis()))
    }

    private companion object {
        /** Its own tag, so a drive capture is one `adb logcat -s` and no `ObdBle` narrative. */
        const val TAG = "ObdTraffic"
    }
}

/** Debug builds capture traffic. See the release-variant twin of this file. */
internal fun defaultTrafficLog(): TrafficLog = LogcatTrafficLog()
