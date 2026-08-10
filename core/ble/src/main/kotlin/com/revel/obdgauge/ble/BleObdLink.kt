package com.revel.obdgauge.ble

import com.revel.obdgauge.ble.di.LinkDispatcher
import com.revel.obdgauge.ble.gatt.GattSession
import com.revel.obdgauge.ble.gatt.GattTransportFactory
import com.revel.obdgauge.ble.gatt.SessionOutcome
import com.revel.obdgauge.ble.permission.BleEnvironment
import com.revel.obdgauge.ble.scan.BleScanner
import com.revel.obdgauge.ble.scan.ScanOutcome
import com.revel.obdgauge.ble.store.BluetoothAddress
import com.revel.obdgauge.ble.store.RememberedDeviceStore
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * `ObdLink` over Android BLE/GATT (OBD-17 + OBD-18).
 *
 * ### What connect does
 * 1. Gather preconditions (BLE support, permissions, adapter state, remembered address) and ask
 *    [ConnectPlanner] what to do. Anything impossible ends as a typed `LinkState.Error` — this
 *    method does not throw for expected failures, per the `ObdLink` contract.
 * 2. **Remembered-device fast path**: a previously-successful address is connected to directly,
 *    with no scan at all. If that fails (dongle swapped, or the van is parked next to a
 *    different one) it falls back to a scan rather than giving up — the memory is an
 *    optimisation, never a constraint.
 * 3. Otherwise scan (filtered sweep, then a broad one), then connect.
 * 4. Connect means: `connectGatt` → `discoverServices` → UUID probe → CCCD → MTU → Ready, all
 *    inside [GattSession]. Only on Ready is the address remembered.
 *
 * ### Half-duplex
 * [sendRaw] takes a [Mutex] *inside* the call, so concurrent callers are serialized
 * transparently, exactly as the contract requires. There is no window in which two commands are
 * on the wire; the second caller suspends at the mutex and its command is written only after
 * the first response has been assembled (or has failed).
 *
 * ### Threading
 * Every mutation of link state — the state flow, the session reference, GATT callback fan-in —
 * happens on a single link dispatcher. Public suspend functions hop onto it with `withContext`.
 * The `state` flow itself is free to read from anywhere.
 *
 * ### Not here yet
 * No auto-reconnect: a dropped link parks in `LinkState.Error` and waits for someone to call
 * [connect] again (OBD-23). No ELM327 init sequence: this module moves strings and knows
 * nothing about their meaning — `:core:protocol` runs `ATZ`/`ATE0`/… over [sendRaw] once the
 * link is Ready (`docs/01-build-plan.md` §2C).
 */
@Singleton
class BleObdLink
    @Suppress("LongParameterList") // DI composition root: seven collaborators, each a test seam.
    @Inject
    constructor(
        private val environment: BleEnvironment,
        private val scanner: BleScanner,
        private val transports: GattTransportFactory,
        private val rememberedDevices: RememberedDeviceStore,
        private val config: BleConfig,
        private val logger: BleLogger,
        @param:LinkDispatcher private val dispatcher: CoroutineDispatcher,
    ) : ObdLink {
        private val mutableState = MutableStateFlow<LinkState>(LinkState.Disconnected)
        override val state: StateFlow<LinkState> = mutableState.asStateFlow()

        private val commandMutex = Mutex()
        private var session: GattSession? = null
        private var sessionScope: CoroutineScope? = null

        /**
         * Bumped by every [connect] and [disconnect]. A handshake publishes state only while its
         * generation is still current, so a `disconnect()` issued mid-handshake wins instead of
         * being overwritten seconds later by the attempt it cancelled, and an overlapping
         * `connect()` cannot have its result stomped by the one it replaced.
         */
        private var generation = 0L

        /**
         * Runtime permissions still missing, for `:app` to request. Empty means a connect will
         * not fail on permissions. This module never prompts — it has no UI.
         */
        val missingPermissions: List<String>
            get() = environment.missingPermissions()

        /** The address the fast path will try next, or `null` if the next connect must scan. */
        suspend fun rememberedDevice(): String? = rememberedDevices.lastAddress()

        /** Forgets the remembered device, forcing the next connect to scan. */
        suspend fun forgetRememberedDevice() {
            rememberedDevices.forget()
            logger.log("remembered device cleared")
        }

        override suspend fun connect() {
            withContext(dispatcher) {
                release()
                val attempt = ++generation
                val stored = rememberedDevices.lastAddress()
                val remembered = stored?.takeIf(BluetoothAddress::isValid)
                val plan = planConnect(environment, remembered)
                logger.log("connect plan: $plan")
                when (plan) {
                    is ConnectPlan.Abort -> publish(attempt, LinkState.Error(plan.error))
                    is ConnectPlan.Direct ->
                        // The fallback is gated on the generation too: a superseded attempt
                        // must not start a scan (or another session) on a stale ticket.
                        if (!openSession(plan.address, attempt) && attempt == generation) {
                            logger.log("direct connect to remembered ${plan.address} failed; falling back to scan")
                            scanAndConnect(attempt, stored)
                        }
                    ConnectPlan.Scan -> scanAndConnect(attempt, stored)
                }
            }
        }

        override suspend fun disconnect() {
            withContext(dispatcher) {
                generation++
                release()
                mutableState.value = LinkState.Disconnected
            }
        }

        /**
         * Single-flight is enforced here, by [commandMutex], not by the caller: the mutex is
         * taken before the command is written and released only once the response is assembled,
         * has timed out, or the link has dropped. A cancelled caller releases it the same way.
         *
         * A timeout leaves the link `Ready` — a silent ECU is normal traffic, not a dead link.
         */
        override suspend fun sendRaw(
            command: String,
            timeout: Duration,
        ): String =
            commandMutex.withLock {
                withContext(dispatcher) {
                    val active =
                        session ?: throw BleLinkException(
                            LinkError.Unknown("link is not connected; sendRaw(\"$command\") rejected"),
                        )
                    active.send(command, timeout)
                }
            }

        private suspend fun scanAndConnect(
            attempt: Long,
            preferred: String?,
        ) {
            publish(attempt, LinkState.Scanning)
            val outcome = scanner.scan(config.scanPasses(), preferred)
            // The scan suspends for seconds; a disconnect() or newer connect() may have moved
            // the generation on. A stale attempt discards its result instead of opening a
            // session nothing tracks.
            if (attempt != generation) {
                logger.log("connect attempt superseded during scan; result discarded")
                return
            }
            when (outcome) {
                is ScanOutcome.Failed -> publish(attempt, LinkState.Error(outcome.error))
                is ScanOutcome.Found -> openSession(outcome.device.address, attempt)
            }
        }

        /**
         * Brings one device up to Ready. Returns false (with `state` already set to the typed
         * error) if anything in the handshake failed.
         *
         * The session is tracked from the moment it is created, not from Ready: Android grants a
         * process roughly 32 GATT client interfaces and only `close()` gives one back, so a
         * handshake that is cancelled — a dead `viewModelScope`, a `withTimeout` around
         * `connect()` — must still be reachable by [release]. Every exit path here releases
         * it: success keeps it, failure closes it, cancellation closes it and rethrows.
         */
        @Suppress("ReturnCount") // Guard clauses ARE the fix: a stale-generation check must
        // follow every suspension point (round-2 review B2), and early returns express that
        // more safely than nested branches.
        private suspend fun openSession(
            address: String,
            attempt: Long,
        ): Boolean {
            // Stale attempts do no work at all — round-2 review proved that generation-gating
            // only the state publishes lets a superseded attempt open (and orphan) real GATT
            // clients. The check repeats after every suspension point below.
            if (attempt != generation) {
                return false
            }
            publish(attempt, LinkState.Connecting)
            val transport =
                runCatching { transports.create(address) }
                    .getOrElse { failure ->
                        logger.log("cannot create GATT transport for $address: $failure")
                        publish(attempt, LinkState.Error(LinkError.Unknown("cannot open $address: ${failure.message}")))
                        return false
                    }
            if (attempt != generation) {
                logger.log("connect attempt superseded before handshake; closing $address")
                runCatching { transport.close() }
                return false
            }

            // Never track a new session over an incumbent: release whatever is still tracked
            // first, so `session = opened` can never make a live client unreachable.
            release()
            val scope = CoroutineScope(dispatcher + SupervisorJob())
            val opened = GattSession(transport, scope, config, logger)
            opened.onTerminated = { error -> onSessionTerminated(error) }
            session = opened
            sessionScope = scope

            val outcome =
                runCatching { opened.open() }
                    .getOrElse { failure ->
                        release(opened, scope)
                        throw failure
                    }

            return when (outcome) {
                is SessionOutcome.Failed -> {
                    release(opened, scope)
                    publish(attempt, LinkState.Error(outcome.error))
                    false
                }
                is SessionOutcome.Ready ->
                    if (attempt != generation) {
                        // A disconnect() or newer connect() won while the handshake ran; the
                        // completed session loses and is closed, not kept.
                        logger.log("handshake on $address completed for a superseded attempt; releasing")
                        release(opened, scope)
                        false
                    } else {
                        rememberedDevices.remember(address)
                        logger.log("link ready on $address (mtu=${outcome.mtu}) — ${outcome.profile.describe()}")
                        publish(attempt, LinkState.Ready)
                        true
                    }
            }
        }

        /**
         * The peer dropped after Ready. The state is published *before* the session is released
         * because [release] cancels the scope this very callback is running in — anything
         * after it is not guaranteed to run. The session is closed rather than merely forgotten:
         * only `close()` hands a GATT client interface back to the system, and a link that drops
         * daily without closing eventually stops connecting at all. No reconnect (OBD-23).
         */
        private fun onSessionTerminated(error: LinkError) {
            // Deliberately not generation-gated: this callback only fires from the tracked
            // session's own event loop, and both connect() and disconnect() call release()
            // (cancelling that loop) BEFORE moving the generation — so a stale session can
            // never reach here. If that ordering ever changes, gate this publish too.
            mutableState.value = LinkState.Error(error)
            release()
        }

        /**
         * Closes [target] and clears the tracked references if they still point at it. Called
         * with no arguments it releases whatever session is currently tracked, and is a no-op
         * when there is none — so it is safe on every path, however many times it runs.
         */
        private fun release(
            target: GattSession? = session,
            scope: CoroutineScope? = sessionScope,
        ) {
            if (target == null) {
                return
            }
            if (session === target) {
                session = null
                sessionScope = null
            }
            target.close()
            scope?.cancel("session released")
        }

        /** Publishes [state] only if [attempt] is still the current connect generation. */
        private fun publish(
            attempt: Long,
            state: LinkState,
        ) {
            if (attempt == generation) {
                mutableState.value = state
            }
        }
    }

/**
 * Gathers preconditions with the permission gate **short-circuiting the radio reads**.
 *
 * `BluetoothAdapter.isEnabled` carries `@RequiresPermission(BLUETOOTH_CONNECT)` on API 31+, so
 * evaluating it eagerly (as a plain constructor argument would) throws `SecurityException` out
 * of `connect()` on exactly the first-run path where the answer is already known to be
 * `PermissionDenied`. The remaining checks are only asked once permissions allow them.
 */
private fun planConnect(
    environment: BleEnvironment,
    remembered: String?,
): ConnectPlan {
    val missing = environment.missingPermissions()
    val supported = environment.isBleSupported()
    val mayQueryRadio = supported && missing.isEmpty()
    return ConnectPlanner.plan(
        ConnectPreconditions(
            bleSupported = supported,
            bluetoothEnabled = mayQueryRadio && environment.isBluetoothEnabled(),
            locationUsableForScan = !mayQueryRadio || environment.isLocationUsableForScan(),
            missingPermissions = missing,
            rememberedAddress = remembered,
        ),
    )
}
