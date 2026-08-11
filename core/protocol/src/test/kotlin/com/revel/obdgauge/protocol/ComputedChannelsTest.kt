package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Boost across the elevation band this van actually lives in.
 *
 * The three barometric fixtures are the point of the whole computed channel: the same manifold
 * pressure means a very different amount of boost at sea level than it does on a Colorado pass,
 * and any implementation that subtracts a constant is wrong by several psi exactly where the
 * engine is working hardest.
 */
class ComputedChannelsTest {
    @Test
    fun `hard pull at sea level`() {
        assertBoost(expected = 79.0, map = SPOOLED_MAP, baro = BARO_SEA_LEVEL)
    }

    @Test
    fun `hard pull at 6000 feet - Colorado front range`() {
        assertBoost(expected = 99.0, map = SPOOLED_MAP, baro = BARO_6K_FT)
    }

    @Test
    fun `hard pull at 10000 feet - Loveland Pass`() {
        assertBoost(expected = 111.0, map = SPOOLED_MAP, baro = BARO_10K_FT)
    }

    @Test
    fun `idle vacuum is negative at every elevation, not clamped to zero`() {
        assertBoost(expected = -66.0, map = IDLE_MAP, baro = BARO_SEA_LEVEL)
        assertBoost(expected = -46.0, map = IDLE_MAP, baro = BARO_6K_FT)
        assertBoost(expected = -34.0, map = IDLE_MAP, baro = BARO_10K_FT)
    }

    @Test
    fun `key on engine off reads zero boost at every elevation`() {
        // Manifold and ambient equalize with the engine off - the bring-up checklist's first check.
        assertBoost(expected = 0.0, map = BARO_SEA_LEVEL, baro = BARO_SEA_LEVEL)
        assertBoost(expected = 0.0, map = BARO_6K_FT, baro = BARO_6K_FT)
        assertBoost(expected = 0.0, map = BARO_10K_FT, baro = BARO_10K_FT)
    }

    @Test
    fun `a fixed sea-level offset would be wrong by several psi at altitude`() {
        val atAltitude = boost(SPOOLED_MAP, BARO_10K_FT)!!.value
        val naiveFixedOffset = SPOOLED_MAP - BARO_SEA_LEVEL

        // ~32 kPa = ~4.6 psi of invented boost. This is the bug the channel exists to avoid.
        assertEquals(BARO_SEA_LEVEL - BARO_10K_FT, atAltitude - naiveFixedOffset, TOLERANCE)
    }

    @Test
    fun `staleness of either input propagates to boost`() {
        assertFalse(boost(SPOOLED_MAP, BARO_6K_FT)!!.stale)
        assertTrue(boost(SPOOLED_MAP, BARO_6K_FT, mapStale = true)!!.stale)
        assertTrue(boost(SPOOLED_MAP, BARO_6K_FT, baroStale = true)!!.stale)
    }

    @Test
    fun `boost is timestamped by its oldest input`() {
        val older = Instant.EPOCH
        val newer = Instant.EPOCH.plusSeconds(30)

        val mapNewer = ComputedChannels.boost(reading(SPOOLED_MAP, newer), reading(BARO_6K_FT, older))
        val baroNewer = ComputedChannels.boost(reading(SPOOLED_MAP, older), reading(BARO_6K_FT, newer))

        assertEquals(older, mapNewer!!.timestamp)
        assertEquals(older, baroNewer!!.timestamp)
    }

    @Test
    fun `no boost at all until both inputs exist`() {
        assertNull(ComputedChannels.boost(map = reading(SPOOLED_MAP), baro = null))
        assertNull(ComputedChannels.boost(map = null, baro = reading(BARO_6K_FT)))
        assertNull(ComputedChannels.boost(map = null, baro = null))
    }

    @Test
    fun `boost is published under the contract's boost id`() {
        assertEquals(PidIds.BOOST, boost(SPOOLED_MAP, BARO_6K_FT)!!.id)
    }

    private fun assertBoost(
        expected: Double,
        map: Double,
        baro: Double,
    ) {
        assertEquals("map $map kPa, baro $baro kPa", expected, boost(map, baro)!!.value, TOLERANCE)
    }

    private fun boost(
        map: Double,
        baro: Double,
        mapStale: Boolean = false,
        baroStale: Boolean = false,
    ): Reading? =
        ComputedChannels.boost(
            map = reading(map, stale = mapStale),
            baro = reading(baro, stale = baroStale),
        )

    private fun reading(
        value: Double,
        timestamp: Instant = Instant.EPOCH,
        stale: Boolean = false,
    ): Reading = Reading(id = "x", value = value, timestamp = timestamp, stale = stale)

    private companion object {
        const val TOLERANCE = 1e-9

        /** ~14.7 psi absolute. */
        const val BARO_SEA_LEVEL = 101.0

        /** ~11.8 psi absolute, Denver/Front Range. */
        const val BARO_6K_FT = 81.0

        /** ~10.1 psi absolute, Loveland Pass. */
        const val BARO_10K_FT = 69.0

        /** Manifold pressure on a hard pull. */
        const val SPOOLED_MAP = 180.0

        /** Manifold pressure at warm idle - well below ambient. */
        const val IDLE_MAP = 35.0
    }
}
