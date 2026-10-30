package com.smartapes.openrower.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.smartapes.openrower.model.SegmentType
import com.smartapes.openrower.model.WorkoutSegment
import kotlin.math.max

/**
 * Horizontal timeline displaying custom workout segments.
 *
 * Each segment is drawn as a rounded rectangle scaled by duration.
 * WORKOUT segments are blue; RECOVERY segments are green.
 * The selected segment is highlighted with a bright border.
 * A minimum pixel width ensures small segments remain tappable.
 *
 * During a live workout [activeSegmentIndex] tints the current segment.
 */
class WorkoutTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var segments: List<WorkoutSegment> = emptyList()
        set(value) { field = value; recalcLayout(); invalidate() }

    var selectedIndex: Int = -1
        set(value) { field = value; invalidate() }

    var activeSegmentIndex: Int = -1
        set(value) { field = value; invalidate() }

    var onSegmentClick: ((index: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val minSegPx = (56 * density).toInt()   // minimum clickable width
    private val paddingPx = (4 * density).toInt()
    private val cornerPx  = (6 * density)

    private val paintFill   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val paintLabel  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val rects = mutableListOf<RectF>()

    private val gesture = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val idx = rects.indexOfFirst { it.contains(e.x, e.y) }
                if (idx >= 0) {
                    selectedIndex = idx
                    onSegmentClick?.invoke(idx)
                }
                return true
            }
        })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gesture.onTouchEvent(event)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenWidth = resources.displayMetrics.widthPixels
        val desiredW = if (segments.isEmpty()) screenWidth else computeContentWidth(screenWidth)
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize((80 * density).toInt(), heightMeasureSpec)
        )
    }

    private fun computeContentWidth(screenWidth: Int): Int {
        val durations = segments.map { max(1.0, it.estimatedDurationSec) }
        val totalDur = durations.sum()
        val gaps = paddingPx * 2 + paddingPx * (segments.size - 1)
        val refAvail = (screenWidth - gaps).coerceAtLeast(segments.size * minSegPx)
        val widths = durations.map { (it / totalDur * refAvail).toInt().coerceAtLeast(minSegPx) }
        return widths.sum() + gaps
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcLayout()
    }

    private fun recalcLayout() {
        rects.clear()
        if (segments.isEmpty() || width == 0) return

        val durations = segments.map { max(1.0, it.estimatedDurationSec) }
        val totalDur  = durations.sum()
        val availableWidth = width - paddingPx * 2 - paddingPx * (segments.size - 1)

        val finalWidths = durations.map {
            (it / totalDur * availableWidth).toInt().coerceAtLeast(minSegPx)
        }

        val top    = paddingPx.toFloat()
        val bottom = (height - paddingPx).toFloat()
        var left   = paddingPx.toFloat()

        for (w in finalWidths) {
            rects.add(RectF(left, top, left + w, bottom))
            left += w + paddingPx
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) {
            // Draw placeholder
            paintLabel.textSize = 14 * density
            paintLabel.color = Color.parseColor("#546E7A")
            canvas.drawText("No segments — tap + to add", width / 2f, height / 2f, paintLabel)
            return
        }

        val textSize = ((height - paddingPx * 2) * 0.28f).coerceIn(10 * density, 16 * density)
        paintLabel.textSize = textSize

        for ((i, seg) in segments.withIndex()) {
            val rect = rects.getOrNull(i) ?: continue
            val isSelected = i == selectedIndex
            val isActive   = i == activeSegmentIndex

            val baseColor = if (seg.type == SegmentType.WORKOUT)
                Color.parseColor("#1565C0") else Color.parseColor("#1B5E20")
            val fillColor = when {
                isActive   -> lighten(baseColor, 0.35f)
                isSelected -> lighten(baseColor, 0.15f)
                else       -> baseColor
            }

            paintFill.color = fillColor
            canvas.drawRoundRect(rect, cornerPx, cornerPx, paintFill)

            paintBorder.color = when {
                isSelected -> Color.WHITE
                isActive   -> Color.parseColor("#FFEB3B")
                else       -> Color.parseColor("#37474F")
            }
            canvas.drawRoundRect(rect, cornerPx, cornerPx, paintBorder)

            // Label: "W1", "R", "W2" etc.
            val label = buildLabel(seg, i)
            paintLabel.color = Color.WHITE
            canvas.drawText(label, rect.centerX(), rect.centerY() + textSize / 3f, paintLabel)

            // Duration sub-label if the rect is wide enough
            if (rect.width() > 60 * density) {
                paintLabel.textSize = textSize * 0.65f
                paintLabel.color = Color.parseColor("#B0BEC5")
                val durLabel = formatDur(seg)
                canvas.drawText(durLabel, rect.centerX(), rect.centerY() + textSize * 1.1f, paintLabel)
                paintLabel.textSize = textSize
            }
        }
    }

    private fun buildLabel(seg: WorkoutSegment, idx: Int): String {
        var wIdx = 0; var rIdx = 0
        for (i in 0..idx) {
            if (segments[i].type == SegmentType.WORKOUT) wIdx++ else rIdx++
        }
        return if (seg.type == SegmentType.WORKOUT) "W$wIdx" else "R"
    }

    private fun formatDur(seg: WorkoutSegment): String {
        seg.distanceMeters?.let { return "%.0fm".format(it) }
        val s = seg.durationSec?.toLong() ?: return "∞"
        return if (s >= 60) "${s / 60}:${"%02d".format(s % 60)}" else "${s}s"
    }

    private fun lighten(color: Int, factor: Float): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val f = (factor * 255).toInt()
        return Color.rgb(
            (r + f).coerceAtMost(255),
            (g + f).coerceAtMost(255),
            (b + f).coerceAtMost(255)
        )
    }
}
