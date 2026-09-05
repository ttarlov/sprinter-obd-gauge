package com.revel.obdgauge.app.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revel.obdgauge.app.ui.theme.GaugeAmber
import com.revel.obdgauge.app.ui.theme.GaugeGreen
import com.revel.obdgauge.app.ui.theme.GaugeRed
import com.revel.obdgauge.app.ui.theme.MaintenanceNeutralChip
import com.revel.obdgauge.app.ui.theme.MaintenancePanelBackground
import com.revel.obdgauge.app.ui.theme.MaintenanceTeal
import java.util.Locale
import kotlin.math.roundToInt

private const val SECTION_SPACING_DP = 16
private const val ROW_SPACING_DP = 10
private const val ODOMETER_DIGITS_SP = 30
private const val PANEL_CORNER_RADIUS_DP = 12
private const val PANEL_BORDER_WIDTH_DP = 1
private const val PANEL_PADDING_DP = 14
private const val CHIP_CORNER_RADIUS_DP = 8
private const val CHIP_BORDER_WIDTH_DP = 1
private const val CHIP_H_PADDING_DP = 10
private const val CHIP_V_PADDING_DP = 6
private const val CHIP_FILL_ALPHA = 0.16f
private const val CATEGORY_LETTER_SPACING_SP = 1.5

// Days-per-month approximation used only for display (rounding a day-count into "N mo") — the
// underlying interval math (MaintenanceStatus) works in exact calendar months, this is purely a
// countdown label's number, never fed back into a threshold decision.
private const val DAYS_PER_MONTH_APPROX = 30.44

private val CATEGORY_LABELS =
    mapOf(
        MaintenanceCategory.ROUTINE to "Routine",
        MaintenanceCategory.SCHEDULED to "Scheduled",
        MaintenanceCategory.DRIVELINE to "Driveline",
        MaintenanceCategory.CONSUMABLE to "Consumable",
        MaintenanceCategory.KNOWN_ISSUE to "Known issue",
    )

/**
 * OBD-79's Maintenance list — stateless (state in, event callbacks out), same "test the screen,
 * not the Activity" split `SettingsScreen`/`DashboardScreen` already use. [MaintenanceRoute] wires
 * the real [MaintenanceViewModel] for `MainActivity`.
 */
@Composable
fun MaintenanceScreen(
    state: MaintenanceListUiState,
    onSetOdometer: (Int) -> Unit,
    onItemClick: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("maintenance-screen"),
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
                TextButton(onClick = onBack, modifier = Modifier.testTag("maintenance-back-button")) {
                    Text("< Back")
                }
                Text(text = "Maintenance", style = MaterialTheme.typography.headlineSmall)
            }

            val odometerMiles = (state as? MaintenanceListUiState.Loaded)?.currentOdometerMiles ?: 0
            OdometerReadout(odometerMiles, onSetOdometer)

            when (state) {
                is MaintenanceListUiState.Loading -> Unit
                is MaintenanceListUiState.Loaded ->
                    state.rows.groupBy { it.item.category }.forEach { (category, rows) ->
                        CategorySection(category, rows, onItemClick)
                    }
            }
        }
    }
}

/**
 * The list screen's signature device: a recessed "trip meter" panel — the current manual odometer
 * anchor rendered in the same bold gauge-digit weight the dashboard's own tiles use, framed by a
 * teal border/label rather than the dashboard's threshold colors (this number is never itself a
 * pass/fail reading). Tap "Set →" to open an inline editable field; commit with the checkmark.
 */
@Composable
private fun OdometerReadout(
    currentMiles: Int,
    onSetOdometer: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(currentMiles, editing) { mutableStateOf(currentMiles.toString()) }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaintenancePanelBackground, RoundedCornerShape(PANEL_CORNER_RADIUS_DP.dp))
                .border(PANEL_BORDER_WIDTH_DP.dp, MaintenanceTeal, RoundedCornerShape(PANEL_CORNER_RADIUS_DP.dp))
                .padding(PANEL_PADDING_DP.dp)
                .testTag("odometer-readout"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ODO",
                style = MaterialTheme.typography.labelSmall,
                color = MaintenanceTeal,
            )
            if (editing) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontSize = ODOMETER_DIGITS_SP.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onBackground),
                    singleLine = true,
                    modifier = Modifier.testTag("odometer-field"),
                )
            } else {
                Text(
                    text = "${formatCount(currentMiles)} mi",
                    fontSize = ODOMETER_DIGITS_SP.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (editing) {
            TextButton(
                onClick = {
                    text.toIntOrNull()?.let(onSetOdometer)
                    editing = false
                },
                modifier = Modifier.testTag("odometer-commit-button"),
            ) { Text("✓ Save") }
        } else {
            TextButton(onClick = { editing = true }, modifier = Modifier.testTag("odometer-set-button")) {
                Text("Set →")
            }
        }
    }
}

@Composable
private fun CategorySection(
    category: MaintenanceCategory,
    rows: List<MaintenanceListRow>,
    onItemClick: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Text(
            text = (CATEGORY_LABELS[category] ?: category.name).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = CATEGORY_LETTER_SPACING_SP.sp),
            color = MaintenanceTeal,
        )
        rows.forEach { row -> ServiceItemRow(row, onItemClick) }
    }
}

@Composable
private fun ServiceItemRow(
    row: MaintenanceListRow,
    onItemClick: (Long) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("maintenance-item-${row.item.id}")
                .clickable { onItemClick(row.item.id) }
                .padding(vertical = CHIP_V_PADDING_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = row.item.name, modifier = Modifier.weight(1f))
        StatusChip(row.status)
    }
}

@Composable
private fun StatusChip(status: MaintenanceStatus) {
    val color = statusColor(status.level)
    Text(
        text = countdownText(status),
        color = color,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .testTag("maintenance-status-chip")
                .background(color.copy(alpha = CHIP_FILL_ALPHA), RoundedCornerShape(CHIP_CORNER_RADIUS_DP.dp))
                .border(CHIP_BORDER_WIDTH_DP.dp, color, RoundedCornerShape(CHIP_CORNER_RADIUS_DP.dp))
                .padding(horizontal = CHIP_H_PADDING_DP.dp, vertical = CHIP_V_PADDING_DP.dp),
    )
}

private fun statusColor(level: MaintenanceStatusLevel): Color =
    when (level) {
        MaintenanceStatusLevel.OK -> GaugeGreen
        MaintenanceStatusLevel.DUE_SOON -> GaugeAmber
        MaintenanceStatusLevel.OVERDUE -> GaugeRed
        MaintenanceStatusLevel.NEVER_SERVICED, MaintenanceStatusLevel.DISABLED -> MaintenanceNeutralChip
    }

/** The chip's label — see this file's own KDoc examples in `issues/OBD-79.md` ("3,200 mi / 4 mo"). */
internal fun countdownText(status: MaintenanceStatus): String =
    when (status.level) {
        MaintenanceStatusLevel.NEVER_SERVICED -> "log one"
        MaintenanceStatusLevel.DISABLED -> "disabled"
        else -> {
            val parts = mutableListOf<String>()
            status.milesUntil?.let { miles ->
                parts += if (miles <= 0) "overdue ${formatCount(-miles)} mi" else "${formatCount(miles)} mi"
            }
            status.daysUntil?.let { days ->
                parts +=
                    if (days <= 0) {
                        "overdue"
                    } else {
                        "${(days / DAYS_PER_MONTH_APPROX).roundToInt().coerceAtLeast(1)} mo"
                    }
            }
            parts.ifEmpty { listOf("OK") }.joinToString(" / ")
        }
    }

// Locale.US always, matching this codebase's discipline elsewhere (RecordingsFormatting.kt,
// CsvEngine.kt) — a comma-grouped mileage number must never silently become a decimal-comma one.
private fun formatCount(value: Int): String = String.format(Locale.US, "%,d", value)
