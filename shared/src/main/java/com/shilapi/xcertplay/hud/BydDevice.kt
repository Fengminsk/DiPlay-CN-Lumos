package com.shilapi.xcertplay.hud

/**
 * True only on firmware that ships BYD's automotive SDK. The BYD HUD, cluster, song and call
 * outputs otherwise keep retrying a SOME/IP service that does not exist (for example on a Huawei
 * pad), so they stay completely idle elsewhere.
 */
internal object BydDevice {
    fun available(): Boolean = runCatching {
        Class.forName("android.hardware.bydauto.BYDAutoDeviceManager")
    }.isSuccess
}
