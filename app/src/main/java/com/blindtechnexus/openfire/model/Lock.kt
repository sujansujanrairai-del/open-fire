package com.blindtechnexus.openfire.model

/**
 * "Lock the Opponent": the fifth item of the battle quick menu.
 *
 * The lock is a pure, timed state of the fight:
 *
 *  - a player who owns or buys the lock may freeze the opponent completely for
 *    [DURATION_MS] (one minute), after which the lock lifts by itself;
 *  - a locked opponent cannot move, cannot fire, cannot throw a bomb or a
 *    missile, cannot use a defence and cannot flee: every one of his own
 *    action loops is stopped the moment the lock starts;
 *  - an offensive weapon cannot be used AGAINST a locked opponent either, so
 *    the lock freezes the fight instead of becoming a free execution;
 *  - defensive items stay fully usable during the lock - the car, the
 *    anti-missile system, the anti-bomb explosive, and wall or glue covers -
 *    so the player can prepare [DURATION_MS] of breathing room in peace.
 *
 * The rules live here, with no Android dependency, so the menu, the battle
 * engine, the HUD and the tests all read the same numbers.
 */
object LockRules {
    /** Shop price of one "Lock the Opponent" charge. */
    const val PRICE = 1500

    /** How long one lock freezes the opponent: one minute. */
    const val DURATION_MS = 60_000L

    /** Completed rounds required before the lock appears in the quick menu. */
    const val REQUIRED_COMPLETED_ROUNDS = 1

    /** Rounds between the lock ticks that announce the remaining time. */
    const val ANNOUNCE_EVERY_MS = 15_000L

    /** True while the opponent is frozen by a lock that has not expired. */
    fun isLocked(lockedUntilMs: Long, nowMs: Long): Boolean =
        lockedUntilMs > 0L && nowMs < lockedUntilMs

    /** Milliseconds the lock has left, never negative. */
    fun remainingMs(lockedUntilMs: Long, nowMs: Long): Long =
        (lockedUntilMs - nowMs).coerceAtLeast(0L)

    /** The moment a lock started [nowMs] will end. */
    fun endsAt(nowMs: Long): Long = nowMs + DURATION_MS

    /**
     * True while the lock may be (re)activated: a lock is never stacked or
     * extended by a second click, it simply runs its full minute first.
     */
    fun canActivate(alreadyLocked: Boolean, charges: Int): Boolean =
        !alreadyLocked && charges > 0

    /**
     * True when the opponent's own brain must stand still: no movement step,
     * no shot, no missile, no bomb, no defence and no sprint.
     */
    fun blocksOpponent(locked: Boolean): Boolean = locked

    /**
     * True when an OFFENSIVE weapon must refuse to fire at the opponent.
     * Guns, melee, launchers and explosives are offensive; the electric shock
     * counts as offensive too because it is aimed at the opponent.
     */
    fun blocksOffensiveWeapons(locked: Boolean): Boolean = locked

    /**
     * True when a DEFENSIVE item may still be used while the opponent is
     * locked. The car, the anti-missile system, the anti-bomb explosive, the
     * wall and the glue cover are all defensive and always allowed.
     */
    fun allowsDefence(locked: Boolean): Boolean = true

    /**
     * True when [item] may be used while the opponent is locked. Every item of
     * the quick menu is defensive or neutral: the car, the two interceptors,
     * the wall, the glue cover and the medikit all protect the player, and the
     * lock itself is never needed twice. Nothing in the defence menu can hurt
     * the frozen opponent, so nothing there is blocked.
     */
    fun isDefensive(item: DefenceItem): Boolean = DefenceItem.entries.contains(item)

    /**
     * True when the weapon in hand is offensive, and therefore refused while
     * the opponent is locked. Every weapon of the game is fired at the
     * opponent, so the whole arsenal is frozen with him.
     */
    @Suppress("UNUSED_PARAMETER")
    fun weaponIsOffensive(weapon: Weapon): Boolean = true

    /** Identifier of the lock in the shop and the battle menu. */
    const val ITEM_ID = "lockOpponent"

    /** Spoken key of the lock item in the quick menu. */
    const val ITEM_KEY = "lockOpponent"

    /** Spoken confirmation of one activation, as a translation key. */
    const val ACTIVATED_KEY = "lockOpponentActivated"

    /** Spoken notice when the minute has run out, as a translation key. */
    const val EXPIRED_KEY = "lockOpponentExpired"
}
