package com.revel.obdgauge.ble.gatt

import java.util.UUID

/** One known dongle family: which service and characteristics to look for. */
data class CandidateProfile(
    val name: String,
    val service: UUID,
    val notify: UUID,
    val write: UUID,
)

/**
 * Picks the serial service/characteristic pair out of a discovered GATT tree.
 *
 * There is no single "ELM327 BLE" UUID — the dongles are a zoo of BLE-to-UART bridge chips,
 * each with its own vendor service. So this probes a candidate list in order (most specific
 * and most commonly-shipped first) and, if nothing matches, falls back to the structural
 * definition of a serial port: **the first non-housekeeping service exposing both a notifiable
 * and a writable characteristic**. That fallback is what keeps an unknown dongle working
 * instead of failing at bring-up in a parking lot.
 *
 * Pure by construction: the input is plain data, so every branch below is JVM-testable, and
 * the real `BluetoothGatt` never enters a unit test.
 */
object SerialProfileProbe {
    /**
     * Candidate order. The list is ordered by specificity first (a family that names distinct
     * notify/write characteristics is checked before the same service used single-characteristic)
     * and prevalence second.
     *
     * The target hardware, a Veepeak OBDCheck BLE+, is expected to land on [FFF0_SPLIT] —
     * expected, not verified: no real device has been probed yet (OBD-22).
     */
    val CANDIDATES: List<CandidateProfile> =
        listOf(
            FFF0_SPLIT,
            FFF0_SINGLE,
            FFE0_SPLIT,
            FFE0_SINGLE,
            LELINK_18F0,
            NORDIC_UART,
        )

    /**
     * Services that never carry a vendor serial port, skipped by the fallback scan so it cannot
     * latch onto, say, a writable+notifiable pair inside the Generic Attribute service.
     */
    val HOUSEKEEPING_SERVICES: Set<UUID> =
        setOf(
            shortUuid("1800"), // Generic Access
            shortUuid("1801"), // Generic Attribute
            shortUuid("180A"), // Device Information
            shortUuid("180F"), // Battery Service
            shortUuid("FE59"), // Nordic DFU
        )

    /** Every service UUID worth putting in a hardware scan filter. */
    val CANDIDATE_SERVICE_UUIDS: List<UUID> = CANDIDATES.map(CandidateProfile::service).distinct()

    /** Returns the profile to use, or `null` if this device exposes nothing serial-shaped. */
    fun probe(services: List<GattServiceInfo>): SerialProfile? = matchCandidate(services) ?: matchFallback(services)

    /**
     * A device may legitimately expose the same service UUID more than once (clones that ship a
     * primary and a secondary instance of the vendor service, only one of which is wired up), so
     * every instance is tried before the candidate is written off.
     */
    private fun matchCandidate(services: List<GattServiceInfo>): SerialProfile? =
        CANDIDATES.firstNotNullOfOrNull { candidate ->
            services
                .filter { it.uuid == candidate.service }
                .firstNotNullOfOrNull { service ->
                    val notify = service.characteristics.firstOrNull { it.uuid == candidate.notify && it.isNotifiable }
                    val write = service.characteristics.firstOrNull { it.uuid == candidate.write && it.isWritable }
                    if (notify == null || write == null) {
                        null
                    } else {
                        profile(service, notify, write, ProfileSource.Candidate(candidate.name))
                    }
                }
        }

    private fun matchFallback(services: List<GattServiceInfo>): SerialProfile? =
        services
            .asSequence()
            .filterNot { it.uuid in HOUSEKEEPING_SERVICES }
            .firstNotNullOfOrNull { service ->
                val notify = service.characteristics.firstOrNull(GattCharacteristicInfo::isNotifiable)
                val write = service.characteristics.firstOrNull(GattCharacteristicInfo::isWritable)
                if (notify == null || write == null) {
                    null
                } else {
                    profile(service, notify, write, ProfileSource.Fallback)
                }
            }

    private fun profile(
        service: GattServiceInfo,
        notify: GattCharacteristicInfo,
        write: GattCharacteristicInfo,
        source: ProfileSource,
    ): SerialProfile =
        SerialProfile(
            serviceUuid = service.uuid,
            notifyUuid = notify.uuid,
            writeUuid = write.uuid,
            writeType = write.preferredWriteType,
            notifyKind = notify.notifyKind,
            source = source,
        )
}

/** Veepeak OBDCheck BLE+, vLinker, Vgate iCar Pro: notify on FFF1, write on FFF2. */
private val FFF0_SPLIT =
    CandidateProfile(
        name = "FFF0 split (Veepeak / vLinker / Vgate)",
        service = shortUuid("FFF0"),
        notify = shortUuid("FFF1"),
        write = shortUuid("FFF2"),
    )

/** Same vendor service, but the clone exposes only FFF1 and expects writes on it. */
private val FFF0_SINGLE =
    CandidateProfile(
        name = "FFF0 single (FFF1 read/write)",
        service = shortUuid("FFF0"),
        notify = shortUuid("FFF1"),
        write = shortUuid("FFF1"),
    )

/** HM-10/CC254x clones that split the transparent-UART service across FFE1 and FFE2. */
private val FFE0_SPLIT =
    CandidateProfile(
        name = "FFE0 split (HM-10 clone, FFE1/FFE2)",
        service = shortUuid("FFE0"),
        notify = shortUuid("FFE1"),
        write = shortUuid("FFE2"),
    )

/** The classic HM-10 transparent UART: one characteristic, both directions. */
private val FFE0_SINGLE =
    CandidateProfile(
        name = "FFE0 single (HM-10 transparent UART)",
        service = shortUuid("FFE0"),
        notify = shortUuid("FFE1"),
        write = shortUuid("FFE1"),
    )

/** LELink-style dongles using the 18F0 vendor service. */
private val LELINK_18F0 =
    CandidateProfile(
        name = "18F0 (LELink family)",
        service = shortUuid("18F0"),
        notify = shortUuid("2AF0"),
        write = shortUuid("2AF1"),
    )

/** Nordic UART Service, shipped by nRF-based dongles. TX (notify) is 0003, RX (write) is 0002. */
private val NORDIC_UART =
    CandidateProfile(
        name = "Nordic UART Service",
        service = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
        notify = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
        write = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
    )
