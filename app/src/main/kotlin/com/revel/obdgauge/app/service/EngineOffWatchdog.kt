package com.revel.obdgauge.app.service

import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading

/**
 * OBD-71: what one [com.revel.obdgauge.model.VehicleDataSource.readings] emission says about the
 * engine, read off [PidIds.RPM] — the input [EngineOffPromptController.evaluate] debounces into
 * "engine off."
 *
 * - [RUNNING]: RPM present, fresh (`!stale`), and `> 0`.
 * - [OFF]: RPM present, fresh, and `== 0` — the engine just stopped, or has been stopped a while.
 * - [AMBIGUOUS]: RPM absent from this emission, or present but stale — the signature of a BLE
 *   dropout losing specifically the FAST-priority RPM poll for a cycle or two, not a parked
 *   vehicle. Neither confirms nor denies engine-off, so [EngineOffPromptController] leaves its
 *   debounce clock untouched on this signal rather than guessing either way.
 */
internal enum class RpmSignal { RUNNING, OFF, AMBIGUOUS }

internal fun rpmSignal(readings: Map<String, Reading>): RpmSignal {
    val rpm = readings[PidIds.RPM]
    return when {
        rpm == null || rpm.stale -> RpmSignal.AMBIGUOUS
        rpm.value > 0 -> RpmSignal.RUNNING
        else -> RpmSignal.OFF
    }
}

/**
 * OBD-71: what [ObdConnectionService] should do right now about the engine-off signal, as decided
 * by [EngineOffPromptController.evaluate] on each tick. Idempotent to re-read every tick (mirrors
 * `shouldPostNotification`'s dedupe-by-diffing shape, `ObdConnectionService.kt`) — the UI layer
 * reacts to CHANGES in this value published through [EngineOffBridge]; nothing calls into this
 * class directly except [ObdConnectionService]. Public (not `internal` like [RpmSignal]/
 * [EngineOffPromptController]): it appears in `DashboardViewModel.engineOffAction`'s and
 * `EngineOffPromptHost`'s own public signatures, and Kotlin doesn't allow a public member to
 * expose an `internal` type — same reason `RecordingState` (its OBD-70 analogue) is public too.
 */
sealed interface EngineOffAction {
    /** Engine running, engine-off not yet debounced-confirmed, or a recording is in progress. */
    data object None : EngineOffAction

    /** User present: show/keep showing the "Engine off — keep monitoring?" dialog. */
    data class ShowPrompt(
        val deadlineMillis: Long,
    ) : EngineOffAction

    /** Tear the connection down via the existing OBD-69 stop path. */
    data object Stop : EngineOffAction
}

/**
 * OBD-71: the engine-off + user-presence decision — "keep alive if (engine running) OR (user
 * actively watching); only stop when the engine's off AND the user has walked away" (Taras's
 * design, `issues/OBD-71.md`). Pure/stateful-but-Android-free, mirroring [IdleActivityTracker] +
 * `shouldStopForIdle`'s split: the state lives here, [evaluate] is fed a monotonic `now`
 * (production: `SystemClock.elapsedRealtime`) rather than reading a clock itself, so every branch
 * is unit-testable with plain `Long`s (`EngineOffWatchdogTest`) and no Robolectric.
 *
 * ### Why this is a second, independent watchdog rather than a replacement for OBD-69's
 * OBD-69's original "any non-empty readings emission is activity" idle backstop stays exactly as
 * it was (`IdleWatchdog.kt`) — it remains the generic no-data/link-down safety net for a session
 * that goes fully dark for any reason. This class is a second, faster-acting, MORE SPECIFIC
 * trigger: it can positively identify "the engine is off" from RPM, which the generic backstop
 * cannot (a live dongle keeps answering non-RPM PIDs as a parked engine cools — see
 * `ObdConnectionService`'s class KDoc / `issues/OBD-71.md` for the full trace of why "any data"
 * alone was defeated, and why an earlier, since-reverted version of this fix made RPM==0 stop
 * unconditionally — Taras wants to keep watching oil/coolant during a deliberate key-on/engine-off
 * session, so a pure engine-state trigger was too blunt). Either watchdog reaching its own stop
 * condition tears the same connection down the same way.
 *
 * ### The phases [evaluate] walks through
 * 1. **Debounce.** [RpmSignal.OFF] starts a clock ([rpmZeroSinceMillis]); it must read OFF
 *    continuously for [engineOffDebounceMillis] before "engine off" is CONFIRMED — a brief 0
 *    during a stall/restart must not trigger anything. [RpmSignal.RUNNING] clears the clock (and
 *    every other piece of state below) outright. [RpmSignal.AMBIGUOUS] touches nothing: it
 *    neither confirms nor denies, so an in-progress debounce (or an already-confirmed off) rides
 *    through a momentary BLE hiccup unaffected.
 * 2. **Once confirmed off, branch on [userPresent].** Present: a 20-second countdown
 *    ([EngineOffAction.ShowPrompt]) the user can cancel with [keepMonitoringTapped] — this is the
 *    whole reason presence matters: Taras wants to watch oil/coolant temps with the engine off
 *    (heat-soak / key-on-engine-off diagnostics), not have the connection yanked out from under
 *    him. Absent: no prompt (nobody to see it) — a shorter silent grace, then
 *    [EngineOffAction.Stop]. This is the actual overnight-drain fix; a prompt can't help while
 *    nobody's watching.
 * 3. **After Keep-monitoring, don't nag.** [keepMonitoringTapped] suppresses the prompt/stop
 *    entirely while the user stays present. The moment [userPresent] later reads `false` (screen
 *    off/backgrounded) with the engine still off, the silent-grace clock starts fresh from THAT
 *    moment and the session falls through to the same silent stop — "keep monitoring" is not a
 *    permanent override, only a "not right now."
 *
 * [recording] inhibits every phase outright (OBD-70 seam, mirrors
 * `ObdConnectionService.checkIdleAndMaybeStop`'s own recording check) — an active recording is
 * benefit-in-progress even with the engine off.
 */
internal class EngineOffPromptController(
    private val engineOffDebounceMillis: Long = ENGINE_OFF_DEBOUNCE_MILLIS,
    private val promptTimeoutMillis: Long = ENGINE_OFF_PROMPT_TIMEOUT_MILLIS,
    private val silentGraceMillis: Long = ENGINE_OFF_SILENT_GRACE_MILLIS,
) {
    private var rpmZeroSinceMillis: Long? = null
    private var keepMonitoring = false
    private var absentSinceMillis: Long? = null

    /** The dialog's "Keep monitoring" tap — see class KDoc phase 3. */
    fun keepMonitoringTapped() {
        keepMonitoring = true
        absentSinceMillis = null
    }

    fun evaluate(
        nowMillis: Long,
        rpm: RpmSignal,
        userPresent: Boolean,
        recording: Boolean,
    ): EngineOffAction {
        when (rpm) {
            RpmSignal.RUNNING -> reset()
            RpmSignal.OFF -> if (rpmZeroSinceMillis == null) rpmZeroSinceMillis = nowMillis
            RpmSignal.AMBIGUOUS -> Unit
        }

        // Single expression body (ReturnCount) — offSinceMillis == null means RPM has never
        // read OFF (or was just cleared by RUNNING above); folded into the "not yet confirmed"
        // branch below rather than an early return.
        val confirmedAtMillis = rpmZeroSinceMillis?.plus(engineOffDebounceMillis)
        return when {
            confirmedAtMillis == null || nowMillis < confirmedAtMillis || recording -> EngineOffAction.None
            userPresent -> {
                absentSinceMillis = null
                evaluatePresent(confirmedAtMillis, nowMillis)
            }
            else -> evaluateAbsent(nowMillis)
        }
    }

    private fun evaluatePresent(
        confirmedAtMillis: Long,
        nowMillis: Long,
    ): EngineOffAction {
        if (keepMonitoring) return EngineOffAction.None
        val deadlineMillis = confirmedAtMillis + promptTimeoutMillis
        return if (nowMillis >= deadlineMillis) EngineOffAction.Stop else EngineOffAction.ShowPrompt(deadlineMillis)
    }

    private fun evaluateAbsent(nowMillis: Long): EngineOffAction {
        val sinceMillis = absentSinceMillis ?: nowMillis.also { absentSinceMillis = it }
        return if (nowMillis - sinceMillis >= silentGraceMillis) EngineOffAction.Stop else EngineOffAction.None
    }

    private fun reset() {
        rpmZeroSinceMillis = null
        keepMonitoring = false
        absentSinceMillis = null
    }
}

// OBD-71: how long RPM must read a fresh, continuous 0 before "engine off" is confirmed — long
// enough that a stall-then-immediate-restart (or a momentary decode blip) never trips the prompt/
// silent-stop machinery, short enough that the fix still acts promptly once the engine is truly
// off. 45 s sits inside Taras's given 30-60 s band.
private const val ENGINE_OFF_DEBOUNCE_MILLIS = 45 * 1000L

// OBD-71: the "Engine off — keep monitoring?" dialog's fixed countdown (spec).
private const val ENGINE_OFF_PROMPT_TIMEOUT_MILLIS = 20 * 1000L

// OBD-71: the silent (no-dialog) grace once the user is confirmed absent with the engine off —
// short by design (this is the actual overnight-drain fix), but a little more forgiving than the
// prompt's 20 s since there's no user reaction to wait on — only room for a momentary screen-off/
// on flap (e.g. a dash mount's proximity sensor) not to trip a silent stop prematurely.
private const val ENGINE_OFF_SILENT_GRACE_MILLIS = 30 * 1000L
