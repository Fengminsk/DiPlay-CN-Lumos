package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import kotlin.math.roundToInt

/**
 * Ambient light read/write for ECARX/Geely head units.
 *
 * All calls are synchronous and must run on the caller's lamp executor: the controller already has
 * one scheduler, and a second thread here would only add races around the restore snapshot. The
 * AdaptAPI classes live on the boot classpath, so they are reached by reflection and a missing SDK
 * simply makes [present] false.
 */
internal class EcarxAmbientLightClient(context: Context) {
    /** What the lamps looked like before DiPlay took them over. */
    data class Snapshot(
        val switch: Int?,
        val mode: Int?,
        val effect: Int?,
        val colors: Map<Int, Int>,
        val intensities: Map<Int, Int>,
    )

    private val app = context.applicationContext
    private val function: Any? = runCatching {
        val car = Class.forName(CAR).getMethod("create", Context::class.java).invoke(null, app)
        Class.forName(ICAR).getMethod("getICarFunction").invoke(car)
    }.getOrElse {
        Log.i(TAG, "ECARX car function unavailable: ${it.javaClass.simpleName}")
        null
    }
    private val type: Class<*>? = runCatching { Class.forName(ICAR_FUNCTION) }.getOrNull()
    private val zones: List<Int> by lazy {
        runCatching {
            (invoke("getSupportedFunctionZones", EcarxAmbientLightPolicy.FUNC_COLOR_SET) as? IntArray)
                ?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun present(): Boolean = function != null && type != null

    /** Colour control must be published; brightness is best effort on firmwares that omit it. */
    fun supported(): Boolean {
        if (!present()) return false
        val zone = EcarxAmbientLightPolicy.zoneForArea(zones, 3)
        if (!isActive(EcarxAmbientLightPolicy.FUNC_COLOR_SET, zone)) return false
        val mode = intValue(EcarxAmbientLightPolicy.FUNC_MAIN_COLOR, EcarxAmbientLightPolicy.ZONE_NONE)
        // A readable main-colour mode is what lets COLOR_SET take effect.
        return mode != null
    }

    fun zoneForArea(area: Int): Int = EcarxAmbientLightPolicy.zoneForArea(zones, area)

    /** Reads the pre-control state; null when the car publishes no colour value at all. */
    fun read(): Snapshot? {
        if (!present()) return null
        val switch = intValue(EcarxAmbientLightPolicy.FUNC_ATMOSPHERE_SWITCH, EcarxAmbientLightPolicy.ZONE_NONE)
        val mode = intValue(EcarxAmbientLightPolicy.FUNC_MAIN_COLOR, EcarxAmbientLightPolicy.ZONE_NONE)
        val effect = intValue(EcarxAmbientLightPolicy.FUNC_EFFECT_SET, EcarxAmbientLightPolicy.ZONE_NONE)
        val targets = zones.ifEmpty {
            listOf(EcarxAmbientLightPolicy.zoneForArea(emptyList(), 3))
        }
        val colors = LinkedHashMap<Int, Int>()
        val intensities = LinkedHashMap<Int, Int>()
        for (zone in targets) {
            intValue(EcarxAmbientLightPolicy.FUNC_COLOR_SET, zone)
                ?.takeIf(EcarxAmbientLightPolicy::validColor)?.let { colors[zone] = it }
            floatValue(EcarxAmbientLightPolicy.FUNC_INTENSITY_SET, zone)
                ?.takeIf { it.isFinite() }?.let { intensities[zone] = it.roundToInt() }
        }
        if (colors.isEmpty()) return null
        return Snapshot(switch, mode, effect, colors, intensities)
    }

    /**
     * Turns the lamps on if they were off and puts the main colour into custom-colour mode, then
     * returns the original snapshot for [restore]. [seed] keeps an earlier original when a new
     * phrase starts after an idle hand-back.
     */
    fun begin(seed: Snapshot? = null): Snapshot? {
        val original = seed ?: read() ?: return null
        if (original.switch == 0) {
            writeInt(EcarxAmbientLightPolicy.FUNC_ATMOSPHERE_SWITCH, EcarxAmbientLightPolicy.ZONE_NONE, 1)
        }
        if (original.mode != null && original.mode != EcarxAmbientLightPolicy.MODE_SET_COLOR) {
            writeInt(EcarxAmbientLightPolicy.FUNC_MAIN_COLOR, EcarxAmbientLightPolicy.ZONE_NONE,
                EcarxAmbientLightPolicy.MODE_SET_COLOR)
        }
        // Solid colour: a running gradient/breathe effect would animate over every write.
        if (original.effect != null && original.effect != EcarxAmbientLightPolicy.EFFECT_SOLID) {
            writeInt(EcarxAmbientLightPolicy.FUNC_EFFECT_SET, EcarxAmbientLightPolicy.ZONE_NONE,
                EcarxAmbientLightPolicy.EFFECT_SOLID)
        }
        return original
    }

    /** Writes one zone. Returns false only when the colour itself could not be written. */
    fun apply(zone: Int, rgb: Int, brightnessPercent: Int): Boolean {
        if (!present() || !EcarxAmbientLightPolicy.validColor(rgb)) return false
        val colorOk = writeInt(EcarxAmbientLightPolicy.FUNC_COLOR_SET, zone, rgb) ||
            (zone != EcarxAmbientLightPolicy.ZONE_NONE &&
                writeInt(EcarxAmbientLightPolicy.FUNC_COLOR_SET, EcarxAmbientLightPolicy.ZONE_NONE, rgb))
        if (!colorOk) return false
        val level = brightnessPercent.coerceIn(
            EcarxAmbientLightPolicy.MIN_BRIGHTNESS, EcarxAmbientLightPolicy.MAX_BRIGHTNESS,
        ).toFloat()
        if (!writeFloat(EcarxAmbientLightPolicy.FUNC_INTENSITY_SET, zone, level) &&
            zone != EcarxAmbientLightPolicy.ZONE_NONE
        ) {
            writeFloat(EcarxAmbientLightPolicy.FUNC_INTENSITY_SET, EcarxAmbientLightPolicy.ZONE_NONE, level)
        }
        return true
    }

    /** Best-effort restoration of the exact pre-control colours, brightness, mode and switch. */
    fun restore(snapshot: Snapshot) {
        if (!present()) return
        for ((zone, color) in snapshot.colors) {
            writeInt(EcarxAmbientLightPolicy.FUNC_COLOR_SET, zone, color)
        }
        for ((zone, intensity) in snapshot.intensities) {
            writeFloat(EcarxAmbientLightPolicy.FUNC_INTENSITY_SET, zone, intensity.toFloat())
        }
        snapshot.effect?.let {
            writeInt(EcarxAmbientLightPolicy.FUNC_EFFECT_SET, EcarxAmbientLightPolicy.ZONE_NONE, it)
        }
        snapshot.mode?.let {
            writeInt(EcarxAmbientLightPolicy.FUNC_MAIN_COLOR, EcarxAmbientLightPolicy.ZONE_NONE, it)
        }
        if (snapshot.switch == 0) {
            writeInt(EcarxAmbientLightPolicy.FUNC_ATMOSPHERE_SWITCH, EcarxAmbientLightPolicy.ZONE_NONE, 0)
        }
    }

    private fun isActive(id: Int, zone: Int): Boolean = runCatching {
        invoke("isFunctionSupported", id, zone)?.toString() == "active"
    }.getOrElse {
        runCatching { invoke("isFunctionSupported", id)?.toString() == "active" }.getOrDefault(false)
    }

    private fun intValue(id: Int, zone: Int): Int? = runCatching {
        (invoke("getFunctionValue", id, zone) as? Number)?.toInt()
    }.getOrElse {
        runCatching { (invoke("getFunctionValue", id) as? Number)?.toInt() }.getOrNull()
    }

    private fun floatValue(id: Int, zone: Int): Float? = runCatching {
        (invoke("getCustomizeFunctionValue", id, zone) as? Number)?.toFloat()
    }.getOrElse {
        runCatching { (invoke("getCustomizeFunctionValue", id) as? Number)?.toFloat() }.getOrNull()
    }

    private fun writeInt(id: Int, zone: Int, value: Int): Boolean = runCatching {
        invoke("setFunctionValue", id, zone, value) as? Boolean ?: true
    }.getOrElse {
        runCatching { invoke("setFunctionValue", id, value) as? Boolean ?: true }.getOrDefault(false)
    }

    private fun writeFloat(id: Int, zone: Int, value: Float): Boolean = runCatching {
        invoke("setCustomizeFunctionValue", id, zone, value) as? Boolean ?: true
    }.getOrElse {
        runCatching { invoke("setCustomizeFunctionValue", id, value) as? Boolean ?: true }.getOrDefault(false)
    }

    /**
     * Matches the declared parameter types, not the runtime class, so both SDK DEX layouts and
     * wrapper implementations resolve to the same interface method.
     */
    private fun invoke(name: String, vararg args: Any): Any? {
        val contract = type ?: return null
        val owner = function ?: return null
        val parameters = args.map {
            when (it) {
                is Float -> Float::class.javaPrimitiveType
                is Int -> Int::class.javaPrimitiveType
                else -> it.javaClass
            }
        }.toTypedArray()
        return contract.getMethod(name, *parameters).invoke(owner, *args)
    }

    private companion object {
        const val TAG = "DiPlay-EcarxAmbient"
        const val CAR = "com.ecarx.xui.adaptapi.car.Car"
        const val ICAR = "com.ecarx.xui.adaptapi.car.ICar"
        const val ICAR_FUNCTION = "com.ecarx.xui.adaptapi.car.base.ICarFunction"
    }
}
