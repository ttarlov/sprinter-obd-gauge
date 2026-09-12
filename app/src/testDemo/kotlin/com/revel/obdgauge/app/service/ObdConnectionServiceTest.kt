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
import com.revel.obdgauge.app.gauge.GAUGE_CATALOG
import com.revel.obdgauge.app.recording.RecordingState
import com.revel.obdgauge.app.recording.logsDir
import com.revel.obdgauge.app.recording.readSessionIndex
import com.revel.obdgauge.model.PidIds
import com.revel.obdgauge.model.Reading
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
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
import java.time.Instant

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

    // ---- OBD-70: the recorder, field-injected and wired against the real demo Hilt graph ----

    @Test
    fun `the recorder is constructed on create against the real demo loggablePids and poll set`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        assertNotNull(service.recorder)
        // demo's LoggablePidsModule: DASHBOARD_PIDS + RPM_PID_DEFINITION, 5 channels.
        assertEquals(5, service.loggablePids.size)

        controller.destroy()
    }

    @Test
    fun `recordingBridge start creates a CSV with a header in the logs directory and widens the poll set`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        assertEquals(GAUGE_CATALOG.size, service.activePollSet.activePids().size)

        service.recordingBridge.start()

        assertTrue(service.recordingBridge.state.value is RecordingState.Recording)
        val logsDirectory = logsDir(service)
        val csvFile = logsDirectory.listFiles { file -> file.name.startsWith("obdlog_") }?.firstOrNull()
        assertNotNull(csvFile)
        assertEquals("# sprinter-obd-gauge log v1", csvFile!!.readLines().first())
        // demo's loggablePids (5) happen to be a subset of GAUGE_CATALOG (6, adds "speed"), so the
        // union doesn't grow the COUNT here — prod's much larger PidCatalog.definitions set does.
        // What's actually load-bearing (and true regardless of flavor): every recorded id is polled.
        assertEquals(service.loggablePids, service.activePollSet.recordingPids.value)
        val activeIds =
            service.activePollSet
                .activePids()
                .map { it.id }
                .toSet()
        assertTrue(service.loggablePids.all { it.id in activeIds })

        service.recordingBridge.stop()
        controller.destroy()
    }

    @Test
    fun `ticks append rows and stop flushes, updates the index, and reverts the poll set`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        service.recordingBridge.start()
        val recordingState = service.recordingBridge.state.value as RecordingState.Recording
        // Real 1 Hz ticker (the Hilt-wired production interval) — wait past one tick.
        runBlocking { delay(TICK_WAIT_MILLIS) }
        service.recordingBridge.stop()

        val dataLines = recordingState.file.readLines().drop(HEADER_LINE_COUNT)
        assertTrue("expected at least one appended row, got ${dataLines.size}", dataLines.isNotEmpty())
        assertTrue(service.recordingBridge.state.value is RecordingState.Idle)

        val logsDirectory = logsDir(service)
        val index = readSessionIndex(logsDirectory)
        val entry = index.first { it.file == recordingState.file.name }
        assertNotNull(entry.endedAt)
        assertTrue(entry.rows > 0)
        assertEquals(GAUGE_CATALOG.size, service.activePollSet.activePids().size)

        controller.destroy()
    }

    @Test
    fun `onDestroy flushes and stops a recording still in progress rather than orphaning it`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()

        service.recordingBridge.start()
        val recordingState = service.recordingBridge.state.value as RecordingState.Recording

        controller.destroy()

        assertTrue(service.recordingBridge.state.value is RecordingState.Idle)
        val logsDirectory = logsDir(service)
        val entry = readSessionIndex(logsDirectory).first { it.file == recordingState.file.name }
        assertNotNull(entry.endedAt)
    }

    // ---- OBD-70: an active recording inhibits OBD-69's idle-stop ----

    @Test
    fun `an active recording inhibits the idle watchdog`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        // Simulate "recording in progress" without waiting on the real ticker: publish the state
        // the real Recorder would, straight through the same recordingBridge the idle check reads.
        service.recordingBridge.publish(
            RecordingState.Recording(startedAtMillis = 0L, file = java.io.File("unused"), rowCount = 1),
        )

        val pastTheTimeout = service.idleTracker.lastDataAtMillis + IDLE_MILLIS_WELL_PAST_TIMEOUT
        val stopped = service.checkIdleAndMaybeStop(pastTheTimeout)

        assertFalse(stopped)
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.destroy()
    }

    @Test
    fun `idle-stop resumes once the recording bridge returns to Idle`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        service.recordingBridge.publish(RecordingState.Idle)

        val pastTheTimeout = service.idleTracker.lastDataAtMillis + IDLE_MILLIS_WELL_PAST_TIMEOUT
        val stopped = service.checkIdleAndMaybeStop(pastTheTimeout)

        assertTrue(stopped)
        controller.destroy()
    }

    // ---- OBD-71: the engine-off + user-presence watchdog ----
    //
    // A fresh RecordingVehicleDataSource replaces the real demo-flavor GRADE_CLIMB fake (RPM
    // 2000-3200, never 0) via the same field-swap seam `onDestroy cancels the service scope...`
    // above already uses, so these tests can drive RPM directly instead of waiting on a script.
    // evaluateEngineOffAndMaybeStop is called directly (not through the real 1 s watchdog loop),
    // mirroring how the OBD-69 tests above drive checkIdleAndMaybeStop deterministically.

    @Test
    fun `RPM greater than zero never triggers the engine-off watchdog, present or absent`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val fake = RecordingVehicleDataSource()
        service.dataSource = fake
        fake.setReadings(mapOf(PidIds.RPM to freshRpm(2400.0)))

        val presentAction = service.evaluateEngineOffAndMaybeStop(ENGINE_OFF_START, userPresent = true)
        val absentAction = service.evaluateEngineOffAndMaybeStop(ENGINE_OFF_START + 1, userPresent = false)

        assertEquals(EngineOffAction.None, presentAction)
        assertEquals(EngineOffAction.None, absentAction)
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.destroy()
    }

    @Test
    fun `engine-off confirmed, user absent, past the silent grace stops - even with non-RPM data still emitting`() {
        // The exact OBD-71 overnight scenario this fix targets: RPM reads a confirmed 0, but the
        // ECU keeps trickling other data (coolant cooling) as it does all night — that non-RPM
        // data arriving must not matter once RPM says the engine is off and nobody's watching.
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val fake = RecordingVehicleDataSource()
        service.dataSource = fake
        fake.setReadings(mapOf(PidIds.RPM to freshRpm(0.0), "coolant" to freshCoolant()))
        service.evaluateEngineOffAndMaybeStop(ENGINE_OFF_START, userPresent = false)

        val confirmedAtMillis = ENGINE_OFF_START + ENGINE_OFF_DEBOUNCE
        fake.setReadings(mapOf(PidIds.RPM to freshRpm(0.0), "coolant" to freshCoolant()))
        val atConfirm = service.evaluateEngineOffAndMaybeStop(confirmedAtMillis, userPresent = false)
        assertEquals(EngineOffAction.None, atConfirm)
        assertFalse(shadowOf(service).isStoppedBySelf)

        fake.setReadings(mapOf(PidIds.RPM to freshRpm(0.0), "coolant" to freshCoolant()))
        val action =
            service.evaluateEngineOffAndMaybeStop(
                confirmedAtMillis + ENGINE_OFF_SILENT_GRACE,
                userPresent = false,
            )

        assertEquals(EngineOffAction.Stop, action)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)

        controller.destroy()
    }

    @Test
    fun `engine-off confirmed, user present, shows the prompt then stops on countdown-expiry`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val fake = RecordingVehicleDataSource()
        service.dataSource = fake
        fake.setReadings(mapOf(PidIds.RPM to freshRpm(0.0)))
        service.evaluateEngineOffAndMaybeStop(ENGINE_OFF_START, userPresent = true)

        val confirmedAtMillis = ENGINE_OFF_START + ENGINE_OFF_DEBOUNCE
        val shown = service.evaluateEngineOffAndMaybeStop(confirmedAtMillis, userPresent = true)

        assertTrue(shown is EngineOffAction.ShowPrompt)
        // Published through the same bridge the dialog observes — pins the UI wiring, not just
        // the internal decision.
        assertEquals(shown, service.engineOffBridge.state.value)
        assertFalse(shadowOf(service).isStoppedBySelf)

        val stopAction =
            service.evaluateEngineOffAndMaybeStop(confirmedAtMillis + ENGINE_OFF_PROMPT_TIMEOUT, userPresent = true)

        assertEquals(EngineOffAction.Stop, stopAction)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(shadowOf(service).isForegroundStopped)

        controller.destroy()
    }

    @Test
    fun `Keep monitoring cancels the prompt-path stop and the connection stays up`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val fake = RecordingVehicleDataSource()
        service.dataSource = fake
        fake.setReadings(mapOf(PidIds.RPM to freshRpm(0.0)))
        service.evaluateEngineOffAndMaybeStop(ENGINE_OFF_START, userPresent = true)
        val confirmedAtMillis = ENGINE_OFF_START + ENGINE_OFF_DEBOUNCE
        service.evaluateEngineOffAndMaybeStop(confirmedAtMillis, userPresent = true)

        // The dialog's button, exercised through the real bridge (not the controller directly).
        service.engineOffBridge.keepMonitoring()

        val action =
            service.evaluateEngineOffAndMaybeStop(confirmedAtMillis + ENGINE_OFF_PROMPT_TIMEOUT, userPresent = true)

        assertEquals(EngineOffAction.None, action)
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.destroy()
    }

    @Test
    fun `an active recording inhibits the engine-off silent stop too`() {
        val controller = Robolectric.buildService(ObdConnectionService::class.java)
        val service = controller.create().get()
        val fake = RecordingVehicleDataSource()
        service.dataSource = fake
        service.recordingBridge.publish(
            RecordingState.Recording(startedAtMillis = 0L, file = java.io.File("unused"), rowCount = 1),
        )
        fake.setReadings(mapOf(PidIds.RPM to freshRpm(0.0)))
        service.evaluateEngineOffAndMaybeStop(ENGINE_OFF_START, userPresent = false)

        val action =
            service.evaluateEngineOffAndMaybeStop(
                ENGINE_OFF_START + ENGINE_OFF_DEBOUNCE + ENGINE_OFF_SILENT_GRACE,
                userPresent = false,
            )

        assertEquals(EngineOffAction.None, action)
        assertFalse(shadowOf(service).isStoppedBySelf)

        controller.destroy()
    }

    private fun freshRpm(value: Double): Reading = Reading(PidIds.RPM, value, Instant.EPOCH, stale = false)

    private fun freshCoolant(): Reading = Reading("coolant", 150.0, Instant.EPOCH, stale = false)

    private companion object {
        // Comfortably past the 20-min IDLE_TIMEOUT_MILLIS (file-private to ObdConnectionService).
        const val IDLE_MILLIS_WELL_PAST_TIMEOUT = 25L * 60 * 1000
        const val HEADER_LINE_COUNT = 6

        // Mirrors EngineOffWatchdog.kt's file-private ENGINE_OFF_*_MILLIS constants (not visible
        // here — `private` in Kotlin is file-scoped, stricter than `internal`).
        const val ENGINE_OFF_START = 1_000_000L
        const val ENGINE_OFF_DEBOUNCE = 45L * 1000
        const val ENGINE_OFF_PROMPT_TIMEOUT = 20L * 1000
        const val ENGINE_OFF_SILENT_GRACE = 30L * 1000

        // Comfortably past the recorder's real 1 Hz tick, without padding the suite too much.
        const val TICK_WAIT_MILLIS = 1_300L
    }
}
