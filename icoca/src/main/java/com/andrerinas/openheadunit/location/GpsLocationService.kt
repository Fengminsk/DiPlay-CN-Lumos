package com.andrerinas.openheadunit.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.andrerinas.openheadunit.utils.AppLog

class GpsLocationService : Service() {
    private var gpsLocation: GpsLocation? = null

    override fun onCreate() {
        super.onCreate()
        // 服务被创建/重建的那一刻必须留痕：进程被杀后定位是在什么时候重新起来的，
        // 是判断「断连时定位是否也一起没了」的时间基准。
        AppLog.i("GpsLocationService: onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("GpsLocationService: onStartCommand startId=$startId flags=$flags"
                + " 复用实例=${gpsLocation != null}")
        if (gpsLocation == null) {
            gpsLocation = GpsLocation(this)
        }

        gpsLocation?.start()

        // 不用 START_STICKY：本服务的生命周期由调用方显式 start/stop（AapService 连接时、
        // IccoaManager 随链路起停）。粘性重启会让它在链路已经结束时自己又跑起来，
        // 而那边的 locationFeedRunning 还以为是"停着的"，两边从此对不上。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 进程被杀 / 链路结束时都会走到这里，但 onDestroy 不保证被调用（强杀就是直接没）。
        // 所以「有没有这一行」本身就是判据：断连现场附近若没有它，说明定位采样还在跑。
        AppLog.i("GpsLocationService: onDestroy")
        super.onDestroy()

        gpsLocation?.stop()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        AppLog.i("GpsLocationService: onTaskRemoved（任务被划掉，服务可能被回收）")
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, GpsLocationService::class.java)
        }
    }

}
