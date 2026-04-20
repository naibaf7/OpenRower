package com.naibaf7.openrower.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rowing_machines")
data class RowingMachine(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val distFactor: Float = 0.035f,
    val dragFactor: Float = 105f,
    val flywheelInertia: Float = 0.100f
)
