// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The selection is one glass piece sliding on the rail surface. Item rows stay transparent,
 * while their icon and text colours follow the moving piece.
 */
internal class DiPlayRailLayout(
    context: Context,
    night: Boolean,
    startIndex: Int,
    endIndex: Int,
    private val unselectedText: Int,
) : FrameLayout(context) {

    private val pill = DiPlayLiquidGlassDrawable(dp(22).toFloat(), night, control = true, tinted = true)

    private var position = startIndex.toFloat()
    private var rowColors = IntArray(0)

    init {
        background = DiPlayLiquidGlassDrawable(dp(30).toFloat(), night)
        post {
            if (startIndex == endIndex) {
                position = endIndex.toFloat()
                invalidate()
                return@post
            }
            ValueAnimator.ofFloat(startIndex.toFloat(), endIndex.toFloat()).apply {
                duration = 420
                interpolator = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
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
        val items = getChildAt(0) as? LinearLayout
        if (items != null) {
            if (rowColors.size != items.childCount) rowColors = IntArray(items.childCount) { Int.MIN_VALUE }
            for (index in 0 until items.childCount) {
                val row = items.getChildAt(index) as? LinearLayout ?: continue
                val proximity = (1f - abs(index - position)).coerceIn(0f, 1f)
                val color = ColorUtils.blendARGB(unselectedText, Color.WHITE, proximity)
                if (rowColors[index] != color) {
                    rowColors[index] = color
                    (row.getChildAt(0) as? ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(color)
                    (row.getChildAt(1) as? TextView)?.setTextColor(color)
                }
            }
        }
        super.dispatchDraw(canvas)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
