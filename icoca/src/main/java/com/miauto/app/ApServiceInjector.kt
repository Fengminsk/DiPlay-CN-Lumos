package com.miauto.app

import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AP 复用反射常量集中表（方案 D-A2 / 风险 R-02）。
 *
 * 桩 `icar-sdk-stubs.jar` 是窄桩：不含 `notifyOnApCreated` / `getWifiApState` / `setAddress`，
 * 也不 extends `Service`，所以 bind 必须用 `setClassName` 字符串形式、这三个成员一律反射。
 * SDK 升版导致私有名漂移时只改这一个 object；任何一项解析失败都会走降级，不崩。
 */
object ApReuseRefs {
    /** SDK 服务类名（已注册于 AndroidManifest，可 bind）。 */
    const val SERVICE_CLASS = "com.share.connect.wifiap.WifiApService"

    /**
     * SDK 会话服务类名（G8 抢跑用）：手机下发的 ssid/password/mSsidNeedPrefix 在它的
     * `mSsid/mPassword/mSsidNeedPrefix` 字段上（反编译 ShareLinkService:56/:349/:868），
     * `createSoftAp` 的 actualSsid 就从这些值算出来 —— 抢跑 = 在它读之前覆写。
     */
    const val SHARELINK_CLASS = "com.share.connect.ShareLinkService"

    /** 内部类 stub 指向外层 Service 实例的合成字段名。 */
    const val OUTER_FIELD = "this\$0"

    // ---- WifiApService 的注入目标字段（反编译 26–45 行，逐条核对过） ----
    const val F_SSID = "mSsid"
    const val F_PASSPHRASE = "mPassphrase"
    const val F_CHANNEL = "mChannel"
    const val F_IP = "mIpAddress"
    const val F_MAC = "mMac"
    const val F_IS_SYSTEM_AP = "mIsSystemApEnabled"
    const val F_AP_INFO_NOTIFIER = "mApInfoNotifier"

    /** 只读字段：AP 信息是否已通知过手机（反编译 37 行；真 = 不再重复补偿通知）。 */
    const val F_IS_AP_CREATED = "mIsApCreated"

    /** 私有通知方法：参数 (ssid, password, ip, mac, frequency)。 */
    const val M_NOTIFY_AP_CREATED = "notifyOnApCreated"

    // ---- 接入看门狗补偿（T2）：反编译 WifiApService.java 逐条核对过 ----
    /** 只读字段：是否已有客户端接入（:38 声明；仅 onConnectedClientsChanged/onNumClientsChanged 维护）。 */
    const val F_IS_AP_CONNECTED = "mIsApConnected"

    /** 私有方法：撤/装 20s 接入看门狗，参数 (boolean)（:535–541）。 */
    const val M_SET_ACCEPT_TIMEOUT = "setAcceptTimeout"

    /** 私有方法：通知观察者客户端已接入（:454–466，内含 mIsApClosedByUCar=false 副作用）。 */
    const val M_NOTIFY_ON_CONNECTED = "notifyOnConnected"

    // ---- ShareLinkService 的抢跑目标字段（反编译 ShareLinkService.java 逐条核对过） ----
    /** 手机经 BLE open() 下发的请求 ssid（:56 附近声明，:349/:868 传给 createSoftAp）。 */
    const val F_SLS_SSID = "mSsid"
    /** 手机下发的请求密码。 */
    const val F_SLS_PASSWORD = "mPassword"
    /** true 且 ssid 非 DIRECT-xx 前缀时 actualSsid 变随机值 —— 必须覆写为 false。 */
    const val F_SLS_PREFIX = "mSsidNeedPrefix"
}

/**
 * 注入配置：来自系统热点配置读取或用户手填兜底（`ApReusePolicy.PREF_SSID/PASSWORD`）。
 */
data class ApReuseConfig(val ssid: String, val password: String, val channel: Int)

/** 注入结果。 */
sealed class ApInjectResult {
    /** 注入完成，SDK 的复用分支在热点开启时应当命中。 */
    object Injected : ApInjectResult()

    /** 降级：不注入，任由 SDK 走既有路径；reason 落诊断（D-A5）。 */
    data class Degraded(val reason: String) : ApInjectResult()
}


/**
 * 注入器接缝：真实现经 `bindService` 拿 binder，测试用假实现。
 */
interface ApServiceBackend {
    /** 返回 `WifiApService$WifiApStub` 的 binder 实例；不可用返回 null。 */
    fun acquireBinder(): Any?
}

/**
 * 把「AP 已就绪」的答案注入 SDK 的 WifiApService，使其复用分支命中（方案 D-A2）。
 *
 * 注入语义（全部以反编译 `WifiApService.java` 为准）：
 * - `mSsid`/`mPassphrase`/`mChannel`：复用判定比对 `mSsid.equals(actualSsid)`，通知时按字段现值取；
 * - `mIsSystemApEnabled = true`：`stopTethering()` 在此标志下早退（180–183 行），
 *   保证用户热点不被 SDK 关掉（风险 R-03）；
 * - `mIpAddress`/`mMac`：SDK 只在自己的 tether 广播里填（88 行 setAddress），热点先开的时序下
 *   永远是 null，必须自己扫网口补齐，否则手机拿到的 AP 信息残缺；
 * - `mApInfoNotifier`：SDK 为 null 时复用分支直接 return 不通知（738–748 行），补一个可调用的
 *   Runnable，运行时读实例字段现值（与 SDK 自己的 lambda 行为一致，131–134 行）。
 *
 * 字段/方法任一解析失败 → [ApInjectResult.Degraded]，且保证不留下半套注入状态
 * （先解析全部引用，再统一写入）。
 */
class ApServiceInjector(
    private val backend: ApServiceBackend,
    private val log: (String) -> Unit,
    /** 地址解析接缝：默认扫真实网口，单测替换（JVM 宿主机上没有 tether 网口）。 */
    private val addressResolver: () -> Pair<String, String>? = { resolveTetherAddress() },
) {

    /** 一站式入口：拿 binder → 取外层实例 → 注入。 */
    fun inject(cfg: ApReuseConfig): ApInjectResult {
        val binder = backend.acquireBinder()
            ?: return ApInjectResult.Degraded("binder 不可用（bind 失败或超时）")
        val service = findOuterService(binder)
            ?: return ApInjectResult.Degraded("外层 WifiApService 实例不可达")
        return inject(service, cfg)
    }

    /**
     * 从 stub binder 里取外层 Service 实例：优先按合成字段名 `this$0`，
     * 兜底按「字段类型名 == serviceClass」扫描（防 R8 改名）。
     * binder 本身就是 Service 实例（同进程直返场景）时原样返回。
     * [serviceClass] 缺省为 WifiApService；ShareLinkService 抢跑（G8）传 [ApReuseRefs.SHARELINK_CLASS]。
     */
    fun findOuterService(binder: Any, serviceClass: String = ApReuseRefs.SERVICE_CLASS): Any? {
        if (binder.javaClass.name == serviceClass) return binder
        for (clazz in generateSequence(binder.javaClass) { it.superclass }) {
            for (f in clazz.declaredFields) {
                if (f.name != ApReuseRefs.OUTER_FIELD && f.type.name != serviceClass) {
                    continue
                }
                val outer = runCatching {
                    f.isAccessible = true
                    f.get(binder)
                }.getOrNull()
                if (outer != null) return outer
            }
        }
        return null
    }

    /** 注入到已解析出的服务实例上。幂等：重复调用整体覆写。 */
    fun inject(service: Any, cfg: ApReuseConfig): ApInjectResult {
        val fSsid = field(service, ApReuseRefs.F_SSID)
            ?: return ApInjectResult.Degraded("字段缺失：${ApReuseRefs.F_SSID}")
        val fPass = field(service, ApReuseRefs.F_PASSPHRASE)
            ?: return ApInjectResult.Degraded("字段缺失：${ApReuseRefs.F_PASSPHRASE}")
        val fChannel = field(service, ApReuseRefs.F_CHANNEL)
            ?: return ApInjectResult.Degraded("字段缺失：${ApReuseRefs.F_CHANNEL}")
        val fIp = field(service, ApReuseRefs.F_IP)
            ?: return ApInjectResult.Degraded("字段缺失：${ApReuseRefs.F_IP}")
        val fMac = field(service, ApReuseRefs.F_MAC)
            ?: return ApInjectResult.Degraded("字段缺失：${ApReuseRefs.F_MAC}")
        val fSystem = field(service, ApReuseRefs.F_IS_SYSTEM_AP)
            ?: return ApInjectResult.Degraded("字段缺失：${ApReuseRefs.F_IS_SYSTEM_AP}")
        val fNotifier = field(service, ApReuseRefs.F_AP_INFO_NOTIFIER)
            ?: return ApInjectResult.Degraded("字段缺失：${ApReuseRefs.F_AP_INFO_NOTIFIER}")
        val notify = notifyMethod(service)
            ?: return ApInjectResult.Degraded("方法缺失：${ApReuseRefs.M_NOTIFY_AP_CREATED}")

        // 地址尽力而为：核心字段（ssid/pass/channel/isSystemAp/notifier）不依赖网口，
        // 必须先落 —— 复用判定只看 mSsid；地址由 TETHER 广播 / 补偿窗 / 通知运行时补齐。
        // G7 实证：把「网口未就绪」当整体降级会让 notifier 永远挂不上，补偿路径整条失效。
        val addr = addressResolver()

        return try {
            fSsid.set(service, cfg.ssid)
            fPass.set(service, cfg.password)
            fChannel.setInt(service, cfg.channel)
            if (addr != null) {
                fIp.set(service, addr.first)
                fMac.set(service, addr.second)
            }
            fSystem.setBoolean(service, true)
            fNotifier.set(service, Runnable { runNotifier(service, notify) })
            if (addr != null) {
                log("ApReuse 注入成功 ssid=${mask(cfg.ssid)} ch=${cfg.channel} " +
                        "ip=${addr.first} mac=${addr.second}")
            } else {
                log("ApReuse 注入成功（核心字段；tether 网口未就绪，地址待补） " +
                        "ssid=${mask(cfg.ssid)} ch=${cfg.channel}")
            }
            ApInjectResult.Injected
        } catch (t: Throwable) {
            ApInjectResult.Degraded("写入字段失败：$t")
        }
    }

    /** 启动自检（方案 D-A2）：全部引用可解析返回 null，否则返回降级原因。 */
    fun selfCheck(service: Any): String? {
        val required = listOf(
            ApReuseRefs.F_SSID, ApReuseRefs.F_PASSPHRASE, ApReuseRefs.F_CHANNEL,
            ApReuseRefs.F_IP, ApReuseRefs.F_MAC, ApReuseRefs.F_IS_SYSTEM_AP,
            ApReuseRefs.F_AP_INFO_NOTIFIER,
        )
        for (name in required) {
            if (field(service, name) == null) return "字段缺失：$name"
        }
        if (notifyMethod(service) == null) return "方法缺失：${ApReuseRefs.M_NOTIFY_AP_CREATED}"
        return null
    }

    // ---- internals ----

    private fun runNotifier(service: Any, notify: ReflectMethod) {
        try {
            // 通知前最后一刻补地址：注入时网口未就绪（ip/mac 为 null）的话，此刻多半已分配。
            val fIp = field(service, ApReuseRefs.F_IP)
            val fMac = field(service, ApReuseRefs.F_MAC)
            val curIp = fIp?.get(service) as? String
            val curMac = fMac?.get(service) as? String
            if ((curIp == null || curMac == null)) {
                addressResolver()?.let { (ip, mac) ->
                    runCatching { fIp?.set(service, ip); fMac?.set(service, mac) }
                    log("ApReuse 通知前补地址：ip=$ip mac=$mac")
                }
            }
            val ssid = field(service, ApReuseRefs.F_SSID)?.get(service) as? String
            val pass = field(service, ApReuseRefs.F_PASSPHRASE)?.get(service) as? String
            val ip = fIp?.get(service) as? String
            val mac = fMac?.get(service) as? String
            val ch = field(service, ApReuseRefs.F_CHANNEL)?.getInt(service) ?: 0
            notify.invoke(service, ssid, pass, ip, mac, frequencyForChannel(ch))
        } catch (t: Throwable) {
            log("ApReuse 通知回调失败：$t")
        }
    }

    private fun field(service: Any, name: String): ReflectField? = try {
        var f: java.lang.reflect.Field? = null
        for (c in generateSequence(service.javaClass) { it.superclass }) {
            f = try { c.getDeclaredField(name) } catch (_: NoSuchFieldException) { null }
            if (f != null) break
        }
        f?.apply { isAccessible = true }
    } catch (_: Throwable) {
        null
    }

    private fun notifyMethod(service: Any): ReflectMethod? = try {
        service.javaClass
            .getDeclaredMethod(
                ApReuseRefs.M_NOTIFY_AP_CREATED,
                String::class.java, String::class.java,
                String::class.java, String::class.java, Int::class.javaPrimitiveType,
            )
            .apply { isAccessible = true }
    } catch (_: Throwable) {
        null
    }

    /** 诊断脱敏：任何日志只出 SSID 尾 4 位（方案 D-A5 安全红线，密码永不落日志）。 */
    internal fun mask(ssid: String): String =
        if (ssid.length <= 4) "****" else "****${ssid.takeLast(4)}"

    companion object {
        // 常见高通平台 tether 网口名：ap0/swlan0/softap0 是 AP 专用口；wlan1 为兼容存量机型保留。
        // wlan0：单 radio 车机在 Wi-Fi 关闭（与热点互斥）后，ROM 可能把 AP 挂在 wlan0 上复用
        // （DiAuto 上游文档亦列 wlan0 为常见 AP 口名）。注入器只在 transport=ap 时运行，此时
        // STA 已关，扫到的 wlan0 必为 AP 口，不会误抓 STA 地址。
        private val TETHER_IFACE_NAMES = setOf("ap0", "ap1", "swlan0", "softap0", "wlan1", "wlan0")

        /** tether 网口名单（只读暴露：接入看门狗补偿的 ARP 过滤复用同一份清单）。 */
        internal val tetherIfaces: Set<String> get() = TETHER_IFACE_NAMES

        /** 信道 → 频率（MHz）。2.4G: ch1..13 = 2412+5(n-1)，ch14 = 2484；5G: 5000+5ch。 */
        fun frequencyForChannel(channel: Int): Int = when {
            channel in 1..13 -> 2412 + (channel - 1) * 5
            channel == 14 -> 2484
            channel >= 36 -> 5000 + channel * 5
            else -> 0
        }

        /**
         * 频率 → 信道（G8：通知里要报真实信道，镜像 SDK `updateWifiChannelMaps` 的合法表）。
         * 取自 SDK 广播日志 `{1=2412, 36=5180, 40=5200, 44=5220, 48=5240, 149=5745,
         * 153=5765, 157=5785, 161=5805, 165=5825}`；认不出返回 0（调用方回退默认信道）。
         */
        fun channelFromFrequency(frequencyMHz: Int): Int = when (frequencyMHz) {
            2412 -> 1
            5180 -> 36
            5200 -> 40
            5220 -> 44
            5240 -> 48
            5745 -> 149
            5765 -> 153
            5785 -> 157
            5805 -> 161
            5825 -> 165
            else -> 0
        }

        /** 判断网口名是否是 tether 口（纯函数，供单测钉住平台网口名清单）。 */
        fun looksLikeTetherIf(name: String): Boolean = name in TETHER_IFACE_NAMES

        /**
         * 扫 tether 网口取 (IPv4, MAC)。镜像 SDK `setAddress`（498–515 行）的取值口径：
         * 非回环 IPv4 + 硬件地址冒号分隔。找不到返回 null。
         */
        fun resolveTetherAddress(): Pair<String, String>? {
            val candidates = try {
                NetworkInterface.getNetworkInterfaces() ?: return null
            } catch (_: Throwable) {
                return null
            }
            for (nif in candidates) {
                if (!looksLikeTetherIf(nif.name)) continue
                val macBytes = try { nif.hardwareAddress } catch (_: Throwable) { null }
                    ?: continue
                if (macBytes.isEmpty()) continue
                val mac = macBytes.joinToString(":") { "%02x".format(it) }
                val ip = nif.inetAddresses.toList().firstOrNull {
                    !it.isLoopbackAddress && it.hostAddress?.contains(':') == false
                }?.hostAddress ?: continue
                return ip to mac
            }
            return null
        }
    }
}

/** 类型别名：便于测试里替换反射对象。 */
private typealias ReflectField = java.lang.reflect.Field
private typealias ReflectMethod = java.lang.reflect.Method
