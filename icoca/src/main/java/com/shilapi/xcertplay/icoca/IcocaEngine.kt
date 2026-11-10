package com.shilapi.xcertplay.icoca

import android.content.Context
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.miauto.app.DeviceDisplay

/**
 * Process-wide entry point to the ICCOA engine.
 *
 * [IcocaBridge] owns the SDK state machine and expects one instance per process; this hands the
 * rest of the app a stable way to start and stop it without knowing about the ported classes.
 */
object IcocaEngine {

    @Volatile
    private var instance: IcocaBridge? = null

    @JvmStatic
    fun bridge(context: Context): IcocaBridge =
        instance ?: synchronized(this) {
            instance ?: IcocaBridge(context.applicationContext).also { instance = it }
        }

    /**
     * Starts BLE advertising so a phone can find the head unit.
     *
     * The canvas size is reported here because the SDK negotiates it before any surface exists;
     * the projection page updates it again when it takes over.
     */
    fun start(context: Context, wiredOnly: Boolean = false) {
        val engine = bridge(context)
        val size = DeviceDisplay.realSizePx(context)
        if (size[0] > 0 && size[1] > 0) engine.setScreenSize(size[0], size[1])
        engine.start(wiredOnly)
    }

    fun stop(context: Context) {
        bridge(context).stop()
    }

    /**
     * Re-arms the wired (AOA) scan after the app obtained USB permission for an attached phone.
     *
     * The SDK's scan thread polls forever but only accepts devices it already has permission for,
     * and it never prompts by itself, so this is the point where a wired phone becomes visible.
     */
    fun refreshUsb(context: Context, force: Boolean = false) {
        bridge(context).manager.refreshUsbTransport(force)
    }

    fun isStarted(context: Context): Boolean = bridge(context).isStarted
}
