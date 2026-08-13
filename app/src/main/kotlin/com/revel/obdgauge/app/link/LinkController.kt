package com.revel.obdgauge.app.link

/**
 * The **only** thing in `:app` allowed to ask the transport to connect or disconnect (OBD-25).
 *
 * ### Why this interface exists at all
 * `src/main/` is flavor-common and must not reference `:core:ble` (which is not on the `demo`
 * release classpath at all — see `app/MODULE.md`'s HARD CONSTRAINT and the `prodImplementation`
 * scoping in `build.gradle.kts`). The connect affordance, however, lives in flavor-common UI
 * (`MainActivity` + `ConnectionBanner`). This interface is that seam: `prod` binds it to
 * `BleLinkController` (a thin edge over `BleObdLink`), `demo` binds nothing at all and every
 * injection point sees `Optional.empty()` — which is precisely why the demo dashboard shows no
 * connect button and behaves exactly as it did before this issue.
 *
 * ### Link ownership (the OBD-25 hazard resolution — read this before adding a caller)
 * `BleObdLink`'s reconnect state machine (OBD-23) is the single authority over the link. It
 * decides when to retry, on what backoff, and when to give up. Therefore:
 *
 * - [connect] and [disconnect] may be called **only** from an explicit user gesture (the banner's
 *   Connect action, the notification's Stop action) or from [connectIfRemembered]'s one-shot
 *   launch attempt.
 * - **Nothing may call either one in reaction to an observed [com.revel.obdgauge.model.LinkState]
 *   transition.** `connect()` restarts the backoff from zero by design ("the user asking again is
 *   not the eleventh failure in a row" — `BleObdLink.connect`'s KDoc); a state-driven caller
 *   therefore defeats the backoff on every cycle, which is the measured 30-`start()`/60s failure
 *   mode recorded in `reviews/OBD-24-round1.md`.
 * - `ConnectionServiceController` deliberately holds **no reference to this type**, so that rule
 *   is structural for the one component most tempted to break it.
 */
interface LinkController {
    /** Whether this build has a real link to drive; `false` for the `demo` flavor's no-op. */
    val available: Boolean

    /**
     * Runtime permissions that must be granted before [connect] can succeed, in the order
     * `:core:ble` wants them requested. Empty means "connect will not fail on permissions".
     * `:core:ble` never prompts (it has no UI) — `MainActivity` does, at the connect moment.
     */
    val missingPermissions: List<String>

    /** Connects, and stays connected: arms `:core:ble`'s auto-reconnect. User-gesture only. */
    suspend fun connect()

    /** Explicit user stop: disarms auto-reconnect and drops the link. User-gesture only. */
    suspend fun disconnect()

    /**
     * The remembered-device fast path, as a launch-time one-shot: connects **only** if a device
     * was previously paired successfully *and* every runtime permission is already granted.
     *
     * Deliberately silent otherwise — a launch that would have to prompt, or that has nothing to
     * fast-path to, leaves the banner's Connect action as the (visible, user-driven) way in
     * rather than throwing a permission dialog at someone who just opened the app.
     */
    suspend fun connectIfRemembered()

    /** The `demo` flavor's shape of this: there is no link, so there is nothing to drive. */
    object None : LinkController {
        override val available: Boolean = false
        override val missingPermissions: List<String> = emptyList()

        override suspend fun connect() = Unit

        override suspend fun disconnect() = Unit

        override suspend fun connectIfRemembered() = Unit
    }
}
