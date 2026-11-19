package com.blindtechnexus.openfire.audio

import android.content.Context
import com.blindtechnexus.openfire.audio.native.OpenFireAudioEngine
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.RunPressure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
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
        /**
         * How long the catalogue warm-up waits after the engine is created.
         *
         * Long enough for the first frame of the app, short enough that the
         * menus feel instant; a sound asked for during the wait is decoded on
         * the priority lane instead, so no interaction is ever delayed by it.
         */
        private const val WARMUP_DELAY_MS = 1_200L

        /** How many voice handles are remembered at most. */
        private const val MAX_HANDLES = 512

        /**
         * How long a freshly started loop voice is trusted before its liveness
         * is questioned. The audio callback publishes a new voice within one
         * block (a few milliseconds), so a quarter second is far more than the
         * engine ever needs - and it makes a repeated start safe.
         */
        private const val LOOP_START_GRACE_MS = 250L
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

    /** When each loop voice id was handed out, for [loopVoiceIsGone]. */
    private val loopStartedAtMs = ConcurrentHashMap<String, Long>()

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

    /**
     * The priority decode lane.
     *
     * The catalogue is warmed up by the engine's own background lane; this
     * single lane exists for the sounds the game actually asked for, so a
     * first click is decoded *now* instead of waiting behind fifty other
     * files. A single thread is enough: decoding is the slow part and the
     * requests are naturally serialised by gameplay.
     */
    private val requestExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenFireRequest").apply {
            priority = Thread.NORM_PRIORITY + 2
            isDaemon = true
        }
    }

    /** True once the catalogue warm-up has been handed to the engine. */
    private val warmUpStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Children born in [playOneShot] get a short, unique handle of their own. */
    private val nextHandle = AtomicInteger(1)

    /** Invalidates a pending cold-cache mix when all audio is stopped. */
    private val mixGeneration = AtomicInteger(0)
    private val mixLock = Any()

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
        if (!warmUpStarted.compareAndSet(false, true)) return
        // The catalogue is warmed up on its own low-priority thread, after the
        // first frame of the app has been drawn: creating fifty-odd media
        // codecs during start-up competes with the UI for the same cores. A
        // sound the game asks for in the meantime takes the priority lane
        // above, so nothing is ever late because of this delay.
        val thread = Thread({ decodeCatalogue() }, "OpenFireWarmUp")
        thread.isDaemon = true
        thread.priority = Thread.NORM_PRIORITY - 1
        thread.start()
    }

    /**
     * Hands the whole catalogue to the engine's decode lane, most used first.
     *
     * Runs on the warm-up thread only. The sleep is the quiet period after the
     * first frame; a sound the game asks for during it is decoded on the
     * priority lane instead, so this delay never delays an interaction.
     */
    private fun decodeCatalogue() {
        try {
            Thread.sleep(WARMUP_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        val ordered = SoundLibrary.ESSENTIAL_FILES + SoundLibrary.files()
            .filter { it !in SoundLibrary.ESSENTIAL_FILES }
        for (file in ordered) {
            if (Thread.currentThread().isInterrupted || !available) break
            engine.warmUp(file)
        }
    }

    /** Decodes [file] at most once, on a background thread only. */
    private fun ensureDecoded(file: String): Int {
        if (!available) return 0
        val cached = engine.loadClipIfPresent(file)
        if (cached > 0) return cached
        return engine.loadClip(file)
    }

    /**
     * Runs [action] with the clip of [file].
     *
     * When the clip is already uploaded - the normal case after the first
     * second of play - this is synchronous and allocation-free, which is what
     * makes a shot audible on the very next audio callback. Only a genuinely
     * cold sound takes the priority lane, and it still plays as soon as its
     * decode finishes.
     */
    private fun withClip(file: String, action: (Int) -> Unit) {
        val cached = engine.loadClipIfPresent(file)
        if (cached > 0) {
            action(cached)
            return
        }
        if (!available) return
        submitRequest {
            val id = ensureDecoded(file)
            if (id > 0 && available) action(id)
        }
    }

    /** Submits a priority decode, ignoring a submission after [release]. */
    private fun submitRequest(task: () -> Unit) {
        try {
            requestExecutor.execute {
                try {
                    task()
                } catch (_: Throwable) {
                }
            }
        } catch (_: RejectedExecutionException) {
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
        // One throttled, lock-free probe of the stream: if the platform closed
        // it (a route change, a phone call), the reopen is handed to the
        // engine's stream lane instead of being attempted here. This is a
        // boolean read at most every 400 ms, never a lock or an open.
        engine.ensureStream()
        val flags = buildFlags(key, place, deadlineMs, approachProximity, loops = false)
        val handle = nextHandle.getAndIncrement()
        val record = VoiceHandle(handle, key, owner, flags, place, 0, volume, rate, deadlineMs)
        handles[handle] = record
        if (handles.size > MAX_HANDLES) pruneHandles()
        withClip(file) { clipId ->
            if (handles[handle] !== record) return@withClip
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
            if (id > 0 && handles[handle] !== record) engine.stopVoice(id)
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

    /** Start decoding the clips needed by an upcoming interaction. */
    fun prepareSounds(keys: List<String>) {
        if (!available) return
        keys.mapNotNull(SoundLibrary::fileOf).distinct().forEach(engine::requestClip)
    }

    /**
     * Plays several sounds together at one place. On a cold cache, decode the
     * whole group first on the request lane; otherwise the first clip could
     * finish decoding and play long before the second. A failed decode does
     * not prevent the other clips from being heard.
     */
    fun playMixed(keys: List<String>, volume: Float = 1.0f, place: SpatialPlace? = null) {
        if (!available) return
        val clips = keys.mapNotNull { key -> SoundLibrary.fileOf(key)?.let { key to it } }
        if (clips.isEmpty()) return
        val generation = mixGeneration.get()
        fun startReadyClips() {
            synchronized(mixLock) {
                if (!available || generation != mixGeneration.get()) return
                for ((key, file) in clips) {
                    if (engine.hasClip(file)) playOneShot(key, volume = volume, place = place)
                }
            }
        }
        if (clips.all { engine.hasClip(it.second) }) {
            startReadyClips()
        } else {
            submitRequest {
                clips.map { it.second }.distinct().forEach { ensureDecoded(it) }
                startReadyClips()
            }
        }
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
        if (existing != null && existing > 0 && loopVoiceIsGone(slotKey, existing)) {
            // The voice behind this slot ended or was stopped by another path
            // (a stop-by-key, a route change that cleared the mix). Forget it
            // and open a fresh one instead of updating an id that is gone.
            loopVoices.remove(slotKey, existing)
            loopStartedAtMs.remove(slotKey)
        }
        val live = loopVoices[slotKey]
        if (live != null && live > 0) {
            engine.update(
                voiceId = live,
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
            return live
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
                submitRequest {
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
            if (id > 0) {
                loopVoices[slotKey] = id
                loopStartedAtMs[slotKey] = nowMs()
            }
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
        if (id > 0) {
            loopVoices[slotKey] = id
            loopStartedAtMs[slotKey] = nowMs()
        }
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
        val slotKey = "$owner|$key"
        val id = loopVoices[slotKey] ?: return
        if (loopVoiceIsGone(slotKey, id)) {
            // Nothing to retune: drop the dead slot so the next start opens a
            // real voice again.
            loopVoices.remove(slotKey, id)
            loopStartedAtMs.remove(slotKey)
            return
        }
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
        loopStartedAtMs.remove(slotKey)
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
            loopStartedAtMs.remove(slotKey)
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
            loopStartedAtMs.remove(slotKey)
            val id = loopVoices.remove(slotKey) ?: continue
            engine.stopVoice(id)
        }
        engine.stopOwner(owner)
    }

    /** Stops one sound key completely: its loops and its one-shots. */
    fun stopKey(key: String) {
        if (!available) return
        for ((handle, record) in handles) {
            if (record.key == key) handles.remove(handle, record)
        }
        stopLoopsOfKey(key)
    }

    /** Stops every sound of one key that belongs to [owner]. */
    fun stopOwnerKey(owner: Int, key: String) {
        if (!available) return
        val slotKey = "$owner|$key"
        loopStartedAtMs.remove(slotKey)
        loopVoices.remove(slotKey)?.let { engine.stopVoice(it) }
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

    /** Prevent a still-decoding mix from starting after screen cleanup. */
    fun cancelPendingMixes() {
        synchronized(mixLock) { mixGeneration.incrementAndGet() }
    }

    fun stopAll() {
        cancelPendingMixes()
        if (!available) return
        pendingLoops.clear()
        loopVoices.clear()
        loopStartedAtMs.clear()
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

    /**
     * Chooses the spatial model of the engine: the binaural head model on
     * headphones, the crossfeed model on loudspeakers. Called by
     * [AudioManager] whenever the audio route changes.
     */
    fun setOutputMode(headphones: Boolean) = engine.setOutputMode(headphones)

    /** True while the headphone spatial model is active. */
    val headphoneMode: Boolean get() = engine.headphonesMode

    fun pause() {
        paused = true
        engine.setPaused(true)
    }

    fun resume() {
        paused = false
        engine.setPaused(false)
    }

    /** Live stream diagnostics; the guide speaks them. */
    fun diagnostics(): String = engine.diagnostics()

    fun release() {
        cancelPendingMixes()
        pendingLoops.clear()
        loopVoices.clear()
        loopStartedAtMs.clear()
        handles.clear()
        try {
            requestExecutor.shutdownNow()
        } catch (_: Throwable) {
        }
        engine.release()
    }

    // ------------------------------------------------------------- internals

    /**
     * True when the voice remembered for [slotKey] is really gone.
     *
     * The audio callback publishes a new voice a few milliseconds after it was
     * created, so an id younger than [LOOP_START_GRACE_MS] is always trusted.
     * Only a voice that never appears - or one that ended or was stopped by
     * another path - is replaced, which keeps a repeated start from layering a
     * second copy of the same loop on top of the first one.
     */
    private fun loopVoiceIsGone(slotKey: String, voiceId: Int): Boolean {
        if (engine.isVoiceSounding(voiceId)) return false
        val startedAt = loopStartedAtMs[slotKey] ?: return true
        return nowMs() - startedAt > LOOP_START_GRACE_MS
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

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
