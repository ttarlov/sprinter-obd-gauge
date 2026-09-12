package com.revel.obdgauge.app.gauge

import android.os.SystemClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.revel.obdgauge.app.service.EngineOffAction
import kotlinx.coroutines.delay

/**
 * OBD-71: the "Engine off — keep monitoring?" prompt — the present-user branch of
 * `EngineOffPromptController`. Plain [AlertDialog], matching this codebase's Material3 dialog
 * usage elsewhere ([RecordConfirmDialog] / `RecordingsScreen`'s delete-confirm). No dismiss button
 * and `onDismissRequest = {}` deliberately: the countdown itself IS the "no response" path — a
 * back-button/outside-tap must not silently cancel monitoring without the explicit choice the
 * design calls for (Taras wants a real "Keep monitoring" tap, not an accidental swipe/back-press).
 */
@Composable
fun EngineOffPromptDialog(
    remainingSeconds: Int,
    onKeepMonitoring: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = {},
        modifier = modifier.testTag("engine-off-prompt-dialog"),
        title = { Text("Engine off — keep monitoring?") },
        text = { Text("Stopping in ${remainingSeconds}s unless you keep monitoring.") },
        confirmButton = {
            TextButton(onClick = onKeepMonitoring, modifier = Modifier.testTag("engine-off-keep-monitoring")) {
                Text("Keep monitoring")
            }
        },
    )
}

/**
 * OBD-71: `MainActivity`'s ticking wrapper — reduces the service's fixed
 * [EngineOffAction.ShowPrompt] deadline (`SystemClock.elapsedRealtime()` domain, matching
 * `IdleWatchdog`'s clock choice) to the whole-second countdown [EngineOffPromptDialog] renders,
 * recomposing once a second while shown. Kept separate from the dialog itself so the dialog stays
 * a pure function of an `Int` — trivially snapshot-testable with a fixed value, no clock inside it.
 * A no-op (renders nothing) for every [EngineOffAction] other than [EngineOffAction.ShowPrompt].
 */
@Composable
fun EngineOffPromptHost(
    action: EngineOffAction,
    onKeepMonitoring: () -> Unit,
) {
    val prompt = action as? EngineOffAction.ShowPrompt ?: return
    var remainingSeconds by remember(prompt.deadlineMillis) {
        mutableIntStateOf(remainingSecondsUntil(prompt.deadlineMillis, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(prompt.deadlineMillis) {
        while (remainingSeconds > 0) {
            delay(COUNTDOWN_TICK_MILLIS)
            remainingSeconds = remainingSecondsUntil(prompt.deadlineMillis, SystemClock.elapsedRealtime())
        }
    }
    EngineOffPromptDialog(remainingSeconds = remainingSeconds, onKeepMonitoring = onKeepMonitoring)
}

/** Pure: whole seconds from [nowMillis] to [deadlineMillis], floored at 0 (never negative). */
internal fun remainingSecondsUntil(
    deadlineMillis: Long,
    nowMillis: Long,
): Int = ((deadlineMillis - nowMillis) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)

private const val MILLIS_PER_SECOND = 1000L
private const val COUNTDOWN_TICK_MILLIS = 1000L
