package com.blindtechnexus.openfire.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure volume arithmetic shared by every audio channel.
 *
 * Keeping this logic in one small, testable object is what prevents the
 * "slider silently reverts to a fixed level" bug and keeps one clear mix
 * hierarchy:
 *
 *  - [speech] is the reference of every announcement. The TTS setting is
 *             returned as it is, and the engine's own speech output is
 *             normalised to [SPEECH_REFERENCE] of full scale (see
 *             [speechInput] / [speechOutput]), so the stored setting is
 *             always the level the user really hears.
 *  - [music]  is trimmed under the speech level by [NON_SPEECH_UNDER_SPEECH],
 *             so the background tune never buries an announcement.
 *  - [sfx]    plays at the SAME level as speech at the same slider position
 *             ([SFX_AT_SPEECH_LEVEL]), and every loud weapon - machine gun,
 *             submachine gun, grenade and atom bomb - is lifted to that level
 *             as well ([LOUD_BUS_GAIN]). A machine gun is therefore exactly as
 *             audible as the voice that announces the battle, and moving the
 *             SFX slider scales it once, without any hidden trim.
 *  - walking footsteps take the same SFX level ([walkLevel]); they are never
 *    one bus quieter than the rest of the fight.
 *  - [busGainFor] is the per-sound trim of the mixer. Loud weapons sit at
 *    full scale, a hit marker sits slightly above them, and everything else
 *    is untouched, so feedback of a landed shot is never buried.
 *  - walking helpers scale their playback rate with the real movement speed
 *    inside a narrow, natural window and pan them only when the walker is
 *    genuinely to the side. A fighter running straight at the listener (or
 *    straight away) stays perfectly centred.
 *  - [distanceGain] is the one distance curve of the game: a sound from far
 *    away starts low and swells continuously as the source closes in, and
 *    fades again as it recedes. Every positional sound - the enemy's
 *    footsteps, his gun fire, his explosions - follows it, so approaching
 *    and receding are heard unmistakably.
 */
object VolumeMixer {
    /**
     * No bus is trimmed down relative to another: one slider position means
     * one level for speech and for the effects.
     */
    const val SPEECH_PRIORITY = 1.0f

    /** Non-speech buses are not cut under speech any more. */
    const val NON_SPEECH_UNDER_SPEECH = 1f

    /** Speech and effects share the same nominal level. */
    const val SFX_AT_SPEECH_LEVEL = 1.0f

    /**
     * Level the speech engine voice is normalised to. Engines that report a
     * full-scale utterance still come out at the level of the sound effects,
     * so a machine gun and the voice that describes it are equally audible.
     */
    const val SPEECH_REFERENCE = 1.0f

    /**
     * Music sits [MUSIC_TRIM] under speech so the theme never covers an
     * announcement, and it is the only trimmed bus of the mix.
     */
    const val MUSIC_TRIM = 0.75f

    /** No constant background gain is hidden behind the music slider. */
    const val MUSIC_BACKGROUND_GAIN = 1.0f

    /** Ordinary weapon fire plays at the unmodified SFX level. */
    const val WEAPON_BUS_GAIN = 1.0f

    /**
     * The loud weapons: the machine gun, the submachine gun, the grenade, the
     * atom bomb and the missile. Their base level is lifted to full scale
     * ([LOUD_BUS_GAIN]) so they can never be quieter than the speech, whatever
     * the distance of the source and whatever the SFX slider says; the slider
     * still scales them exactly once through [mix].
     */
    const val LOUD_BUS_GAIN = 1.0f

    /**
     * Every sound that must stay at the speech level: the four weapons the
     * player complained about (machine gun, submachine gun, grenade and atom
     * bomb) plus the missile and the general blast recordings they share.
     */
    val LOUD_SFX_KEYS: Set<String> = setOf(
        "machineGun", "machineGunShot",
        "submachineGun", "submachineGunShot",
        "grenade", "atomBomb", "missile",
        "explosion", "timerBomb"
    )

    /** Impact trim: hits play at the full SFX level the player selected. */
    const val HIT_BUS_GAIN = 1.00f

    /**
     * Per-asset loudness compensation, measured on the shipped recordings.
     *
     * The car recordings were mastered much quieter than the guns: the moving
     * loop sits at 0.119 RMS, the idle engine at 0.093 and the door-open at
     * 0.012, against 0.205 for the pistol. With every key played at the same
     * nominal level that made the car almost inaudible - the player heard a
     * faint hum where a car should be. Each entry is the measured RMS of the
     * recording relative to the 0.15 reference, turned into the gain that
     * brings that recording up to the reference; the values are capped so no
     * recording is ever lifted more than about four times (+12 dB) and a loud
     * one is never cut.
     */
    val ASSET_GAIN_KEYS: Map<String, Float> = mapOf(
        // Moving loop: 0.119 RMS -> about +1.9 dB.
        "carMoving" to 1.26f,
        "enemyCarMoving" to 1.26f,
        // Idle engine: 0.093 RMS -> about +4.2 dB.
        "carEngine" to 1.62f,
        "enemyCarEngine" to 1.62f,
        // Ignition take: 0.027 RMS -> about +12 dB, the cap.
        "carStart" to 4.0f,
        // Tyres: 0.080 RMS -> about +5.5 dB.
        "carBrake" to 1.88f,
        // Doors: the opening take is almost silent (0.012 RMS), the closing
        // one is healthy (0.048 RMS).
        "carDoorOpen" to 4.0f,
        "carDoorClose" to 2.0f
    )

    /**
     * The mix gain of one recording's own loudness. Keys without a measured
     * entry play exactly as recorded.
     */
    fun assetGainFor(key: String): Float =
        (ASSET_GAIN_KEYS[key] ?: 1f).coerceIn(0.5f, 4f)

    /** Pans smaller than this are centred: straight motion never wobbles. */
    const val PAN_DEAD_ZONE = 0.12f

    /** Only part of the geometric pan is used, so sides stay gentle. */
    const val PAN_STRENGTH = 0.85f

    /** Movement at (or above) this speed counts as fast running. */
    const val FAST_RUN_SPEED_MPS = 8f

    /** Footstep volume refresh cadence while walking / running fast. */
    const val WALK_UPDATE_INTERVAL_MS = 500L
    const val WALK_UPDATE_FAST_INTERVAL_MS = 250L

    /**
     * The natural window of every playback rate. A recording is only ever
     * played inside this window, so no sound can be shifted into the shrill,
     * chipped pitch the game must never produce. The window is wide enough for
     * three clearly distinct running levels and narrow enough that none of them
     * ever sounds chipped; the native engine enforces the same bounds.
     */
    const val MIN_RATE = 0.70f
    const val MAX_RATE = 1.60f

    /** The three fixed footstep rates of the running levels. */
    const val SLOW_FOOTSTEP_RATE = 0.78f
    const val NORMAL_FOOTSTEP_RATE = 1.00f
    const val FAST_FOOTSTEP_RATE = 1.45f

    /**
     * The keys of every weapon that fires a shot. They play at the same level
     * as the impacts, so the gun that caused a hit is never quieter than its
     * marker.
     */
    val WEAPON_BUS_KEYS: Set<String> = setOf(
        "assault", "pistol", "shortGun", "sniperRifle", "glueWallBreaker",
        "awm", "m10", "ar15",
        "submachineGun", "ak47", "machineGun",
        "submachineGunShot", "ak47Shot", "machineGunShot",
        // Flight sounds of the launchers are weapon audio as well.
        "missile", "grenade", "atomBomb"
    )

    /**
     * The keys of every impact: these are mixed on the hit bus, so the player
     * never misses the feedback of a landed shot.
     */
    val HIT_BUS_KEYS: Set<String> = setOf(
        "gunHit", "enemyHit", "playerHit", "knifeHit", "swordHit",
        "glueHit", "coverHit", "coverCrack", "glueWallCrack",
        "explosion", "timerBomb"
    )

    fun clamp(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    /**
     * Effective music player volume for a stored 0..1 music setting. Music
     * is the only bus trimmed under the announcements.
     */
    fun music(setting: Float): Float =
        clamp(clamp(setting) * MUSIC_BACKGROUND_GAIN * MUSIC_TRIM)

    /** Effective speech volume for a stored 0..1 TTS setting. */
    fun speech(setting: Float): Float = clamp(setting * SPEECH_REFERENCE)

    /**
     * Level handed to the speech engine. The engine receives the selected
     * level exactly, so the stored setting is the level the user hears.
     */
    fun speechInput(setting: Float): Float = clamp(setting)

    /**
     * Relative loudness of the speech voice compared with the effects. Both
     * buses are at the same nominal scale, so the announcement and the battle
     * are equally audible; the value is exposed for tests and for the guide.
     */
    fun speechOverSfx(setting: Float = 1f): Float {
        val sfx = sfx(setting, 1f, 1f)
        return if (sfx <= 0.0001f) 1f else speech(setting) / sfx
    }

    /**
     * Effective SFX volume for a base volume and a 0..1 distance gain. The
     * player's SFX slider scales the whole bus exactly once.
     */
    fun sfx(setting: Float, base: Float, distanceGain: Float): Float =
        clamp(clamp(base) * clamp(setting) * clamp(distanceGain) * NON_SPEECH_UNDER_SPEECH)

    /**
     * Walking plays at the SFX level itself, so footsteps are as clear as the
     * weapons of the fight and follow the same slider.
     */
    fun walkLevel(setting: Float, distanceGain: Float, speedGain: Float = 1f): Float =
        clamp(sfx(setting, 1f, distanceGain) * runVolumeGain(speedGain))

    /**
     * Mixer trim of one sound key. The loud weapons are lifted to full scale,
     * the impacts sit at full scale too, and everything else is untouched.
     */
    fun busGainFor(key: String): Float = when {
        key in LOUD_SFX_KEYS -> LOUD_BUS_GAIN
        key in HIT_BUS_KEYS -> HIT_BUS_GAIN
        key in WEAPON_BUS_KEYS -> WEAPON_BUS_GAIN
        else -> 1.0f
    }

    /**
     * True when [key] is one of the loud weapons that must be audible at the
     * speech level (machine gun, submachine gun, grenade, atom bomb).
     */
    fun isLoudSfx(key: String): Boolean = key in LOUD_SFX_KEYS

    /**
     * The complete SFX gain of one sound: the player's setting, the sound's
     * own base volume, the distance gain of the 3D position and its bus trim.
     * One slider position means one level for every bus.
     */
    fun mix(key: String, setting: Float, base: Float, distanceGain: Float): Float =
        // The one clamp sits at the very end: the asset lift of a quiet
        // recording has to happen BEFORE it, or a full-scale setting would
        // swallow the lift and the car would stay as quiet as its master.
        // The returned value still never leaves 0..1, so the engine is safe.
        (sfx(setting, base, distanceGain) * busGainFor(key) * assetGainFor(key))
            .let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }

    /**
     * Stereo pan (-1 full left, 0 centre, +1 full right) for a sound that
     * is [lateral] metres to the side at [distance] metres. Anything inside
     * the head-on cone is centred exactly, so a fighter moving straight
     * forward or backward never drifts between the ears.
     */
    fun panFor(lateral: Float, distance: Float): Float {
        val safeDistance = if (distance.isNaN()) 0f else distance.coerceAtLeast(0f)
        if (lateral.isNaN() || safeDistance <= 0.01f) return 0f
        val raw = (lateral / safeDistance).coerceIn(-1f, 1f) * PAN_STRENGTH
        return if (kotlin.math.abs(raw) < PAN_DEAD_ZONE) 0f else raw.coerceIn(-1f, 1f)
    }

    /**
     * Constant-power left/right gains for [pan], normalised so a centred
     * sound plays at full volume in both ears — the same loudness as the
     * mono speech voice — instead of dipping the way the raw sin/cos law
     * does. This is what makes head-on combat sound clean with and
     * without earphones instead of harsh and over-mixed.
     */
    fun panGains(pan: Float): Pair<Float, Float> {
        val safePan = if (pan.isNaN()) 0f else pan.coerceIn(-1f, 1f)
        val angle = (safePan + 1f) * (Math.PI.toFloat() / 4f)
        val normalize = sqrt(2f)
        val left = (cos(angle) * normalize).coerceIn(0f, 1f)
        val right = (sin(angle) * normalize).coerceIn(0f, 1f)
        return Pair(left, right)
    }

    /**
     * Distance gain (0..1) for every positional sound: almost inaudible at
     * the far edge of the arena and swelling continuously as the source
     * closes in, then fading again while it recedes. The square curve keeps
     * far sounds present but unmistakably distant and close sounds clear and
     * loud, which is exactly how an approaching or retreating enemy is
     * heard in the real world.
     */
    fun distanceGain(distance: Float, maxRange: Float): Float {
        val safeRange = maxRange.coerceAtLeast(1f)
        val safeDistance = if (distance.isNaN()) 0f else distance.coerceIn(0f, safeRange)
        val proximity = (1f - safeDistance / safeRange).coerceIn(0f, 1f)
        return (DISTANCE_FLOOR + proximity * proximity * (1f - DISTANCE_FLOOR))
            .coerceIn(DISTANCE_FLOOR, 1f)
    }

    /**
     * The quietest a positional sound ever becomes at the far edge. A far
     * sound stays present but unmistakably distant, and the loud weapons are
     * separately lifted to the speech level ([LOUD_SFX_KEYS]), so a machine gun
     * or an atom bomb fired from the opposite corner is still clearly audible
     * without smearing the whole battlefield into one loud mix.
     */
    const val DISTANCE_FLOOR = 0.08f

    /** Footsteps use the very same distance curve as everything else. */
    fun walkDistanceGain(distance: Float, maxRange: Float): Float =
        distanceGain(distance, maxRange)

    /**
     * Playback rate of the enemy's footsteps. He uses the same recording as
     * the player, so a small, deliberate rate difference keeps the two loops
     * from ever locking into the same beat while both fighters walk.
     */
    fun enemyWalkRate(speedMps: Float): Float =
        (walkRate(speedMps) * 0.97f).coerceIn(MIN_RATE, MAX_RATE)

    /**
     * Walking-loop volume from the current distance, at the SFX level.
     * Kept for the general path; the live footstep tracks use [walkLevel].
     */
    fun walk(setting: Float, distance: Float, maxRange: Float, distanceGain: Float): Float =
        walkLevel(setting, distanceGain = walkDistanceGain(distance, maxRange) * clamp(distanceGain))

    /**
     * Footstep playback rate for a movement speed in metres per second.
     *
     * Three levels, three clearly different paces: a slow run drags the steps
     * ([SLOW_FOOTSTEP_RATE]), a normal run plays the recording at its natural
     * speed ([NORMAL_FOOTSTEP_RATE]) and a fast run drives it clearly quicker
     * ([FAST_FOOTSTEP_RATE]). Between two levels the rate blends with the real
     * speed, so the transition is smooth instead of stepping, and nothing ever
     * leaves the natural window [MIN_RATE]..[MAX_RATE].
     *
     * The base speed is [RunPressure.BASE_SPEED_MPS]; a fast level reaches the
     * enemy's own fleeing speed, which is where [FAST_FOOTSTEP_RATE] applies.
     */
    fun footstepRate(
        speedMps: Float,
        slowSpeedMps: Float,
        baseSpeedMps: Float,
        fastSpeedMps: Float
    ): Float {
        if (speedMps.isNaN() || speedMps <= 0f) return NORMAL_FOOTSTEP_RATE
        val slow = if (slowSpeedMps > 0f) slowSpeedMps else baseSpeedMps * 0.5f
        val base = if (baseSpeedMps > slow) baseSpeedMps else slow * 2f
        val fast = if (fastSpeedMps > base) fastSpeedMps else base * 3f
        if (speedMps <= slow * 1.02f) return SLOW_FOOTSTEP_RATE
        if (speedMps >= fast * 0.98f) return FAST_FOOTSTEP_RATE
        if (speedMps <= base) {
            val t = ((speedMps - slow) / (base - slow)).coerceIn(0f, 1f)
            return (SLOW_FOOTSTEP_RATE + t * (NORMAL_FOOTSTEP_RATE - SLOW_FOOTSTEP_RATE))
                .coerceIn(MIN_RATE, MAX_RATE)
        }
        val t = ((speedMps - base) / (fast - base)).coerceIn(0f, 1f)
        return (NORMAL_FOOTSTEP_RATE + t * (FAST_FOOTSTEP_RATE - NORMAL_FOOTSTEP_RATE))
            .coerceIn(MIN_RATE, MAX_RATE)
    }

    /**
     * Footstep playback rate for a movement speed in metres per second: the
     * steps follow the real speed smoothly inside the natural window. This is
     * the continuous companion of [footstepRate]'s three levels; both are
     * used, and both stay inside [MIN_RATE]..[MAX_RATE].
     */
    fun walkRate(speedMps: Float): Float {
        val safeSpeed = if (speedMps.isNaN()) 0f else speedMps.coerceAtLeast(0f)
        return (0.90f + safeSpeed * 0.010f).coerceIn(MIN_RATE, MAX_RATE)
    }

    /**
     * Footstep rate of a walker whose real pace is [speedMps].
     *
     * [walkRate] alone tops out at [MAX_RATE] (1.60) and only reaches about
     * 1.5 for an enemy sprint, so a running enemy used to sound almost the
     * same as a strolling one and the speed difference was carried by the
     * distance only. This curve follows the real speed over the whole
     * running ladder - a stroll under [STROLL_SPEED_MPS] drags, a normal run
     * sits at the natural rate and a sprint over [SPRINT_SPEED_MPS] is paced
     * clearly quicker - and is then clamped to the same window, so it can
     * never turn into the chipped pitch the game must not produce. The
     * difference between a slow and a fast enemy is therefore audible in the
     * step cadence as well as in the distance.
     */
    fun walkRateForSpeed(speedMps: Float): Float {
        val safeSpeed = if (speedMps.isNaN()) 0f else speedMps.coerceAtLeast(0f)
        return when {
            safeSpeed <= STROLL_SPEED_MPS -> SLOW_FOOTSTEP_RATE
            safeSpeed >= SPRINT_SPEED_MPS -> MAX_RATE
            // A straight line from a dragging stroll to a sprinting pace: one
            // rate for one speed, strictly rising, never outside the window.
            else -> SLOW_FOOTSTEP_RATE +
                (safeSpeed - STROLL_SPEED_MPS) /
                (SPRINT_SPEED_MPS - STROLL_SPEED_MPS) *
                (MAX_RATE - SLOW_FOOTSTEP_RATE)
        }.coerceIn(MIN_RATE, MAX_RATE)
    }

    /** Speeds at or below this are a stroll: the slowest footstep rate. */
    const val STROLL_SPEED_MPS = 2f

    /** Speeds at or above this are a sprint: the quickest footstep rate. */
    const val SPRINT_SPEED_MPS = 9f

    /**
     * The engine's SFX bus gain for a stored 0..1 effects setting. The native
     * engine applies it once, on every block, so moving the slider is heard
     * on the sounds that are already playing.
     */
    fun sfxGainForBus(setting: Float): Float = clamp(setting)

    /** The extra playback rate of a projectile at [proximity] (0 far, 1 at impact). */
    fun approachRate(proximity: Float): Float =
        if (proximity.isNaN()) 1f else 1f + 0.16f * proximity.coerceIn(0f, 1f)

    /** Highest playback rate a footstep loop may ever reach. */
    const val MAX_WALK_RATE = MAX_RATE

    /** True when [speedMps] counts as fast running. */
    fun isFastRun(speedMps: Float): Boolean =
        !speedMps.isNaN() && speedMps >= FAST_RUN_SPEED_MPS

    /**
     * Loudness of a running loop for a speed-level gain (slow, normal or fast
     * running). The gain is clamped so a slow run never disappears completely
     * and a fast run can never exceed the level the user chose.
     */
    fun runVolumeGain(speedGain: Float): Float =
        if (speedGain.isNaN()) 1f else speedGain.coerceIn(MIN_RUN_GAIN, 1f)

    /** Quietest a running loop may ever become. */
    const val MIN_RUN_GAIN = 0.55f

    /**
     * Playback rate for an explicit running-speed multiplier, used when the
     * speed is chosen with the three-finger gesture instead of being derived
     * from a distance. One is the natural walking rate and the result stays
     * inside the natural window [MIN_RATE]..[MAX_RATE].
     */
    fun runRate(speedMultiplier: Float): Float {
        if (speedMultiplier.isNaN() || speedMultiplier <= 0f) return 1f
        return (1f + (speedMultiplier - 1f) * 0.15f).coerceIn(MIN_RATE, MAX_RATE)
    }

    /**
     * Clamps any requested playback rate into the natural window. Every sound
     * of the game passes through this, so a shifted, distorted pitch is not a
     * recoverable state of the audio engine.
     */
    fun naturalRate(rate: Float): Float =
        if (rate.isNaN()) 1f else rate.coerceIn(MIN_RATE, MAX_RATE)

    /**
     * How often the live footstep volume is refreshed: every half second
     * while moving, and every quarter second while running fast, so the
     * approach and retreat swells always track the real distance.
     */
    fun walkUpdateIntervalMs(speedMps: Float): Long =
        if (isFastRun(speedMps)) WALK_UPDATE_FAST_INTERVAL_MS else WALK_UPDATE_INTERVAL_MS
}
