package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UsbBusTraceTest {
    @Test fun serviceBusSnapshotsReachTheCloudConnectionLog() {
        val app: Context = RuntimeEnvironment.getApplication()
        val log = File(File(app.filesDir, "logs"), CarPlayConnectionTrace.FILE_NAME)
        UsbBusTrace(app).use { it.snapshot("transport-error") }
        val contents = log.readText()
        assertTrue(contents.contains("usb bus reason=start state=empty"))
        assertTrue(contents.contains("usb bus reason=transport-error state=empty"))
    }
}
