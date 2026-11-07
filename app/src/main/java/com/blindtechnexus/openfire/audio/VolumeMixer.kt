package com.blindtechnexus.openfire.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure volume arithmetic shared by every audio channel.
 *
 * Keeping this logic in one small, testable object is what prevents the
 * "slider silently reverts to a fixed level" bug:
 *
 *  - [music]  returns the user's music setting as it is. There is no hidden
 *             background gain any more, so a slider at 20% really plays at
 *             20% and a slider at 100% really plays at 100%.
 *  - [speech] returns the user's TTS setting unchanged.
 *  - [sfx]    applies the user's SFX setting and the 3D distance gain to the
 *             sound's own base volume.
 *  - [busGainFor] is the per-sound trim of the mixer. Weapon fire sits below
 *             the impacts, so a hit marker (player hit, enemy hit, gun hit,
 *             explosion) is always louder than the gun that caused it.
 *  - walking helpers keep footsteps at the speech (TTS) level, scale their
 *    playback rate with the real movement speed, and pan them only when
 *    the walker is genuinely to the side. A fighter running straight at
 *    the listener (or straight away) stays perfectly centred, exactly like
 *    a head-on opponent heard through earphones or on the phone speaker.
 */
object VolumeMixer {
    /**
     * The music setting is the real output level: no constant gain is applied,
     * so the chair of the mixer always matches the position of the slider.
     */
    const val MUSIC_BACKGROUND_GAIN = 1.0f

    /**
     * Gunfire trim. Weapons are mixed slightly under the impacts so the
     * player can always tell a hit from the shot that caused it, which is the
     * whole point of an audio-only game.
     */
    const val WEAPON_BUS_GAIN = 0.60f

    /** Impact trim: hits play at the full SFX level the player selected. */
    const val HIT_BUS_GAIN = 1.00f

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
     * The keys of every weapon that fires a shot. They are mixed on the
     * weapon bus, so a hit is always louder than the weapon sound.
     */
    val WEAPON_BUS_KEYS: Set<String> = setOf(
        "assault", "pistol", "shortGun", "sniperRifle", "glueWallBreaker",
        "awm", "mp40", "m10",
        "submachineGun", "ak47", "machineGun",
        "submachineGunShot", "ak47Shot", "machineGunShot",
        // Flight sounds of the launchers are weapon audio as well.
        "missile", "grenade", "atomBomb"
    )

    /**
     * The keys of every impact: these are mixed on the hit bus, louder than
     * any weapon, so the player never misses the feedback of a landed shot.
     */
    val HIT_BUS_KEYS: Set<String> = setOf(
        "gunHit", "enemyHit", "playerHit", "knifeHit", "swordHit",
        "glueHit", "coverHit", "coverCrack", "glueWallCrack",
        "explosion", "timerBomb"
    )

    fun clamp(value: Float): Float =
        if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    /** Effective music player volume for a stored 0..1 music setting. */
    fun music(setting: Float): Float = clamp(clamp(setting) * MUSIC_BACKGROUND_GAIN)

    /** Effective speech volume for a stored 0..1 TTS setting. */
    fun speech(setting: Float): Float = clamp(setting)

    /** Effective SFX volume for a base volume and a 0..1 distance gain. */
    fun sfx(setting: Float, base: Float, distanceGain: Float): Float =
        clamp(clamp(base) * clamp(setting) * clamp(distanceGain))

    /**
     * Mixer trim of one sound key: weapons play a little under the impacts,
     * hits play at full level and everything else is untouched.
     */
    fun busGainFor(key: String): Float = when {
        key in HIT_BUS_KEYS -> HIT_BUS_GAIN
        key in WEAPON_BUS_KEYS -> WEAPON_BUS_GAIN
        else -> 1.0f
    }

    /**
     * The complete SFX gain of one sound: the player's setting, the sound's
     * own base volume, the distance gain of the 3D position and its bus trim.
     * A hit is always louder than the weapon that fired it, at every slider
     * position.
     */
    fun mix(key: String, setting: Float, base: Float, distanceGain: Float): Float =
        clamp(sfx(setting, base, distanceGain) * busGainFor(key))

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
     * Distance gain (0..1) for footsteps: almost inaudible at the far edge
     * of the arena and swelling as the walker closes in, then fading again
     * while receding. The square curve keeps far footsteps present but
     * unmistakably distant.
     */
    fun walkDistanceGain(distance: Float, maxRange: Float): Float {
        val safeRange = maxRange.coerceAtLeast(1f)
        val proximity = (1f - clamp(distance / safeRange)).coerceIn(0f, 1f)
        // A slightly steeper curve than before: a distant walker is clearly
        // far away and a close one clearly close, which is what keeps the two
        // footstep loops from ever sounding like one local source.
        return (0.08f + proximity * proximity * 0.92f).coerceIn(0.08f, 1f)
    }

    /**
     * Playback rate of the enemy's footsteps. He uses the same recording as
     * the player, so a small, deliberate rate difference keeps the two loops
     * from ever locking into the same beat while both fighters walk.
     */
    fun enemyWalkRate(speedMps: Float): Float =
        (walkRate(speedMps) * 0.93f).coerceIn(0.6f, MAX_WALK_RATE)

    /**
     * Walking-loop volume from the current distance. Kept for the general
     * SFX path; the live footstep tracks use [walkDistanceGain] directly on
     * the speech-level base volume so they match the TTS loudness.
     */
    fun walk(setting: Float, distance: Float, maxRange: Float, distanceGain: Float): Float =
        sfx(setting, walkDistanceGain(distance, maxRange), distanceGain)

    /**
     * Footstep playback rate for a movement speed in metres per second.
     *
     * Slow movement stretches the steps out; a fast run speeds them up; and
     * the Max Speed extension on the fast level pushes the rate to the top of
     * the range, so an extremely fast run really sounds extremely fast.
     */
    fun walkRate(speedMps: Float): Float {
        val safeSpeed = if (speedMps.isNaN()) 0f else speedMps.coerceAtLeast(0f)
        return (0.55f + safeSpeed * 0.16f).coerceIn(0.6f, 2.0f)
    }

    /** Highest playback rate a footstep loop may ever reach. */
    const val MAX_WALK_RATE = 2.0f

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
    const val MIN_RUN_GAIN = 0.45f

    /**
     * Playback rate for an explicit running-speed multiplier, used when the
     * speed is chosen with the three-finger gesture instead of being derived
     * from a distance. One is the natural walking rate.
     */
    fun runRate(speedMultiplier: Float): Float =
        if (speedMultiplier.isNaN() || speedMultiplier <= 0f) {
            1f
        } else {
            (0.6f + speedMultiplier * 0.4f).coerceIn(0.6f, 1.9f)
        }

    /**
     * How often the live footstep volume is refreshed: every half second
     * while moving, and every quarter second while running fast, so the
     * approach and retreat swells always track the real distance.
     */
    fun walkUpdateIntervalMs(speedMps: Float): Long =
        if (isFastRun(speedMps)) WALK_UPDATE_FAST_INTERVAL_MS else WALK_UPDATE_INTERVAL_MS
}
