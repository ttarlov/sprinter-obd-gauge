package com.revel.obdgauge.app.gauge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revel.obdgauge.app.gauge.grid.GridEngine
import com.revel.obdgauge.app.gauge.grid.GridLayout
import com.revel.obdgauge.app.gauge.grid.GridMigration
import com.revel.obdgauge.app.service.PollKeepAlive
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.settings.effectiveThresholds
import com.revel.obdgauge.app.settings.withGaugeSwapped
import com.revel.obdgauge.app.sparkline.SparklineHistoryHolder
import com.revel.obdgauge.app.sparkline.SparklinePoint
import com.revel.obdgauge.model.VehicleDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import javax.inject.Inject

/**
 * StateFlow-in, Compose-state-out (per `docs/01-build-plan.md` §2A): formats
 * [VehicleDataSource] output into [DashboardUiState] and nothing else. Zero protocol math —
 * every value it renders arrived already parsed and unit-converted from the data source.
 *
 * @param clock used only to compute "last seen Xs ago" for stale readings; injected (rather
 *   than `Instant.now()`) so tests can supply a fixed instant for deterministic assertions.
 * @param settingsRepository OBD-21 settings (thresholds/units/gauge order/keep-screen-on):
 *   combined into [uiState] so a settings edit recolors/reformats the dashboard live, without
 *   restarting anything.
 * @param keepAlive OBD-25: whether [com.revel.obdgauge.app.service.ObdConnectionService] is
 *   currently keeping the shared data source polling. Defaults to a fresh (inactive) instance so
 *   a ViewModel built without one behaves exactly as it did before — see [stopUnlessKeptAlive].
 */
@HiltViewModel
class DashboardViewModel
    @Inject
    constructor(
        private val dataSource: VehicleDataSource,
        private val clock: Clock,
        private val settingsRepository: SettingsRepository,
        private val keepAlive: PollKeepAlive = PollKeepAlive(),
    ) : ViewModel() {
        // Per-gauge rolling history for OBD-20's sparklines, fed a step inside the same
        // combine() below. Deliberately NOT part of `uiState`'s DashboardUiState — see
        // SparklineHistoryHolder's KDoc for why folding it in would defeat the whole point of
        // keeping 4 Hz updates from recomposing the entire dashboard.
        // GAUGE_CATALOG (OBD-42's swap-picker superset), not DASHBOARD_PIDS: a gauge swapped
        // into a slot (e.g. rpm) needs its own rolling history too, not just the core four.
        private val sparklineHistory = SparklineHistoryHolder(GAUGE_CATALOG.map { it.id })

        init {
            // OBD-64: eager-seed the canonical grid ONCE, then treat `gridLayout` as the single
            // live source of truth for which gauges show, where, and at what size. Before this
            // phase `gridLayout` was left null and the screen derived a throwaway layout from
            // `gaugeOrder` every recomposition; a persisted seed is what lets add/remove/resize
            // mutate a real stored layout instead. Idempotent: the transform re-checks null under
            // the repository lock, and the outer `first()` skips the write entirely once seeded, so
            // this never rewrites an existing layout on later launches. Canonical = 4 columns
            // (`GRID_CANONICAL_COLUMNS`), matching landscape, so the migrated default still fills
            // one row exactly like the pre-grid dashboard; the screen repacks per orientation.
            viewModelScope.launch {
                if (settingsRepository.settings.first().gridLayout == null) {
                    settingsRepository.update { settings ->
                        if (settings.gridLayout != null) {
                            settings
                        } else {
                            settings.copy(
                                gridLayout =
                                    GridMigration.fromGaugeOrder(settings.gaugeOrder, GRID_CANONICAL_COLUMNS),
                            )
                        }
                    }
                }
            }
        }

        // start()/stop() are driven by [uiState]'s own subscription (onStart/onCompletion,
        // upstream of stateIn) rather than the ViewModel's own init/onCleared lifetime — that
        // way SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS) actually gates polling: the
        // data source starts only once uiState gains its first collector, and stops once the
        // last collector has been gone for STOP_TIMEOUT_MILLIS.
        val uiState: StateFlow<DashboardUiState> =
            combine(
                dataSource.readings,
                dataSource.connection,
                settingsRepository.settings,
            ) { readings, connection, settings ->
                sparklineHistory.onReadings(readings, clock.instant())
                toDashboardUiState(
                    readings,
                    connection,
                    clock.instant(),
                    settings.effectiveThresholds(),
                    settings.units,
                )
            }.onStart { dataSource.start(GAUGE_CATALOG) }
                .onCompletion { stopUnlessKeptAlive() }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    initialValue = DashboardUiState.Loading,
                )

        /** Which gauges show, and in what order — OBD-21's settings screen writes this. */
        val gaugeOrder: StateFlow<List<GaugeOrderEntry>> =
            settingsRepository.settings
                .map { it.gaugeOrder }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DEFAULT_GAUGE_ORDER)

        /**
         * OBD-63: the persisted spanning-grid layout, or `null` until one is written — the
         * dashboard derives one from [gaugeOrder] in that case (see `GaugeDashboard`). Passed
         * straight through unresolved so the screen can repack it per orientation.
         */
        val gridLayout: StateFlow<GridLayout?> =
            settingsRepository.settings
                .map { it.gridLayout }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppSettings().gridLayout)

        /**
         * OBD-66: the effective per-gauge threshold table ([ThresholdConfig.seed] + user overrides)
         * the dashboard's in-tile gear editor reads to pre-fill its stepper and to preserve the
         * *other* boundary when persisting one. Same value `uiState` already classifies against —
         * exposed separately here only because the editor needs the boundaries themselves, not just
         * the resulting zone color.
         */
        val thresholds: StateFlow<Map<String, GaugeThresholds>> =
            settingsRepository.settings
                .map { it.effectiveThresholds() }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    ThresholdConfig.seed,
                )

        /** OBD-21's keep-screen-on toggle; `MainActivity` applies it to the window. */
        val keepScreenOn: StateFlow<Boolean> =
            settingsRepository.settings
                .map { it.keepScreenOn }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    AppSettings().keepScreenOn,
                )

        /** OBD-20's per-gauge sparkline strip — see [SparklineHistoryHolder]. */
        fun sparklineFlow(id: String): StateFlow<List<SparklinePoint>> = sparklineHistory.flowFor(id)

        /**
         * OBD-42: the long-press picker's "tap a candidate" action. Replaces [oldId] with
         * [newId] at that gaugeOrder position (keeping visibility — [AppSettings.withGaugeSwapped])
         * via the exact same [SettingsRepository.update] path [SettingsViewModel]'s mutators use,
         * so the swap persists and the dashboard/settings screen converge on it live, no restart.
         * A no-op when [oldId] equals [newId] (the picker's own "tap the current gauge to dismiss"
         * affordance calls back into UI-only state, never this).
         */
        fun swapGauge(
            oldId: String,
            newId: String,
        ) {
            if (oldId == newId) return
            // OBD-64: the swap now lands on the grid too — `GridEngine.replaceId` renames the
            // placement in place (same cell, same span), the source of truth for rendering. The
            // `gaugeOrder` rewrite (`withGaugeSwapped`) is kept in lockstep so any code still
            // reading `gaugeOrder` (settings screen, backfill) stays consistent.
            viewModelScope.launch {
                settingsRepository.update { settings ->
                    settings
                        .withGaugeSwapped(oldId, newId)
                        .copy(gridLayout = GridEngine.replaceId(canonicalGrid(settings), oldId, newId))
                }
            }
        }

        /**
         * OBD-66: persists gauge [id]'s threshold override from the in-tile gear editor, through the
         * exact same [SettingsRepository.update] path [swapGauge] and OBD-21's settings screen use —
         * so an edit here live-recolors the dashboard (and stays in sync with the settings screen's
         * own threshold fields, both writing the one [AppSettings.thresholdOverrides] map). [next] is
         * the full replacement band for [id] (the editor folds a single boundary edit into the
         * gauge's current effective band before calling this — see `ThresholdEditing.kt`).
         */
        fun setThreshold(
            id: String,
            next: GaugeThresholds,
        ) {
            viewModelScope.launch {
                settingsRepository.update { settings ->
                    settings.copy(thresholdOverrides = settings.thresholdOverrides + (id to next))
                }
            }
        }

        /**
         * OBD-64: adds [id] to the grid in the first free canonical slot (a no-op if already
         * placed). The add-palette's only mutator.
         */
        fun addGauge(id: String) = mutateGrid { GridEngine.addInFirstFreeSlot(it, id) }

        /**
         * OBD-64: removes [id] from the grid and repacks the rest closed (a no-op if absent). The
         * removed id becomes an add-palette candidate again. Note this touches only `gridLayout` —
         * `gaugeOrder` keeps the entry so a later re-add restores its former visibility default.
         */
        fun removeGauge(id: String) = mutateGrid { GridEngine.remove(it, id) }

        /**
         * OBD-64: resizes [id]'s tile to [colSpan]×[rowSpan] and repacks; spans are clamped by the
         * engine. A no-op if [id] is absent.
         */
        fun resizeGauge(
            id: String,
            colSpan: Int,
            rowSpan: Int,
        ) = mutateGrid { GridEngine.resize(it, id, colSpan, rowSpan) }

        /**
         * Applies [op] to the stored *canonical* (4-column) layout and persists it — every grid
         * mutation reads the persisted layout (falling back to a fresh migration only if the eager
         * seed somehow hasn't run yet), never the orientation-repacked copy the screen renders.
         */
        private fun mutateGrid(op: (GridLayout) -> GridLayout) {
            viewModelScope.launch {
                settingsRepository.update { settings -> settings.copy(gridLayout = op(canonicalGrid(settings))) }
            }
        }

        private fun canonicalGrid(settings: AppSettings): GridLayout =
            settings.gridLayout ?: GridMigration.fromGaugeOrder(settings.gaugeOrder, GRID_CANONICAL_COLUMNS)

        override fun onCleared() {
            // Belt-and-suspenders: makes teardown deterministic on ViewModel clear rather than
            // relying solely on the async cancellation of the onCompletion above. stop() is
            // idempotent (see FakeVehicleDataSource), so this is safe to call twice.
            stopUnlessKeptAlive()
            super.onCleared()
        }

        /**
         * The UI-gated `stop()`, with OBD-25's one condition on it: **do not stop a data source
         * the foreground service is deliberately keeping alive.**
         *
         * [dataSource] is a `@Singleton` with two owners. This ViewModel's
         * `WhileSubscribed(5_000)` teardown is right when it is the only one — closing the app
         * should not leave a dongle being polled forever. It is wrong ~5 s after the screen turns
         * off on a dash mount, which is the exact scenario `ObdConnectionService` (and the wake
         * lock it holds) exists for: the service would sit there burning a wake lock on a poll
         * loop this line had just cancelled.
         *
         * OBD-24 patched that from the other side, by having the service watch for a fall to
         * `Disconnected` and re-issue `start()`. `reviews/OBD-24-round1.md` measured what that
         * costs once a real reconnect policy is behind the same source (30 `start()`/60 s, three
         * times the OS scan throttle, defeating the very backoff it was racing) and its hazard
         * statement made resolving the ownership part of OBD-25. This is the resolution's
         * ViewModel half: the service's intent is a published fact
         * ([com.revel.obdgauge.app.service.PollKeepAlive]) rather than something inferred from
         * link state — which never carried that information in the first place, since
         * `RealVehicleDataSource.connection` just forwards `ObdLink.state` and start/stop does not
         * touch it.
         *
         * `start()` is deliberately NOT gated the same way: it "replaces" per the frozen
         * contract, so a foregrounding app re-asserting a session it is about to render is
         * harmless, and gating it would mean the dashboard could open onto a source nobody
         * started.
         */
        private fun stopUnlessKeptAlive() {
            if (keepAlive.active.value) return
            dataSource.stop()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
