package com.blindtechnexus.openfire.audio

import android.content.Context
import com.blindtechnexus.openfire.audio.native.OpenFireAudioEngine
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.RunPressure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge between the game and the native Oboe engine.
 *
 * This is the piece [AudioManager] talks to. It owns the three things the
 * engine deliberately knows nothing about:
 *
 *  - **which asset belongs to which sound key** ([SoundLibrary]);
 *  - **decoding**: assets are decoded on a background lane and uploaded to
 *    C++ once, so a play request never waits for a decoder, and the first
 *    shot of a battle is never late;
 *  - **what a sound is**: one shot or loop, positional or centred, a
 *    projectile with an approach cue, and the running-level playback rate of
 *    the two footstep loops.
 *
 * Everything is non-blocking and safe from any thread. If the native library
 * is missing (an ABI the APK does not carry, a stripped build) [available] is
 * false and every call is a cheap no-op: the game keeps running silently
 * rather than crashing.
 *
 * Unlike the old software mixer, a loop voice is not remembered here as a
 * handle that can be "the" loop: the engine is the owner of voice ids, and a
 * loop is looked up by its key and owner exactly as the game asks for it.
 * [startLoop] therefore returns the native voice id it created or moved.
 */
class NativeMixer(context: Context) {

    companion object {
        /** Decoding runs here, never on the audio or the UI thread. */
        private const val DECODE_THREADS = 2

        /** How many voice handles are remembered at most. */
        private const val MAX_HANDLES = 512
    }

    val engine = OpenFireAudioEngine(context)

    /** True when the native stream is open and sounds can be played. */
    val available: Boolean get() = engine.available

    /**
     * Loops that are open right now, by `owner|key`. The native id is the
     * authority on the voice; this map only remembers the parameters so a
     * refresh does not have to ask the engine for them.
     */
    private val loopVoices = ConcurrentHashMap<String, Int>()

    /**
     * Loops that were asked for while their clip was still decoding. They are
     * opened, unchanged, the moment the decoder delivers the clip, so a first
     * click of a battle never loses its sound.
     */
    private val pendingLoops = ConcurrentHashMap<String, PendingLoop>()

    private class PendingLoop(
        val key: String,
        val owner: Int,
        val flags: Int,
        val place: SpatialPlace?,
        val volume: Float,
        val rate: Float,
        val startOffsetMs: Int,
        val startDelayMs: Int
    )

    private val decodeExecutor = Executors.newFixedThreadPool(DECODE_THREADS) { runnable ->
        Thread(runnable, "OpenFireDecode").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    /** Guards the "decode once" bookkeeping. */
    private val decodeLock = Any()

    /** Children born in [playOneShot] get a short, unique handle of their own. */
    private val nextHandle = AtomicInteger(1)

    /** Live voices by handle, so a caller can retune or cut one sound. */
    private val handles = ConcurrentHashMap<Int, VoiceHandle>()

    /** Master SFX level, applied to the engine's SFX bus. */
    var sfxVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.clamp(value)
            engine.setBusGain(OpenFireAudioEngine.BUS_SFX, VolumeMixer.sfxGainForBus(field))
        }

    /**
     * The effects level the engine is rendering with, 0..1. Reading it back is
     * how the settings screen and the tests confirm that a slider move really
     * reached the bus.
     */
    val sfxGain: Float get() = sfxVolume

    /** True while the whole battle is frozen (a pause, a backgrounded app). */
    @Volatile
    var paused: Boolean = false
        private set

    /**
     * A voice that was started or requested, so a later call can move it or
     * stop it. [voiceId] is 0 until the clip has been decoded.
     */
    private class VoiceHandle(
        val order: Int,
        val key: String,
        val owner: Int,
        @Volatile var flags: Int,
        @Volatile var place: SpatialPlace?,
        @Volatile var voiceId: Int,
        @Volatile var volume: Float,
        @Volatile var rate: Float,
        @Volatile var deadlineMs: Int
    )

    init {
        engine.setBusGain(OpenFireAudioEngine.BUS_SFX, VolumeMixer.sfxGainForBus(sfxVolume))
        if (available) warmUp()
    }

    // ------------------------------------------------------------- decoding

    /**
     * Decodes and uploads every sound of the game in the background. A sound
     * that is requested before its turn decodes first, so a first click is
     * still heard immediately.
     */
    private fun warmUp() {
        decodeExecutor.execute {
            for (file in SoundLibrary.files()) {
                if (Thread.currentThread().isInterrupted) return@execute
                ensureDecoded(file)
            }
        }
    }

    /** Decodes [file] at most once, on any thread. */
    private fun ensureDecoded(file: String): Int {
        if (!available) return 0
        val cached = engine.loadClipIfPresent(file)
        if (cached > 0) return cached
        synchronized(decodeLock) {
            val again = engine.loadClipIfPresent(file)
            if (again > 0) return again
            return engine.loadClip(file)
        }
    }

    /** Requests a decode now and runs [action] as soon as the clip is ready. */
    private fun withClip(file: String, action: (Int) -> Unit) {
        val cached = engine.loadClipIfPresent(file)
        if (cached > 0) {
            action(cached)
            return
        }
        if (!available) return
        // A requested sound is not lost to a cold cache: it is decoded now and
        // played the moment its clip is ready. Repeated requests for the same
        // file are coalesced by `ensureDecoded`, which uploads it exactly once.
        decodeExecutor.execute {
            val id = ensureDecoded(file)
            if (id > 0 && available) action(id)
        }
    }

    // ------------------------------------------------------------ one-shots

    /**
     * Plays one sound.
     *
     * @param volume the voice's own level (the SFX slider is applied by the bus)
     * @param rate playback rate; 1 is the natural pitch
     * @param place where the sound is, or null for a centred, non-positional one
     * @param owner the fighter the sound belongs to
     * @param deadlineMs a bounded burst length (automatic weapons), 0 for the whole clip
     * @param approachProximity 0..1 when the sound is a projectile closing in
     * @param delayMs a silent lead-in, for a chained sound
     * @return the handle of the voice, or -1
     */
    fun playOneShot(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        place: SpatialPlace? = null,
        owner: Int = OpenFireAudioEngine.OWNER_NONE,
        deadlineMs: Int = 0,
        approachProximity: Float = -1f,
        delayMs: Int = 0
    ): Int {
        if (!available) return -1
        val file = SoundLibrary.fileOf(key) ?: return -1
        val flags = buildFlags(key, place, deadlineMs, approachProximity, loops = false)
        val handle = nextHandle.getAndIncrement()
        val record = VoiceHandle(handle, key, owner, flags, place, 0, volume, rate, deadlineMs)
        handles[handle] = record
        if (handles.size > MAX_HANDLES) pruneHandles()
        withClip(file) { clipId ->
            val id = engine.play(
                clipId = clipId,
                key = key,
                owner = owner,
                flags = flags,
                volume = record.volume,
                rate = record.rate,
                pan = record.place?.pan ?: 0f,
                distance = record.place?.distance ?: 0f,
                rear = record.place?.rear ?: 0f,
                approach = if (approachProximity >= 0f) approachProximity else 0f,
                deadlineMs = record.deadlineMs,
                startOffsetMs = 0,
                maxDistanceM = Combat.MAX_RANGE.toInt(),
                delayMs = delayMs
            )
            record.voiceId = id
            if (id <= 0) handles.remove(handle, record)
        }
        return handle
    }

    /** The native voice id behind a handle, or 0. */
    fun nativeIdOf(handle: Int): Int = handles[handle]?.voiceId ?: 0

    /**
     * True while a requested one-shot is still waiting for its clip. The
     * diagnostics line reports how many sounds are in that state, so a cold
     * cache is visible instead of appearing as "a missed click".
     */
    fun pendingOneShots(): Int = handles.values.count { it.voiceId == 0 }

    /** Plays a sound only when no copy of the same key is already sounding. */
    fun playOnce(
        key: String,
        volume: Float = 1.0f,
        place: SpatialPlace? = null,
        owner: Int = OpenFireAudioEngine.OWNER_NONE
    ): Int {
        if (!available) return -1
        if (isKeyActive(key)) return -1
        return playOneShot(key, volume = volume, place = place, owner = owner)
    }

    /** Plays several sounds at the same time, at one place. */
    fun playMixed(keys: List<String>, volume: Float = 1.0f, place: SpatialPlace? = null) {
        for (key in keys) playOneShot(key, volume = volume, place = place)
    }

    /**
     * Plays [keys] back to back and starts [loopKey] when the last one ends.
     * The engine keeps the silent lead-in, so the chain stays gapless.
     */
    fun playSequence(keys: List<String>, loopKey: String? = null, volume: Float = 1.0f) {
        if (!available) return
        var offsetMs = 0
        for (key in keys) {
            playOneShot(key, volume = volume, delayMs = offsetMs)
            offsetMs += SoundLibrary.durationMsOf(key).toInt()
        }
        if (loopKey != null) startLoop(loopKey, volume = volume, startDelayMs = offsetMs)
    }

    // ---------------------------------------------------------------- loops

    /**
     * Starts a looping voice, or moves the one that already runs for this key
     * and owner. A loop is never layered twice by accident.
     *
     * @return the native voice id, or 0 while the clip is still decoding
     */
    fun startLoop(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        place: SpatialPlace? = null,
        owner: Int = OpenFireAudioEngine.OWNER_NONE,
        startOffsetMs: Int = 0,
        startDelayMs: Int = 0,
        critical: Boolean = false
    ): Int {
        if (!available) return 0
        val file = SoundLibrary.fileOf(key) ?: return 0
        val slotKey = "$owner|$key"
        val flags = buildFlags(key, place, 0, -1f, loops = true, critical = critical)
        val existing = loopVoices[slotKey]
        if (existing != null && existing > 0) {
            engine.update(
                voiceId = existing,
                key = key,
                owner = owner,
                flags = flags,
                volume = volume,
                rate = rate,
                pan = place?.pan ?: 0f,
                distance = place?.distance ?: 0f,
                rear = place?.rear ?: 0f,
                approach = 0f,
                maxDistanceM = Combat.MAX_RANGE.toInt()
            )
            return existing
        }
        val cached = engine.loadClipIfPresent(file)
        if (cached <= 0) {
            // Cold cache: remember the loop and open it as soon as the decoder
            // has the clip. A second request updates the same pending loop.
            pendingLoops[slotKey] = PendingLoop(
                key = key,
                owner = owner,
                flags = flags,
                place = place,
                volume = volume,
                rate = rate,
                startOffsetMs = startOffsetMs,
                startDelayMs = startDelayMs
            )
            if (available) {
                decodeExecutor.execute {
                    if (ensureDecoded(file) > 0) openPendingLoop(slotKey)
                }
            }
            return 0
        }
        val record = VoiceHandle(nextHandle.getAndIncrement(), key, owner, flags, place, 0, volume, rate, 0)
        withClip(file) { clipId ->
            if (!available) return@withClip
            val id = engine.play(
                clipId = clipId,
                key = key,
                owner = owner,
                flags = flags,
                volume = record.volume,
                rate = record.rate,
                pan = record.place?.pan ?: 0f,
                distance = record.place?.distance ?: 0f,
                rear = record.place?.rear ?: 0f,
                approach = 0f,
                deadlineMs = 0,
                startOffsetMs = startOffsetMs,
                maxDistanceM = Combat.MAX_RANGE.toInt(),
                delayMs = startDelayMs
            )
            record.voiceId = id
            if (id > 0) loopVoices[slotKey] = id
        }
        return record.voiceId
    }

    /** Opens a loop that was waiting for its clip, if it is still wanted. */
    private fun openPendingLoop(slotKey: String) {
        val pending = pendingLoops.remove(slotKey) ?: return
        if (!available) return
        val id = startLoop(
            key = pending.key,
            volume = pending.volume,
            rate = pending.rate,
            place = pending.place,
            owner = pending.owner,
            startOffsetMs = pending.startOffsetMs,
            startDelayMs = pending.startDelayMs
        )
        if (id > 0) loopVoices[slotKey] = id
    }

    /**
     * Retunes a loop in place: level, rate, position. A no-op when the loop is
     * not running.
     */
    fun updateLoop(
        key: String,
        volume: Float,
        rate: Float,
        place: SpatialPlace?,
        owner: Int = OpenFireAudioEngine.OWNER_NONE
    ) {
        if (!available) return
        val id = loopVoices["$owner|$key"] ?: return
        val flags = buildFlags(key, place, 0, -1f, loops = true, critical = true)
        engine.update(
            voiceId = id,
            key = key,
            owner = owner,
            flags = flags,
            volume = volume,
            rate = rate,
            pan = place?.pan ?: 0f,
            distance = place?.distance ?: 0f,
            rear = place?.rear ?: 0f,
            approach = 0f,
            maxDistanceM = Combat.MAX_RANGE.toInt()
        )
    }

    /** The native id of a running loop, or 0. */
    fun nativeLoopId(key: String, owner: Int = OpenFireAudioEngine.OWNER_NONE): Int =
        loopVoices["$owner|$key"] ?: 0

    /** True while the loop of this key and owner is running. */
    fun isLoopActive(key: String, owner: Int = OpenFireAudioEngine.OWNER_NONE): Boolean =
        loopVoices.containsKey("$owner|$key")

    /** Stops one loop of one owner. */
    fun stopLoop(key: String, owner: Int = OpenFireAudioEngine.OWNER_NONE) {
        val slotKey = "$owner|$key"
        pendingLoops.remove(slotKey)
        if (!available) return
        val id = loopVoices.remove(slotKey) ?: return
        engine.stopVoice(id)
    }

    /** Stops every loop of [key], of every owner. */
    fun stopLoopsOfKey(key: String) {
        for (pendingKey in pendingLoops.keys.filter { it.endsWith("|$key") }) {
            pendingLoops.remove(pendingKey)
        }
        if (!available) return
        val ids = loopVoices.keys.filter { it.endsWith("|$key") }
        for (slotKey in ids) {
            val id = loopVoices.remove(slotKey) ?: continue
            engine.stopVoice(id)
        }
        engine.stopKey(key)
    }

    /** Stops every loop of one owner. */
    fun stopLoopsOfOwner(owner: Int) {
        if (!available) return
        val ids = loopVoices.keys.filter { it.startsWith("$owner|") }
        for (slotKey in ids) {
            val id = loopVoices.remove(slotKey) ?: continue
            engine.stopVoice(id)
        }
        engine.stopOwner(owner)
    }

    /** Stops one sound key completely: its loops and its one-shots. */
    fun stopKey(key: String) {
        if (!available) return
        stopLoopsOfKey(key)
    }

    /** Stops every sound of one key that belongs to [owner]. */
    fun stopOwnerKey(owner: Int, key: String) {
        if (!available) return
        loopVoices.remove("$owner|$key")?.let { engine.stopVoice(it) }
        engine.stopOwnerKey(owner, key)
    }

    /**
     * Retunes a one-shot that is already sounding: its rate, its place and its
     * approach cue. This is what makes a projectile swell and rise in pitch as
     * it closes in, without ever restarting the recording.
     */
    fun updateHandle(
        handle: Int,
        rate: Float,
        place: SpatialPlace?,
        approachProximity: Float
    ) {
        val record = handles[handle] ?: return
        record.rate = rate
        record.place = place
        if (record.voiceId <= 0) return
        var flags = record.flags and OpenFireAudioEngine.FLAG_DEADLINE.inv()
        if (place != null) flags = flags or OpenFireAudioEngine.FLAG_SPATIAL
        if (approachProximity >= 0f) flags = flags or OpenFireAudioEngine.FLAG_APPROACH
        record.flags = flags
        engine.update(
            voiceId = record.voiceId,
            key = record.key,
            owner = record.owner,
            flags = flags,
            volume = record.volume,
            rate = rate,
            pan = place?.pan ?: 0f,
            distance = place?.distance ?: 0f,
            rear = place?.rear ?: 0f,
            approach = if (approachProximity >= 0f) approachProximity else 0f,
            maxDistanceM = Combat.MAX_RANGE.toInt()
        )
    }

    /** Stops a single voice by its handle. */
    fun stopHandle(handle: Int) {
        val record = handles.remove(handle) ?: return
        if (record.voiceId > 0) engine.stopVoice(record.voiceId)
    }

    fun stopAll() {
        if (!available) return
        pendingLoops.clear()
        loopVoices.clear()
        handles.clear()
        engine.stopAll()
    }

    /** True while any voice of [key] is sounding (a loop or a one-shot). */
    fun isKeyActive(key: String): Boolean {
        if (!available) return false
        if (loopVoices.keys.any { it.endsWith("|$key") }) return true
        return engine.isKeySounding(key)
    }

    // -------------------------------------------------------------- controls

    fun pause() {
        paused = true
        engine.setPaused(true)
    }

    fun resume() {
        paused = false
        engine.setPaused(false)
    }

    /** Live stream diagnostics; the guide speaks them. */
    fun diagnostics(): String =
        engine.streamInfo()?.summary ?: "Native audio engine unavailable"

    fun release() {
        pendingLoops.clear()
        loopVoices.clear()
        handles.clear()
        decodeExecutor.shutdownNow()
        engine.release()
    }

    // ------------------------------------------------------------- internals

    private fun buildFlags(
        key: String,
        place: SpatialPlace?,
        deadlineMs: Int,
        approachProximity: Float,
        loops: Boolean,
        critical: Boolean = false
    ): Int {
        var flags = 0
        if (loops) flags = flags or OpenFireAudioEngine.FLAG_LOOP
        if (deadlineMs > 0) flags = flags or OpenFireAudioEngine.FLAG_DEADLINE
        if (approachProximity >= 0f) flags = flags or OpenFireAudioEngine.FLAG_APPROACH
        if (critical || loops) flags = flags or OpenFireAudioEngine.FLAG_CRITICAL
        if (place != null) flags = flags or OpenFireAudioEngine.FLAG_SPATIAL
        if (SoundLibrary.isStereoRecording(key)) flags = flags or OpenFireAudioEngine.FLAG_STEREO
        return flags
    }

    /**
     * Bounds the handle table of a long battle.
     *
     * A handle only exists so a voice can be retuned or cut before it ends, and
     * the game only ever does that for the short-lived projectile cue. What is
     * still waiting for its clip is always kept; the rest is dropped oldest
     * first, so the table can never grow without bound over thousands of shots.
     */
    private fun pruneHandles() {
        val pending = handles.values.filter { it.voiceId == 0 }
        val keepOrders = pending.map { it.order }.toSet()
        val finished = handles.entries
            .filter { it.value.voiceId != 0 && it.value.order !in keepOrders }
            .sortedBy { it.value.order }
        val excess = handles.size - MAX_HANDLES
        if (excess <= 0) return
        for (entry in finished.take(excess)) handles.remove(entry.key, entry.value)
    }

    /**
     * The three running levels as playback rates. A slow run drags the steps,
     * a normal run plays them at their natural pace and a fast run drives them
     * clearly quicker; all three stay inside the engine's natural window.
     */
    fun footstepRate(speedMps: Float, fastSpeedMps: Float = RunPressure.BASE_SPEED_MPS * 3f): Float =
        VolumeMixer.footstepRate(
            speedMps = speedMps,
            slowSpeedMps = RunPressure.BASE_SPEED_MPS * 0.5f,
            baseSpeedMps = RunPressure.BASE_SPEED_MPS,
            fastSpeedMps = fastSpeedMps
        )
}
