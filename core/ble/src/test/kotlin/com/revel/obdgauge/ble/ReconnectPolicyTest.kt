package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.scan.ScanStateMachine
import com.revel.obdgauge.model.LinkError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OBD-23, the pure half: which failures are worth retrying, and how long to wait.
 *
 * The classification rule under test is *retry unless a retry is provably futile* — futile
 * meaning the module would be asking the same question of the same unchanged world. Every case
 * below is an application of that rather than a lookup table someone can quietly extend the
 * wrong way.
 *
 * Round 1 stated the rule as "anything only a human can fix is terminal", which sounded tidier
 * and put `BluetoothOff` on the wrong side of it — see the reclassification test below.
 */
class ReconnectPolicyTest {
    private val config = BleConfig(reconnectInitialDelay = 1.seconds, reconnectMaxDelay = 60.seconds)

    // ---- classification --------------------------------------------------------------------

    @Test
    fun `a changed world could answer differently, so these are retried`() {
        val recoverable =
            listOf(
                // Round-1 MINOR m4: the dominant cause of an off adapter is a Bluetooth stack
                // restart, which reports off for seconds — and the first backoff is one second,
                // so terminal here meant one unlucky read disarmed the link permanently.
                LinkError.BluetoothOff,
                // Round-1 BLOCKER B1: a retry that died on an exception is one more failed
                // attempt, not a verdict. The budget bounds how long that stays true.
                LinkError.Unknown("${ReconnectPolicy.ABNORMAL_ATTEMPT}: java.lang.IllegalStateException"),
                // Key-off with a clean status: the shape GattSession reports for a peer that
                // simply went away. The case that motivated the whole issue.
                LinkError.Unknown("dongle disconnected"),
                // Key-off with a stack status, which is what actually happens more often.
                LinkError.Gatt(GATT_ERROR),
                // The van is parked in the next street; nothing answered the sweep.
                LinkError.DeviceNotFound,
                LinkError.Timeout,
                // A spent scan budget recovers by waiting, which is the one thing the backoff
                // is already doing.
                LinkError.Unknown("${ScanStateMachine.THROTTLED}: 5 scans per 30 s exceeded"),
            )

        recoverable.forEach { cause ->
            assertEquals("$cause should be retried", Recoverability.RECOVERABLE, ReconnectPolicy.classify(cause))
        }
    }

    @Test
    fun `nothing about the world can change these, so retrying them never converges`() {
        val terminal =
            listOf(
                // The app may not even look until it is granted, and :app owns that dialog and
                // re-calls connect() on the grant — so the module is not the one waiting.
                LinkError.PermissionDenied,
                LinkError.Unknown(ConnectPlanner.LOCATION_OFF),
                LinkError.Unknown(ConnectPlanner.BLE_UNSUPPORTED),
            )

        terminal.forEach { cause ->
            assertEquals("$cause must not be retried", Recoverability.TERMINAL, ReconnectPolicy.classify(cause))
        }
    }

    @Test
    fun `an off adapter is recoverable, because the usual reason it is off is not a person`() {
        // Pinned on its own, not just as a list entry: this reclassification is round-1 MINOR m4
        // and the reasoning is not self-evident from the enum name.
        assertEquals(Recoverability.RECOVERABLE, ReconnectPolicy.classify(LinkError.BluetoothOff))
    }

    @Test
    fun `the planner's own abort messages are the ones the policy matches on`() {
        // Pinned deliberately: these two aborts ride on LinkError.Unknown because LinkError is a
        // frozen :core:model contract. If ConnectPlanner ever reworded them, classification
        // would silently flip to RECOVERABLE and the app would retry a phone with no BLE radio
        // forever. This test is the tripwire.
        val unsupported = ConnectPlanner.plan(preconditions(bleSupported = false))
        val locationOff = ConnectPlanner.plan(preconditions(remembered = null, locationUsable = false))

        assertEquals(Recoverability.TERMINAL, ReconnectPolicy.classify((unsupported as ConnectPlan.Abort).error))
        assertEquals(Recoverability.TERMINAL, ReconnectPolicy.classify((locationOff as ConnectPlan.Abort).error))
    }

    // ---- backoff ---------------------------------------------------------------------------

    @Test
    fun `without jitter the wait doubles from the initial delay`() {
        val unjittered = config.copy(reconnectJitter = 0.0)

        val schedule = (1..5).map { attempt -> ReconnectPolicy.delayFor(attempt, unjittered) }

        assertEquals(listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds), schedule)
    }

    @Test
    fun `the doubling stops at the ceiling and stays there`() {
        val unjittered = config.copy(reconnectJitter = 0.0)

        assertEquals(32.seconds, ReconnectPolicy.delayFor(SIXTH_ATTEMPT, unjittered))
        assertEquals(60.seconds, ReconnectPolicy.delayFor(SEVENTH_ATTEMPT, unjittered))
        // A van parked for a week must not overflow the shift into a negative wait.
        assertEquals(60.seconds, ReconnectPolicy.delayFor(Int.MAX_VALUE, unjittered))
    }

    @Test
    fun `jitter stays inside its band and never collapses to an immediate retry`() {
        val jittered = config.copy(reconnectJitter = 0.25)
        val random = Random(SEED)

        val waits = (1..SAMPLES).map { ReconnectPolicy.delayFor(attempt = 1, jittered, random) }

        assertTrue(waits.toString(), waits.all { it in 750.milliseconds..1250.milliseconds })
        assertTrue("a spread of one value is not jitter", waits.distinct().size > 1)
    }

    @Test
    fun `jitter never pushes a wait past the ceiling`() {
        val jittered = config.copy(reconnectJitter = 0.5)
        val random = Random(SEED)

        val waits = (1..SAMPLES).map { ReconnectPolicy.delayFor(attempt = TENTH_ATTEMPT, jittered, random) }

        assertTrue(waits.maxOrNull().toString(), waits.all { it <= 60.seconds })
        assertTrue(waits.minOrNull().toString(), waits.all { it >= 30.seconds })
    }

    @Test
    fun `a zero jitter fraction is exact, not almost exact`() {
        // The soak and transition tests set jitter to zero so they can assert on wall-clock
        // arithmetic. If zero were merely "very little jitter", those tests would flake.
        val exact = config.copy(reconnectJitter = 0.0)

        repeat(SAMPLES) {
            assertEquals(4.seconds, ReconnectPolicy.delayFor(attempt = 3, exact))
        }
    }

    @Test
    fun `attempt numbering is one-based and says so`() {
        val thrown = runCatching { ReconnectPolicy.delayFor(attempt = 0, config) }.exceptionOrNull()

        assertTrue("$thrown", thrown is IllegalArgumentException)
    }

    private fun preconditions(
        bleSupported: Boolean = true,
        remembered: String? = null,
        locationUsable: Boolean = true,
    ) = ConnectPreconditions(
        bleSupported = bleSupported,
        bluetoothEnabled = true,
        locationUsableForScan = locationUsable,
        missingPermissions = emptyList(),
        rememberedAddress = remembered,
    )

    private companion object {
        const val GATT_ERROR = 8
        const val SEED = 20260812
        const val SAMPLES = 200
        const val SIXTH_ATTEMPT = 6
        const val SEVENTH_ATTEMPT = 7
        const val TENTH_ATTEMPT = 10
    }
}
