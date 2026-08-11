package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import kotlin.time.toKotlinDuration

/**
 * The real [VehicleDataSource]: ELM327 init, a single-flight sequential poll loop over an
 * [ObdLink], and the computed boost channel — everything between a raw string pipe and the
 * [Reading]s the UI renders.
 *
 * ## The loop
 *
 * One coroutine per session. It initializes once, then repeats: poll this cycle's PIDs in order,
 * one command at a time, publishing after each; derive boost; wait [PollConfig.cycleInterval];
 * repeat. Sequential is not a simplification — the ELM327 is half-duplex and can hold exactly one
 * command in flight, so concurrency here would buy nothing and break the dongle. `StateFlow` gives
 * latest-value semantics with no backpressure: a UI that renders at 60 fps and a bus that answers
 * at 10 Hz simply never queue against each other.
 *
 * Cadence is FAST every cycle, SLOW every Nth ([PollSchedule]). Temperatures and barometric
 * pressure move on a timescale of minutes; spending a BLE round trip on them at boost's rate would
 * slow down the gauge that actually needs to be quick.
 *
 * ## What it refuses to do
 *
 * A parse failure **skips** the reading: the previous value stands and ages toward stale, and a
 * [PollEvent.ReadingSkipped] is emitted. Nothing partial, defaulted, or interpolated is ever
 * published — a gauge showing an old value dimmed is honest, a gauge showing a fresh-looking wrong
 * value on a mountain grade is the failure mode this entire module is built around.
 *
 * Staleness is recomputed on every publish from the reading's own age, so a value goes dim on its
 * own even if the loop stops feeding it. When the loop exits, everything is marked stale on the
 * way out.
 *
 * ## Lifecycle, per the pinned contract
 *
 * [start] replaces: the previous loop is cancelled and awaited before the new one publishes, so
 * repeated `start` can never leave two loops writing to [readings]. [stop] is idempotent.
 * `start` after `stop` is a clean session — readings are cleared and init runs again.
 *
 * **Connection lifecycle is not owned here.** This class never calls [ObdLink.connect] or
 * [ObdLink.disconnect]; it assumes an already-connected link and only speaks commands on it.
 * Scan/connect/reconnect belong to `:core:ble` (OBD-23) and the DI owner. That is a deliberate
 * deviation from [VehicleDataSource.stop]'s "release the underlying connection" wording, resolved
 * at Phase-4 integration: whoever owns the link disconnects it. [connection] simply forwards
 * [ObdLink.state].
 *
 * **On a link drop the loop parks** — marks every reading stale, emits [PollEvent.LinkDropped],
 * and returns without throwing. It does not reconnect and does not spin retrying: reconnection is
 * `:core:ble`'s job (OBD-23), and a loop hammering a dead link would drain a parked van's battery
 * to no purpose. Polling resumes when the owner calls [start] again on a live link.
 *
 * @param link a connected link. Must be single-flight per its own contract.
 * @param scope the scope the poll loop runs in; the owner's lifetime bounds the loop. Tests
 *   inject a `TestScope.backgroundScope` to drive it on virtual time.
 * @param config poll cadence, timeouts, and staleness windows; see [PollConfig].
 * @param clock source for [Reading.timestamp] and staleness. Inject a fixed/mutable clock in tests.
 * @param onEvent sink for typed diagnostics; see [PollEvent]. Called from the loop coroutine, so
 *   it must not block.
 */
class RealVehicleDataSource(
    private val link: ObdLink,
    private val scope: CoroutineScope,
    private val config: PollConfig = PollConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val onEvent: (PollEvent) -> Unit = {},
) : VehicleDataSource {
    private val mutableReadings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    override val readings: StateFlow<Map<String, Reading>> = mutableReadings.asStateFlow()

    override val connection: StateFlow<LinkState> get() = link.state

    private val lifecycle = Any()
    private var session: Job? = null

    /**
     * Starts (or restarts) polling for exactly [pids], resolved by [PidDefinition.id].
     *
     * Ids unknown to [PidCatalog] are dropped with a [PollEvent.UnknownPid]; requesting
     * [PidIds.BOOST] additionally pulls in `map` and `baro`, so a screen can ask for boost without
     * knowing what feeds it.
     */
    override fun start(pids: List<PidDefinition>) {
        val plan = planFor(pids)
        synchronized(lifecycle) {
            val previous = session
            previous?.cancel()
            session =
                scope.launch {
                    // Join before touching state: the outgoing loop must be finished publishing
                    // before this one clears readings, or a dying cycle could repopulate them.
                    previous?.join()
                    mutableReadings.value = emptyMap()
                    runSession(plan)
                }
        }
    }

    /** Cancels the poll loop. Idempotent; does not touch the link. See the class KDoc. */
    override fun stop() {
        synchronized(lifecycle) {
            session?.cancel()
            session = null
        }
    }

    private fun planFor(pids: List<PidDefinition>): PollPlan {
        val requested = pids.map(PidDefinition::id).distinct()
        val computeBoost = PidIds.BOOST in requested
        val needed = requested.flatMap { id -> listOf(id) + PidCatalog.dependenciesOf(id) }.distinct()
        val polled =
            needed.mapNotNull { id ->
                val resolved = PidCatalog.byId(id)
                if (resolved == null && id != PidIds.BOOST) {
                    onEvent(PollEvent.UnknownPid(id))
                }
                resolved
            }
        return PollPlan(polled = polled, computeBoost = computeBoost)
    }

    private suspend fun runSession(plan: PollPlan) {
        val samples = mutableMapOf<String, Sample>()
        if (!initialize()) {
            publish(samples, plan, forceStale = true)
            return
        }
        val requester = Mode22Requester(link, config.mode22)
        var cycle = 0
        while (currentCoroutineContext().isActive) {
            if (!requester.restoreHeaders()) {
                onEvent(PollEvent.HeaderRestoreFailed)
            }
            for (pid in PollSchedule.cycleMembers(plan.polled, cycle, config.slowEveryNCycles)) {
                if (!applyPoll(pid, requester, samples)) {
                    publish(samples, plan, forceStale = true)
                    return
                }
                publish(samples, plan)
            }
            publish(samples, plan)
            cycle = (cycle + 1) % config.slowEveryNCycles
            delay(config.cycleInterval)
        }
    }

    /** Runs the ELM327 init sequence once per session. `false` means the session cannot proceed. */
    private suspend fun initialize(): Boolean =
        when (val result = Elm327InitStateMachine(link, config.initTimeouts).run()) {
            is InitResult.Success -> {
                onEvent(PollEvent.Initialized(result))
                true
            }
            is InitResult.Failure -> {
                onEvent(PollEvent.InitFailed(result.failure))
                false
            }
        }

    /**
     * Polls one channel and folds the result into [samples].
     *
     * @return `false` when the link went down and the loop must park; `true` otherwise, including
     *   for a skipped reading.
     */
    private suspend fun applyPoll(
        pid: PolledPid,
        requester: Mode22Requester,
        samples: MutableMap<String, Sample>,
    ): Boolean {
        val id = pid.definition.id
        return when (val outcome = poll(pid, requester)) {
            is PollOutcome.Value -> {
                samples[id] = Sample(outcome.value, Instant.now(clock), pid.definition.pollPriority)
                true
            }
            is PollOutcome.Skipped -> {
                onEvent(PollEvent.ReadingSkipped(id, outcome.reason))
                true
            }
            is PollOutcome.HeaderRejected -> {
                onEvent(PollEvent.HeaderRejected(id, outcome.command, outcome.raw))
                true
            }
            is PollOutcome.LinkDown -> {
                onEvent(PollEvent.LinkDropped(id, outcome.message))
                false
            }
        }
    }

    /**
     * Issues one channel's request.
     *
     * Both arms answer in [PollOutcome] — not because a standard PID needs header framing, but
     * because the *outcomes* are the same four, and one result type keeps [applyPoll] a single
     * exhaustive `when` instead of two near-identical ones that could drift apart.
     */
    private suspend fun poll(
        pid: PolledPid,
        requester: Mode22Requester,
    ): PollOutcome =
        when (pid) {
            is PolledPid.Manufacturer -> requester.request(pid.spec)
            is PolledPid.Standard -> {
                // A failed header restore from a mode-22 poll earlier in THIS cycle must not
                // leave this standard query addressed to 7E1 (review round-1 M1): retry the
                // restore first. No-op when nothing is pending, so the happy path pays nothing.
                if (!requester.restoreHeaders()) {
                    onEvent(PollEvent.HeaderRestoreFailed)
                }
                when (val raw = link.sendCatching(pid.spec.command, config.commandTimeout)) {
                    is RawResult.Failed -> PollOutcome.LinkDown(raw.message)
                    is RawResult.Text ->
                        when (val parsed = ResponseParser.parse(pid.spec, raw.value)) {
                            is ParseOutcome.Success -> PollOutcome.Value(parsed.value)
                            is ParseOutcome.Failure -> PollOutcome.Skipped(parsed.reason)
                        }
                }
            }
        }

    /**
     * Rebuilds [readings] from [samples], re-evaluating staleness against the current clock and
     * deriving boost.
     *
     * The whole map is rebuilt every publish rather than patched, because staleness is a function
     * of *time*, not of what just arrived: a channel that stopped answering has to go stale on its
     * own, without anything happening to it.
     */
    private fun publish(
        samples: Map<String, Sample>,
        plan: PollPlan,
        forceStale: Boolean = false,
    ) {
        val now = Instant.now(clock)
        val polled =
            samples.mapValues { (id, sample) ->
                Reading(
                    id = id,
                    value = sample.value,
                    timestamp = sample.timestamp,
                    stale = forceStale || sample.isStale(now, config),
                )
            }
        val boost =
            if (plan.computeBoost) {
                ComputedChannels.boost(map = polled[ProtocolPidIds.MAP], baro = polled[PidIds.BARO])
            } else {
                null
            }
        mutableReadings.value = if (boost == null) polled else polled + (boost.id to boost)
    }
}

/** The resolved channels one session polls, plus whether boost is derived from them. */
private data class PollPlan(
    val polled: List<PolledPid>,
    val computeBoost: Boolean,
)

/** The last successful value for one channel, before staleness is applied. */
private data class Sample(
    val value: Double,
    val timestamp: Instant,
    val priority: PollPriority,
) {
    fun isStale(
        now: Instant,
        config: PollConfig,
    ): Boolean =
        java.time.Duration
            .between(timestamp, now)
            .toKotlinDuration() > config.staleAfter(priority)
}
