package com.andrerinas.openheadunit.connection

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import com.andrerinas.openheadunit.utils.AppLog
import com.miauto.app.HotspotState
import com.miauto.app.IccoaManager
import com.ucar.vehiclesdk.UCarCommon
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Bridges the MiAuto ICCOA CarLink SDK (a plain Java facade, [IccoaManager]) into DiAuto's
 * reactive architecture.
 *
 * DiAuto drives every consumer from a single `StateFlow` (see `CommManager.connectionState`);
 * the ICCOA SDK instead pushes callbacks on worker threads. This class is the adapter between
 * the two: it implements [IccoaManager.Listener], folds the raw callbacks into one observable
 * [state], and exposes the navigation stream the BYD cluster / HUD outputs consume.
 *
 * MiAuto is ICCOA-only — there is no Android Auto path at runtime — so this bridge (not
 * `CommManager`) is the source of truth for both the home screen and the projection activity.
 */
class IcocaBridge(context: Context) : IccoaManager.Listener {

    /** Coarse connection phase, mapped from the SDK's numeric state. */
    enum class Phase {
        /** Not started, or stopped by the user. */
        Idle,

        /**
         * The user (here or on the phone) ended the session on purpose: advertising is stopped and
         * nothing reconnects on its own until the home screen's "Connect" button is pressed.
         *
         * Distinct from [Idle], which means "not started yet" — the standby screen shows a Connect
         * action here, whereas Idle is the pre-first-start state.
         */
        Disconnected,

        /** BLE advertising; waiting for a phone to discover us. */
        Advertising,

        /**
         * 车载热点模式下热点未开启 —— 启动门禁把广播**停在原地**，等用户去系统设置打开热点。
         *
         * 与 [Idle] 的区别在语义：Idle 是「还没开始」，这里是「本该开始但被前置条件挡住」。
         * 主屏据此显示「等待开启车载热点」而不是「就绪」—— 后者会让司机以为车机已经在广播，
         * 反复在手机上找这台车却找不到。热点打开、门禁放行后立即转到 [Advertising]。
         */
        WaitingForHotspot,

        /**
         * 车载热点模式下热点已就绪，但**还没起链** —— 等用户点「连接」。
         *
         * 为什么不自动起链：一旦起链，SDK 就会去接管热点（`createSoftAp` 判定不可复用时走
         * recreate，先 `stopTethering` 把用户刚打开的热点关掉）。热点开关的所有权交还用户，
         * MiAuto 只在其上建链，绝不替用户开/关。
         */
        HotspotReady,

        /** Phone found us and is pairing / negotiating (PIN exchanged, Wi-Fi or USB negotiated). */
        Pairing,

        /** Session established; the phone may now push video. */
        Connected,
    }

    data class State(
        val phase: Phase = Phase.Idle,
        /** Pairing code to show on screen, if the SDK handed one out. */
        val pinCode: String? = null,
        val carDeviceName: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _navigation = MutableStateFlow<UCarCommon.NavigationInfo?>(null)

    /** Latest navigation snapshot from the phone (ICCOA `NotifyNavigationInfo`). */
    val navigation: StateFlow<UCarCommon.NavigationInfo?> = _navigation.asStateFlow()

    /**
     * “The phone went back to its own launcher” — i.e. the driver pressed Home (or swiped home) on
     * the mirrored phone. The car is expected to follow it home; this is the *only* on-screen way
     * back to the car UI, which is why the projection screen has no separate “car home” button.
     *
     * A hot [SharedFlow] with no replay: the interest is “this just happened”, and a stale replay
     * would bounce the car home again the next time the mirror opens.
     */
    private val _carHomeRequested = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val carHomeRequested: SharedFlow<Unit> = _carHomeRequested.asSharedFlow()

    /**
     * When the car last followed a `GO_TO_DESKTOP` report, and when the cast last (re)started —
     * the two inputs [PhoneGoHomePolicy] needs.
     *
     * Plain fields are enough: [IccoaManager] funnels every callback through its main-thread
     * `Handler` (`main.post` for both `onCastStarted` and `onConnectionState`), so both are written
     * and read on the car's main thread and can never be observed half-updated.
     */
    private var lastGoHomeAtMs: Long? = null
    private var lastCastStartedAtMs: Long? = null

    /**
     * Fires whenever the SDK re-attaches the decoder to a surface (`onCastStarted`).
     *
     * Not the same as [state] becoming [Phase.Connected]: when the driver comes back to the mirror
     * the phase is *already* Connected, so a state-based signal would never fire again. The
     * projection screen uses this as the start of its "first frame is coming" timer — the picture
     * lands ~1.2 s later (see `IccoaManager.onSurfaceDestroyed` for the measurement).
     */
    private val _castStarted = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val castStarted: SharedFlow<Unit> = _castStarted.asSharedFlow()

    /** The underlying SDK facade. Public so projection / navigation code can reach raw APIs. */
    val manager = IccoaManager(context.applicationContext, this)

    /** 热点探测要用的上下文（`IccoaManager` 自己留了一份，这条门禁也要用）。 */
    private val appContext: Context = context.applicationContext

    /**
     * 热点闸的设备侧：探测 + 跳过决定 + 热点开关广播。判定规则在 [AdvertiseGate]，阶段与提示
     * 留在本类与主屏 —— 谁负责什么都写在 `HotspotAdvertiseGate` 的类注释里。
     */
    private val hotspotGate = HotspotAdvertiseGate(
        context = appContext,
        apTransport = { manager.isApTransport },
        onHotspotLost = { handleHotspotLost() },
        onHotspotRestored = {
            // **只刷新界面状态，绝不自动起链**：一起链 SDK 就会接管热点，不可复用时
            // `stopTethering()` 把用户刚打开的热点直接关掉（实机日志：caller:com.miauto.app）。
            // 热点开关归用户，MiAuto 只在他点「连接」之后才广播。
            // 刷新必须以「SDK 是否真的广播过」为准（见 refreshHotspotReadiness）：否则一条
            // 兜底广播就能把界面写成「广播中」并收掉「连接」按钮，用户从设置回来无按钮可点。
            refreshHotspotReadiness()
        },
    )

    /**
     * 广播被热点闸拦下的时刻。
     *
     * 主屏据此弹「去开启热点」的引导 —— 闸在 SDK 侧（见 [IccoaManager.Listener.canAdvertise]），
     * 拦下的位置不在任何 UI 调用栈上，只能靠事件把界面叫起来。
     */
    private val _hotspotBlocked = MutableSharedFlow<HotspotState>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val hotspotBlocked: SharedFlow<HotspotState> = _hotspotBlocked.asSharedFlow()

    @Volatile
    private var started = false

    /**
     * Starts (or restarts) the ICCOA link: SDK init once, then BLE advertising.
     *
     * [wiredOnly] keeps the SDK initialised (the AOA path needs it) but suppresses every BLE
     * broadcast, so a plugged phone cannot be pulled into a wireless session first.
     */
    fun start(wiredOnly: Boolean = false) {
        // Switching transport tears advertising down; never do that to a live session.
        if (_state.value.phase != Phase.Connected) manager.setUsbOnly(wiredOnly)
        hotspotGate.startWatching()
        if (started) {
            AppLog.i("ICCOA: start() ignored — already started; restarting advertise")
            manager.start() // init-once inside; falls through to reAdvertise()
            return
        }
        started = true
        AppLog.i("ICCOA: start()")
        updatePhase(Phase.Advertising)
        manager.start()
    }

    /**
     * 热点打开后再起一次链（**只在用户点「连接」时调用**，不再由热点广播自动触发）。
     *
     * **不预置 [Phase.Advertising]**：阶段只由 [canAdvertise] 在真正放行的时刻改写。先置
     * 「广播中」会造出一次假的 `Waiting → Advertising` 迁移 —— 主屏据此判定「闸已放行」，
     * 既弹一句「已检测到车载热点」，又把刚排上的引导弹窗收掉，用户只能干等 30 秒看门狗重播。
     */
    fun advertiseAgain() {
        AppLog.i("ICCOA: 起链（用户触发）")
        if (!started) {
            start()
            return
        }
        manager.start() // 已初始化 → 只走 reAdvertise；闸会在 canAdvertise 里决定阶段
    }

    /**
     * 按热点实况刷新「等待热点 / 热点就绪」两态。**只改界面阶段，不碰 SDK**。
     *
     * 这是「热点开关归用户」的核心实现：热点开了只把状态推进到 [Phase.HotspotReady]（主屏变成
     * 「热点已开启 · 点连接继续」），广播等用户点连接；热点关了退回 [Phase.WaitingForHotspot]。
     *
     * <p><b>2026-11-06 修订：判据不再用界面阶段</b>。旧实现只在 `phase == WaitingForHotspot`
     * 时才推进，可兜底广播一旦把阶段写成 `Advertising`，用户从系统设置回来就再也看不到
     * 「连接」按钮；30 秒看门狗又因 `advertisingArmed=false` 直接 return，状态自锁，
     * 只能杀进程重开。现在改用 `isAdvertisingArmed`（SDK 是否走过起链流程）这个事实判定：
     * 只要没起链、也没停机/会话，就把界面拉回可点的那一态。仍然**不自动起链**。
     *
     * @return true = 热点已就绪
     */
    fun refreshHotspotReadiness(): Boolean {
        val blocked = hotspotGate.blockedReason()
        val phase = _state.value.phase
        // 会话中 / 配对中 / 用户主动停机：界面阶段由各自的生命周期负责，这里不抢。
        val awaitingUser = !manager.isAdvertisingArmed && !manager.isPaused &&
            phase != Phase.Connected && phase != Phase.Pairing
        if (blocked == null) {
            if (awaitingUser && phase != Phase.HotspotReady) {
                AppLog.i("ICCOA: 热点已开启 —— SDK 尚未广播，等用户点「连接」（不自动起链）")
                updatePhase(Phase.HotspotReady)
            }
            return true
        }
        if (awaitingUser && phase != Phase.WaitingForHotspot) {
            AppLog.w("ICCOA: 热点又关掉了 → 回到等待热点")
            markWaitingForHotspot()
        }
        return false
    }

    /** 用户选择「仍然继续」：本进程内不再用热点闸拦广播。 */
    fun bypassHotspotGate() = hotspotGate.bypass()

    /**
     * 车载热点被关掉（系统广播驱动，不是广播请求驱动）。
     *
     * 已经发出去的广播帧不会因为热点关掉而消失，手机照样能扫到、能发起连接，然后死在入网 ——
     * 所以这里直接把广播停掉。会话进行中不抢：那条链路由连接层自己的断开/失败路径处理，
     * 在这里再动手只会制造状态竞争。
     */
    private fun handleHotspotLost() {
        if (!started) return
        if (manager.isPaused || isSessionConnected) return
        AppLog.w("ICCOA: 检测到车载热点被关闭 → 暂停广播，等待重新开启")
        markWaitingForHotspot()
        manager.pauseAdvertisingForGate()
        _hotspotBlocked.tryEmit(HotspotState.DISABLED)
    }

    /**
     * 广播准入（[IccoaManager.Listener.canAdvertise] 的实现）：**每次**广播前都会被问到。
     *
     * 车载热点模式下热点必须已开；Wi-Fi Direct 不读状态直接放行。拦下时做两件事：把界面阶段
     * 置为 [Phase.WaitingForHotspot]（主屏显示「等待开启车载热点」而不是「就绪」），并抛出
     * [hotspotBlocked] 让主屏弹引导。
     *
     * 放行时如果上一轮是等待态或就绪待连，顺手切回 [Phase.Advertising] —— 这次广播是用户点
     * 「连接」触发的，界面要跟着走。
     */
    override fun canAdvertise(): Boolean {
        val blocked = hotspotGate.blockedReason()
        if (blocked == null) {
            val phase = _state.value.phase
            if (phase == Phase.WaitingForHotspot || phase == Phase.HotspotReady) {
                AppLog.i("ICCOA: 热点已就绪 —— 开始广播")
                updatePhase(Phase.Advertising)
            }
            return true
        }
        AppLog.w("ICCOA: 车载热点未开启（$blocked）—— 本次不广播")
        markWaitingForHotspot()
        _hotspotBlocked.tryEmit(blocked)
        return false
    }

    /**
     * 兜底广播的准入：**只判热点，不把界面写成「广播中」、也不弹引导窗**。
     *
     * 兜底广播是 SDK 广播的并行副本，不是一次起链；它没有 GATT 服务与连接状态机。
     * 让它复用 [canAdvertise] 的界面副作用，会把 `WaitingForHotspot` / `HotspotReady`
     * 写成 `Advertising`（日志「热点已就绪 —— 开始广播」），首页随即收起「连接」按钮、
     * 弹窗被收掉并 Toast「正在启动互联」—— 用户被明确告知已经在连，实际 SDK 一条广播
     * 都没发。2026-11-06 18:43 的六轮失败就是这个假迁移造成的自锁。
     *
     * 被拦时仍调 [markWaitingForHotspot] 把界面退回等待态（幂等）：SDK init 失败 + 热点没开
     * 时主路径不会跑，不退回就会停在假的「就绪」。但不等同于主路径 —— 不 emit
     * [hotspotBlocked]，引导窗由 [canAdvertise] 负责，避免一条辅助广播反复打断用户。
     */
    override fun canAdvertiseForFallback(): Boolean {
        val blocked = hotspotGate.blockedReason()
        if (blocked == null) return true
        AppLog.w("ICCOA: 车载热点未开启（$blocked）—— 兜底广播也不发")
        markWaitingForHotspot()
        return false
    }

    /**
     * 广播被闸拦下时的界面信号：车载热点模式但热点没开。
     *
     * 只改界面阶段，不碰 SDK —— 这次广播根本没发出去，没什么可停的。只有「已连接」不改：
     * 那是会话层的事实，不该被本地一条广播判定覆盖。配对中要改 —— 热点没了配对注定失败，
     * 让界面老实说「等待开启车载热点」比继续转圈诚实。
     */
    fun markWaitingForHotspot() {
        val phase = _state.value.phase
        if (phase == Phase.Connected || phase == Phase.WaitingForHotspot) return
        updatePhase(Phase.WaitingForHotspot)
    }

    /** Stops advertising and tears the session down, keeping the SDK initialised for a restart. */
    fun stop() {
        if (!started) return
        started = false
        AppLog.i("ICCOA: stop()")
        manager.stop()
        _state.update { it.copy(phase = Phase.Idle, pinCode = null) }
    }

    /**
     * 屏幕方向变更后重建协商画布（会话级生效）。
     *
     * UCarConfig 的 screen/video 尺寸只在 SDK init 时灌入、运行期改不了，而 start() 在
     * sdkInitialized 后只走 reAdvertise —— 不拆 SDK 的话「改了方向、重连还是老方向」。
     * 这里拆掉当前会话并 deInit，下一次 start() 会带新画布完整重init。
     */
    fun reinitForOrientation() {
        started = false
        AppLog.i("ICCOA: reinitForOrientation — tearing down for a fresh canvas")
        _state.update { it.copy(phase = Phase.Idle, pinCode = null) }
        manager.reinitForOrientation()
    }

    /** 用户在投屏中改方向但选择保持当前连接：下次断开时补拆 SDK（见 IccoaManager.markReinitPending）。 */
    fun markOrientationReinitPending() {
        manager.markReinitPending()
    }

    /** SDK 是否已初始化（init-once 守门为真时，重连不会重新协商画布）。 */
    fun isSdkInitialized(): Boolean = manager.isSdkInitialized()

    val isStarted: Boolean get() = started

    /**
     * True while a phone session is established.
     *
     * Anything that would put another screen on top of a running projection has to check this. The
     * equivalent Android Auto guard, `CommManager.isConnected`, cannot stand in for it: MiAuto ships
     * as an ICCOA-only build, so that one reads false for the entire drive and every "are we
     * projecting?" check built on it silently never fires.
     *
     * Deliberately **excludes** [Phase.Pairing], which is the opposite case: pairing needs the car to
     * show the PIN, the link is only ever started from `MainActivity`, and a session can be pairing
     * while the app sits backgrounded on someone else's screen. Suppressing an auto-start there would
     * strand the driver with the phone pairing into a screen nobody can see.
     */
    val isSessionConnected: Boolean
        get() = state.value.phase == Phase.Connected

    /**
     * Drops the current session **and stays down**: the link stops advertising, so the phone cannot
     * discover the car and cannot reconnect by itself. Backs the standby screen's "Disconnect".
     *
     * Staying down is the point — while the car keeps advertising, CarWith recognises it and
     * reconnects within seconds, which makes the button look broken. Coming back requires a
     * deliberate tap on "Connect" ([connect]).
     */
    fun disconnectSession() {
        AppLog.i("ICCOA: disconnectSession() — stopping the link, no auto-reconnect")
        manager.disconnectSession()
        _state.update { it.copy(phase = Phase.Disconnected, pinCode = null) }
    }

    /**
     * Manual "Connect" from the standby screen: leaves the stopped state and starts advertising
     * again, so the phone can find the car (or auto-connect to it) as usual.
     *
     * @return true when the SDK accepted the restart; false when it is not ready yet, in which case
     *         the phase is left at [Phase.Disconnected] so the button stays visible.
     */
    fun connect(): Boolean {
        AppLog.i("ICCOA: connect() — manual resume requested")
        // 顺序是硬要求：**先**置「广播中」，闸在 resumeLink() 里同步跑，被拦时会把阶段改回
        // WaitingForHotspot，最终值才是真相。反过来（先 resumeLink 再写 Advertising）会把闸刚
        // 写好的等待态盖掉：主屏显示「等待手机连接」而实际一条广播都没发，弹窗还会被这次假的
        // `Waiting → Advertising` 迁移当成「闸放行」收掉，用户要等 30 秒看门狗重播才看到提示。
        _state.update { it.copy(phase = Phase.Advertising, pinCode = null) }
        if (!manager.resumeLink()) {
            AppLog.w("ICCOA: connect() refused — SDK not ready")
            // 没恢复成：回到「已断开」，让主屏继续给「连接」按钮，别停在假的「广播中」。
            _state.update { it.copy(phase = Phase.Disconnected, pinCode = null) }
            return false
        }
        return true
    }

    /** True while the link is deliberately stopped by the user (standby screen shows "Connect"). */
    val isStoppedByUser: Boolean get() = manager.isPaused

    /**
     * 车机物理按键 → 手机（ICCOA 的按键回传通道）。会话没建立时返回 false，交给调用方按老路处理。
     *
     * MiAuto 运行期没有 AA 传输，方向盘按键只能走 ICCOA 这条通道，故 `CommManager` 把按键投到这里。
     */
    fun forwardKey(keyCode: Int, isPress: Boolean): Boolean =
        manager.sendKeyToPhone(keyCode, isPress)

    /** Whether a session is actually up (used to decide where physical keys should go). */
    val isSessionUp: Boolean get() = manager.isSessionActive

    /**
     * User-triggered "refresh pairing code": re-broadcasts immediately so the phone re-prompts,
     * instead of waiting for the 30-second watchdog. The stale code is cleared at once so the
     * screen never shows a code that is already dead.
     */
    fun refreshPairing() {
        AppLog.i("ICCOA: refreshPairing()")
        manager.refreshPairing()
        _state.update { it.copy(pinCode = null) }
    }

    /** Native screen size handed to the SDK; drives the negotiated stream resolution. */
    fun setScreenSize(width: Int, height: Int) = manager.setScreenSize(width, height)

    /**
     * Hands the projection [surface] to the SDK **along with its real pixel size**.
     *
     * The size is not cosmetic: `UCarAdapter.startCast(surface, w, h)` uses `w`/`h` only as the
     * divisor for UIBC touch mapping (`sourceX = x / w * sourceWidth`) — the decoder never sees
     * them. Passing the negotiated stream size instead of the surface's own size offsets every
     * touch (and on a portrait/landscape mismatch leaves only part of the screen clickable).
     */
    fun setSurface(surface: Surface, width: Int, height: Int) =
        manager.setSurface(surface, width, height)

    /**
     * Tells the SDK the projection surface is gone (`surfaceDestroyed`). Without it the next bind
     * is a no-op and the picture stays black with audio playing — full rationale and the state
     * machine live in `IccoaManager.onSurfaceDestroyed()` / `CastGate`.
     */
    fun onSurfaceDestroyed() = manager.onSurfaceDestroyed()

    /**
     * Pauses the video decoder while the projection screen is still alive (`Activity.onPause`).
     *
     * Called *before* the surface is torn down so the SDK swaps its output to the internal dummy
     * surface while the real one is still valid. Doing it any later is what pushes the codec into
     * `hasError`, which makes `startCast()` rebuild the decoder on the way back — and a fresh
     * decoder has to wait for a keyframe, i.e. the long black gap. See
     * `IccoaManager.pauseCastWhileHidden()`.
     */
    fun pauseCasting() = manager.pauseCastWhileHidden()

    /**
     * 抓一张当前画面存为「最后一帧」，供下次回到投屏页时占位。
     *
     * **必须在 [pauseCasting] 之前调用**：那之后输出面已换成 SDK 的 dummy 面，拷到的未必是用户
     * 最后看到的那帧；也不能放在 `surfaceDestroyed` 里 —— 那时 Surface 已失效，`PixelCopy` 必失败。
     *
     * 异步、只在切出时执行一次，不介入渲染管线；API &lt; 26 静默降级为不占位。
     */
    fun captureLastFrame(view: SurfaceView) = manager.captureLastFrame(view)

    /**
     * 探测投屏 Surface 此刻是否已经真的出画（异步，结果在主线程回调）。
     *
     * 投屏页据此决定何时撤掉最后一帧占位图：手机侧不出帧就不撤，避免到点露出纯黑。
     */
    fun probeSurfaceContent(view: SurfaceView, cb: IccoaManager.ContentProbe) =
        manager.probeSurfaceContent(view, cb)

    /**
     * 把当前 Surface 重新交给 SDK 一次 —— 长时间等不到画面时的自救。
     *
     * 只有「画面一帧不动且没有任何错误回调」这一种情形需要它；调用方必须节流。
     *
     * @return true 表示确实发起了重绑（不代表随后一定出画）
     */
    fun forceRestartCast(): Boolean = manager.forceRestartCast()

    /**
     * 缓存的最后一帧；没有 / 已过期 / 已释放时返回 null。
     *
     * **调用方不要 `recycle()`**：位图仍归 `IccoaManager` 所有，下次切出还要用，
     * 用完让 ImageView 断掉引用即可（见投屏页的 `onStop`）。
     */
    fun lastFrame(): Bitmap? = manager.lastFrame()

    /**
     * 声明 UI 开始 / 结束显示 [lastFrame] 返回的那张位图（配对调用）。
     *
     * 登记期间缓存即使被作废也不会 `recycle()` 当前帧 —— 否则 ImageView 下一帧会去画一张
     * 已回收的位图。结束显示（ImageView 断掉引用）后必须调 `false`，才会真正释放。
     */
    fun setLastFrameDisplayed(displayed: Boolean) = manager.setLastFrameDisplayed(displayed)

    /**
     * 兜底绑定该用哪个尺寸（判据见 `SurfaceBindPolicy.pickFallbackSize`）。
     *
     * 兜底是延时执行的，那时 Surface / View 已是终态；用 `surfaceChanged` 当时的快照去绑，
     * 等于多换一次输出面、多等一个关键帧。
     */
    fun pickFallbackSurfaceSize(
        surfaceW: Int,
        surfaceH: Int,
        viewW: Int,
        viewH: Int,
        cbW: Int,
        cbH: Int,
    ): IntArray = manager.pickFallbackSurfaceSize(surfaceW, surfaceH, viewW, viewH, cbW, cbH)

    /**
     * Re-binds the surface when the screen comes back but no `surfaceCreated`/`surfaceChanged`
     * arrived — i.e. the activity was paused and its surface survived (car launcher drawn as an
     * overlay, multi-window, …). Without this the decoder stays attached to the SDK's dummy surface
     * forever, because those two callbacks are the only place that binds. Returns true if it rebound.
     */
    fun resumeCastingIfStillPaused(surface: Surface, width: Int, height: Int): Boolean =
        manager.resumeCastingIfStillPaused(surface, width, height)

    /**
     * True when this `surfaceChanged` size should be **skipped** — either invalid, or the first bind
     * of a surface whose orientation contradicts the negotiated canvas（旋转过渡态）.
     *
     * Binding both flips would re-attach the decoder twice within a second, and each re-attach costs
     * a keyframe. Rule and its exception in `SurfaceBindPolicy`：已成功绑过一次的表面一律立刻绑，
     * 避免竖屏车机每次尺寸变化都白等。
     */
    fun shouldDeferSurfaceBind(width: Int, height: Int): Boolean =
        manager.shouldDeferSurfaceBind(width, height)

    /**
     * 反向控制：把车机屏幕上的触摸事件回传给手机（ICCOA UIBC 通道）。
     *
     * 调用方（投屏页的 `OnTouchListener`）**必须返回 true** 来锁定整个手势；返回 false 的话
     * ACTION_DOWN 之后不再收到 MOVE/UP，拖拽与长按会失效、手机端还会残留按下的手指。
     *
     * 坐标用 View 内像素即可 —— `startCast(surface, w, h)` 传入的 w/h 就是 SDK 的 display 尺寸
     * 基准，而投屏 SurfaceView 是满屏的，两者坐标系一致。
     */
    fun sendTouch(event: MotionEvent): Boolean = manager.sendTouch(event)

    /** 触摸回传的累计成功次数（诊断用）。 */
    fun touchSentCount(): Int = manager.getTouchSentCount()

    /** Last PIN the SDK issued, or null. Useful when the projection activity opens late. */
    fun lastPinCode(): String? = manager.lastPinCode

    // ------------------------------------------------------------------
    // IccoaManager.Listener — called on SDK worker threads, hence the StateFlow.
    // ------------------------------------------------------------------

    override fun onLog(msg: String?) {
        // Deliberately not folded into [state]: this runs for every SDK log line (several per
        // second during a session) and [state] drives the on-screen status text — one main-thread
        // round trip per log line, during projection, competing with the audio/video threads for
        // CPU. The line is still written to the app log, which is where it gets read from.
        AppLog.i("ICCOA: $msg")
    }

    /**
     * SDK 的连接状态码是 `UCarConnectState.UCAR_DEVICE_*`：
     * `2` 已连接、`4` 投屏已连接、`1` 已断开、`5` 投屏已停止、`3` **连接失败**、`6/7` 手机回桌面/回前台。
     *
     * 注意 `3` 是**连接失败**而非「已建立」，按真实语义映射。
     */
    override fun onConnectionState(stateCode: Int) {
        // 6 = UCAR_DEVICE_GO_TO_HOME：手机自己回桌面了（用户在镜像里按了手机的 Home/主屏手势，
        // 或直接在手机上退出 CarWith 界面）。车机跟着回车机首页 —— 这就是「返回车机」的唯一入口，
        // 所以投屏页不再需要一颗「返回车机」按钮。
        // 7 = UCAR_DEVICE_BACK_TO_FOREGROUND：手机回到自己的投屏界面，是 6 的反向信号。
        //
        // 这两个状态合起来走 [PhoneGoHomePolicy]：实测手机会在车机每次重新绑定画面后约 2 秒回报
        // 一次 6（而不是只在用户真按 Home 时报一次），无条件跟随就把司机卡在「投屏 ↔ 车机桌面」
        // 的弹跳圈里。判定规则与取证都在那个纯策略类里。
        if (stateCode == UCAR_DEVICE_GO_TO_HOME || stateCode == UCAR_DEVICE_BACK_TO_FOREGROUND) {
            val event = if (stateCode == UCAR_DEVICE_GO_TO_HOME) {
                PhoneGoHomePolicy.Event.GO_TO_DESKTOP
            } else {
                PhoneGoHomePolicy.Event.BACK_TO_FOREGROUND
            }
            val now = SystemClock.elapsedRealtime()
            val decision = PhoneGoHomePolicy.decide(event, now, lastGoHomeAtMs, lastCastStartedAtMs)
            if (decision.goHome) {
                lastGoHomeAtMs = now
                AppLog.i("ICCOA: 手机已回桌面（state=6）→ 车机跟随返回车机首页")
                _carHomeRequested.tryEmit(Unit)
            } else {
                AppLog.i("ICCOA: ${decision.detail.orEmpty()}")
                // 手机明确回到了自己的投屏界面：上一笔「已跟随回桌面」到此为止，
                // 否则司机关掉手机投屏后紧接着的那次真 Home 会被误当成重复上报。
                if (decision.ignored == PhoneGoHomePolicy.Ignore.BACK_TO_FOREGROUND) lastGoHomeAtMs = null
            }
            return
        }
        val phase = when (stateCode) {
            2, 4 -> Phase.Connected
            // 1/3/5 都是「会话不在了」。是否回到「广播中」取决于车机侧是不是被要求停机：
            // 用户手动断开 / 手机端主动断开 → Disconnected（等用户点「连接」）；
            // 异常断连 → Advertising（IccoaManager 已经开始重播，手机马上能连回来）。
            1, 3, 5 -> when {
                manager.isPaused -> Phase.Disconnected
                // 断连后要重播，但热点闸正拦着：界面必须继续是「等待开启车载热点」，
                // 否则会显示「等待手机连接」而实际上一条广播都没发出去。
                hotspotGate.blockedReason() != null -> Phase.WaitingForHotspot
                else -> Phase.Advertising
            }
            else -> return // 其它未识别的状态码：不改界面阶段
        }
        AppLog.i("ICCOA: connection state $stateCode -> $phase")
        // 会话边界：上一轮会话里「已经跟随过一次回桌面」不该影响这一轮，同理换面回声窗口。
        // lastCastStartedAtMs 只在断开时清 —— 连接分支紧接着就会由 onCastStarted 重新写。
        if (phase == Phase.Disconnected || phase == Phase.Advertising) {
            lastGoHomeAtMs = null
            lastCastStartedAtMs = null
        }
        updatePhase(phase)
    }

    /** 连接失败：错误码是判断"为什么连不上"的关键，单独记一条醒目的日志。 */
    override fun onConnectFailed(errorCode: Int) {
        AppLog.w("ICCOA: connect failed, errorCode=$errorCode")
    }

    override fun onPinCode(pinCode: String?, carDeviceName: String?) {
        // 不在这里落日志：IccoaManager.onPinCode 已按「空转期 5 分钟例报 / 会话期立即记」
        // 的节流策略记录，这里再打一遍就是每 30 秒两条重复行。状态更新照常。
        _state.update {
            it.copy(phase = Phase.Pairing, pinCode = pinCode, carDeviceName = carDeviceName)
        }
    }

    override fun onCastStarted() {
        AppLog.i("ICCOA: cast started")
        // 车机（重新）把解码器挂到一块新表面上，手机侧往往把这一眼当成「投屏被切走」，几秒后回报
        // 一次 GO_TO_DESKTOP。把这个时刻交给 [PhoneGoHomePolicy] 用来认回声。
        lastCastStartedAtMs = SystemClock.elapsedRealtime()
        updatePhase(Phase.Connected)
        _castStarted.tryEmit(Unit)
    }

    override fun onNavigation(info: UCarCommon.NavigationInfo?) {
        _navigation.value = info
    }

    /**
     * Moves the phase and keeps the stored PIN honest: a code only means something while the SDK is
     * still pairing.
     *
     * Leaving it up after the session opens would show a code that no longer matches anything, and
     * leaving it up after a drop is worse — the phone is about to be issued a *different* one, so
     * the screen would invite the user to type a code that is already dead. Clearing on every
     * non-pairing phase is what makes the code "refresh itself" across reconnects.
     */
    private fun updatePhase(phase: Phase) {
        _state.update { current ->
            current.copy(
                phase = phase,
                pinCode = if (phase == Phase.Pairing) current.pinCode else null,
            )
        }
    }

    private companion object {
        /** `UCarConnectState.UCAR_DEVICE_GO_TO_HOME` —— 手机回桌面。 */
        const val UCAR_DEVICE_GO_TO_HOME = 6

        /** `UCarConnectState.UCAR_DEVICE_BACK_TO_FOREGROUND` —— 手机回到投屏界面，6 的反向信号。 */
        const val UCAR_DEVICE_BACK_TO_FOREGROUND = 7
    }
}
