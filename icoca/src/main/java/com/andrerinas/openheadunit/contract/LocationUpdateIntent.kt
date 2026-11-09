package com.andrerinas.openheadunit.contract

import android.content.Intent
import android.location.Location
import android.location.LocationManager

/**
 * Location fix hand-off inside the app process.
 *
 * MiAuto's original broadcasts under the legacy OpenHeadUnit package name; DiPlay keeps the
 * broadcast local (`setPackage(context.packageName)` at the call site), so only the extra key
 * has to match.
 */
class LocationUpdateIntent(location: Location) : Intent(ACTION) {
    init {
        putExtra(LocationManager.KEY_LOCATION_CHANGED, location)
    }

    companion object {
        const val ACTION = "com.shilapi.xcertplay.LOCATION_UPDATE"

        fun extractLocation(intent: Intent): Location? =
            intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
    }
}
