package com.revel.obdgauge.ble

import com.revel.obdgauge.model.LinkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every reason a connect can be a non-starter, and the one reason it can skip the scan. */
class ConnectPlannerTest {
    @Test
    fun `a device without bluetooth le aborts with a typed unknown error`() {
        val plan = ConnectPlanner.plan(preconditions(bleSupported = false))

        val abort = plan as ConnectPlan.Abort
        assertTrue(abort.error is LinkError.Unknown)
    }

    @Test
    fun `missing permissions abort with PermissionDenied`() {
        val plan = ConnectPlanner.plan(preconditions(missing = listOf("android.permission.BLUETOOTH_SCAN")))

        assertEquals(ConnectPlan.Abort(LinkError.PermissionDenied), plan)
    }

    @Test
    fun `permissions are checked before the adapter so the report stays actionable`() {
        val plan =
            ConnectPlanner.plan(
                preconditions(
                    bluetoothEnabled = false,
                    missing = listOf("android.permission.BLUETOOTH_CONNECT"),
                ),
            )

        assertEquals(ConnectPlan.Abort(LinkError.PermissionDenied), plan)
    }

    @Test
    fun `a disabled adapter aborts with BluetoothOff`() {
        val plan = ConnectPlanner.plan(preconditions(bluetoothEnabled = false))

        assertEquals(ConnectPlan.Abort(LinkError.BluetoothOff), plan)
    }

    @Test
    fun `a remembered address takes the direct path`() {
        val plan = ConnectPlanner.plan(preconditions(remembered = "AA:BB:CC:DD:EE:FF"))

        assertEquals(ConnectPlan.Direct("AA:BB:CC:DD:EE:FF"), plan)
    }

    @Test
    fun `with nothing remembered the plan is to scan`() {
        val plan = ConnectPlanner.plan(preconditions())

        assertEquals(ConnectPlan.Scan, plan)
    }

    @Test
    fun `a remembered address does not rescue a device with bluetooth off`() {
        val plan = ConnectPlanner.plan(preconditions(bluetoothEnabled = false, remembered = "AA:BB:CC:DD:EE:FF"))

        assertEquals(ConnectPlan.Abort(LinkError.BluetoothOff), plan)
    }

    @Test
    fun `below android 12, location services off blocks a scan with a distinct message`() {
        val plan = ConnectPlanner.plan(preconditions(locationUsable = false))

        val abort = plan as ConnectPlan.Abort
        assertEquals(LinkError.Unknown(ConnectPlanner.LOCATION_OFF), abort.error)
    }

    @Test
    fun `location services do not block the remembered-device fast path, which never scans`() {
        val plan = ConnectPlanner.plan(preconditions(locationUsable = false, remembered = "AA:BB:CC:DD:EE:FF"))

        assertEquals(ConnectPlan.Direct("AA:BB:CC:DD:EE:FF"), plan)
    }

    @Test
    fun `permissions still outrank location services`() {
        val plan =
            ConnectPlanner.plan(
                preconditions(locationUsable = false, missing = listOf("android.permission.BLUETOOTH_SCAN")),
            )

        assertEquals(ConnectPlan.Abort(LinkError.PermissionDenied), plan)
    }

    private fun preconditions(
        bleSupported: Boolean = true,
        bluetoothEnabled: Boolean = true,
        locationUsable: Boolean = true,
        missing: List<String> = emptyList(),
        remembered: String? = null,
    ) = ConnectPreconditions(bleSupported, bluetoothEnabled, locationUsable, missing, remembered)
}
