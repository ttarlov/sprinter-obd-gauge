package com.revel.obdgauge.app.e2e

import com.revel.obdgauge.app.datasource.DisplayUnitDataSource
import com.revel.obdgauge.app.gauge.DashboardViewModel
import com.revel.obdgauge.app.gauge.GAUGE_CATALOG_BY_ID
import com.revel.obdgauge.app.gauge.NO_READING_TEXT
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.settings.UnitPreferences
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.VehicleDataSource
import com.revel.obdgauge.protocol.ChannelAvailability
import com.revel.obdgauge.protocol.PidCatalog
import com.revel.obdgauge.protocol.PollConfig
import com.revel.obdgauge.protocol.PollEvent
import com.revel.obdgauge.protocol.ProtocolPidIds
import com.revel.obdgauge.protocol.RealVehicleDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds

/**
 * **The OBD-25 end-to-end test**: real captured bytes in, rendered gauge state out, no Android
 * instrumentation anywhere.
 *
 * ```
 * ScriptedVanLink (2026-08-12 captures)
 *   → Elm327InitStateMachine   (real)
 *   → ResponseParser/PollSchedule/Mode22Requester (real)
 *   → RealVehicleDataSource    (real)
 *   → DisplayUnitDataSource    (real — the prod DI wrapper)
 *   → DashboardViewModel       (real)
 *   → DashboardUiState
 * ```
 *
 * Every link in that chain is the production object, wired the way
 * `src/prod/.../di/DataSourceModule.kt` wires it. The only substitution is the transport, which
 * is the one thing a JVM test cannot have — and even that replays bytes a real OM642 put on the
 * wire rather than bytes this codebase predicted it would.
 *
 * ### Why the two halves
 * The `readings` half asks the source for **every channel the session captured**, including
 * `engineLoad` and `throttle` — which have no dashboard tile today, because `GAUGE_CATALOG` is
 * deliberately pinned to the core four plus rpm (`GaugeCatalogTest`, OBD-42). Those two are still
 * the strongest evidence the parser and scheduler are right about this van, so they are asserted
 * where they exist: in the readings map. The `uiState` half then runs the ViewModel exactly as
 * the app does — `start(GAUGE_CATALOG)`, no more — and asserts what a person sitting in the van
 * would actually see.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProdChainEndToEndTest {
    private val testDispatcher = StandardTestDispatcher()

    /** Fixed: nothing in this test should go stale, and every reading lands at one instant. */
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-12T18:00:00Z"), ZoneOffset.UTC)

    private val events = mutableListOf<PollEvent>()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ---- half 1: the captures, through the real protocol chain, into readings ----

    @Test
    fun `every channel the van answered on 2026-08-12 decodes to its captured value`() =
        runTest(testDispatcher) {
            val source = startVanSession(CAPTURED_CHANNELS)

            val readings = source.readings.value

            // Coolant `86` on all three ECU lines → 94 °C, re-expressed in the FAHRENHEIT the
            // app's catalog declares (and thresholds are stored in) by DisplayUnitDataSource.
            assertEquals(COOLANT_F, readings.getValue(PidIds.COOLANT).value, TOLERANCE)
            // rpm `0B54`..`0B64` → 725..729 at idle. The session recorded a range, not a sample.
            assertTrue(readings.getValue(PidIds.RPM).value in RPM_IDLE_RANGE)
            // Load `8E`/`90` → ~56 % (warm idle with AC on, at ~5 800 ft).
            assertEquals(LOAD_PERCENT, readings.getValue(ProtocolPidIds.ENGINE_LOAD).value, PERCENT_TOLERANCE)
            // Throttle `D3` → 83 %. Diesel intake flap, not a driver-commanded plate — see
            // PidRegistry.throttlePosition's caveat. 83 % at idle is the CORRECT reading.
            assertEquals(THROTTLE_PERCENT, readings.getValue(ProtocolPidIds.THROTTLE).value, PERCENT_TOLERANCE)
            // Baro `52` → 82 kPa. The half of altitude-true boost that works today.
            assertEquals(BARO_KPA, readings.getValue(PidIds.BARO).value, TOLERANCE)
        }

    @Test
    fun `boost is typed-unavailable, never a zero`() =
        runTest(testDispatcher) {
            val source = startVanSession(CAPTURED_CHANNELS)

            // OBD-57: boost is now the speed-density estimate, and its mass-airflow input has no
            // channel yet (0166 answers, but g/s has no frozen unit — OBD-58), so there is no MAP
            // and therefore no boost. The identity element of a subtraction is 0, which on a boost
            // gauge reads as "engine not pulling" and is indistinguishable from a real measurement
            // — so nothing is published at all. Absence, not a value, is the honest answer.
            assertNull(source.readings.value[PidIds.BOOST])
            assertNull(source.readings.value[ProtocolPidIds.MAF])

            // ...and the reason is announced, typed, once per session and before a single command
            // goes out: the consequence (boost) and the cause (maf) as separate events.
            val availability = events.filterIsInstance<PollEvent.ChannelAvailabilityChanged>()
            assertEquals(
                ChannelAvailability.MissingInputs(listOf(ProtocolPidIds.MAF)),
                availability.first { it.id == PidIds.BOOST }.availability,
            )
            assertTrue(
                availability.first { it.id == ProtocolPidIds.MAF }.availability
                    is ChannelAvailability.PendingUnitContract,
            )
            // MAF cannot be put on the wire — it has no channel until OBD-58 lands the g/s unit —
            // but the pollable inputs are: IAT (0168) is attempted this session.
            assertTrue(IAT_SENSOR_COMMAND in link.commands)
        }

    @Test
    fun `the trans-temp record is polled and its 63-raw value reaches the readings map`() =
        runTest(testDispatcher) {
            val source = startVanSession(CAPTURED_CHANNELS)

            // OBD-55: the crown-jewel decode is live. The 2026-08-13 drive test identified record
            // byte 1 under °C = 63 − raw; the scripted post-drive record has byte 1 = 0x12 → 45 °C,
            // re-expressed in the FAHRENHEIT the app's catalog declares.
            assertEquals(
                TRANS_F,
                source.readings.value
                    .getValue(PidIds.TRANS_TEMP)
                    .value,
                TOLERANCE,
            )

            // The framed record sequence actually went on the wire — the DecodeFalsified gate that
            // used to hold it back is gone (TRANS_TEMP left FALSIFIED_DECODES), so it is polled.
            assertTrue(HEADER_SET_TCU in link.commands)
            assertTrue(KWP_RECORD_COMMAND in link.commands)

            // And there is no availability complaint for trans any more: it is a live channel, not
            // an unsupported or falsified one.
            assertTrue(
                events.filterIsInstance<PollEvent.ChannelAvailabilityChanged>().none { it.id == PidIds.TRANS_TEMP },
            )
        }

    @Test
    fun `the real ELM327 init sequence ran once, in order, against the captured banner`() =
        runTest(testDispatcher) {
            startVanSession(CAPTURED_CHANNELS)

            assertEquals(INIT_COMMANDS, link.commands.take(INIT_COMMANDS.size))
            assertEquals(1, link.commands.count { it == "ATZ" })
            val initialized = events.filterIsInstance<PollEvent.Initialized>().single()
            assertEquals("ELM327 v2.2", initialized.result.banner)
        }

    // ---- half 2: the same chain, through the real ViewModel, into rendered state ----

    @Test
    fun `the dashboard renders the van's coolant, rpm and now the live trans temp`() =
        runTest(testDispatcher) {
            val state = renderDashboard(UnitPreferences())

            // 94 °C, displayed in the default Fahrenheit.
            assertEquals("201°F", state.coolant.valueText)
            assertTrue(
                state.extraTiles
                    .getValue(PidIds.RPM)
                    .valueText
                    .removeSuffix(" RPM")
                    .toInt() in RPM_IDLE_INTS,
            )

            // Boost: the placeholder, NOT "0.0 PSI". This is the whole reason ComputedChannels
            // returns null instead of a difference-of-absent-inputs.
            assertEquals(NO_READING_TEXT, state.boost.valueText)
            assertFalse(state.boost.valueText.contains("0"))
            // Trans (OBD-55): the byte-1 record decode is live, so the tile now shows a real value
            // — 45 °C in the default Fahrenheit. Oil (OBD-50: PidRegistry.oilTemp, standard 015C)
            // still renders "no reading" here — not because the channel is unavailable, but because
            // this session's fixture never scripted a 015C response and CAPTURED_CHANNELS below
            // only requests channels this fixture actually has bytes for.
            assertEquals(TRANS_DISPLAY, state.transTemp.valueText)
            assertEquals(NO_READING_TEXT, state.oilTemp.valueText)

            assertFalse(state.coolant.isStale)
        }

    @Test
    fun `switching to metric renders the captured coolant as its literal captured value`() =
        runTest(testDispatcher) {
            val state = renderDashboard(UnitPreferences(temperatureUnit = MeasurementUnit.CELSIUS))

            // The round trip closes: `86` on the wire → 94 °C in :core:protocol → 201.2 °F at
            // the DI seam → 94.0 °C back on screen. A conversion error anywhere in that chain
            // shows up here as a number that is not 94.
            assertEquals("94.0°C", state.coolant.valueText)
        }

    @Test
    fun `every tile's unverified badge matches the protocol catalog's own verification verdict`() =
        runTest(testDispatcher) {
            val state = renderDashboard(UnitPreferences())

            // The badge is driven by :app's PidDefinition.verified; the truth about a decode
            // lives in PidCatalog.isVerified. This pins the two together across the module
            // boundary for every id both know about, so a hypothesis promoted (or demoted) in
            // :core:protocol cannot silently leave the dashboard badging the opposite.
            // OBD-50: oilTemp joins the pinned set now that PidRegistry.oilTemp exists — the
            // exact gap this parity check exists to close (there was nothing in PidCatalog to
            // compare against before this issue).
            for (id in listOf(PidIds.COOLANT, PidIds.RPM, PidIds.BOOST, PidIds.TRANS_TEMP, PidIds.OIL_TEMP)) {
                assertEquals(id, PidCatalog.isVerified(id), GAUGE_CATALOG_BY_ID.getValue(id).verified)
            }
            assertTrue(state.coolant.verified)
            assertTrue(state.extraTiles.getValue(PidIds.RPM).verified)
            assertFalse(state.transTemp.verified)
            assertTrue(state.oilTemp.verified)
        }

    @Test
    fun `the banner is quiet because the link is Ready`() =
        runTest(testDispatcher) {
            val state = renderDashboard(UnitPreferences())

            // connection forwards ObdLink.state straight through RealVehicleDataSource and the
            // display wrapper — Ready means ConnectionBanner renders no node at all.
            assertEquals(LinkState.Ready, state.connection)
        }

    // ---- fixture plumbing ----

    private lateinit var link: ScriptedVanLink

    /**
     * Brings the real chain up on a connected [ScriptedVanLink] and runs enough poll cycles for
     * both the FAST channels and the every-Nth SLOW ones (baro among them) to have landed.
     */
    private suspend fun TestScope.startVanSession(pids: List<PidDefinition>): VehicleDataSource {
        link = ScriptedVanLink()
        link.connect()
        val real =
            RealVehicleDataSource(
                link = link,
                scope = backgroundScope,
                config = PollConfig(cycleInterval = CYCLE),
                clock = clock,
                onEvent = events::add,
            )
        val source = DisplayUnitDataSource(real, backgroundScope)
        source.start(pids)
        advanceTimeBy(CYCLE * CYCLES_TO_RUN)
        advanceUntilIdle()
        return source
    }

    /** The same chain, driven by the real [DashboardViewModel] rather than by hand. */
    private suspend fun TestScope.renderDashboard(units: UnitPreferences) =
        with(startVanSession(CAPTURED_CHANNELS)) {
            val viewModel = DashboardViewModel(this, clock, FixedSettingsRepository(AppSettings(units = units)))
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceTimeBy(CYCLE * CYCLES_TO_RUN)
            advanceUntilIdle()
            viewModel.uiState.value
        }

    private companion object {
        val CYCLE = 200.milliseconds
        const val CYCLES_TO_RUN = 12

        /**
         * Exactly the channels `docs/hardware/session-2026-08-12.md` has captured bytes for,
         * plus the two the session's *negative* results are about: `boost` (which expands to
         * `map` + `baro`) and `transTemp` (which the falsified-decode gate must keep off the
         * wire). `speed` is left out on purpose — the bitmap advertises it but the session never
         * captured a reply, and scripting one would be a prediction.
         */
        val CAPTURED_CHANNELS: List<PidDefinition> =
            PidCatalog.definitions.filter {
                it.id in
                    setOf(
                        PidIds.COOLANT,
                        PidIds.RPM,
                        PidIds.BARO,
                        PidIds.BOOST,
                        PidIds.TRANS_TEMP,
                        ProtocolPidIds.ENGINE_LOAD,
                        ProtocolPidIds.THROTTLE,
                    )
            }

        val INIT_COMMANDS = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0", "0100")

        const val IAT_SENSOR_COMMAND = "0168"
        const val KWP_RECORD_COMMAND = "2130"
        const val HEADER_SET_TCU = "ATSH7E1"

        /** `86` → 94 °C, converted to the FAHRENHEIT `:app`'s catalog declares for coolant. */
        const val COOLANT_F = 201.2

        /** Record byte 1 `0x12` → 45 °C (63 − 18), converted to the app's declared FAHRENHEIT. */
        const val TRANS_F = 113.0

        /** 45 °C in the default Fahrenheit, as the tile renders it. */
        const val TRANS_DISPLAY = "113°F"

        /** `0B54`..`0B64` at warm idle. */
        val RPM_IDLE_RANGE = 725.0..729.0
        val RPM_IDLE_INTS = 725..729

        /** `8E` = 142 → 142 × 100 / 255, and `90` = 144 → 144 × 100 / 255. */
        const val LOAD_PERCENT = 56.0

        /** `D3` = 211 → 211 × 100 / 255. */
        const val THROTTLE_PERCENT = 82.7

        /** `52` = 82 kPa absolute. */
        const val BARO_KPA = 82.0

        const val TOLERANCE = 0.01
        const val PERCENT_TOLERANCE = 1.0
    }
}

/** Non-persisting [SettingsRepository]: this test is about the protocol chain, not DataStore. */
private class FixedSettingsRepository(
    settings: AppSettings,
) : SettingsRepository {
    private val state = MutableStateFlow(settings)
    override val settings: Flow<AppSettings> = state

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
