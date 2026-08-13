package com.revel.obdgauge.app.link

import com.revel.obdgauge.ble.BleObdLink
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `prod` flavor [LinkController]: the thin edge between flavor-common UI and `:core:ble`'s
 * [BleObdLink] (OBD-25).
 *
 * Injects the **concrete** [BleObdLink], not the frozen `ObdLink` contract, for the same reason
 * `ConsoleViewModel` does: [BleObdLink.missingPermissions] and [BleObdLink.rememberedDevice] are
 * module-level extras deliberately kept off the frozen interface (see `core/ble/MODULE.md`'s
 * "Permissions" section).
 *
 * Every method here is a pass-through. That is the point — see [LinkController]'s KDoc for the
 * ownership rule this type exists to make enforceable: policy about *when* to retry belongs to
 * `BleObdLink`'s reconnect machine, and nothing in `:app` is allowed to second-guess it.
 */
@Singleton
class BleLinkController
    @Inject
    constructor(
        private val link: BleObdLink,
    ) : LinkController {
        override val available: Boolean = true

        override val missingPermissions: List<String> get() = link.missingPermissions

        override suspend fun connect() = link.connect()

        override suspend fun disconnect() = link.disconnect()

        /**
         * Remembered-device fast path. Both guards are load-bearing:
         *
         * - **permissions already granted** — a launch-time `connect()` with anything missing
         *   parks the link in `LinkState.Error(PermissionDenied)` (see `ConnectPlanner`), which
         *   `ReconnectPolicy` classifies as terminal, so the machine disarms itself before the
         *   user has been asked anything. Leaving it alone keeps the banner's Connect action —
         *   which *does* prompt — as the way in.
         * - **a device is remembered** — with none, `connect()` would start a BLE scan the user
         *   never asked for, spending one of Android's 5-scans-per-30s budget on a launch that
         *   may just be someone checking the app in their kitchen.
         */
        override suspend fun connectIfRemembered() {
            if (missingPermissions.isNotEmpty()) return
            if (link.rememberedDevice() == null) return
            link.connect()
        }
    }
