package com.smartapes.openrower.db

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CustomWorkoutDao {

    @Query("SELECT * FROM custom_workouts ORDER BY modifiedMs DESC")
    fun getAllWorkoutsLive(): LiveData<List<CustomWorkoutEntity>>

    @Query("SELECT * FROM custom_workouts ORDER BY modifiedMs DESC")
    suspend fun getAllWorkouts(): List<CustomWorkoutEntity>

    @Query("SELECT * FROM custom_workouts WHERE id = :id")
    suspend fun getById(id: Long): CustomWorkoutEntity?

    @Query("SELECT * FROM workout_segments WHERE workoutId = :workoutId ORDER BY segmentIndex ASC")
    suspend fun getSegments(workoutId: Long): List<WorkoutSegmentEntity>

    @Insert
    suspend fun insertWorkout(entity: CustomWorkoutEntity): Long

    @Update
    suspend fun updateWorkout(entity: CustomWorkoutEntity)

    @Insert
    suspend fun insertSegments(segments: List<WorkoutSegmentEntity>)

    @Query("DELETE FROM workout_segments WHERE workoutId = :workoutId")
    suspend fun deleteSegments(workoutId: Long)

    @Query("DELETE FROM custom_workouts WHERE id = :id")
    suspend fun deleteWorkout(id: Long)
}
