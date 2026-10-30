package com.smartapes.openrower.bluetooth

import com.smartapes.openrower.bluetooth.RowingDataParser.Message
import com.smartapes.openrower.db.MachineProfiles
import com.smartapes.openrower.model.SensorMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines exactly as OpenRowerArduino and OpenRowerESP32 emit them. */
class RowingDataParserTest {

    @Test fun arduinoPulseAndHeartbeat() {
        assertEquals(Message.Pulse(15234, 4_000_123), RowingDataParser.parseLine("P,15234,4000123"))
        assertEquals(Message.Pulse(15234, 0), RowingDataParser.parseLine("P,15234"))
        assertEquals(Message.Heartbeat, RowingDataParser.parseLine("H"))
    }

    @Test fun esp32PulseHas64BitTimestamp() {
        // the ESP32 clock does not wrap after 71 minutes like Arduino micros()
        assertEquals(Message.Pulse(5021, 9_876_543_210_123), RowingDataParser.parseLine("P,5021,9876543210123"))
    }

    @Test fun esp32StatusInfoAndDetect() {
        val s = RowingDataParser.parseLine("S,HALL5V,4650,OK") as Message.Status
        assertEquals("HALL5V", s.mode); assertEquals(4650, s.ringMv); assertEquals("OK", s.state)
        assertTrue(s.isHallMode)
        val c2 = RowingDataParser.parseLine("S,C2,18200,GENERATOR") as Message.Status
        assertEquals(false, c2.isHallMode)
        assertEquals(Message.Info("OpenRowerESP32", "1.0.0"), RowingDataParser.parseLine("I,OpenRowerESP32,1.0.0"))
        assertEquals(Message.DetectResult("SHORT", 120), RowingDataParser.parseLine("A,SHORT,120"))
    }

    @Test fun malformedLinesAreIgnored() {
        assertNull(RowingDataParser.parseLine(""))
        assertNull(RowingDataParser.parseLine("P,0,1"))
        assertNull(RowingDataParser.parseLine("S,HALL5V"))
        assertNull(RowingDataParser.parseLine("S,HALL5V,abc,OK"))
        assertNull(RowingDataParser.parseLine("A,SHORT"))
        assertNull(RowingDataParser.parseLine("garbage"))
    }

    @Test fun sensorModeCodesMatchFirmware() {
        assertEquals(listOf("HALL3V3", "HALL5V", "C2"), SensorMode.entries.map { it.code })
        assertEquals(SensorMode.HALL_3V3, SensorMode.fromCode(null))
        assertEquals(SensorMode.HALL_3V3, SensorMode.fromCode("bogus"))
    }

    @Test fun concept2Profiles() {
        val byName = MachineProfiles.BUILT_IN.associateBy { it.name }
        assertEquals(3, byName.getValue("Concept2 Model C").pulsesPerRevolution)
        assertEquals(6, byName.getValue("Concept2 Model D").pulsesPerRevolution)
        assertEquals(6, byName.getValue("Concept2 Model E").pulsesPerRevolution)
        listOf("Concept2 Model C", "Concept2 Model D", "Concept2 Model E").forEach {
            assertEquals(SensorMode.CONCEPT2.code, byName.getValue(it).sensorMode)
        }
        assertEquals(-1, byName.getValue("Concept2 Model D").id)    // keeps old "Concept2" selections
        assertEquals(MachineProfiles.BUILT_IN.size, MachineProfiles.BUILT_IN.map { it.id }.toSet().size)
        assertTrue(MachineProfiles.BUILT_IN.all { it.id < 0 })
    }
}
