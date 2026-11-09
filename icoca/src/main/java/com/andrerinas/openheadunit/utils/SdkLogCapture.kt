package com.andrerinas.openheadunit.utils

import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.EnumMap

/**
 * 把 ICCOA SDK 的内部日志打捞进我们自己的诊断日志。
 *
 * <h3>为什么必须做这一层</h3>
 *
 * <p>诊断报表只采 `OPENHU` 这一个 tag，而 SDK 的内部日志走另一条路：`easy.logger.EasyLog`
 * → `android.util.Log`。这些行里正好有判「断连 / 断音」根因必须的证据：
 *
 * <ul>
 *   <li>`AudioPlayerMgr: no focus, drop data, type = N` —— 音频帧被丢的直接证据（断音）；</li>
 *   <li>`requestAudioFocus` 的返回值、`handleLossAudioFocus` 的瞬时与永久分支；</li>
 *   <li>`VideoPlayerV2` / `VideoQueueManager` / `CastManager` / `UCarConnectProxy` 的停流、掉组、超时；</li>
 *   <li>`UCarProtocol` —— 协议层（心跳、鉴权、链路断开）的告警，断连真因大概率在这里。</li>
 * </ul>
 *
 * <p>车上打不开 USB 调试，**这条通道是唯一能把 SDK 日志带回实验室的办法**。同进程同 UID，
 * 读自己的 logcat 不需要 READ_LOGS 权限。
 *
 * <h3>tag 前缀的坑（第一版翻车的地方，别再踩）</h3>
 *
 * <p>`EasyLog.PREFIX` 的初值是 `"easylog_"`，但 <b>`UCarAdapter` 的静态初始化块里有一句
 * `EasyLog.setPrefix("UCar_")`</b>（`decompiled-sdk/.../UCarAdapter.java:104`）。也就是说
 * `UCarAdapter` 一旦被类加载，全进程的 SDK 日志 tag 就变成 `UCar_AudioPlayerMgr` 了。
 *
 * <p>0.1.11.2-debug 第一轮上车就是按 `easylog_*` 过滤的，结果 logcat 子进程跑了 231 秒、
 * **一行没捞到**、最后 exit=1 —— 报表里看起来像「SDK 什么都没说」，实际是我们自己把过滤条件写错了。
 *
 * <p>所以现在**不猜前缀**：直接读本 UID 的全部日志（反正 app 只能看到自己的 UID，不会真的读到
 * 系统别人的日志），客户端按 `UCar_` / `easylog_` / `UCarProtocol` 三类前缀认领。
 * 另外每 60 秒输出一次「扫描统计 + TOP tag」，万一将来前缀又变，下一次报表会直接告诉我们是哪个 tag。
 *
 * <h3>洪泛控制</h3>
 *
 * <p>SDK 可能逐帧打日志。这里按「tag + 文本」聚合去重，每秒最多吐 [MAX_LINES_PER_FLUSH] 行，
 * 重复次数合并成 `SDK×37` —— 一帧一条的 `drop data` 反而变成最有信息量的那一行。
 */
object SdkLogCapture {

    /** 聚合后多久吐一次。1 秒：足够把同一次事件的上下文凑齐，又不会让时序错位太多。 */
    private const val FLUSH_INTERVAL_MS = 1_000L

    /** 一轮最多吐多少行。超出的只计数不输出，避免 SDK 洪泛把环形缓冲冲干净。 */
    private const val MAX_LINES_PER_FLUSH = 40

    /** 聚合表的键上限。SDK 若把变化的数值拼进消息，键会无限增长，必须设上限。 */
    private const val MAX_KEYS = 128

    /** 扫描统计周期：多久报一次「读了多少行 / 命中多少行 / TOP tag」。 */
    private const val SCAN_REPORT_MS = 60_000L

    /** 子进程意外退出后的重启次数上限。 */
    private const val MAX_RESTARTS = 8

    /** 重启前等多久：给 logd 一点时间恢复（它重启时所有 logcat 会一起 EOF）。 */
    private const val RESTART_DELAY_MS = 3_000L

    /** TOP tag 统计表上限（只用于自诊断，不需要精确）。 */
    private const val MAX_SEEN_TAGS = 48

    /**
     * 已知「纯刷屏」消息的抑制类别。
     *
     * <p>2026-10-24 上午路测实测：10 分钟会话 5946 行，其中 **400 行/分钟（67%）** 是 6 类
     * 每秒 1 条的 SDK 消息 —— 链路延迟、音乐信息、心跳收发、定时器调度、导航距离、队列 size=0。
     * 它们每一条单独看都没信息量（数值恒为 0 或递增计数），但聚合后每秒仍占 6–8 行出口额度，
     * 把 12000 行环形缓冲的覆盖时间从 80 分钟压到 20 分钟，断连前的上下文被挤掉。
     *
     * <p>处理方式：**只计数、不进聚合表**，分类计数合并进每分钟的自诊断行。这样
     * 「数据面还在流动吗」仍然可判（计数 > 0），但不再一条一行地刷。
     *
     * <p>安全边界：含 `error` / `Exception` / `fail` 的消息**一律不抑制** —— 异常往往挂在
     * 同一条模板上（如 `sendHeartBeat to phone failed` 与 `succeeded` 只差一个词）。
     */
    private enum class NoiseKind { HEARTBEAT, MUSIC_INFO, NAVIGATION, LINK_LATENCY, QUEUE_IDLE, PLAYER_DETAIL, HOST_SETUP }

    /** 低价值消息的分类计数（每分钟清零，随自诊断行输出）。 */
    private val noiseCounts = EnumMap<NoiseKind, Int>(NoiseKind::class.java)

    /** 匹配低价值消息；不关心返回值时为 null。异常关键词直接放行。 */
    private fun noiseKind(tag: String, msg: String): NoiseKind? {
        // 异常永不被抑制：模板相同、只差一个 failed 的消息必须能出来。
        if (msg.contains("error", true) || msg.contains("Exception", true) ||
            msg.contains("fail", true) || msg.contains("Failed", true)) return null
        return when {
            tag == "WifiDisplaySink" && msg.contains("avg. latency") -> NoiseKind.LINK_LATENCY
            msg.contains("handleMusicInfo") -> NoiseKind.MUSIC_INFO
            msg.contains("heart beat timer") -> NoiseKind.HEARTBEAT
            msg.contains("sendHeartBeat") -> NoiseKind.HEARTBEAT
            msg.contains("onHeartbeat") -> NoiseKind.HEARTBEAT
            msg.contains("handlePhoneHeartbeat") -> NoiseKind.HEARTBEAT
            msg.contains("navigation info", true) || msg.contains("handleNavigationInfo") -> NoiseKind.NAVIGATION
            tag.contains("VideoQueueManager") && msg.contains("buffer queue size") &&
                QUEUE_SIZE_ZERO.matches(msg) -> NoiseKind.QUEUE_IDLE
            // AOAHostSetup 的 USB 设备计数：没有手机连接时每 1.5 秒一条（实测 40 行/分钟），
            // 是「USB 口上挂着几台设备」的空转日志，对 ICCOA 投屏毫无判读价值。
            msg.contains("device count") -> NoiseKind.HOST_SETUP
            // CarVerifier 每次重播都读一遍证书（3 条：路径无效提示 + 两个 reader 名），
            // 读成功时这三条毫无信息量；真正该看的「证书校验失败」不含这些字样、会正常入表。
            msg.contains("ccd file path is invalid") || msg.contains("getDataReader") ||
                msg.contains("getDefaultReader") -> NoiseKind.HOST_SETUP
            // BLE 外设广播的启停流程：空转期每 30 秒完整走一遍（stop→Closing→Stopping→
            // 再启动），其中「启动成功 status 0」与关闭前半段毫无区分度。
            // status != 0 的启动失败**不**在此列（不含 "status 0" 字样），会正常入表。
            (msg.contains("onAdvertisingSetStarted") && msg.contains("status 0")) ||
                msg.contains("Stopping advertisers.") || msg.contains("Closing...") ->
                NoiseKind.HOST_SETUP
            // 播放器的逐帧细节：createPlayer/stopPlayer/managePlayer 这些状态跃迁保留，
            // 填充等待、参数调整、resume/release/abandon 这些每次播报都有的丢成计数。
            (tag.contains("AudioPlayer") || tag.contains("AudioPlayerMgr")) && (
                msg.contains("wait buffer to fill") || msg.contains("buffer fill done") ||
                msg.contains("updateAudioPlayParams") || msg.startsWith("resume") ||
                msg.startsWith("release") || msg.startsWith("stop,") ||
                msg.startsWith("stop ") || msg.contains("abandon audio focus succeed") ||
                msg.contains("notifyAudioFocusChanged")) -> NoiseKind.PLAYER_DETAIL
            else -> null
        }
    }

    /**
     * 认领规则：**所有** `UCar_*` / `easylog_*` 前缀的 tag，加上协议层 `UCarProtocol` 与系统侧
     * `WifiDisplaySink`。
     *
     * <p>不逐个列举业务 tag —— 列举就意味着「SDK 加了新 TAG 就漏」，而漏掉的那一条很可能正是
     * 断连真因。前缀认领 + 聚合去重已经足够控量。
     *
     * <p>`WifiDisplaySink` 是系统投屏接收端（Miracast/WFD sink）：会话异常停止时它一定会留下痕迹，
     * 而它不是 `UCar_` 命名空间，不显式加进来就会被当成「自身日志」丢掉。
     */
    private fun isSdkTag(tag: String): Boolean =
        tag.startsWith("UCar_") || tag.startsWith("easylog_") ||
            tag == "UCarProtocol" || tag == "WifiDisplaySink"

    /** threadtime 格式：`10-23 21:08:49.453  1843  1860 I UCar_AudioPlayerMgr: 消息`。 */
    private val LINE = Regex(
        "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+([VDIWEFA])\\s+(\\S+)\\s*:\\s?(.*)$"
    )

    /** 聚合单元：首个原文 + 本轮次数。 */
    private class Agg(val text: String) {
        var n = 0
    }

    private val lock = Any()
    /**
     * key = "tag\u0000数字归一化后的消息"，value = 聚合单元。
     *
     * <p>为什么按「数字归一化」做键：SDK 会把变化的数值拼进消息里（`buffer queue size: 10, total: 537`），
     * 逐字做键会让同一类消息占满 [MAX_KEYS]，把真正的一次性异常挤掉。0.1.11.3 实测一分钟
     * `命中 3078 行`，而出口上限只有 24 行/秒 —— 一半以上的「不同消息」其实是同一个模板换了数字。
     */
    private val counts = LinkedHashMap<String, Agg>()
    private var droppedKeys = 0

    /** 音频队列/播放器的量化统计（每分钟一行，替代几千行逐帧日志）。 */
    private class AudioStat {
        var maxQueue = 0
        var samples = 0
    }
    private val audioStats = LinkedHashMap<String, AudioStat>()
    /** `wait buffer to fill` 次数：播放器等数据填够，持续出现就是欠载（断音的客观指标之一）。 */
    private var underrunWaits = 0
    /** `drop data` 次数：SDK 主动丢帧（分 no focus / no player 两种原因）。 */
    private var droppedDataLines = 0

    private val QUEUE_LINE = Regex("buffer queue size: (\\d+), total: (\\d+)")
    /** `buffer queue size: 0, ...` —— 队列空才是常态，积压（>0）必须放行。 */
    private val QUEUE_SIZE_ZERO = Regex("buffer queue size: 0,")
    private val DIGITS = Regex("\\d+")

    /** 自诊断：本周期读到的行数与各 tag 出现次数。 */
    private var scanLines = 0L
    private var scanHits = 0L
    private var scanSkipped = 0L
    private val seenTags = HashMap<String, Int>()
    /** 上一周期被抑制的低价值消息计数（快照，随自诊断行输出）。 */
    private var lastNoiseText: String = ""

    private var started = false
    private var restarts = 0
    /** 只留引用便于调试时确认子进程是否还活着；不参与逻辑。 */
    @Volatile
    private var process: Process? = null
    private var firstLineSeen = false

    /**
     * 幂等启动。可在任何线程调用；重复调用只记一次失败，不会反复起进程。
     *
     * <p>`@JvmStatic`：调用方是 Java（`IccoaManager.start()`）。Kotlin `object` 的成员默认是
     * 实例方法，Java 里写成 `SdkLogCapture.start()` 会被当成「静态调用实例方法」直接编译失败。
     */
    @JvmStatic
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        // 起进程放到后台线程：调用点是 IccoaManager.start()，落在主线程上。
        Thread({ runCapture(attempt = 1) }, "SdkLogCapture").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    /**
     * 跑一次 logcat 并读到底；意外退出就按 [RESTART_DELAY_MS] 重启，最多 [MAX_RESTARTS] 次。
     *
     * <p>为什么需要重启：第一版实测子进程跑 231 秒后 `exit=1` 就没再起来，后半程断连全程没有 SDK
     * 日志。logd 重启 / 被 ROM 回收都会造成这种「静默断流」，不重启等于赌运气。
     */
    private fun runCapture(attempt: Int) {
        val p = try {
            // 不带 filterspec：app 只能看到本 UID 的日志，所以「全量」实际上就是「我们自己的进程」，
            // 量级可控；换来的是前缀猜错时依然不会静默（见 isSdkTag 与扫描统计）。
            ProcessBuilder("logcat", "-v", "threadtime").redirectErrorStream(true).start()
        } catch (t: Throwable) {
            AppLog.w("! SDK 日志采集启动失败（报表将只有应用侧日志）: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        process = p
        AppLog.i("SDK 日志采集：已启动（第 $attempt 次；按 UCar_/easylog_/UCarProtocol 认领）")
        val exit = readLoop(p)
        // exit == 0 视为正常结束（例如进程被系统整体回收前的收尾），不再重启。
        if (exit != 0 && attempt <= MAX_RESTARTS) {
            AppLog.w("! SDK 日志采集中断（exit=$exit）→ ${RESTART_DELAY_MS / 1000} 秒后第 ${attempt + 1} 次重启")
            SystemClock.sleep(RESTART_DELAY_MS)
            runCapture(attempt + 1)
        } else if (exit != 0) {
            AppLog.w("! SDK 日志采集已停止（exit=$exit，重启次数用尽 $MAX_RESTARTS）")
        }
    }

    /** @return 子进程退出码；读取异常返回 -1。 */
    private fun readLoop(p: Process): Int {
        var lastFlush = SystemClock.uptimeMillis()
        var lastScanReport = lastFlush
        try {
            BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { br ->
                while (true) {
                    val raw = br.readLine() ?: break
                    accept(raw)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                        lastFlush = now
                        flush()
                    }
                    if (now - lastScanReport >= SCAN_REPORT_MS) {
                        lastScanReport = now
                        reportScan()
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w("! SDK 日志采集读取中断: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            flush()
            reportScan()
        }
        return try {
            p.waitFor()
        } catch (t: Throwable) {
            -1
        }
    }

    private fun accept(raw: String) {
        val m = LINE.matchEntire(raw) ?: return
        val tag = m.groupValues[2]
        synchronized(lock) {
            scanLines++
            // 自诊断：无论命中与否都记 tag 频度，前缀变了才能从报表里看出来
            if (seenTags.size < MAX_SEEN_TAGS || seenTags.containsKey(tag)) {
                seenTags[tag] = (seenTags[tag] ?: 0) + 1
            }
        }
        if (!isSdkTag(tag)) {
            // 我们自己的 OPENHU 行已经在诊断日志里了，再收一遍就是重复；只计数不落库。
            synchronized(lock) { scanSkipped++ }
            return
        }
        val msg = m.groupValues[3].trim()
        if (msg.isEmpty()) return
        // 断音的两个客观计数必须先于「刷屏抑制」做：被抑制的消息（wait buffer to fill）本身
        // 不再入表，但它的次数是每分钟音频行的「等待填充」指标，不能一起被吃掉。
        if (msg.contains("wait buffer to fill")) {
            synchronized(lock) { underrunWaits++ }
        }
        if (msg.contains("drop data")) {
            synchronized(lock) { droppedDataLines++ }
        }
        // 已知纯刷屏消息：只计数不入表（见 noiseKind 的实测依据）。
        val noise = noiseKind(tag, msg)
        if (noise != null) {
            synchronized(lock) { noiseCounts[noise] = (noiseCounts[noise] ?: 0) + 1 }
            return
        }
        // 逐帧刷屏的音频队列日志不进聚合表，只更新每分钟的量化统计：
        // 一行「峰值队列=12 采样=750」比 750 行原文有用得多，也不占报表额度。
        if (tag.contains("AudioQueueManager")) {
            val q = QUEUE_LINE.find(msg)
            if (q != null) {
                val key = tag.removePrefix("UCar_").removePrefix("AudioQueueManager-")
                val depth = q.groupValues[1].toIntOrNull() ?: 0
                synchronized(lock) {
                    val st = audioStats.getOrPut(key) { AudioStat() }
                    st.samples++
                    if (depth > st.maxQueue) st.maxQueue = depth
                }
                return
            }
        }
        // 注意：wait buffer to fill 已在上面被 PLAYER_DETAIL 抑制（只计数），
        // drop data 不属任何抑制类别，会正常入表 —— 它正是断音最关键的判据。
        val key = "$tag\u0000${DIGITS.replace(msg, "#")}"
        synchronized(lock) {
            scanHits++
            val agg = counts[key]
            if (agg != null) {
                agg.n++
            } else if (counts.size < MAX_KEYS) {
                counts[key] = Agg(msg).also { it.n = 1 }
            } else {
                droppedKeys++
            }
        }
        if (!firstLineSeen) {
            firstLineSeen = true
            AppLog.i("✓ SDK 日志采集已接入（首个 tag=$tag）")
        }
    }

    private fun flush() {
        val batch = ArrayList<String>(MAX_LINES_PER_FLUSH)
        var skipped = 0
        var pendingDropped = 0
        synchronized(lock) {
            if (counts.isEmpty()) return
            val it = counts.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (batch.size < MAX_LINES_PER_FLUSH) {
                    val parts = e.key.split('\u0000', limit = 2)
                    val tag = parts[0]
                    val agg = e.value
                    batch.add(if (agg.n > 1) "SDK×${agg.n} $tag: ${agg.text}"
                              else "SDK $tag: ${agg.text}")
                } else {
                    skipped++
                }
                it.remove()
            }
            pendingDropped = droppedKeys
            droppedKeys = 0
        }
        batch.forEach { AppLog.i(it) }
        if (skipped > 0) {
            AppLog.i("SDK 其余 $skipped 种消息本轮未输出（每秒上限 $MAX_LINES_PER_FLUSH 行）")
        }
        if (pendingDropped > 0) {
            AppLog.i("SDK 另有 $pendingDropped 条消息因聚合表已满被合并丢弃")
        }
    }

    /**
     * 每分钟一行自诊断。
     *
     * <p>这一行是「采集器到底通没通」的唯一可信判据：
     * <ul>
     *   <li>`读 0 行` → logcat 根本读不到日志（ROM 拦截 / logd 挂了），与 SDK 是否打日志无关；</li>
     *   <li>`读 N 行 / 命中 0 行` + TOP tag 里能看到 SDK 的真实 tag → 认领规则要改（第一轮就是这么发现的）；</li>
     *   <li>`命中 M 行` → 通了，后面直接看 `SDK ...` 行。</li>
     * </ul>
     */
    /** 抑制类别的报表用短名。 */
    private fun noiseLabel(k: NoiseKind): String = when (k) {
        NoiseKind.HEARTBEAT -> "心跳类"
        NoiseKind.MUSIC_INFO -> "音乐信息"
        NoiseKind.NAVIGATION -> "导航"
        NoiseKind.LINK_LATENCY -> "链路延迟"
        NoiseKind.QUEUE_IDLE -> "队列空"
        NoiseKind.HOST_SETUP -> "主机设置"
        NoiseKind.PLAYER_DETAIL -> "播放细节"
    }

    private fun reportScan() {
        val lines: Long
        val hits: Long
        val skipped: Long
        val top: List<Pair<String, Int>>
        val audio: List<Triple<String, Int, Int>>
        val underrun: Int
        val drops: Int
        val noiseText: String
        synchronized(lock) {
            lines = scanLines
            hits = scanHits
            skipped = scanSkipped
            scanLines = 0
            scanHits = 0
            scanSkipped = 0
            top = seenTags.entries.sortedByDescending { it.value }.take(4).map { it.key to it.value }
            seenTags.clear()
            audio = audioStats.entries.map { Triple(it.key, it.value.maxQueue, it.value.samples) }
            audioStats.clear()
            underrun = underrunWaits
            drops = droppedDataLines
            underrunWaits = 0
            droppedDataLines = 0
            // 抑制计数快照：按类别汇总成一行，随自诊断输出。
            noiseText = if (noiseCounts.isEmpty()) "" else noiseCounts.entries
                .sortedByDescending { it.value }
                .joinToString(" ") { "${noiseLabel(it.key)}=${it.value}" }
            noiseCounts.clear()
        }
        if (lines == 0L) {
            AppLog.w("! SDK 扫描 60s：本周期一行日志都没读到 —— logcat 读不到本进程日志（ROM 拦截或 logd 异常）")
            return
        }
        val topText = top.joinToString(" ") { "${it.first}(${it.second})" }
        AppLog.i("SDK 扫描 60s：读 $lines 行 / 命中 $hits 行 / 跳过 $skipped 行（自身日志）｜TOP tag: $topText")
        // 刷屏抑制明细：这些类别每秒 1 条、实测占 67% 行数（见 noiseKind）。计数 >0 即说明
        // 对应数据面仍在流动；若某类计数突然归零，本身就是「链路停了」的信号。
        if (noiseText.isNotEmpty()) {
            AppLog.i("SDK 刷屏抑制 60s：$noiseText")
        }
        // 音频量化：断音判读就看这一行。
        //  · 峰值队列持续偏高（>20）→ 车机侧消费不过来，会听到卡顿；
        //  · 等待填充 / 丢帧 > 0 → 客观的断音证据（分别对应「数据不够」与「SDK 主动丢」）。
        if (audio.isNotEmpty()) {
            val text = audio.joinToString("；") { "${it.first} 峰值=${it.second} 采样=${it.third}" }
            AppLog.i("SDK 音频队列 60s：$text｜等待填充=$underrun｜丢帧=$drops")
        } else if (underrun > 0 || drops > 0) {
            AppLog.i("SDK 音频 60s：无队列采样｜等待填充=$underrun｜丢帧=$drops")
        }
    }
}
