package com.andrerinas.openheadunit.location

import android.os.SystemClock
import java.util.Locale

/**
 * 定位链路的**诊断快照** —— 只服务日志，不参与任何业务判定。
 *
 * <p>为什么需要一个独立的快照对象：车上的问题是「只有行驶中才容易异常断开」，而要验证这条线索，
 * 断连那一刻必须能同时看到<b>车速</b>与<b>GPS 是否锁定</b>。{@code LocationHolder} 只保留「最新的
 * 一个位置」，不保留「订阅是否成功」「锁定用了多久」「可见卫星数」这些<b>过程量</b>，它也没有权限
 * 与 Provider 开关的状态。没有这些，报表里出现「没有车速字段」时无法区分
 * 「GPS 一直没锁定」与「有定位但没速度字段」这两种完全不同的情况。
 *
 * <p>全部字段由 {@link GpsLocation} 在唯一的写入点更新；读取方（心跳、断连现场）只读不写。
 */
object GpsStatus {

    /** ACCESS_FINE_LOCATION 是否已被授予（每次订阅前与重查时刷新）。 */
    @Volatile
    @JvmField
    var permissionGranted: Boolean = false

    /**
     * 是否真的订阅成功（= 有 provider 可用且 requestLocationUpdates 没抛异常）。
     *
     * <p>注意它<b>不等于</b>「系统定位开关是否打开」：没有 GNSS 芯片的设备（Wi-Fi 平板）
     * 上 {@code isProviderEnabled(GPS_PROVIDER)} 恒 false，但系统定位其实是开着的、
     * 也能用 network/fused 拿到车速。这两个必须分开记，否则报表会说反。
     */
    @Volatile
    @JvmField
    var providerEnabled: Boolean = false

    /** 系统「位置信息」总开关（API 28+ 的 LocationManager.isLocationEnabled）。 */
    @Volatile
    @JvmField
    var systemLocationOn: Boolean = false

    /** 这台设备上可用（isProviderEnabled=true）的 provider 列表，如 `network/fused`；无则为「无」。 */
    @Volatile
    @JvmField
    var availableProviders: String = "未知"

    /** 本次实际订阅到的 provider（gps / network / fused）；未订阅时 null。 */
    @Volatile
    @JvmField
    var activeProvider: String? = null

    /** 是否已成功向 LocationManager 订阅（= 真的在等定位回调）。 */
    @Volatile
    @JvmField
    var subscribed: Boolean = false

    /** 订阅成功的时刻（elapsedRealtime）。用来回答「等了多久才锁定」。 */
    @Volatile
    @JvmField
    var subscribedAtMs: Long = 0L

    /** 本进程累计收到的定位回调条数。 */
    @Volatile
    @JvmField
    var fixCount: Long = 0L

    /** 最后一次真实定位的时刻（elapsedRealtime），0 = 从未锁定。 */
    @Volatile
    @JvmField
    var lastFixAtMs: Long = 0L

    /** 首次定位耗时（ms），-1 = 还没锁定。这是判断「天线接没接」最直接的量。 */
    @Volatile
    @JvmField
    var firstFixDelayMs: Long = -1L

    /** 最后一次定位的 provider（gps / network / passive / fused）。 */
    @Volatile
    @JvmField
    var provider: String? = null

    @Volatile
    @JvmField
    var latitude: Double = Double.NaN

    @Volatile
    @JvmField
    var longitude: Double = Double.NaN

    /** 水平精度（米），NaN = 未提供。 */
    @Volatile
    @JvmField
    var accuracyM: Float = Float.NaN

    /**
     * 速度（m/s），NaN = 该次定位不带速度且无法推算（首条定位）。
     *
     * <p>带 {@link #speedEstimated} 一起看：推算值来自相邻两条定位的位移 / 时间差，
     * 只够判「在不在动」，不能当 GNSS 准值读（日志里标了「(推算)」）。
     */
    @Volatile
    @JvmField
    var speedMps: Float = Float.NaN

    /** 当前 {@link #speedMps} 是否为位移法推算值（true = 该次定位本身没给速度）。 */
    @Volatile
    @JvmField
    var speedEstimated: Boolean = false

    /** 可见卫星总数，-1 = 未注册 GnssStatus 或尚未回调。 */
    @Volatile
    @JvmField
    var satellites: Int = -1

    /** 其中参与解算的卫星数，-1 = 同上。 */
    @Volatile
    @JvmField
    var usedInFix: Int = -1

    /** 定时重发了多少次上一次定位（停在车库里时靠它维持导航）。 */
    @Volatile
    @JvmField
    var resendCount: Long = 0L

    /** 订阅失败的原因（异常或权限/开关缺失），成功订阅后清空。 */
    @Volatile
    @JvmField
    var lastError: String? = null

    /** 订阅成功后一直没等到定位时，是否已经告警过（避免每秒刷同一行）。 */
    @Volatile
    @JvmField
    var noFixWarned: Boolean = false

    /** 回到初始态。进程内复用实例时（stop 后再 start）调用，防止把上次会话的过程量带过来。 */
    @JvmStatic
    fun reset() {
        subscribed = false
        activeProvider = null
        subscribedAtMs = 0L
        fixCount = 0L
        lastFixAtMs = 0L
        firstFixDelayMs = -1L
        provider = null
        latitude = Double.NaN
        longitude = Double.NaN
        accuracyM = Float.NaN
        speedMps = Float.NaN
        speedEstimated = false
        satellites = -1
        usedInFix = -1
        resendCount = 0L
        lastError = null
        noFixWarned = false
    }

    /**
     * 单行快照，供心跳与断连现场拼进日志。
     *
     * <p>三种形态（用前缀区分，便于关键字检索）：
     * <pre>
     *   未订阅（权限=false 开关=true）
     *   订阅中 未锁定（已等 287s，星=0/0）
     *   锁定 3s前 provider=gps 星=9/12 车速=45km/h acc=±9m
     * </pre>
     *
     * <p>「未订阅」与「未锁定」必须分开：前者是权限/开关问题（本应用根本没在等定位），
     * 后者是射频/天线问题（在等，但卫星收不到）。二者的排查方向完全不同。
     */
    @JvmStatic
    fun snapshot(): String {
        val now = SystemClock.elapsedRealtime()
        if (!subscribed) {
            val err = lastError
            return StringBuilder("未订阅（权限=").append(permissionGranted)
                .append(" 系统定位=").append(systemLocationOn)
                .append(" 可用provider=").append(availableProviders)
                .append(if (err == null) "" else " 原因=" + err).append("）").toString()
        }
        val waitedS = (now - subscribedAtMs) / 1000L
        val sat = if (satellites >= 0) "星=" + usedInFix + "/" + satellites else "星=未知"
        if (lastFixAtMs == 0L) {
            return "订阅中 未锁定（provider=" + (activeProvider ?: "?") + " 已等 " + waitedS + "s，" + sat + "）"
        }
        val ageS = (now - lastFixAtMs) / 1000L
        val speed = if (speedMps.isNaN()) "" else
            String.format(Locale.US, " 车速=%.0fkm/h%s%s", speedMps * 3.6f,
                if (speedEstimated) "(推算)" else "",
                if (speedMps * 3.6f < 3.0f) "(静止)" else "")
        val acc = if (accuracyM.isNaN()) "" else
            String.format(Locale.US, " acc=±%.0fm", accuracyM)
        return "锁定 " + ageS + "s前 provider=" + (provider ?: "?") + " " + sat + speed + acc
    }
}
