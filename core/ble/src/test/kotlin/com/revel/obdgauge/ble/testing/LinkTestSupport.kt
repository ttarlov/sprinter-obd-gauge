package com.revel.obdgauge.ble.testing

import com.revel.obdgauge.ble.BleConfig
import com.revel.obdgauge.ble.BleLinkException
import com.revel.obdgauge.ble.BleLogger
import com.revel.obdgauge.ble.BleObdLink
import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.scan.BleScanner
import com.revel.obdgauge.ble.store.RememberedDeviceStore
import com.revel.obdgauge.ble.traffic.TrafficLog
import com.revel.obdgauge.model.LinkState
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue

/** The address the fakes agree the dongle lives at. */
const val DEVICE = "AA:BB:CC:DD:EE:FF"

/** A second address, used for the remembered-but-gone device cases. */
const val STALE_DEVICE = "11:22:33:44:55:66"

/**
 * Builds a link on the test scheduler. Every timeout in the module then runs on virtual time, so
 * the suite exercises real waits without spending real seconds.
 */
@Suppress("LongParameterList") // Mirrors the production constructor; each one is a seam.
fun TestScope.bleObdLink(
    environment: FakeBleEnvironment,
    /** Widened from `FakeBleScanner` for OBD-23: reconnect tests need a scanner whose answer changes between sweeps. */
    scanner: BleScanner,
    transports: GattTransportFactory,
    /** Widened for OBD-23 round 2: the m2/M1 regressions need a store whose read actually suspends. */
    store: RememberedDeviceStore,
    /** Widened for OBD-23 round 2: the B1 handler-net test needs a logger that throws. */
    logger: BleLogger,
    config: BleConfig = BleConfig(),
    /** OBD-48 capture tap. Defaulted off so pre-OBD-48 tests exercise the untapped link. */
    traffic: TrafficLog = TrafficLog.NONE,
): BleObdLink =
    BleObdLink(
        environment = environment,
        scanner = scanner,
        transports = transports,
        rememberedDevices = store,
        config = config,
        logger = logger,
        dispatcher = StandardTestDispatcher(testScheduler),
        traffic = traffic,
    )

/** Runs [block], asserting it fails with the module's typed transport exception. */
suspend fun linkFailure(block: suspend () -> Unit): BleLinkException {
    val thrown = runCatching { block() }.exceptionOrNull()
    assertTrue("expected a BleLinkException but got $thrown", thrown is BleLinkException)
    return thrown as BleLinkException
}

/** Collects every state the link publishes, unconfined so no transition is conflated away. */
fun TestScope.recordStates(link: BleObdLink): List<LinkState> {
    val states = mutableListOf<LinkState>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        link.state.collect { state -> states += state }
    }
    return states
}
