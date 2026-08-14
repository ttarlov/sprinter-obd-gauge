# Hardware Session 5 — 2026-08-13, trans temp IDENTIFIED (record 21 30 byte 11, °C = raw − 50)

Warm restart (~53 min after an earlier run), then a ~29-minute varied drive: idle, cruise,
several grade pulls, back to idle. Method: a throwaway diagnostic build of `RealVehicleDataSource`
that requested the **full `21 30` record every poll cycle** and logged all 24 raw bytes next to
coolant (`0105`), RPM (`010C`) and oil (`015C`) ground truth over wireless adb. **2495 samples**
over 1717 s, streamed live and mirrored to the phone's storage (survives WiFi loss). Raw evidence:
`docs/hardware/evidence/session-5-transtemp.csv` (downsampled) + full log in session scratch.

This is the rigorous re-identification OBD-51 called for — the answer to the aliasing that
falsified the byte-1 candidate in session 4. The discriminator that no snapshot could provide:
**frame-to-frame behavior under real load, against thermal ground truth.**

## Result: byte 11 is transmission fluid temperature, `°C = raw − 50`

Of all 24 record bytes, byte 11 is the **only** one that behaves like a warming fluid:

| Discriminator | Byte 11 | What a real fluid temp looks like |
|---|---|---|
| Frame-to-frame jitter | **0.1** counts | ~0 (thermal inertia); byte 1 was 25.2 = noise |
| Range over drive | 116→149 raw (66→99 °C) | a plausible, bounded ATF band |
| Correlation r(oil) | **+0.94** | tracks thermal state |
| Correlation r(coolant) | +0.83 | tracks, but is **not** a mirror |
| Samples below coolant | **2310 / 2495**; above: **0** | ATF lags coolant, never exceeds it here |

### Why it is trans temp and NOT the "coolant echo" session 2 called it

Session 2 saw byte 11 track coolant `27/30/50/65/90` in lockstep and labeled it a TCU-side
coolant echo. That was an **idle-only** observation: at idle the transmission makes almost no
heat, so ATF, block and coolant soak-warm together and read nearly equal. **Under drive load the
byte decouples:**

```
 t(s)   rpm   ATF(b11−50)  coolant   ATF−coolant
    0  1440      68 °C        93 °C      −25   ← trans cold on restart, engine already warm
  213  1483      69 °C        93 °C      −24
  498  1692      80 °C        96 °C      −16   ← climbing under load
  855  1986      90 °C        95 °C       −5
 1215   702      94 °C        96 °C       −2
 1701   705      99 °C        99 °C       ±0   ← converged, fully warm
```

A software echo of the `0105` value **cannot** sit 25 °C below coolant and then converge — that is
two physically distinct thermal masses with different warm-up slopes (coolant range 11 °C, ATF
range 33 °C over the same window). Reported by the transmission controller (7E1), a slow-warming
fluid that lags engine coolant and converges under load is the ATF sump temperature — the NAG1's
own shift-scheduling sensor. It stays ~15 °C below engine oil throughout too, so it is not an oil
echo either.

### The `raw − 50` offset is anchored, not guessed

The strongest anchor is a **multi-point match over a 63 °C span**: during the session-2 cold-start
idle warm-up, byte 11 under `raw − 50` tracked the engine's own `0105` coolant in lockstep at
**27 / 30 / 50 / 65 / 90 °C** (ATF ≈ coolant at idle, as established above). A wrong offset cannot
match five points across a 63 ° range — a constant offset error would show as a constant gap at
every point, and there is none. A second, independent anchor agrees: the cold-soak record reads
byte 11 = `0x4D` = 77 → `77 − 50 = 27 °C`, which equals the independently-logged ambient (`0146`) and
coolant at full cold soak; `raw − 40` would put ATF at 37 °C, impossible above a cold-soaked ambient.
Both land on **`raw − 50`** — the same Mercedes coolant-family offset OBD-15 solved from the
ScanGauge `MTH` field. So the *decode* session 2 wrote for byte 11 was correct; only its *label*
(coolant vs ATF) was wrong. The drive corrects the label.

## Confidence and the one clean confirmation left

This is a rigorous identification: 2495 samples, frame-to-frame stability, correlation with two
independent thermal references, a 25 °C load-induced decoupling that directly refutes the echo
hypothesis, and an offset anchored to cold-soak = ambient. It is the opposite of the six-snapshot
aliasing that produced the byte-1 error.

The single remaining emphatic confirmation is a **true cold start** (overnight soak): byte 11 would
start at cold ambient alongside coolant, then lag it by 40–60 °C during warm-up as the thermostat
races coolant to 90 °C while ATF climbs slowly — a separation an order larger than anything an echo
could show. This warm-restart drive already breaks the echo hypothesis (25 °C is impossible for a
mirror); the cold start would merely make it emphatic. Trans temp ships now on this evidence;
🖐 a cold-start drive, if convenient, is belt-and-suspenders.

## Boost (speed-density) — inputs captured, sanity-checked

The same log carries MAF/IAT/RPM/baro for every sample. There is no independent boost reference on
this van (engine ECU serves no MAP/boost DID — session 2 §3), so this is a plausibility check, not a
calibration against ground truth: the estimator's inputs moved sensibly with load (MAF 31→53 g/s on
pulls, baro steady ~82–83 kPa) and the derived MAP stayed in-band. VE-curve calibration still wants a
run where an independent MAP can be captured; none exists here, so boost remains `verified = false`.

## Process note — how session 4's mistake was avoided

Session 4 identified a temperature from 6 sparse snapshots at near-identical quiet states → aliased.
Session 5's method is the fix, and belongs in the playbook: **never identify a temperature byte
without (1) frame-to-frame stability across hundreds of samples, (2) correlation against independent
thermal ground truth, and (3) a load transient that separates it from the quantity it merely
resembles at idle.** The diagnostic-build-logs-the-whole-record approach is the instrument that makes
all three cheap.
