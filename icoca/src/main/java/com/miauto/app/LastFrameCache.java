package com.miauto.app;

import android.graphics.Bitmap;
import android.os.SystemClock;

/**
 * 投屏「最后一帧」缓存：切出投屏页时留一张画面，切回来先拿它占位，避免首帧到达前显示纯黑。
 *
 * <p>投屏页用的是 {@code SurfaceView}，其 Surface 被系统回收再重建后内容是<b>空白</b>的；
 * 重挂输出面后解码器还要等手机侧推来下一个可解码帧才出画，这段空窗在车机上就是纯黑。
 * 本类缓存的是<b>已渲染出来的像素</b>（{@link LastFrameGrabber} 用 {@code PixelCopy} 拷出），
 * 只有一张与投屏画面同尺寸（长边超过 {@link #maxEdge} 时才缩）的 {@code RGB_565} 位图，随会话作废。
 *
 * <p>作废时机（缺一个就会显示过期画面）：会话结束、切换投屏设备、分辨率或横竖屏变化、
 * 超过 {@link #MAX_AGE_MS}。
 *
 * <p>抓帧回调与 UI 读取分处两个线程，故全部 {@code synchronized}。作废判定抽成静态纯函数以便单测。
 */
final class LastFrameCache {

    /** 日志出口（{@code IccoaManager} 的 {@code log()}，带前缀且会转发给 UI 层）。 */
    interface Log {
        void line(String msg);
    }

    /**
     * 缓存位图的最大长边（<b>运行时</b>由 {@link #setMaxEdge} 注入，取屏幕真实长边）。
     *
     * <p><b>为什么不是常量</b>：它必须让投屏画面<b>原尺寸留存</b> —— 占位图按 {@code fitCenter}
     * 铺满整屏，小于 Surface 的位图会被放大显示，放大多少就糊多少（2880x1800 被限到 1920x1200
     * 再放大 1.5 倍，就是用户看到的「恢复时第一帧发虚」）。写死成任何一个数，在屏幕更长或更短
     * 的车机上都会失效：大了白吃内存，小了就糊。所以跟着 {@code DeviceDisplay} 量到的真实屏走。
     *
     * <p>{@code 0} = 屏幕还没量到（见 {@link DeviceDisplay#NOT_READY}），此时不抓帧也不占位 ——
     * 拿不到分辨率就先不缓存，下一次切出时再试，比缓存一张尺寸不明的图安全。
     *
     * <p>内存账：{@code 2880x1800} 的 {@code RGB_565} 约 10 MB（Android 8+ 算在 native 堆上，
     * 不占 Java 堆上限），只在「切出投屏页」到「下次切出」之间持有。真 OOM 时
     * {@link LastFrameGrabber} 会退到半尺寸重试。
     */
    private int maxEdge = 0;

    /** 缓存有效期。超过就当作废，避免隔了很久给用户看一张过期画面。 */
    static final long MAX_AGE_MS = 10 * 60 * 1000L;

    private final Log log;

    private Bitmap bitmap = null;
    /** 缓存帧属于哪台手机（{@code IccoaManager.lastDeviceId}）。 */
    private String deviceId = null;
    /** 缓存帧对应的 Surface 像素尺寸 —— 分辨率 / 横竖屏变化的判据。 */
    private int width = 0, height = 0;
    /** 抓取时刻（{@link SystemClock#uptimeMillis()}）。 */
    private long capturedAtMs = 0L;

    /** UI 此刻是否正在显示缓存帧；显示期间不回收位图（画它的是另一个 {@code ImageView}）。 */
    private boolean displaying = false;

    /** 显示期间被作废、等 UI 断掉引用（{@link #setDisplayed}）后再回收的位图。 */
    private Bitmap pendingRecycle = null;

    LastFrameCache(Log log) {
        this.log = log;
    }

    // ------------------------------------------------------------------
    // 纯判定（可单测，不碰 Android 对象）
    // ------------------------------------------------------------------

    /** 当前生效的最大长边；{@code 0} 表示屏幕还没量到。 */
    synchronized int maxEdge() {
        return maxEdge;
    }

    /**
     * 注入屏幕的真实长边（像素），由 {@link IccoaManager#setScreenSize} 在量到分辨率后调用。
     *
     * <p>只在<b>变大</b>时更新：投屏中途换到更大的屏（外接显示器 / 旋转后重新量）时上限要跟上，
     * 而一次量歪（多窗口下窗口被压缩）不该把已经缓存好的原尺寸图判成超限。
     */
    synchronized void setMaxEdge(int longSidePx) {
        if (longSidePx > maxEdge) maxEdge = longSidePx;
    }

    /**
     * 按 {@code maxEdge} 等比缩到目标位图尺寸。长边不超过上限时<b>原样返回</b>（不缩放）。
     *
     * <p>上限是参数而非常量：它由运行时量到的屏幕尺寸决定（见 {@link #maxEdge}），
     * 单测里显式传值即可，不必依赖某个「典型屏幕」。
     *
     * @return {@code int[]{w, h}}；输入非法或上限未就绪时返回 {@code {0, 0}}（调用方据此放弃抓帧）
     */
    static int[] fitSize(int w, int h, int maxEdge) {
        if (w <= 0 || h <= 0 || maxEdge <= 0) return new int[]{0, 0};
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) return new int[]{w, h};
        int outW, outH;
        if (w >= h) {
            outW = maxEdge;
            outH = Math.max(1, Math.round(h * (float) maxEdge / w));
        } else {
            outH = maxEdge;
            outW = Math.max(1, Math.round(w * (float) maxEdge / h));
        }
        // 偶数对齐：RGB_565 缩放时奇数边会在边缘留出色带。
        // 只有下面这条缩小分支才需要 —— 上面原尺寸留存时不重采样，也就没有色带问题。
        outW &= ~1;
        outH &= ~1;
        return new int[]{Math.max(2, outW), Math.max(2, outH)};
    }

    /** 缓存是否已经过期（含「从未抓过」）。 */
    static boolean stale(long nowMs, long capturedAtMs) {
        if (capturedAtMs <= 0L) return true;
        long age = nowMs - capturedAtMs;
        // age < 0 只可能是 uptime 回绕（理论上不会发生），当作过期处理最安全。
        return age < 0L || age > MAX_AGE_MS;
    }

    /**
     * 换了手机（画面属于别人）。
     *
     * <p>任一侧未知时不猜：SDK 在部分跃迁上给出的 {@code deviceID} 是 {@code null}，
     * 那时断言「手机换了」会把一张完全可用的占位图扔掉，占位图就退化成黑屏。
     */
    static boolean deviceChanged(String cachedDevice, String nowDevice) {
        return cachedDevice != null && nowDevice != null && !cachedDevice.equals(nowDevice);
    }

    /**
     * 尺寸或方向变了（横竖屏 / 分辨率）：占位图会被拉伸错位，照着它点就点歪了。
     *
     * <p>任一侧无效（Surface / View 还没测量）时一律不算变 —— 那时占位图正是最该出现的时刻，
     * 拿 0 去比会把一张完全可用的画面当成「尺寸不符」删掉。
     */
    static boolean sizeChanged(int cachedW, int cachedH, int nowW, int nowH) {
        if (cachedW <= 0 || cachedH <= 0 || nowW <= 0 || nowH <= 0) return false;
        return cachedW != nowW || cachedH != nowH;
    }

    // ------------------------------------------------------------------
    // 状态（synchronized：抓帧回调线程与 UI 线程都会进来）
    // ------------------------------------------------------------------

    synchronized boolean hasFrame() {
        return bitmap != null && !bitmap.isRecycled();
    }

    /**
     * 存入新抓到的一帧，旧的立刻回收。
     *
     * @param bmp    已抓好的位图；本类此后独占它的生命周期（含 {@code recycle()}）
     * @param device 手机 ID
     * @param w      对应的 Surface 宽（不是位图宽 —— 位图可能已被 {@link #fitSize} 缩小）
     * @param h      对应的 Surface 高
     */
    synchronized void store(Bitmap bmp, String device, int w, int h) {
        if (bmp == null) return;
        Bitmap old = bitmap;
        bitmap = bmp;
        deviceId = device;
        width = w;
        height = h;
        capturedAtMs = SystemClock.uptimeMillis();
        if (old != null && old != bmp) discard(old);
    }

    /**
     * 取缓存帧给 UI 显示；过期或已回收则返回 null 并顺手清掉。
     *
     * <p><b>调用方不要 {@code recycle()} 它</b>：位图仍归本类所有，下次切出还要用。
     * 用完只需让 ImageView 断掉引用即可。
     */
    synchronized Bitmap peek() {
        if (!hasFrame()) {
            if (bitmap != null || deviceId != null) invalidate("缓存位图已被回收");
            return null;
        }
        if (stale(SystemClock.uptimeMillis(), capturedAtMs)) {
            invalidate("缓存已超过 " + (MAX_AGE_MS / 60_000L) + " 分钟");
            return null;
        }
        return bitmap;
    }

    /** 手机换了 → 作废。返回是否真的丢了。 */
    synchronized boolean dropIfDeviceChanged(String device) {
        if (!hasFrame()) return false;
        if (!deviceChanged(deviceId, device)) return false;
        invalidate("切换投屏设备（" + deviceId + " → " + device + "）");
        return true;
    }

    /** 尺寸 / 方向变化 → 作废。返回是否真的丢了。 */
    synchronized boolean dropIfSizeChanged(int w, int h) {
        if (!hasFrame()) return false;
        if (!sizeChanged(width, height, w, h)) return false;
        invalidate("Surface 尺寸变化（" + width + "x" + height + " → " + w + "x" + h + "）");
        return true;
    }

    /**
     * UI 声明自己开始 / 结束显示缓存帧（见 {@link #displaying}）。
     *
     * <p>{@code on=true} 之后本类<b>一张都不回收</b>，{@code on=false} 时才把这段时间里攒下的
     * 待回收位图释放掉。调用方必须在 {@code ImageView} 断掉引用<b>之后</b>调 {@code false} ——
     * 顺序反了，缓存就可能在 ImageView 还持有引用时把位图回收掉。
     */
    synchronized void setDisplayed(boolean on) {
        if (on) {
            displaying = true;
            return;
        }
        displaying = false;
        if (pendingRecycle != null) {
            recycle(pendingRecycle);
            pendingRecycle = null;
        }
    }

    /** 作废并释放位图。重复调用无副作用。 */
    synchronized void invalidate(String reason) {
        boolean had = bitmap != null || deviceId != null;
        Bitmap old = bitmap;
        bitmap = null;
        deviceId = null;
        width = 0;
        height = 0;
        capturedAtMs = 0L;
        discard(old);
        if (had && log != null) log.line("最后一帧缓存已释放（" + reason + "）");
    }

    /**
     * 释放一张不再使用的位图 —— 显示中就推迟到 {@link #setDisplayed(false)}。
     *
     * <p><b>为什么显示期间一张都不回收</b>：{@code displaying} 只说明「UI 还挂着某张位图」，
     * 并不能确定是哪一张 —— 缓存可能在 UI 拿到之后又被作废、又被新帧顶掉。此刻还去
     * {@code recycle()} 其中任何一张，都有机会正好回收掉屏幕上那张。
     *
     * <p>所以这里只保留最后一张待回收的，更早的那些直接丢给 GC（{@code Bitmap} 的
     * {@code BitmapFinalizer} 会释放其 native 像素）。代价是最多滞留一张位图 ——
     * {@code 2880x1800} 的 {@code RGB_565} 约 10 MB —— 且只在「显示期间缓存被连续作废两次」
     * 这种罕见交错下发生 —— 比崩在绘制里划算得多。
     */
    private void discard(Bitmap bmp) {
        if (bmp == null) return;
        if (displaying) {
            pendingRecycle = bmp;
        } else {
            recycle(bmp);
        }
    }

    private static void recycle(Bitmap bmp) {
        try {
            if (!bmp.isRecycled()) bmp.recycle();
        } catch (Throwable ignored) {
            // recycle 失败只是少回收一点内存，不影响正确性。
        }
    }
}
