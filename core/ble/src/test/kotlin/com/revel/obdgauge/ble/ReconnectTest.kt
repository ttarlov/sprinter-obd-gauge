package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.gatt.GattTransport
import com.revel.obdgauge.ble.permission.BlePermissionPolicy
import com.revel.obdgauge.ble.scan.BleScanner
import com.revel.obdgauge.ble.scan.DiscoveredDevice
import com.revel.obdgauge.ble.scan.ScanOutcome
import com.revel.obdgauge.ble.scan.ScanPass
import com.revel.obdgauge.ble.store.RememberedDeviceStore
import com.revel.obdgauge.ble.testing.DEVICE
import com.revel.obdgauge.ble.testing.FakeBleEnvironment
import com.revel.obdgauge.ble.testing.FakeBleScanner
import com.revel.obdgauge.ble.testing.FakeGattTransport
import com.revel.obdgauge.ble.testing.FakeGattTransportFactory
import com.revel.obdgauge.ble.testing.FakeRememberedDeviceStore
import com.revel.obdgauge.ble.testing.RecordingLogger
import com.revel.obdgauge.ble.testing.bleObdLink
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-23: the reconnect state machine driven through the real link against the GATT doubles.
 *
 * Every test here spends virtual time, because the whole feature *is* time — what the module has
 * to get right is not "does it try again" but "when, how often, and when does it stop". Jitter is
 * switched off in most of them so the arithmetic is assertable; the band itself is pinned in
 * [ReconnectPolicyTest] and once here.
 */
class ReconnectTest {
    private val environment = FakeBleEnvironment()
    private val scanner = FakeBleScanner(ScanOutcome.Found(DiscoveredDevice(DEVICE, "VEEPEAK-OBD")))
    private val store = FakeRememberedDeviceStore()
    private val logger = RecordingLogger()
    private val transport = FakeGattTransport()

    /** Jitter off, so a schedule is arithmetic instead of a band. The band is pinned separately. */
    private val unjittered = BleConfig(reconnectInitialDelay = 1.seconds, reconnectJitter = 0.0)

    private var factoryInUse: FakeGattTransportFactory? = null

    // ---- the drop → retry loop --------------------------------------------------------------

    @Test
    fun `a recoverable drop reconnects itself, on the remembered fast path, with no scan`() =
        runTest {
            val link = newLink()
            link.connect()
            val scansToGetHere = scanner.calls

            transport.dropConnection()
            runCurrent()
            assertEquals(
                "the wait is spent in Error — LinkState has no Reconnecting",
                ERROR_AFTER_DROP,
                link.state.value,
            )

            advancePast(INITIAL)

            assertEquals(LinkState.Ready, link.state.value)
            assertEquals(
                "the resume path is the remembered fast path, not a fresh sweep",
                scansToGetHere,
                scanner.calls,
            )
        }

    @Test
    fun `nothing happens before the backoff has elapsed`() =
        runTest {
            val link = newLink()
            link.connect()
            val clientsSoFar = clientCount()

            transport.dropConnection()
            runCurrent()
            advanceTimeBy(INITIAL - 1.milliseconds)
            runCurrent()

            assertEquals("a retry that fires early is a spin, not a backoff", ERROR_AFTER_DROP, link.state.value)
            assertEquals(clientsSoFar, clientCount())
        }

    @Test
    fun `the wait doubles across consecutive failures and holds at the ceiling`() =
        runTest {
            val attempts = mutableListOf<Long>()
            val failing = timestampingScanner(attempts) { ScanOutcome.Failed(LinkError.DeviceNotFound) }
            val link = newLink(scanner = failing)

            link.connect()
            advancePast(INITIAL + 2.seconds + 4.seconds + 8.seconds + 16.seconds + 32.seconds + CEILING)

            // The first entry is the connect() the caller asked for; the rest are the retries.
            val gaps = attempts.zipWithNext { earlier, later -> (later - earlier).milliseconds }
            assertEquals(
                listOf(INITIAL, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 32.seconds, CEILING),
                gaps,
            )
        }

    @Test
    fun `a success resets the backoff, so the next drop waits one second again`() =
        runTest {
            val attempts = mutableListOf<Long>()
            var findable = false
            val flaky =
                timestampingScanner(attempts) {
                    if (findable) {
                        ScanOutcome.Found(
                            DiscoveredDevice(DEVICE, "VEEPEAK-OBD"),
                        )
                    } else {
                        ScanOutcome.Failed(LinkError.DeviceNotFound)
                    }
                }
            val link = newLink(scanner = flaky)

            link.connect()
            advancePast(INITIAL) // retry 1 fails
            advancePast(2.seconds) // retry 2 fails
            findable = true
            advancePast(4.seconds) // retry 3 succeeds
            assertEquals(LinkState.Ready, link.state.value)

            val readyAt = currentTime
            transport.dropConnection()
            runCurrent()
            advancePast(INITIAL)

            assertEquals(LinkState.Ready, link.state.value)
            assertTrue(
                "a link that has been up since must not inherit the old attempt count",
                currentTime - readyAt < 2.seconds.inWholeMilliseconds,
            )
        }

    @Test
    fun `key-off then key-on recovers with no user action at all`() =
        runTest {
            // The van's OBD port dies with the ignition. The dongle is gone — every connect
            // attempt fails outright — until the key comes back, and the app must be connected
            // again by itself. Exactly one user-initiated call in this whole test.
            var poweredOn = true
            val dongle = FakeGattTransport()
            val factory =
                FakeGattTransportFactory {
                    if (poweredOn) dongle else FakeGattTransport(connectStatus = FakeGattTransport.GATT_ERROR)
                }
            val link =
                newLink(
                    // Remembered from a previous drive, so the fast path is what runs — and what
                    // keeps failing while the port is dead.
                    store = FakeRememberedDeviceStore(DEVICE),
                    transports = factory,
                    scanner = FakeBleScanner(ScanOutcome.Failed(LinkError.DeviceNotFound)),
                )

            link.connect()
            assertEquals(LinkState.Ready, link.state.value)

            poweredOn = false
            dongle.dropConnection()
            runCurrent()
            advancePast(INITIAL)
            advancePast(2.seconds)
            advancePast(4.seconds)
            assertTrue("still off, still trying", link.state.value is LinkState.Error)

            poweredOn = true
            advancePast(8.seconds)

            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a connect that fails outright is retried too, not just a drop after Ready`() =
        runTest {
            // Starting the app while the van is off: connect() means "be connected", so the
            // first failure schedules a retry exactly like a mid-drive drop does.
            var findable = false
            val scanner =
                object : BleScanner {
                    override suspend fun scan(
                        passes: List<ScanPass>,
                        preferredAddress: String?,
                    ): ScanOutcome =
                        if (findable) {
                            ScanOutcome.Found(DiscoveredDevice(DEVICE, "VEEPEAK-OBD"))
                        } else {
                            ScanOutcome.Failed(LinkError.DeviceNotFound)
                        }
                }
            val link = newLink(scanner = scanner)

            link.connect()
            assertEquals(LinkState.Error(LinkError.DeviceNotFound), link.state.value)

            findable = true
            advancePast(INITIAL)

            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `resume-on-found falls back to a scan when the remembered address stops answering`() =
        runTest {
            // The dongle came back on a different address (swapped unit, or a randomised one).
            // The fast path fails and the retry's scan is what finds it — no new machinery.
            val gone = FakeGattTransport(address = STALE, connectStatus = FakeGattTransport.GATT_ERROR)
            val factory = FakeGattTransportFactory { address -> if (address == DEVICE) transport else gone }
            val moved = FakeRememberedDeviceStore(STALE)
            val link = newLink(store = moved, transports = factory)

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
            assertEquals("the scan is the resume-on-found path", 1, scanner.calls)
            assertEquals(
                "and the address it found replaces the one that stopped answering",
                DEVICE,
                moved.remembered.last(),
            )
        }

    // ---- stopping: the typed distinction ----------------------------------------------------

    @Test
    fun `a user disconnect stops the retries for good`() =
        runTest {
            val link = newLink()
            link.connect()
            transport.dropConnection()
            runCurrent()

            link.disconnect()
            val clientsAtStop = clientCount()
            advancePast(1.minutes)

            assertEquals(LinkState.Disconnected, link.state.value)
            assertEquals("a disconnected link must stay disconnected", clientsAtStop, clientCount())
        }

    @Test
    fun `forgetting the remembered device stops the retries`() =
        runTest {
            val link = newLink()
            link.connect()
            transport.dropConnection()
            runCurrent()

            link.forgetRememberedDevice()
            val clientsAtStop = clientCount()
            advancePast(1.minutes)

            assertTrue(link.state.value is LinkState.Error)
            assertEquals("a forgotten device must not still be hunted", clientsAtStop, clientCount())
        }

    @Test
    fun `a denied permission is never retried`() =
        runTest {
            environment.missing = listOf(BlePermissionPolicy.BLUETOOTH_SCAN)
            val link = newLink()

            link.connect()
            advancePast(1.minutes)

            assertEquals(LinkState.Error(LinkError.PermissionDenied), link.state.value)
            assertEquals("retrying a denied permission is a loop that never converges", 0, scanner.calls)
        }

    @Test
    fun `a re-armed connect resumes retrying after a terminal stop`() =
        runTest {
            // The app grants the permission and asks again: connect() re-arms, so the machine is
            // not permanently poisoned by one terminal verdict.
            environment.missing = listOf(BlePermissionPolicy.BLUETOOTH_SCAN)
            val link = newLink()
            link.connect()

            environment.missing = emptyList()
            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `the attempt budget is finite and the link says when it is spent`() =
        runTest {
            val budgeted = unjittered.copy(reconnectMaxAttempts = BUDGET)
            val empty = FakeBleScanner(ScanOutcome.Failed(LinkError.DeviceNotFound))
            val link = newLink(config = budgeted, scanner = empty)

            link.connect()
            advancePast(1.minutes)

            assertEquals("one caller-driven attempt plus the budget", BUDGET + 1, empty.calls)
            assertTrue(logger.containing("gave up after $BUDGET attempts").isNotEmpty())
        }

    // ---- the generation invariant -----------------------------------------------------------

    @Test
    fun `an explicit connect supersedes a scheduled retry and restarts the backoff`() =
        runTest {
            val attempts = mutableListOf<Long>()
            val failing = timestampingScanner(attempts) { ScanOutcome.Failed(LinkError.DeviceNotFound) }
            val link = newLink(scanner = failing)

            link.connect()
            advancePast(INITIAL) // retry 1: the next wait would be 2s
            attempts.clear()

            link.connect()
            val explicitAt = currentTime
            advancePast(INITIAL)

            assertEquals("the user asking again is not the third failure in a row", 2, attempts.size)
            assertEquals(explicitAt, attempts.first())
            assertEquals(INITIAL.inWholeMilliseconds, attempts.last() - explicitAt)
        }

    @Test
    fun `a disconnect during a retry's handshake wins and the finished session is released`() =
        runTest {
            // The invariant, applied to OBD-23: a retry that is superseded mid-handshake must
            // not publish, must not keep its client, and must not book another retry.
            val silent = FakeGattTransport(connectStatus = null)
            val second = FakeGattTransport()
            val queue = ArrayDeque(listOf<GattTransport>(transport, silent, second))
            val factory = FakeGattTransportFactory { queue.removeFirst() }
            val link = newLink(transports = factory)

            link.connect()
            transport.dropConnection()
            runCurrent()
            advancePast(INITIAL) // the retry is now stuck in a silent handshake on `silent`

            link.disconnect()
            advancePast(1.minutes)

            assertEquals(LinkState.Disconnected, link.state.value)
            assertEquals("the superseded retry's client must be handed back", 1, silent.closeCount)
            assertEquals("and no third client may be opened", 2, factory.requested.size)
        }

    @Test
    fun `a superseded attempt does not schedule a reconnect of its own`() =
        runTest {
            // Two connects race; the loser fails on a stale ticket. If the loser were allowed to
            // book a retry, the winner's Ready link would be torn down seconds later by a
            // reconnect nobody asked for.
            val silent = FakeGattTransport(connectStatus = null)
            val winner = FakeGattTransport()
            val queue = ArrayDeque(listOf<GattTransport>(silent, winner))
            val factory = FakeGattTransportFactory { queue.removeFirst() }
            val link = newLink(store = FakeRememberedDeviceStore(DEVICE), transports = factory)

            val superseded = launch { link.connect() }
            runCurrent()
            link.connect()
            runCurrent()
            superseded.join()
            assertEquals(LinkState.Ready, link.state.value)

            advancePast(1.minutes)

            assertEquals("a stale attempt must do no work, and scheduling a retry is work", 2, factory.requested.size)
            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a stale attempt that finishes after the winner has already failed books nothing`() =
        runTest {
            // The sharp edge of the invariant, and the one the Ready-winner case above cannot
            // reach: when the winner *also* fails, the state flow already holds an Error by the
            // time the loser lands, so every later guard in scheduleReconnect waves it through.
            // Only the generation check stops it — and unchecked it books a second retry, which
            // orphans the first scheduled job (the field is overwritten, not cancelled) and fires
            // two attempts where one was owed.
            //
            // The loser is held in a slow sweep so it can only land after the winner is done.
            var sweep = 0
            val slowFirstSweep =
                object : BleScanner {
                    override suspend fun scan(
                        passes: List<ScanPass>,
                        preferredAddress: String?,
                    ): ScanOutcome {
                        if (sweep++ == 0) {
                            delay(SLOW_SWEEP)
                        }
                        return ScanOutcome.Failed(LinkError.DeviceNotFound)
                    }
                }
            // A backoff far longer than the sweep, so nothing else can write to the log meanwhile.
            val link = newLink(scanner = slowFirstSweep, config = unjittered.copy(reconnectInitialDelay = 5.minutes))

            val superseded = launch { link.connect() }
            runCurrent()
            link.connect()
            runCurrent()
            assertEquals("the winner should have failed and booked its own retry", 1, attemptsBooked())

            advancePast(SLOW_SWEEP)
            superseded.join()

            assertEquals(
                "a stale attempt must do no work, and booking a retry is work: ${logger.containing(BOOKED)}",
                1,
                attemptsBooked(),
            )
        }

    // ---- round-2 review: B1, the unattended attempt has an exception boundary ----------------

    @Test
    fun `an attempt that throws mid-sweep is reported and retried, never left wedged in Scanning`() =
        runTest {
            // Round-1 BLOCKER. A scheduled retry has no caller to catch for it, and OEM stacks
            // throw IllegalStateException out of startScan when the adapter dies mid-sweep. The
            // throw used to escape the job: state stuck on Scanning, autoReconnect still true,
            // nothing scheduled, nothing logged — every later key-on ignored, forever.
            var dongleAlive = true
            val healthy = FakeGattTransport()
            val factory =
                FakeGattTransportFactory {
                    if (dongleAlive) healthy else FakeGattTransport(connectStatus = FakeGattTransport.GATT_ERROR)
                }
            val link =
                newLink(
                    store = FakeRememberedDeviceStore(DEVICE),
                    transports = factory,
                    scanner = throwingScanner(),
                )

            link.connect()
            assertEquals(LinkState.Ready, link.state.value)

            dongleAlive = false
            healthy.dropConnection()
            runCurrent()
            advancePast(INITIAL) // the retry: fast path fails, then the sweep throws

            val parked = link.state.value
            assertTrue("a throw must not leave the link mid-attempt: $parked", parked is LinkState.Error)
            val cause = (parked as LinkState.Error).cause
            assertTrue(
                "$cause",
                cause is LinkError.Unknown && cause.message.startsWith(ReconnectPolicy.ABNORMAL_ATTEMPT),
            )
            assertTrue(
                "the throw must be named, not swallowed: $cause",
                (cause as LinkError.Unknown).message.contains(ADAPTER_DIED),
            )
            assertEquals("an abnormal attempt is still an attempt: it must book the next one", 2, attemptsBooked())
            assertTrue("and the machine must still say it is trying", link.retrying.value)
        }

    @Test
    fun `an attempt that throws with the budget spent disarms out loud rather than silently`() =
        runTest {
            val link =
                newLink(
                    store = FakeRememberedDeviceStore(DEVICE),
                    transports =
                        FakeGattTransportFactory {
                            FakeGattTransport(
                                connectStatus = FakeGattTransport.GATT_ERROR,
                            )
                        },
                    scanner = throwingScanner(),
                    config = unjittered.copy(reconnectMaxAttempts = 1),
                )

            link.connect()
            advancePast(INITIAL)

            assertTrue("never Scanning", link.state.value is LinkState.Error)
            assertFalse("a spent budget must be visible, not inferred", link.retrying.value)
            assertEquals(1, logger.containing("gave up after 1 attempts").size)
        }

    @Test
    fun `a recovery that itself throws cannot poison the retry scope`() =
        runTest {
            // The second half of B1's fix. The try/catch handles a throwing *attempt*, but the
            // recovery path is code too: here a logger dies on the abnormal message, so the throw
            // comes out of the catch block itself and only the CoroutineExceptionHandler on
            // linkScope is left.
            //
            // Asserted on the handler's own log line, not on scope survival — `SupervisorJob`
            // already guarantees a failed child cannot cancel the scope, so a test written
            // against "the link still works" passes with the handler deleted and pins nothing.
            // What the handler uniquely does is *observe* the escape (and in production keep an
            // uncaught coroutine throwable off the app's default handler, which a JVM test
            // cannot see at all).
            val brittle =
                BleLogger { message ->
                    logger.log(message)
                    if (message.startsWith(ReconnectPolicy.ABNORMAL_ATTEMPT)) {
                        throw IllegalStateException("the logger itself is broken")
                    }
                }
            val healthy = FakeGattTransport()
            var dongleAlive = true
            val factory =
                FakeGattTransportFactory {
                    if (dongleAlive) healthy else FakeGattTransport(connectStatus = FakeGattTransport.GATT_ERROR)
                }
            val link =
                newLink(
                    store = FakeRememberedDeviceStore(DEVICE),
                    transports = factory,
                    scanner = throwingScanner(),
                    logger = brittle,
                )

            link.connect()
            dongleAlive = false
            healthy.dropConnection()
            runCurrent()
            advancePast(INITIAL) // retry throws; the recovery for it throws too

            assertEquals(
                "the escape must be caught and named by the handler",
                1,
                logger.containing("escaped its own boundary").size,
            )
            // And it must have been a real second attempt at recovery, not just a log line.
            assertEquals(
                "the handler re-runs the recovery it netted",
                2,
                logger.containing(ReconnectPolicy.ABNORMAL_ATTEMPT).size,
            )

            // The link is still usable afterwards: one poisoned retry takes nothing with it.
            dongleAlive = true
            link.connect()
            assertEquals(LinkState.Ready, link.state.value)
        }

    // ---- round-2 review: M1, forget supersedes an in-flight attempt --------------------------

    @Test
    fun `forgetting the device mid-attempt supersedes it, so the old address is not re-remembered`() =
        runTest {
            // Round-1 MAJOR. Forget disarmed the retries but left the running attempt alone, so
            // it completed and re-remembered the address just forgotten. Van scenario: swap the
            // dongle at a rest stop, press Forget, and the app quietly re-learns the old unit —
            // with auto-reconnect now off, so the new one is never found.
            val store = SlowFirstReadStore(DEVICE)
            val link = newLink(store = store)

            val inFlight = launch { link.connect() }
            runCurrent() // parked in the remembered-address read
            link.forgetRememberedDevice()
            advancePast(SLOW_READ)
            inFlight.join()

            assertEquals("the forgotten address must not be re-remembered", emptyList<String>(), store.remembered)
            assertEquals(1, store.forgetCount)
            assertEquals("a superseded attempt opens no client at all", 0, clientCount())
            assertTrue(
                "and books no state",
                link.state.value !is LinkState.Error && link.state.value != LinkState.Ready,
            )
        }

    // ---- round-2 review: m2, the stale gate comes before the sweep ---------------------------

    @Test
    fun `a superseded attempt does not burn a scan`() =
        runTest {
            // Android allows 5 startScan calls per rolling 30 s. The gate used to sit *after* the
            // sweep, so a superseded attempt still spent one of them and merely discarded what it
            // found — a fifth of the budget, thrown away by a user tapping Connect twice.
            val link = newLink(store = SlowFirstReadStore(null))

            val superseded = launch { link.connect() }
            runCurrent() // parked in the remembered-address read
            link.disconnect()
            advancePast(SLOW_READ)
            superseded.join()

            assertEquals("a stale attempt must not reach the radio at all", 0, scanner.calls)
            assertEquals(LinkState.Disconnected, link.state.value)
        }

    // ---- round-2 review: m3, giving up is observable -----------------------------------------

    @Test
    fun `retrying tracks the machine, so giving up is distinguishable from still trying`() =
        runTest {
            // LinkState is frozen, so the whole retry loop is spent in Error(cause) and "sit
            // tight" and "press Retry" look identical to :app without this flow.
            val empty = FakeBleScanner(ScanOutcome.Failed(LinkError.DeviceNotFound))
            val link = newLink(config = unjittered.copy(reconnectMaxAttempts = BUDGET), scanner = empty)

            link.connect()
            assertTrue("a booked retry means the module is still trying", link.retrying.value)
            assertTrue(link.state.value is LinkState.Error)

            advancePast(1.minutes)

            assertFalse("giving up has to be observable", link.retrying.value)
            assertTrue("and the cause must stay on screen while it is", link.state.value is LinkState.Error)
        }

    @Test
    fun `retrying goes quiet the moment the link is back`() =
        runTest {
            val link = newLink()

            link.connect()
            assertFalse("a link that connected first time never retried", link.retrying.value)

            transport.dropConnection()
            runCurrent()
            assertTrue("a drop with a retry booked is 'still trying'", link.retrying.value)

            advancePast(INITIAL)

            assertEquals(LinkState.Ready, link.state.value)
            assertFalse("a recovered link is not retrying", link.retrying.value)
        }

    // ---- round-2 review: m4, an adapter that comes back is not a terminal verdict ------------

    @Test
    fun `an adapter that comes back within the budget recovers with no user action`() =
        runTest {
            // BluetoothOff used to be terminal, which read as "only a human can fix it". The
            // dominant trigger is not a human: a Bluetooth stack restart reports the adapter off
            // for a few seconds, and the first backoff is one second — so the module sampled
            // almost exactly that window, disarmed permanently, and never came back.
            environment.bluetoothEnabled = false
            val link = newLink(store = FakeRememberedDeviceStore(DEVICE))

            link.connect()
            assertEquals(LinkState.Error(LinkError.BluetoothOff), link.state.value)
            assertTrue("a stack restart must not disarm the machine", link.retrying.value)

            environment.bluetoothEnabled = true
            advancePast(INITIAL)

            assertEquals(LinkState.Ready, link.state.value)
            assertEquals("and it must not have touched the radio while the adapter was off", 0, scanner.calls)
        }

    // ---- jitter, once, through the real link ------------------------------------------------

    @Test
    fun `with jitter on, the retry lands inside the band and not before it`() =
        runTest {
            val jittered = BleConfig(reconnectInitialDelay = 1.seconds, reconnectJitter = 0.25)
            val link = newLink(config = jittered)
            link.connect()
            val clientsAtDrop = clientCount()

            transport.dropConnection()
            runCurrent()
            advanceTimeBy(JITTER_FLOOR - 1.milliseconds)
            runCurrent()
            assertEquals("nothing may fire before the band opens", clientsAtDrop, clientCount())

            advancePast(JITTER_CEILING)

            assertEquals(LinkState.Ready, link.state.value)
        }

    // ---- helpers ----------------------------------------------------------------------------

    private fun clientCount(): Int = factoryInUse?.requested?.size ?: 0

    /** How many retries the link has booked so far, read off its own narrative. */
    private fun attemptsBooked(): Int = logger.containing(BOOKED).size

    /** Records the virtual instant of every sweep, so a backoff schedule is directly assertable. */
    private fun TestScope.timestampingScanner(
        into: MutableList<Long>,
        outcome: () -> ScanOutcome,
    ): BleScanner =
        object : BleScanner {
            override suspend fun scan(
                passes: List<ScanPass>,
                preferredAddress: String?,
            ): ScanOutcome {
                into += currentTime
                return outcome()
            }
        }

    /** Advances past [wait] (the scheduler fires tasks strictly before the target) and settles. */
    private fun TestScope.advancePast(wait: Duration) {
        advanceTimeBy(wait + 1.milliseconds)
        runCurrent()
    }

    private fun TestScope.newLink(
        store: RememberedDeviceStore = this@ReconnectTest.store,
        transports: FakeGattTransportFactory = FakeGattTransportFactory(transport),
        scanner: BleScanner = this@ReconnectTest.scanner,
        config: BleConfig = unjittered,
        logger: BleLogger = this@ReconnectTest.logger,
    ): BleObdLink {
        factoryInUse = transports
        return bleObdLink(environment, scanner, transports, store, logger, config)
    }

    /** Always throws, the way an OEM stack does when the adapter dies under a sweep. */
    private fun throwingScanner(): BleScanner =
        object : BleScanner {
            override suspend fun scan(
                passes: List<ScanPass>,
                preferredAddress: String?,
            ): ScanOutcome = throw IllegalStateException(ADAPTER_DIED)
        }

    /**
     * A store whose first read genuinely suspends — which is the window round-1 M1 and m2 both
     * live in. `runConnectAttempt` takes its generation, then suspends here reading the remembered
     * address, and whatever the user does during that gap has to win.
     */
    private class SlowFirstReadStore(
        private var address: String?,
    ) : RememberedDeviceStore {
        val remembered = mutableListOf<String>()
        var forgetCount = 0
        private var slowReadsLeft = 1

        override suspend fun lastAddress(): String? {
            if (slowReadsLeft-- > 0) {
                delay(SLOW_READ)
            }
            return address
        }

        override suspend fun remember(address: String) {
            this.address = address
            remembered += address
        }

        override suspend fun forget() {
            address = null
            forgetCount++
        }
    }

    private companion object {
        val INITIAL = 1.seconds
        val CEILING = 60.seconds
        val JITTER_FLOOR = 750.milliseconds
        val JITTER_CEILING = 1250.milliseconds
        const val BUDGET = 3
        const val STALE = "11:22:33:44:55:66"
        const val BOOKED = "reconnect scheduled"
        const val ADAPTER_DIED = "adapter died under the sweep"
        val SLOW_SWEEP = 5.seconds
        val SLOW_READ = 3.seconds
        val ERROR_AFTER_DROP = LinkState.Error(LinkError.Gatt(FakeGattTransport.GATT_ERROR))
    }
}
