package com.revel.obdgauge.ble.scan

import com.revel.obdgauge.model.LinkError

/**
 * Runs a multi-pass scan, pure of Android: [runPass] is whatever actually sweeps the radio.
 *
 * Only "nothing found" is worth another pass. A scan that failed for a *reason* — throttled,
 * permission revoked, adapter turned off underneath us — will fail the same way a second time,
 * and retrying it burns one of the five scan starts the system allows per 30 seconds.
 */
suspend fun runScanPasses(
    passes: List<ScanPass>,
    runPass: suspend (ScanPass) -> ScanOutcome,
): ScanOutcome {
    var outcome: ScanOutcome = ScanOutcome.Failed(LinkError.DeviceNotFound)
    for (pass in passes) {
        outcome = runPass(pass)
        if (!worthAnotherPass(outcome)) {
            break
        }
    }
    return outcome
}

/** True only when the pass completed and simply saw no dongle. */
fun worthAnotherPass(outcome: ScanOutcome): Boolean =
    outcome is ScanOutcome.Failed && outcome.error == LinkError.DeviceNotFound

/**
 * Android throttles an app to 5 `startScan` calls per rolling 30 seconds; exceeding it returns
 * `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` and — worse — the app gets nothing back for the rest of
 * the window. Two passes per connect means three connect attempts in half a minute is enough to
 * hit it, which is exactly what a user does when the dongle is not plugged in.
 *
 * So the budget is enforced before the sweep rather than discovered after it: a refused pass is
 * reported as throttled immediately, with the sweeps the system did allow still spent on the
 * most likely candidate. Pure — the clock is a parameter.
 */
class ScanBudget(
    private val maxStarts: Int = MAX_STARTS_PER_WINDOW,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    private val starts = ArrayDeque<Long>()

    /** Records a scan start at [nowMillis], or returns false if the budget is spent. */
    fun tryConsume(nowMillis: Long): Boolean {
        while (starts.isNotEmpty() && nowMillis - starts.first() >= windowMillis) {
            starts.removeFirst()
        }
        return if (starts.size >= maxStarts) {
            false
        } else {
            starts.addLast(nowMillis)
            true
        }
    }

    /** Milliseconds until one more start becomes available, 0 when one is available now. */
    fun retryAfterMillis(nowMillis: Long): Long =
        if (starts.size < maxStarts) {
            0
        } else {
            (windowMillis - (nowMillis - starts.first())).coerceAtLeast(0)
        }

    companion object {
        const val MAX_STARTS_PER_WINDOW = 5
        const val WINDOW_MILLIS = 30_000L
    }
}
