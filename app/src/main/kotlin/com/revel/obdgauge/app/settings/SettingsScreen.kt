package com.revel.obdgauge.app.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.revel.obdgauge.app.gauge.DASHBOARD_PIDS_BY_ID
import com.revel.obdgauge.app.gauge.GAUGE_CATALOG_BY_ID
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.app.gauge.UnitConversion
import com.revel.obdgauge.app.gauge.displayUnitFor
import com.revel.obdgauge.app.recording.RecordingsRoute
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds

private val THRESHOLD_GAUGE_IDS = listOf(PidIds.COOLANT, PidIds.TRANS_TEMP, PidIds.OIL_TEMP)
private const val SECTION_SPACING_DP = 16
private const val ROW_SPACING_DP = 8

/**
 * OBD-21 settings screen. Stateless (a plain `AppSettings` in, event callbacks out) so it's
 * testable without a `ViewModel`/Hilt/Activity in the loop — same "test the screen, not the
 * Activity" split `DashboardScreen.kt`/`ConsoleScreen.kt` already use. [SettingsRoute] below
 * wires the real [SettingsViewModel] for [com.revel.obdgauge.app.MainActivity].
 */
@Composable
@Suppress("LongParameterList") // stateless screen: one param/callback per independently-testable setting.
fun SettingsScreen(
    settings: AppSettings,
    onSetGaugeVisible: (String, Boolean) -> Unit,
    onMoveGauge: (String, Int) -> Unit,
    onSetThresholdOverride: (String, GaugeThresholds?) -> Unit,
    onResetThresholds: () -> Unit,
    onSetUnits: (UnitPreferences) -> Unit,
    onSetKeepScreenOn: (Boolean) -> Unit,
    onSetShowConnectionStatus: (Boolean) -> Unit,
    onSetPollRate: (PollRate) -> Unit,
    onOpenRecordings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag("settings-screen"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(SECTION_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING_DP.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("settings-back-button")) { Text("< Back") }
                Text(text = "Settings", style = MaterialTheme.typography.headlineSmall)
            }

            GaugesSection(settings.gaugeOrder, onSetGaugeVisible, onMoveGauge)
            HorizontalDivider()
            ThresholdsSection(settings, onSetThresholdOverride, onResetThresholds)
            HorizontalDivider()
            UnitsSection(settings.units, onSetUnits)
            HorizontalDivider()
            KeepScreenOnSection(settings.keepScreenOn, onSetKeepScreenOn)
            HorizontalDivider()
            // OBD-84: inlined rather than its own private fun, same reasoning as the Recordings
            // section below — this file is already at detekt's per-file function-count bar.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "Show connection status", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.showConnectionStatus,
                    onCheckedChange = onSetShowConnectionStatus,
                    modifier = Modifier.testTag("show-connection-status-switch"),
                )
            }
            HorizontalDivider()
            PollRateSection(settings.pollRate, onSetPollRate)
            HorizontalDivider()
            // OBD-70: inlined rather than its own private fun — SettingsScreen.kt is already at
            // detekt's per-file function-count bar, and a two-line section doesn't earn a new one.
            Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
                Text(text = "Recordings", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onOpenRecordings, modifier = Modifier.testTag("recordings-nav-button")) {
                    Text("View recorded sessions")
                }
            }
        }
    }
}

@Composable
private fun GaugesSection(
    gaugeOrder: List<GaugeOrderEntry>,
    onSetGaugeVisible: (String, Boolean) -> Unit,
    onMoveGauge: (String, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Text(text = "Gauges", style = MaterialTheme.typography.titleMedium)
        gaugeOrder.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // GAUGE_CATALOG_BY_ID, not DASHBOARD_PIDS_BY_ID: an OBD-42 swap can leave a
                    // non-core id (e.g. "rpm") in gaugeOrder, and this list should still show
                    // its real label rather than the raw id.
                    text = GAUGE_CATALOG_BY_ID[entry.id]?.label ?: entry.id,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = entry.visible,
                    onCheckedChange = { visible -> onSetGaugeVisible(entry.id, visible) },
                    modifier = Modifier.testTag("gauge-visible-${entry.id}"),
                )
                TextButton(
                    onClick = { onMoveGauge(entry.id, -1) },
                    modifier = Modifier.testTag("gauge-up-${entry.id}"),
                ) {
                    Text("Up")
                }
                TextButton(
                    onClick = { onMoveGauge(entry.id, 1) },
                    modifier = Modifier.testTag("gauge-down-${entry.id}"),
                ) {
                    Text("Down")
                }
            }
        }
    }
}

@Composable
private fun ThresholdsSection(
    settings: AppSettings,
    onSetThresholdOverride: (String, GaugeThresholds?) -> Unit,
    onResetThresholds: () -> Unit,
) {
    val effective = settings.effectiveThresholds()
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Text(text = "Thresholds", style = MaterialTheme.typography.titleMedium)
        THRESHOLD_GAUGE_IDS.forEach { id ->
            key(id) {
                val thresholds = effective[id] ?: GaugeThresholds()
                val wireUnit = DASHBOARD_PIDS_BY_ID[id]?.unit ?: MeasurementUnit.FAHRENHEIT
                val displayUnit = settings.units.displayUnitFor(wireUnit)
                ThresholdRow(id, thresholds, wireUnit, displayUnit, onSetThresholdOverride)
            }
        }
        val focusManager = LocalFocusManager.current
        TextButton(onClick = {
            focusManager.clearFocus()
            onResetThresholds()
        }, modifier = Modifier.testTag("reset-thresholds-button")) {
            Text("Reset thresholds to defaults")
        }
    }
}

@Composable
private fun ThresholdRow(
    id: String,
    thresholds: GaugeThresholds,
    wireUnit: MeasurementUnit,
    displayUnit: MeasurementUnit,
    onSetThresholdOverride: (String, GaugeThresholds?) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = DASHBOARD_PIDS_BY_ID[id]?.label ?: id, modifier = Modifier.weight(1f))
        ThresholdField(
            label = "Green max",
            wireValue = thresholds.greenMax,
            wireUnit = wireUnit,
            displayUnit = displayUnit,
            testTag = "threshold-$id-greenMax",
            onCommit = { newWireValue -> onSetThresholdOverride(id, thresholds.copy(greenMax = newWireValue)) },
        )
        ThresholdField(
            label = "Red min",
            wireValue = thresholds.redMin,
            wireUnit = wireUnit,
            displayUnit = displayUnit,
            testTag = "threshold-$id-redMin",
            onCommit = { newWireValue -> onSetThresholdOverride(id, thresholds.copy(redMin = newWireValue)) },
        )
    }
}

private const val FIELD_WIDTH_DP = 72
private const val FIELD_BORDER_WIDTH_DP = 1
private const val FIELD_INNER_PADDING_DP = 6
private const val FIELD_HORIZONTAL_PADDING_DP = 4

/**
 * One editable threshold boundary. Displayed and typed in [displayUnit] (the user's current
 * °F/°C or PSI/kPa choice); every edit is converted back to [wireUnit] before [onCommit] — the
 * stored value never changes unit just because the display toggle did (OBD-21 AC).
 *
 * A blank or unparsable field commits nothing — the last valid value stays persisted (review
 * round-1 M6: an earlier version committed `null` for a blank field, which clears that
 * boundary entirely; for coolant that meant a stray blanked green-max field left every reading
 * permanently AMBER, in a dash app, with no way back short of "reset to defaults". Deliberately
 * *not* a supported way to clear a boundary — "reset thresholds to defaults" is.
 *
 * A hand-rolled [BasicTextField] rather than Material3's `OutlinedTextField`, kept lightweight;
 * NOT a workaround for a text-field-specific rendering bug (an earlier hypothesis attributing
 * click-routing failures elsewhere in the screen to this choice was wrong — see
 * `app/MODULE.md`'s "Settings screen" section and review round-1 M2 for the actual cause).
 */
@Composable
@Suppress("LongParameterList") // one field per (label, value, source/display unit, tag, commit) — all load-bearing.
private fun ThresholdField(
    label: String,
    wireValue: Double?,
    wireUnit: MeasurementUnit,
    displayUnit: MeasurementUnit,
    testTag: String,
    onCommit: (Double) -> Unit,
) {
    val displayValue = wireValue?.let { UnitConversion.convert(it, wireUnit, displayUnit) }
    val textColor = MaterialTheme.colorScheme.onBackground
    // The text is LOCAL state while the field has focus (round-2 regression: deriving it from
    // committed state made a cleared field snap back with the cursor at 0, so retyping
    // prepended — clear + "225" stored 225220.0, silently GREEN forever). While focused the
    // user owns the text; commits stream out on every parseable value. When not focused,
    // external changes (unit toggle, reset-to-defaults) re-derive it from the wire value.
    var text by remember { mutableStateOf(displayValue?.toString().orEmpty()) }
    var focused by remember { mutableStateOf(false) }
    // A UNIT change always re-derives, even mid-edit — round-3 finding: Compose clickable
    // does not take focus, so a unit toggle while typing left on-screen Fahrenheit digits
    // parsing as Celsius (stored 3821 F, permanently green). The focus guard applies only to
    // wireValue, which is what this field's own keystrokes change.
    LaunchedEffect(displayUnit) {
        text = displayValue?.toString().orEmpty()
    }
    LaunchedEffect(wireValue, focused) {
        if (!focused) {
            text = displayValue?.toString().orEmpty()
        }
    }
    Column(modifier = Modifier.padding(horizontal = FIELD_HORIZONTAL_PADDING_DP.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        BasicTextField(
            value = text,
            onValueChange = { updated ->
                text = updated
                // Blank/unparsable commits nothing at all — see this function's KDoc.
                updated.toDoubleOrNull()?.let { parsed ->
                    onCommit(UnitConversion.convert(parsed, displayUnit, wireUnit))
                }
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            cursorBrush = SolidColor(textColor),
            singleLine = true,
            modifier =
                Modifier
                    .testTag(testTag)
                    .width(FIELD_WIDTH_DP.dp)
                    .border(FIELD_BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.outline)
                    .padding(FIELD_INNER_PADDING_DP.dp)
                    .onFocusChanged { focused = it.isFocused },
        )
    }
}

@Composable
private fun UnitsSection(
    units: UnitPreferences,
    onSetUnits: (UnitPreferences) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Text(text = "Units", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Temperature", modifier = Modifier.weight(1f))
            UnitChoiceButton("°F", units.temperatureUnit == MeasurementUnit.FAHRENHEIT, "unit-temp-fahrenheit") {
                onSetUnits(units.copy(temperatureUnit = MeasurementUnit.FAHRENHEIT))
            }
            UnitChoiceButton("°C", units.temperatureUnit == MeasurementUnit.CELSIUS, "unit-temp-celsius") {
                onSetUnits(units.copy(temperatureUnit = MeasurementUnit.CELSIUS))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Pressure", modifier = Modifier.weight(1f))
            UnitChoiceButton("PSI", units.pressureUnit == MeasurementUnit.PSI, "unit-pressure-psi") {
                onSetUnits(units.copy(pressureUnit = MeasurementUnit.PSI))
            }
            UnitChoiceButton("kPa", units.pressureUnit == MeasurementUnit.KPA, "unit-pressure-kpa") {
                onSetUnits(units.copy(pressureUnit = MeasurementUnit.KPA))
            }
        }
    }
}

@Composable
private fun UnitChoiceButton(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    // Clear focus so an in-progress threshold edit is committed-as-was and re-derived in the
    // new unit rather than left showing digits in the old one (round-3, belt-and-braces with
    // the split LaunchedEffect in ThresholdField).
    val focusManager = LocalFocusManager.current
    TextButton(onClick = {
        focusManager.clearFocus()
        onClick()
    }, modifier = Modifier.testTag(testTag)) {
        Text(text = if (selected) "[$label]" else label)
    }
}

@Composable
private fun KeepScreenOnSection(
    keepScreenOn: Boolean,
    onSetKeepScreenOn: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Keep screen on", modifier = Modifier.weight(1f))
        Switch(
            checked = keepScreenOn,
            onCheckedChange = onSetKeepScreenOn,
            modifier = Modifier.testTag("keep-screen-on-switch"),
        )
    }
}

@Composable
private fun PollRateSection(
    pollRate: PollRate,
    onSetPollRate: (PollRate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ROW_SPACING_DP.dp)) {
        Text(text = "Poll rate", style = MaterialTheme.typography.titleMedium)
        Row {
            PollRate.entries.forEach { rate ->
                UnitChoiceButton(
                    label = "${1_000 / rate.intervalMs} Hz",
                    selected = pollRate == rate,
                    testTag = "poll-rate-${rate.name}",
                    onClick = { onSetPollRate(rate) },
                )
            }
        }
    }
}

/**
 * Hilt/ViewModel-wired entry point for [com.revel.obdgauge.app.MainActivity]. Kept separate from
 * [SettingsScreen] (see its KDoc) so the substantial screen logic stays testable without Hilt.
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    // OBD-70: Recordings is a nested destination reached from this screen (this issue's spec
    // explicitly allows either that or a new arm of MainActivity's own screen swap — nesting it
    // here keeps the change local to the settings flow rather than touching MainActivity's nav
    // state at all). Plain local state, same "no nav library" shape MainActivity's own KDoc
    // documents for the top-level swap.
    var showRecordings by remember { mutableStateOf(false) }
    if (showRecordings) {
        RecordingsRoute(onBack = { showRecordings = false }, modifier = modifier)
        return
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        onSetGaugeVisible = viewModel::setGaugeVisible,
        onMoveGauge = viewModel::moveGauge,
        onSetThresholdOverride = viewModel::setThresholdOverride,
        onResetThresholds = viewModel::resetThresholdsToDefault,
        onSetUnits = viewModel::setUnits,
        onSetKeepScreenOn = viewModel::setKeepScreenOn,
        onSetShowConnectionStatus = viewModel::setShowConnectionStatus,
        onSetPollRate = viewModel::setPollRate,
        onOpenRecordings = { showRecordings = true },
        onBack = onBack,
        modifier = modifier,
    )
}
