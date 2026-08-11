package com.revel.obdgauge.protocol

import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.ObdRequest
import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.PollPriority
import com.revel.obdgauge.testing.link.FakeObdLink
import com.revel.obdgauge.testing.link.Fault
import com.revel.obdgauge.testing.link.TranscriptParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins the `join` in [RealVehicleDataSource.start] — the one part of the pinned restart contract
 * that virtual time cannot falsify.
 *
 * The rest of the lifecycle suite runs on `TestScope`, whose single-threaded scheduler serializes
 * the dying loop and the incoming one for free, so removing `previous?.join()` passes all of it.
 * The race only exists on a real dispatcher, and only while the outgoing loop is inside
 * **non-suspending** code: cancellation is not observed until the next suspension point, so a loop
 * that is mid-cycle when `start` cancels it will still run its `publish` to completion — after the
 * new session cleared `readings`. That leaves a dead session's value on a live gauge.
 *
 * This test parks the outgoing loop exactly there. `onEvent` is called from the loop coroutine and
 * is documented as "must not block"; blocking it is the point — it is the only hook that reaches
 * non-suspending loop code from outside. Do not "fix" the blocking call.
 *
 * The timings are load-bearing, not decoration: the 400 ms `ATZ` keeps the incoming session in
 * init while the assertion runs (shorten it and the test silently stops distinguishing the
 * mutant); SETTLE_MS is long enough for the released loop to finish its publish, far short of
 * the ATZ window.
 */
class RealVehicleDataSourceJoinTest {
    private val transcript = TranscriptParser.parseResource("transcripts/mode22-trans-temp.txt")

    @Test
    fun `a cancelled session must not repopulate readings after the new one clears them`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            repeat(REPEATS) { attempt ->
                val heldInEvent = CountDownLatch(1)
                val release = CountDownLatch(1)
                // 0105 always fails -> ReadingSkipped -> the loop parks inside onEvent, AFTER rpm
                // has been sampled but BEFORE this cycle's publish.
                val faults = mapOf("0105" to List(FAULTS) { Fault.NoData })
                val link =
                    FakeObdLink(
                        transcript,
                        commandLatency = mapOf("ATZ" to 400.milliseconds),
                        commandFaults = faults,
                    )
                val source =
                    RealVehicleDataSource(
                        link = link,
                        scope = scope,
                        config = PollConfig(cycleInterval = 50.milliseconds),
                        onEvent = { event ->
                            if (event is PollEvent.ReadingSkipped) {
                                heldInEvent.countDown()
                                release.await(TIMEOUT_S, TimeUnit.SECONDS)
                            }
                        },
                    )

                source.start(listOf(def(PidIds.RPM), def(PidIds.COOLANT)))
                check(heldInEvent.await(TIMEOUT_S, TimeUnit.SECONDS)) { "loop never reached the skip" }

                // The outgoing loop is now parked in non-suspending code with rpm in its samples.
                source.start(listOf(def(PidIds.BARO)))
                release.countDown()
                Thread.sleep(SETTLE_MS)

                assertNull(
                    "attempt $attempt: a dead session's reading survived the new session's clear",
                    source.readings.value[PidIds.RPM],
                )
                source.stop()
            }
        } finally {
            scope.cancel()
        }
    }

    /** A caller-supplied definition carrying nothing but an id — all `start` uses. */
    private fun def(id: String): PidDefinition =
        PidDefinition(
            id = id,
            label = id,
            unit = MeasurementUnit.CELSIUS,
            request = ObdRequest.StandardPid(mode = 1, pid = 0),
            parse = { WRONG_ON_PURPOSE },
            pollPriority = PollPriority.FAST,
        )

    private companion object {
        const val REPEATS = 10
        const val FAULTS = 50
        const val TIMEOUT_S = 5L
        const val SETTLE_MS = 30L
        const val WRONG_ON_PURPOSE = -999.0
    }
}
