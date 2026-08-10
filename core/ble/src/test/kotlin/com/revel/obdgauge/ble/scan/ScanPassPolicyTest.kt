package com.revel.obdgauge.ble.scan

import com.revel.obdgauge.ble.BleConfig
import com.revel.obdgauge.model.LinkError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-pass scan plan and the throttle budget, both pure so the decisions survive without a
 * radio. Round-1 review: two mutations (collapsing the plan to a single unfiltered pass, and
 * never running the fallback sweep) previously passed the whole suite — these are the tests that
 * kill them.
 */
class ScanPassPolicyTest {
    @Test
    fun `the plan is a filtered sweep followed by an unfiltered one`() {
        val passes = BleConfig().scanPasses()

        assertEquals(2, passes.size)
        assertTrue("pass 1 must use the hardware service-UUID filter", passes[0].filterByServiceUuid)
        assertFalse("pass 2 must sweep unfiltered for name-only dongles", passes[1].filterByServiceUuid)
    }

    @Test
    fun `each pass carries its own configured timeout`() {
        val config = BleConfig()
        val passes = config.scanPasses()

        assertEquals(config.filteredScanTimeout, passes[0].timeout)
        assertEquals(config.broadScanTimeout, passes[1].timeout)
    }

    @Test
    fun `a first pass that finds nothing is followed by the second`() =
        runTest {
            val run = mutableListOf<ScanPass>()

            val outcome =
                runScanPasses(BleConfig().scanPasses()) { pass ->
                    run += pass
                    ScanOutcome.Failed(LinkError.DeviceNotFound)
                }

            assertEquals(listOf(true, false), run.map { it.filterByServiceUuid })
            assertEquals(ScanOutcome.Failed(LinkError.DeviceNotFound), outcome)
        }

    @Test
    fun `a first pass that finds a device stops the plan`() =
        runTest {
            var runs = 0
            val found = DiscoveredDevice("AA:BB:CC:DD:EE:FF", "VEEPEAK")

            val outcome =
                runScanPasses(BleConfig().scanPasses()) {
                    runs++
                    ScanOutcome.Found(found)
                }

            assertEquals(1, runs)
            assertEquals(ScanOutcome.Found(found), outcome)
        }

    @Test
    fun `a first pass that fails for a reason is not retried`() =
        runTest {
            var runs = 0
            val throttled = ScanOutcome.Failed(LinkError.Unknown("${ScanStateMachine.THROTTLED}: retry in 8000ms"))

            val outcome =
                runScanPasses(BleConfig().scanPasses()) {
                    runs++
                    throttled
                }

            assertEquals("retrying a throttled scan burns another of the five starts", 1, runs)
            assertEquals(throttled, outcome)
        }

    @Test
    fun `an empty plan reports DeviceNotFound rather than looping`() =
        runTest {
            assertEquals(ScanOutcome.Failed(LinkError.DeviceNotFound), runScanPasses(emptyList()) { error("unused") })
        }

    @Test
    fun `only nothing-found is worth another pass`() {
        assertTrue(worthAnotherPass(ScanOutcome.Failed(LinkError.DeviceNotFound)))
        assertFalse(worthAnotherPass(ScanOutcome.Failed(LinkError.BluetoothOff)))
        assertFalse(worthAnotherPass(ScanOutcome.Failed(LinkError.PermissionDenied)))
        assertFalse(worthAnotherPass(ScanOutcome.Found(DiscoveredDevice("AA:BB:CC:DD:EE:FF", "OBDII"))))
    }

    @Test
    fun `the budget allows five scan starts per window and then refuses`() {
        val budget = ScanBudget()

        repeat(ScanBudget.MAX_STARTS_PER_WINDOW) { attempt ->
            assertTrue("start ${attempt + 1} should be allowed", budget.tryConsume(attempt.toLong()))
        }

        assertFalse("the sixth start inside the window is what Android throttles", budget.tryConsume(10L))
    }

    @Test
    fun `the budget frees a start once its entry ages out of the window`() {
        val budget = ScanBudget()
        repeat(ScanBudget.MAX_STARTS_PER_WINDOW) { budget.tryConsume(0L) }

        assertFalse(budget.tryConsume(ScanBudget.WINDOW_MILLIS - 1))
        assertTrue(budget.tryConsume(ScanBudget.WINDOW_MILLIS))
    }

    @Test
    fun `the budget reports how long the caller has to wait`() {
        val budget = ScanBudget()
        repeat(ScanBudget.MAX_STARTS_PER_WINDOW) { budget.tryConsume(1_000L) }

        assertEquals(ScanBudget.WINDOW_MILLIS - 4_000L, budget.retryAfterMillis(5_000L))
        assertEquals(0L, budget.retryAfterMillis(ScanBudget.WINDOW_MILLIS + 1_000L))
    }

    @Test
    fun `a throttled scan is recognisable so the app can say wait rather than no dongle`() {
        val throttled = ScanStateMachine.scanFailureError(SCAN_FAILED_SCANNING_TOO_FREQUENTLY)

        assertTrue(ScanStateMachine.isThrottled(throttled))
        assertFalse(ScanStateMachine.isThrottled(ScanStateMachine.scanFailureError(SCAN_FAILED_INTERNAL_ERROR)))
        assertFalse(ScanStateMachine.isThrottled(LinkError.DeviceNotFound))
    }

    private companion object {
        const val SCAN_FAILED_INTERNAL_ERROR = 3
        const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
