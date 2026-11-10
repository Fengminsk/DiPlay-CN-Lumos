package com.shilapi.xcertplay.hud

/**
 * Fast, side-effect-free probe for the settings UI: whether this head unit ships the ECARX
 * AdaptAPI car-function surface at all. It does not create a car session or read a lamp, so it is
 * safe on the UI thread; the actual support check is [EcarxAmbientLightClient.supported].
 */
object EcarxAmbientLamps {
    fun available(): Boolean = runCatching {
        Class.forName("com.ecarx.xui.adaptapi.car.Car")
        Class.forName("com.ecarx.xui.adaptapi.car.ICar")
        Class.forName("com.ecarx.xui.adaptapi.car.base.ICarFunction")
    }.isSuccess
}
