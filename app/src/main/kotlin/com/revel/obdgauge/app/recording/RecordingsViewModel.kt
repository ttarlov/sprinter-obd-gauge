package com.revel.obdgauge.app.recording

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

/** [SessionIndexEntry] plus its live on-disk size — see [SessionIndexEntry]'s KDoc for why size
 * isn't itself persisted in the index. */
data class RecordingSummary(
    val entry: SessionIndexEntry,
    val sizeBytes: Long,
)

/**
 * OBD-70 Part C: Hilt/ViewModel-wired entry point behind [RecordingsScreen], the same split
 * `SettingsViewModel`/`SettingsScreen` use. Reads/writes `logsDir/index.json` directly (plain
 * file IO on a ViewModel — no repository layer exists for this feature, and one row of state
 * doesn't warrant inventing one) and shares the exact same [logsDir] definition
 * `ObdConnectionService` writes to, so the two can never disagree about where sessions live.
 */
@HiltViewModel
class RecordingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val logsDirectory = logsDir(context)
        private val mutableSessions = MutableStateFlow<List<RecordingSummary>>(emptyList())

        /** Newest-first, per this issue's spec. */
        val sessions: StateFlow<List<RecordingSummary>> = mutableSessions.asStateFlow()

        init {
            refresh()
        }

        /** Re-reads the index off disk — called on entry to the screen, since a new recording
         * (or a USB-side deletion) may have happened since this ViewModel was last active. */
        fun refresh() {
            mutableSessions.value =
                readSessionIndex(logsDirectory)
                    .sortedByDescending { it.startedAt }
                    .map { entry -> RecordingSummary(entry, File(logsDirectory, entry.file).length()) }
        }

        /** Builds the share chooser and launches it — [context] is the caller's (Activity)
         * context, so the chooser opens in the right task, not detached via the app context. */
        fun share(
            launchContext: Context,
            summary: RecordingSummary,
        ) {
            val file = File(logsDirectory, summary.entry.file)
            val intent = Intent.createChooser(buildShareIntent(launchContext, file), "Share recording")
            launchContext.startActivity(intent)
        }

        fun delete(summary: RecordingSummary) {
            File(logsDirectory, summary.entry.file).delete()
            // Round-1 review (finding 2): goes through the shared updateSessionIndex choke point,
            // not a hand-rolled read-modify-write — Recorder's start/stop/error-finalize can run
            // concurrently on the service thread while this screen is open, and a bare
            // readSessionIndex→writeSessionIndex pair here could lose whichever wrote second.
            updateSessionIndex(logsDirectory) { existing -> existing.filterNot { it.file == summary.entry.file } }
            refresh()
        }
    }

/**
 * Wires [RecordingsViewModel] for `SettingsRoute`'s nested "Recordings" destination — same shape
 * as `SettingsRoute` itself.
 */
@Composable
fun RecordingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecordingsViewModel = viewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // A new recording (or a USB-side delete) may have happened since this ViewModel was last
    // active — re-read the index every time this destination is (re)entered, not just once.
    LaunchedEffect(Unit) { viewModel.refresh() }
    RecordingsScreen(
        sessions = sessions,
        onShare = { summary -> viewModel.share(context, summary) },
        onDelete = viewModel::delete,
        onBack = onBack,
        modifier = modifier,
    )
}
