package com.smartapes.openrower.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rowing_machines")
data class RowingMachine(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val distFactor: Float = 0.035f,
    val dragFactor: Float = 105f,
    val flywheelInertia: Float = 0.100f,
    val pulsesPerRevolution: Int = 3,
    /** [com.smartapes.openrower.model.SensorMode.code] sent to the sensor board (default 3.3 V hall). */
    val sensorMode: String = "HALL3V3"
)
