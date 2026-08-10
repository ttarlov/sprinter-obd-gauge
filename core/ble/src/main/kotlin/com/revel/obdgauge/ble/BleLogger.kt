package com.revel.obdgauge.ble

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where this module's connect/probe narrative goes.
 *
 * An interface, not a direct `Log.d` call, for two reasons: unit tests assert on what was
 * logged (the UUID probe result in particular — "log what was chosen" is an acceptance
 * criterion, so it is worth testing), and `Log` is one of the Android stubs that throws in a
 * plain JVM unit test.
 */
fun interface BleLogger {
    fun log(message: String)

    companion object {
        /** Discards everything. Default for tests that do not care. */
        val NONE: BleLogger = BleLogger { }
    }
}

/** Writes to logcat under a single tag, so a bring-up session is one `adb logcat -s` away. */
@Singleton
class AndroidBleLogger
    @Inject
    constructor() : BleLogger {
        override fun log(message: String) {
            Log.i(TAG, message)
        }

        private companion object {
            const val TAG = "ObdBle"
        }
    }
