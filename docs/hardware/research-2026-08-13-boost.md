# Research annex — OM642 boost/MAP via generic OBD (2026-08-13)

Exhaustive open-source hunt (benzworld, mbworld, mbclub, jeepforum/jeepgarage, torque-bhp,
300cforums) for a working boost/MAP request on Mercedes-badged OM642 engine ECUs.

## Verdict: NO working request exists in open sources. Strong negative.
- **Jeep-badged OM642** (WK/WH Grand Cherokee CRD, same engine + Bosch ECU family) exposes
  boost via plain STANDARD 010B — confirmed live by multiple posters. Market-calibration
  difference: Chrysler maps the charge-air sensor into the legacy MAP PID slot;
  Mercedes-badged calibrations (E/ML/GL/R CDI, Sprinter) do not — matching our live
  NO DATA on 010B.
- **Mercedes-badged OM642**: zero working custom DIDs found for boost, rail pressure,
  EGR, oil pressure, or trans temp. Torque's own developer spent ~18 months (2013-15)
  hunting the analogous 722.9 trans-temp DID on a CLS320 and the community concluded
  "not available through OBD2 on Mercedes vehicles."
- No working extended-session init string documented anywhere; likely session + SECURITY
  ACCESS (seed/key) gating, uncracked in the open community for CDI/EDC16-17.

## Consequences for OBD-52 (revised)
1. **The one free shot left: service 21 sweep at the ENGINE ecu (7E0), default session** —
   genuinely untested by the entire community (all custom-PID culture is service 22).
   No charter change needed; ~5 probes; next van minute.
2. The extended-session (10 03) sweep's expected value DROPS: research suggests DIDs are
   additionally security-gated, so 10 03 alone probably yields more 7F responses. Still
   Taras's call, but no longer the promising path it looked like.
3. If 21-at-7E0 fails: native boost over OBD is realistically dead. Remaining paths:
   (a) sniff a real Xentry/Star session on this van and replay (a much bigger adventure,
   new charter conversation), or (b) aftermarket MAP sensor feeding the app directly
   (BLE pressure sensor / CAN tap — hardware project). Boost tile stays typed-unavailable,
   honestly, until one of these is chosen.
