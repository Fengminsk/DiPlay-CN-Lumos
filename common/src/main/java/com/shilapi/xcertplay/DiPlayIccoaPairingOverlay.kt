// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
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
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.shilapi.xcertplay.host.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The in-page ICCOA pairing card, driven by [IcocaBridge.State]. The web pairing layer is part of
 * the home, so this view is added above the home instead of opening a separate window. Closing
 * stops the engine through the caller.
 */
internal class DiPlayIccoaPairingOverlay(
    context: Context,
    private val night: Boolean,
    private val onClose: () -> Unit,
    private val onProjection: () -> Unit,
    private val onDisconnect: () -> Unit = {},
) : FrameLayout(context) {

    private val card: LinearLayout
    private lateinit var backdrop: ImageView
    private lateinit var veil: View
    private var expanded = false
    private val codeView: TextView
    private val statusView: TextView
    private val disconnectView: TextView
    private val spinner: DiPlaySpinner
    private val accent = 0xFF9FD0FF.toInt()

    init {
        isClickable = true
        isFocusable = true
        val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0f }
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val scrim = View(context).apply { background = DiPlayPairScrimDrawable(); isClickable = true; setOnClickListener { dismiss() } }
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setPadding(dp(24), dp(22), dp(24), dp(22))
            background = DiPlayLiquidGlassDrawable(dp(32).toFloat(), dark = true)
            elevation = dp(16).toFloat()
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF4B9DFF.toInt(), 0xFF1E6CF2.toInt()),
            ).apply { cornerRadius = dp(15).toFloat() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_dp_iccoa)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER })
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(context).apply {
            text = context.getString(R.string.iccoa)
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(13), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        val close = ImageButton(context).apply {
            setImageResource(R.drawable.ic_dp_close)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.cancel)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x52203252)
                    setStroke(dp(1), 0x8CFFFFFF.toInt())
                },
                null,
            )
            setOnClickListener { dismiss() }
            DiPlayGlassTouch.install(this)
        }
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(header, LinearLayout.LayoutParams(-1, dp(48)))

        card.addView(TextView(context).apply {
            text = context.getString(R.string.glass_iccoa_prompt)
            setTextColor(0xEBFFFFFF.toInt())
            textSize = 19f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        val wired = IcocaSettings.usb(context) && !IcocaSettings.wireless(context)
        card.addView(TextView(context).apply {
            text = context.getString(
                if (wired) R.string.glass_iccoa_transport_usb
                else R.string.glass_iccoa_transport_wireless,
            )
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        codeView = TextView(context).apply {
            text = "••••••"
            setTextColor(Color.WHITE)
            textSize = 46f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(10), 0, dp(6))
        }
        card.addView(codeView, LinearLayout.LayoutParams(-1, -2))

        // Closing the card only hides it; disconnecting is an explicit decision.
        disconnectView = TextView(context).apply {
            text = context.getString(R.string.disconnect)
            setTextColor(0xCC9FD0FF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            visibility = GONE
            isClickable = true
            setOnClickListener { onDisconnect() }
        }
        card.addView(disconnectView, LinearLayout.LayoutParams(-1, -2))

        card.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x006FB6FF, 0xFF6FB6FF.toInt(), 0xFFB6DCFF.toInt(), 0xFF6FB6FF.toInt(), 0x006FB6FF),
            )
        }, LinearLayout.LayoutParams(dp(220), dp(3)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(4)
        })

        card.addView(TextView(context).apply {
            text = context.getString(R.string.glass_iccoa_valid)
            setTextColor(0x99FFFFFF.toInt())
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        }
        spinner = DiPlaySpinner(context)
        statusRow.addView(spinner, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        statusView = TextView(context).apply {
            text = context.getString(R.string.glass_iccoa_waiting)
            setTextColor(0xE6FFFFFF.toInt())
            textSize = 15f
        }
        statusRow.addView(statusView, LinearLayout.LayoutParams(-2, -2))
        card.addView(statusRow, LinearLayout.LayoutParams(-1, -2))

        val width = min(dp(365), resources.displayMetrics.widthPixels - dp(48))
        addView(card, LayoutParams(width, -2).apply { gravity = Gravity.CENTER })
        veil = View(context).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            // The card itself has elevation 16; a sibling veil with no elevation is painted
            // underneath it, so the picture only went black after the card finished growing.
            // Elevate the veil above the card so the blackening follows the expansion.
            elevation = dp(17).toFloat()
        }
        addView(veil, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.setOnLongClickListener {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) {
                render(IcocaBridge.State(phase = IcocaBridge.Phase.Connected, pinCode = "373762"))
                true
            } else {
                false
            }
        }
        card.alpha = 0f
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.rotationX = 11f
        card.cameraDistance = 1400f * resources.displayMetrics.density
        card.translationY = dp(56).toFloat()
        alpha = 0f
        post {
            animate().alpha(1f).setDuration(450).start()
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotationX(0f)
                .translationY(0f)
                .setDuration(850)
                .setInterpolator(PathInterpolator(0.18f, 1.18f, 0.3f, 1f))
                .start()
        }
        this.backdrop = backdrop
    }

    fun close() = dismiss()

    fun setBackdrop(bitmap: android.graphics.Bitmap) {
        backdrop.setImageBitmap(bitmap)
        (backdrop.drawable as? android.graphics.drawable.BitmapDrawable)?.isFilterBitmap = true
        backdrop.animate().alpha(1f).setDuration(250).start()
    }

    fun render(state: IcocaBridge.State) {
        codeView.text = state.pinCode ?: "••••••"
        val connected = state.phase == IcocaBridge.Phase.Connected
        disconnectView.visibility = if (connected) View.VISIBLE else View.GONE
        spinner.visibility = if (connected) View.GONE else View.VISIBLE
        val message = when (state.phase) {
            IcocaBridge.Phase.Connected -> context.getString(R.string.glass_iccoa_connected)
            IcocaBridge.Phase.Pairing -> context.getString(R.string.glass_iccoa_pairing)
            IcocaBridge.Phase.WaitingForHotspot -> context.getString(R.string.glass_iccoa_hotspot)
            else -> context.getString(R.string.glass_iccoa_waiting)
        }
        statusView.text = message
        statusView.setTextColor(
            when (state.phase) {
                IcocaBridge.Phase.Connected -> 0xFF6BE0A0.toInt()
                IcocaBridge.Phase.WaitingForHotspot -> 0xFFFFC880.toInt()
                else -> 0xE6FFFFFF.toInt()
            },
        )
        if (connected && !expanded) {
            expanded = true
            postDelayed({ expand() }, 350)
        }
    }

    /** The web's `pair-card.is-expanding`: grow the glass card to the full screen, then black. */
    private fun expand() {
        if (!isAttachedToWindow) return
        val startWidth = card.width
        val startHeight = card.height
        val targetWidth = width
        val targetHeight = height
        if (startWidth <= 0 || startHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            onProjection()
            return
        }
        val startRadius = dp(32).toFloat()
        val glass = card.background as? DiPlayLiquidGlassDrawable
        val content = (0 until card.childCount).map { card.getChildAt(it) }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = PathInterpolator(0.65f, 0f, 0.25f, 1f)
            addUpdateListener {
                val progress = it.animatedValue as Float
                val params = card.layoutParams
                params.width = (startWidth + (targetWidth - startWidth) * progress).toInt()
                params.height = (startHeight + (targetHeight - startHeight) * progress).toInt()
                card.layoutParams = params
                glass?.cornerRadius = startRadius * (1f - progress)
                val contentAlpha = (1f - progress * 3f).coerceAtLeast(0f)
                content.forEach { child -> child.alpha = contentAlpha }
                // The veil is above the card and darkens in lockstep with the expansion, so the
                // screen is already black when the card reaches full size.
                veil.alpha = progress
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onProjection()
                }
            })
            start()
        }
    }

    private fun dismiss() {
        if (expanded) return
        if (!isAttachedToWindow) return
        card.animate()
            .alpha(0f)
            .scaleX(0.93f)
            .scaleY(0.93f)
            .rotationX(6f)
            .translationY(dp(30).toFloat())
            .setDuration(450)
            .setInterpolator(PathInterpolator(0.5f, 0f, 0.75f, 0.35f))
            .withEndAction { onClose() }
            .start()
        animate().alpha(0f).setDuration(450).start()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true
}




