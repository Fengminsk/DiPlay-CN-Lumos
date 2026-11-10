// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * In-page glass dialog. Native AlertDialogs open their own window, which makes the car's status
 * bar reappear and uses a completely different visual language. This dialog is just a view on top
 * of the current activity, so the immersive bars stay hidden.
 */
internal class DiPlayDialogBuilder(internal val source: Context) {
    internal val context: Context get() = source

    internal var title: CharSequence? = null
    internal var message: CharSequence? = null
    internal var customView: View? = null
    internal var opaqueGlass = false
    internal var maxWidthDp = 760
    internal var fitIme = false
    internal var items: Array<out CharSequence>? = null
    internal var itemListener: DialogInterface.OnClickListener? = null
    internal var singleItems: Array<out CharSequence>? = null
    internal var singleChecked = -1
    internal var singleListener: DialogInterface.OnClickListener? = null
    internal var positive: ButtonSpec? = null
    internal var negative: ButtonSpec? = null
    internal var neutral: ButtonSpec? = null
    internal var cancelable = true
    internal var canceledOnTouchOutside = true
    internal var onCancel: DialogInterface.OnCancelListener? = null
    internal var onDismiss: DialogInterface.OnDismissListener? = null
    internal var onShow: DialogInterface.OnShowListener? = null

    internal data class ButtonSpec(val label: CharSequence, val listener: DialogInterface.OnClickListener?)

    fun setTitle(text: CharSequence) = apply { title = text }
    fun setTitle(id: Int) = apply { title = source.getString(id) }
    fun setMessage(text: CharSequence) = apply { message = text }
    fun setMessage(id: Int) = apply { message = source.getString(id) }
    fun setView(view: View) = apply { customView = view }
    fun setOpaqueGlass(value: Boolean) = apply { opaqueGlass = value }
    fun setMaxWidthDp(value: Int) = apply { maxWidthDp = value }
    fun setFitIme(value: Boolean) = apply { fitIme = value }
    fun setItems(items: Array<out CharSequence>, listener: DialogInterface.OnClickListener?) =
        apply { this.items = items; itemListener = listener }
    fun setSingleChoiceItems(items: Array<out CharSequence>, checked: Int,
        listener: DialogInterface.OnClickListener?) =
        apply { singleItems = items; singleChecked = checked; singleListener = listener }
    fun setPositiveButton(text: CharSequence, listener: DialogInterface.OnClickListener?) =
        apply { positive = ButtonSpec(text, listener) }
    fun setPositiveButton(id: Int, listener: DialogInterface.OnClickListener?) =
        apply { positive = ButtonSpec(source.getString(id), listener) }
    fun setNegativeButton(text: CharSequence, listener: DialogInterface.OnClickListener?) =
        apply { negative = ButtonSpec(text, listener) }
    fun setNegativeButton(id: Int, listener: DialogInterface.OnClickListener?) =
        apply { negative = ButtonSpec(source.getString(id), listener) }
    fun setNeutralButton(text: CharSequence, listener: DialogInterface.OnClickListener?) =
        apply { neutral = ButtonSpec(text, listener) }
    fun setNeutralButton(id: Int, listener: DialogInterface.OnClickListener?) =
        apply { neutral = ButtonSpec(source.getString(id), listener) }
    fun setCancelable(value: Boolean) = apply { cancelable = value }
    fun setCanceledOnTouchOutside(value: Boolean) = apply { canceledOnTouchOutside = value }
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) = apply { onCancel = listener }
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) = apply { onDismiss = listener }
    fun setOnShowListener(listener: DialogInterface.OnShowListener?) = apply { onShow = listener }

    fun create(): DiPlayDialog = DiPlayDialog(this)
    fun show(): DiPlayDialog = create().also { it.show() }
}

internal class DiPlayDialog internal constructor(
    private val builder: DiPlayDialogBuilder,
) {
    val window: Window? = null
    private val activity: ComponentActivity? = builder.source.findComponentActivity()
    private val night: Boolean = (builder.source as? AppAppearanceOwner)?.currentAppNight
        ?: builder.source.resolveAppNightNow()
    internal val nightForTest: Boolean get() = night
    private val palette = DiPlayPalette.of(night)
    private val buttons = mutableMapOf<Int, Button>()
    private var overlay: FrameLayout? = null
    private var backCallback: OnBackPressedCallback? = null
    private var imeLayoutListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    private var showing = false
    val isShowing: Boolean get() = showing

    companion object {
        private val active = java.util.WeakHashMap<ComponentActivity, MutableList<DiPlayDialog>>()

        /** A page re-render replaces the activity content; put open in-page dialogs back on top. */
        internal fun reattach(activity: ComponentActivity) {
            active[activity]?.toList()?.forEach { it.reattachSelf() }
        }
    }

    private fun reattachSelf() {
        val host = activity ?: return
        val current = overlay ?: return
        if (current.parent == null) {
            host.addContentView(current, ViewGroup.LayoutParams(-1, -1))
            current.alpha = 1f
        }
    }

    fun getButton(which: Int): Button = makeButton(which) ?: Button(activity ?: builder.source).apply { visibility = View.GONE }

    fun setOnShowListener(listener: DialogInterface.OnShowListener?) { builder.onShow = listener }
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) { builder.onCancel = listener }
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) { builder.onDismiss = listener }

    private fun spec(which: Int) = when (which) {
        AlertDialog.BUTTON_POSITIVE -> builder.positive
        AlertDialog.BUTTON_NEGATIVE -> builder.negative
        AlertDialog.BUTTON_NEUTRAL -> builder.neutral
        else -> null
    }

    private fun makeButton(which: Int): Button? {
        val spec = spec(which) ?: return null
        return buttons.getOrPut(which) {
            val host = activity ?: return null
            Button(host).apply {
                text = spec.label
                isAllCaps = false
                textSize = 15f
                setTextColor(if (which == AlertDialog.BUTTON_POSITIVE) palette.onAccent else palette.primaryText)
                background = RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(if (which == AlertDialog.BUTTON_POSITIVE) palette.accent else Color.TRANSPARENT)
                    },
                    null,
                )
                setPadding(dp(18), 0, dp(18), 0)
                setOnClickListener {
                    spec.listener?.onClick(iface, which)
                    dismiss()
                }
            }
        }
    }

    fun show() {
        if (showing) return
        val host = activity ?: return
        showing = true

        val root = FrameLayout(host).apply { isClickable = true }
        val scrim = View(host).apply {
            background = DiPlayPairScrimDrawable()
            isClickable = true
        }
        if (builder.cancelable && builder.canceledOnTouchOutside) {
            scrim.setOnClickListener { cancel() }
        }
        root.addView(scrim, ViewGroup.LayoutParams(-1, -1))

        val card = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(24), dp(26), dp(20))
            background = DiPlayLiquidGlassDrawable(dp(28).toFloat(), dark = night,
                opaque = builder.opaqueGlass)
            elevation = dp(18).toFloat()
            isClickable = true
        }
        val screenW = host.resources.displayMetrics.widthPixels
        val screenH = host.resources.displayMetrics.heightPixels
        val cardWidth = min((screenW * 0.74f).roundToInt(), dp(builder.maxWidthDp))

        val scroll = ScrollView(host).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        builder.title?.let { text ->
            content.addView(TextView(host).apply {
                this.text = text
                setTextColor(palette.primaryText)
                textSize = 22f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, dp(12))
            })
        }
        builder.message?.let { text ->
            content.addView(TextView(host).apply {
                this.text = text
                setTextColor(palette.secondaryText)
                textSize = 15f
                setLineSpacing(dp(4).toFloat(), 1f)
                setPadding(0, 0, 0, dp(14))
            })
        }
        builder.customView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            content.addView(view, ViewGroup.LayoutParams(-1, -2))
        }
        builder.items?.let { labels ->
            labels.forEachIndexed { index, label ->
                content.addView(TextView(host).apply {
                    text = label
                    setTextColor(palette.primaryText)
                    textSize = 16f
                    setPadding(dp(6), dp(14), dp(6), dp(14))
                    isClickable = true
                    setOnClickListener {
                        builder.itemListener?.onClick(iface, index)
                        dismiss()
                    }
                })
            }
        }
        val singleRows = mutableListOf<TextView>()
        builder.singleItems?.let { labels ->
            labels.forEachIndexed { index, label ->
                val row = TextView(host).apply {
                    text = (if (index == builder.singleChecked) "✓  " else "     ") + label
                    setTextColor(if (index == builder.singleChecked) palette.accent else palette.primaryText)
                    textSize = 16f
                    setPadding(dp(6), dp(14), dp(6), dp(14))
                    isClickable = true
                    setOnClickListener {
                        builder.singleChecked = index
                        builder.singleListener?.onClick(iface, index)
                        singleRows.forEachIndexed { rowIndex, rowView ->
                            val selected = rowIndex == index
                            rowView.text = (if (selected) "✓  " else "     ") + labels[rowIndex]
                            rowView.setTextColor(if (selected) palette.accent else palette.primaryText)
                        }
                    }
                }
                singleRows += row
                content.addView(row)
            }
        }

        val buttonRow = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL,
            AlertDialog.BUTTON_POSITIVE).forEach { which ->
            makeButton(which)?.let { button ->
                buttonRow.addView(button, LinearLayout.LayoutParams(-2, dp(44)).apply {
                    marginStart = dp(8)
                })
            }
        }
        if (buttonRow.childCount > 0) card.addView(buttonRow, LinearLayout.LayoutParams(-1, -2))

        val cardLayout = FrameLayout.LayoutParams(cardWidth, -2, Gravity.CENTER)
        root.addView(card, cardLayout)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxCardHeight = (screenH * 0.82f).roundToInt()
        if (card.measuredHeight > maxCardHeight) {
            card.layoutParams = FrameLayout.LayoutParams(cardWidth, maxCardHeight, Gravity.CENTER)
        } else {
            scroll.layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        root.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        host.addContentView(root, ViewGroup.LayoutParams(-1, -1))
        overlay = root
        if (builder.fitIme) {
            val normalHeight = card.layoutParams.height
            var expandedHeight = card.measuredHeight
            var fullWindowHeight = 0
            imeLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
                val visible = Rect()
                root.getWindowVisibleDisplayFrame(visible)
                val location = IntArray(2)
                root.getLocationOnScreen(location)
                val availableHeight = (visible.bottom - location[1]).coerceIn(0, root.height)
                if (availableHeight == 0) return@OnGlobalLayoutListener
                // Some windows resize for the IME; others retain their size and clip the visible frame.
                fullWindowHeight = maxOf(fullWindowHeight, root.height)
                val keyboardVisible = fullWindowHeight - availableHeight > dp(120)
                val params = card.layoutParams as FrameLayout.LayoutParams
                if (!keyboardVisible && params.height == normalHeight && card.height > 0) {
                    expandedHeight = card.height
                }
                val desiredHeight = if (!keyboardVisible) normalHeight
                    else min(expandedHeight, (availableHeight - dp(24)).coerceAtLeast(1))
                val desiredGravity = if (keyboardVisible) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
                val desiredTop = if (keyboardVisible) ((availableHeight - desiredHeight) / 2).coerceAtLeast(0) else 0
                if (params.topMargin != desiredTop || params.bottomMargin != 0 ||
                    params.height != desiredHeight || params.gravity != desiredGravity) {
                    params.topMargin = desiredTop
                    params.bottomMargin = 0
                    params.height = desiredHeight
                    params.gravity = desiredGravity
                    card.layoutParams = params
                }
            }
            root.viewTreeObserver.addOnGlobalLayoutListener(imeLayoutListener)
        }
        active.getOrPut(host) { mutableListOf() }.add(this)
        root.animate().alpha(1f).setDuration(180).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(220).start()

        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (builder.cancelable) cancel()
            }
        }.also { host.onBackPressedDispatcher.addCallback(host, it) }
        builder.onShow?.onShow(iface)
    }

    private val iface: DialogInterface = object : DialogInterface {
        override fun cancel() {}
        override fun dismiss() { this@DiPlayDialog.dismiss() }
    }

    private fun cancel() {
        builder.onCancel?.onCancel(iface)
        dismiss()
    }

    fun dismiss() {
        if (!showing) return
        showing = false
        backCallback?.let { it.isEnabled = false; it.remove() }
        backCallback = null
        val current = overlay ?: return
        overlay = null
        imeLayoutListener?.let { current.viewTreeObserver.removeOnGlobalLayoutListener(it) }
        imeLayoutListener = null
        activity?.let { active[it]?.remove(this) }
        current.animate().alpha(0f).setDuration(160).withEndAction {
            (current.parent as? ViewGroup)?.removeView(current)
            builder.onDismiss?.onDismiss(iface)
        }.start()
    }

    private fun dp(value: Int): Int = (value * (activity?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}




