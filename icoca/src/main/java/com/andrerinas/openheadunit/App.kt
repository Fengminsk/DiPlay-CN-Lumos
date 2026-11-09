package com.andrerinas.openheadunit

import android.content.Context
import com.andrerinas.openheadunit.utils.Settings

/**
 * Minimal stand-in for MiAuto's Application object.
 *
 * The ported location feed reads `App.provide(context).settings`; DiPlay has its own Application
 * class, so this only carries the single accessor the engine needs.
 */
object App {

    @Volatile
    private var instance: Holder? = null

    fun provide(context: Context): Holder =
        instance ?: synchronized(this) {
            instance ?: Holder(Settings(context.applicationContext)).also { instance = it }
        }

    class Holder(val settings: Settings)
}
