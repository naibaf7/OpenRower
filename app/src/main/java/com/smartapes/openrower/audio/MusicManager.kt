package com.smartapes.openrower.audio

import com.smartapes.openrower.model.CustomWorkout
import com.smartapes.openrower.model.SegmentType
import com.smartapes.openrower.model.WorkoutSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Loads and analyses a music library, then matches and assigns songs to
 * custom workout segments.
 *
 * Analysis results are cached in memory for the lifetime of the manager.
 */
class MusicManager {

    private val analyzer  = AudioAnalyzer()
    private val cache     = mutableMapOf<String, SongInfo>()

    // ── Library loading ───────────────────────────────────────────────────

    /** Scans a folder or M3U playlist and returns fully-analysed [SongInfo] list. */
    suspend fun loadLibrary(
        mediaFolder: String?,
        playlistPath: String?
    ): List<SongInfo> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        if (playlistPath != null && playlistPath.isNotBlank()) {
            paths += M3uParser.parse(playlistPath, mediaFolder)
        } else if (mediaFolder != null && mediaFolder.isNotBlank()) {
            File(mediaFolder).takeIf { it.isDirectory }
                ?.walkTopDown()
                ?.filter { it.extension.lowercase() in setOf("mp3", "flac", "ogg", "m4a", "aac") }
                ?.forEach { paths += it.absolutePath }
        }
        paths.mapNotNull { analyzeOrCached(it) }
    }

    private suspend fun analyzeOrCached(path: String): SongInfo? {
        cache[path]?.let { return it }
        return try {
            val base       = analyzer.getMetadata(path)
            val (bpm, nrg) = analyzer.analyze(path)
            val info       = base.copy(bpm = bpm, energyLevel = nrg)
            cache[path]    = info
            info
        } catch (_: Exception) { null }
    }

    // ── Song suggestion ───────────────────────────────────────────────────

    fun suggestRecovery(songs: List<SongInfo>): SongInfo? =
        songs.minByOrNull { it.energyLevel }

    /**
     * Returns the song whose BPM is closest to [targetBpm] from the energetic half of the library.
     * If [targetBpm] is 0 (unknown), returns the highest-energy song instead.
     */
    fun suggestWorkout(songs: List<SongInfo>, targetBpm: Double): SongInfo? {
        if (songs.isEmpty()) return null
        return if (targetBpm > 0) {
            val energetic = songs.filter { it.energyLevel >= songs.map { s -> s.energyLevel }.average() }
                .takeIf { it.isNotEmpty() } ?: songs
            energetic.minByOrNull { abs(it.bpm - targetBpm) }
        } else {
            songs.maxByOrNull { it.energyLevel }
        }
    }

    /**
     * Auto-assigns music to every segment in [workout] that has no music yet.
     * Returns a new [CustomWorkout] with assignments filled in.
     */
    suspend fun autoAssign(workout: CustomWorkout, songs: List<SongInfo>): CustomWorkout =
        withContext(Dispatchers.Default) {
            if (songs.isEmpty()) return@withContext workout

            val recoverySong = if (workout.recoveryMusicPath != null)
                songs.find { it.path == workout.recoveryMusicPath } ?: suggestRecovery(songs)
            else
                suggestRecovery(songs)

            val newSegments = workout.segments.map { seg ->
                if (seg.musicSongPath != null) return@map seg  // already assigned
                when (seg.type) {
                    SegmentType.RECOVERY -> seg.copy(musicSongPath = recoverySong?.path)
                    SegmentType.WORKOUT  -> {
                        val avgBpm = seg.targetPaceSteps
                            .map { it.strokesPerMin * 2.0 }  // ~stroke cadence → music BPM
                            .average().let { if (it.isNaN()) 0.0 else it }
                        val song   = suggestWorkout(songs, avgBpm) ?: return@map seg
                        val endMs  = if (seg.durationSec != null && seg.durationSec > 0)
                            (seg.durationSec * 1000).toLong().coerceAtMost(song.durationMs)
                        else song.durationMs
                        seg.copy(musicSongPath = song.path, musicStartMs = 0L, musicEndMs = endMs)
                    }
                }
            }
            workout.copy(
                segments          = newSegments,
                recoveryMusicPath = recoverySong?.path ?: workout.recoveryMusicPath
            )
        }

    /**
     * Creates a workout segment whose length is determined by the given song.
     * The segment gets a target pace derived from the song's BPM.
     */
    fun createSegmentFromSong(song: SongInfo): WorkoutSegment {
        val durationSec = song.durationMs / 1000.0
        val spm = (song.bpm / 2.0).coerceIn(14.0, 36.0)  // stroke rate ~ half music BPM
        return WorkoutSegment(
            type             = SegmentType.WORKOUT,
            durationSec      = durationSec,
            targetPaceSteps  = if (spm > 0) listOf(
                com.smartapes.openrower.model.TargetPaceStep(durationSec, spm)
            ) else emptyList(),
            musicSongPath    = song.path,
            musicStartMs     = 0L,
            musicEndMs       = song.durationMs
        )
    }
}
