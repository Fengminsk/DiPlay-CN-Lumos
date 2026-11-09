package com.miauto.app;

import java.nio.charset.StandardCharsets;

/**
 * 「AP 通道复用既有热点」的纯判定（AP 通道改造支线 A，不改 dex）。
 *
 * <p><b>背景</b>：ICCOA SDK 的 AP 通道只会「自建热点」—— {@code WifiApService.oldStartSoftAp}
 * 先 {@code setWifiApConfiguration} 改写车机热点配置、再反射 {@code ConnectivityManager.mService}
 * 去起 tethering（后者在 targetSdk 36 下被 hidden API 拦截，必失败）。结果是「配置被改了、
 * 热点却没起来」。SDK 内部其实有复用分支（反编译 {@code WifiApService$WifiApStub.createSoftAp}
 * 736 行：{@code mSsid != null && mSsid.equals(actualSsid) && getWifiApState() == 13} → 走
 * {@code mApInfoNotifier} 通知、零 tethering 调用），但 {@code mSsid} 只在其自己的广播接收器里
 * 被赋值，先开热点再启动 app 时序上永远错过。
 *
 * <p><b>本类的职责</b>：在 SDK 打算自建热点之前，判定「要不要把 AP 已就绪的答案注入给它」
 * （让复用分支命中）。判定拆成三条独立输入：
 * <ul>
     *   <li>{@code flagEnabled}：总开关 {@code mi_auto_lowend/ap_reuse}，默认 <b>true</b>
     *       （2026-10-24 起：配置入口合并进「连接设置」后默认开启；UI 入口在
     *       {@code ConnectionSetupFragment} 的「车载热点」下）；</li>
 *   <li>{@code transport}：建链方式偏好，仅 {@code ap} 时才有注入的意义；</li>
 *   <li>{@code apState} / {@code ssid} / {@code password} / {@code injectorHealthy}：
 *       车机热点是否开着、配置是否可用、注入器自检是否通过。</li>
 * </ul>
 *
 * <p>输出三值：{@link Decision#NOT_APPLICABLE}（与我无关，什么都不做）、
 * {@link Decision#REUSE}（注入，让 SDK 复用既有热点）、
 * {@link Decision#DECLINE}（不注入 —— SDK 会走它今天的失败路径，我们只负责落诊断，
 * 不去替它兜底，避免「半个自建」造成新状态）。
 *
 * <p><b>已知边界（写进文档的，不是缺陷）</b>：DECLINE 时 SDK 仍会尝试自建并失败，
 * {@code setWifiApConfiguration} 仍会改写<b>已保存的</b>热点配置（反编译 301–324 行，
 * 配置写入在反射失败之前）。热点开启且注入命中的场景（REUSE）不会走到那条路 —— 这正是
 * 验收指标 P1「配置被改写次数 = 0」要求热点先开的原因。彻底封死自建属于支线 B（dex 补丁），
 * 由 Gate-R 决定是否执行。
 *
 * <p>抽成独立纯函数类的原因与 {@link CastGate} / {@link LinkStatePolicy} 相同：注入行为只有
 * 在 Android 10 车机上才看得出差别，先在 JVM 单测里把判定钉死（见 {@code ApReusePolicyTest}）。
 */
final class ApReusePolicy {

    // ---- 偏好键（方案 D0.3 / D-A4；UI 接线在 D-A4，常量先于此固化，防止键名漂移） ----

    /** 所有 MiAuto 偏好共用的存储文件（与 {@code transport} / {@code cast_fps} 同处）。 */
    static final String PREF_FILE = "mi_auto_lowend";
    /** AP 复用总开关，默认 {@code true}（读写默认值统一在 {@code ApReusePrefs.flag}）。 */
    static final String PREF_FLAG = "ap_reuse";
    /** 用户手填的车机热点 SSID（DiPlay 式兜底，绕开读系统热点配置的权限/时序问题）。 */
    static final String PREF_SSID = "ap_ssid";
    /** 用户手填的车机热点密码，与 {@link #PREF_SSID} 配套。 */
    static final String PREF_PASSWORD = "ap_password";

    /** SDK {@code WifiManager.getWifiApState()} 的「已开启」值（反编译 21–25 行常量表）。 */
    static final int WIFI_AP_STATE_ENABLED = 13;

    /** 车机热点状态（由调用方经反射读出后传入，本类不碰 Android API）。 */
    enum ApState { ENABLED, DISABLED, UNKNOWN }

    enum Decision {
        /** 注入 SDK：让它复用既有热点，不发起 tethering。 */
        REUSE,
        /** 不注入：条件不满足，落诊断后任由 SDK 走它既有的（会失败的）路径。 */
        DECLINE,
        /** 与本功能无关（开关关 / 通道不是 ap），什么都不做。 */
        NOT_APPLICABLE,
    }

    private ApReusePolicy() { }

    /**
     * @param flagEnabled     总开关 {@code mi_auto_lowend/ap_reuse} 的当前值
     * @param transport       建链方式偏好（{@code p2p} / {@code ap} / {@code both}）
     * @param apState         车机热点当前状态
     * @param ssid            热点 SSID（自动读到的或用户手填的）
     * @param password        热点密码
     * @param injectorHealthy 注入器启动自检是否通过（类可加载、字段/方法可解析）
     */
    static Decision decide(boolean flagEnabled, String transport, ApState apState,
                           String ssid, String password, boolean injectorHealthy) {
        if (!flagEnabled) return Decision.NOT_APPLICABLE;
        // 非 ap 一律不管：AP/P2P 互斥（IccoaManager.transportMode 已把历史 "both" 收敛成
        // ap 或 p2p），p2p 通道下热点与注入都无关。
        if (!"ap".equals(transport)) return Decision.NOT_APPLICABLE;
        if (!injectorHealthy) return Decision.DECLINE;
        if (apState != ApState.ENABLED) return Decision.DECLINE;
        if (!isValidSsid(ssid)) return Decision.DECLINE;
        if (!isValidPassphrase(password)) return Decision.DECLINE;
        return Decision.REUSE;
    }

    /**
     * SSID 合法性，镜像 SDK {@code WifiApService.isValidSsid} 的字节长度口径：
     * UTF-8 编码后 9..32 字节（反编译 396–405 行）。长度不符时 SDK 的复用判定同样不会命中，
     * 与其对齐可避免「策略说 REUSE、SDK 却判 invalid」的错位。
     */
    static boolean isValidSsid(String ssid) {
        if (ssid == null) return false;
        int len = ssid.getBytes(StandardCharsets.UTF_8).length;
        return len >= 9 && len <= 32;
    }

    /**
     * 密码合法性：WPA2-PSK 口径 8..63 字符。SDK 自身只查非空（反编译 731–733 行），
     * 但手填兜底入口要在这里拦住「明显连不上的密码」，否则注入成功、手机却入不了网，
     * 现象与「复用失败」无法区分。
     */
    static boolean isValidPassphrase(String password) {
        if (password == null) return false;
        int len = password.length();
        return len >= 8 && len <= 63;
    }
}
