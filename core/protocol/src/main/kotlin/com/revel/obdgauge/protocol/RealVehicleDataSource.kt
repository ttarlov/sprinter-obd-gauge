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
class RealVehicleDataSource internal constructor(
    private val link: ObdLink,
    private val scope: CoroutineScope,
    private val config: PollConfig,
    private val clock: Clock,
    private val onEvent: (PollEvent) -> Unit,
    /**
     * Test-only catalog overrides. **Always [TestOverrides.NONE] in production**, and unreachable
     * outside this module — the public constructor below cannot set it. See [TestOverrides].
     */
    private val overrides: TestOverrides,
) : VehicleDataSource {
    constructor(
        link: ObdLink,
        scope: CoroutineScope,
        config: PollConfig = PollConfig(),
        clock: Clock = Clock.systemUTC(),
        onEvent: (PollEvent) -> Unit = {},
    ) : this(link, scope, config, clock, onEvent, TestOverrides.NONE)

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
                val resolved = PidCatalog.byId(id) ?: overrides.extraChannels.firstOrNull { it.definition.id == id }
                if (resolved == null && id != PidIds.BOOST) {
                    onEvent(PollEvent.UnknownPid(id))
                }
                resolved
            }
        reportUnavailable(needed)
        return PollPlan(polled = polled, computeBoost = computeBoost)
    }

    /**
     * Announces, before a single command goes out, every channel in the plan that the hardware
     * survey has already falsified — on this van, `map` and `iat`, and therefore `boost`.
     *
     * Walks the *expanded* set, so asking for boost reports both halves of the story: `boost` is
     * [ChannelAvailability.MissingInputs]`(["map"])` — the consequence a gauge has to render —
     * and `map` is [ChannelAvailability.UnsupportedByVehicle] with its capture — the cause.
     *
     * Once per session, at plan time, because that is when the answer is known: it comes from a
     * capture, not from the wire. Deriving it instead from "boost has not been published for a
     * while" would emit continuously, arrive late, and confuse a permanently absent PID with a
     * reading that simply has not landed yet — which `readings` already expresses by absence.
     *
     * The unsupported PIDs are still polled. The survey is a fact about one vehicle recorded in
     * [PidCatalog], and letting it *silence* a request would mean a wrong entry in that table
     * could never be discovered; a `NO DATA` per cycle is one cheap round trip that keeps the
     * evidence flowing.
     */
    private fun reportUnavailable(needed: List<String>) {
        for (id in needed) {
            val availability = availabilityOf(id)
            if (availability != ChannelAvailability.Available) {
                onEvent(PollEvent.ChannelAvailabilityChanged(id, availability))
            }
        }
    }

    /**
     * [PidCatalog.availabilityOf], with any test-injected [extraFalsified] ids folded in as
     * [ChannelAvailability.DecodeFalsified]. Production passes no extras, so this is exactly the
     * catalog's answer; the seam exists only to keep the falsified-decode gate testable now that
     * the catalog's own set is empty (OBD-55). Both the plan-time announcement and the poll-time
     * gate go through here, so a synthetic falsified channel is announced and blocked identically
     * to a real one.
     */
    private fun availabilityOf(id: String): ChannelAvailability =
        if (id in overrides.extraFalsified) {
            ChannelAvailability.DecodeFalsified("synthetic falsified channel (test seam); see TestOverrides")
        } else {
            PidCatalog.availabilityOf(id)
        }

    private suspend fun runSession(plan: PollPlan) {
        val samples = mutableMapOf<String, Sample>()
        if (!initialize()) {
            publish(samples, plan, forceStale = true)
            return
        }
        val requesters = Requesters(Mode22Requester(link, config.mode22), KwpRecordRequester(link, config.mode22))
        var cycle = 0
        while (currentCoroutineContext().isActive) {
            if (!requesters.restoreHeaders()) {
                onEvent(PollEvent.HeaderRestoreFailed)
            }
            for (pid in PollSchedule.cycleMembers(plan.polled, cycle, config.slowEveryNCycles)) {
                if (!applyPoll(pid, requesters, samples)) {
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
        requesters: Requesters,
        samples: MutableMap<String, Sample>,
    ): Boolean {
        val id = pid.definition.id
        // Round-1 review (OBD-43/49): a channel whose decode was FALSIFIED by capture is neither
        // sent nor stored — unlike an unsupported PID (polled for discoverability), an answer
        // here would be misread by construction, and the misreading renders a plausible-looking
        // wrong number. The plan-time ChannelAvailabilityChanged(DecodeFalsified) announcement
        // is the one signal; per-cycle re-announcement would be noise. (Empty in prod since
        // OBD-55; see [availabilityOf] and [extraFalsified].)
        if (availabilityOf(id) is ChannelAvailability.DecodeFalsified) {
            return true
        }
        return when (val outcome = poll(pid, requesters)) {
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
        requesters: Requesters,
    ): PollOutcome =
        when (pid) {
            is PolledPid.Manufacturer -> requesters.mode22.request(pid.spec)
            // A KWP record (OBD-55: the trans-temp channel) frames its own ATSH/ATCRA sequence and
            // restores after, exactly like the mode-22 arm — it differs only in that the answer is
            // a multi-frame block scaled by field offset, which KwpRecordRequester owns.
            is PolledPid.Record -> requesters.record.poll(pid.spec)
            is PolledPid.Standard -> {
                // A failed header restore from a framed poll earlier in THIS cycle must not
                // leave this standard query addressed to 7E1 (review round-1 M1): retry the
                // restore on BOTH framed requesters first. No-op when nothing is pending, so the
                // happy path pays nothing.
                if (!requesters.restoreHeaders()) {
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
        // No boost reading rather than a substituted one: `boost == null` means an input is
        // absent, and the identity element of a subtraction is 0 — which on a boost gauge reads
        // as "engine not pulling" and is indistinguishable from a real measurement. See
        // [ChannelAvailability]; [reportUnavailable] is what tells a consumer why.
        mutableReadings.value = if (boost == null) polled else polled + (boost.id to boost)
    }
}

/**
 * Test-only catalog overrides for [RealVehicleDataSource], both empty in production and reachable
 * only through its `internal` constructor — `:app` gets the public constructor, which passes
 * [NONE]. Two seams that keep coverage the OBD-55 catalog would otherwise lose:
 *
 * - [extraChannels] — channels resolvable in addition to [PidCatalog]'s. `MercedesPidRegistry.all`
 *   is empty since the trans decode moved to a KWP record, so the manufacturer *pipeline* (framed
 *   sequence in a cycle, `ATSH` rejection costing only that channel, restore retry) has no
 *   production spec to ride; tests supply a synthetic one rather than let it go untested.
 * - [extraFalsified] — ids to treat as [ChannelAvailability.DecodeFalsified] on top of
 *   [PidCatalog]'s (now-empty) set, so the falsified-decode gate itself stays tested.
 *
 * The property `start` is built on survives either way: it still takes only ids, and framing and
 * scaling still come from a registry rather than a caller's lambda.
 */
internal data class TestOverrides(
    val extraChannels: List<PolledPid> = emptyList(),
    val extraFalsified: Set<String> = emptySet(),
) {
    companion object {
        val NONE = TestOverrides()
    }
}

/**
 * The two physically-addressed request paths a session may need, sharing the poll loop's restore
 * discipline. A mode-22 single-frame read ([Mode22Requester]) and a KWP multi-frame record read
 * ([KwpRecordRequester]) each own a [HeaderScope], so a restore left pending by one must be picked
 * up whoever polls next — [restoreHeaders] fixes up both.
 */
private class Requesters(
    val mode22: Mode22Requester,
    val record: KwpRecordRequester,
) {
    /**
     * Restores default headers on both paths. Both calls always run — a `&&` would short-circuit
     * and skip the record path's restore whenever the mode-22 one reported not-yet-clean — and the
     * combined result is "both are known clean".
     */
    suspend fun restoreHeaders(): Boolean {
        val mode22Clean = mode22.restoreHeaders()
        val recordClean = record.restoreHeaders()
        return mode22Clean && recordClean
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
