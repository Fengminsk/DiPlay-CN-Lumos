package com.shilapi.xcertplay.media

import kotlin.math.exp

/**
 * A tiny rule-based drum model: it separates kick, snare and hi-hat from the three band groups,
 * tracks their onset intervals for a BPM estimate, and emits a short punch envelope.
 *
 * It is deliberately not a neural network. A 20Hz on-device DSP state machine is predictable on
 * every head unit, needs no model file or runtime, and reacts within one analysis window.
 */
internal class AmbientBeatModel {
    data class Pulse(
        val level: Double,
        val hue: Double?,
        val kick: Boolean,
        val snare: Boolean,
        val hat: Boolean,
        val bpm: Double?,
        val lastEventAt: Long,
    )

    private var bassBaseline = 0.16
    private var midBaseline = 0.16
    private var highBaseline = 0.16
    private var previousBass = 0.0
    private var previousMid = 0.0
    private var previousHigh = 0.0
    private var lastKickAt = -1L
    private var lastSnareAt = -1L
    private var lastHatAt = -1L
    private var lastEventAt = -1L
    private var lastUpdateAt = -1L
    private var intervals = ArrayDeque<Long>()
    private var bpm: Double? = null
    private var punch = 0.0
    private var holdUntil = 0L
    private var decayMillis = KICK_DECAY_MILLIS
    private var hue: Double? = null

    fun reset() {
        bassBaseline = 0.16; midBaseline = 0.16; highBaseline = 0.16
        previousBass = 0.0; previousMid = 0.0; previousHigh = 0.0
        lastKickAt = -1L; lastSnareAt = -1L; lastHatAt = -1L; lastEventAt = -1L; lastUpdateAt = -1L
        intervals.clear(); bpm = null
        punch = 0.0; holdUntil = 0L; decayMillis = KICK_DECAY_MILLIS; hue = null
    }

    fun update(bands: IntArray, nowMillis: Long): Pulse {
        val bass = level(bands, 0, 2)
        val mid = level(bands, 4, 7)
        val high = level(bands, 10, 15)
        val dt = decay(nowMillis)
        // Decay first, then let a new drum replace the old punch instead of being wiped by it.
        if (nowMillis >= holdUntil) punch *= exp(-dt / decayMillis)
        var kick = false
        var snare = false
        var hat = false
        // Kick first: it owns the biggest flash and the warm hue when two drums land together.
        if (onset(bass, previousBass, bassBaseline, nowMillis, lastKickAt, KICK_REFRACTORY_MILLIS)) {
            kick = true
            val previousKick = lastKickAt
            lastKickAt = nowMillis
            punch = 1.0; holdUntil = nowMillis + KICK_HOLD_MILLIS; decayMillis = KICK_DECAY_MILLIS
            hue = KICK_HUE
            trackKick(nowMillis, previousKick)
        } else if (onset(mid, previousMid, midBaseline, nowMillis, lastSnareAt, SNARE_REFRACTORY_MILLIS)) {
            snare = true; lastSnareAt = nowMillis
            punch = maxOf(punch, 0.72); holdUntil = nowMillis + SNARE_HOLD_MILLIS; decayMillis = SNARE_DECAY_MILLIS
            hue = SNARE_HUE
        } else if (onset(high, previousHigh, highBaseline, nowMillis, lastHatAt, HAT_REFRACTORY_MILLIS)) {
            hat = true; lastHatAt = nowMillis
            punch = maxOf(punch, 0.34); holdUntil = nowMillis + HAT_HOLD_MILLIS; decayMillis = HAT_DECAY_MILLIS
            hue = HAT_HUE
        }
        if (kick || snare || hat) lastEventAt = nowMillis
        bassBaseline += (bass - bassBaseline) * BASELINE_RATE
        midBaseline += (mid - midBaseline) * BASELINE_RATE
        highBaseline += (high - highBaseline) * BASELINE_RATE
        previousBass = bass; previousMid = mid; previousHigh = high
        return Pulse(punch.coerceIn(0.0, 1.0), hue, kick, snare, hat, bpm, lastEventAt)
    }

    /** Decays the current punch without new audio, e.g. a buffer gap. */
    fun idle(nowMillis: Long): Pulse {
        val dt = decay(nowMillis)
        if (nowMillis >= holdUntil) punch *= exp(-dt / decayMillis)
        return Pulse(punch.coerceIn(0.0, 1.0), null, false, false, false, bpm, lastEventAt)
    }

    private fun decay(nowMillis: Long): Long {
        val dt = if (lastUpdateAt < 0L) 0L else (nowMillis - lastUpdateAt).coerceAtLeast(0)
        lastUpdateAt = nowMillis
        return dt
    }

    private fun onset(
        value: Double,
        previous: Double,
        baseline: Double,
        nowMillis: Long,
        lastAt: Long,
        refractoryMillis: Long,
    ): Boolean {
        if (lastAt >= 0L && nowMillis - lastAt < refractoryMillis) return false
        return value > baseline + ONSET_MARGIN && value - previous > ONSET_RISE
    }

    /** Mean of the normalised levels in [from]..[to]; all bands sit on the same 1..15 scale. */
    private fun level(bands: IntArray, from: Int, to: Int): Double {
        if (bands.size <= from) return 0.0
        val end = minOf(to, bands.size - 1)
        var sum = 0.0
        for (index in from..end) sum += ((bands[index] - 1) / 14.0).coerceIn(0.0, 1.0)
        return sum / (end - from + 1)
    }

    private fun trackKick(nowMillis: Long, previousKickAt: Long) {
        if (previousKickAt < 0L || nowMillis <= previousKickAt) return
        val interval = nowMillis - previousKickAt
        if (interval in MIN_BEAT_MILLIS..MAX_BEAT_MILLIS) {
            intervals.addLast(interval)
            while (intervals.size > BPM_WINDOW) intervals.removeFirst()
            if (intervals.size >= 3) {
                val mean = intervals.average()
                val stable = intervals.all { kotlin.math.abs(it - mean) <= mean * 0.15 }
                bpm = if (stable) 60_000.0 / mean else null
            }
        } else if (interval > MAX_BEAT_MILLIS) {
            intervals.clear(); bpm = null
        }
    }

    private companion object {
        const val KICK_HUE = 22.0
        const val SNARE_HUE = 205.0
        const val HAT_HUE = 285.0
        const val KICK_HOLD_MILLIS = 90L
        const val SNARE_HOLD_MILLIS = 60L
        const val HAT_HOLD_MILLIS = 35L
        const val KICK_DECAY_MILLIS = 170.0
        const val SNARE_DECAY_MILLIS = 120.0
        const val HAT_DECAY_MILLIS = 70.0
        const val KICK_REFRACTORY_MILLIS = 110L
        const val SNARE_REFRACTORY_MILLIS = 120L
        const val HAT_REFRACTORY_MILLIS = 70L
        const val ONSET_MARGIN = 0.11
        const val ONSET_RISE = 0.07
        const val BASELINE_RATE = 0.08
        const val MIN_BEAT_MILLIS = 300L
        const val MAX_BEAT_MILLIS = 900L
        const val BPM_WINDOW = 4
    }
}
