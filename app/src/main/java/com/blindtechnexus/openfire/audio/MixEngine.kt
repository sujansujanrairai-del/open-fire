package com.blindtechnexus.openfire.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
 *    and are picked up by the mixer at the start of the next block;
 *  - pausing freezes every voice in place and resuming continues it exactly.
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
            val nextIndex = if (index + 1 < total) index + 1 else if (voice.loop) 0 else index
            val first = samples[index].toFloat()
            val sample = first + (samples[nextIndex] - first) * fraction

            currentLeft += (targetLeft - currentLeft) * GAIN_SMOOTHING
            currentRight += (targetRight - currentRight) * GAIN_SMOOTHING
            val scaled = sample * fade / 32768f
            out[frame * 2] += scaled * currentLeft
            out[frame * 2 + 1] += scaled * currentRight

            position += step
            played++
        }
        voice.position = position
        voice.playedFrames = played
        voice.currentLeft = currentLeft
        voice.currentRight = currentRight
        return true
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

    private fun createTrack(): AudioTrack? = try {
        val minBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BLOCK_FRAMES * CHANNELS * 2 * 4)
        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            minBytes,
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
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
