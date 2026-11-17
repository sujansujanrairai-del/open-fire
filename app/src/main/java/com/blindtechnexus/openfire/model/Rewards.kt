package com.blindtechnexus.openfire.model

import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One reward bundle: a fixed amount of survival items handed out for free.
 *
 * Bundles only ever contain defences, never weapons and never coins, and a
 * random level reward never contains more than one glue wall.
 */
data class RewardBundle(
    val items: Map<DefenceItem, Int>
) {
    val isEmpty: Boolean
        get() = items.isEmpty() || items.values.all { it <= 0 }

    fun count(item: DefenceItem): Int = items[item] ?: 0

    val glueWalls: Int
        get() = count(DefenceItem.GLUE_WALL)

    val medikits: Int
        get() = count(DefenceItem.MEDKIT)

    val covers: Int
        get() = count(DefenceItem.COVER)

    /** Total number of items in the bundle. */
    val total: Int
        get() = items.values.filter { it > 0 }.sum()

    /** Merges two rewards, adding the amounts of the same defence together. */
    operator fun plus(other: RewardBundle): RewardBundle {
        val merged = items.toMutableMap()
        for ((item, amount) in other.items) {
            merged[item] = (merged[item] ?: 0) + amount
        }
        return RewardBundle(merged)
    }

    /**
     * The bundle as a spoken list, for example "two covers" or
     * "one glue wall and three medikits". The words come from the plural
     * translation keys so every language announces it correctly.
     */
    fun parts(): List<Pair<DefenceItem, Int>> =
        DefenceItem.entries.mapNotNull { item ->
            val amount = count(item)
            if (amount > 0) Pair(item, amount) else null
        }
}

/**
 * Everything one finished battle knows about how well it was played.
 *
 * Coin payouts use ONLY this description of the fight: the power of the
 * weapons that really landed, the share of the opponent's health the electric
 * shock took, the health the player kept and the accuracy of the firing. The
 * number of plays, the number of games, the length of the fight and the
 * number of "struggles" are deliberately not part of it, so a player is never
 * rewarded for grinding and never punished for finishing a fight quickly.
 */
data class BattlePerformance(
    val won: Boolean = false,
    /** Power points of the weapons that landed ([Rewards.powerPointsFor]). */
    val firePower: Int = 0,
    /** Coins the electric shock earned this battle ([Rewards.shockCoins]). */
    val shockCoins: Int = 0,
    /** Health the player still had when the battle ended. */
    val playerHp: Int = 0,
    val playerMaxHp: Int = 0,
    /** Shots the player fired and shots that really connected. */
    val hits: Int = 0,
    val shots: Int = 0
) {
    /** Fraction of the player's own health bar that survived the fight. */
    val healthFraction: Float
        get() = if (playerMaxHp <= 0) 0f else {
            (playerHp.toFloat() / playerMaxHp.toFloat()).coerceIn(0f, 1f)
        }

    /**
     * Share of the player's shots that really landed (0..1).
     *
     * A fight without a single shot is not an inaccurate fight: a player who
     * ended it with the electric shock is paid the full accuracy credit, so
     * the accuracy bonus never punishes a legitimate way of fighting.
     */
    val accuracyFraction: Float
        get() = if (shots <= 0) 1f else (hits.toFloat() / shots.toFloat()).coerceIn(0f, 1f)
}

/**
 * The reward ladder.
 *
 *  - five wins complete a level, and every completed level hands out one
 *    random bundle out of [LEVEL_REWARDS];
 *  - five levels complete a round, and a completed round always hands out the
 *    fixed [ROUND_REWARD]: two glue walls, two medikits and three covers;
 *  - a random level reward never contains more than one glue wall
 *    ([MAX_GLUE_WALLS_PER_LEVEL_REWARD]).
 *
 * ## The coin law
 *
 * Coins describe the CAPABILITY AND POWER of the played battle, never the
 * number of plays:
 *
 * ```
 * coins = win base + weapon power + shock payout + health bonus + accuracy bonus
 * ```
 *
 *  - [WIN_BASE_COINS]      every won battle pays this before anything else;
 *  - weapon power          the power ladder of [powerPointsFor]: a missile that
 *                          ends the fight is worth 600, a grenade 350, and every
 *                          gun scores three times its point-blank damage;
 *  - shock payout          [shockCoins], 700 to 1000 coins: the electric shock
 *                          is one of the most powerful acts of the game, so a
 *                          single real use can never pay a token 150 coins;
 *  - [MAX_HEALTH_BONUS]    scaled by the health the player kept, so a clean
 *                          fight pays more than a fight survived on fumes;
 *  - [MAX_ACCURACY_BONUS]  scaled by how many of the fired shots really
 *                          connected, so spraying blindly pays less.
 *
 * A won battle pays at most [MAX_BATTLE_COINS], a defeat pays no coins here
 * (the defeat penalty is separate), and no counter of "how many times you
 * played" is ever consulted.
 */
object Rewards {
    /** Wins that complete one level. */
    const val WINS_PER_LEVEL = Progression.WINS_PER_LEVEL

    /** A won battle pays at most this many coins. */
    const val MAX_BATTLE_COINS = 2500

    /** Every won battle pays this base before the power of the fight. */
    const val WIN_BASE_COINS = 200

    /** Biggest bonus for finishing the fight with a full health bar. */
    const val MAX_HEALTH_BONUS = 250

    /** Biggest bonus for landing every fired shot. */
    const val MAX_ACCURACY_BONUS = 150

    /** Power points: one landed missile. The most powerful single act. */
    const val MISSILE_POWER_POINTS = 600

    /** Power points: one landed atom bomb. */
    const val ATOM_BOMB_POWER_POINTS = 500

    /** Power points: one landed grenade or timer bomb. */
    const val BOMB_POWER_POINTS = 350

    /**
     * The electric shock is paid through [shockCoins] instead of the weapon
     * power ladder: one real use is worth [SHOCK_MIN_COINS] to
     * [SHOCK_MAX_COINS] coins, far above any single bullet.
     */
    const val SHOCK_POWER_POINTS = 0

    /** Smallest payout of one real electric-shock use. */
    const val SHOCK_MIN_COINS = 700

    /** Payout of an electric shock that alone finished the opponent. */
    const val SHOCK_MAX_COINS = 1_000

    /**
     * Share of the opponent's bar the shock may still take and be paid the
     * minimum [SHOCK_MIN_COINS]: a short touch is a real use, not a bargain.
     */
    const val SHOCK_MIN_DRAIN_FRACTION = 0.01f

    /**
     * The power points of one landed hit of [weapon]: explosives carry the big
     * scores, and every other weapon scores its point-blank power three times
     * over - the AWM is worth more than the knife, exactly like the damage
     * ladder. The electric shock pays through [shockCoins] instead.
     */
    fun powerPointsFor(weapon: Weapon): Int = when {
        weapon.missileLauncher -> MISSILE_POWER_POINTS
        weapon.id == "atomBomb" -> ATOM_BOMB_POWER_POINTS
        weapon.bomb -> BOMB_POWER_POINTS
        weapon.electricShock -> SHOCK_POWER_POINTS
        else -> DamageTable.percentOf(weapon, HitZone.HEAVY) * 3
    }

    /**
     * Coins earned by the electric shock.
     *
     * One real use always pays at least [SHOCK_MIN_COINS]: holding the shock
     * long enough to drain anything at all is a powerful act, not a token
     * gesture. The payout then grows with the share of the opponent's health
     * the shock really took, and an opponent finished by the shock alone pays
     * the full [SHOCK_MAX_COINS].
     *
     * @param drainedHp health the shock really removed from the opponent.
     * @param opponentMaxHp the opponent's maximum health in that battle.
     */
    fun shockCoins(drainedHp: Int, opponentMaxHp: Int): Int {
        val drained = drainedHp.coerceAtLeast(0)
        if (drained <= 0) return 0
        val maxHp = opponentMaxHp.coerceAtLeast(1)
        if (drained >= maxHp) return SHOCK_MAX_COINS
        // A short touch of the shock pays exactly SHOCK_MIN_COINS; the payout
        // only grows once the shock has really bitten.
        val floor = (maxHp * SHOCK_MIN_DRAIN_FRACTION).coerceAtLeast(1f)
        if (drained.toFloat() <= floor) return SHOCK_MIN_COINS
        val effectiveness = ((drained - floor) / (maxHp - floor)).coerceIn(0f, 1f)
        val span = (SHOCK_MAX_COINS - SHOCK_MIN_COINS).toFloat()
        return (SHOCK_MIN_COINS + span * effectiveness).roundToInt()
            .coerceIn(SHOCK_MIN_COINS, SHOCK_MAX_COINS)
    }

    /**
     * The coins a finished battle pays, from the whole performance of the
     * fight. A defeat pays nothing here (the defeat penalty is separate).
     */
    fun battleCoins(performance: BattlePerformance): Int {
        if (!performance.won) return 0
        // Truncated, never rounded up: the payout describes what really
        // happened, so the numbers are reproducible by hand.
        val healthBonus = (MAX_HEALTH_BONUS * performance.healthFraction).toInt()
        val accuracyBonus = (MAX_ACCURACY_BONUS * performance.accuracyFraction).toInt()
        val total = WIN_BASE_COINS +
            performance.firePower.coerceAtLeast(0) +
            performance.shockCoins.coerceIn(0, SHOCK_MAX_COINS) +
            healthBonus.coerceIn(0, MAX_HEALTH_BONUS) +
            accuracyBonus.coerceIn(0, MAX_ACCURACY_BONUS)
        return total.coerceAtMost(MAX_BATTLE_COINS)
    }

    /**
     * Compatibility entry point kept for the older callers and tests: a win
     * without a shock pays the win base plus the weapon power. Everything the
     * game itself awards goes through [battleCoins] with the full
     * [BattlePerformance] of the fight.
     */
    @Deprecated("Use battleCoins(BattlePerformance) so shock, health and accuracy are paid.")
    fun battleCoins(firePowerPoints: Int, won: Boolean): Int {
        if (!won) return 0
        return (WIN_BASE_COINS + firePowerPoints.coerceAtLeast(0))
            .coerceAtMost(MAX_BATTLE_COINS)
    }

    /** Hard rule: a random level reward carries at most one glue wall. */
    const val MAX_GLUE_WALLS_PER_LEVEL_REWARD = 1

    /** The five random level rewards. */
    val LEVEL_REWARDS: List<RewardBundle> = listOf(
        // Two covers.
        RewardBundle(mapOf(DefenceItem.COVER to 2)),
        // Three medkits.
        RewardBundle(mapOf(DefenceItem.MEDKIT to 3)),
        // One medkit and two covers.
        RewardBundle(mapOf(DefenceItem.MEDKIT to 1, DefenceItem.COVER to 2)),
        // One glue wall and two medkits.
        RewardBundle(mapOf(DefenceItem.GLUE_WALL to 1, DefenceItem.MEDKIT to 2)),
        // One glue wall and two covers.
        RewardBundle(mapOf(DefenceItem.GLUE_WALL to 1, DefenceItem.COVER to 2))
    )

    /** Completing a round always grants two glue walls, two medkits, three covers. */
    val ROUND_REWARD: RewardBundle = RewardBundle(
        mapOf(
            DefenceItem.GLUE_WALL to 2,
            DefenceItem.MEDKIT to 2,
            DefenceItem.COVER to 3
        )
    )

    /** Picks one of the random level rewards. */
    fun levelReward(random: Random): RewardBundle =
        LEVEL_REWARDS[random.nextInt(LEVEL_REWARDS.size)]

    /** The fixed round reward. */
    fun roundReward(): RewardBundle = ROUND_REWARD

    /**
     * The reward for one promotion: a completed round pays the round reward,
     * a completed level pays a random level reward, and anything else pays
     * nothing at all.
     */
    fun forPromotion(levelCompleted: Boolean, roundCompleted: Boolean, random: Random): RewardBundle =
        when {
            roundCompleted -> roundReward()
            levelCompleted -> levelReward(random)
            else -> RewardBundle(emptyMap())
        }

    /** Safety check used by the unit tests: the one-glue-wall rule holds. */
    fun levelRewardsRespectTheGlueWallLimit(): Boolean =
        LEVEL_REWARDS.all { it.glueWalls <= MAX_GLUE_WALLS_PER_LEVEL_REWARD } &&
            LEVEL_REWARDS.none { it.isEmpty }
}
