---
sprint: 2
title: Protocol & Pipe
---

# Sprint 2 — Protocol & Pipe

Sprint goal: the protocol layer fully works over fakes; the BLE link connects to real hardware via the debug console.

Runs as three parallel waves per STATUS.md: 2a protocol (Opus), 2b BLE (Opus), 2c UI (Sonnet).

| ID | Title | Owner | Pts | Wave | Status |
|----|-------|-------|-----|------|--------|
| OBD-13 | ELM327 init state machine | protocol-agent | 3 | 2a | open |
| OBD-14 | Standard PID registry and parser | protocol-agent | 3 | 2a | open |
| OBD-15 | Mode-22 Mercedes PID support | protocol-agent | 5 | 2a | open |
| OBD-16 | Computed boost channel | protocol-agent | 3 | 2a | open |
| OBD-17 | BLE permissions and scanner | ble-agent | 3 | 2b | open |
| OBD-18 | GATT serial bridge | ble-agent | 5 | 2b | open |
| OBD-19 | Debug console screen | ble-agent | 2 | 2b | open |
| OBD-20 | Sparkline strip charts | ui-agent | 3 | 2c | open |
| OBD-21 | Settings screen | ui-agent | 3 | 2c | open |

Sprint 2 velocity: 30 pts (three agents in parallel).

**Demo/milestone:** debug console talking to the actual Veepeak in the van; dashboard demo with charts and settings.

## Ledger

| Wave | Planned target | Actual spend | Merged vs rolled | Escalations |
|---|---|---|---|---|
| 2a | — | — | — | — |
| 2b | — | — | — | — |
| 2c | — | — | — | — |

## Ledger (2b wave 1)
| Planned target | Actual spend | Merged vs rolled | Escalations |
|---|---|---|---|
| +900k | ~1.15M (build 248k + fix 351k + reviews ~440k + orchestrator ~110k incl. arbitration) | 2 merged (batch d838cc7) / OBD-19 rolled | 1 orchestrator arbitration (round-2 blocker); 2 agent stalls recovered |
