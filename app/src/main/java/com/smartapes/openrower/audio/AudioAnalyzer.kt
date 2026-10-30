package com.smartapes.openrower.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decodes audio files to PCM and computes BPM (via onset autocorrelation)
 * and energy level (normalised RMS).
 *
 * Analysis samples only the first ~20 seconds of each file to stay fast.
 */
class AudioAnalyzer {

    /** @return Pair(bpm, energyLevel 0..1) */
    suspend fun analyze(path: String): Pair<Double, Double> = withContext(Dispatchers.IO) {
        try {
            val (pcm, sampleRate) = decodePcmMono(path, maxSamples = 44100 * 20)
            if (pcm.isEmpty()) return@withContext 0.0 to 0.0
            val energy = computeEnergy(pcm)
            val bpm    = detectBpm(pcm, sampleRate)
            bpm to energy
        } catch (_: Exception) { 0.0 to 0.0 }
    }

    /** Reads metadata only (no audio decode). */
    suspend fun getMetadata(path: String): SongInfo = withContext(Dispatchers.IO) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            SongInfo(
                path      = path,
                title     = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)  ?: "",
                artist    = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "",
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                 ?.toLongOrNull() ?: 0L
            )
        } catch (_: Exception) { SongInfo(path = path) }
        finally { mmr.release() }
    }

    // ── PCM decode ───────────────────────────────────────────────────────

    /** Returns decoded mono PCM shorts and the effective sample rate. */
    private fun decodePcmMono(path: String, maxSamples: Int): Pair<ShortArray, Int> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val samples   = ArrayList<Short>(maxSamples)
        var sampleRate = 44100
        try {
            extractor.setDataSource(path)
            val trackIdx = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return ShortArray(0) to sampleRate

            extractor.selectTrack(trackIdx)
            val format   = extractor.getTrackFormat(trackIdx)
            val mime     = format.getString(MediaFormat.KEY_MIME) ?: return ShortArray(0) to sampleRate
            sampleRate   = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                               format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                               format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufInfo = MediaCodec.BufferInfo()
            var eos = false

            while (!eos && samples.size < maxSamples) {
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val sz = extractor.readSampleData(inBuf, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufInfo, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    val sb = outBuf.asShortBuffer()
                    val chunk = ShortArray(sb.remaining())
                    sb.get(chunk)
                    // Mix down to mono
                    var i = 0
                    while (i < chunk.size && samples.size < maxSamples) {
                        if (channels >= 2) {
                            val mono = ((chunk[i].toInt() + chunk[(i + 1).coerceAtMost(chunk.size - 1)].toInt()) / 2).toShort()
                            samples.add(mono)
                            i += channels
                        } else {
                            samples.add(chunk[i++])
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }
        } catch (_: Exception) {}
        finally {
            try { codec?.stop(); codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
        return samples.toShortArray() to sampleRate
    }

    // ── Energy ───────────────────────────────────────────────────────────

    private fun computeEnergy(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
        return (rms / 32768.0).coerceIn(0.0, 1.0)
    }

    // ── BPM detection via onset-flux autocorrelation ──────────────────────
    // Window-based RMS → positive flux (onset strength) → autocorrelation peak.

    private fun detectBpm(pcm: ShortArray, sampleRate: Int): Double {
        val winSize = sampleRate / 100   // 10 ms window
        val hopSize = winSize / 2        // 5 ms hop → 200 hop/sec

        // Build RMS envelope
        val envelope = ArrayList<Double>((pcm.size / hopSize) + 1)
        var pos = 0
        while (pos + winSize <= pcm.size) {
            val rms = sqrt(pcm.slice(pos until pos + winSize).sumOf { it.toDouble() * it } / winSize)
            envelope.add(rms)
            pos += hopSize
        }
        if (envelope.size < 20) return 0.0

        // Positive spectral flux (onset strength)
        val flux = DoubleArray(envelope.size) { i ->
            if (i == 0) 0.0 else maxOf(0.0, envelope[i] - envelope[i - 1])
        }

        val hopSec   = hopSize.toDouble() / sampleRate
        val minLag   = (60.0 / 200.0 / hopSec).toInt().coerceAtLeast(1)  // 200 BPM
        val maxLag   = (60.0 / 55.0  / hopSec).toInt()                   //  55 BPM
        if (minLag >= maxLag || maxLag >= flux.size) return 0.0

        var bestLag  = minLag
        var bestCorr = Double.MIN_VALUE
        for (lag in minLag..maxLag.coerceAtMost(flux.size / 2)) {
            var corr = 0.0
            for (i in 0 until flux.size - lag) corr += flux[i] * flux[i + lag]
            if (corr > bestCorr) { bestCorr = corr; bestLag = lag }
        }

        return (60.0 / (bestLag * hopSec)).coerceIn(55.0, 200.0)
    }
}
