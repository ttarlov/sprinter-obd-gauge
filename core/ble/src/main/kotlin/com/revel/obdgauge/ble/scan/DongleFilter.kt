package com.revel.obdgauge.ble.scan

import com.revel.obdgauge.ble.gatt.SerialProfileProbe
import java.util.UUID

/**
 * Decides whether an advertisement looks like an OBD dongle.
 *
 * Two independent signals, OR'd: the advertised name starts with a known dongle prefix, or the
 * advertisement carries one of the candidate serial service UUIDs. Prefix matching (not
 * `contains`) keeps a phone called "Dad's OBD-blocked hotspot" out of the results; the UUID
 * signal catches nameless dongles.
 *
 * Pure: [matches] takes plain data, so every prefix in the list is testable without a radio.
 */
data class DongleFilter(
    val namePrefixes: List<String> = DEFAULT_NAME_PREFIXES,
    val serviceUuids: List<UUID> = SerialProfileProbe.CANDIDATE_SERVICE_UUIDS,
) {
    fun matches(device: DiscoveredDevice): Boolean = matchesName(device.name) || matchesServiceUuid(device.serviceUuids)

    fun matchesName(name: String?): Boolean {
        val normalized = name?.trim()?.uppercase().orEmpty()
        return normalized.isNotEmpty() && namePrefixes.any(normalized::startsWith)
    }

    fun matchesServiceUuid(advertised: List<UUID>): Boolean = advertised.any(serviceUuids::contains)

    companion object {
        /**
         * Advertised-name prefixes seen across the ELM327 BLE clone market, uppercased.
         *
         * Unverified against real hardware (OBD-22): the Veepeak OBDCheck BLE+ is expected to
         * advertise as `VEEPEAK` or `OBDCheck`, both covered here. A dongle whose name is not on
         * this list still connects through the service-UUID signal or the remembered-device
         * fast path, so a miss here is an inconvenience, not a dead end.
         */
        val DEFAULT_NAME_PREFIXES: List<String> =
            listOf(
                "OBD",
                "VEEPEAK",
                "VLINKER",
                "V-LINK",
                "IOS-VLINK",
                "VGATE",
                "ICAR",
                "ELM",
                "LELINK",
                "KONNWEI",
                "CARISTA",
                "BLE-OBD",
                "BLE_OBD",
                "VIECAR",
                "TONWON",
            )
    }
}
