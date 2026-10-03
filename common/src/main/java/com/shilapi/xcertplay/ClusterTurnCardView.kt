package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.ClusterTurnGuidance
import kotlin.math.cos
import kotlin.math.sin

/**
 * Instruction card drawn by DiPlay on top of the dashboard map.
 *
 * Visual language follows Apple's turn banners: a dark glass capsule with a hairline stroke,
 * the maneuver glyph in a soft chip on the left, distance and road stacked on the right.
 * Arrow geometry matches CarPlay semantics — slight is a shallow diagonal, turn is a right
 * angle, sharp bends past ninety degrees and points slightly downward.
 */
internal class ClusterTurnCardView(context: Context) : View(context) {
    private var guidance: ClusterTurnGuidance? = null
    private var xPercent = ClusterTurnCardOverlay.DEFAULT_X_PERCENT
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var size = CarPlayClusterDisplay.OverlaySize.MEDIUM

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 28, 28, 30) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 255, 255) }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 132, 255)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(10, 132, 255); style = Paint.Style.FILL
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val path = Path()
    private val rect = RectF()

    fun setLayout(xPercent: Int, yPercent: Int, size: CarPlayClusterDisplay.OverlaySize) {
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.size = size
        invalidate()
    }

    fun setGuidance(next: ClusterTurnGuidance?) {
        if (guidance == next) {
            visibility = if (next == null) GONE else VISIBLE
            return
        }
        guidance = next
        visibility = if (next == null) GONE else VISIBLE
        bringToFront()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1),
            MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val next = guidance ?: return
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, size)
        val h = card.height.toFloat()
        val w = card.width.toFloat()

        val radius = h * 0.30f
        rect.set(card.left.toFloat(), card.top.toFloat(), card.left + w, card.top + h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val chip = h * 0.76f
        val chipLeft = card.left + h * 0.12f
        val chipTop = card.top + (h - chip) / 2f
        rect.set(chipLeft, chipTop, chipLeft + chip, chipTop + chip)
        canvas.drawRoundRect(rect, chip * 0.26f, chip * 0.26f, chipPaint)
        drawManeuver(canvas, next, chipLeft + chip * 0.08f, chipTop + chip * 0.08f, chip * 0.84f)

        val textLeft = chipLeft + chip + h * 0.14f
        val textWidth = card.left + w - h * 0.10f - textLeft
        if (textWidth <= 0f) return
        distancePaint.textSize = h * 0.30f
        roadPaint.textSize = h * 0.17f
        canvas.drawText(
            ellipsize(distanceLabel(next.distanceMeters), textWidth, distancePaint),
            textLeft, card.top + h * 0.44f, distancePaint,
        )
        val road = roadLabel(next)
        if (road.isNotEmpty()) {
            canvas.drawText(
                ellipsize(road, textWidth, roadPaint),
                textLeft, card.top + h * 0.72f, roadPaint,
            )
        }
    }

    // ---- maneuver glyphs: s = box side, m = margin, head = arrowhead size, degrees measured
    // from the +x axis so headAt() can rotate the filled triangle along the travel direction.
    private fun drawManeuver(canvas: Canvas, next: ClusterTurnGuidance, left: Float, top: Float, s: Float) {
        arrowPaint.strokeWidth = s * 0.11f
        val cx = left + s / 2f
        val cy = top + s / 2f
        val m = s * 0.14f
        val head = s * 0.19f
        val bottom = top + s - m
        val topEdge = top + m
        when (next.icon) {
            2 -> turn(canvas, cx, cy, s, m, head, bottom, mirror = true)
            3 -> turn(canvas, cx, cy, s, m, head, bottom, mirror = false)
            4 -> slight(canvas, cx, s, m, head, bottom, topEdge, mirror = true)
            5 -> slight(canvas, cx, s, m, head, bottom, topEdge, mirror = false)
            6 -> sharp(canvas, cx, cy, s, m, head, bottom, mirror = true)
            7 -> sharp(canvas, cx, cy, s, m, head, bottom, mirror = false)
            8 -> uTurn(canvas, cx, cy, s, m, head, bottom, mirror = true)
            19 -> uTurn(canvas, cx, cy, s, m, head, bottom, mirror = false)
            11, 12, 17, 18 -> roundabout(canvas, cx, cy, s, m, head, next.roundaboutExit)
            15 -> destination(canvas, cx, cy, s, m)
            else -> {
                canvas.drawLine(cx, bottom, cx, topEdge + head * 0.5f, arrowPaint)
                headAt(canvas, cx, topEdge, -90f, head)
            }
        }
    }

    private fun turn(canvas: Canvas, cx: Float, cy: Float, s: Float, m: Float, head: Float, bottom: Float, mirror: Boolean) {
        val dir = if (mirror) -1f else 1f
        val stemX = cx - dir * s * 0.10f
        val elbowY = cy
        val endX = cx + dir * (s / 2f - m - head * 0.2f)
        canvas.drawLine(stemX, bottom, stemX, elbowY, arrowPaint)
        canvas.drawLine(stemX, elbowY, endX, elbowY, arrowPaint)
        headAt(canvas, cx + dir * (s / 2f - m), elbowY, if (mirror) 180f else 0f, head)
    }

    private fun slight(canvas: Canvas, cx: Float, s: Float, m: Float, head: Float, bottom: Float, topEdge: Float, mirror: Boolean) {
        val dir = if (mirror) -1f else 1f
        // A shallow diagonal — about 30 degrees off vertical.
        val endX = cx + dir * (s / 2f - m - head * 0.3f)
        val endY = topEdge + s * 0.30f
        canvas.drawLine(cx - dir * s * 0.10f, bottom, endX, endY, arrowPaint)
        val angle = Math.toDegrees(kotlin.math.atan2((endY - bottom).toDouble(), (endX - (cx - dir * s * 0.10f)).toDouble())).toFloat()
        headAt(canvas, endX + dir * head * 0.2f, endY, angle, head)
    }

    private fun sharp(canvas: Canvas, cx: Float, cy: Float, s: Float, m: Float, head: Float, bottom: Float, mirror: Boolean) {
        val dir = if (mirror) -1f else 1f
        val stemX = cx - dir * s * 0.04f
        val elbowY = cy - s * 0.06f
        val endX = cx + dir * (s / 2f - m - head * 0.2f)
        val endY = elbowY + s * 0.26f // bends past ninety degrees and points slightly down
        canvas.drawLine(stemX, bottom, stemX, elbowY, arrowPaint)
        canvas.drawLine(stemX, elbowY, endX, endY, arrowPaint)
        val angle = Math.toDegrees(kotlin.math.atan2((endY - elbowY).toDouble(), (endX - stemX).toDouble())).toFloat()
        headAt(canvas, cx + dir * (s / 2f - m), endY + s * 0.02f, angle, head)
    }

    private fun uTurn(canvas: Canvas, cx: Float, cy: Float, s: Float, m: Float, head: Float, bottom: Float, mirror: Boolean) {
        val dir = if (mirror) -1f else 1f
        val stemX = cx - dir * s * 0.14f
        val otherX = cx + dir * s * 0.14f
        val arcTop = cy - s * 0.22f
        val r = kotlin.math.abs(otherX - stemX) / 2f
        canvas.drawLine(stemX, bottom, stemX, arcTop + r, arrowPaint)
        rect.set(minOf(stemX, otherX), arcTop, minOf(stemX, otherX) + r * 2f, arcTop + r * 2f)
        canvas.drawArc(rect, 180f, 180f, false, arrowPaint)
        canvas.drawLine(otherX, arcTop + r, otherX, bottom - head * 0.4f, arrowPaint)
        headAt(canvas, otherX, bottom, 90f, head)
    }

    private fun roundabout(canvas: Canvas, cx: Float, cy: Float, s: Float, m: Float, head: Float, exit: Int) {
        val r = s * 0.26f
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 130f, 280f, false, arrowPaint)
        val exitX = cx + r + s * 0.06f
        val exitY = cy - s * 0.02f
        headAt(canvas, exitX, exitY, -35f, head)
        if (exit in 1..9) {
            distancePaint.textSize = s * 0.26f
            val label = exit.toString()
            canvas.drawText(label, cx - distancePaint.measureText(label) / 2f, cy + distancePaint.textSize * 0.35f, distancePaint)
        }
    }

    private fun destination(canvas: Canvas, cx: Float, cy: Float, s: Float, m: Float) {
        val pole = cx - s * 0.06f
        canvas.drawLine(pole, cy + s / 2f - m, pole, cy - s / 2f + m, arrowPaint)
        path.reset()
        path.moveTo(pole, cy - s / 2f + m)
        path.lineTo(pole + s * 0.36f, cy - s * 0.08f)
        path.lineTo(pole, cy + s * 0.02f)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    /** Filled triangle with its tip at (x, y), pointing along [angleDeg] from the +x axis. */
    private fun headAt(canvas: Canvas, x: Float, y: Float, angleDeg: Float, size: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val tipX = x + (size * 0.42 * cos(rad)).toFloat()
        val tipY = y + (size * 0.42 * sin(rad)).toFloat()
        val back = size * 0.58
        val half = size * 0.46
        val bx = x - (back * cos(rad)).toFloat()
        val by = y - (back * sin(rad)).toFloat()
        val nx = (-sin(rad) * half).toFloat()
        val ny = (cos(rad) * half).toFloat()
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(bx + nx, by + ny)
        path.lineTo(bx - nx, by - ny)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    private fun distanceLabel(meters: Int): String = when {
        meters <= 20 -> context.getString(com.shilapi.xcertplay.host.R.string.turn_card_now)
        meters < 1000 -> context.getString(com.shilapi.xcertplay.host.R.string.turn_card_distance_m, meters)
        else -> context.getString(
            com.shilapi.xcertplay.host.R.string.turn_card_distance_km, meters / 100 / 10f,
        )
    }

    private fun roadLabel(next: ClusterTurnGuidance): String =
        if (next.roundaboutExit in 1..9) {
            context.getString(com.shilapi.xcertplay.host.R.string.turn_card_exit, next.roundaboutExit)
        } else {
            next.road
        }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.take(end) + ellipsis) > maxWidth) end--
        return if (end == 0) ellipsis else text.take(end) + ellipsis
    }
}
