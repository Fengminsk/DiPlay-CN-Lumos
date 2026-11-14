package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.min
import kotlin.math.roundToInt

/** Home-level CarPlay connection card, using the same glass surface and motion as ICCOA. */
internal class DiPlayCarPlayPairingOverlay(
    context: Context,
    private val onClose: () -> Unit,
    private val onProjection: () -> Unit,
    private val onResetWifi: () -> Unit,
    private val onDisconnect: () -> Unit,
) : FrameLayout(context) {
    private val backdrop: ImageView
    private val card: LinearLayout
    private val veil: View
    private val status: TextView
    private val spinner: DiPlaySpinner
    private val disconnect: TextView
    private val resetWifi: TextView
    private var expanded = false

    init {
        isClickable = true
        isFocusable = true
        backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
        addView(backdrop, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = DiPlayPairScrimDrawable()
            isClickable = true
            setOnClickListener { dismiss() }
        }, LayoutParams(-1, -1))
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(22), dp(24), dp(24))
            background = DiPlayLiquidGlassDrawable(dp(32).toFloat(), dark = true)
            elevation = dp(16).toFloat()
            isClickable = true
        }
        val header = LinearLayout(context).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        val close = ImageButton(context).apply {
            setImageResource(R.drawable.ic_dp_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = context.getString(R.string.cancel)
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x52203252); setStroke(dp(1), 0x8CFFFFFF.toInt()) }, null)
            setOnClickListener { dismiss() }
            DiPlayGlassTouch.install(this)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(header, LinearLayout.LayoutParams(-1, dp(40)))
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(20))
        }
        spinner = DiPlaySpinner(context)
        row.addView(spinner, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        status = TextView(context).apply {
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 19f
            maxLines = 3
            gravity = Gravity.CENTER
        }
        row.addView(status, LinearLayout.LayoutParams(-2, -2))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        resetWifi = TextView(context).apply {
            text = context.getString(R.string.reset_carplay_wi_fi)
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = DiPlayLiquidGlassDrawable(dp(18).toFloat(), dark = true)
            setOnClickListener { onResetWifi() }
            DiPlayGlassTouch.install(this)
        }
        card.addView(resetWifi, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        disconnect = TextView(context).apply {
            text = context.getString(R.string.disconnect)
            setTextColor(0xCC9FD0FF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
            setOnClickListener { onDisconnect() }
        }
        card.addView(disconnect, LinearLayout.LayoutParams(-1, -2))
        addView(card, LayoutParams(min(dp(365), resources.displayMetrics.widthPixels - dp(48)), -2, Gravity.CENTER))
        veil = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            elevation = dp(17).toFloat()
        }
        addView(veil, LayoutParams(-1, -1))
        card.alpha = 0f
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.translationY = dp(56).toFloat()
        alpha = 0f
        post {
            animate().alpha(1f).setDuration(450).start()
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(850).setInterpolator(PathInterpolator(0.18f, 1.18f, 0.3f, 1f)).start()
        }
        render(CarPlayLiveSession.value())
    }

    fun setBackdrop(bitmap: android.graphics.Bitmap) {
        backdrop.setImageBitmap(bitmap)
        (backdrop.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
        backdrop.animate().alpha(1f).setDuration(250).start()
    }

    fun render(state: CarPlayLiveSession.State) {
        status.text = state.message.ifBlank { context.getString(R.string.preparing_mfi_authentication) }
        spinner.visibility = if (state.phase == CarPlayLiveSession.Phase.Streaming || state.wifiResetRequired) GONE else VISIBLE
        resetWifi.visibility = if (state.wifiResetRequired) VISIBLE else GONE
        disconnect.visibility = if (state.phase == CarPlayLiveSession.Phase.Idle) GONE else VISIBLE
        if (state.phase == CarPlayLiveSession.Phase.Streaming && !expanded) {
            expanded = true
            postDelayed(::expand, 350)
        }
    }

    fun close() = dismiss()

    private fun expand() {
        if (!isAttachedToWindow) return
        val startWidth = card.width
        val startHeight = card.height
        if (startWidth <= 0 || startHeight <= 0 || width <= 0 || height <= 0) {
            onProjection()
            return
        }
        val glass = card.background as? DiPlayLiquidGlassDrawable
        val content = (0 until card.childCount).map(card::getChildAt)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = PathInterpolator(0.65f, 0f, 0.25f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                card.layoutParams = card.layoutParams.apply {
                    width = (startWidth + (this@DiPlayCarPlayPairingOverlay.width - startWidth) * progress).toInt()
                    height = (startHeight + (this@DiPlayCarPlayPairingOverlay.height - startHeight) * progress).toInt()
                }
                glass?.cornerRadius = dp(32) * (1f - progress)
                content.forEach { child -> child.alpha = (1f - progress * 3f).coerceAtLeast(0f) }
                veil.alpha = progress
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { onProjection() }
            })
            start()
        }
    }

    private fun dismiss() {
        if (expanded || !isAttachedToWindow) return
        card.animate().alpha(0f).scaleX(0.93f).scaleY(0.93f).translationY(dp(30).toFloat())
            .setDuration(450).setInterpolator(PathInterpolator(0.5f, 0f, 0.75f, 0.35f))
            .withEndAction { onClose() }.start()
        animate().alpha(0f).setDuration(450).start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
