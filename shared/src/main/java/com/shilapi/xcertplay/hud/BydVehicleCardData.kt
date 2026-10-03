package com.shilapi.xcertplay.hud

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb

/**
 * Optional, needs ADB over network: a light poller for the dashboard's vehicle-status card.
 * Speed and gear come from the same autoservice reads the wheel-speed feature uses; the battery
 * uses the shared battery reader and is refreshed on a slower cadence and cached between polls.
 */
object BydVehicleCardData {
    private const val TAG = "DiPlay-VehicleCard"
    private const val FAST_MILLIS = 2_000L
    private const val BATTERY_MILLIS = 30_000L

    data class Snapshot(
        val speedKmh: Double? = null,
        val gear: String? = null,
        val batteryPercent: Double? = null,
        val rangeKm: Int? = null,
        val remainingKwh: Double? = null,
        val charging: Boolean? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-vehicle-card").apply { isDaemon = true }
    }
    private val shell = BydAdbShell(TAG)
    private var running = false
    private var listener: ((Snapshot) -> Unit)? = null
    private var appContext: Context? = null

    @Volatile private var latest = Snapshot()
    @Volatile private var batteryAt = 0L

    fun start(context: Context, onUpdate: (Snapshot) -> Unit) {
        appContext = context.applicationContext
        listener = onUpdate
        if (running) return
        running = true
        worker.execute(::loop)
    }

    fun stop() {
        running = false
        listener = null
        appContext = null
    }

    private fun loop() {
        while (running) {
            val app = appContext ?: break
            val now = System.currentTimeMillis()
            val speedRaw = BydParcel.value(shell.run(app, BydWheelSpeed.SPEED))
            val speed = if (speedRaw != null) speed(speedRaw) else null
            val gear = BydWheelSpeed.gear(shell.run(app, BydWheelSpeed.GEAR))?.name?.substring(0, 1)
            var snapshot = latest.copy(speedKmh = speed, gear = gear)
            if (now - batteryAt >= BATTERY_MILLIS) {
                runCatching { BydBattery.read { command -> shell.run(app, command) } }
                    .getOrNull()
                    ?.let { reading ->
                        batteryAt = now
                        snapshot = snapshot.copy(
                            batteryPercent = reading.percent,
                            rangeKm = reading.rangeKm,
                            remainingKwh = reading.remainingKwh,
                            charging = reading.charging,
                        )
                    }
            }
            if (snapshot != latest) {
                latest = snapshot
                val deliver = snapshot
                main.post { listener?.invoke(deliver) }
            }
            Thread.sleep(FAST_MILLIS)
        }
        shell.close()
    }

    private fun speed(raw: Long): Double? = raw.toDouble().let {
        // The float arrives as raw bits in the parcel value on some builds; both shapes stay sane here.
        val kmh = Float.fromBits(raw.toInt()).toDouble()
        if (kmh in 0.0..300.0) kmh else null
    }
}
