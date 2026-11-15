package com.blindtechnexus.openfire.model

/**
 * The electric shock. The user holds the up-swipe to shock Robert; Robert can
 * only answer with his own shock, and only while the user is shocking.
 *
 *  - The user drains 80% of Robert's maximum health per second while he stands
 *    in the close band (40%-100% proximity). Outside that band the shock is
 *    weak: 10% per second.
 *  - Robert may answer only after the user has been holding for five seconds.
 *    His shock drains 10% of the user's maximum health per second and stops the
 *    moment the user lets go.
 *  - A single use lasts at most thirty seconds. When the time is up both shocks
 *    stop, and the user has to swipe again.
 */
object ElectricShockRules {
    /** Longest single use, in milliseconds. */
    const val MAX_USE_MS = 30_000L

    /** Robert answers only once the user has held the shock this long. */
    const val COUNTER_DELAY_MS = 5_000L

    /** Drain of Robert's health per second while the user is in the close band. */
    const val PLAYER_CLOSE_PERCENT_PER_SECOND = 80f

    /** Drain of Robert's health per second outside the close band. */
    const val PLAYER_FAR_PERCENT_PER_SECOND = 10f

    /** Drain of the user's health per second while Robert answers. */
    const val ROBERT_COUNTER_PERCENT_PER_SECOND = 10f

    /** Lower edge of the close band, in proximity percent. */
    const val CLOSE_BAND_MIN_PROXIMITY = 40f

    /** Upper edge of the close band, in proximity percent. */
    const val CLOSE_BAND_MAX_PROXIMITY = 100f

    /** Health the user's shock drains per second at [proximityPercent]. */
    fun playerDrainPercentPerSecond(proximityPercent: Float): Float =
        if (proximityPercent >= CLOSE_BAND_MIN_PROXIMITY &&
            proximityPercent <= CLOSE_BAND_MAX_PROXIMITY
        ) {
            PLAYER_CLOSE_PERCENT_PER_SECOND
        } else {
            PLAYER_FAR_PERCENT_PER_SECOND
        }

    /** True once Robert may answer with his own shock. */
    fun counterOpen(heldMs: Long): Boolean = heldMs >= COUNTER_DELAY_MS

    /** True once the use has lasted long enough to stop by itself. */
    fun timeUp(heldMs: Long): Boolean = heldMs >= MAX_USE_MS

    /** Health removed in [dtMs] milliseconds by a drain of [percentPerSecond]. */
    fun damageFor(maxHp: Int, percentPerSecond: Float, dtMs: Long): Float =
        maxHp.coerceAtLeast(0) * percentPerSecond / 100f * dtMs.coerceAtLeast(0L) / 1000f
}
