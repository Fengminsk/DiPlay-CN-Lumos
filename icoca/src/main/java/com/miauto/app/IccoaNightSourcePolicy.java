package com.miauto.app;

import com.andrerinas.openheadunit.utils.NightSource;

/**
 * ICCOA（CarWith）昼夜转发的<b>取源策略</b>：同一个「深色模式」设置项算出来的值，
 * 什么条件下按它发、什么条件下回退深色。
 *
 * <p>纯逻辑，不引用 Android / Kotlin / SDK 类型，可直接 JVM 单测（见
 * {@code IccoaNightSourcePolicyTest}）。算得出来/算不出来由
 * {@code com.andrerinas.openheadunit.utils.NightSource} 统一给出（那一份应用主题也在用），
 * 本类只负责「拿到之后发什么、日志怎么写」。
 *
 * <h3>为什么要共享设置项，而不是各写一套</h3>
 *
 * <p>设置里那 8 种昼夜来源（{@code Settings.NightMode}）本来就与传输层无关：
 * {@code NightModeManager} 只算一个 {@code isNight} 布尔再回调，Android Auto 专属的只有
 * {@code AapService} 里 {@code commManager.send(NightModeEvent(isNight))} 那一行。
 * ICCOA 侧此前写死框架 {@code uiMode} 夜间位，等于第二套判定逻辑，而且它依赖 OEM 接线 ——
 * BYD 测试机实测 {@code getNightMode()=MODE_NIGHT_YES}（显式钉死），一旦 OEM 不刷新这个位，
 * 转发就永远只有一个值。改成读同一个设置项后，用户可按每台车的实情挑源。
 *
 * <h3>共享之后必须挡住的「静默错值」</h3>
 *
 * <p>这四条都不是崩溃，是<b>安静地给一个错答案</b>，而且在车上表现为「投屏主题不跟」，
 * 比"什么都不发"更难查 —— 每一条都对应一次真实上车：
 *
 * <ul>
 *   <li><b>AUTO（日出/日落）没有可用定位</b>：没有固定点、也没有任何一次定位（本机 GPS /
 *       系统 last-known / 曾保存的位置）时，内置的默认坐标只是"成都"这个猜测值 ——
 *       {@code TwilightCalculator} 比的是绝对 UTC 时刻，经度错 1 度就差 4 分钟，
 *       上游的海法默认值在北京偏约 5.4 小时。所以宁可判"算不出来"。</li>
 *   <li><b>LIGHT_SENSOR 没有光照传感器</b>：车机实测「光照传感器 无」，这一路在那台机器上
 *       等于恒白天 → 不信。</li>
 *   <li><b>CAR_SIGNAL 没有 {@code car_mode}</b>：车上实测三张 Settings 表里都没有这个 key。
 *       旧实现的 {@code else} 分支是「保留上一个值」，而第一个值恒为 false ——
 *       于是「大灯 ILL+」在绝大多数前装车上等于恒白天，且只打一条 warning。
 *       <b>这是本次车机日志里被抓到的第一条异常路径</b>。</li>
 *   <li><b>SCREEN_BRIGHTNESS 读失败</b>：同样落到「保留上一个值」，首帧即白天。</li>
 * </ul>
 *
 * <p>所以判据从「这个模式需不需要某种硬件」（旧版按模式白名单硬编码，必然漏掉上面后两条）
 * 改成「这一次到底有没有算出值」—— 由 {@code NightSource.Result.known} 给出，
 * 本类不再自己维护一份能力表。两份能力表正是上一次漏掉 CAR_SIGNAL / SCREEN_BRIGHTNESS 的原因。
 *
 * <p>算不出来时的退路只有一条：<b>深色</b>（见 {@link #FALLBACK_SEND}）。
 * 不再退回框架昼夜位 —— 那个位只是本机系统设置的回声，与用户选的来源无关，
 * 拿它兜底会让「设置项」看起来生效、实际一直跟着系统走。它仍会被读出来写进诊断日志。
 */
public final class IccoaNightSourcePolicy {

    private IccoaNightSourcePolicy() { }

    // 模式取值<b>不在这里重抄</b>：NightSource.MODE_* 就是 Settings.NightMode 的那批整数，
    // 重抄一份等于再造一张会漂移的表（上一次漏的正是 CAR_SIGNAL / SCREEN_BRIGHTNESS）。
    // 下面 switch 的 case 直接用限定名 —— 它们是编译期常量，switch 依然成立。

    /** 不许发（= SDK 的 UNKNOWN_MODE，{@code ControlManager} 对它直接 return false）。 */
    public static final int SEND_NONE = 0;
    public static final int SEND_DAY = DayNightProbePolicy.SDK_DAY;
    public static final int SEND_NIGHT = DayNightProbePolicy.SDK_NIGHT;

    // ---- 最终决定 ------------------------------------------------------------

    /**
     * 「算不出来」时要发的值：<b>深色</b>。
     *
     * <p>口径（0.1.9 起，用户明令）：大灯、光照传感器这类<b>车载数据源</b>能取到值就按值判，
     * 取不到值就<b>默认回退深色</b>。日出/日落缺经纬度、按区域无定位同样回退深色 ——
     * 规则单一才可预测；原来那套「退回框架昼夜位」在这台车机上是把一个恒夜间的系统位
     * 当成真信号，看起来像生效、实际与用户选择无关。
     */
    public static final int FALLBACK_SEND = SEND_NIGHT;

    /**
     * 这一次要向手机发什么。
     *
     * <p>优先级只有两级，没有第三级：
     * <ol>
     *   <li>所选来源算出了值 → 按它算出的昼夜发（这就是"设置生效"的定义）；</li>
     *   <li>算不出来（缺传感器 / 缺 {@code car_mode} / 缺定位）→ 回退 {@link #FALLBACK_SEND}（深色）。</li>
     * </ol>
     * 框架 {@code uiMode} 昼夜位<b>不再参与判定</b>：它只是本机系统设置的回声，
     * 与用户选的那一路无关，拿它兜底等于让设置项失效。它仍会被读出来写进诊断日志。
     *
     * @param mode          {@code Settings.NightMode} 的取值。<b>当前不参与决定</b>（回退值与模式无关），
     *                      保留它是为了让调用点/测试能表达"哪一路在这个组合下"，日志也用它取名字
     * @param sourceKnown   所选来源这一次<b>是否真的算出了值</b>
     *                      （{@code NightSource.Result.known}；没有读数时是 false）
     * @param sourceIsNight 所选来源算出的昼夜（{@code sourceKnown=false} 时无意义）
     * @return {@link #SEND_DAY} / {@link #SEND_NIGHT}（本方法不会返回 {@link #SEND_NONE}）
     */
    public static int decide(int mode, boolean sourceKnown, boolean sourceIsNight) {
        if (sourceKnown) {
            return sourceIsNight ? SEND_NIGHT : SEND_DAY;
        }
        return FALLBACK_SEND;
    }

    /** 这次决定实际用的是哪一路源。 */
    public static final int KIND_SHARED = 0;
    /** 源算不出来，用了回退值（深色）。 */
    public static final int KIND_FALLBACK = 1;
    public static final int KIND_NONE = 2;

    /**
     * 用的是哪一路源 —— <b>必须由纯函数判</b>，不能让调用点自己拼。
     *
     * <p>平板实测翻过一次车：AUTO 无定位时正确回退了、也真的发出了 {@code DAY_MODE(1)}，
     * 但日志却写着「源=无可用源」。因为这行是上车唯一的判读依据，
     * 写错一次就等于把一次上车作废，所以"用了哪一路"和"发了什么"必须由同一个纯函数
     * 一起决定、一起被单测钉住，而不是在接线处临时拼参数。
     *
     * @param sendMode {@link #decide} 的结果
     */
    public static int sourceKind(boolean sourceKnown, int sendMode) {
        if (sendMode == SEND_NONE) return KIND_NONE;
        return sourceKnown ? KIND_SHARED : KIND_FALLBACK;
    }

    /** 这次决定用的是哪一路，进日志便于上车判读。 */
    public static String sourceLabel(int mode, int kind) {
        switch (kind) {
            case KIND_SHARED:
                return "共享设置项[" + modeName(mode) + "]";
            case KIND_FALLBACK:
                return "回退深色（" + modeName(mode) + " 在本机算不出来）";
            default:
                return "无可用源";
        }
    }

    /** 模式可读名。 */
    public static String modeName(int mode) {
        switch (mode) {
            case NightSource.MODE_AUTO: return "日出/日落";
            case NightSource.MODE_DAY: return "恒白天";
            case NightSource.MODE_NIGHT: return "恒夜间";
            case NightSource.MODE_MANUAL_TIME: return "自定义时段";
            case NightSource.MODE_LIGHT_SENSOR: return "光照传感器";
            case NightSource.MODE_SCREEN_BRIGHTNESS: return "屏幕亮度";
            case NightSource.MODE_CAR_SIGNAL: return "大灯 ILL+";
            case NightSource.MODE_LOCATION: return "按区域";
            default: return "未知模式(" + mode + ")";
        }
    }

    /**
     * 一行转发日志。两种结果必须分得开：发了（按所选来源 / 按回退值）、什么都没发。
     * 上车只能靠这几行判断"是没检测到变化"还是"检测到了但不敢发"。
     */
    public static String sendLine(int sendMode, String source, boolean ok) {
        String what = sendMode == SEND_NIGHT ? "NIGHT_MODE(2)"
                : (sendMode == SEND_DAY ? "DAY_MODE(1)" : "（不发）");
        return "昼夜转发：源=" + source + " → 发 " + what
                + (sendMode == SEND_NONE
                        ? " —— 没有可发的值：宁可不发，也不把猜的值写进协议"
                        : (ok ? " → SDK 已接受投递" : " → SDK 拒收（无会话时本就不可能就绪）"));
    }
}
