package com.revel.obdgauge.ble.gatt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * UUID probing, the part of OBD-18 most likely to be wrong on hardware nobody has plugged in
 * yet. Every candidate family in the list gets a test, and so does the structural fallback that
 * has to carry an unknown dongle.
 */
class SerialProfileProbeTest {
    @Test
    fun `veepeak style FFF0 with split characteristics is the first choice`() {
        val profile = SerialProfileProbe.probe(services(fff0Split()))

        assertEquals(shortUuid("FFF0"), profile?.serviceUuid)
        assertEquals(shortUuid("FFF1"), profile?.notifyUuid)
        assertEquals(shortUuid("FFF2"), profile?.writeUuid)
        assertEquals(ProfileSource.Candidate("FFF0 split (Veepeak / vLinker / Vgate)"), profile?.source)
    }

    @Test
    fun `FFF0 exposing only FFF1 falls through to the single-characteristic candidate`() {
        val service =
            GattServiceInfo(
                shortUuid("FFF0"),
                listOf(
                    GattCharacteristicInfo(
                        shortUuid("FFF1"),
                        GattProperty.NOTIFY or GattProperty.WRITE_NO_RESPONSE,
                    ),
                ),
            )

        val profile = SerialProfileProbe.probe(services(service))

        assertEquals(shortUuid("FFF1"), profile?.notifyUuid)
        assertEquals(shortUuid("FFF1"), profile?.writeUuid)
        assertEquals(ProfileSource.Candidate("FFF0 single (FFF1 read/write)"), profile?.source)
    }

    @Test
    fun `HM-10 transparent UART on FFE0 matches its candidate`() {
        val service =
            GattServiceInfo(
                shortUuid("FFE0"),
                listOf(
                    GattCharacteristicInfo(
                        shortUuid("FFE1"),
                        GattProperty.NOTIFY or GattProperty.WRITE or GattProperty.WRITE_NO_RESPONSE,
                    ),
                ),
            )

        val profile = SerialProfileProbe.probe(services(service))

        assertEquals(shortUuid("FFE0"), profile?.serviceUuid)
        assertEquals(ProfileSource.Candidate("FFE0 single (HM-10 transparent UART)"), profile?.source)
    }

    @Test
    fun `nordic UART service matches with TX as notify and RX as write`() {
        val service =
            GattServiceInfo(
                UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"),
                listOf(
                    GattCharacteristicInfo(
                        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
                        GattProperty.WRITE_NO_RESPONSE,
                    ),
                    GattCharacteristicInfo(
                        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
                        GattProperty.NOTIFY,
                    ),
                ),
            )

        val profile = SerialProfileProbe.probe(services(service))

        assertEquals(UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), profile?.notifyUuid)
        assertEquals(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), profile?.writeUuid)
    }

    @Test
    fun `an unknown vendor service falls back to its writable plus notifiable pair`() {
        val vendor =
            GattServiceInfo(
                UUID.fromString("0000abcd-0000-1000-8000-00805F9B34FB"),
                listOf(
                    GattCharacteristicInfo(shortUuid("BEEF"), GattProperty.NOTIFY),
                    GattCharacteristicInfo(shortUuid("CAFE"), GattProperty.WRITE),
                ),
            )

        val profile = SerialProfileProbe.probe(services(vendor))

        assertEquals(shortUuid("BEEF"), profile?.notifyUuid)
        assertEquals(shortUuid("CAFE"), profile?.writeUuid)
        assertEquals(ProfileSource.Fallback, profile?.source)
    }

    @Test
    fun `the fallback skips housekeeping services even when they look serial-shaped`() {
        val genericAttribute =
            GattServiceInfo(
                shortUuid("1801"),
                listOf(
                    GattCharacteristicInfo(shortUuid("2A05"), GattProperty.INDICATE),
                    GattCharacteristicInfo(shortUuid("2B29"), GattProperty.WRITE),
                ),
            )
        val vendor =
            GattServiceInfo(
                shortUuid("ABF0"),
                listOf(
                    GattCharacteristicInfo(shortUuid("ABF1"), GattProperty.NOTIFY),
                    GattCharacteristicInfo(shortUuid("ABF2"), GattProperty.WRITE),
                ),
            )

        val profile = SerialProfileProbe.probe(listOf(genericAttribute, vendor))

        assertEquals(shortUuid("ABF0"), profile?.serviceUuid)
    }

    @Test
    fun `write type prefers no-response when the characteristic advertises it`() {
        val profile = SerialProfileProbe.probe(services(fff0Split()))

        assertEquals(GattWriteType.NO_RESPONSE, profile?.writeType)
    }

    @Test
    fun `write type falls back to default when only plain write is advertised`() {
        val service =
            GattServiceInfo(
                shortUuid("FFF0"),
                listOf(
                    GattCharacteristicInfo(shortUuid("FFF1"), GattProperty.NOTIFY),
                    GattCharacteristicInfo(shortUuid("FFF2"), GattProperty.WRITE),
                ),
            )

        val profile = SerialProfileProbe.probe(services(service))

        assertEquals(GattWriteType.DEFAULT, profile?.writeType)
    }

    @Test
    fun `an indicate-only characteristic is accepted as the notify side`() {
        val service =
            GattServiceInfo(
                shortUuid("FFF0"),
                listOf(
                    GattCharacteristicInfo(shortUuid("FFF1"), GattProperty.INDICATE),
                    GattCharacteristicInfo(shortUuid("FFF2"), GattProperty.WRITE),
                ),
            )

        val profile = SerialProfileProbe.probe(services(service))

        assertEquals(NotifyKind.INDICATE, profile?.notifyKind)
    }

    @Test
    fun `a device with nothing writable and notifiable probes to null`() {
        val readOnly =
            GattServiceInfo(
                shortUuid("FFF0"),
                listOf(GattCharacteristicInfo(shortUuid("FFF1"), GattProperty.READ)),
            )

        assertNull(SerialProfileProbe.probe(services(readOnly)))
    }

    @Test
    fun `an empty service list probes to null rather than throwing`() {
        assertNull(SerialProfileProbe.probe(emptyList()))
    }

    @Test
    fun `candidates are ordered split-before-single within a family`() {
        val names = SerialProfileProbe.CANDIDATES.map { it.name }

        assertTrue(
            "FFF0 split must be tried before FFF0 single",
            names.indexOfFirst { it.startsWith("FFF0 split") } < names.indexOfFirst { it.startsWith("FFF0 single") },
        )
        assertTrue(
            "FFE0 split must be tried before FFE0 single",
            names.indexOfFirst { it.startsWith("FFE0 split") } < names.indexOfFirst { it.startsWith("FFE0 single") },
        )
    }

    @Test
    fun `every candidate service uuid is offered to the scan filter`() {
        assertTrue(
            SerialProfileProbe.CANDIDATE_SERVICE_UUIDS.containsAll(
                SerialProfileProbe.CANDIDATES.map { it.service },
            ),
        )
    }

    private fun services(vararg vendor: GattServiceInfo): List<GattServiceInfo> =
        listOf(
            GattServiceInfo(shortUuid("1800"), listOf(GattCharacteristicInfo(shortUuid("2A00"), GattProperty.READ))),
        ) + vendor

    private fun fff0Split(): GattServiceInfo =
        GattServiceInfo(
            shortUuid("FFF0"),
            listOf(
                GattCharacteristicInfo(shortUuid("FFF1"), GattProperty.NOTIFY or GattProperty.READ),
                GattCharacteristicInfo(shortUuid("FFF2"), GattProperty.WRITE or GattProperty.WRITE_NO_RESPONSE),
            ),
        )
}
