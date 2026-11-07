package com.blindtechnexus.openfire.model

/**
 * Robert's counter-fire strategy, balanced against the player's own rhythm.
 *
 * The enemy no longer fires on a fixed clock, and he no longer ignores a
 * player who is firing faster than him. He listens to the real gaps between
 * the player's shots and answers with one of three beats:
 *
 *  - the player fires faster than one second (0.25 s or 0.5 s): Robert
 *    answers once a second, so a held automatic weapon cannot pin him into
 *    doing nothing;
 *  - the player fires at one or 1.5 second intervals: Robert matches that
 *    with the 1.5 second beat;
 *  - the player fires every 1.5 to 2.5 seconds: Robert answers every
 *    2.5 seconds, giving the slower player the same fair exchange;
 *  - the player stops firing a sustained rhythm: Robert fires three quick
 *    answering shots 0.5 seconds apart and then runs away, so the end of a
 *    burst is never a free kill and never an endless chase.
 *
 * The enemy's damage still comes from the same [DamageTable] as the player's,
 * so his power never silently scales: only the timing changes.
 */
object EnemyFireStrategy {
    /** Gaps shorter than this are the same trigger pull, not a rhythm. */
    const val MIN_SAMPLE_MS = 120L

    /** Faster player fire is answered with the one second beat. */
    const val FAST_PLAYER_THRESHOLD_MS = 1_000L

    /** One and 1.5 second player fire is answered with 1.5 seconds. */
    const val STANDARD_PLAYER_THRESHOLD_MS = 1_500L

    /** Slower player fire up to this gap is answered with 2.5 seconds. */
    const val SLOW_PLAYER_THRESHOLD_MS = 2_500L

    /** The three answering beats. */
    const val COUNTER_FAST_MS = 1_000L
    const val COUNTER_STANDARD_MS = 1_500L
    const val COUNTER_SLOW_MS = 2_500L

    /** Beat used before the player has fired twice. */
    const val DEFAULT_INTERVAL_MS = COUNTER_STANDARD_MS

    /** Shots Robert fires once the player's burst has stopped. */
    const val STOP_BURST_SHOTS = 3

    /** Spacing of that closing burst. */
    const val STOP_BURST_INTERVAL_MS = 500L

    /** Player silence that counts as "the continuous fire has stopped". */
    const val STOP_DETECT_MS = SLOW_PLAYER_THRESHOLD_MS

    /** How many recent samples are read: the newest rhythm wins. */
    const val SAMPLE_WINDOW = 6

    /** The gaps between [shotTimes], oldest first, that describe a rhythm. */
    fun intervalsFrom(shotTimes: List<Long>): List<Long> =
        shotTimes.zipWithNext { previous, next -> next - previous }
            .filter { it >= MIN_SAMPLE_MS && it <= SLOW_PLAYER_THRESHOLD_MS }

    /**
     * The player's current firing interval, or null when there is not enough
     * usable rhythm yet. The median of the newest samples is used so a single
     * accidental double tap cannot turn the enemy into a machine gun.
     */
    fun playerInterval(shotTimes: List<Long>): Long? {
        val samples = intervalsFrom(shotTimes)
            .takeLast(SAMPLE_WINDOW)
            .sorted()
        if (samples.isEmpty()) return null
        return samples[samples.size / 2]
    }

    /** The beat Robert answers a player interval with. */
    fun counterInterval(playerIntervalMs: Long?): Long = when {
        playerIntervalMs == null -> DEFAULT_INTERVAL_MS
        playerIntervalMs < FAST_PLAYER_THRESHOLD_MS -> COUNTER_FAST_MS
        playerIntervalMs <= STANDARD_PLAYER_THRESHOLD_MS -> COUNTER_STANDARD_MS
        playerIntervalMs <= SLOW_PLAYER_THRESHOLD_MS -> COUNTER_SLOW_MS
        else -> DEFAULT_INTERVAL_MS
    }

    /** True once the player's sustained fire has gone quiet. */
    fun playerStopped(lastShotAtMs: Long, nowMs: Long): Boolean =
        lastShotAtMs > 0L && nowMs - lastShotAtMs > STOP_DETECT_MS

    /**
     * True when Robert should open his three-shot closing burst: the player
     * was firing a rhythm and has now stopped, the burst is not already
     * running and it has not just finished.
     */
    fun shouldStartStopBurst(
        hasPlayerRhythm: Boolean,
        lastShotAtMs: Long,
        nowMs: Long,
        shotsLeft: Int,
        lastStopBurstAtMs: Long
    ): Boolean {
        if (!hasPlayerRhythm || shotsLeft > 0) return false
        if (!playerStopped(lastShotAtMs, nowMs)) return false
        return lastStopBurstAtMs <= 0L || nowMs - lastStopBurstAtMs > STOP_DETECT_MS
    }

    /** The gap before the next closing shot. */
    @Suppress("UNUSED_PARAMETER")
    fun stopBurstInterval(shotsLeft: Int): Long = STOP_BURST_INTERVAL_MS

    /** True while a closing burst still has shots to fire. */
    fun inStopBurst(shotsLeft: Int): Boolean = shotsLeft > 0
}
