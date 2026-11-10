// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * DiPlay's shared liquid-glass surface. It mirrors the web `.gh-card` / `.pair-card` fills:
 * a blue translucent body, a white top-left bloom, a warm bottom-right bloom, a bright rim and
 * a soft inner highlight. Pressing the owning view makes the surface animate a short white
 * flash; dragging it moves the bloom with the finger.
 */
internal class DiPlayLiquidGlassDrawable(
    cornerRadius: Float,
    private val dark: Boolean,
) : Drawable() {

    var cornerRadius: Float = cornerRadius
        set(value) {
            field = value
            invalidateSelf()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var press = 0f
    private var pressAnimator: ValueAnimator? = null

    var dragOffsetX = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = RectF(bounds)
        if (b.isEmpty) return
        val radius = min(cornerRadius, min(b.width(), b.height()) / 2f)

        paint.shader = if (dark) {
            LinearGradient(
                b.left, b.top, b.right, b.bottom,
                intArrayOf(0xC8AACDF5.toInt(), 0xB89CC2F0.toInt(), 0xA882AAE6.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                b.left, b.top, b.right, b.bottom,
                intArrayOf(0xF2FFFFFF.toInt(), 0xEAF3F7FD.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(b, radius, radius, paint)

        paint.shader = RadialGradient(
            b.left + b.width() * 0.18f + dragOffsetX * 0.18f,
            b.top,
            b.width() * 0.66f,
            if (dark) 0x80FFFFFF.toInt() else 0x66FFFFFF,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(b, radius, radius, paint)

        paint.shader = RadialGradient(
            b.right - b.width() * 0.10f,
            b.bottom,
            b.width() * 0.52f,
            if (dark) 0x42FFC496 else 0x1FFFC496,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(b, radius, radius, paint)

        if (press > 0f) {
            paint.shader = null
            paint.color = Color.argb((press * 0x14).toInt(), 255, 255, 255)
            canvas.drawRoundRect(b, radius, radius, paint)
        }

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = 0x1CFFFFFF
        paint.strokeWidth = 10f
        canvas.drawRoundRect(b, radius, radius, paint)
        paint.color = if (dark) 0x8CFFFFFF.toInt() else 0xCCFFFFFF.toInt()
        paint.strokeWidth = 3.0f
        canvas.drawRoundRect(b, radius, radius, paint)
        paint.color = 0x66FFFFFF
        paint.strokeWidth = 1.2f
        canvas.drawLine(
            b.left + b.width() * 0.20f,
            b.top + 1.6f,
            b.right - b.width() * 0.20f,
            b.top + 1.6f,
            paint,
        )
        paint.style = Paint.Style.FILL
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = state.contains(android.R.attr.state_pressed)
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(press, if (pressed) 1f else 0f).apply {
            duration = if (pressed) 90L else 220L
            addUpdateListener {
                press = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
        return true
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** The web pairing scrim: a soft blue-black radial dim, not a flat dialog dim. */
internal class DiPlayPairScrimDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = RadialGradient(
            w * 0.5f,
            h * 0.46f,
            maxOf(w, h) * 0.58f,
            0x30142846,
            0x7A0A162A,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/** Press feedback shared by the floating glass controls and the pairing card buttons. */
internal object DiPlayGlassTouch {
    fun install(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate()
                    .scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                    .scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
    }
}




