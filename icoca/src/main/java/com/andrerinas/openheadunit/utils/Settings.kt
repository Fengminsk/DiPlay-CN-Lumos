package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.SharedPreferences
import android.location.Location

/**
 * Narrow stand-in for MiAuto's 1449-line `Settings`.
 *
 * Only the members the ICCOA engine actually reads are exposed. Storage keys, defaults and
 * fallback chains are copied verbatim from MiAuto 0.2.0 so the ported code keeps its behaviour;
 * the AAP/decoder/Audio settings that class also carried belong to DiPlay's other engine and are
 * deliberately absent.
 *
 * P0 keeps its own preferences file (same name MiAuto used). P1 maps these onto DiPlay's shared
 * preferences so one settings screen drives both engines.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var nightMode: NightMode
        get() = NightMode.fromInt(prefs.getInt("night-mode", 0)) ?: NightMode.AUTO
        set(value) = prefs.edit().putInt("night-mode", value.value).apply()

    var nightModeManualStart: Int
        get() = prefs.getInt("night-mode-manual-start", 1140) // 19:00
        set(value) = prefs.edit().putInt("night-mode-manual-start", value).apply()

    var nightModeManualEnd: Int
        get() = prefs.getInt("night-mode-manual-end", 420) // 07:00
        set(value) = prefs.edit().putInt("night-mode-manual-end", value).apply()

    var nightModeThresholdLux: Int
        get() = prefs.getInt("night-mode-threshold-lux", 100)
        set(value) = prefs.edit().putInt("night-mode-threshold-lux", value).apply()

    var nightModeThresholdBrightness: Int
        get() = prefs.getInt("night-mode-threshold-brightness", 100)
        set(value) = prefs.edit().putInt("night-mode-threshold-brightness", value).apply()

    /** Whether [lastKnownLocation] was really written, as opposed to the built-in fallback point. */
    val hasUserSunLocation: Boolean
        get() = prefs.getBoolean("last-loc-user-set", false)

    var lastKnownLocation: Location
        get() {
            val location = Location("")
            location.latitude = java.lang.Double.longBitsToDouble(
                prefs.getLong("last-loc-latitude", DEFAULT_SUN_LATITUDE.toRawBits())
            )
            location.longitude = java.lang.Double.longBitsToDouble(
                prefs.getLong("last-loc-longitude", DEFAULT_SUN_LONGITUDE.toRawBits())
            )
            return location
        }
        set(location) {
            prefs.edit()
                .putLong("last-loc-latitude", location.latitude.toRawBits())
                .putLong("last-loc-longitude", location.longitude.toRawBits())
                .putBoolean("last-loc-user-set", true)
                .apply()
        }

    var useFixedSunriseLocation: Boolean
        get() = prefs.getBoolean("use-fixed-sun-location", false)
        set(value) = prefs.edit().putBoolean("use-fixed-sun-location", value).apply()

    var fixedSunriseLatitude: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong("fixed-sun-latitude", DEFAULT_SUN_LATITUDE.toRawBits())
        )
        set(value) = prefs.edit().putLong("fixed-sun-latitude", value.toRawBits()).apply()

    var fixedSunriseLongitude: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong("fixed-sun-longitude", DEFAULT_SUN_LONGITUDE.toRawBits())
        )
        set(value) = prefs.edit().putLong("fixed-sun-longitude", value.toRawBits()).apply()

    var geofenceLocations: List<com.andrerinas.openheadunit.location.GeofenceLocation>
        get() = com.andrerinas.openheadunit.location.GeofenceLocation.listFromJson(
            prefs.getString("geofence-locations", "[]") ?: "[]"
        )
        set(value) = prefs.edit().putString(
            "geofence-locations",
            com.andrerinas.openheadunit.location.GeofenceLocation.listToJson(value)
        ).apply()

    var locationOutsideNight: Boolean
        get() = prefs.getBoolean("location-outside-night", false)
        set(value) = prefs.edit().putBoolean("location-outside-night", value).apply()

    /** Forward the car's fix to the phone for navigation. */
    var useGpsForNavigation: Boolean
        get() = prefs.getBoolean("gps-navigation", true)
        set(value) = prefs.edit().putBoolean("gps-navigation", value).apply()

    /** Older band switch: "ask the P2P layer for a 5 GHz channel". */
    var askFiveGhz: Boolean
        get() = prefs.getBoolean("ask-five-ghz", prefs.getBoolean("p2p-legacy-5ghz", false))
        set(value) = prefs.edit()
            .putBoolean("ask-five-ghz", value)
            .remove("p2p-legacy-5ghz")
            .apply()

    var askFiveGhzUpperBand: Boolean
        get() = prefs.getBoolean("ask-five-ghz-upper", prefs.getBoolean("p2p-legacy-5ghz-upper", false))
        set(value) = prefs.edit()
            .putBoolean("ask-five-ghz-upper", value)
            .remove("p2p-legacy-5ghz-upper")
            .apply()

    var screenOrientation: ScreenOrientation
        get() = ScreenOrientation.fromInt(prefs.getInt(KEY_SCREEN_ORIENTATION, 0)) ?: ScreenOrientation.SYSTEM
        set(orientation) = prefs.edit().putInt(KEY_SCREEN_ORIENTATION, orientation.value).apply()

    var logSource: LogSource
        get() = LogSource.entries.getOrElse(
            prefs.getInt(KEY_LOG_SOURCE, LogSource.LOGCAT.ordinal)
        ) { LogSource.LOGCAT }
        set(value) = prefs.edit().putInt(KEY_LOG_SOURCE, value.ordinal).apply()

    /**
     * Night-mode source. Values are persisted, so they must stay identical to MiAuto's; the
     * engine reads them by number.
     */
    enum class NightMode(val value: Int) {
        AUTO(0),
        DAY(1),
        NIGHT(2),
        MANUAL_TIME(3),
        LIGHT_SENSOR(4),
        SCREEN_BRIGHTNESS(5),
        CAR_SIGNAL(6),
        LOCATION(7);

        companion object {
            private val map = values().associateBy(NightMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    /**
     * Canvas orientation reported to the phone. Six persisted values, three UI choices: the
     * reverse/auto entries only exist so old saved values keep parsing.
     */
    enum class ScreenOrientation(val value: Int) {
        SYSTEM(0),
        AUTO(1),
        LANDSCAPE(2),
        LANDSCAPE_REVERSE(3),
        PORTRAIT(4),
        PORTRAIT_REVERSE(5);

        fun normalizeForUi(): ScreenOrientation = when (this) {
            SYSTEM, AUTO -> SYSTEM
            LANDSCAPE, LANDSCAPE_REVERSE -> LANDSCAPE
            PORTRAIT, PORTRAIT_REVERSE -> PORTRAIT
        }

        companion object {
            private val map = values().associateBy(ScreenOrientation::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class LogSource {
        LOGCAT,
        APPLOG_FILE
    }

    companion object {
        const val PREFS_NAME = "settings"

        /** Built-in sunrise/sunset reference (Chengdu Shuangliu), same as MiAuto's fallback. */
        const val DEFAULT_SUN_LATITUDE = 30.5728
        const val DEFAULT_SUN_LONGITUDE = 103.9235

        private const val KEY_SCREEN_ORIENTATION = "screen-orientation"
        private const val KEY_LOG_SOURCE = "log-source"
    }
}
