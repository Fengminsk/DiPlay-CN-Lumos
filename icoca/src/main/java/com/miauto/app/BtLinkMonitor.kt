package com.miauto.app

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andrerinas.openheadunit.utils.AppLog

/**
 * 蓝牙链路的<b>双向</b>观测点。
 *
 * <h3>为什么非补不可</h3>
 *
 * <p>2026-10-23 那两份报表里，三次异常断连（`param=-1`）之后 0.5–1.7 秒都紧跟一条
 * `BT Device connected` —— 这说明断连那一刻蓝牙<b>是断开的</b>。但工程里唯一记录蓝牙的地方是
 * {@code AutoStartReceiver}，它只处理 {@code ACTION_ACL_CONNECTED}：
 *
 * <pre>
 * if (action == BluetoothDevice.ACTION_ACL_CONNECTED) { ... }   // 没有 else
 * </pre>
 *
 * <p>于是「什么时候断的、断的是哪一侧（手机主动 / BT 栈自己 / 距离）、断之前链路活了多久」
 * 这四个问题在报表里全是空白。ICCOA 的控制通道跑在蓝牙上，蓝牙一断就必然带崩会话 ——
 * 这是头号嫌疑，却因为缺一行日志而永远只能「疑似」。
 *
 * <h3>为什么用动态注册</h3>
 *
 * <p>`ACL_CONNECTED` 走的是清单静态注册（`AndroidManifest.xml` 里那个 intent-filter），
 * 而 Android 8+ 对隐式广播的豁免清单是老版本行为、各 ROM 都可能改。动态注册不受隐式广播限制，
 * 与 {@code AapService} 里 MEDIA_BUTTON / WiFi 自启两条的做法一致 —— 那两条的注释写得很清楚：
 * 「runtime receivers bypass the Android 8+ implicit broadcast restriction」。
 *
 * <p>这里只读状态、只写日志，不触发任何自启动逻辑，因此不会像静态注册那条路一样有
 * 「重复拉起界面」的副作用。
 */
class BtLinkMonitor : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED ->
                logDevice(context, "ACL_CONNECTED", intent, "蓝牙 ACL 建立")

            BluetoothDevice.ACTION_ACL_DISCONNECTED ->
                logDevice(context, "ACL_DISCONNECTED", intent, "★ 蓝牙 ACL 断开（会带崩 ICCOA 控制通道）")

            BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED ->
                logDevice(context, "ACL_DISCONNECT_REQUESTED", intent, "蓝牙 ACL 即将断开（对端先发了断链请求）")

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                AppLog.i("BT 链路：适配器状态 → ${adapterStateName(state)}（$state）")
            }

            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                AppLog.i("BT 链路：适配器连接状态 → ${profileStateName(state)}（$state）")
            }
        }
    }

    private fun logDevice(context: Context, tag: String, intent: Intent, what: String) {
        val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        // bondState 是关键判据：ACL 断开但仍是 BONDED，说明是链路级断开（距离/干扰/对端关蓝牙），
        // 不是被用户取消配对 —— 这两种情况的自愈能力完全不同。
        val bond = try {
            when (device?.bondState) {
                BluetoothDevice.BOND_BONDED -> "BONDED"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_NONE -> "NONE"
                else -> "?"
            }
        } catch (_: Throwable) { "?" }
        AppLog.i(
            "BT 链路：$tag ← $what｜设备=${device?.name ?: "?"}(${device?.address ?: "?"})" +
                " 配对态=$bond 已连接设备=${connectedDeviceCount(context)} ${profiles()}"
        )
    }

    /**
     * 当前连着几台蓝牙设备。
     *
     * <p>不用 `BluetoothDevice.isConnected()`：那是 `@hide`，编译期取不到（本项目在
     * `BtLinkMonitor` 上撞过一次）。`BluetoothManager.getConnectedDevices()` 是 API 18 起的
     * 公开接口，且不需要先拿 profile 代理。
     */
    private fun connectedDeviceCount(context: Context): String = try {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        val list = bm?.getConnectedDevices(BluetoothProfile.GATT)
        if (list == null) "?" else list.size.toString()
    } catch (_: Throwable) {
        "?"
    }

    /**
     * 顺带采一次 A2DP / HFP 的连接状态。
     *
     * <p>ICCOA 的投屏音视频走 Wi-Fi，但「电话/媒体控制」这类通道依赖蓝牙 profile；
     * A2DP 掉了而 ACL 还在，是「能投屏但方向盘键失灵 / 接不了电话」的典型现场。
     */
    private fun profiles(): String {
        return try {
            val a = BluetoothAdapter.getDefaultAdapter() ?: return "profiles=无适配器"
            val a2dp = try { a.getProfileConnectionState(BluetoothProfile.A2DP) } catch (_: Throwable) { -1 }
            val hfp = try { a.getProfileConnectionState(BluetoothProfile.HEADSET) } catch (_: Throwable) { -1 }
            "A2DP=${profileStateName(a2dp)} HFP=${profileStateName(hfp)}"
        } catch (_: Throwable) {
            "profiles=读取失败"
        }
    }

    companion object {
        fun filter(): IntentFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
        }

        private fun adapterStateName(s: Int): String = when (s) {
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "STATE_$s"
        }

        private fun profileStateName(s: Int): String = when (s) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            -1 -> "?"
            else -> "STATE_$s"
        }
    }
}
