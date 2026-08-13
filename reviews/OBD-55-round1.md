---
issue: OBD-55
round: 1
reviewers: [rev-correctness Tier A (Opus); orchestrator doc-nit fix]
verdict: approved
gate: green
reviewed-commit: 6730f51
---

Tier-A review of the trans-temp id-swap — the crown-jewel decode going live.

## Fix list
- [x] ✅ Decode `63 − raw` at record byte 1 hand-verified against all three session-3
      fixtures (warm-idle 28°C, post-drive 45°C, heat-soak 45°C steady while coolant
      drops — the decoupling that identifies the field). Coolant byte 11 (raw−50) kept as
      internal tripwire, not displayed.
- [x] ✅ Id swap: PidIds.TRANS_TEMP → PolledPid.Record, polled + displayed; X-Gauge
      byte-0/18 spec retired from all poll paths (MercedesPidRegistry.all empty); framing
      + header set/restore discipline preserved; no id collision.
- [x] ✅ Falsified-gate removal did not regress boost/MAP/IAT availability; the gate
      machinery still exercised via the extraFalsified seam (mutation e proves it blocks).
- [x] ✅ OBD-15 X-Gauge MTH arithmetic tests intact (only the trans-channel role retired).
- [x] ✅ e2e: value anchored on written-down session-3 bytes (45°C→113°F), tile carries
      the unverified badge (pinned to PidCatalog.isVerified).
- [x] ✅ All 5 mandatory mutations KILLED: byte1→0, offset 63→62, drop header-restore,
      read byte11-not-byte1, re-add to FALSIFIED_DECODES. Tree clean after each.
- [x] ✅ verified=false + provisional-slope honesty correctly represented at every layer
      (flag, KDoc, isVerified, tile badge); nothing asserts the slope as proven.
- [x] ✅ Doc-nit (stale RealVehicleDataSourceTest comment) fixed by orchestrator; test green.

## ⚠️ CARRIED FORWARD (not blocking — the honest residual, elevated priority)
The `63 − raw` law saturates at 63°C (raw masked 0..255). Normal ATF runs 70-90°C, and a
grade/tow hits 100°C+ — above 63°C the model reads LOW (the dangerous direction, exactly
when a warning matters). Correctly represented (verified=false, badged, documented) so it
ships, BUT it is NOT yet trustworthy as a hot-grade warning. **OBD-51's hot-cross-checked
sample is now the CRITICAL gate to verified=true, not optional** — one loaded/hard pull
past 63°C reveals the true high-temp encoding (wrap vs re-slope) and fixes the ceiling.

Verdict: **approved**. Merge target: main.

**Re-attest note:** Opus reviewed ad3c258. The only delta to reviewed-commit 6730f51 is the
comment-only doc-nit fix the reviewer explicitly requested (stale RealVehicleDataSourceTest
comment) — no code, assertion, or behavior change; orchestrator-verified test-green. This
re-attestation lets merge.sh's stale-approval guard pass on a legitimately-covered head.
