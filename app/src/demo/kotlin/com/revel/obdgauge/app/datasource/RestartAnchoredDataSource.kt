package com.revel.obdgauge.app.datasource

import com.revel.obdgauge.model.PidDefinition
import com.revel.obdgauge.model.VehicleDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Demo-flavor decorator that records the wall-clock instant of every [start] call, so a
 * [Clock] can stay aligned with [com.revel.obdgauge.testing.datasource.FakeVehicleDataSource]'s
 * virtual timeline — which resets to `Instant.EPOCH` on every `start()` (per the
 * [VehicleDataSource] restart contract). `WhileSubscribed` wiring in the ViewModel restarts
 * the source on every background/foreground round-trip, so a one-shot anchor drifts; this
 * one re-anchors with it (OBD-11 review round-1 M1).
 */
class RestartAnchoredDataSource(
    private val delegate: VehicleDataSource,
) : VehicleDataSource by delegate {
    @Volatile
    private var startedAt: Instant = Instant.now()

    override fun start(pids: List<PidDefinition>) {
        startedAt = Instant.now()
        delegate.start(pids)
    }

    /**
     * A clock reading `EPOCH + (wall-clock elapsed since the most recent [start])` — i.e. the
     * same "now" the fake's virtual timestamps assume, no matter how many times the source
     * has been restarted.
     */
    fun epochSinceStartClock(): Clock = EpochSinceStartClock(this)

    private class EpochSinceStartClock(
        private val source: RestartAnchoredDataSource,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.EPOCH.plus(Duration.between(source.startedAt, Instant.now()))
    }
}
