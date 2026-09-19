---
id: OBD-87
title: Instant MPG computed gauge (corrected speed ÷ fuel rate, lightly smoothed)
module: app
owner: ui-agent
sprint: telemetry
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: feat/87-instant-mpg-gauge
---

## What (Taras, 2026-09-19)

> "Feature request: instant MPG readout gauge."

A live **instant fuel-economy** gauge: `MPG = corrected_speed(mph) ÷ fuel_rate(gal/h)`. Both inputs are
verified channels on this van — `speed` (0x0D, GPS-corrected) and `fuelRate` (`015E`, `(256A+B)/20` L/h,
live since OBD-58). It's a **computed channel** like boost. Decisions (Taras, 2026-09-19):
- **Lightly smoothed** — a ~2–3 s rolling average (raw instant MPG flickers wildly: pins high on
  decel fuel-cut, drops to 0 at stops). Readable but still "right now."
- **US MPG only** — miles per US gallon; no metric toggle for now.

## Architecture (from the OBD-87 scoping pass — follow it)

**Compute at a NEW outermost decorator, NOT in `RealVehicleDataSource`.** Boost is computed innermost
where `speed` is still raw km/h; MPG needs the **GPS-corrected mph** speed, which only exists at the
outermost `SpeedCorrectionDataSource` output. So:

- **New `InstantMpgDataSource`** (`app/src/prod/kotlin/.../datasource/`) wrapping `SpeedCorrectionDataSource`,
  becoming the value returned by `provideVehicleDataSource` in `app/src/prod/.../di/DataSourceModule.kt`:
  `... ; val corrected = SpeedCorrectionDataSource(display, factor, scope); return InstantMpgDataSource(corrected, scope)`.
  Mirror the `DisplayUnitDataSource`/`SpeedCorrectionDataSource` shape (`map`/`combine` the delegate's
  `readings` StateFlow + `stateIn`). It reads `readings["speed"]` (mph, corrected) and
  `readings["fuelRate"]` (L/h), computes MPG, and injects `readings + (INSTANT_MPG_ID to reading)`.
- **Compute:** `mpg = speed_mph / (fuelRate_Lph / 3.785411784)`. Reading freshness = older of the two
  inputs (`timestamp = minOf(...)`, `stale = a.stale || b.stale`), mirroring the boost `Reading`.
- **Guards (reuse `ComputedChannels` discipline — emit ABSENCE, never a fabricated number):**
  - `speed == null || fuelRate == null` → no MPG reading.
  - `fuelRate.value <= 0` (fuel-cut on decel / engine off) → no reading (blank tile), not `inf`/`NaN`.
  - `speed.value == 0` (idle/stopped) → MPG = 0 (well-defined; show 0).
  - `!result.isFinite()` → no reading (backstop).
- **Smoothing:** maintain a small time-windowed buffer of recent computed MPG samples and emit their
  ~2–3 s rolling average (pure + unit-tested). Skip null/absent samples in the window; when no valid
  samples in-window, emit absence. Keep it deterministic (inject a clock/`now`), like the wedge logic.

**New unit:** append `MILES_PER_GALLON` to `MeasurementUnit` (`core/model/.../MeasurementUnit.kt`).
This is an **additive** change to the frozen enum — explicitly permitted (same as D9's `GRAMS_PER_SECOND`
/`LITERS_PER_HOUR`/`VOLTS` additions); no contract-change sign-off needed, but keep it strictly additive
(append at the end). MPG stays `UnitKind.OTHER` pass-through — the L/h→gal/h division is intrinsic to the
compute fn, NOT a `UnitConversion` (which only converts within one unit-kind), so `DisplayUnitDataSource`
and `UnitConversion` need no change.

**Poll both inputs.** `speed` is already polled. `fuelRate` is a live protocol channel (`PidRegistry.fuelRate`)
but currently NOT fetched (absent from `GAUGE_CATALOG` → not in `ActivePollSet.activePids()`). Add a
**swap-only `FUEL_RATE_PID_DEFINITION`** to `GAUGE_CATALOG` (mirror `SPEED_PID_DEFINITION` — in the
catalog so it's polled + offerable, not forced as a default tile) so fuel rate goes on the wire. Local
app-side id `FUEL_RATE_PID_ID` with a `testProd` parity assertion == `ProtocolPidIds.FUEL_RATE` (like the
speed/engine-load parity tests). (Fuel rate itself becomes a usable gauge too — bonus.)

**Surface MPG as a gauge.** Add `INSTANT_MPG_PID_ID = "instantMpg"` + `INSTANT_MPG_PID_DEFINITION`
(unit `MILES_PER_GALLON`, `verified = false` — computed estimate, like boost; placeholder request/parse
never used at the app layer) to `GAUGE_CATALOG` as a **swap-only** neutral gauge (no `ThresholdConfig.seed`
entry → NEUTRAL, like boost/engine-load). Optional `GaugeScaleDefaults.seed` range ~0–40 mpg for the
needle/bar-arc styles; digital readout otherwise.

## Flavor note (demo)
`InstantMpgDataSource` is prod-only (mirrors `SpeedCorrectionDataSource`). Decide the demo behavior: if
the demo `FakeVehicleDataSource` scenario emits both `speed` and `fuelRate`, wrap the demo chain too so
the MPG tile shows a value in demo (nicer for Roborazzi + demo testing); if the fake doesn't provide
fuel rate, the MPG tile is a blank/`—` catalog entry in demo — acceptable, but note which you did.

## Testing
- **Pure JUnit:** the MPG compute fn (correct value from a known mph + L/h; the divide/guard cases:
  null inputs, fuelRate ≤ 0, speed 0 → 0, non-finite) + the rolling-average smoothing (window math,
  gap-skipping, deterministic via injected clock).
- **`testProd`:** `FUEL_RATE_PID_ID`/`INSTANT_MPG_PID_ID` parity vs `ProtocolPidIds`; `InstantMpgDataSource`
  decorator test (feed speed+fuelRate readings → asserts smoothed MPG appears; guards produce absence);
  extend `ProdChainEndToEndTest`/`DisplayUnitDataSourceTest` for the new outer decorator + that MPG is
  NOT unit-converted.
- **Roborazzi (testDemo):** the new swap/add-palette entries (fuel rate + instant MPG) — re-record.
- `tools/gate.sh` green. (Known flaky `ObdConnectionServiceTest` under full-suite contention — re-run in
  isolation to confirm, then re-run the gate.)
- **Device (🖐 Taras, non-gating smoke):** add the Instant MPG tile → cruise shows a sane ~15–22 mpg on
  the diesel, climbs/decel move it, idle → 0. `hardware-verify: false` (both inputs already verified;
  this is compute + UI).

## Out of scope
- Average / trip MPG (needs distance+fuel integration over time — that's the OBD-80 mileage territory).
- Metric fuel-economy (L/100km) / unit toggle.
- Threshold coloring (neutral readout; higher-is-better bands are a trivial follow-up if wanted).

## Base note
Builds on `main`. Touches `:core:model` (additive enum) + `:app` (main catalog + prod decorator/DI) — no
`:core:protocol` change (fuel rate already decoded+verified there). A device build for the van must be
cut from an integration that includes the reconnect fixes etc. (all already on main) — main is current.
</content>
