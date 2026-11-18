package com.blindtechnexus.openfire.model

/**
 * Shock damage and timing rules.
 *
 * Two situations exist, and they are deliberately described by two different
 * functions so no caller can mix them up:
 *
 *  - **Alone** ([shockPercentPerSecond]) the shock drains the whole proximity
 *    ladder of the game: 60% of maximum health per second at 40% to 100%
 *    proximity (the only band in which the shock works at all) and 10% per
 *    second outside it.
 *  - **When both fighters shock at once** ([duelPercentPerSecond]) the fight
 *    turns into a duel of colliding shocks. Both fighters lose exactly 10% of
 *    their maximum health every [DUEL_WINDOW_MS] (two seconds), so the duel is
 *    perfectly symmetric and the fighter with the weaker health bar loses
 *    first.
 *
 * The enemy may either counter the player's shock ([RobertRules.shouldCounterShock])
 * or flee out of it ([RobertRules.shouldFleeShock]); the battle engine rolls
 * that decision at random.
 */
object ElectricShockRules {
    /** Longest single use, in milliseconds. */
    const val MAX_USE_MS = 30_000L

    /** The robot answers immediately when a shock is detected. */
    const val COUNTER_DELAY_MS = 0L

    /** Drain of the opponent's health per second while alone in the close band. */
    const val PLAYER_CLOSE_PERCENT_PER_SECOND = 60f

    /** Drain of the opponent's health per second while alone outside the close band. */
    const val PLAYER_FAR_PERCENT_PER_SECOND = 10f

    /** Health percentage both fighters lose in one window of a shock duel. */
    const val DUEL_PERCENT_PER_WINDOW = 10f

    /** The two-second window of the duel drain: 10 percent every two seconds. */
    const val DUEL_WINDOW_MS = 2_000L

    /**
     * Percentage per second of the duel drain, derived from the two-second
     * window. Exposed so the guide, the tests and the engine all read the
     * same number.
     */
    val DUEL_PERCENT_PER_SECOND: Float =
        DUEL_PERCENT_PER_WINDOW * 1000f / DUEL_WINDOW_MS

    /** Kept for older callers: the single-striker drain in a duel. */
    const val FIRST_STRIKER_PERCENT_PER_SECOND = DUEL_PERCENT_PER_WINDOW * 1000f / DUEL_WINDOW_MS

    /** Kept for older callers: the mirror-image drain of the other striker. */
    const val SECOND_STRIKER_PERCENT_PER_THREE_SECONDS = DUEL_PERCENT_PER_WINDOW

    /** The two-second window of the second striker's slow drain. */
    const val SECOND_STRIKER_WINDOW_MS = DUEL_WINDOW_MS

    /** Lower edge of the close band, in proximity percent. */
    const val CLOSE_BAND_MIN_PROXIMITY = 40f

    /** Upper edge of the close band, in proximity percent. */
    const val CLOSE_BAND_MAX_PROXIMITY = 100f

    /**
     * Only continuous shock inside the 90%-100% band breaks a barrier. The
     * required duration depends on whether the target has a gloo wall or cover.
     */
    const val BREAK_BAND_MIN_PROXIMITY = 90f

    /** Continuous shock needed to destroy a gloo wall. */
    const val GLUE_WALL_BREAK_MS = 15_000L

    /** Continuous shock needed to destroy a cover. */
    const val COVER_BREAK_MS = 7_000L

    /** Backwards-compatible name for the longer gloo-wall timer. */
    const val BREAK_SUSTAINED_MS = GLUE_WALL_BREAK_MS

    /**
     * The share of the opponent's health one shock use must drain before the
     * use is worth the full shock payout. Anything above zero is already a
     * real use and pays [Rewards.SHOCK_MIN_COINS].
     */
    const val MIN_PAYABLE_DRAIN_HP = 1

    /** Health the shock drains per second at [proximityPercent] while alone. */
    fun playerDrainPercentPerSecond(proximityPercent: Float): Float =
        if (inCloseBand(proximityPercent)) {
            PLAYER_CLOSE_PERCENT_PER_SECOND
        } else {
            PLAYER_FAR_PERCENT_PER_SECOND
        }

    /** True while the target is inside the 40%-100% close band. */
    fun inCloseBand(proximityPercent: Float): Boolean =
        proximityPercent >= CLOSE_BAND_MIN_PROXIMITY &&
            proximityPercent <= CLOSE_BAND_MAX_PROXIMITY

    /** True while the target is inside the 90%-100% barrier-breaking band. */
    fun inBreakBand(proximityPercent: Float): Boolean =
        proximityPercent >= BREAK_BAND_MIN_PROXIMITY &&
            proximityPercent <= CLOSE_BAND_MAX_PROXIMITY

    /** Health percentage per second the first striker drains in a duel. */
    fun firstStrikerPercentPerSecond(): Float = DUEL_PERCENT_PER_SECOND

    /** Health percentage per second the other striker drains in a duel. */
    fun secondStrikerPercentPerSecond(): Float = DUEL_PERCENT_PER_SECOND

    /**
     * Health percentage per second each fighter loses while both shocks run.
     * The duel is symmetric: whoever activated first gains nothing, so the
     * 10 percent every two seconds applies to both fighters alike.
     */
    fun duelPercentPerSecond(): Float = DUEL_PERCENT_PER_SECOND

    /** True once the opponent may answer with their own shock. */
    fun counterOpen(heldMs: Long): Boolean = heldMs >= COUNTER_DELAY_MS

    /** True once the use has lasted long enough to stop by itself. */
    fun timeUp(heldMs: Long): Boolean = heldMs >= MAX_USE_MS

    /** Required continuous shock duration for the selected barrier. */
    fun barrierBreakDurationMs(isGlueWall: Boolean): Long =
        if (isGlueWall) GLUE_WALL_BREAK_MS else COVER_BREAK_MS

    /** True once a continuous close-band shock can destroy this barrier. */
    fun barrierBreakReady(heldMs: Long, isGlueWall: Boolean = true): Boolean =
        heldMs >= barrierBreakDurationMs(isGlueWall)

    /** Health removed in [dtMs] milliseconds by a drain of [percentPerSecond]. */
    fun damageFor(maxHp: Int, percentPerSecond: Float, dtMs: Long): Float =
        maxHp.coerceAtLeast(0) * percentPerSecond / 100f * dtMs.coerceAtLeast(0L) / 1000f

    /**
     * The drain percentage of one fighter's shock - the whole shock law in
     * one pure function.
     *
     * Alone, the close band decides (60 or 10 percent per second). While both
     * shocks run the duel law replaces it: 10 percent of maximum health every
     * two seconds for both fighters, whatever the proximity and whoever
     * activated first.
     *
     * @param proximityPercent closeness of the target (100 = point blank).
     * @param bothShocking true while the antagonist's shock runs as well.
     * @param activatedFirst kept for compatibility; the duel is symmetric.
     */
    fun shockPercentPerSecond(
        proximityPercent: Float,
        bothShocking: Boolean,
        activatedFirst: Boolean
    ): Float = when {
        bothShocking -> duelPercentPerSecond()
        else -> playerDrainPercentPerSecond(proximityPercent)
    }

    /** True once one shock use has drained enough health to be worth coins. */
    fun paysCoins(drainedHp: Int): Boolean = drainedHp >= MIN_PAYABLE_DRAIN_HP
}
