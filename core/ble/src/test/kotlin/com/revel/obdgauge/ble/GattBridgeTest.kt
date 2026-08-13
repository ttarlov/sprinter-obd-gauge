package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.gatt.GattCharacteristicInfo
import com.revel.obdgauge.ble.gatt.GattProperty
import com.revel.obdgauge.ble.gatt.GattServiceInfo
import com.revel.obdgauge.ble.gatt.GattTransport
import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.gatt.GattWriteType
import com.revel.obdgauge.ble.gatt.ProfileSource
import com.revel.obdgauge.ble.gatt.shortUuid
import com.revel.obdgauge.ble.scan.DiscoveredDevice
import com.revel.obdgauge.ble.scan.ScanOutcome
import com.revel.obdgauge.ble.testing.DEVICE
import com.revel.obdgauge.ble.testing.FakeBleEnvironment
import com.revel.obdgauge.ble.testing.FakeBleScanner
import com.revel.obdgauge.ble.testing.FakeGattTransport
import com.revel.obdgauge.ble.testing.FakeGattTransportFactory
import com.revel.obdgauge.ble.testing.FakeRememberedDeviceStore
import com.revel.obdgauge.ble.testing.RecordingLogger
import com.revel.obdgauge.ble.testing.bleObdLink
import com.revel.obdgauge.ble.testing.linkFailure
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-18: the GATT serial bridge — handshake, UUID probe, single-flight, timeouts, the
 * response/ack debt that keeps a timed-out command from desyncing the link, and drops.
 */
class GattBridgeTest {
    private val environment = FakeBleEnvironment()
    private val scanner = FakeBleScanner(ScanOutcome.Found(DiscoveredDevice(DEVICE, "VEEPEAK-OBD")))
    private val store = FakeRememberedDeviceStore()
    private val logger = RecordingLogger()
    private val transport = FakeGattTransport()

    // ---- OBD-18: connect handshake ---------------------------------------------------------

    @Test
    fun `the handshake probes, enables notifications and negotiates the mtu before Ready`() =
        runTest {
            val link = newLink()

            link.connect()

            val profile = transport.profileUsed
            assertEquals(shortUuid("FFF0"), profile?.serviceUuid)
            assertEquals(shortUuid("FFF1"), profile?.notifyUuid)
            assertEquals(shortUuid("FFF2"), profile?.writeUuid)
            assertEquals(GattWriteType.NO_RESPONSE, profile?.writeType)
            assertTrue(logger.containing("serial profile chosen").isNotEmpty())
            assertTrue(logger.containing("MTU negotiated: 512").isNotEmpty())
        }

    @Test
    fun `an unknown dongle connects through the fallback profile and says so in the log`() =
        runTest {
            transport.serviceTree =
                listOf(
                    GattServiceInfo(
                        shortUuid("A0F0"),
                        listOf(
                            GattCharacteristicInfo(shortUuid("A0F1"), GattProperty.NOTIFY),
                            GattCharacteristicInfo(shortUuid("A0F2"), GattProperty.WRITE),
                        ),
                    ),
                )
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
            assertEquals(ProfileSource.Fallback, transport.profileUsed?.source)
            assertEquals(GattWriteType.DEFAULT, transport.profileUsed?.writeType)
            assertTrue(logger.containing("via=Fallback").isNotEmpty())
        }

    @Test
    fun `a dongle with nothing serial-shaped fails with a typed unknown error`() =
        runTest {
            transport.serviceTree =
                listOf(
                    GattServiceInfo(
                        shortUuid("1800"),
                        listOf(GattCharacteristicInfo(shortUuid("2A00"), GattProperty.READ)),
                    ),
                )
            val link = newLink()

            link.connect()

            assertTrue((link.state.value as LinkState.Error).cause is LinkError.Unknown)
        }

    @Test
    fun `a refused mtu request still reaches Ready`() =
        runTest {
            transport.mtuGranted = 23
            transport.mtuStatus = MTU_FAILURE_STATUS
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
            assertTrue(logger.containing("MTU request not honoured").isNotEmpty())
        }

    @Test
    fun `a stack that never answers the mtu request still reaches Ready`() =
        runTest {
            transport.mtuGranted = null
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a failed CCCD write aborts the connect with the gatt status`() =
        runTest {
            transport.notificationsStatus = CCCD_FAILURE_STATUS
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.Gatt(CCCD_FAILURE_STATUS)), link.state.value)
        }

    @Test
    fun `a silent stack times out the connect rather than hanging forever`() =
        runTest {
            transport.connectStatus = null
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.Timeout), link.state.value)
        }

    @Test
    fun `service discovery that never reports back times out`() =
        runTest {
            transport.discoverStatus = null
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.Timeout), link.state.value)
        }

    // ---- OBD-18: sendRaw -------------------------------------------------------------------

    @Test
    fun `sendRaw writes the command with a carriage return and returns the assembled response`() =
        runTest {
            transport.responder = { listOf("41 05 ", "5A\r\r>") }
            val link = newLink()
            link.connect()

            val response = link.sendRaw("0105", COMMAND_TIMEOUT)

            assertEquals("41 05 5A", response)
            assertEquals(listOf("0105\r"), transport.writtenChunks)
        }

    @Test
    fun `a long command is split across writes that fit the negotiated mtu`() =
        runTest {
            transport.mtuGranted = 23
            val link = newLink()
            link.connect()
            val command = "A".repeat(25)

            link.sendRaw(command, COMMAND_TIMEOUT)

            assertEquals(listOf(20, 6), transport.writes.map { it.size })
            assertEquals(command + "\r", transport.writtenChunks.joinToString(""))
        }

    @Test
    fun `a silent dongle fails the command with Timeout and leaves the link usable`() =
        runTest {
            transport.responder = { emptyList() }
            val link = newLink()
            link.connect()

            val failure = linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }

            assertEquals(LinkError.Timeout, failure.error)
            assertEquals("a quiet ECU is not a dead link", LinkState.Ready, link.state.value)
        }

    @Test
    fun `a command sent before connecting fails typed instead of crashing`() =
        runTest {
            val link = newLink()

            val failure = linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }

            assertTrue(failure.error is LinkError.Unknown)
        }

    @Test
    fun `concurrent callers are serialised - the second command is not written until the first answers`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            val first = async { link.sendRaw("0105", COMMAND_TIMEOUT) }
            runCurrent()
            val second = async { link.sendRaw("010B", COMMAND_TIMEOUT) }
            runCurrent()

            assertEquals("only the first command may be on the wire", listOf("0105"), transport.commands)

            transport.emitResponse("41 05 5A\r\r>")
            runCurrent()
            assertEquals(listOf("0105", "010B"), transport.commands)

            transport.emitResponse("41 0B 64\r\r>")
            runCurrent()

            assertEquals("41 05 5A", first.await())
            assertEquals("41 0B 64", second.await())
            assertEquals(listOf("0105\r", "010B\r"), transport.writtenChunks)
        }

    @Test
    fun `a cancelled command releases the link for the next one`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            val abandoned = launch { link.sendRaw("0105", COMMAND_TIMEOUT) }
            runCurrent()
            abandoned.cancel()
            runCurrent()

            // The mutex is free the instant the cancelled caller unwinds: the next command is
            // admitted immediately, and its write goes out as soon as the abandoned command's
            // owed answer settles (or the quiet window lapses — see settleDebts).
            val next = async { link.sendRaw("010B", COMMAND_TIMEOUT) }
            runCurrent()
            transport.emitResponse("41 05 5A\r\r>") // the abandoned command's late answer
            runCurrent()
            assertEquals(listOf("0105", "010B"), transport.commands)

            transport.emitResponse("41 0B 64\r\r>")
            runCurrent()

            assertEquals("41 0B 64", next.await())
            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a response that arrives after its command was cancelled is discarded, not misattributed`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            val abandoned = launch { link.sendRaw("0105", COMMAND_TIMEOUT) }
            runCurrent()
            abandoned.cancel()
            runCurrent()
            transport.emitResponse("41 05 5A\r\r>")
            runCurrent()

            val next = async { link.sendRaw("010B", COMMAND_TIMEOUT) }
            runCurrent()
            transport.emitResponse("41 0B 64\r\r>")
            runCurrent()

            assertEquals("41 0B 64", next.await())
            assertTrue(logger.containing("paid response debt").isNotEmpty())
        }

    @Test
    fun `a genuinely unsolicited response with no debt owed is discarded`() =
        runTest {
            val link = newLink()
            link.connect()

            transport.emitResponse("41 05 5A\r\r>")
            runCurrent()

            assertTrue(logger.containing("discarded unsolicited response").isNotEmpty())
            assertEquals(LinkState.Ready, link.state.value)
        }

    // ---- OBD-18: drops ---------------------------------------------------------------------

    @Test
    fun `a drop mid-response fails the pending command with the gatt status`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            // runCatching inside the coroutine: an `async` that fails would cancel the whole
            // test scope before the assertion ever ran.
            val pending = async { runCatching { link.sendRaw("0105", COMMAND_TIMEOUT) } }
            runCurrent()
            transport.dropConnection()
            runCurrent()

            val failure = pending.await().exceptionOrNull()
            assertTrue("expected a BleLinkException but got $failure", failure is BleLinkException)
            assertEquals(LinkError.Gatt(FakeGattTransport.GATT_ERROR), (failure as BleLinkException).error)
        }

    @Test
    fun `a peer drop after Ready surfaces the typed error before any retry runs`() =
        runTest {
            // OBD-23 made the drop recoverable, but the *observable* moment is unchanged and
            // still belongs here: the drop is reported with the stack's own status, immediately,
            // and no retry has run yet — `LinkState` has no Reconnecting case, so the wait that
            // follows is spent in exactly this state. When and how it retries is ReconnectTest's.
            val link = newLink()
            link.connect()
            val scansAtReady = scanner.calls

            transport.dropConnection()
            runCurrent()

            assertEquals(LinkState.Error(LinkError.Gatt(FakeGattTransport.GATT_ERROR)), link.state.value)
            assertEquals("the backoff has not elapsed; nothing may have been tried yet", scansAtReady, scanner.calls)
        }

    @Test
    fun `after a drop the next command fails typed rather than writing into a dead link`() =
        runTest {
            val link = newLink()
            link.connect()
            transport.dropConnection()
            runCurrent()
            val writesBefore = transport.writes.size

            linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }

            assertEquals(writesBefore, transport.writes.size)
        }

    @Test
    fun `a clean disconnect closes the transport and returns to Disconnected`() =
        runTest {
            val link = newLink()
            link.connect()

            link.disconnect()

            assertEquals(LinkState.Disconnected, link.state.value)
            assertEquals(1, transport.closeCount)
        }

    @Test
    fun `reconnecting after a drop works and does not leak the old session`() =
        runTest {
            val link = newLink()
            link.connect()
            transport.dropConnection()
            runCurrent()

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
            assertEquals("the dropped session must be released before reconnecting", 1, transport.closeCount)
        }

    // ---- round-1 review: B1, timeout must not desync the link ------------------------------

    @Test
    fun `a late response to a timed-out command is never handed to the next command`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            // 0105 is asked for and never answered in time — a SEARCHING... that runs long is
            // ordinary van behaviour, and the answer still turns up afterwards.
            val timedOut = async { runCatching { link.sendRaw("0105", COMMAND_TIMEOUT) } }
            runCurrent()
            advanceTimeBy(COMMAND_TIMEOUT + 1.seconds)
            assertTrue(timedOut.await().exceptionOrNull() is BleLinkException)

            val next = async { link.sendRaw("010B", COMMAND_TIMEOUT) }
            runCurrent()
            transport.emitResponse("41 05 5A\r\r>") // the dead command's answer, arriving late
            runCurrent()
            transport.emitResponse("41 0B 64\r\r>") // 010B's own answer
            runCurrent()

            assertEquals("010B must get 010B's answer, not the previous command's", "41 0B 64", next.await())
            assertTrue(logger.containing("paid response debt").isNotEmpty())
        }

    @Test
    fun `the link keeps its footing across several abandoned commands`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            repeat(2) {
                val abandoned = async { runCatching { link.sendRaw("0105", COMMAND_TIMEOUT) } }
                runCurrent()
                advanceTimeBy(COMMAND_TIMEOUT + 1.seconds)
                abandoned.await()
            }

            val next = async { link.sendRaw("010C", COMMAND_TIMEOUT) }
            runCurrent()
            transport.emitResponse("41 05 5A\r\r>", "41 05 5A\r\r>") // both owed answers settle
            runCurrent()
            transport.emitResponse("41 0C 1F 40\r\r>") // then 010C's own
            runCurrent()

            assertEquals("41 0C 1F 40", next.await())
        }

    @Test
    fun `a cancelled command also books its debt so the next answer is not stolen`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            val abandoned = launch { link.sendRaw("0105", COMMAND_TIMEOUT) }
            runCurrent()
            abandoned.cancel()
            runCurrent()

            val next = async { link.sendRaw("010B", COMMAND_TIMEOUT) }
            runCurrent()
            transport.emitResponse("41 05 5A\r\r>") // settles the debt; the write follows
            runCurrent()
            transport.emitResponse("41 0B 64\r\r>") // 010B's own answer
            runCurrent()

            assertEquals("41 0B 64", next.await())
        }

    // ---- round-2 review: R2-1, debts expire; R2-2, no debt without a wire ---------------------

    @Test
    fun `a response that never arrives costs one quiet window, not the link`() =
        runTest {
            transport.responder = null
            val link = newLink()
            link.connect()

            // The dongle accepts "0105" and then never answers — reset, truncated frame,
            // whatever. The debt this books must not eat every future response.
            val dead = async { runCatching { link.sendRaw("0105", COMMAND_TIMEOUT) } }
            runCurrent()
            advanceTimeBy(COMMAND_TIMEOUT + 1.seconds)
            assertTrue(dead.await().exceptionOrNull() is BleLinkException)

            val next = async { link.sendRaw("010B", COMMAND_TIMEOUT) }
            runCurrent()
            advanceTimeBy(BleConfig().debtQuietWindow) // the quiet window elapses, nothing arrives
            runCurrent()
            transport.emitResponse("41 0B 64\r\r>") // 010B's own answer, on a healthy dongle
            runCurrent()

            assertEquals("the settled debt must not eat the good answer", "41 0B 64", next.await())
            assertTrue(logger.containing("presumed lost").isNotEmpty())
        }

    @Test
    fun `a refused write books no debt and the next command pays no quiet window`() =
        runTest {
            val link = newLink()
            link.connect()

            // The stack refuses the write outright: the command never reached the wire, so
            // nothing is owed (mutation guard for the commandOnTheWire invariant).
            transport.writeAccepted = false
            val refused = linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }
            assertTrue(refused.error is LinkError.Unknown)

            transport.writeAccepted = true
            val before = currentTime
            val answer = link.sendRaw("010B", COMMAND_TIMEOUT)
            assertEquals("OK", answer)
            assertEquals(
                "no debt was owed, so the next command must not wait a quiet window",
                before,
                currentTime,
            )
        }

    // ---- round-1 review: B2, the session is reachable from the moment it exists -------------

    @Test
    fun `a connect cancelled mid-handshake still hands the GATT client back`() =
        runTest {
            transport.connectStatus = null // a stack that simply never calls back
            val link = newLink()

            val attempt = launch { link.connect() }
            runCurrent()
            attempt.cancel()
            runCurrent()

            assertEquals("a leaked client is permanent; ~32 of them kill BLE app-wide", 1, transport.closeCount)
        }

    @Test
    fun `disconnect during a handshake wins and is not overwritten when the handshake finishes`() =
        runTest {
            transport.connectStatus = null
            val link = newLink()

            val attempt = launch { link.connect() }
            runCurrent()
            link.disconnect()
            advanceUntilIdle()
            attempt.join()

            assertEquals(LinkState.Disconnected, link.state.value)
            assertEquals(1, transport.closeCount)
        }

    @Test
    fun `an overlapping connect does not orphan the first client`() =
        runTest {
            val first = FakeGattTransport(connectStatus = null)
            val second = FakeGattTransport()
            val queue = ArrayDeque(listOf<GattTransport>(first, second))
            val link = newLink(transports = FakeGattTransportFactory { queue.removeFirst() })

            val abandoned = launch { link.connect() }
            runCurrent()
            link.connect()
            advanceUntilIdle()
            abandoned.join()

            assertEquals(1, first.closeCount)
            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a command issued mid-handshake is refused rather than written into a half-open link`() =
        runTest {
            transport.connectStatus = null
            val link = newLink()
            val attempt = launch { link.connect() }
            runCurrent()

            val failure = linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }

            assertTrue(failure.error is LinkError.Unknown)
            assertEquals(0, transport.writes.size)
            attempt.cancel()
        }

    // ---- round-1 review: M3, write flow control ---------------------------------------------

    @Test
    fun `an unacked chunk stops the command instead of blasting the next one`() =
        runTest {
            transport.mtuGranted = 23
            val link = newLink()
            link.connect()
            transport.writeStatusSequence = mutableListOf(null) // first chunk is never acked

            val failure = linkFailure { link.sendRaw("A".repeat(25), COMMAND_TIMEOUT) }

            assertEquals(LinkError.Timeout, failure.error)
            assertEquals("overlapping writes are rejected outright on API 33+", 1, transport.writes.size)
            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a write ack carrying a failure status fails the command typed`() =
        runTest {
            val link = newLink()
            link.connect()
            transport.writeStatus = WRITE_FAILURE_STATUS

            val failure = linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }

            assertEquals(LinkError.Gatt(WRITE_FAILURE_STATUS), failure.error)
            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a late write ack is not mistaken for the next command's`() =
        runTest {
            val link = newLink()
            link.connect()
            transport.writeStatusSequence = mutableListOf(null)

            linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }
            transport.emitWriteAck() // the abandoned chunk's ack, arriving late
            runCurrent()
            val response = link.sendRaw("010B", COMMAND_TIMEOUT)

            assertEquals("OK", response)
        }

    // ---- round-1 review: M6/M7/M8, doubles that model reality --------------------------------

    @Test
    fun `a request the BLE stack refuses outright fails with a typed error, per step`() =
        runTest {
            val refusals =
                listOf<Pair<String, (FakeGattTransport) -> Unit>>(
                    "connectGatt" to { it.openAccepted = false },
                    "discoverServices" to { it.discoverAccepted = false },
                    "CCCD write" to { it.notificationsAccepted = false },
                )

            for ((name, refuse) in refusals) {
                val refused = FakeGattTransport().also(refuse)
                val link = newLink(store = FakeRememberedDeviceStore(), transports = FakeGattTransportFactory(refused))

                link.connect()

                val state = link.state.value
                assertTrue("$name refusal should be a typed error, was $state", state is LinkState.Error)
                assertTrue("$name refusal should report Unknown", (state as LinkState.Error).cause is LinkError.Unknown)
                assertEquals("$name refusal must still release the client", 1, refused.closeCount)
            }
        }

    @Test
    fun `an mtu request the stack refuses outright is tolerated like any other mtu failure`() =
        runTest {
            transport.mtuAccepted = false
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a write the stack refuses outright fails the command typed`() =
        runTest {
            val link = newLink()
            link.connect()
            transport.writeAccepted = false

            val failure = linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }

            assertTrue(failure.error is LinkError.Unknown)
            assertEquals(LinkState.Ready, link.state.value)
        }

    @Test
    fun `a drop during service discovery aborts the connect and releases the client`() =
        runTest {
            transport.dropDuringDiscovery = DISCOVERY_DROP_STATUS
            val link = newLink()

            link.connect()

            assertEquals(LinkState.Error(LinkError.Gatt(DISCOVERY_DROP_STATUS)), link.state.value)
            assertEquals(1, transport.closeCount)
        }

    @Test
    fun `a response that beats its own write ack is still delivered`() =
        runTest {
            transport.notifyBeforeAck = true
            transport.responder = { listOf("41 05 5A\r\r>") }
            val link = newLink()
            link.connect()

            assertEquals("41 05 5A", link.sendRaw("0105", COMMAND_TIMEOUT))
        }

    // ---- helpers ---------------------------------------------------------------------------

    private fun TestScope.newLink(
        store: FakeRememberedDeviceStore = this@GattBridgeTest.store,
        transports: GattTransportFactory = FakeGattTransportFactory(transport),
        config: BleConfig = BleConfig(),
    ): BleObdLink = bleObdLink(environment, scanner, transports, store, logger, config)

    private companion object {
        const val MTU_FAILURE_STATUS = 133
        const val CCCD_FAILURE_STATUS = 137
        const val WRITE_FAILURE_STATUS = 3
        const val DISCOVERY_DROP_STATUS = 19
        val COMMAND_TIMEOUT = 2.seconds
    }
}
