package com.shilapi.xcertplay

import android.content.Context

internal object CarPlayNameSettings {
    private const val PREFS = "diplay_carplay_name"
    private const val KEY_USE_HOTSPOT_NAME = "use_hotspot_name"

    fun useHotspotName(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_USE_HOTSPOT_NAME, false)

    fun setUseHotspotName(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_HOTSPOT_NAME, enabled).apply()
    }
}
