# Releasing

Binaries and generated fabrication files are never committed. The app goes to Google Play. Firmware images
and the board's fabrication files go to the
[GitHub releases page](https://github.com/naibaf7/OpenRower/releases).

## Android app (Google Play)

The app is published by **Smartapes** as `com.smartapes.openrower`. Never change that ID: Play treats a new
ID as a different app.

### One-time setup

1. **Upload key.** Create it once, outside the repository:
   ```
   keytool -genkeypair -v -keystore ~/keys/smartapes-upload.jks -alias upload \
           -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Smartapes"
   ```
   Back up the `.jks` file and both passwords. With Play App Signing (the default), Google holds the key
   that signs the app for users. This key only signs your uploads, so if it is lost, Play support can reset
   it.
2. **`keystore.properties`** in the project root. It is git-ignored:
   ```
   storeFile=/home/<you>/keys/smartapes-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
   Without this file, release builds come out unsigned and Play rejects them.
3. **Play Console:** create the app, then fill in the store listing and the App content forms (below).
4. **New personal accounts only:** if the Smartapes developer account is a personal account created after
   13 November 2023, Google requires a closed test first. At least 12 testers must stay opted in for 14 days
   in a row before you can apply for production access.

### Every release

1. In `app/build.gradle.kts`:
   - raise `versionCode` by one (Play refuses a code it has seen before);
   - set `versionName`.
   If you added or changed a library, update the in-app licences list in
   `app/src/main/res/raw/open_source_licenses.txt`. To see what the app bundles, run
   `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
2. Write the release notes in `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, at most 500
   characters.
3. Build and check:
   ```
   ./gradlew testDebugUnitTest bundleRelease
   keytool -printcert -jarfile app/build/outputs/bundle/release/app-release.aab   # must show CN=Smartapes
   ```
4. Upload `app-release.aab`. Use the internal testing track first, then promote it to production.
5. Tag the commit `app-v<versionName>`.

### Store listing

The text and the 512 × 512 icon are in `fastlane/metadata/android/en-US/`. This is the layout that fastlane
uploads from and that F-Droid reads. The icon is the launcher icon's visible area; regenerate it if the
launcher icon changes.

Still to make by hand:

- a feature graphic, 1024 × 500;
- at least 2 phone screenshots. Test Mode gives realistic workout screens without hardware;
- tablet screenshots, if you want the app listed for tablets.

### App content answers

| Form | Answer |
|---|---|
| Privacy policy | <https://github.com/naibaf7/OpenRower/blob/main/PRIVACY.md> |
| Ads | No ads |
| App access | All functionality is available without login. Instructions for reviewers: *"No rowing hardware is needed to review the app. On the Workout Setup screen, turn on Test Mode and tap START WORKOUT. The app then simulates a rower and heart-rate strap."* |
| Data safety | No data collected, no data shared. The app has no INTERNET permission; everything stays on the device. |
| Content rating | Complete the questionnaire as a utility/fitness app with no user interaction or objectionable content |
| Target audience | 13 and over. Leaving out under-13s keeps the app outside the Families policy. |
| Health apps | Fitness: activity tracking, and heart rate read from a Bluetooth strap. The app uses no Health Connect or body-sensor permissions. |
| Foreground services, financial features, government, news | None / no |

## ESP32 firmware (GitHub releases)

1. Bump `FW_VERSION` in `OpenRowerESP32/OpenRowerESP32.ino`.
2. Build the image (see [`OpenRowerESP32/README.md`](OpenRowerESP32/README.md)):
   ```
   arduino-cli compile -b esp32:esp32:esp32c3:CDCOnBoot=cdc --output-dir OpenRowerESP32/release OpenRowerESP32
   ```
3. Create a GitHub release tagged `fw-v<FW_VERSION>`. Attach:
   - `OpenRowerESP32/release/OpenRowerESP32.ino.merged.bin`, renamed to `OpenRowerESP32-<FW_VERSION>-merged.bin`;
   - `OpenRowerESP32/THIRD_PARTY_NOTICES.txt`. The licences of the bundled core require it. If you built with
     a core other than 3.3.12, update the versions and links in it first.

## Sensor board (GitHub releases)

1. From `hardware/`, run `python3 tools/make_fab.py`. It writes the gerber zip, `bom/jlcpcb_bom.csv` and
   `bom/jlcpcb_cpl.csv`. None of these are committed.
2. Create a GitHub release tagged `hw-rev<N>` (for example `hw-rev2`). Attach:
   - `openrower_sensor_gerbers.zip`;
   - `bom/jlcpcb_bom.csv` and `bom/jlcpcb_cpl.csv`;
   - `openrower_sensor_schematic.pdf`.
3. In the release text, point to the order and production notes in `hardware/openrower_sensor_circuit.md`
   §9 (the J2 rail notice).
