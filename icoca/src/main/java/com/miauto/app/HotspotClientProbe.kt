package com.miauto.app

import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 热点存量客户端探测（接入看门狗补偿的现场取证层，选项 T2）。
 *
 * 背景：SDK 的热点接入看门狗（`WifiApService.mAcceptStopper`，20s）只认「会话开始后
 * 客户端列表变化」的回调事件（反编译 :608–618）；手机在会话开始**前**就连上车机热点
 * （上车自动回连/手动测试先连）时回调永不触发 → `mIsApConnected` 恒 false →
 * 看门狗到点 `No client join` → `stopTethering()` + `notifyOnAcceptFailed()` → 10008，
 * 且 `notifyOnConnected()` 不发则 `ShareLinkService.onWirelessConnected()` 永不执行
 * （反编译 :414–415），会话就卡死在 phase 30 —— 这正是 2026-10-24 晚实测的失败形态。
 *
 * **实测教训（必须多策略）**：
 * 1. Android 10+ SELinux 对 untrusted_app 屏蔽 procfs，App 进程读 `/proc/net/arp` 拿到的是空
 *    （adb shell 能读是另一个上下文，勿被误导）→ 补 `ip neigh`（netlink，应用可执行）；
 * 2. MIUI 上 `NetworkInterface.getHardwareAddress` 也常返回 null（连累 resolveTetherAddress）；
 * 3. **普通 App 不能靠 `InetAddress.isReachable()` 做 ICMP**（无 CAP_NET_RAW，退化为 TCP/7
 *    探测，恒 false）—— 2026-10-24 23:12 会话失败就是被它坑了：首探打印了正确的网段
 *    （192.168.103）却一台都没扫到。必须 exec `/system/bin/ping`。
 * 因此探测按序降级：`/proc/net/arp` → `ip neigh` → 前两者全空时对 tether /24 做 ICMP 并行
 * 扫描。命中与否最终都以 ping 实测定生死（ARP/neigh 残留表项 ping 不通即丢弃）。
 *
 * 已知限制：目标机禁 ICMP 且回退通道也堵时探测不到 → 不补偿，行为退回原状（看门狗照常
 * 触发），零回归。transport=ap 时 STA 与热点互斥、STA 必关，tether 网口上的可达客户端
 * 不可能是 STA 自身。JVM 可测：三个 reader/pinger 均可注入。
 */
class HotspotClientProbe(
    private val arpReader: () -> String? = DEFAULT_ARP_READER,
    private val neighReader: () -> String? = DEFAULT_NEIGH_READER,
    private val pinger: (String) -> Boolean = DEFAULT_PINGER,
    /** ICMP 全段扫描的并行度（仅 /24 = 254 个地址，32 并发最坏 ~2s）。 */
    private val sweepParallelism: Int = 32,
    /** 诊断输出（可空；线上透过日志落 $[ApReuse] 通道）。 */
    private val diagnostics: (String) -> Unit = {},
) {

    /** 单条邻居表项（仅保留判定所需字段）。 */
    data class Entry(val ip: String, val device: String, val complete: Boolean)

    /**
     * 是否存在存活的存量客户端。
     * [excludeIp] 为本机 tether 地址（优先取 SDK `mIpAddress` 字段），可空；
     * [tetherIfaces] 为 tether 网口名单（`ApServiceInjector.tetherIfaces`）；
     * [subnetBase] 为 tether /24 网段前缀（如 "192.168.103"），null 则不做全段扫描。
     */
    fun hasLiveClient(excludeIp: String?, tetherIfaces: Set<String>, subnetBase: String? = null): Boolean {
        // 策略 1+2：邻居表（两种来源合并去重），命中后以 ping 定生死的最终判据
        val arpText = arpReader()
        val neighText = neighReader()
        val neighbors = arpText?.let { parseArp(it) }.orEmpty() +
                (neighText?.let { parseNeigh(it) }.orEmpty())
        diagnostics("arp=${entriesOf(arpText).size} neigh=${entriesOf(neighText).size} " +
                "合并=${neighbors.size} 排除本机=$excludeIp")
        val pinged = neighbors.asSequence()
            .filter { it.complete && it.ip != excludeIp && it.device in tetherIfaces }
            .map { it.ip }
            .distinct()
            .any { pinger(it) }
        if (pinged) return true
        // 策略 3：邻居表不可读（Android 10+ procfs 屏蔽的典型形态）→ 全段 ICMP 扫描。
        // 节流：一次全扫是 254 次 exec ping，别在 2s 步进的轮询里连着扫。
        if (subnetBase != null && neighbors.isEmpty()) {
            val now = System.currentTimeMillis()
            if (now - lastSweepAt < SWEEP_MIN_INTERVAL_MS) return false
            lastSweepAt = now
            diagnostics("邻居表全空 → 触发 ${subnetBase}.0/24 ICMP 全段扫描")
            return sweepPing(subnetBase, excludeIp)
        }
        return false
    }

    /** 上一次全段扫描的时刻（0 = 从未扫过）。 */
    private var lastSweepAt = 0L

    /** /24 全段并行 ICMP 扫描：任一地址实测可达即认为有客户端。 */
    private fun sweepPing(subnetBase: String, excludeIp: String?): Boolean {
        val pool = Executors.newFixedThreadPool(sweepParallelism) { r ->
            Thread(r, "HcProbe-sweep").apply { isDaemon = true }
        }
        return try {
            (1..254).asSequence().map { "$subnetBase.$it" }
                .filter { it != excludeIp }
                .map { ip -> pool.submit<Boolean> { pinger(ip) } }
                .any { runCatching { it.get(5, TimeUnit.SECONDS) }.getOrDefault(false) }
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val PING_TIMEOUT_MS = 500

        /** 全段 ICMP 扫描的最小间隔（节流：254 次 exec ping 不能每轮都扫）。 */
        private const val SWEEP_MIN_INTERVAL_MS = 6000L

        private val DEFAULT_ARP_READER: () -> String? = {
            runCatching { File("/proc/net/arp").readText() }.getOrNull()
        }

        /** `ip neigh` 两种写法都试：部分 ROM 的 toybox 只认其中一种。 */
        private val DEFAULT_NEIGH_READER: () -> String? = {
            val a = runCatching { exec(listOf("/system/bin/ip", "neigh", "show")) }.getOrNull()
            if (!a.isNullOrBlank()) a else runCatching { exec(listOf("/system/bin/ip", "neigh")) }.getOrNull()
        }

        /** exec 兜底：`ProcessBuilder` 的 PATH 不一定含 /system/bin，直接给绝对路径最稳。 */
        private val DEFAULT_PINGER: (String) -> Boolean = { ip ->
            try {
                val p = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip)
                    .redirectErrorStream(true)
                    .start()
                try {
                    runCatching { p.waitFor(3, TimeUnit.SECONDS) }
                    val out = runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("")
                    out.contains("bytes from", ignoreCase = true) || out.contains("ttl=", ignoreCase = true)
                } finally {
                    runCatching { p.destroy() }
                }
            } catch (t: Throwable) {
                // 二进制 ping 不可用（极老 ROM / SELinux 收紧）→ 退回 JVM 通道（成功率低但保留）
                runCatching { InetAddress.getByName(ip).isReachable(PING_TIMEOUT_MS) }.getOrDefault(false)
            }
        }

        private fun exec(cmd: List<String>): String = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
            .run {
                val out = runCatching { inputStream.bufferedReader().readText() }.getOrDefault("")
                runCatching { waitFor(3, TimeUnit.SECONDS) }
                runCatching { destroy() }
                out
            }

        private fun entriesOf(text: String?): List<Entry> =
            text?.let { parseArp(it) + parseNeigh(it) } ?: emptyList()

        /**
         * 解析 `/proc/net/arp` 文本：跳过表头；列序
         * `IP address  HW type  Flags  HW address  Mask  Device`；
         * flags 0x2 = 完整表项（0x0 = incomplete），大小写不敏感。
         * 注意：解析**保留** incomplete 项（complete=false），过滤发生在 hasLiveClient。
         */
        internal fun parseArp(text: String): List<Entry> = text
            .lineSequence()
            .drop(1)   // 表头
            .mapNotNull { line ->
                val f = line.trim().split(Regex("\\s+"))
                if (f.size < 6) {
                    null
                } else {
                    Entry(f[0], f[5], f[2].equals("0x2", ignoreCase = true))
                }
            }
            .filter { it.ip.isNotEmpty() }
            .toList()

        /**
         * 解析 `ip neigh` 输出：行形如
         * `192.168.103.30 dev wlan1 lladdr aa:bb:cc:dd:ee:ff REACHABLE`（列序随状态浮动）；
         * FAILED/INCOMPLETE 视为不完整（complete=false），其余状态（REACHABLE/STALE/
         * DELAY/PROBE/PERMANENT）都算「值得 ping 一下」，生死由 ping 定。
         */
        internal fun parseNeigh(text: String): List<Entry> = text
            .lineSequence()
            .mapNotNull { line ->
                val t = line.trim().split(Regex("\\s+"))
                if (t.size < 4 || !t[1].startsWith("dev")) return@mapNotNull null
                val state = t.last()
                Entry(t[0], t[2], !(state.equals("FAILED", true) || state.equals("INCOMPLETE", true)))
            }
            .filter { it.ip.isNotEmpty() }
            .toList()
    }
}
