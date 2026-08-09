package com.revel.obdgauge.testing.datasource

import app.cash.turbine.test
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PollPriority
import com.revel.obdgauge.model.Reading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeVehicleDataSourceTest {
    private val tick = 100.milliseconds

    private fun pid(id: String) =
        PidDefinition(
            id = id,
            label = id,
            unit = MeasurementUnit.FAHRENHEIT,
            request = ObdRequest.StandardPid(mode = 1, pid = 0),
            parse = { 0.0 },
            pollPriority = PollPriority.SLOW,
        )

    private val allChannels =
        listOf(
            ScenarioChannel.COOLANT,
            ScenarioChannel.OIL_TEMP,
            ScenarioChannel.TRANS_TEMP,
            ScenarioChannel.BOOST,
            ScenarioChannel.RPM,
        )
    private val allPids = allChannels.map(::pid)

    @Test
    fun `IDLE scenario emits exact scripted sequence`() =
        runTest {
            val fake = FakeVehicleDataSource(Scenario.IDLE, tick, Instant.EPOCH, backgroundScope)
            val expectedCoolant = listOf(190.0, 190.5, 189.5, 190.25, 189.75, 190.5, 189.5, 190.0)
            val expectedRpm = listOf(780.0, 790.0, 770.0, 785.0, 775.0, 790.0, 770.0, 780.0)

            fake.readings.test {
                assertEquals(emptyMap<String, Reading>(), awaitItem())
                fake.start(allPids)
                for (i in expectedCoolant.indices) {
                    val item = awaitItem()
                    assertEquals(expectedCoolant[i], item.getValue(ScenarioChannel.COOLANT).value, 0.0)
                    assertEquals(expectedRpm[i], item.getValue(ScenarioChannel.RPM).value, 0.0)
                    assertTrue(item.values.none { it.stale })
                }
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `TOWN_HEAT_SOAK scenario emits exact scripted sequence and crosses amber thresholds`() =
        runTest {
            val fake = FakeVehicleDataSource(Scenario.TOWN_HEAT_SOAK, tick, Instant.EPOCH, backgroundScope)
            val expectedCoolant = listOf(190.0, 197.0, 204.0, 211.0, 218.0, 225.0)
            val expectedOil = listOf(200.0, 208.0, 216.0, 224.0, 232.0, 240.0)
            val expectedTrans = listOf(160.0, 171.0, 182.0, 193.0, 204.0, 215.0)

            fake.readings.test {
                assertEquals(emptyMap<String, Reading>(), awaitItem())
                fake.start(allPids)
                for (i in expectedCoolant.indices) {
                    val item = awaitItem()
                    assertEquals(expectedCoolant[i], item.getValue(ScenarioChannel.COOLANT).value, 0.0)
                    assertEquals(expectedOil[i], item.getValue(ScenarioChannel.OIL_TEMP).value, 0.0)
                    assertEquals(expectedTrans[i], item.getValue(ScenarioChannel.TRANS_TEMP).value, 0.0)
                }
                cancelAndIgnoreRemainingEvents()
            }

            // Thresholds per docs/01-build-plan.md: coolant amber 220-230, oil amber >235,
            // trans amber 200-240.
            val coolantAmber = 220.0..230.0
            val oilAmberThreshold = 235.0
            val transAmber = 200.0..240.0
            assertTrue(expectedCoolant.last() in coolantAmber)
            assertTrue(expectedOil.last() > oilAmberThreshold)
            assertTrue(expectedTrans.last() in transAmber)
        }

    @Test
    fun `GRADE_CLIMB stays within scripted boost and rpm bounds and hits the sweep peak`() =
        runTest {
            val fake = FakeVehicleDataSource(Scenario.GRADE_CLIMB, tick, Instant.EPOCH, backgroundScope)
            val emitCount = 8

            fake.readings.test {
                awaitItem()
                fake.start(allPids)
                val boosts = mutableListOf<Double>()
                val rpms = mutableListOf<Double>()
                repeat(emitCount) {
                    val item = awaitItem()
                    boosts += item.getValue(ScenarioChannel.BOOST).value
                    rpms += item.getValue(ScenarioChannel.RPM).value
                }
                cancelAndIgnoreRemainingEvents()

                assertTrue(boosts.all { it in 0.0..15.0 })
                assertEquals(15.0, boosts.max(), 0.0)
                assertTrue(rpms.all { it in 2000.0..3200.0 })
                assertEquals(3200.0, rpms.max(), 0.0)
            }
        }

    @Test
    fun `GRADE_CLIMB scenario is deterministic across separate runs`() =
        runTest {
            suspend fun replay(): List<Map<String, Reading>> {
                val fake = FakeVehicleDataSource(Scenario.GRADE_CLIMB, tick, Instant.EPOCH, backgroundScope)
                val captured = mutableListOf<Map<String, Reading>>()
                fake.readings.test {
                    captured += awaitItem()
                    fake.start(allPids)
                    repeat(8) { captured += awaitItem() }
                    cancelAndIgnoreRemainingEvents()
                }
                return captured
            }

            val first = replay()
            val second = replay()
            assertEquals(first, second)
        }

    @Test
    fun `DISCONNECT_RECONNECT walks LinkState Ready to Error to Scanning to Connecting to Ready`() =
        runTest {
            val fake = FakeVehicleDataSource(Scenario.DISCONNECT_RECONNECT, tick, Instant.EPOCH, backgroundScope)

            fake.connection.test {
                assertEquals(LinkState.Disconnected, awaitItem())
                fake.start(allPids)
                assertEquals(LinkState.Ready, awaitItem())
                assertEquals(LinkState.Error(LinkError.Timeout), awaitItem())
                assertEquals(LinkState.Scanning, awaitItem())
                assertEquals(LinkState.Connecting, awaitItem())
                assertEquals(LinkState.Ready, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `DISCONNECT_RECONNECT freezes readings stale during the outage`() =
        runTest {
            val pids = listOf(pid(ScenarioChannel.COOLANT), pid(ScenarioChannel.RPM))
            val fake = FakeVehicleDataSource(Scenario.DISCONNECT_RECONNECT, tick, Instant.EPOCH, backgroundScope)

            fake.readings.test {
                assertEquals(emptyMap<String, Reading>(), awaitItem())
                fake.start(pids)

                val first = awaitItem()
                assertEquals(190.0, first.getValue(ScenarioChannel.COOLANT).value, 0.0)
                assertTrue(first.values.none { it.stale })

                val second = awaitItem()
                assertEquals(191.0, second.getValue(ScenarioChannel.COOLANT).value, 0.0)
                assertTrue(second.values.none { it.stale })

                val staled = awaitItem()
                assertEquals(191.0, staled.getValue(ScenarioChannel.COOLANT).value, 0.0)
                assertEquals(
                    second.getValue(ScenarioChannel.COOLANT).timestamp,
                    staled.getValue(ScenarioChannel.COOLANT).timestamp,
                )
                assertTrue(staled.values.all { it.stale })

                val recovered = awaitItem()
                assertEquals(192.0, recovered.getValue(ScenarioChannel.COOLANT).value, 0.0)
                assertTrue(recovered.values.none { it.stale })

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `start replaces the active set and stop is idempotent`() =
        runTest {
            val fake = FakeVehicleDataSource(Scenario.IDLE, tick, Instant.EPOCH, backgroundScope)

            fake.start(allPids)
            advanceTimeBy(tick * 3)
            runCurrent()
            assertTrue(fake.readings.value.isNotEmpty())

            fake.start(allPids)
            assertEquals(emptyMap<String, Reading>(), fake.readings.value)

            fake.stop()
            fake.stop()
            assertEquals(LinkState.Disconnected, fake.connection.value)
        }
}
