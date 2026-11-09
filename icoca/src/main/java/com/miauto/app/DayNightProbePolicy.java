package com.miauto.app;

import com.andrerinas.openheadunit.utils.NightSource;

import java.util.Collection;
import java.util.Locale;

/**
 * 昼夜（白天/夜间）主题诊断的<b>纯策略</b>：取值合法性、变化判定、日志文案、候选 key 过滤。
 *
 * <p>纯逻辑，不引用任何 Android / SDK 类型，因此可直接 JVM 单测（见
 * {@code DayNightProbePolicyTest}）；带 Android 与 SDK 调用的部分全部在 {@link DayNightProbe}。
 * 这个拆分是工程惯例（{@link NonMusicBufferingPolicy}、{@link AudioFocusPolicy} 同构）。
 *
 * <h3>为什么要先做诊断，而不是直接改</h3>
 *
 * <p>「CarWith 场景界面固定夜间」这件事，车机侧能<b>证实</b>的只有一半：
 * {@code IccoaManager} 从未调用过 {@code UCarAdapter.notifySwitchDayOrNight}（全仓库 0 命中），
 * 而反编译 {@code icar-sdk.dex} 确认该接口同时喂两处 ——
 * 运行期的 {@code NotifySwitchDayOrNight}（methodIndex=15、sourceDevice=CAR）与
 * 握手期 {@code AuthResponse.dayOrNightMode}（{@code ShareLinkService.mDayOrNightMode} 默认 0）。
 * 也就是说车机确实<b>一个字节都没报过</b>。
 *
 * <p>但另外两半<b>证实不了</b>，只能测：
 * <ul>
 *   <li><b>手机端吃不吃这条</b>：不发时手机按什么兜底（很可能是手机自己的深色模式，而不是「默认夜间」），
 *       发一个与现场相反的值场景会不会跟着翻 —— 这决定整个方案成不成立；</li>
 *   <li><b>这台车到底用哪一路昼夜信号</b>：现象是「进隧道（光线变暗）切夜间」，
 *       而 {@code Settings.System.car_mode} 是<b>大灯 ILL+ 通路线号</b>
 *       （{@code NightModeManager} 里对它的注释明确写着面向后装机）。两者是不同的物理信号，
 *       前装的欧尚 Z6 iDD 很可能没有 {@code car_mode}，或有值但不随光线变。</li>
 * </ul>
 *
 * <p>「强制值」这一条始终默认不发（见 {@link #FORCE_NONE}）：它是用来发<b>与现场相反</b>的值的，
 * 只有在人主动要求时才该发。而<b>转发</b>（把车机真实昼夜报给手机）默认开，
 * 依据见 {@link #isForwardEnabled} —— 两者不是一回事，别混。
 * 发信必须由人用 adb 显式打开，且刻意发一个「与现场相反」的值来判别通道有效性。
 * 这与工程既有的运行时覆盖惯例一致（先例：{@code miauto_nav_usage}）。
 *
 * <h3>一个必须写死的细节</h3>
 *
 * <p>{@code DayNightMode} 的取值是 {@code UNKNOWN_MODE(0) / DAY_MODE(1) / NIGHT_MODE(2)}，
 * 而 {@code ControlManager.sendSwitchDayOrNight} 对 {@code null} 和 {@code UNKNOWN_MODE} 是
 * <b>直接 return false</b>（日志 {@code sendSwitchDayOrNight() args error}）。
 * 因此「关」（设置值为 0 或没设）绝不能翻译成「发 UNKNOWN_MODE」—— 那会在车上表现为
 * 「发了但手机没反应」，被误读成通道无效。这就是 {@link #parseForceMode} 与
 * {@link #isSendable} 存在的唯一理由。
 */
public final class DayNightProbePolicy {

    private DayNightProbePolicy() { }

    // ---- SDK 侧 DayNightMode 的取值（与 icar-sdk.dex 内枚举一致）----------------
    // dexdump 已核：notifySwitchDayOrNight 是 access 0x0001 PUBLIC，
    // DAY_MODE/NIGHT_MODE 是 access 0x4019 PUBLIC STATIC FINAL ENUM，描述符与桩一致，
    // 所以调用点可以直调，不需要反射（工程里反射只用于 jadx 会渲染错的 private 成员）。

    /** {@code DayNightMode.UNKNOWN_MODE.getValue()}；<b>禁止</b>发给手机，见类注释。 */
    public static final int SDK_UNKNOWN = 0;
    /** {@code DayNightMode.DAY_MODE.getValue()}。 */
    public static final int SDK_DAY = 1;
    /** {@code DayNightMode.NIGHT_MODE.getValue()}。 */
    public static final int SDK_NIGHT = 2;

    // ---- adb 可改的强制值语义（Settings.Global，见 DayNightProbe）--------------

    /** 没设置 / 显式设 0 = <b>一个协议包都不发</b>，纯只读采样。默认态。 */
    public static final int FORCE_NONE = 0;
    /** 值存在但不是 0/1/2 —— 同样不发，但额外打一条「被拒绝」，避免静默失效。 */
    public static final int FORCE_INVALID = -1;

    /** 采样里「还没读到过任何值」的哨兵，与真实读数（含 0）不冲突。 */
    public static final int NOT_READ = Integer.MIN_VALUE;

    // ---- 采样与补发的节奏 ------------------------------------------------------

    /** 会话期间的轮询周期。5 秒足够抓住进/出隧道这类切换，又不会在车上刷出几千行。 */
    public static final long SAMPLE_INTERVAL_MS = 5_000L;

    /**
     * {@code Settings} 观察者驱动的<b>重扫</b>最小间隔。
     *
     * <p>必须有：观察者注册在 {@code Settings.System} 基 URI 上（{@code notifyForDescendants=true}），
     * 所以<b>任何</b>一个 key 被写都会回调一次，而每次回调都要扫一遍全表。前装车机上不少车辆状态
     * （车速、挡位、时长）也常驻 {@code Settings.System} 并高频改写 —— 不限流就等于给一条本来就在
     * 抢 CPU 的投屏链路再加一个高频 IPC 源，这正是本工程刻意回避的事（低端车机降帧到 30fps 同源）。
     *
     * <p>代价如实说明：两次限流之间「翻了又翻回去」的 key 会被漏掉。5 秒轮询本来就有同样的局限，
     * 而这次要抓的是进隧道那种<b>持续</b>切换，不是毫秒级抖动。
     */
    public static final long MIN_RESCAN_MS = 2_000L;

    /**
     * 轮询驱动的<b>全表重扫</b>间隔。
     *
     * <p>为什么与 5 秒采样解耦：三张 Settings 表各扫一遍（每张最多 {@link #MAX_ROWS_SCANNED} 行、
     * 三个 Cursor）只对<b>诊断</b>有用 —— 真正要发给手机的取值在 {@code readRaw} 里，几个 key 就够。
     * 原来它跟着 5 秒轮询跑，等于在正在解码 30fps 的同一台低端车机上每 5 秒再做一次三表遍历。
     * 进隧道那种「哪个 key 跟着翻」的判读，30 秒内发现一次完全够用。
     *
     * <p>不变量：必须 &gt;= {@link #SAMPLE_INTERVAL_MS}，否则限流形同虚设
     * （单测 {@code rescanIsSlowerThanSampling} 钉住）。
     */
    public static final long RESCAN_INTERVAL_MS = 30_000L;

    /**
     * 无会话自跑模式的<b>最长时长</b>，到点自己停。
     *
     * <p>会话模式有 {@code stop()} 收尾，但台架（平板）上的 App 可能一直开着 ——
     * 一个被忘了关的 adb 开关不该变成常驻轮询。同一个理由，工程里投屏重试才有
     * {@code CAST_RETRY_MAX} 上限，待机页不留永不停止的定时器。
     */
    public static final long STANDALONE_MAX_MS = 10 * 60_000L;

    /**
     * 台架自跑时长可用 adb 覆盖：{@code settings put global miauto_daynight_probe_minutes 60}。
     *
     * <p>为什么必须能改：默认 10 分钟是给"读一次即时信号"用的，而验证日出/日落需要跨越切换时刻，
     * 至少半小时起；固定值会让"到点自动停止"看起来像"不切换"（日志里那条停止提示很容易被忽略）。
     */
    public static final String SETTING_STANDALONE_MINUTES = "miauto_daynight_probe_minutes";
    public static final int STANDALONE_MINUTES_MAX = 240;

    /** 解析台架时长（分钟）；非法/越界一律回默认值，绝不留下一个永不停止的定时器。 */
    public static long parseStandaloneMaxMs(String raw) {
        if (raw == null) return STANDALONE_MAX_MS;
        String t = raw.trim();
        if (t.isEmpty()) return STANDALONE_MAX_MS;
        try {
            int v = Integer.parseInt(t);
            if (v <= 0 || v > STANDALONE_MINUTES_MAX) return STANDALONE_MAX_MS;
            return v * 60_000L;
        } catch (NumberFormatException e) {
            return STANDALONE_MAX_MS;
        }
    }

    /** 开关语义：只有恰好 {@code "1"} 算开（"随便写了个什么"不能被当成开）。 */
    public static boolean isSwitchOn(String raw) {
        return raw != null && "1".equals(raw.trim());
    }

    // ---- 三个 adb 可调项的名字（集中一处，日志文案与读取点都引用这里）------------

    /** 强制发一个指定昼夜值（诊断通道有效性用）；未设置 = 不强制。 */
    public static final String SETTING_FORCE = "miauto_force_daynight";
    /** 没有会话时也采样（平板台架用）；未设置 = 不跑，避免每台设备常驻轮询。 */
    public static final String SETTING_PROBE_STANDALONE = "miauto_daynight_probe";
    /** 把车机昼夜转发给手机；<b>默认开</b>，见 {@link #isForwardEnabled}。 */
    public static final String SETTING_FORWARD = "miauto_daynight_forward";

    /**
     * 转发是否启用 —— <b>默认开</b>，只有显式关掉才不发。
     *
     * <p>口径从"默认关"改成"默认开"是有条件的，两个前提都被实测补齐了：
     * <ul>
     *   <li><b>信号源查实</b>：平板上翻深色模式，框架 {@code uiMode} 夜间位跟着翻
     *       （{@code 0x11 ↔ 0x21}），不需要依赖任何厂商私有 key；</li>
     *   <li><b>通道查实</b>：连着手机的会话里 {@code notifySwitchDayOrNight} 返回已接受，
     *       且<b>投屏画面真的跟着换了主题</b>（浅底黑字 ↔ 深底白字），
     *       说明手机端吃这条、且优先级高于手机自己的主题。</li>
     * </ul>
     * 之前默认关是"源与汇都未证实"时的保守选择；现在留着默认关反而有害 ——
     * {@code Settings.Global} 是按设备存的，平板上开的开关不会跟到车机，
     * 上车那一趟只要忘记拨一下，就会把"根本没开"看成"车机上不生效"。
     *
     * <p>关闭用一整个"关词族"而不是只认 {@code 0}：{@code false}/{@code off}/{@code no} 都算关。
     * 只认 0 的话，有人写了 {@code false} 反而是开，方向正好相反、更难查。
     *
     * <p>注意与 {@link #SETTING_PROBE_STANDALONE} 的区别：那个仍然严格 opt-in（只有恰好 "1" 才开），
     * 因为它会在<b>没有会话</b>时常驻 5 秒轮询，默认开等于给每台设备加背景负载。
     */
    public static boolean isForwardEnabled(String raw) {
        if (raw == null) return true;
        String v = raw.trim().toLowerCase(java.util.Locale.US);
        if (v.isEmpty()) return true;      // 空串按"未设置"对待，别让它变成静默关
        return !("0".equals(v) || "false".equals(v) || "off".equals(v) || "no".equals(v));
    }

    /**
     * 启动行里"这次到底会不会发"那一段。
     *
     * <p>必须是纯函数并单独测：这段文案是上车唯一的判读依据，若它和真正的门禁不一致，
     * 人会照着文案去判断"没发"还是"发了没效果"，直接被误导一整个来回。
     */
    public static String sendStateLine(int forceMode, boolean forwardOn) {
        if (isSendable(forceMode)) {
            return "强制值=" + forceMode + " → 本次按强制值向手机发包（转发让位于强制值）";
        }
        return forwardOn
                ? "转发默认开 → 昼夜变化会发给手机；想关掉：settings put global "
                    + SETTING_FORWARD + " 0"
                : "转发已被 " + SETTING_FORWARD + "=0 关掉 → 本次只诊断不发送";
    }

    /** 三张 Settings 表都要扫：平板实测证明昼夜 key <b>不一定</b>在 System 上。 */
    public static final String[] SETTINGS_TABLES = { "system", "secure", "global" };

    /**
     * 强制值生效时的补发上限（×{@link #FORCE_SEND_RETRY_MS} = 最长约 12 秒）。
     *
     * <p>必须有：{@code sendSwitchDayOrNight} 在控制通道未就绪时只返回 {@code false}，
     * 若只试一次就放弃，「通道没就绪」会被误判成「手机不吃这条」——那正好是本次要回答的问题。
     * 同时必须有上限：会话本身可能被用户中途拆掉，不能留下一个永不停止的定时器。
     */
    public static final int FORCE_SEND_MAX_ATTEMPTS = 6;
    public static final long FORCE_SEND_RETRY_MS = 2_000L;

    /** 一次会话内最多打出的「感兴趣的 key」条数（防止个别 ROM 上命中几百行刷屏）。 */
    public static final int MAX_INTERESTED_LOGGED = 40;

    /**
     * 「所选来源算不出来 → 回退深色」这条提示的最小间隔。
     *
     * <p>为什么需要节流：通道未就绪时发送会失败并把 {@code lastSent} 清回 NONE，于是每 5 秒都会
     * 重新走到这条提示 —— 一台没有该传感器的机器上相当于每分钟 12 条，DiAuto 报告会被它刷满，
     * 反而盖住真正有用的行。首条必打，之后每 10 分钟一条，足以说明"它一直在回退"。
     */
    public static final long FALLBACK_LOG_INTERVAL_MS = 10 * 60_000L;

    /** 这一次该不该打「回退深色」的提示（首条、强制、或距上次已过间隔）。 */
    public static boolean shouldLogFallback(long nowMs, long lastAtMs, boolean forced) {
        if (forced) return true;
        if (lastAtMs <= 0L) return true;
        return nowMs - lastAtMs >= FALLBACK_LOG_INTERVAL_MS;
    }

    /** 全表扫描的行数上限（防御性：个别前装 ROM 的 Settings.System 可能异常巨大）。 */
    public static final int MAX_ROWS_SCANNED = 800;

    /** 光照变化超过该阈值才算「变了」（Lux；1.0 以下的抖动没有判读价值）。 */
    public static final float LUX_REPORT_STEP = 1.0f;

    /**
     * 「初值」为等首帧光照读数最多推迟几次（×{@link #INITIAL_LUX_WAIT_MS} ≈ 1.5 秒）。
     *
     * <p>为什么必须等：{@code SensorManager} 没有同步读接口，注册后第一个
     * {@code onSensorChanged} 通常 100ms 内到，但 {@code begin()} 里的初值判定是同步跑的 ——
     * 那一刻 {@code lastLux} 还是 {@code -1}，于是「光照传感器」这条路径会先判成「算不出来」
     * 退回框架昼夜位，5 秒后采样轮才纠正。框架位与光照判定相反时，手机端就会先闪一次错主题。
     * 这不是"路径没生效"，是"初值来得太早"，所以只推迟初值、不推迟后续采样。
     *
     * <p>必须有上限：真没有光照传感器的机器（车机实测就是"光照传感器 无"）永远等不到读数，
     * 无限等下去等于初值永不发送；到上限就照常发送（退回框架位，正是该走的路）。
     */
    public static final int INITIAL_LUX_WAIT_ATTEMPTS = 6;
    public static final long INITIAL_LUX_WAIT_MS = 250L;

    /**
     * 初值这一次要不要继续等光照读数。
     *
     * @param mode     当前昼夜模式（{@code NightSource.MODE_*}）
     * @param lux      已拿到的光照读数，{@code < 0} 表示还没有
     * @param attempts 已经等过的次数
     */
    public static boolean waitForFirstLux(int mode, float lux, int attempts) {
        if (mode != NightSource.MODE_LIGHT_SENSOR) return false;
        if (lux >= 0f) return false;
        return attempts < INITIAL_LUX_WAIT_ATTEMPTS;
    }

    // ---- 强制值解析 -----------------------------------------------------------

    /**
     * 把 adb 写进来的字符串翻译成要发的 SDK 值。
     *
     * @return {@link #SDK_DAY} / {@link #SDK_NIGHT} 表示要发；{@link #FORCE_NONE} 表示不发；
     *         {@link #FORCE_INVALID} 表示不发且要打一条拒绝日志
     */
    public static int parseForceMode(String raw) {
        if (raw == null) return FORCE_NONE;
        String t = raw.trim();
        if (t.isEmpty()) return FORCE_NONE;
        int v;
        try {
            v = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return FORCE_INVALID;      // 打错字必须可见，否则人会以为自己已经打开了
        }
        if (v == FORCE_NONE) return FORCE_NONE;
        if (v == SDK_DAY || v == SDK_NIGHT) return v;
        return FORCE_INVALID;          // 含 0 以外的未知值；尤其 0 绝不能当 UNKNOWN_MODE 发出去
    }

    /** 只有 DAY / NIGHT 允许发；UNKNOWN 与 0 都不行（SDK 会静默 return false）。 */
    public static boolean isSendable(int sdkMode) {
        return sdkMode == SDK_DAY || sdkMode == SDK_NIGHT;
    }

    // 夜间位 → SDK 值的翻译，以及转发日志文案，已随「转发取源改为共享设置项」一并
    // 迁到 IccoaNightSourcePolicy（fromNightBit / sendLine，带单测）。
    // 这里刻意不留第二份实现：同一个判定写两处，正是本次要消灭的问题。

    /** 明确「不发」的取值（与 INVALID 区分：前者是正常关闭，后者是操作失误）。 */
    public static boolean isOff(int forceMode) {
        return forceMode == FORCE_NONE;
    }

    // ---- 发送判定 -------------------------------------------------------------

    /** 该不该再补发一次：成功就不再补，失败则用尽上限前继续。 */
    public static boolean shouldRetrySend(int attemptsDone, boolean lastOk) {
        if (lastOk) return false;
        return attemptsDone < FORCE_SEND_MAX_ATTEMPTS;
    }

    /** 到点没有（含时钟回拨：宁可立刻重采，也不能因 now&lt;last 永远不再采）。 */
    public static boolean shouldSample(long nowMs, long lastMs, long intervalMs) {
        if (lastMs == NOT_READ) return true;
        if (intervalMs <= 0) return true;
        long delta = nowMs - lastMs;
        return delta < 0 || delta >= intervalMs;
    }

    // ---- 变化判定与去重 -------------------------------------------------------

    /**
     * 是否算「变了」（首次读数、以及与上次的差异都算；未读到则不算）。
     *
     * <p>去重的目的是让一次进隧道的切换在日志里只占一行，而不是每 5 秒重复一行；
     * 上车抓的那份报告要能一眼看出「哪一路信号在什么时刻翻了」。
     */
    public static boolean changed(int last, int now) {
        if (now == NOT_READ) return false;
        return last != now;
    }

    /** 同上，字符串版（Settings 的 value 是字符串）。 */
    public static boolean changed(String last, String now) {
        if (now == null) return false;
        return !now.equals(last);
    }

    /** 光照是否值得报一次。 */
    public static boolean luxChanged(float last, float now) {
        if (now < 0f) return false;                       // 还没有读数
        if (last < 0f) return true;                       // 第一次读数
        return Math.abs(now - last) >= LUX_REPORT_STEP;
    }

    // ---- android.content.res.Configuration 的 uiMode 翻译 ---------------------
    //
    // 这些常量镜像 android.content.res.Configuration 里的 public 常量（自 API 8 起未变）。
    // 之所以在这里重抄一遍整数：本类要能在 JVM 上跑单测，不能 import Android 类型。

    public static final int UI_MODE_TYPE_MASK = 0x0f;
    public static final int UI_MODE_NIGHT_MASK = 0x30;
    public static final int UI_MODE_NIGHT_NO = 0x10;
    public static final int UI_MODE_NIGHT_YES = 0x20;

    /** 夜间位的可读名。 */
    public static String nightName(int uiMode) {
        switch (uiMode & UI_MODE_NIGHT_MASK) {
            case UI_MODE_NIGHT_NO: return "NIGHT_NO(白天)";
            case UI_MODE_NIGHT_YES: return "NIGHT_YES(夜间)";
            default: return "NIGHT_UNDEFINED";
        }
    }

    /** 是否处于夜间（无法判定时按<b>非夜间</b>返回，只用于日志聚合，不参与任何决策）。 */
    public static boolean isNightByUiMode(int uiMode) {
        return (uiMode & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES;
    }

    /** 屏幕形态位（CAR / DESK / NORMAL …）—— 顺带能看出这台前装有没有把车机置成 CAR 形态。 */
    public static String typeName(int uiMode) {
        switch (uiMode & UI_MODE_TYPE_MASK) {
            case 0: return "UNDEFINED";
            case 1: return "NORMAL";
            case 2: return "CAR";
            case 3: return "DESK";
            case 4: return "TELEVISION";
            case 5: return "APPLIANCE";
            default: return "TYPE_" + (uiMode & UI_MODE_TYPE_MASK);
        }
    }

    /** 一行 uiMode 判读，例：{@code uiMode=17(0x11) 夜间位=NIGHT_NO(白天) 形态=NORMAL}。 */
    public static String describeUiMode(int uiMode) {
        return "uiMode=" + uiMode + "(0x"
                + Integer.toHexString(uiMode).toUpperCase(Locale.US) + ")"
                + " 夜间位=" + nightName(uiMode) + " 形态=" + typeName(uiMode);
    }

    // ---- 候选 key 与全表扫描过滤 ----------------------------------------------

    /**
     * 点名候选，<b>无条件</b>逐个在每张表里试读一次。
     *
     * <p>清单是实测长出来的，不是猜的：Xiaomi Pad 6（HyperOS / Android 14）台架侦察发现
     * {@code car_mode} 在 system/secure/global <b>三张表里全都不存在</b>（原报告假设的 key 在这类
     * 系统上直接落空），而它的昼夜真值确实存在、且是<b>标准 Android 框架侧</b>那一路
     * （{@code dumpsys uimode} 给 {@code mNightMode=1 (no) mNightModeLocked=true}，
     * {@code mCurUiMode=0x11}）；MIUI 自己的开关则写在 {@code Settings.System.dark_mode_enable}。
     *
     * <p>读不存在的 key 只返回 null，无副作用，所以宁可全试，好在车上/台上直接看到「到底哪几个存在」。
     */
    public static String[] namedCandidates() {
        return new String[] {
                "car_mode",            // 后装机的大灯 ILL+（NightModeManager.CAR_SIGNAL 用的就是它）
                "dark_mode_enable",    // MIUI/HyperOS 的深色模式总开关（平板实测存在于 Settings.System）
                "ui_night_mode",       // AOSP 框架侧 UiModeManager 的持久值（标准 Android 应在此）
                "night_mode",
                "day_night_mode",
                "headlight_state",
                "illum",
        };
    }

    /**
     * 全表扫描时保留哪些 key —— 判据是「名字里带昼夜相关词根」。
     *
     * <p><b>为什么不猜死一个 key</b>：前装系统自定义的昼夜信号叫什么，坐在电脑前无从得知。
     * 与其继续猜（每猜错一次就要重新上车一趟），不如把三张 {@code Settings} 表都扫一遍、
     * 只保留词根命中的那些，再在会话期间盯着这一批做<b>差异</b>日志 ——
     * 司机进一次隧道，到底哪个 key 跟着翻了，就自己浮出来了。
     *
     * <p><b>词根表是平板实测修正过的</b>：原来只有 {@code night|day|light|illum|theme|dim}，
     * 在 Xiaomi Pad 6 上一条都命中不到真正的开关 {@code dark_mode_enable}
     * （以及 {@code dark_mode_sunrise_time} / {@code dark_mode_sunset_time}）—— 也就是
     * 「跑对了探针、日志里却没有真信号」这种最浪费的失败。{@code dark} 与 {@code sun}
     * 因此是<b>必需</b>词根，不是锦上添花。
     *
     * <p>允许误报（{@code highlight_*} 会被 {@code light} 命中、快捷方式列表里带
     * {@code night} 也会被命中）：命中项有数量上限，且只影响日志条数，不影响任何行为；
     * 而漏报的代价是再多跑一趟车。
     */
    public static boolean isInterestingName(String name) {
        if (name == null || name.isEmpty()) return false;
        String n = name.toLowerCase(Locale.US);
        return n.contains("night") || n.contains("day") || n.contains("light")
                || n.contains("illum") || n.contains("theme") || n.contains("dim")
                || n.contains("dark") || n.contains("sun") || n.contains("twilight")
                || n.contains("uimode") || n.contains("ui_mode")
                // car_mode 没有任何昼夜词根（就是"车灯信号"这个含义本身），必须点名保留；
                // 少了这一条，后装机上最可能的那个 key 会被词根表静默漏掉。
                || n.contains("car_mode");
    }

    /** 从一组名字里挑出感兴趣的，最多 {@code max} 个（保持传入顺序，便于对照）。 */
    public static String[] keepInteresting(Collection<String> names, int max) {
        if (names == null || max <= 0) return new String[0];
        String[] out = new String[Math.min(max, names.size())];
        int n = 0;
        for (String s : names) {
            if (!isInterestingName(s)) continue;
            if (n >= out.length) break;
            out[n++] = s;
        }
        if (n == out.length) return out;
        String[] trimmed = new String[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /**
     * 差异日志只比对「词根命中」的 key。
     *
     * <p>必须有：整表比对在真机上不可用 —— 平板实测 {@code system 373 / secure 399 / global 501}
     * 行，而 {@code ContentResolver.query} <b>不保证两次返回同一行序</b>，加上扫描有截断上限，
     * 两轮之间就会冒出上百条「新出现」，把真正的昼夜翻转挤出打印上限（平板第一版就是这么翻车的）。
     * 收敛到词根命中的那几十项后，噪音消失、目标信号一条不落。
     *
     * <p>代价如实说明：一个词根不命中、却承载昼夜语义的 key（厂商起名成 {@code xyz_display_policy}
     * 之类）会被漏掉。所以基线那条「共 N 行」要保留 —— 一旦怀疑漏了，就把名字补进
     * {@link #isInterestingName} 重出一包，而不是退回整表比对。
     *
     * @param rawKey 形如 {@code "system:dark_mode_enable"}，词根判定只作用于冒号后的 key 部分
     */
    public static boolean isDiffableName(String rawKey) {
        if (rawKey == null) return false;
        int colon = rawKey.indexOf(':');
        return isInterestingName(colon < 0 ? rawKey : rawKey.substring(colon + 1));
    }

    /** 日志里一个 Settings 值最多打多少个字符（ROM 里存在几百字的 JSON 值）。 */
    public static final int MAX_VALUE_LEN = 48;

    /**
     * 把读到的值压成"安全的一行"。
     *
     * <p>平板实测逼出来的两件事，都不是格式洁癖：
     * <ul>
     *   <li><b>NULL 列</b>：不少 key 存在但值为空。若原样带成 {@code null}，差异判定会把
     *       {@code snapshot.get()==null} 误认成「这个 key 没见过」，于是<b>每轮重报一次</b>
     *       「新出现」，几百行噪音把要看的信号埋掉；</li>
     *   <li><b>值里带换行</b>：如 {@code global:audio_switch_day_hour_time} 的值是
     *       {@code "2026-10-22\n11,12"}。DiAuto 报告<b>按行</b>收日志，一条里冒出换行
     *       会把后半截甩成无主行，看着像丢日志。</li>
     * </ul>
     */
    public static String safeValue(String v) {
        if (v == null) return "(空)";
        // 连续空白（含 \r\n\t 连写）折叠成一个空格：逐个 replace 会把 "a\r\n\tb" 变成 "a   b"，
        // 既难看，也让"改前/改后"看起来像不同的值。
        String s = v.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return "(空)";
        if (s.length() <= MAX_VALUE_LEN) return s;
        return s.substring(0, MAX_VALUE_LEN) + "…";
    }

    // ---- 日志文案（抽出来才能被单测钉住）--------------------------------------

    /** 强制值被拒绝时的日志（要说清「所以本次什么都没发」，否则会被当成通道无效）。 */
    public static String forceRejectLine(String raw) {
        return "昼夜诊断：强制值「" + raw + "」非法（只接受 1=白天 / 2=夜间 / 0=不发）"
                + " → 本次一个强制包都没发；设置法：adb shell settings put global "
                + SETTING_FORCE + " 1";
    }

    /** 发信前后的日志。{@code ok} 为 SDK 返回值（true 仅代表已投递，不代表手机端已换主题）。 */
    public static String sendLine(int sdkMode, int attempt, boolean ok) {
        return "昼夜诊断：★ 第 " + attempt + " 次向手机发 "
                + (sdkMode == SDK_NIGHT ? "NIGHT_MODE(2)" : "DAY_MODE(1)")
                + (ok ? " → SDK 已接受投递（若车机界面已是该主题，画面应随之切换；"
                        + "不变则说明手机端不吃这条，或不吃 UNKNOWN 之外的值）"
                      : " → SDK 拒收（控制通道未就绪；稍后自动补发，先别判定为通道无效）");
    }

    /** 感兴趣的 key 发生变化（含首次出现）。值经 {@link #safeValue} 压成一行。 */
    public static String settingsChangedLine(String name, String oldV, String nowV) {
        return "昼夜诊断：Settings." + name + " "
                + (oldV == null ? "（新出现）" : safeValue(oldV) + " → ") + safeValue(nowV);
    }

    /** uiMode 变化。 */
    public static String uiModeChangedLine(int uiMode) {
        return "昼夜诊断：框架配置 " + describeUiMode(uiMode) + " ← 变了（"
                + (isNightByUiMode(uiMode) ? "现在是夜间" : "现在是白天")
                + "）；若这一路会跟着切，则信号源应当取它，而不是 car_mode";
    }

    /** 光照读数变化（顺带带上此刻框架昼夜位，便于对照「光照变了但 uiMode 没跟」这种情形）。 */
    public static String luxLine(float lux, int uiMode) {
        return "昼夜诊断：光照 " + String.format(Locale.US, "%.1f", lux) + " Lux"
                + "（此刻 " + describeUiMode(uiMode) + "）";
    }
}
