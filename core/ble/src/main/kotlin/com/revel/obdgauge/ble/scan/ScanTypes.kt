package com.revel.obdgauge.ble.scan

import com.revel.obdgauge.model.LinkError
import java.util.UUID
import kotlin.time.Duration

/** A BLE advertisement, reduced to the fields the filter and the log care about. */
data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val serviceUuids: List<UUID> = emptyList(),
    val rssi: Int = 0,
)

/** Terminal result of a scan. */
sealed interface ScanOutcome {
    data class Found(
        val device: DiscoveredDevice,
    ) : ScanOutcome

    data class Failed(
        val error: LinkError,
    ) : ScanOutcome
}

/** What the scan state machine wants done after one event. */
sealed interface ScanDecision {
    /** Nothing selectable yet; keep scanning. [reason] is for the debug log only. */
    data class Continue(
        val reason: String,
    ) : ScanDecision

    data class Select(
        val device: DiscoveredDevice,
    ) : ScanDecision

    data class Fail(
        val error: LinkError,
    ) : ScanDecision
}

/**
 * One sweep of a two-pass scan.
 *
 * Pass 1 hands the candidate service UUIDs to the BLE stack as hardware [android.bluetooth.le.ScanFilter]s
 * — cheap, battery-friendly, and the behaviour OBD-17 asks for. But plenty of ELM327 clones
 * advertise a name and nothing else, so a UUID-filtered sweep alone would never see them.
 * Pass 2 therefore sweeps unfiltered and matches in software, and only runs if pass 1 found
 * nothing. Cheap in the common case, correct in the awkward one.
 */
data class ScanPass(
    val filterByServiceUuid: Boolean,
    val timeout: Duration,
)
