package com.miauto.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [ApServiceBackend] 的真实现：bind 进 SDK 的 [ApReuseRefs.SERVICE_CLASS] 拿 binder。
 *
 * <ul>
 *   <li>bind 必须用 {@code setClassName} 字符串形式 —— 编译桩不 extends Service，
 *       {@code setClass(ctx, WifiApService::class.java)} 编不过（方案 D-A2）；</li>
 *   <li>{@code acquireBinder()} 会阻塞等 {@code onServiceConnected}，<b>禁止在主线程调用</b>
 *       （主线程死锁）；D-A3 接入时安排在 SDK 初始化后的工作线程；</li>
 *   <li>{@code BIND_AUTO_CREATE} 会强制拉起 WifiApService —— 这是预期的：注入发生在
 *       SDK 建链流程之前，服务必须已存在才能被注入。</li>
 * </ul>
 *
 * 本类涉及真机行为（Android 10 车机），不做 JVM 单测，验证靠 D-C2 的 V2/V3 场景。
 */
class IccoaWifiApBackend(
    private val context: Context,
    /** 要 bind 的 SDK 服务类名；G8 抢跑另建一个实例绑 ShareLinkService。 */
    private val serviceClass: String = ApReuseRefs.SERVICE_CLASS,
    private val log: (String) -> Unit = {},
) : ApServiceBackend {

    private var bound = false
    @Volatile private var binder: IBinder? = null
    private val connected = CountDownLatch(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // SDK 侧自杀（如进程内 stopTethering 后 onDestroy）时置空；下一次 inject 会重新 bind。
            binder = null
            log("ApReuse: WifiApService 断开绑定")
        }
    }

    override fun acquireBinder(): Any? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            log("! ApReuse: acquireBinder 在主线程被调用，拒绝（防死锁）")
            return null
        }
        if (!bound) {
            val intent = android.content.Intent().setClassName(context, serviceClass)
            bound = try {
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            } catch (t: Throwable) {
                log("! ApReuse: bindService 失败：$t")
                false
            }
            if (!bound) return null
        }
        return if (connected.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) binder else {
            log("! ApReuse: 等待 onServiceConnected 超时（${BIND_TIMEOUT_MS}ms）")
            null
        }
    }

    /** 会话结束后解除绑定（与 IccoaManager 的 keepAlive 释放同节奏，D-A3 接入时调用）。 */
    fun release() {
        if (bound) {
            bound = false
            runCatching { context.unbindService(conn) }
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 5000L
    }
}
