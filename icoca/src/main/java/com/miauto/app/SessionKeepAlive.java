package com.miauto.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;

/**
 * 「会话期间别让车机省电策略掐断链路」的两把锁：Wi-Fi 高性能锁 + CPU 部分唤醒锁。
 *
 * <p>从 {@link IccoaManager} 抽出来的原因只有一个：那是个 1600 行、同时管 SDK 生命周期、投屏、
 * 音频焦点、按键回传的类，而这两把锁和它们一点关系都没有，只需要「会话开始取、会话结束放」。
 *
 * <h3>为什么必须要</h3>
 * <p>车机的 Wi-Fi 省电策略在屏幕熄灭、应用退到后台、或系统认为「没有前台流量」时会把射频打进
 * 低功耗（DTIM 周期拉长 / 直接 idle）。这条链路上连坐三件事：
 * <ol>
 *   <li><b>音频卡顿</b>：音频是独立 RTP 流，包间隔抖动直接表现为断续（视频还能靠帧缓冲掩盖）；</li>
 *   <li><b>无线掉线</b>：SDK 有心跳定时器，{@code onHeartBeatTimeout()} 到点就
 *       {@code mConnectProxy.disconnect()} —— 错过几个心跳，链路自己就断了；</li>
 *   <li><b>P2P 组被回收</b>：GC 回收组后必须重走整套握手，用户看到的就是「一会儿又断了」。</li>
 * </ol>
 * {@code WIFI_MODE_FULL_LOW_LATENCY}（API 29+）就是系统给这类实时流的官方出口；CPU 锁同理，
 * 避免调度器把解码/播放线程压到低频核上。
 *
 * <p>锁的粒度是「会话」：连上就取，断开/失败/停机立刻放（{@link IccoaManager} 的四条路径）。
 * 不设超时是有意的 —— 车机在 ACC ON 期间本就常供电，锁泄漏的上限是「进程被杀时系统自动释放」。
 */
final class SessionKeepAlive {

    /** 日志出口（IccoaManager 的 log()，带前缀且会转发给 UI 层）。 */
    interface Log {
        void line(String msg);
    }

    private final Context appCtx;
    private final Log log;

    private WifiManager.WifiLock wifiLock = null;
    private PowerManager.WakeLock cpuLock = null;

    SessionKeepAlive(Context appCtx, Log log) {
        this.appCtx = appCtx;
        this.log = log;
    }

    /** 取锁（幂等：已持有则什么都不做）。 */
    void acquire() {
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) appCtx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    int mode = android.os.Build.VERSION.SDK_INT >= 29
                            ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY   // 专为音视频低延迟设计
                            : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                    wifiLock = wm.createWifiLock(mode, "MiAuto:ICCOA");
                }
            }
            if (wifiLock != null && !wifiLock.isHeld()) {
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                log.line("✓ Wi-Fi 高性能锁已持有（mode="
                        + (android.os.Build.VERSION.SDK_INT >= 29 ? "LOW_LATENCY" : "HIGH_PERF") + "）");
            }
        } catch (Throwable t) {
            log.line("✗ Wi-Fi 锁获取失败（继续运行，但可能被省电策略影响）: " + t);
        }
        try {
            if (cpuLock == null) {
                PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiAuto:ICCOA:Session");
                    cpuLock.setReferenceCounted(false);
                }
            }
            if (cpuLock != null && !cpuLock.isHeld()) {
                cpuLock.acquire();
                log.line("✓ CPU 部分唤醒锁已持有（投屏期间不休眠）");
            }
        } catch (Throwable t) {
            log.line("✗ CPU 唤醒锁获取失败: " + t);
        }
    }

    /**
     * 当前是否持有 Wi-Fi 锁。
     *
     * <p>只给诊断用：断连现场要能一眼看出「锁是不是还在」，否则无法排除「省电策略把链路掐了」
     * 这条假设 —— 从日志上看，锁已释放和锁从未获取是同一副样子。
     */
    boolean isWifiHeld() {
        try {
            return wifiLock != null && wifiLock.isHeld();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前是否持有 CPU 锁（同上，仅诊断）。 */
    boolean isCpuHeld() {
        try {
            return cpuLock != null && cpuLock.isHeld();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 放锁（幂等：没持有就跳过，重复调用安全）。 */
    void release() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
                log.line("Wi-Fi 高性能锁已释放");
            }
        } catch (Throwable t) {
            log.line("Wi-Fi 锁释放异常: " + t);
        }
        try {
            if (cpuLock != null && cpuLock.isHeld()) {
                cpuLock.release();
                log.line("CPU 部分唤醒锁已释放");
            }
        } catch (Throwable t) {
            log.line("CPU 锁释放异常: " + t);
        }
    }
}
