package com.shilapi.xcertplay.link

import android.content.Context

/** Persists the driver's projection-source choice. Defaults to CarPlay so upgrades are unchanged. */
object PhoneLinkSettings {
    private const val PREFS = "diplay_phone_link"
    private const val KEY_SOURCE = "projection_source"

    fun loadSource(context: Context): ProjectionSource =
        ProjectionSource.entries.getOrElse(
            prefs(context).getInt(KEY_SOURCE, ProjectionSource.CARPLAY.ordinal),
        ) { ProjectionSource.CARPLAY }

    fun saveSource(context: Context, source: ProjectionSource) {
        prefs(context).edit().putInt(KEY_SOURCE, source.ordinal).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
