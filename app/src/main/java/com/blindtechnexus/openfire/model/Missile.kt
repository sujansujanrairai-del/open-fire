package com.blindtechnexus.openfire.model

/**
 * Pure missile rules, shared by the shop, the battle engine and Robert.
 *
 * A missile is a guided attack:
 *  - it unlocks after round two has been completed and costs
 *    [MISSILE_PRICE] coins, appearing in the normal weapon inventory;
 *  - every launch consumes exactly one missile power ([MISSILE_POWER_PRICE]
 *    coins each). Powers stack like defences and must be bought again once
 *    used;
 *  - once launched it tracks its target through ordinary barriers and, when
 *    it hits, completely destroys that fighter: the missile removes every
 *    remaining health point, so a direct hit always ends the battle. The only
 *    way to survive is an anti-missile interceptor that destroys the missile
 *    in the air. A missile hit bypasses the second chance;
 *  - the target still tries to run as far away as possible inside the 25 m
 *    arena while the missile sound plays. If the target can run, the sound
 *    loops and is cut the moment the missile impacts; if the target has
 *    nowhere left to run, the sound plays once and the missile impacts as
 *    it ends. The explosion (and the target hit sound)
 *    always plays at impact, mixed together, and the battle only ends once
 *    the explosion sound has really finished ([EXPLOSION_MS]);
 *  - in round three Robert has no anti-missile system at all, so a launched
 *    missile simply makes him sprint away at his fast speed, running and
 *    dodging inside the zone until the missile catches him;
 *  - from round four Robert owns an anti-missile system, but he may only
 *    fire it in an end-game situation: his health below
 *    [ANTI_MISSILE_MAX_HP] while a player missile is in the air. Round
 *    four holds two interceptors, round five holds four;
 *  - from round four Robert also carries one missile power per game (two in
 *    round five). He spends it only as a desperation weapon while he is
 *    clearly losing.
 */
object MissileRules {
    /** Shop price of the missile launcher itself (rebalanced). */
    const val MISSILE_PRICE = 2200

    /** Shop price of one consumable missile power (rebalanced). */
    const val MISSILE_POWER_PRICE = 1000

    /** Shop price of one anti-missile defence system (rebalanced). */
    const val ANTI_MISSILE_PRICE = 1800

    /**
     * Length of `missile.mp3` (about 14 seconds). A direct hit lands
     * exactly when this sound finishes.
     */
    const val SOUND_DURATION_MS = SoundDurations.MISSILE_MS

    /**
     * Length of `explosion.mp3`. The battle ends only after the impact
     * explosion has really finished playing, never on the frame of the hit.
     */
    const val EXPLOSION_MS = SoundDurations.EXPLOSION_MS

    /** The flight sound sits slightly under the other effects. */
    const val SOUND_VOLUME = 0.92f

    /** A chasing missile plays its looped flight sound a touch faster. */
    const val CHASE_SOUND_RATE = 1.06f

    /** Extra chase time for every metre the target can still run away. */
    const val CHASE_MS_PER_METER = 60L

    /** Hard cap so a fleeing chase can never drag on forever. */
    const val MAX_FLIGHT_MS = 22_000L

    /** Robert may fire an interceptor only while below this much health. */
    const val ANTI_MISSILE_MAX_HP = 100

    /** First round that carries missile and anti-missile equipment. */
    const val FIRST_MISSILE_ROUND = 4

    /** Robert's health fraction at (or below) which he is "clearly losing". */
    const val ENEMY_LAUNCH_MAX_HP_FRACTION = 0.40f

    /** Shots Robert must have fired before he considers his missile. */
    const val ENEMY_LAUNCH_MIN_SHOTS = 3

    /**
     * A direct missile hit destroys its target: it deals every health point
     * the victim still has. There is no percentage, no cap and no second
     * chance - only an anti-missile interception prevents the destruction.
     */
    const val DIRECT_HIT_DESTROYS = true

    /** Damage of a direct missile impact on a victim with [remainingHp] health. */
    fun impactDamage(remainingHp: Int): Int = remainingHp.coerceAtLeast(0)

    /** True when the target still has room to run away inside the arena. */
    fun targetWillFlee(distance: Float): Boolean =
        distance < Combat.MAX_RANGE - 1f

    /**
     * Sprint speed of a target running from a missile, in metres per second.
     * It is Robert's fast running speed for the round plus the panic bonus,
     * which is exactly the speed the player's fast three-finger level reaches.
     */
    fun fleeSpeedMps(round: Int): Float =
        RobertRules.enemyFastSpeedMps(round) * FLEE_PANIC_FACTOR

    /** Extra urgency of a missile escape. */
    const val FLEE_PANIC_FACTOR = 1.15f

    /** Metres covered per flee tick while escaping a missile. */
    fun fleeStep(round: Int, tickMs: Long): Float =
        fleeSpeedMps(round) * (tickMs / 1000f)

    /**
     * How long a launched missile stays in the air. A target with nowhere
     * to run is hit after exactly one play of the missile sound. A fleeing
     * target is chased: the sound loops and the flight grows with the room
     * the target has to run, capped by [MAX_FLIGHT_MS]. The missile always
     * catches its target either way.
     */
    fun flightMs(distanceAtLaunch: Float): Long {
        val distance = distanceAtLaunch.coerceIn(0f, Combat.MAX_RANGE)
        if (!targetWillFlee(distance)) return SOUND_DURATION_MS
        val roomToRun = Combat.MAX_RANGE - distance
        val extra = (roomToRun * CHASE_MS_PER_METER).toLong()
        return (SOUND_DURATION_MS + extra).coerceIn(SOUND_DURATION_MS, MAX_FLIGHT_MS)
    }

    /** True while [flightMs] says the missile sound must loop (a chase). */
    fun soundLoops(distanceAtLaunch: Float): Boolean = targetWillFlee(distanceAtLaunch)

    /** True when a launch is allowed right now. */
    fun canLaunch(ownsMissile: Boolean, missilePowers: Int, missileInFlight: Boolean): Boolean =
        ownsMissile && missilePowers > 0 && !missileInFlight

    /** Interceptors Robert carries in a round: 0, then 2 in round 4, 4 in round 5. */
    fun antiMissilesForRound(round: Int): Int = when (round.coerceIn(1, Progression.ROUNDS_PER_MATCH)) {
        4 -> 2
        5 -> 4
        else -> 0
    }

    /**
     * Missile powers Robert carries in a round: one per game in round four
     * and two per game in round five.
     */
    fun enemyMissilePowersForRound(round: Int): Int =
        when (round.coerceIn(1, Progression.ROUNDS_PER_MATCH)) {
            4 -> 1
            5 -> 2
            else -> 0
        }

    /**
     * True when Robert fires an interceptor against a player missile. All
     * end-game conditions must hold at once: round four or later, an
     * interceptor still unused, a missile actually in the air, and his
     * health below [ANTI_MISSILE_MAX_HP]. A healthy Robert never wastes
     * one, and each interceptor can only ever be used once.
     */
    fun willIntercept(
        round: Int,
        robertHp: Int,
        antiMissilesLeft: Int,
        missileInFlight: Boolean
    ): Boolean =
        missileInFlight &&
            round.coerceIn(1, Progression.ROUNDS_PER_MATCH) >= FIRST_MISSILE_ROUND &&
            antiMissilesLeft > 0 &&
            robertHp in 1 until ANTI_MISSILE_MAX_HP

    /**
     * True when Robert spends his single missile power. It is a desperation
     * weapon only: round four or later, still unused, the fight properly
     * underway, his own health badly down and the player healthier than he
     * is. A Robert who is winning never launches it.
     */
    fun shouldEnemyLaunch(
        round: Int,
        missilePowers: Int,
        robertHp: Int,
        robertMaxHp: Int,
        playerHp: Int,
        playerMaxHp: Int,
        robertShots: Int,
        missileInFlight: Boolean
    ): Boolean {
        if (missileInFlight || missilePowers <= 0) return false
        if (round.coerceIn(1, Progression.ROUNDS_PER_MATCH) < FIRST_MISSILE_ROUND) return false
        if (robertMaxHp <= 0 || playerMaxHp <= 0 || robertHp <= 0 || playerHp <= 0) return false
        if (robertShots < ENEMY_LAUNCH_MIN_SHOTS) return false
        val robertFraction = robertHp.toFloat() / robertMaxHp.toFloat()
        val playerFraction = playerHp.toFloat() / playerMaxHp.toFloat()
        return robertFraction <= ENEMY_LAUNCH_MAX_HP_FRACTION && playerFraction > robertFraction
    }
}
