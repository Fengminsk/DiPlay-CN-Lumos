package com.miauto.app

import android.content.Context
import android.net.wifi.WifiManager
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * 热点客户端列表监听（接入看门狗补偿的**权威**取证通道，选项 T2-A）。
 *
 * <p><b>为什么必须自己注册</b>：SDK 在 `reuse existing Wifi softAp` 分支（反编译
 * `WifiApService.java:736-743`）会**提前 return，不调用 `registerSoftApCallback()`** ——
 * 而「手机在会话开始前就已连上车机热点」（上车自动回连的常态）走的正是这条分支。
 * 后果：即使热点客户端列表发生变化（实测 23:01:10 确有 `WifiClient` 上线），SDK 进程里
 * **没有任何回调在听**，`mIsApConnected` 恒 false → 20s 后 `No client join` →
 * `stopTethering()` + `notifyOnAcceptFailed()` → 10008。佐证：全缓冲搜不到任何
 * `UCar_WifiApService: register SoftAp Callback`。
 *
 * <p><b>做法</b>：镜像 SDK 自己的招数 —— 反射取 `WifiManager$SoftApCallback` 接口做动态代理，
 * 调 `registerSoftApCallback(Executor, SoftApCallback)` 注册；收到非空客户端列表时回调
 * [onClients]（数量 + 明细文本），由上层决定是否补 `notifyOnConnected()`。
 *
 * <p>注册失败（ROM 收紧反射 / 无权限）时只降级，交由 `HotspotClientProbe` 的邻居表 + ICMP
 * 扫描兜底；即便注册成功也可能拿不到「注册那一刻的存量客户端」，所以两条腿都要留。
 */
class SoftApClientWatcher(
    private val context: Context,
    private val log: Consumer<String>,
    private val onClients: (Int, String) -> Unit,
) {

    private val callbackExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SoftApWatch").apply { isDaemon = true }
    }
    private var proxy: Any? = null

    /** 幂等注册：已注册返回 true；失败返回 false 并打日志（调用方据此决定是否靠兜底探测）。 */
    @Synchronized
    fun register(): Boolean {
        proxy?.let { return true }
        return try {
            val cbClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val impl = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass)) { _, method, args ->
                when (method.name) {
                    "equals" -> (args?.getOrNull(0) === proxy)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "MiAutoSoftApCallback"
                    "onConnectedClientsChanged" -> handleClients(args)
                    else -> null
                }
            }
            proxy = impl
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
            val m = WifiManager::class.java.getMethod(
                "registerSoftApCallback", java.util.concurrent.Executor::class.java, cbClass
            )
            m.invoke(wm, callbackExecutor, impl)
            log.accept("[ApReuse] SoftAp 客户端回调已注册（权威通道，替代只读邻居表的猜测）")
            true
        } catch (t: Throwable) {
            proxy = null
            log.accept("! [ApReuse] SoftAp 客户端回调注册失败（退化到邻居表/ICMP 兜底）：$t")
            false
        }
    }

    /**
     * 取出变更里的客户端数量：`onConnectedClientsChanged` 在 API 33+ 是
     * `(SoftApInfo, List<WifiClient>)`，30~32 是 `(List<WifiClient>)` —— 列表总在最后一位。
     */
    private fun handleClients(args: Array<Any?>?): Any? {
        val list = args?.lastOrNull() as? Collection<*>
        val count = list?.size ?: 0
        val detail = list?.joinToString(",") { runCatching { it.toString() }.getOrDefault("?") }.orEmpty()
        log.accept("[ApReuse] SoftAp 客户端回调：clients=$count $detail")
        if (count > 0) runCatching { onClients(count, detail) }
        return null
    }

    /** 释放回调（与注册成对）。多次调用幂等。 */
    @Synchronized
    fun unregister() {
        val impl = proxy ?: return
        proxy = null
        runCatching {
            val cbClass = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val m = WifiManager::class.java.getMethod("unregisterSoftApCallback", cbClass)
            m.invoke(wm, impl)
        }.onFailure { log.accept("! [ApReuse] SoftAp 客户端回调注销失败：$it") }
    }
}
