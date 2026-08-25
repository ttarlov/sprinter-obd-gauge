package com.revel.obdgauge.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OBD-70: the UI-facing handle onto whichever [Recorder] the (started, not bound)
 * `ObdConnectionService` currently owns — the same "a Hilt `@Singleton` is the same fact with
 * none of the binder machinery" shape [com.revel.obdgauge.app.service.PollKeepAlive] already
 * uses for the analogous "can a ViewModel talk to the service" problem.
 *
 * `DashboardViewModel` depends on this, never on `Recorder`/`ObdConnectionService` directly: it
 * calls [start]/[stop] and reads [state], and doesn't need to know whether the service (and
 * therefore a real [Recorder]) has finished starting up yet — [start]/[stop] are silent no-ops
 * against a not-yet-[attach]ed bridge, same "app just launched, tap landed a frame early" gap
 * `PollKeepAlive`'s own callers tolerate elsewhere in this app.
 */
@Singleton
class RecordingBridge
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)

        /** What the dashboard's Record control renders. */
        val state: StateFlow<RecordingState> = mutableState.asStateFlow()

        @Volatile
        private var activeRecorder: Recorder? = null

        /** Called only by `ObdConnectionService.onCreate`/`onDestroy` — `null` tears down the link. */
        internal fun attach(recorder: Recorder?) {
            activeRecorder = recorder
            if (recorder == null) mutableState.value = RecordingState.Idle
        }

        /** The Record button's confirmed-start action. */
        fun start() {
            activeRecorder?.start()
        }

        /** The recording indicator's one-tap stop action. */
        fun stop() {
            activeRecorder?.stop()
        }

        /** [Recorder]'s own state-changed callback — publishes every start/tick/stop transition. */
        internal fun publish(newState: RecordingState) {
            mutableState.value = newState
        }
    }
