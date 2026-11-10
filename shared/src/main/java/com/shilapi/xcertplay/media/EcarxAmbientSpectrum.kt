package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns the 16 played-music bands into one continuous RGB colour.
 *
 * The bands are relative levels (1..15) at the frequencies in [AmbientMusicBandAnalyzer]. Their
 * energy-weighted centroid becomes a hue on a warm-to-cool ramp, so a bass-heavy passage leans
 * red/amber and a bright passage leans cyan/blue; the loudest band sets saturation. Only the hue is
 * smoothed, with a short constant so a drop still reads as a drop without strobing.
 */
internal object EcarxAmbientSpectrum {
    private val FREQUENCIES = AmbientMusicBandAnalyzer.FREQUENCIES
    private val LOG_LOW = log2(FREQUENCIES.first().toDouble())
    private val LOG_HIGH = log2(FREQUENCIES.last().toDouble())
    private const val MAX_HUE = 300.0
    private const val HUE_TAU_MILLIS = 160.0
    private const val MAX_HUE_DEGREES_PER_SECOND = 360.0

    /**
     * Null when the window is effectively silent, so the caller keeps its current colour.
     * Levels are dB-compressed, so each step is turned back into an amplitude. Weighting by
     * (level - 1) alone makes most bands equally heavy and pins the hue in the middle.
     */
    fun hue(bands: IntArray): Double? {
        if (bands.isEmpty()) return null
        var weight = 0.0
        var weighted = 0.0
        for (index in bands.indices) {
            val level = (bands[index] - 1).coerceAtLeast(0)
            if (level <= 0) continue
            // About 6 dB per level; a dominant band then outweighs the floor instead of tying it.
            val amplitude = 10.0.pow(level / 6.0)
            weight += amplitude
            weighted += amplitude * index
        }
        if (weight < 0.5) return null
        val position = (weighted / weight) / (bands.size - 1).coerceAtLeast(1)
        val frequency = LOG_LOW + (LOG_HIGH - LOG_LOW) * position
        return ((frequency - LOG_LOW) / (LOG_HIGH - LOG_LOW) * MAX_HUE).coerceIn(0.0, MAX_HUE)
    }

    fun saturation(bands: IntArray): Double {
        val peak = bands.maxOrNull() ?: 1
        val strength = ((peak - 1) / (AmbientMusicBandAnalyzer.MAX_LEVEL - 1).toDouble()).coerceIn(0.0, 1.0)
        return 0.65 + 0.30 * strength
    }

    /**
     * Shortest-arc exponential smoothing with a slew limit, so a passage that jumps between two
     * notes glides instead of flicking. A null [previous] adopts [target] directly.
     */
    fun smooth(previous: Double?, target: Double, elapsedMillis: Long): Double {
        if (previous == null) return normalise(target)
        var delta = normalise(target) - normalise(previous)
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        val elapsed = elapsedMillis.coerceAtLeast(0L)
        val factor = 1.0 - exp(-elapsed / HUE_TAU_MILLIS)
        val maxStep = MAX_HUE_DEGREES_PER_SECOND * elapsed / 1_000.0
        val step = (delta * factor).coerceIn(-maxStep, maxStep)
        return normalise(previous + step)
    }

    /** A stable warm hue for the fixed/idle state, derived from the saved 1..31 colour index. */
    fun staticHue(colorIndex: Int): Double =
        ((colorIndex - 1).coerceIn(0, 30) / 30.0) * MAX_HUE

    fun rgb(hue: Double, saturation: Double, value: Double = 1.0): Int {
        val h = normalise(hue) / 60.0
        val s = saturation.coerceIn(0.0, 1.0)
        val v = value.coerceIn(0.0, 1.0)
        val chroma = v * s
        val x = chroma * (1.0 - abs(h % 2.0 - 1.0))
        val floor = v - chroma
        val (r, g, b) = when (h.toInt().coerceIn(0, 5)) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        fun channel(input: Double) = ((input + floor) * 255.0).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun normalise(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}
