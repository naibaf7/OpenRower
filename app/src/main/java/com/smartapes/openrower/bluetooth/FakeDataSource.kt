package com.smartapes.openrower.bluetooth

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Generates realistic synthetic hall-sensor pulse data for test mode.
 *
 * ── Stroke model ──────────────────────────────────────────────────────────
 * Drive phase (35 % of stroke period):
 *   ω(t) = ω_start + Δω · (1 − cos(π·t/T_drive)) / 2
 *   This means ω rises smoothly from ω_start to ω_peak (cosine ramp).
 *   The angular acceleration α = dω/dt = Δω·π/(2·T_drive)·sin(π·t/T_drive)
 *   is a pure sine arch — the classic bell-shaped force curve.
 *
 * Recovery phase (65 % of stroke period):
 *   ω(t) = ω_end + (ω_peak − ω_end) · (1 + cos(π·t/T_rec)) / 2
 *   Smooth cosine decay from peak back to the base RPM.
 *
 * ── Pulse timing ──────────────────────────────────────────────────────────
 * At flywheel speed ω (rad/s), 3 magnets per revolution → each pulse
 * corresponds to 2π/3 rad of rotation.  Interval between pulses:
 *   T_pulse = (2π/3) / ω  seconds
 *
 * We emit one [RowingDataParser.Message.Pulse] with intervalUs = T_pulse × 1 000 000,
 * then delay by exactly T_pulse so the timing matches real hardware.
 */
object FakeDataSource {

    // ── Workout parameters ────────────────────────────────────────────────
    private const val STROKE_RATE_SPM = 24.0                               // strokes / min
    private const val STROKE_PERIOD_MS = 60_000.0 / STROKE_RATE_SPM       // 2500 ms
    private const val DRIVE_FRACTION   = 0.35
    private const val BASE_RPM         = 1000.0  // flywheel RPM at start / end of stroke
    private const val PEAK_RPM         = 1300.0  // flywheel RPM at end of drive
    private const val MAGNETS          = 3

    fun rowingMessages(): Flow<RowingDataParser.Message> = flow {
        val rng = Random(42)
        var fakeTimestampUs = 0L
        // Absolute wall-clock scheduler: tracks when the next pulse should fire,
        // preventing drift from toLong() truncation accumulating over many pulses.
        var nextEmitWallMs = System.currentTimeMillis().toDouble()
        while (true) {
            // Slight natural variation in stroke rate and power
            val periodMs   = STROKE_PERIOD_MS * (1.0 + rng.nextDouble(-0.04, 0.04))
            val driveMs    = periodMs * DRIVE_FRACTION
            val recoveryMs = periodMs - driveMs
            val startRpm   = BASE_RPM  + rng.nextDouble(-20.0,  20.0)
            val peakRpm    = PEAK_RPM  + rng.nextDouble(-50.0,  70.0)

            // Drive: ω rises from startRpm → peakRpm  (force curve is bell-shaped)
            val (ts1, next1) = emitPhase(driveMs, startRpm, peakRpm,
                isDrive = true, startTimestampUs = fakeTimestampUs,
                nextEmitWallMs = nextEmitWallMs) { emit(it) }
            fakeTimestampUs = ts1; nextEmitWallMs = next1

            // Recovery: ω decays from peakRpm → startRpm
            val (ts2, next2) = emitPhase(recoveryMs, peakRpm, startRpm,
                isDrive = false, startTimestampUs = fakeTimestampUs,
                nextEmitWallMs = nextEmitWallMs) { emit(it) }
            fakeTimestampUs = ts2; nextEmitWallMs = next2
        }
    }

    /**
     * Simulate heart rate starting at 130 bpm, slowly climbing to ~158 bpm.
     */
    fun heartRateMessages(): Flow<Int> = flow {
        var bpm = 130
        while (true) {
            emit(bpm)
            delay(1000L)
            if (bpm < 158) bpm++
        }
    }

    // ── Phase emitter ─────────────────────────────────────────────────────

    // Returns (final fake timestamp, next scheduled wall-clock emission time) so
    // the caller can chain phases without resetting the absolute-time scheduler.
    private suspend fun emitPhase(
        durationMs: Double,
        rpmFrom: Double,
        rpmTo: Double,
        isDrive: Boolean,
        startTimestampUs: Long,
        nextEmitWallMs: Double,
        emit: suspend (RowingDataParser.Message.Pulse) -> Unit
    ): Pair<Long, Double> {
        var elapsedMs      = 0.0
        var timestampUs    = startTimestampUs
        var scheduleWallMs = nextEmitWallMs   // fractional ms, never truncated

        while (elapsedMs < durationMs) {
            val progress = (elapsedMs / durationMs).coerceIn(0.0, 1.0)
            val rpm = if (isDrive) {
                rpmFrom + (rpmTo - rpmFrom) * (1.0 - cos(PI * progress)) / 2.0
            } else {
                rpmTo + (rpmFrom - rpmTo) * (1.0 + cos(PI * progress)) / 2.0
            }

            val intervalMs = 60_000.0 / (rpm.coerceAtLeast(10.0) * MAGNETS)
            val intervalUs = (intervalMs * 1_000.0).toLong().coerceAtLeast(1_000L)
            timestampUs   += intervalUs

            // Sleep until the scheduled wall-clock time; skip sleep if already late.
            val sleepMs = scheduleWallMs.toLong() - System.currentTimeMillis()
            if (sleepMs > 0) delay(sleepMs)

            emit(RowingDataParser.Message.Pulse(intervalUs, timestampUs))
            elapsedMs      += intervalMs
            scheduleWallMs += intervalMs   // advance by exact fractional ms — no drift
        }
        return Pair(timestampUs, scheduleWallMs)
    }
}
