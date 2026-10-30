package com.smartapes.openrower.model

import java.io.Serializable

enum class WorkoutMode : Serializable {
    FREE,       // unlimited, sliding window display
    DISTANCE,   // stop at target distance
    TIME,       // stop at target time
    TARGET,     // row to maintain a target pace
    CUSTOM      // custom workout with multiple segments
}

enum class EnergyUnit : Serializable { WATTS, CALORIES }

/**
 * Immutable configuration for a single workout session.
 *
 * Implements [Serializable] so it can be passed via [android.os.Bundle].
 *
 * @param mode           workout termination / display mode
 * @param targetDistance target distance in meters (used when mode == DISTANCE)
 * @param targetSeconds  target duration in seconds (used when mode == TIME)
 * @param targetPaceSec  target pace in seconds per 500 m (used when mode == TARGET)
 * @param splitMeters    split length in meters; default = targetDistance/5 or 500 for 2000 m
 * @param splitSeconds   split length in seconds (used when mode == TIME); default = 60
 * @param windowSeconds  sliding window width in seconds (used when mode == FREE)
 * @param energyUnit     display energy as watts or kcal/h
 * @param useTestMode    feed synthetic data instead of real Bluetooth
 * @param rowingDeviceAddress MAC address of the paired Arduino BT module (empty = none selected)
 * @param hrDeviceAddress     MAC address of the paired BLE HR monitor (empty = none selected)
 */
data class WorkoutConfig(
    val mode: WorkoutMode = WorkoutMode.FREE,
    val targetDistance: Int = 0,
    val targetSeconds: Int = 0,
    val targetPaceSec: Int = 120,
    val splitMeters: Int = 500,
    val splitSeconds: Int = 60,
    val windowSeconds: Int = 300,
    val energyUnit: EnergyUnit = EnergyUnit.WATTS,
    val useTestMode: Boolean = false,
    val rowingDeviceAddress: String = "",
    val hrDeviceAddress: String = "",
    /** Non-null when mode == CUSTOM; references a saved CustomWorkout in the DB. */
    val customWorkoutId: Long? = null,
    /** Display name for custom workouts (not persisted to DB separately). */
    val customWorkoutName: String = ""
) : Serializable {
    companion object {
        private const val serialVersionUID: Long = 2L
    }
}
