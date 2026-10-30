/*
 * OpenRowerESP32 -- firmware for the OpenRower sensor board (ESP32-C3-MINI-1, hardware rev 2)
 *
 * Reads the rowing machine sensor on the 2.5 mm jack and streams pulse timing to the OpenRower
 * app over BLE (HM-10 style UART service, same as the BT05 the Arduino build used) and over
 * native USB (CDC serial, e.g. a phone on USB-OTG).  Both outputs carry the same text protocol.
 *
 * ── Board pins (hardware/openrower_sensor_circuit.md) ───────────────────────────────────────
 *   GPIO3  SENSOR_OUT   comparator A (DC, 1.65 V threshold): hall sensors. HIGH while the hall
 *                       output is low, so a RISING edge = magnet arrives.
 *   GPIO6  COIL_OUT     comparator B (AC-coupled zero crossing): Concept2 coil / tacho.
 *   GPIO4  HALL_3V3_EN  SY6280 load switch: 3.3 V onto the jack RING (active high, pulled down)
 *   GPIO5  HALL_5V_EN   SY6280 load switch: 5 V onto the jack RING (active high, pulled down)
 *   GPIO0  RING_SENSE   ADC, RING voltage x 10/110
 *   GPIO7  LED_BLE      blue, GPIO10 LED_DATA red
 *
 * ── Protocol (one line each, '\n' terminated) ───────────────────────────────────────────────
 *   board -> app
 *     P,<interval_us>,<timestamp_us>   pulse; timestamp = 64-bit µs since boot (never wraps)
 *     H                                heartbeat, every 500 ms without other traffic
 *     I,OpenRowerESP32,<version>       firmware info (on connect and on '?')
 *     S,<mode>,<ring_mV>,<state>       sensor status: every 2 s and on every change
 *                                      mode  OFF | HALL3V3 | HALL5V | C2   (as requested)
 *                                      state OK | SHORT | GENERATOR
 *     A,<result>,<ring_mV>             auto-detect result: GENERATOR | SHORT | POWERED
 *   app -> board
 *     M,<mode>                         select sensor input; replies with S
 *     D                                auto-detect (probes RING with the 3.3 V supply only)
 *     ?                                reply with I and S
 *
 * ── Safety ──────────────────────────────────────────────────────────────────────────────────
 *   * Boots with both RING supplies off; they only switch on after an M command.
 *   * Before switching a supply on, RING must not carry an external voltage (> 6 V = Concept2
 *     D/E generator). After switching on, RING must rise (< 0.8 V = short, e.g. Concept2 Model C
 *     mono plug). While on, RING is checked every 50 ms. A fault switches the supply off and
 *     reports the state. A short is re-checked every 5 s; a generator stays locked out until
 *     the app sends M again.
 *   * The hardware already survives every case (current-limited, reverse-blocking switches);
 *     the firmware just avoids sitting in a fault and tells the app about it.
 *
 * Build: Arduino IDE / arduino-cli with the esp32 core 3.x, board "ESP32C3 Dev Module",
 *        "USB CDC On Boot: Enabled".   See README.md.
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include "esp_timer.h"

#if !ARDUINO_USB_CDC_ON_BOOT
#error "Set Tools > USB CDC On Boot: Enabled, so Serial is the native USB port the app talks to"
#endif

#define FW_VERSION  "1.0.0"
#define TEST_MODE   0         // 1 = synthetic pulses (no sensor needed), like OpenRowerArduino

// ── Pins ─────────────────────────────────────────────────────────────────────────────────
static const int PIN_SENSOR_OUT  = 3;
static const int PIN_COIL_OUT    = 6;
static const int PIN_HALL3V3_EN  = 4;
static const int PIN_HALL5V_EN   = 5;
static const int PIN_RING_SENSE  = 0;
static const int PIN_LED_BLE     = 7;
static const int PIN_LED_DATA    = 10;

// ── Tuning ───────────────────────────────────────────────────────────────────────────────
static const float    RING_DIVIDER      = 11.0f;     // (100k + 10k) / 10k
static const int      RING_SHORT_MV     = 800;       // supply on but RING below this = short
static const int      RING_GEN_OFF_MV   = 6000;      // supplies off, RING above = external source
static const int      RING_GEN_ON_MV    = 6500;      // supply on (<= 5.1 V), RING above = external
static const uint32_t DEBOUNCE_US       = 1000;      // same as OpenRowerArduino
static const uint32_t HEARTBEAT_MS      = 500;
static const uint32_t STATUS_MS         = 2000;
static const uint32_t MONITOR_MS        = 50;
static const uint32_t RETRY_MS          = 5000;
static const uint32_t SUPPLY_SETTLE_MS  = 30;

// ── Sensor mode / state ──────────────────────────────────────────────────────────────────
enum Mode  { MODE_OFF, MODE_HALL3V3, MODE_HALL5V, MODE_C2 };
enum State { ST_OK, ST_SHORT, ST_GENERATOR };
static const char* MODE_NAME[]  = {"OFF", "HALL3V3", "HALL5V", "C2"};
static const char* STATE_NAME[] = {"OK", "SHORT", "GENERATOR"};

static Mode     g_mode        = MODE_OFF;
static State    g_state       = ST_OK;
static bool     g_supplyOn    = false;
static int      g_ringMv      = 0;
static int      g_lowCount    = 0;
static uint32_t g_lastRetry   = 0;

// ── Pulse capture (ISR -> ring buffer of 64-bit timestamps) ─────────────────────────────
static const int   PULSE_BUF = 64;
static volatile uint64_t g_pulseTs[PULSE_BUF];
static volatile uint32_t g_pulseHead = 0;
static volatile uint64_t g_lastEdgeUs = 0;
static uint32_t g_pulseTail = 0;
static uint64_t g_prevPulseUs = 0;
static bool     g_havePrev = false;
static int      g_irqPin = -1;

void IRAM_ATTR onSensorEdge() {
  uint64_t now = esp_timer_get_time();
  if (now - g_lastEdgeUs < DEBOUNCE_US) return;
  g_lastEdgeUs = now;
  uint32_t h = g_pulseHead;
  g_pulseTs[h % PULSE_BUF] = now;
  g_pulseHead = h + 1;
}

// ── Output: USB CDC + BLE notify ─────────────────────────────────────────────────────────
static BLEServer*         g_server = nullptr;
static BLECharacteristic* g_uart   = nullptr;
static volatile bool      g_bleConnected = false;
static volatile uint16_t  g_mtu = 23;
static volatile bool      g_sayHello = false;
static String             g_bleTx;                 // pending notification bytes
static portMUX_TYPE       g_rxMux = portMUX_INITIALIZER_UNLOCKED;
static String             g_bleRx;                 // bytes written by the app (BLE task)
static String             g_usbRx;
static uint32_t           g_lastTxMs = 0;

static void emit(const char* line) {
  Serial.print(line);                              // tx timeout 0: dropped when no USB host
  if (g_bleConnected && g_bleTx.length() < 4096) g_bleTx += line;
  g_lastTxMs = millis();
}

static void flushBle() {
  if (!g_bleConnected || g_bleTx.length() == 0) return;
  static uint32_t lastNotifyUs = 0;
  uint32_t nowUs = micros();
  if (nowUs - lastNotifyUs < 2000) return;         // pace notifications (<= 500/s)
  lastNotifyUs = nowUs;
  size_t chunk = min((size_t)(g_mtu > 23 ? g_mtu - 3 : 20), (size_t)g_bleTx.length());
  g_uart->setValue((uint8_t*)g_bleTx.c_str(), chunk);
  g_uart->notify();
  g_bleTx.remove(0, chunk);
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { g_bleConnected = true; g_mtu = 23; g_sayHello = true; }
  void onDisconnect(BLEServer* s) override {
    g_bleConnected = false;
    s->startAdvertising();
  }
#if defined(CONFIG_NIMBLE_ENABLED)                // core 3.3+ builds the C3 with NimBLE
  void onMtuChanged(BLEServer*, ble_gap_conn_desc*, uint16_t mtu) override { g_mtu = mtu; }
#else
  void onMtuChanged(BLEServer*, esp_ble_gatts_cb_param_t* param) override { g_mtu = param->mtu.mtu; }
#endif
};

class UartCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* c) override {
    String v = c->getValue();
    portENTER_CRITICAL(&g_rxMux);
    if (g_bleRx.length() < 256) g_bleRx += v;
    portEXIT_CRITICAL(&g_rxMux);
  }
};

static void setupBle() {
  BLEDevice::init("OpenRower");
  BLEDevice::setMTU(247);
  g_server = BLEDevice::createServer();
  g_server->setCallbacks(new ServerCallbacks());
  BLEService* svc = g_server->createService(BLEUUID((uint16_t)0xFFE0));
  g_uart = svc->createCharacteristic(BLEUUID((uint16_t)0xFFE1),
      BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY |
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  g_uart->addDescriptor(new BLE2902());
  g_uart->setCallbacks(new UartCallbacks());
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(BLEUUID((uint16_t)0xFFE0));
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// ── RING measurement and supplies ────────────────────────────────────────────────────────
static int readRingMv() {
  uint32_t sum = 0;
  for (int i = 0; i < 8; i++) sum += analogReadMilliVolts(PIN_RING_SENSE);
  return (int)(sum / 8 * RING_DIVIDER);
}

static void suppliesOff() {
  digitalWrite(PIN_HALL3V3_EN, LOW);
  digitalWrite(PIN_HALL5V_EN, LOW);
  g_supplyOn = false;
}

static void supplyOn(Mode m) {
  suppliesOff();
  digitalWrite(m == MODE_HALL5V ? PIN_HALL5V_EN : PIN_HALL3V3_EN, HIGH);
  g_supplyOn = true;
}

static void sendStatus() {
  char buf[48];
  snprintf(buf, sizeof(buf), "S,%s,%d,%s\n", MODE_NAME[g_mode], g_ringMv, STATE_NAME[g_state]);
  emit(buf);
}

static void sendInfo() { emit("I,OpenRowerESP32," FW_VERSION "\n"); }

static void attachSensor(int pin) {
  if (g_irqPin >= 0) detachInterrupt(digitalPinToInterrupt(g_irqPin));
  g_irqPin = pin;
  g_pulseTail = g_pulseHead;                       // drop pulses captured on the previous input
  g_havePrev = false;                              // first pulse after a change: no interval
  if (pin >= 0) attachInterrupt(digitalPinToInterrupt(pin), onSensorEdge, RISING);
}

// Bring the hardware in line with g_mode. Returns true if the state changed.
static bool applyMode() {
  State before = g_state;
  bool  wasOn  = g_supplyOn;
  g_lowCount = 0;
  g_lastRetry = millis();
  if (g_mode == MODE_HALL3V3 || g_mode == MODE_HALL5V) {
    suppliesOff();
    delay(5);
    g_ringMv = readRingMv();
    if (g_ringMv > RING_GEN_OFF_MV) {
      g_state = ST_GENERATOR;                      // something powers RING: never back-drive it
    } else {
      supplyOn(g_mode);
      delay(SUPPLY_SETTLE_MS);
      g_ringMv = readRingMv();
      if (g_ringMv < RING_SHORT_MV) {
        suppliesOff();
        g_state = ST_SHORT;
      } else {
        g_state = ST_OK;
      }
    }
    attachSensor(PIN_SENSOR_OUT);
  } else {
    suppliesOff();
    g_ringMv = readRingMv();
    g_state = (g_mode == MODE_C2 && g_ringMv > RING_GEN_OFF_MV) ? ST_GENERATOR : ST_OK;
    attachSensor(g_mode == MODE_C2 ? PIN_COIL_OUT : -1);
  }
  return g_state != before || g_supplyOn != wasOn;
}

static void monitorRing() {
  static uint32_t last = 0;
  uint32_t now = millis();
  if (now - last < MONITOR_MS) return;
  last = now;
  bool hall = g_mode == MODE_HALL3V3 || g_mode == MODE_HALL5V;
  int mv = readRingMv();
  g_ringMv = mv;
  if (hall && g_supplyOn) {
    if (mv > RING_GEN_ON_MV) {
      suppliesOff();
      g_state = ST_GENERATOR;
      g_lastRetry = now;
      sendStatus();
    } else if (mv < RING_SHORT_MV) {
      if (++g_lowCount >= 3) {
        suppliesOff();
        g_state = ST_SHORT;
        g_lastRetry = now;
        sendStatus();
      }
    } else {
      g_lowCount = 0;
    }
  } else if (hall && g_state == ST_SHORT && now - g_lastRetry >= RETRY_MS) {
    // a short (e.g. mono plug) is re-checked so plugging in the right sensor recovers by itself;
    // a generator stays locked out until the app selects a mode again
    if (applyMode()) sendStatus();
    g_lastRetry = now;
  } else if (g_mode == MODE_C2) {
    State s = mv > RING_GEN_OFF_MV ? ST_GENERATOR : ST_OK;
    if (s != g_state) { g_state = s; sendStatus(); }
  }
}

static void runDetect() {
  Mode keep = g_mode;
  suppliesOff();
  delay(10);
  int mv = readRingMv();
  const char* result;
  if (mv > RING_GEN_OFF_MV) {
    result = "GENERATOR";                          // Concept2 D/E flywheel turning
  } else {
    // 3.3 V is safe for every sensor type and current-limited into a short
    digitalWrite(PIN_HALL3V3_EN, HIGH);
    delay(SUPPLY_SETTLE_MS);
    mv = readRingMv();
    digitalWrite(PIN_HALL3V3_EN, LOW);
    result = mv < RING_SHORT_MV ? "SHORT" : "POWERED";
  }
  char buf[40];
  snprintf(buf, sizeof(buf), "A,%s,%d\n", result, mv);
  emit(buf);
  g_mode = keep;
  applyMode();
  sendStatus();
}

// ── Commands ─────────────────────────────────────────────────────────────────────────────
static void handleCommand(String line) {
  line.trim();
  if (line.length() == 0) return;
  if (line == "?") { sendInfo(); sendStatus(); return; }
  if (line == "D") { runDetect(); return; }
  if (line.startsWith("M,")) {
    String m = line.substring(2);
    Mode want;
    if      (m == "OFF")     want = MODE_OFF;
    else if (m == "HALL3V3") want = MODE_HALL3V3;
    else if (m == "HALL5V")  want = MODE_HALL5V;
    else if (m == "C2")      want = MODE_C2;
    else return;
    if (want != g_mode || g_state != ST_OK) {
      g_mode = want;
      applyMode();
    }
    sendStatus();
  }
}

static void pollCommands() {
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n' || c == '\r') { handleCommand(g_usbRx); g_usbRx = ""; }
    else if (g_usbRx.length() < 64) g_usbRx += c;
  }
  String rx;
  portENTER_CRITICAL(&g_rxMux);
  rx = g_bleRx;
  g_bleRx = "";
  portEXIT_CRITICAL(&g_rxMux);
  static String partial;
  for (size_t i = 0; i < rx.length(); i++) {
    char c = rx[i];
    if (c == '\n' || c == '\r') { handleCommand(partial); partial = ""; }
    else if (partial.length() < 64) partial += c;
  }
}

// ── LEDs ─────────────────────────────────────────────────────────────────────────────────
static uint32_t g_dataLedOffMs = 0;

static void updateLeds() {
  uint32_t now = millis();
  // BLE: solid while connected, short blink every 2 s while advertising
  digitalWrite(PIN_LED_BLE, g_bleConnected || (now % 2000) < 60 ? HIGH : LOW);
  // DATA: flash per pulse; 4 Hz blink while a hall supply is held off by a fault
  bool fault = (g_mode == MODE_HALL3V3 || g_mode == MODE_HALL5V) && g_state != ST_OK;
  if (fault) digitalWrite(PIN_LED_DATA, (now % 250) < 125 ? HIGH : LOW);
  else       digitalWrite(PIN_LED_DATA, now < g_dataLedOffMs ? HIGH : LOW);
}

// ── Pulses ───────────────────────────────────────────────────────────────────────────────
static void sendPulse(uint64_t intervalUs, uint64_t timestampUs) {
  char buf[64];
  snprintf(buf, sizeof(buf), "P,%llu,%llu\n", (unsigned long long)intervalUs, (unsigned long long)timestampUs);
  emit(buf);
  g_dataLedOffMs = millis() + 15;
}

static void drainPulses() {
  while (g_pulseTail != g_pulseHead) {
    if (g_pulseHead - g_pulseTail > PULSE_BUF) {   // overrun: skip, and do not report an
      g_pulseTail = g_pulseHead - PULSE_BUF;       // interval spanning the dropped pulses
      g_havePrev = false;
    }
    uint64_t t = g_pulseTs[g_pulseTail % PULSE_BUF];
    g_pulseTail++;
    if (g_havePrev) sendPulse(t - g_prevPulseUs, t);
    g_prevPulseUs = t;
    g_havePrev = true;
  }
}

#if TEST_MODE
// Synthetic flywheel: 1000 -> 1300 rpm drive, back to 1000 rpm, 24 spm, 3 pulses/rev
static void testPulses() {
  static uint64_t next = 0, strokeStart = 0;
  uint64_t now = esp_timer_get_time();
  if (next == 0) { next = now; strokeStart = now; }
  if (now < next) return;
  const double period = 60e6 / 24.0, drive = period * 0.35;
  double ph = (double)(now - strokeStart);
  if (ph >= period) { strokeStart = now; ph = 0; }
  double rpm = ph < drive ? 1000 + 300 * (1 - cos(M_PI * ph / drive)) / 2
                          : 1300 - 300 * (1 - cos(M_PI * (ph - drive) / (period - drive))) / 2;
  uint64_t interval = (uint64_t)(60e6 / (rpm * 3));
  next += interval;
  sendPulse(interval, next);
}
#endif

// ── Setup / loop ─────────────────────────────────────────────────────────────────────────
void setup() {
  pinMode(PIN_HALL3V3_EN, OUTPUT);
  pinMode(PIN_HALL5V_EN, OUTPUT);
  suppliesOff();                                   // also held low by 100k pull-downs at reset
  pinMode(PIN_LED_BLE, OUTPUT);
  pinMode(PIN_LED_DATA, OUTPUT);
  pinMode(PIN_SENSOR_OUT, INPUT);                  // open-collector outputs, 10k pull-ups on board
  pinMode(PIN_COIL_OUT, INPUT);
  analogReadResolution(12);
  analogSetPinAttenuation(PIN_RING_SENSE, ADC_11db);   // 0..~2.5 V at the pin = RING 0..27 V

  setCpuFrequencyMhz(80);                          // lowest clock BLE and USB run at
  Serial.setTxTimeoutMs(0);                        // never block when no USB host listens
  Serial.begin(115200);
  setupBle();

  g_ringMv = readRingMv();
  sendInfo();
  sendStatus();
}

void loop() {
  uint32_t now = millis();
  pollCommands();
#if TEST_MODE
  testPulses();
#else
  drainPulses();
  monitorRing();
#endif
  if (g_sayHello) { g_sayHello = false; sendInfo(); sendStatus(); }
  static uint32_t lastStatus = 0;
  if (now - lastStatus >= STATUS_MS) { lastStatus = now; sendStatus(); }
  if (millis() - g_lastTxMs >= HEARTBEAT_MS) emit("H\n");
  flushBle();
  updateLeds();
  delay(1);
}
