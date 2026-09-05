package com.revel.obdgauge.app.maintenance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Hilt/ViewModel-wired entry point for [com.revel.obdgauge.app.MainActivity]'s Maintenance arm.
 * Kept separate from [MaintenanceScreen]/[MaintenanceDetailScreen] (see their own KDoc) so the
 * substantial screen logic stays testable without Hilt — same split `SettingsRoute` uses.
 *
 * List↔detail is local `mutableStateOf` navigation, not a nav library — same "no nav-library
 * dependency" style [com.revel.obdgauge.app.settings.SettingsRoute] nests `RecordingsRoute` with.
 */
@Composable
fun MaintenanceRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MaintenanceViewModel = viewModel(),
) {
    var selectedItemId by remember { mutableStateOf<Long?>(null) }
    val itemId = selectedItemId
    if (itemId != null) {
        MaintenanceDetailRoute(
            itemId = itemId,
            viewModel = viewModel,
            onBack = { selectedItemId = null },
            modifier = modifier,
        )
        return
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MaintenanceScreen(
        state = state,
        onSetOdometer = viewModel::setOdometer,
        onItemClick = { id -> selectedItemId = id },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
private fun MaintenanceDetailRoute(
    itemId: Long,
    viewModel: MaintenanceViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detailFlow = remember(itemId) { viewModel.detailState(itemId) }
    val state by detailFlow.collectAsStateWithLifecycle(initialValue = null)
    val loaded = state ?: return

    MaintenanceDetailScreen(
        state = loaded,
        onSave = viewModel::updateServiceItem,
        onLogService = { date, odometerMiles, notes, costCents ->
            viewModel.logService(itemId, date.toEpochDay(), odometerMiles, notes, costCents)
        },
        onBack = onBack,
        modifier = modifier,
    )
}
