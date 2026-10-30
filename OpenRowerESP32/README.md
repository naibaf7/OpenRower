# OpenRowerESP32

Firmware for the OpenRower sensor board (ESP32-C3-MINI-1, hardware rev 2, see `hardware/`).
It replaces the Arduino + BT05 build (`OpenRowerArduino/`).

- **Outputs:** the same text protocol over BLE and native USB at the same time.
  - BLE uses the HM-10-style UART service (FFE0/FFE1), so the app treats it exactly like the BT05.
  - USB is a CDC serial port (`303A:1001`), for a phone on USB-OTG or a PC.
- **Sensor input:** the app selects it, and the board makes every choice safe on its own (below).

## Flashing

**Prebuilt image:** download `OpenRowerESP32-<version>-merged.bin` from the
[releases page](https://github.com/naibaf7/OpenRower/releases). Images are not kept in the repository.
The image contains the bootloader, partition table and app; write it at offset `0x0`. Either of these
works:

- Browser: <https://espressif.github.io/esptool-js/>. Connect, add the file at `0x0`, then Program.
- esptool:
  ```
  esptool.py --chip esp32c3 write_flash 0x0 OpenRowerESP32-<version>-merged.bin
  ```

USB-C alone is enough; the board normally enters the bootloader by itself. If it doesn't (for
example, old firmware that crashes at boot), hold **BOOT**, tap **RESET**, release BOOT, and flash
again.

**From source** (Arduino IDE 2 or arduino-cli, ESP32 core **3.x**, tested with 3.1.1 and 3.3.12):

- Board: **ESP32C3 Dev Module**
- USB CDC On Boot: **Enabled**
- Everything else at its default (4 MB flash, default partition table)

```
arduino-cli core install esp32:esp32@3.1.1
arduino-cli compile -b esp32:esp32:esp32c3:CDCOnBoot=cdc OpenRowerESP32
arduino-cli upload  -b esp32:esp32:esp32c3:CDCOnBoot=cdc -p /dev/ttyACM0 OpenRowerESP32
```

**Release image:** add `--output-dir OpenRowerESP32/release` to the compile command. Publish
`OpenRowerESP32/release/OpenRowerESP32.ino.merged.bin` on the releases page as
`OpenRowerESP32-<version>-merged.bin`, where `<version>` is `FW_VERSION` in the sketch. `*.bin` is
git-ignored. See also [RELEASING.md](../RELEASING.md).

**Licences:** the sketch is MIT. A firmware image also contains the Arduino core for ESP32 (LGPL-2.1 /
Apache-2.0), its BLE library, ESP-IDF, NimBLE and FreeRTOS. Their notices are in
[THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt), which goes with every published image.

Set `TEST_MODE 1` in the sketch to stream a synthetic 24 spm stroke without a sensor (same profile
as the Arduino test mode and the app's FakeDataSource).

## Protocol

One line per message, terminated by `\n`.

| Direction | Line | Meaning |
|---|---|---|
| board → app | `P,<interval_us>,<timestamp_us>` | one sensor pulse. The timestamp is a 64-bit µs clock since boot (never wraps). |
| | `H` | heartbeat, every 500 ms without other traffic |
| | `I,OpenRowerESP32,<version>` | firmware info |
| | `S,<mode>,<ring_mV>,<state>` | sensor status, every 2 s and on every change. `mode` = `OFF`/`HALL3V3`/`HALL5V`/`C2` (as requested); `state` = `OK`/`SHORT`/`GENERATOR` |
| | `A,<result>,<ring_mV>` | auto-detect result: `GENERATOR`, `SHORT` or `POWERED` |
| app → board | `M,<mode>` | select the sensor input. Answered with `S`. |
| | `D` | auto-detect what is plugged in, then return to the selected mode |
| | `?` | answer with `I` and `S` |

`P` and `H` are identical to the Arduino build, so older app versions still read pulses. They do
need to send `M`, because the board starts with its input off.

## Sensor inputs and safety

| Mode | Ring (sensor supply) | Input read | For |
|---|---|---|---|
| `HALL3V3` | 3.3 V (≈3.0 V at the plug), current-limited | GPIO3, rising edge = magnet arrives | hall sensors, reed switches |
| `HALL5V` | 5 V (≈4.65 V at the plug), current-limited | GPIO3 | 4.5–24 V hall sensors (A3144, OH3144) |
| `C2` | off | GPIO6, zero crossing of the coil/tacho | Concept2 Model C (3 pulses/rev), D/E (6 pulses/rev) |
| `OFF` | off | none | default after power-up or reset |

How the board protects itself and the sensor:

- **Starts with the supply off.** Both supplies stay off until the app sends `M`. The app re-sends
  the mode if the board reports `OFF` after a reset.
- **Before switching a supply on:** if Ring already carries more than 6 V, a Concept2 D/E generator
  is connected. The supply stays off with state `GENERATOR`, until the app selects a mode again.
- **After switching on:** if Ring stays below 0.8 V, it is shorted (for example a Concept2 Model C
  mono plug). The supply goes off with state `SHORT`, and the board re-checks every 5 s, so plugging
  in the right sensor recovers by itself.
- **While on:** Ring is checked every 50 ms for a short or an external voltage.
- **The hardware is safe regardless.** The SY6280 switches limit current to ~0.45 A (0.23 A into a
  short), and 40 V Schottky diodes block the generator, so these checks only keep the board from
  sitting in a fault and report the problem to the app.
- **Auto-detect (`D`)** probes Ring with the 3.3 V supply only, which is safe for every sensor type:
  - `GENERATOR`: a turning Concept2 D/E flywheel.
  - `SHORT`: a Concept2 Model C mono plug, or a D/E at rest.
  - `POWERED`: a hall sensor, or nothing plugged in.

LEDs:
- **Blue:** solid while BLE is connected; a short blink every 2 s while advertising.
- **Red:** a flash per pulse; blinks at 4 Hz while a hall supply is held off by a fault.
