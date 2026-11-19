package com.blindtechnexus.openfire.model

/**
 * Strict progression ladder:
 *  - 5 wins    = 1 level (and one random reward)
 *  - 5 levels  = 1 round (and the fixed round reward)
 *  - 6 rounds  = the whole match
 *
 * Wins collected inside the current level are tracked explicitly instead of
 * being inferred from the lifetime win count. Completed rounds are also kept
 * explicitly: unlike the current round, that counter is not reset when a new
 * match starts, so permanent unlocks such as the Missile stay unlocked.
 */
object Progression {
    /** Five wins complete one level and pay one random reward. */
    const val WINS_PER_LEVEL = 5
    const val LEVELS_PER_ROUND = 5
    const val ROUNDS_PER_MATCH = 6

    /** Health at round 1, level 1, and the increase for every later level. */
    const val INITIAL_HEALTH = 200
    const val HEALTH_PER_LEVEL = 100

    /** Completed rounds needed to unlock the missile and its powers. */
    const val MISSILE_UNLOCK_COMPLETED_ROUNDS = 2

    /**
     * Completing round three (entering round four) unlocks the player's
     * anti-missile system and also hands out one free interceptor.
     */
    const val ANTI_MISSILE_UNLOCK_COMPLETED_ROUNDS = 3

    /** The round in which the free anti-missile reward is handed out. */
    const val ANTI_MISSILE_REWARD_ROUND = 4

    /**
     * The final round is played against the clock: seven minutes per game.
     * If Robert is still alive when they run out, the player loses.
     */
    const val ROUND_FIVE_TIME_LIMIT_MS = 7L * 60L * 1_000L
    const val FINAL_ROUND_TIME_LIMIT_MS = 5L * 60L * 1_000L

    /** Coins taken away from the player for a lost game. */
    const val DEFEAT_COIN_PENALTY = 20

    /** The time limit of a round in milliseconds (0 = no limit). */
    fun roundTimeLimitMs(round: Int): Long =
        when (round.coerceIn(1, ROUNDS_PER_MATCH)) {
            5 -> ROUND_FIVE_TIME_LIMIT_MS
            6 -> FINAL_ROUND_TIME_LIMIT_MS
            else -> 0L
        }

    /**
     * Coins really deducted for a defeat: the penalty, capped by what the
     * player actually owns, so a player with no coins loses nothing at all.
     */
    fun coinsLostOnDefeat(coins: Int): Int {
        val safe = coins.coerceAtLeast(0)
        return minOf(DEFEAT_COIN_PENALTY, safe)
    }

    data class WinResult(
        val stats: GameStats,
        val leveledUp: Boolean,
        val roundCompleted: Boolean,
        /** True when the sixth round was completed: the match is won. */
        val matchWon: Boolean = false
    )

    /** Applies one won game to [stats] and resolves level / round / match promotion. */
    fun applyWin(stats: GameStats): WinResult {
        var level = stats.level.coerceIn(1, LEVELS_PER_ROUND)
        var round = stats.round.coerceIn(1, ROUNDS_PER_MATCH)
        var levelWins = stats.levelWins.coerceIn(0, WINS_PER_LEVEL - 1)
        val completedBefore = maxOf(stats.completedRounds, round - 1).coerceAtLeast(0)
        var leveledUp = false
        var roundCompleted = false
        var matchWon = false

        levelWins += 1
        if (levelWins >= WINS_PER_LEVEL) {
            levelWins = 0
            leveledUp = true
            if (level >= LEVELS_PER_ROUND) {
                level = 1
                roundCompleted = true
                if (round >= ROUNDS_PER_MATCH) {
                    matchWon = true
                    round = 1
                } else {
                    round += 1
                }
            } else {
                level += 1
            }
        }

        return WinResult(
            stats = stats.copy(
                level = level,
                round = round,
                levelWins = levelWins,
                completedRounds = completedBefore + if (roundCompleted) 1 else 0
            ),
            leveledUp = leveledUp,
            roundCompleted = roundCompleted,
            matchWon = matchWon
        )
    }

    /**
     * Health both fighters receive for a specific round and level.
     *
     * The ladder never resets at a round boundary: R1/L1 is 200, R1/L5 is
     * 600, R2/L1 is 700, and every subsequent level adds another 100.
     */
    fun hpFor(round: Int, level: Int): Int {
        val safeRound = round.coerceIn(1, ROUNDS_PER_MATCH)
        val safeLevel = level.coerceIn(1, LEVELS_PER_ROUND)
        val zeroBasedLevel = (safeRound - 1) * LEVELS_PER_ROUND + (safeLevel - 1)
        return INITIAL_HEALTH + zeroBasedLevel * HEALTH_PER_LEVEL
    }

    /** True when the given round is the last one of a match. */
    fun isFinalRound(round: Int): Boolean = round >= ROUNDS_PER_MATCH

    /** True once round two has been completed at any time in this profile. */
    fun missileUnlocked(stats: GameStats): Boolean =
        stats.completedRounds >= MISSILE_UNLOCK_COMPLETED_ROUNDS

    /** True once round three has been completed: the anti-missile unlocks. */
    fun antiMissileUnlocked(stats: GameStats): Boolean =
        stats.completedRounds >= ANTI_MISSILE_UNLOCK_COMPLETED_ROUNDS

    /** True when the current round owes the player the free interceptor. */
    fun antiMissileRewardDue(round: Int, granted: Boolean): Boolean =
        !granted && round.coerceIn(1, ROUNDS_PER_MATCH) >= ANTI_MISSILE_REWARD_ROUND

    /** Keeps loaded stats inside the valid ladder ranges. */
    fun normalize(stats: GameStats): GameStats = stats.copy(
        games = stats.games.coerceAtLeast(0),
        wins = stats.wins.coerceAtLeast(0),
        kills = stats.kills.coerceAtLeast(0),
        coins = stats.coins.coerceAtLeast(0),
        loanBalance = stats.loanBalance.coerceIn(0, Loans.AMOUNTS.last()),
        round = stats.round.coerceIn(1, ROUNDS_PER_MATCH),
        level = stats.level.coerceIn(1, LEVELS_PER_ROUND),
        levelWins = ((stats.levelWins % WINS_PER_LEVEL) + WINS_PER_LEVEL) % WINS_PER_LEVEL,
        completedRounds = stats.completedRounds.coerceAtLeast(0)
    )
}
