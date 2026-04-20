package com.naibaf7.openrower.db

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

class WorkoutRepository(context: Context) {

    private val db  = WorkoutDatabase.getInstance(context)
    private val dao = db.workoutDao()

    suspend fun save(workout: WorkoutEntity, splits: List<SplitEntity>) {
        db.withTransaction {
            val id = dao.insertWorkout(workout)
            if (splits.isNotEmpty()) {
                dao.insertSplits(splits.map { it.copy(workoutId = id) })
            }
        }
    }

    fun getAllWorkouts(): Flow<List<WorkoutEntity>> = dao.getAllWorkouts()

    suspend fun getSplits(workoutId: Long): List<SplitEntity> =
        dao.getSplitsForWorkout(workoutId)

    suspend fun getWorkoutDays(): List<Long> = dao.getWorkoutDays()

    suspend fun deleteWorkout(workoutId: Long) = dao.deleteWorkout(workoutId)

    suspend fun deleteOlderThan(beforeMs: Long) = dao.deleteWorkoutsOlderThan(beforeMs)
}
