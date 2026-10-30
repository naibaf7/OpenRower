package com.smartapes.openrower.engine

import com.smartapes.openrower.model.CustomWorkout
import com.smartapes.openrower.model.SegmentType
import com.smartapes.openrower.model.WorkoutSegment

data class CustomWorkoutEngineState(
    val segmentIndex: Int = 0,
    val totalSegments: Int = 0,
    val segmentType: SegmentType = SegmentType.WORKOUT,
    val segmentElapsedSec: Double = 0.0,
    val segmentRemainingSeconds: Double? = null,
    val countdownSeconds: Int? = null,
    val targetStrokeRate: Double? = null,
    val isWaitingForPull: Boolean = false,
    val isFinished: Boolean = false
)

/**
 * Manages execution state for a custom multi-segment workout.
 *
 * The caller advances time by calling [advance] on every engine pulse or tick.
 * Transition callbacks fire on segment boundaries or when the workout ends.
 * TTS countdown (10 → 0) is fired via [onCountdown] each integer step.
 *
 * Timeless recovery: ends when the rower pulls again, subject to a 10-second
 * lockout so the rower can safely stop without the recovery being skipped.
 */
class CustomWorkoutController(val workout: CustomWorkout) {

    private val segments = workout.segments
    private var segmentIndex = 0
    private var segmentElapsedSec = 0.0
    private var segmentElapsedMeters = 0.0
    private var totalElapsedSec = 0.0
    private var isFinished = false

    // Timeless-recovery state
    private var isWaitingForPull = false
    private var lockoutUntilSec = 0.0
    private var prevStrokeCount = -1

    private var lastCountdownEmitted = -1

    /** Called when transitioning from segment [from] to segment [to]. */
    var onSegmentTransition: ((from: Int, to: Int) -> Unit)? = null

    /** Called with the countdown integer (10 down to 0) before a non-recovery segment ends. */
    var onCountdown: ((seconds: Int) -> Unit)? = null

    /** Called when the final segment is complete. */
    var onFinished: (() -> Unit)? = null

    val currentSegment: WorkoutSegment? get() = segments.getOrNull(segmentIndex)
    val totalSegments: Int get() = segments.size

    /**
     * Advance controller by [dtSec] wall seconds.
     * [newMeters] is distance covered in this interval.
     * [strokeCount] is the cumulative stroke count from the engine.
     */
    fun advance(dtSec: Double, newMeters: Double, strokeCount: Int) {
        if (isFinished) return
        val seg = currentSegment ?: run {
            finish(); return
        }

        totalElapsedSec += dtSec

        if (seg.isTimeless) {
            segmentElapsedSec += dtSec
            if (isWaitingForPull) {
                // New stroke detected and we are past the lockout window
                if (prevStrokeCount >= 0 && strokeCount > prevStrokeCount
                    && totalElapsedSec >= lockoutUntilSec
                ) {
                    prevStrokeCount = strokeCount
                    advanceToNext()
                    return
                }
            }
            prevStrokeCount = strokeCount
            return
        }

        segmentElapsedSec += dtSec
        segmentElapsedMeters += newMeters

        val segComplete = when {
            seg.durationSec != null    -> segmentElapsedSec    >= seg.durationSec
            seg.distanceMeters != null -> segmentElapsedMeters >= seg.distanceMeters
            else                       -> false
        }

        // Countdown before time-based segment ends (only for workout segments moving to next)
        if (!segComplete && seg.durationSec != null) {
            val remaining = seg.durationSec - segmentElapsedSec
            if (remaining in 0.0..10.0) {
                val c = remaining.toInt().coerceIn(0, 10)
                if (c != lastCountdownEmitted) {
                    lastCountdownEmitted = c
                    onCountdown?.invoke(c)
                }
            }
        }

        if (segComplete) advanceToNext()
    }

    private fun advanceToNext() {
        val from = segmentIndex
        segmentIndex++
        segmentElapsedSec = 0.0
        segmentElapsedMeters = 0.0
        lastCountdownEmitted = -1

        if (segmentIndex >= segments.size) { finish(); return }

        val next = segments[segmentIndex]
        if (next.isTimeless) {
            isWaitingForPull = true
            lockoutUntilSec  = totalElapsedSec + 10.0
            prevStrokeCount  = -1
        } else {
            isWaitingForPull = false
        }

        onSegmentTransition?.invoke(from, segmentIndex)
    }

    private fun finish() {
        isFinished = true
        onFinished?.invoke()
    }

    fun getState(): CustomWorkoutEngineState {
        val seg = currentSegment
        val remaining: Double? = when {
            seg == null            -> null
            seg.isTimeless         -> null
            seg.durationSec != null -> (seg.durationSec - segmentElapsedSec).coerceAtLeast(0.0)
            else                   -> null
        }
        val countdown = remaining?.let {
            if (it <= 10.0 && it >= 0.0) it.toInt().coerceIn(0, 10) else null
        }
        return CustomWorkoutEngineState(
            segmentIndex           = segmentIndex,
            totalSegments          = segments.size,
            segmentType            = seg?.type ?: SegmentType.WORKOUT,
            segmentElapsedSec      = segmentElapsedSec,
            segmentRemainingSeconds = remaining,
            countdownSeconds       = countdown,
            targetStrokeRate       = seg?.let { computeTargetRate(it, segmentElapsedSec) },
            isWaitingForPull       = isWaitingForPull,
            isFinished             = isFinished
        )
    }

    private fun computeTargetRate(seg: WorkoutSegment, elapsed: Double): Double? {
        if (seg.targetPaceSteps.isEmpty()) return null
        var cumTime = 0.0
        for (step in seg.targetPaceSteps) {
            cumTime += step.durationSec
            if (elapsed < cumTime) return step.strokesPerMin
        }
        return seg.targetPaceSteps.lastOrNull()?.strokesPerMin
    }
}
