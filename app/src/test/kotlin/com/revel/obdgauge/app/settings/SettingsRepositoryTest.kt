package com.revel.obdgauge.app.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.revel.obdgauge.app.gauge.GaugeThresholds
import com.revel.obdgauge.model.MeasurementUnit
import com.revel.obdgauge.model.PidIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * OBD-21 AC: "Settings persist across app restart (DataStore-backed)". Against a real
 * Preferences DataStore writing to a temp file — no Robolectric needed, mirroring
 * `:core:ble`'s `RememberedDeviceStoreTest` (the repository takes a `DataStore`, not a
 * `Context`).
 */
class SettingsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults are returned before anything is saved`() =
        runTest {
            assertEquals(AppSettings(), newRepository().settings.first())
        }

    @Test
    fun `a write survives a simulated restart (fresh repository instance, same file)`() =
        runTest {
            val file = temporaryFolder.newFile("settings-${counter++}.preferences_pb").also { it.delete() }

            // Two DataStore instances over the same file may not be simultaneously active
            // (AndroidX DataStore enforces single-active-instance-per-file) — cancelling the
            // first instance's scope before opening the second is what actually simulates a
            // process restart (the old process is gone) rather than two live instances racing.
            val firstScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
            val first = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = firstScope) { file })
            first.update {
                it.copy(
                    keepScreenOn = true,
                    units = UnitPreferences(temperatureUnit = MeasurementUnit.CELSIUS),
                )
            }
            firstScope.cancel()

            val secondScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())
            val second = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = secondScope) { file })
            val restarted = second.settings.first()
            secondScope.cancel()

            assertEquals(true, restarted.keepScreenOn)
            assertEquals(MeasurementUnit.CELSIUS, restarted.units.temperatureUnit)
        }

    @Test
    fun `update layers onto the previously saved value, not just the last write`() =
        runTest {
            val repository = newRepository()

            repository.update { it.copy(keepScreenOn = true) }
            repository.update {
                it.copy(
                    thresholdOverrides = mapOf(PidIds.COOLANT to GaugeThresholds(greenMax = 210.0)),
                )
            }

            val settings = repository.settings.first()
            assertEquals(true, settings.keepScreenOn)
            assertEquals(210.0, settings.thresholdOverrides.getValue(PidIds.COOLANT).greenMax)
        }

    @Test
    fun `a corrupted store reads as defaults instead of failing every read forever`() =
        runTest {
            val corrupted = temporaryFolder.newFile("corrupted-${counter++}.preferences_pb")
            corrupted.writeBytes(byteArrayOf(0x42, 0x00, 0x7F, 0x13, 0x37, 0x00, 0x01))
            val repository = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { corrupted })

            assertEquals(AppSettings(), repository.settings.first())
        }

    private fun newRepository(): DataStoreSettingsRepository =
        DataStoreSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFile("settings-${counter++}.preferences_pb").also { it.delete() }
            },
        )

    private companion object {
        var counter = 0
    }
}
