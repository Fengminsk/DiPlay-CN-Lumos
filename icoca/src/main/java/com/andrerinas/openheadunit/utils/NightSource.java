package com.andrerinas.openheadunit.utils;

/**
 * 「深色模式」八条来源路径的<b>统一判定</b>——应用自身主题与应用内 CarWith 投屏转发共用这一份。
 *
 * <h3>为什么必须只有一份</h3>
 *
 * <p>这里原本有三份互不相干的实现：{@code AppThemeManager}（应用主题）、
 * {@code NightModeManager}（Android Auto / ICCOA 转发）、{@code NightMode.current}（投屏单色滤镜）。
 * 三份都在「源取不到」时给了同一个错答案 —— {@code isNight} 停在初始的 {@code false}，也就是
 * <b>安静地报白天</b>。于是同一个设置项在三条路径上的表现分别是：
 *
 * <ul>
 *   <li>本机没有光照传感器时，「光照传感器」等于「恒白天」；</li>
 *   <li>本机没有 {@code Settings.System.car_mode}（车上实测就是没有）时，「大灯 ILL+」等于「恒白天」；</li>
 *   <li>屏幕亮度读失败的首帧同样落到白天。</li>
 * </ul>
 *
 * <p>三者都不是崩溃、不是报错，只是值错了 —— 用户看到的就是「深色模式设了不生效」。
 * 所以本类把「这一次到底算不算得出来」明确成一个 {@link Result#known} 位：
 * <b>算不出来就不许给值</b>，调用点必须自己决定退路（工程口径：回退 {@link #FALLBACK_NIGHT 深色}，
 * 见 {@link #fallbackNight()}）。
 *
 * <h3>为什么放在纯 JVM 可测的地方</h3>
 *
 * <p>本类不引用任何 Android 类型（{@code mode} 用与 {@code Settings.NightMode} 一一对齐的整数，
 * 原始读数由调用点取好再传进来），因此可以直接 JVM 单测，见 {@code NightSourceTest}。
 * 取读数与读框架位的部分在 {@link NightSignals}。这个拆分与工程里既有的
 * {@code IccoaNightSourcePolicy} / {@code DayNightProbePolicy} 同构。
 */
public final class NightSource {

    private NightSource() { }

    // ---- 来源路径（与 Settings.NightMode 的 value 一一对齐）--------------------
    // 这里重抄整数而不是 import Kotlin 枚举：本类要能在 JVM 上单测。

    public static final int MODE_AUTO = 0;
    public static final int MODE_DAY = 1;
    public static final int MODE_NIGHT = 2;
    public static final int MODE_MANUAL_TIME = 3;
    public static final int MODE_LIGHT_SENSOR = 4;
    public static final int MODE_SCREEN_BRIGHTNESS = 5;
    public static final int MODE_CAR_SIGNAL = 6;
    public static final int MODE_LOCATION = 7;

    /**
     * {@code Settings.AppTheme} 里那些「不需要逐帧判定」的静态主题（AUTOMATIC / CLEAR / DARK /
     * EXTREME_DARK）。应用主题侧拿到这个返回值就说明：不该由 {@link #decide} 决定，
     * 交给 {@code AppThemeManager.applyStaticTheme}。
     */
    public static final int APP_THEME_STATIC = -1;

    /**
     * 「算不出来」时的回退值：<b>深色</b>。
     *
     * <p>口径（0.1.9 起，用户明令）：大灯、光照传感器这类<b>车载数据源</b>能取到值就按值判，
     * 取不到值就默认回退深色。日出/日落缺经纬度、按区域无定位同样回退深色 ——
     * 规则只有一条才可预测，也不至于出现「这台机器上某个来源悄悄等价于恒白天」。
     *
     * <p>为什么不再退回框架昼夜位：那个位是本机<b>系统设置</b>的回声（车机实测恒
     * {@code ui_night_mode=2}），与用户选的来源毫无关系。拿它兜底会让设置项看起来生效、
     * 实际一直跟着系统走；还会把「本机没有这个传感器」这个事实掩盖掉。
     * 框架位仍会被读出来写进日志，只作诊断对照。
     */
    public static final boolean FALLBACK_NIGHT = true;

    /** 回退值（见 {@link #FALLBACK_NIGHT}）。 */
    public static boolean fallbackNight() {
        return FALLBACK_NIGHT;
    }

    /**
     * {@code Settings.AppTheme} 的取值 → 本类的 MODE。
     *
     * <p>必须显式映射，<b>不能直接把两个枚举的 {@code value} 互相传</b>：{@code AppTheme} 的
     * 值域是 0..9（AUTOMATIC=0 CLEAR=1 DARK=2 EXTREME_DARK=3 AUTO_SUNRISE=4 MANUAL_TIME=5
     * LIGHT_SENSOR=6 SCREEN_BRIGHTNESS=7 CAR_SIGNAL=8 LOCATION=9），而 {@code NightMode} 是
     * 0..7；照 value 直传会把「恒夜间(2)」当成「深色(2)」、把「按区域(9)」当成未知模式 ——
     * 两个枚举只有前四项巧合相同，越往后越错。
     */
    public static int modeForAppTheme(int appThemeValue) {
        switch (appThemeValue) {
            case 4: return MODE_AUTO;            // AppTheme.AUTO_SUNRISE
            case 5: return MODE_MANUAL_TIME;
            case 6: return MODE_LIGHT_SENSOR;
            case 7: return MODE_SCREEN_BRIGHTNESS;
            case 8: return MODE_CAR_SIGNAL;
            case 9: return MODE_LOCATION;
            default: return APP_THEME_STATIC;    // AUTOMATIC / CLEAR / DARK / EXTREME_DARK
        }
    }

    // ---- 输入：调用点取好的原始读数 -------------------------------------------

    /**
     * 一次判定需要的全部原始读数。
     *
     * <p>字段默认值刻意都是「取不到」（{@code -1} / {@code null}）：漏填某个读数时
     * 结果是 {@code known=false} 而不是一个看似正常的 {@code false}。
     */
    public static final class Raw {
        /** 光照读数（Lux）；{@code < 0} = 没有读数。 */
        public float lux = -1f;
        /** 屏幕亮度 0..255；{@code < 0} = 读失败。 */
        public int brightness = -1;
        /** {@code Settings.System.car_mode}；{@code < 0} = 本机没有这个 key。 */
        public int carMode = -1;
        /** 「按区域」判出的昼夜；{@code null} = 判不出来（没有定位）。 */
        public Boolean locationNight = null;
        /**
         * 日出/日落有没有可用<b>坐标</b>。
         *
         * <p>注意口径：只要「有一个位置」就算可用，<b>不要求定位新鲜</b>（车停着两小时前的定位依然
         * 有效，日出日落只关心经度）；但内置默认坐标不算，必须真的来自固定位置 / 定位 / 曾保存的位置。
         * 详见 {@link SunriseLocationPolicy}。
         */
        public boolean locationUsable = false;
        /** 日出/日落这次用的坐标来源（{@link SunriseLocationPolicy.Ref#label()}），只进日志。 */
        public String locationSource = null;
        /** 日出/日落路径算出的昼夜（调用点用 TwilightCalculator 算）。 */
        public boolean sunsetNight = false;
        /** 当前时刻（当天第几分钟）。 */
        public int nowMinutes = 0;
        /** 自定义时段起止（当天第几分钟）。 */
        public int manualStart = 0;
        public int manualEnd = 0;
        /** 光照阈值（Lux）与亮度阈值（0..255）。 */
        public int thresholdLux = 100;
        public int thresholdBrightness = 100;
        /**
         * 上一次已经生效的值，用于迟滞（防在阈值附近来回抖）。
         * {@code null} = 还没有生效过任何值，按「白天」侧起步 —— 与既有实现一致。
         */
        public Boolean lastNight = null;
    }

    /** 判定结果：{@code known=false} 时 {@code isNight} 无意义，调用点必须走退路。 */
    public static final class Result {
        public final boolean known;
        public final boolean isNight;
        /** 判读依据（进日志用；上车只靠这几行判断走的是哪一路）。 */
        public final String why;

        Result(boolean known, boolean isNight, String why) {
            this.known = known;
            this.isNight = isNight;
            this.why = why;
        }
    }

    private static Result known(boolean isNight, String why) {
        return new Result(true, isNight, why);
    }

    private static Result unknown(String why) {
        return new Result(false, false, why);
    }

    // ---- 判定 ----------------------------------------------------------------

    /**
     * 按用户选的来源算出这一次的昼夜。
     *
     * <p>「算不出来」与「算出来是白天」是两件完全不同的事，这里绝不合并：
     * 前者返回 {@code known=false}，后者返回 {@code known=true, isNight=false}。
     */
    public static Result decide(int mode, Raw r) {
        if (r == null) r = new Raw();
        switch (mode) {
            case MODE_DAY:
                return known(false, "恒白天");
            case MODE_NIGHT:
                return known(true, "恒夜间");
            case MODE_MANUAL_TIME: {
                boolean night = inWindow(r.nowMinutes, r.manualStart, r.manualEnd);
                return known(night, "自定义时段 " + hhmm(r.manualStart) + "-" + hhmm(r.manualEnd)
                        + "，现在 " + hhmm(r.nowMinutes));
            }
            case MODE_AUTO: {
                if (!r.locationUsable) {
                    // 坐标策略保证「正常路径永远有坐标」（最差是内置成都双流，见 SunriseLocationPolicy），
                    // 所以走到这里只剩一种情况：取坐标时抛了异常。仍按既定规则回退深色。
                    return unknown("日出/日落取坐标异常（策略层未能给出任何坐标）");
                }
                return known(r.sunsetNight, "日出/日落（坐标来源："
                        + (r.locationSource == null ? "未知" : r.locationSource) + "）");
            }
            case MODE_LIGHT_SENSOR: {
                if (r.lux < 0f) return unknown("没有光照读数");
                boolean night = luxIsNight(r.lux, r.thresholdLux, r.lastNight);
                return known(night, "光照 " + String.format(java.util.Locale.US, "%.1f", r.lux)
                        + " Lux / 阈值 " + r.thresholdLux);
            }
            case MODE_SCREEN_BRIGHTNESS: {
                if (r.brightness < 0) return unknown("读不到屏幕亮度");
                boolean night = brightnessIsNight(r.brightness, r.thresholdBrightness, r.lastNight);
                return known(night, "亮度 " + r.brightness + " / 阈值 " + r.thresholdBrightness);
            }
            case MODE_CAR_SIGNAL: {
                if (r.carMode < 0) return unknown("本机没有 Settings.System.car_mode（大灯 ILL+）");
                return known(r.carMode != 0, "car_mode=" + r.carMode);
            }
            case MODE_LOCATION: {
                if (r.locationNight == null) return unknown("按区域判不出来（没有任何定位）");
                return known(r.locationNight, "按区域");
            }
            default:
                return unknown("未知模式(" + mode + ")");
        }
    }

    // ---- 可单独单测的小判定（迟滞口径与原实现逐字一致）-------------------------

    /**
     * 自定义时段是否落在夜间。
     *
     * <p>跨零点（如 22:00→06:00）走 {@code now >= start || now <= end}，
     * 起点终点相等时按「只有那一分钟」处理，与既有行为一致。
     */
    public static boolean inWindow(int nowMinutes, int start, int end) {
        if (start <= end) return nowMinutes >= start && nowMinutes <= end;
        return nowMinutes >= start || nowMinutes <= end;
    }

    /** 光照迟滞：已在夜间时用「阈值 + 10 Lux」才退出，防止隧道口来回翻。 */
    public static boolean luxIsNight(float lux, int thresholdLux, Boolean lastNight) {
        float limit = Boolean.TRUE.equals(lastNight) ? thresholdLux + 10f : thresholdLux;
        return lux < limit;
    }

    /** 亮度迟滞：同上，±10 个 0..255 的刻度。 */
    public static boolean brightnessIsNight(int brightness, int threshold, Boolean lastNight) {
        int limit = Boolean.TRUE.equals(lastNight) ? threshold + 10 : threshold;
        return brightness < limit;
    }

    public static String hhmm(int minutes) {
        return String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** 来源路径可读名（日志与设置页共用口径）。 */
    public static String modeName(int mode) {
        switch (mode) {
            case MODE_AUTO: return "日出/日落";
            case MODE_DAY: return "恒白天";
            case MODE_NIGHT: return "恒夜间";
            case MODE_MANUAL_TIME: return "自定义时段";
            case MODE_LIGHT_SENSOR: return "光照传感器";
            case MODE_SCREEN_BRIGHTNESS: return "屏幕亮度";
            case MODE_CAR_SIGNAL: return "大灯 ILL+";
            case MODE_LOCATION: return "按区域";
            default: return "未知模式(" + mode + ")";
        }
    }
}
