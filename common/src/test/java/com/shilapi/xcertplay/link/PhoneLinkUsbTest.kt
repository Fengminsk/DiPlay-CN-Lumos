package com.shilapi.xcertplay.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PhoneLinkUsbTest {
    @Test fun closingTheHomeScreenInterruptsTheUsbPollerWithoutCrashingTheProcess() {
        val previousPollers = Thread.getAllStackTraces().keys.filter { it.name == "PhoneLinkUsb" }.toSet()
        val watcher = PhoneLinkUsb.attach(RuntimeEnvironment.getApplication())
        val poller = Thread.getAllStackTraces().keys.single { it.name == "PhoneLinkUsb" && it !in previousPollers }
        val failure = AtomicReference<Throwable>()
        poller.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> failure.set(error) }
        try {
            assertTrue(PhoneLinkUsb.isWatching())
            watcher.close()
            poller.join(2_000)
            assertFalse(poller.isAlive)
            assertNull(failure.get())
        } finally {
            watcher.close()
        }
    }
}
