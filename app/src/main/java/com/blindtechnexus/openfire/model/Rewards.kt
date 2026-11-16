package com.blindtechnexus.openfire.model

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
 * The reward ladder.
 *
 *  - five wins complete a level, and every completed level hands out one
 *    random bundle out of [LEVEL_REWARDS];
 *  - five levels complete a round, and a completed round always hands out the
 *    fixed [ROUND_REWARD]: two glue walls, two medikits and three covers;
 *  - a random level reward never contains more than one glue wall
 *    ([MAX_GLUE_WALLS_PER_LEVEL_REWARD]).
 */
object Rewards {
    /** Wins that complete one level. */
    const val WINS_PER_LEVEL = Progression.WINS_PER_LEVEL

    /**
     * Battle coins are awarded for the POWER of the gameplay, never for the
     * number of shots fired. One missile that kills the enemy outright is a
     * more powerful game than a long spray of light bullets, and it earns
     * more. A winning game pays at most [MAX_BATTLE_COINS] coins.
     */
    const val MAX_BATTLE_COINS = 1500

    /** Every win pays this base before the power of the fight is added. */
    const val WIN_BASE_COINS = 150

    /** Power points: one landed missile. The most powerful single act. */
    const val MISSILE_POWER_POINTS = 600

    /** Power points: one landed atom bomb. */
    const val ATOM_BOMB_POWER_POINTS = 500

    /** Power points: one landed grenade or timer bomb. */
    const val BOMB_POWER_POINTS = 350

    /** Power points: one round of electric shock that lands. */
    const val SHOCK_POWER_POINTS = 200

    /**
     * The power points of one landed hit of [weapon]: explosives and the
     * electric shock carry the big scores, and every other weapon scores its
     * point-blank power three times over - the AWM is worth more than the
     * knife, exactly like the damage ladder.
     */
    fun powerPointsFor(weapon: Weapon): Int = when {
        weapon.missileLauncher -> MISSILE_POWER_POINTS
        weapon.id == "atomBomb" -> ATOM_BOMB_POWER_POINTS
        weapon.bomb -> BOMB_POWER_POINTS
        weapon.electricShock -> SHOCK_POWER_POINTS
        else -> DamageTable.percentOf(weapon, HitZone.HEAVY) * 3
    }

    /**
     * The coins a finished battle pays. A defeat pays nothing here (the
     * defeat penalty is separate). The score is the win base plus everything
     * the player's firing power accumulated, capped at [MAX_BATTLE_COINS]:
     * playing longer never pays less and playing harder always pays more.
     */
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
