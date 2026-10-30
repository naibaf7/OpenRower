package com.smartapes.openrower.audio

import java.io.File

/** Parses M3U / M3U8 playlists, resolving paths relative to the playlist file or a media folder. */
object M3uParser {

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "ogg", "m4a", "wav", "aac")

    fun parse(playlistPath: String, mediaFolder: String? = null): List<String> {
        val file = File(playlistPath)
        if (!file.exists()) return emptyList()
        val baseDir = file.parentFile?.absolutePath ?: "/"
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { resolvePath(it, baseDir, mediaFolder) }
    }

    private fun resolvePath(entry: String, baseDir: String, mediaFolder: String?): String? {
        if (entry.startsWith("/")) return File(entry).takeIf { it.exists() }?.absolutePath

        val relPlaylist = File(baseDir, entry)
        if (relPlaylist.exists()) return relPlaylist.absolutePath

        if (mediaFolder != null) {
            val relMedia = File(mediaFolder, entry)
            if (relMedia.exists()) return relMedia.absolutePath
        }

        // Fallback: closest filename match
        return findClosest(entry.substringAfterLast('/'), baseDir, mediaFolder)
    }

    private fun findClosest(filename: String, vararg dirs: String?): String? {
        val nameStem = filename.substringBeforeLast('.').lowercase()
        for (dir in dirs) {
            if (dir == null) continue
            val folder = File(dir)
            if (!folder.isDirectory) continue
            val candidates = folder.listFiles { f -> f.extension.lowercase() in AUDIO_EXTENSIONS }
                ?: continue
            // Exact name
            candidates.find { it.name == filename }?.let { return it.absolutePath }
            // Case-insensitive exact
            candidates.find { it.name.lowercase() == filename.lowercase() }?.let { return it.absolutePath }
            // Levenshtein closest if distance < 1/3 of name length
            val best = candidates.minByOrNull { levenshtein(it.nameWithoutExtension.lowercase(), nameStem) }
            if (best != null) {
                val d = levenshtein(best.nameWithoutExtension.lowercase(), nameStem)
                if (d <= (nameStem.length / 3).coerceAtLeast(1)) return best.absolutePath
            }
        }
        return null
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
        }
        return dp[a.length][b.length]
    }
}
