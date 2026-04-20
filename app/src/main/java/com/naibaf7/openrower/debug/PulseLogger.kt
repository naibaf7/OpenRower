package com.naibaf7.openrower.debug

import android.content.Context
import android.os.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs raw BLE pulse data to a CSV file in the app's external files directory.
 *
 * File location (readable without root):
 *   /sdcard/Android/data/com.naibaf7.openrower/files/logs/pulses_<timestamp>.csv
 *
 * Pull with:
 *   adb pull /sdcard/Android/data/com.naibaf7.openrower/files/logs/
 *
 * Or browse via a file manager app on the device.
 *
 * CSV columns:
 *   seq          – pulse sequence number
 *   wall_ms      – System.currentTimeMillis() when pulse arrived on Android
 *   interval_us  – flywheel interval from Arduino (hardware-measured)
 *   timestamp_us – Arduino micros() at capture (0 if not provided)
 *   omega_raw    – computed ω = (2π/3) / interval_us  (rad/s)
 *   alpha        – (ω_n − ω_{n-1}) / dt_hw  (rad/s²); 0 on first pulse
 *   phase        – D=drive, R=recovery, U=unknown (after engine sees it)
 */
class PulseLogger(context: Context) {

    private val writer: BufferedWriter?
    val filePath: String

    init {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "pulses_$ts.csv")
        filePath = file.absolutePath
        writer = try {
            BufferedWriter(FileWriter(file))
                .also { it.write("seq,wall_ms,interval_us,timestamp_us,omega_raw,alpha,phase\n") }
        } catch (e: Exception) { null }
    }

    fun log(
        seq: Int,
        intervalUs: Long,
        timestampUs: Long,
        omegaRaw: Double,
        alpha: Double,
        phase: Char
    ) {
        writer?.apply {
            write("$seq,${System.currentTimeMillis()},$intervalUs,$timestampUs,")
            write("%.4f,%.4f,$phase\n".format(omegaRaw, alpha))
            // flush periodically so data survives a crash
            if (seq % 50 == 0) flush()
        }
    }

    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
    }
}
