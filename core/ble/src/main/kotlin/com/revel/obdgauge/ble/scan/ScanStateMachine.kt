package com.revel.obdgauge.ble.scan

import com.revel.obdgauge.model.LinkError

/**
 * The scan's decision logic, extracted whole from the Android `ScanCallback` so it can be
 * driven event-by-event on the JVM.
 *
 * Terminal once it decides: after a [ScanDecision.Select] or [ScanDecision.Fail], late
 * callbacks (the BLE stack keeps delivering for a beat after `stopScan`) are absorbed as
 * [ScanDecision.Continue] rather than producing a second, contradictory decision.
 *
 * @param filter which advertisements count as a dongle.
 * @param preferredAddress a previously-successful device address. If it shows up it is selected
 *   immediately, even when [filter] would not have matched it — it connected before, which is
 *   stronger evidence than any name heuristic.
 */
class ScanStateMachine(
    private val filter: DongleFilter = DongleFilter(),
    private val preferredAddress: String? = null,
) {
    private val seen = mutableSetOf<String>()
    private var decided = false

    /** Addresses observed this sweep, in first-seen order — logged when a scan comes up empty. */
    val seenAddresses: Set<String> get() = seen

    fun onAdvertisement(device: DiscoveredDevice): ScanDecision =
        when {
            decided -> ScanDecision.Continue("already decided")
            device.address.equals(preferredAddress, ignoreCase = true) -> select(device)
            filter.matches(device) -> select(device)
            else -> {
                seen += device.address
                ScanDecision.Continue("no match: ${device.address} (${device.name ?: "unnamed"})")
            }
        }

    fun onScanFailed(errorCode: Int): ScanDecision = fail(scanFailureError(errorCode))

    /** The sweep ran its full duration without a match. */
    fun onTimeout(): ScanDecision = fail(LinkError.DeviceNotFound)

    private fun select(device: DiscoveredDevice): ScanDecision {
        seen += device.address
        decided = true
        return ScanDecision.Select(device)
    }

    private fun fail(error: LinkError): ScanDecision =
        if (decided) {
            ScanDecision.Continue("already decided")
        } else {
            decided = true
            ScanDecision.Fail(error)
        }

    companion object {
        /**
         * Prefix for the "5 scan starts per 30 s" throttle, so `:app` can recognise it and say
         * "wait half a minute" instead of "no dongle found". `LinkError` is a frozen
         * `:core:model` contract — a dedicated variant needs an orchestrator decision, so a
         * stable message prefix is the honest stand-in.
         */
        const val THROTTLED = "BLE scan throttled"

        /**
         * Maps an `android.bluetooth.le.ScanCallback.SCAN_FAILED_*` code onto a typed
         * [LinkError]. None of these are recoverable inside a single scan, so they all abort it.
         */
        fun scanFailureError(errorCode: Int): LinkError =
            if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) {
                LinkError.Unknown("$THROTTLED: ${ScanBudget.MAX_STARTS_PER_WINDOW} scans per 30 s exceeded")
            } else {
                LinkError.Unknown("BLE scan failed: ${describe(errorCode)}")
            }

        /** True for the error [scanFailureError] produces when the system throttled the scan. */
        fun isThrottled(error: LinkError): Boolean = error is LinkError.Unknown && error.message.startsWith(THROTTLED)

        private fun describe(errorCode: Int): String =
            when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "already started (1)"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "application registration failed (2)"
                SCAN_FAILED_INTERNAL_ERROR -> "internal error (3)"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported (4)"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "out of hardware resources (5)"
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "scanning too frequently (6)"
                else -> "code $errorCode"
            }

        private const val SCAN_FAILED_ALREADY_STARTED = 1
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
        private const val SCAN_FAILED_INTERNAL_ERROR = 3
        private const val SCAN_FAILED_FEATURE_UNSUPPORTED = 4
        private const val SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5
        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
