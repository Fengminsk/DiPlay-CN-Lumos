package com.miauto.app;

/**
 * 「这次 {@code surfaceChanged} 的尺寸该不该立刻绑给 SDK」的纯判定。
 *
 * <p>回到投屏页时 SurfaceView 可能先按竖尺寸回调一次 {@code surfaceChanged}，随后才切成终态尺寸。
 * 两次都绑就是短时间内换两次输出面，而每换一次面解码器都要等一个新的关键帧（表现为明显黑屏）。
 *
 * <p>判据是回调尺寸的<em>长短边关系</em>是否与协商画布相反：相反即过渡态，等终态那次再绑。
 * {@code alreadyBound} 为真时必须立刻绑 —— 协商尺寸按「长边在前」归一化，竖屏车机上真实竖屏
 * Surface 会被一直判成过渡态，靠这个条件可以把误判限制在首次绑定。
 */
final class SurfaceBindPolicy {

    enum Bind {
        /** 立刻把这个尺寸绑给 SDK。 */
        BIND_NOW,
        /** 先别绑：要么尺寸无效，要么是旋转过渡态，等终态那次（调用方负责兜底超时）。 */
        DEFER_FOR_SETTLE,
    }

    private SurfaceBindPolicy() { }

    /**
     * @param w            {@code surfaceChanged} 给的宽度（Surface 实际像素）
     * @param h            {@code surfaceChanged} 给的高度
     * @param alreadyBound 本条 Surface 是否已经成功绑过一次（{@code CastGate.isStarted()}）
     * @param negotiatedW  协商画布宽度
     * @param negotiatedH  协商画布高度
     */
    static Bind decide(int w, int h, boolean alreadyBound, int negotiatedW, int negotiatedH) {
        if (w <= 0 || h <= 0) return Bind.DEFER_FOR_SETTLE;   // 尺寸无效：绑上去只会错位
        if (alreadyBound) return Bind.BIND_NOW;               // 已经绑过一次 → 这是真实的尺寸变化
        return contradicts(w, h, negotiatedW, negotiatedH) ? Bind.DEFER_FOR_SETTLE : Bind.BIND_NOW;
    }

    /** 长短边关系是否与协商画布相反（正方形或协商尺寸非法时一律视为「不矛盾」）。 */
    static boolean contradicts(int w, int h, int negotiatedW, int negotiatedH) {
        if (w <= 0 || h <= 0 || w == h) return false;
        if (negotiatedW <= 0 || negotiatedH <= 0 || negotiatedW == negotiatedH) return false;
        return (negotiatedW > negotiatedH) != (w > h);
    }

    /**
     * 兜底绑定（{@code surfaceChanged} 没来 / 只来了过渡态）该用哪个尺寸。
     *
     * <p>不能直接用回调给的尺寸：回调时 View 可能还没测量完，而兜底是延时执行的，
     * 那时 Surface / View 早已是终态；用旧快照去绑会多换一次输出面、多等一个关键帧。
     *
     * <p>取值顺序：{@code getSurfaceFrame()} → {@code View 测量尺寸} → {@code 回调尺寸}，
     * 优先取「有效且与协商方向不矛盾」的，其次取第一个有效的，全无效才退回协商画布尺寸。
     *
     * @param surfaceW/H {@code holder.getSurfaceFrame()} 的尺寸（最权威，不依赖 View 测量）
     * @param viewW/H    {@code surfaceView.width/height}
     * @param cbW/H      当初那条回调给的尺寸（最后兜底用）
     * @param negW/H     协商画布尺寸
     * @return {@code int[]{w, h}}；协商尺寸也非法时返回 {@code {0, 0}}，调用方应放弃这次绑定
     */
    static int[] pickFallbackSize(int surfaceW, int surfaceH, int viewW, int viewH,
                                  int cbW, int cbH, int negW, int negH) {
        int[][] candidates = {
                {surfaceW, surfaceH},
                {viewW, viewH},
                {cbW, cbH},
        };
        int[] firstValid = null;
        for (int[] c : candidates) {
            if (c[0] <= 0 || c[1] <= 0) continue;
            if (firstValid == null) firstValid = c;
            if (!contradicts(c[0], c[1], negW, negH)) return new int[]{c[0], c[1]};
        }
        if (firstValid != null) return new int[]{firstValid[0], firstValid[1]};
        return new int[]{Math.max(0, negW), Math.max(0, negH)};
    }
}
