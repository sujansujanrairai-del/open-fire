package com.blindtechnexus.openfire.model

/**
 * The "clear sky" rule: while a projectile is in the air, **neither fighter**
 * may use a weapon.
 *
 * Why one rule for both
 * ---------------------
 * A missile or a bomb is a slow, guided event: it takes seconds to travel and
 * it decides the fight when it lands. Guns have always been held during that
 * time, because the grace window is exactly what lets the player deploy an
 * anti-missile system or an anti-bomb explosive.
 *
 * The bug this object fixes was an asymmetry: the check was written as
 * "a missile is in flight, or the bomb in flight is *mine*", so a bomb thrown
 * by the enemy blocked the player's weapon and left the enemy free to keep
 * firing. The player therefore stood in a fight he could not shoot back in,
 * while the enemy fired at will - and, if a projectile never resolved, the
 * player was locked out of his weapon for the rest of the match.
 *
 * The rule is therefore stated once, for both fighters
 * ([blocksWeapons]), and it is *bounded*: a projectile that never resolves
 * (a lost callback, a screen change in the middle of a flight, a bomb whose
 * explosion was cut short) cannot hold the sky forever. When the flight has
 * outlived its maximum, [expired] says so and the battle engine releases the
 * lock instead of hanging on a stale flag.
 */
object SkyLock {

    /**
     * Longest a projectile may hold the sky, in milliseconds.
     *
     * The slowest flight of the game - a timer bomb counted down to its last
     * second plus the chained explosion - is comfortably inside this window,
     * so a real projectile is never cut short. Anything longer is a stale
     * flag, not a projectile.
     */
    const val MAX_HOLD_MS = 20_000L

    /**
     * True while a projectile of either fighter is in the air.
     *
     * This is the single authority: the player's trigger and the enemy's
     * trigger both read it, so the fight can never be one-sided again.
     */
    fun blocksWeapons(missileInFlight: Boolean, bombInFlight: Boolean): Boolean =
        missileInFlight || bombInFlight

    /**
     * True when the projectile has been in the air longer than any real flight
     * may last, which means the flag is stale and the lock must be released.
     *
     * @param heldSinceMs when the projectile was launched, or 0 when unknown.
     */
    fun expired(heldSinceMs: Long, nowMs: Long): Boolean =
        heldSinceMs > 0L && nowMs - heldSinceMs > MAX_HOLD_MS

    /**
     * Milliseconds of a real flight that are left, never negative. The battle
     * engine uses it to tell the player how long the sky stays closed.
     */
    fun remainingMs(heldSinceMs: Long, nowMs: Long): Long =
        if (heldSinceMs <= 0L) 0L else (MAX_HOLD_MS - (nowMs - heldSinceMs)).coerceAtLeast(0L)

    /** Spoken key of a refused shot: the only feedback the player gets. */
    const val CANT_FIRE_KEY = "cantFire"
}
