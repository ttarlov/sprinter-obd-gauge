package com.revel.obdgauge.ble.scan

/**
 * Finds an OBD dongle. An interface so `BleObdLink`'s connect flow — including "the fast path
 * must not scan at all" — is testable without a radio.
 */
interface BleScanner {
    /**
     * Runs [passes] in order, stopping at the first device that matches (or the first failure
     * that is not "nothing found"). [preferredAddress], when set, wins over the name/UUID
     * filter: a device that connected before is worth taking whatever it calls itself now.
     */
    suspend fun scan(
        passes: List<ScanPass>,
        preferredAddress: String? = null,
    ): ScanOutcome
}
