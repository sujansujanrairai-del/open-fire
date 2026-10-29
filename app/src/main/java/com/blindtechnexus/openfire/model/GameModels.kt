package com.blindtechnexus.openfire.model

enum class Screen {
    WELCOME,
    MENU,
    SHOP,
    GUIDE,
    PROFILE,
    ABOUT,
    CHANGELOG,
    SETTINGS,
    READER,
    JUMP,
    PARA,
    LAND,
    BATTLE,
    RESULT
}

enum class SoundTheme(val title: String) {
    TACTICAL("Tactical Royale"),
    CYBER("Sci-Fi Cyber Warfare"),
    STEALTH("Night Ops / Stealth")
}

/**
 * The seven items of the in-game quick menu, in the order they are cycled by a
 * two-finger swipe up or down: medikit, cover, glue wall, anti-missile system,
 * anti-bomb explosive, exit and recover.
 *
 * The old "play" item was replaced by Recovery, which repairs every damaged
 * weapon for [Combat.GUN_RECOVERY_COST] coins.
 */
enum class BattleMenuItem(
    val nameKey: String,
    val defence: DefenceItem?
) {
    MEDKIT("medkit", DefenceItem.MEDKIT),
    COVER("cover", DefenceItem.COVER),
    GLUE_WALL("glooWall", DefenceItem.GLUE_WALL),
    ANTI_MISSILE("antiMissile", DefenceItem.ANTI_MISSILE),
    ANTI_BOMB("antiBomb", DefenceItem.ANTI_BOMB),
    EXIT("exitGame", null),
    RECOVER("recoverWeapon", null);

    companion object {
        /**
         * The menu starts on Recover. It is the safe default: while every
         * weapon works it only announces that nothing is damaged and never
         * spends a coin.
         */
        val RECOVER_INDEX: Int = RECOVER.ordinal

        fun at(index: Int): BattleMenuItem {
            val size = BattleMenuItem.entries.size
            return BattleMenuItem.entries[((index % size) + size) % size]
        }
    }
}

data class GameSettings(
    val name: String = "",
    val lang: String = "en", // "en", "hi", "ne"
    val ttsEnginePackage: String = "",
    val voiceName: String = "",
    val ttsRate: Float = 1.0f,
    val ttsVolume: Float = 1.0f,
    val musicVolume: Float = 0.5f,
    val sfxVolume: Float = 1.0f,
    val soundTheme: SoundTheme = SoundTheme.TACTICAL,
    val hapticsEnabled: Boolean = true,
    val keepScreenOn: Boolean = false
)

data class GameStats(
    val games: Int = 0,
    val wins: Int = 0,
    val kills: Int = 0,
    val coins: Int = 100,
    val round: Int = 1,
    val level: Int = 1,
    /** Wins collected inside the current level. Five wins promote a level. */
    val levelWins: Int = 0,
    /** Lifetime completed rounds; unlike [round], this never resets between matches. */
    val completedRounds: Int = 0
) {
    /** Wins still required to reach the next level (always 1..5). */
    val needWinsForNext: Int
        get() = (Progression.WINS_PER_LEVEL - levelWins)
            .coerceIn(1, Progression.WINS_PER_LEVEL)

    companion object {
        /**
         * A completely wiped profile: every counter is zero, the inventory is
         * empty and level and round are back to 1.
         */
        fun cleared(): GameStats = GameStats(
            games = 0,
            wins = 0,
            kills = 0,
            coins = 0,
            round = 1,
            level = 1,
            levelWins = 0,
            completedRounds = 0
        )
    }
}

/**
 * Complete, immutable snapshot of a battle.
 *
 * Health, weapons, ammo, positions and defences of both fighters live here so
 * the interface can render the whole battle from one state object.
 *
 * The battle is played inside a real 25 m by 25 m square zone ([Arena]): both
 * fighters own an (x, y) position, the fighting distance is derived from those
 * positions, and running into one of the four boundaries is a wall hit.
 */
data class GameState(
    val playerHp: Int = 200,
    val playerMaxHp: Int = 200,
    val robertHp: Int = 200,
    val robertMaxHp: Int = 200,
    val round: Int = 1,
    val level: Int = 1,

    /** Weapon ids owned for this battle, in switch order. */
    val weapons: List<String> = Weapon.defaults().map { it.id },
    val weaponIndex: Int = 0,
    val ammo: List<Int> = Weapon.defaults().map { it.magazine },
    /** Which weapons are currently damaged and need Recovery. */
    val damaged: List<Boolean> = Weapon.defaults().map { false },

    val distance: Float = Combat.MAX_RANGE,

    // ------------------------------------------------------------------
    // Real positions inside the 25 m square battle zone. Y is the depth axis: the
    // player starts at the near edge, Robert at the far edge, so the fight
    // begins exactly 25 m apart. X is the width of the zone and is what the
    // left and right halves of the center portion run along.
    // ------------------------------------------------------------------
    val playerX: Float = Arena.PLAYER_START_X,
    val playerY: Float = Arena.PLAYER_START_Y,
    val robertX: Float = Arena.ROBERT_START_X,
    val robertY: Float = Arena.ROBERT_START_Y,

    /** The running speed level toggled by a three-finger swipe ([RunSpeed.id]). */
    val runSpeed: String = RunSpeed.NORMAL.id,

    /**
     * The direction the player is really aiming at ([Facing.id]). It follows
     * the last movement: running right turns the player to the right. A shot
     * only lands while Robert stands inside the aiming cone of this
     * direction, so firing blindly forward at an enemy who stands to the side
     * never registers a hit.
     */
    val playerFacing: String = Facing.FORWARD.id,

    /** A cover blocks 11 hits; the 12th breaks it. */
    val coverActive: Boolean = false,
    val coverHits: Int = 0,
    /**
     * A player gloo wall stops everything except three consecutive
     * Gloo Wall Breaker shots from the 90%-100% band.
     */
    val glueWallActive: Boolean = false,
    val glueWallProgress: Int = 0,

    val robertCoverActive: Boolean = false,
    val robertCoverHits: Int = 0,
    val robertGlueWallActive: Boolean = false,
    val robertGlueWallProgress: Int = 0,
    /**
     * Gloo Wall Breaker charges Robert may still spend this match. The enemy
     * only ever receives the breaker when the player raises a gloo wall: once
     * in round one, twice in round two, and he uses it automatically.
     */
    val robertGlueBreakers: Int = 0,
    /** True while Robert is firing his breaker at the player's wall. */
    val robertBreakerArmed: Boolean = false,
    /** Gloo Wall Breaker shots Robert has already landed on the current wall. */
    val robertBreakerHits: Int = 0,
    val robertWeapons: List<String> = emptyList(),
    val robertWeaponIndex: Int = 0,
    val robertAmmo: List<Int> = emptyList(),
    val robertDamaged: List<Boolean> = emptyList(),
    val robertMedkits: Int = 0,
    val robertCovers: Int = 0,
    val robertGlueWalls: Int = 0,
    /** True when the Robert may spend its next defence. */
    val robertDefenceArmed: Boolean = true,
    val robertConsecutiveHits: Int = 0,
    /** Shots Robert has fired during the current exchange of hits. */
    val robertExchangeShots: Int = 0,
    /**
     * Sideways offset of Robert in metres for positional audio. Zero means
     * directly head-on (the normal fight). It is kept in sync with the real
     * positions: `robertX - playerX`.
     */
    val robertLateral: Float = 0f,
    /**
     * "advance", "retreat", "flank", "avoid" (a glue wall he cannot break),
     * "missileFlee", "bombFlee" or "turnBack" (he detected a wall).
     */
    val robertState: String = "advance",
    /** Ticks left of the turn-back run after Robert detected a wall. */
    val robertTurnBackTicks: Int = 0,
    /** Which way Robert comes around while flanking: +1 right, -1 left. */
    val robertFlankSide: Int = 1,

    /** True while a guided missile is in the air (either fighter's). */
    val missileInFlight: Boolean = false,
    /** True when the missile in the air was launched by the player. */
    val missileFromPlayer: Boolean = false,
    /** True when Robert will fire his anti-missile interceptor at impact. */
    val missileInterceptPlanned: Boolean = false,
    /** Missile powers Robert may still spend this game (rounds 4 and 5). */
    val robertMissilePowers: Int = 0,
    /** Anti-missile interceptors Robert may still fire this game. */
    val robertAntiMissiles: Int = 0,

    // ------------------------------------------------------------------
    // Bombs (grenade, atom bomb and timer bomb), from round three onwards.
    // ------------------------------------------------------------------
    /** True while a bomb is counting down towards its destination. */
    val bombInFlight: Boolean = false,
    /** True when the bomb in the air was thrown by the player. */
    val bombFromPlayer: Boolean = false,
    /** The bomb in the air ([BombKind.id]), or empty. */
    val bombKind: String = "",
    /** Destination of the bomb inside the zone: it never moves again. */
    val bombX: Float = Arena.PLAYER_START_X,
    val bombY: Float = Arena.PLAYER_START_Y,
    /** True when Robert will deploy his anti-bomb explosive at the blast. */
    val bombInterceptPlanned: Boolean = false,
    /** True when the player deployed the anti-bomb explosive at this bomb. */
    val bombInterceptedByPlayer: Boolean = false,
    /** Bombs Robert may still throw this game. */
    val robertBombs: Int = 0,
    /** Grenades Robert may still throw this game (twice in round three). */
    val robertGrenades: Int = 0,
    /** Atom bombs Robert may still throw (three per game from round four). */
    val robertAtomBombs: Int = 0,
    /** Timer bombs Robert may still throw (three per game from round four). */
    val robertTimerBombs: Int = 0,
    /** Anti-bomb explosives Robert may still deploy this game (2 / 3 / 5). */
    val robertAntiBombs: Int = 0,

    /**
     * Shots Robert has fired in a row without pausing, and the length of the
     * current burst (one or two shots). This is what stops him from firing
     * more than once or twice in succession.
     */
    val robertSuccessiveShots: Int = 0,
    val robertBurstLimit: Int = RobertRules.MAX_SUCCESSIVE_SHOTS,

    /**
     * Round five time limit in milliseconds (0 = no limit). Seven minutes are
     * armed only in the final round: when they run out while Robert is still
     * alive, the player loses the game.
     */
    val battleLimitMs: Long = 0L,
    /** Milliseconds left before the round five limit expires. */
    val battleRemainingMs: Long = 0L,

    /** True while the timer bomb duration picker is open. */
    val timerSelectActive: Boolean = false,
    /** Highlighted duration of the timer bomb picker. */
    val timerIndex: Int = BombRules.DEFAULT_TIMER_INDEX,

    /** True while an automatic weapon keeps firing (the up-swipe is held). */
    val autoFireActive: Boolean = false,

    /**
     * The player's current fire burst: how many shots landed inside
     * [RobertRules.PLAYER_BURST_WINDOW_MS], and the health both fighters had
     * when the burst started. Robert reads these to answer continuous fire
     * with continuous fire, to retreat while his own health drops and to keep
     * firing while the player's health drops.
     */
    val playerBurstShots: Int = 0,
    val playerBurstRobertHp: Int = 0,
    val playerBurstPlayerHp: Int = 0,

    /**
     * The band Robert randomly prefers to fire from ([FireRange.id]):
     * 40%-70%, 90%-100%, or the 0%-40% deception band.
     */
    val robertFireRange: String = FireRange.MID.id,
    /** Deception shots still to fire from the 0%-40% band (one or two). */
    val robertDeceptionShots: Int = 0,
    val robertHits: Int = 0,
    val robertShots: Int = 0,
    val robertDamage: Int = 0,
    val playerShots: Int = 0,
    val playerHits: Int = 0,
    /**
     * Hits landed inside the current exchange of blows. Robert compares them
     * with the hit-exchange rule: when the player suddenly lands more hits
     * (two against one, for example) he retreats at once. Both counters are
     * reset when a retreat starts, so the next exchange starts fair.
     */
    val fightPlayerHits: Int = 0,
    val fightRobertHits: Int = 0,
    val consecutiveHeavyHits: Int = 0,
    /** Consecutive point-blank hits the player has landed on Robert. */
    val playerHeavyHits: Int = 0,

    /** One second chance per fight for the player. */
    val playerSecondChanceUsed: Boolean = false,

    /** Index of the highlighted quick-menu item ([BattleMenuItem]). */
    val menuIndex: Int = BattleMenuItem.RECOVER_INDEX,
    /** True while the player is navigating the menu: hits then cost 2 percent. */
    val menuActive: Boolean = false,
    /** Item waiting for the second confirming tap ("Tap again to buy"). */
    val pendingPurchaseId: String? = null,

    val log: String = "",
    val matchWon: Boolean = false
) {
    val activeWeapon: Weapon
        get() = Weapon.fromId(weapons.getOrNull(weaponIndex)) ?: Weapon.SHORT_GUN

    val activeWeaponDamaged: Boolean
        get() = damaged.getOrElse(weaponIndex) { false }

    val activeAmmo: Int
        get() = ammo.getOrElse(weaponIndex) { 0 }

    val anyWeaponDamaged: Boolean
        get() = damaged.any { it }

    val menuItem: BattleMenuItem
        get() = BattleMenuItem.at(menuIndex)

    val robertWeapon: Weapon?
        get() = Weapon.fromId(robertWeapons.getOrNull(robertWeaponIndex))

    val robertAmmoNow: Int
        get() = robertAmmo.getOrElse(robertWeaponIndex) { 0 }

    val robertFleeing: Boolean
        get() = robertState == "retreat"

    /** True while Robert is protected by one of his own barriers. */
    val robertHasActiveDefence: Boolean
        get() = robertCoverActive || robertGlueWallActive

    /** True while the player is protected by one of their barriers. */
    val playerHasActiveDefence: Boolean
        get() = coverActive || glueWallActive

    /** True while Robert refuses to approach an unbreakable glue wall. */
    val robertAvoiding: Boolean
        get() = robertState == "avoid"

    /** True while Robert is running away from a bomb destination. */
    val robertBombFleeing: Boolean
        get() = robertState == "bombFlee"

    /** The running speed level selected with the three-finger gesture. */
    val runSpeedLevel: RunSpeed
        get() = RunSpeed.fromId(runSpeed)

    /** The direction the player is aiming at right now. */
    val facing: Facing
        get() = Facing.fromId(playerFacing)

    /** True when Robert stands inside the player's aiming cone. */
    val playerFacingRobert: Boolean
        get() = Combat.isFacingTarget(facing, robertX - playerX, robertY - playerY)

    /** True while Robert may still raise a gloo wall of his own. */
    val robertGlueWallAvailable: Boolean
        get() = robertGlueWalls > 0

    /** True while Robert may still use his Gloo Wall Breaker. */
    val robertGlueBreakerAvailable: Boolean
        get() = robertGlueBreakers > 0

    /** The bomb currently in the air, or null. */
    val bomb: BombKind?
        get() = BombKind.fromId(bombKind)

    /** The chosen timer bomb duration in seconds. */
    val timerSeconds: Int
        get() = BombRules.timerSecondsAt(timerIndex)

    /** True while the round five seven minute limit is running. */
    val timeLimitActive: Boolean
        get() = battleLimitMs > 0L

    /** True when the player's anti-bomb explosive is free and always usable. */
    val playerAntiBombFree: Boolean
        get() = BombRules.playerAntiBombsFree(round)

    /** Distance of the player from the bomb destination, in metres. */
    val playerDistanceFromBomb: Float
        get() = Arena.distanceTo(bombX, bombY, playerX, playerY)

    /** Distance of Robert from the bomb destination, in metres. */
    val robertDistanceFromBomb: Float
        get() = Arena.distanceTo(bombX, bombY, robertX, robertY)

    /** The band Robert picked for himself, ignoring forced point blank. */
    val robertRange: FireRange
        get() = FireRange.fromId(robertFireRange)

    /** Health of both fighters as a percentage of their own maximum. */
    val playerHpPercent: Int
        get() = if (playerMaxHp <= 0) 0 else (playerHp * 100 / playerMaxHp).coerceIn(0, 100)

    val robertHpPercent: Int
        get() = if (robertMaxHp <= 0) 0 else (robertHp * 100 / robertMaxHp).coerceIn(0, 100)
}

data class ResultData(
    val won: Boolean = false,
    val coins: Int = 0,
    val hits: Int = 0,
    val shots: Int = 0,
    val robertShots: Int = 0,
    val hp: Int = 0,
    val levelUp: Boolean = false,
    val roundComplete: Boolean = false,
    val matchWon: Boolean = false,
    val round: Int = 1,
    val level: Int = 1,
    /** Reward handed out for this promotion (already localised). */
    val rewardText: String = "",
    val rewardMedkits: Int = 0,
    val rewardCovers: Int = 0,
    val rewardGlueWalls: Int = 0,
    /** Weapons issued for free with this promotion, by id. */
    val rewardWeapons: List<String> = emptyList(),
    /** Coins deducted for a defeat (0 when the player had none). */
    val coinsLost: Int = 0,
    /** True when the round five time limit ran out: Robert survived. */
    val timeUp: Boolean = false
) {
    val hasReward: Boolean
        get() = rewardText.isNotBlank() ||
            rewardMedkits > 0 || rewardCovers > 0 || rewardGlueWalls > 0 ||
            rewardWeapons.isNotEmpty()
}

data class TtsEngineInfo(
    val name: String,
    val label: String
)

data class TtsVoiceInfo(
    val name: String,
    val displayName: String,
    val language: String
)
