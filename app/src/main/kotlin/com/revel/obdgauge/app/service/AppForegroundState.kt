package com.revel.obdgauge.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OBD-71: whether `MainActivity` is currently started/visible — half of the "user present" signal
 * [EngineOffPromptController] needs. (The other half, screen-interactive, is read live from
 * `android.os.PowerManager.isInteractive` by `ObdConnectionService.isUserPresent` — a `Service` is
 * already a `Context`, so that half needs no plumbing at all.) Same `@Singleton`-as-published-fact
 * shape as [PollKeepAlive] — see its KDoc for why a plain Hilt singleton beats binding a Service
 * to answer one boolean.
 *
 * `MainActivity` sets this in `onStart`/`onStop` (the same "foreground/visible" lifecycle pair its
 * `speedSource` already follows), not `onResume`/`onPause` — `onStart`/`onStop` is the "can the
 * user currently see this Activity" boundary, and flipping on every transient partial-obscure (a
 * system dialog, a notification shade drag) would flap presence for no reason.
 */
@Singleton
class AppForegroundState
    @Inject
    constructor() {
        private val mutableIsForeground = MutableStateFlow(false)

        /** `true` while `MainActivity` is started (visible), independent of screen-interactive state. */
        val isForeground: StateFlow<Boolean> = mutableIsForeground.asStateFlow()

        /** Called only by `MainActivity.onStart`/`onStop`. */
        fun setForeground(foreground: Boolean) {
            mutableIsForeground.value = foreground
        }
    }
