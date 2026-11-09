package com.miauto.app;

/**
 * 「断开之后要不要自己连回去」的纯判定。
 *
 * <p><b>背景</b>：SDK 的所有断开都走同一个回调 {@code onConnectStateChanged(deviceID, 1|5, param)}，
 * 但不能一律重播 —— 车机一重新广播，手机端 CarWith 就会把熟悉的车机自动连回来，用户点了「断开」
 * 却发现几秒后又连上了。反过来，网络抖动导致的断连又必须自动恢复。
 *
 * <p>所以判定拆成两条独立输入：
 * <ul>
 *   <li>{@code paused}：车机侧是否已进入「等用户点连接」的停机状态（用户手动断开时会置位）；</li>
 *   <li>{@code param}：SDK 给的断开原因，{@code 11 = DISCONNECT_BY_USER}（{@code UCarConnectState
 *       .DISCONNECT_BY_USER}）表示对端用户主动断开，属于「人的决定」，同样不该自动连回去。</li>
 * </ul>
 * 其余（{@code param = -1} / 其他）一律视为异常断连 → 自动重连。
 *
 * <p>抽成独立类的原因与 {@link CastGate} 相同：这两种行为在车机上才看得出差别，
 * 放在 JVM 单测里把四条不变式钉死，比上车试错便宜得多（见 {@code LinkStatePolicyTest}）。
 */
final class LinkStatePolicy {

    /** SDK 的 {@code UCarConnectState.DISCONNECT_BY_USER}。 */
    static final int DISCONNECT_BY_USER = 11;

    /**
     * SDK 的连接状态码：{@code CAST_DISCONNECTED}（投屏停止）。
     *
     * <p>反编译 {@code UCarAdapter.onWfdSinkStopped()} 可见它抛的是
     * {@code notifyConnectStateChanged(id, 5, -1)} —— <b>param 恒为 -1</b>，不带真实断开原因。
     * 真正的用户/手机端主动断开码（{@code param=11}）只挂在随后到达的 {@code DISCONNECTED(1)} 上。
     */
    static final int CAST_DISCONNECTED = 5;

    enum Action {
        /** 保持停机：不重新广播，等用户点「连接」。 */
        STAY_STOPPED,
        /** 异常断连：重新广播，让手机自动连回来。 */
        AUTO_RECONNECT,
    }

    private LinkStatePolicy() { }

    /**
     * 收到 {@code CAST_DISCONNECTED(5)} 时，是否应当<b>把「重连/停机」的判定让给随后到达的
     * {@code DISCONNECTED(1)}</b>，此处先不下「异常断连 → 立即重播」的结论。
     *
     * <p>原因：state=5 的 param 恒为 -1（见 {@link #CAST_DISCONNECTED}），单看它无法区分「链路抖动
     * 掉线」与「手机端主动断开」——后者要等它的配对回调 state=1 带 {@code param=11} 才知道。
     * 若在此处就按 -1 判「异常断连、立即重播」，遇到用户/手机端主动断开时会先抢跑一次多余的重新
     * 广播（日志里表现为「异常断连 → 立即重新广播」紧接着一行「手机端主动断开 → 停机」），甚至在
     * state=1 稍晚到达时把用户刚点的断开又自动连回去。
     *
     * <p>已经停机（{@code paused}）时无需再defer：反正不会重播。
     */
    static boolean shouldDeferCastStop(int state, int param, boolean paused) {
        return state == CAST_DISCONNECTED && !paused;
    }

    /**
     * @param param  SDK 断开回调的第三个参数（原因码）
     * @param paused 车机侧是否已处于「用户要求停机」状态
     */
    static Action onDisconnected(int param, boolean paused) {
        if (paused) return Action.STAY_STOPPED;
        if (param == DISCONNECT_BY_USER) return Action.STAY_STOPPED;
        return Action.AUTO_RECONNECT;
    }
}
