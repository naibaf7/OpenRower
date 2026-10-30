package com.smartapes.openrower.audio

import java.io.Serializable

data class SongInfo(
    val path: String,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0L,
    val bpm: Double = 0.0,
    /** Normalised RMS energy 0.0 (quiet) – 1.0 (loud). */
    val energyLevel: Double = 0.0,
    val playlistPath: String? = null
) : Serializable {
    val displayName: String get() = when {
        title.isNotEmpty() && artist.isNotEmpty() -> "$artist – $title"
        title.isNotEmpty() -> title
        else -> path.substringAfterLast('/').substringBeforeLast('.')
    }
}
