package com.blindtechnexus.openfire.model

/** Shock damage and timing rules. The enemy only counters the player's hold. */
object ElectricShockRules {
    /** Longest single use, in milliseconds. */
    const val MAX_USE_MS = 30_000L

    /** The robot answers immediately when a shock is detected. */
    const val COUNTER_DELAY_MS = 0L

    /** Drain of the opponent's health per second while alone in the close band. */
    const val PLAYER_CLOSE_PERCENT_PER_SECOND = 60f

    /** Drain of the opponent's health per second while alone outside the close band. */
    const val PLAYER_FAR_PERCENT_PER_SECOND = 10f

    /**
     * While both shocks run, the fighter who activated FIRST drains the
     * antagonist at 10 percent per second.
     */
    const val FIRST_STRIKER_PERCENT_PER_SECOND = 10f

    /**
     * While both shocks run, the fighter who activated second drains the
     * first one at 10 percent per two seconds - much weaker, so the first
     * activation always wins a duel of shocks.
     */
    const val SECOND_STRIKER_PERCENT_PER_THREE_SECONDS = 10f

    /** The two-second window of the second striker's slow drain. */
    const val SECOND_STRIKER_WINDOW_MS = 2_000L

    /** Lower edge of the close band, in proximity percent. */
    const val CLOSE_BAND_MIN_PROXIMITY = 40f

    /** Upper edge of the close band, in proximity percent. */
    const val CLOSE_BAND_MAX_PROXIMITY = 100f

    /**
     * Only a shock held inside the 90%-100% band can break a glue wall or a
     * cover, and only after ten seconds of continuous use.
     */
    const val BREAK_BAND_MIN_PROXIMITY = 90f

    /** Continuous seconds of close-band shock needed to break a barrier. */
    const val BREAK_SUSTAINED_MS = 10_000L

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

    /** Health the first striker drains per second while both shocks run. */
    fun firstStrikerPercentPerSecond(): Float = FIRST_STRIKER_PERCENT_PER_SECOND

    /**
     * Health the second striker drains per second while both shocks run:
     * 10 percent spread over three seconds, so a continuous duel slowly
     * costs the first striker 10 percent every three seconds.
     */
    fun secondStrikerPercentPerSecond(): Float =
        SECOND_STRIKER_PERCENT_PER_THREE_SECONDS * 1000f / SECOND_STRIKER_WINDOW_MS

    /** True once the opponent may answer with their own shock. */
    fun counterOpen(heldMs: Long): Boolean = heldMs >= COUNTER_DELAY_MS

    /** True once the use has lasted long enough to stop by itself. */
    fun timeUp(heldMs: Long): Boolean = heldMs >= MAX_USE_MS

    /**
     * True once a continuous shock of [heldMs] inside the 90%-100% band has
     * lasted long enough to break a glue wall or a cover.
     */
    fun barrierBreakReady(heldMs: Long): Boolean = heldMs >= BREAK_SUSTAINED_MS

    /** Health removed in [dtMs] milliseconds by a drain of [percentPerSecond]. */
    fun damageFor(maxHp: Int, percentPerSecond: Float, dtMs: Long): Float =
        maxHp.coerceAtLeast(0) * percentPerSecond / 100f * dtMs.coerceAtLeast(0L) / 1000f

    /**
     * The drain percentage of one fighter's shock - the whole shock law in
     * one pure function. Alone, the close band decides (60 or 10 percent per
     * second). While both shocks run, the activation order decides: the first
     * striker drains 10 percent per second and the second striker only
     * 10 percent per two seconds.
     *
     * @param proximityPercent closeness of the target (100 = point blank).
     * @param bothShocking true while the antagonist's shock runs as well.
     * @param activatedFirst true when this fighter activated before the antagonist.
     */
    fun shockPercentPerSecond(
        proximityPercent: Float,
        bothShocking: Boolean,
        activatedFirst: Boolean
    ): Float = when {
        bothShocking && activatedFirst -> FIRST_STRIKER_PERCENT_PER_SECOND
        bothShocking -> secondStrikerPercentPerSecond()
        else -> playerDrainPercentPerSecond(proximityPercent)
    }
}
