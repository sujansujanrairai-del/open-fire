package com.blindtechnexus.openfire.audio.native

import android.content.Context
import android.util.Log
import com.blindtechnexus.openfire.audio.SoundLibrary
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Kotlin face of the native Oboe engine (`libopenfire_audio.so`).
 *
 * This class is the *only* place that talks to the native library. Three
 * rules make it fast enough for a game and safe enough for a phone:
 *
 *  1. **No caller ever blocks.** Decoding, opening the stream and re-opening
 *     it after a route change all happen on background lanes. A call from the
 *     UI or gameplay thread is a hash lookup plus one JNI call, never a media
 *     codec, a mutex held across a decode, or an `AAudio` open (which can take
 *     hundreds of milliseconds and used to freeze the game).
 *  2. **A requested sound never waits behind the warm-up.** The catalogue is
 *     decoded in the background on one lane, but a sound the game asks for is
 *     decoded on a *priority* lane immediately, so the first shot of a battle
 *     is heard now - not three seconds later, behind fifty other sounds.
 *  3. **The stream heals itself.** The native engine supervises its own
 *     stream, and this class additionally re-opens it (on a background lane,
 *     rate-limited) when the platform reports that the stream died. A route
 *     change, a headset being plugged in, or a vendor stack reclaiming the
 *     device therefore never leaves the game silent.
 *
 * Everything here is thread-safe. The heavy lifting - mixing, panning,
 * distance, front/rear, the binaural head model and the speaker crossfeed -
 * happens in C++ on the Oboe callback.
 */
class OpenFireAudioEngine(context: Context) {

    companion object {
        private const val TAG = "OpenFireAudio"

        /** How many integers one packed voice carries. */
        private const val PARAM_COUNT = 15

        /** Bus indices, mirroring `enum of_bus` in `of_audio.h`. */
        const val BUS_SFX = 0
        const val BUS_VOICE = 1
        const val BUS_MUSIC = 2

        /** Voice flags, mirroring `enum of_voice_flags` in `of_audio.h`. */
        const val FLAG_LOOP = 1 shl 0
        const val FLAG_STEREO = 1 shl 1
        const val FLAG_SPATIAL = 1 shl 2
        const val FLAG_DEADLINE = 1 shl 3
        const val FLAG_APPROACH = 1 shl 4
        const val FLAG_CRITICAL = 1 shl 5

        /** Owner ids, mirroring `enum of_owner`. */
        const val OWNER_NONE = 0
        const val OWNER_PLAYER = 1
        const val OWNER_ENEMY = 2

        /**
         * True once the native library of this build has been loaded.
         *
         * Loading is attempted when an engine is actually created, never from a
         * static initializer: a plain JVM unit test (and any device without the
         * ABI) must be able to read the pure parts of this class without the
         * class loader failing under it.
         */
        @Volatile
        var libraryLoaded: Boolean = false
            private set

        /** Loads the library once. Returns true when it is usable. */
        private fun ensureLoaded(): Boolean {
            if (libraryLoaded) return true
            synchronized(this) {
                if (libraryLoaded) return true
                libraryLoaded = try {
                    System.loadLibrary("openfire_audio")
                    true
                } catch (error: Throwable) {
                    Log.e(TAG, "The native audio engine is not available", error)
                    false
                }
                return libraryLoaded
            }
        }

        /** How often the live stream is re-checked while the game plays. */
        private const val STREAM_CHECK_INTERVAL_MS = 400L

        /**
         * The rate the native loader resamples to before any stream exists.
         * It mirrors `kPreferredSampleRate` in the engine.
         */
        private const val PREFERRED_RATE = 48_000

        /* ------------------------------------------------------------- natives */

        @JvmStatic private external fun create(): Long
        @JvmStatic private external fun destroy(handle: Long)
        @JvmStatic private external fun start(handle: Long): Int
        @JvmStatic private external fun stopStream(handle: Long)
        @JvmStatic private external fun isRunning(handle: Long): Boolean
        @JvmStatic private external fun loadClip(
            handle: Long,
            samples: ShortArray,
            frames: Int,
            channels: Int,
            sampleRate: Int
        ): Int
        @JvmStatic private external fun unloadClip(handle: Long, clipId: Int)
        @JvmStatic private external fun play(handle: Long, params: IntArray): Int
        @JvmStatic private external fun update(handle: Long, voiceId: Int, params: IntArray)
        @JvmStatic private external fun stop(handle: Long, voiceId: Int)
        @JvmStatic private external fun stopKey(handle: Long, keyId: Int)
        @JvmStatic private external fun stopOwnerKey(handle: Long, owner: Int, keyId: Int)
        @JvmStatic private external fun stopOwner(handle: Long, owner: Int)
        @JvmStatic private external fun stopAll(handle: Long)
        @JvmStatic private external fun setBusGain(handle: Long, bus: Int, gain: Float)
        @JvmStatic private external fun setMasterGain(handle: Long, gain: Float)
        @JvmStatic private external fun setPaused(handle: Long, paused: Boolean)
        @JvmStatic private external fun setOutputMode(handle: Long, headphones: Boolean)
        @JvmStatic private external fun info(handle: Long): NativeStreamInfo?
        @JvmStatic private external fun activeVoices(handle: Long): Int
        @JvmStatic private external fun isKeySounding(handle: Long, keyId: Int): Boolean
        @JvmStatic private external fun isVoiceSounding(handle: Long, voiceId: Int): Boolean

        /**
         * The 32-bit hash of a sound key. Every stop-by-key call uses it, so
         * the native side never has to compare strings.
         */
        fun keyIdOf(key: String): Int = SoundLibrary.keyIdOf(key)
    }

    private val appContext = context.applicationContext

    /** The engine handle, or 0 when the library is missing. Released once. */
    @Volatile
    private var handle: Long = 0L

    /** True when the native engine exists and can accept sounds. */
    @Volatile
    var available: Boolean = false
        private set

    /** True when the engine believes its output stream is running. */
    @Volatile
    var streamRunning: Boolean = false
        private set

    /** Reason the engine is unavailable, for the diagnostics line. */
    @Volatile
    var unavailableReason: String? = null
        private set

    /** Decoded clips by asset file. Never held across a decode. */
    private val clipIds = ConcurrentHashMap<String, Int>()

    /** A decode that is already running, so the same file is decoded once. */
    private val decodesInFlight = ConcurrentHashMap<String, CompletableFuture<Int>>()

    /** The output rate the stream reported, or 0 before it opens. */
    @Volatile
    private var loadedSampleRate: Int = 0

    /**
     * The rate the uploaded clips are actually resampled for.
     *
     * The native loader resamples a clip once, at upload, for the rate of the
     * stream that is running - or, before the first stream opens, for the rate
     * the engine prefers (48 kHz). If the device later opens a stream at
     * another rate, those clips are reloaded instead of being played at the
     * wrong pitch.
     */
    @Volatile
    private var clipRate: Int = 0

    /**
     * Background lane for the catalogue warm-up. Below the normal priority:
     * nothing the player is waiting for runs on this lane, and the first frame
     * of the app must not compete with fifty codec instances.
     */
    private val decodeLane: ExecutorService =
        newLane("OpenFireDecode", Thread.NORM_PRIORITY - 1)

    /**
     * Priority lane: the sounds the game actually asked for. Kept separate
     * from [decodeLane] so a first click is never queued behind the warm-up of
     * the whole catalogue - that queueing was the three-second delay.
     */
    private val requestLane: ExecutorService = newLane("OpenFireRequest")

    /** Owns every stream operation: opening, closing and health checks. */
    private val streamLane: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "OpenFireStream").apply { isDaemon = true }
        }

    /** Per-thread parameter buffers: public calls may arrive concurrently. */
    private val scratch = ThreadLocal.withInitial { IntArray(PARAM_COUNT) }

    /** Guards the "open the stream once" decision. */
    private val streamLock = Any()

    @Volatile
    private var lastStreamCheckMs: Long = 0L

    /** True while the last known output route is headphones or a headset. */
    @Volatile
    var headphonesMode: Boolean = true
        private set

    init {
        if (ensureLoaded()) {
            try {
                handle = create()
            } catch (error: Throwable) {
                Log.e(TAG, "Could not create the native engine", error)
                unavailableReason = error.message ?: "the native engine could not be created"
                handle = 0L
            }
        } else {
            unavailableReason = "the native audio library could not be loaded"
        }

        // An `init` block cannot `return`, so the two outcomes are branches.
        if (handle == 0L) {
            available = false
            unavailableReason = unavailableReason ?: "the native engine is unavailable"
        } else {
            available = true
            // Opening the stream takes milliseconds to hundreds of
            // milliseconds on a cold device. It must never happen on the
            // thread that created the engine (the UI thread), so it is the
            // first job of the stream lane.
            val priority = Thread.NORM_PRIORITY + 2
            submitStream {
                Thread.currentThread().priority = priority
                openStreamNow()
            }
        }
    }

    /**
     * Creates a daemon background lane at [threadPriority].
     *
     * The parameter is not called `priority` on purpose: inside `apply` an
     * unqualified `priority` would resolve to the thread's own property and
     * the assignment would be the silent no-op `thread.priority =
     * thread.priority`.
     */
    private fun newLane(name: String, threadPriority: Int = Thread.NORM_PRIORITY + 1) =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply {
                priority = threadPriority
                isDaemon = true
            }
        }

    /** Submits a stream task, ignoring a submission after [release]. */
    private fun submitStream(task: () -> Unit) {
        try {
            streamLane.execute {
                try {
                    task()
                } catch (error: Throwable) {
                    Log.e(TAG, "Stream management failed", error)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Submits a priority decode, ignoring a submission after [release]. */
    private fun submitRequest(task: () -> Unit) {
        try {
            requestLane.execute {
                try {
                    task()
                } catch (_: Throwable) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Submits a warm-up decode, ignoring a submission after [release]. */
    private fun submitDecode(task: () -> Unit) {
        try {
            decodeLane.execute {
                try {
                    task()
                } catch (_: Throwable) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** Opens the stream on the stream lane. */
    private fun openStreamNow() {
        val current = handle
        if (current == 0L) return
        val result = try {
            start(current)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not start the native stream", error)
            -1
        }
        if (result == 0) {
            streamRunning = true
            unavailableReason = null
            val rate = streamInfo()?.sampleRate ?: 0
            if (rate > 0) {
                loadedSampleRate = rate
                val built = clipRate
                if (clipIds.isNotEmpty() && built != rate) {
                    // Clips are resampled once, at upload. A stream that opened
                    // at a different rate than the one they were built for
                    // (a 44.1 kHz device, or a route change) must be refilled
                    // instead of played at the wrong pitch.
                    Log.i(TAG, "The output rate is $rate Hz; reloading every sound")
                    unloadAllClips()
                    for (file in SoundLibrary.files()) warmUp(file)
                }
            }
        } else {
            // The native supervisor keeps retrying in the background, so this
            // is not fatal: sounds are queued and heard as soon as it opens.
            streamRunning = false
            unavailableReason = "the output stream could not be opened yet"
        }
    }

    /**
     * Makes sure the Oboe stream is running, without ever blocking the caller.
     *
     * The probe is one lock-free boolean read in C++ and is throttled, so a
     * burst of shots costs one call every [STREAM_CHECK_INTERVAL_MS]. A dead
     * stream is handed to the stream lane, never reopened here.
     */
    fun ensureStream(): Boolean {
        if (handle == 0L) return false
        val now = System.nanoTime() / 1_000_000L
        if (now - lastStreamCheckMs < STREAM_CHECK_INTERVAL_MS) return streamRunning
        lastStreamCheckMs = now
        val running = try {
            isRunning(handle)
        } catch (_: Throwable) {
            false
        }
        if (running) {
            streamRunning = true
            return true
        }
        streamRunning = false
        submitStream { openStreamNow() }
        return false
    }

    /* ------------------------------------------------------------------ clips */

    /**
     * True when [file] was already decoded and uploaded. A pure map read: it
     * is the fast path of every play and it never waits for a decoder.
     */
    fun loadClipIfPresent(file: String): Int = clipIds[file] ?: 0

    /** True when [file] was already decoded and uploaded. */
    fun hasClip(file: String): Boolean = clipIds.containsKey(file)

    /**
     * Decodes `assets/sounds/[file]` once and uploads it. Returns the native
     * clip id, or 0 when it cannot be decoded.
     *
     * The decode happens **outside** every lock: two callers that ask for the
     * same file share one decode through [decodesInFlight], and a caller that
     * asks for a different file is never blocked by it. Blocking the UI thread
     * on a media codec (what the old code did) is what froze the game.
     */
    fun loadClip(file: String): Int {
        val existing = clipIds[file]
        if (existing != null) return existing
        val mine = CompletableFuture<Int>()
        val running = decodesInFlight.putIfAbsent(file, mine)
        if (running != null) {
            return try {
                running.get(4, TimeUnit.SECONDS)
            } catch (_: Throwable) {
                0
            }
        }
        var id = 0
        try {
            id = decodeAndUpload(file)
        } finally {
            decodesInFlight.remove(file, mine)
            mine.complete(id)
        }
        return id
    }

    /** Requests a decode on the priority lane, if it is not already there. */
    fun requestClip(file: String) {
        if (handle == 0L) return
        if (clipIds.containsKey(file)) return
        submitRequest { loadClip(file) }
    }

    /** Decodes on the warm-up lane, one file at a time. */
    fun warmUp(file: String) {
        if (handle == 0L) return
        if (clipIds.containsKey(file)) return
        submitDecode { loadClip(file) }
    }

    /** Decodes and uploads one file. Called on a background lane only. */
    private fun decodeAndUpload(file: String): Int {
        val current = handle
        if (current == 0L) return 0
        val decoded = try {
            appContext.assets.openFd("sounds/$file").use { NativePcmDecoder.decode(it) }
        } catch (error: Throwable) {
            Log.e(TAG, "Could not decode sounds/$file", error)
            null
        } ?: return 0
        if (decoded.frames <= 0 || decoded.channels <= 0 ||
            decoded.frames.toLong() * decoded.channels > decoded.samples.size
        ) {
            Log.e(TAG, "sounds/$file decoded to an impossible geometry")
            return 0
        }
        val id = try {
            loadClip(current, decoded.samples, decoded.frames, decoded.channels, decoded.sampleRate)
        } catch (error: Throwable) {
            Log.e(TAG, "Could not upload sounds/$file", error)
            0
        }
        if (id > 0) {
            clipIds[file] = id
            clipRate = loadedSampleRate.takeIf { it > 0 } ?: PREFERRED_RATE
        }
        return id
    }

    /** True while one voice of [key] is still sounding. */
    fun isKeySounding(key: String): Boolean {
        if (handle == 0L) return false
        return try {
            isKeySounding(handle, keyIdOf(key))
        } catch (_: Throwable) {
            false
        }
    }

    /** True while this exact voice id is still in the mixer pool. */
    fun isVoiceSounding(voiceId: Int): Boolean {
        if (handle == 0L || voiceId <= 0) return false
        return try {
            isVoiceSounding(handle, voiceId)
        } catch (_: Throwable) {
            false
        }
    }

    /** Number of assets uploaded to the engine right now. */
    val clipCount: Int get() = clipIds.size

    /** Drops every uploaded clip (a full reset of the audio stack). */
    fun unloadAllClips() {
        val current = handle
        if (current == 0L) return
        val ids = clipIds.values.toList()
        clipIds.clear()
        clipRate = 0
        for (id in ids) {
            try {
                unloadClip(current, id)
            } catch (_: Throwable) {
            }
        }
    }

    /* ----------------------------------------------------------------- voices */

    /**
     * Starts one voice and returns its id (0 when it could not start).
     *
     * The parameters are packed into one small `IntArray` because a primitive
     * array crosses JNI as a single, allocation-free copy - no per-call
     * object, no field lookup on the native side.
     */
    fun play(
        clipId: Int,
        key: String,
        owner: Int,
        flags: Int,
        volume: Float,
        rate: Float,
        pan: Float,
        distance: Float,
        rear: Float,
        approach: Float,
        deadlineMs: Int,
        startOffsetMs: Int,
        maxDistanceM: Int,
        delayMs: Int = 0
    ): Int {
        val current = handle
        if (current == 0L || clipId <= 0) return 0
        pack(clipId, key, owner, flags, volume, rate, pan, distance, rear, approach,
            deadlineMs, startOffsetMs, maxDistanceM, delayMs)
        return try {
            play(current, scratch.get())
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Updates the live parameters of a playing voice. Only the values that can
     * change while a voice sounds are used; the clip and the key are ignored
     * by the native side.
     */
    fun update(
        voiceId: Int,
        key: String,
        owner: Int,
        flags: Int,
        volume: Float,
        rate: Float,
        pan: Float,
        distance: Float,
        rear: Float,
        approach: Float,
        maxDistanceM: Int
    ) {
        val current = handle
        if (current == 0L || voiceId <= 0) return
        pack(0, key, owner, flags, volume, rate, pan, distance, rear, approach, 0, 0,
            maxDistanceM, 0)
        try {
            update(current, voiceId, scratch.get())
        } catch (_: Throwable) {
        }
    }

    fun stopVoice(voiceId: Int) {
        val current = handle
        if (current == 0L || voiceId <= 0) return
        try {
            stop(current, voiceId)
        } catch (_: Throwable) {
        }
    }

    fun stopKey(key: String) {
        val current = handle
        if (current == 0L) return
        try {
            stopKey(current, keyIdOf(key))
        } catch (_: Throwable) {
        }
    }

    fun stopOwnerKey(owner: Int, key: String) {
        val current = handle
        if (current == 0L) return
        try {
            stopOwnerKey(current, owner, keyIdOf(key))
        } catch (_: Throwable) {
        }
    }

    fun stopOwner(owner: Int) {
        val current = handle
        if (current == 0L) return
        try {
            stopOwner(current, owner)
        } catch (_: Throwable) {
        }
    }

    fun stopAll() {
        val current = handle
        if (current == 0L) return
        try {
            stopAll(current)
        } catch (_: Throwable) {
        }
    }

    /* ------------------------------------------------------------------ buses */

    /** Applies a bus gain (0..1). The SFX slider is one call. */
    fun setBusGain(bus: Int, gain: Float) {
        val current = handle
        if (current == 0L) return
        try {
            setBusGain(current, bus, gain.coerceIn(0f, 1f))
        } catch (_: Throwable) {
        }
    }

    fun setMasterGain(gain: Float) {
        val current = handle
        if (current == 0L) return
        try {
            setMasterGain(current, gain.coerceIn(0f, 1f))
        } catch (_: Throwable) {
        }
    }

    fun setPaused(paused: Boolean) {
        val current = handle
        if (current == 0L) return
        try {
            setPaused(current, paused)
        } catch (_: Throwable) {
        }
    }

    /**
     * Chooses the spatial model: binaural on headphones, crossfeed on
     * loudspeakers. Safe to call from a route-change callback.
     */
    fun setOutputMode(headphones: Boolean) {
        headphonesMode = headphones
        val current = handle
        if (current == 0L) return
        try {
            setOutputMode(current, headphones)
        } catch (_: Throwable) {
        }
    }

    fun isRunning(): Boolean {
        val current = handle
        if (current == 0L) return false
        return try {
            isRunning(current).also { streamRunning = it }
        } catch (_: Throwable) {
            false
        }
    }

    /** Live stream information, or null when the engine is not available. */
    fun streamInfo(): NativeStreamInfo? {
        val current = handle
        if (current == 0L) return null
        return try {
            info(current)
        } catch (_: Throwable) {
            null
        }
    }

    /** Voices the mixer is rendering right now. */
    fun voiceCount(): Int {
        val current = handle
        if (current == 0L) return 0
        return try {
            activeVoices(current)
        } catch (_: Throwable) {
            0
        }
    }

    /** Closes the stream and frees the engine. Every later call is a no-op. */
    fun release() {
        val current = handle
        handle = 0L
        available = false
        streamRunning = false
        loadedSampleRate = 0
        clipIds.clear()
        clipRate = 0
        decodesInFlight.clear()
        try {
            streamLane.shutdownNow()
        } catch (_: Throwable) {
        }
        try {
            decodeLane.shutdownNow()
        } catch (_: Throwable) {
        }
        try {
            requestLane.shutdownNow()
        } catch (_: Throwable) {
        }
        if (current == 0L) return
        try {
            stopStream(current)
        } catch (_: Throwable) {
        }
        try {
            destroy(current)
        } catch (_: Throwable) {
        }
    }

    /**
     * One line that explains the native audio state: the negotiated stream,
     * how many sounds are loaded, and - when it is not working - why.
     */
    fun diagnostics(): String {
        val current = handle
        if (current == 0L) {
            return "Native audio engine unavailable (${unavailableReason ?: "unknown reason"})"
        }
        val line = streamInfo()?.summary
            ?: return "Native audio engine present, stream details unavailable"
        val state = when {
            streamRunning -> "streaming"
            else -> "stream stopped"
        }
        return "$line, $clipCount clips, $state"
    }

    /* ------------------------------------------------------------------ pack */

    /**
     * Packs the parameters in the order the JNI bridge reads them, scaled to
     * integers so a float never has to cross the boundary as an array element.
     */
    private fun pack(
        clipId: Int,
        key: String,
        owner: Int,
        flags: Int,
        volume: Float,
        rate: Float,
        pan: Float,
        distance: Float,
        rear: Float,
        approach: Float,
        deadlineMs: Int,
        startOffsetMs: Int,
        maxDistanceM: Int,
        delayMs: Int
    ) {
        val params = scratch.get()
        params[0] = clipId
        params[1] = keyIdOf(key)
        params[2] = owner
        params[3] = flags
        params[4] = (volume.coerceIn(0f, 8f) * 10_000f).toInt()
        params[5] = (rate.coerceIn(0.1f, 8f) * 10_000f).toInt()
        params[6] = (pan.coerceIn(-1f, 1f) * 10_000f).toInt()
        params[7] = (distance.coerceAtLeast(0f) * 100f).toInt()
        params[8] = (rear.coerceIn(-1f, 1f) * 10_000f).toInt()
        params[9] = (approach.coerceIn(0f, 1f) * 10_000f).toInt()
        params[10] = deadlineMs
        params[11] = startOffsetMs
        params[12] = if (maxDistanceM > 0) maxDistanceM else 25
        params[13] = delayMs.coerceAtLeast(0)
        params[14] = 0
    }
}
