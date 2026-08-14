---
issue: OBD-61
round: 1
reviewers: [orchestrator small-track (D6)]
verdict: approved
gate: green
reviewed-commit: 9cf3c5f
covers: [OBD-61]
---

Small-track (D6) orchestrator review of the GPS-auto-calibrated Speed tile. `:app`-only, additive,
graceful. One hardening applied during review (`@Volatile state`); gate green at 9cf3c5f.

## Fix list
- [x] ✅ **Pure calibration engine correct.** `SpeedCalibration.accept` gates (both speeds ≥ 40 km/h,
      accuracy null-or-≤2 m/s, steadiness |Δ| ≤ 5, ratio ∈ [0.80,1.25]) and median-of-ring-buffer
      (≥5 samples else default) are sound. Division-by-zero is safe (Double → Infinity → fails the
      ratio gate, no throw). 21 exhaustive unit tests on the engine.
- [x] ✅ **No feedback loop.** The calibrator observes `real` (RealVehicleDataSource, raw km/h,
      pre-display) for its ratio math — it never sees the corrected value, so the factor cannot
      drift toward 1.0. Correction wraps `display` (mph); factor is unitless so it commutes with the
      km/h→mph conversion. Verified in `DataSourceModule` wiring.
- [x] ✅ **Live propagation.** `SpeedCorrectionDataSource` = `combine(readings, factor).stateIn(Eagerly)`,
      so a learned-factor change re-emits to the dashboard in prod (the flagged test used
      `UnconfinedTestDispatcher`; that is a harness detail, not a prod defect).
- [x] ✅ **Steadiness gate recovers, not deadlocks.** `prevEcuSpeedKmh` updates on every observation
      (accepted or not), so a hard-acceleration stretch cannot permanently wedge the gate — explicit
      test. (Builder's flagged deviation #2 is the correct behavior.)
- [x] ✅ **Graceful degradation.** No permission / no fix / demo (Optional.empty SpeedSource) → factor
      1.0 → `applyCorrection` passes every reading through byte-for-byte. Stale speed readings are
      skipped (never calibrate against a frozen last-known value).
- [x] ✅ **Isolation.** `:app`/`issues` only (module-isolation green). Speed added to `GAUGE_CATALOG`
      (swap-only) but NOT `DASHBOARD_PIDS`, so it is not a default tile and no Roborazzi ref changed.
      `DisplayUnitDataSource` untouched (wrapped, not modified). Demo DI chain untouched.
- [x] ✅ **Permission flow additive.** MainActivity requests FINE_LOCATION once (mirrors the
      notification-permission pattern), foreground-only GPS via onStart/onStop, idempotent. Does not
      touch the existing BLE connect-moment permission flow.
- [x] ✅ **Persistence.** `speedCorrectionFactor: Double = 1.0` added to AppSettings + codec
      (doublePreferencesKey, default-on-malformed), round-trip tested; written only past a 0.005
      epsilon to avoid DataStore churn.
- [x] ✅ **Hardening applied in review:** `SpeedCalibrator.state` made `@Volatile` (scope is
      Dispatchers.Default; single writer, so visibility is the only requirement) to match
      `persistedFactor`.
- [ ] 🖐 Hardware validation deferred to Taras: confirm the learned factor converges to ~1.10 and the
      Speed tile matches GPS on the next drive (v1 ships on unit-tested logic; on-device is his check).

Gate: PASS (assembleDebug, test, ktlint, detekt, verifyRoborazzi, assembleDemoDebug, module-isolation)
at 9cf3c5f. 33 tests added.
