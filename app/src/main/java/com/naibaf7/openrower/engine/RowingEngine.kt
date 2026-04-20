package com.naibaf7.openrower.engine

import com.naibaf7.openrower.debug.PulseLogger
import com.naibaf7.openrower.model.SplitSummary
import com.naibaf7.openrower.model.StrokeRecord
import com.naibaf7.openrower.model.WorkoutConfig
import com.naibaf7.openrower.model.WorkoutMode
import com.naibaf7.openrower.model.WorkoutState
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

/**
 * Core rowing physics and workout-state machine.
 *
 * ── Force curve shape ─────────────────────────────────────────────────────
 * FakeDataSource uses ω(t) = ωₛ + Δω·(1−cos(πt/T))/2 for the drive phase.
 * The derivative α = dω/dt = Δω·π/(2T)·sin(πt/T) is a pure sine arch —
 * the bell-shaped force curve characteristic of air-resistance ergometers.
 *
 * In RowingEngine we reconstruct α from consecutive raw ω samples:
 *   α_n = (ω_n − ω_{n-1}) / dt_n
 * Positive α during drive → accumulate in force-curve buffer.
 * The buffer is normalised against the rolling max of the last 10 strokes.
 *
 * ── Physics model ─────────────────────────────────────────────────────────
 * Power:    P = k_drag · ω̄³          (EMA-smoothed ω for stability)
 * Speed:    v = c_dist · ω̄           (m/s)
 * Pace:     500 / v                   (s / 500 m)
 * Calories: (P·4 + 300/3600) · dt    (kcal, rough metabolic estimate)
 *
 * ── Calibration ───────────────────────────────────────────────────────────
 * dragFactor ≈ 0.000105  (drag factor 105 on a 100-150 scale; stored raw)
 * distFactor ≈ 0.035     (tune so displayed distance ≈ calibrated erg;
 *                          assumes flywheel at ~1000–1300 RPM)
 */
class RowingEngine(private val config: WorkoutConfig, val logger: PulseLogger? = null) {

    // ── Calibration ───────────────────────────────────────────────────────
    var dragFactor: Double = 0.000105
    var distFactor: Double = 0.035
    var flywheelInertia: Double = 0.1

    // Latest drag factor measured during the most recent recovery coast-down.
    // Stored in the 100-150 display scale (df × 1 000 000).  0 = not yet measured.
    var latestMeasuredDf: Double = 0.0
        private set

    // Deceleration samples collected during the current recovery phase.
    // Each sample = (1/ω_n − 1/ω_{n-3}) / dtRev = k/J, the reciprocal of the
    // drag-to-inertia ratio.  Scaled by J × 1e6 on finalisation → drag factor.
    private val recoveryDfSamples = mutableListOf<Double>()

    // ── Omega tracking ────────────────────────────────────────────────────
    private var smoothedOmega: Double = 0.0   // EMA, for power/pace/distance
    private val emaAlpha               = 0.20  // smoothing strength (lower = smoother)

    // Per-magnet alpha: compare each pulse to the same magnet 1 revolution ago.
    // This eliminates zigzag caused by unequal physical magnet spacing (even a
    // few degrees of offset creates systematic interval alternation that looks
    // like large spurious acceleration when taking a pulse-to-pulse derivative).
    private val prevOmegaPerMagnet     = DoubleArray(3) { 0.0 }
    private val prevTimestampPerMagnet = LongArray(3)   { 0L  }
    private var pulseCount             = 0               // tracks which magnet slot

    // ── Stroke detection ──────────────────────────────────────────────────
    private enum class Phase { DRIVE, RECOVERY, UNKNOWN }
    private var phase: Phase = Phase.UNKNOWN

    // Minimum raw angular acceleration to switch drive/recovery phases (rad/s²).
    // At 1000–1300 RPM the peak drive alpha is ~56 rad/s²; threshold at 5 gives
    // good noise immunity while still triggering well before the peak.
    private val ACCEL_THRESHOLD = 5.0


    // Sanity clamp: ignore pulses that imply impossible flywheel speeds.
    // ~40 RPM → interval ~500 ms = 500 000 µs.  10 000 RPM → ~2 000 µs.
    // Upper bound is generous to capture slow deceleration and maximise distance accuracy.
    private val MIN_INTERVAL_US = 2_000L
    private val MAX_INTERVAL_US = 500_000L

    // ── Force curve buffer (current stroke drive phase) ───────────────────
    // Stores (timeWithinDriveSec, smoothedAlpha ≥ 0) for each drive pulse.
    private val forceCurveBuffer   = mutableListOf<Pair<Float, Float>>()
    private var drivePulseTimeSec  = 0.0                // accumulated dt during current drive
    private val driveDurations     = ArrayDeque<Double>(6) // last 5 completed drive durations
    private var currentDt          = 0.0                // set each pulse for use in handleStroke
    private var smoothedAlpha      = 0f                 // EMA of alpha within drive phase
    private val ALPHA_EMA          = 0.25f

    // Rolling max force across last 10 strokes, for stable y-normalisation
    private val strokeMaxForces    = ArrayDeque<Float>(11)
    private var rollingMaxForce    = 1f

    // ── Stroke accumulation (for per-stroke record) ────────────────────────
    private var strokeCount      = 0
    private var strokeStartSec   = 0.0
    private var strokeStartMeters = 0.0
    private var strokePaceAcc  = 0.0   // for averaging
    private var strokePowerAcc = 0.0
    private var strokeSamples  = 0
    private var lastCatchSec   = -1.0  // time of previous stroke catch, for SPM

    // Latched per-stroke display values (updated once per completed stroke)
    private var displayPaceSec500:   Double  = 999.0
    private var displayPowerWatts:   Double  = 0.0
    private var displayProjMeters:   Double? = null
    private var displayProjSeconds:  Double? = null
    private val strokeIntervals = ArrayDeque<Double>(8)  // last N full stroke durations
    private val strokeHistory   = mutableListOf<StrokeRecord>()

    // ── Totals ────────────────────────────────────────────────────────────
    private var totalSeconds:   Double = 0.0
    private var totalMeters:    Double = 0.0
    private var totalCalories:  Double = 0.0
    private var totalPowerAcc:  Double = 0.0
    private var totalPowerN:    Int    = 0

    // ── Current split ─────────────────────────────────────────────────────
    private var splitStartSec:      Double = 0.0
    private var splitStartMeters:   Double = 0.0
    private var splitMeters:      Double = 0.0
    private var splitElapsedSec:  Double = 0.0   // elapsed seconds in current split
    private var splitPowerAcc:    Double = 0.0
    private var splitPowerN:      Int    = 0
    private var splitHrAcc:       Int    = 0
    private var splitHrN:         Int    = 0
    private var splitStrokeCount: Int    = 0
    private val splitHistory      = mutableListOf<SplitSummary>()
    // Frozen window duration for the current split (only updated at split boundaries)
    private var splitWindowDurSec: Double = 0.0

    // ── HR ────────────────────────────────────────────────────────────────
    private var latestHr: Int? = null
    fun updateHeartRate(bpm: Int) {
        latestHr = bpm
        splitHrAcc += bpm
        splitHrN++
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    private var started = false
    fun start() { started = true }

    private var isFinished = false

    // Workout clock only runs after the first actual stroke begins
    private var clockRunning = false

    // Wall-clock time of the last processed pulse, used by tick() to advance
    // totalSeconds when the flywheel is silent (no pulses arriving).
    private var lastPulseWallMs: Long = 0L
    // Wall-clock time already added to totalSeconds by tick() since last pulse;
    // reset each pulse to avoid double-counting.
    private var tickedExtraSec: Double = 0.0

    // ── Main entry point ──────────────────────────────────────────────────

    private var pulseSeq: Int = 0

    /**
     * @param intervalUs   µs between this pulse and the previous (flywheel time).
     * @param timestampUs  Arduino micros() at capture; 0 means no timestamp available
     *                     (old firmware / FakeDataSource), fall back to interval-based alpha.
     */
    /**
     * Called once per second by the ViewModel ticker.
     * Advances totalSeconds and splitElapsedSec by wall-clock time when the flywheel
     * has been silent for > 1 s, so the UI clock and HR display keep updating at rest.
     * Does NOT add distance or power — the flywheel isn't spinning.
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): WorkoutState {
        if (!clockRunning || isFinished || lastPulseWallMs <= 0L) return buildState()
        val wallSincePulse = (nowMs - lastPulseWallMs) / 1000.0
        // Only count wall time that isn't already covered by recent pulses (>1 s silence)
        if (wallSincePulse < 1.0) return buildState()
        val toAdd = wallSincePulse - tickedExtraSec
        if (toAdd > 0.0) {
            totalSeconds    += toAdd
            splitElapsedSec += toAdd
            tickedExtraSec   = wallSincePulse
            checkFinished()
        }
        return buildState()
    }

    fun processPulse(intervalUs: Long, timestampUs: Long = 0L): WorkoutState {
        if (isFinished) return buildState()
        if (!started) start()

        // Discard corrupt / impossible pulses (garbled BLE packet, noise)
        if (intervalUs !in MIN_INTERVAL_US..MAX_INTERVAL_US) return buildState()

        // Record wall time so tick() knows when the flywheel last spoke
        lastPulseWallMs = System.currentTimeMillis()
        tickedExtraSec  = 0.0

        val dt      = intervalUs / 1_000_000.0              // seconds
        val omegaRaw = (2.0 * PI / 3.0) / dt               // rad/s from raw interval

        // Initialise EMA on first pulse
        if (smoothedOmega == 0.0) smoothedOmega = omegaRaw
        smoothedOmega = emaAlpha * omegaRaw + (1.0 - emaAlpha) * smoothedOmega

        // Per-magnet alpha: compare this pulse to the previous pulse from the SAME
        // magnet (3 slots ago).  Because both pulses span the same physical arc,
        // unequal magnet spacing cancels out.  The resulting α is also naturally
        // averaged over one full revolution, giving a much smoother signal than a
        // consecutive-pulse derivative without sacrificing real drive/recovery shape.
        val magnetIdx = pulseCount % 3
        val prevOmegaSlot = prevOmegaPerMagnet[magnetIdx]
        val prevTsSlot    = prevTimestampPerMagnet[magnetIdx]
        val alpha: Double = when {
            prevOmegaSlot <= 0.0 -> 0.0   // not yet seen this magnet slot (first revolution)
            timestampUs > 0L && prevTsSlot > 0L -> {
                val dtRev = (timestampUs - prevTsSlot) / 1_000_000.0
                if (dtRev > 0.0) (omegaRaw - prevOmegaSlot) / dtRev else 0.0
            }
            else -> (omegaRaw - prevOmegaSlot) / (dt * 3.0)  // fallback: ~one revolution dt
        }
        // Auto-measure drag factor: collect deceleration samples during recovery coast-down.
        // Uses the same per-magnet same-slot comparison as alpha, so unequal magnet spacing
        // cancels out.  Samples are finalised into a new dragFactor at the next drive catch.
        if (phase == Phase.RECOVERY && alpha < -ACCEL_THRESHOLD
                && prevOmegaSlot > 0.0 && omegaRaw > 0.0) {
            val dtRevDf = when {
                timestampUs > 0L && prevTsSlot > 0L ->
                    (timestampUs - prevTsSlot) / 1_000_000.0
                else -> dt * 3.0
            }
            if (dtRevDf > 0.0) {
                val sample = (1.0 / omegaRaw - 1.0 / prevOmegaSlot) / dtRevDf
                if (sample > 0.0) recoveryDfSamples.add(sample)
            }
        }

        prevOmegaPerMagnet[magnetIdx]     = omegaRaw
        prevTimestampPerMagnet[magnetIdx] = timestampUs
        pulseCount++
        currentDt = dt

        logger?.log(++pulseSeq, intervalUs, timestampUs, omegaRaw, alpha,
            when (phase) { Phase.DRIVE -> 'D'; Phase.RECOVERY -> 'R'; else -> 'U' })

        // Only accumulate clocks & distance after first stroke starts
        val velocity = distFactor * smoothedOmega
        if (clockRunning) {
            totalSeconds += dt
            val dm        = velocity * dt
            totalMeters  += dm
            splitMeters  += dm
            splitElapsedSec += dt

            // Power & energy
            val power = dragFactor * smoothedOmega.pow(3)
            totalPowerAcc += power;  totalPowerN++
            splitPowerAcc += power;  splitPowerN++
            // Metabolic rate (kcal/h) ≈ mechanical_watts × 3.6 (25 % efficiency) + 300 kcal/h resting.
            // Divide by 3600 to convert kcal/h → kcal/s, then multiply by dt.
            totalCalories += (power * 3.6 + 300.0) / 3600.0 * dt
        }

        // Pace for per-stroke averaging
        val power = dragFactor * smoothedOmega.pow(3)
        val instantPace = if (velocity > 0.001) 500.0 / velocity else 999.0

        // Stroke detection + force curve
        handleStroke(alpha, power, instantPace)

        // Split management
        manageSplits(velocity)

        // Termination
        checkFinished()

        return buildState()
    }

    // ── Stroke FSM ────────────────────────────────────────────────────────

    private fun handleStroke(alpha: Double, power: Double, instantPace: Double) {
        val newPhase = when {
            alpha  >  ACCEL_THRESHOLD -> Phase.DRIVE
            alpha  < -ACCEL_THRESHOLD -> Phase.RECOVERY
            else                       -> phase
        }

        when {
            // Recovery → Drive : new stroke begins (catch)
            newPhase == Phase.DRIVE && phase != Phase.DRIVE -> {
                // Finalise drag factor from the preceding recovery coast-down.
                // Require at least 5 samples for a stable median; fewer samples
                // (e.g. at the very first stroke) leave dragFactor at its seed value.
                if (recoveryDfSamples.size >= 5) {
                    val sorted = recoveryDfSamples.sorted()
                    val median = if (sorted.size % 2 == 0)
                        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
                    else sorted[sorted.size / 2]
                    val newDfScale = (median * flywheelInertia * 1e6).coerceIn(5.0, 500.0)
                    dragFactor       = newDfScale / 1_000_000.0
                    latestMeasuredDf = newDfScale
                }
                recoveryDfSamples.clear()

                // Record full stroke duration (catch-to-catch) for SPM
                val catchSec = totalSeconds
                if (lastCatchSec > 0) {
                    val dur = catchSec - lastCatchSec
                    if (dur in 1.0..5.0) {
                        strokeIntervals.addLast(dur)
                        if (strokeIntervals.size > 8) strokeIntervals.removeFirst()
                    }
                }
                lastCatchSec = catchSec

                // Flush previous stroke record
                if (strokeSamples > 0 && strokeCount > 0) recordStroke()

                strokeCount++
                splitStrokeCount++
                strokeStartSec    = totalSeconds
                strokeStartMeters = totalMeters
                strokePaceAcc     = 0.0
                strokePowerAcc    = 0.0
                strokeSamples     = 0
                drivePulseTimeSec = 0.0
                forceCurveBuffer.clear()
                smoothedAlpha     = 0f

                // Start workout clock on first stroke
                clockRunning = true
            }

            // Drive → Recovery : record drive duration for force curve window; reset df accumulator
            newPhase == Phase.RECOVERY && phase == Phase.DRIVE -> {
                recoveryDfSamples.clear()  // start fresh for this recovery phase
                if (drivePulseTimeSec in 0.2..3.0) {
                    driveDurations.addLast(drivePulseTimeSec)
                    if (driveDurations.size > 5) driveDurations.removeFirst()
                }
                // Update rolling max force for stable y-normalisation
                val strokeMax = forceCurveBuffer.maxOfOrNull { it.second } ?: 0f
                if (strokeMax > 0f) {
                    strokeMaxForces.addLast(strokeMax)
                    if (strokeMaxForces.size > 10) strokeMaxForces.removeFirst()
                    rollingMaxForce = strokeMaxForces.max()
                }
            }
        }

        // Accumulate drive-phase samples
        if (newPhase == Phase.DRIVE) {
            drivePulseTimeSec += currentDt
            strokePaceAcc     += instantPace
            strokePowerAcc    += power
            strokeSamples++

            val rawAlpha  = alpha.toFloat().coerceAtLeast(0f)
            smoothedAlpha = ALPHA_EMA * rawAlpha + (1f - ALPHA_EMA) * smoothedAlpha
            forceCurveBuffer.add(Pair(drivePulseTimeSec.toFloat(), smoothedAlpha))
        }

        phase = newPhase
    }

    private fun recordStroke() {
        val avgPace  = strokePaceAcc  / strokeSamples
        val avgPower = strokePowerAcc / strokeSamples
        // Latch for display — held until the next stroke completes
        displayPaceSec500  = avgPace
        displayPowerWatts  = avgPower
        val vel = distFactor * smoothedOmega
        displayProjMeters  = if (config.mode == WorkoutMode.TIME && vel > 0.001)
            totalMeters + vel * (config.targetSeconds - totalSeconds) else null
        displayProjSeconds = if (config.mode == WorkoutMode.DISTANCE && vel > 0.001)
            totalSeconds + (config.targetDistance - totalMeters) / vel else null
        strokeHistory.add(
            StrokeRecord(
                workoutTimeSec = totalSeconds,
                startTimeSec   = strokeStartSec,
                paceSec500     = avgPace,
                powerWatts     = avgPower,
                heartRateBpm   = latestHr,
                splitNumber    = splitHistory.size + 1,
                startMeters    = strokeStartMeters,
                endMeters      = totalMeters
            )
        )
    }

    // ── Split management ─────────────────────────────────────────────────

    private fun manageSplits(velocity: Double) {
        val splitReached = when (config.mode) {
            WorkoutMode.TIME -> splitElapsedSec >= config.splitSeconds
            else             -> splitMeters >= config.splitMeters
        }
        if (!splitReached) return

        val dur   = totalSeconds - splitStartSec
        val dist  = splitMeters
        val pace  = if (dist > 0 && dur > 0) 500.0 / (dist / dur) else 0.0
        val power = if (splitPowerN > 0) splitPowerAcc / splitPowerN else 0.0
        val rate  = if (dur > 0) splitStrokeCount / (dur / 60.0) else 0.0
        val avgHr = if (splitHrN > 0) splitHrAcc / splitHrN else null

        splitHistory.add(SplitSummary(
            splitNumber    = splitHistory.size + 1,
            elapsedSeconds = dur,
            distanceMeters = dist,
            avgPaceSec500  = pace,
            avgPowerWatts  = power,
            avgStrokeRate  = rate,
            avgHeartRate   = avgHr
        ))

        splitMeters       = 0.0
        splitElapsedSec   = 0.0
        splitPowerAcc     = 0.0
        splitPowerN       = 0
        splitHrAcc        = 0
        splitHrN          = 0
        splitStrokeCount  = 0
        splitStartSec     = totalSeconds
        splitStartMeters  = totalMeters

        // Freeze split window for the new split (no jitter mid-split)
        splitWindowDurSec = when (config.mode) {
            WorkoutMode.TIME -> config.splitSeconds.toDouble()
            else -> if (velocity > 0.001) config.splitMeters / velocity
                    else config.windowSeconds.toDouble()
        }
    }

    // ── Termination ───────────────────────────────────────────────────────

    private fun checkFinished() {
        isFinished = when (config.mode) {
            WorkoutMode.DISTANCE -> totalMeters  >= config.targetDistance
            WorkoutMode.TIME     -> totalSeconds >= config.targetSeconds
            else                 -> false
        }
    }

    // ── State builder ─────────────────────────────────────────────────────

    private fun buildState(): WorkoutState {
        val velocity    = distFactor * smoothedOmega
        // Pace and power are latched per stroke — no mid-stroke fluctuation
        val curPace     = displayPaceSec500
        val avgPace     = if (totalMeters > 0 && totalSeconds > 0)
            500.0 / (totalMeters / totalSeconds) else 999.0
        val avgPower    = if (totalPowerN > 0) totalPowerAcc / totalPowerN else 0.0
        val strokeRate  = if (strokeIntervals.isNotEmpty()) 60.0 / strokeIntervals.average() else 0.0

        // Force curve: fixed window from last-5 avg drive duration × 1.3
        val avgDriveDur    = if (driveDurations.isNotEmpty()) driveDurations.average() else 1.0
        val curveWindowSec = (avgDriveDur * 1.3).toFloat().coerceAtLeast(0.5f)
        val normSamples = if (rollingMaxForce > 0f)
            forceCurveBuffer.map { Pair(it.first, it.second / rollingMaxForce) }
        else forceCurveBuffer.toList()

        // Chart window
        val (chartStart, chartEnd) = computeChartWindow(velocity)
        val (chartStartM, chartEndM) = computeChartWindowMeters()

        // Projections are latched per stroke
        val projMeters  = displayProjMeters
        val projSeconds = displayProjSeconds

        return WorkoutState(
            elapsedSeconds        = totalSeconds,
            remainingSeconds      = if (config.mode == WorkoutMode.TIME)
                max(0.0, config.targetSeconds - totalSeconds) else null,
            elapsedMeters         = totalMeters,
            remainingMeters       = if (config.mode == WorkoutMode.DISTANCE)
                max(0.0, config.targetDistance - totalMeters) else null,
            strokeRate            = strokeRate,
            strokeCount           = strokeCount,
            currentPaceSec500     = curPace,
            avgPaceSec500         = avgPace,
            currentPowerWatts     = displayPowerWatts,
            avgPowerWatts         = avgPower,
            totalCaloriesKcal     = totalCalories,
            heartRateBpm          = latestHr,
            currentSplitNumber    = splitHistory.size + 1,
            splitElapsedSeconds   = splitElapsedSec,
            splitElapsedMeters    = splitMeters,
            splitHistory          = splitHistory.toList(),
            strokeHistory         = strokeHistory.toList(),
            chartWindowStartSec    = chartStart,
            chartWindowEndSec      = chartEnd,
            chartWindowStartMeters = chartStartM,
            chartWindowEndMeters   = chartEndM,
            projectedTotalMeters  = projMeters,
            projectedTotalSeconds = projSeconds,
            forceCurveSamples     = normSamples,
            forceCurveWindowSec   = curveWindowSec,
            isFinished            = isFinished,
            isConnected           = true
        )
    }

    private fun computeChartWindowMeters(): Pair<Double?, Double?> {
        return when (config.mode) {
            WorkoutMode.DISTANCE -> splitStartMeters to (splitStartMeters + config.splitMeters)
            else -> null to null
        }
    }

    private fun computeChartWindow(velocity: Double): Pair<Double, Double> {
        return when (config.mode) {
            WorkoutMode.FREE, WorkoutMode.TARGET -> {
                // Sliding window anchored to present
                val end   = totalSeconds
                val start = max(0.0, end - config.windowSeconds)
                start to end
            }
            WorkoutMode.DISTANCE, WorkoutMode.TIME -> {
                // Use frozen split window (no jitter); fall back to estimate on first split
                val winDur = if (splitWindowDurSec > 0) splitWindowDurSec else {
                    when (config.mode) {
                        WorkoutMode.TIME -> config.splitSeconds.toDouble()
                        else -> if (velocity > 0.001) config.splitMeters / velocity
                                else config.windowSeconds.toDouble()
                    }
                }
                splitStartSec to (splitStartSec + winDur)
            }
        }
    }
}
