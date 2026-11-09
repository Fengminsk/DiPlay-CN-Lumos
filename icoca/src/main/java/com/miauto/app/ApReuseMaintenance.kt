package com.miauto.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AP 复用的注入维护层（方案 G4 / D-A3'）—— IccoaManager 与注入器之间的常驻桥。
 *
 * <p><b>职责</b>（全部后台串行，绝不阻塞主线程）：
 * <ol>
 *   <li><b>建链前注入</b>：`IccoaManager.start()` 时 prime 一次 —— flag+transport 门卫通过
 *       才 bind SDK 服务（flag 关时零副作用，不 bind），把手填凭据注入
 *       `mSsid/mPassphrase/mChannel/mIsSystemApEnabled`；</li>
 *   <li><b>热点状态跟随</b>：监听 `WIFI_AP_STATE_CHANGED`，state=13（已开启）防抖重注入、
 *       state=11（已关闭）作废补偿代次 —— SDK 自己的接收器此时会清空全部注入字段
 *       （反编译 67 行），重注入必须挂在「重新开启」上；</li>
 *   <li><b>tether 网口就绪补地址</b>：`TETHER_STATE_CHANGED` 时重注入，把
 *       `mIpAddress/mMac` 补齐（SDK 只在自己的广播里填，用户先开热点时序下永远为 null）；</li>
 *   <li><b>路径二补偿</b>：手机协商开始后按 1s/2.5s/5s 三次尝试 —— 若 SDK 的 reuse 没命中
 *       （手填名 ≠ 手机请求名）且 `mIsApCreated=false`，直接跑注入的 notifier 把凭据推给手机。
 *       会话建立后不再补偿（能建链说明凭据已到）；</li>
 *   <li><b>接入看门狗补偿（T2）</b>：手机在会话开始前就连上了热点（上车自动回连）时，SDK 的
 *       20s 接入看门狗因「无人监听客户端列表」永不撤除，到点误杀会话（10008）。协商开始后：
 *       <b>(a)</b> 注册 `SoftApClientWatcher` 拿权威客户端列表（SDK 在 reuse 分支连回调都不
 *       注册，反编译 :736–743 提前 return，只能自己来）；<b>(b)</b> 兜底轮询 1.5s 起步、2s
 *       步进至 27.5s，用邻居表 + ICMP 实测可达判定存量客户端。任一通道确认有客户端即
 *       [patchAcceptLocked] 镜像 SDK 客户端接入分支 —— 撤看门狗 + 补
 *       `notifyOnConnected` + 置位 `mIsApConnected`（反编译 :608–614）。武装条件只看
 *       transport=ap（本 bug 与 ApReuse flag 无关，选热点模式就会踩）；</li>
 *   <li><b>诊断埋点</b>（G5）：结构化日志，凭据只出 SSID 尾 4 位 + 密码长度，永不落全值。</li>
 * </ol>
 *
 * <p><b>为什么补偿走「代次（generation）」</b>：断连、热点关闭、新一轮 prime 都应让进行中的
 * 补偿序列作废，否则会把上一轮的凭据推给下一台手机。代次在单线程 executor 上单调递增，
 * 每次延迟任务携带发起时的代次，执行前校验。
 */
class ApReuseMaintenance(
    private val context: Context,
    // 用 Consumer 而非 Kotlin 函数类型：IccoaManager（Java）用 this::log 传入，void 返回
    // 只匹配 Consumer.accept，匹配不了要求返回 kotlin.Unit 的 (String) -> Unit。
    private val log: java.util.function.Consumer<String>,
) {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ApReuseMaintenance").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val receiverRegistered = AtomicBoolean(false)

    /** 注入器与 backend 懒建：flag 关闭的进程连 bindService 都不该发生。 */
    @Volatile private var injector: ApServiceInjector? = null
    @Volatile private var backend: IccoaWifiApBackend? = null

    /** 补偿代次（仅 executor 线程读写）。 */
    private var generation = 0L

    /** 存量客户端探测器（arpReader/pinger 默认实现，单测不直接用到本类）。 */
    private val clientProbe = HotspotClientProbe { msg -> log.accept("[ApReuse] 探测：$msg") }

    /**
     * 热点客户端列表监听（T2-A 权威通道）。
     *
     * SDK 走「reuse existing softAp」时不注册回调（反编译 :736-743 提前 return），
     * 我们自己注册一份；收到客户端即驱动补偿。注册失败自动退化到
     * [clientProbe] 的邻居表 + ICMP 兜底。
     */
    private val clientWatcher = SoftApClientWatcher(context, { msg -> log.accept(msg) }) { count, _ ->
        executor.execute {
            if (!acceptGuardActive) return@execute
            if (count > 0) patchAcceptLocked(generation, "SoftAp 回调 clients=$count")
        }
    }

    /** 协商进行中（phase≥10）才有补偿意义 —— 回调在会话外触发时直接忽略。 */
    @Volatile private var acceptGuardActive = false

    /** 接入看门狗补偿已收尾的代次（-1 = 本协商轮尚未收尾）。 */
    @Volatile private var acceptGuardSettledGen = -1L

    /** 接入看门狗补偿已做过首探日志的代次（防每个轮询周期刷屏）。 */
    @Volatile private var acceptGuardProbedGen = -1L

    // ---- G5 埋点计数（诊断报表可见） ----
    @Volatile var primeCount = 0L; private set
    @Volatile var injectSuccessCount = 0L; private set
    @Volatile var injectFailCount = 0L; private set
    @Volatile var compensateCount = 0L; private set
    @Volatile var acceptPatchCount = 0L; private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                CarHotspotStatus.STICKY_ACTION -> {
                    val raw = intent.getIntExtra(CarHotspotStatus.STICKY_EXTRA, -1)
                    when (raw) {
                        CarHotspotStatus.RAW_ENABLED -> {
                            // 立即注入，**不再等 800ms**：SDK 在热点开启后约 1 s 就跑
                            // `createSoftAp` 做复用判定，等 800ms 才写字段会让它读到旧值 →
                            // 判「不可复用」→ recreate 分支先 `stopTethering` 把用户刚开的热点
                            // 关掉（实测 caller:com.miauto.app）。立即写 + 200ms 补一次即可。
                            prime("热点已开启(state=13) 立即注入")
                            main.postDelayed(
                                { prime("热点已开启(state=13) 补注入") },
                                REINJECT_RETRY_MS,
                            )
                        }
                        CarHotspotStatus.RAW_DISABLED -> {
                            executor.execute {
                                generation++
                                log.accept("[ApReuse] 热点已关闭(state=11)：代次作废（SDK 同时清空注入字段）")
                            }
                        }
                    }
                }
                "android.net.conn.TETHER_STATE_CHANGED" -> {
                    // 网口就绪 → 补 mIpAddress/mMac。SDK 自己也会在这条广播上走原生通知链
                    // （mSsid 非空时），我们只负责把地址字段补齐，通知交给 SDK。
                    executor.execute { primeLocked("tether 网口就绪 补地址", allowCompensation = false) }
                }
            }
        }
    }

    /** `IccoaManager.start()` 调用：注册广播 + 首次 prime。可重复调用（幂等）。 */
    fun onManagerStart() {
        if (receiverRegistered.compareAndSet(false, true)) {
            val filter = IntentFilter().apply {
                addAction(CarHotspotStatus.STICKY_ACTION)
                addAction("android.net.conn.TETHER_STATE_CHANGED")
            }
            runCatching { context.applicationContext.registerReceiver(receiver, filter) }
                .onFailure { log.accept("! [ApReuse] 注册热点广播失败：$it") }
        }
        prime("app 启动/重启 建链前注入")
    }

    /** 手机开始协商（onConnectingProgress ≥ 10）：安排路径二补偿序列 + 接入看门狗补偿轮询。 */
    fun onNegotiationStart() {
        executor.execute {
            generation++
            val gen = generation
            acceptGuardSettledGen = -1L
            acceptGuardProbedGen = -1L
            acceptGuardActive = true
            // T2-A：注册热点客户端回调（幂等）。ARM 失败不影响下面的兜底轮询。
            if (ApReusePrefs.transport(context) == "ap") runCatching { clientWatcher.register() }
            for (delay in COMPENSATION_DELAYS_MS) {
                main.postDelayed({
                    executor.execute { compensateLocked(gen) }
                }, delay)
            }
            // 接入看门狗补偿（T2）：createSoftAp 已装 20s 看门狗（setAcceptTimeout(true)，
            // 反编译 :735），存量客户端不会触发 SDK 的撤除回调 —— 覆盖 0~27.5s 全窗口轮询。
            var d = ACCEPT_GUARD_FIRST_DELAY_MS
            while (d <= ACCEPT_GUARD_LAST_DELAY_MS) {
                val delay = d
                main.postDelayed({
                    executor.execute { acceptGuardPollLocked(gen) }
                }, delay)
                d += ACCEPT_GUARD_STEP_MS
            }
            log.accept("[ApReuse] 协商开始 → 安排 ${COMPENSATION_DELAYS_MS.size} 次补偿窗口 " +
                    "(延迟 ${COMPENSATION_DELAYS_MS.joinToString("/")}ms, 代次=$gen) + " +
                    "接入看门狗补偿轮询 (${ACCEPT_GUARD_FIRST_DELAY_MS}~${ACCEPT_GUARD_LAST_DELAY_MS}ms)")
        }
    }

    /**
     * 协商预检（phase=10 钩子，G8b）。
     *
     * G8 原设计（主线程同步覆写 ShareLinkService + WifiApService）实测两个死结：
     *  1. onConnectingProgress 回调在主线程 —— bind 的 latch 等待在主线程被防死锁保护拒绝；
     *  2. 回调时序比 SDK BLE 线程的 createSoftAp 晚约 5ms —— 同步覆写永远赶不上当次判定。
     *
     * G8b 改走官方通道：actualSsid 由 `UCarConfig.setWifiSsid/setWifiPassword`（buildConfig，
     * init 时）经 `open()` 直通 —— 本钩子退化为**异步**确保注入（prime 曾因网口未就绪降级时，
     * 此刻网口多半已就绪），绝不阻塞主线程；错过当次判定由补偿窗与下一次自动重播兜住。
     * 门卫不过时完全静默；任何异常只降级，绝不向上抛。
     */
    fun preemptOnPhoneInfo() {
        if (!ApReuseGuard.isArmed(ApReusePrefs.flag(context), ApReusePrefs.transport(context))) return
        try {
            executor.execute { primeLocked("协商开始 补注入", allowCompensation = false) }
        } catch (t: Throwable) {
            log.accept("! [ApReuse] 协商预检失败（降级走补偿窗口）：$t")
        }
    }

    /** 通知里报真实信道：STA 频率 → 信道（镜像 SDK `getCurrentWifiFrequency` 口径）；取不到回退默认。 */
    private fun currentApChannel(): Int {
        val freq = try {
            val wm = context.applicationContext
                .getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wm.connectionInfo?.frequency ?: -1
        } catch (_: Throwable) {
            -1
        }
        val ch = ApServiceInjector.channelFromFrequency(freq)
        if (ch == 0) {
            log.accept("[ApReuse] STA 频率 $freq MHz 认不出信道 → 回退默认 ${ApReuseGuard.DEFAULT_CHANNEL}")
            return ApReuseGuard.DEFAULT_CHANNEL
        }
        return ch
    }

    /** 会话建立：只复查不补偿（能建链说明凭据已到或走了别的通道），并撤掉 SoftAp 客户端回调。 */
    fun onSessionUp() {
        executor.execute {
            generation++
            acceptGuardActive = false
            runCatching { clientWatcher.unregister() }
            val service = resolveServiceLocked() ?: return@execute
            val created = readApCreated(service)
            if (!ApReuseGuard.compensateOnSessionUp(created)) {
                log.accept("[ApReuse] 会话建立，mIsApCreated=true → 无需补偿")
            } else {
                log.accept("[ApReuse] 会话建立但 mIsApCreated=false（AP 凭据未通知过；" +
                        "若本次走的是 P2P/其它通道则属正常）")
            }
        }
    }

    /** 连接失败 / 断连：作废补偿序列（下一轮协商重新安排），并撤掉 SoftAp 客户端回调。 */
    fun onSessionDown(reason: String) {
        executor.execute {
            generation++
            acceptGuardActive = false
            runCatching { clientWatcher.unregister() }
            log.accept("[ApReuse] 会话结束（$reason）→ 补偿代次作废（=$generation）")
        }
    }

    // ---- internals（全部在 executor 线程执行；Locked 后缀示意，非锁） ----

    fun prime(reason: String) {
        executor.execute { primeLocked(reason, allowCompensation = false) }
    }

    private fun primeLocked(reason: String, allowCompensation: Boolean) {
        val flag = ApReusePrefs.flag(context)
        val transport = ApReusePrefs.transport(context)
        if (!ApReuseGuard.isArmed(flag, transport)) return   // 零副作用铁律：门卫不过连日志都不刷

        primeCount++
        val apState = CarHotspotStatus.probe(context)
        val ssid = ApReusePrefs.ssid(context)
        val password = ApReusePrefs.password(context)
        val injectorHealthy = resolveServiceLocked()?.let { injector?.selfCheck(it) == null } ?: false

        val decision = ApReusePolicy.decide(
            flag, transport, ApReuseGuard.mapState(apState), ssid, password, injectorHealthy
        )
        log.accept("[ApReuse] prime($reason)：state=$apState ssid=${ssid?.let { mask(it) }} " +
                "pwdLen=${password?.length ?: 0} healthy=$injectorHealthy → $decision " +
                "(prime=$primeCount ok=$injectSuccessCount fail=$injectFailCount)")

        if (decision != ApReusePolicy.Decision.REUSE) return

        val inj = injector ?: return
        val svc = resolveServiceLocked() ?: return
        if (readApCreated(svc) == true) {
            log.accept("[ApReuse] mIsApCreated=true（原生路径已通知过）→ 不覆写注入")
            return
        }
        val cfg = ApReuseConfig(ssid!!, password!!, currentApChannel())
        val result = inj.inject(svc, cfg)
        when (result) {
            is ApInjectResult.Injected -> injectSuccessCount++
            is ApInjectResult.Degraded -> injectFailCount++
        }
        log.accept("[ApReuse] 注入结果：$result")
        // 注：这里**不要**清空 SDK 的 mSsid 去「强制重建热点」—— 实测（2026-10-25 00:31）
        // 那样会让 SDK 走 startTethering → recreateSoftAp → 先 stopTethering（把用户
        // 热点真的关掉）→ 而第三方 App 无权重新建热点，结果是 `softAp is created failed`
        // + 10003，比原来的 reuse（10008）更糟：热点都没了。
    }

    private fun compensateLocked(gen: Long) {
        if (gen != generation) return   // 已被断连/热点关闭/新一轮作废
        val flag = ApReusePrefs.flag(context)
        val transport = ApReusePrefs.transport(context)
        if (!ApReuseGuard.isArmed(flag, transport)) return

        val inj = injector ?: return
        val svc = resolveServiceLocked() ?: return
        val apState = CarHotspotStatus.probe(context)
        val ssid = ApReusePrefs.ssid(context)
        val password = ApReusePrefs.password(context)
        val healthy = inj.selfCheck(svc) == null
        val decision = ApReusePolicy.decide(
            flag, transport, ApReuseGuard.mapState(apState), ssid, password, healthy
        )
        if (decision != ApReusePolicy.Decision.REUSE) {
            log.accept("[ApReuse] 补偿跳过：state=$apState decision=$decision (代次=$gen)")
            return
        }
        val created = readApCreated(svc)
        val notifier = runCatching {
            svc.javaClass.getDeclaredField(ApReuseRefs.F_AP_INFO_NOTIFIER).apply { isAccessible = true }
                .get(svc) as? Runnable
        }.getOrNull()
        if (!ApReuseGuard.shouldNotify(created, notifier != null)) {
            log.accept("[ApReuse] 补偿跳过：mIsApCreated=$created notifier=${notifier != null} (代次=$gen)")
            return
        }
        // G7 实证：prime 曾因网口未就绪降级 → notifier 没挂上 → 补偿全跳过。这里先补一次
        // 注入（此刻网口多半已就绪，顺带把地址填上），再跑通知。
        if (notifier == null) {
            val ssid2 = ApReusePrefs.ssid(context)
            val pwd2 = ApReusePrefs.password(context)
            if (ssid2 != null && pwd2 != null) {
                val r = inj.inject(svc, ApReuseConfig(ssid2, pwd2, currentApChannel()))
                if (r is ApInjectResult.Injected) injectSuccessCount++ else injectFailCount++
                log.accept("[ApReuse] 补偿前补注入：$r")
            }
        }
        val liveNotifier = runCatching {
            svc.javaClass.getDeclaredField(ApReuseRefs.F_AP_INFO_NOTIFIER).apply { isAccessible = true }
                .get(svc) as? Runnable
        }.getOrNull()
        if (liveNotifier == null) {
            log.accept("[ApReuse] 补偿放弃：补注入后 notifier 仍缺失 (代次=$gen)")
            return
        }
        liveNotifier.run()
        compensateCount++
        log.accept("[ApReuse] ★ 补偿 notifyOnApCreated 已执行 (第 $compensateCount 次, 代次=$gen) " +
                "ssid=${ssid?.let { mask(it) }} pwdLen=${password?.length ?: 0}")
    }

    /**
     * 接入看门狗补偿轮询（T2，executor 线程）。三分支：
     * 1. SDK 已判定接入（`mIsApConnected=true`，正常回调路径赢了）→ 本轮作罢；
     * 2. 现场探测到存活存量客户端（ARP 完整表项 + ping 通）→ 镜像 SDK 客户端接入分支
     *    （反编译 :608–614）：`setAcceptTimeout(false)` 撤看门狗 + `notifyOnConnected()`
     *    + 置位 `mIsApConnected`（方法自带防重复与 `mIsApClosedByUCar=false` 语义）；
     * 3. 两者皆无 → 静默返回（到点看门狗自然触发，行为与现状完全一致，零回归）。
     *
     * 武装条件只看 transport=ap：本 bug 与 ApReuse flag 无关，选了热点模式、手机先连
     * （上车自动回连的常态）就必然踩；代次校验防跨会话污染（断连/热点关闭即作废）。
     */
    private fun acceptGuardPollLocked(gen: Long) {
        if (gen != generation || gen == acceptGuardSettledGen) return
        if (ApReusePrefs.transport(context) != "ap") return
        val svc = resolveServiceLocked() ?: return
        val fConn = runCatching {
            svc.javaClass.getDeclaredField(ApReuseRefs.F_IS_AP_CONNECTED).apply { isAccessible = true }
        }.getOrNull() ?: return
        val connected = runCatching { fConn.getBoolean(svc) }.getOrDefault(false)
        if (connected) {
            acceptGuardSettledGen = gen
            log.accept("[ApReuse] 接入看门狗补偿：SDK 已判定客户端接入 → 无需补偿 (代次=$gen)")
            return
        }
        // 本机 tether 地址优先取 SDK 自己填的 mIpAddress（tether 广播路径，实测可靠）；
        // resolveTetherAddress 依赖 getHardwareAddress，MIUI 上常为 null（「地址待补」的根源）。
        val tetherIp = runCatching {
            svc.javaClass.getDeclaredField(ApReuseRefs.F_IP).apply { isAccessible = true }
                .get(svc) as? String
        }.getOrNull() ?: ApServiceInjector.resolveTetherAddress()?.first
        val subnetBase = tetherIp?.substringBeforeLast('.')
        val firstProbe = acceptGuardProbedGen != gen
        if (firstProbe) {
            acceptGuardProbedGen = gen
            log.accept("[ApReuse] 看门狗补偿首探 (代次=$gen)：tetherIp=$tetherIp 网段=$subnetBase")
        }
        val live = clientProbe.hasLiveClient(tetherIp, ApServiceInjector.tetherIfaces, subnetBase)
        if (!live) {
            if (firstProbe) {
                log.accept("[ApReuse] 看门狗补偿首探未发现存量客户端（三轮询继续，静默等待）(代次=$gen)")
            }
            return
        }
        patchAcceptLocked(gen, "邻居表/ICMP 实测可达（本机 tether=$tetherIp）")
    }

    /**
     * 执行补偿（executor 线程）：镜像 SDK 客户端接入分支（反编译 :608–614）——
     * `setAcceptTimeout(false)` 撤看门狗 → 复查 `mIsApConnected` 防重复 → `notifyOnConnected()`
     * → 置位。成功与否都收尾本轮（避免同一会话反复补）。
     *
     * @param reason 触发来源，进日志便于现场还原是哪条通道立了功。
     */
    private fun patchAcceptLocked(gen: Long, reason: String) {
        if (gen != generation || gen == acceptGuardSettledGen) return
        val svc = runCatching { resolveServiceLocked() }.getOrNull() ?: return
        val fConn = runCatching {
            svc.javaClass.getDeclaredField(ApReuseRefs.F_IS_AP_CONNECTED).apply { isAccessible = true }
        }.getOrNull() ?: return
        if (runCatching { fConn.getBoolean(svc) }.getOrDefault(false)) {
            acceptGuardSettledGen = gen
            log.accept("[ApReuse] 接入看门狗补偿：SDK 已判定客户端接入 → 无需补偿 (代次=$gen)")
            return
        }
        val patched = runCatching {
            svc.javaClass
                .getDeclaredMethod(ApReuseRefs.M_SET_ACCEPT_TIMEOUT, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(svc, false)
            if (!fConn.getBoolean(svc)) {
                svc.javaClass
                    .getDeclaredMethod(ApReuseRefs.M_NOTIFY_ON_CONNECTED)
                    .apply { isAccessible = true }
                    .invoke(svc)
                fConn.setBoolean(svc, true)
            }
            true
        }.getOrDefault(false)
        acceptGuardSettledGen = gen
        if (patched) {
            acceptPatchCount++
            log.accept("[ApReuse] ★ 接入看门狗补偿：$reason → " +
                    "已撤看门狗并补 notifyOnConnected (第 $acceptPatchCount 次, 代次=$gen)")
        } else {
            log.accept("! [ApReuse] 接入看门狗补偿执行失败（反射链断裂，行为退回原状）(代次=$gen)")
        }
    }

    /** 懒建 backend/injector 并解析出 WifiApService 外层实例；任何一步失败返回 null。 */
    @Synchronized
    private fun resolveServiceLocked(): Any? {
        if (injector == null) {
            val be = IccoaWifiApBackend(context) { msg -> log.accept(msg) }
            backend = be
            injector = ApServiceInjector(be, { msg -> log.accept(msg) })
        }
        val binder = backend!!.acquireBinder() ?: return null
        return injector!!.findOuterService(binder)
    }

    private fun readApCreated(service: Any): Boolean? = runCatching {
        service.javaClass.getDeclaredField(ApReuseRefs.F_IS_AP_CREATED).apply { isAccessible = true }
            .getBoolean(service)
    }.getOrNull()

    private fun mask(ssid: String): String = injector?.mask(ssid) ?: "****"

    private companion object {
        /**
         * 热点开启后补注入的延迟。
         *
         * 首轮已改为**立即**执行（见 `RAW_ENABLED` 分支）：SDK 在我们之后的几百毫秒内就会做
         * 复用判定，晚一步写入等于没写。这一轮只是补一次（bind/解析可能失败），不再承担防抖。
         */
        const val REINJECT_RETRY_MS = 200L
        val COMPENSATION_DELAYS_MS = listOf(1000L, 2500L, 5000L)

        // 接入看门狗补偿轮询窗口：SDK 看门狗 20s 到点（:539 postDelayed 20000），
        // 首轮 1.5s（协商开始后 createSoftAp 紧随，差个几 ms 不影响覆盖），2s 步进到 27.5s，
        // 即看门狗触发前后各有余量。
        const val ACCEPT_GUARD_FIRST_DELAY_MS = 1500L
        const val ACCEPT_GUARD_STEP_MS = 2000L
        const val ACCEPT_GUARD_LAST_DELAY_MS = 27500L
    }
}
