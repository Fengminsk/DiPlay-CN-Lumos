package com.diplay.home

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** The standalone Home sample keeps widget prompts inside its own dark glass surface. */
internal object HomeGlassDialog {
    fun choices(activity: Activity, title: String, choices: Array<String>, onChoice: (Int) -> Unit) {
        show(activity, title, null, choices, onChoice)
    }

    fun message(activity: Activity, title: String, message: String) {
        show(activity, title, message, emptyArray(), {})
    }

    private fun show(activity: Activity, title: String, message: String?, choices: Array<String>,
        onChoice: (Int) -> Unit) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
        val root = FrameLayout(activity).apply {
            isClickable = true
            isFocusableInTouchMode = true
        }
        fun dismiss() { (root.parent as? ViewGroup)?.removeView(root) }
        root.addView(View(activity).apply {
            setBackgroundColor(0xC9000712.toInt())
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(-1, -1))

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFA2B405E.toInt(), 0xFA132138.toInt())).apply {
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), 0x8BAAC6E8.toInt())
            }
            elevation = dp(18).toFloat()
            isClickable = true
        }
        card.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(0, 0, 0, dp(12))
        })
        val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        message?.let { body ->
            content.addView(TextView(activity).apply {
                text = body
                setTextColor(0xFFD0DBEA.toInt())
                textSize = 16f
                setPadding(0, dp(8), 0, dp(12))
            })
        }
        choices.forEachIndexed { index, label ->
            content.addView(TextView(activity).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
                isClickable = true
                setOnClickListener { dismiss(); onChoice(index) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        scroll.addView(content)
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        card.addView(TextView(activity).apply {
            text = activity.getString(if (message == null) android.R.string.cancel else android.R.string.ok)
            setTextColor(0xFFACCAFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        val width = min((activity.resources.displayMetrics.widthPixels * 0.74f).toInt(), dp(640))
        root.addView(card, FrameLayout.LayoutParams(width,
            min((activity.resources.displayMetrics.heightPixels * 0.80f).toInt(),
                dp(130) + choices.size.coerceAtMost(7) * dp(55) + if (message != null) dp(160) else 0),
            Gravity.CENTER))
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                dismiss(); true
            } else false
        }
        activity.addContentView(root, ViewGroup.LayoutParams(-1, -1))
        root.requestFocus()
    }
}
