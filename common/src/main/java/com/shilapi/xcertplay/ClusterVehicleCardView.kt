package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.hud.BydVehicleCardData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The vehicle-status twin of the custom turn card: the same glass capsule, slider-driven
 * placement and size, showing one of several live readings (speed/gear, battery/range,
 * clock/battery, charging) from the ADB-backed poller.
 */
internal class ClusterVehicleCardView(context: Context) : View(context) {
    enum class Content { OFF, SPEED_GEAR, BATTERY_RANGE, CLOCK_BATTERY, CHARGING }

    private var content = Content.OFF
    private var xPercent = DEFAULT_X
    private var yPercent = ClusterTurnCardOverlay.DEFAULT_Y_PERCENT
    private var sizePercent = ClusterTurnCardOverlay.DEFAULT_SIZE_PERCENT
    private var snapshot: BydVehicleCardData.Snapshot? = null

    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 28, 28, 30) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(179, 199, 199, 204); textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val rect = RectF()

    fun setLayout(content: Content, xPercent: Int, yPercent: Int, sizePercent: Int) {
        this.content = content
        this.xPercent = xPercent
        this.yPercent = yPercent
        this.sizePercent = sizePercent
        visibility = if (content == Content.OFF) GONE else VISIBLE
        invalidate()
    }

    fun setSnapshot(next: BydVehicleCardData.Snapshot?) {
        if (snapshot == next) return
        snapshot = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (content == Content.OFF) return
        val data = snapshot
        val card = ClusterTurnCardOverlay.card(width, height, xPercent, yPercent, sizePercent)
        val h = card.height.toFloat()
        val w = card.width.toFloat()
        val radius = h * 0.30f
        rect.set(card.left.toFloat(), card.top.toFloat(), card.left + w, card.top + h)
        canvas.drawRoundRect(rect, radius, radius, glassPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val (primary, secondary) = texts(data) ?: run {
            subPaint.textSize = h * 0.24f
            canvas.drawText("—", card.left + w / 2f, card.top + h * 0.60f, subPaint)
            return
        }
        valuePaint.textSize = h * 0.42f
        subPaint.textSize = h * 0.20f
        canvas.drawText(primary, card.left + w / 2f, card.top + h * 0.52f, valuePaint)
        if (secondary.isNotEmpty()) {
            canvas.drawText(secondary, card.left + w / 2f, card.top + h * 0.79f, subPaint)
        }
    }

    private fun texts(data: BydVehicleCardData.Snapshot?): Pair<String, String>? = when (content) {
        Content.OFF -> null
        Content.SPEED_GEAR -> {
            val speed = data?.speedKmh?.roundToInt()?.toString() ?: return null
            speed to (data.gear ?: "")
        }
        Content.BATTERY_RANGE -> {
            val percent = data?.batteryPercent?.roundToInt() ?: return null
            "$percent%" to (data.rangeKm?.let { "$it km" } ?: "")
        }
        Content.CLOCK_BATTERY -> {
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val battery = data?.batteryPercent?.roundToInt()
            clock to if (battery != null) {
                val range = data.rangeKm
                if (range != null) "$battery% · $range km" else "$battery%"
            } else ""
        }
        Content.CHARGING -> {
            val charging = data?.charging ?: return null
            val percent = data.batteryPercent?.roundToInt()
            (if (charging) "⚡" else "—") to (percent?.let { "$it%" } ?: "")
        }
    }

    companion object {
        const val DEFAULT_X = 24
    }
}
