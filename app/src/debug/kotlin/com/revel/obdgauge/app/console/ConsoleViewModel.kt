package com.revel.obdgauge.app.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revel.obdgauge.ble.BleObdLink
import com.revel.obdgauge.ble.console.ConsoleEntry
import com.revel.obdgauge.ble.console.ConsoleSession
import com.revel.obdgauge.ble.console.RememberedDeviceForgetter
import com.revel.obdgauge.model.LinkState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * OBD-19: thin Android/Hilt edge around [ConsoleSession]. Every decision (scrollback shape,
 * in-flight refusal, timeout handling) lives in `:core:ble`'s pure controller; this class only
 * adds `viewModelScope` and turns suspend calls into fire-and-forget ones a Compose click
 * handler can call directly.
 *
 * Injects the concrete [BleObdLink] rather than the [com.revel.obdgauge.model.ObdLink]
 * interface: [ConsoleSession] itself only needs the interface (that's what keeps it testable
 * against `FakeObdLink`), but this Activity-facing layer additionally needs
 * `missingPermissions` and `forgetRememberedDevice`, which are `BleObdLink`-specific and not
 * part of the frozen `ObdLink` contract.
 */
@HiltViewModel
class ConsoleViewModel
    @Inject
    constructor(
        private val bleObdLink: BleObdLink,
    ) : ViewModel() {
        private val session =
            ConsoleSession(
                link = bleObdLink,
                scope = viewModelScope,
                forgetter = RememberedDeviceForgetter { bleObdLink.forgetRememberedDevice() },
                clock = Clock.systemUTC(),
            )

        val entries: StateFlow<List<ConsoleEntry>> = session.entries
        val linkState: StateFlow<LinkState> = session.linkState
        val commandInFlight: StateFlow<Boolean> = session.commandInFlight

        /** Checked by the Activity before a connect tap: empty means connect() won't need a prompt. */
        val missingPermissions: List<String>
            get() = bleObdLink.missingPermissions

        fun sendCommand(command: String) {
            val trimmed = command.trim()
            if (trimmed.isEmpty()) {
                return
            }
            viewModelScope.launch { session.sendCommand(trimmed) }
        }

        fun connect() {
            viewModelScope.launch { session.connect() }
        }

        fun disconnect() {
            viewModelScope.launch { session.disconnect() }
        }

        fun forgetRememberedDevice() {
            viewModelScope.launch { session.forgetRememberedDevice() }
        }

        /**
         * Records a permission-denial outcome in the console log. Called by the Activity's
         * `ActivityResultContracts.RequestMultiplePermissions` callback — `:core:ble` never
         * prompts and knows nothing about that flow, so the log entry is written from here.
         */
        fun recordPermissionDenied(denied: List<String>) {
            session.recordError("Bluetooth permission denied: ${denied.joinToString()}")
        }
    }
