package com.naibaf7.openrower.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Insert
    suspend fun insertSplits(splits: List<SplitEntity>)

    @Query("SELECT * FROM workouts ORDER BY dateMs DESC")
    fun getAllWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM splits WHERE workoutId = :workoutId ORDER BY splitNumber")
    suspend fun getSplitsForWorkout(workoutId: Long): List<SplitEntity>

    @Query("SELECT DISTINCT (dateMs / 86400000) FROM workouts")
    suspend fun getWorkoutDays(): List<Long>  // days-since-epoch

    @Query("DELETE FROM workouts WHERE id = :workoutId")
    suspend fun deleteWorkout(workoutId: Long)

    @Query("DELETE FROM workouts WHERE dateMs < :beforeMs")
    suspend fun deleteWorkoutsOlderThan(beforeMs: Long)
}
