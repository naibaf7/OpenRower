package com.smartapes.openrower.bluetooth

/**
 * Parses the text protocol emitted by the OpenRowerArduino sketch and the OpenRowerESP32
 * firmware.
 *
 * Protocol lines (each terminated by '\n'):
 *   P,<interval_us>,<timestamp_us>  – sensor pulse
 *       interval_us  : µs since previous pulse (hardware-measured flywheel time)
 *       timestamp_us : board clock in µs at the moment the pulse was captured
 *   H                               – heartbeat / keep-alive (no data payload)
 *   I,<name>,<version>              – firmware info (ESP32 board only)
 *   S,<mode>,<ring_mV>,<state>      – sensor status (ESP32 board only): mode as requested
 *                                     (OFF/HALL3V3/HALL5V/C2), RING voltage, state OK/SHORT/GENERATOR
 *   A,<result>,<ring_mV>            – auto-detect result: GENERATOR / SHORT / POWERED
 *
 * The hardware timestamp makes angular-acceleration (alpha) independent of BLE delivery
 * timing, eliminating jitter-induced spikes in the force curve.
 *
 * Unknown / malformed lines are silently ignored.
 */
object RowingDataParser {

    sealed class Message {
        /**
         * Sensor pulse.
         * @param intervalUs  µs between this pulse and the previous one (flywheel time).
         * @param timestampUs board clock at capture; monotonically increasing.
         *                    Used by RowingEngine to compute alpha from hardware time.
         */
        data class Pulse(val intervalUs: Long, val timestampUs: Long) : Message()

        /** Keep-alive heartbeat from the board. */
        object Heartbeat : Message()

        /** Firmware identification. */
        data class Info(val name: String, val version: String) : Message()

        /** Sensor input status of the ESP32 board. */
        data class Status(val mode: String, val ringMv: Int, val state: String) : Message() {
            val isHallMode get() = mode == "HALL3V3" || mode == "HALL5V"
        }

        /** Result of an auto-detect (`D`) request. */
        data class DetectResult(val result: String, val ringMv: Int) : Message()
    }

    /**
     * Parse a single line (without the trailing newline) into a [Message],
     * or return null if the line is malformed / unrecognised.
     */
    fun parseLine(line: String): Message? {
        if (line.isEmpty()) return null
        return when {
            line == "H" -> Message.Heartbeat
            line.startsWith("P,") -> {
                val parts    = line.split(',')
                if (parts.size < 2) return null
                val interval  = parts[1].toLongOrNull() ?: return null
                if (interval <= 0) return null
                // timestamp field is optional for backwards compatibility
                val timestamp = if (parts.size >= 3) parts[2].toLongOrNull() ?: 0L else 0L
                Message.Pulse(interval, timestamp)
            }
            line.startsWith("S,") -> {
                val parts = line.split(',')
                if (parts.size < 4) return null
                Message.Status(parts[1], parts[2].toIntOrNull() ?: return null, parts[3])
            }
            line.startsWith("I,") -> {
                val parts = line.split(',')
                if (parts.size < 3) return null
                Message.Info(parts[1], parts[2])
            }
            line.startsWith("A,") -> {
                val parts = line.split(',')
                if (parts.size < 3) return null
                Message.DetectResult(parts[1], parts[2].toIntOrNull() ?: return null)
            }
            else -> null
        }
    }
}
