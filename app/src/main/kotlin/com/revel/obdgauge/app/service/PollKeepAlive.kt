package com.revel.obdgauge.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether [ObdConnectionService] currently intends the shared
 * [com.revel.obdgauge.model.VehicleDataSource] to keep polling — the one bit that lets two
 * independent owners of the same singleton stop fighting over it (OBD-25).
 *
 * ## The conflict this settles
 *
 * `DashboardViewModel` starts the shared data source when its `uiState` gains a collector and
 * **stops it** ~5 s after the last one goes away (`SharingStarted.WhileSubscribed(5_000)`), which
 * is the correct behaviour when nothing else wants the dongle — and exactly wrong the moment the
 * screen turns off on a dash mount, which is the entire reason OBD-24's foreground service (and
 * its wake lock) exists. OBD-24 papered over that with a self-heal in
 * [ConnectionServiceController] that re-issued `start()` whenever it saw the connection fall to
 * `Disconnected`; `reviews/OBD-24-round1.md` measured what that costs once a real reconnect
 * policy sits behind the same source, and the hazard statement made resolving it part of OBD-25.
 *
 * This is the resolution's other half: a **positive, published intent** instead of an inference
 * from link state. The service raises it on start and drops it on stop; the ViewModel's UI-gated
 * teardown consults it and declines to stop a source somebody else is keeping alive. Nobody has
 * to guess what a `Disconnected` meant, so the whole class of "was that the UI stopping, or the
 * link dropping, or the reconnect machine parked between attempts?" ambiguity disappears — which
 * is the ambiguity the hazard statement is about.
 *
 * ## Why a separate singleton, and not a flag on the service
 *
 * `ObdConnectionService` is an Android `Service`; a ViewModel cannot read its fields without a
 * binder, and binding a service to answer one boolean would be a large amount of lifecycle for a
 * very small amount of truth. A `@Singleton` in the same Hilt component is the same fact with
 * none of the machinery, and it stays testable as a plain object — [ConnectionServiceController]
 * and `DashboardViewModel` are both unit-tested against it without Robolectric.
 *
 * Defaults to `false`, so a ViewModel constructed without one (every existing `demo` test, and
 * any future test that does not care) behaves exactly as it did before this change.
 */
@Singleton
class PollKeepAlive
    @Inject
    constructor() {
        private val mutableActive = MutableStateFlow(false)

        /** `true` while the foreground service intends the data source to keep polling. */
        val active: StateFlow<Boolean> = mutableActive.asStateFlow()

        /** Called only by [ConnectionServiceController.start]. */
        internal fun acquire() {
            mutableActive.value = true
        }

        /** Called only by [ConnectionServiceController.stop]. */
        internal fun release() {
            mutableActive.value = false
        }
    }
