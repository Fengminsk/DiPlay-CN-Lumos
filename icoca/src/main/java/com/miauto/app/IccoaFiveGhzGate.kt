package com.miauto.app

import android.content.Context
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import com.andrerinas.openheadunit.aap.P2pChannelPolicy
import com.andrerinas.openheadunit.aap.P2pOperatingChannelPolicy
import com.andrerinas.openheadunit.connection.WifiDirectCompat
import com.andrerinas.openheadunit.connection.WifiP2pChannelCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ICCOA（小米 UCar）路的「仅 5G」闸门。
 *
 * <p>这条路的 P2P 组是 UCar SDK 建的，MiAuto 拿不到建组参数，也调用不到它内部那句
 * 「set channel to 0」。能做的是两件尽力而为的事：
 *
 * <ol>
 *   <li><b>建组前偏置</b>：用隐藏的 {@code setWifiP2pChannels} 把整机 P2P 工作信道限制到
 *       5G（UNII-1 的 36 或 UNII-3 的 149，按「Use the upper 5 GHz range」偏好）。UCar 建组时
 *       有可能自己复位成 0 把这层限制抹掉——抹掉了这条就退化成「不干预」，不会让连接失败。</li>
 *   <li><b>建组后校验</b>：读 p2p0 当前组的实际频率，交回调用方；调用方据此决定是否中止会话。</li>
 * </ol>
 *
 * <p><b>开关来源</b>：全局 5G 开关 {@code ask-five-ghz}（"Ask for 5 GHz"）——车载热点频段顺序、
 * AA Wi-Fi Direct（10+ 受支持频段请求 / 9- 隐藏信道请求）与 ICCOA 路共用的唯一入口，ICCOA 侧
 * 不限系统版本。原调试偏好 {@code debugForceP2pBand24} 已删除，「强制 2.4G」语义由关掉该开关承担。
 *
 * <p>整个类没有「失败即中断」的路径：反射不到、权限不够、组还不存在，都只是日志里多一行。
 */
object IccoaFiveGhzGate {

    /** 日志出口，接 {@code IccoaManager.log()}，让这些行和会话日志在同一处。 */
    fun interface Logger {
        fun log(line: String)
    }

    /** 频率回调。{@code <= 0} 表示读不到（无组 / 无权限 / 平台不给），不是 2.4G。 */
    fun interface FrequencyCallback {
        fun onFrequency(frequencyMhz: Int)
    }

    /** 同一频率在该窗口内重复读到就当作重复回调丢弃（见 [verify]）。 */
    private const val DUPLICATE_WINDOW_MS = 5000L

    private val main = Handler(Looper.getMainLooper())
    private val verifying = AtomicBoolean(false)

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var initFailed = false

    /**
     * 我们确实成功下发过一次信道限制 —— 只有它为 true 时 [release] 才值得去撤销。
     *
     * <p>注意它记的是「下发成功过」，不是「此刻仍然有效」：UCar 建组时的 {@code set channel to 0}
     * 会把限制抹掉，我们看不到那个时刻，也没必要跟踪 —— 撤销一个已经不在的限制是无害的，
     * 而拿它当「还需不需要再下发」的依据就会漏掉「被 UCar 复位后重新偏置」这一步。
     */
    private var restrictionApplied = false

    /**
     * 上一次下发的结果摘要，只用于<b>日志去重</b>。
     *
     * <p>[apply] 挂在「重新广播」上，会话没建立时每 30s 跑一次。而这台机器的
     * {@code setWifiP2pChannels} 在有组存在时必然返回 {@code refused (reason=0)}（AOSP 语义：
     * 已有 group 就不允许改 operating channel），于是同一行失败日志在 15:47–16:02 刷了 30 条。
     *
     * <p>但**不能因此就不再重试**：有组时的失败是暂时的，下一个「无组窗口」正好是限制可能成功的
     * 时刻，UCar 又会在建组时把限制复位。所以做法是每次都试、只在结果<b>变化</b>时打日志。
     */
    private var lastApplyDetail: String? = null

    /** 上一次上报过的频率与时刻：同一次连接里 {@code verify} 可能被两条状态回调各触发一次，重复上报只是噪音。 */
    private var lastFrequency = 0
    private var lastFrequencyAt = 0L

    /**
     * 建组前调用。返回是否被平台接受；{@code false} 只意味着这层偏置没生效。
     *
     * @param useUpperBand 与 AA 路共用「Use the upper 5 GHz range」偏好：请求 UNII-3 的 149
     *   而不是 UNII-1 的 36（仅某些监管域拒收 UNII-1 时才需要）。
     */
    @JvmStatic
    fun apply(context: Context, logger: Logger, useUpperBand: Boolean = false) {
        main.post {
            val ch = ensureChannel(context, logger) ?: return@post
            val target = if (useUpperBand) P2pOperatingChannelPolicy.CHANNEL_UPPER
                         else P2pOperatingChannelPolicy.CHANNEL_LOWER
            WifiP2pChannelCompat.setOperatingChannel(manager!!, ch, target) { applied, detail ->
                if (applied) {
                    restrictionApplied = true
                    if (lastApplyDetail != DETAIL_ACCEPTED) {
                        lastApplyDetail = DETAIL_ACCEPTED
                        logger.log(
                            "仅5G：已请求 P2P 工作信道=$target（" +
                                P2pOperatingChannelPolicy.frequencyMhzFor(target) +
                                "MHz）；UCar 建组时若仍打 " +
                                "\"set channel to 0\"，这层限制会被它复位"
                        )
                    }
                } else if (detail != lastApplyDetail) {
                    lastApplyDetail = detail
                    logger.log(
                        "仅5G：请求 5G 工作信道未生效（$detail）—— 仍按自动选，只做事后校验。" +
                            "有 P2P 组存在时这句是正常的，每次重新广播都会再试一次"
                    )
                }
            }
        }
    }

    /** 组已建起后调用，异步把实际频率交回 [callback]。 */
    @JvmStatic
    fun verify(context: Context, logger: Logger, callback: FrequencyCallback) {
        main.post {
            val ch = ensureChannel(context, logger)
            if (ch == null) {
                callback.onFrequency(-1)
                return@post
            }
            // 上一次查询还在路上：这次直接放弃且不打日志。打日志只会和调用方那句
            // 「读不到实际频率」凑成两条说同一件事的噪音，而结果同样是「不判定」。
            if (!verifying.compareAndSet(false, true)) {
                callback.onFrequency(-1)
                return@post
            }
            try {
                manager!!.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                    verifying.set(false)
                    val frequency = WifiDirectCompat.getGroupFrequency(group)
                    val now = System.currentTimeMillis()
                    // 同一次连接里 CAST_CONNECTED 与 CONNECTED 会各触发一次校验，频率必然相同 → 只上报一次。
                    if (frequency > 0 && frequency == lastFrequency && now - lastFrequencyAt < DUPLICATE_WINDOW_MS) {
                        return@requestGroupInfo
                    }
                    if (frequency > 0) {
                        lastFrequency = frequency
                        lastFrequencyAt = now
                    }
                    main.post { callback.onFrequency(frequency) }
                }
            } catch (t: Throwable) {
                verifying.set(false)
                logger.log("仅5G：读 P2P 频率失败（${t.javaClass.simpleName}: ${t.message}）")
                callback.onFrequency(-1)
            }
        }
    }

    /** 会话结束/停机/关掉开关时把信道限制还给平台，免得跟着车机走到别的 Wi-Fi Direct 用途上。 */
    @JvmStatic
    fun release(logger: Logger) {
        lastFrequency = 0            // 下一次连接必须重新上报频率，不继承上一轮的记录
        lastFrequencyAt = 0L
        lastApplyDetail = null       // 撤销之后再下发，日志要重新出现
        val ch = channel ?: return
        // 从没成功下发过就没什么可撤销的：避免每次停机都多一条「撤销未生效」。
        if (!restrictionApplied) return
        restrictionApplied = false
        WifiP2pChannelCompat.clearOperatingChannel(manager!!, ch) { applied, detail ->
            logger.log(
                if (applied) "仅5G：已撤销 P2P 信道限制"
                else "仅5G：撤销信道限制未生效（$detail）"
            )
        }
    }

    /** 是否落在 2.4G；读不到（<=0）一律不算，理由同 AA 路：没测量就不下结论。 */
    @JvmStatic
    fun isTwoPointFourGhz(frequencyMhz: Int): Boolean = P2pChannelPolicy.is24GHz(frequencyMhz)

    /**
     * 频率 → 信道号，只用于日志可读性。
     *
     * <p>2.4G 交给既有的 [P2pChannelPolicy.channelFor]（ch14 与「不在 5MHz 网格上」这两个例外它已经
     * 处理对了，不要在这里重写一遍）；5G 是 [P2pOperatingChannelPolicy.frequencyMhzFor] 最后一条分支
     * 的逆运算，那边只做了正向转换，所以这一行是唯一新增的算术。
     */
    @JvmStatic
    fun frequencyToChannel(frequencyMhz: Int): Int = when {
        P2pChannelPolicy.is24GHz(frequencyMhz) -> P2pChannelPolicy.channelFor(frequencyMhz)
        frequencyMhz >= 5000 -> (frequencyMhz - 5000) / 5
        else -> 0
    }

    private const val DETAIL_ACCEPTED = "accepted"

    private fun ensureChannel(context: Context, logger: Logger): WifiP2pManager.Channel? {
        channel?.let { return it }
        if (initFailed) return null
        return try {
            val mgr = context.applicationContext
                .getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            val ch = mgr.initialize(context.applicationContext, Looper.getMainLooper(), null)
            if (ch == null) {
                initFailed = true
                logger.log("仅5G：WifiP2pManager 初始化返回 null（本机不支持 P2P？）")
                null
            } else {
                manager = mgr
                channel = ch
                ch
            }
        } catch (t: Throwable) {
            initFailed = true
            logger.log("仅5G：WifiP2pManager 初始化失败（${t.javaClass.simpleName}: ${t.message}）")
            null
        }
    }
}
