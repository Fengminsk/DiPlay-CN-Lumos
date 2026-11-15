package com.shilapi.xcertplay

import android.content.Context

/**
 * Controls the one-time USB auto-confirm offer independently of the process lifetime.
 *
 * The offer is shown on the first two cold starts only. Starting with the third, the
 * permission badge/marker in the UI is enough; the full dialog must not cover the home
 * screen on every launch. A fresh install clears SharedPreferences, so the count starts
 * from zero again. The explicit "Set up USB and map permissions" button never goes
 * through this gate.
 */
internal object UsbAutoConfirmPrompt {

    private const val PREFS = "usb_auto_confirm_prompt"
    private const val KEY_COLD_STARTS = "cold_starts"
    private const val PROMPT_LIMIT = 2

    /** Records one cold start and returns whether the startup dialog may be shown. */
    fun shouldAskOnLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(KEY_COLD_STARTS, 0) + 1
        prefs.edit().putInt(KEY_COLD_STARTS, count).apply()
        return count <= PROMPT_LIMIT
    }
}
