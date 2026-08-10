package com.revel.obdgauge.ble.scan

import com.revel.obdgauge.ble.gatt.shortUuid
import com.revel.obdgauge.model.LinkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner's decisions, with no radio in sight. */
class ScanStateMachineTest {
    @Test
    fun `an advertisement with a known name prefix is selected`() {
        val machine = ScanStateMachine()

        val decision = machine.onAdvertisement(device(name = "VEEPEAK-1234"))

        assertEquals(ScanDecision.Select(device(name = "VEEPEAK-1234")), decision)
    }

    @Test
    fun `an unnamed advertisement carrying a candidate service uuid is selected`() {
        val machine = ScanStateMachine()

        val decision =
            machine.onAdvertisement(device(name = null, serviceUuids = listOf(shortUuid("FFF0"))))

        assertTrue(decision is ScanDecision.Select)
    }

    @Test
    fun `unrelated devices keep the scan going`() {
        val machine = ScanStateMachine()

        val decision = machine.onAdvertisement(device(name = "Bose QC35", address = "11:22:33:44:55:66"))

        assertTrue(decision is ScanDecision.Continue)
    }

    @Test
    fun `the remembered address wins even when the filter would not match it`() {
        val machine = ScanStateMachine(preferredAddress = "11:22:33:44:55:66")

        val decision = machine.onAdvertisement(device(name = "no idea", address = "11:22:33:44:55:66"))

        assertTrue(decision is ScanDecision.Select)
    }

    @Test
    fun `the remembered address matches case-insensitively`() {
        val machine = ScanStateMachine(preferredAddress = "aa:bb:cc:dd:ee:ff")

        val decision = machine.onAdvertisement(device(name = "whatever", address = "AA:BB:CC:DD:EE:FF"))

        assertTrue(decision is ScanDecision.Select)
    }

    @Test
    fun `a sweep that runs out of time reports DeviceNotFound`() {
        val machine = ScanStateMachine()

        assertEquals(ScanDecision.Fail(LinkError.DeviceNotFound), machine.onTimeout())
    }

    @Test
    fun `a scan failure code becomes a typed unknown error naming the code`() {
        val machine = ScanStateMachine()

        val decision = machine.onScanFailed(SCAN_FAILED_INTERNAL_ERROR) as ScanDecision.Fail
        val error = decision.error as LinkError.Unknown

        assertTrue(error.message.contains("internal error"))
    }

    @Test
    fun `the throttle code is reported distinctly so the app can say wait, not no dongle`() {
        val machine = ScanStateMachine()

        val decision = machine.onScanFailed(SCAN_FAILED_SCANNING_TOO_FREQUENTLY) as ScanDecision.Fail

        assertTrue(ScanStateMachine.isThrottled(decision.error))
    }

    @Test
    fun `an unrecognised failure code still produces a typed error`() {
        val machine = ScanStateMachine()

        val decision = machine.onScanFailed(UNKNOWN_FAILURE_CODE) as ScanDecision.Fail

        assertTrue((decision.error as LinkError.Unknown).message.contains("$UNKNOWN_FAILURE_CODE"))
    }

    @Test
    fun `late callbacks after a selection do not produce a second decision`() {
        val machine = ScanStateMachine()
        machine.onAdvertisement(device(name = "OBDII"))

        val late = machine.onAdvertisement(device(name = "VEEPEAK", address = "99:88:77:66:55:44"))
        val lateFailure = machine.onScanFailed(SCAN_FAILED_INTERNAL_ERROR)
        val lateTimeout = machine.onTimeout()

        assertTrue(late is ScanDecision.Continue)
        assertTrue(lateFailure is ScanDecision.Continue)
        assertTrue(lateTimeout is ScanDecision.Continue)
    }

    @Test
    fun `addresses seen during a sweep are recorded for the empty-scan log`() {
        val machine = ScanStateMachine()

        machine.onAdvertisement(device(name = "Bose QC35", address = "11:22:33:44:55:66"))
        machine.onAdvertisement(device(name = "Tile", address = "22:33:44:55:66:77"))

        assertEquals(setOf("11:22:33:44:55:66", "22:33:44:55:66:77"), machine.seenAddresses)
    }

    private fun device(
        name: String?,
        address: String = "AA:BB:CC:DD:EE:FF",
        serviceUuids: List<java.util.UUID> = emptyList(),
    ) = DiscoveredDevice(address = address, name = name, serviceUuids = serviceUuids)

    private companion object {
        const val SCAN_FAILED_INTERNAL_ERROR = 3
        const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
        const val UNKNOWN_FAILURE_CODE = 42
    }
}
