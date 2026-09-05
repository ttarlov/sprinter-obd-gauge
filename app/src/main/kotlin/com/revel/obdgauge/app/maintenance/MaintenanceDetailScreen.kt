package com.revel.obdgauge.app.maintenance

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.revel.obdgauge.app.ui.theme.GaugeNeedleAccent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val SECTION_SPACING_DP = 16
private const val ROW_SPACING_DP = 8
private const val FIELD_BORDER_WIDTH_DP = 1
private const val FIELD_PADDING_DP = 6
private const val FIELD_WIDTH_DP = 120
private const val WIDE_FIELD_WIDTH_DP = 260

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

/**
 * OBD-79's Maintenance detail screen: edit one [ServiceItem]'s catalog fields, log a new service
 * event against it, and review its [MaintenanceRecord] history. Stateless, same split as
 * [MaintenanceScreen]; [MaintenanceDetailRoute] wires the real [MaintenanceViewModel].
 */
@Composable
@Suppress("LongParameterList") // stateless screen: one param/callback per independently-testable piece.
fun MaintenanceDetailScreen(
    state: MaintenanceDetailUiState,
    onSave: (ServiceItem) -> Unit,
    onLogService: (date: LocalDate, odometerMiles: Int, notes: String?, costCents: Int?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("maintenance-detail-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(SECTION_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("maintenance-detail-back-button")) {
                    Text("< Back")
                }
                Text(text = state.item.name, style = MaterialTheme.typography.headlineSmall)
            }

            EditableItemFields(state.item, onSave)
            HorizontalDivider()
            LogServiceForm(state.currentOdometerMiles, onLogService)
            HorizontalDivider()
            RecordHistory(state.records)
        }
    }
}

@Composable
private fun EditableItemFields(
    item: ServiceItem,
    onSave: (ServiceItem) -> Unit,
) {
    var intervalMiles by remember(item.id) { mutableStateOf(item.intervalMiles?.toString().orEmpty()) }
    var intervalMonths by remember(item.id) { mutableStateOf(item.intervalMonths?.toString().orEmpty()) }
    var partNumbers by remember(item.id) { mutableStateOf(item.partNumbers.joinToString(", ")) }
    var specNotes by remember(item.id) { mutableStateOf(item.specNotes) }

    fun commit(next: ServiceItem) = onSave(next)

    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Enabled", modifier = Modifier.weight(1f))
            Switch(
                checked = item.enabled,
                onCheckedChange = { checked -> commit(item.copy(enabled = checked)) },
                modifier = Modifier.testTag("maintenance-enabled-switch"),
            )
        }
        LabeledField("Interval (miles)", intervalMiles, wide = false, testTag = "maintenance-interval-miles") { text ->
            intervalMiles = text
            commit(item.copy(intervalMiles = text.toIntOrNull()))
        }
        LabeledField(
            label = "Interval (months)",
            value = intervalMonths,
            wide = false,
            testTag = "maintenance-interval-months",
        ) { text ->
            intervalMonths = text
            commit(item.copy(intervalMonths = text.toIntOrNull()))
        }
        LabeledField("Part numbers", partNumbers, wide = true, testTag = "maintenance-part-numbers") { text ->
            partNumbers = text
            commit(item.copy(partNumbers = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
        }
        LabeledField("Spec notes", specNotes, wide = true, testTag = "maintenance-spec-notes") { text ->
            specNotes = text
            commit(item.copy(specNotes = text))
        }
    }
}

/**
 * One editable text field, matching `settings/SettingsScreen.kt`'s `ThresholdField`
 * hand-rolled-[BasicTextField] idiom rather than Material3's `OutlinedTextField`. [wide] picks
 * [WIDE_FIELD_WIDTH_DP] (part numbers/spec notes/notes — free text that needs room) vs
 * [FIELD_WIDTH_DP] (a short number/date).
 */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    wide: Boolean,
    testTag: String,
    onCommit: (String) -> Unit,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        val textColor = MaterialTheme.colorScheme.onBackground
        BasicTextField(
            value = value,
            onValueChange = onCommit,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            cursorBrush = SolidColor(textColor),
            modifier =
                Modifier
                    .testTag(testTag)
                    .width((if (wide) WIDE_FIELD_WIDTH_DP else FIELD_WIDTH_DP).dp)
                    .border(FIELD_BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.outline)
                    .padding(FIELD_PADDING_DP.dp),
        )
    }
}

@Composable
private fun LogServiceForm(
    currentOdometerMiles: Int,
    onLogService: (date: LocalDate, odometerMiles: Int, notes: String?, costCents: Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    if (!expanded) {
        TextButton(onClick = { expanded = true }, modifier = Modifier.testTag("log-service-open-button")) {
            Text("+ Log service")
        }
        return
    }

    var dateText by remember { mutableStateOf(DATE_FORMATTER.format(LocalDate.now())) }
    var odometerText by remember { mutableStateOf(currentOdometerMiles.toString()) }
    var notesText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Text(text = "Log service", style = MaterialTheme.typography.titleMedium)
        LabeledField("Date (YYYY-MM-DD)", dateText, wide = false, testTag = "log-service-date") { dateText = it }
        LabeledField(
            label = "Odometer (mi)",
            value = odometerText,
            wide = false,
            testTag = "log-service-odometer",
        ) { odometerText = it }
        LabeledField("Notes", notesText, wide = true, testTag = "log-service-notes") { notesText = it }
        LabeledField("Cost (USD, optional)", costText, wide = false, testTag = "log-service-cost") { costText = it }
        TextButton(
            onClick = {
                val date = parseDateOrNull(dateText) ?: LocalDate.now()
                val odometer = odometerText.toIntOrNull() ?: currentOdometerMiles
                val costCents = costText.toDoubleOrNull()?.let { dollars -> (dollars * CENTS_PER_DOLLAR).toInt() }
                onLogService(date, odometer, notesText.ifBlank { null }, costCents)
                expanded = false
            },
            modifier = Modifier.testTag("log-service-save-button"),
        ) {
            Text("Save", color = GaugeNeedleAccent)
        }
    }
}

private const val CENTS_PER_DOLLAR = 100

// runCatching, matching SettingsCodec's decodeUnit/decodePollRate idiom: a malformed date field
// falls back to today rather than throwing mid-composition.
private fun parseDateOrNull(text: String): LocalDate? =
    runCatching { LocalDate.parse(text, DATE_FORMATTER) }.getOrNull()

@Composable
private fun RecordHistory(records: List<MaintenanceRecord>) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Text(text = "History", style = MaterialTheme.typography.titleMedium)
        if (records.isEmpty()) {
            Text(
                text = "No service logged yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("maintenance-history-empty"),
            )
        }
        records.forEach { record ->
            Row(modifier = Modifier.fillMaxWidth().testTag("maintenance-record-${record.id}")) {
                Text(text = DATE_FORMATTER.format(LocalDate.ofEpochDay(record.performedDateEpochDay)))
                Text(text = " · ${String.format(Locale.US, "%,d", record.odometerMiles)} mi")
                record.notes?.let { notes -> Text(text = " · $notes") }
            }
        }
    }
}
