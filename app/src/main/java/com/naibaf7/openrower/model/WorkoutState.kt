package com.naibaf7.openrower.model

/**
 * Per-stroke record stored in the engine and used by the bar chart.
 *
 * @param workoutTimeSec  workout-clock time (s) at the END of the stroke (drive→recovery)
 * @param startTimeSec    workout-clock time at the START of the stroke (catch)
 * @param paceSec500      average pace during this stroke (s / 500 m)
 * @param powerWatts      average power during this stroke (W)
 * @param heartRateBpm    HR at the moment the stroke was recorded (null = no monitor)
 * @param splitNumber     which split this stroke belongs to
 */
data class StrokeRecord(
    val workoutTimeSec: Double,
    val startTimeSec: Double,
    val paceSec500: Double,
    val powerWatts: Double,
    val heartRateBpm: Int?,
    val splitNumber: Int,
    val startMeters: Double = 0.0,
    val endMeters: Double = 0.0
) {
    val durationSec: Double get() = workoutTimeSec - startTimeSec
    val midTimeSec: Double  get() = startTimeSec + durationSec / 2.0
}

/**
 * Per-split summary retained for historical display.
 */
data class SplitSummary(
    val splitNumber: Int,
    val elapsedSeconds: Double,
    val distanceMeters: Double,
    val avgPaceSec500: Double,
    val avgPowerWatts: Double,
    val avgStrokeRate: Double,
    val avgHeartRate: Int? = null
)

/**
 * Full snapshot of live workout state, updated on every engine tick.
 */
data class WorkoutState(
    // ── Timing ────────────────────────────────────────────────────────────
    val elapsedSeconds: Double = 0.0,
    val remainingSeconds: Double? = null,

    // ── Distance ──────────────────────────────────────────────────────────
    val elapsedMeters: Double = 0.0,
    val remainingMeters: Double? = null,

    // ── Stroke metrics ────────────────────────────────────────────────────
    val strokeRate: Double = 0.0,
    val strokeCount: Int = 0,

    // ── Pace ──────────────────────────────────────────────────────────────
    val currentPaceSec500: Double = 0.0,
    val avgPaceSec500: Double = 0.0,

    // ── Power / energy ────────────────────────────────────────────────────
    val currentPowerWatts: Double = 0.0,
    val avgPowerWatts: Double = 0.0,
    val totalCaloriesKcal: Double = 0.0,

    // ── Heart rate ────────────────────────────────────────────────────────
    val heartRateBpm: Int? = null,

    // ── Split tracking ────────────────────────────────────────────────────
    val currentSplitNumber: Int = 1,
    val splitElapsedSeconds: Double = 0.0,
    val splitElapsedMeters: Double = 0.0,
    val splitHistory: List<SplitSummary> = emptyList(),

    // ── Stroke history (for bar chart) ────────────────────────────────────
    val strokeHistory: List<StrokeRecord> = emptyList(),

    // ── Chart window ──────────────────────────────────────────────────────
    val chartWindowStartSec: Double = 0.0,
    val chartWindowEndSec: Double = 120.0,
    // Meters-based window for DISTANCE mode (null = use seconds)
    val chartWindowStartMeters: Double? = null,
    val chartWindowEndMeters: Double? = null,

    // ── Projections ───────────────────────────────────────────────────────
    val projectedTotalMeters: Double? = null,
    val projectedTotalSeconds: Double? = null,

    // ── Force curve ───────────────────────────────────────────────────────
    // Each entry: Pair(timeWithinDriveSec, normalisedForce 0–1).
    // X positions are real timestamps, not evenly spaced.
    val forceCurveSamples: List<Pair<Float, Float>> = emptyList(),
    // Fixed x-axis width = avg drive duration × 1.3 (from last 5 strokes).
    val forceCurveWindowSec: Float = 1.2f,

    // ── Status ────────────────────────────────────────────────────────────
    val isFinished: Boolean = false,
    val isConnected: Boolean = false
)
