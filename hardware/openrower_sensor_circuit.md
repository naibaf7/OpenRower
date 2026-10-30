# OpenRower sensor board — rev 2 (2026-10-17)

USB-C powered ESP32-C3 board that reads a rowing machine's flywheel sensor from a 2.5 mm TRS jack and
streams pulse timing over BLE or USB CDC. It supports:

- powered hall sensors at 3.3 V or 5 V (e.g. the Infinity rower setup, A3144/OH3144-type sensors);
- Concept2 Model C (pickup coil);
- Concept2 Model D/E (generator tacho).

The sensor mode is selected in the app. **No mode selection, right or wrong, can damage the board, the
phone/USB host or the rowing machine.** The one case hardware cannot catch is a sensor that is only rated
for 3.3 V being given 5 V (see §6).

Source of truth: `openrower_sensor.kicad_sch` / `.kicad_pcb`. Both are generated:

| Script | Produces |
|---|---|
| `tools/gen_schematic.py` | Schematic. The whole netlist is the `PARTS` table, so review the circuit there. |
| `tools/check_netlist.py <net>` | Compares KiCad's exported netlist against `PARTS`. |
| `tools/gen_pcb.py` | Board with footprints placed and linked to the schematic. It refuses to run once the board is routed. `--silk` only rewrites the silkscreen texts and keeps the routing. |
| `tools/check_spacing.py` | Courtyard and pad gaps, and component distance to the board edges (JLC rail rule). |
| `tools/route.py` | 2-layer autorouter: exact-geometry grid A*, hand-designed USB-C breakout, rip-up, GND vias, stitching and pours. Log in `tools/route.log`. |
| `tools/jlc_align.py` | Compares each part's JLC (EasyEDA) footprint with its KiCad footprint and writes the rotation/offset corrections to `tools/jlc_corrections.json`. It needs network access; the downloaded footprints are cached locally in `tools/easyeda_cache/`, which is not in the repository. |
| `tools/make_fab.py` | `bom/jlcpcb_bom.csv` and `bom/jlcpcb_cpl.csv` (with JLC corrections applied), plus gerbers and drill files in `fab/` and `openrower_sensor_gerbers.zip`. None of these production files are in the repository; they are attached to each hardware release. |

**License:** the hardware design is licensed under CERN-OHL-S v2 (`LICENSE`, `NOTICE`); `tools/` is MIT. See
`NOTICE` for the scope and the third-party footprints.

---

## 1. Concept2 jack wiring (what the board has to survive)

| Machine | Plug | Tip | Ring | Sleeve |
|---|---|---|---|---|
| Concept2 Model C | 2.5 mm **mono** | pickup coil, ~100 Ω, bipolar pulses, 3 per rev | **shorted to sleeve by the mono plug** | GND |
| Concept2 Model D/E | 2.5 mm TRS | tacho, 0–18 V sinusoid, 6 periods/rev | **generator DC output, ~18 V** (powers the PM) | GND |
| Hall sensor | 2.5 mm TRS | sensor output (open-collector or push-pull) | sensor VCC (from this board) | GND |

Sources: posts by Concept2 technician Carl Watts and others in the c2forum threads *Replacing a monitor
pick up wire* and *Bench Testing a PM5*, and the OpenRowingMonitor developer ("18 V continuous and 18 V
sinusoid, 6 periods per rotation"). None of this is an official Concept2 spec. **Measure a real D/E before
the first order** (§9).

Design limits that follow from this:

- Tip and Ring must tolerate 0…27 V continuously, in every mode.
- Ring must never be clamped into a supply rail.
- The hall supply must survive Ring being shorted to GND indefinitely.

---

## 2. Power

| Ref | Part | Function |
|---|---|---|
| J1 | HRO TYPE-C-31-M-12 | USB-C receptacle (USB 2.0). CC1/CC2 → **R1/R2 5.1 kΩ to GND** (sink Rd), so C-to-C chargers and phones (OTG) turn on VBUS. |
| F1 | nSMD050-33V PPTC (500 mA hold) | VBUS → +5V |
| U4 | USBLC6-2SC6 | ESD protection on D+/D− (flow-through), VBUS pin on +5V |
| U2 | AP2112K-3.3 | +5V → +3V3 LDO (600 mA, 250 mV dropout, ceramic-stable). EN pulled to +5V by R3. |
| C1/C2, C3/C4 | 10 µF 0603 + 100 nF | LDO input and output |

Nothing on the board can feed current back into VBUS. The Ring blocking diodes (§4) stop the Concept2
generator, and there are no clamps to +5V.

---

## 3. ESP32-C3-MINI-1-H4X (U1)

- C5/C6 (10 µF + 100 nF) at the 3V3 pin.
- **EN: R4 10 kΩ to 3V3 and C7 1 µF to GND** (Espressif's RC power-on delay), plus SW1 RESET to GND.
- Strapping pins:
  - GPIO9: SW2 BOOT to GND (internal pull-up).
  - **GPIO8: R5 10 kΩ pull-up.** Download mode needs GPIO8 = 1, so nothing else is connected to it.
  - **GPIO2: R6 10 kΩ pull-up** (the datasheet recommends this against glitches).
- The H4X variant is Espressif's recommendation for new designs (chip rev v1.1). The -N4 is NRND. Check
  that the firmware toolchain supports chip rev v1.1.
- Placement: the antenna sits flush with the right board edge. The module footprint's keep-out covers
  both copper layers, and nothing is placed within ~3 mm of it.

### GPIO map

| GPIO | Pin | Net | Use |
|---|---|---|---|
| 0 | 12 | RING_SENSE | ADC1_CH0: Ring voltage × 0.0909 (R27 100 k / R28 10 k, C16 10 nF) |
| 1 | 13 | — | spare (ADC1_CH1) |
| 2 | 5 | STRAP_GPIO2 | strap, pulled up; spare |
| 3 | 6 | SENSOR_OUT | comparator A output (hall / DC path) |
| 4 | 18 | HALL_3V3_EN | U5 enable, active high, R24 pull-down |
| 5 | 19 | HALL_5V_EN | U6 enable, active high, R26 pull-down |
| 6 | 20 | COIL_OUT | comparator B output (Concept2 / AC path) |
| 7 | 21 | LED_BLE | LED2 blue via R8 100 Ω (high = on) |
| 8 | 22 | STRAP_GPIO8 | strap, pulled up; spare. Don't load it. |
| 9 | 23 | BOOT | SW2 |
| 10 | 16 | LED_DATA | LED3 red via R9 330 Ω (high = on) |
| 18/19 | 26/27 | USB_DM/USB_DP | native USB Serial/JTAG (CDC) |
| 20/21 | 30/31 | — | UART0, unconnected |

LED1 (green, power) is driven from +3V3 through R7 (1 kΩ, ~0.7 mA).

---

## 4. Sensor jack and Ring supply

**J2: SHOU HAN PJ-242** (LCSC C431536), 2.5 mm TRS jack, SMD, with normally-closed tip/ring switch
contacts (left unconnected). The footprint `CustomFootprints:Jack_2.5mm_SHOUHAN_PJ-242` was built from
the datasheet land pattern. Datasheet pin → pad:

- pins 2 and 3 → **S** (both contact the sleeve)
- pin 6 → **T**
- pin 4 → **R**
- pin 7 → TN, pin 5 → RN

Input protection:

- **D1, D2: SMF24CA** bidirectional TVS on Tip and Ring (24 V standoff, 26.7 V breakdown).
- **C8:** 10 nF / 50 V on Ring.

### Hall supply on Ring (U5 = 3.3 V, U6 = 5 V)

Each rail is switched by an **SY6280AAC current-limited load switch**:

- **Current limit:** Ilim = 6800 / Rset with R23/R25 = 15 kΩ, which is about **0.45 A**. It folds back to
  about **0.23 A into a short**, with thermal shutdown and auto-retry. That is plenty for a hall sensor
  plus an indicator LED on the sensor board.
- **EN** is active high and driven directly from GPIO4/GPIO5. The 100 kΩ pull-downs keep both switches off
  through reset and boot, so Ring is unpowered until firmware decides otherwise.
- The switch blocks reverse current and actively discharges its output (150 Ω) when off.

Each output feeds Ring through a **B5819W Schottky (D6/D7, 40 V)**. That diode is what keeps the Concept2
D/E generator's ~18 V out of the rails. Its leakage is absorbed by the switch's output discharge. With both
switches on by mistake, the diodes simply OR the outputs and the 5 V one wins.

| Mode | Ring voltage at the sensor |
|---|---|
| Hall 3.3 V | ≈ 3.3 − 0.3 = **~3.0 V** |
| Hall 5 V | ≈ VBUS − 0.35 = **~4.65 V** (similar to an Arduino Nano's 5 V pin on USB) |

**RING_SENSE** (GPIO0) lets firmware check Ring:

- About 0 V with a supply enabled means a short: a mono plug (Concept2 C) or a damaged cable. Switch the
  supply off.
- More than ~0.5 V at the ADC (Ring > 6 V) with both supplies off means a Concept2 D/E generator is
  present. That makes auto-detection of D/E possible.

---

## 5. Signal conditioning (U3, LM393 on +5 V)

The LM393 runs from **+5 V**. That makes its input common-mode range 0…3.0 V over temperature, and both
references (1.65 V) sit well inside it. Its outputs are open-collector, pulled up to **3.3 V**, so they are
safe for the ESP32.

All comparator inputs are clamped by **BAT54S** pairs (D4, D5) to −0.3…+3.6 V. Each signal sits on the
inverting input, so even a below-ground excursion gives the correct output state.

**Tip pull-up:** R10 10 kΩ from +3V3 through D3 (B5819W). It provides the pull-up for open-collector hall
sensors (high level ≈ 3.1 V). D3 blocks the D/E tacho's 18 V from back-feeding 3V3. The pull-up puts only
~30 mV DC across a Concept2 C coil, which comparator B AC-couples away.

### Comparator A: DC path → SENSOR_OUT (GPIO3), for hall sensors

- Tip → **R11 100 kΩ** → TIP_DC (clamp D4) → IN−A. The 100 kΩ limits clamp injection into 3V3 to
  ≤ 0.24 mA even at 27 V.
- VREF_A = 1.65 V (R12/R13 100 k/100 k).
- Hysteresis from R14 (1 MΩ, with R15 10 k pull-up): thresholds **1.572 V / 1.728 V (~156 mV)**.
- Hall output low (≤ 0.4 V) gives SENSOR_OUT **high**. Hall output high (3.0–5 V, clamped at 3.6 V) gives
  it low.
- Also toggles cleanly on the Concept2 D/E 0–18 V tacho.

### Comparator B: AC-coupled zero-crossing → COIL_OUT (GPIO6), for Concept2

- Tip → **R16 10 kΩ (0603)** → C9 1 µF/50 V → COIL_AC.
- COIL_AC is biased to 1.65 V (R17/R18 100 k/100 k) and clamped by D5, then fed to IN−B. The high-pass
  corner is 1/(2π·60 kΩ·1 µF) = **2.7 Hz**.
- Clamping *after* the coupling cap keeps the clipping symmetric about 1.65 V, so zero-crossings don't
  shift with amplitude.
- VREF_B = 1.65 V (R19/R20 22 k/22 k).
- Hysteresis from R21 (1 MΩ, with R22 10 k pull-up): **±18 mV at the node, ~43 mV p-p at Tip**. That is
  sensitive enough for a slow Model C coil and still immune to idle noise.
- COIL_OUT is high during the negative half-cycle.

### Which output to read

| Mode | Read | Ring supply |
|---|---|---|
| Hall 3.3 V / 5 V | SENSOR_OUT (GPIO3), **rising** edge = magnet arrives. Comparator A inverts, so this matches the old Arduino `FALLING` ISR on the raw hall pin. | U5 / U6 on |
| Concept2 C | COIL_OUT (GPIO6), one edge polarity per cycle | off |
| Concept2 D/E | COIL_OUT (GPIO6), or SENSOR_OUT | off |

Compared with rev 1: the old 48–97 mV threshold existed because one comparator had to serve both the coil
and hall sensors. Each comparator now has its own threshold, suited to its sensor type.

---

## 6. Wrong-mode / fault behaviour

| Plugged in | Mode selected | What happens | Damage? |
|---|---|---|---|
| Hall sensor | Concept2 | sensor unpowered, no pulses | no |
| Hall sensor (4.5–24 V type) | 3.3 V | ~3.0 V supply, may not switch | no |
| Hall sensor (3.3 V-only type) | **5 V** | sensor receives ~4.65 V | **possibly the sensor**: the only case hardware can't catch. The app defaults to 3.3 V and asks for confirmation before selecting 5 V (§10). |
| Concept2 C (mono plug) | Hall 3.3/5 V | Ring shorted: U5/U6 limit at ~0.23 A with thermal retry; RING_SENSE ≈ 0 → firmware switches off | no |
| Concept2 D/E | Hall 3.3/5 V | D6/D7 reverse-biased by the ~18 V generator, no current either way | no |
| Concept2 D/E | any | tacho 18 V on Tip: ≤ 1.4 mA (R16) + 0.15 mA (R11) into the clamps, D3 blocks the pull-up path | no |
| any plug, inserted while Ring powered | — | momentary contact shorts current-limited | no |
| Tip/Ring surge or ESD | any | SMF24CA clamp, series resistors limit comparator input current | no |
| USB host | — | no path from the jack into VBUS; CC Rd = 5.1 kΩ | no |

---

## 7. PCB

- 2 layers, 1.6 mm FR-4, **55 × 45 mm**, 4× M3 mounting holes (NPTH) in the corners. All parts on top
  (single-sided SMT); J1's shield legs are the only through-hole joints.
- Left edge: USB-C (J1, face flush with the edge) and the sensor jack (J2, nose overhangs 1.5 mm).
  J2's front ring collides with an assembly rail on this edge; see the production notice in §9.
- Right edge: ESP32 antenna. RESET/BOOT are next to EN/GPIO9; LEDs are below the module.
- Back silkscreen: board name, source location (github.com/naibaf7/OpenRower), licence and copyright, as
  the CERN-OHL-S v2 notice asks of products (see `NOTICE`).
- Placement groups: Ring/hall supply next to J2's Ring pad; Tip front-end and comparator in the middle with
  outputs facing the module's GPIO side; USB ESD and the power chain beside J1.
- Design rules: JLC-compatible.
  - Net classes: Default 0.25 mm and Power 0.4 mm (+5V, +3V3, VBUS, HALL_*_OUT, JACK_RING), both with
    0.2 mm clearance.
  - Vias 0.6/0.3 mm, 0.5 mm copper-to-edge, 0.25 mm hole clearance.
  - References of 0402/0603 passives and diodes are on F.Fab (assembly drawing) so the silkscreen stays
    readable.
- Spacing (checked by `tools/check_spacing.py`):
  - all courtyards ≥ 0.3 mm apart, and pads of different parts ≥ 0.3 mm apart;
  - every component body ≥ 2.5 mm from the top and bottom edges. That is JLC's body-to-edge rule, and
    those two edges are where JLC adds assembly rails on boards smaller than 70 × 70 mm. The left edge has
    the connectors and the right edge has the antenna, so **order with rails on the top/bottom edges**.
- **USB:** the ESP32-C3 enumerates as `303A:1001` (CDC-ACM + JTAG). Flash it with esptool, Arduino or
  PlatformIO over USB-C with no buttons needed; BOOT + RESET is the fallback.
  The Android app matches vendor 0x303A in `usb_device_filter.xml` and uses `CdcAcmSerialDriver`. It holds
  DTR/RTS low on open, because esptool uses those lines as reset/boot-select on this port.
- Status: **routed. DRC with schematic parity: 0 violations, 0 unconnected, 0 footprint errors.**
  ERC: 0 violations.
- Routing (by `tools/route.py`):
  - 405 mm of track on the top layer and 122 mm on the bottom; 47 signal vias.
  - All power nets are 0.4 mm along their whole length.
  - Both layers are solid GND pours. The bottom stays a near-continuous plane.
  - 184 GND vias: a via next to every GND pad, 5 inside the ESP32 ground pads (4 in the exposed pad,
    1 in the crowded corner pad 51), and about 125 stitching vias.
- USB-C breakout: the pin order is DM, DP, DM, DP at 0.5 mm pitch, so one pair has to change layer.
  - DP joins on the top layer. DM's A7 pad drops to a via and joins underneath.
  - Both VBUS pad pairs drop to vias just past the pad row.
  - These stubs are hand-designed and fixed in `PREROUTES`.
- Known compromise: a few short bottom-layer jumps pass under the USB pair close to the module.
  Harmless at full-speed USB (12 Mbit/s).
- Nothing is routed under the ESP32 module body. The antenna keep-out is clear on both layers.

---

## 8. Bill of materials (JLCPCB/LCSC, all in stock 2026-10-17)

`bom/jlcpcb_bom.csv` is generated from the board: 30 lines, 18 Basic and 12 Extended parts. Extended parts
cost JLC's per-part setup fee.

| Ref | Part | LCSC | Type |
|---|---|---|---|
| U1 | ESP32-C3-MINI-1-H4X | C41349510 | ext |
| U2 | AP2112K-3.3TRG1 | C51118 | ext |
| U3 | LM393DR (TI) | C67470 | ext |
| U4 | USBLC6-2SC6 (ST) | C7519 | ext |
| U5, U6 | SY6280AAC | C55136 | ext |
| J1 | HRO TYPE-C-31-M-12 | C165948 | ext |
| J2 | SHOU HAN PJ-242 | C431536 | ext |
| F1 | nSMD050-33V | C70077 | ext |
| D1, D2 | SMF24CA (MDD) | C123811 | ext |
| D3, D6, D7 | B5819W SL | C8598 | Basic |
| D4, D5 | BAT54S,215 (Nexperia) | C47546 | ext |
| LED1 / LED2 / LED3 | XL-1608UGC-04 / XL-1608UBC-04 / KT-0603R | C965804 / C965807 / C2286 | ext / ext / Basic |
| SW1, SW2 | TS-1187A-B-A-B | C318884 | Basic |
| R, C | UNI-ROYAL 0402/0603, Samsung MLCC | see CSV | Basic |

The 10 µF parts are 0603 (C19702) so the footprint matches the part. A 10 V 0402 part would also lose most
of its capacitance at 5 V bias.

---

## 9. Before ordering / bring-up checklist

1. **Measure a Concept2 D/E**, PM unplugged, flywheel at sprint speed. Tip and Ring must stay below 24 V
   for D1/D2 to stay non-conducting. If either goes higher, use SMF30CA and re-check D6/D7 (40 V).
2. **J2 footprint:** check the custom land pattern against a physical PJ-242. On the first assembled board,
   confirm with a TRS plug that the plug tip reaches the T pad and the ring reaches the R pad *before*
   connecting any sensor.
3. **JLC placement preview:** the CPL already contains corrections for JLC's own footprints. They were
   derived from JLC's pad data and checked by simulating JLC's placement (every pad within 0.3 mm).
   - SOT-23-5, SOT-23-6 and SOIC-8 parts get +270°, and SOT-23 gets +180°.
   - The ESP32 moves 2.7 mm and the USB-C 1.57 mm along their axes, because JLC uses the pad-pattern
     centre as origin.
   - LED and diode polarity is checked against JLC's own symbol pin names.
   - Still glance over the preview. A new part needs `tools/jlc_align.py` re-run first; `make_fab.py`
     refuses to write a CPL without a correction for every part.
4. **Firmware:** `OpenRowerESP32/` (§10). It compiles but has not run on hardware yet. On the first
   board, follow the bring-up steps in §10 before plugging in a sensor.
5. **Order:** upload `openrower_sensor_gerbers.zip` (gerbers + PTH/NPTH drill), `bom/jlcpcb_bom.csv` and
   `bom/jlcpcb_cpl.csv`. Take all three from the hardware release on the
   [releases page](https://github.com/naibaf7/OpenRower/releases), or generate them with `make_fab.py`.
   - Board: 2 layers, 1.6 mm, standard 0.3 mm min hole, tented vias.
   - Assembly: top side only, edge rails on the top and bottom edges (or let JLC add them, but not on the
     left or right edge).
   - Coordinates: gerbers, drill and CPL all use the board's bottom-left corner as origin.
   - **Production notice (J2 front ring):** the jack's front ring (about Ø5 mm, from JLC's footprint)
     overhangs the left board edge by 1.5 mm. Its centre is 33.0 mm above the bottom-left corner
     (12.0 mm below the top-left corner). It collides with an assembly rail on the left edge, so that
     rail needs a hole drilled where the ring sits. Add this to the order remark:
     > J2 (2.5 mm jack) front ring overhangs the left board edge by 1.5 mm, ring diameter approx. 5 mm,
     > centred 33.0 mm above the bottom-left board corner. Please drill a clearance hole in the edge rail
     > at that position (at least 6 mm along the edge and 2 mm into the rail from the board edge) so the
     > jack can sit flat. Do not cut into the board itself.
6. **Regenerating:** run `gen_schematic.py`, then `gen_pcb.py --force` (this discards the routing), then
   `route.py`, then `make_fab.py`. The router is deterministic, taking about 2 minutes. For a silkscreen-only
   change, run `gen_pcb.py --silk`, then `make_fab.py`.

---

## 10. Firmware and app

**Firmware:** `OpenRowerESP32/`; flashing, protocol and LED meanings are in its README. Prebuilt image:
`OpenRowerESP32-<version>-merged.bin` from the [releases page](https://github.com/naibaf7/OpenRower/releases),
at offset 0x0. Flash it over USB-C.

- Sends the same `P,<interval_us>,<timestamp_us>` / `H` lines as the Arduino build, over BLE (FFE0/FFE1)
  and USB CDC at the same time. Timestamps are 64-bit, so they don't wrap after 71 minutes.
- Starts with Ring unpowered. The app selects the input with `M,HALL3V3|HALL5V|C2`.
- Before enabling a supply, it refuses if RING_SENSE > 6 V (a D/E generator). This is latched until the
  next `M`.
- After enabling a supply, it switches off if RING_SENSE < 0.8 V (a short or a Model C mono plug), and
  retries every 5 s.
- Status `S,<mode>,<ring_mV>,<OK|SHORT|GENERATOR>` goes out every 2 s. Auto-detect `D` probes with the
  3.3 V supply only and answers `A,<GENERATOR|SHORT|POWERED>,<mV>`.

**App:**
- **Machine profiles carry the sensor input.** Built-in profiles:

  | Profile | Pulses/rev | Input |
  |---|---|---|
  | Concept2 Model C | 3 | C2 |
  | Concept2 Model D | 6 | C2 |
  | Concept2 Model E | 6 | C2 |
  | Infinity R70 | 3 | HALL5V |

  Custom profiles default to HALL3V3.
- **Settings → Sensor input:** choose the input, or tap **Detect** to have the board probe the jack.
  Selecting 5 V asks for confirmation.
- **The app keeps the board in the profile's mode.** It sends the mode on connect, and again if the board
  reports something else (for example after a reset). Shorts and generator lockouts are shown as notices
  during a workout.
- **USB:** the app opens the ESP32's native USB port (`303A:1001`) with DTR/RTS held inactive, so connecting
  never resets the board.

**Bring-up on the first board:**
1. Flash with nothing in the jack. Connect a serial terminal and send `?`. Expect `S,OFF,<≈0>,OK`.
2. Send `M,HALL3V3`. Expect Ring ≈ 3.0 V on a meter and `S,HALL3V3,≈3000,OK`. Then send `M,HALL5V`: ≈ 4.65 V.
3. Short Ring to Sleeve with a wire; the switch limits it to ~0.23 A. A resistor would have to be below
   ~1.5 Ω to pull Ring under the 0.8 V threshold. Expect `SHORT` and the red LED blinking. Remove the
   short; within 5 s it should recover to `OK`.
4. Only then plug in the hall sensor or the Concept2.
