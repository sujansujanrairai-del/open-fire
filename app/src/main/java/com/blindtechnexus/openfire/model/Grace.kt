package com.blindtechnexus.openfire.model

/**
 * The grace window that opens while a guided projectile is in the air.
 *
 * The rule of the game is simple and absolute: while a missile or a bomb is
 * flying, nothing else is fired and nothing else shocks the opponent. A held
 * weapon is not simply ignored, though - it is **held aiming at the opponent
 * and then drifts back toward the player**, which is exactly the time a blind
 * player needs to reach the quick menu and deploy the anti-missile system or
 * the anti-bomb explosive.
 *
 * [GraceRules.driftMeters] describes that drift: it grows while the sky is not
 * clear, so the weapon really moves back towards the player, and it decays once
 * the projectile has resolved, so the fight resumes from the position the
 * player is holding. The drift never changes the real distance between the
 * fighters - it is the aim of the held weapon, and it only decides whether the
 * weapon can reach the opponent.
 */
object GraceRules {
    /** Distance the held weapon drifts back from the opponent per tick. */
    const val DRIFT_PER_TICK_M = 0.35f

    /** Length of one drift tick. */
    const val DRIFT_TICK_MS = 100L

    /** Hard cap of the drift, so it can never run away. */
    const val MAX_DRIFT_M = Combat.MAX_RANGE / 2f

    /**
     * Grace time a player is given before an incoming projectile resolves at
     * all: the shortest flight of the game is the atom bomb, so this is the
     * guaranteed minimum window to deploy a defence.
     */
    const val MIN_GRACE_MS = 1_500L

    /** Drift after one more tick of a held weapon. */
    fun driftAfter(drift: Float): Float =
        (drift.coerceAtLeast(0f) + DRIFT_PER_TICK_M).coerceAtMost(MAX_DRIFT_M)

    /** Drift after the projectile resolved: the weapon walks back in. */
    fun driftReleased(drift: Float): Float =
        (drift.coerceAtLeast(0f) - DRIFT_PER_TICK_M).coerceAtLeast(0f)

    /** True while the held weapon has drifted back at all. */
    fun isHeld(drift: Float): Boolean = drift > 0.01f

    /** True when the grace window is still open for the player. */
    fun graceOpen(elapsedMs: Long): Boolean = elapsedMs in 0..MIN_GRACE_MS

    /** Metres of drift gained in [elapsedMs], for the very first refusal. */
    fun driftFor(elapsedMs: Long): Float =
        (DRIFT_PER_TICK_M * (elapsedMs.coerceAtLeast(0L) / DRIFT_TICK_MS.toFloat()))
            .coerceIn(0f, MAX_DRIFT_M)

    /**
     * The distance a weapon is really aiming at: the fight distance plus the
     * drift of the held weapon. A held weapon therefore aims further away than
     * the opponent really is, which is what "the weapon drifts back" means for
     * the proximity ladder.
     */
    fun aimedDistance(fightDistance: Float, drift: Float): Float =
        (fightDistance.coerceAtLeast(0f) + drift.coerceAtLeast(0f)).coerceAtMost(Combat.MAX_RANGE)

    /** True when a held weapon has drifted right out of its useful range. */
    fun driftedOutOfRange(fightDistance: Float, drift: Float): Boolean =
        !ElectricShockRules.inCloseBand(Combat.proximityOf(aimedDistance(fightDistance, drift)))
}
