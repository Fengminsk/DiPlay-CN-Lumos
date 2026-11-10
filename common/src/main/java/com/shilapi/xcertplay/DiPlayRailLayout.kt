// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Glass rail whose blue selection pill is painted behind the item column. Painting it in
 * [dispatchDraw] keeps the item labels above the pill, so the pill can slide without covering
 * the selected row.
 */
internal class DiPlayRailLayout(
    context: Context,
    night: Boolean,
    startIndex: Int,
    endIndex: Int,
) : FrameLayout(context) {

    private val pill = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xFF4B9DFF.toInt(), 0xFF1F6CF2.toInt()),
    ).apply { cornerRadius = dp(22).toFloat() }

    private var position = startIndex.toFloat()

    init {
        background = GradientDrawable().apply {
            cornerRadius = dp(30).toFloat()
            setColor(if (night) 0xC816202E.toInt() else 0xC8F8FBFF.toInt())
            setStroke(dp(1), if (night) 0x33FFFFFF else 0xA6FFFFFF.toInt())
        }
        post {
            if (startIndex == endIndex) {
                position = endIndex.toFloat()
                invalidate()
                return@post
            }
            ValueAnimator.ofFloat(startIndex.toFloat(), endIndex.toFloat()).apply {
                duration = 320
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val top = dp(12) + (position * dp(72)).roundToInt()
        pill.setBounds(dp(12), top, width - dp(12), top + dp(64))
        pill.draw(canvas)
        super.dispatchDraw(canvas)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
