// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
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
    private val control: Boolean = false,
    private val tinted: Boolean = false,
    private val opaque: Boolean = false,
    private val circle: Boolean = false,
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
        val shape = if (circle) Path().apply { addOval(b, Path.Direction.CW) }
            else continuousCornerPath(b, radius)
        paint.color = Color.WHITE

        paint.shader = if (opaque && dark) {
            LinearGradient(b.left, b.top, b.right, b.bottom,
                intArrayOf(0xFF24344B.toInt(), 0xFF101D30.toInt()), null, Shader.TileMode.CLAMP)
        } else if (opaque) {
            LinearGradient(b.left, b.top, b.right, b.bottom,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFF4F8FF.toInt()), null, Shader.TileMode.CLAMP)
        } else if (control && tinted && dark) {
            LinearGradient(
                b.left, b.top, b.right, b.bottom,
                intArrayOf(0xE54A6E9C.toInt(), 0xE3345785.toInt(), 0xE525416D.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else if (control && tinted) {
            LinearGradient(
                b.left, b.top, b.right, b.bottom,
                intArrayOf(0xEC58A9FF.toInt(), 0xE63C8CFA.toInt(), 0xE41C67EF.toInt()),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else if (control && dark) {
            LinearGradient(
                b.left, b.top, b.right, b.bottom,
                intArrayOf(0xDE354C6A.toInt(), 0xDC2A405E.toInt(), 0xDA203450.toInt()),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else if (control) {
            LinearGradient(
                b.left, b.top, b.right, b.bottom,
                intArrayOf(0xD9FFFFFF.toInt(), 0xC7F4F9FF.toInt(), 0xB8DDEBFA.toInt()),
                floatArrayOf(0f, 0.54f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else if (dark) {
            LinearGradient(
                b.left, b.top, b.right, b.bottom,
                intArrayOf(0xE0263A54.toInt(), 0xDC23344D.toInt(), 0xD81B2B43.toInt()),
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
        canvas.drawPath(shape, paint)

        paint.shader = RadialGradient(
            b.left + b.width() * 0.18f + dragOffsetX * 0.18f,
            b.top,
            b.width() * 0.66f,
            if (dark && tinted) 0x32FFFFFF else if (dark && control) 0x26FFFFFF
            else if (dark) 0x1CFFFFFF else if (control) 0xA6FFFFFF.toInt() else 0x66FFFFFF,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(shape, paint)

        paint.shader = RadialGradient(
            b.right - b.width() * 0.10f,
            b.bottom,
            b.width() * 0.52f,
            if (dark) 0x16FFC496 else if (control) 0x36FFC496 else 0x1FFFC496,
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(shape, paint)

        if (press > 0f) {
            paint.shader = null
            paint.color = Color.argb((press * if (control) 0x30 else 0x14).toInt(), 255, 255, 255)
            canvas.drawPath(shape, paint)
        }

        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = if (dark) 0x18FFFFFF else if (control) 0x30FFFFFF else 0x1CFFFFFF
        paint.strokeWidth = if (control) 5f else 10f
        canvas.drawPath(shape, paint)
        paint.color = if (dark) 0x70FFFFFF else 0xCCFFFFFF.toInt()
        paint.strokeWidth = if (control) 1.5f else 2f
        canvas.drawPath(shape, paint)
        paint.color = if (dark) 0x36FFFFFF else 0x66FFFFFF
        paint.strokeWidth = 1.2f
        if (!circle) canvas.drawLine(
            b.left + b.width() * 0.20f,
            b.top + 1.6f,
            b.right - b.width() * 0.20f,
            b.top + 1.6f,
            paint,
        )
        paint.style = Paint.Style.FILL
    }

    private fun continuousCornerPath(b: RectF, radius: Float): Path {
        val shoulder = radius * 0.28f
        return Path().apply {
            moveTo(b.left + radius, b.top)
            lineTo(b.right - radius, b.top)
            cubicTo(b.right - shoulder, b.top, b.right, b.top + shoulder, b.right, b.top + radius)
            lineTo(b.right, b.bottom - radius)
            cubicTo(b.right, b.bottom - shoulder, b.right - shoulder, b.bottom, b.right - radius, b.bottom)
            lineTo(b.left + radius, b.bottom)
            cubicTo(b.left + shoulder, b.bottom, b.left, b.bottom - shoulder, b.left, b.bottom - radius)
            lineTo(b.left, b.top + radius)
            cubicTo(b.left, b.top + shoulder, b.left + shoulder, b.top, b.left + radius, b.top)
            close()
        }
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
            val glass = (v.background as? RippleDrawable)?.getDrawable(0) as? DiPlayLiquidGlassDrawable
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    glass?.dragOffsetX = event.x - v.width / 2f
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> glass?.dragOffsetX = event.x - v.width / 2f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    glass?.dragOffsetX = 0f
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }
            }
            false
        }
    }
}




