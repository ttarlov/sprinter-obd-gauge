---
id: OBD-61
title: Speed tile with GPS-auto-calibrated correction (larger tires read low)
module: app
owner: orchestrator
sprint: ad-hoc
status: merged
type: feature
hardware-verify: false
blocked-by: []
branch: feat/61-gps-speed-correction
---

## What
A **Speed** gauge (swap-only catalog entry, standard PID `010D`) that shows the *corrected* true
speed. The van's speedometer reads ~10% low on larger-than-stock tires (indicated 60 ≈ true 66).
The correction is a unitless multiplier `factor` (true = ecu × factor) learned **automatically**
from the phone's GPS — no manual entry, no Settings UI in v1.

## How (additive, isolated, degrades to a no-op)
- **`SpeedCalibration`** (pure, `app/.../speed/SpeedCalibration.kt`) — the correctness core. A
  bounded ring buffer (cap 20) of accepted `gps/ecu` ratios + the last observed ECU speed.
  `accept()` rejects a sample unless: both speeds ≥ 40 km/h; GPS accuracy null-or-≤ 2 m/s;
  |ΔecuSpeed| ≤ 5 km/h vs the previous observation (steadiness — skipped on the first sample);
  ratio ∈ [0.80, 1.25]. `factor(default)` = **median** of the buffer once ≥ 5 samples, else the
  passed-in default (persisted/last, or 1.0). Even-sized median averages the two middle values.
- **`GpsSpeedProvider`** — thin `LocationManager` GPS_PROVIDER glue behind a `SpeedSource`
  interface; foreground-only, catches every failure to "no samples", no new Gradle dependency.
- **`SpeedCalibrator`** — app-scoped: pairs raw ECU km/h (from `readings[speed]`, pre-conversion)
  with GPS fixes, runs the engine, publishes `factor: StateFlow<Double>`, persists it past a small
  epsilon.
- **`SpeedCorrectionDataSource`** (prod) — wraps `DisplayUnitDataSource`, multiplies only the
  speed channel by `factor`; identity at 1.0.
- Persistence: `AppSettings.speedCorrectionFactor` + codec round-trip.
- Permission: `ACCESS_FINE_LOCATION`, requested by `MainActivity`, GPS started/stopped with the
  foreground lifecycle. Denied/absent → factor 1.0.

## Isolation
Swap-only (in `GAUGE_CATALOG`, **not** `DASHBOARD_PIDS`), so it never becomes a default tile. Demo
DI chain untouched (no GPS → raw fake speed). No behavior change to any other channel. The only
Roborazzi delta is the swap-picker carousel gaining a Speed mini-card (see reviews note).
