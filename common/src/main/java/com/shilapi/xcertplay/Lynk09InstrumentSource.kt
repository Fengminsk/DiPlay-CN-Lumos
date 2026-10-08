package com.shilapi.xcertplay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.transport.VehicleGear
import com.shilapi.xcertplay.transport.VehicleSpeedReading
import com.shilapi.xcertplay.transport.VehicleSpeedSample
import com.shilapi.xcertplay.transport.VehicleSpeedSource

/** Reads only CarsBridge's instrument snapshot, using its existing vehicle-service session. */
internal class Lynk09InstrumentSource(context: Context) : VehicleSpeedSource {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var running = false
    private val samples = ArrayList<VehicleSpeedSample>()
    private var gear: VehicleGear? = null

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        generation++
        samples.clear()
        gear = null
        handler.post { poll(generation) }
    }

    @Synchronized
    override fun stop() {
        running = false
        generation++
        samples.clear()
        gear = null
    }

    @Synchronized
    override fun drain(): VehicleSpeedReading? {
        val current = gear ?: return null
        if (samples.isEmpty()) return null
        return VehicleSpeedReading(current, samples.toList()).also { samples.clear() }
    }

    private fun poll(requestGeneration: Int) {
        if (!isCurrent(requestGeneration)) return
        val intent = Intent(ACTION).apply {
            component = ComponentName(BRIDGE_PACKAGE, RECEIVER)
            putExtra("command", "read_instrument_status")
        }
        try {
            app.sendOrderedBroadcast(intent, null, object : BroadcastReceiver() {
                override fun onReceive(context: Context?, resultIntent: Intent?) {
                    if (!isCurrent(requestGeneration)) return
                    val reading = if (resultCode == Activity.RESULT_OK) {
                        Lynk09InstrumentReading.parse(resultData)
                    } else null
                    if (reading != null) synchronized(this@Lynk09InstrumentSource) {
                        if (gear != reading.gear) samples.clear()
                        gear = reading.gear
                        if (samples.size == MAX_SAMPLES) samples.removeAt(0)
                        samples += VehicleSpeedSample(SystemClock.elapsedRealtime(), reading.speedKph / 3.6)
                    }
                    handler.postDelayed({ poll(requestGeneration) }, POLL_MILLIS)
                }
            }, handler, Activity.RESULT_CANCELED, null, null)
        } catch (error: RuntimeException) {
            Log.w(TAG, "CarsBridge instrument request failed", error)
            handler.postDelayed({ poll(requestGeneration) }, POLL_MILLIS)
        }
    }

    @Synchronized
    private fun isCurrent(requestGeneration: Int): Boolean = running && generation == requestGeneration

    private companion object {
        const val TAG = "DiPlay-Lynk09"
        const val BRIDGE_PACKAGE = "cn.lumos.carsbridge"
        const val RECEIVER = "$BRIDGE_PACKAGE.HvacCommandReceiver"
        const val ACTION = "$BRIDGE_PACKAGE.HVAC_COMMAND"
        const val POLL_MILLIS = 1_000L
        const val MAX_SAMPLES = 40
    }
}

/** CarsBridge's ordered-broadcast result is line-oriented; incomplete readings are discarded. */
internal data class Lynk09InstrumentReading(val gear: VehicleGear, val speedKph: Double) {
    companion object {
        private val gearLine = Regex("(?m)^gear=(P|R|N|D|[1-9]|10)\\r?$")
        private val speedLine = Regex("(?m)^speed=([0-9]+(?:\\.[0-9]+)?) km/h\\r?$")

        fun parse(result: String?): Lynk09InstrumentReading? {
            if (result == null || !result.startsWith("成功 ")) return null
            val gear = when (gearLine.findAll(result).singleOrNull()?.groupValues?.get(1)) {
                "P" -> VehicleGear.PARK
                "R" -> VehicleGear.REVERSE
                "N" -> VehicleGear.NEUTRAL
                "D", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10" -> VehicleGear.DRIVE
                else -> return null
            }
            val speed = speedLine.findAll(result).singleOrNull()?.groupValues?.get(1)
                ?.toDoubleOrNull() ?: return null
            if (!speed.isFinite() || speed !in 0.0..300.0) return null
            return Lynk09InstrumentReading(gear, speed)
        }
    }
}
