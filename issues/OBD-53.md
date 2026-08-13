---
id: OBD-53
title: App badge derives verified from PidCatalog — eliminate the DASHBOARD_PIDS flag duplication
module: app
owner: ui-agent
sprint: backlog
status: open
type: feature
hardware-verify: false
blocked-by: []
branch: app/53-verified-from-catalog
---

## Feature
The :app badge's `verified` flag is currently duplicated in DASHBOARD_PIDS (a pre-Phase-4
placeholder) and must be hand-kept in sync with :core:protocol's PidCatalog.isVerified.
Since OBD-25 the prod flavor depends on :core:protocol — derive the badge from
PidCatalog.isVerified directly so the duplication (and the cross-module edit it forces)
disappears.

## Why (OBD-50 review finding)
OBD-50 (a protocol change flipping oilTemp verified→true) was FORCED to edit 12
ui-agent-owned :app files — a module-isolation violation accepted only because the app
mirror flag cannot be left stale. Root cause: the duplication. Fix it once, and protocol
verified-flag changes stop bleeding into :app.

## Acceptance criteria
- [ ] Badge `verified` sourced from PidCatalog.isVerified (or a single app-side adapter
      over it), not a hand-maintained DASHBOARD_PIDS field
- [ ] Demo flavor (no :core:protocol dep) still resolves — provide the flag via the demo
      DI seam or a shared constant, documented
- [ ] A test pins app-badge-verified == PidCatalog.isVerified for every dashboard id
      (the cross-module drift guard OBD-25 review B8 asked for)
- [ ] Existing badge/screenshot tests green

## Out of scope
The DASHBOARD_PIDS placeholder's eventual full retirement (Phase-4).
