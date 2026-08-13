# Research annex — boost by INFERENCE (2026-08-13)

Direct MAP/boost DID is dead (confirmed on-vehicle: 010B unsupported, 2020C4/2080xx
requestOutOfRange even in confirmed extended session, service-21 absent at 7E0). BUT the
captured extended sensor PIDs decode to give us the inputs for a computed boost.

## MISSED in the survey — real sensor reads available on this van
Decoding session-2's capture against SAE J1979-DA:
- **PID 0166 = Mass Air Flow (dual-bank).** Bytes `01 01 C7 00 00`: sensor A =
  (256·B + C)/32 = (256+199)/32 = **14.2 g/s** (sensor B absent). Standard 0110 MAF is
  unsupported, but 0166 IS answered. MAF is readable.
- **PID 0168 = Intake Air Temp (dual, padded).** Bytes `01 54 00 00 21 00...`: sensor 1 =
  0x54 − 40 = **44°C**. Standard 010F IAT unsupported, but 0168 sensor-1 fills that gap.
  (Trailing bytes 4-7 are non-standard ECU padding — UNIDENTIFIED, worth a byte-diff probe
  across idle→WOT; small chance one is charge-air pressure/temp.)
- PID 0167 = coolant (dual): sensor1 0x82−40 = 90°C (confirms coolant, not new).
- PID 016C = commanded intake/swirl-flap actuators: 96.1% / 94.9% (a COMMAND %, not a
  pressure — possible "boost demand" indicator, not boost itself).
All decodes are HIGH-confidence on format; the VALUES are plausible but UNVERIFIED against
ground truth (need throttle-sweep confirmation: MAF rises with load, IAT ≈ ambient+soak).

## Speed-density boost inference (computable from confirmed-live PIDs)
Inputs all available: MAF (0166), IAT (0168, →K), RPM (010C), BARO (0133), Vdisp=2.987 L.
  MAP (kPa) = MAF(g/s) · IAT(K) · 34.44 / (VE · Vdisp · RPM)
  Est. boost = MAP − BARO
VE (volumetric efficiency) is the one unmodeled term. Options:
1. Flat VE=0.90 (diesel default) → "good enough," same technique every MAF-based calc-boost
   gauge in the wild uses. Ship labeled "Est. Boost (calc)", unverified badge.
2. Calibrate VE(RPM) against known points (idle≈atmospheric, MB WOT spec ~21-22 psi peak,
   or a logged drive vs a mechanical gauge) → tightens it to a real number.

## Wider DID search (angle 3): still dead — no published OM642 charge-pressure DID anywhere.

## Recommendation
- MAF (0166) + IAT (0168) → clean real-sensor gauges (OBD-56), verified=false until a
  throttle-sweep confirms the decode.
- Estimated boost (OBD-57) → PRODUCT DECISION for Taras: ship an honestly-labeled computed
  approximation ("Est. Boost", badged) vs keep the boost tile blank until a true reading
  exists. Consistent with the charter ONLY if clearly labeled as computed, not measured.
  If yes: flat-VE first, then a VE-calibration drive (log MAF/IAT/RPM/baro/torque vs a
  known boost reference).
