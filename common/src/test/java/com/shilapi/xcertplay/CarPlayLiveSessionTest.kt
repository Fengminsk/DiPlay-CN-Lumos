package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayLiveSessionTest {
    @Test fun firstFrameGatesFullScreenAndIsClearedOnDisconnect() {
        CarPlayLiveSession.idle()
        CarPlayLiveSession.preparing()
        CarPlayLiveSession.connecting("USB handshake")
        CarPlayLiveSession.connected()
        assertFalse(CarPlayLiveSession.value().firstFrame)

        CarPlayLiveSession.streaming("CarPlay connected")
        assertEquals(CarPlayLiveSession.Phase.Streaming, CarPlayLiveSession.value().phase)
        assertTrue(CarPlayLiveSession.value().firstFrame)

        CarPlayLiveSession.connecting("late handshake status")
        assertEquals(CarPlayLiveSession.Phase.Streaming, CarPlayLiveSession.value().phase)
        CarPlayLiveSession.reconnecting("link lost")
        assertEquals(CarPlayLiveSession.Phase.Connecting, CarPlayLiveSession.value().phase)
        assertFalse(CarPlayLiveSession.value().firstFrame)
        CarPlayLiveSession.reconnecting("Wi-Fi Direct reset", wifiResetRequired = true)
        assertTrue(CarPlayLiveSession.value().wifiResetRequired)
        assertFalse(CarPlayLiveSession.value().firstFrame)
        CarPlayLiveSession.idle()
    }
}
