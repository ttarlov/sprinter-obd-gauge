---
issue: OBD-58
round: 1
reviewers: [orchestrator (contract-change close-out; compute path pre-reviewed at e06eaf3)]
verdict: approved
gate: green
reviewed-commit: bcf62d4
---

Orchestrator close-out of the boost live-flip contract change. The speed-density compute
path was exhaustively Tier-A reviewed at e06eaf3 ("safe to flip live"); OBD-58 is the
pre-vetted mechanical flip (units + MAF channel), verified here with targeted mutation.

## Fix list
- [x] ✅ :core:model purely ADDITIVE: GRAMS_PER_SECOND/LITERS_PER_HOUR/VOLTS appended,
      ordinal-safe (serialized by name — confirmed SettingsCodec uses valueOf), no existing
      member touched. D9-authorized contract change.
- [x] ✅ MAF (0166 g/s) wired as a live StandardPidSpec channel; removed from
      PENDING_UNIT_CONTRACT/PENDING_UNITS. fuelRate (015E L/h) + moduleVoltage (0142 V)
      also live (verified=true, live-captured). Registry 15→18.
- [x] ✅ Boost auto-flips MissingInputs(["maf"]) → Available, compute path unchanged
      (publish already routes through speedDensityBoost). Verified by MUTATION: undoing the
      MAF registry wiring makes the OBD-58 flip tests fail; pristine green.
- [x] ✅ Value self-consistency: the flip test asserts boost == the pure function on its
      inputs (RPM 2000 + idle airflow → vacuum, correct); the TRUE idle-sanity anchor
      (MAF 14.2, RPM ~723, baro 82 → near-zero boost) is pinned in ComputedChannelsTest,
      alongside "idle vacuum is negative, never clamped to zero at any elevation".
- [x] ✅ Never-zero guarantee intact (compute path pre-reviewed unchanged; runtime-missing-
      MAF test keeps the skipped-read-not-zero behavior).
- [x] ✅ Boost stays verified=false ("Est.") — VE calibration still pending (OBD-57
      residual). :app render: one exhaustive when(MeasurementUnit) → g/s/L/h/V suffixes.
- [x] ✅ Demo flavor unaffected (scripted boost, never touches ComputedChannels). Gate PASS.

Contract-change: DECISIONS.md D9 is the sign-off. Cross-module spread (model+protocol+app)
is inherent to a contract change. verdict: **approved** at bcf62d4. Merge target: main.

## Carried forward
- 🖐 OBD-57 residual: VE-calibration drive → boost verified=true.
- OBD-57b: phone-baro fallback provider (:app) + display-side smoothing for the LOW-1
  near-stall transient the boost reviewer flagged.
