package com.andrerinas.openheadunit.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import com.andrerinas.openheadunit.location.GeofenceLocation
import com.andrerinas.openheadunit.location.LocationHolder
import java.util.Calendar

/**
 * 昼夜判定需要的**机器读数**：框架昼夜位、光照传感器、屏幕亮度、`car_mode`、按区域、当前时刻。
 *
 * <p>判定本身在纯 JVM 可测的 [NightSource] 里；这里只负责"读"，
 * 因为这几件事都必须碰 Android API。
 *
 * <h3>框架昼夜位为什么不能直接从自己的 Resources 读</h3>
 *
 * <p>应用可以自己调 `AppCompatDelegate.setDefaultNightMode()`，那会把**本进程**的
 * `Resources.configuration.uiMode` 改成我们要的值。拿它当"框架位"就成了自问自答：
 * 深色模式一旦被我们设成白天，回退路径读到的也永远是白天，越修越黑。
 * 所以这里按三级取，全部是**系统级**、不受本进程覆盖影响的口径：
 *
 * <ol>
 *   <li>`Resources.getSystem().configuration.uiMode` —— 系统资源，AppCompat 碰不到；</li>
 *   <li>`UiModeManager.getNightMode()` 为 1/2（恒白天/恒夜间）时直接用；</li>
 *   <li>`Settings.Secure.ui_night_mode`（AOSP `UiModeManagerService` 的持久值，
 *       本车实测 = "2"、平板实测存在）。</li>
 * </ol>
 *
 * <p>三级都判不出来时返回 [BIT_UNKNOWN]：调用点**不许**把它当成白天（同工程既有口径：
 * 量不到就不把猜的值写进协议）。
 */
object NightSignals {

    const val BIT_UNKNOWN = 0x00
    const val BIT_NO = Configuration.UI_MODE_NIGHT_NO      // 0x10
    const val BIT_YES = Configuration.UI_MODE_NIGHT_YES    // 0x20

    private const val MASK = Configuration.UI_MODE_NIGHT_MASK

    /** 当前时刻（当天第几分钟），给「自定义时段」用。 */
    fun nowMinutes(): Int {
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }

    /** 本机有没有对应用暴露光照传感器（车上实测：没有）。 */
    fun hasLightSensor(context: Context): Boolean = try {
        (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
            ?.getDefaultSensor(Sensor.TYPE_LIGHT) != null
    } catch (t: Throwable) {
        false
    }

    /**
     * 屏幕亮度 0..255；读不到返回 -1。
     *
     * <p>API 28+ 优先 `screen_brightness_float`：滑块写的是浮点，整数 key 在亮度为 0 时可能
     * 缺失、也可能被自动亮度带偏（原注释在 `NightModeManager.readBrightness`，此处收敛成一处）。
     */
    fun readBrightness(context: Context): Int {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val f = AndroidSettings.System.getFloat(cr, "screen_brightness_float")
                if (!f.isNaN()) return (f * 255f).toInt().coerceIn(0, 255)
            } catch (_: Throwable) { /* 落到整数那一路 */ }
        }
        return try {
            AndroidSettings.System.getInt(cr, AndroidSettings.System.SCREEN_BRIGHTNESS).coerceIn(0, 255)
        } catch (_: Throwable) {
            -1
        }
    }

    /** `Settings.System.car_mode`（大灯 ILL+ 通路线号）；本机没有这个 key 时返回 -1。 */
    fun readCarMode(context: Context): Int = try {
        AndroidSettings.System.getInt(context.contentResolver, "car_mode")
    } catch (_: Throwable) {
        -1
    }

    /**
     * 「按区域」的昼夜；判不出来返回 null。
     *
     * <p>三种情况要分开：没有保存任何地点 → 用用户显式选的"区域外默认"（这是**有效配置**，
     * 不是猜，算得出来）；地点有、但一个定位都没有 → 判不出来（null，交给退路）；
     * 地点有且有定位 → 命中地点用它，否则用区域外默认。
     */
    fun geofenceNight(context: Context, settings: Settings): Boolean? = try {
        val places = settings.geofenceLocations
        val outside = settings.locationOutsideNight
        if (places.isEmpty()) {
            outside
        } else {
            val fix = LocationHolder.geofenceFix(context)
            if (fix == null) null else places.firstOrNull { it.contains(fix) }?.forceNight ?: outside
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 日出/日落该用哪个坐标：固定点 → 任意年龄的定位 → 曾被写入过的存储位置 → 内置成都双流。
     *
     * <p>与旧的判据（"有 10 分钟内的当前定位，或用户显式开了固定点"）相比有几处关键差别，
     * 都是为了修「CarWith 下日出日落不切换」：
     * <ul>
     *   <li><b>不再要求定位新鲜度</b>（原来要求 10 分钟内）。日出日落只关心经度，车停着不动时
     *       两小时前的位置依然正确；用车一停就翻成"没有坐标"是语义错误。</li>
     *   <li><b>存储位置要带「曾写入过」戳</b>。{@code lastKnownLocation} 的默认值是成都，未被打上
     *       {@code last-loc-user-set} 之前不能冒充"用户所在位置"。</li>
     *   <li><b>但兜底不再返回 null</b>（本次修复起）。前三级都没有时给内置成都双流坐标，
     *       避免整条链路落到"算不出来 → 回退深色"上；那在 ICCOA 侧是常态，不是例外。</li>
     * </ul>
     *
     * <p><b>调用点一次只取一次</b>：本方法每次都要问一遍 LocationManager（最多 3 个 provider），
     * 「能不能用」与「日志里写哪个点」都从同一个返回值上取，别再各调一次 ——
     * 那会让三处取坐标的结果有机会互相不一致。
     *
     * @return 可用坐标。<b>正常路径永远非 null</b>（最差是内置成都双流）；
     *         {@code null} 只剩"取坐标过程中抛了异常"这一种情况，调用方仍按既定规则回退深色。
     */
    fun sunriseReference(context: Context, settings: Settings): SunriseLocationPolicy.Ref? = try {
        val fixed = settings.useFixedSunriseLocation
        val fix = LocationHolder.anyAgeFix(context)
        val stored = settings.lastKnownLocation
        SunriseLocationPolicy.choose(
            if (fixed) settings.fixedSunriseLatitude else null,
            if (fixed) settings.fixedSunriseLongitude else null,
            fix?.latitude,
            fix?.longitude,
            settings.hasUserSunLocation,
            stored.latitude,
            stored.longitude
        )
    } catch (t: Throwable) {
        null
    }

    /** 框架昼夜位（见类注释的三级取法）。 */
    fun frameworkNightBit(context: Context): Int {
        val fromSystemResources = try {
            Resources.getSystem().configuration.uiMode and MASK
        } catch (t: Throwable) {
            0
        }
        if (fromSystemResources == BIT_NO || fromSystemResources == BIT_YES) return fromSystemResources

        if (Build.VERSION.SDK_INT >= 20) {
            try {
                val um = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                when (um?.nightMode) {
                    UiModeManager.MODE_NIGHT_NO -> return BIT_NO
                    UiModeManager.MODE_NIGHT_YES -> return BIT_YES
                }
            } catch (_: Throwable) { }
        }

        return when (readSecure(context, "ui_night_mode")) {
            "1" -> BIT_NO
            "2" -> BIT_YES
            else -> BIT_UNKNOWN
        }
    }

    /** 框架昼夜位判不出来时返回 null（调用点不许当成白天）。 */
    fun frameworkIsNight(context: Context): Boolean? = when (frameworkNightBit(context)) {
        BIT_YES -> true
        BIT_NO -> false
        else -> null
    }

    /** 这一级框架位是从哪读到的 —— 上车判读用，别让人猜。 */
    fun frameworkSourceLabel(context: Context): String {
        val fromSystemResources = try {
            Resources.getSystem().configuration.uiMode and MASK
        } catch (t: Throwable) {
            0
        }
        if (fromSystemResources == BIT_NO || fromSystemResources == BIT_YES) return "系统资源配置"
        if (Build.VERSION.SDK_INT >= 20) {
            try {
                val um = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                if (um?.nightMode == UiModeManager.MODE_NIGHT_NO ||
                    um?.nightMode == UiModeManager.MODE_NIGHT_YES
                ) {
                    return "UiModeManager.getNightMode()"
                }
            } catch (_: Throwable) { }
        }
        return if (readSecure(context, "ui_night_mode") != null) "Settings.Secure.ui_night_mode"
        else "读不到"
    }

    private fun readSecure(context: Context, key: String): String? = try {
        AndroidSettings.Secure.getString(context.contentResolver, key)
    } catch (_: Throwable) {
        null
    }
}
