package com.smartapes.openrower.db

import android.app.Application
import androidx.lifecycle.LiveData
import com.smartapes.openrower.model.CustomWorkout
import com.smartapes.openrower.model.SegmentType
import com.smartapes.openrower.model.TargetPaceStep
import com.smartapes.openrower.model.WorkoutSegment

class CustomWorkoutRepository(application: Application) {

    private val dao: CustomWorkoutDao =
        WorkoutDatabase.getInstance(application).customWorkoutDao()

    val allWorkoutsLive: LiveData<List<CustomWorkoutEntity>> = dao.getAllWorkoutsLive()

    suspend fun getAll(): List<CustomWorkout> =
        dao.getAllWorkouts().map { hydrate(it) }

    suspend fun load(id: Long): CustomWorkout? {
        val entity = dao.getById(id) ?: return null
        return hydrate(entity)
    }

    /** Saves a new or existing custom workout; returns the assigned id. */
    suspend fun save(workout: CustomWorkout): Long {
        val entity = CustomWorkoutEntity(
            id                = workout.id,
            name              = workout.name,
            mediaFolderPath   = workout.mediaFolderPath,
            recoveryMusicPath = workout.recoveryMusicPath,
            modifiedMs        = System.currentTimeMillis()
        )
        val id = if (workout.id == 0L) dao.insertWorkout(entity)
                 else { dao.updateWorkout(entity); workout.id }

        dao.deleteSegments(id)
        dao.insertSegments(workout.segments.mapIndexed { i, seg ->
            WorkoutSegmentEntity(
                workoutId        = id,
                segmentIndex     = i,
                type             = seg.type.name,
                durationSec      = seg.durationSec,
                distanceMeters   = seg.distanceMeters,
                paceStepsJson    = TargetPaceStep.listToJson(seg.targetPaceSteps),
                musicSongPath    = seg.musicSongPath,
                musicPlaylistPath = seg.musicPlaylistPath,
                musicStartMs     = seg.musicStartMs,
                musicEndMs       = seg.musicEndMs
            )
        })
        return id
    }

    suspend fun delete(id: Long) = dao.deleteWorkout(id)

    private suspend fun hydrate(entity: CustomWorkoutEntity): CustomWorkout {
        val segments = dao.getSegments(entity.id).map { seg ->
            WorkoutSegment(
                type             = if (seg.type == "RECOVERY") SegmentType.RECOVERY else SegmentType.WORKOUT,
                durationSec      = seg.durationSec,
                distanceMeters   = seg.distanceMeters,
                targetPaceSteps  = TargetPaceStep.listFromJson(seg.paceStepsJson),
                musicSongPath    = seg.musicSongPath,
                musicPlaylistPath = seg.musicPlaylistPath,
                musicStartMs     = seg.musicStartMs,
                musicEndMs       = seg.musicEndMs
            )
        }
        return CustomWorkout(
            id                = entity.id,
            name              = entity.name,
            segments          = segments,
            mediaFolderPath   = entity.mediaFolderPath,
            recoveryMusicPath = entity.recoveryMusicPath
        )
    }
}
