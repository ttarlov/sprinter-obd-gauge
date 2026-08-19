# Per-Gauge Full-Scale Display Ranges (OBD-72 input)

**Purpose.** OBD-72 (backlog) proposes selectable gauge render styles — an analog needle and an
LED bar-arc — alongside the current digital tile. Both styles need a fixed `scaleMin`/`scaleMax`
sweep per channel; the app has per-gauge color thresholds (`ThresholdConfig`) today but no notion
of a full-scale range. This doc is that missing table: one row per channel this app's protocol
layer knows how to produce, with a defensible min/max, a tick interval, and a citation trail.

**Vehicle.** Mercedes-Benz OM642 3.0L V6 turbodiesel, Sprinter NCV3 4x4 (Winnebago Revel).

**Display-unit convention.** The `prod` flavor displays **°F / PSI / mph**, user-switchable to SI
via `UnitPreferences` (`app/src/main/kotlin/com/revel/obdgauge/app/gauge/UnitConversion.kt`).
Only three unit *kinds* are switchable — `TEMPERATURE` (°F↔°C), `PRESSURE` (PSI↔kPa), `SPEED`
(mph↔km/h); RPM, percent, g/s, L/h and volts pass straight through with no toggle
(`UnitConversion.kt:10-18`). Ranges below are given in the **display unit** for every channel,
with SI noted for the three switchable kinds.

**Channel inventory.** 20 channels total — `PidCatalog.definitions`
(`core/protocol/.../PidCatalog.kt:135`) = 18 standard mode-01 PIDs (`PidRegistry.all`) + 0
Mercedes mode-22 PIDs (`MercedesPidRegistry.all` is empty since OBD-55 — the sole entry,
`transTemp`, was falsified on-vehicle and retired) + 1 KWP record channel (`TcuRecordRegistry
.transTempRecord`) + 1 computed channel (`boost`). Only 6 of the 20 are wired into
`GaugeCatalog.kt`'s `GAUGE_CATALOG` today (coolant, oil, trans, boost, rpm, speed); the other 14
are live/polled protocol channels with no dashboard gauge yet. All 20 are covered below since
OBD-72's gauge-style picker is a natural place to eventually expose them.

## Master table

| id | label | display unit | scaleMin | scaleMax | tick | contains thresholds? | basis |
|---|---|---|---|---|---|---|---|
| `coolant` | Coolant | °F (SI °C) | 40 °F (4 °C) | 260 °F (127 °C) | 20 °F (~10 °C) | **Yes** — 215/225 | judgment, anchored to sourced ~212 °F/100 °C OM642 thermostat target |
| `oilTemp` | Oil | °F (SI °C) | 100 °F (38 °C) | 300 °F (149 °C) | 25 °F (~14 °C) | **Yes** — 245/260 | judgment, anchored to sourced 220–250 °F OM642 oil "sweet spot" |
| `transTemp` | Trans | °F (SI °C) | 100 °F (38 °C) | 280 °F (138 °C) | 20 °F (~10 °C) | **Yes** — 215/240 | judgment (no OM642-specific ATF temp spec found; sized around repo thresholds with headroom) |
| `boost` | Boost | PSI (SI kPa) | 0 psi (0 kPa) | 25 psi (172 kPa) | 5 psi (25 kPa) | No (neutral) | sourced (~20–22 psig OM642 max boost) + judgment headroom; **Est., unverified** |
| `rpm` | RPM | rpm | 0 | 5,000 | 500 | No | judgment, anchored to sourced peak-power band (3,600–4,000 rpm) |
| `speed` (app id) | Speed | mph (SI km/h) | 0 mph (0 km/h) | 100 mph (160 km/h) | 20 mph (20 km/h) | No | judgment, anchored to sourced NCV3 governor range (74–99 mph observed across configs) |
| `baro` | Baro | kPa | 60 kPa | 105 kPa | 5 kPa | No | judgment, anchored to sourced sea-level 101 kPa + captured 82 kPa @ ~5,800 ft |
| `map` (protocol id) | MAP | kPa | 20 kPa | 250 kPa | 25 kPa | No | judgment (baro floor + max boost ceiling, absolute); **UNSUPPORTED on this vehicle (`010B` → NO DATA)** |
| `iat` (protocol id) | Intake Air | °C (°F) | −40 °C (−40 °F) | 120 °C (~248 °F) | 20 °C (40 °F) | No | judgment, standard IAT sweep; **UNSUPPORTED on this vehicle (`010F` → NO DATA)** |
| `iatSensor` | Intake Air | °C (°F) | −40 °C (−40 °F) | 120 °C (~248 °F) | 20 °C (40 °F) | No | judgment, same physical quantity as `iat`; **unverified value** |
| `maf` | MAF | g/s | 0 | 200 | 25 | No | judgment (idle anchor 14.2 g/s sourced; WOT ceiling extrapolated from generic 3.0L diesel airflow — **not captured on this van**); **unverified** |
| `engineLoad` | Load | % | 0 | 100 | 10 | No | definitional (SAE `A×100/255`) |
| `throttle` | Throttle | % | 0 | 100 | 10 | No | definitional; **reads intake-flap position, not a driver throttle — idle sits ≈83 %, not 0 %** |
| `fuelLevel` | Fuel Level | % | 0 | 100 | 10 | No | definitional |
| `ambientTemp` | Ambient | °F (°C) | −40 °F (−40 °C) | 120 °F (~49 °C) | 20 °F (~10 °C) | No | judgment, sized for van travel (desert to alpine winter), not just OM642 spec |
| `accelPedal` | Accel Pedal | % | 0 | 100 | 10 | No | definitional |
| `demandTorque` | Demand Torque | % | −25 | 125 | 25 | No | judgment, practical operating band; SAE raw byte supports −125…+130 % |
| `actualTorque` | Actual Torque | % | −25 | 125 | 25 | No | judgment, same as `demandTorque` |
| `fuelRate` | Fuel Rate | L/h | 0 | 30 | 5 | No | judgment, anchored to sourced idle 1.15 L/h capture; cruise/load ceiling extrapolated |
| `moduleVoltage` | Voltage | V | 10 | 16 | 1 | No | judgment, anchored to sourced 13.8–14.5 V charging band + captured 14.05 V; floor covers cranking sag |

## Notes / assumptions

**Diesel redline reasoning (`rpm`).** No OM642 factory rev-limiter number turned up in search —
Mercedes doesn't publish it the way a gas-engine redline gets quoted. What's solidly sourced is
the peak-power band, **3,600–4,000 rpm** (aggregated from `dieselhub.com`, `mercedesclub.cz`, and
`motorreviewer.com` spec pages). Diesel V6 truck engines like this one typically carry 500–1,000
rpm of governed headroom above peak power before the injection-limiter cuts in, which is why
`0–5,000` was chosen: it comfortably contains the sourced peak-power band with room to spare, and
puts a plausible governed cutoff (~4,500 rpm, unconfirmed) inside the sweep rather than pinned at
the top — same principle as the coolant/oil/trans redlines. Idle (~800 rpm, weakly sourced to
general Sprinter forum threads, not OM642-specific) sits well inside the low end. **This is the
single least-confident number in the table** — if Taras has a tach reading from a redline event or
a factory spec, it should override this.

**Boost is a speed-density *estimate*, not a measured value** (`PidCatalog.computedBoost`,
`verified = false`, badged "Est." in the UI — OBD-57). `010B` MAP returns NO DATA on this van; the
gauge chews on MAF/IAT/RPM/baro through an uncalibrated volumetric-efficiency curve. The 0–25 psi
sweep is sized around sourced turbo hardware limits (14.8 psia idle floor up to a 35–37 psia /
20–22 psig ceiling before the VNT backs off, per Sprinter-Source forum threads and
`theboostlab.com`), **not** a guarantee the estimate itself will track that range accurately.
Whatever style OBD-72 ships should carry the "Est." badge through onto the needle/arc, not just
the digital tile.

**Coolant/oil/trans all start at 40–100 °F, well below normal operating temperature, on purpose** —
per the team lead's brief, a cold-start reading (this app's OBD-60 trans-temp identification work
specifically used a cold-soak capture reading ambient) needs to land somewhere legible on the
sweep, not pinned at the peg. None of the three needed to go below freezing: diesels this size
don't idle-start at sub-zero speed in the app's own capture history, and a van living mostly in
Colorado/Southwest conditions rarely cold-soaks the block below ~30–40 °F for long once running.

**`map` and `iat` are dead protocol channels on this specific van** (`PidCatalog
.UNSUPPORTED_BY_THIS_VEHICLE`, captured `NO DATA` 2026-08-12, `docs/hardware/session-2026-08-12
.md`) — their ranges are given for protocol completeness (a second vehicle, or the record-keeping
value of the row) but a gauge-style picker should filter them out via `PidCatalog.availabilityOf`,
the same way the rest of the UI already does.

**`baro` was given in kPa, not psi**, even though `KPA`/`PSI` are the same switchable
`PRESSURE` kind `UnitConversion.kt` already handles. Barometric *absolute* pressure reads
awkwardly in psi (60–105 kPa is 8.7–15.2 psi, not round in either unit) and no existing gauge in
this app displays baro at all — if OBD-72 or a later ticket adds a baro gauge, the psi
conversion is mechanically free (same seam as boost) but I'd keep kPa as the natural unit for an
absolute-pressure barometer read.

**Demand/actual torque's practical band (−25…125 %) is narrower than the SAE byte range
(−125…+130 %)** on purpose. The `0161`/`0162` formula is `A − 125` over a full `0–255` raw byte,
so the wire *can* carry the full ±125-ish swing, but this van's only two captured samples (5 % and
11 %, both near idle, 2026-08-13) plus ordinary driving don't approach the extremes; a gauge sized
to the full SAE range would waste most of its sweep on values that never occur. If a full-throttle
or engine-braking capture later shows the practical band is wrong, it's a one-line fix, not a
rethink — the SAE range is noted in the table precisely so that ceiling is visible.

**MAF's ceiling (200 g/s) is the weakest-sourced pressure/airflow number here**, on the same
axis as boost: this van's `0166` PID only reports "sensor A" (`PidRegistry.maf`'s KDoc, dual-bank
frame with sensor B padding absent), and the only anchor is a single warm-idle capture at 14.2 g/s
(2026-08-13). 200 g/s is a generic 3.0L diesel WOT airflow figure, not something pulled from this
van under load — treat it as a placeholder until a throttle-sweep capture (already planned per
`PidRegistry.maf`'s KDoc, to verify the decode itself) gives a real ceiling.

**Ambient temp's range is sized for the van's travel envelope, not the engine.** Every other
temperature channel here is bounded by what the OM642/722.9 can survive; ambient is bounded by
where Taras parks — desert summer highs and alpine/winter lows both plausible for a van doing
Colorado-based overlanding, so −40…120 °F errs wide rather than clipping a real reading.

**Speed's ceiling (100 mph) is a dashboard-sizing choice, not a vehicle-capability claim.**
Sourced governor figures for the NCV3 range from 74 mph (XFS fleet-governor option) up to ~82–99
mph reported in other forum threads depending on configuration — inconsistent enough that no
single number is authoritative for *this* van. 100 mph was picked because it comfortably clears
every sourced figure with headroom to spare, matching the "redline inside the sweep, not at the
edge" principle used everywhere else in this table, not because the van is rated to reach it.

Sources: [MB Medic — OM642 guide](https://www.mercedesmedic.com/the-complete-guide-to-the-mercedes-benz-om642-diesel-engine/), [Mercedes-Benz OM642 engine — Wikipedia](https://en.wikipedia.org/wiki/Mercedes-Benz_OM642_engine), [dieselhub.com — 3.0L OM642 specs](https://www.dieselhub.com/specs/mercedes-3.0l-om642.html), [mercedesclub.cz — OM642 engine family](https://en.mercedesclub.cz/engines_family.php?id=68), [Sprinter-Source — boost PSI thread](https://sprinter-source.com/forum/showthread.php?page=2&t=25127), [theboostlab.com — Sprinter turbo rebuild](https://www.theboostlab.com/mercedes-sprinter-turbo-rebuild/), [PeachParts — OM642 operating temp](https://www.peachparts.com/shopforum/diesel-discussion/395924-om642-operating-temp.html), [Sprinter-Source — OM642 oil temperature](https://sprinter-source.com/forum/showthread.php?p=670178), [Blauparts — Sprinter ATF spec guide](https://www.blauparts.com/blog/sprinter-transmission-fluid-atf-spec-types-use-fitment-guide.html), [Sprinter-Source — speed limiter thread](https://sprinter-source.com/forum/showthread.php?t=8200), [poweruptips.com — alternator charging voltage](https://poweruptips.com/alternator-charging-voltage-12v-battery/), [w8ji.com — battery and charging system](https://www.w8ji.com/battery_and_charging_system.htm).
