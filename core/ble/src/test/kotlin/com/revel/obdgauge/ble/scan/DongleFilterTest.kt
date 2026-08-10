package com.revel.obdgauge.ble.scan

import com.revel.obdgauge.ble.gatt.shortUuid
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DongleFilterTest {
    private val filter = DongleFilter()

    @Test
    fun `name matching is case-insensitive and ignores surrounding whitespace`() {
        assertTrue(filter.matchesName("veepeak-obd"))
        assertTrue(filter.matchesName("  OBDII  "))
        assertTrue(filter.matchesName("vLinker MC+"))
    }

    @Test
    fun `matching is by prefix, so a device that merely mentions obd is ignored`() {
        assertFalse(filter.matchesName("Tesla OBD Dongle"))
        assertFalse(filter.matchesName("MyOBDThing"))
    }

    @Test
    fun `unrelated bluetooth devices do not match`() {
        assertFalse(filter.matchesName("Bose QC35"))
        assertFalse(filter.matchesName("Garmin InReach"))
        assertFalse(filter.matchesName(null))
        assertFalse(filter.matchesName("   "))
    }

    @Test
    fun `a candidate service uuid matches regardless of name`() {
        assertTrue(filter.matchesServiceUuid(listOf(shortUuid("FFE0"))))
        assertTrue(filter.matchesServiceUuid(listOf(shortUuid("18F0"))))
    }

    @Test
    fun `an unrelated service uuid does not match`() {
        assertFalse(filter.matchesServiceUuid(listOf(UUID.fromString("0000180d-0000-1000-8000-00805F9B34FB"))))
        assertFalse(filter.matchesServiceUuid(emptyList()))
    }

    @Test
    fun `either signal is enough to accept a device`() {
        val namedOnly = DiscoveredDevice(address = "AA:BB:CC:DD:EE:FF", name = "OBDCheck")
        val uuidOnly =
            DiscoveredDevice(
                address = "AA:BB:CC:DD:EE:F0",
                name = "unnamed thing",
                serviceUuids = listOf(shortUuid("FFF0")),
            )
        val neither = DiscoveredDevice(address = "AA:BB:CC:DD:EE:F1", name = "Bose QC35")

        assertTrue(filter.matches(namedOnly))
        assertTrue(filter.matches(uuidOnly))
        assertFalse(filter.matches(neither))
    }
}
