package com.revel.obdgauge.app.service

import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Plain-JVM tests for OBD-71's engine-off + user-presence decision — no Android, no Robolectric,
 * a `Long` standing in for the clock (`SystemClock.elapsedRealtime` in production), matching
 * `IdleWatchdogTest`'s own discipline. The service wiring (that [EngineOffAction.Stop] reaches
 * `stopSelf`/releases the wake lock) is pinned separately in `ObdConnectionServiceTest`; this file
 * owns the timing and the presence/recording branching.
 */
class EngineOffWatchdogTest {
    // ---- rpmSignal: classifying one readings emission ----

    @Test
    fun `rpmSignal is RUNNING for a fresh RPM greater than zero`() {
        val readings = mapOf(PidIds.RPM to freshRpm(2400.0))

        assertEquals(RpmSignal.RUNNING, rpmSignal(readings))
    }

    @Test
    fun `rpmSignal is OFF for a fresh RPM exactly zero`() {
        val readings = mapOf(PidIds.RPM to freshRpm(0.0))

        assertEquals(RpmSignal.OFF, rpmSignal(readings))
    }

    @Test
    fun `rpmSignal is AMBIGUOUS when RPM is absent`() {
        val readings = mapOf("coolant" to Reading("coolant", 190.0, Instant.EPOCH, stale = false))

        assertEquals(RpmSignal.AMBIGUOUS, rpmSignal(readings))
    }

    @Test
    fun `rpmSignal is AMBIGUOUS when RPM is present but stale, even at zero`() {
        val readings = mapOf(PidIds.RPM to Reading(PidIds.RPM, 0.0, Instant.EPOCH, stale = true))

        assertEquals(RpmSignal.AMBIGUOUS, rpmSignal(readings))
    }

    // ---- EngineOffPromptController.evaluate: the debounce ----

    @Test
    fun `a single OFF reading does not confirm engine-off before the debounce elapses`() {
        val controller = testController()

        val action = controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)

        assertEquals(EngineOffAction.None, action)
    }

    @Test
    fun `sustained OFF for the debounce window confirms engine-off and shows the prompt`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)

        val action = controller.evaluate(START + DEBOUNCE, RpmSignal.OFF, userPresent = true, recording = false)

        assertTrue(action is EngineOffAction.ShowPrompt)
    }

    @Test
    fun `a stall-then-restart blip inside the debounce window never triggers anything`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)
        // Restarts before the debounce elapses.
        controller.evaluate(START + DEBOUNCE / 2, RpmSignal.RUNNING, userPresent = true, recording = false)

        val action = controller.evaluate(START + DEBOUNCE, RpmSignal.OFF, userPresent = true, recording = false)

        // The clock restarted at DEBOUNCE/2 on the RUNNING reset, so DEBOUNCE alone isn't enough yet.
        assertEquals(EngineOffAction.None, action)
    }

    @Test
    fun `RPM absent (ambiguous) never starts or advances the debounce on its own`() {
        val controller = testController()

        val action = controller.evaluate(START, RpmSignal.AMBIGUOUS, userPresent = true, recording = false)

        assertEquals(EngineOffAction.None, action)
    }

    @Test
    fun `RPM absent mid-debounce does not reset an in-progress OFF clock`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)
        // A BLE dropout loses the RPM poll for a beat — must not reset the clock already ticking.
        controller.evaluate(START + 1, RpmSignal.AMBIGUOUS, userPresent = true, recording = false)

        val action = controller.evaluate(START + DEBOUNCE, RpmSignal.AMBIGUOUS, userPresent = true, recording = false)

        assertTrue(action is EngineOffAction.ShowPrompt)
    }

    // ---- present branch: the 20 s prompt ----

    @Test
    fun `the prompt deadline is debounce plus the prompt timeout from the first OFF reading`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)

        val action = controller.evaluate(START + DEBOUNCE, RpmSignal.OFF, userPresent = true, recording = false)

        val expectedDeadline = START + DEBOUNCE + PROMPT_TIMEOUT
        assertEquals(EngineOffAction.ShowPrompt(expectedDeadline), action)
    }

    @Test
    fun `present and past the prompt deadline without a tap stops`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)

        val action =
            controller.evaluate(
                START + DEBOUNCE + PROMPT_TIMEOUT,
                RpmSignal.OFF,
                userPresent = true,
                recording = false,
            )

        assertEquals(EngineOffAction.Stop, action)
    }

    @Test
    fun `Keep monitoring tapped suppresses the prompt and the stop while present`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)
        controller.evaluate(START + DEBOUNCE, RpmSignal.OFF, userPresent = true, recording = false)

        controller.keepMonitoringTapped()

        val action =
            controller.evaluate(
                START + DEBOUNCE + PROMPT_TIMEOUT + DEBOUNCE,
                RpmSignal.OFF,
                userPresent = true,
                recording = false,
            )

        assertEquals(EngineOffAction.None, action)
    }

    // ---- absent branch: no prompt, silent grace, then stop ----

    @Test
    fun `absent from the moment engine-off is confirmed shows no prompt`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = false, recording = false)

        val action = controller.evaluate(START + DEBOUNCE, RpmSignal.OFF, userPresent = false, recording = false)

        assertEquals(EngineOffAction.None, action)
    }

    @Test
    fun `absent past the silent grace stops with no prompt ever shown`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = false, recording = false)
        // Seeds the absent-since clock exactly at confirmation — mirrors the real watchdog's
        // own ~1s tick cadence reaching this state, rather than jumping straight to a distant
        // `now` no tick ever actually sampled (absence is measured from when userPresent was
        // first OBSERVED false at-or-after confirmation, not retroactively from the OFF reading).
        val confirmedAtMillis = START + DEBOUNCE
        val atConfirm = controller.evaluate(confirmedAtMillis, RpmSignal.OFF, userPresent = false, recording = false)
        assertEquals(EngineOffAction.None, atConfirm)

        val action =
            controller.evaluate(
                confirmedAtMillis + SILENT_GRACE,
                RpmSignal.OFF,
                userPresent = false,
                recording = false,
            )

        assertEquals(EngineOffAction.Stop, action)
    }

    @Test
    fun `Keep monitoring then going absent falls through to a fresh silent grace, not an instant stop`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)
        controller.evaluate(START + DEBOUNCE, RpmSignal.OFF, userPresent = true, recording = false)
        controller.keepMonitoringTapped()

        // Stays present a long while (would have failed a naive "stop 20s after confirm" check).
        val wentAbsentAt = START + DEBOUNCE + PROMPT_TIMEOUT + DEBOUNCE
        val stillWatching =
            controller.evaluate(wentAbsentAt - 1, RpmSignal.OFF, userPresent = true, recording = false)
        assertEquals(EngineOffAction.None, stillWatching)

        // Goes absent — must NOT stop immediately; the grace clock starts fresh from here.
        val justWentAbsent = controller.evaluate(wentAbsentAt, RpmSignal.OFF, userPresent = false, recording = false)
        assertEquals(EngineOffAction.None, justWentAbsent)

        val stillWithinGrace =
            controller.evaluate(wentAbsentAt + SILENT_GRACE - 1, RpmSignal.OFF, userPresent = false, recording = false)
        assertEquals(EngineOffAction.None, stillWithinGrace)

        val pastGrace =
            controller.evaluate(
                wentAbsentAt + SILENT_GRACE,
                RpmSignal.OFF,
                userPresent = false,
                recording = false,
            )
        assertEquals(EngineOffAction.Stop, pastGrace)
    }

    // ---- RPM running: the never-stop-mid-drive guarantee ----

    @Test
    fun `RPM running never triggers anything, present or absent`() {
        val controller = testController()

        val presentAction = controller.evaluate(START, RpmSignal.RUNNING, userPresent = true, recording = false)
        val absentAction = controller.evaluate(START + 1, RpmSignal.RUNNING, userPresent = false, recording = false)

        assertEquals(EngineOffAction.None, presentAction)
        assertEquals(EngineOffAction.None, absentAction)
    }

    @Test
    fun `RPM running clears an in-progress debounce - restarting resets the clock, not just pauses it`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = false, recording = false)
        controller.evaluate(START + 1, RpmSignal.RUNNING, userPresent = false, recording = false)

        // Immediately off again — must debounce from scratch, not resume the old clock.
        controller.evaluate(START + 2, RpmSignal.OFF, userPresent = false, recording = false)
        val tooSoon =
            controller.evaluate(
                START + 2 + DEBOUNCE - 1,
                RpmSignal.OFF,
                userPresent = false,
                recording = false,
            )

        assertEquals(EngineOffAction.None, tooSoon)
    }

    @Test
    fun `RPM running also clears a chosen Keep monitoring - the next engine-off re-prompts`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = false)
        controller.evaluate(START + DEBOUNCE, RpmSignal.OFF, userPresent = true, recording = false)
        controller.keepMonitoringTapped()

        controller.evaluate(START + DEBOUNCE + 1, RpmSignal.RUNNING, userPresent = true, recording = false)
        controller.evaluate(START + DEBOUNCE + 2, RpmSignal.OFF, userPresent = true, recording = false)

        val action =
            controller.evaluate(
                START + DEBOUNCE + 2 + DEBOUNCE,
                RpmSignal.OFF,
                userPresent = true,
                recording = false,
            )

        assertTrue(
            "expected a fresh prompt after a restart, not a suppressed None",
            action is EngineOffAction.ShowPrompt,
        )
    }

    // ---- OBD-70: recording inhibits every phase ----

    @Test
    fun `an active recording inhibits the prompt even past the deadline`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = true, recording = true)

        val action =
            controller.evaluate(
                START + DEBOUNCE + PROMPT_TIMEOUT,
                RpmSignal.OFF,
                userPresent = true,
                recording = true,
            )

        assertEquals(EngineOffAction.None, action)
    }

    @Test
    fun `an active recording inhibits the silent stop even past the grace`() {
        val controller = testController()
        controller.evaluate(START, RpmSignal.OFF, userPresent = false, recording = true)

        val action =
            controller.evaluate(
                START + DEBOUNCE + SILENT_GRACE,
                RpmSignal.OFF,
                userPresent = false,
                recording = true,
            )

        assertEquals(EngineOffAction.None, action)
    }

    private fun testController() =
        EngineOffPromptController(
            engineOffDebounceMillis = DEBOUNCE,
            promptTimeoutMillis = PROMPT_TIMEOUT,
            silentGraceMillis = SILENT_GRACE,
        )

    private fun freshRpm(value: Double): Reading = Reading(PidIds.RPM, value, Instant.EPOCH, stale = false)

    private companion object {
        const val START = 1_000_000L
        const val DEBOUNCE = 45L * 1000
        const val PROMPT_TIMEOUT = 20L * 1000
        const val SILENT_GRACE = 30L * 1000
    }
}
