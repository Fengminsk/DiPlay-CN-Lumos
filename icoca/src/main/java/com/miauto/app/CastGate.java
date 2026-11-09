package com.miauto.app;

/**
 * 投屏 Surface 生命周期 → 「是否需要 (重新) startCast」的纯状态机。
 *
 * <p>关键不变式：Surface 被系统销毁后必须重置，否则切回时若 View 尺寸没变，
 * 「尺寸变化才重置」的分支不成立，{@code tryStartCast()} 会被「已投屏」挡掉，
 * SDK 继续往已释放的 Surface 上画（黑屏有声，需重连）。
 *
 * <p>本类不依赖 ICCOA SDK，可在 JVM 单测里钉死这些不变式（见 {@code CastGateTest}）。
 *
 * <p>SDK 回调在 worker 线程改状态，故 {@code started} 是 volatile。
 */
final class CastGate {

    /** 是否已经投屏。 */
    private volatile boolean started = false;

    /**
     * 最近一次测到的 Surface 实际像素尺寸 —— UIBC 坐标映射的除数基准。
     * 0 表示尚未测量，调用方应退回协商的 {@code videoW}/{@code videoH}。
     */
    private int width = 0, height = 0;

    /**
     * Surface 就绪 / 变化。
     *
     * <p>尺寸变化会把 Surface 从 SDK 脚下换掉，必须让下一次 {@link #isStarted()} 返回 false，
     * 从而触发重新绑定；尺寸不变时**不能**重置，否则每次 {@code surfaceChanged} 都会重绑一次。
     *
     * @return true 表示尺寸相对上次测量发生了变化（调用方可据此打日志 / 重测触摸基准）
     */
    boolean onSurface(int viewW, int viewH) {
        if (viewW <= 0 || viewH <= 0) return false;          // 未测量到，保留旧基准
        if (viewW == width && viewH == height) return false; // 同尺寸：不重绑
        width = viewW;
        height = viewH;
        started = false;
        return true;
    }

    /**
     * 投屏标志清零，尺寸基准保留。
     *
     * <p>两个调用场景：① Surface 被系统销毁（投屏页 {@code surfaceDestroyed}）—— 切回时新
     * Surface 的尺寸与旧的相同，{@link #onSurface} 不会返回 true，只有这里清了标志，
     * {@link #isStarted()} 才会放行下一次绑定；② 会话结束 / 停止 / 重播 —— 投屏已不成立。
     *
     * <p>尺寸刻意不清：同一个 View 切回来尺寸不会变，留着正好供 UIBC 继续用。
     */
    void reset() {
        started = false;
    }

    /** 记录一次投屏结果（{@code startCast} 的返回值，true 只代表已投递到 SDK 工作线程）。 */
    void markStarted(boolean ok) {
        started = ok;
    }

    boolean isStarted() {
        return started;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }
}
