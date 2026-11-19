package com.blindtechnexus.openfire.model

import kotlin.math.roundToInt

/**
 * Shock damage and timing rules.
 *
 * Two situations exist, and they are deliberately described by two different
 * functions so no caller can mix them up:
 *
 *  - **Alone** ([shockPercentPerSecond]) the shock drains the whole proximity
 *    ladder of the game: 50% of maximum health per second at 40% to 100%
 *    proximity (the only band in which the shock works at all) and 10% per
 *    second outside it.
 *  - **When both fighters shock at once** ([duelPercentPerSecond]) the fight
 *    turns into a duel of colliding shocks. Both fighters lose exactly 10% of
 *    their maximum health every [DUEL_WINDOW_MS] (two seconds), so the duel is
 *    perfectly symmetric and the fighter with the weaker health bar loses
 *    first. The single-striker 50% rule is the whole law of a solo shock: the
 *    old flat "60 health points every second" is gone, so the drain follows
 *    the victim's own health bar in every round and at every level.
 *
 * The enemy may either counter the player's shock ([RobertRules.shouldCounterShock])
 * or flee out of it ([RobertRules.shouldFleeShock]); the battle engine rolls
 * that decision at random.
 */
object ElectricShockRules {
    /** Longest continuous use, measured on the pausable battle clock. */
    const val MAX_USE_MS = 11_000L

    /** Continuous use needed to push a missile or moving car. */
    const val PUSH_DELAY_MS = 5_000L

    /** The robot answers after five seconds when a shock is detected. */
    const val COUNTER_DELAY_MS = 5_000L

    /** Drain of the opponent's health per second while alone in the close band. */
    const val PLAYER_CLOSE_PERCENT_PER_SECOND = 50f

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

    /**
     * Continuous shock needed to destroy a gloo wall. This must fit within one
     * ten-second use; cover remains the quicker barrier to break.
     */
    const val GLUE_WALL_BREAK_MS = 9_000L

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
     * Alone, the close band decides (50 or 10 percent per second). While both
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

    /**
     * The whole duel drain of one two-second window, for BOTH fighters.
     *
     * A duel is symmetric by law: whoever struck first gains nothing, and
     * because the share is taken from each fighter's own maximum health, a
     * 2600-health bar loses thirteen times as many points as a 200-health bar
     * in the same window. Both numbers are returned together so a caller can
     * never apply one law to the player and another to the enemy - which is
     * exactly the bug this function replaced (Robert was drained by a
     * percentage, the player by a flat ten points).
     *
     * @param playerMaxHp the player's maximum health this battle.
     * @param enemyMaxHp the enemy's maximum health this battle.
     * @param windows whole two-second windows elapsed; a partial window pays
     *   nothing, so the drain can never drift between ticks.
     */
    fun duelDamagePerWindows(
        playerMaxHp: Int,
        enemyMaxHp: Int,
        windows: Long
    ): Pair<Int, Int> {
        if (windows <= 0L) return Pair(0, 0)
        val elapsedMs = windows * DUEL_WINDOW_MS
        val playerLoss = damageFor(playerMaxHp, duelPercentPerSecond(), elapsedMs)
            .roundToInt()
        val enemyLoss = damageFor(enemyMaxHp, duelPercentPerSecond(), elapsedMs)
            .roundToInt()
        return Pair(playerLoss, enemyLoss)
    }

    // ------------------------------------------------------------------
    // The throw and the way back
    // ------------------------------------------------------------------

    /**
     * Metres the returning enemy covers on each movement tick.
     *
     * The throw must give the player real breathing room, so the walk back is
     * deliberately unhurried: at [RETURN_STEP_M] every [RETURN_TICK_MS] the
     * enemy needs about 3.4 seconds to cross the shortest line of the arena
     * and about 6.9 seconds to walk back from the farthest corner. While he
     * walks, his gun is silent ([GameState.robertShockRecovering]), so the
     * throw can never be answered in a second as it used to be.
     */
    const val RETURN_STEP_M = 1.6f

    /** Length of a return tick, in pausable battle-clock milliseconds. */
    const val RETURN_TICK_MS = 300L

    /**
     * Silence that follows a finished return walk.
     *
     * A throw must never be answered with a shot a second later. Even after
     * the enemy has walked all the way back into fighting range he keeps his
     * gun lowered for this long, so every throw really buys the player the
     * breathing room the throw is for: up to about seven seconds of walking
     * (the farthest-corner landing) plus a further [RECOVERY_SILENCE_MS]
     * before his trigger is live again.
     */
    const val RECOVERY_SILENCE_MS = 1_500L

    /** Ticks of the return walk for a landing this far from the player. */
    fun returnTicksForDistance(distance: Float): Int = returnTicks(distance)

    /**
     * Total milliseconds a throw keeps the enemy silent: the walk back plus
     * the recovery silence. This is the number the battle engine and the
     * tests can reason about when they promise "he cannot return in a second".
     */
    fun throwSilenceMs(fromDistance: Float): Long =
        returnTimeMs(fromDistance) + RECOVERY_SILENCE_MS

    /**
     * Capacity of the enemy's anti-bomb explosive: it stops two bombs, one
     * less than the player's own three ([BombRules]).
     */
    const val ENEMY_ANTI_BOMB_STOPS = 2

    /**
     * The farthest reachable point in the square from the player. The farthest
     * point is always a corner, even when the player is in the centre or at a
     * wall. Every landing is beyond ordinary gun range. An AWM can still reach
     * some centre-to-corner landings: no point farther away exists there, so
     * the battle engine separately blocks attacks until Robert returns. Ties
     * are deterministic and coordinates use Arena's normal bounds/fallbacks.
     *
     * The landing is kept [ROBERT_WALL_MARGIN] metres inside the boundary, so
     * the thrown fighter is standing in the arena rather than wedged into the
     * wall he lands on.
     */
    fun farthestLanding(playerX: Float, playerY: Float): Pair<Float, Float> {
        val x = Arena.clampX(playerX)
        val y = Arena.clampY(playerY)
        val middle = Arena.SIZE / 2f
        return Pair(
            if (x < middle) Arena.MAX else Arena.MIN,
            if (y < middle) Arena.MAX else Arena.MIN
        )
    }

    /**
     * Where a thrown car lands: the same farthest arena corner a thrown
     * fighter reaches, so the car has the whole diagonal to drive back over.
     *
     * A car that is thrown out of the shock is handled as a wreck by the
     * battle engine when the push finishes ([throwWrecksCar]) - it never
     * silently pops back beside the player.
     */
    fun carLanding(playerX: Float, playerY: Float): Pair<Float, Float> {
        val (x, y) = farthestLanding(playerX, playerY)
        return Pair(Arena.clampX(x), Arena.clampY(y))
    }

    /** True when a throw that reaches its final push destroys the enemy's car. */
    fun throwWrecksCar(targetInCar: Boolean, pushedToFarthestPoint: Boolean): Boolean =
        targetInCar && pushedToFarthestPoint

    /**
     * Metres between the landing point and the player, used by the engine to
     * report the throw distance back as speech.
     */
    fun throwDistance(playerX: Float, playerY: Float, landing: Pair<Float, Float>): Float =
        Arena.distanceBetween(playerX, playerY, landing.first, landing.second)

    /** A countershock halves the displacement, without changing the release sound. */
    fun pushLanding(playerX: Float, playerY: Float, targetX: Float, targetY: Float, countered: Boolean): Pair<Float, Float> {
        val (farX, farY) = farthestLanding(playerX, playerY)
        val fraction = if (countered) 0.5f else 1f
        val x = Arena.clampX(targetX)
        val y = Arena.clampY(targetY)
        return Pair(x + (farX - x) * fraction, y + (farY - y) * fraction)
    }

    /**
     * One real step toward the player's current position. A square is convex,
     * so this direct route stays inside its walls even when the throw lands
     * exactly on a corner. Clamp the step to the *uncapped* Euclidean distance
     * to avoid overshooting, including when both fighters are on the same spot.
     */
    fun returnPosition(playerX: Float, playerY: Float, enemyX: Float, enemyY: Float): Pair<Float, Float> {
        val px = Arena.clampX(playerX)
        val py = Arena.clampY(playerY)
        val ex = Arena.clampX(enemyX)
        val ey = Arena.clampY(enemyY)
        val actualDistance = kotlin.math.hypot(px - ex, py - ey)
        val move = Arena.moveToward(ex, ey, px, py, minOf(RETURN_STEP_M, actualDistance))
        return Pair(move.x, move.y)
    }

    /**
     * Return ticks for an actual, uncapped distance. There is no arbitrary
     * eight-tick ceiling: a diagonal across the arena is longer than one side.
     */
    fun returnTicks(fromDistance: Float, toDistance: Float = 0f): Int {
        val from = if (fromDistance.isFinite()) fromDistance.coerceAtLeast(0f) else 0f
        val to = if (toDistance.isFinite()) toDistance.coerceAtLeast(0f) else 0f
        val gap = (from - to).coerceAtLeast(0f)
        return kotlin.math.ceil(gap / RETURN_STEP_M).toInt()
    }

    /** Milliseconds of movement for the corresponding distance. */
    fun returnTimeMs(fromDistance: Float, toDistance: Float = 0f): Long =
        returnTicks(fromDistance, toDistance) * RETURN_TICK_MS
}
