package com.shilapi.xcertplay

import android.content.Context

/** Opt-in to CarsBridge's read-only Lynk 09 instrument feed. */
internal object Lynk09Settings {
    private const val BRIDGE_PACKAGE = "cn.lumos.carsbridge"
    private const val PREFS = "diplay_lynk09"
    private const val KEY_INSTRUMENT_TO_IPHONE = "instrument_to_iphone"

    fun instrumentToIphone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_INSTRUMENT_TO_IPHONE, false)

    fun instrumentToIphoneActive(context: Context): Boolean =
        instrumentToIphone(context) && runCatching {
            context.packageManager.getPackageInfo(BRIDGE_PACKAGE, 0)
        }.isSuccess

    fun setInstrumentToIphone(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_INSTRUMENT_TO_IPHONE, enabled).apply()
    }
}
