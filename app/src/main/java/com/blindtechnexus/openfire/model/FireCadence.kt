package com.blindtechnexus.openfire.model

/**
 * Shared firing rhythm of the whole game, in game-time milliseconds.
 *
 * A single-shot weapon fires once per swipe. The three automatic weapons fire
 * one bullet per interval based on their power:
 *  - the submachine gun is the lightest bullet, so it fires fastest;
 *  - the AK-47 sits in the middle with a one second beat;
 *  - the machine gun has the heaviest bullet, so it fires slowest.
 *
 * The enemy mirrors the player's own rhythm with the same three beats (see
 * [EnemyFireStrategy]).
 */
object FireCadence {
    /** Ordinary single-shot rhythm (single shot weapons). */
    const val NORMAL_MS = 1_500L

    /** Legacy name of the one-second beat. */
    const val CONTINUOUS_MS = 1_000L

    /** Submachine gun: lightest automatic bullet, 0.5 second beat. */
    const val AUTO_FAST_MS = 500L

    /** AK-47: medium automatic bullet, 1 second beat. */
    const val AUTO_MEDIUM_MS = 1_000L

    /** Machine gun: heaviest automatic bullet, 1.5 second beat. */
    const val AUTO_SLOW_MS = 1_500L

    /** True when [intervalMs] is one of the three automatic beats. */
    fun isAutomaticBeat(intervalMs: Long): Boolean =
        intervalMs == AUTO_FAST_MS || intervalMs == AUTO_MEDIUM_MS || intervalMs == AUTO_SLOW_MS
}
