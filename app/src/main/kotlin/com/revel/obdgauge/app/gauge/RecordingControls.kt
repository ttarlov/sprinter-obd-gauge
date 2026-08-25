package com.revel.obdgauge.app.gauge

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.recording.RecordingState
import com.revel.obdgauge.app.recording.formatElapsedRecording
import com.revel.obdgauge.app.ui.theme.GaugeRed

// OBD-70: the dashboard top row's Record control — the ⏺ glyph matches this codebase's
// icon-free style (SETTINGS_GLYPH's own "⚙", DashboardScreen.kt).
private const val RECORD_GLYPH = "⏺"
private const val RECORD_DOT_SIZE_DP = 10
private const val RECORD_DOT_SPACING_DP = 6
private const val RECORD_DOT_MIN_ALPHA = 0.35f
private const val RECORD_DOT_MAX_ALPHA = 1f

/**
 * OBD-70's Record button/live-indicator, one composable over both [RecordingState] arms so the
 * top row always has exactly one control in this slot (no layout-width jump between "Rec" and a
 * running timer). [elapsedMillis] is precomputed by the caller ([GaugeDashboard]) from its own
 * ticking clock — this composable never reads the wall clock itself, so it stays deterministic
 * for Roborazzi (pass a fixed value and it renders exactly that, every time).
 */
@Composable
fun RecordControl(
    state: RecordingState,
    elapsedMillis: Long,
    onTapIdle: () -> Unit,
    onTapRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is RecordingState.Idle ->
            TextButton(onClick = onTapIdle, modifier = modifier.testTag("record-button")) {
                Text(text = "$RECORD_GLYPH Rec", style = MaterialTheme.typography.titleMedium)
            }
        is RecordingState.Recording ->
            TextButton(onClick = onTapRecording, modifier = modifier.testTag("recording-indicator")) {
                RecordingDot()
                Spacer(modifier = Modifier.width(RECORD_DOT_SPACING_DP.dp))
                Text(text = formatElapsedRecording(elapsedMillis), style = MaterialTheme.typography.titleMedium)
            }
    }
}

/**
 * OBD-66's danger-zone pulse idiom, reused verbatim (same period, same "only animate when
 * [LocalDangerPulseEnabled] allows it" test seam) for a small solid dot instead of a tile
 * background — a running recording is exactly the kind of "needs a glance, not a stare" state
 * that pulse already exists for.
 */
@Composable
private fun RecordingDot() {
    val alpha =
        if (LocalDangerPulseEnabled.current) {
            val transition = rememberInfiniteTransition(label = "record-pulse")
            val animated by
                transition.animateFloat(
                    initialValue = RECORD_DOT_MIN_ALPHA,
                    targetValue = RECORD_DOT_MAX_ALPHA,
                    animationSpec = infiniteRepeatable(tween(DANGER_PULSE_PERIOD_MS), RepeatMode.Reverse),
                    label = "record-pulse-alpha",
                )
            animated
        } else {
            RECORD_DOT_MAX_ALPHA
        }
    val dotModifier =
        Modifier
            .size(RECORD_DOT_SIZE_DP.dp)
            .background(GaugeRed.copy(alpha = alpha), CircleShape)
    Box(modifier = dotModifier)
}

/**
 * OBD-70: the accidental-trigger guard — only [start][com.revel.obdgauge.app.gauge.RecordControl]
 * is confirmed; stop is one tap (spec). Plain [AlertDialog], matching this codebase's Material3
 * dialog usage elsewhere (`RecordingsScreen`'s own delete-confirm).
 */
@Composable
fun RecordConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag("record-confirm-dialog"),
        title = { Text("Start recording?") },
        text = {
            Text(
                "Logs every mapped PID once per second to a CSV on this device until you tap " +
                    "Stop. Gauges may refresh a bit slower while a recording is active.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("record-confirm-start")) {
                Text("Start")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("record-confirm-cancel")) {
                Text("Cancel")
            }
        },
    )
}
