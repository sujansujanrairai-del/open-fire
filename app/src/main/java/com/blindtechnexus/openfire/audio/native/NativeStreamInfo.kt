package com.blindtechnexus.openfire.audio.native

/**
 * Live information about the native output stream.
 *
 * The values come straight from Oboe: the sample rate the device really
 * opened, the size of one burst, whether the stream got the exclusive
 * low-latency path, and how many voices the mixer is rendering right now.
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
    val xrunCount: Int
) {
    /** One line, spoken by the audio diagnostics of the guide. */
    val summary: String
        get() = "$sampleRate Hz, $framesPerBurst frames per burst, " +
            if (isLowLatency) "low latency" else "shared" +
            ", $activeVoices voices"
}
