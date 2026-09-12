package com.revel.obdgauge.app.service

// OBD-78: the pure wedge-recovery decision, kept beside (not inside) ObdConnectionService the same
// way shouldStopForIdle lives in IdleWatchdog.kt — so it is testable without a link, a clock, or
// Robolectric, and so the service file stays under detekt's per-file function count.

/** [wedgeReconnectDecision]'s output — whether to force a reconnect now, and the new latch value. */
internal data class WedgeDecision(
    val forceReconnect: Boolean,
    val pending: Boolean,
)

/** [wedgeInputs]'s output: the two booleans [wedgeReconnectDecision] needs, plus the health stamp to carry. */
internal data class WedgeInputs(
    val stale: Boolean,
    val sustainedHealthy: Boolean,
    val dataHealthySinceMillis: Long,
)

/**
 * OBD-78: turns the service's raw timers into the [WedgeInputs] the decision needs — pure, so the
 * two round-3 fixes are unit-testable without a service or a real clock.
 *
 * - **[stale] is SESSION-relative (round-3 M1):** measured from `max(`[readySinceMillis]`,
 *   `[lastDataAtMillis]`)`, so a link fresh out of a long outage gets its own quiet window instead of
 *   being judged by a global stamp that aged while it was disconnected. Only meaningful when
 *   [linkReady]; `false` otherwise.
 * - **[sustainedHealthy] needs data to flow for [sustainedAfterMillis] (round-3 B3):** the returned
 *   [WedgeInputs.dataHealthySinceMillis] is `now` when flow starts, carried forward while it
 *   continues, and `0` the moment it breaks. Because a single sample keeps the link non-stale for
 *   only one [staleAfterMillis] window and [sustainedAfterMillis] is strictly larger, one sample can
 *   never make this true — which is what stops an answers-once-then-wedges dongle from looping.
 */
@Suppress("LongParameterList") // seven timers/thresholds, each a distinct input to a pure computation.
internal fun wedgeInputs(
    linkReady: Boolean,
    readySinceMillis: Long,
    lastDataAtMillis: Long,
    dataHealthySinceMillis: Long,
    nowMillis: Long,
    staleAfterMillis: Long,
    sustainedAfterMillis: Long,
): WedgeInputs {
    val stale = linkReady && nowMillis - maxOf(readySinceMillis, lastDataAtMillis) >= staleAfterMillis
    val dataFlowing = nowMillis - lastDataAtMillis < staleAfterMillis
    val nextHealthy = if (dataFlowing) dataHealthySinceMillis.takeIf { it != 0L } ?: nowMillis else 0L
    val sustainedHealthy = nextHealthy != 0L && nowMillis - nextHealthy >= sustainedAfterMillis
    return WedgeInputs(stale = stale, sustainedHealthy = sustainedHealthy, dataHealthySinceMillis = nextHealthy)
}

/**
 * The pure wedge-recovery decision. Its whole job is to turn "is this a silent-but-connected dongle,
 * and have we already tried" into "force a reconnect (once)". See
 * `ObdConnectionService.checkWedgeAndMaybeReconnect` for how the inputs are gathered (both [stale]
 * and [sustainedHealthy] are session-relative — see there) and the `forceReconnect` acted on, and
 * `reviews/OBD-78-round2.md`/`-round3.md` for why this lives at the service layer and why the
 * two round-3 blockers force the shape below.
 *
 * Truth table (checked top to bottom):
 * - **link absent / not `Ready`** → do nothing, [pending] unchanged. "Not `Ready`" includes the
 *   `Connecting` phase of our OWN forced reconnect, so the latch MUST survive it — clearing it here
 *   is what would let the watchdog re-fire every window and storm.
 * - **[sustainedHealthy]** (data has flowed continuously long enough to trust the link) → clear
 *   [pending]: re-arm for a future wedge episode. This — NOT a single fresh sample — is the only
 *   re-arm. Requiring *sustained* data is what stops a "answers once, then wedges again" dongle from
 *   looping force→sample→wedge forever and defeating the idle-stop (round-3 B3).
 * - **[stale] and not yet forced this episode** → **force** a reconnect and latch [pending].
 * - **otherwise** (stale+already-forced, or not-yet-stale-and-not-yet-sustained) → hold the latch.
 */
internal fun wedgeReconnectDecision(
    linkAvailable: Boolean,
    linkReady: Boolean,
    stale: Boolean,
    sustainedHealthy: Boolean,
    pending: Boolean,
): WedgeDecision =
    when {
        !linkAvailable || !linkReady -> WedgeDecision(forceReconnect = false, pending = pending)
        sustainedHealthy -> WedgeDecision(forceReconnect = false, pending = false)
        stale && !pending -> WedgeDecision(forceReconnect = true, pending = true)
        else -> WedgeDecision(forceReconnect = false, pending = pending)
    }
