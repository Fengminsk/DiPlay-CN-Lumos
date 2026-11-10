package com.shilapi.xcertplay.hud

/**
 * ECARX/Geely interior ambient light through the AdaptAPI car-function surface.
 *
 * The IDs below come from the head unit's own settings APK and the AdaptAPI SDK:
 * `SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR` selects the colour source, `..._COLOR_SET` takes a
 * 24-bit RGB value per zone and `..._INTENSITY_SET` takes a percentage as a float.
 * They are not public Android properties, so the client reaches them by reflection.
 */
internal object EcarxAmbientLightPolicy {
    const val FUNC_MAIN_COLOR = 537526784
    const val FUNC_COLOR_SET = 537528576
    const val FUNC_INTENSITY_SET = 704708864
    const val FUNC_ATMOSPHERE_SWITCH = 553979904
    const val FUNC_EFFECT_SET = 705167616

    /** `SETTING_FUNC_AMBIENCE_LIGHT_MAINCOLOR` value that lets `COLOR_SET` drive the lamps. */
    const val MODE_SET_COLOR = 537526787

    /** Solid colour; leaving the car's gradient/breathe effect running fights every colour write. */
    const val EFFECT_SOLID = 705167617

    const val ZONE_ALL = 537526528
    const val ZONE_FRONT = 537526529
    const val ZONE_HEADREST = 537526530
    const val ZONE_REAR = 537526531

    /** Zone-less function id, used on head units whose SDK registers only one zone. */
    const val ZONE_NONE = Int.MIN_VALUE

    /**
     * A zero setting is the driver's "lowest" choice, not "off": the lamps stay faintly lit so the
     * car still looks alive when a track ends.
     */
    const val MIN_BRIGHTNESS = 8
    const val MAX_BRIGHTNESS = 100

    fun zoneForArea(supported: List<Int>, area: Int): Int {
        val preferred = when (area) {
            1 -> ZONE_FRONT
            2 -> ZONE_REAR
            else -> ZONE_ALL
        }
        return when {
            supported.isEmpty() -> ZONE_NONE
            preferred in supported -> preferred
            ZONE_ALL in supported -> ZONE_ALL
            else -> supported.first()
        }
    }

    /** The saved 0..6 ceiling (shared with the BYD card) as an ECARX percentage. */
    fun ceilingPercent(level: Int): Int {
        if (level <= 0) return MIN_BRIGHTNESS
        return (level * MAX_BRIGHTNESS / 6).coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
    }

    /** Day and night keep separate ceilings; the caller decides which one is active. */
    fun brightnessLevel(day: Int, night: Int, isNight: Boolean): Int =
        (if (isNight) night else day).coerceIn(0, 6)

    fun validColor(rgb: Int): Boolean = rgb in 0..0xFFFFFF

    fun validBrightness(percent: Int): Boolean = percent in 0..MAX_BRIGHTNESS
}
