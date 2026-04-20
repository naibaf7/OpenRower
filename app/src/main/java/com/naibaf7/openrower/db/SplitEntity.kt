package com.naibaf7.openrower.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "splits",
    foreignKeys = [ForeignKey(
        entity = WorkoutEntity::class,
        parentColumns = ["id"],
        childColumns = ["workoutId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("workoutId")]
)
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val splitNumber: Int,
    val durationSec: Double,
    val distanceMeters: Double,
    val avgPowerWatts: Double,
    val avgHeartRate: Int?,
    val avgStrokeRate: Double
)
