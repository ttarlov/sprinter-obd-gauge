package com.revel.obdgauge.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OBD-71: the UI-facing handle onto [ObdConnectionService]'s [EngineOffPromptController] — the
 * same "a Hilt `@Singleton` is the same fact with none of the binder machinery" shape
 * `com.revel.obdgauge.app.recording.RecordingBridge` already uses for the analogous "can a
 * ViewModel talk to the service" problem (see its KDoc).
 *
 * `DashboardViewModel` depends on this, never on [EngineOffPromptController]/`ObdConnectionService`
 * directly: it reads [state] to render the "Engine off — keep monitoring?" dialog and calls
 * [keepMonitoring] for its button, and doesn't need to know whether the service has finished
 * starting up yet — both are silent no-ops against a not-yet-[attach]ed bridge, same gap
 * `RecordingBridge`'s own callers tolerate.
 */
@Singleton
class EngineOffBridge
    @Inject
    constructor() {
        private val mutableState = MutableStateFlow<EngineOffAction>(EngineOffAction.None)

        /** What the dashboard renders — [EngineOffAction.ShowPrompt] means "show the dialog." */
        val state: StateFlow<EngineOffAction> = mutableState.asStateFlow()

        @Volatile
        private var activeController: EngineOffPromptController? = null

        /** Called only by `ObdConnectionService.onCreate`/`onDestroy` — `null` clears the dialog. */
        internal fun attach(controller: EngineOffPromptController?) {
            activeController = controller
            if (controller == null) mutableState.value = EngineOffAction.None
        }

        /** The dialog's "Keep monitoring" tap. */
        fun keepMonitoring() {
            activeController?.keepMonitoringTapped()
        }

        /** [ObdConnectionService]'s watchdog tick — publishes every phase transition. */
        internal fun publish(action: EngineOffAction) {
            mutableState.value = action
        }
    }
