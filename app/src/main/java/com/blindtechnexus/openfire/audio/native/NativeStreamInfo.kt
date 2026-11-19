package com.blindtechnexus.openfire.audio.native

/**
 * Live information about the native output stream.
 *
 * The values come straight from Oboe: the sample rate the device really
 * opened, the size of one burst, whether the stream got the exclusive
 * low-latency path, and how many voices the mixer is rendering right now.
 *
 * [bufferFrames] is the latency budget of the whole engine: audio written
 * into a buffer of N frames is heard N frames later, so this is the number
 * that says whether an interaction is audible "now" (a few hundred frames) or
 * a fraction of a second late (thousands). [headphones] reports which spatial
 * model is active - the binaural head model, or the loudspeaker one with
 * crossfeed.
 *
 * The diagnostics line of the guide and the tests read this object; nothing
 * in the game loop depends on it.
 */
data class NativeStreamInfo(
    val sampleRate: Int,
    val framesPerBurst: Int,
    val channels: Int,
    val isFloat: Boolean,
    val isLowLatency: Boolean,
    val activeVoices: Int,
    val peakVoices: Int,
    val xrunCount: Int,
    val bufferFrames: Int,
    val headphones: Boolean
) {
    /** Latency of the negotiated buffer, in milliseconds. */
    val bufferLatencyMs: Float
        get() = if (sampleRate > 0) 1000f * bufferFrames / sampleRate else 0f

    /** One line, spoken by the audio diagnostics of the guide. */
    val summary: String
        get() {
            // The path name is built first on purpose: mixing `+` and
            // `if`/`else` in one expression drops the voices count from the
            // low-latency branch.
            val path = if (isLowLatency) "low latency" else "shared"
            val spatial = if (headphones) "headphones" else "speakers"
            val latency = bufferLatencyMs
            return "$sampleRate Hz, $framesPerBurst frames per burst, $path, " +
                "${String.format(java.util.Locale.US, "%.1f", latency)} ms buffer, $spatial, " +
                "$activeVoices voices"
        }
}
