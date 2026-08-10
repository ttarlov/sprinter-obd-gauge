package com.revel.obdgauge.ble.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.revel.obdgauge.ble.BleLogger
import com.revel.obdgauge.model.LinkError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Real BLE scanning, kept as thin as the API allows: start a sweep, translate each
 * `ScanResult` into a [DiscoveredDevice], hand it to a [ScanStateMachine], act on the decision.
 * All the judgement lives in the state machine, which has no Android in it.
 *
 * Permission is verified by `BleObdLink` before this is called; the `SecurityException` catch is
 * belt-and-braces for the race where the user revokes permission mid-scan.
 */
@SuppressLint("MissingPermission")
class AndroidBleScanner
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val logger: BleLogger,
        private val filter: DongleFilter = DongleFilter(),
    ) : BleScanner {
        /** Not injected: the throttle window is a property of the platform, not of this app. */
        private val budget = ScanBudget()
        private val clock: () -> Long = System::currentTimeMillis

        override suspend fun scan(
            passes: List<ScanPass>,
            preferredAddress: String?,
        ): ScanOutcome {
            val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
            return if (scanner == null) {
                ScanOutcome.Failed(LinkError.BluetoothOff)
            } else {
                runScanPasses(passes) { pass -> budgetedPass(scanner, pass, preferredAddress) }
            }
        }

        /** Refuses a sweep the system would throttle anyway, and says so in the typed error. */
        private suspend fun budgetedPass(
            scanner: BluetoothLeScanner,
            pass: ScanPass,
            preferredAddress: String?,
        ): ScanOutcome {
            val now = clock()
            return if (budget.tryConsume(now)) {
                runPass(scanner, pass, preferredAddress)
            } else {
                val wait = budget.retryAfterMillis(now)
                logger.log("scan pass skipped: throttle budget spent, ${wait}ms to go")
                ScanOutcome.Failed(
                    LinkError.Unknown("${ScanStateMachine.THROTTLED}: retry in ${wait}ms"),
                )
            }
        }

        private suspend fun runPass(
            scanner: BluetoothLeScanner,
            pass: ScanPass,
            preferredAddress: String?,
        ): ScanOutcome {
            val machine = ScanStateMachine(filter, preferredAddress)
            val filters = if (pass.filterByServiceUuid) serviceUuidFilters() else emptyList()
            logger.log("scan pass: serviceUuidFilter=${pass.filterByServiceUuid} timeout=${pass.timeout}")

            var started: ScanCallback? = null
            val outcome =
                try {
                    withTimeoutOrNull(pass.timeout) {
                        suspendCancellableCoroutine { continuation ->
                            val callback = scanCallback(machine, continuation)
                            started = callback
                            runScan(scanner, filters, callback, continuation)
                        }
                    }
                } finally {
                    started?.let { callback -> runCatching { scanner.stopScan(callback) } }
                }

            return outcome ?: timedOut(machine)
        }

        private fun runScan(
            scanner: BluetoothLeScanner,
            filters: List<ScanFilter>,
            callback: ScanCallback,
            continuation: CancellableContinuation<ScanOutcome>,
        ) {
            try {
                scanner.startScan(filters, SCAN_SETTINGS, callback)
            } catch (denied: SecurityException) {
                logger.log("scan rejected: $denied")
                continuation.resumeIfActive(ScanOutcome.Failed(LinkError.PermissionDenied))
            }
        }

        private fun scanCallback(
            machine: ScanStateMachine,
            continuation: CancellableContinuation<ScanOutcome>,
        ): ScanCallback =
            object : ScanCallback() {
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    when (val decision = machine.onAdvertisement(result.toDiscoveredDevice())) {
                        is ScanDecision.Select -> {
                            logger.log("scan matched ${decision.device.address} (${decision.device.name ?: "unnamed"})")
                            continuation.resumeIfActive(ScanOutcome.Found(decision.device))
                        }
                        is ScanDecision.Fail -> continuation.resumeIfActive(ScanOutcome.Failed(decision.error))
                        is ScanDecision.Continue -> Unit
                    }
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    results.forEach { result -> onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result) }
                }

                override fun onScanFailed(errorCode: Int) {
                    val decision = machine.onScanFailed(errorCode)
                    if (decision is ScanDecision.Fail) {
                        logger.log("scan failed: ${decision.error}")
                        continuation.resumeIfActive(ScanOutcome.Failed(decision.error))
                    }
                }
            }

        private fun timedOut(machine: ScanStateMachine): ScanOutcome {
            logger.log("scan pass found nothing; saw ${machine.seenAddresses.size} other device(s)")
            val decision = machine.onTimeout()
            return if (decision is ScanDecision.Fail) {
                ScanOutcome.Failed(decision.error)
            } else {
                ScanOutcome.Failed(LinkError.DeviceNotFound)
            }
        }

        private fun serviceUuidFilters(): List<ScanFilter> =
            filter.serviceUuids.map { uuid ->
                ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
            }

        private companion object {
            val SCAN_SETTINGS: ScanSettings =
                ScanSettings
                    .Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build()
        }
    }

private fun CancellableContinuation<ScanOutcome>.resumeIfActive(outcome: ScanOutcome) {
    if (isActive) {
        resume(outcome)
    }
}

/**
 * Prefers the name from the advertisement record over `BluetoothDevice.name`: the latter reads
 * the bonded-device cache and needs `BLUETOOTH_CONNECT`, which a scan-only flow may not have.
 */
private fun ScanResult.toDiscoveredDevice(): DiscoveredDevice =
    DiscoveredDevice(
        address = device.address,
        name = scanRecord?.deviceName,
        serviceUuids = scanRecord?.serviceUuids.orEmpty().map(ParcelUuid::getUuid),
        rssi = rssi,
    )
