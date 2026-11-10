package com.shilapi.xcertplay.icoca

import android.content.Context
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.miauto.app.DeviceDisplay

/**
 * Process-wide entry point to the ICCOA engine.
 *
 * [IcocaBridge] owns the SDK state machine and expects one instance per process; this hands the
 * rest of the app a stable way to start and stop it without knowing about the ported classes.
 */
object IcocaEngine {

    private const val WATCHDOG_INTERVAL_MS = 3_000L
    private const val WATCHDOG_SOFT_ATTEMPTS = 3
    private const val APPLE_VENDOR_ID = 0x05AC

    @Volatile
    private var instance: IcocaBridge? = null

    @Volatile
    private var watchdog: Thread? = null

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

    /**
     * Keeps trying to revive the wired link while a phone is plugged in but no session exists.
     *
     * Every exit from the projection page leaves the SDK in a state the app cannot inspect, and a
     * disconnect can leave the manager's own "session still active" flag set. Without this, the
     * next tap does nothing at all: the scan is refused locally and the SDK keeps its stale state.
     */
    fun startWatchdog(context: Context) {
        if (watchdog != null) return
        synchronized(this) {
            if (watchdog != null) return
            val app = context.applicationContext
            watchdog = Thread({
                var attempts = 0
                while (true) {
                    try {
                        Thread.sleep(WATCHDOG_INTERVAL_MS)
                        val engine = bridge(app)
                        val idle = engine.isStarted &&
                            engine.state.value.phase != IcocaBridge.Phase.Connected
                        if (!idle || !hasPhoneAttached(app)) {
                            attempts = 0
                            continue
                        }
                        attempts++
                        if (attempts <= WATCHDOG_SOFT_ATTEMPTS) {
                            engine.manager.refreshUsbTransport(true)
                        } else {
                            // Rebuilding is the only way out once the SDK's own state machine is
                            // wedged; reinitialising is safe because init-once is reset here.
                            runCatching {
                                engine.reinitForOrientation()
                                engine.start(wiredOnly = true)
                            }
                            attempts = 0
                        }
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (t: Throwable) {
                        android.util.Log.w("IcocaEngine", "watchdog tick failed", t)
                    }
                }
            }, "IccoaWiredWatchdog").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.also { it.start() }
        }
    }

    private fun hasPhoneAttached(context: Context): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return runCatching {
            usb.deviceList.values.any { it.vendorId != APPLE_VENDOR_ID }
        }.getOrDefault(false)
    }

    fun isStarted(context: Context): Boolean = bridge(context).isStarted
}
