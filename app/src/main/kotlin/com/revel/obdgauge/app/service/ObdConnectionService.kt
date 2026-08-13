package com.revel.obdgauge.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import com.revel.obdgauge.app.MainActivity
import com.revel.obdgauge.app.R
import com.revel.obdgauge.app.link.LinkController
import com.revel.obdgauge.model.VehicleDataSource
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

/**
 * OBD-24: the `connectedDevice`-typed foreground service that keeps [VehicleDataSource] polling
 * alive with the screen off, and shows a persistent notification with the live link state and
 * headline reading while it does.
 *
 * ### Lifecycle ownership
 * [MainActivity] starts this service (as a foreground service, via
 * `ContextCompat.startForegroundService`) once, the moment the app launches — "the app's
 * connection lifecycle" this issue's brief refers to is "the app wants to be connected for as
 * long as it's in use," not "as long as a screen happens to be visible." [onCreate] builds the
 * notification channel, calls [startForegroundDegrading] (see its own KDoc for the round-1 B1
 * fix) with a "starting up" placeholder, acquires a [PowerManager.PARTIAL_WAKE_LOCK] (B3 — see
 * the Doze section below), then hands off to a [ConnectionServiceController] — every actual
 * decision (when to (re)start the data source, what the notification should say) lives there,
 * pure and unit-tested without Robolectric; see its KDoc for why a *second*, independent owner
 * of [VehicleDataSource.start]/[stop] is the right shape given `DashboardViewModel`'s existing,
 * pinned UI-gated lifecycle.
 *
 * ### Round-1 review fix (B5 MAJOR): START_NOT_STICKY, not START_STICKY, and why
 * The original `START_STICKY` choice was justified as "a process death mid-drive should resume
 * the service" — the reviewer's orphan scenario shows why that reasoning backfires: if the OS
 * (LMK) kills this app's whole *process* overnight — not a user swipe, which already hits
 * [onTaskRemoved] and calls `stopSelf()` cleanly first — `START_STICKY` has Android recreate this
 * service from a null `Intent` shortly after, with no [MainActivity] task the user ever asked
 * for, holding a live GATT link open to an always-hot OBD port, reachable only via force-stop.
 * `START_NOT_STICKY` means a process-level kill simply ends the session; the user reopens the app
 * (which they will, since driving is the only reason this app is running) to reconnect. The
 * content [PendingIntent] and Stop action below (B5) make the running service reachable/
 * dismissable from the notification either way, but staying stopped after a real process kill is
 * the safer default until OBD-23/OBD-25 give this service an actual reconnect policy to restart
 * *into* rather than just an empty one.
 *
 * [onTaskRemoved] treats "user swiped the app out of recents" as "disconnect" and stops the
 * service; [onDestroy] is where the controller is actually torn down, the wake lock released, and
 * [VehicleDataSource.stop] is called exactly once for a clean shutdown.
 *
 * ### Doze-mode behavior (written artifact per this issue's self-test plan — not automatable)
 * Round-1 review fix (B3 MAJOR): the previous version of this KDoc claimed a foreground service
 * alone keeps the CPU running — it doesn't. A foreground service (this one: [startForegroundDegrading]
 * + a persistent, ongoing notification) exempts the process from Doze/App Standby's *bucketing*
 * restrictions (deferred jobs, batched/deferred network access) — that part was right. What was
 * wrong: bucketing exemption is not the same as keeping the SoC out of suspend. With the screen
 * off and no wake lock held, the device can still suspend the CPU between radio wakeups, and a
 * plain `delay()`-scheduled coroutine poll loop stops firing within seconds of that — exactly
 * the failure mode the 10-min screen-off AC would have caught on-device (never caught by
 * Robolectric, which doesn't model CPU suspend at all). Fix: a [PowerManager.PARTIAL_WAKE_LOCK]
 * (screen may turn off, CPU must not sleep) is acquired for the service's entire lifetime —
 * [onCreate] to [onDestroy] — not per-poll-cycle, since the cost of holding one continuously for
 * a bounded "app is actively driving" session is the whole point of this service existing. A
 * generous [WAKE_LOCK_TIMEOUT_MILLIS] safety-net timeout is passed to `acquire()` (Android lint's
 * `WakelockTimeout` check flags an untimed indefinite hold as a leak risk) purely as a backstop
 * against a future bug skipping [onDestroy] — under normal operation `release()` always runs
 * first. The one caveat repeated from before: this covers *this app's own process* scheduling,
 * not the BLE radio/link itself — a real dongle going out of range or losing power still drops
 * the connection regardless of wake lock or Doze state, which is
 * [ConnectionServiceController]'s "re-assert against a known stop" self-heal (not a full
 * transport reconnect policy — that's OBD-23) and, beyond that, orthogonal to this service.
 */
@AndroidEntryPoint
class ObdConnectionService : Service() {
    @Inject
    lateinit var dataSource: VehicleDataSource

    /**
     * OBD-25: published so `DashboardViewModel`'s UI-gated teardown can tell "nobody wants this
     * source" from "the service is keeping it alive with the screen off". See [PollKeepAlive].
     */
    @Inject
    lateinit var keepAlive: PollKeepAlive

    /**
     * OBD-25: present on `prod` only (`Optional.empty()` on `demo`). Used for exactly one thing —
     * hanging up the link when the *user* ends the session (the notification's Stop action, or
     * swiping the app out of recents). Never in reaction to a link-state transition; see
     * [LinkController]'s KDoc for why that distinction is the whole hazard resolution.
     */
    @Inject
    lateinit var linkController: Optional<LinkController>

    // internal (not private): ObdConnectionServiceTest substitutes a test double controller and
    // reads wakeLock/serviceScope state directly — Robolectric's ShadowService can't reproduce
    // the round-1 B1 crash or observe onDestroy's teardown any other way (see both classes' KDoc).
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var controller: ConnectionServiceController? = null
    internal var wakeLock: PowerManager.WakeLock? = null

    private var lastPosted: ServiceNotificationState? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val startingNotification =
            buildNotification(ServiceNotificationState(title = STARTING_TITLE, text = STARTING_TEXT))
        startForegroundDegrading(
            typed = {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    startingNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            },
            untyped = { ServiceCompat.startForeground(this, NOTIFICATION_ID, startingNotification, 0) },
        )
        wakeLock = acquireWakeLock(this)
        controller =
            ConnectionServiceController(
                dataSource = dataSource,
                scope = serviceScope,
                keepAlive = keepAlive,
                onStateChanged = ::postNotification,
            ).also { it.start() }
    }

    /** B5: an explicit Stop-action tap ends the session; every other start (including a bare
     * re-launch) just keeps the already-running service going. See this class's KDoc for why
     * [START_NOT_STICKY], not [START_STICKY]. */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            disconnectLinkOnUserStop()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** "App swiped out of recents" reads as "disconnect" — see this class's KDoc. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        disconnectLinkOnUserStop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * OBD-25: the two paths above are the user saying "we're done" — the notification's Stop
     * action and a swipe out of recents. Both must disarm `:core:ble`'s auto-reconnect, or the
     * app would go on quietly retrying a GATT link to an always-hot OBD port after the user
     * explicitly ended the session; `BleObdLink.disconnect()` is exactly that disarm.
     *
     * These are the **only** two link calls this file makes, and both are user gestures. Nothing
     * here reacts to a `LinkState` — see [ConnectionServiceController]'s ownership KDoc.
     *
     * Deliberately NOT launched on [serviceScope]: `stopSelf()` reaches `onDestroy`, which
     * cancels that scope, and a cancelled `disconnect()` would leave auto-reconnect armed — the
     * precise bug this is here to prevent. A one-shot scope outlives the service just long enough
     * to finish; it is not stored, and `disconnect()` is a bounded dispatcher hop plus a GATT
     * close, so there is nothing to leak.
     */
    private fun disconnectLinkOnUserStop() {
        val controller = linkController.orElse(null) ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { controller.disconnect() }
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        releaseWakeLock(wakeLock)
        wakeLock = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun postNotification(state: ServiceNotificationState) {
        // B7: one-line last-state guard — without it every reading tick re-posts the identical
        // text (measured: 22 posts / 3 distinct texts, ~14,400 binder round-trips/hour at 2Hz).
        // Gated behind the permission check (not before it) so a later-granted POST_NOTIFICATIONS
        // still posts the current state on the next state change rather than staying silently
        // stuck on whatever `lastPosted` was set to while ungranted. The decision itself is
        // extracted to `shouldPostNotification` (this file, top level) so it's directly testable
        // without Robolectric — see `PostNotificationDedupeTest`.
        if (!areNotificationsSafeToPost()) return
        if (!shouldPostNotification(state, lastPosted)) return
        lastPosted = state
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
    }

    /**
     * [NotificationManagerCompat.notify] on API 33+ requires the POST_NOTIFICATIONS runtime
     * grant (declared in the manifest, but not auto-granted) — checking first turns a missing
     * grant into "notification silently doesn't update," never a `SecurityException` crash. The
     * service (and the poll loop it keeps alive) is unaffected either way; only the visible
     * notification depends on this. B6: `MainActivity` requests this permission on first launch.
     */
    private fun areNotificationsSafeToPost(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun buildNotification(state: ServiceNotificationState): Notification =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // B5: a way back into the app, and an explicit way to end the session — the round-1
            // review's finding that neither existed at all.
            .setContentIntent(buildContentPendingIntent(this))
            .addAction(0, STOP_ACTION_TITLE, buildStopPendingIntent(this))
            .build()

    /**
     * `IMPORTANCE_LOW`: shows in the status bar/shade without a sound, vibration, or heads-up
     * pop — this notification exists to answer "are we still connected," not to interrupt.
     */
    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESCRIPTION
                setShowBadge(false)
            }
        getSystemService<NotificationManager>()?.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "obd_connection"
        const val CHANNEL_NAME = "OBD connection"
        const val CHANNEL_DESCRIPTION = "Shows the dongle link state and a live reading while connected."
        const val NOTIFICATION_ID = 1
        const val STARTING_TITLE = "OBD Gauge"
        const val STARTING_TEXT = "Starting…"
        const val STOP_ACTION_TITLE = "Stop"
    }
}

// File-scope (not the class's private companion object) — the class's own TooManyFunctions count
// pushed acquireWakeLock/releaseWakeLock/the two PendingIntent builders out to plain top-level
// functions (below), and Kotlin doesn't let file-scope code see a class's *private* companion
// object members, so their constants moved out here with them.
private const val ACTION_STOP = "com.revel.obdgauge.app.service.ACTION_STOP"
private const val REQUEST_CODE_CONTENT = 0
private const val REQUEST_CODE_STOP = 1
private const val WAKE_LOCK_TAG = "ObdGauge:ConnectionPoll"

// Safety-net only (ObdConnectionService's KDoc) — 12h covers any plausible single drive/session
// with generous headroom without tripping Android lint's WakelockTimeout warning for an untimed
// indefinite acquire().
private const val WAKE_LOCK_TIMEOUT_MILLIS = 12 * 60 * 60 * 1000L

private fun buildContentPendingIntent(context: Context): PendingIntent =
    PendingIntent.getActivity(
        context,
        REQUEST_CODE_CONTENT,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

private fun buildStopPendingIntent(context: Context): PendingIntent =
    PendingIntent.getService(
        context,
        REQUEST_CODE_STOP,
        Intent(context, ObdConnectionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

/** B3: acquires a held [PowerManager.PARTIAL_WAKE_LOCK] — see [ObdConnectionService]'s Doze KDoc. */
private fun acquireWakeLock(context: Context): PowerManager.WakeLock? {
    val manager = context.getSystemService<PowerManager>() ?: return null
    return manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
        setReferenceCounted(false)
        acquire(WAKE_LOCK_TIMEOUT_MILLIS)
    }
}

private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
    wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
}

/**
 * B1 BLOCKER belt-and-braces (reviews/OBD-24-round1.md): Robolectric's `ShadowService` doesn't
 * enforce the real `ForegroundServiceTypePolicy` GRANTED-permission check for `connectedDevice`
 * foreground services, so the fresh-install crash this guards against is invisible to this
 * suite — this function exists so the degrade-not-crash *logic itself* is at least directly,
 * deterministically testable without Robolectric (`StartForegroundDegradingTest`, `app/src/test`
 * — flavor-agnostic, since this function has no Android types in its own signature). Attempts
 * [typed] first; if it throws [SecurityException] — the real failure mode when none of the
 * `connectedDevice` type's allowed permissions are currently granted — falls back to [untyped]
 * (a plain, type-less `startForeground`) instead of letting the exception propagate and kill the
 * process before first paint. The manifest's `CHANGE_NETWORK_STATE` declaration
 * (`AndroidManifest.xml`) is the primary fix — a normal, install-time-auto-granted permission
 * that satisfies the typed check even before the user has granted `BLUETOOTH_CONNECT` — this is
 * the defense for whatever combination of OS/permission state that doesn't cover.
 */
@Suppress("SwallowedException")
internal fun startForegroundDegrading(
    typed: () -> Unit,
    untyped: () -> Unit,
) {
    try {
        typed()
    } catch (expectedWhenTypeGrantMissing: SecurityException) {
        // Deliberately not logged/rethrown: this function is intentionally platform-agnostic (no
        // android.util.Log — that would break StartForegroundDegradingTest's plain-JUnit,
        // Robolectric-free run, per this KDoc). A SecurityException here is the ANTICIPATED
        // degrade-path trigger, not a bug to surface — untyped() below is the actual recovery.
        untyped()
    }
}

/**
 * B7 MINOR (round-1 review): the dedupe decision behind [ObdConnectionService.postNotification],
 * extracted to a pure top-level function for the same "test the seam directly" reason as
 * [startForegroundDegrading] — see `PostNotificationDedupeTest` (`app/src/test`).
 */
internal fun shouldPostNotification(
    new: ServiceNotificationState,
    previouslyPosted: ServiceNotificationState?,
): Boolean = new != previouslyPosted
