package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.gatt.GattTransport
import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.scan.DiscoveredDevice
import com.revel.obdgauge.ble.scan.ScanOutcome
import com.revel.obdgauge.ble.testing.DEVICE
import com.revel.obdgauge.ble.testing.FakeBleEnvironment
import com.revel.obdgauge.ble.testing.FakeBleScanner
import com.revel.obdgauge.ble.testing.FakeGattTransport
import com.revel.obdgauge.ble.testing.FakeRememberedDeviceStore
import com.revel.obdgauge.ble.testing.RecordingLogger
import com.revel.obdgauge.ble.testing.bleObdLink
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-23's soak: fifty scripted key-off/key-on cycles, back to back, against the GATT doubles.
 *
 * ### What it drops
 * Each cycle brings the link to Ready, runs a command whose answer is cycle-unique, then abandons
 * a second command and lets its answer arrive late — so the session is carrying a response debt —
 * and then drops the connection out from under it exactly as an ignition-off does. Auto-reconnect
 * brings it back for the next cycle unaided: `connect()` is called once, at the top, and fifty of
 * the fifty-one links in this test are ones the module decided to build by itself.
 *
 * ### What it asserts
 * Per cycle: the link comes back to Ready on the remembered fast path, the answer it receives is
 * *its own* (an unsettled debt would hand it the previous cycle's, which is this project's worst
 * failure mode — plausible and wrong), the drop is reported, and exactly one GATT client is live.
 * Across the soak: one client created per cycle and every retired one closed exactly once
 * (Android grants a process ~32 and only `close()` returns one, so a leak here kills BLE app-wide
 * within an hour of driving), never two clients live at once, one scan for the whole run, and the
 * backoff never escalating — a cycle that recovers on its first retry must not inherit the last
 * cycle's attempt count.
 */
class ReconnectSoakTest {
    private val environment = FakeBleEnvironment()
    private val scanner = FakeBleScanner(ScanOutcome.Found(DiscoveredDevice(DEVICE, "VEEPEAK-OBD")))
    private val logger = RecordingLogger()

    @Test
    fun `fifty scripted drop and recover cycles leak nothing, duplicate nothing and settle every debt`() =
        runTest {
            val clients = TrackingTransportFactory()
            val link = bleObdLink(environment, scanner, clients, FakeRememberedDeviceStore(), logger, SOAK_CONFIG)
            val perCycleState = mutableListOf<LinkState>()

            link.connect()
            assertEquals("the soak never even started", LinkState.Ready, link.state.value)

            repeat(CYCLES) { index ->
                val cycle = index + 1
                val live = clients.live ?: error("cycle $cycle: no live client to drive")

                // 1. A normal exchange, keyed to this cycle — so receiving the previous cycle's
                //    answer is detectable rather than merely suspicious.
                live.responder = { command -> listOf("$command=$cycle\r\r>") }
                assertEquals(
                    "cycle $cycle was handed another cycle's answer",
                    "0105=$cycle",
                    link.sendRaw("0105", TIMEOUT),
                )

                // 2. Abandon a command, then let its answer turn up late: the session ends this
                //    cycle owing a response, which must not follow it into the next one.
                live.responder = { emptyList() }
                val abandoned = async { runCatching { link.sendRaw("010B", TIMEOUT) } }
                runCurrent()
                advanceTimeBy(TIMEOUT + 1.milliseconds)
                runCurrent()
                assertTrue("cycle $cycle: a silent dongle must fail the command", abandoned.await().isFailure)
                live.emitResponse("010B=$cycle\r\r>")
                runCurrent()

                // 3. Key-off.
                live.dropConnection()
                runCurrent()
                assertTrue("cycle $cycle did not report the drop", link.state.value is LinkState.Error)
                assertNull("cycle $cycle left a client open after the drop", clients.live)

                // 4. Key-on. Nobody calls connect(); the backoff elapses and the link rebuilds
                //    itself on the remembered address.
                advanceTimeBy(BACKOFF + 1.milliseconds)
                runCurrent()
                perCycleState += link.state.value
                assertEquals("cycle $cycle did not recover on its own", LinkState.Ready, link.state.value)
                assertEquals("cycle $cycle is running two sessions at once", 1, clients.liveCount)
            }

            assertEquals("every cycle must end Ready", List(CYCLES) { LinkState.Ready }, perCycleState)
            assertEquals("one client per cycle, plus the first connect", CYCLES + 1, clients.created.size)
            assertEquals(
                "every retired client must be handed back",
                CYCLES,
                clients.created.count { it.closeCount > 0 },
            )
            assertEquals("and handed back exactly once", CYCLES, clients.created.sumOf { it.closeCount })
            assertEquals("the fast path is the resume path: one scan for the whole soak", 1, scanner.calls)
            assertEquals("never two live clients", 1, clients.peakLive)
            assertTrue(
                "a cycle that recovers on its first retry must not inherit the last cycle's attempt count",
                logger.containing("reconnect scheduled: attempt 2").isEmpty(),
            )
        }

    @Test
    fun `a link that never comes back stops at its budget instead of retrying forever`() =
        runTest {
            // The other half of "no state corruption": fifty failures in a row must end in one
            // stated give-up, one client per attempt, and nothing still scheduled.
            val clients = TrackingTransportFactory(healthy = false)
            val link =
                bleObdLink(
                    environment,
                    FakeBleScanner(ScanOutcome.Failed(LinkError.DeviceNotFound)),
                    clients,
                    FakeRememberedDeviceStore(DEVICE),
                    logger,
                    SOAK_CONFIG.copy(reconnectMaxAttempts = CYCLES),
                )

            link.connect()
            advanceTimeBy(1.hours)
            runCurrent()

            assertEquals("one client per attempt, and not one more", CYCLES + 1, clients.created.size)
            assertEquals(
                "a failed attempt still hands its client back",
                CYCLES + 1,
                clients.created.sumOf { it.closeCount },
            )
            assertEquals(1, logger.containing("gave up after $CYCLES attempts").size)
        }

    /**
     * Hands out a fresh scripted client per request and tracks liveness, because "no leaked
     * clients" and "no duplicate sessions" are counting problems and one shared double cannot
     * answer them.
     */
    private class TrackingTransportFactory(
        private val healthy: Boolean = true,
    ) : GattTransportFactory {
        val created = mutableListOf<FakeGattTransport>()

        var peakLive = 0
            private set

        val liveCount: Int get() = created.count { it.closeCount == 0 }

        /** The one client currently open, or null — and a loud failure if there is more than one. */
        val live: FakeGattTransport?
            get() {
                val open = created.filter { it.closeCount == 0 }
                check(open.size <= 1) { "two GATT clients are live at once: $open" }
                return open.firstOrNull()
            }

        override fun create(address: String): GattTransport {
            val transport =
                FakeGattTransport(
                    address = address,
                    connectStatus = if (healthy) GATT_SUCCESS_STATUS else FakeGattTransport.GATT_ERROR,
                )
            created += transport
            peakLive = maxOf(peakLive, liveCount)
            return transport
        }

        private companion object {
            const val GATT_SUCCESS_STATUS = 0
        }
    }

    private companion object {
        const val CYCLES = 50
        val TIMEOUT = 2.seconds
        val BACKOFF: Duration = 1.seconds

        /**
         * Jitter off so the soak advances by an exact backoff each cycle; the jittered band is
         * pinned in `ReconnectPolicyTest` and once through the real link in `ReconnectTest`.
         */
        val SOAK_CONFIG = BleConfig(reconnectInitialDelay = BACKOFF, reconnectJitter = 0.0)
    }
}
