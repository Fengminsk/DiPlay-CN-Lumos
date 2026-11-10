package com.miauto.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceView;

import com.andrerinas.openheadunit.utils.SdkLogCapture;
import com.andrerinas.openheadunit.utils.Settings;
import com.share.connect.WifiOwnerConfig;
import com.ucar.vehiclesdk.IAppSurfaceStateListener;
import com.ucar.vehiclesdk.ICarConnectListener;
import com.ucar.vehiclesdk.ICarInitCallback;
import com.ucar.vehiclesdk.IPhoneDataListener;
import com.ucar.vehiclesdk.UCarAdapter;
import com.ucar.vehiclesdk.UCarCommon;
import com.ucar.vehiclesdk.UCarConfig;
import com.ucar.vehiclesdk.audio.UCarAudioManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * MiAuto 车机端 ICCOA CarLink（小米 CarWith）SDK 生命周期管理器。
 *
 * <p>本类只管 SDK：初始化 / 广播 / 投屏 Surface / 回调转发。UI 与 BYD 输出通过
 * {@link Listener} 解耦（由 Activity 或 {@code IccoaNavigationBridge} 实现）。
 *
 * <p><b>★ init-once 状态机</b>：{@link UCarAdapter#init} 在 SDK 内部是「进程一次性」
 * （{@code sIsServiceInitialized} 守门）。重启只 {@code startAdvertise()}，绝不重复 init，
 * 否则 BLE 广播状态残留，手机扫不到、{@code onPinCode} 不再触发。
 * {@link #stop()} 断开但保留初始化标志。
 */
public class IccoaManager {

    private static final String TAG = "MiAuto/ICCOA";

    /** 事件回调。由 UI / 导航桥接实现。 */
    public interface Listener {
        void onLog(String msg);
        /** 已连接状态变化：0 断开 / 1 连接中 / 2 已连接 / 3 已建立 */
        void onConnectionState(int state);
        void onPinCode(String pinCode, String carDeviceName);
        void onCastStarted();
        /**
         * 连接失败。{@code errorCode} 见 {@code UCarConnectState.ErrorCode}：
         * 10003 无线建链失败 / 10004 USB 连接失败 / 10005 启动投屏失败 /
         * 10006 建链超时（鉴权通过后 10 秒内没建好） / 10007 用户确认超时。
         */
        default void onConnectFailed(int errorCode) { }
        /** 手机导航信息（POC-2 / BYD 仪表与 HUD 的数据源）。 */
        void onNavigation(UCarCommon.NavigationInfo info);

        /**
         * 广播准入检查 —— <b>每一次</b>拉起 BLE 广播之前都会调用。
         *
         * <p>「车载热点」模式下手机必须加入车机自己的热点才能投屏，热点没开时广播出去只会换来
         * 10003/10008 这种静默失败；把判定放在这里而不是 UI 层，是因为广播不止由界面触发：
         * 首次起链、手动连接、手动刷新配对码、30 秒空转重播、兜底广播都要过同一道闸。
         *
         * @return false = 本次不广播（调用方不报错、不改会话状态，等下一次广播机会）
         */
        default boolean canAdvertise() { return true; }

        /**
         * 兜底广播的准入检查 —— 判定口径与 {@link #canAdvertise()} 完全一致，但<b>不把界面
         * 推进到「广播中」</b>（被拦时仍允许退回等待态，不弹引导窗）。
         *
         * <p>为什么要分开：兜底广播只是并行补一条传统 31 字节广播帧，SDK 的 GATT 服务与连接
         * 状态机仍由 {@code doAdvertise()} / {@code startAdvertise()} 负责。让一条兜底广播去
         * 把界面阶段从「热点已就绪·点连接继续」写成「广播中」，等于告诉用户「已经在连了」，
         * 同时把首页唯一的「连接」按钮收起来 —— 2026-11-06 18:43 的六轮连接失败就是这么
         * 卡死的。默认实现回落到 {@link #canAdvertise()}，保持旧 Listener 的行为不变。
         */
        default boolean canAdvertiseForFallback() { return canAdvertise(); }
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 低端车机兜底：SDK 用扩展广播 API（startAdvertisingSet）发广播，部分老车机蓝牙芯片
     * 不支持该路径 —— 调用成功但射频无输出，手机永远扫不到。本广播器用传统 31 字节广播
     * 复刻同一份广播帧并行发出，并带来真实的失败错误码（诊断用）。
     */
    private final FallbackBleAdvertiser fallback;

    private UCarAdapter adapter = null;
    private Surface surface = null;

    /**
     * 投屏状态与 Surface 尺寸基准的唯一持有者（原 {@code castStarted}/{@code displayW}/{@code displayH}）。
     * 抽成独立的纯状态机是为了能单测「Surface 销毁后切回必须重绑」这条不变式 —— 见 {@link CastGate}。
     */
    private final CastGate castGate = new CastGate();

    /** 车机 ID（6 字节）：SDK 用它的十六进制串做广播字段，兜底广播要与之一致。 */
    private byte[] carIdBytes = null;

    /** {@link #tryStartCast()} 的重试预算（×500ms = 最长 10 秒）。 */
    private static final int CAST_RETRY_MAX = 20;
    private int castRetry = 0;

    // ---- 状态机 ----

    /**
     * SDK 的 {@code onConnectStateChanged} 真实语义：第二个参数是 {@code UCarConnectState.UCAR_DEVICE_*}
     * 状态码，第三个参数是附加信息（失败时是错误码）。
     */
    private static final int ST_DISCONNECTED = 1;
    private static final int ST_CONNECTED = 2;
    private static final int ST_CONNECT_FAILED = 3;
    private static final int ST_CAST_CONNECTED = 4;
    private static final int ST_CAST_DISCONNECTED = 5;

    /** 兜底广播延后启动的时长：等 SDK 把本轮广播序列号写进 serial_num_prefs。 */
    private static final long FALLBACK_START_DELAY_MS = 800L;

    /**
     * 「SDK 迟迟没走到广播」的自救时限。
     *
     * <p>正常路径下兜底广播由 {@link #doAdvertise()} 顺带拉起，而 {@code doAdvertise()} 只在
     * {@code onInitSuccess} 之后才跑。可老车机上 SDK 初始化本身就可能出不来（原生库加载失败、
     * SecurityException、SDK 内部线程起不来…），一旦如此原实现**两条广播都不会发** —— 车机上
     * 表现为「App 能装能开，但手机永远发现不了车机」，且日志里除了 init 失败什么都没有。
     * 所以这里再加一道：启动后 5 秒仍未走到 {@code startAdvertise()}，就独立把兜底广播拉起来。
     *
     * <p>此时 SDK 没广播过，不存在「两条广播序列号不一致」的问题，镜像到的旧序列号反而是
     * 手机端记得的那一个会话号，安全。
     *
     * <p><b>2026-11-06 修订</b>：计时器到点不等于「SDK 故障」—— 启动瞬间热点没开、
     * {@code doAdvertise()} 被门禁主动拦下时，SDK 恰恰是好的。是否真要救由
     * {@link FallbackRescuePolicy} 判定（口径与单测都在那个纯策略类里），别在这里再加条件。
     */
    private static final long EARLY_FALLBACK_MS = 5_000L;

    /** 协商中断自救时限（见 {@link #rescueRunnable}）。 */
    private static final long NEGOTIATION_RESCUE_MS = 90_000L;

    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;

    /**
     * 广播是否**已被授权**（用户点过连接 / 启动即建链，且确实发出去过）。
     *
     * <p>看门狗（30 秒空转重播）只有在授权后才运行：热点模式下车机停在「热点已就绪、等用户点
     * 连接」时，任何自动重播都会把 SDK 推去接管热点（不可复用时 `stopTethering` 把用户刚开的
     * 热点关掉）。这个标志就是「用户没要求，就别自作主张广播」的唯一判据。
     */
    private volatile boolean advertisingArmed = false;
    /**
     * 「方向已改、SD 协商画布还挂着旧值」的挂起标记。
     *
     * <p>用户在投屏中把方向设置改了但选了「保持当前连接」时置位：当前会话继续放，
     * 但下一次 {@link #resumeLink()}（主页「连接」）或 {@link #stop()}（手动断开 /
     * 异常断连停机）必须顺手把 SDK 拆掉，否则重连走 reAdvertise 分支、旧画布原样带到
     * 下一场会话。
     */
    private volatile boolean reinitPending = false;
    private volatile String lastPinCode = null;

    /** SDK 是否已经真正调用过 {@code startAdvertise()}（用于 {@link #EARLY_FALLBACK_MS} 自救判定）。 */
    private volatile boolean advertiseAttempted = false;

    /** 手机是否已进入协商（收到过任意连接进度）。看门狗据此避让，绝不在握手中途重播。 */
    private volatile boolean negotiating = false;

    /**
     * 最近一次<b>非零</b>连接阶段（SDK 的 phase，取值 10/20/.../100）与对端设备 ID —— 失败时用来
     * 指明「死在哪一步」。
     *
     * <p><b>为什么必须是「非零」</b>：2026-10-23 的车上报表里有一行自相矛盾的诊断
     * 「连接失败：错误码 10008 … 中止于阶段 尚未开始（手机还没发来任何进度）」，而时间线里
     * phase 10/20/30/40/50 明明都到过。原因就是这里无条件 {@code lastPhase = progress}：
     * SDK 在协商被中止/重来时补发一个 {@code phase=0}，把真实阶段冲成了 0，于是失败那一刻
     * 打印出来的是「尚未开始」。**这条日志骗了整整一轮排查**，所以 phase=0 只记为「复位事件」，
     * 绝不覆盖 lastPhase。
     */
    private volatile int lastPhase = 0;

    /** {@link #lastPhase} 的取得时刻（uptimeMillis），用来在断连现场说清「多久之前」。 */
    private volatile long lastPhaseAt = 0L;

    /**
     * 本进程最近若干个 phase 的时间线（{@code [phase, uptimeMillis]}）。
     *
     * <p>失败/断连时一次性打出来，才能看出「是笔直走完的还是中途回退过」——
     * 回退（例如 50 → 0 → 10 → 30）本身就是手机侧中途撒手的强信号。
     */
    private final ArrayDeque<int[]> phaseHistory = new ArrayDeque<>();

    private static final int PHASE_HISTORY_MAX = 12;

    private volatile String lastDeviceId = null;

    /** 本次会话建立的时刻（uptimeMillis）；0 = 当前没有会话。 */
    private volatile long sessionStartedAt = 0L;

    /** 本进程累计的会话建立次数与断开次数（含人为断开），用于报表里直接看频率。 */
    private volatile int sessionCount = 0;
    private volatile int disconnectCount = 0;
    private volatile long lastDisconnectAt = 0L;

    /**
     * 会话心跳周期。
     *
     * <p>存在的理由：这两份报表里，「断连前 44 秒什么都没发生」和「断连前音频正常」在日志上
     * 长得一模一样 —— 因为没有周期性采样，**无法区分「链路静默了」与「我们没记录」**。
     * 5 秒一行、单行汇总，代价是 12 行/分钟，换来的是「断连前最后一次心跳长什么样」这个判据。
     */
    private static final long HEARTBEAT_INTERVAL_MS = 5_000L;

    /** 心跳里每几拍去异步读一次 P2P 组信息（读一次要过一次 binder，不必 5 秒一问）。 */
    private static final int P2P_PROBE_EVERY_TICKS = 2;

    private int heartbeatTicks = 0;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sessionActive) return;
            heartbeatTicks++;
            if (heartbeatTicks % P2P_PROBE_EVERY_TICKS == 0) probeP2p(null);
            try {
                log(heartbeatLine());
            } catch (Throwable t) {
                log("! 心跳输出异常（继续）: " + t);
            }
            if (!sessionActive) return;
            main.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private void startHeartbeat() {
        main.removeCallbacks(heartbeatRunnable);
        heartbeatTicks = 0;
        main.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void stopHeartbeat() {
        main.removeCallbacks(heartbeatRunnable);
    }

    /** 异步读一次 P2P 组信息；{@code tag} 非空时把结果单独打一行（断连现场用）。 */
    private void probeP2p(final String tag) {
        try {
            P2pLinkProbe.snapshot(appCtx, text -> {
                if (tag != null) log("  P2P（" + tag + "）：" + text);
                else p2pSnapshot = text;
            });
        } catch (Throwable t) {
            if (tag != null) log("  P2P（" + tag + "）：读取异常 " + t);
        }
    }

    /** 心跳里引用的最近一次 P2P 快照（异步回调写入）。 */
    private volatile String p2pSnapshot = "未采样";

    /** 会话结束后重新广播的延时：留出 SDK 内部收尾时间，避免与断连流程竞争。 */
    private static final long RE_ADVERTISE_DELAY_MS = 1200L;

    /**
     * 「仅 5G」的本地快照（实际生效值，开关源见 {@link #refreshFiveGhzOnly()}）。
     *
     * <p>这条路的 P2P 组由小米 UCar SDK 建（内部 "set channel to 0" 自动选），MiAuto 不能直接
     * 指定频段。开关打开后只做两件尽力而为的事：建组前用隐藏 setWifiP2pChannels 把整机 P2P 工作
     * 信道限制到 5G；组建起后读 p2p0 实际频率，落在 2.4G 就停机并提示。默认关。
     *
     * <p>只在两个时刻从 SharedPreferences 重读（广播前、连接建立时），不在每次判断时读盘，
     * 因为 {@link #verifyFiveGhz()} 可能在状态回调里被连着触发两次。
     */
    private volatile boolean fiveGhzOnly = false;

    /**
     * 配对码自动刷新周期。
     *
     * <p>车机一直在广播并不足以把手机叫回来：BLE 广播帧内容不变时，手机端 CarWith 不会重新
     * 弹出配对界面。用户一旦关掉那个弹窗，之后就必须手动去 CarWith 里找车机才能重连。
     * 荣耀车联 / HiCar 的车机端做法是周期性重播，本常量即那个周期。
     */
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L;

    /** 「跳过本轮」的累计轮次，只为降噪（常态轮次每 20 轮 = 10 分钟才落一行）。 */
    private volatile int autoRefreshSkipCount = 0;

    /** 配对码未变化时的例报间隔：空转重播每 30 秒一次，同码只 5 分钟记一回。 */
    private static final long PINCODE_LOG_THROTTLE_MS = 300_000L;

    /** 配对码上次落库时刻（空转期节流；会话期内不节流）。 */
    private volatile long lastPinCodeLogAtMs = 0L;

    /** 空转重播累计轮次（真正执行重播的那轮 ++；每 20 轮 = 10 分钟聚合一条）。 */
    private volatile int idleReadvertiseCount = 0;

    /** startAdvertise 调用计数：空转期第 2 次起不再逐条记录。 */
    private volatile int startAdvertiseCount = 0;

    /** 会话是否正在进行（手机连接中或已连接）。由连接回调维护，看门狗据此避让。 */
    private volatile boolean sessionActive = false;

    /**
     * 「等用户点连接」的停机状态。
     *
     * <p>用户手动断开（或手机端主动断开）后置位：此时**不再广播、不再自动重连**，车机安静下来，
     * 直到 UI 上按「连接」（{@link #resumeLink()}）才恢复。理由见 {@link LinkStatePolicy}。
     *
     * <p>它与 {@link #sdkReady} 的区别：{@code sdkReady = false} 只出现在 {@link #stop()}（彻底停机），
     * 而这里 SDK 仍然初始化好、只是不广播 —— 所以「连接」能立刻恢复，不必重启进程。
     */
    private volatile boolean paused = false;

    /**
     * 本会话内<b>连续</b>「无线建链类失败」（10003/10006/10008）的次数，用于 {@link ReconnectBackoff}
     * 的指数退避档位。连接成功、或走通一次重播即复位。
     */
    private int wirelessFailStreak = 0;

    /** 手机当前是否在放歌（ICCOA 的 MusicInfo）。只用于把 PLAY_PAUSE 翻成 PLAY 或 PAUSE。 */
    private volatile boolean musicPlaying = false;

    /**
     * 本颗「播放/暂停族」物理键在 DOWN 时选定的 ICCOA 键值，供对应的 UP 复用。
     *
     * <p>{@link MediaKeyMap#NONE} = 当前没有按下中的此类键。没有这个缓存时，UP 会用被 DOWN
     * 翻转过的 {@link #musicPlaying} 重新翻译，得到与 DOWN 相反的键 —— 一次按键下发
     * {@code DOWN(PLAY)} + {@code UP(PAUSE)}，手机端两个都执行时观感仍是「按键没反应」。
     */
    private volatile int heldPlayPauseCode = MediaKeyMap.NONE;

    /**
     * 会话期间的链路保活锁（Wi-Fi 高性能 + CPU 部分唤醒）。
     *
     * <p>行为与「为什么必须要」全部在 {@link SessionKeepAlive}；这里只保留四个调用点：
     * 连上取、失败 / 断开 / 停机放。
     */
    private final SessionKeepAlive keepAlive;
    /** AP 复用注入维护层（方案 G4）：建链前注入 + 热点状态跟随 + 路径二补偿。 */
    private final ApReuseMaintenance apReuse;

    /**
     * 昼夜主题诊断采样器（只读为主；只有 adb 显式设了强制值才会向手机发包）。
     *
     * <p>生命周期与本字段所依赖的「会话」完全一致，因此调用点刻意与 {@link #keepAlive} 逐一对齐：
     * 建立起、失败 / 断开 / 停机 / 彻底停止四处停。判据与文案全在 {@link DayNightProbePolicy}。
     */
    private final DayNightProbe dayNight;

    /**
     * 定位采样是否已拉起（日出/日落要用坐标算切换时刻）。
     *
     * <p>为什么必须由本类负责起停：{@code GpsLocationService} 原先只在 {@code AapService.onConnected()}
     * 里启动，而本工程 {@code ICCOA_ONLY} 跑不到 AA，等于<b>从来没有定位来源</b> ——
     * {@code LocationHolder} 永远空，日出/日落被永久判成"没有坐标"（详见 SunriseLocationPolicy）。
     * 现在跟随链路起停，量很小（只在应用运行期间），断开即停。
     */
    private boolean locationFeedRunning = false;

    /** 按键回传被拒的告警只打一次，避免方向盘长按时刷屏。 */
    private boolean keyWarned = false;

    /**
     * 投屏页是否已经「提前暂停」过视频（见 {@link #pauseCastWhileHidden()}）。
     *
     * <p>为 true 时 {@link #onSurfaceDestroyed()} 里的兜底 {@code pauseCast(false)} 会跳过 ——
     * Surface 已经没了再去换输出面才有失败风险，能不做就不做。
     */
    private volatile boolean videoPaused = false;

    /**
     * 投屏「最后一帧」缓存。
     *
     * <p>存在的理由只有一个：{@code SurfaceView} 的 Surface 被回收后内容是<b>空白</b>的，而重挂
     * 输出面之后解码器必须等手机侧推来下一个可解码帧才出画，这段空窗在车机上就是纯黑。
     * 缓存留一张离开时的画面，回来先拿它占位。
     *
     * <p>生命周期与作废时机全部在 {@link LastFrameCache}。这里只保留四个调用点：
     * 离开时抓（{@link #captureLastFrame}）、回来时取（{@link #lastFrame}）、
     * 尺寸/设备变化时作废（{@link #setSurface} / {@code onConnectStateChanged}）、
     * 会话结束时释放（{@link #stop()} / {@link #pauseLink(String)} / 断连与失败分支）。
     *
     * <p><b>为什么不放在 Activity 里</b>：缓存必须活过 Activity 的 {@code onPause→onStop}，
     * 而这一页的 {@code onDestroy} 也可能先于「回来」发生（会话结束时本页会被 finish）。
     * 挂在 SDK 生命周期上才有意义。
     */
    private final LastFrameCache lastFrame = new LastFrameCache(this::log);

    // ==================================================================
    // 首帧回调：只作诊断（为什么不能当判据，见下面 surfaceStateListener 的注释）
    // ==================================================================

    /**
     * 本次绑定后是否已经收到过 SDK 的「首帧已渲染」回调，只用于日志。
     *
     * <p><b>不能拿它当「画面回来了」的判据</b>：这个回调由 {@code VideoPlayerV2} 的
     * {@code mIsSurfaceAlReady} 触发，条件是「某个输出帧的 pts 超过重挂输出面时记录的阈值」，
     * 而暂停期间积压的旧帧 pts 都很小 —— 画面已经出来了，回调仍可能迟到大几十秒。
     * 基于它做「几秒没出画就催一次」的看门狗只会在画面正常时反复重挂输出面，反而把画面拖黑。
     */
    private volatile boolean frameRendered = false;

    /** SDK 的「首帧已渲染」回调，仅用于诊断。 */
    private final IAppSurfaceStateListener surfaceStateListener = new IAppSurfaceStateListener() {
        @Override
        public void onSurfaceReady() {
            if (!frameRendered) {
                frameRendered = true;
                main.post(() -> log("✓ SDK 报告首帧已渲染（仅作诊断，不作为出画判据）"));
            }
        }
    };


    // ==================================================================
    // 音频焦点（「连上了、手机在放歌、车机没声音」的主因，见 AudioFocusPolicy）
    // ==================================================================

    /**
     * 音乐流（{@code STREAM_CAST_MUSIC}）<b>自己</b>最近一次焦点回调。
     *
     * <p>必须按类型分开记：SDK 的焦点状态键是 {@code focusGain_usage_contentType}，通知/播报
     * 与音乐共用默认属性时会共用一个状态，于是「通知放完」会推给我们一个 {@code -5}，
     * 而音乐其实好好的；反过来音乐被别的东西顶掉时，最后一个事件也可能来自别的类型。
     * 拿全局最后一个事件做判断，这两种情况都会误判。
     */
    private volatile int musicFocus = AudioFocusPolicy.NO_EVENT;
    private volatile long musicFocusAt = 0L;
    /** 本轮已经补过几次「允许抢焦点」。 */
    private volatile int audioFocusStrikes = 0;
    private volatile long lastAudioFocusAttemptAt = 0L;

    /** 音频看门狗的轮询周期。比 {@link AudioFocusPolicy#RETRY_INTERVAL_MS} 小，保证到点就能补。 */
    private static final long AUDIO_FOCUS_POLL_MS = 2_000L;

    /**
     * 非音乐流预缓冲水位的写入通道。
     *
     * <p>{@code UCarAdapter.handleOnAudioPlayerControl(AudioPlayerControl)} 在真实 dex 里是
     * <b>private</b>，而 {@code stubs/icar-sdk-stubs.jar} 也没有声明它；反过来 {@code AudioPlayerControl}
     * 这个类本身在桩里是齐的（公开无参构造 + 三个 setter）。所以对象构造保持类型安全，只有这一次调用
     * 走反射 + {@code setAccessible}。不补桩是因为给 {@code UCarAdapter} 重新生成桩等于手工复刻它全部
     * 成员，漏一个签名的坏法和其余 500 个类一样安静（编译期看不出、运行时才炸）。
     *
     * <p>探测只做一次并缓存：拿不到方法就永久放弃（{@code null} + {@link #bufferingEntryProbed}
     * 标记），行为退回今天的 100ms 下限，不影响任何其它音频逻辑。
     */
    private java.lang.reflect.Method bufferingEntry;
    private boolean bufferingEntryProbed;
    /**
     * 已就哪个音源打过水位日志（存 {@code UCarCommon.AudioType} 取值）。
     *
     * <p>台账<b>按音源</b>记而不是一个总开关：一个 boolean 会让第一个命中的音源（实际是
     * STREAM_NOTIFICATION）把日志吃光，导航明明也被抬了却在报告里查不到 —— 在车上就会误判成
     * "导航没生效"。判据与状态转移都在 NonMusicBufferingPolicy（可单测）。
     */
    private int[] bufferingFloorAnnounced = new int[0];

    /**
     * 把导航播报 / 通知音的预缓冲水位从 SDK 下限 100ms 抬到 200ms。取 200、为什么只动这两个音源、
     * 为什么能在播放器未建时推，全部记在 {@link NonMusicBufferingPolicy}。
     *
     * @param type 目标音源；不在表里的（音乐、通话、未覆盖音源）直接返回，绝不推
     */
    private void applyBufferingFloor(UCarCommon.AudioType type) {
        UCarAdapter a = adapter;
        if (a == null || type == null) return;
        final int ms = NonMusicBufferingPolicy.bufferingMsFor(type.getValue());
        if (ms <= 0) return;
        try {
            if (!bufferingEntryProbed) {
                bufferingEntryProbed = true;
                try {
                    // 必须是 getDeclaredMethod：这个方法在真实 dex 里是 PRIVATE
                    // （`access : 0x0002`，与同为 SDK 入口的 allowGainAudioFocus 的 `0x0001 PUBLIC` 相对）。
                    // jadx 把它渲染成了 public，只核"名字+签名存在"是查不出来的 —— 第一次上车就是这样
                    // 白跑了一趟。getMethod 只找 public，会静默抛 NoSuchMethodException。
                    // 它 private 意味着这是 SDK 内部接收手机消息的入口，我们从外面喂它是越界用法；
                    // 但这是应用侧唯一能设水位的门，且失败会自动退回今天的行为，故可接受。
                    java.lang.reflect.Method m = UCarAdapter.class.getDeclaredMethod(
                            "handleOnAudioPlayerControl", UCarAudioManager.AudioPlayerControl.class);
                    m.setAccessible(true);   // 本进程内自己的 dex，不受框架隐藏 API 名单约束
                    bufferingEntry = m;
                } catch (NoSuchMethodException e) {
                    bufferingEntry = null;
                    log("! SDK 未提供 handleOnAudioPlayerControl → 导航/通知水位保持默认 100ms（"
                            + e.getMessage() + "）");
                }
            }
            java.lang.reflect.Method m = bufferingEntry;
            if (m == null) return;
            UCarAudioManager.AudioPlayerControl c = new UCarAudioManager.AudioPlayerControl();
            c.setAudioType(type);
            c.setBufferingCount(ms);
            c.setSpeedAdjustStep(NonMusicBufferingPolicy.speedAdjustStepMs());
            m.invoke(a, c);
            // 每个被抬的音源各打一行，之后同一音源不再刷；INFO 级是硬要求 —— DiAuto 报告只收
            // INFO 以上，否则改了什么在车上根本看不出来。
            if (NonMusicBufferingPolicy.shouldAnnounce(bufferingFloorAnnounced, type.getValue())) {
                bufferingFloorAnnounced =
                        NonMusicBufferingPolicy.noteAnnounced(bufferingFloorAnnounced, type.getValue());
                log(NonMusicBufferingPolicy.announceLine(type.getValue(), String.valueOf(type)));
            }
        } catch (Throwable t) {
            log("! 写入音频水位失败（保持默认，不影响播放）: " + t);
        }
    }

    /** 会话建立时用：把该抬的音源先推一遍，值会存进 mPlayersParams，等建播放器时立刻生效。 */
    private void primeBufferingFloors() {
        UCarAdapter a = adapter;
        if (a == null) return;
        bufferingFloorAnnounced = new int[0];   // 新会话：每个音源都要重新报一次，便于上车逐条核对
        for (int typeValue : NonMusicBufferingPolicy.typesToPrime()) {
            UCarCommon.AudioType t = UCarCommon.AudioType.fromInt(typeValue);
            if (t != null) applyBufferingFloor(t);
        }
    }

    /**
     * 音频看门狗：会话期间每 2 秒看一次「手机在放歌但焦点是丢的」并补一次
     * {@code allowGainAudioFocus()}。
     *
     * <p>为什么需要定时轮询而不是只在焦点回调里处理：焦点<b>丢</b>的那一刻手机很可能还没开始放歌
     * （用户是先连接、再打开音乐），回调那次就被 {@code musicPlaying=false} 挡掉了，之后不会再有
     * 新的焦点回调 —— 不轮询就永远等不到「该补」的那个时机。
     */
    private final Runnable audioFocusGuardRunnable = () -> {
        if (!sessionActive) return;
        maybeRearmAudioFocus();
        // 会话还在就继续轮询（方法内部再取字段，避免 lambda 初始化时自引用）
        if (!sessionActive) return;
        if (audioFocusStrikes >= AudioFocusPolicy.MAX_STRIKES) {
            // 额度用尽：对方就是不放焦点（通话中、车机策略），继续每 2 秒空转没有意义。
            // 手机重新开始放歌时会重新 arm（见 onMusicInfoReceived），所以不需要常驻轮询。
            log("音频看门狗：已用尽 " + AudioFocusPolicy.MAX_STRIKES
                    + " 次补发额度 → 暂停轮询，等手机重新开始放歌再恢复");
            return;
        }
        armAudioFocusGuard();
    };

    private void armAudioFocusGuard() {
        main.removeCallbacks(audioFocusGuardRunnable);
        main.postDelayed(audioFocusGuardRunnable, AUDIO_FOCUS_POLL_MS);
    }

    private void cancelAudioFocusGuard() {
        main.removeCallbacks(audioFocusGuardRunnable);
    }

    /**
     * 判断并补发一次 {@code allowGainAudioFocus()}。
     *
     * <p>这个调用把 SDK 内部那个一次性闩（{@code mEnabledAudioFocusGain}）重新打开，
     * 于是下一帧音频到达时 SDK 会自己重新 {@code requestAudioFocus()} —— 声音就回来了。
     * 判定与限额在 {@link AudioFocusPolicy}（可单测）。
     */
    private void maybeRearmAudioFocus() {
        UCarAdapter a = adapter;
        if (a == null) return;
        long now = SystemClock.uptimeMillis();
        if (!AudioFocusPolicy.shouldRearm(sessionActive, musicPlaying, musicFocus,
                musicFocusAt == 0L ? Long.MAX_VALUE / 2 : now - musicFocusAt,
                audioFocusStrikes, now - lastAudioFocusAttemptAt)) {
            return;
        }
        audioFocusStrikes++;
        lastAudioFocusAttemptAt = now;
        try {
            a.allowGainAudioFocus();
            log("★ 音频焦点未恢复（musicFocus=" + audioFocusName(musicFocus)
                    + "，手机仍在放歌）→ 第 " + audioFocusStrikes + "/" + AudioFocusPolicy.MAX_STRIKES
                    + " 次补发「允许抢焦点」；SDK 会在下一帧音频重新申请");
        } catch (Throwable t) {
            log("✗ allowGainAudioFocus 异常: " + t);
        }
    }

    /**
     * 会话建立时把音频这条链路的初始状态打出来。
     *
     * <p>「连上了没声音」的现场证据就在这几行：音源是不是被静音、媒体音量是不是 0、
     * {@code AudioManager} 的模式是不是还停在通话模式（那会把媒体声甩到听筒/通话通路）。
     * 打印本身不改任何状态，出问题时一眼能定位到是链路还是车机音频策略。
     */
    /**
     * 导航播报播放侧 usage 的运行时覆盖，供上车扫「车机把哪个 usage 认成它那一路导航通道」。
     *
     * <p>用 {@code Settings.Global} 而不是隐藏 API {@code SystemProperties} 或 SharedPreferences：
     * 它公开、adb 一行可改、release 签名包也不需要 root（{@code SharedPreferences} 那条在 release 包上
     * adb 根本写不进去，{@code run-as} 只对 debuggable 有效）。读是免费权限。
     *
     * <p>车机上：{@code adb shell settings put global miauto_nav_usage 12}（13 / 16 / 22 / 1 依次试），
     * 每改一次重新触发一次导航播报即可，不用重编包。返回 0 = 未设置/非法，策略表用默认值。
     */
    private static final String NAV_USAGE_SETTING = "miauto_nav_usage";

    private int guidanceUsageOverride() {
        try {
            String raw = android.provider.Settings.Global.getString(
                    appCtx.getContentResolver(), NAV_USAGE_SETTING);
            if (raw == null || raw.trim().isEmpty()) return 0;
            int requested = Integer.parseInt(raw.trim());
            // 传 0 当 fallback：返回 0 就说明这个值被策略挡掉了（会命中通话态或语义冲突）。
            int accepted = AudioFocusAttributionPolicy.acceptedGuidanceUsage(requested, 0);
            if (accepted == 0) {
                log("! 导航 usage 覆盖值 " + requested + " 被拒绝（会命中 SDK 通话态 / 与哨兵值冲突）→ 用默认");
                return 0;
            }
            if (accepted != requested) {
                log("导航 usage 覆盖值 " + requested + " 未识别 → 用默认");
                return 0;
            }
            return accepted;
        } catch (Throwable t) {
            return 0;   // 读不到就当没设，走默认
        }
    }

    private void logAudioDiagnostics() {
        try {
            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            boolean muted = android.os.Build.VERSION.SDK_INT >= 23
                    && am.isStreamMute(AudioManager.STREAM_MUSIC);
            log("音频环境：mode=" + audioModeName(am.getMode())
                    + " 媒体音量=" + vol + "/" + max + (muted ? "（已静音）" : "")
                    + " STREAM_MUSIC 是否在响=" + am.isMusicActive());
            if (muted || vol == 0) {
                log("! 车机把媒体音轨静音/音量 0 —— 这种情况下 SDK 播放正常但听不到声音，"
                        + "请先把媒体音量调起来再判断");
            }
            logAudioTopology(am);
        } catch (Throwable t) {
            log("音频环境读取失败: " + t);
        }
    }

    /**
     * 车机音频拓扑快照。
     *
     * <p>存在的理由很实际：「导航音该用哪个 usage 才会被送到车机留给导航的那一路功放通道」这件事，
     * 映射表整个在应用层之下（audio policy / HAL / ACDB），而 {@code AudioDeviceInfo.getMaxChannels()}
     * 是 API 30、车机是 API 29，<b>应用在车上根本查不到</b>。DiAuto 报告又只收应用日志，拿不到系统音频
     * tag。于是把还能查的东西在会话建立时打全，让下一次上车那份报告至少能说清：车暴露了哪些输出、
     * 有没有 TYPE_BUS 这类独立总线、哪些音量组真的存在。没有这些数据，通道映射适配只能是猜。
     *
     * <p>每个调用都单独 try/catch 并带 SDK_INT 判断：这里只列了 API 23/24 就稳定的方法，但车机 ROM 是
     * 定制过的，不能让一句诊断把会话建立带崩。
     */
    private void logAudioTopology(AudioManager am) {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            log("音频拓扑：跳过（getDevices 需 API 23，当前 " + android.os.Build.VERSION.SDK_INT + "）");
            return;
        }
        try {
            StringBuilder devices = new StringBuilder();
            int bus = 0;
            for (android.media.AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d == null || !d.isSink()) continue;
                if (d.getType() == AudioFocusAttributionPolicy.DEVICE_TYPE_BUS) bus++;
                devices.append(' ').append(deviceTypeName(d.getType()))
                        .append("(addr=").append(d.getAddress().isEmpty() ? "-" : d.getAddress()).append(')');
            }
            log("音频拓扑·输出设备:" + (devices.length() == 0 ? " 无" : devices.toString())
                    + "；TYPE_BUS 总线数=" + bus
                    + (bus > 0 ? " ← 车机确实暴露了可独立映射的音频总线" : ""));
        } catch (Throwable t) {
            log("音频拓扑·输出设备读取失败: " + t);
        }
        try {
            StringBuilder groups = new StringBuilder();
            // 逐个音量组问一遍：车机往往有定制组（导航、提示、语音），打印 max>0 的那些，
            // 就能看出它把「压低」做在哪个组上 —— 这直接决定 usage 该报成什么。
            // 只标确定的名字，其余按编号原样输出，不在这里靠记忆猜。
            int[] candidates = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14};
            for (int s : candidates) {
                try {
                    int m = am.getStreamMaxVolume(s);
                    if (m <= 0) continue;
                    groups.append(' ').append(streamName(s)).append('=').append(am.getStreamVolume(s))
                            .append('/').append(m);
                } catch (Throwable ignored) {
                    // 这台车没有这个组，跳过
                }
            }
            log("音频拓扑·音量组：" + (groups.length() == 0 ? " 仅默认" : groups.toString())
                    + "；speakerphoneOn=" + am.isSpeakerphoneOn());
        } catch (Throwable t) {
            log("音频拓扑·音量组读取失败: " + t);
        }
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case 1: return "earpiece";
            case 2: return "speaker";
            case 3: return "wired_headset";
            case 4: return "wired_headphones";
            case 5: return "line_analog";
            case 6: return "line_digital";
            case 7: return "bt_sco";
            case 8: return "bt_a2dp";
            case 9: return "hdmi";
            case 10: return "hdmi_arc";
            case 11: return "usb_device";
            case 12: return "usb_accessory";
            case 13: return "dock";
            case 14: return "fm";
            case 15: return "builtin_mic";
            case 19: return "aux_line";
            case 21: return "BUS";
            case 22: return "usb_headset";
            case 23: return "hearing_aid";
            case 24: return "speaker_safe";
            case 25: return "remote_submix";
            case 26: return "ble_headset";
            case 27: return "ble_speaker";
            case 29: return "hdmi_earc";
            default: return "type" + type;
        }
    }

    private static String streamName(int id) {
        switch (id) {
            case 0: return "call";
            case 1: return "system";
            case 2: return "ring";
            case 3: return "media";
            case 4: return "alarm";
            case 5: return "notif";
            case 6: return "bt_sco";
            case 7: return "system_ed";
            case 8: return "dtmf";
            case 10: return "accessib";
            default: return "stream" + id;
        }
    }

    private static String audioFocusName(int focus) {
        switch (focus) {
            case 1: return "GAIN";
            case 0: return "PRE_REQUEST";
            case -1: return "LOSS";
            case -2: return "LOSS_TRANSIENT";
            case -3: return "LOSS_TRANSIENT_CAN_DUCK";
            case -4: return "REQUEST_FAILED";
            case -5: return "ABANDONED";
            default: return AudioFocusPolicy.NO_EVENT == focus ? "无事件" : ("未知(" + focus + ")");
        }
    }

    private static String audioModeName(int mode) {
        switch (mode) {
            case AudioManager.MODE_NORMAL: return "MODE_NORMAL";
            case AudioManager.MODE_RINGTONE: return "MODE_RINGTONE";
            case AudioManager.MODE_IN_CALL: return "MODE_IN_CALL";
            case AudioManager.MODE_IN_COMMUNICATION: return "MODE_IN_COMMUNICATION";
            default: return "MODE_" + mode;
        }
    }

    /**
     * 会话结束后的重新广播。
     *
     * <p>车机端必须一直可被发现：手机断开后若不再广播，用户就得手动重启 App 才能重连。
     * 重新广播时会清掉旧配对码，手机因此走一次全新配对并下发【新的】配对码 —— 这就是
     * 「断开后自动刷新链接码」。
     */
    private final Runnable reAdvertiseRunnable = () -> {
        if (sdkReady && adapter != null) {
            log("会话结束 → 重新广播（等待手机再次连接，届时下发新的配对码）");
            reAdvertise();
        }
    };

    private void scheduleReAdvertise() {
        scheduleReAdvertiseAfter(RE_ADVERTISE_DELAY_MS);
    }

    /** 可指定延时的重播排程；{@link ReconnectBackoff} 用它对无线建链失败做指数退避。 */
    private void scheduleReAdvertiseAfter(long delayMs) {
        main.removeCallbacks(reAdvertiseRunnable);
        main.postDelayed(reAdvertiseRunnable, delayMs);
    }

    /**
     * defer state=5 的兜底：万一配对的 {@code DISCONNECTED(1)} 回调没来（不同手机/固件时序差异），
     * 3 秒后按「异常断连」补一次重播，绝不把会话永久留在悬空状态。真 state=1 到达时会取消它。
     */
    private static final long CAST_STOP_RECONSIDER_MS = 3_000L;
    private final Runnable castStopReconsiderRunnable = () -> {
        if (paused || sessionActive || castGate.isStarted()) return;
        log("★ 投屏停止(5)后 " + (CAST_STOP_RECONSIDER_MS / 1000)
                + " 秒仍无 DISCONNECTED(1) → 按异常断连补一次重播");
        scheduleReAdvertise();
        scheduleAutoRefresh();
    };

    /**
     * 配对码看门狗：每隔 {@link #AUTO_REFRESH_INTERVAL_MS} 检查一次，仍未连上就重播一次。
     *
     * <p>这是「刷新出来的新码没人来连，之后就再也不刷新」的解法 —— 那种情况下手机端弹窗
     * 已经关掉，车机只能靠重播把它重新叫出来，否则用户点开 CarWith 也发现不了车机。
     *
     * <p>避让规则：手机正在连接或已连上（{@link #sessionActive}）、或画面已经投出去
     * （{@link CastGate#isStarted()}）时一律跳过，绝不打断进行中的会话。新码下发时计时会重置，
     * 保证用户拿到完整的 30 秒去输入。
     */
    private final Runnable autoRefreshRunnable = () -> {
        if (!sdkReady || adapter == null) return;
        if (!advertisingArmed) {
            // 从没广播过（或已被热点闸/停机收回）：热点模式下这代表用户还没点「连接」。
            log("自动刷新：当前没有授权广播（等用户点「连接」）→ 跳过本轮");
            return;
        }
        if (paused) {
            // 停机也是长期状态：和下面「会话进行中」一样降噪，否则 30 秒一行的常态日志
            // 会把断连现场前后的上下文挤出 12000 行环形缓冲（实测报表里 710 行全是这种行）。
            if (++autoRefreshSkipCount == 1 || autoRefreshSkipCount % 20 == 0) {
                log("自动刷新：已停机（等用户点「连接」），跳过本轮（已跳过 " + autoRefreshSkipCount
                        + " 轮；常态轮次每 10 分钟只记一次）");
            }
            return;
        }
        if (sessionActive || castGate.isStarted() || negotiating) {
            if (++autoRefreshSkipCount == 1 || autoRefreshSkipCount % 20 == 0) {
                log("自动刷新：会话进行中，跳过本轮（已跳过 " + autoRefreshSkipCount
                        + " 轮；常态轮次每 10 分钟只记一次）");
            }
            scheduleAutoRefresh();
            return;
        }
        // 真正执行重播的那一轮：这是「手机弹窗被重新叫出来」的痕迹，必须留；但空转期
        // 每 30 秒就一轮，逐轮记会把额度耗光 —— 改为累计计数，每 20 轮（10 分钟）聚合一条，
        // 首轮与「刚连上又掉了」的情形立即记。
        autoRefreshSkipCount = 0;
        idleReadvertiseCount++;
        if (idleReadvertiseCount == 1 || idleReadvertiseCount % 20 == 0 || sessionCount <= 1) {
            log("★ 自动刷新：超过 " + (AUTO_REFRESH_INTERVAL_MS / 1000)
                    + " 秒未连接 → 重播以重新触发手机弹窗（空转重播第 " + idleReadvertiseCount
                    + " 轮，常态轮次每 10 分钟聚合一条）");
        }
        if (!reAdvertise()) {
            log("自动刷新：广播被热点闸拦下 → 停止自动刷新（等用户点「连接」）");
            return;
        }
        scheduleAutoRefresh();
    };

    private void scheduleAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
        main.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    private void cancelAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
    }

    /**
     * 协商自救计时器。
     *
     * <p>手机一旦开始握手（收到任意 {@code onConnectingProgress}），看门狗就不再重播 ——
     * 重播会换掉广播序列号，而序列号在 ICCOA 协议里就是「配对会话号」：变了手机端会当成
     * 另一个会话，正在进行的握手会被打断（这正是「手机端总是提示连接失败」的一个典型成因）。
     *
     * <p>但握手也可能无疾而终（既不成功、也不回调失败），所以留一条 90 秒的自救通道：
     * 确实一直没有结果，就重新广播一次，让用户能重来。
     */
    private final Runnable rescueRunnable = () -> {
        if (paused) {
            log("协商自救：已停机，跳过");
            return;
        }
        if (sessionActive || castGate.isStarted()) {
            log("协商自救：已连上，跳过");
            return;
        }
        log("★ 协商中断自救：握手超过 " + (NEGOTIATION_RESCUE_MS / 1000) + " 秒无结果 → 重播一次");
        negotiating = false;
        reAdvertise();
    };

    private void armRescue() {
        main.removeCallbacks(rescueRunnable);
        main.postDelayed(rescueRunnable, NEGOTIATION_RESCUE_MS);
    }

    private void cancelRescue() {
        main.removeCallbacks(rescueRunnable);
    }

    // ---- 屏幕尺寸 ----
    //
    // 0 = 还没量到（窗口未 attach / Display 不可用）。刻意<b>不给</b>「常见值」兜底：
    // 这两个数会直接进 UCarConfig 报给手机端，手机一旦按 1920x720 协商完编码分辨率，后面
    // 没有任何环节会纠正它 —— 画面会一直按错误的画布拉伸，UIBC 触摸也整体偏移。
    // 宁可等一帧重试（见 MainActivity.startIcocaLink），也不要静默用错的值。
    // 真实分辨率由 DeviceDisplay 在运行时量出，经 setScreenSize() 灌进来。
    private volatile int screenW = 0, screenH = 0, videoW = 0, videoH = 0;
    // 上一次 buildConfig() → initSdk 真正灌进 UCarConfig 的画布上报序（0 = 尚未记下）。
    // 协商画布在 init 那一刻定死、运行期没有任何 API 能改；于是「现在量到的画布」与「当初
    // 灌进去的那份」不一致时，重连必须拆 SDK 重新 init —— 否则 start() 的 reAdvertise 分支
    // 只是拿旧 config 重播（SYSTEM 档转过屏幕再连、却还是老方向，根因就在这里）。
    private volatile int initCanvasW = 0, initCanvasH = 0;

    /** 屏幕尺寸是否已经量到 —— {@link #refreshScreenSizeIfNeeded} 的短路判据。 */
    boolean screenSizeReady() {
        return screenW > 0 && screenH > 0;
    }

    /**
     * 兜底刷新：外部还没灌进有效尺寸时，直接问系统要一次。
     *
     * <p>正常路径是 {@link #setScreenSize}；这条只用于「没人告诉过我」的情形（例如从广播
     * 拉起、Activity 还没走完 onCreate）。仍拿不到就返回 false，由调用方重试。
     *
     * @return 是否拿到了有效尺寸
     */
    public boolean refreshScreenSizeIfNeeded() {
        if (screenSizeReady()) return true;
        remeasureScreenSize();
        return screenSizeReady();
    }

    /**
     * 不管当前有没有值，都重新量一次屏幕并按 {@link ScreenSizePolicy} 归一后灌进来。
     *
     * <p>与 {@link #refreshScreenSizeIfNeeded} 的区别只在那条 {@code screenSizeReady()} 短路：
     * 本方法**总是**重量，给「停机期间用户转过屏幕 / 改过方向设置」这类场景用
     * （见 {@link #resumeLink()}）。量不到（窗口未 attach 等）就原样沿用上一次的有效值，
     * 由调用方按旧画布继续 —— 不猜一个「常见分辨率」顶上。
     *
     * <p>上报序与 {@code MainActivity.startIcocaLink} 的正常路径同口径（竖屏档短边在前、
     * 横屏档长边在前、SYSTEM/AUTO 透传当前窗口）。两条路必须一致，否则会出现「首页按横屏
     * 协商、兜底却按竖屏」这种只在特定时序下复现的错位。
     */
    public void remeasureScreenSize() {
        int[] size = DeviceDisplay.realSizePx(appCtx);
        if (size[0] <= 0 || size[1] <= 0) {
            log("! 屏幕分辨率重量失败（窗口未 attach？）—— 沿用上一次的有效值 "
                    + screenW + "x" + screenH);
            return;
        }
        int orientation = new Settings(appCtx).getScreenOrientation().getValue();
        int[] reported = ScreenSizePolicy.reported(size[0], size[1], orientation);
        if (reported[0] == screenW && reported[1] == screenH) return;   // 没变就别刷、也别刷日志
        log("屏幕分辨率（重量）= " + reported[0] + "x" + reported[1] + "（方向档 " + orientation + "）");
        setScreenSize(reported[0], reported[1]);
    }

    /**
     * 现在量到的画布与 SDK 初始化时灌进 {@code UCarConfig} 的那份是否不一致（= 是否该重谈）。
     *
     * <p>判定本身是纯函数（{@link ScreenSizePolicy#needsRenegotiation}，有单测钉住）；这里只负责
     * 把两个字段递过去。横竖互换、分辨率变化（换屏 / 改显示大小）都算「不一致」。
     */
    private boolean canvasStale() {
        return ScreenSizePolicy.needsRenegotiation(screenW, screenH, initCanvasW, initCanvasH);
    }

    /**
     * 画布与 SDK 初始化时灌进 {@code UCarConfig} 的那份不一致（或用户改过方向但选了
     * 「保持当前连接」、有挂起的重init）时：拆掉 SDK 并完整重init，让这次连接按新画布协商。
     *
     * <p>{@link #start()} 的重播分支与 {@link #resumeLink()}（主页「连接」按钮）共用同一个
     * 判断 —— 两处都面对「SDK 还活着但画布可能已经作废」的局面，各写一遍必然漂移。
     *
     * @param reason 写进日志的触发点
     * @return true = 已经接手重init，调用方不要再走 reAdvertise / 重播分支
     */
    private boolean renegotiateIfStale(String reason) {
        boolean pending = reinitPending;
        if (pending) {
            reinitPending = false;
            log("兑现挂起的方向重init（用户当时选了「保持当前连接」）");
        }
        if (!pending && !canvasStale()) return false;
        log("协商画布需重谈（init 时 " + initCanvasW + "x" + initCanvasH
                + " → 现在 " + screenW + "x" + screenH + "）→ 拆 SDK 重新 init（" + reason + "）");
        deinitAdapter(reason);
        beginFreshLink();
        return true;
    }

    public IccoaManager(Context ctx, Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
        this.keepAlive = new SessionKeepAlive(this.appCtx, this::log);
        this.fallback = new FallbackBleAdvertiser(this.appCtx, this::log);
        this.dayNight = new DayNightProbe(this.appCtx, this::log, this::sendDayNightForProbe);
        // AP 复用维护层（G4）：flag 关闭时不 bind、不注入、几乎无痕 —— 见 ApReuseGuard.isArmed。
        this.apReuse = new ApReuseMaintenance(this.appCtx, this::log);
    }

    public void setScreenSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            // 无效值绝不落库：宁可维持「未量到」状态让上层重试，也不要把 0 或负数报给手机端。
            log("! 忽略无效的屏幕尺寸 " + w + "x" + h + " —— 保留上一次的有效值，等重试");
            return;
        }
        // 协商分辨率变了（换了手机、改了 DPI、车机换了屏、或旋转后重新量）→ 旧画面的像素
        // 不再是同一块画布，尺寸判定看的是 Surface 尺寸，抓不住这种情况，这里补一道。
        boolean changed = (w != videoW || h != videoH);
        screenW = w; screenH = h; videoW = w; videoH = h;
        // 最后一帧缓存的上限跟着屏幕长边走，保证占位图永远按投屏画面的原尺寸留存。
        lastFrame.setMaxEdge(Math.max(w, h));
        if (changed) lastFrame.invalidate("协商分辨率变化（" + w + "x" + h + "）");
    }

    public String getLastPinCode() { return lastPinCode; }

    /**
     * Surface 就绪 / 变化，并带上 **Surface 的实际像素尺寸**。
     *
     * <p>尺寸不是可有可无的：{@code startCast(surface, w, h)} 里的 w/h 只被 SDK 用于 UIBC
     * 坐标映射（{@code sourceX = x / w * sourceWidth}），而 SDK 内部的
     * {@code startCast} 根本不把 w/h 传给解码器（见 {@code CastManager#startCast}）。
     * 车机是「协商成横屏 1920x720、View 却按竖屏测量」这类不一致时，传错尺寸会让点击整体偏移、
     * 甚至只有屏幕左半边能点。所以这里优先用 Surface 的真实尺寸。
     */
    public void setSurface(Surface s, int viewW, int viewH) {
        this.surface = s;
        videoPaused = false;    // 新 Surface 到位，恢复正常出帧（起播在 tryStartCast 里）
        castRetry = 0;          // 新 Surface 到位，重试预算重置
        // 分辨率 / 横竖屏变了：旧的占位图会被拉伸错位，宁可不显示也不能显示错的。
        lastFrame.dropIfSizeChanged(viewW, viewH);
        boolean wasCasting = castGate.isStarted();
        if (castGate.onSurface(viewW, viewH)) {
            log("投屏 Surface 尺寸 = " + viewW + "x" + viewH
                    + "（协商值 " + videoW + "x" + videoH + "）→ 触摸坐标按 Surface 实际尺寸映射");
            if (wasCasting) {
                // 旋转/尺寸变化把 Surface 从 SDK 脚下换掉了：重新绑定，否则画面与触摸都会错位。
                log("尺寸变化，重新绑定投屏 Surface");
            }
        }
        tryStartCast();
    }

    /**
     * 投屏页<b>即将不可见</b>（Activity {@code onPause}，此时 Surface 仍然有效）→ 提前暂停视频。
     *
     * <p><b>为什么不能只等 {@code surfaceDestroyed}</b>：那条路上 Surface 已经被系统回收，
     * {@code CastManager.pauseCast()} 内部要把解码器的输出面换成 SDK 自己的 dummy 面，
     * 而解码器此刻仍挂在一个已失效的 Surface 上 —— 换面失败就会把播放器置成 {@code hasError}。
     * 之后回到投屏页，{@code CastManager.startCast()} 见 {@code hasError} 就 {@code release()} + 重建
     * 解码器，新解码器必须等 SPS/PPS + 关键帧，而队列里全是旧数据 —— 这就是「偶尔回来黑屏很久」。
     *
     * <p>提前到 {@code onPause} 就能走「只换输出面、不重建解码器」的快路径
     * （SDK 日志为 {@code init, mHasConfigured is true, reset output surface}，不会出现
     * {@code configureDecoder}）；首帧时间取决于手机下一次出帧。
     *
     * <p>只暂停视频：音频走独立 RTP 通道，{@code pauseCast(false)} 不经过它，也不会向手机发
     * {@code gotoBackground}（那是 {@code pauseCast()} 默认参数 {@code true} 的行为）。
     */
    public void pauseCastWhileHidden() {
        UCarAdapter a = adapter;
        if (a == null || videoPaused) return;
        if (!castGate.isStarted()) {
            // 还没绑上 Surface，没有解码器可暂停；等 setSurface 再走起播流程。
            return;
        }
        try {
            a.pauseCast(false);
            videoPaused = true;
            log("投屏页离开（onPause，Surface 仍有效）→ 提前 pauseCast(false)："
                    + "只换输出面、不重建解码器，回来时不用等关键帧");
        } catch (Throwable t) {
            log("提前 pauseCast(false) 异常（不影响后续重建路径）: " + t);
        }
    }

    /**
     * 投屏 Surface 已被系统销毁（投屏页 `surfaceDestroyed`）。
     *
     * <p><b>不通知会黑屏</b>：用户回车机主页时 SurfaceView 的 Surface 被释放，但
     * {@link #setSurface} 只在 <b>尺寸变化</b> 时才重置投屏标志。切回来时 View 尺寸没变 →
     * {@link #tryStartCast()} 被「已在投屏」直接 return，新 Surface 从没交给 SDK，
     * SDK 仍在往那个已释放的 Surface 上画 → <b>黑屏</b>。音频走独立 RTP 通道、不经过
     * Surface，所以声音照常（即「黑屏有声，需断开重连」）。
     *
     * <p><b>正常路径下视频已经在 {@link #pauseCastWhileHidden()} 里停过了</b>，这里只是兜底：
     * 配置变化、被系统直接销毁等没有经过 {@code onPause} 的情形。已经暂停过就<b>不再</b>碰
     * 解码器 —— Surface 已经没了，能不做换面就不做（换面失败即 {@code hasError} → 重建 → 等关键帧）。
     */
    /**
     * 这次 {@code surfaceChanged} 的尺寸该不该先等一等（旋转过渡态 / 无效尺寸）。
     *
     * <p>从车机桌面点图标回来时，SurfaceView 可能先按竖尺寸回调一次 {@code surfaceChanged}，
     * 随后才切成终态尺寸。两次都绑就是短时间内换两次输出面，每次换面都要等一个新的关键帧。
     *
     * <p>判据与「已绑过一次」的例外条件都在 {@link SurfaceBindPolicy}（可单测）。
     */
    public boolean shouldDeferSurfaceBind(int w, int h) {
        return SurfaceBindPolicy.decide(w, h, castGate.isStarted(), videoW, videoH)
                == SurfaceBindPolicy.Bind.DEFER_FOR_SETTLE;
    }

    public void onSurfaceDestroyed() {
        surface = null;
        castRetry = 0;
        // ★ 先记账再 reset：本次销毁之前 SDK 到底还挂没挂在我们的 Surface 上。
        //   没挂过就不换面（对已失效的 Surface 做 setOutputSurface → hasError →
        //   下次 startCast 重建解码器 → 等 SPS/PPS + 关键帧）。
        boolean wasAttached = castGate.isStarted();
        if (wasAttached) {
            log("投屏 Surface 被系统销毁 → 重置投屏状态，切回后重新绑定新 Surface");
        }
        castGate.reset();
        UCarAdapter a = adapter;
        if (a != null && !videoPaused && wasAttached) {
            // 只有「onPause 那条路没走过」（配置变化 / 被系统直接销毁）才需要兜底换面。
            try { a.pauseCast(false); } catch (Throwable t) { log("pauseCast(false) 异常: " + t); }
        } else if (a != null) {
            log("视频已暂停，或本轮从未成功绑定过 Surface → 本次不再换面"
                    + "（避免对已失效的 Surface 做 setOutputSurface → hasError → 重建解码器）");
        }
        videoPaused = false;
    }

    /**
     * 把<b>当前</b> Surface 重新交给 SDK 一次（重绑输出面 + 重新起播）。
     *
     * <p><b>用途只有一个</b>：投屏页长时间等不到画面时的自救。SDK 没有任何回调会告诉我们
     * 「解码器的输出面已经不对了」—— {@code pauseCast} 失败、输出面被系统换掉、{@code startCast}
     * 与 {@code pauseCast} 在 SDK 工作线程上交错，这几种情况都只会表现为「一帧不动、也不报错」。
     * 此时把同一个 Surface 再绑一次，就能让它重走 {@code init, mHasConfigured is true,
     * reset output surface} 的快路径，而<b>不会</b>重建解码器。
     *
     * <p>调用方负责节流（见投屏页的 {@code STUCK_RESTART_PROBES}）：这里每次都真的重绑，
     * 密集调用等于定时重启投屏，反而会把「等关键帧」的时间拖长。
     *
     * @return true 表示确实发起了重绑（不代表随后一定出画）
     */
    public boolean forceRestartCast() {
        Surface s = surface;
        if (s == null || !s.isValid()) return false;
        int w = castGate.width(), h = castGate.height();
        if (w <= 0 || h <= 0) return false;   // 从没绑过 → 交给 surfaceChanged 那条路
        log("! 长时间无画面 → 主动重绑输出面（" + w + "x" + h + "）");
        castGate.reset();                     // 不清就会被 tryStartCast() 的「已在投屏」挡掉
        setSurface(s, w, h);
        return true;
    }

    /**
     * 回到投屏页时，如果视频仍停在「已暂停」态，主动重绑一次。
     *
     * <p><b>补的是一个确定性漏洞</b>：{@link #pauseCastWhileHidden()} 在 {@code onPause} 里就暂停了，
     * 但「暂停」并不保证随后一定有 {@code surfaceDestroyed} —— 车机桌面若以半透明/浮层方式盖住
     * 投屏页、或系统只是把界面挡到后台却没回收 Surface，SurfaceView 就不会重新
     * {@code surfaceCreated / surfaceChanged}。那两条回调是<b>唯一</b>会调用 {@link #setSurface}
     * 的地方，于是回到前台时没有任何人重绑，解码器还挂在 dummy 面上 —— 画面永远黑，直到某个
     * 无关事件碰巧改了 Surface 尺寸（用户感知就是「黑 20 秒到 1 分钟」）。
     *
     * <p>{@code videoPaused} 在这里正好是最准的判据：{@link #onSurfaceDestroyed()} 会把它清掉，
     * 所以「它还是 true」就意味着 Surface 活了下来、且没人重绑。
     *
     * @return true 表示这次真的重绑了
     */
    public boolean resumeCastingIfStillPaused(Surface s, int viewW, int viewH) {
        if (!videoPaused) return false;
        if (s == null || !s.isValid()) return false;   // Surface 不在了就交给 surfaceChanged 那条路
        log("回到投屏页：Surface 未被销毁（没有 surfaceCreated/Changed）→ "
                + "主动重新绑定，别让画面停在暂停态");
        // 必须先把投屏标志清掉：{@link #setSurface} 只在尺寸变化时才重置它，而这里尺寸根本没变，
        // 不清就会让 tryStartCast() 以「已在投屏」为由直接返回，等于什么都没做。
        castGate.reset();
        castRetry = 0;
        setSurface(s, viewW, viewH);
        return true;
    }

    // ==================================================================
    // 最后一帧缓存（切出时留一张画面，切回来先占位，别让司机对着纯黑等手机出帧）
    // ==================================================================

    /**
     * 在投屏页<b>即将不可见、Surface 仍然有效</b>的时刻抓一张当前画面存起来。
     *
     * <p><b>必须早于 {@link #pauseCastWhileHidden()}</b>：那之后解码器的输出面已被换成 SDK 的
     * dummy 面，SurfaceView 这一层不会再有新内容，拷到的未必是用户最后看到的那帧。
     *
     * <p>异步执行（{@code PixelCopy} 拷的是 SurfaceFlinger 里那张图层，不占渲染管线），
     * 只在切出时跑一次 —— 对推流帧率与延迟没有影响。API &lt; 26 直接降级为不占位。
     */
    public void captureLastFrame(SurfaceView view) {
        if (view == null) return;
        if (!LastFrameGrabber.supported()) return;   // 低版本不占位，静默降级
        int maxEdge = lastFrame.maxEdge();
        if (maxEdge <= 0) {
            // 屏幕还没量到：缓存一张尺寸不明的图没有意义，下一次切出时会自动重试。
            log("屏幕分辨率未就绪 → 跳过本次最后一帧抓取，下次切出再试");
            return;
        }
        LastFrameGrabber.capture(view, main, maxEdge, new LastFrameGrabber.Callback() {
            @Override
            public void onCaptured(Bitmap bmp, int surfaceW, int surfaceH) {
                // PixelCopy 是异步的：等回调落地时会话可能已经结束（手机断开 / 用户点了断开）。
                // 那之后再存进去，就等于给「下一次连接」预留了一张上一次会话的过期画面。
                if (!sessionActive) {
                    log("最后一帧抓取完成时会话已结束 → 丢弃，不作占位");
                    try { bmp.recycle(); } catch (Throwable ignored) { }
                    return;
                }
                // 用「当前」的手机 ID 记账：切设备时 {@link #lastFrame} 才能认出这张是谁的。
                lastFrame.store(bmp, lastDeviceId, surfaceW, surfaceH);
                log("最后一帧已缓存（" + surfaceW + "x" + surfaceH + " → 位图 "
                        + bmp.getWidth() + "x" + bmp.getHeight() + "），回来时用作占位");
            }

            @Override
            public void onFailed(int result) {
                log("最后一帧抓取失败（result=" + result + "）→ 回来时不显示占位，按原样等首帧");
            }
        });
    }

    /** 「投屏 Surface 是否已经真的出画」的探测结果回调。 */
    public interface ContentProbe {
        void onResult(boolean hasContent);
    }

    /**
     * 探测投屏 {@code Surface} 此刻是否已经<b>真的出画</b>（异步，结果在主线程回调）。
     *
     * <p><b>为什么需要它</b>：切回投屏页后 {@code SurfaceView} 新拿到的 Surface 是<b>空白</b>的，
     * 要等手机侧推来下一个可解码帧才有画面。以前占位图是定时撤的（1.2 秒），手机侧不出帧时
     * 一到点就露出纯黑 —— 这正是「切回来只看到一两秒就黑屏」的来源。改成只有探测到内容才撤：
     * 手机不出帧就一直留着最后一帧。
     *
     * <p><b>为什么不能改用 SDK 的「首帧已渲染」回调</b>（见 {@link #surfaceStateListener}）：
     * 那个回调由「输出帧 pts 越过重挂输出面时的阈值」触发，暂停期间积压的旧帧 pts 都很小，
     * 画面早就出来了，回调仍可能迟到大几十秒。拿它当判据会在画面正常时把占位图多盖几十秒。
     *
     * <p><b>回调必有且仅有一次</b>：调用方的轮询链靠它续命，漏掉一次就再也不会撤占位图了。
     * 因此参数非法、Surface 还没就绪、位图开不出来这些情况都统一回 {@code false}。
     */
    public void probeSurfaceContent(SurfaceView view, ContentProbe cb) {
        if (cb == null) return;
        if (view == null) {
            cb.onResult(false);
            return;
        }
        LastFrameGrabber.sample(view, main, cb::onResult);
    }

    /**
     * 取缓存的最后一帧给 UI 显示；没有 / 过期 / 已回收则返回 null。
     *
     * <p><b>调用方不要 {@code recycle()} 它</b>：位图仍归 {@link #lastFrame} 所有，下次切出还要用。
     * 用完让 ImageView 断掉引用即可（见投屏页的 {@code onStop}）。
     */
    public Bitmap lastFrame() {
        return lastFrame.peek();
    }

    /**
     * UI 声明自己开始 / 结束显示 {@link #lastFrame()} 返回的那张位图。
     *
     * <p>必须配对调用，否则本类不敢回收当前帧（最多滞留一张位图；现行上限 {@code 2880x1800} 的
     * {@code RGB_565} 约 10 MB，仅 API 26+ 才走抓帧，且像素算在 native 堆上）。登记期间
     * 会话结束 / 尺寸变化只会把位图从缓存里摘掉，真正的 {@code recycle()} 推迟到 {@code false}
     * 之后 —— 直接回收会让 ImageView 下一帧去画一张已回收的位图而崩。
     */
    public void setLastFrameDisplayed(boolean displayed) {
        lastFrame.setDisplayed(displayed);
    }

    /**
     * 兜底绑定该用哪个尺寸 —— 判据与优先级在 {@link SurfaceBindPolicy#pickFallbackSize}。
     *
     * <p>抽到这里而不是让 Activity 各写一遍：兜底是延时执行的，那时 Surface / View 已是终态，
     * 用回调当时那个快照去绑等于多换一次输出面、多等一个关键帧。
     */
    public int[] pickFallbackSurfaceSize(int surfaceW, int surfaceH, int viewW, int viewH,
                                         int cbW, int cbH) {
        return SurfaceBindPolicy.pickFallbackSize(
                surfaceW, surfaceH, viewW, viewH, cbW, cbH, videoW, videoH);
    }

    // ==================================================================
    // 反向控制：车机屏幕触摸 → 手机（ICCOA 的 UIBC 通道）
    // ==================================================================

    /** 已成功回传的触摸动作数；日志节流用，避免拖拽时刷屏。 */
    private int touchSent = 0;
    /** 「被拒」的告警只打一次，避免每个 ACTION_MOVE 都刷一行。 */
    private boolean touchWarned = false;

    /**
     * 把车机屏幕上的触摸事件回传给手机（反向控制）。
     *
     * <p><b>为什么不用 SDK 自带的 {@code UCarSurfaceView}</b>：它的 {@code onTouchEvent} 末尾
     * 调用 {@code super.onTouchEvent()}，而 {@link android.view.SurfaceView} 非 clickable 时对
     * ACTION_DOWN 返回 false —— 手势不会被该 View 捕获，后续 MOVE/UP 根本收不到，于是「单点一下
     * 可能灵、拖拽/长按/滑动必失效」，手机端还会残留一根按下的手指。这里改成由投屏页的
     * OnTouchListener 返回 true 锁定整个手势，本方法只负责逐事件转发。
     *
     * <p><b>坐标语义</b>：{@code startCast(surface, w, h)} 传入的 w/h 就是 SDK 内部的 display
     * 尺寸基准（{@code UibcManager.setDisplayWidth/Height}），SDK 再用
     * {@code x / displayWidth * sourceWidth} 映射到手机屏幕。所以这里必须传 <b>View 内像素坐标</b>，
     * 且 startCast 传入的 w/h 要与该 View 的实际像素尺寸一致，否则点击会整体偏移。
     *
     * <p>事件动作原样透传 {@link MotionEvent#getAction()}（含多指的 pointer index 位），与 SDK
     * 官方 demo 的 {@code UCarSurfaceView} 保持一致，不擅自改成 {@code getActionMasked()}。
     *
     * @return true 表示已交给 SDK（不代表手机端已执行）
     */
    public boolean sendTouch(MotionEvent e) {
        if (e == null) return false;
        UCarAdapter a = adapter;
        if (a == null) return false;

        int count = e.getPointerCount();
        int[] trackID = new int[count];
        int[] x = new int[count];
        int[] y = new int[count];
        for (int i = 0; i < count; i++) {
            trackID[i] = e.getPointerId(i);
            x[i] = (int) e.getX(i);
            y[i] = (int) e.getY(i);
        }

        boolean ok;
        try {
            ok = a.sendTouchEvent(e.getAction(), count, trackID, x, y);
        } catch (Throwable t) {
            ok = false;
            log("触摸回传异常: " + t);
        }

        if (ok) {
            if (touchSent++ == 0) {
                log("✓ 触摸回传已接通 —— 车机屏幕可以直接操作手机（UIBC）");
            }
        } else if (!touchWarned) {
            touchWarned = true;
            log("✗ 触摸回传被 SDK 拒绝：需「投屏已启动 + 手机视频尺寸已知 + 加密已协商」。"
                    + "细节见 UibcManager 标签（encryption is not settled / source size haven't been initialized）");
        }
        return ok;
    }

    /** 触摸回传的成功计数（诊断页/日志用）。 */
    public int getTouchSentCount() { return touchSent; }

    /** 新会话开始时清零，好让「接通/被拒」各只打一次。 */
    private void resetTouchStats() {
        touchSent = 0;
        touchWarned = false;
    }

    // ==================================================================
    // 反向控制：车机方向盘/物理按键 → 手机（ICCOA 的 UIBC 按键通道）
    // ==================================================================

    /**
     * 把车机的物理按键回传给手机（上一曲/下一曲/播放暂停/返回/方向键）。
     *
     * <p>MiAuto 运行期只有 ICCOA，没有 Android Auto 传输，所以方向盘按键必须走这条通道：
     * {@code UCarAdapter#sendKeyEvent} → {@code ControlManager#sendKeyEventToPhone()}
     * （走数据通道，不是 UIBC 触摸那条 JSON 通道）。
     *
     * <p>键码翻译在 {@link MediaKeyMap}（纯 JVM 可单测）。SDK 不可控的键（音量、快进…）返回 false，
     * 由调用方决定怎么处理。
     *
     * @return true 表示已交给 SDK（不代表手机端已执行）
     */
    public boolean sendKeyToPhone(int androidKeyCode, boolean down) {
        UCarAdapter a = adapter;
        if (a == null || !sessionActive) return false;
        // 按下/抬起成对解析：PLAY_PAUSE 族的目标键值取决于「按下那一刻」是否在放歌，UP 必须复用
        // DOWN 选定的键值，否则一次按键会下发 DOWN(PLAY) + UP(PAUSE) 两个相反的动作，
        // 手机端两个都消费时观感仍是「按键没反应」。（见 MediaKeyMap.resolve）
        MediaKeyMap.KeyStroke stroke =
                MediaKeyMap.resolve(androidKeyCode, down, musicPlaying, heldPlayPauseCode);
        heldPlayPauseCode = stroke.nextHeldCode;
        int sdkCode = stroke.sdkCode;
        if (sdkCode == MediaKeyMap.NONE) {
            if (!keyWarned) {
                keyWarned = true;
                log("! 按键 " + androidKeyCode + " 不在 ICCOA 可控键表内，忽略（音量等由车机自己处理）");
            }
            return false;
        }
        UCarCommon.KeyCodeType code = UCarCommon.KeyCodeType.fromInt(sdkCode);
        if (code == UCarCommon.KeyCodeType.KEY_CODE_UNDEFINED) {
            log("! 按键 " + androidKeyCode + " 映射到 SDK 键值 " + sdkCode + " 但 SDK 不认识，忽略");
            return false;
        }
        // 按下瞬间把「预期播放状态」翻转过来，否则长按/连点会连发两次同一个动作（观感也是「没反应」）。
        // 注意只翻状态、不重算本次键值：本次键值已在上面固定。
        if (down && MediaKeyMap.isPlayPauseFamily(androidKeyCode)) {
            if (sdkCode == UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PLAY.getValue()) musicPlaying = true;
            else if (sdkCode == UCarCommon.KeyCodeType.KEY_CODE_MEDIA_PAUSE.getValue()) musicPlaying = false;
        }
        boolean ok;
        try {
            ok = a.sendKeyEvent(
                    down ? UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_DOWN
                         : UCarCommon.KeyEventActionType.KEY_EVENT_ACTION_UP,
                    code, 0);
        } catch (Throwable t) {
            log("按键回传异常（key=" + androidKeyCode + "）: " + t);
            return false;
        }
        if (!ok && !keyWarned) {
            keyWarned = true;
            log("✗ 按键回传被 SDK 拒绝（key=" + androidKeyCode + "）—— 多半是数据通道还没就绪");
        } else if (ok) {
            log("✓ 按键回传 → 手机 key=" + androidKeyCode + " → " + code + " down=" + down);
        }
        return ok;
    }

    /** 新会话开始时清零按键告警（并丢掉上一会话可能残留的按下缓存）。 */
    private void resetKeyStats() {
        keyWarned = false;
        heldPlayPauseCode = MediaKeyMap.NONE;
    }

    // ==================================================================
    // 会话保活：见 SessionKeepAlive（两把锁已搬到独立类）
    // ==================================================================

    /**
     * 会话结束时清理音频侧状态：停看门狗 + 把焦点还给系统。
     *
     * <p>不还焦点会有两个后果：一是车机自己的收音机/导航一直以为「有个媒体应用占着焦点」，
     * 二是 SDK 内部的焦点状态残留（{@code mHasFocus} / 一次性闩），下次连接时更容易落到
     * 「有数据但没有焦点」这个静音分支上。
     */
    private void releaseAudio() {
        cancelAudioFocusGuard();
        musicPlaying = false;
        audioFocusStrikes = 0;
        musicFocus = AudioFocusPolicy.NO_EVENT;
        musicFocusAt = 0L;
        lastAudioFocusAttemptAt = 0L;
        UCarAdapter a = adapter;
        if (a != null) {
            try {
                a.abandonAudioFocus();
                log("音频焦点已归还（abandonAudioFocus）");
            } catch (Throwable t) {
                log("! abandonAudioFocus 异常（继续）: " + t);
            }
        }
    }

    // ==================================================================
    // 昼夜主题诊断（第 1、2 步：先量清这台车有哪一路昼夜信号，再验手机吃不吃这条通道）
    // ==================================================================

    /**
     * {@link DayNightProbe} 的发信出口 —— SDK 类型只准出现在这里。
     *
     * <p>{@code UCarAdapter.notifySwitchDayOrNight} 在真实 {@code icar-sdk.dex} 里是
     * {@code access 0x0001 PUBLIC}、描述符 {@code (Lcom/ucar/vehiclesdk/UCarCommon$DayNightMode;)Z}
     * 与 {@code stubs/icar-sdk-stubs.jar} 完全一致（{@code dexdump} 核过，含 access flags ——
     * jadx 会把 private 渲染成 public，只核名字与签名会踩坑，本工程在音频水位上白跑过一趟），
     * 所以这里可以直调，不需要反射。
     *
     * <p>{@code UNKNOWN_MODE(0)} 被 {@code ControlManager.sendSwitchDayOrNight} 直接拒收
     * （{@code return false}），所以非 1/2 的值一律不发，见 {@link DayNightProbePolicy#isSendable}。
     *
     * @param sdkMode {@code UCarCommon.DayNightMode.getValue()}：1=白天 / 2=夜间
     * @return SDK 返回值（true 仅表示已投递到它自己的线程，不代表手机端已换主题）
     */
    private boolean sendDayNightForProbe(int sdkMode) {
        UCarAdapter a = adapter;
        if (a == null || !DayNightProbePolicy.isSendable(sdkMode)) return false;
        try {
            return a.notifySwitchDayOrNight(sdkMode == UCarCommon.DayNightMode.NIGHT_MODE.getValue()
                    ? UCarCommon.DayNightMode.NIGHT_MODE
                    : UCarCommon.DayNightMode.DAY_MODE);
        } catch (Throwable t) {
            log("昼夜诊断：notifySwitchDayOrNight 异常（不影响投屏）: " + t);
            return false;
        }
    }

    // ==================================================================
    // 定位源（日出/日落的坐标来源）
    // ==================================================================

    /**
     * 拉起定位采样。幂等；没有定位权限或 GPS 被关掉时 {@code GpsLocation.start()} 自己会空转，
     * 这里不额外判权限 —— 少一处判据就少一处漏判。
     */
    private void startLocationFeed() {
        if (locationFeedRunning) return;
        try {
            appCtx.startService(com.andrerinas.openheadunit.location.GpsLocationService.Companion.intent(appCtx));
            locationFeedRunning = true;
            // 权限与定位开关在这里也记一份：startService 是异步的，等 GpsLocation 自己报
            // 「granted=false」要等几百毫秒，而报表里最先被看到的往往是这一行。两者都写，
            // 才能把「调用方有没有拉起」和「拉起后能不能订阅」分开。
            boolean perm = false;
            boolean sysOn = false;
            String providers = "未知";
            try {
                perm = androidx.core.content.PermissionChecker.checkSelfPermission(appCtx,
                        android.Manifest.permission.ACCESS_FINE_LOCATION)
                        == androidx.core.content.PermissionChecker.PERMISSION_GRANTED;
                android.location.LocationManager lm = (android.location.LocationManager)
                        appCtx.getSystemService(android.content.Context.LOCATION_SERVICE);
                if (lm != null) {
                    // 不能用 isProviderEnabled(GPS_PROVIDER) 当「定位开关」：没有 GNSS 芯片的设备
                    // （Wi-Fi 平板）上它恒 false，会把「系统定位开着、只是没有 GPS」报成
                    // 「用户把定位关了」。开关要看系统总开关，能力要看有哪些 provider 可用。
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        sysOn = lm.isLocationEnabled();
                    }
                    StringBuilder sb = new StringBuilder();
                    String[] cand = new String[]{
                            android.location.LocationManager.GPS_PROVIDER,
                            android.location.LocationManager.NETWORK_PROVIDER, "fused"};
                    for (String p : cand) {
                        try {
                            if (lm.isProviderEnabled(p)) {
                                if (sb.length() > 0) sb.append('/');
                                sb.append(p);
                            }
                        } catch (Throwable ignored2) { }
                    }
                    providers = sb.length() == 0 ? "无" : sb.toString();
                }
            } catch (Throwable ignored) { }
            log("定位源：已启动（日出/日落与心跳车速需要坐标）｜启动前自检 权限=" + perm
                    + " 系统定位开关=" + sysOn + " 可用provider=" + providers
                    + (perm && !"无".equals(providers) ? "" : " → 本次可能拿不到车速，见后续 GpsLocation 行"));
        } catch (Throwable t) {
            log("定位源：启动失败（不影响投屏）: " + t);
        }
    }

    /** 停掉定位采样。已取到的最后一个位置仍留在 {@code LocationHolder} 里，日出日落照样能算。 */
    private void stopLocationFeed() {
        if (!locationFeedRunning) return;
        try {
            appCtx.stopService(com.andrerinas.openheadunit.location.GpsLocationService.Companion.intent(appCtx));
            log("定位源：已停止（链路结束；最后一次位置仍保留给日出日落用）");
        } catch (Throwable t) {
            log("定位源：停止异常: " + t);
        }
        locationFeedRunning = false;
    }

    // ==================================================================
    // 主入口
    // ==================================================================

    /** 启动（或重启）投屏链路。权限由调用方（Activity）负责。 */
    public void start() {
        log("=== 启动（ICCOA CarWith · 低端车机加固版）===");
        log("环境：Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")"
                + " ABI=" + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS)
                + " 机型=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        logNativeLibCheck();
        // ★ SDK 日志打捞：SDK 的全部内部日志走 EasyLog（tag=easylog_*），而诊断报表只采
        //   OPENHU。车上没有 adb，这是唯一能把「no focus, drop data」「WfdSinkStopped」
        //   这类判死因的行带回实验室的通道。幂等，重复调用无害。
        SdkLogCapture.start();
        // ★ AP 复用（G4）：注册热点状态广播并做一次建链前注入。全部动作在维护层自己的
        //   后台线程串行执行；flag 关闭时 isArmed 不过，连 bindService 都不发生（零副作用）。
        apReuse.onManagerStart();
        // 新一轮由人发起的链路：phase 时间线从这里重新记，避免把上一次的残留混进失败现场。
        synchronized (phaseHistory) {
            phaseHistory.clear();
        }
        log("加固：会话期间持 Wi-Fi 低延迟锁 + CPU 唤醒锁；手动断开后停机、不自动重连；"
                + "异常断连立刻重播；方向盘媒体键经 UIBC 回传手机");
        // 只有用户主动恢复才会走到这里（Activity 在应用启动时调用一次），所以一并清掉停机态。
        paused = false;
        // 台架模式：adb 显式设了 miauto_daynight_probe=1 才启动，用于「没有手机连上也想读昼夜信号」
        // （平板上没有会话，跟会话跑的那条 start() 永远不会触发）。没设开关时本调用什么都不做。
        dayNight.startStandaloneIfRequested();
        startLocationFeed();
        if (sdkInitialized && adapter != null) {
            // 画布过期（停机期间转过屏幕 / 改过方向设置）时，helper 会拆 SDK 并完整重init。
            if (renegotiateIfStale("start() 画布变化")) return;
            log("重启：SDK 已初始化，仅重新广播");
            reAdvertise();
            return;
        }
        beginFreshLink();
    }

    /**
     * 完整初始化一段新链路：建 config（带上当前画布）→ 拿 adapter → 注册监听 → init。
     *
     * <p>{@link #start()} 与 {@link #resumeLink()}（主页「连接」按钮，停机期间画布可能已变）
     * 共用。画布是在 {@link #buildConfig()} 里读 {@link #screenW}/{@link #screenH} 的，所以
     * 调用方必须先 {@link #setScreenSize} / {@link #remeasureScreenSize()} 把新画布灌好。
     */
    private void beginFreshLink() {
        // ★ 5 秒后检查一次兜底自救：只有 SDK 真的没就绪才发传统广播。启动瞬间热点没开、
        //   准入闸主动拦下 doAdvertise() 属设计意图，不算故障（判定见 FallbackRescuePolicy）。
        advertiseAttempted = false;
        main.removeCallbacks(earlyFallbackRunnable);
        main.postDelayed(earlyFallbackRunnable, EARLY_FALLBACK_MS);
        UCarConfig config;
        try {
            config = buildConfig();
        } catch (Throwable t) {
            log("✗ 配置构造失败: " + t);
            return;
        }
        try {
            adapter = UCarAdapter.getInstance();
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("✗ getInstance/register 异常: " + t);
            return;
        }
        // 记下这次真正灌进 UCarConfig 的画布，供 canvasStale() 判断「下次重连要不要重谈」。
        // screenW 只可能是 0 或正数（setScreenSize 拒收 ≤0），0 原样留 0：那次 init 用的是
        // Math.max(1, …) 的残缺画布，那份 config 不该被当成有效基准，让 canvasStale() 继续报
        // 「需重谈」。
        initCanvasW = screenW;
        initCanvasH = screenH;
        initSdk(config);
    }

    /**
     * 原生库自检 —— 只读，不改行为，纯粹为了让老车机上的失败原因一眼可见。
     *
     * <p>SDK 的解码器 {@code libovmsink.so} 与 USB 通道 {@code libusbio.so} **只编了 arm64-v8a**。
     * 32 位车机上这两个库不存在，SDK 初始化会失败，而失败点在 SDK 内部线程里、异常可能被吞掉，
     * 车机上就只看到「App 正常打开、配对码不出现、手机扫不到」。这里提前把结论写进日志。
     */
    private void logNativeLibCheck() {
        try {
            String nativeDir = appCtx.getApplicationInfo().nativeLibraryDir;
            java.io.File dir = nativeDir == null ? null : new java.io.File(nativeDir);
            String[] names = dir == null ? null : dir.list();
            int count = names == null ? 0 : names.length;
            boolean sink = false, usbio = false;
            if (names != null) {
                for (String n : names) {
                    if ("libovmsink.so".equals(n)) sink = true;
                    else if ("libusbio.so".equals(n)) usbio = true;
                }
            }
            log("原生库自检：nativeLibraryDir 内 " + count + " 个 .so，"
                    + "libovmsink.so=" + (sink ? "有" : "缺") + " libusbio.so=" + (usbio ? "有" : "缺"));
            if (!sink) {
                log("✗✗ 缺 libovmsink.so —— 本机装载的是 32 位/非 arm64 包（或安装时被裁掉了 arm64 原生库）。"
                        + "SDK 初始化必然失败，投屏走不通；BLE 发现仍可靠兜底广播。");
            }
        } catch (Throwable t) {
            log("原生库自检异常（忽略）: " + t);
        }
    }

    private void initSdk(UCarConfig config) {
        boolean posted;
        try {
            posted = adapter.init(appCtx, config, new ICarInitCallback() {
                @Override
                public void onInitSuccess() {
                    log("★ onInitSuccess()");
                    sdkReady = true;
                    sdkInitialized = true;   // ★ init-once 守门
                    enableUsbTransport();
                    main.post(() -> safe("doAdvertise", IccoaManager.this::doAdvertise));
                    main.post(IccoaManager.this::scheduleAutoRefresh);   // ★ 启动配对码看门狗
                }

                @Override
                public void onInitFailed(int errCode) {
                    log("✗ onInitFailed(" + errCode + ")"
                            + (errCode == 10001 ? " ← HandlerThread 启动失败" : ""));
                    // SDK 起不来 → 广播链路不能跟着哑掉，兜底广播照发。
                    startFallbackRescue("SDK 初始化失败 onInitFailed(" + errCode + ")");
                }
            });
        } catch (Throwable t) {
            // init() 里最可能是 UnsatisfiedLinkError（缺 arm64 原生库）或 SecurityException。
            log("✗ init() 抛异常: " + t);
            startFallbackRescue("SDK init 抛异常 " + t.getClass().getSimpleName());
            return;
        }
        if (!posted) {
            log("✗ init() 返回 false —— 任务未提交");
            startFallbackRescue("SDK init 未提交任务");
        }
    }

    /** 5 秒兜底：只在 SDK 确实没就绪时才独立拉起传统广播（判定见 FallbackRescuePolicy）。 */
    private final Runnable earlyFallbackRunnable =
            () -> startFallbackRescue("SDK 5 秒内未走到 startAdvertise()");

    /**
     * 独立拉起兜底广播（不依赖 SDK 状态）。
     *
     * <p>三种触发：SDK 初始化失败 / init 抛异常 / 5 秒内没走到 {@code startAdvertise()}。
     * 前两种确实是「SDK 这条路发不出广播」；<b>第三种未必</b> —— 启动瞬间热点没开时，
     * 准入闸把 {@link #doAdvertise()} 拦下是设计意图，SDK 好得很。判定交给
     * {@link FallbackRescuePolicy}：只在 SDK 真的没就绪时才发这条传统广播。
     */
    private void startFallbackRescue(String reason) {
        FallbackRescueDecision decision = FallbackRescuePolicy.INSTANCE.decide(
                sdkReady, advertiseAttempted, fallback.isRunning());
        if (decision == FallbackRescueDecision.SKIP_GATE_BLOCKED) {
            // 这一行是以后排查「手机反复连上又断开」的关键：它明确区分了「没广播是因为
            // 用户还没点连接」和「SDK 挂了」。发一条没有 GATT 服务承接的传统广播只会让手机
            // 每 5~6 秒连一次 ACL 再断开，还会把首页「连接」按钮顶掉。
            log("※ 跳过兜底广播自救：SDK 已就绪、广播被准入闸主动拦下 → 等用户点「连接」（"
                    + reason + "）；此时发传统广播没有 GATT 承接，只会让手机反复连上又断开");
            return;
        }
        if (decision == FallbackRescueDecision.SKIP_ALREADY_RUNNING) {
            log("兜底广播已在运行，忽略自救（" + reason + "）");
            return;
        }
        if (decision != FallbackRescueDecision.RESCUE) {
            // SKIP_SDK_ADVERTISING：SDK 已广播，兜底交给 startAdvertise() 后的 800ms 并行任务。
            log("SDK 已走到 startAdvertise()，无需自救（" + reason + "）");
            return;
        }
        log("★ 启动兜底广播自救： " + reason);
        if (carIdBytes == null || carIdBytes.length != 6) {
            byte[] id = new byte[6];
            new Random().nextBytes(id);
            carIdBytes = id;
            log("! 自救时 carId 不可用，临时随机一个（手机端会当成陌生车机，需重新配对）");
        }
        startFallback();
    }

    /**
     * 重播：先停旧广播，再启新广播。
     *
     * <p><b>必须先 {@code stopAdvertise()}</b> —— 对一条已经在广播中的链路重复调用
     * {@code startAdvertise()} 会被 SDK 直接忽略，广播帧内容不变，手机端因此不会重新发现
     * 车机、也就不会重新弹窗。只有「停-启」才是一次真正的重播。
     */
    private boolean reAdvertise() {
        if (adapter == null) return false;
        try {
            adapter.registerCarConnectListener(connectListener);
            adapter.registerPhoneDataListener(phoneDataListener);
        } catch (Throwable t) {
            log("reAdvertise 重新注册异常: " + t);
        }
        try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise(重播前) 异常: " + t); }
        main.removeCallbacks(fallbackStartRunnable);
        main.removeCallbacks(earlyFallbackRunnable);
        fallback.stop();   // 兜底广播同样「停-启」，否则广播帧不变手机不会重新弹窗
        sdkReady = true;
        paused = false;      // 只要能重播，就说明用户/看门狗要求这条链路活着
        castGate.reset();
        castRetry = 0;
        lastPinCode = null;
        negotiating = false;
        lastPhase = 0;
        resetTouchStats();
        resetKeyStats();
        cancelRescue();
        return doAdvertise();
    }

    /**
     * 用户主动「刷新配对码」：立刻重播一次，不必等看门狗的 30 秒周期。
     *
     * <p>与自动刷新走同一条路径，重播后看门狗重新计时。
     */
    public void refreshPairing() {
        log("=== 手动刷新配对码 ===");
        if (!sdkReady || adapter == null) {
            log("刷新失败：SDK 尚未就绪");
            return;
        }
        if (!reAdvertise()) {
            log("刷新配对码：广播被热点闸拦下 → 不排自动刷新");
            return;
        }
        scheduleAutoRefresh();
    }

    /**
     * 车载热点被关掉时把广播停下来（{@code canAdvertise()} 为假之后由上层调用）。
     *
     * <p><b>为什么不只是「下次别再播」</b>：热点关掉的那一刻，已经在跑的广播帧还在外发，手机
     * 照样能扫到这台车、发起连接，然后死在 Wi-Fi 入网阶段 —— 用户看到的又是「连了半天失败」。
     * 停掉广播，手机就扫不到，配合界面上的「等待开启车载热点」，用户才知道该去开热点。
     *
     * <p>只停广播：不拆会话（会话若还在，由连接层自己的失败/断开路径处理）、不改 {@code paused}
     * （那不是用户主动停机）、不清 {@code sdkReady}（热点恢复后一次 {@link #start()} /
     * {@link #resumeLink()} 就能重播）。
     */
    public void pauseAdvertisingForGate() {
        advertisingArmed = false;
        if (adapter != null) {
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise(热点闸) 异常: " + t); }
        }
        main.removeCallbacks(fallbackStartRunnable);
        main.removeCallbacks(earlyFallbackRunnable);
        main.removeCallbacks(autoRefreshRunnable);
        fallback.stop();
        log("广播已暂停：车载热点未开启（热点恢复后重播）");
    }

    public void stop() {
        log("=== 停止 ===");
        advertisingArmed = false;
        main.removeCallbacks(fallbackStartRunnable);
        main.removeCallbacks(earlyFallbackRunnable);
        fallback.stop();
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise 异常: " + t); }
            try {
                adapter.unregisterCarConnectListener(connectListener);
                adapter.unregisterPhoneDataListener(phoneDataListener);
            } catch (Throwable ignored) { }
        }
        main.removeCallbacks(reAdvertiseRunnable);
        cancelAutoRefresh();
        cancelRescue();
        sessionActive = false;
        sessionStartedAt = 0L;
        stopHeartbeat();
        castGate.reset();
        negotiating = false;
        sdkReady = false;
        lastPinCode = null;
        paused = false;
        releaseAudio();
        keepAlive.release();
        dayNight.stop();
        apReuse.onSessionDown("stop()");
        stopLocationFeed();
        lastFrame.invalidate("链路彻底停止（stop）");
        resetTouchStats();
        resetKeyStats();
        // 「仅 5G」的信道限制存在 supplicant 里、会跟着进程活到下次启动 —— 彻底停止时还给平台。
        // 不能拿 fiveGhzOnly 当门：用户可能先开开关（限制已下发）再关掉它，那时快照是 false，
        // 限制就永远留着了。闸门自己知道有没有东西可撤（restrictionApplied），无脑调即可。
        IccoaFiveGhzGate.release(this::log);
        // 方向挂起补偿：用户在投屏中改了方向但选了「保持当前连接」，此刻会话真的断了，
        // 把 SDK 拆掉，让下一次 start() 用新画布重新协商（见 reinitPending 的注释）。
        if (reinitPending) {
            reinitPending = false;
            deinitAdapter("stop() 方向挂起补偿");
        }
        // sdkInitialized 一般保留（init() 进程一次性，重启走 reAdvertise）；但上面的方向挂起
        // 补偿可能已经把它拆掉 —— 那正是为了让下一次连接按新画布重新协商。
    }

    /**
     * 屏幕方向变更后重建 SDK 协商画布 —— **会话级生效**的落点。
     *
     * <p>为什么必须拆 SDK：协商画布（[UCarConfig] 的 screen/video 尺寸）只在
     * {@link #buildConfig()} → {@link #initSdk} 时灌入，运行期没有任何 API 能改；
     * 而 {@link #start()} 在 {@code sdkInitialized} 为真时只走 reAdvertise 分支，
     * 拿着旧 config 重播 —— 这就是「改了方向、重连还是老方向」的根因。
     * 拆掉之后，下一次 {@code start()} 必然走完整分支：重新 buildConfig（新画布）
     * + 重新 init，新方向从下一次建立连接起生效。
     *
     * <p>当前会话会被一并拆掉（stop + deInit 的语义如此），调用方负责提示用户重连。
     */
    public void reinitForOrientation() {
        log("=== 方向切换：重建 SDK 协商画布 ===");
        // 没投过屏时 stop() 也是幂等的（adapter 有 null 守卫，各组件自管），无需额外判断。
        stop();
        reinitPending = false;
        deinitAdapter("reinitForOrientation");
    }

    /** 标记「下次恢复连接/停止时补拆 SDK」（用户在投屏中改方向但选择保持当前连接）。 */
    public void markReinitPending() {
        if (!sdkInitialized && adapter == null) return;
        reinitPending = true;
        log("方向切换：保持当前连接，SDK 重init 已挂起（下次恢复连接或停止时生效）");
    }

    /**
     * 反初始化 SDK 并清掉 init-once 守门，让下一次 {@link #start()} 重新 buildConfig + init。
     *
     * <p>deInit 是 SDK 的公开入口（见 {@code UCarAdapter#deInit}），与 init 成对；
     * 异常路径也照样清标志 —— 下次 start() 重新 init 试一试，总比挂着旧 config 强。
     */
    private void deinitAdapter(String reason) {
        UCarAdapter ad = adapter;
        adapter = null;   // 下一次 start() 重新 getInstance + register + init
        sdkInitialized = false;
        sdkReady = false;
        if (ad == null) {
            log("SDK 重init（" + reason + "）：adapter 本就为空，仅清标志");
            return;
        }
        try {
            ad.deInit();
            log("SDK deInit 完成（" + reason + "）—— 下次连接按新方向协商");
        } catch (Throwable t) {
            log("! deInit 异常（" + reason + "，忽略，标志已清下次重新 init）: " + t);
        }
    }

    /**
     * 用户主动断开（投屏页的「断开连接」）：拆会话 + **停机**，不再自动重连。
     *
     * <p><b>为什么不是「只拆会话、继续广播」</b>（旧行为）：车机一继续广播，手机端 CarWith 就会把
     * 这台熟悉的 MiAutoCar 自动连回来 —— 用户按了「断开」，几秒后投屏画面又顶上来，等于按钮没生效。
     * 现在把 SDK 广播、兜底广播、配对码看门狗、协商自救计时器全部停掉，并关掉 USB 自动发现，
     * 车机安静下来；要重连必须由人在主页按「连接」（{@link #resumeLink()}）。
     *
     * <p>与 {@link #stop()} 的区别：{@code stop()} 是彻底停机（连 SDK 初始化标志都保留、监听器注销），
     * 本方法只「暂停广播」，SDK 仍初始化可用，所以恢复是即时的、不需要重启进程。
     */
    public void disconnectSession() {
        pauseLink("用户手动断开");
    }

    /**
     * 进入「等用户点连接」的停机状态：停广播、断会话、放锁、清空计时器。
     *
     * @param reason 写进日志的原因（用户手动断开 / 手机端主动断开）
     */
    private void pauseLink(String reason) {
        paused = true;
        advertisingArmed = false;
        log("=== 停机：" + reason + " → 停止广播，不再自动重连 ===");
        main.removeCallbacks(fallbackStartRunnable);
        main.removeCallbacks(earlyFallbackRunnable);
        main.removeCallbacks(reAdvertiseRunnable);
        cancelAutoRefresh();
        cancelRescue();
        fallback.stop();
        if (adapter != null) {
            try { adapter.pauseCast(); } catch (Throwable t) { log("pauseCast 异常: " + t); }
            try { adapter.disconnect(); } catch (Throwable t) { log("disconnect 异常: " + t); }
            try { adapter.stopAdvertise(); } catch (Throwable t) { log("stopAdvertise 异常: " + t); }
            // 有线通道同样要停：插着数据线的手机在 AOA 通道上照样能自己连回来。
            try { adapter.enableUsbDeviceDetection(false); } catch (Throwable t) { log("关 USB 发现异常: " + t); }
        }
        sessionActive = false;
        sessionStartedAt = 0L;
        stopHeartbeat();
        castGate.reset();
        castRetry = 0;
        videoPaused = false;
        negotiating = false;
        lastPinCode = null;
        releaseAudio();
        keepAlive.release();
        dayNight.stop();
        stopLocationFeed();
        // 会话没了，画面也属于那次会话 —— 留着会让下次连接的第一帧之前闪出一台别的手机的画面。
        lastFrame.invalidate("会话结束（停机）");
        // 「仅 5G」的限制同理要还回去：落在 2.4G 被拒时走的正是这条停机路径（不是 stop()），
        // 不在这里撤销就会把整机 P2P 钉在 5G 上，跟着车机的其它 Wi-Fi Direct 用途一起受影响。
        IccoaFiveGhzGate.release(this::log);
        log("已停机 —— 车机不再广播；手机端也不会自动连回。需要时在主页点「连接」恢复。");
    }

    /**
     * 手动恢复（主页「连接」按钮）：退出停机状态并重新广播。
     *
     * @return true 表示已重新广播；false 表示 SDK 还没就绪（此时不改状态，让 UI 继续显示「已断开」）
     */
    public boolean resumeLink() {
        if (paused) log("=== 手动连接：恢复广播 ===");
        if (!sdkReady || adapter == null) {
            log("✗ 恢复失败：SDK 尚未就绪（onInitSuccess 还没回来？）");
            return false;
        }
        paused = false;
        synchronized (phaseHistory) {
            phaseHistory.clear();
        }
        enableUsbTransport();   // 停机时关掉的 USB 自动发现，一并恢复
        startLocationFeed();    // 停机时停掉的定位采样，随恢复一起拉起
        // 画布保鲜（0.1.14）：停机期间用户可能转过屏幕（SYSTEM 档「系统缺省」尤其常见 —— 该档
        // 不转 App 界面，只在连接时按窗口真实方向上报画布），也可能在会话里改过方向但选了
        // 「保持当前连接」。协商画布在 SDK init 时定死、reAdvertise 只会拿旧 config 重播，
        // 所以先重量一次屏幕，交给 renegotiateIfStale 定去向：画布变了就拆 SDK 完整重init，
        // 没变仍旧走即时 reAdvertise，不增加连接耗时。carId 有 SharedPreferences 缓存，
        // 重 init 不换身份、不需要重新配对。
        remeasureScreenSize();
        if (renegotiateIfStale("resumeLink 画布重谈")) return true;
        if (!reAdvertise()) {
            // 广播被热点闸拦下：**不排看门狗**。排了就等于 30 秒后替用户自动起链 ——
            // 用户在「热点已就绪」上还没点「连接」，车机却自己广播起来，又要走一遍
            // SDK 接管热点（不可复用时 stopTethering 关热点）的老路。
            log("广播被热点闸拦下 → 本轮不排自动刷新（等用户点「连接」）");
            return true;
        }
        scheduleAutoRefresh();
        return true;
    }

    /** 是否处于「等用户点连接」的停机状态（UI 据此显示「已断开 + 连接按钮」）。 */
    public boolean isPaused() { return paused; }

    /**
     * SDK 起链流程是否已授权广播（{@code doAdvertise()} 走到过 {@code adapter.startAdvertise()}）。
     *
     * <p>这是「车机有没有被点过连接」的事实源，界面阶段不是 —— 一次兜底广播、一次状态迁移
     * 都可能把 `Advertising` 写在「SDK 一条广播都没发」的状态上；它同时是 30 秒看门狗的
     * 准入条件。注意它表示<b>流程已走通</b>，不是物理广播帧一定发出：
     * {@code startAdvertise()} 返回 false 时仍置位，避免看门狗彻底放弃重播。
     * {@code IcocaBridge.refreshHotspotReadiness()} 据此把界面收敛回可点「连接」的那一态。
     */
    public boolean isAdvertisingArmed() { return advertisingArmed; }

    /**
     * SDK 是否已在本进程内初始化（init-once 守门的现状值）。
     *
     * <p>建链方式（transport）只在 {@link #start()} 首次初始化时被 {@code buildConfig()} 读取，
     * 之后改偏好键不会生效 —— 「连接设置」向导据此判断：SDK 已初始化且 transport 实际变化时，
     * 必须重启应用（进程死亡 → 重新 init）才能让新模式生效。
     */
    public boolean isSdkInitialized() { return sdkInitialized; }

    /** 会话是否已建立（连接或投屏任一成立）。按键回传据此决定走不走 ICCOA 通道。 */
    public boolean isSessionActive() { return sessionActive; }

    /**
     * 投屏/连接会话是否活跃 —— 在线更新硬门禁的唯一事实源。
     *
     * <p>「会话中」= {@link #sessionActive} 或 {@link CastGate#isStarted()} 任一成立。
     * 更新模块只调这个只读访问器，不接触 {@link CastGate} 本体，也不自行推断会话状态
     * （root 静默装会杀进程重启，等于主动制造断链黑屏 —— 本 App 最不可触碰的不变量）。
     */
    public boolean isCastActive() { return sessionActive || castGate.isStarted(); }

    // ==================================================================
    // 广播 / 投屏
    // ==================================================================

    /**
     * 广播前的准入检查（语义见 {@link Listener#canAdvertise()}）。
     *
     * <p>检查本身抛异常一律**放行**：这道闸是为了少一次注定失败的广播，不是为了用异常把
     * 整条链路掐死。被拦时不改任何会话状态 —— 下一次广播机会（用户返回应用、30 秒空转重播）
     * 会自动再判一次。
     */
    private boolean advertiseAllowed(String why) {
        if (listener == null) return true;
        boolean allowed;
        try {
            allowed = listener.canAdvertise();
        } catch (Throwable t) {
            log("! 广播前检查异常（" + why + "）→ 按放行处理: " + t);
            return true;
        }
        if (!allowed) {
            // 常态（热点关着时的空转重播）会反复走到这里，只记一行不带堆栈。
            log("广播前检查未通过（" + why + "）→ 本次不广播");
        }
        return allowed;
    }

    /**
     * 兜底广播的准入检查：判定与 {@link #advertiseAllowed(String)} 同源，但不触发
     * 「放行 → 广播中」的界面迁移，也不弹引导窗。
     *
     * <p>检查抛异常同样按放行处理 —— 这道闸是为了少发一次注定失败的广播，不是为了把
     * 老车机上唯一能被发现的通道掐死。
     */
    private boolean fallbackAdvertiseAllowed() {
        if (listener == null) return true;
        boolean allowed;
        try {
            allowed = listener.canAdvertiseForFallback();
        } catch (Throwable t) {
            log("! 兜底广播准入检查异常 → 按放行处理: " + t);
            return true;
        }
        if (!allowed) log("兜底广播准入未通过 → 本次不发");
        return allowed;
    }

    private boolean doAdvertise() {
        if (adapter == null) return false;
        // 广播闸（见 Listener#canAdvertise）。放在这里而不是 UI 层：界面、看门狗、兜底三轮
        // 广播最终都汇到本方法，这里是「真正发出广播帧」的唯一出口。
        if (!advertiseAllowed("doAdvertise")) return false;
        advertiseAttempted = true;                 // 自救计时器从此不再触发
        main.removeCallbacks(earlyFallbackRunnable);
        // ★ 「仅 5G」的建组前偏置：此刻还没 P2P 组，setWifiP2pChannels 才可能被平台接受。
        //   手机随后发起连接时，UCar 会自己打 "set channel to 0" 复位 —— 若它把限制抹掉，
        //   这里就退化成「不干预」，最终以 verifyFiveGhz() 读到的实频为准。
        refreshFiveGhzOnly();
        if (fiveGhzOnly) {
            IccoaFiveGhzGate.apply(appCtx, this::log,
                    new com.andrerinas.openheadunit.utils.Settings(appCtx).getAskFiveGhzUpperBand());
        }
        boolean ok = adapter.startAdvertise();
        advertisingArmed = true;   // 起链流程已授权 → 允许看门狗延续「保持可被发现」；
                                   // 不看 ok：广播失败时也不能让看门狗彻底放弃重播
        // 空转期每次重播都会走到这里，30 秒一条没有判读价值；首次与「刚连上又掉了」
        // （sessionCount<=1 之后的第一次）才立即记，其余并入空转重播的聚合行。
        if (startAdvertiseCount == 0 || sessionActive || sessionCount <= 1) {
            log((ok ? "✓ " : "✗ ") + "startAdvertise() = " + ok);
        }
        startAdvertiseCount++;
        // ★ 低端车机兜底：SDK 走扩展广播，老芯片可能「成功但无声」。并行再发一条传统广播，
        //   帧内容与 SDK 完全一致。
        //   延后 800ms：SDK 是在 startAdvertise() 的 worker 线程里生成广播序列号并写入
        //   serial_num_prefs 的，兜底广播必须读到同一个序列号。序列号在 ICCOA 里是「配对
        //   会话号」，两条广播序列号不一致时，手机端会把同一台车看成两个会话并反复重置配对。
        main.removeCallbacks(fallbackStartRunnable);
        main.postDelayed(fallbackStartRunnable, FALLBACK_START_DELAY_MS);
        tryStartCast();
        return true;
    }

    /**
     * 重读「仅 5G」的实际生效值；设置页保存后下一次广播/连接即生效，不需要重启进程。
     *
     * <p>2026-10-24 起不再有独立偏好：改由全局 5G 开关 {@code ask-five-ghz}
     * （"Ask for 5 GHz"，车载热点频段顺序 / AA 遗留信道请求 / 本路闸门共用的唯一入口）驱动；
     * 上频段偏好 {@code ask-five-ghz-upper} 一并透传给建组前偏置。
     *
     * <p>两个口径：
     * <ul>
     *   <li>开关源即全局 {@code ask-five-ghz}（"Ask for 5 GHz"），不再叠加任何例外 —— 原先压制
     *       本闸门的调试开关 debugForceP2pBand24 已删除，其「强制 2.4G」语义由关掉本开关承担；</li>
     *   <li>「开→关」的跃迁仍要当场撤销已下发的信道限制，否则用户以为关掉了，
     *       实际整机 P2P 还钉在 5G 上（限制存在 supplicant 里，不在本进程）。</li>
     * </ul>
     */
    private void refreshFiveGhzOnly() {
        boolean previous = fiveGhzOnly;
        try {
            fiveGhzOnly = new com.andrerinas.openheadunit.utils.Settings(appCtx).getAskFiveGhz();
        } catch (Throwable t) {
            fiveGhzOnly = false;
        }
        if (previous && !fiveGhzOnly) {
            log("仅5G：开关已关闭 → 撤销 P2P 信道限制");
            IccoaFiveGhzGate.release(this::log);
        }
    }

    /**
     * 「仅 5G」事后校验。
     *
     * <p>读 p2p0 当前组的实际频率：读不到（无组 / 无权限 / 平台不提供）一律不判定，理由与 AA 路
     * 一致 —— 没测量就不下结论；落在 2.4G 则<b>停机</b>（走 {@link #pauseLink(String)}，不会自动
     * 重连，所以不存在「拒了又连、连了又拒」的死循环），并把原因写进日志与提示。
     */
    private void verifyFiveGhz() {
        IccoaFiveGhzGate.verify(appCtx, this::log, frequency -> {
            if (frequency <= 0) {
                log("仅5G：读不到 P2P 实际频率（无组/无权限/平台不提供），本次不判定");
                return;
            }
            boolean band24 = IccoaFiveGhzGate.isTwoPointFourGhz(frequency);
            log("仅5G：P2P 实际频率=" + frequency + "MHz（" + (band24 ? "2.4G" : "5G")
                    + "，信道 " + IccoaFiveGhzGate.frequencyToChannel(frequency) + "）");
            if (!band24) return;
            log("✗ 仅5G：本次 P2P 落在 2.4G（" + frequency + "MHz）→ 停机，不在这条链路上投屏");
            main.post(() -> {
                try {
                    android.widget.Toast.makeText(appCtx,
                            "仅5G：P2P 落在 2.4G（" + frequency + "MHz），已停止连接",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) { }
                pauseLink("仅5G：P2P 落在 2.4G（" + frequency + "MHz）");
            });
        });
    }

    /** 兜底广播的启动动作（延后执行，见 {@link #doAdvertise()}）。 */
    private final Runnable fallbackStartRunnable = this::startFallback;

    private void startFallback() {
        // 会话一旦建立就必须停止广播：SDK 在建链完成时会自己 stopAdvertise()，我们这条并行广播
        // 若继续发，手机端会把「已连接的车机」重新当成可连接设备扫描，甚至反复重置配对会话。
        // 这里再挡一道：延后 800ms 的启动任务可能在连接成功之后才跑（见 FALLBACK_START_DELAY_MS）。
        if (sessionActive) {
            log("会话已建立，兜底广播不再启动");
            return;
        }
        // 兜底广播同样是一次真实的 BLE 广播，同一道闸：热点没开时它也不该发。
        // 但必须走「静默」版本 —— 一条辅助广播不能改写界面阶段、收掉首页「连接」按钮，
        // 否则用户看到「等待手机连接」却没有任何办法起链（见 canAdvertiseForFallback）。
        if (!fallbackAdvertiseAllowed()) return;
        fallback.start(carIdBytes, sessionActive);
    }

    /**
     * 启用 USB（AOA）有线通道。
     *
     * <p>SDK 自带 USB 有线连接能力（{@code enableUsbDeviceDetection}），但 MiAuto 从没打开过它。
     * 对蓝牙栈老旧、BLE 广播发不出去的车机，有线是唯一稳的路径：手机插车机 USB 数据口即可，
     * 完全绕开 BLE 发现。SDK 内部会自己处理 AOA 模式切换与鉴权（PIN 由 carId 派生）。
     */
    private void enableUsbTransport() {
        if (adapter == null) return;
        try {
            adapter.enableUsbDeviceDetection(true);
            log("✓ 已启用 USB 有线通道（enableUsbDeviceDetection(true)）：插上数据线即可尝试连接");
        } catch (Throwable t) {
            log("✗ enableUsbDeviceDetection 失败: " + t);
        }
    }

    /**
     * 尝试起投。
     *
     * <p>重试有上限：停车待机页没有 Surface，无上限重试会变成一个永不停止的 500ms 定时器
     * （待机页常驻，等于白耗电）。Surface 稍后就绪的常见情形由 {@link #setSurface} 兜底，
     * 这里的十秒预算只用于「Surface 已设但尚未 valid」「分辨率基准还没量到」这两种短暂窗口。
     * 两条重试路径共用一个 {@code castRetry} 预算，复位点在真正起投之前（见方法内注释）。
     */
    private void tryStartCast() {
        if (!sdkReady || castGate.isStarted() || adapter == null) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                main.postDelayed(this::tryStartCast, 500);
            }
            return;
        }
        // 这里刻意不重置 castRetry：下面「分辨率基准还没拿到」那条重试要靠它计数。若在尺寸检查
        // 之前清零，每次重试都从 0 重来，预算永远到不了上限，500ms 定时器会无限自续。
        // UIBC 坐标的除数基准：优先用 Surface 实际尺寸（见 setSurface 的说明）。
        int measuredW = castGate.width(), measuredH = castGate.height();
        int dw = measuredW > 0 ? measuredW : videoW;
        int dh = measuredH > 0 ? measuredH : videoH;
        if (dw <= 0 || dh <= 0) {
            // 两个来源都还没有：大概是没人灌过尺寸（广播拉起 / Activity 还没走完 onCreate），
            // 自己量一次再试。
            refreshScreenSizeIfNeeded();
            dw = measuredW > 0 ? measuredW : videoW;
            dh = measuredH > 0 ? measuredH : videoH;
        }
        if (dw <= 0 || dh <= 0) {
            // 拿 0 去 startCast 会让 SDK 用 0 当除数，触摸映射整个失效（点了没反应、或位置全错）。
            // 分辨率是运行时量出来的、窗口一 attach 就有，所以等一帧重试好过带着错的基准起投。
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                log("! 分辨率基准还没拿到（surface=" + measuredW + "x" + measuredH
                        + " 协商=" + videoW + "x" + videoH + "）→ 第 " + castRetry + " 次重试延后执行");
                main.postDelayed(this::tryStartCast, 500);
            } else {
                log("! 分辨率基准始终拿不到 → 放弃起播，等下一次 Surface 就绪");
            }
            return;
        }
        castRetry = 0;   // 走到这里才是「真的起投」，两条重试路径共用这份预算（见方法头注释）
        // 4 参重载带上 IAppSurfaceStateListener：SDK 会在「重挂输出面后的第一个 buffer
        // 真的被渲染」时回调 onSurfaceReady。该回调可能迟到（见 surfaceStateListener 的注释），
        // 所以只用来打日志，不用它做任何动作判断。
        boolean ok = adapter.startCast(s, dw, dh, surfaceStateListener);
        castGate.markStarted(ok);
        // 注意 ok 的语义：UCarAdapter.startCast 返回的是「任务已投递到它自己的 worker 线程」，
        // 真正的结果（CastManager.startCast && sendGotoForeground）只会在 SDK 日志里以
        // 「start cast failed.」出现。所以这里不能把 ok 当成「投屏已开始」——
        // 是否真的出画由投屏页的出画探测判定（{@link #probeSurfaceContent}）。
        log((ok ? "✓ " : "✗ ") + "startCast(" + dw + "x" + dh + ") 已投递 = " + ok
                + (ok ? "（触摸映射基准 " + dw + "x" + dh + "）" : ""));
        if (ok) {
            frameRendered = false;
            main.post(listener::onCastStarted);
        }
    }

    // ==================================================================
    // SDK 回调
    // ==================================================================

    private final ICarConnectListener connectListener = new ICarConnectListener() {
        @Override
        public void onPinCode(String pinCode, String carDeviceName) {
            lastPinCode = pinCode;
            // 降噪：SDK 每次重播都下发新配对码（空转期 30 秒一次），「码变了」不是可用的触发条件。
            // 空转期只 5 分钟例报一次；会话期内（重连换码）立即记 —— 那才影响用户输入。
            boolean quiet = !sessionActive && !negotiating &&
                    SystemClock.uptimeMillis() - lastPinCodeLogAtMs <= PINCODE_LOG_THROTTLE_MS;
            if (!quiet) {
                lastPinCodeLogAtMs = SystemClock.uptimeMillis();
                log("★ onPinCode pinCode=" + pinCode + " carDeviceName=" + carDeviceName
                        + (sessionActive ? "" : "（空转期 5 分钟例报）"));
            }
            // 新码下发 → 看门狗重新计时，保证用户拿到完整的有效期去确认，不会刚出码就被重播顶掉。
            scheduleAutoRefresh();
            main.post(() -> listener.onPinCode(pinCode, carDeviceName));
        }

        /**
         * SDK 的真实签名语义是 {@code (deviceID, state, parameter)} —— <b>不是</b>「旧状态 → 新状态」。
         *
         * <p>{@code state} 取 {@code UCarConnectState.UCAR_DEVICE_*}：
         * 1 已断开（parameter==11 = 用户主动断开）、2 已连接、<b>3 连接失败（parameter = 错误码）</b>、
         * 4 投屏已连接、5 投屏已停止、6 手机回桌面、7 手机回前台。
         *
         * <p>旧代码按「旧/新状态」解读，把 {@code 3}（= 连接失败）当成「已建立」，于是手机端一报
         * 失败，车机这边反而认为连上了：错误码被丢弃、看门狗停摆、兜底广播被关掉 —— 之后手机
         * 再也连不上，表现就是「手机端总是提示连接失败」。
         */
        @Override
        public void onConnectStateChanged(String deviceID, int state, int param) {
            lastDeviceId = deviceID;
            // 换了手机：上一台手机的最后画面绝不能给这一台当占位图。
            lastFrame.dropIfDeviceChanged(deviceID);
            log("onConnectStateChanged state=" + stateName(state) + " param=" + param + peerInfo());
            switch (state) {
                case ST_CONNECTED:
                case ST_CAST_CONNECTED:
                    // 真连上了：撤销待执行的重新广播，免得刚连上就被重播打断。
                    sessionActive = true;
                    boolean firstStateOfSession = sessionStartedAt == 0L;
                    if (firstStateOfSession) {
                        sessionStartedAt = SystemClock.uptimeMillis();
                        sessionCount++;
                        // ★ 焦点记录只在本会话的**第一条**状态回调里清一次。
                        //   0.1.11.3 把它放在外面（每条 state=4/2 都清），结果：
                        //   CAST_CONNECTED(4) 清 → 23:24:53.070 音乐 GAIN → CONNECTED(2) 又清 →
                        //   整个会话再没有焦点事件，看门狗以为「无事件」连补 6 次 allowGainAudioFocus，
                        //   日志里 21 次「音频焦点未恢复」全是误报（实测 `no focus, drop data` 为 0）。
                        audioFocusStrikes = 0;
                        musicFocus = AudioFocusPolicy.NO_EVENT;
                        musicFocusAt = 0L;
                    }
                    negotiating = false;
                    wirelessFailStreak = ReconnectBackoff.resetStreak();   // 链路活了，退避复位
                    cancelRescue();
                    main.removeCallbacks(reAdvertiseRunnable);
                    main.removeCallbacks(castStopReconsiderRunnable);
                    // ★ 会话期间必须保住 Wi-Fi 与 CPU，否则音频会被省电策略打断（见 SessionKeepAlive）。
                    keepAlive.acquire();
                    // ★ 昼夜诊断随会话起停（默认只读，不发任何包；见 DayNightProbe）。
                    dayNight.start();
                    // ★ 每次会话建立都把音频焦点的「一次性闩」重新打开。
                    //   SDK 只在「新建播放器」时打开它；重连时播放器已经存在（走的是 createPlayer 的
                    //   另一条分支），闩一旦在上次会话里用掉就不会再打开，于是手机推来的每一帧音频都被
                    //   「no focus, drop data」丢掉 —— 表现为「连上了、手机在放歌、车机没声音」。
                    //   allowGainAudioFocus() 是 SDK 自己的公开入口，幂等，重复调用只会让下一次音频帧
                    //   重新申请一次焦点。
                    audioFocusStrikes = 0;
                    try {
                        adapter.allowGainAudioFocus();
                        log("音频焦点：已允许 SDK 重新抢占（allowGainAudioFocus），"
                                + "防止重连后闩已用掉导致静音");
                    } catch (Throwable t) {
                        log("! allowGainAudioFocus 异常（继续）: " + t);
                    }
                    armAudioFocusGuard();
                    // ★ 会话心跳：每 5 秒一行会话健康度快照。断连时「最后一条心跳」就是现场，
                    //   这是没有周期性采样时唯一无法补救的信息（见 HEARTBEAT_INTERVAL_MS）。
                    startHeartbeat();
                    // 流量基线按会话重算：跨会话的字节差会把「重连空窗」算成 0KB/s 或超大值。
                    P2pTrafficProbe.resetBaseline();
                    probeP2p("会话建立");
                    logAudioDiagnostics();
                    // 会话一建立就把非音乐流的预缓冲水位抬起来：此刻导航/通知的播放器还没建，
                    // 但参数会被存进 mPlayersParams，建播放器时立刻被读取应用，所以不用等第一句播报。
                    primeBufferingFloors();
                    // ★ AP 复用（G4）：会话建立只复查不补偿 —— 能建链说明凭据已到或走了别的通道。
                    apReuse.onSessionUp();
                    // ★ SDK 在建链完成时会自己 stopAdvertise()，我们的兜底广播必须同步停 ——
                    //   会话期间还在广播，手机端会把车机当成「可连接的陌生设备」反复重扫，
                    //   廉价蓝牙/Wi-Fi 共用前端时还会互相抢时隙（掉线、卡顿的来源之一）。
                    if (fallback.isRunning()) {
                        log("会话已建立 → 停止兜底广播（SDK 侧此时也已停止广播）");
                        fallback.stop();
                    }
                    // ★ 「仅 5G」事后校验：组已由 UCar SDK 建好，此处只能读实频再决定要不要拒。
                    refreshFiveGhzOnly();
                    if (fiveGhzOnly) {
                        verifyFiveGhz();
                    }
                    break;

                case ST_CONNECT_FAILED:
                    // 失败：如实记录错误码 + 死在哪一步，然后安排重播让手机能重试。
                    log("✗✗ 连接失败：错误码 " + param + "（" + errorName(param) + "）"
                            + "；中止于阶段 " + lastPhaseName() + "；稍后自动重播供手机重试");
                    // 把 phase 时间线完整摊开：单一 phase 值会被 SDK 的复位事件污染，
                    // 只有时间线能看出「笔直走完」还是「中途回退过」。
                    logConnectFailureContext(param);
                    apReuse.onSessionDown("连接失败 code=" + param);
                    sessionActive = false;
                    sessionStartedAt = 0L;
                    stopHeartbeat();
                    castGate.reset();
                    negotiating = false;
                    lastPinCode = null;
                    cancelRescue();
                    releaseAudio();
                    keepAlive.release();
                    dayNight.stop();
                    lastFrame.invalidate("连接失败（会话未建立）");
                    if (paused) {
                        log("（已停机，不再重播）");
                        break;
                    }
                    // 无线建链类失败（P2P/热点没建成、握手无疾而终）说明射频还在回收，硬撞只会拉长
                    // 恢复时间 → 按连续次数指数退避；其它错误码与射频无关，维持固定 base 间隔。
                    wirelessFailStreak = ReconnectBackoff.streakAfterFailure(wirelessFailStreak, param);
                    boolean wireless = ReconnectBackoff.isWirelessBringupFailure(param);
                    long backoffMs = ReconnectBackoff.nextDelayMs(wireless, wirelessFailStreak);
                    if (wireless && backoffMs > RE_ADVERTISE_DELAY_MS) {
                        log("  ↳ 连续无线建链失败 " + wirelessFailStreak
                                + " 次 → 退避 " + backoffMs + "ms 后重播，等 P2P 组干净回收");
                    }
                    scheduleReAdvertiseAfter(backoffMs);
                    scheduleAutoRefresh();
                    final int code = param;
                    main.post(() -> listener.onConnectFailed(code));
                    break;

                case ST_DISCONNECTED:
                case ST_CAST_DISCONNECTED:
                    // 会话结束。分两种，判定在 LinkStatePolicy（可单测）：
                    //   · 人要求的断开（车机手动 / 手机端主动）→ 停机，绝不自动连回去；
                    //   · 异常断连（链路抖动、Wi-Fi 掉组、心跳超时…）→ 立刻重播，让手机自动连回来。
                    // 真断开回调到达，取消 state=5 的悬空兜底计时。
                    main.removeCallbacks(castStopReconsiderRunnable);
                    //
                    // ★ 为什么 5（CAST_DISCONNECTED）也在这里彻底收尾、并且可以安全地放掉保活锁：
                    //   反编译源里 `UCarAdapter.onWfdSinkStopped()` 的实现是
                    //   `if (isConnected()) mConnectProxy.disconnect(); notifyConnectStateChanged(id, 5, -1);`
                    //   —— 即「投屏停止」由 SDK 自己顺手把链路拆掉，随后 `notifyDisconnected()` 还会补一个
                    //   state=1。所以 5 之后链路不会残留：先放锁再重播不会打断一条还活着的链路，
                    //   而紧接着的 state=1 会把 scheduleReAdvertise 重新计时（removeCallbacks + post），
                    //   最终只有一次重播。
                    //   （若将来 SDK 改成「5 只停投屏、链路保留」，这里就必须按 state 分开处理。）
                    // ★ 现场快照必须在收尾**之前**抓：state=5 会顺手把链路拆掉，等它跑完，
                    //   Surface/锁/音频/蓝牙都已经是拆完的样子，问不出「断之前是什么样」。
                    //   一对回调（5 然后 1）只抓第一条 —— sessionActive 此刻仍为 true。
                    if (sessionActive) logSessionEndContext(state, param);
                    sessionActive = false;
                    sessionStartedAt = 0L;
                    stopHeartbeat();
                    castGate.reset();
                    negotiating = false;
                    lastPinCode = null;
                    cancelRescue();
                    releaseAudio();
                    keepAlive.release();
                    dayNight.stop();
                    // 会话结束 = 那台手机的画面到此为止；留着会在下次连接的第一帧之前闪出旧画面。
                    lastFrame.invalidate("会话断开");
                    // ★ AP 复用（G4）：断连即作废补偿代次，防止旧凭据推给下一台手机。
                    apReuse.onSessionDown("state=" + state);
                    // state=5 的 param 恒为 -1（SDK 只在此报「投屏停止」），真实断开原因挂在随后
                    // 到达的 DISCONNECTED(1) 上。此处先完成统一收尾，但把「重连 / 停机」的判定让给
                    // 那条带真因的回调，避免对手机端主动断开抢跑一次多余的「异常断连 → 立即重播」。
                    if (LinkStatePolicy.shouldDeferCastStop(state, param, paused)) {
                        log("投屏停止(5)：收尾完成，重连/停机判定交给随后的 DISCONNECTED(1)");
                        main.removeCallbacks(castStopReconsiderRunnable);
                        main.postDelayed(castStopReconsiderRunnable, CAST_STOP_RECONSIDER_MS);
                        break;
                    }
                    if (LinkStatePolicy.onDisconnected(param, paused) == LinkStatePolicy.Action.STAY_STOPPED) {
                        if (!paused) {
                            log("（断开原因：手机端用户主动断开 param=" + param + "）");
                            pauseLink("手机端主动断开");
                        } else {
                            log("（已停机，忽略本次断开回调）");
                        }
                        break;
                    }
                    log("★ 异常断连（param=" + param + "）→ 自动重连：立即重新广播");
                    scheduleReAdvertise();
                    scheduleAutoRefresh();
                    break;

                default:
                    // 6/7 = 手机回桌面 / 回前台，会话本身没变，不动状态机。
                    break;
            }
            final int st = state;
            main.post(() -> listener.onConnectionState(st));
        }

        @Override
        public void onConnectingProgress(String phoneId, int progress) {
            long now = SystemClock.uptimeMillis();
            if (progress > 0) {
                lastPhase = progress;
                lastPhaseAt = now;
                rememberPhase(progress, now);
            } else {
                // phase=0 是 SDK 的「本轮回退 / 收尾」标记：协商被中止或重来时补发一次。
                // 它**不覆盖** lastPhase（否则失败日志永远打「尚未开始」），只单独记一笔，
                // 让「中途回退过」这件事留在时间线上。
                rememberPhase(0, now);
            }
            log("onConnectingProgress phase=" + progress + "（" + phaseName(progress) + "）"
                    + (progress == 0 ? " ← 回退/收尾标记，不覆盖已到达的阶段" : ""));
            if (progress >= 10 && !sessionActive) {
                // 手机已经找到我们、正在握手：让看门狗避开，并启动 90 秒自救计时。
                negotiating = true;
                // ★ G8 抢跑（必须同步执行，createSoftAp 紧随其后）：覆写 ShareLinkService
                //   下发三值 + 注入 WifiApService，令 SDK 复用判定命中原生通知路径。
                apReuse.preemptOnPhoneInfo();
                // ★ AP 复用补偿窗口（路径二，G4）：手机马上会请求 AP 凭据，若 SDK 的 reuse
                //   没命中（手填热点名 ≠ 手机请求名），按 1s/2.5s/5s 三次尝试补发通知。
                apReuse.onNegotiationStart();
                armRescue();
            }
        }

        @Override
        public void onReconfigureWifiInfo(WifiOwnerConfig config) {
            log("onReconfigureWifiInfo ssid=" + (config == null ? "?" : config.getSsid()));
        }

        @Override
        public void onRethinkBluetoothConnect(boolean rethink) {
            log("onRethinkBluetoothConnect=" + rethink);
        }

        @Override
        public UCarCommon.WorkMode onSelectWorkMode(String deviceID, UCarCommon.WorkMode supported) {
            UCarCommon.WorkMode chosen;
            if (supported == null) {
                chosen = UCarCommon.WorkMode.NONE;
            } else if (supported.isSupportAppWindowCast()) {
                chosen = UCarCommon.WorkMode.APP_WINDOW_CAST;
            } else if (supported.isSupportAppFullScreenCast()) {
                chosen = UCarCommon.WorkMode.APP_FULLSCREEN_CAST;
            } else if (supported.isSupportNormalCast()) {
                chosen = UCarCommon.WorkMode.NORMAL_CAST;
            } else if (supported.isSupportDataTransfer()) {
                chosen = UCarCommon.WorkMode.DATA_TRANSFER;
            } else {
                chosen = UCarCommon.WorkMode.NONE;
            }
            log("onSelectWorkMode 手机支持=" + workModeName(supported)
                    + " → 车机选择=" + workModeName(chosen));
            return chosen;
        }
    };

    private final IPhoneDataListener phoneDataListener = new IPhoneDataListener() {
        @Override
        public void onNavigationInfoReceived(String phoneId, UCarCommon.NavigationInfo info) {
            listener.onNavigation(info);
        }

        @Override public void onMusicInfoReceived(String phoneId, UCarCommon.MusicInfo info) {
            // 两个用途：
            //  1. 把「播放/暂停」这一颗按键翻成 PLAY 或 PAUSE（见 MediaKeyMap）；
            //  2. 音频看门狗拿它当「现在应该有声音」的唯一信号（见 AudioFocusPolicy）。
            if (info != null) {
                boolean playing = info.isPlaying();
                boolean wasPlaying = musicPlaying;
                if (!playing) audioFocusStrikes = 0;   // 停了就重新给满额度
                if (playing != wasPlaying) {
                    log("音乐状态：" + (playing ? "播放中" : "已停止/暂停")
                            + (playing ? "（若此时没声音，看门狗会补发焦点申请）" : ""));
                }
                musicPlaying = playing;
                // 额度用尽后看门狗会停摆（避免空转），这里在「重新开始放歌」时把它叫醒。
                if (playing && !wasPlaying) armAudioFocusGuard();
            }
        }
        @Override public void onCallInfoReceived(String phoneId, UCarCommon.CallInfo info) { }
        @Override public void onPhoneStateInfoReceived(String phoneId, UCarCommon.PhoneStateInfo info) { }
        @Override public void onPOIReceived(String phoneId, UCarCommon.POIAddress poi) { }
        @Override
        public void onAudioFocusChanged(UCarCommon.AudioType type, int focus) {
            // SDK 的焦点状态机走这里。它自己只会「申请一次」，失败或永久丢失之后不再重试
            // （一次性闩，见 AudioFocusPolicy），所以必须由我们把事件记下来、在合适的时候补。
            boolean isMusic = type != null
                    && type.getValue() == UCarCommon.AudioType.STREAM_CAST_MUSIC.getValue();
            if (isMusic) {
                musicFocus = focus;
                musicFocusAt = SystemClock.uptimeMillis();
                audioFocusStrikes = AudioFocusPolicy.strikesAfterFocusEvent(audioFocusStrikes, focus);
            }
            // 降噪：TTS/通知流每次播报都走 PRE_REQUEST(0)→GAIN(1)→ABANDONED(-5) 一整轮，
            // 10 分钟实测 306 行（每 7 秒一轮 ×3 行）。PRE_REQUEST 是「准备申请」的中间态、
            // 没有任何判读价值，丢掉；GAIN/ABANDONED 保留（播报起止是断音判据）。
            // 音乐流的每一次变化都保留 —— 它是看门狗与「手机侧暂停」判定的核心。
            if (isMusic || focus != 0) {
                log("音频焦点 type=" + type + " focus=" + focus + "（" + audioFocusName(focus) + "）"
                        + (isMusic ? " ← 音乐流" : ""));
            }
            if (isMusic && AudioFocusPolicy.isNotHolding(focus)) {
                log("（音乐流当前没有焦点：系统不会再回调；手机若仍在放歌，看门狗会补发 allowGainAudioFocus）");
            }
            // PRE_REQUEST(0) 是 SDK 真的去申请焦点的那一刻，也就是一句话对应的播放器刚刚存在。
            // 在这里补一次水位：万一手机中途下发过更小的值把它盖回去，下一句就又被抬起来。
            // 表里没有的音源（音乐、通话）在 applyBufferingFloor 里直接返回，不会被碰。
            if (focus == 0) applyBufferingFloor(type);
        }

        @Override
        @SuppressWarnings("rawtypes")
        public void onAppListStateChanged(String phoneId, List list, UCarCommon.AppListState state) { }

        @Override
        public void onAppStateChanged(String phoneId, int state, boolean flag,
                                      int a, int b, UCarCommon.AppState appState) { }

        @Override
        public UCarCommon.AudioAttributes getAudioAttributesByType(UCarCommon.AudioType type) {
            // 焦点身份按音源拆开（修断音），播放身份与之一致（让车机把它认成导航那条通道）——
            // 取值表、为什么播放侧也要一致、以及为什么通话仍交回 SDK，都记在
            // AudioFocusAttributionPolicy 里。这里只做两件事：读运行时覆盖、翻译成 SDK 的四参构造。
            if (type == null) return null;
            // android.media.AudioAttributes 是 API 21 才有的类；老设备上保持既有行为（交回 SDK 默认）。
            if (android.os.Build.VERSION.SDK_INT < 21) return null;
            int t = type.getValue();
            AudioFocusAttributionPolicy.Spec spec =
                    AudioFocusAttributionPolicy.specFor(t, guidanceUsageOverride());
            if (spec == null) return null;
            try {
                android.media.AudioAttributes playback = new android.media.AudioAttributes.Builder()
                        .setUsage(spec.playbackUsage)
                        .setContentType(spec.playbackContentType)
                        .build();
                android.media.AudioAttributes focus = new android.media.AudioAttributes.Builder()
                        .setUsage(spec.focusUsage)
                        .setContentType(spec.focusContentType)
                        .build();
                // 上车扫通道映射时，这一行就是「这条流当时到底以什么身份开出去的」的唯一凭据；
                // INFO 级是硬要求 —— DiAuto 报告只收 INFO 以上。
                log("音源身份 " + type + "：焦点(" + spec.focusGain + "," + spec.focusUsage + ","
                        + spec.focusContentType + ") 键=" + AudioFocusAttributionPolicy.focusKey(spec)
                        + "，播放(" + spec.playbackUsage + "," + spec.playbackContentType + ")");
                return new UCarCommon.AudioAttributes(
                        playback, focus, spec.focusGain, spec.streamType);
            } catch (Throwable t2) {
                // 建不出来就退回 null = 本改动之前的行为，别让一个音频属性异常把整条会话带下去。
                log("! 构造音频属性失败（退回 SDK 默认）: " + t2);
                return null;
            }
        }
    };

    // ==================================================================
    // 配置构造（SDK 硬校验，越界即抛）
    // ==================================================================

    private UCarConfig buildConfig() {
        byte[] carId = generateCarId();
        if (carId == null || carId.length != 6) {
            carId = new byte[6];
            new Random().nextBytes(carId);
            log("! carId 长度异常，强制随机 6 字节");
        }
        carIdBytes = carId;   // 兜底广播要与 SDK 用同一个 carId
        // DPI 口径必须对上：setDpi 报<b>逻辑</b>密度（手机端做 UI 缩放用），setRealDpi 报
        // <b>物理</b>密度（每英寸真实像素数）。以前是反的 —— dpi 写死 320、realDpi 却传了
        // 逻辑密度 densityDpi —— 手机端据此算出的缩放系数与物理像素对不上，画面要么被拉伸
        // 要么偏小。两者都从 DeviceDisplay 运行时取，取不到就如实记一条日志。
        int logicalDpi = DeviceDisplay.logicalDpi(appCtx);
        int physicalDpi = DeviceDisplay.physicalDpi(appCtx);
        if (logicalDpi <= 0 || physicalDpi <= 0) {
            log("! 屏幕密度未取到（logical=" + logicalDpi + " physical=" + physicalDpi
                    + "）→ 按下限 120 上报，画面缩放可能与实机不符");
        }
        int dpi = clamp(logicalDpi, 120, 640);
        int realDpi = clamp(physicalDpi, 120, 640);
        int fps = castFps();
        int sw = Math.max(1, screenW), sh = Math.max(1, screenH);
        int vw = Math.max(1, videoW), vh = Math.max(1, videoH);

        UCarCommon.WorkMode workMode = new UCarCommon.WorkMode.Builder()
                .setSupportNormalCast(true)
                .setSupportAppFullScreenCast(false)
                .setSupportAppWindowCast(false)
                .build();

        String transport = transportMode();
        // AP（车机热点）与 Wi-Fi P2P 互斥 —— 硬约束，两个 flag 恰好一真一假：
        // 设置界面选了「车载热点」（transport=ap）→ 只开 SoftAP、P2P 必须关（实车实测热点
        // 占用射频后 P2P 组建不起来，见 DiAuto-report-20261024-194338 的 10003 瞬败）；
        // 其余情况只开 P2P。历史遗留的「both」在 transportMode() 里已收敛，这里不可能出现两者同开。
        boolean supportSoftAp = "ap".equals(transport);
        boolean supportP2p = !supportSoftAp;
        log("建链方式：transport=" + transport + "（supportP2P=" + supportP2p
                + " supportSoftAP=" + supportSoftAp + "）");

        // G8b：AP 复用开启时经官方 API 声明车机热点凭据 —— UCarConnectProxy.open() 会把
        // carConfig.getWifiSsid()/getWifiPassword() 传给 ShareLinkService.open()，从而
        // createSoftAp 的 actualSsid = 手填值，复用判定得以命中（反编译 :556/:868/:736）。
        // setter 对非法值会抛 IllegalArgumentException，先用与 SDK 同口径的策略层预校验。
        UCarConfig.Builder cfgBuilder = new UCarConfig.Builder()
                .setCarBrMac(carId)                  // 必须恰好 6 字节
                .setDpi(dpi)                         // 120..640
                .setRealDpi(realDpi)                 // 120..640
                .setFps(fps)                         // 15..60
                .setScreenWidth(sw)
                .setScreenHeight(sh)
                .setVideoDisplayWidth(vw)
                .setVideoDisplayHeight(vh)
                .setSupportP2P(supportP2p)
                .setSupportSoftAP(supportSoftAp)
                .setSupportCamera(false)
                .setSupportHevc(false)
                .setSupportMic(true)
                .setUseCustomAudioRecord(false)
                .setSupportStereoRecord(false)
                .setSupportLowLatencyDecodingMode(false)
                .setSupportPOINav(false)
                .setCcdFilePath(ensureCcdFile())
                .setSupportedWorkModes(workMode);
        String apSsid = ApReusePrefs.ssid(appCtx);
        String apPwd = ApReusePrefs.password(appCtx);
        if (ApReusePrefs.flag(appCtx) && "ap".equals(transport)
                && ApReusePolicy.isValidSsid(apSsid) && ApReusePolicy.isValidPassphrase(apPwd)) {
            cfgBuilder = cfgBuilder.setWifiSsid(apSsid).setWifiPassword(apPwd);
            log("UCarConfig：声明车机热点凭据（ssid=" + ApReusePrefs.mask(apSsid) + " pwdLen="
                    + (apPwd == null ? 0 : apPwd.length()) + "）—— official API 通道");
        }
        return cfgBuilder.build();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * The SDK's CarVerifier expects a readable file path, not an asset name. MiAuto ships the
     * CCD as an asset, so materialise it into files/ once and hand the SDK that absolute path.
     * Without this the log says "ccd file path is invalid" and the verifier falls back to its
     * built-in default, which breaks the CarWith handshake on phones that validate the car.
     */
    private String ensureCcdFile() {
        try {
            File out = new File(appCtx.getFilesDir(), "ccd.data");
            if (!out.exists() || out.length() == 0) {
                try (InputStream in = appCtx.getAssets().open("ccd.data");
                     FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) os.write(buffer, 0, read);
                }
            }
            log("ccd.data → " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
            return out.getAbsolutePath();
        } catch (Throwable t) {
            log("! ccd.data 复制失败，回退相对路径: " + t);
            return "ccd.data";
        }
    }

    /** 建链方式偏好的存储位置（键 {@code transport}，取值 p2p / ap / both）。 */
    private static final String PREF_FILE = "mi_auto_lowend";
    private static final String PREF_TRANSPORT = "transport";
    /** 投屏帧率偏好（键 {@code cast_fps}，取值 15..60）。 */
    private static final String PREF_CAST_FPS = "cast_fps";

    /**
     * 投屏帧率：默认 **30**，可写偏好覆盖（15..60）。
     *
     * <p><b>为什么从 60 降到 30</b>：这条链路里音频和视频共用同一块 Wi-Fi 带宽和同一个 SoC，
     * 60fps 的 H.264 解码在低端车机上会把 CPU 吃到接近满载 —— 解码、合成、音频播放三个线程争
     * 同样的核，音频线程一旦错过 AudioTrack 的写入窗口就是一次可听的断续；同时 60fps 的码率也
     * 更容易在 P2P 链路上打出瞬时拥塞，反过来拖累音频 RTP 包（表现为「卡顿 + 偶发掉线」）。
     * 30fps 对车机 UI（导航、列表、音乐）完全够用，换来的是稳定的 CPU 余量与带宽余量。
     *
     * <p>确实需要 60 的车机（性能好）可以用 adb 改回来，无需重编译：
     * <pre>
     * adb shell run-as com.miauto.app sh -c 'mkdir -p /data/data/com.miauto.app/shared_prefs'
     * printf '%s' '&lt;map&gt;&lt;int name="cast_fps" value="60" /&gt;&lt;/map&gt;' \
     *   | adb shell run-as com.miauto.app sh -c 'cat &gt; /data/data/com.miauto.app/shared_prefs/mi_auto_lowend.xml'
     * adb shell am force-stop com.miauto.app
     * </pre>
     */
    private int castFps() {
        int fps = 30;
        try {
            fps = appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getInt(PREF_CAST_FPS, 30);
        } catch (Throwable t) {
            log("! 读 cast_fps 失败，按 30 处理: " + t);
        }
        fps = clamp(fps, 15, 60);
        // 30 是刻意选择，不是默认值漏改 —— 日志里写清楚，排查时不会被误当成 bug。
        log("投屏帧率 = " + fps + "fps" + (fps == 30 ? "（低端车机默认值，减少音频线程饥饿与带宽拥塞）" : "（偏好覆盖）"));
        return fps;
    }

    /**
     * 建链方式：{@code p2p}（默认）/ {@code ap}。
     *
     * <p><b>AP 与 P2P 互斥</b>（2026-10-24 用户决策，实车 10003 瞬败定位后的方案 A）：设置界面
     * 选了「车载热点」→ transport=ap，只开 SoftAP、P2P 必须关；选 Wi-Fi Direct 或默认 → p2p。
     * 不存在两者同开的形态。
     *
     * <p><b>默认只用 Wi-Fi P2P</b>：SDK 的 SoftAP 分支需要热点凭据（设置界面「编辑热点」保存，
     * 同步写 {@link ApReusePrefs}），没配凭据时热点链路无法工作；P2P 组网普通应用即可完成，
     * 兼容性最好。
     *
     * <p>历史遗留值 {@code both} 在此收敛：有有效热点凭据 → 按 ap，否则回 p2p ——
     * 「both」会让 SDK 把两个 flag 同开，与本互斥约束冲突。
     */
    private String transportMode() {
        String v;
        try {
            v = appCtx.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                    .getString(PREF_TRANSPORT, "p2p");
        } catch (Throwable ignored) {
            return "p2p";
        }
        if (!"ap".equals(v) && !"both".equals(v) && !"p2p".equals(v)) {
            log("! transport 取值非法（" + v + "），按 p2p 处理");
            return "p2p";
        }
        if ("both".equals(v)) {
            boolean credsOk = ApReusePolicy.isValidSsid(ApReusePrefs.ssid(appCtx))
                    && ApReusePolicy.isValidPassphrase(ApReusePrefs.password(appCtx));
            v = credsOk ? "ap" : "p2p";
            log("transport=both（历史遗留）→ AP/P2P 互斥，按 " + v + " 收敛");
        }
        return v;
    }

    /**
     * 当前建链方式是否为「车载热点」（transport 收敛后 == ap）。
     *
     * <p>启动门禁要在 {@link #start()}（SDK init + 蓝牙广播）**之前**判定模式，所以单独开一个
     * 只读入口 —— 门禁若自己去读偏好键，就会和 {@link #transportMode()} 形成两份收敛逻辑
     * （`both` 的凭据判定），早晚漂移。这里直接复用同一个方法，口径永远一致。
     *
     * <p>不触发任何 SDK 调用，未初始化时也可安全调用。
     */
    public boolean isApTransport() {
        return "ap".equals(transportMode());
    }

    private byte[] generateCarId() {
        SharedPreferences prefs = appCtx.getSharedPreferences("car_id_prefs", Context.MODE_PRIVATE);
        String saved = prefs.getString("car_id_key", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                byte[] cached = Base64.decode(saved, Base64.NO_WRAP);
                if (cached != null && cached.length == 6) return cached;
                log("carId 缓存长度≠6，作废重生成");
            } catch (Throwable t) {
                log("carId 缓存解码失败: " + t);
            }
        }
        byte[] raw = getBluetoothMac();
        if (raw == null || raw.length != 6) {
            raw = new byte[6];
            new Random().nextBytes(raw);
        }
        prefs.edit().putString("car_id_key", Base64.encodeToString(raw, Base64.NO_WRAP)).apply();
        return raw;
    }

    @android.annotation.SuppressLint("MissingPermission")
    private byte[] getBluetoothMac() {
        byte[] macBytes = new byte[6];
        try {
            android.bluetooth.BluetoothManager bm =
                    (android.bluetooth.BluetoothManager) appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter a = (bm == null) ? null : bm.getAdapter();
            String mac = (a == null) ? null : a.getAddress();
            if (mac != null) {
                String[] seg = mac.split(":");
                if (seg.length == 6) {
                    for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(seg[i], 16);
                    return macBytes;
                }
            }
        } catch (Throwable t) {
            log("读蓝牙 MAC 被拒（缺 BLUETOOTH_CONNECT？）: " + t);
        }
        new Random().nextBytes(macBytes);
        return macBytes;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        try { listener.onLog(msg); } catch (Throwable ignored) { }
    }

    private void safe(String where, Runnable r) {
        try { r.run(); } catch (Throwable t) { log("!!! " + where + " 异常: " + t); }
    }

    /** SDK 连接状态码 → 名称。 */
    private static String stateName(int s) {
        switch (s) {
            case ST_DISCONNECTED: return "DISCONNECTED(1)";
            case ST_CONNECTED: return "CONNECTED(2)";
            case ST_CONNECT_FAILED: return "CONNECT_FAILED(3)";
            case ST_CAST_CONNECTED: return "CAST_CONNECTED(4)";
            case ST_CAST_DISCONNECTED: return "CAST_DISCONNECTED(5)";
            case 6: return "PHONE_GO_HOME(6)";
            case 7: return "PHONE_BACK_FOREGROUND(7)";
            default: return "STATE_" + s;
        }
    }

    /** 错误码 → 人话（UCarConnectState.ErrorCode）。这是判断「为什么连不上」的关键信息。 */
    private static String errorName(int code) {
        switch (code) {
            case 10001: return "SDK 初始化失败 ERROR_INIT_FAILED";
            case 10002: return "参数非法 ERROR_INVALID_PARAMETER";
            case 10003: return "无线建链失败 ERROR_WIRELESS_CONNECT_FAILED —— Wi-Fi P2P/热点没建成或手机没连上";
            case 10004: return "USB 连接失败 ERROR_USB_CONNECT_FAILED";
            case 10005: return "启动投屏失败 ERROR_START_CAST_FAILED —— 车机侧 startCast 失败（Surface/编解码）";
            case 10006: return "建链超时 ERROR_CONNECT_TIMEOUT —— 鉴权通过后 10 秒内没建好链路";
            case 10007: return "用户确认超时 ERROR_USER_INTERVENTION_TIMEOUT —— 手机端迟迟没确认";
            case 10008: return "连接内部错误 ERROR_CONNECT_INNER_FAILED";
            default: return "未知错误码";
        }
    }

    /**
     * 连接阶段（SDK 的 {@code onConnectingProgress} 给的是 phase，取值 10..100）→ 人话。
     * 搞清「死在哪一阶段」比错误码更直观（映射见 SDK 的 UCarAdapter.sProgressMap）。
     */
    private static String phaseName(int phase) {
        switch (phase) {
            case 10: return "收到手机信息 → 开始建 Wi-Fi";
            case 20: return "Wi-Fi 直连组/热点已建立（或 USB 已就绪）";
            case 30: return "服务端地址已下发";
            case 40: return "Wi-Fi 已连上手机（或 USB 网络就绪）";
            case 50: return "收到手机密钥协商（校验配对码）";
            case 60: return "鉴权通过";
            case 70: return "收到手机 IP 与工作模式";
            case 80: return "数据通道已建立";
            case 90: return "投屏会话已就绪（WFD sink ready）";
            case 100: return "连接完成";
            default: return "未知阶段";
        }
    }

    private String lastPhaseName() {
        int p = lastPhase;
        return p <= 0 ? "尚未开始（手机还没发来任何进度）" : ("phase " + p + " " + phaseName(p));
    }

    // ==================================================================
    // 会话现场快照（断连 / 失败 / 心跳共用）
    //
    // 全部是「失败之后才写」的独立小节：只是把已经发生的事说清楚，绝不改变任何状态机行为，
    // 所以任何一段抛异常都只多一行日志。
    // ==================================================================

    /** 记一个 phase 到时间线（含 0），并裁到 {@link #PHASE_HISTORY_MAX} 条。 */
    private void rememberPhase(int phase, long at) {
        synchronized (phaseHistory) {
            phaseHistory.addLast(new int[]{phase, (int) (at & 0x7FFFFFFF)});
            while (phaseHistory.size() > PHASE_HISTORY_MAX) phaseHistory.removeFirst();
        }
    }

    /** 时间线 → `10→20→30→50→0→10→30`。回退点肉眼可见。 */
    private String phaseHistoryString() {
        StringBuilder sb = new StringBuilder();
        synchronized (phaseHistory) {
            for (int[] e : phaseHistory) {
                if (sb.length() > 0) sb.append('→');
                sb.append(e[0]);
            }
        }
        return sb.length() == 0 ? "（无）" : sb.toString();
    }

    private static String fmtSecs(long ms) {
        if (ms <= 0) return "-";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        return (s / 60) + "m" + (s % 60) + "s";
    }

    /** 「多久之前」：值为 0 时给 `-`，避免出现「距离上次 1792204209s」这种不可能的数。 */
    private String fmtAgo(long uptimeMs) {
        if (uptimeMs <= 0) return "无记录";
        return fmtSecs(SystemClock.uptimeMillis() - uptimeMs) + "前";
    }

    /**
     * 投屏页此刻是否真的在前台（<b>只看 Activity 自己的 onResume/onPause</b>）。
     *
     * <p>不能用 {@code ProjectionVisibility.isOnScreen()}：那个方法的定义是
     * {@code IcocaProjectionActivity.isForeground || bridge.isSessionConnected}，
     * 而会话期间第二项恒为真 —— 于是它永远返回「前台」。
     * 0.1.11.2-debug 第一轮的报表里就出现过 {@code 页面=前台 Surface=null} 这种自相矛盾的行，
     * 而这个字段恰恰是用来判断「页面隐藏导致的暂停/断连」的，恒真等于没有观测。
     */
    private String pageState() {
        try {
            // 属性名以 is 开头，Kotlin 生成的 getter 就是 isForeground()（不是 getIsForeground），
            // 而且它在 companion 上、外层只有 private static 字段 —— 只能这样访问。
            return com.miauto.app.IcocaHost.isForeground()
                    ? "前台" : "后台";
        } catch (Throwable t) {
            return "?";
        }
    }

    private String surfaceState() {
        Surface s = surface;
        if (s == null) return "null";
        try {
            return s.isValid() ? "valid" : "invalid";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 音频侧一行摘要：断音判读主要看 mode（是否卡在通话路由）+ 媒体音量是否被压成 0。 */
    private String audioSnapshot() {
        try {
            AudioManager am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return "音频服务不可用";
            int vol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            boolean muted = android.os.Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC);
            return "mode=" + audioModeName(am.getMode())
                    + " 在响=" + am.isMusicActive()
                    + " vol=" + vol + "/" + max + (muted ? "（静音）" : "");
        } catch (Throwable t) {
            return "读取失败(" + t.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 蓝牙侧一行摘要。
     *
     * <p>三次异常断连后 0.5–1.7s 都紧跟一条 `BT Device connected` —— 说明断连那一刻 BT 是断的。
     * 但 {@code AutoStartReceiver} 只记 ACL_CONNECTED，掉线时刻完全没有记录，于是「BT 抖动」
     * 这个头号嫌疑始终无法证实/排除。这里在断连现场补一次采样，配合那边的掉线日志闭环。
     */
    private String btSnapshot() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return "无蓝牙适配器";
            int state = a.getState();
            int a2dp = -1;
            int hfp = -1;
            try { a2dp = a.getProfileConnectionState(BluetoothProfile.A2DP); } catch (Throwable ignored) { }
            try { hfp = a.getProfileConnectionState(BluetoothProfile.HEADSET); } catch (Throwable ignored) { }
            int bonded = 0;
            int connected = -1;
            try {
                Set<BluetoothDevice> set = a.getBondedDevices();
                if (set != null) bonded = set.size();
            } catch (Throwable ignored) { }
            try {
                // 不用 BluetoothDevice.isConnected()（@hide，编译期就取不到）；
                // BluetoothManager.getConnectedDevices() 是 API 18 起的公开接口，不需要 profile 代理。
                android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager)
                        appCtx.getSystemService(Context.BLUETOOTH_SERVICE);
                if (bm != null) {
                    List<BluetoothDevice> list = bm.getConnectedDevices(BluetoothProfile.GATT);
                    connected = list == null ? 0 : list.size();
                }
            } catch (Throwable ignored) { }
            return "adapter=" + btStateName(state)
                    + " A2DP=" + profileStateName(a2dp)
                    + " HFP=" + profileStateName(hfp)
                    + " 已配对=" + bonded + " 已连接=" + (connected < 0 ? "?" : connected);
        } catch (Throwable t) {
            return "读取失败(" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String btStateName(int s) {
        switch (s) {
            case BluetoothAdapter.STATE_OFF: return "OFF";
            case BluetoothAdapter.STATE_TURNING_ON: return "TURNING_ON";
            case BluetoothAdapter.STATE_ON: return "ON";
            case BluetoothAdapter.STATE_TURNING_OFF: return "TURNING_OFF";
            default: return "STATE_" + s;
        }
    }

    private static String profileStateName(int s) {
        switch (s) {
            case BluetoothProfile.STATE_CONNECTED: return "CONNECTED";
            case BluetoothProfile.STATE_CONNECTING: return "CONNECTING";
            case BluetoothProfile.STATE_DISCONNECTED: return "DISCONNECTED";
            case BluetoothProfile.STATE_DISCONNECTING: return "DISCONNECTING";
            case -1: return "?";
            default: return "STATE_" + s;
        }
    }

    /** 断开原因码 → 人话。SDK 只在 11 给了明确语义，其余一律「未报告」。 */
    private static String disconnectReasonName(int state, int param) {
        if (param == LinkStatePolicy.DISCONNECT_BY_USER) {
            return "DISCONNECT_BY_USER：对端用户主动断开";
        }
        if (param == -1) {
            return state == ST_CAST_DISCONNECTED
                    ? "SDK 未给原因（CAST_DISCONNECTED 的 param 恒为 -1，真因挂在随后那条 DISCONNECTED 上）"
                    : "SDK 未给原因（异常断连：链路抖动/掉组/心跳超时都走这里）";
        }
        return "未登记的原因码 " + param;
    }

    /**
     * 定位链路快照，用于给断连打「行驶 / 静止」标签，并回答「速度这个值到底可不可信」。
     *
     * <p>为什么需要：车上实测「**只有行驶中才容易异常断开，停车时几乎打不到**」。
     * 这句话把嫌疑范围压到了与「运动」相关的物理因素上（射频环境变化、车机电源/振动、手机在车上的位置与遮挡），
     * 但也可能只是「行驶时才有音乐/导航/长时长」这类混淆变量。心跳带上速度，下一次报表就能直接对齐：
     * 断连那一刻是 0km/h 还是 60km/h。
     *
     * <p>为什么不再只用 {@code LocationHolder}：它只保留「最新的一个位置」，而报表里
     * <b>没有车速字段</b>这件事本身有三种完全不同的解释 —— ①根本没权限/没开定位开关（没订阅）；
     * ②订阅了但 GPS 一直没锁定（实测首次锁定要 287s）；③锁定了但这次回调不带速度。
     * 原实现三种情况都返回 {@code null} 整段略去，于是「略去」无法区分原因。
     * 现在改写 {@link com.andrerinas.openheadunit.location.GpsStatus} 的快照，三种形态用不同前缀区分：
     * {@code 未订阅（权限=false 开关=true）} / {@code 订阅中 未锁定（已等 287s，星=0/0）} /
     * {@code 锁定 3s前 provider=gps 星=9/12 车速=45km/h acc=±9m}。
     *
     * @return 单行快照；读取失败时返回 {@code "?"}，绝不返回 null（缺字段比不打印更难排查）
     */
    private String gpsSnapshot() {
        try {
            return com.andrerinas.openheadunit.location.GpsStatus.snapshot();
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 心跳单行：会话健康度的最小闭环。断连时「最后一条心跳」就是现场。 */
    private String heartbeatLine() {
        long now = SystemClock.uptimeMillis();
        // 数据面是否还在流动：组还在、锁还在、状态都好的情况下，SDK 仍可能因为数据面停摆而收尸。
        // 读不到就整段略去，绝不写成 0KB/s（那会被误读成「流量归零」）。
        String traffic = null;
        try {
            traffic = P2pTrafficProbe.rateLine();
        } catch (Throwable ignored) { }
        return "❤ 心跳 " + fmtSecs(sessionStartedAt == 0 ? 0 : now - sessionStartedAt)
                + " | " + audioSnapshot()
                + " | 音乐=" + (musicPlaying ? "播放" : "停")
                + " 焦点=" + audioFocusName(musicFocus) + "(" + fmtAgo(musicFocusAt) + ")"
                + " 补发=" + audioFocusStrikes + "/" + AudioFocusPolicy.MAX_STRIKES
                + " | 页面=" + pageState()
                + " videoPaused=" + videoPaused
                + " Surface=" + surfaceState()
                + " | P2P=" + p2pSnapshot
                + (traffic == null ? "" : " | 流量=" + traffic)
                + " | GPS=" + gpsSnapshot()
                + " | " + btSnapshot();
    }

    /**
     * 断连现场。
     *
     * <p>只在「一对断连回调里的第一条」调用（{@code sessionActive} 仍为 true 时），
     * 因为 SDK 一次断开会连发 state=5 与 state=1 两条，第二条到达时现场已经被拆掉了。
     */
    private void logSessionEndContext(int state, int param) {
        long now = SystemClock.uptimeMillis();
        long duration = sessionStartedAt == 0 ? 0 : now - sessionStartedAt;
        long sinceLast = lastDisconnectAt == 0 ? 0 : now - lastDisconnectAt;
        disconnectCount++;
        log("════ 会话结束现场 #" + disconnectCount + "（本进程第 " + sessionCount + " 次会话）════");
        log("  回调：state=" + stateName(state) + " param=" + param
                + "（" + disconnectReasonName(state, param) + "）"
                + " | 本会话存活 " + fmtSecs(duration)
                + " | 距上次断连 " + (sinceLast == 0 ? "本次首断" : fmtSecs(sinceLast)));
        log("  阶段：最后非零 " + lastPhaseName() + "（" + fmtAgo(lastPhaseAt) + "）"
                + " | 时间线 " + phaseHistoryString());
        log("  投屏：页面=" + pageState()
                + " videoPaused=" + videoPaused
                + " Surface=" + surfaceState()
                + " 首帧回调=" + frameRendered
                + " 投屏状态=" + (castGate.isStarted() ? "started" : "stopped"));
        log("  音频：" + audioSnapshot()
                + " | 音乐=" + (musicPlaying ? "播放中" : "已停止/暂停")
                + " 焦点=" + audioFocusName(musicFocus) + "(" + fmtAgo(musicFocusAt) + ")"
                + " 补发=" + audioFocusStrikes + "/" + AudioFocusPolicy.MAX_STRIKES);
        log("  蓝牙：" + btSnapshot());
        log("  保活：Wi-Fi锁=" + keepAlive.isWifiHeld() + " CPU锁=" + keepAlive.isCpuHeld());
        log("  定位（断连时）：GPS=" + gpsSnapshot());
        try {
            String traffic = P2pTrafficProbe.rateLine();
            if (traffic != null) {
                log("  数据面（最后 ≤5s 均值）：" + traffic
                        + (traffic.contains("rx=0KB/s") ? " ← 断连前数据面已停摆" : ""));
            }
        } catch (Throwable ignored) { }
        lastDisconnectAt = now;
        probeP2p("断连现场");
    }

    /** 连接失败现场：把 phase 时间线完整摊开，这是「死在哪一步」的唯一可信来源。 */
    private void logConnectFailureContext(int param) {
        log("════ 连接失败现场（本进程第 " + (disconnectCount + 1) + " 次失败/断开）════");
        log("  错误码 " + param + "（" + errorName(param) + "）"
                + " | 最后非零 " + lastPhaseName() + "（" + fmtAgo(lastPhaseAt) + "）");
        log("  阶段时间线 " + phaseHistoryString()
                + "（中途出现 0 或重复 10 说明协商被中止后重来过）");
        log("  音频：" + audioSnapshot() + " | 蓝牙：" + btSnapshot()
                + " | 页面=" + pageState());
        probeP2p("失败现场");
    }

    /** 工作模式可读名（避免只打出一个对象哈希）。 */
    private static String workModeName(UCarCommon.WorkMode m) {
        if (m == null) return "null";
        StringBuilder sb = new StringBuilder();
        if (m.isSupportNormalCast()) sb.append("NORMAL_CAST ");
        if (m.isSupportAppFullScreenCast()) sb.append("APP_FULLSCREEN_CAST ");
        if (m.isSupportAppWindowCast()) sb.append("APP_WINDOW_CAST ");
        if (m.isSupportDataTransfer()) sb.append("DATA_TRANSFER ");
        return sb.length() == 0 ? ("无可用模式(" + m.toInt() + ")") : sb.toString().trim();
    }

    /** 失败时附带对端与链路信息：通道类型（P2P/SoftAP/USB）+ 当前握手阶段。 */
    private String peerInfo() {
        if (adapter == null) return "";
        try {
            String dev = lastDeviceId == null ? "" : lastDeviceId;
            return " / 通道=" + connectTypeName(adapter.getConnectType(dev))
                    + " / phase=" + adapter.getCurrentConnectPhase();
        } catch (Throwable t) {
            return "";
        }
    }

    /** UCarConnectState.ConnectType：1 Wi-Fi P2P / 2 USB / 3 SoftAP。 */
    private static String connectTypeName(int type) {
        switch (type) {
            case 1: return "Wi-Fi P2P(1)";
            case 2: return "USB(2)";
            case 3: return "SoftAP(3)";
            default: return "未知(" + type + ")";
        }
    }
}
