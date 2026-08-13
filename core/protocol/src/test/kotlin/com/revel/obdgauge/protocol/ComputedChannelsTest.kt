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

    // === OBD-57: speed-density boost (MAF + IAT + RPM + baro) ===============================

    /**
     * The folded constant is DERIVED, not a magic `34.44` — this is the unit-tracking proof.
     *
     * ```
     * MAP(kPa) = MAF(g/s) · R · T(K) · 120 / (VE · Vdisp(L) · RPM)
     * ```
     *
     * K = `R · strokesPerCycle · secondsPerMinute · (gramsPerKg · m³PerLiter)`. The last pair is
     * the two unit conversions that let MAF stay in g/s and displacement in litres — `1000 · 0.001
     * = 1`, dimensionless — so what physically survives is `R · 2 · 60`. Rebuilding it here from
     * the named factors and matching [ComputedChannels.SPEED_DENSITY_CONSTANT] is what stops the
     * constant from silently drifting into a wrong number.
     */
    @Test
    fun `the speed-density constant is R times 120, derived from first principles not hardcoded`() {
        val gasConstant = 0.287 // kPa·m³/(kg·K)
        val strokesPerCycle = 2.0 // 4-stroke: one intake charge per two revolutions
        val secondsPerMinute = 60.0 // RPM → per second
        val gramsPerKilogram = 1000.0 // MAF is in grams…
        val cubicMetersPerLiter = 0.001 // …displacement in litres

        val derived =
            gasConstant * strokesPerCycle * secondsPerMinute * gramsPerKilogram * cubicMetersPerLiter

        // The two unit conversions cancel to 1, leaving R·120 = 34.44.
        assertEquals(1.0, gramsPerKilogram * cubicMetersPerLiter, 0.0)
        assertEquals(gasConstant * 120.0, derived, TOLERANCE)
        assertEquals(34.44, derived, TOLERANCE)
        assertEquals(derived, ComputedChannels.SPEED_DENSITY_CONSTANT, TOLERANCE)
    }

    /**
     * **The idle-sanity anchor — the quality proof.** At the session-capture inputs (MAF 14.2 g/s,
     * IAT 44 °C, idle RPM ~723, baro 82 kPa) a correct model must land MAP near ambient, so boost ≈
     * 0 (idle, no load). If the constant is wrong (mutation: ×2) or VE is way off, MAP diverges
     * from atmospheric and this fails loudly.
     */
    @Test
    fun `idle capture inputs yield near-atmospheric MAP and near-zero boost`() {
        val map = ComputedChannels.manifoldPressureFromAirflow(capMaf, capIat, capRpm)!!
        val boost = ComputedChannels.speedDensityBoost(capMaf, capIat, capRpm, capBaro)!!

        // MAP within a few kPa of the 82 kPa ambient — "near-atmospheric" at a no-load idle.
        assertEquals("idle MAP should sit near ambient", CAP_BARO_KPA, map.value, IDLE_MAP_BAND)
        // Boost within ~1 psi of zero. A doubled constant lands MAP near 168 kPa → boost ~86 kPa.
        assertTrue("idle boost ${boost.value} kPa should be near zero", kotlin.math.abs(boost.value) < IDLE_BOOST_BAND)
        assertEquals(PidIds.BOOST, boost.id)
    }

    // ---- graceful degradation: every degenerate input is a typed absence, never a spike -------

    @Test
    fun `RPM of zero yields no MAP and no boost, never a divide-by-zero`() {
        val zeroRpm = reading(0.0)
        assertNull(ComputedChannels.manifoldPressureFromAirflow(capMaf, capIat, zeroRpm))
        assertNull(ComputedChannels.speedDensityBoost(capMaf, capIat, zeroRpm, capBaro))
    }

    @Test
    fun `negative RPM is rejected too, not just exactly zero`() {
        assertNull(ComputedChannels.manifoldPressureFromAirflow(capMaf, capIat, reading(-100.0)))
    }

    @Test
    fun `zero or negative MAF yields no MAP - a dropout is not a real vacuum`() {
        assertNull(ComputedChannels.manifoldPressureFromAirflow(reading(0.0), capIat, capRpm))
        assertNull(ComputedChannels.manifoldPressureFromAirflow(reading(-1.0), capIat, capRpm))
    }

    @Test
    fun `any missing speed-density input yields no boost at all`() {
        assertNull(ComputedChannels.speedDensityBoost(null, capIat, capRpm, capBaro))
        assertNull(ComputedChannels.speedDensityBoost(capMaf, null, capRpm, capBaro))
        assertNull(ComputedChannels.speedDensityBoost(capMaf, capIat, null, capBaro))
        assertNull(ComputedChannels.speedDensityBoost(capMaf, capIat, capRpm, null))
    }

    // ---- physical clamp: a near-stall RPM or a MAF spike is bounded, not a 40-psi needle slam --

    @Test
    fun `an absurdly high raw MAP is clamped to the physical ceiling, not published as a spike`() {
        // Normal idle airflow at a near-stall 50 rpm drives the raw quotient past 1200 kPa.
        val map = ComputedChannels.manifoldPressureFromAirflow(capMaf, capIat, reading(50.0))!!
        assertEquals(ComputedChannels.MAP_MAX_KPA, map.value, TOLERANCE)
    }

    @Test
    fun `an absurdly low raw MAP is clamped to the physical floor`() {
        // A trickle of airflow at high rpm drives the raw quotient below 1 kPa.
        val map = ComputedChannels.manifoldPressureFromAirflow(reading(0.1), capIat, reading(4000.0))!!
        assertEquals(ComputedChannels.MAP_MIN_KPA, map.value, TOLERANCE)
    }

    // ---- VE(RPM) is a curve, not a flat fudge factor ------------------------------------------

    @Test
    fun `VE rises off idle into a boosted-diesel hump above one, then eases - it is not constant`() {
        val idle = VolumetricEfficiency.at(700.0)
        val peak = VolumetricEfficiency.at(2200.0)
        val high = VolumetricEfficiency.at(4600.0)

        assertEquals(0.85, idle, TOLERANCE)
        assertEquals(1.05, peak, TOLERANCE)
        assertEquals(0.95, high, TOLERANCE)
        // The defining property a flattened VE would break: the peak-torque band breathes far
        // better than idle, and past 1.0 (the turbo is force-feeding the cylinders).
        assertTrue("VE must vary across the rev range", peak > idle)
        assertTrue("spooled VE exceeds 1.0 on a boosted engine", peak > 1.0)
    }

    @Test
    fun `VE interpolates linearly between breakpoints and holds flat past the ends`() {
        // Midway 700→1200 (0.85→0.92): 0.6 of the way is 0.892.
        assertEquals(0.892, VolumetricEfficiency.at(1000.0), TOLERANCE)
        // No extrapolation beyond the table.
        assertEquals(0.85, VolumetricEfficiency.at(400.0), TOLERANCE)
        assertEquals(0.95, VolumetricEfficiency.at(6000.0), TOLERANCE)
    }

    // ---- freshness / source-agnostic baro -----------------------------------------------------

    @Test
    fun `boost is timestamped by its oldest speed-density input and stale if any input is stale`() {
        val old = Instant.EPOCH
        val newer = Instant.EPOCH.plusSeconds(60)
        val boost =
            ComputedChannels.speedDensityBoost(
                maf = reading(CAP_MAF_VALUE, newer),
                iat = reading(CAP_IAT_VALUE, old, stale = true),
                rpm = reading(CAP_RPM_VALUE, newer),
                baro = reading(CAP_BARO_KPA, newer),
            )!!
        assertEquals(old, boost.timestamp)
        assertTrue(boost.stale)
    }

    @Test
    fun `boost takes baro from whatever source supplies the reading - ECU or phone are the same here`() {
        // The computation only sees a baro Reading; where it came from is an :app concern (OBD-57b).
        val ecuBaro = ComputedChannels.speedDensityBoost(capMaf, capIat, capRpm, reading(CAP_BARO_KPA))!!
        val phoneBaro = ComputedChannels.speedDensityBoost(capMaf, capIat, capRpm, reading(CAP_BARO_KPA))!!
        assertEquals(ecuBaro.value, phoneBaro.value, TOLERANCE)
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

        // --- OBD-57 speed-density capture anchors (docs/hardware/session-2026-08-13.md §5 +
        // --- research-2026-08-13-boost-inference.md): warm idle, ~5 800 ft. ------------------

        /** MAF `0166` sensor A `01 C7` → (256 + 199) / 32 g/s. */
        const val CAP_MAF_VALUE = 14.21875

        /** IAT `0168` sensor 1 `54` → 84 − 40 °C. */
        const val CAP_IAT_VALUE = 44.0

        /** RPM `010C`, idle midpoint of the captured 725–729 band. */
        const val CAP_RPM_VALUE = 723.0

        /** Baro `0133` `52` → 82 kPa absolute. */
        const val CAP_BARO_KPA = 82.0

        /** Idle MAP should land within a few kPa of ambient (no-load). */
        const val IDLE_MAP_BAND = 8.0

        /** Idle boost within ~1 psi (≈6.9 kPa) of zero. */
        const val IDLE_BOOST_BAND = 6.9
    }

    private val capMaf = reading(CAP_MAF_VALUE)
    private val capIat = reading(CAP_IAT_VALUE)
    private val capRpm = reading(CAP_RPM_VALUE)
    private val capBaro = reading(CAP_BARO_KPA)
}
