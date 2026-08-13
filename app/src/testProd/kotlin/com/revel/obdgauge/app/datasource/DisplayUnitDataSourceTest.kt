package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.app.gauge.GAUGE_CATALOG_BY_ID
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.protocol.PidCatalog
import com.revel.obdgauge.protocol.ProtocolPidIds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The `prod` DI seam that resolves the CELSIUS/KPA vs FAHRENHEIT/PSI mismatch
 * `core/protocol/MODULE.md` has carried since OBD-13. See [DisplayUnitDataSource]'s KDoc for why
 * the conversion lands here rather than in `DASHBOARD_PIDS`' declared units.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayUnitDataSourceTest {
    @Test
    fun `celsius from the protocol layer arrives as the fahrenheit the app catalog declares`() =
        runTest {
            val upstream = FakeSource(reading(PidIds.COOLANT, COOLANT_C))
            val source = DisplayUnitDataSource(upstream, backgroundScope)
            advanceUntilIdle()

            assertEquals(
                COOLANT_F,
                source.readings.value
                    .getValue(PidIds.COOLANT)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `boost kilopascals arrive as the psi the app catalog declares`() =
        runTest {
            // Round-1 review MEDIUM: bypassing the seam for boost survived every test — the
            // charter channel had zero conversion coverage. 82 kPa is the session's captured
            // baro magnitude; 82 / 6.894757 = 11.8931… PSI. Latent until a mode-22 MAP DID
            // lands; the day it does, an untested seam renders raw kPa as PSI (20 kPa cruise
            // reading "20.0 PSI", arc pegged).
            val upstream = FakeSource(reading(PidIds.BOOST, BOOST_KPA))
            val source = DisplayUnitDataSource(upstream, backgroundScope)
            advanceUntilIdle()

            assertEquals(
                BOOST_PSI,
                source.readings.value
                    .getValue(PidIds.BOOST)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `vacuum boost stays negative through the psi conversion`() =
        runTest {
            val upstream = FakeSource(reading(PidIds.BOOST, VACUUM_KPA))
            val source = DisplayUnitDataSource(upstream, backgroundScope)
            advanceUntilIdle()

            assertEquals(
                VACUUM_PSI,
                source.readings.value
                    .getValue(PidIds.BOOST)
                    .value,
                TOLERANCE,
            )
        }

    @Test
    fun `same-unit channels are passed through byte for byte`() =
        runTest {
            val upstream = FakeSource(reading(PidIds.RPM, RPM))
            val source = DisplayUnitDataSource(upstream, backgroundScope)
            advanceUntilIdle()

            assertEquals(reading(PidIds.RPM, RPM), source.readings.value.getValue(PidIds.RPM))
        }

    /**
     * Channels with no `:app` gauge (`engineLoad`, `throttle`, `map`, `speed`, `iat`) have no
     * declared display unit to convert *to*. Inventing one would be worse than leaving the
     * protocol's own, so they pass through untouched — including their unit's meaning.
     */
    @Test
    fun `channels the app has no gauge for keep their protocol units`() =
        runTest {
            val upstream = FakeSource(reading(ProtocolPidIds.ENGINE_LOAD, LOAD_PERCENT))
            val source = DisplayUnitDataSource(upstream, backgroundScope)
            advanceUntilIdle()

            assertEquals(
                LOAD_PERCENT,
                source.readings.value
                    .getValue(ProtocolPidIds.ENGINE_LOAD)
                    .value,
                TOLERANCE,
            )
            assertTrue(ProtocolPidIds.ENGINE_LOAD !in GAUGE_CATALOG_BY_ID)
        }

    /** Nothing is materialised: an absent channel stays absent, which is the boost story. */
    @Test
    fun `an absent channel is never defaulted into existence`() =
        runTest {
            val source = DisplayUnitDataSource(FakeSource(), backgroundScope)
            advanceUntilIdle()

            assertTrue(source.readings.value.isEmpty())
            assertNull(source.readings.value[PidIds.BOOST])
        }

    @Test
    fun `start, stop and connection are pass-throughs - the frozen lifecycle stays the delegate's`() =
        runTest {
            val upstream = FakeSource()
            val source = DisplayUnitDataSource(upstream, backgroundScope)

            source.start(PidCatalog.definitions)
            source.stop()
            upstream.setConnection(LinkState.Ready)

            assertEquals(1, upstream.startCallCount)
            assertEquals(1, upstream.stopCallCount)
            assertEquals(PidCatalog.definitions.map { it.id }, upstream.lastRequested.map { it.id })
            assertEquals(LinkState.Ready, source.connection.value)
        }

    /**
     * Guards the assumption the whole wrapper rests on: `:app` declares coolant in Fahrenheit and
     * boost in PSI while `:core:protocol` parses Celsius and kPa. If a future change aligns the
     * two directly (the other option `core/protocol/MODULE.md` names), this test says so — and at
     * that point `DisplayUnitDataSource` becomes an identity function and should be deleted, not
     * left in place quietly converting nothing.
     */
    @Test
    fun `the mismatch this class exists for is still real`() {
        assertEquals(MeasurementUnit.FAHRENHEIT, GAUGE_CATALOG_BY_ID.getValue(PidIds.COOLANT).unit)
        assertEquals(MeasurementUnit.CELSIUS, PidCatalog.definitions.first { it.id == PidIds.COOLANT }.unit)
        assertEquals(MeasurementUnit.PSI, GAUGE_CATALOG_BY_ID.getValue(PidIds.BOOST).unit)
        assertEquals(MeasurementUnit.KPA, PidCatalog.computedBoost.unit)
    }

    private fun reading(
        id: String,
        value: Double,
    ) = Reading(id = id, value = value, timestamp = Instant.EPOCH, stale = false)

    private companion object {
        const val COOLANT_C = 94.0
        const val COOLANT_F = 201.2

        // 82 kPa: the 2026-08-12 session's captured baro magnitude, reused as a realistic
        // boost-range differential. 82 / 6.894757 exactly; vacuum case pins the sign.
        const val BOOST_KPA = 82.0
        const val BOOST_PSI = 11.893079
        const val VACUUM_KPA = -20.0
        const val VACUUM_PSI = -2.900750
        const val RPM = 727.0
        const val LOAD_PERCENT = 55.7
        const val TOLERANCE = 0.001
    }
}

/** Minimal upstream double — `:core:testing` is `demo`-only and must not reach a prod classpath. */
private class FakeSource(
    vararg initial: Reading,
) : VehicleDataSource {
    private val mutableReadings = MutableStateFlow(initial.associateBy(Reading::id))
    override val readings: StateFlow<Map<String, Reading>> = mutableReadings.asStateFlow()

    private val mutableConnection = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val connection: StateFlow<LinkState> = mutableConnection.asStateFlow()

    var startCallCount = 0
        private set
    var stopCallCount = 0
        private set
    var lastRequested: List<PidDefinition> = emptyList()
        private set

    override fun start(pids: List<PidDefinition>) {
        startCallCount++
        lastRequested = pids
    }

    override fun stop() {
        stopCallCount++
    }

    fun setConnection(state: LinkState) {
        mutableConnection.value = state
    }
}
