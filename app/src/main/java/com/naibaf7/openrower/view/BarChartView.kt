package com.naibaf7.openrower.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.naibaf7.openrower.model.EnergyUnit
import com.naibaf7.openrower.model.StrokeRecord
import com.naibaf7.openrower.model.WorkoutMode

/**
 * Stroke-by-stroke bar chart with heart-rate overlay.
 *
 * ── Layout (vertical) ─────────────────────────────────────────────────────
 *   Upper 25 % of the plot area: HR bars (if available), coloured by 5-zone model.
 *   Lower 75 % of the plot area: power bars (one per stroke).
 *   X-axis: time (seconds), covering [windowStartSec, windowEndSec].
 *
 * ── HR zone colours ───────────────────────────────────────────────────────
 *   Zone 1  < 57 % maxHr  →  light blue
 *   Zone 2  57–63 %        →  cyan/teal
 *   Zone 3  63–73 %        →  green
 *   Zone 4  73–83 %        →  orange
 *   Zone 5  > 83 %         →  red
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Public data bindings ──────────────────────────────────────────────
    var strokes: List<StrokeRecord> = emptyList()
        set(value) { field = value; invalidate() }

    var windowStartSec: Double = 0.0
        set(value) { field = value; invalidate() }

    var windowEndSec: Double = 120.0
        set(value) { field = value; invalidate() }

    /** Non-null when DISTANCE mode: x-axis in meters. */
    var windowStartMeters: Double? = null
        set(value) { field = value; invalidate() }

    var windowEndMeters: Double? = null
        set(value) { field = value; invalidate() }

    var workoutMode: WorkoutMode = WorkoutMode.FREE
        set(value) { field = value; invalidate() }

    var maxHr: Int = 190
        set(value) { field = value; invalidate() }

    var energyUnit: EnergyUnit = EnergyUnit.WATTS
        set(value) { field = value; invalidate() }

    // ── Margins ───────────────────────────────────────────────────────────
    private val ML = 72f
    private val MR = 12f
    private val MT = 36f
    private val MB = 48f

    // ── HR zone colours ───────────────────────────────────────────────────
    private val zoneColors = intArrayOf(
        Color.parseColor("#80B0BEC5"),
        Color.parseColor("#8026C6DA"),
        Color.parseColor("#8066BB6A"),
        Color.parseColor("#80FFA726"),
        Color.parseColor("#80EF5350")
    )

    // ── Paints ────────────────────────────────────────────────────────────
    private val barPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hrPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5"); textSize = 26f; textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1"); textSize = 30f; textAlign = Paint.Align.CENTER
    }
    private val avgLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300"); strokeWidth = 2.5f; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 6f), 0f)
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#0D1B2A"))

        val w = width.toFloat()
        val h = height.toFloat()
        val plotL = ML; val plotR = w - MR
        val plotT = MT; val plotB = h - MB
        val plotW = plotR - plotL; val plotH = plotB - plotT

        val unitLabel = if (energyUnit == EnergyUnit.WATTS) "W" else "kcal/h"
        canvas.drawText("Power / HR  ($unitLabel)", w / 2f, MT - 4f, titlePaint)

        // Axes
        canvas.drawLine(plotL, plotT, plotL, plotB, axisPaint)
        canvas.drawLine(plotL, plotB, plotR, plotB, axisPaint)

        // Use meters for DISTANCE mode, seconds otherwise
        val useMeters = workoutMode == WorkoutMode.DISTANCE &&
                windowStartMeters != null && windowEndMeters != null
        val winStart  = if (useMeters) windowStartMeters!! else windowStartSec
        val winEnd    = if (useMeters) windowEndMeters!!   else windowEndSec
        val windowSec = (winEnd - winStart).coerceAtLeast(1.0)
        val visible = if (useMeters) strokes.filter {
            it.endMeters >= winStart && it.startMeters <= winEnd
        } else strokes.filter {
            it.workoutTimeSec >= windowStartSec && it.startTimeSec <= windowEndSec
        }

        if (visible.isEmpty()) {
            labelPaint.color = Color.parseColor("#546E7A")
            canvas.drawText("No strokes yet", w / 2f, h / 2f, labelPaint)
            labelPaint.color = Color.parseColor("#B0BEC5")
            drawXAxis(canvas, plotL, plotB, plotW, winStart, windowSec, useMeters)
            return
        }

        // HR strip: upper 25 % if HR data available
        val hasHr = visible.any { it.heartRateBpm != null }
        val hrH   = if (hasHr) plotH * 0.25f else 0f
        val hrB   = plotT + hrH
        val paceT = hrB + (if (hasHr) 4f else 0f)
        val paceB = plotB
        val paceH = paceB - paceT

        // Convert watts → display unit
        fun displayValue(w: Double) = if (energyUnit == EnergyUnit.WATTS) w
                                      else w * 3.6 + 300.0

        val displayValues = visible.map { displayValue(it.powerWatts) }
        val maxVal  = displayValues.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val avgVal  = displayValues.average()

        // Y-axis ticks (power)
        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.textSize  = 22f
        for (i in 0..3) {
            val v = maxVal * i / 3.0
            val y = paceB - (i.toFloat() / 3f) * paceH
            canvas.drawLine(plotL - 5f, y, plotL, y, axisPaint)
            canvas.drawText("%.0f".format(v), plotL - 8f, y + 8f, labelPaint)
        }
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize  = 26f

        // Power bars
        for ((stroke, dv) in visible.zip(displayValues)) {
            val startPos = if (useMeters) stroke.startMeters   else stroke.startTimeSec
            val endPos   = if (useMeters) stroke.endMeters     else stroke.workoutTimeSec
            val xStart = plotL + ((startPos - winStart) / windowSec * plotW).toFloat()
            val xEnd   = plotL + ((endPos   - winStart) / windowSec * plotW).toFloat()
            val barX1  = xStart.coerceIn(plotL, plotR)
            val barX2  = (xEnd - 1f).coerceIn(plotL, plotR)
            if (barX2 <= barX1) continue

            val frac   = (dv / maxVal).toFloat().coerceIn(0f, 1f)
            barPaint.color = Color.parseColor("#1565C0")
            canvas.drawRect(barX1, paceB - frac * paceH, barX2, paceB, barPaint)
        }

        // Average power line
        val avgFrac = (avgVal / maxVal).toFloat().coerceIn(0f, 1f)
        val avgY    = paceB - avgFrac * paceH
        canvas.drawLine(plotL, avgY, plotR, avgY, avgLinePaint)
        labelPaint.textAlign  = Paint.Align.RIGHT
        labelPaint.color      = Color.parseColor("#FFB300")
        canvas.drawText("avg %.0f".format(avgVal), plotR - 4f, avgY - 4f, labelPaint)
        labelPaint.color      = Color.parseColor("#B0BEC5")
        labelPaint.textAlign  = Paint.Align.CENTER

        // HR overlay
        if (hasHr) {
            val hrMin   = 50f
            val hrMax   = maxHr.toFloat()
            val hrRange = hrMax - hrMin

            axisPaint.color = Color.parseColor("#37474F")
            canvas.drawLine(plotL, hrB, plotR, hrB, axisPaint)
            axisPaint.color = Color.parseColor("#90A4AE")

            labelPaint.textAlign = Paint.Align.RIGHT
            labelPaint.textSize  = 22f
            canvas.drawText("HR", plotL - 4f, plotT + hrH / 2f + 8f, labelPaint)
            labelPaint.textSize  = 26f

            for (stroke in visible) {
                val bpm  = stroke.heartRateBpm ?: continue
                val startPos = if (useMeters) stroke.startMeters   else stroke.startTimeSec
                val endPos   = if (useMeters) stroke.endMeters     else stroke.workoutTimeSec
                val xStart = plotL + ((startPos - winStart) / windowSec * plotW).toFloat()
                val xEnd   = plotL + ((endPos   - winStart) / windowSec * plotW).toFloat()
                val barX1  = xStart.coerceIn(plotL, plotR)
                val barX2  = (xEnd - 1f).coerceIn(plotL, plotR)
                if (barX2 <= barX1) continue

                val hrFrac = ((bpm - hrMin) / hrRange).toFloat().coerceIn(0f, 1f)
                hrPaint.color = hrZoneColor(bpm)
                canvas.drawRect(barX1, hrB - hrFrac * hrH, barX2, hrB, hrPaint)
            }
            labelPaint.textAlign = Paint.Align.CENTER
        }

        drawXAxis(canvas, plotL, plotB, plotW, winStart, windowSec, useMeters)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun drawXAxis(canvas: Canvas, plotL: Float, plotB: Float, plotW: Float,
                          winStart: Double, windowRange: Double, useMeters: Boolean) {
        val tickCount = 5
        for (i in 0..tickCount) {
            val frac  = i.toFloat() / tickCount
            val x     = plotL + frac * plotW
            val value = winStart + frac * windowRange
            canvas.drawLine(x, plotB, x, plotB + 5f, axisPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.textSize  = 22f
            val label = if (useMeters) "%.0fm".format(value) else formatTime(value)
            canvas.drawText(label, x, plotB + MB - 6f, labelPaint)
            labelPaint.textSize  = 26f
        }
    }

    private fun hrZoneColor(bpm: Int): Int {
        val pct = bpm.toFloat() / maxHr
        return when {
            pct < 0.57f -> zoneColors[0]
            pct < 0.63f -> zoneColors[1]
            pct < 0.73f -> zoneColors[2]
            pct < 0.83f -> zoneColors[3]
            else         -> zoneColors[4]
        }
    }

    private fun formatTime(sec: Double): String {
        val t = sec.toLong().coerceAtLeast(0L)
        val m = t / 60; val s = t % 60
        return "$m:${"%02d".format(s)}"
    }
}
