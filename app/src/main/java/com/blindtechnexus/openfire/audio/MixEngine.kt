package com.blindtechnexus.openfire.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp

/**
 * Decoded, immutable sound: mono 16-bit samples at [MixEngine.SAMPLE_RATE].
 * Mono keeps every clip small enough to hold all sounds in memory at once;
 * stereo placement is applied per voice by its left and right gains.
 */
class PcmClip(val samples: ShortArray) {
    val frames: Int get() = samples.size

    val durationMs: Long
        get() = frames * 1000L / MixEngine.SAMPLE_RATE
}

/**
 * One sound playing inside the mixer.
 *
 * Gains and rate are written by the game thread and read by the mixer thread,
 * so they are volatile. Everything else is owned by the mixer thread alone.
 *
 * @property key the sound key the voice was requested under (for stopping).
 * @property owner the fighter that owns a weapon voice, or an empty string.
 * @property loop true for a held sound that repeats until it is stopped.
 * @property deadlineFrames a one-shot that is faded out once it has played this long.
 */
class Voice(
    val id: Int,
    val key: String,
    val owner: String,
    val clip: PcmClip,
    val loop: Boolean,
    startFrame: Double,
    val deadlineFrames: Long,
    val enqueuedAtMs: Long
) {
    @Volatile var targetLeft: Float = 1f
    @Volatile var targetRight: Float = 1f
    @Volatile var rate: Float = 1f
    @Volatile var spatialPosition: BinauralPosition? = null
    internal var binauralFilter: BinauralFilter? = null

    /** Set by the game thread: the voice fades out and is removed. */
    @Volatile var stopping: Boolean = false

    internal var position: Double = startFrame
    internal var playedFrames: Long = 0L
    internal var currentLeft: Float = 0f
    internal var currentRight: Float = 0f
    internal var fadeLeft: Int = -1
}

/**
 * A software mixer that plays any number of sounds at the same time.
 *
 * Every sound is a [Voice] that reads a decoded [PcmClip]. A single thread
 * owns the audible voice list and renders it into one AudioTrack buffer, so:
 *
 *  - a new sound never waits for a free platform stream, and no sound is ever
 *    cut to make room for another one - voices are summed;
 *  - the game thread never blocks on audio: requests go into a lock-free queue
 *    and are picked up by the mixer at the start of the next block (10 ms);
 *  - pausing freezes every voice in place and resuming continues it exactly;
 *  - output latency is bounded by [MAX_TRACK_BUFFER_MS] on purpose. Some
 *    devices report a huge AudioTrack minimum buffer (up to about a second
 *    of audio), and filling that buffer before anything is heard is exactly
 *    what delayed every shot, every click and every menu sound. The track is
 *    therefore created with the smallest buffer that works, preferring the
 *    low-latency performance mode, and a stop is heard within one block.
 *
 * The mixing arithmetic lives in [renderBlock], which has no Android
 * dependency, so the behaviour is verified on the JVM.
 */
class MixEngine(private val maxVoices: Int = MAX_VOICES) {

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2

        /** Upper bound of voices mixed at the same time (the rest wait or are evicted). */
        const val MAX_VOICES = 2048

        /** Frames rendered per buffer: 10 ms, so a stop is heard within one block. */
        const val BLOCK_FRAMES = 441

        /**
         * The largest AudioTrack buffer this engine ever asks for, in bytes.
         * Forty milliseconds of audio: any device minimum above it is refused
         * on the fast paths and only accepted on the slowest fallback, so the
         * hardware can never add a perceptible delay to an interaction.
         */
        val MAX_TRACK_BUFFER_BYTES: Int = SAMPLE_RATE / 25 * CHANNELS * 2

        /** Length of the fade used when a voice stops or runs out. */
        const val FADE_FRAMES = 256

        /** Gain changes glide toward their target so a moving sound never clicks. */
        const val GAIN_SMOOTHING = 0.08f

        /** Requests that wait longer than this (for example during a pause) are dropped. */
        const val STALE_REQUEST_MS = 3_000L

        /** Samples above this level are bent smoothly towards full scale instead of clipped. */
        private const val KNEE = 0.8f
    }

    private val nextId = AtomicInteger(1)

    /** Every voice that is still audible or still fading, by id. Written by any thread. */
    val live: ConcurrentHashMap<Int, Voice> = ConcurrentHashMap()

    /** New voices handed over by the game thread, drained by the mixer. */
    private val incoming = ConcurrentLinkedQueue<Voice>()

    /** The voices the mixer thread renders. Touched by the mixer thread only. */
    private val active = ArrayList<Voice>()

    /** Master SFX bus; changing it also affects voices already playing. */
    @Volatile
    var sfxGain: Float = 1f

    private var currentSfxGain: Float = 1f

    @Volatile
    var paused: Boolean = false

    @Volatile
    private var running = false

    @Volatile
    private var thread: Thread? = null

    fun newId(): Int = nextId.getAndUpdate { if (it >= Int.MAX_VALUE - 1) 1 else it + 1 }

    /** Queues a voice for mixing. Never blocks. */
    fun add(voice: Voice) {
        live[voice.id] = voice
        incoming.add(voice)
    }

    /** Fades one voice out. Never blocks. */
    fun stop(id: Int) {
        live.remove(id)?.stopping = true
    }

    /** Fades out every voice that matches [predicate]. */
    fun stopWhere(predicate: (Voice) -> Boolean) {
        for (voice in live.values) {
            if (predicate(voice)) stop(voice.id)
        }
    }

    /** Starts the AudioTrack thread once. Safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        val worker = Thread({ runOutput() }, "OpenFireMixer")
        worker.isDaemon = true
        thread = worker
        worker.start()
    }

    /** True while the output thread runs. */
    fun isRunning(): Boolean = running

    /** Stops the output thread. Voices are dropped. */
    @Synchronized
    fun shutdown() {
        running = false
        thread = null
        live.clear()
        incoming.clear()
    }

    /**
     * Renders [frames] stereo frames into [out] (interleaved, `frames * 2`
     * floats). Exposed for the JVM tests and used by the output thread.
     */
    fun renderBlock(out: FloatArray, frames: Int, nowMs: Long = System.currentTimeMillis()) {
        java.util.Arrays.fill(out, 0, frames * CHANNELS, 0f)
        if (paused) return
        drainIncoming(nowMs)
        val iterator = active.iterator()
        while (iterator.hasNext()) {
            val voice = iterator.next()
            if (!mixVoice(voice, out, frames)) {
                iterator.remove()
                live.remove(voice.id, voice)
            }
        }
        val targetGain = VolumeMixer.clamp(sfxGain)
        for (frame in 0 until frames) {
            currentSfxGain += (targetGain - currentSfxGain) * GAIN_SMOOTHING
            out[frame * 2] *= currentSfxGain
            out[frame * 2 + 1] *= currentSfxGain
        }
    }

    /** Converts a rendered float block to 16-bit PCM with a soft knee above [KNEE]. */
    fun toPcm16(input: FloatArray, frames: Int, output: ShortArray) {
        for (index in 0 until frames * CHANNELS) {
            output[index] = (softLimit(input[index]) * 32767f).toInt().toShort()
        }
    }

    /** How many voices the mixer currently renders (for diagnostics and tests). */
    fun activeVoiceCount(): Int = active.size

    private fun drainIncoming(nowMs: Long) {
        while (true) {
            val voice = incoming.poll() ?: break
            if (voice.stopping && voice.fadeLeft < 0) {
                // Stopped before it ever sounded: nothing to render.
                live.remove(voice.id, voice)
                continue
            }
            if (!voice.loop && nowMs - voice.enqueuedAtMs > STALE_REQUEST_MS) {
                live.remove(voice.id, voice)
                continue
            }
            if (active.size >= maxVoices) {
                if (!makeRoomFor(voice)) {
                    live.remove(voice.id, voice)
                    continue
                }
            }
            active.add(voice)
        }
    }

    /**
     * Frees a slot for a new voice by fading out the quietest one-shot that is
     * already audible. Loops are never evicted, so a held weapon keeps firing.
     * Returns false when nothing may be evicted.
     */
    private fun makeRoomFor(newcomer: Voice): Boolean {
        var quietest: Voice? = null
        var quietestLevel = Float.MAX_VALUE
        for (candidate in active) {
            if (candidate.loop || candidate.fadeLeft >= 0) continue
            val level = abs(candidate.currentLeft) + abs(candidate.currentRight)
            if (level < quietestLevel) {
                quietestLevel = level
                quietest = candidate
            }
        }
        val victim = quietest ?: return false
        if (quietestLevel > newcomer.targetLeft + newcomer.targetRight) return false
        victim.fadeLeft = FADE_FRAMES
        return true
    }

    /**
     * Adds one voice into the block. Returns false when the voice has finished
     * (or its fade-out is complete) and must be removed.
     */
    private fun mixVoice(voice: Voice, out: FloatArray, frames: Int): Boolean {
        val samples = voice.clip.samples
        val total = samples.size
        if (total <= 0) return false
        if (voice.stopping && voice.fadeLeft < 0) voice.fadeLeft = FADE_FRAMES

        val step = voice.rate.toDouble().coerceIn(0.05, 4.0)
        var position = voice.position
        var played = voice.playedFrames
        var currentLeft = voice.currentLeft
        var currentRight = voice.currentRight
        val targetLeft = voice.targetLeft
        val targetRight = voice.targetRight
        val spatialPosition = voice.spatialPosition
        val spatializer = if (spatialPosition != null) {
            voice.binauralFilter ?: BinauralFilter().also { voice.binauralFilter = it }
        } else null

        for (frame in 0 until frames) {
            if (!voice.loop && position >= total) return false
            if (played >= voice.deadlineFrames && voice.fadeLeft < 0) {
                voice.fadeLeft = FADE_FRAMES
            }
            var fade = 1f
            if (voice.fadeLeft >= 0) {
                if (voice.fadeLeft == 0) return false
                fade = voice.fadeLeft.toFloat() / FADE_FRAMES
                voice.fadeLeft--
            }
            if (position < 0.0) {
                // A delayed start (a sound chained after another one): silent
                // until the position reaches the first sample of the clip.
                position += 1.0
                continue
            }
            if (voice.loop && position >= total) position %= total

            val index = position.toInt().coerceIn(0, total - 1)
            val fraction = (position - index).toFloat()
            val sample = interpolate(samples, index, fraction, voice.loop)

            currentLeft += (targetLeft - currentLeft) * GAIN_SMOOTHING
            currentRight += (targetRight - currentRight) * GAIN_SMOOTHING
            val scaled = sample * fade / 32768f
            if (spatializer != null && spatialPosition != null) {
                spatializer.add(scaled, spatialPosition, currentLeft, currentRight, out, frame * 2)
            } else {
                out[frame * 2] += scaled * currentLeft
                out[frame * 2 + 1] += scaled * currentRight
            }

            position += step
            played++
        }
        voice.position = position
        voice.playedFrames = played
        voice.currentLeft = currentLeft
        voice.currentRight = currentRight
        return true
    }

    /**
     * Catmull-Rom interpolation of one sample position.
     *
     * A plain linear interpolation between two samples is audible as a harsh,
     * "chipped" colour change as soon as a rate is not exactly 1, which is
     * precisely the distorted pitch the game must never make. The four-point
     * curve below reconstructs the waveform smoothly, so a rate inside
     * [VolumeMixer.MIN_RATE]..[VolumeMixer.MAX_RATE] is transparent. At an
     * exact sample position (fraction 0) it returns that sample unchanged, so
     * a rate of 1 is bit-identical to the recording.
     */
    private fun interpolate(samples: ShortArray, index: Int, fraction: Float, loop: Boolean): Float {
        val total = samples.size
        if (total <= 0) return 0f
        if (fraction <= 0f) return samples[index.coerceIn(0, total - 1)].toFloat()
        fun at(offset: Int): Float {
            var position = index + offset
            if (loop) {
                position = ((position % total) + total) % total
            } else {
                position = position.coerceIn(0, total - 1)
            }
            return samples[position].toFloat()
        }
        val p0 = at(-1)
        val p1 = at(0)
        val p2 = at(1)
        val p3 = at(2)
        val t = fraction
        val t2 = t * t
        val t3 = t2 * t
        return 0.5f * (
            (2f * p1) +
                (-p0 + p2) * t +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
                (-p0 + 3f * p1 - 3f * p2 + p3) * t3
            )
    }

    private fun runOutput() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        } catch (_: Throwable) {
        }
        val track = createTrack() ?: run {
            running = false
            return
        }
        val floats = FloatArray(BLOCK_FRAMES * CHANNELS)
        val pcm = ShortArray(BLOCK_FRAMES * CHANNELS)
        try {
            track.play()
            while (running) {
                renderBlock(floats, BLOCK_FRAMES)
                toPcm16(floats, BLOCK_FRAMES, pcm)
                val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
            }
        } catch (_: Throwable) {
        } finally {
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            track.release()
            // The mixer thread owns the active list, so it empties it itself.
            active.clear()
            running = false
        }
    }

    /**
     * Creates the output track with the lowest latency the device allows.
     *
     * The AudioTrack buffer is the whole latency budget of the mixer: sound
     * written into a buffer of N milliseconds is heard N milliseconds later.
     * Devices that report a huge minimum buffer (some report close to a
     * second) made every interaction feel delayed by about a second, which is
     * why the buffer is capped at [MAX_TRACK_BUFFER_BYTES] and the low-latency
     * performance mode is preferred. Each step is a fallback for the previous
     * one, so a device that refuses a small buffer still gets sound - just
     * from the classic path.
     */
    private fun createTrack(): AudioTrack? {
        val minBytes = try {
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Throwable) {
            return null
        }
        if (minBytes <= 0) return null

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        // The smallest buffer that still holds two blocks of audio.
        val fastBytes = maxOf(MAX_TRACK_BUFFER_BYTES, BLOCK_FRAMES * CHANNELS * 2 * 2)

        // 1. Low-latency track with a tiny buffer: the fast mixer path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildTrack(attributes, format, fastBytes, lowLatency = true)?.let { return it }
            // 2. Low-latency track at the device minimum.
            buildTrack(attributes, format, minBytes, lowLatency = true)?.let { return it }
        }
        // 3. Classic track, still capped so latency stays bounded.
        buildTrack(attributes, format, fastBytes, lowLatency = false)?.let { return it }
        // 4. Classic track at the device minimum: sound above all.
        return buildTrack(attributes, format, minBytes, lowLatency = false)
    }

    /** Builds one AudioTrack candidate, or null when the device refuses it. */
    private fun buildTrack(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferBytes: Int,
        lowLatency: Boolean
    ): AudioTrack? = try {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
        if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val track = builder.build()
        if (track.state == AudioTrack.STATE_INITIALIZED) {
            track
        } else {
            track.release()
            null
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Compresses peaks above [KNEE] smoothly into the range (-1, 1), so a
     * crowd of loud voices bends instead of clicking.
     */
    internal fun softLimit(sample: Float): Float {
        val magnitude = abs(sample)
        if (magnitude <= KNEE) return sample
        val bent = KNEE + (1f - KNEE) * (1f - exp(-(magnitude - KNEE) * 5f / (1f - KNEE)))
        return if (sample < 0f) -bent else bent
    }
}
