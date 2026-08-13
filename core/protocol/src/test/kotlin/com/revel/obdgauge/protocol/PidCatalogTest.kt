package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidCatalogTest {
    @Test
    fun `the catalog covers the registries, the record channel and the computed channel`() {
        val ids = PidCatalog.definitions.map { it.id }

        // Standard PIDs + the (now-empty) mode-22 registry + the trans-temp record channel + boost.
        assertEquals(PidRegistry.all.size + MercedesPidRegistry.all.size + 2, ids.size)
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.containsAll(PidRegistry.definitions.map { it.id }))
        assertTrue(PidIds.TRANS_TEMP in ids)
        assertTrue(PidIds.BOOST in ids)
    }

    @Test
    fun `standard PIDs resolve to their wire spec, the trans channel to its KWP record`() {
        val coolant = PidCatalog.byId(PidIds.COOLANT)
        val transTemp = PidCatalog.byId(PidIds.TRANS_TEMP)

        assertTrue(coolant is PolledPid.Standard)
        assertEquals("0105", (coolant as PolledPid.Standard).spec.command)
        // OBD-55: TRANS_TEMP now resolves to the byte-1 KWP record, not the retired X-Gauge spec.
        assertTrue(transTemp is PolledPid.Record)
        assertEquals("2130", (transTemp as PolledPid.Record).spec.requestBytes)
    }

    @Test
    fun `boost and unknown ids are not polled channels`() {
        assertNull("boost is computed, never requested", PidCatalog.byId(PidIds.BOOST))
        assertNull(PidCatalog.byId("nope"))
    }

    @Test
    fun `verification status is exposed for the UI to badge`() {
        assertTrue(PidCatalog.isVerified(PidIds.COOLANT))
        assertTrue(PidCatalog.isVerified(PidIds.RPM))
        assertFalse(
            "the record decode ships unverified — the slope is provisional (OBD-55)",
            PidCatalog.isVerified(PidIds.TRANS_TEMP),
        )
    }

    @Test
    fun `computed boost is unverified since OBD-57 - it is a speed-density estimate, not a measurement`() {
        // Was verified while boost = 010B MAP − baro (a subtraction of SAE-standard PIDs). Now MAP
        // is computed from an uncalibrated VE model, so boost is an "Est." until the 🖐 VE drive.
        assertFalse(PidCatalog.isVerified(PidIds.BOOST))
    }

    @Test
    fun `an unknown id is reported unverified rather than assumed good`() {
        assertFalse(PidCatalog.isVerified("madeUp"))
    }

    @Test
    fun `boost declares the speed-density channels it is derived from`() {
        // OBD-57: MAP is computed from airflow, not read from 010B — so boost's inputs are the
        // four speed-density terms, in dependency order.
        assertEquals(
            listOf(ProtocolPidIds.MAF, ProtocolPidIds.IAT_SENSOR, PidIds.RPM, PidIds.BARO),
            PidCatalog.dependenciesOf(PidIds.BOOST),
        )
        assertEquals(emptyList<String>(), PidCatalog.dependenciesOf(PidIds.COOLANT))
    }

    // --- OBD-43: vehicle availability, the axis `verified` does not cover -------------------

    @Test
    fun `the PIDs the van answered NO DATA to are reported unsupported, with their evidence`() {
        val map = PidCatalog.availabilityOf(ProtocolPidIds.MAP)
        val iat = PidCatalog.availabilityOf(ProtocolPidIds.IAT)

        assertTrue("010B MAP is NOT SUPPORTED on this vehicle", map is ChannelAvailability.UnsupportedByVehicle)
        assertTrue("010F IAT is NOT SUPPORTED on this vehicle", iat is ChannelAvailability.UnsupportedByVehicle)
        assertTrue(
            "the claim must stay traceable to a capture, not become folklore",
            (map as ChannelAvailability.UnsupportedByVehicle).evidence.contains("2026-08-12"),
        )
    }

    @Test
    fun `boost degrades to a typed unavailable naming the input it lost, never to a number`() {
        val boost = PidCatalog.availabilityOf(PidIds.BOOST)

        // OBD-57: the one input still ungrounded is mass airflow — IAT (0168), rpm and baro all
        // answer. A blanket "boost is unavailable" would lose the fact that three of four exist.
        assertEquals(ChannelAvailability.MissingInputs(listOf(ProtocolPidIds.MAF)), boost)
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(ProtocolPidIds.IAT_SENSOR))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.RPM))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.BARO))
    }

    @Test
    fun `mass airflow answers but cannot be published yet - it is pending the g_s unit contract`() {
        // 0166 IS answered by this van (not UnsupportedByVehicle, not falsified); the only thing
        // missing is a frozen MeasurementUnit for g/s (OBD-58). Its scaling is proven in
        // VendoredSaeScaling. This is the seam OBD-58 flips: remove MAF from the pending set and it
        // becomes an ordinary channel, and boost auto-flips to Available.
        val maf = PidCatalog.availabilityOf(ProtocolPidIds.MAF)
        assertTrue(maf is ChannelAvailability.PendingUnitContract)
        assertEquals("g/s", (maf as ChannelAvailability.PendingUnitContract).quantity)
    }

    @Test
    fun `boost is both unverified and unavailable, two different questions with the same answer today`() {
        // Since OBD-57 boost is an estimate (unverified) AND missing an input (unavailable). The
        // axes stay independent: MAP's own decode is still SAE-correct, so isVerified(MAP) is true
        // even though MAP is not read on this van.
        assertFalse(PidCatalog.isVerified(PidIds.BOOST))
        assertTrue(PidCatalog.availabilityOf(PidIds.BOOST) != ChannelAvailability.Available)
        assertTrue(PidCatalog.isVerified(ProtocolPidIds.MAP))
    }

    @Test
    fun `the identified trans decode is available, no longer falsified`() {
        // OBD-55: the 2026-08-13 drive test identified the field (record byte 1, 63 − raw), so
        // TRANS_TEMP left FALSIFIED_DECODES and is a live, pollable channel again. It is
        // unverified (slope provisional), which availabilityOf does not speak to — see isVerified.
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.TRANS_TEMP))
    }

    @Test
    fun `an unsupported channel and an identified one are distinct verdicts`() {
        // 010B MAP: the van says nothing (unsupported, but still polled for discoverability).
        // 2130: the van answers and OBD-55 identified the field, so it is Available and polled —
        // no longer the DecodeFalsified it was while the byte-0 decode stood.
        assertTrue(PidCatalog.availabilityOf(ProtocolPidIds.MAP) is ChannelAvailability.UnsupportedByVehicle)
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.TRANS_TEMP))
    }

    @Test
    fun `channels the van answered are available, and so is anything nothing is known against`() {
        for (id in listOf(PidIds.COOLANT, PidIds.RPM, PidIds.BARO, ProtocolPidIds.SPEED)) {
            assertEquals(id, ChannelAvailability.Available, PidCatalog.availabilityOf(id))
        }
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(ProtocolPidIds.ENGINE_LOAD))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(ProtocolPidIds.THROTTLE))
        assertEquals(
            "the table records falsified channels only; silence is not evidence of absence",
            ChannelAvailability.Available,
            PidCatalog.availabilityOf("somethingNobodyHasTried"),
        )
    }

    @Test
    fun `the boost definition refuses to be parsed as if it were a raw PID`() {
        val outcome = scaleReading(PidCatalog.computedBoost, listOf(0x64))

        assertTrue("must never yield a number", outcome.failureOrNull() is ParseFailure.ScalingError)
    }
}
