package com.blindtechnexus.openfire.audio

import kotlin.math.sqrt

/**
 * A small, allocation-free-per-sample headphone spatializer. It approximates
 * interaural time differences and pinna reflections; it is not a measured,
 * individualized HRTF. Negative z is in front of the listener, positive z is
 * behind. The same convention is used by AudioManager's positional API.
 *
 * Each voice owns its history. Stereo speakers and mono playback still get a
 * usable signal, but front/back localization depends on the listener, their
 * headphones and device processing and must be auditioned on real hardware.
 */
data class BinauralPosition(val pan: Float, val rear: Float) {
    companion object {
        fun fromCoordinates(x: Float, z: Float): BinauralPosition? {
            if (!x.isFinite() || !z.isFinite()) return null
            val distance = sqrt(x * x + z * z)
            if (!distance.isFinite() || distance < 0.001f) return null
            return BinauralPosition((x / distance).coerceIn(-1f, 1f), (z / distance).coerceIn(-1f, 1f))
        }
    }
}

internal class BinauralFilter {
    companion object {
        // About 0.63 ms at 44.1 kHz, a plausible maximum interaural delay.
        const val MAX_EAR_DELAY = 28
        // A short pinna reflection gives front and rear different spectra.
        const val REFLECTION_DELAY = 8
        private const val HISTORY_SIZE = 64
        private const val SMOOTH = 0.02f
    }

    private val history = FloatArray(HISTORY_SIZE)
    private var cursor = 0
    private var pan = 0f
    private var rear = 0f

    /** Adds a processed frame to [out], preserving history across blocks. */
    fun add(sample: Float, position: BinauralPosition, leftGain: Float, rightGain: Float, out: FloatArray, offset: Int) {
        pan += (position.pan - pan) * SMOOTH
        rear += (position.rear - rear) * SMOOTH
        history[cursor] = sample
        val leftDelay = if (pan > 0f) (pan * MAX_EAR_DELAY).toInt() else 0
        val rightDelay = if (pan < 0f) (-pan * MAX_EAR_DELAY).toInt() else 0
        // The sign of the reflection flips between front and rear. Normalize
        // for roughly constant broadband power, without a hidden volume bus.
        val reflection = -0.35f * rear
        fun ear(delay: Int): Float {
            val direct = history[(cursor - delay + HISTORY_SIZE) % HISTORY_SIZE]
            val reflected = history[(cursor - delay - REFLECTION_DELAY + HISTORY_SIZE) % HISTORY_SIZE]
            return (direct + reflection * reflected) / 1.06f
        }
        out[offset] += ear(leftDelay) * leftGain
        out[offset + 1] += ear(rightDelay) * rightGain
        cursor = (cursor + 1) % HISTORY_SIZE
    }
}
