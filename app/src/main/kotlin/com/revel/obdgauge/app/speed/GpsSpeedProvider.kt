package com.revel.obdgauge.app.speed

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** One GPS speed observation: ground speed and, when the platform reports it, its accuracy. */
data class GpsSpeedSample(
    val speedMps: Double,
    val accuracyMps: Double?,
)

/**
 * The seam between the calibration engine and the phone's GPS — an interface so [SpeedCalibrator]
 * (and its tests) never touch `android.location`. The `prod` flavor binds the real
 * [GpsSpeedProvider]; the `demo` flavor binds nothing, so every injection point there resolves to
 * `Optional.empty()` and the correction feature stays dormant (factor 1.0), exactly like the
 * `LinkController` seam.
 */
interface SpeedSource {
    /** Latest GPS speed sample, or `null` before the first fix (and while stopped). */
    val samples: StateFlow<GpsSpeedSample?>

    /** Begin listening for GPS updates. Safe to call when already started (idempotent). */
    fun start()

    /** Stop listening and release the location updates. Safe to call when already stopped. */
    fun stop()
}

/**
 * Real [SpeedSource] over [LocationManager]'s `GPS_PROVIDER`, kept deliberately thin: it holds no
 * calibration logic (that all lives in the pure [SpeedCalibration]), only the platform glue that
 * cannot be unit-tested. Foreground-only — `MainActivity` calls [start]/[stop] from its
 * `onStart`/`onStop`, so nothing polls GPS while the app is backgrounded.
 *
 * ### Never crashes the app
 * Everything that can throw — a missing permission (`SecurityException`), a device with no GPS
 * provider, a provider that is disabled — is caught and turns into "no samples" (the flow stays
 * at its last value or `null`). A dormant provider is the correct degraded state: the tile falls
 * back to the raw ECU speed. No new Gradle dependency: this is the platform `LocationManager`,
 * not `play-services-location`.
 *
 * ### Accuracy on older devices
 * [Location.getSpeedAccuracyMetersPerSecond] arrived in API 26. [Location.hasSpeedAccuracy]
 * guards it; when absent, the sample carries a `null` accuracy, which [SpeedCalibration] treats
 * as "unknown but allowed" rather than rejecting the fix outright.
 */
@Singleton
class GpsSpeedProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SpeedSource {
        private val mutableSamples = MutableStateFlow<GpsSpeedSample?>(null)
        override val samples: StateFlow<GpsSpeedSample?> = mutableSamples.asStateFlow()

        private val locationManager: LocationManager? =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

        private val listener =
            LocationListener { location ->
                mutableSamples.value = location.toSample()
            }

        private var listening = false

        override fun start() {
            if (listening) return
            val manager = locationManager ?: return
            try {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_UPDATE_INTERVAL_MS,
                    MIN_UPDATE_DISTANCE_M,
                    listener,
                    Looper.getMainLooper(),
                )
                listening = true
            } catch (e: SecurityException) {
                // Permission not granted — stay dormant, factor 1.0. Not an error to surface.
                Log.i(TAG, "GPS updates unavailable (permission): ${e.message}")
            } catch (e: IllegalArgumentException) {
                // No GPS provider on this device.
                Log.i(TAG, "GPS provider unavailable: ${e.message}")
            }
        }

        override fun stop() {
            if (!listening) return
            listening = false
            try {
                locationManager?.removeUpdates(listener)
            } catch (e: SecurityException) {
                Log.i(TAG, "removeUpdates rejected: ${e.message}")
            }
        }

        private fun Location.toSample(): GpsSpeedSample =
            GpsSpeedSample(
                speedMps = speed.toDouble(),
                accuracyMps =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) {
                        speedAccuracyMetersPerSecond.toDouble()
                    } else {
                        null
                    },
            )

        private companion object {
            const val TAG = "GpsSpeedProvider"
            const val MIN_UPDATE_INTERVAL_MS = 1_000L
            const val MIN_UPDATE_DISTANCE_M = 0f
        }
    }
