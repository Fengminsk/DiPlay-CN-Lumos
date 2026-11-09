package com.miauto.app;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.RequiresApi;

/**
 * 用 {@code PixelCopy} 从投屏的 {@code SurfaceView} 上拷一帧出来，供 {@link LastFrameCache} 占位。
 *
 * <p>{@code PixelCopy} 从 SurfaceFlinger 拷 SurfaceView 自己那张图层（不含盖在它上面的 View），
 * 不介入渲染管线。抓帧只在切出时执行一次；探测（{@link #sample}）在最后一帧占位期间周期性执行，
 * 拷的缩略图只有 4.6 KB，对推流帧率与延迟没有影响。
 *
 * <p>两个前提：
 * <ol>
 *   <li>只能在 Surface 还活着时发起 —— Surface 一旦销毁 {@code PixelCopy} 必然失败，
 *       所以调用点在 {@code Activity.onPause}，不能等到 {@code surfaceDestroyed}；</li>
 *   <li>仅 API 26+，低版本一律先问 {@link #supported()} 并降级为不占位。</li>
 * </ol>
 *
 * <p>单独成类是为了把 {@code PixelCopy} 的引用隔离在 {@code @RequiresApi} 之下，
 * 避免 minSdk 16 的 lint NewApi 报错。
 */
final class LastFrameGrabber {

    interface Callback {
        /** 抓帧成功。{@code bmp} 的生命周期此后归调用方（通常是 {@link LastFrameCache}）。 */
        void onCaptured(Bitmap bmp, int surfaceW, int surfaceH);

        /** 抓帧失败（{@code PixelCopy} 的结果码，或 {@link #RESULT_UNSUPPORTED}）。调用方按原样降级。 */
        void onFailed(int result);
    }

    /** 设备不支持（API &lt; 26）。 */
    static final int RESULT_UNSUPPORTED = -1;

    /** 请求没能发出（Surface 尺寸无效 / 位图 OOM / {@code PixelCopy} 直接抛）。 */
    static final int RESULT_FAILED = -2;

    /** 「Surface 是否已出画」的探测结果的接收端。 */
    interface SampleCallback {
        /** @param hasContent true = 此刻 Surface 上有内容（不是一张空白/纯黑的画布）。 */
        void onSample(boolean hasContent);
    }

    /**
     * 探测缩略图的尺寸。只用来判断「有没有内容」，不需要能看清画面：
     * 长边 64 的 {@code RGB_565} 只有 4.6 KB，缩放由 SurfaceFlinger 完成。
     */
    private static final int SAMPLE_W = 64, SAMPLE_H = 36;

    private LastFrameGrabber() { }

    /** 本设备能不能抓帧。调用方应先用它做门禁。 */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * 异步拷一帧。结果在 {@code handler} 所在线程回调。
     *
     * <p>位图尺寸由 {@link LastFrameCache#fitSize} 决定（{@code maxEdge} 是运行时量到的
     * 屏幕真实长边，见 {@link DeviceDisplay}），格式 {@code RGB_565}：占位图不透明，省一半
     * 内存，缩放由 SurfaceFlinger 完成。
     *
     * @param maxEdge 屏幕真实长边（像素）；{@code <= 0} 表示还没量到，此时放弃抓帧
     *                —— 缓存一张尺寸不明的图，不如等下一次切出时再试
     * @return true 表示请求已发出（不代表成功，成功与否看回调）
     */
    static boolean capture(SurfaceView view, Handler handler, int maxEdge, Callback cb) {
        if (view == null || cb == null) return false;
        if (!supported()) {
            cb.onFailed(RESULT_UNSUPPORTED);
            return false;
        }
        if (maxEdge <= 0) {
            cb.onFailed(RESULT_FAILED);
            return false;
        }
        int[] surfaceSize = surfaceSize(view);
        final int sw = surfaceSize[0], sh = surfaceSize[1];
        if (sw <= 0 || sh <= 0) {
            cb.onFailed(RESULT_FAILED);
            return false;
        }
        // 先按 Surface 原尺寸要一张（长边不超过 maxEdge 时 fitSize 不缩放），占位图才不发虚。
        int[] full = LastFrameCache.fitSize(sw, sh, maxEdge);
        Bitmap bmp = create(full);
        if (bmp == null) {
            // 低端车机上 native 内存可能不够：退到半尺寸再试一次 —— 半尺寸会糊，但比黑屏强。
            // 减半要减在「Surface 尺寸」上，不是减在上限上：长边本就 ≤ maxEdge / 2 的小屏车机
            // （800x480 / 1024x600 / 1280x720 恰恰最容易 OOM）按上限减半算出来跟第一次一模一样，
            // 那等于拿同一个尺寸再撞一次 OOM。算完确实变小了才值得重试。
            int[] half = LastFrameCache.fitSize(sw / 2, sh / 2, maxEdge);
            if (half[0] != full[0] || half[1] != full[1]) bmp = create(half);
        }
        if (bmp == null) {
            // 还是失败：宁可不占位，也不能把进程拖垮。
            cb.onFailed(RESULT_FAILED);
            return false;
        }
        final Bitmap frame = bmp;
        try {
            // 回调也搬进 request()：那里才有 @RequiresApi(26)，把 PixelCopy 的所有引用
            // （包括监听器这个匿名类）都关在同一处，minSdk 16 的 lint 才不会报 NewApi。
            request(view, frame, handler, (bmpOut, result) -> {
                if (result == 0) {
                    cb.onCaptured(bmpOut, sw, sh);
                } else {
                    recycle(bmpOut);
                    cb.onFailed(result);
                }
            });
            return true;
        } catch (Throwable t) {
            // PixelCopy 在个别 ROM 上会直接抛（Surface 已失效 / 窗口已 detach）。
            recycle(frame);
            cb.onFailed(RESULT_FAILED);
            return false;
        }
    }

    /** 按给定尺寸开一张 {@code RGB_565} 位图；开不出来（尺寸非法 / OOM）返回 null，不抛。 */
    private static Bitmap create(int[] size) {
        if (size == null || size[0] <= 0 || size[1] <= 0) return null;
        try {
            return Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 探测 {@code Surface} 此刻是否已经<b>真的出画</b>（异步，结果在 {@code handler} 所在线程回调）。
     *
     * <p>与 {@link #capture} 的区别：抓帧要的是能看清的画面，这里只要一个「有 / 没有内容」的
     * 布尔值，拷一张 64x36 缩略图就够（4.6 KB，缩放由 SurfaceFlinger 完成）。缩略图由本类创建
     * 并在回调前回收，调用方只拿到布尔值。
     *
     * <p>为什么需要这条判据、为什么不能用 SDK 的「首帧已渲染」回调，见
     * {@code IccoaManager#probeSurfaceContent}。
     */
    static boolean sample(SurfaceView view, Handler handler, SampleCallback cb) {
        if (view == null || cb == null) return false;
        if (!supported()) {
            cb.onSample(false);       // 低版本没有 PixelCopy：一律当作「还没出画」
            return false;
        }
        int[] surfaceSize = surfaceSize(view);
        if (surfaceSize[0] <= 0 || surfaceSize[1] <= 0) {
            cb.onSample(false);
            return false;
        }
        final Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.RGB_565);
        } catch (Throwable t) {
            cb.onSample(false);
            return false;
        }
        try {
            request(view, bmp, handler, (bmpOut, result) -> {
                boolean has = result == 0 && contentOf(bmpOut);
                recycle(bmpOut);
                cb.onSample(has);
            });
            return true;
        } catch (Throwable t) {
            recycle(bmp);
            cb.onSample(false);
            return false;
        }
    }

    /**
     * 缩略图里是否有非黑像素 —— 有就说明解码器已经把画面画上去了。
     *
     * <p><b>为什么只看 RGB 三个分量</b>：{@code getPixels} 会把 {@code RGB_565} 的每个像素
     * 一律补成不透明的 {@code 0xFF______}。若拿 {@code p != 0} 当判据，连一张还没出画的空白
     * 缩略图也会被算成「有内容」，撤掉占位图就正好露出纯黑 —— 必须把 alpha 掩掉。
     *
     * <p>纯函数（不碰 Android 对象），便于单测。
     */
    static boolean hasContent(int[] pixels) {
        if (pixels == null) return false;
        for (int p : pixels) {
            if ((p & 0x00FFFFFF) != 0) return true;
        }
        return false;
    }

    private static boolean contentOf(Bitmap bmp) {
        try {
            int[] pixels = new int[SAMPLE_W * SAMPLE_H];
            bmp.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H);
            return hasContent(pixels);
        } catch (Throwable t) {
            return false;   // 位图已回收 / 尺寸不对：当作没内容，下次再探
        }
    }

    private static void recycle(Bitmap bmp) {
        try {
            if (!bmp.isRecycled()) bmp.recycle();
        } catch (Throwable ignored) { }
    }

    /**
     * Surface 的真实像素尺寸。优先取 {@code getSurfaceFrame()}：它不依赖 View 是否完成测量，
     * 而 {@code View.getWidth()} 在 {@code surfaceCreated} 刚回调时可能是 0。
     */
    private static int[] surfaceSize(SurfaceView view) {
        int w = view.getWidth(), h = view.getHeight();
        SurfaceHolder holder = view.getHolder();
        if (holder != null) {
            Rect frame = holder.getSurfaceFrame();
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                w = frame.width();
                h = frame.height();
            }
        }
        return new int[]{w, h};
    }

    /**
     * 唯一一处引用 {@code PixelCopy} 的地方（含监听器），统一置于 {@code @RequiresApi} 之下。
     *
     * @param sink 结果接收端；{@code result == 0} 表示成功，否则是 {@code PixelCopy} 的错误码
     */
    @RequiresApi(26)
    private static void request(SurfaceView view, Bitmap dest, Handler handler, CopySink sink) {
        android.view.PixelCopy.request(view, dest, new android.view.PixelCopy.OnPixelCopyFinishedListener() {
            @Override
            public void onPixelCopyFinished(int result) {
                sink.onResult(dest, result == android.view.PixelCopy.SUCCESS ? 0 : result);
            }
        }, handler);
    }

    /** 一次 {@code PixelCopy} 的接收端。实现侧不引用 {@code PixelCopy}，见 {@link #request}。 */
    private interface CopySink {
        void onResult(Bitmap bmp, int result);
    }
}
