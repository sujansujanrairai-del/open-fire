package com.blindtechnexus.openfire.audio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Small, non-blocking playback backend used by [AutomaticWeaponMixer].
 *
 * SoundPool implements this interface in production. Keeping the boundary small
 * makes the trigger lifecycle testable without an Android media server.
 */
internal interface AutomaticAudioBackend {
    /**
     * Starts one looping stream. [priority] is the pool priority of the
     * stream: a held weapon loop is always played above a one-shot effect, so
     * a busy pool sacrifices a hit sound before it ever sacrifices the gun
     * the player is holding.
     */
    fun play(sampleId: Int, left: Float, right: Float, priority: Int = LOOP_STREAM_PRIORITY): Int
    fun stop(streamId: Int)
    fun setVolume(streamId: Int, left: Float, right: Float)

    companion object {
        /**
         * Priority of a held weapon loop. Higher than every one-shot effect
         * (which play at 1), so SoundPool frees a one-shot stream first when
         * its limit is reached. This is what stops a hit sound from cutting a
         * continuous machine-gun burst.
         */
        const val LOOP_STREAM_PRIORITY = 5
    }
}

/**
 * Gapless, hold-to-fire mixer for the submachine gun, AK-47 and machine gun.
 *
 * There is at most one looping stream for each `(owner, weapon)` pair. A start
 * is idempotent, so a bullet tick only refreshes the existing stream instead of
 * layering another recording over it. A stop clears intent before stopping the
 * native stream; a late sample-load or retry therefore cannot resurrect audio.
 *
 * Samples are loaded asynchronously by AudioManager. No prepare, decode, delay
 * or native release operation is performed by this class on the UI thread.
 * SoundPool.play/stop/setVolume are short, thread-safe native commands.
 */
internal class AutomaticWeaponMixer(
    private val backend: AutomaticAudioBackend,
    private val scope: CoroutineScope,
    private val sampleId: (String) -> Int?,
    private val requestSample: (String) -> Unit,
    private val minimumBurstMs: Long = 260L,
    private val takeoverGraceMs: Long = 60L
) {
    internal class Channel(
        val id: String,
        val owner: String,
        val soundKey: String
    ) {
        @Volatile var desired = false
        @Volatile var preparing = false
        @Volatile var streamId = NO_STREAM
        @Volatile var left = 1f
        @Volatile var right = 1f
        @Volatile var bounded = false

        // The following fields are guarded by the mixer's lock.
        var generation = 0L
        var burstGeneration = 0L
        var attempts = 0
        var timeoutJob: Job? = null
        var retryJob: Job? = null
    }

    private val lock = Any()
    internal val channels = ConcurrentHashMap<String, Channel>()

    @Volatile
    private var released = false

    @Volatile
    private var paused = false

    /** Starts (or refreshes) a real held loop. Repeated starts never overlap. */
    fun start(key: String, owner: String, left: Float, right: Float) {
        val request = synchronized(lock) {
            if (released) return
            val channel = channels.getOrPut(channelId(owner, key)) {
                Channel(channelId(owner, key), owner, key)
            }
            channel.timeoutJob?.cancel()
            channel.timeoutJob = null
            channel.burstGeneration++
            channel.bounded = false
            armLocked(channel, left, right)
        }
        start(request)
    }

    /**
     * Plays a bounded automatic burst for a tap/legacy caller.
     *
     * A new burst replaces the previous timeout rather than adding another
     * coroutine. A real hold takes over the same stream and cancels the timeout.
     */
    fun playBurst(
        key: String,
        owner: String,
        holdMs: Long,
        left: Float,
        right: Float
    ) {
        val request: StartRequest?
        val channel: Channel
        val burstGeneration: Long
        val duration = holdMs.coerceAtLeast(minimumBurstMs) + takeoverGraceMs
        synchronized(lock) {
            if (released) return
            channel = channels.getOrPut(channelId(owner, key)) {
                Channel(channelId(owner, key), owner, key)
            }

            // A bounded request must never shorten or restart a genuine hold.
            if (channel.desired && !channel.bounded) {
                updateVolumeLocked(channel, left, right)
                return
            }

            channel.bounded = true
            channel.burstGeneration++
            burstGeneration = channel.burstGeneration
            channel.timeoutJob?.cancel()
            channel.timeoutJob = scope.launch {
                delay(duration)
                stopBounded(channel, burstGeneration)
            }
            request = armLocked(channel, left, right)
        }
        start(request)
    }

    /**
     * Re-arms a loop that lost its native stream without being released.
     *
     * The engine calls this on every bullet of a held burst. It is the hard
     * guarantee behind "a continuous burst is never cut": if anything out
     * there - the pool limit, a system audio glitch, a stolen stream - has
     * stopped the stream while the trigger is still held, the loop is started
     * again on the very next beat instead of going silent for the rest of the
     * magazine.
     */
    fun keepAlive(key: String, owner: String, left: Float, right: Float) {
        val request = synchronized(lock) {
            if (released || paused) return
            val channel = channels[channelId(owner, key)] ?: return
            if (!channel.desired) return
            if (channel.streamId != NO_STREAM) {
                updateVolumeLocked(channel, left, right)
                return
            }
            armLocked(channel, left, right)
        }
        start(request)
    }

    /** Called when an asynchronous SoundPool sample becomes ready. */
    fun onSoundReady(key: String) {
        val requests = synchronized(lock) {
            if (released) return
            channels.values
                .filter { it.soundKey == key && it.desired && it.streamId == NO_STREAM }
                .map { channel ->
                    channel.preparing = true
                    channel.attempts = 0
                    StartRequest(channel, channel.generation)
                }
        }
        requests.forEach(::start)
    }

    fun stop(key: String, owner: String? = null) {
        val doomed = synchronized(lock) {
            channels.values.filter { it.soundKey == key && (owner == null || it.owner == owner) }
        }
        doomed.forEach(::stop)
    }

    fun stopOwner(owner: String) {
        val doomed = synchronized(lock) { channels.values.filter { it.owner == owner } }
        doomed.forEach(::stop)
    }

    fun stopAll() {
        val doomed = synchronized(lock) { channels.values.toList() }
        doomed.forEach(::stop)
    }

    /** Pause is only an intent flag; AudioManager owns SoundPool.autoPause(). */
    fun pause() {
        paused = true
    }

    /** Starts only samples that are still wanted after SoundPool.autoResume(). */
    fun resume() {
        paused = false
        val requests = synchronized(lock) {
            if (released) return
            channels.values
                .filter { it.desired && it.streamId == NO_STREAM }
                .map { StartRequest(it, it.generation) }
        }
        requests.forEach(::start)
    }

    fun refresh(key: String, owner: String, left: Float, right: Float) {
        synchronized(lock) {
            val channel = channels[channelId(owner, key)] ?: return
            if (!channel.desired) return
            updateVolumeLocked(channel, left, right)
        }
    }

    fun isActive(key: String, owner: String): Boolean {
        val channel = channels[channelId(owner, key)] ?: return false
        return channel.desired && (channel.preparing || channel.streamId != NO_STREAM)
    }

    fun isAnyActive(): Boolean = channels.values.any { it.desired }

    fun release() {
        released = true
        stopAll()
        channels.clear()
    }

    private data class StartRequest(val channel: Channel, val generation: Long)

    /** Must be called with [lock] held. */
    private fun armLocked(channel: Channel, left: Float, right: Float): StartRequest? {
        channel.desired = true
        channel.left = left.coerceIn(0f, 1f)
        channel.right = right.coerceIn(0f, 1f)
        if (channel.streamId != NO_STREAM) {
            backend.setVolume(channel.streamId, channel.left, channel.right)
            channel.preparing = false
            return null
        }
        if (channel.preparing) return null
        channel.preparing = true
        channel.attempts = 0
        channel.generation++
        return StartRequest(channel, channel.generation)
    }

    /** Must be called with [lock] held. */
    private fun updateVolumeLocked(channel: Channel, left: Float, right: Float) {
        channel.left = left.coerceIn(0f, 1f)
        channel.right = right.coerceIn(0f, 1f)
        if (channel.streamId != NO_STREAM) {
            backend.setVolume(channel.streamId, channel.left, channel.right)
        }
    }

    /**
     * Validates intent again on the audio path. This is the guard that prevents
     * a released trigger from producing a late shot when loading finishes.
     */
    private fun start(request: StartRequest?) {
        if (request == null) return
        val channel = request.channel
        var load = false
        synchronized(lock) {
            if (released || paused || !channel.desired ||
                channel.generation != request.generation || channel.streamId != NO_STREAM
            ) return

            val sample = sampleId(channel.soundKey)
            if (sample == null) {
                channel.preparing = true
                load = true
            } else {
                val stream = backend.play(
                    sample,
                    channel.left,
                    channel.right,
                    AutomaticAudioBackend.LOOP_STREAM_PRIORITY
                )
                if (stream > 0) {
                    channel.streamId = stream
                    channel.preparing = false
                    channel.attempts = 0
                    channel.retryJob?.cancel()
                    channel.retryJob = null
                    return
                }
                scheduleRetryLocked(channel, request.generation)
            }
        }
        if (load) requestSample(channel.soundKey)
    }

    /** At most one retry job and three immediate attempts per start request. */
    private fun scheduleRetryLocked(channel: Channel, generation: Long) {
        if (channel.retryJob?.isActive == true) return
        if (channel.attempts >= MAX_START_ATTEMPTS) {
            channel.preparing = false
            return
        }
        channel.attempts++
        channel.retryJob = scope.launch {
            delay(RETRY_DELAY_MS)
            // The current retry is active while it runs. Clear its slot before
            // requesting the next attempt, otherwise it suppresses itself.
            synchronized(lock) { channel.retryJob = null }
            start(StartRequest(channel, generation))
        }
    }

    private fun stop(channel: Channel) {
        val stream = synchronized(lock) { detachLocked(channel) }
        if (stream != NO_STREAM) backend.stop(stream)
    }

    /** Detaches intent and stream atomically. The caller holds [lock]. */
    private fun detachLocked(channel: Channel): Int {
        val stream = channel.streamId
        channel.desired = false
        channel.preparing = false
        channel.bounded = false
        channel.streamId = NO_STREAM
        channel.generation++
        channel.burstGeneration++
        channel.timeoutJob?.cancel()
        channel.timeoutJob = null
        channel.retryJob?.cancel()
        channel.retryJob = null
        channels.remove(channel.id, channel)
        return stream
    }

    private fun stopBounded(channel: Channel, burstGeneration: Long) {
        val stream = synchronized(lock) {
            if (!channel.desired || !channel.bounded ||
                channel.burstGeneration != burstGeneration
            ) return
            // Validation and detachment share one critical section: a real hold
            // taking over on another thread cannot be cut by this old timeout.
            detachLocked(channel)
        }
        if (stream != NO_STREAM) backend.stop(stream)
    }

    private fun channelId(owner: String, key: String) = "$owner:$key"

    private companion object {
        const val NO_STREAM = 0
        const val RETRY_DELAY_MS = 40L
        const val MAX_START_ATTEMPTS = 3
    }
}
