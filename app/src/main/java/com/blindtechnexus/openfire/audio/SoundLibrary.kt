package com.blindtechnexus.openfire.audio

/**
 * The sound catalogue of the game.
 *
 * One place says which asset belongs to which sound key, which keys are held
 * loops, which are projectiles with an approach cue and which are genuinely
 * stereo recordings. `AudioManager` re-exports the same tables under its old
 * names, so every existing call site keeps working while the native engine
 * reads this object directly.
 *
 * The asset list is checked by `.github/scripts/check_sound_and_string_consistency.py`:
 * every file of `assets/sounds` is used here, and every key used in the Kotlin
 * sources is declared here.
 */
object SoundLibrary {

    /** Sound key -> asset file name, inside `assets/sounds`. */
    val SOUND_FILES: Map<String, String> = mapOf(
        "click" to "click.ogg",
        "gunReload" to "gun-reload.ogg",
        "assault" to "assault-gun.ogg",
        "pistol" to "pistol-gun.ogg",
        "shortGun" to "shotgun.ogg",
        "sniperRifle" to "sniper-rifle.ogg",
        "glueWallBreaker" to "glue-wall-breaker.ogg",
        "m10" to "m10-gun.ogg",
        "ar15" to "ar-15.ogg",
        "awm" to "awm-rifle.ogg",
        // Automatic weapons: the held loop and the single-shot copy share the recording.
        "submachineGun" to "submachine-gun.ogg",
        "ak47" to "ak-47-gun.ogg",
        "machineGun" to "machine-gun.ogg",
        "submachineGunShot" to "submachine-gun.ogg",
        "ak47Shot" to "ak-47-gun.ogg",
        "machineGunShot" to "machine-gun.ogg",
        "knifeHit" to "knife-hit.ogg",
        "knifeMiss" to "knife-miss.ogg",
        "knifeSwitch" to "knife-switch.ogg",
        "knifeSwish" to "knife-switch.ogg",
        "swordHit" to "sword-hit.ogg",
        "swordMiss" to "sword-miss.ogg",
        "swordSwitch" to "sword-switch.ogg",
        "swordGlide" to "sword-switch.ogg",
        "glueSound" to "glue-sound.ogg",
        "glueHit" to "glue-hit.ogg",
        "glueWallCrack" to "glue-wall-crack.ogg",
        "wall" to "wall-hit.ogg",
        "cover" to "cover.ogg",
        "coverHit" to "cover-hit.ogg",
        "coverCrack" to "cover-crack.ogg",
        "medkit" to "medkit.ogg",
        "gunHit" to "gun-hit.ogg",
        "enemyHit" to "enemy-hit.ogg",
        "playerHit" to "player-hit.ogg",
        "ammo" to "ammo.ogg",
        "spend" to "spend.ogg",
        "jump" to "jump.ogg",
        "land" to "jump.ogg",
        "airplane" to "airplane.ogg",
        "airplaneEngine" to "airplane-engine.ogg",
        "parachute" to "parachute.ogg",
        "win" to "win.ogg",
        "lose" to "lose.ogg",
        "music" to "music.ogg",
        "walk" to "walk.ogg",
        "playerWalk" to "walk.ogg",
        "enemyWalk" to "walk.ogg",
        "missile" to "missile.ogg",
        "explosion" to "explosion.ogg",
        "atomBomb" to "atom-bomb.ogg",
        "grenade" to "grenade.ogg",
        "timerLoop" to "timer-countdown.ogg",
        "timerBomb" to "explosion.ogg",
        "levelComplete" to "level-complete.ogg",
        "roundComplete" to "round-complete.ogg",
        "carBrake" to "car-brake.ogg",
        "carStart" to "car-start.ogg",
        "carEngine" to "car-engine.ogg",
        "carMoving" to "car-moving.ogg",
        "carDoorOpen" to "car-door-open.ogg",
        "carDoorClose" to "car-door-close.ogg",
        "carCrash" to "car-crash.ogg",
        "electricShock" to "electric-shock.ogg",
        "magicalSound" to "magical-sound.ogg"
    )

    /** Auto-fire key -> the loop the shot belongs to. */
    val AUTO_LOOP_OF: Map<String, String> = mapOf(
        "submachineGunShot" to "submachineGun",
        "ak47Shot" to "ak47",
        "machineGunShot" to "machineGun"
    )

    /** The three continuous-fire weapons. */
    val AUTO_LOOP_KEYS: Set<String> = setOf("submachineGun", "ak47", "machineGun")

    /** The single-shot copies of the automatic weapons. */
    val AUTO_SHOT_KEYS: Set<String> = AUTO_LOOP_OF.keys

    /** Every key an automatic weapon can be heard with. */
    val AUTO_WEAPON_KEYS: Set<String> = AUTO_LOOP_KEYS + AUTO_SHOT_KEYS

    /**
     * Held loops of the firing channels: the automatic weapons and the shock
     * hum, plus the moving loop of the car. Exactly the keys the game starts
     * through `startWeaponLoop` and stops through `stopWeaponLoops`.
     */
    val HOLD_LOOP_KEYS: Set<String> = AUTO_LOOP_KEYS + setOf("magicalSound", "carMoving")

    /**
     * The loops of the car that are not firing loops: the idle engine note.
     * They are held loops of the player, but they are never cut by a weapon
     * clean-up, only by [CAR_KEYS].
     */
    val CAR_IDLE_LOOP_KEYS: Set<String> = setOf("carEngine", "electricShock")

    /** Footstep tracks: each fighter walks on his own loop of the same take. */
    val WALK_KEYS: Set<String> = setOf("walk", "playerWalk", "enemyWalk")

    /**
     * Start offsets of the footstep loops. Both fighters use the same walk
     * recording, so starting both at zero would phase-lock them into one
     * source; a different offset keeps them two distinct walkers.
     */
    val WALK_START_OFFSET_MS: Map<String, Long> = mapOf(
        "playerWalk" to 0L,
        "enemyWalk" to 3_600L,
        "walk" to 7_200L
    )

    /** Every key that belongs to a weapon. Screen changes stop all of them. */
    val WEAPON_SOUND_KEYS: Set<String> = setOf(
        "assault", "pistol", "shortGun", "sniperRifle", "glueWallBreaker", "awm",
        "m10", "ar15", "submachineGun", "ak47", "machineGun",
        "submachineGunShot", "ak47Shot", "machineGunShot",
        "knifeSwish", "knifeMiss", "swordGlide", "swordMiss",
        "magicalSound", "electricShock"
    )

    /** Keys of the car sounds. They stop together when the battle ends. */
    val CAR_KEYS: Set<String> = setOf(
        "carBrake", "carStart", "carEngine", "carMoving",
        "carDoorOpen", "carDoorClose", "carCrash"
    )

    /** The projectiles that get the approach cue. */
    val PROJECTILE_KEYS: Set<String> = setOf("missile", "grenade", "atomBomb", "timerLoop")

    /** Recordings that are genuinely stereo and keep their own image. */
    private val STEREO_RECORDINGS: Set<String> = setOf(
        "music", "carMoving", "carEngine", "airplane", "airplaneEngine"
    )

    /** Asset file of [key], or null when the key is unknown. */
    fun fileOf(key: String): String? = SOUND_FILES[key]

    /** Every distinct asset file, in catalogue order. */
    fun files(): List<String> = SOUND_FILES.values.distinct()

    /** True when [key] is a projectile with a rising approach cue. */
    fun isProjectile(key: String): Boolean = key in PROJECTILE_KEYS

    /** True when the recording of [key] is stereo. */
    fun isStereoRecording(key: String): Boolean = key in STEREO_RECORDINGS

    /** True when [key] is a held loop. */
    fun isLoopKey(key: String): Boolean =
        key in HOLD_LOOP_KEYS || key in WALK_KEYS || key == "missile" ||
            key == "timerLoop" || key in CAR_KEYS

    /** The measured length of a sound that drives a game timer, in milliseconds. */
    fun durationMsOf(key: String): Long = com.blindtechnexus.openfire.model.SoundDurations.forKey(key)
}
