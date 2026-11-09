package com.miauto.app;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.andrerinas.openheadunit.utils.NightMode;
import com.andrerinas.openheadunit.utils.NightSignals;
import com.andrerinas.openheadunit.utils.NightSource;
import com.andrerinas.openheadunit.utils.SunriseLocationPolicy;
// 注意：这里<b>不</b> import com.andrerinas.openheadunit.utils.Settings ——
// 本文件已经在用 android.provider.Settings（读 Global / 扫三张表），两个同名类型同时 import
// 会直接编译不过。共享设置那份用全限定名写，见 sharedSettings()。

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 昼夜主题的<b>诊断采样器</b>（只读为主，发信需 adb 显式打开）。判定逻辑全在
 * {@link DayNightProbePolicy}（可单测），本类只做 Android 侧的读与注册。
 *
 * <h3>两种启动方式</h3>
 *
 * <ul>
 *   <li><b>跟会话跑</b>（默认）：与 {@link SessionKeepAlive} 严格同步，会话建立起、
 *       失败 / 断开 / 停机 / 彻底停止四处停。不常驻 —— 待机页挂一个 5 秒定时器等于白耗电
 *       （工程里同类告诫见 {@code IccoaManager.tryStartCast} 的重试上限注释）。</li>
 *   <li><b>无会话自跑</b>（台架用）：{@code adb shell settings put global miauto_daynight_probe 1}
 *       后，在链路拉起时就开始采。没有这条就没有办法在平板上验 —— 台架上不会有手机来连，
 *       跟会话跑的那条路径一次都不会触发。为防忘关，到 {@link DayNightProbePolicy#STANDALONE_MAX_MS}
 *       自己停。</li>
 * </ul>
 *
 * <h3>它要回答的三个问题</h3>
 *
 * <ol>
 *   <li><b>这台设备到底有哪一路昼夜信号</b>：把 {@code system / secure / global} 三张
 *       {@code Settings} 表各快照一遍，之后每次变化都<b>按「表:name」打差异</b>。
 *       使用者翻一次深色模式（或司机进一次隧道），跟着翻的那个 key 就是真信号 ——
 *       不需要事先猜它叫什么。三张表都扫是平板实测逼出来的：MIUI 的开关在
 *       {@code Settings.System.dark_mode_enable}，而 AOSP 框架值历来在 {@code Settings.Secure}，
 *       只扫 System 会「跑对了探针、日志里却没有真信号」。</li>
 *   <li><b>应用侧读得到哪几张表</b>：adb shell 有 {@code READ_SECURE_SETTINGS}，
 *       它在 shell 里读得到的东西<b>不代表</b>普通应用读得到。本类把每张表的扫描结果
 *       （行数，或读失败）原样打出来，这就是应用侧权限的实测结论。</li>
 *   <li><b>手机端吃不吃 {@code notifySwitchDayOrNight}</b>：用
 *       {@code adb shell settings put global miauto_force_daynight 1|2} 强制发一个
 *       <b>与现场相反</b>的值。跟着翻 → 通道有效；不翻 → 根因不在这，方案撤回。
 *       <b>不设这个值就一个字节都不发。</b></li>
 * </ol>
 *
 * <p><b>行为边界</b>：昼夜转发<b>默认开</b>（判据见 {@link DayNightProbePolicy#isForwardEnabled}），
 * 即会话期间车机昼夜变化会经 {@code notifySwitchDayOrNight} 报给手机；
 * 除这一条之外本类只读，其余影响都是日志。DiAuto 报告只收 INFO 以上，
 * 所以全部经 {@link Log} 走 {@code Log.i}。
 */
public final class DayNightProbe {

    /** 日志出口（{@code IccoaManager} 用它同时写 Logcat 与 UI）。 */
    public interface Log {
        void log(String msg);
    }

    /**
     * 发信出口。参数是 {@code UCarCommon.DayNightMode.getValue()}（1=白天 / 2=夜间），
     * 由 {@code IccoaManager} 翻译成 SDK 枚举并调用 {@code notifySwitchDayOrNight}
     * —— SDK 类型只准出现在那一边，本类才能留在纯 JVM 可测的邻域里。
     *
     * @return SDK 的返回值（true 仅表示已投递，不代表手机端已换主题）
     */
    public interface Sender {
        boolean send(int sdkMode);
    }

    /** adb 可改的强制值所在（{@code Settings.Global}，先例见 {@code miauto_nav_usage}）。 */
    static final String FORCE_SETTING = DayNightProbePolicy.SETTING_FORCE;

    /** adb 开关：设为 1 = 没有手机连上也开始采样（平板台架用；严格 opt-in）。 */
    static final String PROBE_SWITCH = DayNightProbePolicy.SETTING_PROBE_STANDALONE;

    /**
     * adb 开关：<b>默认开</b>转发，显式写 0/false/off/no 才不发。
     *
     * <p>口径变化的依据在 {@link DayNightProbePolicy#isForwardEnabled}：信号源（框架 {@code uiMode}
     * 夜间位）与通道有效性（手机端确实换了主题）都已经在平板上连着手机的会话里实测通过，
     * 而 {@code Settings.Global} 按设备存、上车那一趟很容易忘记拨，默认关只会把"没开"看成"不生效"。
     */
    static final String FORWARD_SWITCH = DayNightProbePolicy.SETTING_FORWARD;

    /**
     * 转发是否启用 —— <b>每次用到时现读</b>，不在启动时缓存。
     *
     * <p>第一版把它在 {@link #beginOnMain()} 里读一次存成字段，结果平板上实测翻车：
     * 手机已经连上、会话已经建立之后再打开开关，转发永远不生效，而且<b>没有任何提示</b>
     * （日志里"昼夜转发"零条），看起来就像"改了没反应 / 通道无效"，会直接把一次上车白白浪费掉。
     * {@code Settings.Global} 读的是本地缓存，5 秒一次的代价可忽略。
     */
    private boolean forwardOn() {
        return DayNightProbePolicy.isForwardEnabled(readGlobalSetting(FORWARD_SWITCH));
    }

    private final Context appCtx;
    private final Log sink;
    private final Sender sender;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 快照：{@code "表名:key"} → value。用于打差异，不用于任何决策。 */
    private final Map<String, String> snapshot = new LinkedHashMap<>();

    /** 基线时「词根命中」的条目，单列一份，方便在报告里直接对上候选清单。 */
    private final List<String> interestingAtStart = new ArrayList<>();

    private boolean running = false;
    /** true = 无会话自跑（台架）；false = 跟着 ICCOA 会话跑。 */
    private boolean standalone = false;
    private boolean observerRegistered = false;
    private boolean sensorRegistered = false;
    /** 应用自身「深色模式」设置项的监听（SharedPreferences，见 registerPrefListener）。 */
    private android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener;

    private SensorManager sensors;
    private Sensor lightSensor;
    private float lastLuxReported = -1f;
    /** 只记<b>夜间位</b>（不是整个 uiMode）：形态位被别的改动带偏时不该误报成「昼夜变了」。 */
    private int lastNightBit = DayNightProbePolicy.NOT_READ;

    private long lastSampleAt = DayNightProbePolicy.NOT_READ;
    /** 上次全表重扫三张 Settings 表的时刻（与采样节奏解耦，见 RESCAN_INTERVAL_MS）。 */
    private long lastRescanAt = DayNightProbePolicy.NOT_READ;

    // ---- 转发取源（共享设置项 + 框架位后备）----------------------------------
    //
    // 转发不再写死框架 uiMode 位，而是与 Android Auto 共用同一个「深色模式」设置项
    // （8 种来源）。判定在 NightSource（应用主题也用同一份），本类每轮<b>现读</b>。
    //
    // 为什么必须现读：订阅一个 NightModeManager 的回调只在「值变化」时来，而且它的
    // 传感器/Settings 观察者只在 start() 里注册一次。用户中途把「深色模式」从光照传感器
    // 改成大灯信号时观察者不会重注册、值也不会重算 —— 转发就此冻在旧值上，又因为去重
    // （sendMode == lastSent）连一行日志都不留。车机日志里「改了设置、投屏不变」正是这一条
    // （详见 IccoaNightSourcePolicy 类注释）。现读的代价只是每 5 秒读几个 key。

    /** 与 AA 侧同一份设置读取入口；懒建，会话结束不释放（无状态，只是 prefs 包装）。 */
    private com.andrerinas.openheadunit.utils.Settings sharedSettings;
    /**
     * 最近一次光照读数（没有传感器时为 -1），供「光照传感器」那一路取值。
     *
     * <p>本类只做转发，不注册第二套传感器监听：读数由 {@link #lightListener} 顺手记下来。
     * 没有传感器就是 -1 → {@code NightSource} 会判成「算不出来」而不是「白天」。
     */
    private volatile float lastLux = -1f;
    /** 上一次发给手机的值，用于去重（只在值变了才发）。 */
    private int lastSent = IccoaNightSourcePolicy.SEND_NONE;
    private int forceMode = DayNightProbePolicy.FORCE_NONE;
    private int forceAttempts = 0;
    /** 连续投递失败次数（只为日志节流，不参与判定）。成功即清零。 */
    private int sendFailures = 0;
    /** 「初值」已经为等首帧光照读数推迟过几次（见 DayNightProbePolicy#waitForFirstLux）。 */
    private int initialLuxWaits = 0;
    /** 上次打「回退深色」提示的时刻（见 DayNightProbePolicy#shouldLogFallback）。 */
    private long lastFallbackLogAt = 0L;
    /** 台架自跑时长（默认 10 分钟，可 adb 覆盖，见 DayNightProbePolicy#parseStandaloneMaxMs）。 */
    private long standaloneMaxMs = DayNightProbePolicy.STANDALONE_MAX_MS;

    public DayNightProbe(Context appCtx, Log log, Sender sender) {
        this.appCtx = appCtx.getApplicationContext();
        this.sink = log;
        this.sender = sender;
    }

    /** 统一出口：走 {@code IccoaManager} 那条日志链（Logcat + UI 同步），INFO 级才进 DiAuto 报告。 */
    private void log(String msg) {
        sink.log(msg);
    }

    // ==================================================================
    // 启动 / 停止
    // ==================================================================

    /** ICCOA 会话建立时调用；可重复调用（幂等）。 */
    public void start() {
        // 台架模式还在跑就来真会话：先干净收尾再按会话重来一次，
        // 否则基线是台架期读的，且台架那个自动停的定时器会中途把会话期的采样掐掉。
        if (running && standalone) {
            log("昼夜诊断：台架自跑中来了真会话 → 重起为会话模式");
            stop();
        }
        begin(false);
    }

    /**
     * 台架模式：adb 设了 {@link #PROBE_SWITCH} 才启动，不等手机连接。
     *
     * <p>调用点是 {@code IccoaManager.start()}（链路拉起），<b>不是</b>会话建立 —— 这正是本方法
     * 存在的理由：平板上永远不会有手机来连。
     */
    public void startStandaloneIfRequested() {
        if (running) return;
        if (!DayNightProbePolicy.isSwitchOn(readGlobalSetting(PROBE_SWITCH))) return;
        standaloneMaxMs = DayNightProbePolicy.parseStandaloneMaxMs(
                readGlobalSetting(DayNightProbePolicy.SETTING_STANDALONE_MINUTES));
        log("昼夜诊断：检测到「无会话自跑」开关已置 1（台架模式，"
                + (standaloneMaxMs / 60_000L) + " 分钟后自动停；时长可用 "
                + DayNightProbePolicy.SETTING_STANDALONE_MINUTES + " 覆盖）");
        begin(true);
    }

    private void begin(boolean asStandalone) {
        if (running) return;
        running = true;
        standalone = asStandalone;
        // 基线含整表查询，放到主线程 Handler 上跑：调用点是 SDK 的连接回调线程，
        // 那条线程同时服务投屏链路，不该被诊断读数堵住。
        // 若期间会话被拆掉，stop() 会先把本任务摘掉；即使已入队，beginOnMain 里还会再查 running。
        main.removeCallbacks(beginRunnable);
        main.post(beginRunnable);
    }

    private final Runnable beginRunnable = new Runnable() {
        @Override
        public void run() { beginOnMain(); }
    };

    private void beginOnMain() {
        if (!running) return;
        forceMode = DayNightProbePolicy.parseForceMode(readGlobalSetting(FORCE_SETTING));
        forceAttempts = 0;
        sendFailures = 0;
        lastLuxReported = -1f;
        lastNightBit = DayNightProbePolicy.NOT_READ;
        lastSampleAt = DayNightProbePolicy.NOT_READ;
        lastRescanAt = DayNightProbePolicy.NOT_READ;
        snapshot.clear();
        interestingAtStart.clear();

        boolean fwd = forwardOn();
        log("昼夜诊断：已启动（" + (standalone ? "台架模式，无会话" : "跟随 ICCOA 会话")
                + "；每 " + (DayNightProbePolicy.SAMPLE_INTERVAL_MS / 1000) + " 秒采样一次；"
                + DayNightProbePolicy.sendStateLine(forceMode, fwd) + "）");

        takeBaseline();
        registerObservers();
        armSample();

        if (DayNightProbePolicy.isSendable(forceMode)) {
            attemptForceSend();
        } else if (forceMode == DayNightProbePolicy.FORCE_INVALID) {
            log(DayNightProbePolicy.forceRejectLine(readGlobalSetting(FORCE_SETTING)));
        } else if (fwd) {
            // 初值同步：连接建立后手机侧主题要先对齐，否则要等到下一次翻昼夜才纠正。
            // 现在取源是现读的（见 evaluateAndSend），一次调用就够 —— 不再有"等共享源回调"的
            // 那段竞态（那会让初值落到框架位，与共享源算出的值差一次发送）。
            //
            // 但「现读」对光照路径有个例外：SensorManager 没有同步读接口，刚注册时
            // lastLux 还是 -1，初值就会先退框架位。所以推迟到首帧读数到手再发（最多 1.5 秒）。
            scheduleInitialSend();
        }

        if (standalone) {
            main.removeCallbacks(standaloneStopRunnable);
            main.postDelayed(standaloneStopRunnable, standaloneMaxMs);
        }
    }

    private final Runnable standaloneStopRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || !standalone) return;
            log("昼夜诊断：台架模式到时自动停止（还要长期跑就重设 "
                    + PROBE_SWITCH + "；不再需要请 " +
                    "adb shell settings delete global " + PROBE_SWITCH + "）");
            stop();
        }
    };

    /**
     * 排「初值」这一发；光照路径下先等首帧读数。
     *
     * <p>只为初值服务：后续每 5 秒的采样不等，因为那时读数早已稳定，等只会拖慢纠正。
     */
    private void scheduleInitialSend() {
        initialLuxWaits = 0;
        main.removeCallbacks(initialSendRunnable);
        main.postDelayed(initialSendRunnable, DayNightProbePolicy.INITIAL_LUX_WAIT_MS);
    }

    private final Runnable initialSendRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (DayNightProbePolicy.waitForFirstLux(
                    currentNightModeOrdinal(), lastLux, initialLuxWaits)) {
                initialLuxWaits++;
                main.postDelayed(this, DayNightProbePolicy.INITIAL_LUX_WAIT_MS);
                return;
            }
            if (initialLuxWaits > 0 && lastLux < 0f) {
                // 等满了还没有读数：真没有传感器（车机实测就是这种），照常发（退回框架位）。
                log("昼夜诊断：等首帧光照读数超时（" + initialLuxWaits + " 次），初值按框架昼夜位发");
            }
            evaluateAndSend("初值", true);
        }
    };

    /** 会话结束 / 停机 / 彻底停止时调用；可重复调用。 */
    public void stop() {
        if (!running) return;
        running = false;
        boolean wasStandalone = standalone;
        standalone = false;
        main.removeCallbacks(beginRunnable);
        main.removeCallbacks(sampleRunnable);
        main.removeCallbacks(forceRetryRunnable);
        main.removeCallbacks(standaloneStopRunnable);
        main.removeCallbacks(initialSendRunnable);
        if (sensorRegistered && sensors != null) {
            try { sensors.unregisterListener(lightListener); } catch (Throwable ignored) { }
            sensorRegistered = false;
        }
        if (observerRegistered) {
            try { appCtx.getContentResolver().unregisterContentObserver(settingsObserver); }
            catch (Throwable ignored) { }
            observerRegistered = false;
        }
        unregisterPrefListener();
        snapshot.clear();
        interestingAtStart.clear();
        lastSent = IccoaNightSourcePolicy.SEND_NONE;   // 新会话要重新对齐一次初值
        sendFailures = 0;
        // 提示节流按会话重置：新会话要能打一条「现在起在回退深色」，但同一次启动内被
        // 重复拉起时不重置（否则两三秒内就能刷出两条，实测过）。
        lastFallbackLogAt = 0L;
        lastLux = -1f;
        log("昼夜诊断：已停止（" + (wasStandalone ? "台架模式收尾" : "会话结束")
                + "，注销传感器与 Settings 监听）");
    }

    // ==================================================================
    // 基线快照
    // ==================================================================

    private void takeBaseline() {
        // 1) 框架侧昼夜位 —— 零厂商私有 key 的那一路，标准 Android / 前装多半走这里
        int uiMode = currentUiMode();
        lastNightBit = uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String nightModeViaService = readUiModeManagerNight();
        log("昼夜诊断：基线 " + DayNightProbePolicy.describeUiMode(uiMode)
                + (nightModeViaService == null ? "" : "；UiModeManager.getNightMode()=" + nightModeViaService));

        // 2) 点名候选：报告假设的 car_mode、平板实测的 dark_mode_enable、AOSP 的 ui_night_mode
        //    到底在哪张表里（或根本不存在），这里一次性给结论
        StringBuilder named = new StringBuilder();
        for (String table : DayNightProbePolicy.SETTINGS_TABLES) {
            for (String key : DayNightProbePolicy.namedCandidates()) {
                String v = readTableSetting(table, key);
                if (v == null) continue;      // 只报存在的；一个都不存在时下面全表扫描兜底
                String safe = DayNightProbePolicy.safeValue(v);
                named.append(' ').append(table).append(':').append(key).append("=\"").append(safe).append('"');
                snapshot.put(table + ":" + key, safe);
            }
        }
        log("昼夜诊断：候选 key 命中 →" + (named.length() == 0 ? " 一个都不存在" : named.toString()));

        // 3) 三张表全扫（只留词根命中的项，见 isDiffableName）：真正回答
        //    「这台设备把昼夜写成哪个 key」靠的是它 + 后续差异。
        //    读失败也如实打出来 —— 那就是应用侧权限的实测结论（shell 读得到 ≠ 应用读得到）。
        for (String table : DayNightProbePolicy.SETTINGS_TABLES) {
            Map<String, String> rows = new LinkedHashMap<>();
            int n = scanTableInto(table, rows);
            if (n < 0) {
                log("昼夜诊断：Settings." + table.toUpperCase(java.util.Locale.US)
                        + " 全表应用侧读不到（普通应用无权限或被 ROM 拒）→ 该表只能靠上面的点名候选");
                continue;
            }
            snapshot.putAll(rows);
            for (String key : rows.keySet()) {
                if (interestingAtStart.size() >= DayNightProbePolicy.MAX_INTERESTED_LOGGED) break;
                interestingAtStart.add(key);
            }
            log("昼夜诊断：Settings." + table.toUpperCase(java.util.Locale.US)
                    + " 共 " + n + " 行，词根命中 " + rows.size() + " 项");
        }
        log("昼夜诊断：命中明细 → " + join(interestingAtStart, snapshot));

        // 4) 光照传感器在不在（车上描述的现象就是它驱动的）
        try {
            sensors = (SensorManager) appCtx.getSystemService(Context.SENSOR_SERVICE);
            lightSensor = (sensors == null) ? null : sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        } catch (Throwable t) {
            lightSensor = null;
        }
        log("昼夜诊断：光照传感器 " + (lightSensor == null ? "无（只能靠 key 或 uiMode）"
                : "有（" + lightSensor.getName() + "）"));
    }

    // ==================================================================
    // 注册
    // ==================================================================

    private void registerObservers() {
        // stop() 可能已经先跑过（会话极短 / 建链即断）。running 为假时绝不再注册，
        // 否则会留下一个没人注销的观察者。
        if (!running) return;
        if (!observerRegistered) {
            try {
                // 同一个观察者注册到三张表的基 URI + notifyForDescendants=true：
                // 任何一个 key 被写都会回调，于是"没在候选清单里猜到的那个 key"翻转时同样能被抓到。
                // unregisterContentObserver(观察者) 会把它在全部 URI 上的注册一并摘掉。
                for (String table : DayNightProbePolicy.SETTINGS_TABLES) {
                    appCtx.getContentResolver().registerContentObserver(
                            tableUri(table), true, settingsObserver);
                }
                observerRegistered = true;
            } catch (Throwable t) {
                log("昼夜诊断：Settings 监听注册失败（不影响轮询采样）: " + t);
            }
        }
        if (!sensorRegistered && lightSensor != null && sensors != null) {
            try {
                sensors.registerListener(lightListener, lightSensor,
                        SensorManager.SENSOR_DELAY_NORMAL);
                sensorRegistered = true;
            } catch (Throwable t) {
                log("昼夜诊断：光照监听注册失败: " + t);
            }
        }
        registerPrefListener();
    }

    /**
     * 盯住应用自己的「深色模式」设置项（SharedPreferences）。
     *
     * <p>必须有：{@code Settings} 观察者只能看到 android.provider 那三张表，看不到
     * SharedPreferences。没有这条，用户在设置页换路径后要等下一个 5 秒轮询才可能生效；
     * 而上车验证时"改完立刻看"是唯一可信的判读方式（等 5 秒再变，容易被误判成"改了没反应"）。
     */
    private void registerPrefListener() {
        if (prefListener != null) return;
        try {
            final android.content.SharedPreferences prefs = appCtx.getSharedPreferences(
                    com.andrerinas.openheadunit.utils.Settings.PREFS_NAME,
                    Context.MODE_PRIVATE);
            prefListener = new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) {
                    if (!running || key == null) return;
                    if (!isNightSettingKey(key)) return;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!running) return;
                            if (forwardOn()) evaluateAndSend("设置变化", false);
                        }
                    });
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable t) {
            prefListener = null;
            log("昼夜诊断：设置项监听注册失败（不影响 5 秒轮询）: " + t);
        }
    }

    private void unregisterPrefListener() {
        if (prefListener == null) return;
        try {
            appCtx.getSharedPreferences(com.andrerinas.openheadunit.utils.Settings.PREFS_NAME,
                    Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefListener);
        } catch (Throwable ignored) { }
        prefListener = null;
    }

    /** 与「深色模式」相关的那几个 prefs key（换路径、阈值、时段、固定点、区域）。 */
    static boolean isNightSettingKey(String key) {
        return key.startsWith("night-mode")
                || key.startsWith("use-fixed-sun-location")
                || key.startsWith("fixed-sun")
                || key.startsWith("location-outside-night")
                || key.startsWith("geofence-locations");
    }

    private static Uri tableUri(String table) {
        return Uri.parse("content://settings/" + table);
    }

    private final ContentObserver settingsObserver = new ContentObserver(main) {
        @Override
        public void onChange(boolean selfChange) {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            // 限流：任何 key 被写都会回调，而每次回调都要扫三张表（见 MIN_RESCAN_MS 的说明）。
            // 被跳过的那些不补 —— 轮询会覆盖到。
            // 注意这里用的是 lastRescanAt（重扫节奏），不是 lastSampleAt（采样节奏）：
            // 两者解耦之后，一次 Settings 写入不该把 5 秒采样也往后推。
            if (!DayNightProbePolicy.shouldSample(now, lastRescanAt,
                    DayNightProbePolicy.MIN_RESCAN_MS)) {
                return;
            }
            lastRescanAt = now;
            rescanSettings("Settings 写入");
            // 系统侧设置（含框架昼夜那几个 key）刚被改 → 立刻按新值重算一次转发，
            // 不用等下一个 5 秒轮询。注意这里<b>不是</b>应用自己的「深色模式」设置项：
            // 那个存在 SharedPreferences 里，走 prefListener（见 registerPrefListener）。
            if (forwardOn()) {
                evaluateAndSend("系统设置变化", false);
            }
        }
    };

    private final SensorEventListener lightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!running || event == null || event.values == null || event.values.length == 0) return;
            float lux = event.values[0];
            // 判定要用最新读数（「光照传感器」那一路），所以先落值再判是否值得打日志。
            lastLux = lux;
            if (!DayNightProbePolicy.luxChanged(lastLuxReported, lux)) return;
            lastLuxReported = lux;
            log(DayNightProbePolicy.luxLine(lux, currentUiMode()));
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            if (DayNightProbePolicy.shouldSample(now, lastSampleAt,
                    DayNightProbePolicy.SAMPLE_INTERVAL_MS)) {
                lastSampleAt = now;
                pollOnce();
            }
            armSample();
        }
    };

    private void armSample() {
        main.removeCallbacks(sampleRunnable);
        main.postDelayed(sampleRunnable, DayNightProbePolicy.SAMPLE_INTERVAL_MS);
    }

    // ==================================================================
    // 采样
    // ==================================================================

    private void pollOnce() {
        int uiMode = currentUiMode();
        // 只对夜间位做变化判定：形态位（CAR/NORMAL）可能被无关改动带偏，若拿整个 uiMode 比较，
        // 会把「不是昼夜的变化」误报成「昼夜变了」，直接污染这次要回答的问题。
        int nightBit = uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (DayNightProbePolicy.changed(lastNightBit, nightBit)) {
            log(DayNightProbePolicy.uiModeChangedLine(uiMode));
        }
        lastNightBit = nightBit;
        rescanSettingsIfDue("轮询");
        // 转发不再只在"框架位翻转"时做：源已经换成共享设置项，而它可能因任何一路原因变化
        // （日落时刻跨过、光照读数更新、用户在设置里改模式），这些都不一定伴随 uiMode 翻转。
        // 所以每轮都重算一次，由 evaluateAndSend 自己按"值变了才发"去重。
        if (forwardOn()) {
            evaluateAndSend("轮询", false);
        }
    }

    /**
     * 到点才重扫三张 Settings 表（<b>诊断</b>用，与转发解耦，见 {@link DayNightProbePolicy#RESCAN_INTERVAL_MS}）。
     *
     * <p>转发本身只调 {@code readRaw}（读几个 key），不需要整表遍历。
     */
    private void rescanSettingsIfDue(String why) {
        long now = SystemClock.uptimeMillis();
        if (!DayNightProbePolicy.shouldSample(now, lastRescanAt,
                DayNightProbePolicy.RESCAN_INTERVAL_MS)) {
            return;
        }
        lastRescanAt = now;
        rescanSettings(why);
    }

    // ==================================================================
    // 转发取源：与 Android Auto 共用同一个「深色模式」设置项，框架位作后备
    // ==================================================================

    private com.andrerinas.openheadunit.utils.Settings sharedSettings() {
        if (sharedSettings == null) {
            sharedSettings = new com.andrerinas.openheadunit.utils.Settings(appCtx);
        }
        return sharedSettings;
    }

    /**
     * 把「深色模式」这个设置项现读成一组原始读数。
     *
     * <p>每轮都重新读：用户在设置里换路径、路上光线变化、进隧道，都不需要任何回调通知。
     * 只读当前模式真正需要的那几项，其余留默认 -1（= 取不到），避免把无关读数当成依据。
     */
    private NightSource.Raw readRaw(int mode) {
        com.andrerinas.openheadunit.utils.Settings s = sharedSettings();
        NightSource.Raw raw = new NightSource.Raw();
        // 迟滞：拿上一次"已经发出去的值"当基准，避免在阈值附近来回抖着发。
        raw.lastNight = lastSent == IccoaNightSourcePolicy.SEND_NONE
                ? null : (lastSent == IccoaNightSourcePolicy.SEND_NIGHT);
        raw.thresholdLux = s.getNightModeThresholdLux();
        raw.thresholdBrightness = s.getNightModeThresholdBrightness();
        raw.manualStart = s.getNightModeManualStart();
        raw.manualEnd = s.getNightModeManualEnd();
        raw.nowMinutes = NightSignals.INSTANCE.nowMinutes();
        switch (mode) {
            case NightSource.MODE_LIGHT_SENSOR:
                raw.lux = lastLux;
                break;
            case NightSource.MODE_SCREEN_BRIGHTNESS:
                raw.brightness = NightSignals.INSTANCE.readBrightness(appCtx);
                break;
            case NightSource.MODE_CAR_SIGNAL:
                raw.carMode = NightSignals.INSTANCE.readCarMode(appCtx);
                break;
            case NightSource.MODE_LOCATION:
                raw.locationNight = NightSignals.INSTANCE.geofenceNight(appCtx, s);
                break;
            case NightSource.MODE_AUTO: {
                // 坐标只取一次：判定用的点、参与计算的点、日志里写的点必须是同一个
                // （之前分别调 usable / label / 一个构造期快照的 calculator，等于三次独立取坐标，
                //  取到哪个点全凭运气，日志还会写着另一个）。顺带省掉每轮 new 一整套 NightMode
                //  —— 那是 TwilightCalculator + SimpleDateFormat 一起分配，5 秒一次。
                SunriseLocationPolicy.Ref ref = NightSignals.INSTANCE.sunriseReference(appCtx, s);
                raw.locationUsable = ref != null;
                raw.locationSource = ref == null ? null : ref.toString();
                raw.sunsetNight = ref != null
                        && NightMode.sunriseNightAt(ref.latitude, ref.longitude);
                break;
            }
            default:
                break;
        }
        return raw;
    }

    /**
     * 按 {@link IccoaNightSourcePolicy} 决定这次发什么，值变了才发。
     *
     * <p>优先级两级：算得出来按用户选的来源发；算不出来（无光照传感器 / 无 {@code car_mode} /
     * 无定位）回退<b>深色</b>。框架昼夜位只写进日志做对照，不参与决定 ——
     * 它是系统设置的回声，拿它兜底会让「深色模式」这个设置项形同虚设。
     */
    private void evaluateAndSend(String why, boolean forceLog) {
        int mode = currentNightModeOrdinal();
        NightSource.Result src = NightSource.decide(mode, readRaw(mode));
        // 框架位取的是<b>系统级</b>口径（NightSignals.frameworkNightBit），不是本进程
        // Resources 里那个 —— 后者已被我们自己的 AppCompatDelegate 改写，只反映应用主题。
        // 它只进日志（见下方回退提示），不参与判定。
        int sendMode = IccoaNightSourcePolicy.decide(mode, src.known, src.isNight);
        int kind = IccoaNightSourcePolicy.sourceKind(src.known, sendMode);
        // 回退提示必须排在去重之前。回退态的 sendMode 恒等于 FALLBACK（深色），从第二轮起
        // sendMode == lastSent 就会直接 return，提示再也打不出来 —— 现场只剩"一直深色、
        // 不知道原因"，上一次排查正好撞在这个盲区上（清空 logcat 后一条都抓不到）。
        // 刷屏风险由 shouldLogFallback 自己节流（会话首条 + 每 10 分钟一条）。
        if (kind == IccoaNightSourcePolicy.KIND_FALLBACK && !src.known) {
            // 通道未就绪时每 5 秒都会走到这里，必须节流（首条 + 每 10 分钟一条）。
            // 注意这里刻意<b>不</b>跟 forceLog 走：一次启动里探针会被拉起两次（实测），
            // 两条「初值」各带 force=true 时就会连打两条，等于没节流。
            // 新会话的首条由 stop() 重置时间戳保证。
            long nowMs = SystemClock.uptimeMillis();
            if (DayNightProbePolicy.shouldLogFallback(nowMs, lastFallbackLogAt, false)) {
                lastFallbackLogAt = nowMs;
                log("昼夜诊断：所选「" + IccoaNightSourcePolicy.modeName(mode)
                        + "」在本机算不出来（" + src.why + "）→ 按规则回退深色"
                        + "（框架昼夜位=" + NightSignals.INSTANCE.frameworkSourceLabel(appCtx)
                        + "，仅作对照，不参与判定）");
            }
        }
        if (!forceLog && sendMode == lastSent) return;   // 去重：值没变不重复发，也不刷日志
        lastSent = sendMode;
        if (sendMode == IccoaNightSourcePolicy.SEND_NONE) {
            log(IccoaNightSourcePolicy.sendLine(sendMode,
                    IccoaNightSourcePolicy.sourceLabel(mode, kind), false) + "（" + why + "）");
            return;
        }
        boolean ok = false;
        try {
            ok = sender.send(sendMode);
        } catch (Throwable t) {
            log("昼夜转发：调用异常（不影响投屏）: " + t);
            return;
        }
        if (ok) {
            sendFailures = 0;
        } else {
            // 没投递成功就<b>不能</b>记成"这个值已经发过了"：控制通道在会话刚建立的那几秒
            // 常常还没就绪（sendSwitchDayOrNight 直接 return false）。旧实现照样记下 lastSent，
            // 于是整场会话都不再重试，手机端主题就永远停在上一次会话的值上 ——
            // 表现为"连上了、设置也对、投屏就是不跟"。清回 NONE 让下一轮（≤5 秒）自动补发。
            lastSent = IccoaNightSourcePolicy.SEND_NONE;
            sendFailures++;
            // 通道未就绪时每 5 秒都失败，日志按 1/12 节流：首条必打，之后每分钟一条。
            if (!forceLog && sendFailures > 1 && (sendFailures % 12) != 0) return;
        }
        log(IccoaNightSourcePolicy.sendLine(sendMode,
                IccoaNightSourcePolicy.sourceLabel(mode, kind), ok)
                + "（" + why + "；" + src.why + "）");
    }

    /** 读用户选的昼夜模式；读不到时按 AUTO 处理（最保守，会走定位可用性判定）。 */
    private int currentNightModeOrdinal() {
        try {
            return sharedSettings().getNightMode().getValue();
        } catch (Throwable t) {
            return NightSource.MODE_AUTO;
        }
    }

    /** 重扫三张表，只打与快照的差异（有条数上限，防刷屏）。 */
    private void rescanSettings(String why) {
        Map<String, String> fresh = new LinkedHashMap<>();
        for (String table : DayNightProbePolicy.SETTINGS_TABLES) {
            Map<String, String> rows = new LinkedHashMap<>();
            if (scanTableInto(table, rows) < 0) {
                // 该表读不到：退化为只盯点名候选，至少不是一无所获
                for (String key : DayNightProbePolicy.namedCandidates()) {
                    String v = readTableSetting(table, key);
                    if (v != null) {
                        fresh.put(table + ":" + key, DayNightProbePolicy.safeValue(v));
                    }
                }
                continue;
            }
            fresh.putAll(rows);
        }
        int diffs = 0;
        for (Map.Entry<String, String> e : fresh.entrySet()) {
            String old = snapshot.get(e.getKey());
            if (old == null) {
                if (diffs++ < DayNightProbePolicy.MAX_INTERESTED_LOGGED) {
                    log(DayNightProbePolicy.settingsChangedLine(e.getKey(), null, e.getValue()));
                }
                snapshot.put(e.getKey(), e.getValue());
            } else if (!old.equals(e.getValue())) {
                if (diffs++ < DayNightProbePolicy.MAX_INTERESTED_LOGGED) {
                    log(DayNightProbePolicy.settingsChangedLine(
                            e.getKey(), old, e.getValue()));
                }
                snapshot.put(e.getKey(), e.getValue());
            }
        }
        if (diffs >= DayNightProbePolicy.MAX_INTERESTED_LOGGED) {
            log("昼夜诊断：" + why + "：差异条数已达上限 "
                    + DayNightProbePolicy.MAX_INTERESTED_LOGGED + " 条，其余并入基线不再逐条打印");
        }
    }

    // ==================================================================
    // 强制发信（唯一会改手机行为的路径，且必须 adb 显式打开）
    // ==================================================================

    private void attemptForceSend() {
        if (!running || !DayNightProbePolicy.isSendable(forceMode)) return;
        forceAttempts++;
        boolean ok = false;
        try {
            ok = sender.send(forceMode);
        } catch (Throwable t) {
            log("昼夜诊断：调用 notifySwitchDayOrNight 异常（不影响投屏）: " + t);
            ok = false;
        }
        log(DayNightProbePolicy.sendLine(forceMode, forceAttempts, ok));
        if (DayNightProbePolicy.shouldRetrySend(forceAttempts, ok)) {
            main.removeCallbacks(forceRetryRunnable);
            main.postDelayed(forceRetryRunnable, DayNightProbePolicy.FORCE_SEND_RETRY_MS);
        } else if (!ok) {
            log("昼夜诊断：已用尽 " + DayNightProbePolicy.FORCE_SEND_MAX_ATTEMPTS
                    + " 次补发仍被拒 → 没有手机连着，控制通道本来就不可能就绪；"
                    + "这条只在真会话里才有判读意义");
        }
    }

    private final Runnable forceRetryRunnable = new Runnable() {
        @Override
        public void run() { attemptForceSend(); }
    };

    // ==================================================================
    // 读数（全部只读、全部单独兜异常：一个诊断读数不许把会话带崩）
    // ==================================================================

    private int currentUiMode() {
        try {
            Configuration c = appCtx.getResources().getConfiguration();
            return c == null ? 0 : c.uiMode;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** {@code UiModeManager.getNightMode()}（需 API 20）；拿不到返回 null，不猜。 */
    private String readUiModeManagerNight() {
        if (Build.VERSION.SDK_INT < 20) return null;
        try {
            UiModeManager um = (UiModeManager) appCtx.getSystemService(Context.UI_MODE_SERVICE);
            if (um == null) return null;
            int m = um.getNightMode();
            switch (m) {
                case 0: return "MODE_NIGHT_AUTO(0，按时间/经纬度)";
                case 1: return "MODE_NIGHT_NO(1，恒白天)";
                case 2: return "MODE_NIGHT_YES(2，恒夜间)";
                case 3: return "MODE_NIGHT_FOLLOW_SYSTEM(3)";
                default: return String.valueOf(m);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private String readGlobalSetting(String key) {
        if (Build.VERSION.SDK_INT < 17) return null;   // Settings.Global 需 API 17
        try {
            return Settings.Global.getString(appCtx.getContentResolver(), key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按表名单读一个 key；不存在或读不到返回 null（用 getString，避开 getInt 的异常路径）。 */
    private String readTableSetting(String table, String key) {
        Cursor c = null;
        try {
            c = appCtx.getContentResolver().query(tableUri(table),
                    new String[] { "value" }, "name=?", new String[] { key }, null);
            if (c == null || !c.moveToFirst()) return null;
            int idx = c.getColumnIndex("value");
            return idx < 0 ? null : c.getString(idx);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * 扫某一张 {@code Settings} 表，把<b>词根命中</b>的 name→value 写进 {@code into}。
     *
     * <p>只留命中的项是平板实测定的：整表比对会因行序不稳定与截断上限产生上百条假「新出现」，
     * 见 {@link DayNightProbePolicy#isDiffableName}。
     *
     * <p>值统一过 {@link DayNightProbePolicy#safeValue}：既把 NULL 列归一成「(空)」
     * （否则差异判定每轮都把空值 key 当成没见过、无限重报），也消掉值里的换行
     * （DiAuto 报告按行收日志）。
     *
     * <p>行数一律截断到 {@link DayNightProbePolicy#MAX_ROWS_SCANNED}：基线与重扫用同一个上限，
     * 否则两次扫的行数口径不同，差异日志会把「没扫到」误报成「被删了」。
     *
     * @return 读到的总行数（含未命中的）；整张表读不出来（应用无权限 / ROM 拒绝 / 无该列）返回 -1
     */
    private int scanTableInto(String table, Map<String, String> into) {
        Cursor c = null;
        try {
            c = appCtx.getContentResolver().query(tableUri(table),
                    new String[] { "name", "value" }, null, null, null);
            if (c == null) return -1;
            int nameIdx = c.getColumnIndex("name");
            int valIdx = c.getColumnIndex("value");
            if (nameIdx < 0) return -1;
            int n = 0;
            while (c.moveToNext()) {
                if (n >= DayNightProbePolicy.MAX_ROWS_SCANNED) break;
                String name = c.getString(nameIdx);
                n++;
                if (name == null || !DayNightProbePolicy.isInterestingName(name)) continue;
                into.put(table + ":" + name,
                        DayNightProbePolicy.safeValue(valIdx < 0 ? null : c.getString(valIdx)));
            }
            return n;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Throwable ignored) { }
            }
        }
    }

    /** 把命中的 key 连同当时的值拼成一行（值已在入库时过 {@code safeValue}，不会再带换行）。 */
    private static String join(List<String> keys, Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(' ');
            String k = keys.get(i);
            sb.append(k).append('=').append(values.get(k));
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }
}
