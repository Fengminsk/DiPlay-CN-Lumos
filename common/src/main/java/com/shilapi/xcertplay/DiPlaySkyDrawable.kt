// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * DiPlay's sky. Light values mirror the web `.gh-sky`: a blue-grey base, a cool top-left wash,
 * a warm bottom-right wash, a blue centre wash and the two soft diagonal streaks.
 */
internal class DiPlaySkyDrawable(private val night: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        if (night) drawNight(canvas, w, h) else drawLight(canvas, w, h)
    }

    private fun drawLight(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w * 0.82f, h,
            intArrayOf(0xFFBCC9E1.toInt(), 0xFFA9BCDB.toInt(), 0xFFA1B6D7.toInt()),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        radial(canvas, w * 0.56f, h * 0.32f, w * 0.78f, Color.argb(66, 96, 140, 198), w, h)
        radial(canvas, w * 0.58f, h * 0.60f, w * 0.40f, Color.argb(40, 255, 200, 152), w, h)
        radial(canvas, w * 0.01f, -h * 0.14f, w * 0.42f, Color.argb(60, 255, 255, 255), w, h)
        radial(canvas, -w * 0.08f, -h * 0.24f, w * 0.60f, Color.argb(110, 255, 255, 255), w, h)
        radial(canvas, w * 0.57f, h * 0.55f, w * 0.45f, Color.argb(34, 255, 190, 142), w, h)

    }

    private fun drawNight(canvas: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(
            0f, 0f, w * 0.82f, h,
            intArrayOf(0xFF101A2C.toInt(), 0xFF14233C.toInt(), 0xFF0C1626.toInt()),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        radial(canvas, w * 0.10f, -h * 0.12f, w * 0.70f, Color.argb(82, 120, 152, 205), w, h)
        radial(canvas, w * 1.04f, h * 1.08f, w * 0.55f, Color.argb(41, 255, 170, 110), w, h)
        radial(canvas, -w * 0.08f, -h * 0.24f, w * 0.60f, Color.argb(102, 70, 110, 175), w, h)
        radial(canvas, w * 0.57f, h * 0.55f, w * 0.45f, Color.argb(46, 255, 170, 110), w, h)

    }

    private fun radial(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, w: Float, h: Float) {
        paint.shader = RadialGradient(cx, cy, max(radius, 1f), color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private fun streak(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        length: Float,
        thickness: Float,
        angle: Float,
        color: Int,
    ) {
        val checkpoint = canvas.save()
        canvas.rotate(angle, cx, cy)
        val rect = RectF(cx - length / 2f, cy - thickness / 2f, cx + length / 2f, cy + thickness / 2f)
        paint.shader = LinearGradient(
            rect.left, 0f, rect.right, 0f,
            intArrayOf(Color.TRANSPARENT, color, Color.TRANSPARENT),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(rect, paint)
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

