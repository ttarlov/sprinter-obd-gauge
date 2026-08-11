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

    @Test
    fun `the boost definition refuses to be parsed as if it were a raw PID`() {
        val outcome = scaleReading(PidCatalog.computedBoost, listOf(0x64))

        assertTrue("must never yield a number", outcome.failureOrNull() is ParseFailure.ScalingError)
    }
}
