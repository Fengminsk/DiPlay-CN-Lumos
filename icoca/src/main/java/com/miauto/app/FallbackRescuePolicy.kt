package com.miauto.app

/**
 * 「5 秒兜底广播自救」的准入策略（纯函数，无 Android 依赖）。
 *
 * <p>兜底广播原本是为了救「SDK 压根没起来」：老车机缺 arm64 原生库、init 抛异常、
 * SDK 内部线程起不来时，`onInitSuccess()` 永远不回来，`doAdvertise()` 也就永远不会跑 ——
 * 这时车机一条广播都不发，手机连发现都发现不了，值得独立发一条传统 BLE 广播兜底。
 *
 * <p><b>但「广播被热点闸主动拦下」不是故障</b>（见 [AdvertiseGate]）。SDK 已经 init 成功、
 * 门禁判定热点没开而拦下 `doAdvertise()`，是设计意图：界面停在「等待开启车载热点」，
 * 用户开完热点再点「连接」重新走一次 SDK 广播。此时若因为「5 秒内没走到
 * startAdvertise()」就发兜底广播，会同时造成三个后果：
 * <ol>
 *   <li>传统 BLE 广播只有广播帧，<b>没有 SDK 的 GATT 服务与状态机承接</b>：手机扫到、连上
 *       ACL、发现不了服务就断开，几秒一轮，手机端表现就是反复「连接失败」；</li>
 *   <li>兜底广播过同一道准入检查时会把界面阶段写成 `Advertising`，首页因此收起
 *       「连接」按钮、状态显示「等待手机连接」—— 用户以为车机已经在连，实际一条 SDK
 *       广播都没发出去；</li>
 *   <li>`advertisingArmed` 仍为 false，30 秒看门狗直接 return，状态再也回不来，
 *       只能杀进程重开让门禁重新判一次。</li>
 * </ol>
 *
 * <p>2026-11-06 18:43 的现场正是这条链：18:43:05 启动瞬间热点 DISABLED 被拦 →
 * 18:43:09 用户开热点 → 18:43:10 5 秒计时器到点发兜底广播 → 六轮 ACL 连/断 →
 * 18:44:08 杀掉重开、门禁在热点 ENABLED 下放行，一次连上。
 *
 * <p>所以判据只看三件事，且顺序不可换：兜底是否已在跑、SDK 是否真的发出过广播、
 * SDK 是否已就绪。SDK 就绪却没广播 = 人在等用户操作，不是需要自救的故障。
 */
enum class FallbackRescueDecision {
    /** SDK 没起来 —— 发传统广播，保住「手机能发现车机」这条底线。 */
    RESCUE,

    /** 兜底广播已经在跑，重复启动只会换掉序列号、打断配对会话。 */
    SKIP_ALREADY_RUNNING,

    /** SDK 已走到 startAdvertise()，兜底广播由 800ms 延时任务并行发出。 */
    SKIP_SDK_ADVERTISING,

    /** SDK 已就绪、本次广播被门禁主动拦下 —— 等用户点「连接」，绝不自救。 */
    SKIP_GATE_BLOCKED,
}

object FallbackRescuePolicy {

    /**
     * @param sdkReady          `onInitSuccess()` 是否已经回来（SDK 初始化成功）
     * @param advertiseAttempted 本次进程是否已经走到过 `adapter.startAdvertise()`
     * @param fallbackRunning   传统兜底广播当前是否正在发射
     */
    fun decide(
        sdkReady: Boolean,
        advertiseAttempted: Boolean,
        fallbackRunning: Boolean,
    ): FallbackRescueDecision = when {
        fallbackRunning -> FallbackRescueDecision.SKIP_ALREADY_RUNNING
        advertiseAttempted -> FallbackRescueDecision.SKIP_SDK_ADVERTISING
        // SDK 好着、却没广播：只可能是准入闸拦下了（或用户还没点「连接」）。
        // 发一条没有 GATT 承接的广播比不发更糟 —— 见类注释的三条后果。
        sdkReady -> FallbackRescueDecision.SKIP_GATE_BLOCKED
        else -> FallbackRescueDecision.RESCUE
    }
}
