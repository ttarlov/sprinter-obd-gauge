package com.revel.obdgauge.app.gauge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.revel.obdgauge.app.gauge.grid.GridEngine
import com.revel.obdgauge.app.gauge.grid.GridLayout
import com.revel.obdgauge.app.gauge.grid.GridLayoutSet
import com.revel.obdgauge.app.recording.RecordingBridge
import com.revel.obdgauge.app.recording.RecordingState
import com.revel.obdgauge.app.service.ActivePollSet
import com.revel.obdgauge.app.service.EngineOffAction
import com.revel.obdgauge.app.service.EngineOffBridge
import com.revel.obdgauge.app.service.PollKeepAlive
import com.revel.obdgauge.app.settings.AppSettings
import com.revel.obdgauge.app.settings.DEFAULT_GAUGE_ORDER
import com.revel.obdgauge.app.settings.GaugeOrderEntry
import com.revel.obdgauge.app.settings.SettingsRepository
import com.revel.obdgauge.app.settings.effectiveScales
import com.revel.obdgauge.app.settings.effectiveThresholds
import com.revel.obdgauge.app.settings.withGaugeSwapped
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
 * @param activePollSet OBD-70: GAUGE_CATALOG ∪ whatever a recording session currently wants —
 *   read on every `dataSource.start(...)` call this class makes, instead of the GAUGE_CATALOG
 *   literal, so a dashboard resubscribe (screen off/on) during an active recording re-requests
 *   the WIDENED set rather than silently narrowing it back down. See [ActivePollSet]'s KDoc.
 * @param recordingBridge OBD-70: the Record button's UI handle onto whichever `Recorder`
 *   `ObdConnectionService` currently owns — see [RecordingBridge]'s KDoc.
 * @param engineOffBridge OBD-71: the "Engine off — keep monitoring?" dialog's UI handle onto
 *   whichever `EngineOffPromptController` `ObdConnectionService` currently owns — see
 *   [EngineOffBridge]'s KDoc.
 */
@HiltViewModel
// One small mutator per grid/threshold/swap operation (OBD-64/66/67) is the cohesive shape this
// class's whole job takes — splitting it up would scatter the single `mutateGrid`/`settingsRepository`
// persist path this KDoc describes across multiple classes for no clarity gain.
@Suppress("TooManyFunctions")
class DashboardViewModel
    @Inject
    // OBD-71 added engineOffBridge as the 7th collaborator — every one is a distinct, real Hilt
    // singleton this ViewModel genuinely depends on (mirrors ObdConnectionService's own
    // @Suppress("TooManyFunctions") right above it for the same "this growth is the class's
    // actual job, not sprawl" reason), not a param-list smell worth introducing a params object
    // to hide.
    @Suppress("LongParameterList")
    constructor(
        private val dataSource: VehicleDataSource,
        private val clock: Clock,
        private val settingsRepository: SettingsRepository,
        private val keepAlive: PollKeepAlive = PollKeepAlive(),
        private val activePollSet: ActivePollSet = ActivePollSet(),
        private val recordingBridge: RecordingBridge = RecordingBridge(),
        private val engineOffBridge: EngineOffBridge = EngineOffBridge(),
    ) : ViewModel() {
        init {
            // OBD-68 (was "eager-seed the ONE canonical grid" under OBD-64 — see the round-4 pivot
            // note in `issues/OBD-67.md`): eager-seed BOTH required per-column-count layouts ONCE,
            // then treat `gridLayoutsByColumns` as the live source of truth for which gauges show,
            // where, and at what size, in EACH orientation independently. Idempotent the same way
            // the original single-layout seed was: the outer `first()` skips the write entirely
            // once both counts are already present (`GridLayoutSet.ensureColumns` is itself a
            // no-op once complete, so even a repeat call under the repository lock changes nothing).
            viewModelScope.launch {
                val needsSeed =
                    settingsRepository.settings
                        .first()
                        .gridLayoutsByColumns.keys != REQUIRED_COLUMN_COUNTS
                if (needsSeed) {
                    settingsRepository.update { settings ->
                        settings.copy(
                            gridLayoutsByColumns =
                                GridLayoutSet.ensureColumns(
                                    settings.gridLayoutsByColumns,
                                    REQUIRED_COLUMN_COUNTS,
                                    settings.gaugeOrder,
                                ),
                        )
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
                toDashboardUiState(
                    readings,
                    connection,
                    clock.instant(),
                    settings.effectiveThresholds(),
                    settings.units,
                )
            }.onStart { dataSource.start(activePollSet.activePids()) }
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
         * OBD-68: the persisted per-column-count grid layouts, empty until any are written — the
         * dashboard derives one per orientation from [gaugeOrder] in that case (see
         * `GaugeDashboard`). Passed straight through unresolved (keyed by column count) so the
         * screen picks its own orientation's entry rather than anything being repacked here.
         */
        val gridLayoutsByColumns: StateFlow<Map<Int, GridLayout>> =
            settingsRepository.settings
                .map { it.gridLayoutsByColumns }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    AppSettings().gridLayoutsByColumns,
                )

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

        /**
         * OBD-72: per-gauge render style (digital/needle/bar-arc), keyed by gauge id — the
         * dashboard's in-tile gear editor's style-picker row reads/writes this the same way
         * [thresholds] already works for OBD-66's threshold squares.
         */
        val renderStyles: StateFlow<Map<String, GaugeRenderStyle>> =
            settingsRepository.settings
                .map { it.renderStyles }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    AppSettings().renderStyles,
                )

        /**
         * OBD-72: the effective per-gauge scale table ([com.revel.obdgauge.app.gauge.GaugeScaleDefaults.seed]
         * + user overrides) the needle/bar-arc render styles map a value's sweep/segment position
         * against.
         */
        val scales: StateFlow<Map<String, GaugeScale>> =
            settingsRepository.settings
                .map { it.effectiveScales() }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    GaugeScaleDefaults.seed,
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

        /**
         * OBD-84: whether the permanent connection-status pill's `Ready` state renders; `MainActivity`
         * passes it straight into `GaugeDashboard`. Same shape as [keepScreenOn].
         */
        val showConnectionStatus: StateFlow<Boolean> =
            settingsRepository.settings
                .map { it.showConnectionStatus }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                    AppSettings().showConnectionStatus,
                )

        /** OBD-70: what the dashboard's Record control renders — see [RecordingBridge]. */
        val recordingState: StateFlow<RecordingState> = recordingBridge.state

        /** The confirmation dialog's "Start" tap. */
        fun startRecording() = recordingBridge.start()

        /** The recording indicator's one-tap stop. */
        fun stopRecording() = recordingBridge.stop()

        /** OBD-71: what `EngineOffPromptHost` renders — see [EngineOffBridge]. */
        val engineOffAction: StateFlow<EngineOffAction> = engineOffBridge.state

        /** The "Engine off — keep monitoring?" dialog's one button. */
        fun keepMonitoring() = engineOffBridge.keepMonitoring()

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
            // OBD-64/68: the swap now lands on the grid too — `GridLayoutSet.replaceIdEverywhere`
            // renames the placement in place (same cell, same span, per stored layout) in EVERY
            // orientation's layout — a swap changes which gauge occupies a slot, so it's exactly as
            // much a gauge-set change as add/remove and needs the same cross-orientation sync (see
            // `GridLayoutSet`'s KDoc). The `gaugeOrder` rewrite (`withGaugeSwapped`) is kept in
            // lockstep so any code still reading `gaugeOrder` (settings screen, backfill) stays
            // consistent. Doesn't route through mutateGridSet: this is the one mutator that also
            // needs to rewrite `gaugeOrder` on the same AppSettings, not just the grid layouts.
            viewModelScope.launch {
                settingsRepository.update { settings ->
                    val ensured = ensuredLayouts(settings)
                    settings
                        .withGaugeSwapped(oldId, newId)
                        .copy(gridLayoutsByColumns = GridLayoutSet.replaceIdEverywhere(ensured, oldId, newId))
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
         * OBD-72: persists gauge [id]'s render style from the in-tile gear editor's style-picker
         * row, through the exact same [SettingsRepository.update] path [setThreshold] uses — an
         * edit here live-swaps the dashboard tile's body (digital/needle/bar-arc), no restart.
         */
        fun setRenderStyle(
            id: String,
            style: GaugeRenderStyle,
        ) {
            viewModelScope.launch {
                settingsRepository.update { settings ->
                    settings.copy(renderStyles = settings.renderStyles + (id to style))
                }
            }
        }

        /**
         * OBD-64/68: adds [id] wherever there's room, in EVERY stored orientation's layout
         * ([GridLayoutSet.addAnywhereEverywhere]) — the edit-bar "＋ Add" flow's mutator. Unlike
         * [addGaugeAt] there's no chosen cell, so every layout just gets its own free slot.
         */
        fun addGauge(id: String) = mutateGridSet { GridLayoutSet.addAnywhereEverywhere(it, id) }

        /**
         * OBD-68: adds [id] at an explicit cell in the [columns]-column layout — the rearrange-mode
         * empty-cell "＋" flow's mutator — and syncs it into every OTHER stored orientation's layout
         * too, at a sensible free slot there ([GridLayoutSet.addEverywhere]), so the gauge-set stays
         * identical across orientations (see `GridLayoutSet`'s KDoc). A no-op for a layout [id] is
         * already placed on.
         */
        fun addGaugeAt(
            id: String,
            col: Int,
            row: Int,
            columns: Int,
        ) = mutateGridSet { GridLayoutSet.addEverywhere(it, id, columns, col, row) }

        /**
         * OBD-64/68: removes [id] from EVERY stored orientation's layout and repacks each closed
         * ([GridLayoutSet.removeEverywhere]) — a no-op if absent from a given layout. The removed
         * id becomes an add-palette candidate again. Note this touches only the grid layouts —
         * `gaugeOrder` keeps the entry so a later re-add restores its former visibility default.
         */
        fun removeGauge(id: String) = mutateGridSet { GridLayoutSet.removeEverywhere(it, id) }

        /**
         * OBD-67 round-12 device-verified (user decision): resizes [id]'s tile to
         * [colSpan]×[rowSpan] in the [columns]-column layout ONLY, PUSHING any tile in the way
         * to a free slot — via [GridEngine.resizeWithPush], not round-10's `resizeInPlace` (silent
         * no-op on collision). The round-10/11 device reports ("1×1/2×1 work, 1×2/2×2 don't") were
         * genuine, CORRECT rejections in a packed layout — technically right, but silent, so it
         * read as broken. The user's call: make it push instead of reject.
         *
         * OBD-67 round-13 device-verified (user decision, refining round-12): a widened span that
         * would run off the right edge no longer rejects either — the placement SHIFTS LEFT to
         * fit, per user's own words: "I can resize any way I want, but only when the gauge is on
         * the LEFT side. If a gauge is in the RIGHT column it only resizes up/down, not side to
         * side." See [GridEngine.resizeWithPush]'s own KDoc for the shift math and the one span
         * still refused (wider than the grid itself — impossible via the real size chips, but a
         * real mathematical edge). Per-orientation, like [moveGauge] — a tile's size, like its
         * position, is independent per canvas (a 2×2 in landscape doesn't force a 2×2 in
         * portrait). A no-op if [id] is absent from that layout.
         */
        fun resizeGauge(
            id: String,
            colSpan: Int,
            rowSpan: Int,
            columns: Int,
        ) = mutateColumnsLayout(columns) { layout -> GridEngine.resizeWithPush(layout, id, colSpan, rowSpan) }

        /**
         * OBD-68 (freeform placement, replacing OBD-67's ordered-reflow drop-commit before it
         * shipped — device testing found ordered reflow could never place a tile side-to-side into
         * open space or hold a persistent empty cell; round-4 then made this per-orientation after
         * device testing found landscape/portrait need independent freeform arrangements, not one
         * canonical layout repacked between them — see `issues/OBD-67.md`'s pivot notes for both):
         * rearrange mode's drag-to-move commit — drops [id] at ([col], [row]) via [GridEngine.dropAt]
         * (move if it fits, swap if the target is exactly one same-footprint tile, otherwise a
         * no-op/snap-back) in the [columns]-column layout ONLY. [col]/[row]/[columns] are read
         * directly off whichever orientation is on screen, since that IS the stored layout being
         * edited now — no repack/translation between orientations to worry about.
         */
        fun moveGauge(
            id: String,
            col: Int,
            row: Int,
            columns: Int,
        ) = mutateColumnsLayout(columns) { GridEngine.dropAt(it, id, col, row) }

        /**
         * Ensures both required per-column-count layouts exist (seeding any gap — see
         * [GridLayoutSet.ensureColumns]), applies [op] to the WHOLE map, and persists it. The
         * shared path [addGauge]/[addGaugeAt]/[removeGauge] use — anything that can touch more than
         * one stored layout at once.
         */
        private fun mutateGridSet(op: (Map<Int, GridLayout>) -> Map<Int, GridLayout>) {
            viewModelScope.launch {
                settingsRepository.update { settings ->
                    settings.copy(gridLayoutsByColumns = op(ensuredLayouts(settings)))
                }
            }
        }

        /**
         * [mutateGridSet], narrowed to a single orientation: applies [op] to just the [columns]
         * layout (already guaranteed present by the ensure-step) and leaves every other stored
         * layout untouched. [moveGauge]/[resizeGauge] — genuinely single-orientation edits — use
         * this instead of [mutateGridSet] directly.
         */
        private fun mutateColumnsLayout(
            columns: Int,
            op: (GridLayout) -> GridLayout,
        ) = mutateGridSet { layouts -> layouts + (columns to op(layouts.getValue(columns))) }

        private fun ensuredLayouts(settings: AppSettings): Map<Int, GridLayout> =
            GridLayoutSet.ensureColumns(settings.gridLayoutsByColumns, REQUIRED_COLUMN_COUNTS, settings.gaugeOrder)

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

            // OBD-68: the two column counts a grid layout must exist for — landscape/canonical and
            // portrait, `DashboardScreen.kt`'s own orientation constants (internal there for
            // exactly this cross-file use).
            val REQUIRED_COLUMN_COUNTS = setOf(GRID_CANONICAL_COLUMNS, GRID_PORTRAIT_COLUMNS)
        }
    }
