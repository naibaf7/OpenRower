package com.smartapes.openrower.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "workout_segments",
    foreignKeys = [ForeignKey(
        entity = CustomWorkoutEntity::class,
        parentColumns = ["id"],
        childColumns = ["workoutId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("workoutId")]
)
data class WorkoutSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val workoutId: Long,
    val segmentIndex: Int,
    val type: String,            // "WORKOUT" | "RECOVERY"
    val durationSec: Double? = null,
    val distanceMeters: Double? = null,
    val paceStepsJson: String = "[]",
    val musicSongPath: String? = null,
    val musicPlaylistPath: String? = null,
    val musicStartMs: Long = 0L,
    val musicEndMs: Long = 0L
)
