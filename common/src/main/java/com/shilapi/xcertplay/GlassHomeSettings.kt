package com.shilapi.xcertplay

import android.content.Context

/**
 * Lumos glass home: the ICCOA/CarPlay launch surface with the liquid-glass tiles.
 *
 * On by default: this is the Lumos home. The upstream card layout stays available from the
 * same switch under Advanced → experimental display, so both homes stay reachable.
 */
internal object GlassHomeSettings {
    private const val PREFS = "diplay_glass_home"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
