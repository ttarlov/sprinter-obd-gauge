package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.scan.DiscoveredDevice
import com.revel.obdgauge.ble.scan.ScanOutcome
import com.revel.obdgauge.ble.testing.DEVICE
import com.revel.obdgauge.ble.testing.FakeBleEnvironment
import com.revel.obdgauge.ble.testing.FakeBleScanner
import com.revel.obdgauge.ble.testing.FakeGattTransport
import com.revel.obdgauge.ble.testing.FakeGattTransportFactory
import com.revel.obdgauge.ble.testing.FakeRememberedDeviceStore
import com.revel.obdgauge.ble.testing.RecordingLogger
import com.revel.obdgauge.ble.testing.RecordingTrafficLog
import com.revel.obdgauge.ble.testing.bleObdLink
import com.revel.obdgauge.ble.testing.linkFailure
import com.revel.obdgauge.ble.traffic.RxFate
import com.revel.obdgauge.ble.traffic.TrafficEntry
import com.revel.obdgauge.model.LinkState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-48: the headless capture tap. What matters is that a capture read back after a drive is
 * *complete and in order* — a TX with no RX beside it, or an RX that landed before the state
 * transition that made it possible, is a capture that lies about what happened.
 */
class TrafficTapTest {
    private val environment = FakeBleEnvironment()
    private val scanner = FakeBleScanner(ScanOutcome.Found(DiscoveredDevice(DEVICE, "VEEPEAK-OBD")))
    private val store = FakeRememberedDeviceStore()
    private val logger = RecordingLogger()
    private val transport = FakeGattTransport()
    private val traffic = RecordingTrafficLog()

    @Test
    fun `a connect and one exchange tap as link states then tx then rx, in that order`() =
        runTest {
            transport.responder = { listOf("41 05 86\r\r>") }
            val link = newLink()

            link.connect()
            link.sendRaw("0105", COMMAND_TIMEOUT)

            assertEquals(
                listOf(
                    TrafficEntry.Link(LinkState.Scanning),
                    TrafficEntry.Link(LinkState.Connecting),
                    TrafficEntry.Link(LinkState.Ready),
                    TrafficEntry.Tx("0105"),
                    TrafficEntry.Rx("41 05 86", RxFate.DELIVERED),
                ),
                traffic.entries,
            )
        }

    @Test
    fun `a response the debt bookkeeping discards is still in the capture, marked`() =
        runTest {
            // The pathology worth catching: a timed-out command's answer arriving late and being
            // eaten. A capture that only showed delivered responses would show a gap and no
            // reason for it.
            transport.responder = null
            val link = newLink()
            link.connect()

            val timedOut = async { runCatching { link.sendRaw("0105", COMMAND_TIMEOUT) } }
            runCurrent()
            advanceTimeBy(COMMAND_TIMEOUT + 1.seconds)
            timedOut.await()
            transport.emitResponse("41 05 5A\r\r>")
            runCurrent()

            assertEquals(
                listOf(TrafficEntry.Rx("41 05 5A", RxFate.DISCARDED_AS_DEBT)),
                traffic.entries.filterIsInstance<TrafficEntry.Rx>(),
            )
            assertTrue(
                "the timeout itself has to be visible, or the gap is unexplained",
                traffic.entries.any { it is TrafficEntry.Note && it.text.contains("no response to \"0105\"") },
            )
        }

    @Test
    fun `a drop is tapped as a note and the error transition, in order`() =
        runTest {
            val link = newLink()
            link.connect()
            traffic.entries.clear()

            transport.dropConnection()
            runCurrent()

            val shapes = traffic.entries.map { it::class.simpleName }
            assertEquals("the drop note must precede the state it caused", listOf("Note", "Link"), shapes)
            assertTrue(traffic.entries.first().let { it is TrafficEntry.Note && it.text.contains("dropped") })
        }

    @Test
    fun `the tap never changes what the link does`() =
        runTest {
            // Same script, once with the tap and once with TrafficLog.NONE: identical outcomes,
            // identical wire traffic. This is the "pure tap" acceptance criterion, run rather
            // than asserted in prose.
            transport.responder = { listOf("41 05 86\r\r>") }
            val tapped = newLink()
            tapped.connect()
            val tappedResponse = tapped.sendRaw("0105", COMMAND_TIMEOUT)
            val tappedWrites = transport.writtenChunks.toList()
            tapped.disconnect()

            val plainTransport = FakeGattTransport()
            plainTransport.responder = { listOf("41 05 86\r\r>") }
            val plain =
                bleObdLink(
                    environment,
                    FakeBleScanner(ScanOutcome.Found(DiscoveredDevice(DEVICE, "VEEPEAK-OBD"))),
                    FakeGattTransportFactory(plainTransport),
                    FakeRememberedDeviceStore(),
                    RecordingLogger(),
                )
            plain.connect()
            val plainResponse = plain.sendRaw("0105", COMMAND_TIMEOUT)
            plain.disconnect()

            assertEquals(plainResponse, tappedResponse)
            assertEquals(plainTransport.writtenChunks, tappedWrites)
            assertEquals(plainTransport.closeCount, transport.closeCount)
        }

    @Test
    fun `a refused command is not tapped as traffic that never left`() =
        runTest {
            val link = newLink()

            linkFailure { link.sendRaw("0105", COMMAND_TIMEOUT) }

            assertTrue(
                "nothing was written, so nothing may appear as TX",
                traffic.entries.none { it is TrafficEntry.Tx },
            )
        }

    private fun TestScope.newLink(transports: GattTransportFactory = FakeGattTransportFactory(transport)): BleObdLink =
        bleObdLink(environment, scanner, transports, store, logger, traffic = traffic)

    private companion object {
        val COMMAND_TIMEOUT = 2.seconds
    }
}
