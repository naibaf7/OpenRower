package com.smartapes.openrower.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

enum class SegmentType : Serializable { WORKOUT, RECOVERY }

/** A single step within a workout segment's target pace progression. */
data class TargetPaceStep(
    val durationSec: Double,
    val strokesPerMin: Double
) : Serializable {
    companion object {
        fun fromJson(obj: JSONObject) = TargetPaceStep(
            durationSec   = obj.getDouble("dur"),
            strokesPerMin = obj.getDouble("spm")
        )

        fun listFromJson(json: String): List<TargetPaceStep> {
            if (json.isBlank() || json == "[]") return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) { emptyList() }
        }

        fun listToJson(steps: List<TargetPaceStep>): String {
            val arr = JSONArray()
            steps.forEach { step ->
                arr.put(JSONObject().apply {
                    put("dur", step.durationSec)
                    put("spm", step.strokesPerMin)
                })
            }
            return arr.toString()
        }
    }
}

/**
 * One segment in a custom workout – either a rowing WORKOUT interval
 * or a RECOVERY rest period.
 *
 * WORKOUT: defined by time (durationSec) OR distance (distanceMeters).
 * RECOVERY: defined by time (durationSec). If durationSec is null it is
 *   "timeless" – it ends the moment the rower pulls again (after a 10 s lockout).
 */
data class WorkoutSegment(
    val type: SegmentType = SegmentType.WORKOUT,
    val durationSec: Double? = null,
    val distanceMeters: Double? = null,
    val targetPaceSteps: List<TargetPaceStep> = emptyList(),
    val musicSongPath: String? = null,
    val musicPlaylistPath: String? = null,
    val musicStartMs: Long = 0L,
    val musicEndMs: Long = 0L
) : Serializable {
    val isTimeless: Boolean get() = type == SegmentType.RECOVERY && durationSec == null

    /** Rough estimate for timeline sizing (at a nominal 2:00 / 500 m pace). */
    val estimatedDurationSec: Double get() = when {
        durationSec != null -> durationSec
        distanceMeters != null -> distanceMeters / 500.0 * 120.0
        else -> 60.0
    }
}

/** A complete custom workout consisting of ordered segments. */
data class CustomWorkout(
    val id: Long = 0L,
    val name: String = "Custom Workout",
    val segments: List<WorkoutSegment> = emptyList(),
    val mediaFolderPath: String? = null,
    val recoveryMusicPath: String? = null
) : Serializable
