/*
 * OpenRowerArduino
 *
 * Reads a hall effect sensor on a rowing machine flywheel (3 magnets at 120°)
 * and transmits pulse timing data via Bluetooth serial or USB serial to the
 * OpenRower app.
 *
 * Pin configuration (change constants below to match your wiring):
 *   PIN_BT_RX  - Arduino RX from BT module TX (default: 11)
 *   PIN_BT_TX  - Arduino TX to BT module RX   (default: 10)
 *   PIN_HALL   - Hall sensor signal input      (default: 2, must support interrupt)
 *   BT_BAUD    - Serial baud rate              (default: 38400)
 *
 * Protocol (sent to Android app via BT or USB serial):
 *   Each hall sensor pulse triggers one line:
 *     P,<interval_us>,<timestamp_us>\n
 *   where <interval_us> is µs since the previous pulse (hardware-measured),
 *   and <timestamp_us> is the Arduino micros() value at the moment the pulse
 *   was captured.  The Android app uses the timestamp to compute angular
 *   acceleration (alpha) from hardware time rather than BLE arrival time,
 *   eliminating jitter-induced spikes in the force curve.
 *   The first pulse after power-on is skipped (no reference interval).
 *
 * Heartbeat line sent every HEARTBEAT_INTERVAL_MS when no pulses occur:
 *     H\n
 *
 * ── Output flags ─────────────────────────────────────────────────────────────
 * BT_ENABLED and USB_SERIAL_ENABLED are independent and can both be 1 at the
 * same time — the Arduino will then transmit to both the BT05/HM-10 module
 * and the hardware UART (USB OTG cable) simultaneously.
 *
 *   BT_ENABLED = 1       → initialise SoftwareSerial and the BT05/HM-10 module
 *   USB_SERIAL_ENABLED=1 → initialise hardware Serial at the same baud rate
 *
 * Set either flag to 0 to skip that interface (saves code/RAM if not wired).
 *
 * ── Test mode ────────────────────────────────────────────────────────────────
 * Set TEST_MODE to 1 to generate synthetic hall-sensor pulses instead of
 * reading the real sensor.  The simulated flywheel runs at 1000–1300 RPM with
 * a bell-shaped drive/recovery profile at 24 spm, matching FakeDataSource.kt.
 * Useful for end-to-end validation of the pipeline without a physical rowing
 * machine.  Set to 0 for production.
 */

#define BT_ENABLED          1 // 1 = use BT05/HM-10 SoftwareSerial module
#define USB_SERIAL_ENABLED  1 // 1 = also use hardware UART (USB OTG cable)
#define TEST_MODE           0 // 1 = synthetic pulses, 0 = real hall sensor

#if BT_ENABLED
  #include <SoftwareSerial.h>
#endif
#include <math.h>

// ── Pin configuration ────────────────────────────────────────────────────────
const int  PIN_BT_RX = 11;   // Arduino pin connected to BT module TX
const int  PIN_BT_TX = 10;   // Arduino pin connected to BT module RX
const int  PIN_HALL  = 2;    // Hall sensor input (must be INT0 or INT1)
const long BT_BAUD   = 38400;

// ── Timing ───────────────────────────────────────────────────────────────────
const unsigned long HEARTBEAT_INTERVAL_MS = 500;
const unsigned long DEBOUNCE_US           = 1000;

// ── Test-mode stroke parameters (mirror FakeDataSource.kt) ───────────────────
#if TEST_MODE
const float  TM_BASE_RPM        = 1000.0f; // flywheel RPM at catch / finish
const float  TM_PEAK_RPM        = 1300.0f; // flywheel RPM at end of drive
const float  TM_STROKE_RATE_SPM = 24.0f;   // strokes per minute
const float  TM_DRIVE_FRACTION  = 0.35f;   // fraction of stroke period = drive
const int    TM_MAGNETS         = 3;       // magnets on flywheel

// Derived constants
const unsigned long TM_STROKE_PERIOD_US =
    (unsigned long)(60.0e6f / TM_STROKE_RATE_SPM);      // µs per full stroke
const unsigned long TM_DRIVE_US =
    (unsigned long)(TM_STROKE_PERIOD_US * TM_DRIVE_FRACTION);
const unsigned long TM_RECOVERY_US = TM_STROKE_PERIOD_US - TM_DRIVE_US;
#endif

// ── Globals ──────────────────────────────────────────────────────────────────
#if BT_ENABLED
SoftwareSerial btSerial(PIN_BT_RX, PIN_BT_TX);
#endif

#if TEST_MODE == 0
volatile unsigned long lastPulseTime = 0;
volatile unsigned long pulseInterval = 0;
volatile unsigned long pulseTimestamp = 0;  // micros() at pulse capture
volatile bool          newPulse      = false;
volatile bool          firstPulse    = true;
#endif

unsigned long lastHeartbeat  = 0;
unsigned long runningTimestamp = 0;  // synthetic timestamp for TEST_MODE

// ── ISR (real sensor only) ───────────────────────────────────────────────────
#if TEST_MODE == 0
void hallISR() {
  unsigned long now = micros();

  if (firstPulse) {
    lastPulseTime = now;
    firstPulse    = false;
    return;
  }

  unsigned long interval = now - lastPulseTime;
  if (interval < DEBOUNCE_US) return;

  lastPulseTime  = now;
  pulseInterval  = interval;
  pulseTimestamp = now;
  newPulse       = true;
}
#endif

// ── BT05 baud initialisation ─────────────────────────────────────────────────
//
// The BT05 (HM-10 clone) remembers its baud rate across power cycles.
// On every boot we send AT+BAUD6 (= 38400) at the factory default (9600) first,
// then switch to BT_BAUD.  Two cases:
//
//   Module is at 9600 (factory / first run):
//     → receives AT+BAUD6, switches immediately, we then open at 38400. ✓
//
//   Module is already at 38400 (subsequent runs):
//     → AT+BAUD6 sent at 9600 arrives as garbage, module ignores it,
//       stays at 38400, we open at 38400. ✓
//
// Either way the module is at 38400 after this function returns.
#if BT_ENABLED
static void initBtBaud() {
  btSerial.begin(9600);
  delay(100);
  btSerial.print("AT+BAUD6\r\n");   // BT05/HM-10: BAUD6 = 38400
  delay(200);                   // module needs ~100 ms to switch and settle
  btSerial.end();
  btSerial.begin(BT_BAUD);
  delay(50);
}
#endif

// ── Setup ────────────────────────────────────────────────────────────────────
void setup() {
#if TEST_MODE == 0
  pinMode(PIN_HALL, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_HALL), hallISR, FALLING);
#endif

#if BT_ENABLED
  initBtBaud();
#endif
#if USB_SERIAL_ENABLED
  Serial.begin(BT_BAUD);
#endif
}

// ── Helpers ──────────────────────────────────────────────────────────────────

// Write a pre-formatted string to every enabled output interface.
static void outputPrint(const char* s) {
#if BT_ENABLED
  btSerial.print(s);
#endif
#if USB_SERIAL_ENABLED
  Serial.print(s);
#endif
}

static void sendPulse(unsigned long intervalUs, unsigned long timestampUs) {
  char buf[48];
  snprintf(buf, sizeof(buf), "P,%lu,%lu\n", intervalUs, timestampUs);
  outputPrint(buf);
  lastHeartbeat = millis();
}

// ── Test-mode pulse generator ─────────────────────────────────────────────────
#if TEST_MODE
/*
 * Emits one full stroke worth of synthetic pulses, blocking until done.
 * Drive:    ω rises from BASE_RPM → PEAK_RPM via cosine ramp → bell-shaped α
 * Recovery: ω falls from PEAK_RPM → BASE_RPM via cosine decay
 */
static void emitTestPhase(unsigned long durationUs, float rpmFrom, float rpmTo, bool isDrive) {
  // Use micros()-anchored scheduling: compute the absolute deadline for each pulse
  // so that serial TX time does not accumulate into the next interval.
  unsigned long phaseStart    = micros();
  unsigned long nextPulseTime = phaseStart;
  unsigned long elapsed       = 0;

  while (elapsed < durationUs) {
    float progress = (float)elapsed / (float)durationUs;
    if (progress > 1.0f) progress = 1.0f;

    float rpm = isDrive
      ? rpmFrom + (rpmTo - rpmFrom) * (1.0f - cosf(M_PI * progress)) / 2.0f
      : rpmTo   + (rpmFrom - rpmTo) * (1.0f + cosf(M_PI * progress)) / 2.0f;
    if (rpm < 10.0f) rpm = 10.0f;

    unsigned long intervalUs = (unsigned long)(60.0e6f / (rpm * TM_MAGNETS));

    // Schedule next pulse relative to current deadline, not wall time after TX
    nextPulseTime += intervalUs;
    runningTimestamp += intervalUs;
    sendPulse(intervalUs, runningTimestamp);

    // Busy-wait until the scheduled time — absorbs serial TX latency exactly
    while ((long)(micros() - nextPulseTime) < 0) { /* spin */ }

    elapsed = micros() - phaseStart;
  }
}

static void emitTestStroke() {
  emitTestPhase(TM_DRIVE_US,    TM_BASE_RPM, TM_PEAK_RPM, true);
  emitTestPhase(TM_RECOVERY_US, TM_PEAK_RPM, TM_BASE_RPM, false);
}
#endif

// ── Loop ─────────────────────────────────────────────────────────────────────
void loop() {
#if TEST_MODE
  emitTestStroke();
#else
  // Transmit a captured pulse interval
  if (newPulse) {
    unsigned long interval, timestamp;
    noInterrupts();
    interval  = pulseInterval;
    timestamp = pulseTimestamp;
    newPulse  = false;
    interrupts();

    sendPulse(interval, timestamp);
  }

  // Periodic heartbeat so the app knows the connection is alive
  unsigned long now = millis();
  if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
    outputPrint("H\n");
    lastHeartbeat = now;
  }
#endif
}
