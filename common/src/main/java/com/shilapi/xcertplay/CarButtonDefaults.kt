package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import androidx.annotation.RawRes
import com.shilapi.xcertplay.host.R

/** Defaults for the app tile that returns from CarPlay to the head unit. */
internal object CarButtonDefaults {
    fun isByd(context: Context): Boolean = CarHotspotSetup.isBydHeadUnit(context) ||
        listOf(Build.BRAND, Build.MANUFACTURER).any { it.contains("BYD", ignoreCase = true) }

    fun label(context: Context): String =
        if (isByd(context)) AirPlayPersistence.DEFAULT_OEM_LABEL
        else context.getString(R.string.car_button_return_vehicle)

    @RawRes fun iconResource(context: Context): Int =
        if (isByd(context)) R.raw.ic_car_home else R.raw.ic_return_vehicle

    fun resolveLabel(context: Context, saved: String?): String {
        val custom = saved?.takeIf { it.isNotBlank() }
        // Earlier versions stored BYD as the default even on other head units.
        return if (custom == null || (!isByd(context) &&
                custom.trim().equals(AirPlayPersistence.DEFAULT_OEM_LABEL, ignoreCase = true))) {
            label(context)
        } else {
            custom
        }
    }
}
