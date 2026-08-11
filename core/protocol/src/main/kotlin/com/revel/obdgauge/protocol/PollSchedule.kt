package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.PollPriority
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tuning for [RealVehicleDataSource]'s poll loop.
 *
 * Defaults are chosen for a BLE ELM327 clone, where a round trip costs roughly 40–120 ms and the
 * link is strictly half-duplex — so a cycle of four FAST PIDs already occupies a few hundred
 * milliseconds and there is nothing to gain from a shorter [cycleInterval]. These are starting
 * points, not measurements: real poll-rate tuning against hardware latency is Sprint 3.
 *
 * @param cycleInterval how long the loop waits *between* cycles. Commands themselves are
 *   sequential and take as long as they take, so actual cycle period is this plus the round trips.
 * @param slowEveryNCycles a [PollPriority.SLOW] PID is polled on every Nth cycle, starting with
 *   cycle 0 (so the first cycle reads everything and boost is available immediately). `1` polls
 *   everything every cycle.
 * @param commandTimeout per-command timeout for standard PID requests.
 * @param fastStaleAfter how long a FAST reading stays trusted without a refresh.
 * @param slowStaleAfter the same for SLOW readings, which by design refresh N times less often —
 *   a shared threshold would either flap on SLOW channels or hide a dead FAST one.
 * @param initTimeouts timeouts for the one-per-session ELM327 init; see [InitTimeouts].
 * @param mode22 framing/restore parameters for manufacturer PIDs; see [Mode22Config].
 */
data class PollConfig(
    val cycleInterval: Duration = 200.milliseconds,
    val slowEveryNCycles: Int = 5,
    val commandTimeout: Duration = 2.seconds,
    val fastStaleAfter: Duration = 2.seconds,
    val slowStaleAfter: Duration = 15.seconds,
    val initTimeouts: InitTimeouts = InitTimeouts(),
    val mode22: Mode22Config = Mode22Config(),
) {
    init {
        require(slowEveryNCycles >= 1) { "slowEveryNCycles must be at least 1, was $slowEveryNCycles" }
        require(cycleInterval >= Duration.ZERO) { "cycleInterval must not be negative, was $cycleInterval" }
    }

    /** How long a reading of [priority] may go unrefreshed before it is marked stale. */
    fun staleAfter(priority: PollPriority): Duration =
        when (priority) {
            PollPriority.FAST -> fastStaleAfter
            PollPriority.SLOW -> slowStaleAfter
        }
}

/**
 * The FAST/SLOW cadence, as a pure function of the cycle number.
 *
 * Split out of the loop because "which PIDs on which cycle" is the part worth asserting exactly,
 * and asserting it should not require a fake dongle or virtual time.
 */
object PollSchedule {
    /**
     * The channels to poll on [cycle] (0-based), in the order given.
     *
     * FAST channels appear every cycle; SLOW channels appear when `cycle % slowEveryNCycles == 0`,
     * which includes cycle 0 — the first cycle deliberately reads everything, so baro (and
     * therefore boost) is available from the first moment rather than N cycles in.
     *
     * Input order is preserved rather than regrouped: the caller's order is what makes the command
     * sequence predictable, and a PID's position within a cycle is the only thing that decides how
     * old its value is by the time the cycle publishes.
     */
    fun cycleMembers(
        pids: List<PolledPid>,
        cycle: Int,
        slowEveryNCycles: Int,
    ): List<PolledPid> {
        val includeSlow = includesSlow(cycle, slowEveryNCycles)
        return pids.filter { it.definition.pollPriority == PollPriority.FAST || includeSlow }
    }

    /**
     * Whether [cycle] is one of the every-Nth cycles that also polls SLOW channels.
     *
     * @throws IllegalArgumentException if [slowEveryNCycles] is below 1 — a zero or negative
     *   divisor is a configuration bug, and silently coercing it would silently change the poll
     *   rate of every temperature channel — or if [cycle] is negative, where Kotlin's `%` would
     *   return a negative remainder and quietly shift the whole cadence.
     */
    fun includesSlow(
        cycle: Int,
        slowEveryNCycles: Int,
    ): Boolean {
        require(slowEveryNCycles >= 1) { "slowEveryNCycles must be at least 1, was $slowEveryNCycles" }
        require(cycle >= 0) { "cycle must not be negative, was $cycle" }
        return cycle % slowEveryNCycles == 0
    }
}
