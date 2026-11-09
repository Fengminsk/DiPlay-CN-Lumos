package com.andrerinas.openheadunit.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andrerinas.openheadunit.utils.AppLog
import com.miauto.app.AdvertiseAction
import com.miauto.app.AdvertiseGate
import com.miauto.app.CarHotspotStatus
import com.miauto.app.HotspotState
import com.miauto.app.LinkMode

/**
 * 热点闸的「设备侧」：探测热点、记住用户跳过的决定、监听热点开关广播。
 *
 * 分工：判定规则在 [AdvertiseGate]（纯函数），界面阶段与提示在 [IcocaBridge] 与主屏，这里只和
 * 系统打交道 —— 反射读状态、收 `WIFI_AP_STATE_CHANGED`，把「热点没了 / 回来了」翻译成回调。
 *
 * 之所以还要一条广播：广播闸只在「正要发广播」时判定，而热点是用户随时会关的。关掉的那一刻
 * 没有广播请求在跑，光靠闸永远不会察觉 —— 车机会一直对外广播，手机扫到、连上、然后死在入网。
 */
internal class HotspotAdvertiseGate(
    private val context: Context,
    /** 当前建链方式是否「车载热点」。由 bridge 从 IccoaManager 读，避免两处各读一份偏好。 */
    private val apTransport: () -> Boolean,
    /** 运行中热点被关掉（广播驱动）。 */
    private val onHotspotLost: () -> Unit,
    /** 热点重新打开（广播驱动）。 */
    private val onHotspotRestored: () -> Unit,
) {

    @Volatile
    private var bypassed = false

    @Volatile
    private var watching = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // 粘性广播可能在注册那一刻同步回调；任何异常都不能把进程带走。
            runCatching {
                if (intent.action != CarHotspotStatus.STICKY_ACTION) return
                // P2P 模式与热点无关（反而是「热点开着」才会抢射频），这里一律不参与。
                if (!apTransport()) return
                val raw = intent.getIntExtra(CarHotspotStatus.STICKY_EXTRA, -1)
                when (CarHotspotStatus.fromRawState(raw)) {
                    HotspotState.DISABLED -> if (!bypassed) onHotspotLost()
                    HotspotState.ENABLED -> onHotspotRestored()
                    // ENABLING/DISABLING 之类的过渡态：不抢跑，等终态广播。
                    HotspotState.UNKNOWN -> Unit
                }
            }.onFailure { AppLog.w("ICCOA: 处理热点状态广播异常：$it") }
        }
    }

    /** 用户选择「仍然继续」：本进程内不再拦（也不因热点被关而停播）。 */
    fun bypass() {
        bypassed = true
        AppLog.w("ICCOA: 用户跳过热点检查 —— 之后不再拦截广播")
    }

    /**
     * 当前拦截原因：null = 放行；否则是被探测到的热点状态（DISABLED / UNKNOWN）。
     *
     * 探测只做一次，返回状态而不是布尔，是因为拦截时还要拿它去选「已关闭」/「无法确认」两套文案。
     */
    fun blockedReason(): HotspotState? {
        if (bypassed || !apTransport()) return null
        val hotspot = CarHotspotStatus.probe(context)
        val action = AdvertiseGate.decide(LinkMode.CAR_HOTSPOT, hotspot, bypassed = false)
        return if (action == AdvertiseAction.ALLOW) null else hotspot
    }

    /**
     * 开始监听热点状态（幂等）。
     *
     * 不放在构造里：注册粘性广播可能立刻回调，那时持有者的字段还没初始化完。
     */
    fun startWatching() {
        if (watching) return
        watching = true
        runCatching {
            context.registerReceiver(receiver, IntentFilter(CarHotspotStatus.STICKY_ACTION))
        }.onFailure {
            watching = false
            AppLog.w("ICCOA: 注册热点状态广播失败：$it")
        }
    }
}
