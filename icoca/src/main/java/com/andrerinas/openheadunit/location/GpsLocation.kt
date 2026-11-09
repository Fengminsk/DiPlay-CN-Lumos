package com.andrerinas.openheadunit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.PermissionChecker
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.contract.LocationUpdateIntent
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale

class GpsLocation constructor(private val context: Context): LocationListener {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val settings by lazy { App.provide(context).settings }
    private var requested: Boolean = false
    private val handler = Handler(Looper.getMainLooper())

    // Only ever set from onLocationChanged, so a resend can never mislabel a network/passive
    // fallback fix as this head unit's real GPS.
    private var lastGpsFix: Location? = null

    // Taken from the monotonic clock rather than Location.time, because a resend deliberately
    // bumps that field and would otherwise keep declaring itself fresh.
    private var lastGpsFixAtMs: Long = 0L

    // When the subscription was made, so the first fix can be reported with how long it took.
    private var subscribedAtMs: Long = 0L

    private var staleFixReported = false

    /** 上次「卫星」摘要落库时刻（elapsedRealtime），配合 [SAT_LOG_INTERVAL_MS] 降噪。 */
    private var lastSatLogAtMs: Long = 0L

    // 诊断用：本轮订阅收到多少条定位、上一次「定位摘要」什么时候打出去的。
    // 摘要按 FIX_LOG_INTERVAL_MS 节流，1Hz 的定位不至于把 12000 行环形缓冲刷掉。
    private var fixCount: Long = 0L
    private var lastFixLogAtMs: Long = 0L

    // 订阅失败（权限未授予 / 定位开关没开）时的重查。车机上权限往往是在应用已经跑起来之后
    // 才被用户在设置里打开的，而原先的实现只在 startService 那一刻判一次：那一瞬间没权限，
    // 整个会话就再也不会订阅了，报表里只剩一行 granted=false，看不出后来有没有拿到。
    private var recheckCount = 0
    private val recheck = object : Runnable {
        override fun run() {
            if (requested) return
            recheckCount++
            val hasPermission = hasFineLocation()
            GpsStatus.permissionGranted = hasPermission
            GpsStatus.systemLocationOn = systemLocationOn()
            GpsStatus.availableProviders = availableProviders()
            val available = GpsStatus.availableProviders != "无"
            // 前 3 次每次都报（用户刚点完授权的那几秒最关键），之后每 8 次（2 分钟）报一次，
            // 免得静止一晚上刷出几百行同样的内容。
            if (recheckCount <= 3 || recheckCount % 8 == 0) {
                AppLog.i("GpsLocation: 重查 #$recheckCount — 权限=$hasPermission"
                        + " 系统定位开关=${GpsStatus.systemLocationOn}"
                        + " 可用provider=${GpsStatus.availableProviders}")
            }
            if (hasPermission && available) {
                AppLog.i("GpsLocation: 权限/开关已就位（重查 #$recheckCount），补订定位")
                start()
                if (requested) return
            }
            handler.postDelayed(this, RECHECK_INTERVAL_MS)
        }
    }

    // Android Auto is reported to prefer the car's GPS but fall back to the phone's own once the
    // car's signal looks interrupted, so a head unit that stops emitting looks like a head unit
    // with no GPS. The subscription below asks for fixes on time alone, which keeps a parked unit
    // emitting, but not every chip obliges - resend the last real fix on a timer as a backstop, the
    // way a real GPS chip keeps outputting NMEA sentences whether or not the car moves.
    //
    // A resend only ever stands in for a beat the chip actually missed: while real fixes arrive on
    // schedule it does nothing, so it duplicates nothing. And it stops once the last real fix is
    // older than RESEND_MAX_AGE_MS - without that bound a GPS that genuinely dies mid-drive
    // (tunnel, garage, antenna knocked loose) would be indistinguishable from a parked car forever,
    // and Android Auto would stay pinned to the last position instead of falling back to the
    // phone. A wrong position while moving is worse than the stall this resend exists to prevent.
    private val periodicResend = object : Runnable {
        override fun run() {
            val fix = lastGpsFix
            if (fix != null && settings.useGpsForNavigation) {
                val ageMs = SystemClock.elapsedRealtime() - lastGpsFixAtMs
                if (ageMs > RESEND_MAX_AGE_MS) {
                    if (!staleFixReported) {
                        staleFixReported = true
                        AppLog.i("GpsLocation: no GPS fix for ${ageMs / 1000}s, stopping resend so the phone can fall back to its own location")
                    }
                } else if (ageMs >= RESEND_INTERVAL_MS) {
                    val refreshed = Location(fix).apply {
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    GpsStatus.resendCount = GpsStatus.resendCount + 1
                    context.sendBroadcast(LocationUpdateIntent(refreshed).setPackage(context.packageName))
                }
                // ageMs < RESEND_INTERVAL_MS: a real fix just went out; nothing to stand in for.
            }
            // 从未锁定的告警必须放在 resend 分支外面：那一段只在「拿到过至少一个定位」之后才有意义。
            // 一块从来没锁定过的芯片，日志里原本只会是一片空白 —— 而空白恰恰是最容易被误读成
            // 「没发生什么」的那种证据。
            warnIfNeverFixed()
            handler.postDelayed(this, RESEND_INTERVAL_MS)
        }
    }

    /**
     * 卫星可见性。定位「有没有锁定」是结果，星数是原因：收到 12 颗但 0 颗参与解算，
     * 和一颗都收不到，指向的故障完全不同（天线没接 / 被金属膜挡住 / 芯片没供电）。
     */
    private val gnssCallback: GnssStatus.Callback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    var used = 0
                    for (i in 0 until status.satelliteCount) {
                        if (status.usedInFix(i)) used++
                    }
                    // 突变判据必须在赋新值之前算：赋值之后差值恒为 0，永远触发不了。
                    val bigChange = Math.abs(status.satelliteCount - GpsStatus.satellites) >= 3 ||
                        Math.abs(used - GpsStatus.usedInFix) >= 3
                    GpsStatus.satellites = status.satelliteCount
                    GpsStatus.usedInFix = used
                    // 降噪：星数每秒都在抖（1Hz 定位下实测 23 行/分钟，占自有点一半以上）。
                    // 改成「变化 ≥3 颗」或「距上次 ≥30s」才记 —— 突变（遮挡/天线脱落）与
                    // 趋势（逐步锁定）都保得住，逐颗抖动不再进报表。
                    // 心跳里始终有最新的星数快照，这里丢的是重复、不是观测。
                    val nowMs = SystemClock.elapsedRealtime()
                    if (bigChange || nowMs - lastSatLogAtMs >= SAT_LOG_INTERVAL_MS) {
                        lastSatLogAtMs = nowMs
                        AppLog.i("GpsLocation: 卫星 可见=${status.satelliteCount} 参与解算=$used"
                                + if (bigChange) "（变化≥3）" else "（30s 例报）")
                    }
                }

                override fun onFirstFix(ttffMillis: Int) {
                    AppLog.i("GpsLocation: GNSS 首次定位耗时 ${ttffMillis}ms")
                }

                override fun onStarted() {
                    AppLog.i("GpsLocation: GNSS 已启动")
                }

                override fun onStopped() {
                    AppLog.i("GpsLocation: GNSS 已停止")
                }
            }
        } else {
            null
        }

    private fun hasFineLocation(): Boolean =
        PermissionChecker.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PermissionChecker.PERMISSION_GRANTED

    /**
     * 候选 provider，按精度从高到低。
     *
     * <p>车机有 GNSS 芯片时永远命中第一项，行为与改动前完全一致；这条回退链只为两类设备兜底：
     * <ul>
     *   <li><b>没有 GPS_PROVIDER 的设备</b>：实测这块小米平板（23043RP34C，Wi-Fi 版）
     *       {@code dumpsys location} 里只有 passive/network/fused，
     *       {@code isProviderEnabled(GPS_PROVIDER)} 恒 false —— 于是原来那条
     *       「定位开关未打开」把<em>系统定位明明开着</em>的事实报成了相反的样子，
     *       整条定位链路（车速、星数、首锁耗时）在报表里一行都不会出现；</li>
     *   <li><b>有芯片但用户只开了「网络定位」</b>：此时 GPS 不可用但车速仍然拿得到。</li>
     * </ul>
     */
    private val PROVIDER_CHAIN = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        FUSED_PROVIDER
    )

    private fun isEnabled(name: String): Boolean = try {
        locationManager.isProviderEnabled(name)
    } catch (t: Throwable) {
        AppLog.w("GpsLocation: isProviderEnabled($name) 异常: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * 系统「位置信息」总开关（API 28+）。
     *
     * <p>不能再用 {@code isProviderEnabled(GPS_PROVIDER)} 代替它：没有 GPS 芯片的设备上后者恒 false，
     * 会把「位置服务是开着的、只是没有 GNSS」误报成「用户把定位关了」，排查方向直接被带偏。
     */
    private fun systemLocationOn(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                locationManager.isLocationEnabled
            } catch (t: Throwable) {
                PROVIDER_CHAIN.any { isEnabled(it) }
            }
        } else {
            PROVIDER_CHAIN.any { isEnabled(it) }
        }

    /** 可用 provider 列表（诊断用，让报表能看出这台设备到底有没有 GNSS）。 */
    private fun availableProviders(): String =
        PROVIDER_CHAIN.filter { isEnabled(it) }.joinToString("/").ifEmpty { "无" }

    /**
     * 真正订阅：从 {@link #PROVIDER_CHAIN} 里挑第一个「开关已开且订阅不抛异常」的。
     *
     * <p>fused 在部分 ROM 上需要系统权限，{@code requestLocationUpdates} 会抛
     * SecurityException —— 接住后继续试下一个，否则整个服务会被这一下打崩。
     *
     * @return 订阅成功的 provider 名；全部失败时返回 null（并写出失败原因）
     */
    @SuppressLint("MissingPermission")
    private fun trySubscribe(): Pair<String?, String?> {
        var lastErr: String? = null
        for (p in PROVIDER_CHAIN) {
            if (!isEnabled(p)) continue
            try {
                locationManager.requestLocationUpdates(p, UPDATE_INTERVAL_MS, 0f, this)
                return p to null
            } catch (t: Throwable) {
                lastErr = "$p 订阅失败（${t.javaClass.simpleName}: ${t.message}）"
                AppLog.w("GpsLocation: $lastErr —— 试下一个 provider")
            }
        }
        return null to (lastErr ?: "无可用定位 provider（系统定位开关=${systemLocationOn()}）")
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (requested) {
            return
        }
        val hasPermission = hasFineLocation()
        GpsStatus.permissionGranted = hasPermission
        GpsStatus.systemLocationOn = systemLocationOn()
        GpsStatus.availableProviders = availableProviders()
        AppLog.i("Request location updates")
        AppLog.i("GpsLocation: 订阅前自检 权限(ACCESS_FINE_LOCATION)=$hasPermission"
                + " 系统定位开关=${GpsStatus.systemLocationOn}"
                + " 可用provider=${GpsStatus.availableProviders}"
                + "（候选=${PROVIDER_CHAIN.joinToString("/")}）")
        val (subscribedProvider, subscribeError) = if (hasPermission) trySubscribe() else (null to "权限未授予")
        GpsStatus.providerEnabled = subscribedProvider != null
        GpsStatus.activeProvider = subscribedProvider
        if (subscribedProvider != null) {
            // No minDistance: Android ANDs the time and distance gates, so any non-zero distance
            // stops a stationary head unit after its very first fix. Asking on time alone is what
            // a real head unit does, and it costs nothing extra - the chip is already running.
            //
            // 1Hz is the automotive GPS norm and what Android Auto's navigation is tuned for.
            // The old 5m gate delivered a fix every ~0.4-0.6s at driving speed, so this is fewer
            // updates while moving, not more; the parked case goes from one fix ever to a steady
            // stream, and the fan-out cost of that is paid at the sinks, not by starving nav.
            //
            // 没有 GNSS 的设备会退到 network/fused：那也能给出车速与坐标（实测 network 的
            // ProviderProperties 里就带 speed），只是精度从米级降到几十米级 —— 对
            // 「断连那一刻是停着还是在跑」这个判据来说足够了，但日志必须写清 provider，
            // 免得把网络定位的 ±30m 当成 GNSS 的 ±5m 去解读。
            if (subscribedProvider != LocationManager.GPS_PROVIDER) {
                AppLog.i("GpsLocation: 本设备无可用 GPS_PROVIDER，回退订阅 $subscribedProvider"
                        + "（车速仍可读，但精度是网络/融合定位级别，不要按 GNSS 精度判读）")
            }
            val location = try {
                locationManager.getLastKnownLocation(subscribedProvider)
            } catch (t: Throwable) {
                AppLog.w("GpsLocation: getLastKnownLocation 异常: ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            // 系统缓存的 last-known 也能给出速度与坐标，但它是「上一次别人用过定位时的位置」，
            // 不能算本车此刻的锁定状态 —— 所以只写进快照供参考，不进 lastGpsFix、不触发重发。
            if (location != null) {
                GpsStatus.provider = location.provider
                GpsStatus.latitude = location.latitude
                GpsStatus.longitude = location.longitude
                if (location.hasAccuracy()) GpsStatus.accuracyM = location.accuracy
                if (location.hasSpeed()) GpsStatus.speedMps = location.speed
            }
            AppLog.i("Last known location:  ${location?.toString() ?: "Unknown"}")
            requested = true
            subscribedAtMs = SystemClock.elapsedRealtime()
            GpsStatus.subscribed = true
            GpsStatus.subscribedAtMs = subscribedAtMs
            GpsStatus.lastError = null
            registerGnss()
            handler.removeCallbacks(recheck)
            handler.postDelayed(periodicResend, RESEND_INTERVAL_MS)
        } else {
            GpsStatus.lastError = subscribeError ?: "未知原因"
            AppLog.i("GpsLocation: 本次未订阅 —— 权限=$hasPermission 系统定位开关=${GpsStatus.systemLocationOn}"
                    + " 可用provider=${GpsStatus.availableProviders} 原因=${GpsStatus.lastError}")
            // 安排重查：权限/开关可能在会话进行中被打开，没有这一路的补订就永远等不到定位。
            handler.removeCallbacks(recheck)
            handler.postDelayed(recheck, RECHECK_INTERVAL_MS)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerGnss() {
        val cb = gnssCallback ?: return
        try {
            locationManager.registerGnssStatusCallback(cb)
            AppLog.i("GpsLocation: 已注册 GNSS 星数回调（API=${Build.VERSION.SDK_INT}）")
        } catch (t: Throwable) {
            AppLog.w("GpsLocation: registerGnssStatusCallback 失败（日志将没有星数）: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 订阅成功但一直没等到任何定位 —— 每 30 秒报一次，最多 10 次，之后只在恢复订阅时重新计数。 */
    private fun warnIfNeverFixed() {
        if (!requested || lastGpsFix != null) return
        val waitedS = (SystemClock.elapsedRealtime() - subscribedAtMs) / 1000L
        if (waitedS < NO_FIX_WARN_AFTER_SEC) return
        if (SystemClock.elapsedRealtime() - lastNoFixWarnAtMs < NO_FIX_WARN_INTERVAL_MS) return
        lastNoFixWarnAtMs = SystemClock.elapsedRealtime()
        if (noFixWarnCount >= NO_FIX_WARN_MAX) return
        noFixWarnCount++
        val sat = if (GpsStatus.satellites >= 0) "星=${GpsStatus.usedInFix}/${GpsStatus.satellites}" else "星=未知"
        AppLog.w("GpsLocation: 订阅后 ${waitedS}s 仍无任何定位回调（$sat；"
                + "权限=${GpsStatus.permissionGranted} 开关=${GpsStatus.providerEnabled}）——"
                + "GPS 未锁定/天线未接/芯片无供电，本次不会有任何车速")
    }
    private var lastNoFixWarnAtMs: Long = 0L
    private var noFixWarnCount: Int = 0

    /** 上一条定位（用于位移法推算速度）。 */
    private var prevLocation: Location? = null

    /**
     * 用相邻两条定位的位移 / 时间差推算速度（m/s）。
     *
     * <p>只在本次回调不带 speed 时使用。1Hz 采样下静止时的定位抖动（网络定位尤其明显）
     * 会算出 0.x m/s 的假速度，所以低于 {@code 0.5m/s}（1.8km/h）一律按 0 记 ——
     * 「静止」这个结论必须稳，宁可把慢速挪车判成静止，也不能把停车判成行驶。
     *
     * @return 推算速度；样本不足（首条 / 时间差异常）时返回 null
     */
    private fun computeSpeedFromDisplacement(location: Location): Float? {
        val prev = prevLocation
        prevLocation = Location(location)
        if (prev == null) return null
        val dtSec = (location.time - prev.time) / 1000.0
        if (dtSec <= 0.0 || dtSec > 30.0) return null
        val meters = prev.distanceTo(location).toDouble()
        val v = meters / dtSec
        return if (v < 0.5f) 0f else v.toFloat()
    }

    override fun onLocationChanged(location: Location) {
        // A callback dispatched before removeUpdates() took effect must not repopulate state a
        // stop() just cleared - the next start() would inherit a fix from the previous session.
        if (!requested) {
            return
        }
        // Once per subscription, and carrying no position: without it a head unit whose antenna is
        // not connected is indistinguishable from a working one in a log. It passes every gate in
        // start() - the provider exists and is enabled - and then simply never locks, so the only
        // other evidence is the absence of lines that were never going to appear. How long the
        // lock took separates a healthy chip from one that is barely hearing the satellites.
        // lastGpsFix is the flag: it is null exactly between start() and the first fix.
        if (lastGpsFix == null) {
            val tookS = (SystemClock.elapsedRealtime() - subscribedAtMs) / 1000L
            GpsStatus.firstFixDelayMs = SystemClock.elapsedRealtime() - subscribedAtMs
            AppLog.i("GpsLocation: first fix after ${tookS}s")
        }
        // Cadence only, never a position: this is what lets a parked verbose log show whether the
        // chip honours a time-only subscription (and so whether the resend backstop is needed).
        if (AppLog.LOG_VERBOSE) {
            AppLog.v("GpsLocation: fix received")
        }
        lastGpsFix = location
        lastGpsFixAtMs = SystemClock.elapsedRealtime()

        // —— 诊断快照：心跳与断连现场读的就是这几个字段 ——
        fixCount++
        GpsStatus.fixCount = fixCount
        GpsStatus.lastFixAtMs = lastGpsFixAtMs
        GpsStatus.provider = location.provider
        GpsStatus.latitude = location.latitude
        GpsStatus.longitude = location.longitude
        GpsStatus.accuracyM = if (location.hasAccuracy()) location.accuracy else Float.NaN
        // 速度兜底：实测「这次回调不带 speed」是常态而不是例外 —— 这台平板的 network 定位
        // 每条都没有速度字段，而车机上同为 GPS 也有不带速度的芯片/ROM。若就此留空，
        // 「断连那一刻是停着还是在跑」这个唯一的行驶判据直接失效。
        // 用两条定位之间的位移 / 时间差推一个：精度不够做导航，但判断「在不在动」够了。
        // 推算值必须在日志里标出来，否则会被当成 GNSS 给的准值去读。
        GpsStatus.speedEstimated = false
        GpsStatus.speedMps = if (location.hasSpeed()) {
            location.speed
        } else {
            computeSpeedFromDisplacement(location)?.also { GpsStatus.speedEstimated = true } ?: Float.NaN
        }

        // Reset here rather than in the resend's fresh branch: on a healthy chip that branch never
        // runs, and the flag left latched would silence the stale line for every outage after the
        // first one in a session.
        staleFixReported = false

        // 定位摘要：节流到每 5 秒一条。逐条打（1Hz）会在 20 分钟路测里占掉 1200 行，
        // 把断连前后的上下文挤出 12000 行环形缓冲；完全不打则拿不到速度曲线。
        val now = SystemClock.elapsedRealtime()
        if (now - lastFixLogAtMs >= FIX_LOG_INTERVAL_MS) {
            lastFixLogAtMs = now
            val acc = if (location.hasAccuracy())
                String.format(Locale.US, " acc=±%.0fm", location.accuracy) else ""
            val sat = if (GpsStatus.satellites >= 0) " 星=${GpsStatus.usedInFix}/${GpsStatus.satellites}" else ""
            val spd = GpsStatus.speedMps
            val speedTxt = if (spd.isNaN()) "无" else
                String.format(Locale.US, "%.0fkm/h", spd * 3.6f) +
                    (if (GpsStatus.speedEstimated) "(推算)" else "")
            AppLog.i("GpsLocation: 定位 #$fixCount provider=${location.provider} 车速=$speedTxt$acc$sat")
        }

        LocationHolder.update(location)
        context.sendBroadcast(LocationUpdateIntent(location).setPackage(context.packageName))
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        // 原实现只打印 "gps: 2" —— 既搜不到（没有关键字），也读不懂（2 是哪个状态）。
        val sats = extras.getInt("satellites", -1)
        if (sats >= 0) {
            GpsStatus.satellites = sats
        }
        AppLog.i("GpsLocation: provider $provider status=$status（${statusName(status)}）"
                + if (sats >= 0) " 卫星=$sats" else "")
    }

    override fun onProviderEnabled(provider: String) {
        // 原实现只打印一个单词 "gps"，在报表里既无关键字也看不出发生了什么。
        GpsStatus.systemLocationOn = systemLocationOn()
        GpsStatus.availableProviders = availableProviders()
        AppLog.i("GpsLocation: 定位开关已打开 provider=$provider"
                + "（可用=${GpsStatus.availableProviders}；若此前因开关未开而没订阅，重查会自动补订）")
    }

    override fun onProviderDisabled(provider: String) {
        GpsStatus.systemLocationOn = systemLocationOn()
        GpsStatus.availableProviders = availableProviders()
        AppLog.w("GpsLocation: 定位开关被关闭 provider=$provider"
                + "（剩余可用=${GpsStatus.availableProviders}）—— 本进程不再收到该 provider 的定位，"
                + "心跳里的 GPS 会停在最后一次锁定")
    }

    fun stop() {
        AppLog.i("Remove location updates")
        AppLog.i("GpsLocation: 停止订阅（累计定位 ${GpsStatus.fixCount} 条，重发 ${GpsStatus.resendCount} 次，"
                + "首锁耗时 ${if (GpsStatus.firstFixDelayMs < 0) "未锁定" else "${GpsStatus.firstFixDelayMs / 1000}s"}）")
        requested = false
        // Every field start() depends on goes back to its initial value, so a re-armed instance
        // cannot resend a fix acquired during the previous connection.
        lastGpsFix = null
        prevLocation = null
        lastGpsFixAtMs = 0L
        subscribedAtMs = 0L
        staleFixReported = false
        noFixWarnCount = 0
        lastNoFixWarnAtMs = 0L
        handler.removeCallbacks(recheck)
        handler.removeCallbacks(periodicResend)
        unregisterGnss()
        try {
            locationManager.removeUpdates(this)
        } catch (t: Throwable) {
            AppLog.w("GpsLocation: removeUpdates 异常: ${t.javaClass.simpleName}: ${t.message}")
        }
        GpsStatus.reset()
    }

    @Suppress("DEPRECATION")
    private fun unregisterGnss() {
        val cb = gnssCallback ?: return
        try {
            locationManager.unregisterGnssStatusCallback(cb)
        } catch (t: Throwable) {
            AppLog.w("GpsLocation: unregisterGnssStatusCallback 异常: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun statusName(status: Int): String = when (status) {
        android.location.LocationProvider.OUT_OF_SERVICE -> "OUT_OF_SERVICE(0)"
        android.location.LocationProvider.TEMPORARILY_UNAVAILABLE -> "TEMPORARILY_UNAVAILABLE(1)"
        android.location.LocationProvider.AVAILABLE -> "AVAILABLE(2)"
        else -> "未知状态"
    }

    private companion object {
        /** LocationManager.FUSED_PROVIDER 在某些 API 级别上不可见，直接用字符串常量。 */
        const val FUSED_PROVIDER = "fused"
        const val UPDATE_INTERVAL_MS = 1000L
        const val RESEND_INTERVAL_MS = 3000L

        /** How stale the last real fix may be before a resend stops standing in for it. */
        const val RESEND_MAX_AGE_MS = 60_000L

        /** 定位摘要的最小间隔：1Hz 的定位按这个节流，兼顾速度曲线与日志容量。 */
        const val FIX_LOG_INTERVAL_MS = 5000L

        /** 卫星摘要例报间隔（无大变化时）；变化 ≥3 颗时不受此限。 */
        const val SAT_LOG_INTERVAL_MS = 30_000L

        /** 订阅失败后多久重查一次权限与定位开关。 */
        const val RECHECK_INTERVAL_MS = 15_000L

        /** 订阅成功但超过这个秒数还没有任何定位，开始告警。 */
        const val NO_FIX_WARN_AFTER_SEC = 30L

        /** 未锁定告警的最小间隔与总次数上限（避免静止一晚刷屏）。 */
        const val NO_FIX_WARN_INTERVAL_MS = 30_000L
        const val NO_FIX_WARN_MAX = 10
    }
}
