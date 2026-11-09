package com.miauto.app

/**
 * 广播准入策略（纯函数）：给定「连接方式 + 设备热点状态 + 用户是否已跳过」，决定这一次 BLE
 * 广播放不放行。
 *
 * <p><b>每一次广播之前都要过</b>，不只是首次启动：首次起链、主页「连接」、手动刷新配对码、
 * 30 秒空转重播、兜底广播（`FallbackBleAdvertiser`）都走同一条判定。因此这里只有两条要求 ——
 * 便宜、无副作用。
 *
 * <p><b>为什么非要拦</b>：AP 与 P2P 硬互斥（见 `IccoaManager.transportMode()`）。
 * <ul>
 *   <li><b>车载热点（ap）</b>：手机拿到凭据后要加入车机自己的热点才能投屏。热点没开时
 *       SDK 的 SoftAP 分支要么走 reuse（`mSsid` 残留、不重跑入网 → 20s 协商超时 → 10008），
 *       要么走 recreate（先 `stopTethering` 把热点真关掉、再因缺 `TETHER_PRIVILEGED` 重建失败
 *       → 10003）。两条都是用户看不见原因的失败 —— 与其「广播出去等着失败」，不如不广播，
 *       并把用户送去开热点。</li>
 *   <li><b>Wi-Fi Direct（p2p）</b>：SDK 自行组网，与热点无关 → 一律放行，连状态都不读。</li>
 * </ul>
 *
 * <p><b>UNKNOWN 的处理</b>：热点状态三级回退全失败时按「未开启」引导，但**不当作硬错误**
 * （见 `CarHotspotStatus` 的纪律）—— 弹窗给「仍然继续」出口，用户选择后 [bypassed] 生效，
 * 本进程内不再拦。这样隐藏了热点状态的 ROM 不会把车机卡成「永远不广播」。
 *
 * <p>纯函数，无 Android 依赖；口径由 `AdvertiseGateTest` 钉住。
 */
enum class LinkMode {
    /** 车载热点（transport=ap）：车机自建热点，手机加入。 */
    CAR_HOTSPOT,

    /** Wi-Fi Direct（transport=p2p）：SDK 自行组网。 */
    WIFI_DIRECT,
}

enum class AdvertiseAction {
    /** 放行 —— 照常广播。 */
    ALLOW,

    /** 本次不广播 —— 提示并引导用户开启热点。 */
    PROMPT_HOTSPOT,
}

object AdvertiseGate {

    /**
     * 建链方式 → 门禁模式。
     *
     * `ap` 之外的任何取值都按 Wi-Fi Direct 处理：`both` 这类历史值在 `IccoaManager` 里已收敛，
     * 这里再做一次「非 ap 即 p2p」的兜底，避免偏门取值把车机挡在门禁外面。
     */
    fun modeOf(apTransport: Boolean): LinkMode =
        if (apTransport) LinkMode.CAR_HOTSPOT else LinkMode.WIFI_DIRECT

    /**
     * 广播准入判定。
     *
     * @param bypassed 用户已在引导弹窗里选择「仍然继续」—— 本进程内不再拦。
     */
    fun decide(mode: LinkMode, hotspotState: HotspotState, bypassed: Boolean): AdvertiseAction = when {
        bypassed -> AdvertiseAction.ALLOW
        mode == LinkMode.WIFI_DIRECT -> AdvertiseAction.ALLOW
        hotspotState == HotspotState.ENABLED -> AdvertiseAction.ALLOW
        // DISABLED 与 UNKNOWN 同一分支（都要引导），只有文案不同 —— 见 isUncertain()。
        else -> AdvertiseAction.PROMPT_HOTSPOT
    }

    /** 读不到状态：文案换成「无法确认」，避免把探测失败说成「已关闭」。 */
    fun isUncertain(hotspotState: HotspotState): Boolean = hotspotState == HotspotState.UNKNOWN
}
