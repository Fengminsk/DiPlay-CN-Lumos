// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.roundToInt

/** The web's thin blue pairing spinner: an arc rotating at a constant speed. */
internal class DiPlaySpinner(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        strokeCap = Paint.Cap.ROUND
        color = 0xFF9FD0FF.toInt()
    }
    private val bounds = RectF()
    private var rotation = 0f
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotation = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        val diameter = min(width, height).toFloat()
        if (diameter <= 0f) return
        val stroke = paint.strokeWidth
        val left = (width - diameter) / 2f + stroke
        val top = (height - diameter) / 2f + stroke
        bounds.set(left, top, left + diameter - stroke * 2f, top + diameter - stroke * 2f)
        canvas.save()
        canvas.rotate(rotation, width / 2f, height / 2f)
        canvas.drawArc(bounds, -90f, 280f, false, paint)
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!animator.isStarted) animator.start()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

