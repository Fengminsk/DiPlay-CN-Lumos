package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientBeatModelTest {
    private fun bands(kick: Boolean = false, snare: Boolean = false, hat: Boolean = false): IntArray {
        val result = IntArray(AmbientMusicBandAnalyzer.BAND_COUNT) { 1 }
        if (kick) for (index in 0..2) result[index] = 15
        if (snare) for (index in 4..7) result[index] = 15
        if (hat) for (index in 10..15) result[index] = 15
        return result
    }

    @Test fun kicksPunchAndEstimateTempo() {
        val model = AmbientBeatModel()
        var lastPulse: AmbientBeatModel.Pulse? = null
        for (time in 0L..2_000L step 50) {
            val kick = time % 500 == 0L
            lastPulse = model.update(bands(kick = kick), time)
            if (kick) assertTrue("kick at $time", lastPulse.kick && lastPulse.level > 0.9)
        }
        assertEquals(120.0, lastPulse!!.bpm!!, 4.0)
    }

    @Test fun hatsGiveASmallerFlashThanKicks() {
        val model = AmbientBeatModel()
        val kick = model.update(bands(kick = true), 0)
        val hat = model.update(bands(hat = true), 500)
        assertTrue(kick.level > hat.level)
        assertTrue(hat.hat)
        assertFalse(hat.kick)
    }

    @Test fun punchDecaysWithoutNewDrums() {
        val model = AmbientBeatModel()
        model.update(bands(kick = true), 0)
        val idle = model.idle(400)
        assertTrue("idle ${idle.level}", idle.level < 0.2)
    }
}
