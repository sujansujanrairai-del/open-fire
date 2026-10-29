package com.blindtechnexus.openfire.audio

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure volume arithmetic shared by every audio channel.
 *
 * Keeping this logic in one small, testable object is what prevents the
 * "music volume silently reverts to the old lower level" bug: the user's
 * setting is never overwritten by a screen-scaled playback value.
 *
 *  - [music]  applies the constant background gain to the user's music setting.
 *  - [speech] returns the user's TTS setting unchanged.
 *  - [sfx]    applies the user's SFX setting and the 3D distance gain to the
 *             sound's own base volume.
 *  - walking helpers keep footsteps at the speech (TTS) level, scale their
 *    playback rate with the real movement speed, and pan them only when
 *    the walker is genuinely to the side. A fighter running straight at
 *    the listener (or straight away) stays perfectly centred, exactly like
 *    a head-on opponent heard through earphones or on the phone speaker.
 */
object VolumeMixer {
    /** Background music always sits under the sound effects. */
    const val MUSIC_BACKGROUND_GAIN = 0.35f

    /** Pans smaller than this are centred: straight motion never wobbles. */
    const val PAN_DEAD_ZONE = 0.12f

    /** Only part of the geometric pan is used, so sides stay gentle. */
    const val PAN_STRENGTH = 0.85f

    /** Movement at (or above) this speed counts as fast running. */
    const val FAST_RUN_SPEED_MPS = 8f

    /** Footstep volume refresh cadence while walking / running fast. */
    const val WALK_UPDATE_INTERVAL_MS = 500L
    const val WALK_UPDATE_FAST_INTERVAL_MS = 250L

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
        return (0.05f + proximity * proximity * 0.95f).coerceIn(0.05f, 1f)
    }

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
