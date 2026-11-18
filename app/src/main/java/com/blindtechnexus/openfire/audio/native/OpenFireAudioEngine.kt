package com.blindtechnexus.openfire.audio.native

import android.content.Context
import android.util.Log
import com.blindtechnexus.openfire.audio.SoundLibrary

/**
 * Kotlin face of the native Oboe engine (`libopenfire_audio.so`).
 *
 * Responsibilities
 * ----------------
 *  - load the library once and fail softly: a device without the ABI, a
 *    library that cannot be loaded, or an engine that cannot open a stream
 *    simply leaves [available] false, and the caller falls back to the
 *    platform player instead of crashing the game;
 *  - decode the OGG assets through the platform decoder (which is the only
 *    place the game decodes anything) and hand the raw samples to C++ once;
 *  - mirror the small, stateful part of the API: clip ids, voice ids and the
 *    voice parameters, so the rest of the audio stack speaks plain Kotlin.
 *
 * Everything here is thread-safe. The heavy lifting - mixing, panning,
 * distance, the binaural head model - happens in C++ on the Oboe callback.
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
        @JvmStatic private external fun info(handle: Long): NativeStreamInfo?
        @JvmStatic private external fun activeVoices(handle: Long): Int
        @JvmStatic private external fun isKeySounding(handle: Long, keyId: Int): Boolean

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

    /** True when the native engine is loaded and its stream is open. */
    @Volatile
    var available: Boolean = false
        private set

    /** Reason the engine is unavailable, for the diagnostics line. */
    @Volatile
    var unavailableReason: String? = null
        private set

    /** Decoded clips by asset file, guarded by [clipLock]. */
    private val clipIds = HashMap<String, Int>()
    private val clipLock = Any()

    /** Per-thread parameter buffers: public calls may arrive concurrently. */
    private val scratch = ThreadLocal.withInitial { IntArray(PARAM_COUNT) }

    init {
        if (ensureLoaded()) {
            try {
                handle = create()
            } catch (error: Throwable) {
                Log.e(TAG, "Could not create the native engine", error)
                handle = 0L
            }
        } else {
            unavailableReason = "library"
        }
        if (handle != 0L) {
            val result = try {
                start(handle)
            } catch (error: Throwable) {
                Log.e(TAG, "Could not start the native stream", error)
                -1
            }
            available = result == 0
            if (!available) unavailableReason = "stream"
        }
    }

    /* ------------------------------------------------------------------ clips */

    /**
     * Decodes `assets/sounds/[file]` once and loads it into the engine.
     * Returns the native clip id, or 0 when it cannot be decoded.
     */
    fun loadClip(file: String): Int {
        if (handle == 0L) return 0
        synchronized(clipLock) {
            clipIds[file]?.let { return it }
            val decoded = try {
                appContext.assets.openFd("sounds/$file").use { NativePcmDecoder.decode(it) }
            } catch (error: Throwable) {
                Log.e(TAG, "Could not decode sounds/$file", error)
                null
            } ?: return 0
            val id = try {
                loadClip(handle, decoded.samples, decoded.frames, decoded.channels, decoded.sampleRate)
            } catch (error: Throwable) {
                Log.e(TAG, "Could not upload sounds/$file", error)
                0
            }
            if (id > 0) clipIds[file] = id
            return id
        }
    }

    /** True when [file] was already decoded and uploaded. */
    fun hasClip(file: String): Boolean = synchronized(clipLock) { clipIds.containsKey(file) }

    /** The native id of an already uploaded clip, or 0. Never decodes. */
    fun loadClipIfPresent(file: String): Int = synchronized(clipLock) { clipIds[file] ?: 0 }

    /** True while one voice of [key] is still sounding. */
    fun isKeySounding(key: String): Boolean {
        if (handle == 0L) return false
        return try {
            isKeySounding(handle, keyIdOf(key))
        } catch (_: Throwable) {
            false
        }
    }

    /** Drops every uploaded clip (a full reset of the audio stack). */
    fun unloadAllClips() {
        if (handle == 0L) return
        synchronized(clipLock) {
            for (id in clipIds.values) {
                try {
                    unloadClip(handle, id)
                } catch (_: Throwable) {
                }
            }
            clipIds.clear()
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
        if (handle == 0L || clipId <= 0) return 0
        pack(clipId, key, owner, flags, volume, rate, pan, distance, rear, approach,
            deadlineMs, startOffsetMs, maxDistanceM, delayMs)
        return try {
            play(handle, scratch.get())
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
        if (handle == 0L || voiceId <= 0) return
        pack(0, key, owner, flags, volume, rate, pan, distance, rear, approach, 0, 0,
            maxDistanceM, 0)
        try {
            update(handle, voiceId, scratch.get())
        } catch (_: Throwable) {
        }
    }

    fun stopVoice(voiceId: Int) {
        if (handle == 0L || voiceId <= 0) return
        try {
            stop(handle, voiceId)
        } catch (_: Throwable) {
        }
    }

    fun stopKey(key: String) {
        if (handle == 0L) return
        try {
            stopKey(handle, keyIdOf(key))
        } catch (_: Throwable) {
        }
    }

    fun stopOwnerKey(owner: Int, key: String) {
        if (handle == 0L) return
        try {
            stopOwnerKey(handle, owner, keyIdOf(key))
        } catch (_: Throwable) {
        }
    }

    fun stopOwner(owner: Int) {
        if (handle == 0L) return
        try {
            stopOwner(handle, owner)
        } catch (_: Throwable) {
        }
    }

    fun stopAll() {
        if (handle == 0L) return
        try {
            stopAll(handle)
        } catch (_: Throwable) {
        }
    }

    /* ------------------------------------------------------------------ buses */

    /** Applies a bus gain (0..1). The SFX slider is one call. */
    fun setBusGain(bus: Int, gain: Float) {
        if (handle == 0L) return
        try {
            setBusGain(handle, bus, gain.coerceIn(0f, 1f))
        } catch (_: Throwable) {
        }
    }

    fun setMasterGain(gain: Float) {
        if (handle == 0L) return
        try {
            setMasterGain(handle, gain.coerceIn(0f, 1f))
        } catch (_: Throwable) {
        }
    }

    fun setPaused(paused: Boolean) {
        if (handle == 0L) return
        try {
            setPaused(handle, paused)
        } catch (_: Throwable) {
        }
    }

    fun isRunning(): Boolean = handle != 0L && try {
        isRunning(handle)
    } catch (_: Throwable) {
        false
    }

    /** Live stream information, or null when the engine is not available. */
    fun streamInfo(): NativeStreamInfo? {
        if (handle == 0L) return null
        return try {
            info(handle)
        } catch (_: Throwable) {
            null
        }
    }

    /** Voices the mixer is rendering right now. */
    fun voiceCount(): Int = if (handle == 0L) 0 else try {
        activeVoices(handle)
    } catch (_: Throwable) {
        0
    }

    /** Closes the stream and frees the engine. Every later call is a no-op. */
    fun release() {
        val current = handle
        handle = 0L
        available = false
        if (current == 0L) return
        try {
            stopStream(current)
        } catch (_: Throwable) {
        }
        try {
            destroy(current)
        } catch (_: Throwable) {
        }
        synchronized(clipLock) { clipIds.clear() }
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
