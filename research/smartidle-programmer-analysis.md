# Research: Mid-City Engineering "SmartIdle" OBD Programmer (907OBDSM / 907OBDSMRT)

**Scope:** Desk research only. No vehicle was touched, no code changed. Product analyzed: Mid-City Engineering's SmartIdle OBD programmer for 2019+ Mercedes-Benz/Freightliner Sprinter W907 vans, plus the parallel SmartIdle product for the older W906/NCV3 platform, in order to assess relevance to our project van (2014-era NCV3/W906, OM642 3.0L V6 diesel).

**Our app's charter for reference:** read-only — standard mode-01 PIDs and mode-22 reads at header 7E0, no security access, no writes, no coding. Everything below is evaluated against that line.

---

## 1. What does SmartIdle actually unlock?

**Primary feature:** "High idle" — the ability to hold the diesel engine at an elevated, driver-adjustable idle RPM while the vehicle is parked, typically to run auxiliary electrical/HVAC loads (inverter, AC, PTO-style accessory power) without the engine dropping to normal idle. This is explicitly the same feature Mercedes itself sells as a factory option.

- Sourced: Nomadic Supply's product listing describes the tool as unlocking "the high-idle software option, allowing the engine to maintain a higher RPM for accessory power or climate control." ([Nomadic Supply — 907OBDSMRT](https://nomadicsupply.com/mid-city-smartidle-obd-programmer-w907-for-2019-mercedes-sprinter-vans-907obdsmrt/))
- Sourced: Mercedes-Benz Vans' own upfitter documentation confirms this is a real factory-offered function, reachable via the optional **Parameterizable Special Module (PSM)**, referred to in the upfitter bulletin as enabling "High Idle Control (HIC)." ([mbvans.com PSM bulletin UM907/02](https://www.mbvans.com/content/dam/mb-vans/us/upfitter/bulletins/my19/sprinter-psm.pdf); [mbvans.com PSM tech-info](https://www.mbvans.com/en/upfitter/tech-info/psm-info))
- Sourced: forum discussion names two factory Mercedes idle options by RPO-style code — **M53** (fixed high-idle preset) and **MT4** (driver-adjustable high idle) — as the underlying factory feature the aftermarket tools are essentially re-enabling/exposing. ([Sprinter-Source forum search summary](https://sprinter-source.com/forums/index.php?threads%2F48840%2F=))

**Secondary/activation behaviors** (sourced, Mid-City's own installation docs):
- Activation trigger is selectable: parking brake + turn-signal/high-beam stalk (stalk up = idle up, stalk down = idle down), an external switch, or automatic-on-park-brake — this is a *runtime UX* layer, not the unlock mechanism itself. ([Mid-City 907SMRTV4 manual](https://midcityengineering.dozuki.com/Guide/907SMRTV4+SmartIdle%C2%AE+for+2019+2020+2021+2022+2023+2024+W907+Sprinter+Installation+Manual/75))
- The programmer can also be used to *revert* the coding back to OEM/off by holding the turn-signal stalk up during the programming sequence. ([same manual])

Mid-City sells a family of similar single-purpose OBD programmers for the W907 (mirror-fold, alarm-disable, J51, Distronic correction) that follow the identical pattern — this is a product-line technique, not a one-off. ([Mid-City device catalog search results])

---

## 2. HOW it does it — the OBD/UDS mechanism

This is the load-bearing question and the one with the least public depth (Mid-City doesn't publish internals, and I found no teardown/CAN-trace writeup for this specific dongle). Here's what's solidly sourced vs. inferred.

**Sourced facts, from Mid-City's own installation manuals (907SMRT-V2/V3 and 907SMRT-V4):**
- The programmer connects to the standard OBD-II port.
- Programming procedure is a specific ignition-cycle "handshake": ignition ON (2x start-button press, no brake) → OFF (1x press) → within 5 seconds, plug the programmer in and cycle ignition ON again. All dash warning lights illuminating confirms the correct pre-programming state (this is consistent with entering an extended/engineering diagnostic session where all networks wake and self-test lamps light).
- Total programming duration: manual quotes 30 min–2 hr for the *whole install* but the LED-monitored coding step itself is "under 5 minutes" per the marketing copy; LED solid = programming in progress, LED off = complete, LED flashing = programmer already VIN-locked to a *different* vehicle.
- The programmer targets the **SAM module** (Mid-City's docs use both "Signal Acquisition Module" and, in the V2/V3 doc, "Sensor Actuator Module" — same physical module, front SAM, the body/signal controller behind the driver-side kick panel), connected via a **T-harness spliced into the CAN B bus (white connector)**.
- The programmer is **VIN-locked after first use** and can be reused only on that same VIN to toggle the setting; it explicitly cannot program a second vehicle. This is characteristic of a device that performs an actual **security-gated coding write**, not a passive live command — a purely transient command wouldn't need or benefit from VIN-locking.
- Explicit warning: "do not drive with the programmer plugged in" — it must be removed after the coding event, i.e., it is not left in-line as a signal-injection device during normal operation. This is strong evidence the change is *persisted in the target module*, not something the dongle actively mediates in real time.
- ([Mid-City 907SMRT-V2/V3 manual](https://midcityengineering.dozuki.com/Guide/907SMRT-V2++and+907SMRT-V3+Installation+Instructions+-+2019+2020+2021+2022+2023+2024+Sprinter+(907)/27); [Mid-City 907SMRTV4 manual](https://midcityengineering.dozuki.com/Guide/907SMRTV4+SmartIdle%C2%AE+for+2019+2020+2021+2022+2023+2024+W907+Sprinter+Installation+Manual/75))

**Sourced facts, from independent Mercedes diagnostic-tooling documentation (not Mid-City), on how *any* variant coding is done on this platform generation — offered as the general mechanism this class of tool almost certainly rides on:**
- A shop blog walking through W907/VS30 coding with DTS Monaco (the legitimate Mercedes engineering tool) shows the platform uses **DoIP** (Diagnostic-over-IP, ISO 13400) as transport for the newer W907, and requires **UDS security access**: "Security Access Level 3B" against the **EZS167** module (the electronic ignition-switch / immobilizer-adjacent module that on this platform doubles as the gateway/authentication anchor) — contrasted with older vans, which the same source says used "Security Access Level 37" via a **BCMFA2** module. After unlocking EZS167, the tool must run an "Extended Start" (diagnostic session control, UDS 0x10 extended-session equivalent) against each target ECU before **variant coding** (UDS 0x2E write-data-by-identifier, in Mercedes tooling terms) is accepted. The van in the example had 35 addressable ECUs behind this gate. ([VXDIAG shop blog — W907 VS30 DTS Monaco coding walkthrough](https://vxdiagshop.blogspot.com/2024/03/sprinter-w907-vs30-coding-using-vxdiag.html))
- This tells us the **W907 has a real authentication gate in front of coding** (consistent with the brief's note about a locked central gateway on this platform), and that legitimate coding tools defeat it the intended way — with a valid seed/key security-access exchange against EZS167 — not by bypassing or spoofing the gateway.

**Inference (not directly sourced for this specific dongle):** Putting the above together, the most defensible technical picture is: the SmartIdle OBD programmer is a small single-purpose UDS client, pre-loaded with (a) a valid security-access seed/key routine for the target module generation and (b) a canned write-data-by-identifier / routine-control payload that flips the SAM's high-idle-enable variant-coding bit(s) — essentially performing, in an automated 5-minute sequence, the same class of operation a shop would do manually in DTS Monaco/Xentry: session control (0x10) → security access (0x27) → variant/data write (0x2E, possibly with 0x31 routine control to commit) → VIN-lock stored in the dongle's own memory. I could not find a teardown, decapped MCU, or captured CAN log confirming the exact service IDs used by *this* dongle — that part is inference by analogy to how coding is done on this ECU generation generally, not a directly sourced fact about the SmartIdle hardware. Flag: **medium confidence**, not verified against packet captures.

I found no evidence Mid-City has published, or that anyone has reverse-engineered, the actual byte-level UDS traffic of this specific programmer. No patents were found under Mid-City Engineering's name in a general web search (not exhaustive — I did not query USPTO directly).

---

## 3. Read vs. write classification

**Verdict: this is a persistent ECU (SAM) coding write, not a transient live command.**

Evidence, all sourced above:
- VIN-locking behavior (a one-time coding artifact stored per-vehicle) only makes sense for a write that changes stored configuration.
- "Do not drive with the programmer plugged in" / it's unplugged and stowed after the ~5-minute cycle — the feature works afterward with the programmer physically absent, so the SAM itself must now be carrying the new configuration.
- The revert procedure ("hold turn signal stalk up through the entire programming process" to toggle back to OEM) is described as another *programming* pass, not a settings screen — consistent with write-then-rewrite of a coded value, not a live/volatile toggle.
- The independent DTS Monaco walkthrough shows the platform's standard coding workflow is exactly this shape: session control → security access → variant-coding write — reinforcing that "coding" on this ECU generation is inherently a persistent write operation gated by UDS security access, not a live command.

This means the *unlock itself* is categorically outside a read-only charter. The **runtime behavior after coding** (stalk up/down to adjust RPM once high-idle is enabled) is a separate, ongoing live interaction with the SAM — but getting to that state requires the write.

---

## 4. Transferability to our van (2014-era NCV3/W906, OM642)

**Short answer: the feature exists on our platform generation too, and Mid-City sells a dedicated product for it — but the mechanism looks different (and importantly, may NOT require an OBD-port coding write the same way).**

- Sourced: Mid-City publishes a distinct "Smart Idle® for Sprinter 2014-2018 (906)" product and a "SMRTIDLE W906 Sprinter High Idle User's Manual," confirming the 2014-2018 NCV3/W906 generation is directly supported. ([Mid-City W906 product](https://www.midcityengineering.com/product/sprinter-smart-idle/); [W906 manual](https://midcityengineering.dozuki.com/Wiki/W906_SmartIdle_Users_Manual))
- Sourced from that W906 manual: activation is via the cruise-control stalk (stalk up = idle up, stalk down = idle down) — same UX pattern as the W907 product. The manual excerpt I could retrieve did **not** describe an OBD-programmer coding step the way the W907 docs explicitly do; it describes the unit as "a standalone high idle control unit" and points to a separate PDF for wiring/install detail I could not fetch. This is a **gap, not a confirmed negative** — I could not positively confirm whether the W906 kit requires SAM coding via OBD or is a pure signal-injection/standalone harness. Marked **low confidence / needs a primary-source install PDF** to resolve.
- Sourced (Mercedes upfitter documentation, platform-agnostic on the PSM concept): the factory route to high idle is the optional **PSM (Parameterizable Special Module)**, a body-builder-oriented "approved gateway into the vehicle's electronics," accessed via a harness under the driver's seat, present on both platform generations as an ordering option — not exclusive to the W907. ([mbvans.com PSM tech-info](https://www.mbvans.com/en/upfitter/tech-info/psm-info); [PSM bulletin](https://www.mbvans.com/content/dam/mb-vans/us/upfitter/bulletins/my19/sprinter-psm.pdf))
- Sourced (forum): a Metris-forum thread titled "High Idle Control without Parametric Special Module" indicates this is a known pain point across the Sprinter/Metris family — vans that didn't come from the factory with the PSM option need some other route (aftermarket module, or coding) to get high idle, which is exactly the market Mid-City's OBD-programmer products are selling into. I could not fully read the thread content (redirect blocked full retrieval), so I can't cite specifics from it beyond the title/topic — **flag as directionally supportive, not a hard source**.
- Sourced (forum): a Sprinter-Source thread search turned up the factory option codes **M53** (fixed high idle) and **MT4** (adjustable high idle) as the underlying Mercedes factory feature — these are generation-spanning Mercedes ordering codes, not W907-specific, which supports that the *feature* is not new to the 907 platform; only the specific gateway/security-access mechanics (EZS167 vs. the older BCMFA2-gated approach the DTS Monaco writeup mentions for "older vans") differ by generation. ([Sprinter-Source search](https://sprinter-source.com/forums/index.php?threads%2F48840%2F=); [VXDIAG blog, BCMFA2 mention](https://vxdiagshop.blogspot.com/2024/03/sprinter-w907-vs30-coding-using-vxdiag.html))
- **On the "does the W907 gateway-bypass angle even apply to our gateway-less W906?" question:** No — and this cuts in our favor if we ever wanted to go there. The brief's framing is correct: the W907's locked central gateway is the *harder* problem Mid-City had to solve for the newer van. The DTS Monaco source's own aside about older vans using "Security Access Level 37" via BCMFA2 suggests the older platform's security-access barrier is a **different, and by reputation less locked-down, gate** than the W907's CGW/EZS167 scheme. That's consistent with the wider Sprinter-tuning market: OM642/NCV3 diagnostic and coding access (Xentry/Vediamo/DTS Monaco with a VCI) is comparatively well-trodden territory in the independent-shop and enthusiast community, vs. the W907 CGW being a newer, tighter obstacle. I did not find a source stating the NCV3 has *no* security gate at all — only that it's a different, older, better-documented one. **Medium confidence.**
- **Non-coding alternative found:** Agile Offroad's "IOPedal Throttle Tuner with High Idle" is a pedal-signal-based aftermarket high-idle solution for Sprinters (referenced for OM642/2007+), which appears to work by intercepting/modifying the accelerator-pedal-position signal rather than coding the SAM — i.e., a hardware-in-line trick, not a UDS write. ([Agile Offroad IOPedal](https://agileoffroad.com/product/iopedal-throttle-tuner-with-high-idle/); also referenced on the [Winnebago Revel forum](https://www.winnebagorevelforum.com/forum/fun-stuff/upgrades-and-modifications/other-revel-upgrades-modifications/13578-io-pedal-with-high-idle)). Notable since our van is also a Revel — this suggests other Revel owners have solved high-idle without ECU coding at all, worth a closer look if Taras ever wants the *feature* without the *risk*.

---

## 5. What (if anything) could our app do — and the risk line

**Nothing here fits inside the current read-only charter, full stop.** Everything that actually delivers the "unlock" — on either platform generation — is a UDS security-access-gated coding write to the SAM (or equivalent) module. Our app's charter is explicitly "no security access, no writes, no coding," and this feature is, definitionally, security access + a coding write. There's no read-only version of "enable high idle."

If Taras ever wants to pursue this for the van itself (separately from the app), the shape of that decision is:
- **What it would take:** a Mercedes-capable UDS tool (Xentry/DTS Monaco/Vediamo class, not our OBD-II PID reader) or a purpose-built dongle like Mid-City's, valid security-access credentials/seed-key for the NCV3-generation gate, and a one-time coding write to the SAM (or possibly upfitting the OEM PSM harness under the driver's seat if the van doesn't already have it wired).
- **Risk surface:** this is a real ECU coding operation — mis-timed or interrupted coding on a body/signal controller carries bricking risk to that module, is outside any warranty coverage, and (per Mid-City's own install docs) requires precise ignition-cycle timing and a stable battery/charging setup during the write. This is categorically different in risk from anything our app currently does.
- **This is a scope-expansion + risk decision, not an engineering task** — flagging it for Taras to decide, not proposing we build toward it.
- **Read-only adjacent win, if any:** none of the research surfaced a specific DID (data identifier) for high-idle status or SAM PSM-coding state that we could read non-invasively — Mid-City doesn't publish that level of detail, and I didn't find it independently. If Taras wants that explored, the next step would be sniffing/logging CAN B traffic on the van (still read-only, no writes) to see whether the SAM already broadcasts anything idle-related, which is a separate, smaller research task from this one.

---

## Verdict box

| Question | Answer |
|---|---|
| What does SmartIdle unlock? | Factory-grade "high idle" — elevated, adjustable park-idle RPM for accessory power, same feature Mercedes sells as PSM/M53/MT4. |
| Read or write? | **Write.** Persistent UDS coding change to the SAM module, gated by security access (0x27-class), VIN-locked per device. Not a live/transient command. |
| Gateway involved? | Yes on W907 — sourced evidence of an EZS167-gated security scheme (general Mercedes coding workflow, not confirmed for this exact dongle). Older platform uses a different, less locked, gate. |
| Transfers to our OM642/NCV3? | The **feature** transfers — Mid-City sells a W906-specific product and Mercedes' factory PSM/M53/MT4 options predate the W907. The **exact mechanism** for the W906 kit is not fully confirmed (may or may not require OBD/SAM coding the same way; primary install PDF was unreachable). |
| In-charter for our app? | **No.** Any version of this requires security access + a coding write, which is explicitly excluded by our read-only charter. This is a vehicle-level, human decision (with bricking/warranty/safety risk), not something the OBD gauge app should implement. |

---

## Sources

- [Nomadic Supply — Mid City SmartIdle OBD Programmer W907 (907OBDSMRT) product page](https://nomadicsupply.com/mid-city-smartidle-obd-programmer-w907-for-2019-mercedes-sprinter-vans-907obdsmrt/)
- [Mid-City Engineering Dozuki — 907SMRTV4 SmartIdle for 2019-2024 W907 Sprinter Installation Manual](https://midcityengineering.dozuki.com/Guide/907SMRTV4+SmartIdle%C2%AE+for+2019+2020+2021+2022+2023+2024+W907+Sprinter+Installation+Manual/75)
- [Mid-City Engineering Dozuki — 907SMRT-V2 and 907SMRT-V3 Installation Instructions](https://midcityengineering.dozuki.com/Guide/907SMRT-V2++and+907SMRT-V3+Installation+Instructions+-+2019+2020+2021+2022+2023+2024+Sprinter+(907)/27)
- [Mid-City Engineering Dozuki — SMRTIDLE W906 Sprinter High Idle User's Manual](https://midcityengineering.dozuki.com/Wiki/W906_SmartIdle_Users_Manual)
- [Mid-City Engineering — Smart Idle for Sprinter 2014-2018 (906) product page](https://www.midcityengineering.com/product/sprinter-smart-idle/)
- [VXDIAG shop blog — Sprinter W907 VS30 Coding using VXDIAG VCX SE Benz DTS Monaco](https://vxdiagshop.blogspot.com/2024/03/sprinter-w907-vs30-coding-using-vxdiag.html)
- [Mercedes-Benz Vans Upfitter Portal — PSM Tech Info](https://www.mbvans.com/en/upfitter/tech-info/psm-info)
- [Mercedes-Benz Vans — Sprinter MY19+ Parameterizable Special Module (ED5) Technical Bulletin UM907/02 (PDF)](https://www.mbvans.com/content/dam/mb-vans/us/upfitter/bulletins/my19/sprinter-psm.pdf)
- [Metris Forum — "High Idle Control without Parametric Special Module" (thread, title/topic only — full content blocked by redirect)](https://www.metrisforum.com/threads/high-idle-control-without-parametric-special-module.3570/)
- [Sprinter-Source — "How do you create a high idle modification?" (search-summarized, M53/MT4 codes)](https://sprinter-source.com/forums/index.php?threads%2F48840%2F=)
- [Agile Offroad — IOPedal Throttle Tuner with High Idle](https://agileoffroad.com/product/iopedal-throttle-tuner-with-high-idle/)
- [Winnebago Revel Forum — IO Pedal with High Idle](https://www.winnebagorevelforum.com/forum/fun-stuff/upgrades-and-modifications/other-revel-upgrades-modifications/13578-io-pedal-with-high-idle)

**Not independently verified / could not access:** midcityengineering.com's main `/device/obd-programmers/` catalog page and the W906 standalone product's `/shop/` listing both returned HTTP 403; the Metris Forum thread redirected to a paywall/toll host and could not be fully read. These would be the first things to revisit if more certainty is needed on the W906-specific mechanism.
