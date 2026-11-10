package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxAmbientLightPolicyTest {
    @Test fun zoneFollowsTheChosenAreaWhenPublished() {
        val zones = listOf(
            EcarxAmbientLightPolicy.ZONE_ALL,
            EcarxAmbientLightPolicy.ZONE_FRONT,
            EcarxAmbientLightPolicy.ZONE_REAR,
        )
        assertEquals(EcarxAmbientLightPolicy.ZONE_FRONT, EcarxAmbientLightPolicy.zoneForArea(zones, 1))
        assertEquals(EcarxAmbientLightPolicy.ZONE_REAR, EcarxAmbientLightPolicy.zoneForArea(zones, 2))
        assertEquals(EcarxAmbientLightPolicy.ZONE_ALL, EcarxAmbientLightPolicy.zoneForArea(zones, 3))
    }

    @Test fun singleZoneFirmwareFallsBackToAll() {
        assertEquals(
            EcarxAmbientLightPolicy.ZONE_ALL,
            EcarxAmbientLightPolicy.zoneForArea(listOf(EcarxAmbientLightPolicy.ZONE_ALL), 2),
        )
        assertEquals(
            EcarxAmbientLightPolicy.ZONE_NONE,
            EcarxAmbientLightPolicy.zoneForArea(emptyList(), 1),
        )
    }

    @Test fun zeroIsTheFaintFloorNotPowerOff() {
        assertEquals(EcarxAmbientLightPolicy.MIN_BRIGHTNESS, EcarxAmbientLightPolicy.ceilingPercent(0))
        assertEquals(50, EcarxAmbientLightPolicy.ceilingPercent(3))
        assertEquals(100, EcarxAmbientLightPolicy.ceilingPercent(6))
        assertEquals(100, EcarxAmbientLightPolicy.ceilingPercent(99))
    }

    @Test fun dayAndNightCeilingsAreIndependent() {
        assertEquals(6, EcarxAmbientLightPolicy.brightnessLevel(day = 6, night = 2, isNight = false))
        assertEquals(2, EcarxAmbientLightPolicy.brightnessLevel(day = 6, night = 2, isNight = true))
        assertEquals(6, EcarxAmbientLightPolicy.brightnessLevel(day = 9, night = 2, isNight = false))
        assertEquals(0, EcarxAmbientLightPolicy.brightnessLevel(day = 6, night = -3, isNight = true))
    }

    @Test fun colourAndBrightnessStayInRange() {
        assertTrue(EcarxAmbientLightPolicy.validColor(0x00FF00))
        assertFalse(EcarxAmbientLightPolicy.validColor(-1))
        assertTrue(EcarxAmbientLightPolicy.validBrightness(100))
        assertFalse(EcarxAmbientLightPolicy.validBrightness(101))
    }
}
