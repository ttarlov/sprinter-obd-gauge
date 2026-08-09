---
id: OBD-6
title: Research position — adopt existing OBD library
module: research
owner: researcher
sprint: 1
status: merged
type: process
hardware-verify: false
blocked-by: []
branch: research/6-library-position
---

## Feature
A one-page position paper argues for adopting an existing OBD library (obd-java-api / kotlin-obd-api / a maintained fork) over building custom, backed by actual repo code citations.

## Contract surface
None expected — a research document only.

## Acceptance criteria
- [ ] `research/library-position.md` created, one page
- [ ] Cites actual code from at least one candidate library — not just README claims
- [ ] Covers API shape (how requests/responses are modeled)
- [ ] Covers mode-22 (Mercedes-specific PID) support or lack thereof
- [ ] Covers license terms and maintenance status (commit recency, open issues, maintainer responsiveness)
- [ ] States a clear position: adopt, with a named library and version

## Self-test plan
N/A — research artifact. Reviewed by the orchestrator for citation accuracy during the OBD-9 debate round.

## Out of scope
The counter-position (OBD-7); the decision itself (OBD-9).
