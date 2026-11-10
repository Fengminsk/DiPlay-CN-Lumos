package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcarxAmbientSpectrumTest {
    private fun bands(vararg levels: Int) = IntArray(AmbientMusicBandAnalyzer.BAND_COUNT) { index ->
        levels.getOrElse(index) { 1 }
    }

    @Test fun bassEnergyLeansWarm() {
        val hue = EcarxAmbientSpectrum.hue(bands(15, 12, 6))
        assertNotNull(hue)
        assertTrue("bass hue $hue", hue!! < 80.0)
    }

    @Test fun trebleEnergyLeansCool() {
        val hue = EcarxAmbientSpectrum.hue(IntArray(16) { if (it >= 13) 15 else 1 })
        assertNotNull(hue)
        assertTrue("treble hue $hue", hue!! > 220.0)
    }

    @Test fun silenceKeepsThePreviousColour() {
        assertNull(EcarxAmbientSpectrum.hue(bands()))
    }

    @Test fun hueSmoothingTakesTheShortestArc() {
        // 350° to 10° must cross zero, not sweep backwards through 180°.
        val step = EcarxAmbientSpectrum.smooth(350.0, 10.0, 50)
        assertTrue("step $step", step > 350.0 || step < 10.0)
        assertEquals(10.0, EcarxAmbientSpectrum.smooth(350.0, 10.0, 5_000), 0.01)
    }

    @Test fun rgbPacksOpaqueChannels() {
        assertEquals(0xFF0000, EcarxAmbientSpectrum.rgb(0.0, 1.0))
        assertEquals(0x00FF00, EcarxAmbientSpectrum.rgb(120.0, 1.0))
        assertEquals(0x0000FF, EcarxAmbientSpectrum.rgb(240.0, 1.0))
    }

    @Test fun saturationGrowsWithTheLoudestBand() {
        assertTrue(EcarxAmbientSpectrum.saturation(bands(2)) < EcarxAmbientSpectrum.saturation(bands(15)))
    }
}
