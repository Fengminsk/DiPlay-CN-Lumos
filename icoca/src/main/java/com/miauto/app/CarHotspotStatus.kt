package com.miauto.app

import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager

/**
 * 车机热点状态探测（方案 G1）——三级回退，读不到不阻塞。
 *
 * <p><b>为什么是三级</b>：热点的状态读取全在 hidden API 区（POC E1 已证 `getWifiApState`
 * 反射在 targetSdk 36 下可用，但这是小米平板的结论，不能假设所有车机 ROM 都放行），所以
 * 每一级失败都静默落空、由下一级接住：
 * <ol>
 *   <li>反射 `WifiManager.getWifiApState()`（整数状态）；</li>
 *   <li>反射 `WifiManager.isWifiApEnabled()`（布尔封装，部分 ROM 保留）；</li>
 *   <li>粘性广播 `WIFI_AP_STATE_CHANGED` 的 `wifi_state` extra（热点状态变化后系统会重发，
 *       注册 null receiver 即可取到最后一条 —— `ACCESS_WIFI_STATE` 是 normal 级，已声明）。</li>
 * </ol>
 *
 * <p><b>纪律（方案 §2）</b>：三级全失败返回 [HotspotState.UNKNOWN]，调用方按「未开启」引导、
 * 但不把它当成硬错误 —— 状态读不到不阻塞连接（连接可行性由 SDK 侧的注入结果决定）。
 *
 * 纯判定函数（[fromRawState] / [resolve] / [fromStickyExtra]）与 Android 读取分离，
 * JVM 单测钉住映射口径（见 `CarHotspotStatusTest`）。
 */
object CarHotspotStatus {

    /** SDK/框架通用的热点状态原始值（`WifiManager.WIFI_AP_STATE_*`，hidden 常量，字面固化）。 */
    const val RAW_DISABLING = 10
    const val RAW_DISABLED = 11
    const val RAW_ENABLING = 12
    const val RAW_ENABLED = 13

    /** 粘性广播 extra 名（hidden 常量 `WIFI_AP_STATE_CHANGED` 的字段，字面固化）。 */
    const val STICKY_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    const val STICKY_EXTRA = "wifi_state"

    fun fromRawState(raw: Int): HotspotState = when (raw) {
        RAW_ENABLED -> HotspotState.ENABLED
        RAW_DISABLED, RAW_DISABLING -> HotspotState.DISABLED
        // ENABLING 是过渡态：引导页拿它当「还没开」会让用户在开启瞬间被误判，
        // 当 UNKNOWN 让上层复检更稳妥。
        else -> HotspotState.UNKNOWN
    }

    /** 粘性广播 extra 读出的原始值（缺失/非法传 null）。 */
    fun fromStickyExtra(raw: Int?): HotspotState =
        raw?.let { fromRawState(it) } ?: HotspotState.UNKNOWN

    /** 三级回退判定：第一个非 UNKNOWN 的级别生效；全失败才是 UNKNOWN。 */
    fun resolve(vararg levels: HotspotState): HotspotState =
        levels.firstOrNull { it != HotspotState.UNKNOWN } ?: HotspotState.UNKNOWN

    /**
     * 完整探测入口。任何一级抛异常都按「该级失败」处理，绝不向上传播
     * （引导页只是读状态，不允许因为它崩）。
     */
    fun probe(context: Context): HotspotState {
        val viaState = runCatching {
            wifiManager(context)?.let { wm ->
                Integer.parseInt(
                    wm.javaClass.getMethod("getWifiApState").invoke(wm).toString()
                )
            }.let { fromRawState(it ?: -1) }
        }.getOrDefault(HotspotState.UNKNOWN)

        val viaEnabled = runCatching {
            wifiManager(context)?.let { wm ->
                val b = wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as? Boolean
                if (b == true) HotspotState.ENABLED else if (b == false) HotspotState.DISABLED else HotspotState.UNKNOWN
            } ?: HotspotState.UNKNOWN
        }.getOrDefault(HotspotState.UNKNOWN)

        val viaSticky = runCatching { stickyState(context) }.getOrDefault(HotspotState.UNKNOWN)
        return resolve(viaState, viaEnabled, viaSticky)
    }

    // ---- Android 读取（不做单测；口径已由纯函数钉死） ----

    private fun wifiManager(context: Context): WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private fun stickyState(context: Context): HotspotState {
        val intent = context.applicationContext.registerReceiver(
            null, IntentFilter(STICKY_ACTION)
        ) ?: return HotspotState.UNKNOWN
        val raw = intent.getIntExtra(STICKY_EXTRA, -1)
        return if (raw == -1) HotspotState.UNKNOWN else fromRawState(raw)
    }
}

/** 车机热点三值状态。UNKNOWN ≠ DISABLED：前者引导复检，后者才引导去开。 */
enum class HotspotState { ENABLED, DISABLED, UNKNOWN }
