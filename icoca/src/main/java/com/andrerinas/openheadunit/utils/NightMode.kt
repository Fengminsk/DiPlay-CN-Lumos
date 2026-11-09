package com.andrerinas.openheadunit.utils

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class NightMode(
    private val settings: Settings,
    val hasGPSLocation: Boolean,
    /**
     * 现读坐标用的 Context（[NightSignals.sunriseReference] 需要它）。
     *
     * <p><b>必填</b>（原来是可空默认 null）：漏传会让 AUTO / 按区域一路判成"算不出来"而回退深色，
     * 而坐标策略本身已经保证"最差也有内置成都双流"，那条回退路径就成了唯一的静默死角。
     * 现在由编译器拦住，新增调用点不可能再漏。
     */
    private val sunContext: Context
) {
    private val calculator = NightModeCalculator(settings, sunContext)
    var currentLux: Float = -1f
    var currentBrightness: Int = -1

    /**
     * 当前的昼夜（Android Auto 的 NightModeEvent 与投屏页单色滤镜用）。
     *
     * <p>判定与两条 Manager 链完全同源（[NightSource]），不再在这里重复一套 when 分支 ——
     * 原来这一份是第三份实现，任何一路漏改都会让"设置生效了但滤镜没跟"。
     * 算不出来时回退深色（[NightSource.fallbackNight]），不再谎报白天。
     */
    var current: Boolean = false
        get() {
            val result = NightSource.decide(settings.nightMode.value, buildRaw())
            return if (result.known) result.isNight else NightSource.fallbackNight()
        }

    /**
     * 这一路在当前机器上**算不算得出来**。
     *
     * <p>[current] 是 Boolean，算不出来时只能给一个值，调用方无法区分
     * "确实是白天"与"根本没有读数"。需要区分的调用方（两个 Manager、CarWith 转发）
     * 直接用 [NightSource]；这里保留一份等价判定是为了让单色滤镜这类只用 `current`
     * 的地方至少有据可查。
     */
    val known: Boolean
        get() = NightSource.decide(settings.nightMode.value, buildRaw()).known

    /** 一次判定需要的全部原始读数（[current] 与 [known] 共用，口径不可能再分叉）。 */
    private fun buildRaw(): NightSource.Raw {
        val raw = NightSource.Raw()
        raw.lux = currentLux
        raw.brightness = currentBrightness
        raw.lastNight = null
        raw.thresholdLux = settings.nightModeThresholdLux
        raw.thresholdBrightness = settings.nightModeThresholdBrightness
        raw.manualStart = settings.nightModeManualStart
        raw.manualEnd = settings.nightModeManualEnd
        raw.nowMinutes = NightSignals.nowMinutes()
        // 坐标的取值规则只在 NightModeCalculator 一处（SunriseLocationPolicy），
        // 这里既拿"算出的昼夜"也拿"有没有坐标"，两件事同源，不会出现
        // 「判定说能用、计算却用了另一个点」。
        raw.sunsetNight = calculator.current
        raw.locationUsable = calculator.hasReference
        raw.carMode = NightSignals.readCarMode(sunContext)
        raw.locationNight = NightSignals.geofenceNight(sunContext, settings)
        return raw
    }

    override fun toString(): String {
        return when (settings.nightMode){
            Settings.NightMode.AUTO -> "NightMode: ${calculator.current}"
            Settings.NightMode.DAY -> "NightMode: DAY"
            Settings.NightMode.NIGHT -> "NightMode: NIGHT"
            Settings.NightMode.MANUAL_TIME -> {
                val startH = settings.nightModeManualStart / 60
                val startM = settings.nightModeManualStart % 60
                val endH = settings.nightModeManualEnd / 60
                val endM = settings.nightModeManualEnd % 60
                "NightMode: Manual (%02d:%02d - %02d:%02d)".format(startH, startM, endH, endM)
            }
            Settings.NightMode.LIGHT_SENSOR -> "NightMode: Sensor ($currentLux < ${settings.nightModeThresholdLux})"
            Settings.NightMode.SCREEN_BRIGHTNESS -> "NightMode: Brightness ($currentBrightness < ${settings.nightModeThresholdBrightness})"
            Settings.NightMode.CAR_SIGNAL -> "NightMode: Car ILL+ signal"
            Settings.NightMode.LOCATION -> "NightMode: Location (by area)"
        }
    }

    fun getCalculationInfo(): String {
        return calculator.getCalculationInfo()
    }

    companion object {
        /**
         * 用一个**已知坐标**算「此刻是不是夜间」。
         *
         * <p>给那些已经自己取到坐标的调用点用（CarWith 转发每 5 秒判一次）：它们只需要一个布尔，
         * 不该为此 new 一整套 calculator（连带 [SimpleDateFormat]），也不该再取一次坐标 ——
         * 那样日志里的点与实际参与计算的点就有机会不是同一个。
         */
        @JvmStatic
        fun sunriseNightAt(latitude: Double, longitude: Double): Boolean {
            val calc = TwilightCalculator()
            calc.calculateTwilight(System.currentTimeMillis(), latitude, longitude)
            return calc.mState == TwilightCalculator.NIGHT
        }
    }
}

private class NightModeCalculator(private val settings: Settings, private val context: Context) {
    private val twilightCalculator = TwilightCalculator()
    private val format = SimpleDateFormat("HH:mm", Locale.US)

    // 坐标只在这一处决定，避免"判定说能用、计算却用了另一个点"。
    // 取值顺序与 SunriseLocationPolicy 一致：固定点 → 任意年龄定位 → 曾保存的位置 → 内置成都双流。
    //
    // 必须是**每次现读**（getter），不能存成构造期快照：车机没有 GPS 广播时（比如本机没有
    // GNSS、只有网络 last-known）那个快照整场都不会刷新，于是"日志里写的坐标来源"和
    // "真正算日落的坐标"会分叉 —— 正是本次要消灭的那类"安静地错"。
    private val reference: SunriseLocationPolicy.Ref?
        get() = NightSignals.sunriseReference(context, settings)

    /** 这一次到底有没有可用坐标（没有时上层应判成"算不出来"）。 */
    val hasReference: Boolean get() = reference != null

    /**
     * 按当前时刻与**这一次**的坐标算一次（坐标只取一次，太阳时刻与昼夜来自同一次计算）。
     *
     * <p>没有可用坐标时仍给一个值（历史 AA 行为），但上层必须用 [hasReference] 挡住 ——
     * [NightSource] 的 MODE_AUTO 正是这样做的，缺坐标时判成"算不出来"而不是拿默认点蒙一个时刻。
     */
    private fun calculateNow() {
        val time = Calendar.getInstance().time
        val ref = reference
        twilightCalculator.calculateTwilight(
            time.time,
            ref?.latitude ?: settings.lastKnownLocation.latitude,
            ref?.longitude ?: settings.lastKnownLocation.longitude
        )
    }

    fun getCalculationInfo(): String {
        calculateNow()
        val sunrise = if (twilightCalculator.mSunrise > 0) format.format(Date(twilightCalculator.mSunrise)) else "--:--"
        val sunset = if (twilightCalculator.mSunset > 0) format.format(Date(twilightCalculator.mSunset)) else "--:--"
        return "$sunrise - $sunset"
    }

    var current: Boolean = false
        get()  {
            calculateNow()
            return twilightCalculator.mState == TwilightCalculator.NIGHT
        }

    override fun toString(): String {
        val sunrise = if (twilightCalculator.mSunrise > 0) format.format(Date(twilightCalculator.mSunrise)) else "-1"
        val sunset = if (twilightCalculator.mSunset > 0) format.format(Date(twilightCalculator.mSunset)) else "-1"
        val mode = if (twilightCalculator.mState == TwilightCalculator.NIGHT) "NIGHT" else "DAY"
        return String.format(Locale.US, "%s, (%s - %s)", mode, sunrise, sunset)
    }
}