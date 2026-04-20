package com.naibaf7.openrower.bluetooth

/**
 * Parses the text protocol emitted by the OpenRowerArduino sketch.
 *
 * Protocol lines (each terminated by '\n'):
 *   P,<interval_us>,<timestamp_us>  – hall sensor pulse
 *       interval_us  : µs since previous pulse (hardware-measured flywheel time)
 *       timestamp_us : Arduino micros() at the moment the pulse was captured
 *   H                               – heartbeat / keep-alive (no data payload)
 *
 * The hardware timestamp makes angular-acceleration (alpha) independent of BLE
 * delivery timing, eliminating jitter-induced spikes in the force curve.
 *
 * Unknown / malformed lines are silently ignored.
 */
object RowingDataParser {

    sealed class Message {
        /**
         * Hall sensor pulse.
         * @param intervalUs  µs between this pulse and the previous one (flywheel time).
         * @param timestampUs Arduino micros() at capture; monotonically increasing.
         *                    Used by RowingEngine to compute alpha from hardware time.
         */
        data class Pulse(val intervalUs: Long, val timestampUs: Long) : Message()

        /** Keep-alive heartbeat from the Arduino. */
        object Heartbeat : Message()
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
            else -> null
        }
    }
}
