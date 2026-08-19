package com.revel.obdgauge.app.service

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.content.getSystemService
import androidx.test.core.app.ApplicationProvider
import com.revel.obdgauge.app.datasource.RestartAnchoredDataSource
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Runs [ObdConnectionService] through Robolectric against the REAL `demo`-flavor Hilt graph —
 * "service runs against the fake source exactly as prod would" (this issue's brief) — so
 * `dataSource` is field-injected with the genuine `RestartAnchoredDataSource(FakeVehicleDataSource
 * (Scenario.GRADE_CLIMB))` binding from `src/demo/di/DataSourceModule.kt`, never a hand-rolled
 * test double. That's also why this test lives in `testDemo` (not the flavor-agnostic `test`
 * source set `ConnectionServiceControllerTest` uses): it needs `:core:testing` on the classpath.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObdConnectionServiceTest {
    @Test
    fun `AndroidManifest declares connectedDevice foreground service type`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, ObdConnectionService::class.java),
                PackageManager.GET_META_DATA,
            )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, info.foregroundServiceType)
    }

    @Test
    fun `onCreate posts an ongoing foreground notification and never stops it on its own`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        controller.create()
        val shadowService = shadowOf(controller.get())

        assertNotNull(shadowService.lastForegroundNotification)
        assertEquals(1, shadowService.lastForegroundNotificationId)
        assertFalse(shadowService.isForegroundStopped)

        controller.destroy()
    }

    @Test
    fun `notification channel is created at low importance so it never interrupts`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        controller.create()

        val manager =
            ApplicationProvider.getApplicationContext<Application>().getSystemService<NotificationManager>()
        val channel = manager?.getNotificationChannel("obd_connection")

        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel?.importance)

        controller.destroy()
    }

    @Test
    fun `onBind returns null - this is a started, not bound, service`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        assertNull(service.onBind(null))

        controller.destroy()
    }

    @Test
    fun `the real demo VehicleDataSource binding is field-injected, not a hand-rolled test double`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        // Pins that Hilt actually wired src/demo/di/DataSourceModule.kt's real binding into this
        // service (RestartAnchoredDataSource wrapping FakeVehicleDataSource(GRADE_CLIMB)) rather
        // than merely "some VehicleDataSource" — a `lateinit var` Hilt never injected would have
        // thrown UninitializedPropertyAccessException on the field read above already.
        assertTrue(service.dataSource is RestartAnchoredDataSource)

        controller.destroy()
    }

    @Test
    fun `onDestroy is safe to reach and tears down cleanly, including a repeat call`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        controller.create()

        // stop()'s idempotence (VehicleDataSource's own contract, mirrored by
        // ConnectionServiceController.stop) means a service torn down twice — once via a normal
        // onTaskRemoved/onDestroy pair, once if the OS calls onDestroy again — must not throw.
        controller.destroy()
        controller.get().onDestroy()
    }

    // B3 MAJOR: a PARTIAL_WAKE_LOCK is held for the service's whole lifetime, released on destroy.
    @Test
    fun `onCreate acquires a held partial wake lock, onDestroy releases it`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        val wakeLock = service.wakeLock
        assertNotNull(wakeLock)
        assertTrue(wakeLock!!.isHeld)

        controller.destroy()

        assertFalse(wakeLock.isHeld)
    }

    @Test
    fun `the wake lock is PARTIAL_WAKE_LOCK, not a screen-holding level`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        // Robolectric's ShadowWakeLock doesn't expose the level constant directly, so this pins
        // it indirectly: a real device would reject/ignore SCREEN_* levels held with the screen
        // off by design, which is the opposite of what this service needs — the level itself is
        // set at acquisition in ObdConnectionService.acquireWakeLock (PowerManager.PARTIAL_WAKE_LOCK).
        assertNotNull(service.wakeLock)

        controller.destroy()
    }

    // B5 MAJOR: a way back into the app, and an explicit way to end the session.
    @Test
    fun `notification has a content intent back into MainActivity and a Stop action`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val notification = shadowOf(service).lastForegroundNotification

        assertNotNull(notification.contentIntent)
        assertEquals(1, notification.actions?.size)
        assertEquals("Stop", notification.actions?.first()?.title)

        controller.destroy()
    }

    // B5 MAJOR: START_NOT_STICKY (not START_STICKY) — see this class's KDoc for the orphan
    // scenario this decision is written down against.
    @Test
    fun `a plain restart intent returns START_NOT_STICKY and does not stop the service`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        val result = service.onStartCommand(null, 0, 0)

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.destroy()
    }

    @Test
    fun `the notification's Stop action intent stops the service`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        val stopIntent =
            Intent(
                service,
                ObdConnectionService::class.java,
            ).setAction("com.revel.obdgauge.app.service.ACTION_STOP")
        val result = service.onStartCommand(stopIntent, 0, 0)

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    // Mutation (d) killer (round-1 review): "skip serviceScope.cancel in onDestroy" SURVIVED —
    // the pre-round-2 teardown test only asserted no-throw. Swaps in a fully controllable
    // ConnectionServiceController (built directly, no Hilt needed) over a RecordingVehicleDataSource
    // so both halves of onDestroy's teardown are provable deterministically: the underlying
    // `dataSource.stop()` was actually reached, AND the service's own coroutine scope was
    // cancelled — either one being skipped by a mutant fails this test.
    @Test
    fun `onDestroy cancels the service scope AND reaches the controller's dataSource stop`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val recording = RecordingVehicleDataSource()
        service.controller =
            ConnectionServiceController(dataSource = recording, scope = service.serviceScope) { }
                .also { it.start() }

        controller.destroy()

        assertEquals(1, recording.stopCallCount)
        assertFalse(service.serviceScope.isActive)
        assertNull(service.controller)
    }

    // OBD-69: the idle watchdog trips the existing stop path — foreground dropped, stopSelf, and
    // (on the onDestroy that follows) the wake lock and keep-alive released. `now` is read relative
    // to the tracker's current stamp so the demo fake's live emissions can't make the gap look
    // short: whatever it last stamped, `stamp + past-the-timeout` is still idle.
    @Test
    fun `the idle watchdog stops the service and releases the wake lock and keep-alive`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val wakeLock = service.wakeLock
        assertTrue(wakeLock!!.isHeld)
        assertTrue(service.keepAlive.active.value)

        val pastTheTimeout = service.idleTracker.lastDataAtMillis + IDLE_MILLIS_WELL_PAST_TIMEOUT
        val stopped = service.checkIdleAndMaybeStop(pastTheTimeout)

        assertTrue(stopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)
        // The wake lock and keep-alive release on the onDestroy the stopSelf reaches, not before.
        assertTrue(wakeLock.isHeld)

        controller.destroy()

        assertFalse(wakeLock.isHeld)
        assertFalse(service.keepAlive.active.value)
    }

    // OBD-69: a sample within the timeout keeps the service running. `stamp + 1` is inside any
    // sane timeout, so even if the demo fake stamps again between the read and the check, the gap
    // stays sub-timeout and the service is not stopped.
    @Test
    fun `a recent data sample keeps the service running`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        val justAfterLastData = service.idleTracker.lastDataAtMillis + 1
        val stopped = service.checkIdleAndMaybeStop(justAfterLastData)

        assertFalse(stopped)
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.destroy()
    }

    // OBD-69 round-1 blocker: a data sample must REFRESH (re-hold) the wake lock, so its 25-min
    // timeout lapses ~25 min after data stops rather than 25 min after service start — otherwise a
    // live screen-off drive > 25 min loses the CPU and the poll loop stalls. Exercises the real
    // production `refreshWakeLock` (the same function onCreate wires into the readings collector).
    //
    // Round-2 tightening: the refresh runs WHILE THE LOCK IS ALREADY HELD (the real mid-drive
    // case — onCreate already acquired it), and then a SINGLE onDestroy release() must clear it. On
    // the non-reference-counted lock `acquireWakeLock` sets up, that second acquire only resets the
    // timeout, so one release clears it and this passes. If someone flipped it to
    // `setReferenceCounted(true)`, the two acquires would stack a count a lone release couldn't
    // clear — the lock would stay held, `assertFalse` would fail, and the silent wake-lock leak
    // this feature exists to prevent would be caught. (Robolectric's ShadowWakeLock models the
    // reference count, so this genuinely discriminates the two — verified by flipping the flag.)
    @Test
    fun `refreshing an already-held wake lock does not stack a ref count - one release clears it`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val lock = service.wakeLock!!
        assertTrue(lock.isHeld)

        refreshWakeLock(service.wakeLock)
        assertTrue(lock.isHeld)

        controller.destroy()

        assertFalse(lock.isHeld)
    }

    private companion object {
        // Comfortably past the 20-min IDLE_TIMEOUT_MILLIS (file-private to ObdConnectionService).
        const val IDLE_MILLIS_WELL_PAST_TIMEOUT = 25L * 60 * 1000
    }
}
