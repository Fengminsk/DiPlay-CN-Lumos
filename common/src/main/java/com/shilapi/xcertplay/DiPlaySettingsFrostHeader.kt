// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** Samples the scrolling settings panes into a small bitmap before drawing the pinned header. */
internal class DiPlaySettingsFrostHeader(
    context: Context,
    private val panes: List<View>,
    private val night: Boolean,
) : View(context) {
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sample: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sample?.recycle()
        sample = if (w > 0 && h > 0) Bitmap.createBitmap(
            (w / 10).coerceAtLeast(1), (h / 10).coerceAtLeast(1), Bitmap.Config.ARGB_8888,
        ) else null
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = sample ?: return
        bitmap.eraseColor(Color.TRANSPARENT)
        val small = Canvas(bitmap)
        small.scale(bitmap.width.toFloat() / width, bitmap.height.toFloat() / height)
        panes.forEach { pane ->
            small.save()
            small.translate(pane.left.toFloat(), pane.top.toFloat())
            pane.draw(small)
            small.restore()
        }

        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height),
            RectF(0f, 0f, width.toFloat(), height.toFloat()), blurPaint)
        maskPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(Color.WHITE, 0xD9FFFFFF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        maskPaint.xfermode = null
        maskPaint.shader = null
        canvas.restoreToCount(layer)

        tintPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            if (night) intArrayOf(0xE80B121C.toInt(), 0xC80B121C.toInt(), 0x000B121C)
            else intArrayOf(0xE8E7F1FD.toInt(), 0xC8E7F1FD.toInt(), 0x00E7F1FD),
            null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), tintPaint)
        tintPaint.shader = null
    }

    override fun onDetachedFromWindow() {
        sample?.recycle()
        sample = null
        super.onDetachedFromWindow()
    }
}
