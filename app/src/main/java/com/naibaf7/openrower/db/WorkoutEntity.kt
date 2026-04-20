package com.naibaf7.openrower.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMs: Long,
    val modeName: String,
    val totalDurationSec: Double,
    val totalMeters: Double,
    val avgPowerWatts: Double,
    val totalCaloriesKcal: Double,
    val avgHeartRate: Int?,      // null if no HR monitor was connected
    val strokeCount: Int
)
