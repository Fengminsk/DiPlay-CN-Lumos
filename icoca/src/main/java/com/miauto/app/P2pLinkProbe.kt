package com.miauto.app

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.connection.WifiDirectCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 会话期间 P2P 组的只读快照 —— 用来回答「断连时 Wi-Fi 直连组还在不在、频段有没有跳」。
 *
 * <h3>为什么要单独一个探针</h3>
 *
 * <p>2026-10-23 那份报表里，三次异常断连（`param=-1`）前后应用层**什么异常都没有**：没有主动断开、
 * 没有网络回调、锁一直持有。唯一还活着的线索是「每次重建都落在不同信道」——
 * 36(5180) / 40(5200) / 48(5240) 三个频道轮着来，而「仅5G」下发的是 36 且日志自己写着
 * 「UCar 建组时若仍打 set channel to 0，这层限制会被它复位」。
 *
 * <p>而 `IccoaFiveGhzGate.verify()` 只在一处（会话建立时）读一次频率，断了以后什么都不留。
 * 断连那一刻「组还在不在」这个判据，正是区分下面两种根因的唯一办法：
 *
 * <ul>
 *   <li><b>组还在，但会话没了</b> → 问题在 SDK 会话层（WFD 停流、心跳、鉴权），Wi-Fi 无罪；</li>
 *   <li><b>组没了</b> → 问题在传输层（掉组、射频、手机侧热点关闭），SDK 只是被动收尸。</li>
 * </ul>
 *
 * <h3>安全性</h3>
 *
 * <p>只调 `requestGroupInfo`，**从不** createGroup/connect/removeGroup —— 不改任何链路状态。
 * 所有失败路径都返回一句话而不是抛异常：车上一次崩溃比少一条日志严重得多。
 * 通道初始化失败（个别 ROM 会返回 null）后永久停用，不会每次心跳都重试。
 */
object P2pLinkProbe {

    fun interface Callback {
        fun onSnapshot(text: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var disabled = false

    /** 最近一次成功的快照与时刻，供心跳直接引用，避免心跳自己去等异步回调。 */
    @Volatile
    var lastSnapshot: String = "未采样"
        private set
    @Volatile
    var lastSnapshotAt: Long = 0L
        private set

    /**
     * 异步读一次组信息。
     *
     * <p>`@JvmStatic`：唯一的调用方是 Java（`IccoaManager.probeP2p()`）。
     * Kotlin `object` 的成员默认是实例方法，Java 侧写 `P2pLinkProbe.snapshot(...)`
     * 会被判成「静态调用实例方法」而在编译期失败。
     */
    @JvmStatic
    fun snapshot(context: Context, callback: Callback) {
        if (disabled) {
            callback.onSnapshot("不可用")
            return
        }
        main.post {
            val ch = ensureChannel(context)
            if (ch == null) {
                callback.onSnapshot("不可用")
                return@post
            }
            // 上一次查询还没回来：直接放弃。心跳是周期性的，丢一次不影响判读，
            // 但并发两个 requestGroupInfo 会让某些 ROM 直接回调 FAILURE。
            if (!inFlight.compareAndSet(false, true)) {
                callback.onSnapshot(lastSnapshot)
                return@post
            }
            try {
                manager!!.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                    inFlight.set(false)
                    val text = describe(group)
                    lastSnapshot = text
                    lastSnapshotAt = android.os.SystemClock.uptimeMillis()
                    main.post { callback.onSnapshot(text) }
                }
            } catch (t: Throwable) {
                inFlight.set(false)
                val text = "读取失败(${t.javaClass.simpleName})"
                lastSnapshot = text
                lastSnapshotAt = android.os.SystemClock.uptimeMillis()
                callback.onSnapshot(text)
            }
        }
    }

    /**
     * 组信息 → 一行摘要。
     *
     * <p>`频率` 是判频段的唯一依据；`成员数` 用来判断「手机是不是已经退出组」
     * （车机是 GO，手机是 client：client 掉了但组还在，就是手机侧先撒手）。
     */
    private fun describe(group: WifiP2pGroup?): String {
        if (group == null) return "无组（链路已拆）"
        val freq = try { WifiDirectCompat.getGroupFrequency(group) } catch (_: Throwable) { 0 }
        val band = when {
            freq <= 0 -> "频率读不到"
            freq < 3000 -> "2.4G"
            else -> "5G"
        }
        val channel = if (freq >= 5000) (freq - 5000) / 5 else if (freq > 0) IccoaFiveGhzGate.frequencyToChannel(freq) else 0
        val clients = try { group.clientList?.size ?: 0 } catch (_: Throwable) { -1 }
        return buildString {
            append(if (freq > 0) "${freq}MHz(ch$channel,$band)" else band)
            append(" GO=").append(if (group.isGroupOwner) 1 else 0)
            append(" 成员=").append(if (clients < 0) "?" else clients.toString())
        }
    }

    private fun ensureChannel(context: Context): WifiP2pManager.Channel? {
        channel?.let { return it }
        if (disabled) return null
        return try {
            val mgr = context.applicationContext
                .getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (mgr == null) {
                disabled = true
                return null
            }
            val ch = mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)
            if (ch == null) {
                disabled = true
                null
            } else {
                manager = mgr
                channel = ch
                ch
            }
        } catch (t: Throwable) {
            disabled = true
            null
        }
    }
}
