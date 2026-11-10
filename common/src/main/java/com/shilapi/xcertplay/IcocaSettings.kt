// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.content.Context

/**
 * Preferences for the ICCOA CarLink category. The connection mode, hotspot and 5 GHz values are
 * read by the engine through [com.miauto.app.ApReusePrefs] and the MiAuto Settings; the remaining
 * CarLink options are kept here so the ICCOA group is independent from DiPlay/CarPlay settings.
 */
internal object IcocaSettings {

    private const val PREFS = "diplay_iccoa"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wireless(context: Context): Boolean = prefs(context).getBoolean("wireless", true)
    fun setWireless(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("wireless", value).apply()

    fun usb(context: Context): Boolean = prefs(context).getBoolean("usb", false)
    fun setUsb(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("usb", value).apply()

    fun ble(context: Context): Boolean = prefs(context).getBoolean("ble", true)
    fun setBle(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("ble", value).apply()

    fun autoConnect(context: Context): Boolean = prefs(context).getBoolean("auto_connect", true)
    fun setAutoConnect(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("auto_connect", value).apply()

    /** Index into the resolution labels (0 = 480p … 4 = 2160p). */
    fun resolution(context: Context): Int = prefs(context).getInt("resolution", 2)
    fun setResolution(context: Context, value: Int) =
        prefs(context).edit().putInt("resolution", value).apply()

    /** Index into the codec labels (0 = Auto, 1 = H.264, 2 = H.265). */
    fun codec(context: Context): Int = prefs(context).getInt("codec", 0)
    fun setCodec(context: Context, value: Int) =
        prefs(context).edit().putInt("codec", value).apply()

    /** Index into the DPI labels (0 = Auto, 1 = 160 … 4 = 480). */
    fun dpi(context: Context): Int = prefs(context).getInt("dpi", 0)
    fun setDpi(context: Context, value: Int) =
        prefs(context).edit().putInt("dpi", value).apply()

    fun stretch(context: Context): Boolean = prefs(context).getBoolean("stretch", false)
    fun setStretch(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("stretch", value).apply()

    /** Index into the sample-rate labels (0 = 8 kHz … 5 = 48 kHz). */
    fun micRate(context: Context): Int = prefs(context).getInt("mic_rate", 1)
    fun setMicRate(context: Context, value: Int) =
        prefs(context).edit().putInt("mic_rate", value).apply()

    fun micEcho(context: Context): Boolean = prefs(context).getBoolean("mic_echo", false)
    fun setMicEcho(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("mic_echo", value).apply()

    fun micNoise(context: Context): Boolean = prefs(context).getBoolean("mic_noise", false)
    fun setMicNoise(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("mic_noise", value).apply()

    fun touch(context: Context): Boolean = prefs(context).getBoolean("touch", true)
    fun setTouch(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("touch", value).apply()

    fun hud(context: Context): Boolean = prefs(context).getBoolean("hud", false)
    fun setHud(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("hud", value).apply()
}
