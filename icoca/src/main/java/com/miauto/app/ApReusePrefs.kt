package com.miauto.app

import android.content.Context

/**
 * AP 复用偏好的统一读写口（键定义见 [ApReusePolicy]）。
 *
 * 键常量原本 package-private 在 `ApReusePolicy` 里（JVM 单测用），UI（`SettingsFragment`
 * 在别的包）需要公开入口 —— 收拢在这里，避免两处字面量漂移。所有写操作落盘即生效：
 * `IccoaManager.start()` 每次都会重新读（下一次建链前注入用新值），无需重启进程。
 */
object ApReusePrefs {

    fun prefs(context: Context) =
        context.getSharedPreferences(ApReusePolicy.PREF_FILE, Context.MODE_PRIVATE)

    // 默认 true（2026-10-24 用户决策：配置合并进「连接设置」后，复用车机热点成为默认路径；
    // 仅在 transport=ap 时才有注入动作，默认 transport=p2p，故全新安装的实际建链行为不变）。
    @JvmStatic fun flag(context: Context): Boolean =
        prefs(context).getBoolean(ApReusePolicy.PREF_FLAG, true)

    @JvmStatic fun setFlag(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(ApReusePolicy.PREF_FLAG, value).apply()
    }

    @JvmStatic fun ssid(context: Context): String? =
        prefs(context).getString(ApReusePolicy.PREF_SSID, null)?.trim()?.takeIf { it.isNotEmpty() }

    @JvmStatic fun password(context: Context): String? =
        prefs(context).getString(ApReusePolicy.PREF_PASSWORD, null)?.trim()?.takeIf { it.isNotEmpty() }

    @JvmStatic fun setCredentials(context: Context, ssid: String?, password: String?) {
        prefs(context).edit()
            .putString(ApReusePolicy.PREF_SSID, ssid?.trim().orEmpty())
            .putString(ApReusePolicy.PREF_PASSWORD, password?.trim().orEmpty())
            .apply()
    }

    /**
     * 建链方式，与 `IccoaManager.transportMode()` 同键同文件。
     * 取值 p2p / ap（AP 与 P2P 互斥；历史值 both 由 transportMode() 收敛成二者之一，新代码不要写入）。
     */
    @JvmStatic fun transport(context: Context): String =
        prefs(context).getString("transport", "p2p") ?: "p2p"

    @JvmStatic fun setTransport(context: Context, value: String) {
        prefs(context).edit().putString("transport", value).apply()
    }

    // ---- 校验代理（ApReusePolicy 是 package-private，UI 在别的包，经这里转手） ----

    @JvmStatic fun isValidSsid(ssid: String?): Boolean = ApReusePolicy.isValidSsid(ssid)

    @JvmStatic fun isValidPassphrase(password: String?): Boolean = ApReusePolicy.isValidPassphrase(password)

    /** 脱敏（诊断日志用）：只露尾 4 位，短串全掩。 */
    @JvmStatic
    fun mask(ssid: String?): String =
        if (ssid.isNullOrEmpty() || ssid.length <= 4) "****" else "****${ssid.takeLast(4)}"
}
