package com.smartapes.openrower.db

import android.content.SharedPreferences
import com.smartapes.openrower.model.SensorMode

/**
 * Built-in machine profiles and the single place that turns a profile into the preferences
 * the workout engine and the sensor board read ("dist_factor", "drag_factor",
 * "flywheel_inertia", "pulses_per_revolution", "sensor_mode").
 *
 * Built-ins use negative IDs so they never collide with Room's auto-increment IDs.
 * df = 2.8 × c_dist³ × 1e6 (Concept2 "magic constant" relationship used throughout the app).
 *
 * Concept2 values follow OpenRowingMonitor's rower profiles: Model C pickup = 3 pulses per
 * flywheel revolution, Model D/E generator = 6; flywheel inertia ≈ 0.1015 kg·m² on all three.
 * The drag factor is re-measured every stroke, so the starting value only matters for the
 * first strokes.
 */
object MachineProfiles {

    val BUILT_IN = listOf(
        RowingMachine(id = -4, name = "Concept2 Model C", distFactor = 0.0340f, dragFactor = 110.1f,
            flywheelInertia = 0.1015f, pulsesPerRevolution = 3, sensorMode = SensorMode.CONCEPT2.code),
        // id -1 was the generic "Concept2" (6 pulses/rev) profile: keep it so saved selections stay valid
        RowingMachine(id = -1, name = "Concept2 Model D", distFactor = 0.0340f, dragFactor = 110.1f,
            flywheelInertia = 0.1015f, pulsesPerRevolution = 6, sensorMode = SensorMode.CONCEPT2.code),
        RowingMachine(id = -3, name = "Concept2 Model E", distFactor = 0.0340f, dragFactor = 110.1f,
            flywheelInertia = 0.1015f, pulsesPerRevolution = 6, sensorMode = SensorMode.CONCEPT2.code),
        RowingMachine(id = -2, name = "Infinity R70", distFactor = 0.0294f, dragFactor = 71.2f,
            flywheelInertia = 0.020f, pulsesPerRevolution = 3, sensorMode = SensorMode.HALL_5V.code)
    )

    fun builtIn(id: Int): RowingMachine? = BUILT_IN.firstOrNull { it.id == id }

    /** Write all of [m]'s values to the shared preferences. */
    fun apply(prefs: SharedPreferences, m: RowingMachine) {
        prefs.edit()
            .putFloat("dist_factor", m.distFactor)
            .putFloat("flywheel_inertia", m.flywheelInertia)
            .putFloat("drag_factor", m.dragFactor)
            .putInt("pulses_per_revolution", m.pulsesPerRevolution)
            .putString("sensor_mode", m.sensorMode)
            .apply()
    }

    fun sensorMode(prefs: SharedPreferences): SensorMode =
        SensorMode.fromCode(prefs.getString("sensor_mode", null))
}
