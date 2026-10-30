package com.smartapes.openrower.audio

import android.media.MediaPlayer
import com.smartapes.openrower.model.SegmentType
import com.smartapes.openrower.model.WorkoutSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages music playback for a custom workout session.
 *
 * - Recovery segments: single looping song paused/resumed across all recovery intervals.
 * - Workout segments: song played from [musicStartMs] to [musicEndMs] (or natural end).
 * - Preview: hops through segments and plays 5 s of each assigned song.
 */
class WorkoutMusicPlayer {

    private var activePlayer: MediaPlayer? = null
    private var recoveryPositionMs: Int = 0
    private var previewJob: Job? = null

    // ── Segment playback ─────────────────────────────────────────────────

    fun playSegment(seg: WorkoutSegment) {
        val path = seg.musicSongPath ?: return
        releaseActive()
        try {
            activePlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                seekTo(seg.musicStartMs.toInt())
                if (seg.type == SegmentType.RECOVERY) {
                    isLooping = false
                    setOnCompletionListener { seekTo(0); start() }
                }
                start()
            }
        } catch (_: Exception) {}
    }

    /** Pause recovery music and remember position so it can be resumed later. */
    fun pauseRecovery() {
        try {
            recoveryPositionMs = activePlayer?.currentPosition ?: 0
            activePlayer?.pause()
        } catch (_: Exception) {}
    }

    /** Resume recovery music from where it was paused. */
    fun resumeRecovery(path: String) {
        releaseActive()
        try {
            activePlayer = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                seekTo(recoveryPositionMs)
                isLooping = false
                setOnCompletionListener { seekTo(0); start() }
                start()
            }
        } catch (_: Exception) {}
    }

    fun stop() {
        previewJob?.cancel()
        releaseActive()
    }

    // ── Preview ──────────────────────────────────────────────────────────

    /**
     * Plays 5 seconds of each segment's assigned song in sequence.
     * [onSegment] is called with the segment index when each song starts.
     * [onDone] is called when the preview finishes.
     */
    fun preview(
        segments: List<WorkoutSegment>,
        scope: CoroutineScope,
        onSegment: (Int) -> Unit,
        onDone: () -> Unit
    ) {
        previewJob?.cancel()
        previewJob = scope.launch(Dispatchers.Main) {
            for ((i, seg) in segments.withIndex()) {
                val path = seg.musicSongPath ?: continue
                onSegment(i)
                releaseActive()
                try {
                    activePlayer = MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                        seekTo(seg.musicStartMs.toInt())
                        start()
                    }
                } catch (_: Exception) {}
                delay(5_000L)
            }
            releaseActive()
            onDone()
        }
    }

    fun release() {
        previewJob?.cancel()
        releaseActive()
    }

    private fun releaseActive() {
        try {
            activePlayer?.stop()
            activePlayer?.release()
        } catch (_: Exception) {}
        activePlayer = null
    }
}
