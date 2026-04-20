package com.naibaf7.openrower.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Draws the force curve for the current rowing stroke drive phase.
 *
 * ── X-axis ────────────────────────────────────────────────────────────────
 * Fixed width = [windowSec] seconds, computed from the average drive duration
 * of the last 5 strokes × 1.3 (headroom so a slower stroke still fits).
 * Each sample is placed at its real timestamp within the drive, NOT spread
 * evenly, so the curve shape does not change as new points arrive.
 *
 * ── Y-axis ────────────────────────────────────────────────────────────────
 * Normalised force, 0 (catch/finish) → 1 (peak drive). Normalisation is done
 * in the engine over the completed portion of the stroke; the view just draws.
 *
 * ── Data ──────────────────────────────────────────────────────────────────
 * [samples] = list of (timeSec, normForce 0–1) pairs for the current drive.
 * [windowSec] = total X-axis span in seconds (fixed per the last-5 average).
 */
class ForceCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Timed samples: Pair(timeWithinDriveSec, normalisedForce 0–1). */
    var samples: List<Pair<Float, Float>> = emptyList()
        set(value) { field = value; invalidate() }

    /** Fixed x-axis width in seconds. Updated from RowingEngine. */
    var windowSec: Float = 1.2f
        set(value) { field = value; invalidate() }

    // ── Margins ───────────────────────────────────────────────────────────
    private val ML = 64f
    private val MR = 16f
    private val MT = 36f
    private val MB = 48f

    // ── Paints ────────────────────────────────────────────────────────────
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7")
        style = Paint.Style.STROKE; strokeWidth = 3.5f
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A4F7A"); style = Paint.Style.FILL
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90A4AE"); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5"); textSize = 26f; textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ECEFF1"); textSize = 30f; textAlign = Paint.Align.CENTER
    }
    private val windowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E3A5F"); strokeWidth = 1f; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 5f), 0f)
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

        // Title
        canvas.drawText("Force Curve", w / 2f, MT - 6f, titlePaint)

        // Axes
        canvas.drawLine(plotL, plotT, plotL, plotB, axisPaint)
        canvas.drawLine(plotL, plotB, plotR, plotB, axisPaint)

        // Y-axis ticks (25 % increments)
        labelPaint.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val y = plotB - i * plotH / 4f
            canvas.drawLine(plotL - 5f, y, plotL, y, axisPaint)
            canvas.drawText("${i * 25}%", plotL - 8f, y + 9f, labelPaint)
        }

        // X-axis ticks (time in seconds, 4 intervals)
        labelPaint.textAlign = Paint.Align.CENTER
        val tickCount = 4
        for (i in 0..tickCount) {
            val frac = i.toFloat() / tickCount
            val x    = plotL + frac * plotW
            val sec  = frac * windowSec
            canvas.drawLine(x, plotB, x, plotB + 5f, axisPaint)
            labelPaint.textSize = 22f
            canvas.drawText("%.2fs".format(sec), x, plotB + MB - 8f, labelPaint)
            labelPaint.textSize = 26f
        }

        // Dashed vertical line at windowSec boundary (end of expected drive)
        canvas.drawLine(plotR, plotT, plotR, plotB, windowPaint)

        if (samples.isEmpty()) {
            labelPaint.color = Color.parseColor("#546E7A")
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Waiting for stroke…", w / 2f, h / 2f, labelPaint)
            labelPaint.color = Color.parseColor("#B0BEC5")
            return
        }

        // Build fill + stroke path; each sample placed at its real timestamp
        val fillPath   = Path()
        val strokePath = Path()

        samples.forEachIndexed { idx, (timeSec, normForce) ->
            val x = plotL + (timeSec / windowSec).coerceIn(0f, 1f) * plotW
            val y = plotB - normForce.coerceIn(0f, 1f) * plotH

            if (idx == 0) {
                fillPath.moveTo(plotL, plotB)
                fillPath.lineTo(x, y)
                strokePath.moveTo(x, y)
            } else {
                fillPath.lineTo(x, y)
                strokePath.lineTo(x, y)
            }
        }

        // Close fill down to baseline at the last sample's x position
        val lastX = plotL + (samples.last().first / windowSec).coerceIn(0f, 1f) * plotW
        fillPath.lineTo(lastX, plotB)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(strokePath, curvePaint)
    }
}
