# OpenRower

An open-source rowing monitor for any rowing machine. A sensor on the flywheel sends the timing of each
pulse to an Android app. The app works out pace, power, stroke rate and distance, draws a live force curve
and records your workouts.

## Features

- **Workout modes:** free rowing, set distance, set time, target pace, and custom interval workouts built
  from segments.
- **Live display:**
  - pace per 500 m (current and average), stroke rate, power in watts or calories;
  - time and distance, elapsed and remaining, and the projected finish;
  - heart rate.
- **Charts:** a force curve for every stroke, and a stroke-by-stroke bar chart of power with heart-rate zones.
- **Custom workouts:** a timeline editor with a target pace per segment, music per segment (matched to your
  stroke rate by BPM, from a folder or M3U playlist) and a spoken countdown.
- **Training history** with splits for every workout.
- **Heart rate** from BLE chest straps that use the standard Heart Rate service (developed with a Polar H7).
- **Physics:** the drag factor is measured on every stroke. Flywheel inertia and the distance factor are
  stored per machine profile. You can calibrate the distance factor against the machine's own monitor.
- **Built-in profiles** for the Concept2 Model C, D and E and the Infinity R70.
- **Test mode** with simulated data, so you can try the app without any hardware.

## Hardware

You need one of these to connect a rowing machine:

| | OpenRower sensor board | DIY Arduino build |
|---|---|---|
| Board | ESP32-C3 board in [`hardware/`](hardware/openrower_sensor_circuit.md) | Arduino Nano + BT05/HM-10 BLE module |
| Sensor | Plug the machine's sensor into its 2.5 mm jack: hall sensors at 3.3 V or 5 V, Concept2 Model C pickup coil, Concept2 Model D/E generator | Hall sensor on pin 2, magnets on the flywheel (3 magnets 120° apart) |
| Link to the phone | BLE, or USB-C via USB-OTG | BLE, or USB via USB-OTG |
| Firmware | [`OpenRowerESP32/`](OpenRowerESP32/README.md) | [`OpenRowerArduino/`](OpenRowerArduino/OpenRowerArduino.ino) |

The app chooses the sensor board's input mode from the machine profile, or detects what is plugged in.
The board is designed so that no mode choice can damage it, the phone or the rowing machine. The one
exception is giving 5 V to a hall sensor that is only rated for 3.3 V, so the app asks before it switches
to 5 V.

## Getting started

1. **Install the app.** It needs Android 12 or newer. A Google Play release, published by Smartapes, is
   in preparation. Until then, build it from source: connect your phone with USB debugging on, then run
   ```
   ./gradlew installDebug
   ```
   or open the project in Android Studio and press Run.
2. **Set up the hardware.**
   - Sensor board: order it as described in
     [`hardware/openrower_sensor_circuit.md`](hardware/openrower_sensor_circuit.md) §9. Then flash the
     firmware image from the [releases page](https://github.com/naibaf7/OpenRower/releases), or build it
     yourself ([`OpenRowerESP32/README.md`](OpenRowerESP32/README.md)).
   - Arduino: wire up the hall sensor and BT module as described at the top of the sketch, then upload it.
     The sketch sets the BT module to the right baud rate by itself.
3. **In the app:**
   1. In **Settings**, pick or create a machine profile.
   2. Choose the rowing device (Scan BLE) and, for the sensor board, the sensor input.
   3. Go back to **Workout Setup**, pick a mode and start rowing.

To try the app without hardware, turn on **Test Mode** on the Workout Setup screen.

## Status

- **App and Arduino build:** in use on an Infinity R70 fitted with a hall sensor.
- **Sensor board (rev 2) and ESP32 firmware:** designed, and the firmware compiles, but neither has been
  tested on real hardware yet. Start with the bring-up checklist in
  [`hardware/openrower_sensor_circuit.md`](hardware/openrower_sensor_circuit.md) §10.
- **Concept2 profiles:** the constants come from OpenRowingMonitor's rower profiles and have not been checked
  on a real machine yet.

## Repository layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin) |
| `OpenRowerESP32/` | Firmware for the sensor board and the serial/BLE protocol |
| `OpenRowerArduino/` | Firmware for the Arduino Nano + BT05 build |
| `hardware/` | Sensor board: KiCad schematic and routed board, schematic PDF, generator scripts, design notes. Production files (gerbers, BOM, CPL) are on the releases page. |

## Privacy

OpenRower has no internet access. Your workouts stay on your device. See [PRIVACY.md](PRIVACY.md).

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). Maintainers release as described in
[RELEASING.md](RELEASING.md).

## License

- **Software** (the app, both firmwares, `hardware/tools/`): [MIT](LICENSE). Third-party licences: in the
  app under Settings → Open-Source Licenses, and for firmware images in
  [OpenRowerESP32/THIRD_PARTY_NOTICES.txt](OpenRowerESP32/THIRD_PARTY_NOTICES.txt).
- **Hardware** (the sensor board design in `hardware/`): [CERN-OHL-S v2](hardware/LICENSE). If you make
  boards for others, keep the source location visible; see [hardware/NOTICE](hardware/NOTICE).
