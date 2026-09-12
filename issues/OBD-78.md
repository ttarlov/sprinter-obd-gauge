---
id: OBD-78
title: Data-liveness reconnect watchdog — self-heal a silent-but-connected dongle
module: app
owner: ble-agent
sprint: reliability
status: in-review
type: feature
hardware-verify: true
blocked-by: []
branch: feat/78-liveness-reconnect
---

## What (Taras, 2026-08-27)

> "The app never reconnects on its own to the dongle — only if I shut the ignition off for a very
> short time."

The auto-reconnect state machine (`BleObdLink`/`ReconnectPolicy`, OBD-23) **only ever fires on a
hard BLE disconnect** — a `STATE_DISCONNECTED` callback (`GattSession.terminate`). A command that
times out does **not** drop the link: `GattSession` treats a no-response as `LinkError.Timeout` and
deliberately keeps the link `Ready` ("one bad command is not a dead link") — correct for a brief
silent-ECU/`SEARCHING…` moment.

The gap: when the dongle **wedges but keeps its BLE connection up** (a favourite failure mode of
ELM327 clones — stops answering, never drops the radio), there is no disconnect *event*, so the
reconnect machine never books an attempt. The app sits `Ready` forever with dead gauges. A brief
key-off cuts power to the OBD port → the dongle's radio actually drops → *that* produces the
`STATE_DISCONNECTED` the machine was waiting for, and since auto-reconnect is still armed it
recovers. The user is manually manufacturing the disconnect the code needs.

## The fix — at the SERVICE layer, not `:core:ble` (rounds 1 & 2 proved the wrong layer)

Two `:core:ble` attempts failed review, and the second (`reviews/OBD-78-round2.md`) proved *why* the
fix cannot live there: on the **first** command timeout `RealVehicleDataSource`'s poll loop **parks**
(documented — "two more doomed commands would only add timeouts") and issues no second command, so a
per-command counter in the link tops out at 1 and is unreachable. The wedge is only distinguishable
one layer up, where you can see the loop gave up (no fresh data) **while the link still reports
`Ready`** — a real drop leaves `Ready`; only a wedge holds it.

So the watchdog lives in **`ObdConnectionService`** (which already owns `dataSource`, `linkController`,
and the OBD-69 idle machinery). `checkWedgeAndMaybeReconnect`, on a ~10 s ticker, forces a reconnect
(`LinkController.connect()` — release the wedged session, re-establish, re-arm auto-reconnect) when:

- the link is **`Ready`**, AND
- no fresh sample has arrived for `WEDGE_RECONNECT_STALE_MILLIS` (**30 s**) — the same `idleTracker`
  data-flow staleness OBD-69 measures, read against a much shorter window and a different action. A
  `Ready` link producing nothing for 30 s is unambiguous (a healthy one samples every few hundred ms).

The `PollEvent.LinkDropped` typed signal the reviewer floated isn't reachable here: `VehicleDataSource`
is a **frozen Phase-0 contract** exposing only `readings` + `connection`, and widening it is a
contract-change out of scope. Data-flow staleness is the signal available at this layer, and given
the loop parks (produces nothing) it is an exact proxy for "the loop gave up on a Ready link".

**Session-relative staleness (round-3 M1).** Staleness is measured from `max(readySinceMillis,
lastData)`, not the global last-data stamp — so a link fresh out of a long outage (a fuel stop, a
key-off, *the* most common event) is immune for its own 30 s window instead of being torn down the
instant it recovers because the global stamp aged while it was gone. `readySinceMillis` is stamped on
the `Ready` **edge** by a connection collector (not the coarse tick, so a fast reconnect's edge is
never missed).

**No storm + no idle-timer defeat (round-1 B1, round-3 B3).** The `wedgeReconnectPending` latch
allows **one** recovery per episode and re-arms **only on *sustained* data** — `SUSTAINED_HEALTH_MILLIS`
(90 s) of continuous flow, strictly longer than the 30 s stale window, so a **single** confirming
sample can never re-arm it. This is what stops a dongle that *answers once then re-wedges* (a clone
whose serial state resets on reconnect; the overnight case where PIDs drop off the bus one at a time)
from looping force→sample→wedge forever — the loop that also kept resetting OBD-69's 20-min idle
timer. A silent-after-reconnect dongle is recovered once, then OBD-69's idle-stop is the floor. Both
the decision (`wedgeReconnectDecision`) and the input clocks (`wedgeInputs`) are pure and unit-tested.

**Composition.** Data-liveness, not a `LinkState` reaction — so it does not reintroduce the OBD-24
hazard `ConnectionServiceController` is built to avoid. Engine-off (OBD-71): the dongle drops power →
link leaves `Ready` → this never fires; and a live recording that stops getting data is *recovered*,
not stopped (unlike the idle-stop, which inhibits during recording).

## Interaction with OBD-71 (engine-off watchdog)

Complementary, no conflict: OBD-71 *stops polling* when the engine is off (drain fix); this watchdog
only acts *while polling and getting nothing*. Engine off → no `sendRaw` → this watchdog idle. When
OBD-71 merges they should stay coherent (both about correct dongle behaviour when parked/idle) — this
one gates purely on poll-command liveness, so it composes.

## Testing

- Pure/unit (`app/src/test`, `WedgeReconnectDecisionTest`): `wedgeReconnectDecision` fires on
  `Ready`+stale, latches, does **not** re-fire while latched (no storm), clears the latch only on
  fresh data, keeps the latch across its own reconnect's `Connecting` phase, and never fires with no
  link — plus a full-episode walk (fire → ride out reconnect → re-arm on data → fire again later).
- Device (🖐 Taras — the real gate): with the app connected and the dongle wedged (or after the state
  that used to require a key-cycle), the app now **reconnects on its own** within ~half a minute,
  without shutting the ignition off. `hardware-verify: true`.

## Hardware checklist

- [ ] On the van: reproduce the "won't reconnect until I cycle the key" state → confirm the app now
  recovers on its own within ~25–30 s. (To verify on the next Garmin/van build — folds in with the
  OBD-71 overnight test since it's the same subsystem.)

## Out of scope

- Changing the reconnect backoff/curve (OBD-23, unchanged).
- A user-visible "link stalled" indicator (the existing connection banner already reflects the
  Error→reconnect it now goes through).
