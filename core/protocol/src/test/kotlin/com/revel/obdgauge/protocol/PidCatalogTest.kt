package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PidIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PidCatalogTest {
    @Test
    fun `the catalog covers both registries and the computed channel`() {
        val ids = PidCatalog.definitions.map { it.id }

        assertEquals(PidRegistry.all.size + MercedesPidRegistry.all.size + 1, ids.size)
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.containsAll(PidRegistry.definitions.map { it.id }))
        assertTrue(PidIds.TRANS_TEMP in ids)
        assertTrue(PidIds.BOOST in ids)
    }

    @Test
    fun `standard PIDs resolve to their wire spec, manufacturer PIDs to theirs`() {
        val coolant = PidCatalog.byId(PidIds.COOLANT)
        val transTemp = PidCatalog.byId(PidIds.TRANS_TEMP)

        assertTrue(coolant is PolledPid.Standard)
        assertEquals("0105", (coolant as PolledPid.Standard).spec.command)
        assertTrue(transTemp is PolledPid.Manufacturer)
        assertEquals("2130", (transTemp as PolledPid.Manufacturer).spec.requestBytes)
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
        assertFalse("the mode-22 hypothesis must badge as unverified", PidCatalog.isVerified(PidIds.TRANS_TEMP))
    }

    @Test
    fun `computed boost is verified, since both of its inputs are SAE standard`() {
        assertTrue(PidCatalog.isVerified(PidIds.BOOST))
    }

    @Test
    fun `an unknown id is reported unverified rather than assumed good`() {
        assertFalse(PidCatalog.isVerified("madeUp"))
    }

    @Test
    fun `boost declares the raw channels it is derived from`() {
        assertEquals(listOf(ProtocolPidIds.MAP, PidIds.BARO), PidCatalog.dependenciesOf(PidIds.BOOST))
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

        assertEquals(ChannelAvailability.MissingInputs(listOf(ProtocolPidIds.MAP)), boost)
        // The baro half survives — it was captured working — so the missing list must be exactly
        // MAP. A blanket "boost is unavailable" would lose the fact that half the input exists.
        assertEquals(ChannelAvailability.Available, PidCatalog.availabilityOf(PidIds.BARO))
    }

    @Test
    fun `boost stays verified while being unavailable, because they are different questions`() {
        // The subtraction is still SAE-correct; it just has nothing to subtract on this van.
        // Collapsing the two axes would either brand a correct decode a hypothesis or leave the
        // gauge unable to say why it is empty.
        assertTrue(PidCatalog.isVerified(PidIds.BOOST))
        assertTrue(PidCatalog.availabilityOf(PidIds.BOOST) != ChannelAvailability.Available)
        assertTrue(PidCatalog.isVerified(ProtocolPidIds.MAP))
    }

    @Test
    fun `the falsified X-Gauge trans decode is reported as falsified, with its evidence`() {
        val trans = PidCatalog.availabilityOf(PidIds.TRANS_TEMP)

        assertTrue("expected DecodeFalsified, was $trans", trans is ChannelAvailability.DecodeFalsified)
        val evidence = (trans as ChannelAvailability.DecodeFalsified).evidence
        assertTrue("the verdict must cite the capture that produced it: $evidence", evidence.contains("2026-08-12"))
        assertTrue("and point at the decode that replaces it: $evidence", evidence.contains("18"))
    }

    @Test
    fun `falsified is a different verdict from unsupported, because the van does answer`() {
        // 010B MAP: the van says nothing. 2130: the van answers, and we know we misread it.
        // A UI renders those differently ("unavailable" vs "pending verification"), and the
        // poll loop treats them differently (MAP is still polled, trans temp is not).
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
