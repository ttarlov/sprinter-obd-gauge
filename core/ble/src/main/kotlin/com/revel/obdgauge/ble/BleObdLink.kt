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
import com.revel.obdgauge.ble.traffic.TrafficEntry
import com.revel.obdgauge.ble.traffic.TrafficLog
import com.revel.obdgauge.model.LinkError
import com.revel.obdgauge.model.LinkState
import com.revel.obdgauge.model.ObdLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * ### Auto-reconnect (OBD-23)
 * [connect] does not mean "try once", it means "be connected". It arms auto-reconnect, and from
 * then on any *recoverable* failure — the ignition killing the OBD port mid-drive, a drop out of
 * range, a scan over an empty parking lot — schedules another attempt on an exponentially
 * backed-off, jittered schedule ([ReconnectPolicy]) until the link comes back, the attempt
 * budget runs out, or the failure turns out to be one a retry provably cannot help. Each re-runs
 * the whole connect flow, so the remembered-device fast path is the resume path and the scan
 * fallback is the resume-on-found path; nothing extra is needed for either.
 *
 * Disarming is explicit and typed: [disconnect] and [forgetRememberedDevice] are the user saying
 * stop, and [ReconnectPolicy.classify] decides the rest. There is no `Reconnecting` link state —
 * `LinkState` is a frozen `:core:model` contract — so the wait is spent in
 * `LinkState.Error(cause)` and the attempt itself moves through `Connecting`/`Scanning` as any
 * connect does. [retrying] is what separates "still trying" from "gave up", since the state flow
 * alone cannot: `state is Error && !retrying` is exactly the Retry affordance.
 *
 * ### Not here yet
 * No ELM327 init sequence: this module moves strings and knows nothing about their meaning —
 * `:core:protocol` runs `ATZ`/`ATE0`/… over [sendRaw] once the link is Ready
 * (`docs/01-build-plan.md` §2C). No foreground service (OBD-24) — this class keeps a link alive,
 * it does not keep the process alive.
 *
 * ### On the size of this class (`TooManyFunctions`, 15 vs a threshold of 11)
 * Four of the fifteen are the reconnect state machine — disarm, cancel, schedule, and the
 * attempt body. Each is small and single-purpose, and each belongs beside the generation
 * counter and session reference it gates on. Hoisting them into a collaborator would mean
 * handing that mutable state out of the one class allowed to touch it, which is the invariant
 * the whole module is built on: one dispatcher, one owner, no locks.
 */
@Singleton
@Suppress("TooManyFunctions") // See the KDoc note above — the alternative is worse.
class BleObdLink
    @Suppress("LongParameterList") // DI composition root: eight collaborators, each a test seam.
    @Inject
    constructor(
        private val environment: BleEnvironment,
        private val scanner: BleScanner,
        private val transports: GattTransportFactory,
        private val rememberedDevices: RememberedDeviceStore,
        private val config: BleConfig,
        private val logger: BleLogger,
        @param:LinkDispatcher private val dispatcher: CoroutineDispatcher,
        /**
         * OBD-48 capture tap: link-state transitions land here interleaved with the TX/RX lines
         * [GattSession] taps, in one ordered stream (everything below runs on [dispatcher]).
         * Observed only — nothing here reads a result back.
         */
        private val traffic: TrafficLog = TrafficLog.NONE,
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
         * Whether a failure should schedule another attempt. Armed by [connect] — which means
         * "be connected", not "try once" — and disarmed only by an explicit stop ([disconnect],
         * [forgetRememberedDevice]), a [Recoverability.TERMINAL] cause, or a spent budget.
         */
        private var autoReconnect = false

        /** Consecutive failed attempts since the last Ready. Resets on success. */
        private var reconnectAttempts = 0

        /** The scheduled-but-not-yet-run retry, if any. */
        private var reconnectJob: Job? = null

        /**
         * Outlives any one session, unlike [sessionScope]: a retry has to survive the release of
         * the session whose death scheduled it. Never cancelled — this is a `@Singleton` and the
         * process outlives it.
         *
         * At most one retry is ever *pending*: [scheduleReconnect] is the only launcher, every
         * disarming path cancels what it finds, and the job clears the field before running its
         * body — so a job that is already awake is no longer cancellable through
         * [cancelScheduledReconnect] and is stopped by the generation counter instead. That is
         * deliberate (round-1 NIT: the old comment claimed the right thing for the wrong reason).
         *
         * The handler is the last net under B1's boundary: [runConnectAttempt] already turns
         * any throwable into a typed error and a rescheduling decision, so reaching here means
         * the recovery path itself failed. Even then the link must not be left mid-attempt.
         */
        private val linkScope =
            CoroutineScope(
                dispatcher + SupervisorJob() +
                    CoroutineExceptionHandler { _, failure ->
                        logger.log("reconnect job escaped its own boundary: $failure")
                        runCatching { recoverFromAbnormalAttempt(failure) }
                    },
            )

        private val mutableRetrying = MutableStateFlow(false)

        /**
         * Whether the link is coming back on its own, or has stopped trying (round-1 MINOR m3).
         *
         * `LinkState` is a frozen `:core:model` contract, so a `Reconnecting` case is not
         * available and the whole retry loop is spent in `LinkState.Error(cause)` — which makes
         * "still trying, sit tight" and "gave up, press Retry" **observationally identical** to
         * `:app`. That is the difference between a user waiting out a fuel stop and a user
         * staring at a dead gauge. This flow is the distinction, exposed the same way
         * [missingPermissions] is: a module-level extra on the concrete type, no contract change.
         *
         * True from the moment a retry is booked until the link reaches Ready or the machine
         * stops — so `state is Error && !retrying` is exactly "offer the user a Retry button".
         */
        val retrying: StateFlow<Boolean> = mutableRetrying.asStateFlow()

        /**
         * Runtime permissions still missing, for `:app` to request. Empty means a connect will
         * not fail on permissions. This module never prompts — it has no UI.
         */
        val missingPermissions: List<String>
            get() = environment.missingPermissions()

        /** The address the fast path will try next, or `null` if the next connect must scan. */
        suspend fun rememberedDevice(): String? = rememberedDevices.lastAddress()

        /**
         * Forgets the remembered device, forcing the next connect to scan.
         *
         * Also disarms auto-reconnect and **supersedes any attempt already in flight**, exactly as
         * [disconnect] does (round-1 MAJOR M1). Without the generation bump the running attempt
         * ran to completion and `openSession` re-remembered the address just forgotten — and since
         * the forget had also disarmed the retries, the van's actual failure mode was: swap the
         * dongle at a rest stop, press Forget, and the app quietly re-learns the old unit and
         * never looks for the new one.
         */
        suspend fun forgetRememberedDevice() {
            withContext(dispatcher) {
                disarmReconnect("remembered device forgotten")
                generation++
                // A superseded attempt publishes nothing, so a forget landing mid-handshake would
                // leave Connecting/Scanning on screen with nothing alive to move it off. A live
                // Ready link is untouched: forgetting an address is about the *next* connect, not
                // about hanging up on this one.
                if (mutableState.value == LinkState.Connecting || mutableState.value == LinkState.Scanning) {
                    publish(generation, LinkState.Disconnected)
                }
                rememberedDevices.forget()
                logger.log("remembered device cleared")
            }
        }

        /**
         * Connects, and *stays* connected: this arms auto-reconnect, so a recoverable failure —
         * now or hours from now — schedules another attempt instead of parking for good.
         */
        override suspend fun connect() {
            withContext(dispatcher) {
                // A fresh explicit connect supersedes any scheduled retry and restarts the
                // backoff: the user asking again is not the eleventh failure in a row.
                cancelScheduledReconnect("superseded by an explicit connect")
                autoReconnect = true
                reconnectAttempts = 0
                // An explicit connect is not a retry: whatever the user is being shown, they are
                // being shown it because they just asked, not because the module is persisting.
                mutableRetrying.value = false
                runConnectAttempt()
            }
        }

        override suspend fun disconnect() {
            withContext(dispatcher) {
                disarmReconnect("user disconnected")
                generation++
                release()
                publish(generation, LinkState.Disconnected)
            }
        }

        /**
         * Every attempt, with the exception boundary a scheduled job has no caller to provide
         * (round-1 BLOCKER B1).
         *
         * [attemptConnectOnce] reaches three collaborators that can throw things this module never
         * wraps: `scanner.scan` (OEM stacks raise `IllegalStateException` when the adapter dies
         * mid-sweep — `AndroidBleScanner` only guards `SecurityException`),
         * `environment.isBluetoothEnabled` (`SecurityException` on API 31+), and `connectGatt`.
         * Before OBD-23 the only caller was a `viewModelScope`, which has a boundary; the
         * unattended retry has none, and without one the throw escaped the job and left the link
         * parked in `Scanning` forever, nothing scheduled and nothing logged — every later key-on
         * ignored, which is precisely the failure this feature exists to prevent.
         *
         * The boundary wraps **both** callers rather than only the retry. The blocker was found on
         * the unattended path, but an explicit `connect()` that throws leaves the same wedged
         * `Scanning` behind — and the `ObdLink` contract already says failures surface through
         * `state` rather than as throws. One boundary, one behaviour, no attended/unattended
         * split to reason about. `CancellationException` is rethrown untouched: swallowing it
         * would break structured concurrency and make `disconnect()` unreliable.
         */
        private suspend fun runConnectAttempt() {
            try {
                attemptConnectOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (
                @Suppress("TooGenericExceptionCaught") unexpected: Throwable,
            ) {
                recoverFromAbnormalAttempt(unexpected)
            }
        }

        /**
         * One pass of the connect flow, shared by [connect] and every scheduled retry.
         *
         * The trailing bookkeeping is the whole reconnect state machine: success clears the
         * backoff, failure schedules the next attempt — and a *superseded* attempt does neither,
         * because [scheduleReconnect] refuses a stale generation outright. That refusal is the
         * module's core invariant applied to OBD-23: a stale attempt does no work, and
         * "scheduling the next retry" is work.
         */
        private suspend fun attemptConnectOnce() {
            release()
            val attempt = ++generation
            val stored = rememberedDevices.lastAddress()
            val remembered = stored?.takeIf(BluetoothAddress::isValid)
            val plan = planConnect(environment, remembered)
            logger.log("connect plan: $plan")
            val ready =
                when (plan) {
                    is ConnectPlan.Abort -> {
                        publish(attempt, LinkState.Error(plan.error))
                        false
                    }
                    is ConnectPlan.Direct -> connectDirect(plan.address, attempt, stored)
                    ConnectPlan.Scan -> scanAndConnect(attempt, stored)
                }
            if (ready) {
                reconnectAttempts = 0
                mutableRetrying.value = false
            } else {
                scheduleReconnect(attempt)
            }
        }

        /**
         * The remembered-device fast path, and the fallback when it does not answer. The fallback
         * is generation-gated too: a superseded attempt must not start a scan (or another
         * session) on a stale ticket.
         */
        @Suppress("ReturnCount") // Same reason as openSession: a stale-generation check has to
        // follow every suspension point, and an early return says that more safely than nesting.
        private suspend fun connectDirect(
            address: String,
            attempt: Long,
            preferred: String?,
        ): Boolean {
            if (openSession(address, attempt)) {
                return true
            }
            if (attempt != generation) {
                return false
            }
            logger.log("direct connect to remembered $address failed; falling back to scan")
            return scanAndConnect(attempt, preferred)
        }

        /**
         * Books the next attempt after [attempt] failed, or explains why there will not be one.
         *
         * Three refusals, in order of how badly getting them wrong would hurt:
         * 1. **Stale generation** — someone else (a `disconnect`, a newer `connect`) has taken
         *    over. Scheduling here would resurrect an attempt the module has already decided
         *    lost, which is exactly what generation-gating exists to prevent.
         * 2. **Disarmed** — the user said stop, or a previous failure was terminal.
         * 3. **Terminal cause / spent budget** — retrying cannot help, so stop and say so once.
         */
        @Suppress("ReturnCount") // Five guard clauses, and flattening them is the whole point:
        // each one is a distinct reason not to retry, and each has to be checkable on its own.
        // Nested, a reviewer could no longer see at a glance that the generation check comes
        // first — and it is the one that must.
        private fun scheduleReconnect(attempt: Long) {
            if (attempt != generation) {
                logger.log("connect attempt superseded; it does not schedule a reconnect")
                return
            }
            if (!autoReconnect) {
                return
            }
            // Round-1 NIT: this used to be a bare `?: return`, silent — the same shape as the
            // blocker above it. Reaching it means an attempt failed without leaving a typed error
            // behind, which is a bug in whoever failed, not a state to sit in quietly.
            val cause = (mutableState.value as? LinkState.Error)?.cause
            if (cause == null) {
                logger.log(
                    "attempt $attempt failed without publishing a cause (state=${mutableState.value}); not retrying",
                )
                return
            }
            if (ReconnectPolicy.classify(cause) == Recoverability.TERMINAL) {
                disarmReconnect("terminal link failure: $cause")
                return
            }
            if (reconnectAttempts >= config.reconnectMaxAttempts) {
                disarmReconnect("gave up after ${config.reconnectMaxAttempts} attempts; last cause $cause")
                return
            }
            val next = ++reconnectAttempts
            val wait = ReconnectPolicy.delayFor(next, config)
            // "reconnect scheduled", not "reconnect attempt": a booking and a death used to share
            // a prefix, which made the narrative ambiguous to grep — in a test, and in a drive log.
            logger.log("reconnect scheduled: attempt $next of ${config.reconnectMaxAttempts} in $wait (cause $cause)")
            mutableRetrying.value = true
            reconnectJob =
                linkScope.launch {
                    delay(wait)
                    // Cleared before the attempt runs, not after: from here on this job *is* the
                    // current attempt, and an explicit connect() arriving mid-attempt has to win
                    // on the generation counter (which it does) rather than by cancelling the
                    // coroutine it is already racing.
                    reconnectJob = null
                    runConnectAttempt()
                }
        }

        /**
         * Turns a throw out of an attempt into ordinary, visible failure bookkeeping.
         *
         * The error is [LinkError.Unknown] and therefore recoverable, which is the honest reading:
         * a BLE stack that threw on one sweep routinely works on the next, and the attempt budget
         * already bounds how long the module is willing to believe that. What must never happen is
         * silence — so this both publishes and logs, and [scheduleReconnect] says on the way out
         * whether anything further is coming.
         */
        private fun recoverFromAbnormalAttempt(failure: Throwable) {
            val attempt = generation
            logger.log("${ReconnectPolicy.ABNORMAL_ATTEMPT}: $failure")
            publish(attempt, LinkState.Error(LinkError.Unknown("${ReconnectPolicy.ABNORMAL_ATTEMPT}: $failure")))
            scheduleReconnect(attempt)
        }

        /** Stops retrying for good, until someone calls [connect] again. */
        private fun disarmReconnect(reason: String) {
            cancelScheduledReconnect(reason)
            if (autoReconnect) {
                logger.log("auto-reconnect disarmed: $reason")
            }
            autoReconnect = false
            reconnectAttempts = 0
            // The whole point of [retrying]: this is the edge :app renders a Retry button on.
            mutableRetrying.value = false
        }

        private fun cancelScheduledReconnect(reason: String) {
            val pending = reconnectJob ?: return
            reconnectJob = null
            pending.cancel(CancellationException(reason))
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

        @Suppress("ReturnCount") // One return per stale-generation gate, and there are two: one
        // before the sweep and one after. Both are load-bearing; see below.
        private suspend fun scanAndConnect(
            attempt: Long,
            preferred: String?,
        ): Boolean {
            // Round-1 MINOR m2: the gate below (after the sweep) was the only one, so a
            // superseded attempt still *ran* a scan and only discarded the result. The window is
            // real — `runConnectAttempt` suspends in `rememberedDevices.lastAddress()` (a
            // DataStore read) between taking its generation and arriving here. Android allows an
            // app 5 `startScan` calls per rolling 30 s, so a wasted sweep is not free: it is a
            // fifth of the budget a user burns through by tapping Connect twice.
            if (attempt != generation) {
                logger.log("connect attempt superseded before the sweep; no scan started")
                return false
            }
            publish(attempt, LinkState.Scanning)
            val outcome = scanner.scan(config.scanPasses(), preferred)
            // The scan suspends for seconds; a disconnect() or newer connect() may have moved
            // the generation on. A stale attempt discards its result instead of opening a
            // session nothing tracks.
            if (attempt != generation) {
                logger.log("connect attempt superseded during scan; result discarded")
                return false
            }
            return when (outcome) {
                is ScanOutcome.Failed -> {
                    publish(attempt, LinkState.Error(outcome.error))
                    false
                }
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
            val opened = GattSession(transport, scope, config, logger, traffic)
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
         * The peer dropped after Ready — the van's ignition taking the OBD port with it, most of
         * the time. The state is published and the retry is booked *before* the session is
         * released, because [release] cancels the scope this very callback is running in and
         * anything after it is not guaranteed to run. The session is closed rather than merely
         * forgotten: only `close()` hands a GATT client interface back to the system, and a link
         * that drops daily without closing eventually stops connecting at all.
         */
        private fun onSessionTerminated(error: LinkError) {
            // Deliberately passed the *current* generation rather than a captured one: this
            // callback can only fire from the tracked session's own event loop, and release()
            // cancels that loop.
            //
            // Round-1 MINOR m1: the ordering claim that used to sit here was wrong. connect()
            // releases before bumping the generation, but disconnect() bumps FIRST and releases
            // second — so what actually protects this is not the ordering, it is that neither
            // path suspends between the two. The event loop cannot be scheduled in a gap that
            // does not exist. **Inserting any suspension point between `generation++` and
            // `release()` in disconnect() would break that**, and a stale session would then
            // publish over a newer attempt.
            publish(generation, LinkState.Error(error))
            scheduleReconnect(generation)
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

        /**
         * Publishes [state] only if [attempt] is still the current connect generation, and is
         * the **only** writer of [mutableState] — which is what lets the OBD-48 capture tap
         * live here and be sure it cannot miss a transition. Callers that are the current
         * generation by construction (`disconnect`, `onSessionTerminated`) pass [generation]
         * itself rather than writing the flow directly.
         *
         * The tap fires on change only: that is exactly what a `StateFlow` collector sees, and
         * a repeated value is not a transition.
         */
        private fun publish(
            attempt: Long,
            state: LinkState,
        ) {
            if (attempt != generation) {
                return
            }
            if (mutableState.value != state) {
                traffic.record(TrafficEntry.Link(state))
            }
            mutableState.value = state
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
