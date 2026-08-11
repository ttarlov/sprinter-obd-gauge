package com.revel.obdgauge.app.console

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint

/**
 * OBD-19: the debug-build-only "OBD Console" launcher entry. Presentation glue only — every
 * decision (scrollback shape, in-flight policy, timeout handling) lives in `:core:ble`'s
 * `ConsoleSession`, reached through [ConsoleViewModel].
 *
 * ### Permission flow
 * `:core:ble` never requests permissions itself (`BleObdLink.missingPermissions` only
 * *reports*; see core/ble/MODULE.md's "Permissions" section) — prompting is `:app`'s job, and
 * this Activity is that job. A connect tap checks [ConsoleViewModel.missingPermissions] first:
 * if empty, it connects directly; otherwise it requests exactly the missing permissions via the
 * Activity Result API and only connects on grant. A denial is logged to the console (never a
 * crash, never a silent no-op) via [ConsoleViewModel.recordPermissionDenied].
 */
@AndroidEntryPoint
class ConsoleActivity : ComponentActivity() {
    private val viewModel: ConsoleViewModel by viewModels()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grantResults ->
            if (grantResults.values.all { granted -> granted }) {
                viewModel.connect()
            } else {
                val denied = grantResults.filterValues { granted -> !granted }.keys.toList()
                viewModel.recordPermissionDenied(denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val entries by viewModel.entries.collectAsStateWithLifecycle()
            val linkState by viewModel.linkState.collectAsStateWithLifecycle()
            val commandInFlight by viewModel.commandInFlight.collectAsStateWithLifecycle()

            ConsoleScreen(
                entries = entries,
                linkState = linkState,
                commandInFlight = commandInFlight,
                onSend = viewModel::sendCommand,
                onConnect = ::requestConnect,
                onDisconnect = viewModel::disconnect,
                onForget = viewModel::forgetRememberedDevice,
            )
        }
    }

    /** Requests only what's missing, then connects — see this class's KDoc for the full flow. */
    private fun requestConnect() {
        val missing = viewModel.missingPermissions
        if (missing.isEmpty()) {
            viewModel.connect()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }
}
