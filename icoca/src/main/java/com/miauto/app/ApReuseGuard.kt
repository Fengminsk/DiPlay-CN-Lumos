package com.miauto.app

/**
 * AP 复用维护层的纯判定（方案 G4）—— 注入/补偿的门卫，全部 JVM 可测。
 *
 * 把「什么时候允许注入」「什么时候允许补偿通知」从 Android 胶水里抽出来，与
 * {@link CastGate} / {@link ApReusePolicy} 同一风格：真机行为要等车机，判定先在单测里钉死。
 */
object ApReuseGuard {

    /** 注入默认信道（方案 §6 注意 2：真实信道被权限墙挡住读不到，5G ch36 是最常见值）。 */
    const val DEFAULT_CHANNEL = 36

    /** 功能是否已武装：总开关开 **且** 建链方式是 ap。false 时连 bindService 都不做（零副作用铁律）。 */
    fun isArmed(flagOn: Boolean, transport: String): Boolean = flagOn && "ap" == transport

    /**
     * 补偿通知的执行判据（路径二）：
     * - `apCreated == true` → SDK 原生路径已通知过，绝不重复推；
     * - `notifier == null` → 注入器还没把 Runnable 放进去，先注入再谈；
     * - 其余 → 允许执行一次通知（幂等性由「generation + apCreated 复查」保证）。
     */
    fun shouldNotify(apCreated: Boolean?, notifierPresent: Boolean): Boolean =
        apCreated != true && notifierPresent

    /**
     * 会话已建立时还要不要补偿：不要。能建立起会话，说明手机已经拿到了 AP 信息
     * （原生路径命中）或走了别的通道 —— 此时再推一套凭据只会制造第三种状态。
     */
    fun compensateOnSessionUp(apCreated: Boolean?): Boolean = apCreated != true

    /**
     * 接入看门狗补偿判据（T2）：SDK 尚未判定接入（`apConnected != true`，字段读不到时
     * 按「未接入」处理，幂等性由反射写入前的复查兜底）且现场存在实测可达的存量客户端
     * → 需要替 SDK 补发「客户端已接入」。`apConnected == true` 时 SDK 自己已通知过，
     * 任何补丁都是多余的。
     */
    fun shouldPatchAcceptWatchdog(apConnected: Boolean?, clientLive: Boolean): Boolean =
        apConnected != true && clientLive

    /** 状态映射：探测层三值 → 策略层三值（解耦两个枚举，防以后一边改了另一边漏）。 */
    internal fun mapState(state: HotspotState): ApReusePolicy.ApState = when (state) {
        HotspotState.ENABLED -> ApReusePolicy.ApState.ENABLED
        HotspotState.DISABLED -> ApReusePolicy.ApState.DISABLED
        HotspotState.UNKNOWN -> ApReusePolicy.ApState.UNKNOWN
    }
}
