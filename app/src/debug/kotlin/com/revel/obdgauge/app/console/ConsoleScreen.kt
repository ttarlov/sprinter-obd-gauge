package com.revel.obdgauge.app.console

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.ble.console.ConsoleEntry
import com.revel.obdgauge.model.LinkState

private const val SPACING_DP = 8
private const val PADDING_DP = 12
private const val SPINNER_SIZE_DP = 14
private const val SPINNER_STROKE_DP = 2

/** ATZ (reset) first, since it's the one every hardware bring-up session opens with. */
internal val QUICK_COMMANDS = listOf("ATZ", "ATE0", "ATI", "0100", "010C")

/**
 * OBD-19's whole screen, as a stateless composable so a Robolectric test can drive it without a
 * `ViewModel`/Hilt/Activity in the loop — mirrors how `GaugeDashboard`/`ConnectionBanner` are
 * tested in `com.revel.obdgauge.app.gauge`.
 *
 * Deliberately self-contained (its own dark `MaterialTheme`, its own link-state banner) rather
 * than reusing anything from `com.revel.obdgauge.app.gauge` — this is debug-only bring-up
 * tooling, not a dashboard feature, and it must keep working even if the main app's UI is
 * mid-rework.
 */
@Composable
fun ConsoleScreen(
    entries: List<ConsoleEntry>,
    linkState: LinkState,
    commandInFlight: Boolean,
    onSend: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(colorScheme.background)
                    .padding(PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_DP.dp),
        ) {
            LinkStateBanner(linkState)
            Scrollback(entries, modifier = Modifier.weight(1f))
            QuickCommandChips(enabled = !commandInFlight, onSend = onSend)
            ConnectionControls(onConnect = onConnect, onDisconnect = onDisconnect, onForget = onForget)
            CommandInput(commandInFlight = commandInFlight, onSend = onSend)
        }
    }
}

@Composable
private fun LinkStateBanner(linkState: LinkState) {
    val isError = linkState is LinkState.Error
    val isBusy = linkState == LinkState.Scanning || linkState == LinkState.Connecting
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("console-link-state")
                .background(if (isError) colorScheme.errorContainer else colorScheme.surfaceVariant)
                .padding(SPACING_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.testTag("console-link-spinner").size(SPINNER_SIZE_DP.dp),
                strokeWidth = SPINNER_STROKE_DP.dp,
            )
        }
        Text(
            text = "link: ${formatLinkStateName(linkState)}",
            style = typography.bodyMedium,
            color = if (isError) colorScheme.onErrorContainer else colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("console-link-state-message"),
        )
    }
}

@Composable
private fun Scrollback(
    entries: List<ConsoleEntry>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries.lastOrNull()) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("console-scrollback")
                .background(colorScheme.surface),
    ) {
        items(entries.size) { index ->
            val entry = entries[index]
            Text(
                text = "${formatConsoleTimestamp(entry.timestamp)}  ${formatConsoleEntryBody(entry)}",
                fontFamily = FontFamily.Monospace,
                style = typography.bodySmall,
                color = colorForEntry(entry),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("console-entry-$index")
                        .padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun colorForEntry(entry: ConsoleEntry) =
    when (entry) {
        is ConsoleEntry.ErrorOccurred -> colorScheme.error
        is ConsoleEntry.LinkStateChanged -> colorScheme.tertiary
        is ConsoleEntry.CommandSent -> colorScheme.primary
        is ConsoleEntry.ResponseReceived -> colorScheme.onSurface
    }

@Composable
private fun QuickCommandChips(
    enabled: Boolean,
    onSend: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag("console-quick-commands"),
        horizontalArrangement = Arrangement.spacedBy(SPACING_DP.dp),
    ) {
        QUICK_COMMANDS.forEach { command ->
            AssistChip(
                onClick = { onSend(command) },
                enabled = enabled,
                label = { Text(command, fontFamily = FontFamily.Monospace) },
                modifier = Modifier.testTag("console-chip-$command"),
            )
        }
    }
}

@Composable
private fun ConnectionControls(
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag("console-connection-controls"),
        horizontalArrangement = Arrangement.spacedBy(SPACING_DP.dp),
    ) {
        LabeledButton("Connect", "console-connect", onConnect)
        LabeledButton("Disconnect", "console-disconnect", onDisconnect)
        LabeledButton("Forget device", "console-forget", onForget)
    }
}

@Composable
private fun RowScope.LabeledButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, modifier = Modifier.weight(1f).testTag(tag)) {
        Text(label)
    }
}

@Composable
private fun CommandInput(
    commandInFlight: Boolean,
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth().testTag("console-input-row"),
        horizontalArrangement = Arrangement.spacedBy(SPACING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val send = {
            if (!commandInFlight && input.isNotBlank()) {
                onSend(input)
                input = ""
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            enabled = !commandInFlight,
            singleLine = true,
            textStyle = typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            label = { Text("AT command") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f).testTag("console-input"),
        )
        Button(
            onClick = send,
            enabled = !commandInFlight && input.isNotBlank(),
            modifier = Modifier.testTag("console-send"),
        ) {
            Text("Send")
        }
    }
}
