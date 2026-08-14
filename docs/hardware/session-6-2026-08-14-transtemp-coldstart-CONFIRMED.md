# Hardware Session 6 — 2026-08-14, trans-temp identification CONFIRMED by true cold start

The emphatic confirmation OBD-60 flagged as optional: a **true overnight cold soak**, then start and
drive. Same diagnostic instrument (full `21 30` record every cycle vs coolant/RPM/oil ground truth).
**5749 clean samples over 71 minutes.** Evidence: `docs/hardware/evidence/session-6-transtemp-coldstart.csv`.

## Result: byte 11 = transmission fluid temp is now UNSHAKABLE

Byte 11 (`°C = raw − 50`) is the confirmed ATF decode. The cold start delivered the decisive
separation a warm restart could only hint at:

```
 idle, cold:   byte 11 = 36 °C   coolant 36 °C     ← welded together (heat exchanger, at rest)
 → drive:      byte 11 = 46 °C   coolant 95 °C     ← 49 °C APART
 warming:      −49 → −41 → −30 → −20 → −9 → −1
 fully warm:   byte 11 ≈ 100 °C  coolant ≈ 101 °C  ← converged
```

- **Max separation −49 °C** (byte 11 = 46 °C while coolant read 95 °C, at 1932 rpm). A software echo
  of the `0105` coolant value **cannot** read 49 °C different from it. The coolant-echo hypothesis is
  dead beyond any doubt — byte 11 is a physically distinct sensor.
- **Independent per-byte re-check on the cold curve:** byte 11 is again the *only* byte that behaves
  like a warming fluid — r(coolant) **+0.78**, jitter **0.1**, range **70** (33→103 °C, tracking the
  full cold-to-hot sweep). The next-ranked bytes (6, 20, 4) are RPM/load signals: low correlation and
  either negligible range or high jitter. A second, independent dataset reaches the same byte.
- Byte 11 started at **ambient (33–36 °C)** on the cold soak, exactly as a real fluid must — the
  other end of the range from the warm-restart drive, and the offset (`raw − 50`) reads correctly at
  both extremes.

## Why idle looks like coolant but load does not — the mechanism, confirmed

The 722.9 runs a **coolant-warmed ATF heat exchanger**. At rest the coolant drags the ATF along, so
byte 11 ≈ coolant at sustained idle — which is why every idle-only look (sessions 1–3) mistook it for
a coolant echo. Under load the cold, high-thermal-mass fluid shows its true temperature 40–50 °C below
the fast-warming coolant, then climbs and converges. Textbook ATF-behind-a-heat-exchanger; nothing
else produces this idle-coupled / load-decoupled signature. The earlier cold-start *prediction* (a big
lag) was directionally right; the *mechanism* is that the lag surfaces under **load**, not at idle.

## Boost — high-load inputs captured, still no independent reference

The same log caught real wide-open pulls: MAF up to **152 g/s** at 3400–3600 rpm, IAT 43 °C, baro
83–84 kPa — healthy, physically sane airflow for a loaded OM642, so the speed-density estimator's
*inputs* are confirmed good. But this van still exposes **no independent MAP/boost sensor** (session-2
§3), so there is no ground-truth boost value to calibrate the VE curve against. Boost therefore stays
`verified = false` ("Est.") — honest, not for lack of a drive.

## Status
Trans-temp identification (OBD-60, record 21 30 byte 11, °C = raw − 50) is confirmed at both ends of
the range and under load, across two independent drives (2495 warm-restart + 5749 cold-start samples).
No open action. The dashboard's trans tile is a trustworthy ATF gauge.
