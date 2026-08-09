package com.revel.obdgauge.testing.datasource

import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.Reading
import com.revel.obdgauge.model.VehicleDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * Scripted, deterministic [VehicleDataSource] fake. Replays one [Scenario] as an ordered
 * sequence of connection-state changes and reading updates — no wall-clock dependency, no
 * randomness. The same [scenario] with the same [tickInterval]/[startInstant] always produces
 * the exact same emission sequence, which is what makes it usable both for UI development
 * (`:app`'s `demo` flavor) and for exact-sequence unit tests.
 *
 * @param scenario which scripted scenario to replay; see [Scenario].
 * @param tickInterval how long the replay coroutine `delay`s between script steps.
 * @param startInstant the virtual clock's start value; every emitted [Reading]'s timestamp is
 *   derived from this plus whole [tickInterval] multiples, never from `Instant.now()`.
 * @param scope the [CoroutineScope] the replay coroutine runs on. Defaults to a private
 *   [Dispatchers.Default] scope for production/demo use; tests should inject a `TestScope`
 *   (e.g. its `backgroundScope`) to drive the replay on virtual time.
 */
class FakeVehicleDataSource(
    private val scenario: Scenario,
    private val tickInterval: Duration = DEFAULT_TICK_INTERVAL,
    private val startInstant: Instant = Instant.EPOCH,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : VehicleDataSource {
    private val mutableReadings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    override val readings: StateFlow<Map<String, Reading>> = mutableReadings.asStateFlow()

    private val mutableConnection = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val connection: StateFlow<LinkState> = mutableConnection.asStateFlow()

    private var replayJob: Job? = null

    /**
     * Cancels any in-progress replay and starts the scripted scenario from the beginning,
     * emitting only the channels present in [pids]. Idempotent to call repeatedly — each call
     * replaces the active set and resets [readings] to empty before the new script runs.
     */
    override fun start(pids: List<PidDefinition>) {
        replayJob?.cancel()
        val requestedIds = pids.mapTo(mutableSetOf()) { it.id }
        mutableReadings.value = emptyMap()
        replayJob =
            scope.launch {
                var clock = startInstant
                for (step in scenarioSteps(scenario)) {
                    when (step) {
                        is ScriptStep.Connect -> applyConnect(step)
                        is ScriptStep.Emit -> clock = applyEmit(step, requestedIds, clock)
                    }
                    delay(tickInterval)
                }
            }
    }

    private fun applyConnect(step: ScriptStep.Connect) {
        mutableConnection.value = step.state
        if (step.state !is LinkState.Ready) {
            mutableReadings.update { current -> current.mapValues { (_, reading) -> reading.copy(stale = true) } }
        }
    }

    private fun applyEmit(
        step: ScriptStep.Emit,
        requestedIds: Set<String>,
        clock: Instant,
    ): Instant {
        val timestamp = clock.plus(tickInterval.toJavaDuration())
        val fresh =
            step.values
                .filterKeys { it in requestedIds }
                .mapValues { (id, value) -> Reading(id = id, value = value, timestamp = timestamp, stale = false) }
        mutableReadings.update { it + fresh }
        return timestamp
    }

    /** Cancels the replay and returns [connection] to [LinkState.Disconnected]. Idempotent. */
    override fun stop() {
        replayJob?.cancel()
        replayJob = null
        mutableConnection.value = LinkState.Disconnected
    }

    private companion object {
        val DEFAULT_TICK_INTERVAL = 500.milliseconds
    }
}
