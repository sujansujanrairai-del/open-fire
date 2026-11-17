package com.blindtechnexus.openfire.model

/**
 * Every weapon in Open Fire.
 *
 * A weapon owns its damage power, its magazine size and the sound effects
 * that belong to it. Melee weapons (knife and sword) never reload, but they
 * can still be damaged by three consecutive point-blank hits, exactly like
 * firearms.
 *
 * Both fighters use [DamageTable] for ordinary weapons: every gunshot is
 * capped at 80 flat health points and proximity controls its band. Bombs
 * take 60 to 75 percent of the victim's remaining health and a direct
 * missile hit destroys its target (see [BombRules] and [MissileRules]).
 *
 * Three weapon families behave differently in battle:
 *  - classic firearms and melee weapons fire one shot per swipe up;
 *  - automatic weapons ([autoFire]: the submachine gun, the AK-47 and the
 *    machine gun) keep firing while the up-swipe is held, one bullet per
 *    [burstIntervalMs] (0.5 s, 1 s or 1.5 s depending on the weapon's power),
 *    with their sound on a gapless loop for as long as the trigger and the
 *    magazine allow ([maxBurstMs] is only the legacy reference length of one
 *    burst);
 *  - launchers ([missileLauncher] and [bomb]) start a tracked flight instead
 *    of a shot and are resolved by [MissileRules] and [BombRules].
 *
 * Every weapon hit also plays the sound of the fighter that was hit: the
 * weapon's own hit sound (knife-hit, sword-hit, gun-hit or explosion) is
 * always mixed with enemy-hit or player-hit.
 */
enum class Weapon(
    val id: String,
    val nameKey: String,
    /** Flat health-point damage inside the 40%-70% (light) band. */
    val powerPercent: Int,
    /** Flat health-point damage at 90%-100% proximity. */
    val closeRangePowerPercent: Int,
    /** Rounds in a full magazine. Melee weapons, bombs and the missile use 0 or 1. */
    val magazine: Int,
    /** Shop price. 0 = never sold, the weapon is received by default. */
    val price: Int,
    val ownedByDefault: Boolean,
    val melee: Boolean,
    val fireSoundKey: String,
    val switchSoundKey: String,
    val hitSoundKey: String,
    val missSoundKey: String,
    /**
     * True only for the missile launcher. It lives in the normal weapon
     * inventory and is switched to with the usual gun-switch sound, but it
     * has no magazine: every launch consumes one stackable missile power.
     */
    val missileLauncher: Boolean = false,
    /** True for the three bombs: grenade, atom bomb and timer bomb. */
    val bomb: Boolean = false,
    /**
     * Round in which the weapon becomes available (1 = from the very
     * beginning). The shop only sells it once the previous round has been
     * completed, and Robert receives it in his round loadout.
     */
    val unlockRound: Int = 1,
    /**
     * Round from which this weapon is handed to the player for free at the
     * start of a battle (0 = never issued, it must be bought).
     */
    val issuedFreeFromRound: Int = 0,
    /**
     * True for the automatic weapons (submachine gun, AK-47 and machine gun):
     * holding the up-swipe keeps firing one bullet per [burstIntervalMs] for
     * as long as the trigger is held, and the fire sound runs on one gapless
     * loop that starts with the hold and stops the instant it is released.
     */
    val autoFire: Boolean = false,
    val burstIntervalMs: Long = 0L,
    /** Legacy length of one burst; a held trigger is no longer cut by it. */
    val maxBurstMs: Long = 0L,
    /** True when the timer bomb needs its duration picker before it fires. */
    val needsTimerSelection: Boolean = false,
    /**
     * True only for the electric shock: holding the up-swipe drains the
     * enemy's health (see [ElectricShockRules]); it never reloads.
     */
    val electricShock: Boolean = false
) {
    SHORT_GUN(
        id = "shortGun",
        nameKey = "gunShotgun",
        powerPercent = 19,
        closeRangePowerPercent = 38,
        magazine = 6,
        price = 0,
        ownedByDefault = true,
        melee = false,
        fireSoundKey = "shortGun",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click"
    ),
    ASSAULT_GUN(
        id = "assaultGun",
        nameKey = "gunAssault",
        powerPercent = 22,
        closeRangePowerPercent = 44,
        magazine = 6,
        price = 0,
        ownedByDefault = true,
        melee = false,
        fireSoundKey = "assault",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click"
    ),
    PISTOL(
        id = "pistol",
        nameKey = "gunPistol",
        powerPercent = 25,
        closeRangePowerPercent = 50,
        magazine = 6,
        price = 450,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "pistol",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click"
    ),
    SNIPER(
        id = "sniper",
        nameKey = "gunSniper",
        powerPercent = 32,
        closeRangePowerPercent = 64,
        magazine = 6,
        price = 850,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "sniperRifle",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click"
    ),
    /**
     * The Gloo Wall Breaker: the one and only gun that can destroy a gloo
     * wall. Three consecutive shots from the 90%-100% band break any wall;
     * every other weapon - the sniper rifle included - bounces off it.
     *
     * It is a light utility firearm (it is bought to break barriers, not to
     * win a straight firefight), and it is available
     * from round one.
     */
    GLUE_BREAKER(
        id = "glueBreaker",
        nameKey = "gunGlueBreaker",
        powerPercent = 17,
        closeRangePowerPercent = 34,
        magazine = 6,
        price = 750,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "glueWallBreaker",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click"
    ),
    KNIFE(
        id = "knife",
        nameKey = "gunKnife",
        powerPercent = 16,
        closeRangePowerPercent = 32,
        magazine = 0,
        price = 220,
        ownedByDefault = false,
        melee = true,
        fireSoundKey = "knifeSwish",
        switchSoundKey = "knifeSwitch",
        hitSoundKey = "knifeHit",
        missSoundKey = "knifeMiss"
    ),
    SWORD(
        id = "sword",
        nameKey = "gunSword",
        powerPercent = 27,
        closeRangePowerPercent = 54,
        magazine = 0,
        price = 650,
        ownedByDefault = false,
        melee = true,
        fireSoundKey = "swordGlide",
        switchSoundKey = "swordSwitch",
        hitSoundKey = "swordHit",
        missSoundKey = "swordMiss"
    ),
    /**
     * The M10: a single-shot handgun in the pistol family. One swipe fires
     * one round, so it behaves exactly like the pistol and the sniper rifle.
     * It can be unlocked and bought from round one.
     */
    M10(
        id = "m10",
        nameKey = "gunM10",
        powerPercent = 21,
        closeRangePowerPercent = 42,
        magazine = 8,
        price = 600,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "m10",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click",
        unlockRound = 1
    ),
    /**
     * The AR-15: a semi-automatic rifle in the single-shot family. One swipe
     * fires one round, with its own recording. It can be bought from round one.
     */
    AR15(
        id = "ar15",
        nameKey = "gunAr15",
        powerPercent = 24,
        closeRangePowerPercent = 48,
        magazine = 10,
        price = 1000,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "ar15",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click",
        unlockRound = 1
    ),
    /**
     * The submachine gun: a long-range automatic weapon. Holding the
     * up-swipe fires one bullet every half second, and its sound runs on a
     * gapless loop with no silence between two rounds. Its forty-round
     * magazine is the largest of the fast automatics; it unlocks in round two.
     */
    SUBMACHINE_GUN(
        id = "submachineGun",
        nameKey = "gunSubmachineGun",
        powerPercent = 13,
        closeRangePowerPercent = 26,
        magazine = 40,
        price = 900,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "submachineGun",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click",
        unlockRound = 2,
        autoFire = true,
        burstIntervalMs = FireCadence.AUTO_FAST_MS,
        maxBurstMs = 5_000L
    ),
    /**
     * The AK-47: a long-range automatic rifle. It fires one bullet every
     * second and holds thirty rounds, with its looped sound carrying straight
     * from one shot into the next. It unlocks from round one.
     */
    AK47(
        id = "ak47",
        nameKey = "gunAk47",
        powerPercent = 29,
        closeRangePowerPercent = 58,
        magazine = 30,
        price = 1250,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "ak47",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click",
        unlockRound = 1,
        autoFire = true,
        burstIntervalMs = FireCadence.AUTO_MEDIUM_MS,
        maxBurstMs = 5_000L
    ),
    /**
     * The AWM long-range rifle. Inside the 20% to 40% proximity band, where
     * every other weapon misses, it still lands a precision hit worth
     * [DamageTable.AWM_LONG_RANGE_DAMAGE_HP] health points, and its heavy
     * close-range row is the full 80 point ceiling of the damage table.
     */
    AWM(
        id = "awm",
        nameKey = "gunAwm",
        powerPercent = 40,
        closeRangePowerPercent = 80,
        magazine = 5,
        price = 1200,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "awm",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click",
        unlockRound = 2,
        issuedFreeFromRound = 2
    ),
    /**
     * The machine gun: the heaviest automatic weapon. Its slower 1.5 second
     * beat buys the hardest per-bullet damage of the three automatics, and its
     * fifty-round magazine lets a held trigger keep the loop running longest.
     * It can be unlocked from round three, but it is never handed out free.
     */
    MACHINE_GUN(
        id = "machineGun",
        nameKey = "gunMachineGun",
        powerPercent = 35,
        closeRangePowerPercent = 70,
        magazine = 50,
        price = 1700,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "machineGun",
        switchSoundKey = "gunReload",
        hitSoundKey = "gunHit",
        missSoundKey = "click",
        unlockRound = 3,
        autoFire = true,
        burstIntervalMs = FireCadence.AUTO_SLOW_MS,
        maxBurstMs = 5_000L
    ),
    /**
     * The electric shock, listed with the machine guns. It unlocks once round
     * two is complete and costs 1500 coins. Holding the up-swipe shocks Robert
     * for as long as the hold lasts; Robert answers with his own shock.
     */
    ELECTRIC_SHOCK(
        id = "electricShock",
        nameKey = "gunElectricShock",
        powerPercent = 0,
        closeRangePowerPercent = 0,
        magazine = 0,
        price = 1500,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "magicalSound",
        switchSoundKey = "gunReload",
        hitSoundKey = "electricShock",
        missSoundKey = "click",
        unlockRound = 3,
        electricShock = true
    ),
    GRENADE(
        id = "grenade",
        nameKey = "gunGrenade",
        powerPercent = 40,
        closeRangePowerPercent = 40,
        magazine = 1,
        price = 600,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "grenade",
        switchSoundKey = "gunReload",
        hitSoundKey = "explosion",
        missSoundKey = "ammo",
        bomb = true,
        unlockRound = 3,
        issuedFreeFromRound = 3
    ),
    ATOM_BOMB(
        id = "atomBomb",
        nameKey = "gunAtomBomb",
        powerPercent = 60,
        closeRangePowerPercent = 60,
        magazine = 1,
        price = 2000,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "atomBomb",
        switchSoundKey = "gunReload",
        hitSoundKey = "explosion",
        missSoundKey = "ammo",
        bomb = true,
        unlockRound = 3
    ),
    TIMER_BOMB(
        id = "timerBomb",
        nameKey = "gunTimerBomb",
        powerPercent = 50,
        closeRangePowerPercent = 50,
        magazine = 1,
        price = 1700,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "timerLoop",
        switchSoundKey = "gunReload",
        hitSoundKey = "timerBomb",
        missSoundKey = "ammo",
        bomb = true,
        unlockRound = 3,
        needsTimerSelection = true
    ),
    MISSILE(
        id = "missile",
        nameKey = "gunMissile",
        // A direct missile hit destroys every remaining health point.
        powerPercent = 60,
        closeRangePowerPercent = 60,
        magazine = 0,
        price = MissileRules.MISSILE_PRICE,
        ownedByDefault = false,
        melee = false,
        fireSoundKey = "missile",
        // Switching to the missile plays the same gun-switch sound as every
        // other firearm.
        switchSoundKey = "gunReload",
        hitSoundKey = "explosion",
        missSoundKey = "missile",
        missileLauncher = true,
        unlockRound = Progression.MISSILE_UNLOCK_COMPLETED_ROUNDS + 1
    );

    /** Completed rounds required before this weapon can be bought (0 = none). */
    val requiredCompletedRounds: Int
        get() = (unlockRound - 1).coerceAtLeast(0)

    /** True for a gun that fires bullets (not melee, launcher, bomb or shock). */
    val isFirearm: Boolean
        get() = !melee && !missileLauncher && !bomb && !electricShock

    /**
     * Melee weapons are always "loaded"; firearms need a reload after their
     * magazine is empty. The missile never reloads: it is fed by missile
     * powers. A bomb carries exactly one charge per battle and never reloads.
     */
    val needsReload: Boolean
        get() = !melee && !missileLauncher && !bomb && !electricShock

    /** True when the weapon is a tracked launcher (missile or bomb). */
    val launcher: Boolean
        get() = missileLauncher || bomb

    /** The spoken reason while the weapon is still locked by progression. */
    val lockedKey: String
        get() = if (missileLauncher) "missileLocked" else "weaponLocked"

    companion object {
        /** The weapons every player owns without buying them. */
        fun defaults(): List<Weapon> = Weapon.entries.filter { it.ownedByDefault }

        /** The weapons that appear in the shop. */
        fun forSale(): List<Weapon> = Weapon.entries.filter { it.price > 0 }.sortedBy { it.price }

        /**
         * Every weapon listed in the shop: the paid ones and the two free guns
         * that can be disabled but never bought or removed.
         */
        fun shopListed(): List<Weapon> =
            Weapon.entries.filter { it.price > 0 || it.ownedByDefault }.sortedBy { it.price }

        /** Every automatic (hold to keep firing) weapon. */
        fun automatic(): List<Weapon> = Weapon.entries.filter { it.autoFire }

        /** Every bomb weapon, in the order they unlock. */
        fun bombs(): List<Weapon> = Weapon.entries.filter { it.bomb }

        /** Weapons handed out for free once [round] is reached. */
        fun issuedForRound(round: Int): List<Weapon> = Weapon.entries.filter {
            it.issuedFreeFromRound in 1..round
        }

        fun fromId(id: String?): Weapon? = Weapon.entries.firstOrNull { it.id == id }
    }
}

/** The five buyable, consumable defences of the game. */
enum class DefenceItem(
    val id: String,
    val nameKey: String,
    val pluralKey: String,
    val price: Int,
    val descriptionKey: String,
    val requiredCompletedRounds: Int = 0,
    /** Spoken reason while this defence is still locked by progression. */
    val lockedKey: String = "itemLocked"
) {
    MEDKIT(
        id = "medkit",
        nameKey = "medkit",
        pluralKey = "medkitsPlural",
        price = 250,
        descriptionKey = "defenceMedkitInfo"
    ),
    GLUE_WALL(
        id = "glueWall",
        nameKey = "glooWall",
        pluralKey = "glueWallsPlural",
        price = 550,
        descriptionKey = "defenceGlueWallInfo"
    ),
    COVER(
        id = "cover",
        nameKey = "cover",
        pluralKey = "coversPlural",
        price = 400,
        descriptionKey = "defenceCoverInfo"
    ),
    ANTI_MISSILE(
        id = "antiMissile",
        nameKey = "antiMissile",
        pluralKey = "antiMissilesPlural",
        price = MissileRules.ANTI_MISSILE_PRICE,
        descriptionKey = "defenceAntiMissileInfo",
        requiredCompletedRounds = Progression.ANTI_MISSILE_UNLOCK_COMPLETED_ROUNDS,
        lockedKey = "antiMissileLocked"
    ),
    /**
     * The bulletproof car. Bought once, it can be used in two battles before
     * it must be refuelled. It is usable from round two, after round one.
     */
    CAR(
        id = "car",
        nameKey = "car",
        pluralKey = "carsPlural",
        price = CarRules.PRICE,
        descriptionKey = "defenceCarInfo",
        requiredCompletedRounds = 1,
        lockedKey = "carLocked"
    ),
    /**
     * The anti-bomb explosive defence. Deployed while a bomb is in the air it
     * destroys that bomb with an explosion, exactly like the anti-missile
     * system destroys a missile. Robert carries one per game from round three
     * and deploys it automatically the moment he hears a bomb.
     */
    ANTI_BOMB(
        id = "antiBomb",
        nameKey = "antiBomb",
        pluralKey = "antiBombsPlural",
        price = BombRules.ANTI_BOMB_PRICE,
        descriptionKey = "defenceAntiBombInfo",
        requiredCompletedRounds = BombRules.FIRST_BOMB_ROUND - 1,
        lockedKey = "antiBombLocked"
    ),
    /**
     * "Lock the Opponent": freezes Robert completely for one minute. It is a
     * defensive/control item, so it stays usable while he is locked, it is
     * stackable like every other defence, and the lock never lifts early.
     */
    LOCK(
        id = "lockOpponent",
        nameKey = "lockOpponent",
        pluralKey = "lockOpponentsPlural",
        price = LockRules.PRICE,
        descriptionKey = "defenceLockInfo",
        requiredCompletedRounds = LockRules.REQUIRED_COMPLETED_ROUNDS,
        lockedKey = "lockOpponentLocked"
    );

    companion object {
        fun fromId(id: String?): DefenceItem? = DefenceItem.entries.firstOrNull { it.id == id }

        /** The two defences that intercept a projectile already in the air. */
        fun interceptors(): List<DefenceItem> = listOf(ANTI_MISSILE, ANTI_BOMB)

        /**
         * The purely defensive items, which stay usable while the opponent is
         * locked: the car, the two interceptors and the two barriers.
         */
        fun defensive(): List<DefenceItem> =
            listOf(CAR, ANTI_MISSILE, ANTI_BOMB, COVER, GLUE_WALL)
    }
}

/**
 * Optional automatic helpers that can be bought, enabled and disabled.
 *
 * Missile power is deliberately NOT an extension any more: it is a
 * stackable, consumable item (like a defence), so it is counted directly in
 * [Inventory.missilePowers] and bought through [Shop.buyMissilePower].
 */
enum class Extension(
    val id: String,
    val nameKey: String,
    val price: Int,
    val descriptionKey: String
) {
    AUTO_RELOAD("autoReload", "extAutoReload", 75, "extAutoReloadInfo"),
    AUTO_MEDKIT("autoMedkit", "extAutoMedkit", 140, "extAutoMedkitInfo"),
    AUTO_COVER("autoCover", "extAutoCover", 180, "extAutoCoverInfo"),
    AUTO_GLUE_WALL("autoGlueWall", "extAutoGlueWall", 200, "extAutoGlueWallInfo");

    companion object {
        fun fromId(id: String?): Extension? = Extension.entries.firstOrNull { it.id == id }
    }
}

enum class ExtensionState {
    NOT_OWNED,
    /** Bought but switched off: it must not trigger while disabled. */
    DISABLED,
    ENABLED
}

/**
 * Everything the player owns and carries: bought weapons, defence counts,
 * extension states, per-weapon enable switches and the weapon that was used
 * in the previous game.
 */
data class Inventory(
    val weaponIds: Set<String> = Weapon.defaults().map { it.id }.toSet(),
    val medikits: Int = 0,
    val glueWalls: Int = 0,
    val covers: Int = 0,
    val antiMissiles: Int = 0,
    /** Anti-bomb explosive defences, bought and stacked like every defence. */
    val antiBombs: Int = 0,
    val extensions: Map<String, ExtensionState> = emptyMap(),
    val lastWeaponId: String = Weapon.SHORT_GUN.id,
    /** Last selected running-speed level, restored at the next battle. */
    val lastRunSpeedId: String = RunSpeed.NORMAL.id,
    /**
     * Stacked missile powers. Like a defence, every missile launch consumes
     * exactly one, after which another must be bought for the next launch.
     */
    val missilePowers: Int = 0,
    /**
     * Stacked bomb powers. One power is consumed per atom or timer bomb;
     * grenades are free of power charges.
     */
    val bombPowers: Int = 0,
    /**
     * Set once the free anti-missile system of round four has been handed
     * out, so an old profile is upgraded exactly once and the reward can
     * never be claimed twice by re-entering a battle.
     */
    val antiMissileRewardGranted: Boolean = false,
    /**
     * Weapons switched off from the shop's Enable / Disable button. A
     * disabled weapon is never handed out at the start of a battle and can
     * never be revealed by a left or right swipe until it is enabled again.
     */
    val disabledWeapons: Set<String> = emptySet(),
    /** Bulletproof cars owned: at most one. */
    val cars: Int = 0,
    /** Battles the owned car can still be used in before a refuel. */
    val carGamesLeft: Int = 0,
    /** Stacked "Lock the Opponent" charges, one freezes him for a minute. */
    val lockCharges: Int = 0
) {
    fun ownsWeapon(weapon: Weapon): Boolean =
        weapon.ownedByDefault || weaponIds.contains(weapon.id)

    /** True while this owned weapon is switched on in the shop. */
    fun weaponEnabled(weapon: Weapon): Boolean = !disabledWeapons.contains(weapon.id)

    /** All owned battle weapons in switch order, including the missile. */
    fun ownedWeapons(): List<Weapon> = Weapon.entries.filter { ownsWeapon(it) }

    /** Owned weapons that are also enabled: the real battle rotation. */
    fun battleWeapons(): List<Weapon> = ownedWeapons().filter { weaponEnabled(it) }

    /** Owned and enabled weapons available in the given round. */
    fun battleWeapons(round: Int): List<Weapon> =
        battleWeapons().filter { it.unlockRound <= round }

    val hasMissilePower: Boolean
        get() = missilePowers > 0

    fun withMissilePowers(value: Int): Inventory =
        copy(missilePowers = value.coerceAtLeast(0))

    val hasBombPower: Boolean
        get() = bombPowers > 0

    fun withBombPowers(value: Int): Inventory =
        copy(bombPowers = value.coerceAtLeast(0))

    fun withWeaponEnabled(weapon: Weapon, enabled: Boolean): Inventory =
        if (enabled) {
            copy(disabledWeapons = disabledWeapons - weapon.id)
        } else {
            copy(disabledWeapons = disabledWeapons + weapon.id)
        }

    fun count(item: DefenceItem): Int = when (item) {
        DefenceItem.MEDKIT -> medikits
        DefenceItem.GLUE_WALL -> glueWalls
        DefenceItem.COVER -> covers
        DefenceItem.ANTI_MISSILE -> antiMissiles
        DefenceItem.CAR -> cars
        DefenceItem.ANTI_BOMB -> antiBombs
        DefenceItem.LOCK -> lockCharges
    }

    fun withCount(item: DefenceItem, value: Int): Inventory {
        val safe = value.coerceAtLeast(0)
        return when (item) {
            DefenceItem.MEDKIT -> copy(medikits = safe)
            DefenceItem.GLUE_WALL -> copy(glueWalls = safe)
            DefenceItem.COVER -> copy(covers = safe)
            DefenceItem.ANTI_MISSILE -> copy(antiMissiles = safe)
            // One car at most. Without a car there is no fuel either.
            DefenceItem.CAR -> copy(
                cars = safe.coerceAtMost(1),
                carGamesLeft = if (safe > 0) carGamesLeft else 0
            )
            DefenceItem.ANTI_BOMB -> copy(antiBombs = safe)
            DefenceItem.LOCK -> copy(lockCharges = safe)
        }
    }

    fun extensionState(extension: Extension): ExtensionState =
        extensions[extension.id] ?: ExtensionState.NOT_OWNED

    fun extensionOwned(extension: Extension): Boolean =
        extensionState(extension) != ExtensionState.NOT_OWNED

    fun extensionEnabled(extension: Extension): Boolean =
        extensionState(extension) == ExtensionState.ENABLED

    fun withExtension(extension: Extension, state: ExtensionState): Inventory =
        copy(extensions = extensions.toMutableMap().also { it[extension.id] = state })

    /** The weapon the player used last game, if they still own it. */
    fun rememberedWeapon(): Weapon? {
        val remembered = Weapon.fromId(lastWeaponId) ?: return null
        return if (ownsWeapon(remembered)) remembered else null
    }

    /**
     * The weapon the next battle starts with. A disabled weapon is skipped,
     * so disabling one really hides it from the battle rotation.
     */
    fun startingWeapon(): Weapon {
        val remembered = rememberedWeapon()
        if (remembered != null && weaponEnabled(remembered)) return remembered
        return battleWeapons().firstOrNull { it != Weapon.MISSILE } ?: Weapon.SHORT_GUN
    }
}

/**
 * Result of a shop transaction. [inventory] and [coins] are the new values and
 * [reasonKey] is a translation key describing why a transaction was refused.
 */
data class ShopResult(
    val success: Boolean,
    val coins: Int,
    val inventory: Inventory,
    val reasonKey: String? = null
)

/**
 * Pure shop rules. Keeping the money and inventory arithmetic here makes every
 * purchase testable and guarantees the shop, the battle and the automatic
 * extensions all use exactly the same prices.
 */
object Shop {
    /**
     * The translation key of the little sentence shown under a locked shop
     * item. [completedRounds] is the number of rounds that must be finished
     * before the item unlocks (0 = it is available from the beginning).
     */
    fun unlockReasonKey(completedRounds: Int): String = when (completedRounds) {
        1 -> "unlockAfterRound1"
        2 -> "unlockAfterRound2"
        3 -> "unlockAfterRound3"
        4 -> "unlockAfterRound4"
        else -> "unlockAfterRound1"
    }

    /** True while [completedRounds] is not enough to unlock this item. */
    fun isLocked(requiredCompletedRounds: Int, completedRounds: Int): Boolean =
        requiredCompletedRounds > 0 && completedRounds < requiredCompletedRounds

    /**
     * Hands out the one free anti-missile system that completing round three
     * pays. Returns null when the reward was already claimed, so callers can
     * add exactly one interceptor to the displayed reward bundle.
     */
    fun claimAntiMissileReward(inventory: Inventory): Inventory? {
        if (inventory.antiMissileRewardGranted) return null
        return inventory.copy(
            antiMissiles = inventory.antiMissiles + 1,
            antiMissileRewardGranted = true
        )
    }

    /**
     * Buys a weapon. [completedRounds] is the profile's lifetime completed
     * round count; locked weapons (the AWM from round two, the bombs from
     * round three, the machine gun from round three and the missile after
     * round two) check it before anything else.
     * The app always passes the real value; the permissive default only keeps
     * pure price tests of the unrestricted weapons free of progression setup.
     */
    fun buyWeapon(
        inventory: Inventory,
        coins: Int,
        weapon: Weapon,
        completedRounds: Int = Int.MAX_VALUE
    ): ShopResult {
        if (completedRounds < weapon.requiredCompletedRounds) {
            return ShopResult(false, coins, inventory, weapon.lockedKey)
        }
        if (inventory.ownsWeapon(weapon)) {
            return ShopResult(false, coins, inventory, "alreadyOwned")
        }
        if (coins < weapon.price) {
            return ShopResult(false, coins, inventory, "notEnoughCoins")
        }
        return ShopResult(
            success = true,
            coins = coins - weapon.price,
            inventory = inventory.copy(
                weaponIds = inventory.weaponIds + weapon.id,
                disabledWeapons = inventory.disabledWeapons - weapon.id
            )
        )
    }

    /**
     * Hands a weapon out for free (the round-two AWM issue and the
     * round-three grenade reward). Never charges coins and never re-adds a
     * weapon the player already owns.
     */
    fun grantWeapon(inventory: Inventory, weapon: Weapon): ShopResult {
        if (inventory.ownsWeapon(weapon)) {
            return ShopResult(false, 0, inventory, "alreadyOwned")
        }
        return ShopResult(
            success = true,
            coins = 0,
            inventory = inventory.copy(
                weaponIds = inventory.weaponIds + weapon.id,
                disabledWeapons = inventory.disabledWeapons - weapon.id
            )
        )
    }

    /**
     * Buys exactly one missile power. Powers stack like defences: the player
     * may buy several in a row and every missile launch consumes only one.
     */
    fun buyMissilePower(
        inventory: Inventory,
        coins: Int,
        completedRounds: Int
    ): ShopResult {
        if (completedRounds < Progression.MISSILE_UNLOCK_COMPLETED_ROUNDS) {
            return ShopResult(false, coins, inventory, "missileLocked")
        }
        if (coins < MissileRules.MISSILE_POWER_PRICE) {
            return ShopResult(false, coins, inventory, "notEnoughCoins")
        }
        return ShopResult(
            success = true,
            coins = coins - MissileRules.MISSILE_POWER_PRICE,
            inventory = inventory.withMissilePowers(inventory.missilePowers + 1)
        )
    }

    /** Removes exactly one missile power (a launch, or a shop removal). */
    fun consumeMissilePower(inventory: Inventory): Inventory =
        inventory.withMissilePowers(inventory.missilePowers - 1)

    /**
     * Buys exactly one bomb power. Each bomb power costs 850 coins and allows
     * one atom or timer bomb throw. Grenades do not consume a power.
     * Similar to missile power, it stacks.
     */
    fun buyBombPower(
        inventory: Inventory,
        coins: Int,
        completedRounds: Int
    ): ShopResult {
        if (completedRounds < BombRules.FIRST_BOMB_ROUND - 1) {
            return ShopResult(false, coins, inventory, "bombPowerLocked")
        }
        if (coins < BombRules.BOMB_POWER_PRICE) {
            return ShopResult(false, coins, inventory, "notEnoughCoins")
        }
        return ShopResult(
            success = true,
            coins = coins - BombRules.BOMB_POWER_PRICE,
            inventory = inventory.withBombPowers(inventory.bombPowers + 1)
        )
    }

    /** Removes exactly one bomb power (a throw, or shop removal). */
    fun consumeBombPower(inventory: Inventory): Inventory =
        inventory.withBombPowers(inventory.bombPowers - 1)

    fun removeWeapon(inventory: Inventory, coins: Int, weapon: Weapon): ShopResult {
        if (weapon.ownedByDefault || !inventory.ownsWeapon(weapon)) {
            return ShopResult(false, coins, inventory, "cannotRemoveWeapon")
        }
        return ShopResult(
            success = true,
            coins = coins,
            inventory = inventory.copy(
                weaponIds = inventory.weaponIds - weapon.id,
                disabledWeapons = inventory.disabledWeapons - weapon.id,
                lastWeaponId = if (inventory.lastWeaponId == weapon.id) {
                    Weapon.SHORT_GUN.id
                } else {
                    inventory.lastWeaponId
                }
            )
        )
    }

    /**
     * The Enable / Disable switch under every weapon row. It is free, works
     * like an extension, and a disabled weapon is never revealed by a left or
     * right swipe in the game screen until it is enabled again.
     */
    fun toggleWeaponEnabled(inventory: Inventory, coins: Int, weapon: Weapon): ShopResult {
        if (!inventory.ownsWeapon(weapon)) {
            return ShopResult(false, coins, inventory, "notOwned")
        }
        // At least one gun always stays in the battle rotation.
        if (weapon.isFirearm && inventory.weaponEnabled(weapon) &&
            inventory.battleWeapons().none { it != weapon && it.isFirearm }
        ) {
            return ShopResult(false, coins, inventory, "keepOneGunEnabled")
        }
        val enabled = !inventory.weaponEnabled(weapon)
        val next = inventory.withWeaponEnabled(weapon, enabled)
        val fixed = if (!enabled && next.lastWeaponId == weapon.id) {
            next.copy(lastWeaponId = next.startingWeapon().id)
        } else {
            next
        }
        return ShopResult(true, coins, fixed)
    }

    /**
     * Defences are repeatable: every purchase adds exactly one item. The car
     * is the exception: there is only one, and buying it starts a full tank.
     */
    fun buyDefence(
        inventory: Inventory,
        coins: Int,
        item: DefenceItem,
        completedRounds: Int = Int.MAX_VALUE
    ): ShopResult {
        if (completedRounds < item.requiredCompletedRounds) {
            return ShopResult(false, coins, inventory, item.lockedKey)
        }
        if (item == DefenceItem.CAR && inventory.cars > 0) {
            return ShopResult(false, coins, inventory, "alreadyOwned")
        }
        if (coins < item.price) {
            return ShopResult(false, coins, inventory, "notEnoughCoins")
        }
        val bought = if (item == DefenceItem.CAR) {
            inventory.copy(cars = 1, carGamesLeft = CarRules.GAMES_PER_FUEL)
        } else {
            inventory.withCount(item, inventory.count(item) + 1)
        }
        return ShopResult(success = true, coins = coins - item.price, inventory = bought)
    }

    /**
     * Refuels the owned car for [CarRules.REFUEL_PRICE] coins. Only an empty
     * tank can be refuelled, and it is good for [CarRules.GAMES_PER_FUEL] games.
     */
    fun refuelCar(inventory: Inventory, coins: Int): ShopResult {
        if (inventory.cars <= 0) return ShopResult(false, coins, inventory, "notOwned")
        if (inventory.carGamesLeft > 0) return ShopResult(false, coins, inventory, "carHasFuel")
        if (coins < CarRules.REFUEL_PRICE) {
            return ShopResult(false, coins, inventory, "notEnoughCoins")
        }
        return ShopResult(
            success = true,
            coins = coins - CarRules.REFUEL_PRICE,
            inventory = inventory.copy(carGamesLeft = CarRules.GAMES_PER_FUEL)
        )
    }

    /** Uses up one game of fuel when the car is deployed in a battle. */
    fun useCarGame(inventory: Inventory): Inventory =
        inventory.copy(carGamesLeft = CarRules.gamesAfterDeploy(inventory.carGamesLeft))

    /** A destroyed car is gone for good. */
    fun loseCar(inventory: Inventory): Inventory =
        inventory.copy(cars = 0, carGamesLeft = 0)

    fun buyExtension(
        inventory: Inventory,
        coins: Int,
        extension: Extension
    ): ShopResult {
        if (inventory.extensionOwned(extension)) {
            return ShopResult(false, coins, inventory, "alreadyOwned")
        }
        if (coins < extension.price) {
            return ShopResult(false, coins, inventory, "notEnoughCoins")
        }
        return ShopResult(
            success = true,
            coins = coins - extension.price,
            inventory = inventory.withExtension(extension, ExtensionState.ENABLED)
        )
    }

    fun removeExtension(inventory: Inventory, coins: Int, extension: Extension): ShopResult {
        if (!inventory.extensionOwned(extension)) {
            return ShopResult(false, coins, inventory, "notOwned")
        }
        return ShopResult(
            success = true,
            coins = coins,
            inventory = inventory.withExtension(extension, ExtensionState.NOT_OWNED)
        )
    }

    /** Switches an owned extension between enabled and disabled. */
    fun toggleExtension(inventory: Inventory, coins: Int, extension: Extension): ShopResult {
        val current = inventory.extensionState(extension)
        if (current == ExtensionState.NOT_OWNED) {
            return ShopResult(false, coins, inventory, "notOwned")
        }
        val next = if (current == ExtensionState.ENABLED) {
            ExtensionState.DISABLED
        } else {
            ExtensionState.ENABLED
        }
        return ShopResult(
            success = true,
            coins = coins,
            inventory = inventory.withExtension(extension, next)
        )
    }

    /** Removes one used defence. */
    fun consume(inventory: Inventory, item: DefenceItem): Inventory =
        inventory.withCount(item, inventory.count(item) - 1)

    /** Adds a defence without charging for it (used by rewards and migration). */
    fun grant(inventory: Inventory, item: DefenceItem, amount: Int = 1): Inventory =
        inventory.withCount(item, inventory.count(item) + amount.coerceAtLeast(0))

    /** Applies a whole reward bundle without charging for it. */
    fun grantBundle(inventory: Inventory, bundle: RewardBundle): Inventory {
        var next = inventory
        for ((item, amount) in bundle.items) {
            next = grant(next, item, amount)
        }
        return next
    }
}
