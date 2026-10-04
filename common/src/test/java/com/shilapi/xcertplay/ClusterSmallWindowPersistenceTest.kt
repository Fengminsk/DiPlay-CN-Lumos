package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ClusterSmallWindowPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().apply()
    }

    @Test fun defaultsAreOffAndRightOfCentre() {
        assertFalse(AirPlayPersistence.loadClusterSmallWindowMarker(context))
        assertEquals(3, AirPlayPersistence.loadClusterSmallWindowMarkerHorizontalStep(context))
        assertEquals(0, AirPlayPersistence.loadClusterSmallWindowMarkerVerticalStep(context))
    }

    @Test fun stepsRoundTripIndependentlyOfTheFullScreenMarker() {
        AirPlayPersistence.saveClusterMarkerHorizontalStep(context, -2)
        AirPlayPersistence.saveClusterSmallWindowMarker(context, true)
        AirPlayPersistence.saveClusterSmallWindowMarkerHorizontalStep(context, 4)
        AirPlayPersistence.saveClusterSmallWindowMarkerVerticalStep(context, -3)

        assertTrue(AirPlayPersistence.loadClusterSmallWindowMarker(context))
        assertEquals(4, AirPlayPersistence.loadClusterSmallWindowMarkerHorizontalStep(context))
        assertEquals(-3, AirPlayPersistence.loadClusterSmallWindowMarkerVerticalStep(context))
        assertEquals(-2, AirPlayPersistence.loadClusterMarkerHorizontalStep(context))
    }

    @Test fun outOfRangeStepsClampToTheMarkerGrid() {
        AirPlayPersistence.saveClusterSmallWindowMarkerHorizontalStep(context, 99)
        AirPlayPersistence.saveClusterSmallWindowMarkerVerticalStep(context, -99)
        assertEquals(4, AirPlayPersistence.loadClusterSmallWindowMarkerHorizontalStep(context))
        assertEquals(-3, AirPlayPersistence.loadClusterSmallWindowMarkerVerticalStep(context))
    }

    @Test fun turnCardKeepsASecondRectForTheSmallWindow() {
        assertEquals(76, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(24, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(40, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))

        AirPlayPersistence.saveClusterTurnCardOverlayXPercent(context, 20)
        AirPlayPersistence.saveClusterSmallWindowCardXPercent(context, 88)
        AirPlayPersistence.saveClusterSmallWindowCardYPercent(context, 18)
        AirPlayPersistence.saveClusterSmallWindowCardSizePercent(context, 35)

        assertEquals(88, AirPlayPersistence.loadClusterSmallWindowCardXPercent(context))
        assertEquals(18, AirPlayPersistence.loadClusterSmallWindowCardYPercent(context))
        assertEquals(35, AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context))
        assertEquals(20, AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context))
    }

    @Test fun smallWindowCardSavesNotifyTheHostWithoutReconnecting() {
        var noticed = 0
        AirPlayPersistence.overlaySettingsListener = { noticed++ }
        try {
            AirPlayPersistence.saveClusterSmallWindowCardXPercent(context, 60)
            AirPlayPersistence.saveClusterSmallWindowCardYPercent(context, 40)
            AirPlayPersistence.saveClusterSmallWindowCardSizePercent(context, 50)
        } finally {
            AirPlayPersistence.overlaySettingsListener = null
        }
        assertEquals(3, noticed)
    }
}
