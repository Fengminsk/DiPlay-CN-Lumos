// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The approved 1280 脳 720 glass home. The stage scales uniformly and is centred inside the
 * window; the sky fills the whole window. Colours follow [night].
 */
internal class DiPlayGlassHome(
    context: Context,
    private val night: Boolean,
    initialWireless: Boolean,
    onCarPlay: () -> Unit,
    onIccoa: () -> Unit,
    onSettings: () -> Unit,
    onPermissions: () -> Unit,
    onCarHome: () -> Unit,
    onTransport: (Boolean) -> Unit,
) : FrameLayout(context) {

    val statusView = TextView(context).apply {
        gravity = Gravity.CENTER
        maxLines = 2
        setTextColor(if (night) 0xFFCAD7E8.toInt() else 0xFF5E7089.toInt())
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        ellipsize = android.text.TextUtils.TruncateAt.END
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val iconTint = if (night) 0xFFEAF2FF.toInt() else 0xFF1C2A3D.toInt()
    private val carButton = topButton(R.drawable.ic_dp_car, R.string.car_home) { onCarHome() }
    private val gearButton = topButton(R.drawable.ic_dp_gear, R.string.settings) { onSettings() }
    private val permissionButton = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFF1B2433.toInt())
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFC880.toInt())
            },
            null,
        )
        outlineProvider = roundedOutline(0.5f)
        elevation = dp(6f)
        visibility = GONE
        contentDescription = context.getString(R.string.permission_center_title)
        setOnClickListener { onPermissions() }
        DiPlayGlassTouch.install(this)
    }
    private val carTile = glassTile(
        label = context.getString(R.string.carplay),
        iconRes = R.drawable.ic_carplay,
        markRes = null,
        onClick = onCarPlay,
    )
    private val iccoaTile = glassTile(
        label = context.getString(R.string.iccoa),
        iconRes = 0,
        markRes = R.drawable.ic_dp_iccoa,
        onClick = onIccoa,
    )
    private val transport = TransportView(context, night, initialWireless, onTransport)

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var frost: Bitmap? = null
    private val sky = DiPlaySkyDrawable(night)
    private var skyScale = 1f
    private var skyAnimator: ValueAnimator? = null

    init {
        background = null
        addView(carButton)
        addView(gearButton)
        addView(permissionButton)
        addView(carTile)
        addView(iccoaTile)
        carTile.glassRenderer = { canvas -> drawTileGlassInTile(canvas, carTile) }
        iccoaTile.glassRenderer = { canvas -> drawTileGlassInTile(canvas, iccoaTile) }
        addView(transport)
        addView(statusView)
        clipChildren = false
        clipToPadding = false
        carTile.alpha = 0f
        carTile.translationY = dp(26f)
        iccoaTile.alpha = 0f
        iccoaTile.translationY = dp(26f)
        transport.alpha = 0f
        statusView.alpha = 0f
        post {
            carTile.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(60)
                .setDuration(620)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            iccoaTile.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(150)
                .setDuration(620)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            transport.animate().alpha(1f).setStartDelay(280).setDuration(420).start()
            statusView.animate().alpha(1f).setStartDelay(340).setDuration(420).start()
        }
    }

    /** Moves the two cards aside while a pairing card is open, and restores them on close. */
    fun setPairingMode(active: Boolean) {
        val duration = if (active) 750L else 450L
        val interpolator = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        val targetScale = if (active) 0.80f else 1f
        val targetAlpha = if (active) 0.96f else 1f
        carTile.animate()
            .translationX(if (active) -184f * scale else 0f)
            .translationY(if (active) 18f * scale else 0f)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .rotationY(if (active) 9f else 0f)
            .alpha(targetAlpha)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
        iccoaTile.animate()
            .translationX(if (active) 188f * scale else 0f)
            .translationY(if (active) 18f * scale else 0f)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .rotationY(if (active) -9f else 0f)
            .alpha(targetAlpha)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
        transport.animate()
            .translationY(if (active) 16f * scale else 0f)
            .alpha(if (active) 0f else 1f)
            .setDuration(if (active) 400L else 450L)
            .setInterpolator(interpolator)
            .start()
        statusView.animate().alpha(if (active) 0f else 1f).setDuration(duration).start()
        skyAnimator?.cancel()
        skyAnimator = ValueAnimator.ofFloat(skyScale, if (active) 1.02f else 1f).apply {
            this.duration = if (active) 1000L else 450L
            addUpdateListener {
                skyScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun topButton(icon: Int, description: Int, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(iconTint)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(description)
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            DiPlayLiquidGlassDrawable(dp(64f), night, control = true, circle = true),
            null,
        )
        outlineProvider = roundedOutline(0.5f)
        elevation = dp(6f)
        setOnClickListener { click() }
        DiPlayGlassTouch.install(this)
    }

    private fun glassTile(label: String, iconRes: Int, markRes: Int?, onClick: () -> Unit) = GlassTile(
        context = context,
        night = night,
        label = label,
        iconRes = iconRes,
        markRes = markRes,
    ).apply {
        contentDescription = label
        outlineProvider = roundedOutline(72f / 297f)
        setOnClickListener { onClick() }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.982f).scaleY(0.982f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).setDuration(170).start()
            }
            false
        }
    }

    private fun roundedOutline(fraction: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            val radius = min(view.width, view.height) * fraction
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(1)
        scale = min(contentWidth / 1280f, contentHeight / 720f).coerceAtLeast(0.1f)
        offsetX = paddingLeft + (contentWidth - 1280f * scale) / 2f
        offsetY = paddingTop + (contentHeight - 720f * scale) / 2f

        fun measure(view: View, w: Float, h: Float) {
            view.measure(
                MeasureSpec.makeMeasureSpec((w * scale).roundToInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((h * scale).roundToInt(), MeasureSpec.EXACTLY),
            )
        }
        measure(carButton, 50f, 50f)
        measure(gearButton, 50f, 50f)
        measure(permissionButton, 50f, 50f)
        measure(carTile, 297f, 297f)
        measure(iccoaTile, 297f, 297f)
        measure(transport, 274f, 60f)
        measure(statusView, 760f, 40f)
        carButton.setPadding((13f * scale).roundToInt(), (13f * scale).roundToInt(),
            (13f * scale).roundToInt(), (13f * scale).roundToInt())
        gearButton.setPadding((13f * scale).roundToInt(), (13f * scale).roundToInt(),
            (13f * scale).roundToInt(), (13f * scale).roundToInt())
        statusView.paint.textSize = 15f * scale
        carTile.elevation = 0f
        iccoaTile.elevation = 0f
        carTile.cameraDistance = 1400f * scale * resources.displayMetrics.density
        iccoaTile.cameraDistance = 1400f * scale * resources.displayMetrics.density
        transport.elevation = dp(8f) * scale
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(view: View, x: Float, y: Float) {
            val l = (offsetX + x * scale).roundToInt()
            val t = (offsetY + y * scale).roundToInt()
            view.layout(l, t, l + view.measuredWidth, t + view.measuredHeight)
        }
        place(carButton, 1117f, 39f)
        place(gearButton, 1181f, 39f)
        place(permissionButton, 1181f, 101f)
        place(carTile, 324f, 177f)
        place(iccoaTile, 659f, 177f)
        place(transport, 503f, 541f)
        place(statusView, 260f, 491f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frost = buildFrost(w, h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val skyCheckpoint = canvas.save()
        canvas.scale(skyScale, skyScale, width / 2f, height / 2f)
        sky.setBounds(0, 0, width, height)
        sky.draw(canvas)
        canvas.restoreToCount(skyCheckpoint)
        super.dispatchDraw(canvas)
    }

    /** A dim, frosted copy of the home for the pairing layer's backdrop. */
    fun snapshotBlurred(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val smallWidth = max(1, width / 20)
        val smallHeight = max(1, height / 20)
        return runCatching {
            val small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(small)
            canvas.scale(smallWidth / width.toFloat(), smallHeight / height.toFloat())
            draw(canvas)
            small
        }.getOrNull()
    }

    /** A recognisable quarter-size copy of the home, used as the CarPlay connection backdrop. */
    fun snapshotForHost(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val smallWidth = max(1, (width * 0.25f).roundToInt())
        val smallHeight = max(1, (height * 0.25f).roundToInt())
        return runCatching {
            val small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(small)
            canvas.scale(smallWidth / width.toFloat(), smallHeight / height.toFloat())
            draw(canvas)
            small
        }.getOrNull()
    }

    private fun buildFrost(w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val smallWidth = max(1, w / 6)
        val smallHeight = max(1, h / 6)
        return runCatching {
            val bitmap = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val sky = DiPlaySkyDrawable(night)
            sky.setBounds(0, 0, smallWidth, smallHeight)
            sky.draw(canvas)
            bitmap
        }.getOrNull()
    }

    private fun drawTileGlassInTile(canvas: Canvas, tile: View) {
        if (tile.width <= 0 || tile.height <= 0) return
        val rect = RectF(0f, 0f, tile.width.toFloat(), tile.height.toFloat())
        val radius = min(rect.width(), rect.height()) * (72f / 297f)
        val checkpoint = canvas.save()
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.clipPath(clip)

        // The blurred sky crop is the frosted backdrop; the gradients below are the web card fill.
        frost?.let { bitmap ->
            val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            val frostCheckpoint = canvas.save()
            canvas.translate(-tile.left.toFloat(), -tile.top.toFloat())
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
            canvas.restoreToCount(frostCheckpoint)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        if (night) {
            fill.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(0x302E4A70, 0x36405F86, 0x28283E5F),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, fill)
            fill.shader = RadialGradient(
                rect.left + rect.width() * 0.16f,
                rect.top - rect.height() * 0.06f,
                rect.width() * 0.60f,
                0x1CFFFFFF,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, fill)
            fill.shader = RadialGradient(
                rect.left + rect.width() * 0.86f,
                rect.top + rect.height() * 1.04f,
                rect.width() * 0.50f,
                0x14FFB076,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, fill)
            fill.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.top + rect.height() * 0.42f,
                intArrayOf(0x16FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, fill)
        } else {
            fill.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(0xB0C9DCF3.toInt(), 0xA09BBADF.toInt(), 0xA0A6BEE4.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, fill)
            fill.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.top + rect.height() * 0.38f,
                intArrayOf(0x33FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, fill)
            fill.shader = RadialGradient(
                rect.left + rect.width() * 0.84f,
                rect.top + rect.height() * 1.02f,
                rect.width() * 0.36f,
                0x2EFFC696,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, fill)
        }
        canvas.restoreToCount(checkpoint)

        // The bright, slightly warm rim and its white bloom, painted outside the clip.
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        if (night) {
            rim.color = 0x0DFFFFFF
            rim.strokeWidth = 4f * scale
            canvas.drawRoundRect(rect, radius, radius, rim)
            rim.color = 0x29FFFFFF
            rim.strokeWidth = 1.2f * scale
            canvas.drawRoundRect(rect, radius, radius, rim)
            rim.color = 0x1AFFFFFF
            rim.strokeWidth = 1f * scale
            canvas.drawLine(
                rect.left + rect.width() * 0.22f,
                rect.top + 1.4f * scale,
                rect.right - rect.width() * 0.22f,
                rect.top + 1.4f * scale,
                rim,
            )
        } else {
            rim.color = 0x10FFFFFF
            rim.strokeWidth = 6f * scale
            canvas.drawRoundRect(rect, radius, radius, rim)
            rim.color = 0xB3FFFFFF.toInt()
            rim.strokeWidth = 1.8f * scale
            canvas.drawRoundRect(rect, radius, radius, rim)
            rim.color = 0x66FFFFFF
            rim.strokeWidth = 1.2f * scale
            canvas.drawLine(
                rect.left + rect.width() * 0.22f,
                rect.top + 1.6f * scale,
                rect.right - rect.width() * 0.22f,
                rect.top + 1.6f * scale,
                rim,
            )
        }
    }

    private class GlassTile(
        context: Context,
        private val night: Boolean,
        label: String,
        iconRes: Int,
        private val markRes: Int?,
    ) : LinearLayout(context) {

        var glassRenderer: ((Canvas) -> Unit)? = null

        override fun dispatchDraw(canvas: Canvas) {
            glassRenderer?.invoke(canvas)
            super.dispatchDraw(canvas)
        }

        private val iconBox: View
        private val labelView = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            iconBox = ImageView(context).apply {
                if (markRes != null) {
                    setImageResource(markRes)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    background = GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0xFF4B9DFF.toInt(), 0xFF1E6CF2.toInt()),
                    ).apply { cornerRadius = dp(28f) }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                } else {
                    setImageResource(iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            addView(iconBox, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(labelView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (24f * resources.displayMetrics.density).roundToInt()
            })
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            if (width > 0) {
                val icon = (width * 112f / 297f).roundToInt()
                val params = iconBox.layoutParams as LayoutParams
                if (params.width != icon || params.height != icon) {
                    params.width = icon
                    params.height = icon
                    iconBox.layoutParams = params
                }
                val inset = if (markRes != null) (icon * 0.25f).roundToInt() else 0
                iconBox.setPadding(inset, inset, inset, inset)
                labelView.paint.textSize = width * 32f / 297f
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
    }

    private class TransportView(
        context: Context,
        private val night: Boolean,
        initialWireless: Boolean,
        private val onChange: (Boolean) -> Unit,
    ) : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val usbIcon = ContextCompat.getDrawable(context, R.drawable.ic_dp_usb)!!.mutate()
        private val wifiIcon = ContextCompat.getDrawable(context, R.drawable.ic_dp_wifi)!!.mutate()
        private var wifi = initialWireless
        private var thumbX = 0f
        private var animator: ValueAnimator? = null
        private var downX = 0f
        private var startThumb = 0f
        private var dragging = false
        private var moved = false

        init {
            isClickable = true
            isFocusable = true
            contentDescription = context.getString(R.string.glass_home_usb) + " / " +
                context.getString(R.string.glass_home_wifi)
        }

        private val pad get() = height * 0.035f
        private val thumbWidth get() = width * 135f / 274f
        private val thumbHeight get() = height - pad * 2

        private fun targetFor(wifi: Boolean): Float =
            if (wifi) width - thumbWidth - pad else pad

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            thumbX = targetFor(wifi)
        }

        private fun settle(wifi: Boolean, animate: Boolean) {
            this.wifi = wifi
            val target = targetFor(wifi)
            animator?.cancel()
            if (!animate) {
                thumbX = target
                invalidate()
                return
            }
            animator = ValueAnimator.ofFloat(thumbX, target).apply {
                duration = 260
                addUpdateListener {
                    thumbX = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val radius = h / 2f
            val track = RectF(0f, 0f, w, h)
            fill.shader = LinearGradient(
                0f, 0f, 0f, h,
                if (night) 0x20FFFFFF else 0x38FFFFFF,
                if (night) 0x0AFFFFFF else 0x16FFFFFF,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRoundRect(track, radius, radius, fill)
            fill.shader = null
            stroke.color = if (night) 0x3DFFFFFF else 0xA6FFFFFF.toInt()
            stroke.strokeWidth = h * 0.025f
            canvas.drawRoundRect(track, radius, radius, stroke)

            val top = pad
            val left = thumbX
            val thumbRect = RectF(left, top, left + thumbWidth, top + thumbHeight)
            shadow.color = 0x220B1A2E
            canvas.drawRoundRect(
                RectF(left + h * 0.02f, top + h * 0.05f, left + thumbWidth + h * 0.02f, top + thumbHeight + h * 0.05f),
                thumbHeight / 2f, thumbHeight / 2f, shadow,
            )
            thumb.color = if (night) 0xFFF4F8FF.toInt() else 0xFFFDFEFF.toInt()
            canvas.drawRoundRect(thumbRect, thumbHeight / 2f, thumbHeight / 2f, thumb)
            stroke.color = 0x66FFFFFF
            stroke.strokeWidth = h * 0.018f
            canvas.drawRoundRect(thumbRect, thumbHeight / 2f, thumbHeight / 2f, stroke)

            val iconSize = (h * 0.4f).roundToInt()
            usbIcon.setBounds(0, 0, iconSize, iconSize)
            wifiIcon.setBounds(0, 0, iconSize, iconSize)
            usbIcon.setTint(if (wifi) 0xFF8A9AB0.toInt() else 0xFF1B2433.toInt())
            wifiIcon.setTint(if (wifi) 0xFF1B2433.toInt() else 0xFF8A9AB0.toInt())
            val iconTop = ((h - iconSize) / 2f).roundToInt()
            canvas.save()
            canvas.translate(w * 0.25f - iconSize / 2f, iconTop.toFloat())
            usbIcon.draw(canvas)
            canvas.restore()
            canvas.save()
            canvas.translate(w * 0.75f - iconSize / 2f, iconTop.toFloat())
            wifiIcon.draw(canvas)
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = event.x
                    startThumb = thumbX
                    dragging = true
                    moved = false
                    animator?.cancel()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val dx = event.x - downX
                    if (abs(dx) > width * 0.02f) moved = true
                    if (moved) {
                        thumbX = (startThumb + dx).coerceIn(pad, width - thumbWidth - pad)
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return false
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    val next = if (moved) {
                        thumbX > (width - thumbWidth) / 2f
                    } else {
                        event.x > width / 2f
                    }
                    val wasWifi = wifi
                    settle(next, animate = true)
                    if (next != wasWifi) onChange(next)
                    performClick()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }


    /** Home badge under the settings entry: hidden while every permission is already granted. */
    fun setPermissionBadge(count: Int) {
        permissionButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        permissionButton.text = if (count > 9) "9+" else count.toString()
    }
}
