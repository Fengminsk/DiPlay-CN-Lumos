package com.shilapi.xcertplay.orchestration

/** The accessory display name may follow the Wi-Fi name; identifiers stay independent. */
object CarPlayDeviceName {
    const val DEFAULT = "DiPlay"

    fun fromHotspot(enabled: Boolean, ssid: String?): String {
        if (!enabled || ssid.isNullOrBlank()) return DEFAULT
        if (ssid.toByteArray(Charsets.UTF_8).size !in 1..32 || ssid.any { Character.isISOControl(it) }) return DEFAULT
        return ssid
    }
}
