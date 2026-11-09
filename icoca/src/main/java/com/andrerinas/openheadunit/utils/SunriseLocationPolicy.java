package com.andrerinas.openheadunit.utils;

/**
 * 「日出/日落」用哪个坐标 —— <b>纯策略</b>，不引用任何 Android 类型，可直接 JVM 单测。
 *
 * <h3>为什么需要单独一条策略</h3>
 *
 * <p>0.1.9 的判定是「有当前定位 且 不超过 10 分钟，或用户显式开了固定点」，否则认为
 * 「日出/日落没有可用经纬度」→ 按回退规则判成深色。这套判据在 CarWith（{@code ICCOA_ONLY}）
 * 下恒不成立：{@code LocationHolder} 只在 AA 的 {@code GpsLocationService} 与 AA 手机转发时
 * 被喂数据，ICCOA 侧一个定位来源都没有 → 日出/日落永远是深色、永不切换。
 *
 * <p>但「算日出日落」和「算我是不是在某个围栏里」对定位的要求完全不同：
 *
 * <ul>
 *   <li>围栏要的是<b>现在的</b>位置（人可能刚开进来）；</li>
 *   <li>日出日落要的是<b>你在哪条经线上</b>。车停着不动、定位是两小时前拿的，那个位置依然正确，
 *       {@code TwilightCalculator} 按经度算绝对 UTC 时刻，误差只与经度有关，与"多旧"无关。</li>
 * </ul>
 *
 * <p>所以 10 分钟新鲜度这道门（{@code LocationHolder.MAX_AGE_MS}）对日出日落是<b>语义错误</b>：
 * 车一停就会在 10 分钟后从"能用"翻成"不能用"，表现为界面/投屏突然钉死在深色。
 * 这与工程里 {@code LocationHolder.geofenceFix}（明确忽略年龄）的口径应当一致。
 *
 * <h3>取值顺序</h3>
 *
 * <ol>
 *   <li><b>固定点</b>：用户在设置里显式开启，最可信，且离线可用；</li>
 *   <li><b>任意年龄的定位</b>：本机 GPS 最后一次 fix 或系统 last-known（{@code geofenceFix} 同一口径）；</li>
 *   <li><b>用户曾写入过的存储位置</b>：{@code Settings.lastKnownLocation} 只有被真正写进去过才作数；</li>
 *   <li><b>内置兜底（成都双流）</b>：以上都没有时也不返回 null，见下节。</li>
 * </ol>
 *
 * <p>第 3 条为什么必须带「曾写入过」这一位：{@code lastKnownLocation} 的默认值是内置的
 * <b>成都（双流）</b>坐标（{@code Settings.kt} 里的文件级常量 {@code DEFAULT_SUN_LATITUDE}）。
 * 不带上这一位，就等于把"没取到定位"和"人在成都"混为一谈。
 *
 * <h3>为什么最后一级仍然给坐标（本次修复起）</h3>
 *
 * <p>前三档都没有时，旧实现返回 {@code null} → 判定链给 {@code known=false} →
 * {@link com.miauto.app.IccoaNightSourcePolicy} 回退深色。但 ICCOA 侧本来就常常没有定位来源
 * （{@code GpsLocationService} 只在 AA 回调里启动，系统 last-known 也可能因权限组异常读不到），
 * 结果不是"偶尔回退"，而是<b>整场会话恒深色、且再没有回到白天的机会</b>；
 * 更糟的是回退日志被 {@code sendMode == lastSent} 的去重提前挡掉，现场完全静默。
 *
 * <p>现在第 4 档固定返回内置的成都双流坐标（{@link #BUILT_IN_LATITUDE}/{@link #BUILT_IN_LONGITUDE}），
 * 让后续判定<b>永远能算出一个昼夜</b>。代价是：在没有定位的设备上，切换时刻按成都（东经约 104°）
 * 算 —— 北京会偏约 49 分钟、上海约 55 分钟。这是刻意的取舍：宁可时刻偏几十分钟，也不要"永远深色"。
 * 来源会标成 {@link #SOURCE_BUILT_IN_DEFAULT}，日志与设置页都能看出来用的是兜底值。
 */
public final class SunriseLocationPolicy {

    private SunriseLocationPolicy() { }

    /**
     * 内置兜底坐标：成都双流（与 {@code Settings.DEFAULT_SUN_LATITUDE/LONGITUDE} 同值）。
     *
     * <p>取的是双流一带（成都主城西南），东经 103.92°，日落时刻与成都市区差不到 1 分钟。
     *
     * <p><b>改这里必须同步改 {@code Settings.kt} 的 {@code DEFAULT_SUN_LATITUDE/LONGITUDE}</b> ——
     * 那个常量是 {@code lastKnownLocation} 与「固定地点」的默认值。两处都表示同一个物理位置，
     * 分叉了会出现"设置页显示的点"与"实际算日落的点"不是一处，且不会有人报错。
     * 之所以不直接引用它：本类刻意保持"纯策略、不依赖 Android 类型"（可直接 JVM 单测）。
     */
    public static final double BUILT_IN_LATITUDE = 30.5728;
    public static final double BUILT_IN_LONGITUDE = 103.9235;

    /** 坐标来源。数值只用于日志与测试比较，不要持久化。 */
    public static final int SOURCE_FIXED = 0;        // 用户显式设置的固定点
    public static final int SOURCE_ANY_AGE_FIX = 1;  // 任意年龄的定位（本机 GPS / 系统 last-known）
    public static final int SOURCE_STORED = 2;       // 曾经被写入过的存储位置
    public static final int SOURCE_BUILT_IN_DEFAULT = 3; // 内置兜底（成都双流），保证链路不断

    /** 一个可用于日出日落计算的坐标。 */
    public static final class Ref {
        public final double latitude;
        public final double longitude;
        public final int source;

        public Ref(double latitude, double longitude, int source) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.source = source;
        }

        /** 日志用：说清这个坐标是哪来的，否则上车只能猜"是不是用了默认值"。 */
        public String label() {
            switch (source) {
                case SOURCE_FIXED: return "固定位置";
                case SOURCE_ANY_AGE_FIX: return "最近一次定位";
                case SOURCE_STORED: return "曾保存的位置";
                case SOURCE_BUILT_IN_DEFAULT: return "内置默认位置（成都双流）";
                default: return "未知来源";
            }
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%.4f,%.4f(%s)", latitude, longitude, label());
        }
    }

    /** 坐标本身是否可用于计算：范围合法且不是 (0,0)（(0,0) 是"没取到"的常见替身）。 */
    public static boolean isValid(double latitude, double longitude) {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) return false;
        if (latitude < -90.0 || latitude > 90.0) return false;
        if (longitude < -180.0 || longitude > 180.0) return false;
        return !(Math.abs(latitude) < 1e-6 && Math.abs(longitude) < 1e-6);
    }

    private static Ref of(Double latitude, Double longitude, int source) {
        if (latitude == null || longitude == null) return null;
        if (!isValid(latitude, longitude)) return null;
        return new Ref(latitude, longitude, source);
    }

    /**
     * 按可信度挑一个坐标。
     *
     * @param fixedLatitude      固定点纬度；未开启固定点时传 {@code null}
     * @param fixedLongitude     固定点经度；未开启固定点时传 {@code null}
     * @param fixLatitude        任意年龄定位的纬度；没有定位时传 {@code null}
     * @param fixLongitude       任意年龄定位的经度
     * @param storedIsUserSet    存储位置是否<b>真的被写入过</b>（不是默认值）
     * @param storedLatitude     存储位置纬度
     * @param storedLongitude    存储位置经度
     * @return <b>永远非 null</b>：前三档都不可用时返回内置成都双流坐标
     *         （{@link #SOURCE_BUILT_IN_DEFAULT}）。调用方可以据此放心地算昼夜，
     *         只需在界面上区分「真实定位」与「内置兜底」。
     */
    public static Ref choose(Double fixedLatitude, Double fixedLongitude,
                             Double fixLatitude, Double fixLongitude,
                             boolean storedIsUserSet,
                             Double storedLatitude, Double storedLongitude) {
        Ref fixed = of(fixedLatitude, fixedLongitude, SOURCE_FIXED);
        if (fixed != null) return fixed;

        Ref fix = of(fixLatitude, fixLongitude, SOURCE_ANY_AGE_FIX);
        if (fix != null) return fix;

        if (storedIsUserSet) {
            Ref stored = of(storedLatitude, storedLongitude, SOURCE_STORED);
            if (stored != null) return stored;
        }
        // 兜底：不让链路在这里断掉。ICCOA 侧经常一个定位来源都没有，返回 null 的后果是
        // 「恒深色 + 静默」，比"按成都时刻切换"糟糕得多（详见类注释）。
        return new Ref(BUILT_IN_LATITUDE, BUILT_IN_LONGITUDE, SOURCE_BUILT_IN_DEFAULT);
    }

    /** 该坐标是否来自内置兜底（不是真实定位）。界面用它区分提示文案。 */
    public static boolean isBuiltInDefault(Ref ref) {
        return ref != null && ref.source == SOURCE_BUILT_IN_DEFAULT;
    }
}
