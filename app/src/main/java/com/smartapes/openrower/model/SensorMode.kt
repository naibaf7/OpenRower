package com.smartapes.openrower.model

/**
 * Sensor input of the OpenRower ESP32 sensor board, stored per machine profile and sent to the
 * board as `M,<code>` whenever a connection comes up (older Arduino boards simply ignore it).
 *
 *  - [HALL_3V3] / [HALL_5V]: the board powers the sensor through the jack's RING contact and
 *    reads TIP with the DC comparator (hall sensors, reed switches).
 *  - [CONCEPT2]: no supply on RING; TIP goes to the AC zero-crossing comparator (Concept2
 *    Model C pickup coil, Model D/E generator tacho).
 *
 * 5 V is an explicit opt-in in the UI: a sensor rated for 3.3 V only can be damaged by it.
 */
enum class SensorMode(val code: String, val label: String) {
    HALL_3V3("HALL3V3", "Hall sensor – 3.3 V supply"),
    HALL_5V("HALL5V", "Hall sensor – 5 V supply"),
    CONCEPT2("C2", "Concept2 coil / generator – no supply");

    companion object {
        val DEFAULT = HALL_3V3
        fun fromCode(code: String?): SensorMode = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
