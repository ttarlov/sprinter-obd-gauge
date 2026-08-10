---
id: OBD-41
title: Live PID discovery session → committed OM642 code database
module: core/protocol
owner: orchestrator
sprint: backlog
status: open
type: fixture
hardware-verify: true
blocked-by: [OBD-18, OBD-19]
branch: protocol/41-pid-discovery-db
---

## Feature
An interactive discovery session — Taras in/at the van with the Veepeak powered, Claude
driving the live link — that systematically tries candidate mode-01/mode-22 codes against
the real OM642/722.6, reads the raw feedback, and distills the results into a committed,
machine-readable PID database that the protocol registry and OBD-40's picker draw from.

## Contract surface
None expected. Output is data (fixtures + a database file), not interface changes.

## Session shape (agreed with Taras 2026-08-10)
Claude runs the loop live: send candidate code → capture raw response → decode against the
hypothesized RXD/MTH scaling → sanity-check the value against known truth (dash readings,
ambient temp, idle RPM, key-on-engine-off expectations) → classify (mapped / plausible /
garbage / no-response) → next code. Taras supplies ground truth from the driver's seat and
changes engine state when asked (key-on, idle, revs, A/C on, etc.).

## Acceptance criteria
- [ ] Candidate list prepared BEFORE the session: known X-Gauge/OM642 community codes,
      standard mode-01 sweep (0100/0120/0140/0160 supported-PID bitmaps first), plus
      documented Mercedes mode-22 ranges worth probing
- [ ] Read-only discipline: mode 01/09/22 queries only; no mode 2E/31, no writes, no UDS
      session changes; inter-query throttle; hard stop-list; engine state logged per probe
- [ ] Every probe's raw request/response captured verbatim and committed as fixtures
- [ ] Database committed (e.g. `core/protocol/src/main/resources/pids/om642-database.json` —
      format decided in-session): per code — request framing, raw samples, decoding, observed
      value + ground truth at capture time, verdict, suggested gauge mapping, verified flag
- [ ] At least the target set resolved: oil temp (OBD-35's known-unknown), trans temp
      confirmation, and any bonus finds (EGT, fuel rate, boost cross-check)
- [ ] 🖐 Taras confirms ground-truth readings used for verdicts

## Self-test plan
Post-session: protocol parser re-runs green over all captured raw fixtures; database file
schema-validated by a JVM test so a malformed entry fails the gate.

## Out of scope
Building the in-app "add gauge" UX (OBD-40); real-time tile rendering of discovered codes
during the session itself (the debug console is the tool, not the dashboard).
