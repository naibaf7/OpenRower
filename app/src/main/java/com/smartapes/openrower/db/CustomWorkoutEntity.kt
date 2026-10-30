package com.smartapes.openrower.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_workouts")
data class CustomWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdMs: Long = System.currentTimeMillis(),
    val modifiedMs: Long = System.currentTimeMillis(),
    val mediaFolderPath: String? = null,
    val recoveryMusicPath: String? = null
)
