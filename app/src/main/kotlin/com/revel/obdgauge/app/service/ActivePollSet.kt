package com.revel.obdgauge.app.service

import com.revel.obdgauge.app.gauge.GAUGE_CATALOG
import com.revel.obdgauge.app.recording.pollUnion
import com.revel.obdgauge.model.PidDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OBD-70: the single source of truth for "which PIDs should the shared
 * [com.revel.obdgauge.model.VehicleDataSource] be polling right now" — [GAUGE_CATALOG] (the
 * dashboard's own 6 channels) unioned with whatever a recording session currently wants (empty
 * when nothing is recording).
 *
 * ## The coordination hazard this settles
 *
 * `DashboardViewModel.uiState`'s `onStart` and `ConnectionServiceController.start`/its Ready-edge
 * restart both call `dataSource.start(pids)` — pre-OBD-70 they both hardcoded [GAUGE_CATALOG].
 * OBD-70 adds a THIRD caller (`Recorder`, widening to every mapped PID while a session is
 * active), and per this issue's spec neither of the other two may be allowed to "win" and narrow
 * the poll set back down mid-recording — e.g. the dashboard's own `WhileSubscribed` teardown/
 * resubscribe cycle (screen off then on again) must not silently drop the recorder's extra
 * columns just because it re-issued `start(GAUGE_CATALOG)` on the old hardcoded literal.
 *
 * The fix, mirroring [PollKeepAlive]'s "one published fact, not three independent guesses" shape:
 * every `start()` call site reads [activePids] instead of a literal, so whichever caller happens
 * to fire next always requests the CURRENT union, recording or not. [Recorder] is the only writer
 * of the recording half ([setRecordingPids], `internal` — called once on start with `loggablePids`
 * and once on stop with an empty list, both of which explicitly reissue `start(activePids())`
 * themselves so the revert takes effect immediately rather than waiting for some other caller to
 * happen to resubscribe).
 */
@Singleton
class ActivePollSet
    @Inject
    constructor() {
        private val mutableRecordingPids = MutableStateFlow<List<PidDefinition>>(emptyList())

        /** What the active recording session (if any) additionally wants polled. */
        val recordingPids: StateFlow<List<PidDefinition>> = mutableRecordingPids.asStateFlow()

        /** Called only by [com.revel.obdgauge.app.recording.Recorder] on start/stop. */
        internal fun setRecordingPids(pids: List<PidDefinition>) {
            mutableRecordingPids.value = pids
        }

        /** [GAUGE_CATALOG] ∪ [recordingPids] right now — what every `start(pids)` caller should pass. */
        fun activePids(): List<PidDefinition> = pollUnion(GAUGE_CATALOG, mutableRecordingPids.value)
    }
