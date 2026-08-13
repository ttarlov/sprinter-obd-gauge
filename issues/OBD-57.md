---
id: OBD-57
title: High-quality speed-density boost estimation (MAF+IAT+RPM+baro)
module: core/protocol
owner: protocol-agent
sprint: backlog
status: merged
type: feature
hardware-verify: true
blocked-by: [OBD-56]
branch: protocol/57-speed-density-boost
---

## Decision (Taras 2026-08-13): BUILD IT, high quality.
Ship a computed boost gauge from speed density — rigorously modeled, physically bounded,
honestly labeled, calibration-ready. Not a flat guess: a real thermodynamic model.

## The physics (derive cleanly, unit-test the constant)
4-stroke air ingestion: MAF = VE · (Vdisp/2) · (RPM/60) · ρ_charge, ρ_charge = MAP/(R·T).
Invert:  **MAP(kPa) = MAF(g/s) · R · T_charge(K) · 120 / (VE · Vdisp(L) · RPM)**
(R=0.287 kJ/kg·K; the folded constant ≈ 34.44 — DERIVE it with an explicit unit-tracking
test, do not hardcode a magic number.)  **Boost = MAP − BARO** (allow small vacuum; clamp
MAP to a physical range e.g. [20, 260] kPa).

Inputs: MAF 0166·A, IAT 0168·sensor1 (→K), RPM 010C — all ECU, land via OBD-56. Vdisp 2.987 L.

## BARO SOURCE (Taras 2026-08-13): ECU primary, PHONE fallback
- **Primary: ECU baro PID 0133** — confirmed live (82 kPa this session), the ECU's own
  sensor, perfectly matched to the MAP calc. Baro appears TWICE in the math: it's the MAP
  reference (boost = MAP − baro) AND, since this van reports absolute MAP, the whole point.
- **Fallback: the phone's barometer** (Android `Sensor.TYPE_PRESSURE`, present on the Pixel
  — hPa → kPa) when 0133 is absent/stale. The van and phone share the same atmosphere at
  the same altitude, so phone ambient pressure is a valid boost reference.
- **Module split (keep ownership clean, avoid the OBD-50 cross-module mess):**
  - :core:protocol stays SOURCE-AGNOSTIC — the boost computation takes a `baro` input
    channel; it does not know or care where baro came from.
  - A small :app/platform piece provides the baro Reading: ECU-0133 when present, else the
    Android pressure sensor. This is where the fallback selection lives (an :app data
    source that emits baro, ui-agent-owned).
  - Availability: boost needs MAF+IAT+RPM (ECU) AND a baro from EITHER source. With the
    phone fallback, baro is essentially always available → boost unavailable only when the
    ECU airflow inputs are missing.

## What makes it HIGH QUALITY (not flat-VE)
1. **VE(RPM) model, calibration-ready.** A volumetric-efficiency curve as a small
   interpolated table over RPM (sensible boosted-diesel defaults ~0.85→1.05→0.95), NOT a
   flat constant. Structured so a calibration drive refines the table without code change
   (values in one named table + KDoc on how to fit them).
2. **Graceful degradation.** RPM=0 / MAF=0 / missing input → no divide-by-zero, boost
   reports unavailable (ChannelAvailability), never a garbage spike. Sensor-dropout safe.
3. **Physical bounds + honesty.** MAP clamped to a sane range; boost floored at a small
   vacuum; verified=false + "Est." semantics so the UI badges it computed-not-measured.
4. **Charge-temp caveat documented.** IAT (0168) may be pre-turbo, not post-intercooler
   manifold temp; the VE calibration absorbs the offset. State it in KDoc.
5. **Testable.** Unit-test the constant derivation; test the full chain on the session
   capture (MAF 14.2 g/s, IAT 44°C≈317K, idle RPM ~723, baro 82 kPa) → the model must yield
   a near-atmospheric MAP at idle (boost ≈ 0), which is itself a sanity anchor.

## Architecture
A computed channel like the existing MAP−baro boost, but MAP itself computed from
speed-density (a new ComputedChannels function). ChannelAvailability: boost flips
MissingInputs→Available once MAF+IAT are present (OBD-56). verified=false until the
🖐 VE-calibration drive.

## Acceptance criteria
- [ ] Constant derived + unit-tested (not a magic 34.44); full model tested on the capture
      anchors with an idle→near-atmospheric sanity assertion
- [ ] VE(RPM) interpolated table, documented as calibration-ready
- [ ] Divide-by-zero / missing-input / dropout paths all yield typed unavailable, never a
      wrong number (mutation-tested)
- [ ] boost availability flips to Available when MAF+IAT present; verified=false, "Est."
- [ ] Prod e2e: capture inputs → plausible idle boost ≈ 0; UI badges it estimated
- [ ] Frozen :core:model untouched

## 🖐 After merge: VE-calibration drive
Log MAF/IAT/RPM/baro/actual-torque across idle→cruise→WOT vs a known boost reference (MB
WOT spec ~21-22 psi peak, or a mechanical gauge) → fit VE(RPM), flip verified=true.

## Out of scope
0168 padded-byte identification; a torque-model cross-check (future refinement).
