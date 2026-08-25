package com.revel.obdgauge.app.recording

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private const val SECTION_SPACING_DP = 16
private const val ROW_SPACING_DP = 8

/**
 * OBD-70 Part C: the Recordings list, reached from Settings. Stateless (a plain list in, event
 * callbacks out) so it's testable without a `ViewModel`/Hilt/Context — same "test the screen, not
 * the Activity" split `SettingsScreen`/`DashboardScreen` already use. [RecordingsRoute] wires the
 * real [RecordingsViewModel] for `MainActivity`'s Settings flow.
 *
 * @param sessions newest-first (the caller sorts — see [RecordingsViewModel.sessions]).
 */
@Composable
fun RecordingsScreen(
    sessions: List<RecordingSummary>,
    onShare: (RecordingSummary) -> Unit,
    onDelete: (RecordingSummary) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("recordings-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(SECTION_SPACING_DP.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("recordings-back-button")) {
                    Text("< Back")
                }
                Text(text = "Recordings", style = MaterialTheme.typography.headlineSmall)
            }
            if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No recordings yet — tap Record on the dashboard to start one.",
                        modifier = Modifier.testTag("recordings-empty"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("recordings-list"),
                    verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp),
                ) {
                    items(sessions, key = { it.entry.file }) { session ->
                        Column {
                            RecordingRow(session, onShare = { onShare(session) }, onDelete = { onDelete(session) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(
    session: RecordingSummary,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val rowModifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = ROW_SPACING_DP.dp)
            .testTag("recording-row-${session.entry.file}")
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = formatSessionDate(session.entry.startedAt), style = MaterialTheme.typography.bodyLarge)
            Text(
                text =
                    "${formatSessionDuration(session.entry.startedAt, session.entry.endedAt)} · " +
                        "${formatFileSize(session.sizeBytes)} · ${session.entry.pidCount} PIDs",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onShare, modifier = Modifier.testTag("recording-share-${session.entry.file}")) {
            Text("Share")
        }
        TextButton(
            onClick = { showDeleteConfirm = true },
            modifier = Modifier.testTag("recording-delete-${session.entry.file}"),
        ) {
            Text("Delete")
        }
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this recording?") },
            text = { Text("This removes the CSV from the device. This can't be undone.") },
            modifier = Modifier.testTag("recording-delete-confirm-dialog"),
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    modifier = Modifier.testTag("recording-delete-confirm"),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.testTag("recording-delete-cancel"),
                ) { Text("Cancel") }
            },
        )
    }
}
