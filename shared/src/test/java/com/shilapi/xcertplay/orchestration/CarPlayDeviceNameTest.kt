package com.shilapi.xcertplay.orchestration

import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayDeviceNameTest {
    @Test fun exactUtf8HotspotNameIsUsedOnlyWhenEnabled() {
        val name = "领克09-车机"
        assertEquals(name, CarPlayDeviceName.fromHotspot(true, name))
        assertEquals("DiPlay", CarPlayDeviceName.fromHotspot(false, name))
    }

    @Test fun unavailableOrInvalidNameFallsBack() {
        for (ssid in listOf(null, "", "\ncar", "x".repeat(33))) {
            assertEquals("DiPlay", CarPlayDeviceName.fromHotspot(true, ssid))
        }
    }
}
