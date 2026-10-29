package com.blindtechnexus.openfire.model

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Robert's health, survival items, weapons, bombs and fleeing behaviour scale
 * with the current round (and, in round one, with the current level). His
 * maximum health also depends on the current level.
 *
 *  - Round 1, levels 1-2: 2 medkits and 1 cover (no glue walls).
 *    Round 1, levels 3-5: 2 medkits and 2 covers (no glue walls).
 *    Weapons: assault rifle and shotgun; the sniper rifle is present but
 *    locked until the glue-wall / low-health condition is met.
 *  - Round 2 (all levels): 3 medkits, 1 glue wall and 2 covers. The knife,
 *    the sword and the MP40 submachine gun join his weapons.
 *  - Round 3 (all levels): 3 medkits, 2 glue walls and 4 covers. The pistol
 *    and the AWM join his weapons, the sniper rifle unlocks, and he receives
 *    one bomb plus one anti-bomb explosive per game (1 in R3, 3 in R4, unlimited in R5).
 *  - Round 4 (all levels): 5 medkits, 3 glue walls and 5 covers. Every weapon
 *    is available, including the machine gun, missile powers, anti-missiles,
 *    bombs (3) and anti-bombs (3).
 *  - Round 5 (all levels): 5 medkits, 4 glue walls and 7 covers. Strategic round:
 *    equal power, bombs and anti-bombs unlimited — relies on strategy, not raw stats.
 *
 * The missile and the bombs never enter his random weapon rotation: they are
 * fired only through [MissileRules.shouldEnemyLaunch] and
 * [BombRules.shouldEnemyThrow] as desperation/strategic weapons.
 */
data class RobertProfile(
    val round: Int,
    val maxHp: Int,
    val medikits: Int,
    val glueWalls: Int,
    val covers: Int,
    val weapons: List<Weapon>,
    /** True while the sniper rifle may only be used under the special rule. */
    val sniperLocked: Boolean,
    /** Consecutive hits that make Robert run far away. */
    val fleeAfterHits: Int,
    /** Missile powers he may spend this game (round four and later). */
    val missilePowers: Int = 0,
    /** Anti-missile interceptors he carries this game (see [MissileRules]). */
    val antiMissiles: Int = 0,
    /** Bombs he may throw this game (round three and later). */
    val bombs: Int = 0,
    /** Anti-bomb explosives he may deploy this game (round three and later). */
    val antiBombs: Int = 0,
    /** Grenades he may throw this game (twice in round three). */
    val grenades: Int = 0,
    /** Atom bombs he may throw this game (three times per game from round four). */
    val atomBombs: Int = 0,
    /** Timer bombs he may throw this game (three times per game from round four). */
    val timerBombs: Int = 0
)

object RobertRules {
    /**
     * Legacy absolute defence trigger. The real trigger is round aware: see
     * [defenceThresholdHp]. Robert starts far earlier now, which is what makes
     * him actually use the medkits, covers and glue walls he carries.
     */
    const val DEFENCE_THRESHOLD_HP = 60

    /** Robert's sniper is usable under this health while it is locked. */
    const val LOCKED_SNIPER_MAX_HP = 100

    /** Round one: one gloo wall of his own, only while the player has one. */
    const val ENEMY_GLUE_WALLS_ROUND_ONE = 1

    /** From round two every game gives him two gloo walls. */
    const val ENEMY_GLUE_WALLS_FROM_ROUND_TWO = 2

    /** Chance that a relocation is a deception trip into the miss band. */
    const val DECEPTION_CHANCE = 0.22f

    /** Fast retreat: metres gained per flee tick, and the tick length. */
    const val FLEE_STEP_M = 6f
    const val FLEE_TICK_MS = 160L

    /**
     * How much faster Robert sprints than the player can ever run.
     *
     * The player's fastest three-finger level is defined to match
     * [enemyFastSpeedMps] exactly, so every retreat of the enemy covers
     * ground at twice that speed: he breaks contact, crosses the zone and
     * comes back from an unexpected angle before the player can follow.
     */
    const val FLEE_SPEED_MULTIPLIER = 2.0f

    /** How often Robert strafes sideways instead of walking straight. */
    const val STRAFE_CHANCE = 0.20f

    /** Metres covered by one sideways strafe step. */
    const val STRAFE_STEP_M = 6f

    /**
     * Flee ticks one sprint always lasts. At [FLEE_TICK_MS] per tick and
     * [enemyFleeSpeedMps] metres per second this carries Robert right across
     * the 100 m zone in every round: once he decides to run he is really
     * gone, and the player has to hunt him down again.
     */
    const val SPRINT_TICKS = 8

    /** Ticks Robert spends turning back after he detected a wall. */
    const val TURN_BACK_TICKS = 3

    /** Metres covered per turn-back tick. */
    const val TURN_BACK_STEP_M = 8f

    /** Player shots inside one burst window that count as continuous fire. */
    const val COUNTER_FIRE_BURST_SHOTS = 3

    // ------------------------------------------------------------------
    // The player's real firing rhythm, mirrored back at him
    // ------------------------------------------------------------------

    /** Cadence Robert uses until the player has fired at least twice. */
    const val DEFAULT_PLAYER_INTERVAL_MS = 1_500L

    /** Fastest cadence that is ever mirrored (a 0.25 s burst is answered alike). */
    const val MIN_MIRRORED_INTERVAL_MS = 250L

    /** Slower gaps than this are treated as separate taps, not as a rhythm. */
    const val MAX_MIRRORED_INTERVAL_MS = 3_000L

    /** Two player shots closer than this are still the same rhythm sample. */
    const val MIN_PLAYER_INTERVAL_MS = 120L

    /**
     * Robert never trades slower than this while he is coverless and the
     * player is standing behind a cover: even a slow player is then answered
     * once a second, and a faster player is answered faster still.
     */
    const val COVERLESS_PRESS_INTERVAL_MS = 1_000L

    /**
     * The cadence Robert answers with, which is simply the player's own.
     *
     *  - both fighters coverless, only a cover, or only a gloo wall: he fires
     *    at exactly the player's rate (0.5 s, 0.25 s, whatever was heard);
     *  - a coverless Robert against a covered player: at most one second
     *    between shots, faster while the player fires faster.
     *
     * A glue wall of the player is handled by the sniper duel and uses the
     * same mirrored rate. The result is clamped so an accidental double tap
     * can never turn the enemy into an unplayable machine gun.
     */
    fun mirroredFireInterval(
        playerIntervalMs: Long,
        enemyHasCover: Boolean,
        playerHasCover: Boolean
    ): Long {
        val mirrored = playerIntervalMs.coerceIn(MIN_MIRRORED_INTERVAL_MS, MAX_MIRRORED_INTERVAL_MS)
        return if (!enemyHasCover && playerHasCover) {
            minOf(mirrored, COVERLESS_PRESS_INTERVAL_MS)
        } else {
            mirrored
        }
    }

    /**
     * True while Robert must answer at the player's own cadence instead of
     * pausing, repositioning or running after one or two shots.
     */
    fun mirrorsPlayerCadence(
        playerFiringNow: Boolean,
        enemyHasCover: Boolean,
        playerHasCover: Boolean
    ): Boolean = playerFiringNow || (!enemyHasCover && playerHasCover)

    /**
     * The moment Robert answers a hit with a shot of his own: one hit and he
     * fires straight back, so trading single shots with him is never free.
     */
    const val RETALIATE_AFTER_HITS = 1

    /**
     * Player hits in a row (or unanswered hits) that make Robert sprint far
     * away instead of trading shots: the player fired twice, or is simply
     * out-firing him, so he breaks contact at twice the player's speed.
     */
    const val FLEE_AFTER_PLAYER_HITS = 2

    /**
     * How many more hits the player may have landed than Robert before he
     * decides the exchange is lost and runs for the far edge of the zone.
     */
    const val OUTGUNNED_HIT_GAP = 1

    /** Delay before the answering shot of a retaliation really leaves. */
    const val RETALIATION_DELAY_MS = 220L

    /**
     * Shortest gap between two answering shots. The first hit is always
     * answered at once; a held submachine gun that lands hit after hit then
     * gets one answering shot per window instead of an endless exchange,
     * which keeps the retaliation human and the fight readable.
     */
    const val RETALIATION_COOLDOWN_MS = 700L

    /** Window in which player shots are counted as one continuous burst. */
    const val PLAYER_BURST_WINDOW_MS = 2_500L

    /**
     * How long after his last shot the player still counts as firing. The
     * enemy keeps answering at the mirrored cadence inside this short window,
     * so an exchange stays continuous without turning him into a machine gun
     * against an idle opponent.
     */
    const val MIRROR_EXCHANGE_WINDOW_MS = 1_200L

    /** Health fraction below which Robert panics and deploys defences instantly. */
    const val PANIC_FRACTION = 0.65f
    const val PANIC_FRACTION_SEVERE = 0.40f

    /** When health drops below 150 or 200 HP remaining, panic threshold (legacy spec). */
    const val PANIC_HP_150 = 150
    const val PANIC_HP_200 = 200

    /**
     * Legacy random firing intervals (2 s, 2.5 s, 3 s). The live battle now
     * uses the faster human rhythm below: [NORMAL_FIRE_MIN_MS] to
     * [NORMAL_FIRE_MAX_MS] between shots, or exactly [CONTINUOUS_FIRE_MS]
     * while Robert is pressing a defenceless player.
     */
    val NORMAL_INTERVALS_MS = listOf(1_500L, 2_000L)

    /** Fastest normal shot: 1.5 seconds after the previous one. */
    const val NORMAL_FIRE_MIN_MS = 1_500L

    /** Slowest normal shot: 2 seconds after the previous one. */
    const val NORMAL_FIRE_MAX_MS = 2_000L

    /** Continuous pressure fire uses the fastest requested cadence. */
    const val CONTINUOUS_FIRE_MS = 1_500L

    /** Maximum number of shots Robert fires in a row before he pauses. */
    const val MAX_SUCCESSIVE_SHOTS = 2

    /** Health fraction of the player that counts as "extremely weak". */
    const val EXTREMELY_WEAK_FRACTION = 0.18f

    /** Chance that Robert runs instead of firing, per decision tick. */
    const val REPOSITION_CHANCE = 0.40f

    /** Chance that Robert runs right after a finished burst. */
    const val REPOSITION_AFTER_BURST_CHANCE = 0.75f

    /** Minimum distance (metres) he keeps while repositioning. */
    const val REPOSITION_STEP_M = 8f

    /**
     * The three damage ranges he shoots from, expressed as proximity percent:
     * 20-40 percent, 40-70 percent and 70-100 percent. In metres these are the
     * [FireRange] bands FAR (20 percent mid), MID (55 percent mid) and
     * STRONG / CLOSE (80 / 95 percent mid).
     */
    val FIRE_PROXIMITY_RANGES = listOf(20f..40f, 40f..70f, 70f..100f)

    /** Consecutive hits needed before Robert runs far away. */
    fun fleeAfterHits(round: Int): Int = when (round) {
        3 -> 3
        4 -> 4
        5 -> 5
        else -> 2
    }

    /**
     * Round-by-round movement multiplier. Round one is 20% faster than the
     * old movement, and every following round adds another 20% of base speed.
     */
    fun movementSpeedMultiplier(round: Int): Float {
        val safeRound = round.coerceIn(1, Progression.ROUNDS_PER_MATCH)
        return 1.2f + (safeRound - 1) * 0.2f
    }

    /**
     * Robert's fast running speed in metres per second. The player's fast
     * three-finger speed level is defined to match exactly this value, so a
     * sprinting player and a repositioning enemy cover ground at the same
     * rate.
     */
    fun enemyFastSpeedMps(round: Int): Float =
        FLEE_STEP_M * movementSpeedMultiplier(round) / (FLEE_TICK_MS / 1000f)

    /**
     * Robert's sprinting speed in metres per second: exactly
     * [FLEE_SPEED_MULTIPLIER] times the fastest speed the player can run
     * (the player's fast level, which is [enemyFastSpeedMps]). He uses it
     * for every retreat, so losing him once he breaks contact is genuinely
     * hard.
     */
    fun enemyFleeSpeedMps(round: Int): Float =
        enemyFastSpeedMps(round) * FLEE_SPEED_MULTIPLIER

    /** Metres Robert covers in one flee tick while he is sprinting away. */
    fun fleeStep(round: Int): Float =
        FLEE_STEP_M * movementSpeedMultiplier(round) * FLEE_SPEED_MULTIPLIER

    /**
     * The health Robert must fall under before he starts spending his
     * defences. Rounds one and two trigger below 60% of maximum health or
     * below 100 health (whichever is higher); round three triggers below 200;
     * rounds four and five trigger below 300.
     */
    fun defenceThresholdHp(round: Int, maxHp: Int): Int {
        val safeRound = round.coerceIn(1, Progression.ROUNDS_PER_MATCH)
        val safeMax = maxHp.coerceAtLeast(1)
        return when (safeRound) {
            1, 2 -> maxOf(100, (safeMax * 0.60f).roundToInt())
            3 -> 200
            else -> 300
        }
    }

    /** Every standard battle weapon (bombs and the missile are separate). */
    val ALL_STANDARD_WEAPONS: List<Weapon> =
        Weapon.entries.filter { !it.missileLauncher && !it.bomb }

    /**
     * Robert's weapon rotation for a round. He receives the MP40 in round two,
     * the AWM in round three and the machine gun in round four; his sniper
     * rifle stays locked during the first two rounds, and his bombs arrive
     * with round three (see [BombRules.enemyBombsForRound]).
     */
    fun weaponsForRound(round: Int): List<Weapon> {
        val safeRound = round.coerceIn(1, Progression.ROUNDS_PER_MATCH)
        return when (safeRound) {
            1 -> listOf(Weapon.ASSAULT_GUN, Weapon.SHORT_GUN, Weapon.SNIPER)
            // Round two adds the knife, the sword and his own MP40.
            2 -> listOf(
                Weapon.ASSAULT_GUN,
                Weapon.SHORT_GUN,
                Weapon.SNIPER,
                Weapon.KNIFE,
                Weapon.SWORD,
                Weapon.MP40
            )
            // Round three adds the pistol and his AWM.
            3 -> listOf(
                Weapon.SHORT_GUN,
                Weapon.ASSAULT_GUN,
                Weapon.PISTOL,
                Weapon.SNIPER,
                Weapon.KNIFE,
                Weapon.SWORD,
                Weapon.MP40,
                Weapon.AWM
            )
            // Round four adds the machine gun: from here he owns them all.
            else -> ALL_STANDARD_WEAPONS
        }
    }

    fun profile(
        round: Int,
        level: Int = 1,
        /**
         * True when the player owns the sniper rifle. Round one mirrors it:
         * buying a sniper rifle hands Robert one too, unlocked, so he never
         * fights a scoped player with a locked rifle.
         */
        playerOwnsSniper: Boolean = false,
        /** Gloo walls the player carries; round one mirrors one of them. */
        playerGlueWalls: Int = 0
    ): RobertProfile {
        val safeRound = round.coerceIn(1, Progression.ROUNDS_PER_MATCH)
        val safeLevel = level.coerceIn(1, Progression.LEVELS_PER_ROUND)
        val maxHp = Progression.hpFor(safeRound, safeLevel)
        val weapons = weaponsForRound(safeRound)
        return when (safeRound) {
            1 -> {
                // Round one: the sniper rifle is locked unless the player
                // bought one - then Robert carries it unlocked as well - and
                // he may answer the player's gloo wall with one of his own.
                val roundOneWalls = if (playerGlueWalls > 0) ENEMY_GLUE_WALLS_ROUND_ONE else 0
                val sniperLocked = !playerOwnsSniper
                if (safeLevel <= 2) {
                    // Levels 1 and 2: two medkits and one cover.
                    RobertProfile(1, maxHp, 2, roundOneWalls, 1, weapons, sniperLocked, fleeAfterHits(1))
                } else {
                    // Levels 3, 4 and 5: two medkits and two covers.
                    RobertProfile(1, maxHp, 2, roundOneWalls, 2, weapons, sniperLocked, fleeAfterHits(1))
                }
            }
            // From round two on he always carries two gloo walls per game.
            2 -> RobertProfile(2, maxHp, 3, ENEMY_GLUE_WALLS_FROM_ROUND_TWO, 2, weapons, true, fleeAfterHits(2))
            3 -> RobertProfile(
                3, maxHp, 3, 2, 4, weapons, false, fleeAfterHits(3),
                bombs = BombRules.enemyBombsForRound(3),
                antiBombs = BombRules.antiBombsForRound(3),
                grenades = BombRules.enemyBombUsesForRound(3, BombKind.GRENADE),
                atomBombs = BombRules.enemyBombUsesForRound(3, BombKind.ATOM),
                timerBombs = BombRules.enemyBombUsesForRound(3, BombKind.TIMER)
            )
            4 -> RobertProfile(
                4, maxHp, 5, 3, 5, weapons, false, fleeAfterHits(4),
                missilePowers = MissileRules.enemyMissilePowersForRound(4),
                antiMissiles = MissileRules.antiMissilesForRound(4),
                bombs = BombRules.enemyBombsForRound(4),
                antiBombs = BombRules.antiBombsForRound(4),
                grenades = BombRules.enemyBombUsesForRound(4, BombKind.GRENADE),
                atomBombs = BombRules.enemyBombUsesForRound(4, BombKind.ATOM),
                timerBombs = BombRules.enemyBombUsesForRound(4, BombKind.TIMER)
            )
            else -> RobertProfile(
                5, maxHp, 5, 4, 7, weapons, false, fleeAfterHits(5),
                missilePowers = MissileRules.enemyMissilePowersForRound(5),
                antiMissiles = MissileRules.antiMissilesForRound(5),
                bombs = BombRules.enemyBombsForRound(5),
                antiBombs = BombRules.antiBombsForRound(5),
                grenades = BombRules.enemyBombUsesForRound(5, BombKind.GRENADE),
                atomBombs = BombRules.enemyBombUsesForRound(5, BombKind.ATOM),
                timerBombs = BombRules.enemyBombUsesForRound(5, BombKind.TIMER)
            )
        }
    }

    /**
     * Robert reads his damage from the same table as the player. His weapons
     * are the standard ones, so both sides always deduct the exact documented
     * percentage of maximum health.
     */
    fun powerFor(weapon: Weapon, proximityPercent: Float): Int =
        DamageTable.percentAt(weapon, proximityPercent)

    /** Actual Robert damage: the single shared damage table. */
    fun weaponDamage(weapon: Weapon, proximityPercent: Float, victimMaxHp: Int): Int =
        DamageTable.damage(weapon, proximityPercent, victimMaxHp)

    // ------------------------------------------------------------------
    // Where Robert prefers to fire from
    // ------------------------------------------------------------------

    /** The two bands Robert really fights from: 40%-70% or 90%-100%. */
    fun preferredRange(random: Random): FireRange =
        if (random.nextBoolean()) FireRange.MID else FireRange.CLOSE

    /** True when the next relocation should be a confusing 0%-40% trip. */
    fun shouldStartDeception(random: Random): Boolean =
        random.nextFloat() < DECEPTION_CHANCE

    /** How many useless shots he fires from the 0%-40% band: one or two. */
    fun deceptionShots(random: Random): Int = 1 + random.nextInt(2)

    /**
     * The band Robert holds after applying forced point-blank situations. A
     * raised glue wall only pulls him in while he really owns a usable sniper
     * rifle to break it; without one he keeps his distance instead.
     */
    fun fireRangeFor(
        stored: FireRange,
        playerWeaponDamaged: Boolean,
        playerGlueWallActive: Boolean,
        robertHasSniper: Boolean = true
    ): FireRange = when {
        playerWeaponDamaged -> FireRange.CLOSE
        playerGlueWallActive && robertHasSniper -> FireRange.CLOSE
        playerGlueWallActive -> FireRange.FAR
        else -> stored
    }

    /**
     * True when the player's glue wall is up and Robert has no way to break
     * it. He then refuses to approach: he runs away and keeps away until the
     * glue wall is deactivated or broken, and the player has to hunt him down.
     */
    fun shouldAvoidGlueWall(playerGlueWallActive: Boolean, robertHasSniper: Boolean): Boolean =
        playerGlueWallActive && !robertHasSniper

    /**
     * True when Robert owns a sniper rifle he can actually use right now:
     * it must be in the allowed list for this round and situation, and it must
     * not be damaged.
     */
    fun hasUsableSniper(allowedWeapons: List<Weapon>, sniperDamaged: Boolean): Boolean =
        allowedWeapons.contains(Weapon.SNIPER) && !sniperDamaged

    /**
     * The sniper rifle is locked in rounds one and two, and round one
     * unlocks it completely as soon as the player owns one himself. While it
     * is still locked it is used anyway the moment the player raises a gloo
     * wall: a gloo wall is always detected and always answered with the
     * sniper rifle. [robertHp] is kept for the callers that used to gate the
     * locked rifle on health.
     */
    fun sniperAllowed(profile: RobertProfile, playerGlueWallActive: Boolean, robertHp: Int): Boolean =
        // A raised gloo wall is always detected and always answered with the
        // sniper rifle, even in the rounds where it is otherwise locked.
        !profile.sniperLocked || playerGlueWallActive

    /** Every weapon Robert may use right now. */
    fun allowedWeapons(
        profile: RobertProfile,
        playerGlueWallActive: Boolean,
        robertHp: Int
    ): List<Weapon> = profile.weapons.filter { weapon ->
        weapon != Weapon.SNIPER || sniperAllowed(profile, playerGlueWallActive, robertHp)
    }

    /**
     * True when Robert should spend a defence. [thresholdHp] is the round
     * aware trigger from [defenceThresholdHp]; the default keeps the legacy
     * absolute rule for callers that do not know the round yet.
     */
    fun shouldUseDefence(
        robertHp: Int,
        defenceArmed: Boolean,
        thresholdHp: Int = DEFENCE_THRESHOLD_HP
    ): Boolean = defenceArmed && robertHp in 1 until thresholdHp

    /** True when Robert has taken enough hits in a row to run far away. */
    fun shouldFlee(consecutiveHits: Int, fleeAfterHits: Int): Boolean =
        fleeAfterHits > 0 && consecutiveHits >= fleeAfterHits

    /**
     * True when Robert must sprint far away because the player out-fires him:
     * two hits landed in a row, or clearly more hits landed than he managed
     * to answer inside the same exchange. This is the "the player fired
     * twice, so I am gone" decision.
     */
    fun shouldFleeFromPlayerFire(
        hitsTakenInRow: Int,
        playerHits: Int,
        robertHits: Int
    ): Boolean {
        if (hitsTakenInRow >= FLEE_AFTER_PLAYER_HITS) return true
        return playerHits - robertHits > OUTGUNNED_HIT_GAP
    }

    /**
     * True when Robert must answer a hit with a shot of his own instead of
     * only running: a single hit is always answered at once, so the enemy
     * never looks passive while he is being shot at.
     */
    fun shouldRetaliate(hitsTakenInRow: Int): Boolean =
        hitsTakenInRow in RETALIATE_AFTER_HITS until FLEE_AFTER_PLAYER_HITS

    /**
     * Hit-exchange retreat rule. Robert compares the hits he has taken with
     * the hits he has landed inside the current exchange: as soon as the
     * player is ahead (for example Robert took two hits and landed only one)
     * he runs away immediately. While he is landing more hits he stays and
     * fights, and the counters are reset when a retreat starts so the next
     * exchange is judged on its own.
     */
    fun shouldFleeFight(robertHitsTaken: Int, robertHitsLanded: Int): Boolean =
        robertHitsTaken >= 2 && robertHitsTaken > robertHitsLanded

    /**
     * True when a one-sided exchange must make Robert run as fast as
     * possible: he is at a disadvantage, has sustained two or three hits in
     * a row, and has only managed to answer with one or two shots while
     * inside combat range (proximity at least 40%, so the hits hurt him).
     */
    fun shouldFleeFromExchange(
        hitsTakenInRow: Int,
        shotsFiredInExchange: Int,
        proximityPercent: Float,
        atDisadvantage: Boolean = true
    ): Boolean =
        atDisadvantage &&
            hitsTakenInRow >= 2 &&
            shotsFiredInExchange <= 2 &&
            Combat.zoneFor(proximityPercent) != HitZone.MISS

    /**
     * True when Robert must retreat because the player is firing continuously
     * and really taking his health down. While the player's own health is
     * dropping instead, Robert never runs: he keeps firing back.
     */
    fun shouldRetreatFromSustainedFire(
        playerBurstShots: Int,
        robertHealthDropping: Boolean,
        playerHealthDropping: Boolean
    ): Boolean =
        isContinuousPlayerFire(playerBurstShots) &&
            robertHealthDropping &&
            !playerHealthDropping

    fun shouldRetreatWhenPlayerFiresBack(playerBurstShots: Int): Boolean =
        isContinuousPlayerFire(playerBurstShots)

    /** True when health dropped below panic thresholds (150 or 200 HP or fraction). */
    fun shouldPanic(robertHp: Int, robertMaxHp: Int, previousHp: Int = robertMaxHp): Boolean {
        if (robertMaxHp <= 0) return false
        val fraction = robertHp.toFloat() / robertMaxHp.toFloat()
        // Even 1% drop below fraction thresholds triggers panic
        if (fraction < PANIC_FRACTION || fraction < PANIC_FRACTION_SEVERE) return true
        if (robertHp < PANIC_HP_200 || robertHp < PANIC_HP_150) return true
        // Any drop below previous (even 1%) when already below 95% triggers panic
        if (previousHp > robertHp && fraction < 0.95f) return true
        return false
    }

    /** True when the player is vulnerable (damaged, trapped, locked, hindered). */
    fun isPlayerVulnerable(
        playerWeaponDamaged: Boolean,
        playerHp: Int,
        playerMaxHp: Int,
        playerGlueWallActive: Boolean,
        playerCoverActive: Boolean,
        consecutiveHeavyHits: Int = 0
    ): Boolean {
        if (playerWeaponDamaged) return true
        if (playerMaxHp <= 0) return false
        val fraction = playerHp.toFloat() / playerMaxHp.toFloat()
        // Low health = vulnerable
        if (fraction < 0.35f) return true
        // Trapped / hindered: heavy hits streak or no barriers while close
        if (consecutiveHeavyHits >= 2) return true
        // Locked: weapon damaged already handled, but also when both barriers down and health mid?
        if (!playerGlueWallActive && !playerCoverActive && fraction < 0.6f) return true
        return false
    }

    /**
     * Smart weapon selection based on health bands as requested:
     * 40–70% health: balanced mid-range weapons
     * 90–100% health: high power close-range + precision
     * 20–40% health: long-range or desperate high-damage
     * Feels unsafe / significant damage: retreat signal included.
     */
    fun smartWeaponForSituation(
        allowed: List<Weapon>,
        robertHp: Int,
        robertMaxHp: Int,
        proximityPercent: Float,
        playerGlueWallActive: Boolean,
        hasSniper: Boolean
    ): Weapon? {
        if (allowed.isEmpty()) return null
        // If glue wall up and has sniper, sniper is best regardless of health
        if (playerGlueWallActive && hasSniper && allowed.contains(Weapon.SNIPER)) return Weapon.SNIPER
        val fraction = if (robertMaxHp > 0) robertHp.toFloat() / robertMaxHp.toFloat() else 1f
        return when {
            fraction >= 0.9f -> {
                // 90-100% health: best close-range power — pick highest damage at heavy zone
                allowed.maxByOrNull { DamageTable.percentOf(it, HitZone.HEAVY) } ?: allowed.random()
            }
            fraction >= 0.7f -> {
                // 70-90% moderate: strong zone optimized
                allowed.maxByOrNull { DamageTable.percentOf(it, HitZone.STRONG) } ?: allowed.random()
            }
            fraction >= 0.4f -> {
                // 40-70% health: light zone / balanced
                allowed.maxByOrNull { DamageTable.percentOf(it, HitZone.LIGHT) } ?: allowed.random()
            }
            fraction >= 0.2f -> {
                // 20-40% health: desperate — long-range AWM if available, otherwise highest heavy
                when {
                    allowed.contains(Weapon.AWM) -> Weapon.AWM
                    allowed.contains(Weapon.SNIPER) -> Weapon.SNIPER
                    else -> allowed.maxByOrNull { DamageTable.percentOf(it, HitZone.HEAVY) } ?: allowed.first()
                }
            }
            else -> {
                // Below 20%: panic — any barrier breaker or max damage
                if (allowed.contains(Weapon.AWM)) Weapon.AWM else allowed.maxByOrNull { DamageTable.percentOf(it, HitZone.HEAVY) } ?: allowed.first()
            }
        }
    }

    /** True when Robert feels unsafe (low health or heavy damage streak) and should retreat. */
    fun shouldRetreatUnsafe(
        robertHp: Int,
        robertMaxHp: Int,
        consecutiveHits: Int,
        proximityPercent: Float
    ): Boolean {
        if (robertMaxHp <= 0) return false
        val fraction = robertHp.toFloat() / robertMaxHp.toFloat()
        if (fraction < 0.3f) return true
        if (consecutiveHits >= 2 && Combat.zoneFor(proximityPercent) == HitZone.HEAVY) return true
        if (fraction < 0.5f && proximityPercent >= 70f) return true
        return false
    }

    /** True when the player is firing continuously (a real burst). */
    fun isContinuousPlayerFire(playerBurstShots: Int): Boolean =
        playerBurstShots >= COUNTER_FIRE_BURST_SHOTS

    /**
     * True while Robert must press the player instead of repositioning.
     *
     * The situations are deliberately narrow: the player's weapon is damaged,
     * the player is extremely weak, or Robert has an active defence (a cover
     * or a glue wall) while the player has none. In that last case he is
     * protected and can safely fire once every second from close range.
     */
    fun shouldPressFromDefence(
        enemyHasDefence: Boolean,
        playerHasDefence: Boolean,
        playerWeaponDamaged: Boolean,
        playerHpFraction: Float
    ): Boolean =
        (enemyHasDefence && !playerHasDefence) ||
            playerWeaponDamaged ||
            playerHpFraction <= EXTREMELY_WEAK_FRACTION

    /**
     * The live firing rhythm uses a deliberately irregular two-beat cadence:
     * ordinary shots alternate unpredictably between 1.5 and 2 seconds.
     */
    fun normalTacticalInterval(random: Random): Long =
        NORMAL_INTERVALS_MS[random.nextInt(NORMAL_INTERVALS_MS.size)]

    fun tacticalFireInterval(
        enemyHasDefence: Boolean,
        playerHasDefence: Boolean,
        playerWeaponDamaged: Boolean,
        playerHpFraction: Float,
        random: Random,
        enemyHasAdvantage: Boolean = true
    ): Long {
        val mayPress = enemyHasAdvantage && shouldPressFromDefence(
            enemyHasDefence = enemyHasDefence,
            playerHasDefence = playerHasDefence,
            playerWeaponDamaged = playerWeaponDamaged,
            playerHpFraction = playerHpFraction
        )
        return if (mayPress) CONTINUOUS_FIRE_MS else normalTacticalInterval(random)
    }

    /**
     * True when Robert is allowed to fire continuously.
     *
     * Continuous fire is a finishing move, never his default: it happens only
     * when the player's weapon is damaged (no working gun) or the player is
     * extremely weak with no other option left. In every other situation he
     * fires single or double shots at 2 s, 2.5 s or 3 s and repositions.
     */
    fun shouldFireContinuously(
        playerBurstShots: Int,
        playerHealthDropping: Boolean,
        situation: CombatSituation,
        playerVulnerable: Boolean = true,
        playerWeaponDamaged: Boolean = false,
        playerHpFraction: Float = 1f
    ): Boolean {
        // The burst counters and the health trend are kept for the exchange
        // bookkeeping, but they no longer force a machine-gun answer.
        val pinned = playerWeaponDamaged || playerHpFraction <= EXTREMELY_WEAK_FRACTION
        return pinned
    }

    /** Legacy overload for tests. */
    fun shouldFireContinuously(
        playerBurstShots: Int,
        playerHealthDropping: Boolean,
        situation: CombatSituation
    ): Boolean = shouldFireContinuously(
        playerBurstShots,
        playerHealthDropping,
        situation,
        playerVulnerable = true
    )

    /** True when the player is damaged or extremely weak: only then does he press. */
    fun shouldPressPlayer(playerWeaponDamaged: Boolean, playerHpFraction: Float): Boolean =
        playerWeaponDamaged || playerHpFraction <= EXTREMELY_WEAK_FRACTION

    /** Random normal fire interval (2 s, 2.5 s or 3 s). */
    fun normalFireInterval(random: Random): Long = NORMAL_INTERVALS_MS.random(random)

    /** How many shots he fires before pausing: one or two. */
    fun burstLimit(random: Random): Int = 1 + random.nextInt(MAX_SUCCESSIVE_SHOTS)

    /** True when the finished burst must be followed by a pause. */
    fun burstFinished(successiveShots: Int, limit: Int): Boolean =
        successiveShots >= limit.coerceIn(1, MAX_SUCCESSIVE_SHOTS)

    /**
     * True when Robert spends this tick running instead of firing. He flees
     * just as readily as he shoots, unless the player is pinned down.
     */
    fun shouldRepositionInsteadOfFiring(
        situation: CombatSituation,
        playerWeaponDamaged: Boolean,
        playerHpFraction: Float,
        random: Random
    ): Boolean {
        if (shouldPressPlayer(playerWeaponDamaged, playerHpFraction)) return false
        val chance = when (situation) {
            CombatSituation.DISADVANTAGE -> REPOSITION_CHANCE + 0.15f
            CombatSituation.EVEN -> REPOSITION_CHANCE
            CombatSituation.ADVANTAGE -> REPOSITION_CHANCE - 0.10f
            CombatSituation.PRESSURE -> 0f
        }
        return random.nextFloat() < chance
    }

    /** True when a just finished burst turns into a run instead of the next shot. */
    fun shouldRunAfterBurst(random: Random): Boolean =
        random.nextFloat() < REPOSITION_AFTER_BURST_CHANCE

    /**
     * The proximity range (20-40, 40-70 or 70-100 percent) Robert fires from
     * for this situation. A pinned player is always attacked from the
     * 70-100 percent band; otherwise the band follows who is winning.
     */
    fun proximityRangeFor(
        situation: CombatSituation,
        playerWeaponDamaged: Boolean,
        playerHpFraction: Float,
        random: Random
    ): ClosedFloatingPointRange<Float> {
        if (shouldPressPlayer(playerWeaponDamaged, playerHpFraction)) return FIRE_PROXIMITY_RANGES[2]
        return when (situation) {
            CombatSituation.ADVANTAGE -> FIRE_PROXIMITY_RANGES[2]
            CombatSituation.EVEN -> FIRE_PROXIMITY_RANGES[1]
            CombatSituation.DISADVANTAGE -> FIRE_PROXIMITY_RANGES[0]
            CombatSituation.PRESSURE -> FIRE_PROXIMITY_RANGES[2]
        }
    }

    /** True from round five: every gun fires without ever running dry. */
    fun infiniteAmmo(round: Int): Boolean =
        round.coerceIn(1, Progression.ROUNDS_PER_MATCH) >= 5

    /** True from round five: bombs are unlimited as well. */
    fun infiniteBombs(round: Int): Boolean =
        round.coerceIn(1, Progression.ROUNDS_PER_MATCH) >= 5

    /**
     * True once Robert may end the game with his missile in this round.
     * Round four holds one missile power, round five holds two.
     */
    fun missilePowersForRound(round: Int): Int =
        MissileRules.enemyMissilePowersForRound(round)

    /** True when this tick should be a sideways strafe instead of a straight walk. */
    fun shouldStrafe(random: Random): Boolean = random.nextFloat() < STRAFE_CHANCE

    /** Which way Robert strafes: +1 to his right, -1 to his left. */
    fun strafeDirection(random: Random): Int = if (random.nextBoolean()) 1 else -1

    /** Legacy defence order: medkit first, then cover, then glue wall. */
    fun nextDefence(medikits: Int, covers: Int, glueWalls: Int): DefenceItem? = when {
        medikits > 0 -> DefenceItem.MEDKIT
        covers > 0 -> DefenceItem.COVER
        glueWalls > 0 -> DefenceItem.GLUE_WALL
        else -> null
    }

    /**
     * Situational defence choice. Robert now really uses everything he
     * carries, picked for the moment instead of always in the same order:
     *
     *  - under close fire (70% proximity or more) he raises a barrier first:
     *    a cover when he has one, otherwise a glue wall;
     *  - below half health he heals with a medkit;
     *  - otherwise he falls back to cover, glue wall and medkit in turn.
     *  Panic mode (health just dropped) uses immediate best defence.
     */
    fun chooseDefence(
        medikits: Int,
        covers: Int,
        glueWalls: Int,
        robertHp: Int,
        robertMaxHp: Int,
        proximityPercent: Float
    ): DefenceItem? {
        val fraction = if (robertMaxHp > 0) robertHp.toFloat() / robertMaxHp.toFloat() else 0f
        if (proximityPercent >= Combat.STRONG_ZONE_MAX) {
            if (covers > 0) return DefenceItem.COVER
            if (glueWalls > 0) return DefenceItem.GLUE_WALL
        }
        if (fraction <= 0.5f && medikits > 0) return DefenceItem.MEDKIT
        if (covers > 0) return DefenceItem.COVER
        if (glueWalls > 0) return DefenceItem.GLUE_WALL
        if (medikits > 0) return DefenceItem.MEDKIT
        return null
    }

    /** Panic defence: automatically deploy best available for situation. */
    fun panicDefence(
        medikits: Int,
        covers: Int,
        glueWalls: Int,
        antiBombs: Int,
        antiMissiles: Int,
        robertHp: Int,
        robertMaxHp: Int,
        proximityPercent: Float,
        bombInFlight: Boolean = false,
        missileInFlight: Boolean = false
    ): DefenceItem? {
        // Prioritize interceptors if projectile incoming
        if (bombInFlight && antiBombs > 0) return DefenceItem.ANTI_BOMB
        if (missileInFlight && antiMissiles > 0) return DefenceItem.ANTI_MISSILE
        return chooseDefence(medikits, covers, glueWalls, robertHp, robertMaxHp, proximityPercent)
    }

    // ------------------------------------------------------------------
    // Situational decisions: who is winning, where to stand, how fast
    // to fire. Robert never fires on a fixed clock any more.
    // ------------------------------------------------------------------

    /** Health-fraction gap that counts as a moderate / big advantage. */
    const val MODERATE_ADVANTAGE_GAP = 0.10f
    const val BIG_ADVANTAGE_GAP = 0.25f

    /** Who currently holds the upper hand in a fight. */
    enum class CombatSituation {
        /** The player's weapon is damaged: Robert presses at point blank. */
        PRESSURE,
        /** Robert is clearly healthier: he fires continuously, close in. */
        ADVANTAGE,
        /** Roughly even: a varied, human-like pace. */
        EVEN,
        /** Robert is losing: sparse fire while he repositions or flees. */
        DISADVANTAGE
    }

    /** Robert's health advantage: his fraction minus the player's (-1..1). */
    fun advantageGap(
        robertHp: Int,
        robertMaxHp: Int,
        playerHp: Int,
        playerMaxHp: Int
    ): Float {
        if (robertMaxHp <= 0 || playerMaxHp <= 0) return 0f
        val robertFraction = robertHp.toFloat() / robertMaxHp.toFloat()
        val playerFraction = playerHp.toFloat() / playerMaxHp.toFloat()
        return (robertFraction - playerFraction).coerceIn(-1f, 1f)
    }

    fun assessSituation(
        robertHp: Int,
        robertMaxHp: Int,
        playerHp: Int,
        playerMaxHp: Int,
        playerWeaponDamaged: Boolean
    ): CombatSituation {
        if (playerWeaponDamaged) return CombatSituation.PRESSURE
        val gap = advantageGap(robertHp, robertMaxHp, playerHp, playerMaxHp)
        return when {
            gap > MODERATE_ADVANTAGE_GAP -> CombatSituation.ADVANTAGE
            gap < -MODERATE_ADVANTAGE_GAP -> CombatSituation.DISADVANTAGE
            else -> CombatSituation.EVEN
        }
    }

    /**
     * The band Robert holds for the current situation. A forced point blank
     * (damaged weapon, or a glue wall he can break with a sniper) always wins.
     * A glue wall he cannot break pushes him out to the far band instead, so
     * he never walks into a wall he cannot shoot through. Otherwise a big
     * advantage pushes him into the 90%-100% band, a moderate advantage into
     * the 70%-90% band, and a disadvantage pulls him back to 40%-70%.
     * An active 0%-40% deception trip is always allowed to finish.
     * In round 5 (strategy round), positioning is tactical not brute.
     */
    fun situationalRange(
        stored: FireRange,
        playerWeaponDamaged: Boolean,
        playerGlueWallActive: Boolean,
        robertHp: Int,
        robertMaxHp: Int,
        playerHp: Int,
        playerMaxHp: Int,
        deceptionShotsLeft: Int,
        robertHasSniper: Boolean = true,
        playerWeapon: Weapon? = null
    ): FireRange {
        if (playerWeaponDamaged) return FireRange.CLOSE
        if (playerGlueWallActive) return if (robertHasSniper) FireRange.CLOSE else FireRange.FAR
        if (stored == FireRange.FAR && deceptionShotsLeft > 0) return FireRange.FAR
        val gap = advantageGap(robertHp, robertMaxHp, playerHp, playerMaxHp)
        // Round 5 strategic: even when advantaged, prefer STRONG over CLOSE for
        // tactical control.
        val isStrategyRound = robertMaxHp >= Progression.hpFor(5, 1) // approx detection
        // Estimating the weapon in the player's hands: he denies the long-range
        // rifle its band and refuses to stand inside a close-range gun's band.
        val longRangeGun = playerWeapon == Weapon.AWM || playerWeapon == Weapon.SNIPER
        val closeRangeGun = playerWeapon != null && (
            playerWeapon.melee ||
                playerWeapon.autoFire ||
                playerWeapon == Weapon.SHORT_GUN
            )
        return when {
            longRangeGun && gap >= MODERATE_ADVANTAGE_GAP -> FireRange.CLOSE
            longRangeGun -> FireRange.MID
            closeRangeGun && gap < -MODERATE_ADVANTAGE_GAP -> FireRange.MID
            closeRangeGun -> FireRange.STRONG
            gap >= BIG_ADVANTAGE_GAP -> if (isStrategyRound) FireRange.STRONG else FireRange.CLOSE
            gap > MODERATE_ADVANTAGE_GAP -> FireRange.STRONG
            gap < -MODERATE_ADVANTAGE_GAP -> FireRange.MID
            else -> stored
        }
    }

    /**
     * The inclusive delay window (ms) before Robert's next shot. Every window
     * stays inside the 2 s - 3 s rhythm of the new behaviour, with the faster
     * end reserved for an aggressive situation and the slower end for a
     * retreating one.
     */
    fun fireIntervalWindow(situation: CombatSituation, fleeing: Boolean): LongRange = when {
        fleeing -> 2_000L..2_000L
        else -> 1_500L..2_000L
    }

    /**
     * The window used while Robert presses a pinned player continuously. It is
     * still human, never a machine gun: 1.2 s to 2 s between the shots.
     */
    fun continuousFireWindow(): LongRange = 1_500L..2_000L

    /** A jittered delay before Robert's next shot, for the situation. */
    fun fireIntervalMs(
        situation: CombatSituation,
        fleeing: Boolean,
        random: Random,
        continuous: Boolean = false
    ): Long {
        if (fleeing) return NORMAL_FIRE_MAX_MS
        if (continuous) return NORMAL_INTERVALS_MS[random.nextInt(NORMAL_INTERVALS_MS.size)]
        return normalTacticalInterval(random)
    }

    /**
     * The interval before the next shot. Continuous fire only happens while
     * the player is pinned (damaged weapon or extremely weak); every other
     * tick waits a human 2 s, 2.5 s or 3 s, longer while he is running.
     */
    fun firingIntervalWithVulnerability(
        situation: CombatSituation,
        fleeing: Boolean,
        playerVulnerable: Boolean,
        playerBurstShots: Int,
        playerHealthDropping: Boolean,
        random: Random,
        playerWeaponDamaged: Boolean = false,
        playerHpFraction: Float = 1f
    ): Long {
        if (fleeing) return fireIntervalMs(situation, fleeing, random, false)
        val continuous = shouldFireContinuously(
            playerBurstShots = playerBurstShots,
            playerHealthDropping = playerHealthDropping,
            situation = situation,
            playerVulnerable = playerVulnerable,
            playerWeaponDamaged = playerWeaponDamaged,
            playerHpFraction = playerHpFraction
        )
        if (continuous) return fireIntervalMs(situation, fleeing, random, true)
        // Not pinned: the human 2 s / 2.5 s / 3 s rhythm, jittered by the
        // situation so a losing Robert really does shoot more slowly.
        return normalTacticalInterval(random)
    }
}
