package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
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
        // TRANS_TEMP resolves to the `21 30` KWP record — kept in the catalog for OBD-51's re-ID
        // even though OBD-59 re-gated it to unavailable (its byte-1 decode was falsified). The
        // channel resolving is what lets the gate keep it off the wire rather than dropping it silently.
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
            "the record channel is unverified — its byte-1 decode was falsified and retired (OBD-59)",
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
    fun `boost is available now that mass airflow is a live channel - the OBD-58 flip`() {
        // Was MissingInputs(["maf"]) while 0166 had no g/s unit. OBD-58 landed GRAMS_PER_SECOND
        // (DECISIONS.md D9), MAF became a live PidRegistry channel, and all four speed-density
        // inputs are Available — so boost auto-flipped to Available, with no logic change in the
        // catalog. It is still an "Est." (verified = false); availability and verification differ.
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.BOOST))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(ProtocolPidIds.MAF))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(ProtocolPidIds.IAT_SENSOR))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.RPM))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.BARO))
    }

    @Test
    fun `mass airflow is a live polled channel since OBD-58 - the g_s unit landed`() {
        // 0166 was PendingUnitContract only for want of a frozen g/s unit. OBD-58 added
        // GRAMS_PER_SECOND (D9), so PidRegistry.maf resolves the id to a real StandardPidSpec with
        // the scaling proven in VendoredSaeScaling — it is now Available and pollable.
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(ProtocolPidIds.MAF))
        val maf = PidCatalog.byId(ProtocolPidIds.MAF)
        assertTrue(maf is PolledPid.Standard)
        assertEquals("0166", (maf as PolledPid.Standard).spec.command)
        assertEquals(MeasurementUnit.GRAMS_PER_SECOND, maf.spec.definition.unit)
    }

    @Test
    fun `boost stays unverified even though it is now available, two different questions`() {
        // Since OBD-58 boost is Available (MAF is live), but it is still an estimate (unverified)
        // until the 🖐 VE-calibration drive. The axes stay independent: MAP's own decode is still
        // SAE-correct, so isVerified(MAP) is true even though MAP is not read on this van.
        assertFalse(PidCatalog.isVerified(PidIds.BOOST))
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.BOOST))
        assertTrue(PidCatalog.isVerified(ProtocolPidIds.MAP))
    }

    @Test
    fun `the trans decode is re-gated to DecodeFalsified, with the field-falsified evidence`() {
        // OBD-59: the 2026-08-13 on-vehicle look falsified the byte-1 63 − raw decode (it jumps at
        // operating RPM — a dynamic signal, not a temperature), so TRANS_TEMP is back on
        // FALSIFIED_DECODES and the tile blanks to "—". This is the catalog-level guard the OBD-59
        // gate rests on: drop TRANS_TEMP from FALSIFIED_DECODES and this flips to Available.
        val availability = PidCatalog.availabilityOf(PidIds.TRANS_TEMP)

        assertTrue("expected DecodeFalsified, was $availability", availability is ChannelAvailability.DecodeFalsified)
        val evidence = (availability as ChannelAvailability.DecodeFalsified).evidence
        assertTrue("the evidence must name the on-vehicle falsification date", evidence.contains("2026-08-13"))
        assertTrue("the evidence must point at OBD-51 for the re-ID", evidence.contains("OBD-51"))
    }

    @Test
    fun `an unsupported channel and a falsified one are distinct verdicts`() {
        // 010B MAP: the van says nothing (unsupported, but still polled for discoverability).
        // 2130: the van answers, but the byte-1 decode was falsified on-vehicle (OBD-59), so the
        // channel is DecodeFalsified — resolvable and announced, but gated off the wire.
        assertTrue(PidCatalog.availabilityOf(ProtocolPidIds.MAP) is ChannelAvailability.UnsupportedByVehicle)
        assertTrue(PidCatalog.availabilityOf(PidIds.TRANS_TEMP) is ChannelAvailability.DecodeFalsified)
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
