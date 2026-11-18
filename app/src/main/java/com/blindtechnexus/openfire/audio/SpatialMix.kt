package com.blindtechnexus.openfire.audio

import com.blindtechnexus.openfire.model.Combat
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where one sound sits in the arena, expressed in the two numbers the native
 * engine needs: a stereo pan and a distance.
 *
 * The engine does the rest (constant-power pan, distance curve, interaural
 * delay, head shadow). This object only answers "where is it?", so the
 * placement rules of the game stay pure, testable Kotlin and the DSP stays in
 * C++.
 */
data class SpatialPlace(val pan: Float, val distance: Float, val rear: Float) {

    companion object {
        /** The listener's own sound: centred, at the ear. */
        val CENTRE = SpatialPlace(pan = 0f, distance = 0f, rear = 0f)

        /**
         * Places a source at the real offset ([lateral], [forward]) from the
         * listener inside the square arena, where a positive [forward] is in
         * front of the listener (the way `depthOffset` is used by the game).
         *
         *  - the pan follows the sideways share of the offset, with a dead
         *    zone around the forward axis, so head-on motion stays centred;
         *  - the distance is the true 2D distance, so the level swells as the
         *    source closes in and fades as it recedes;
         *  - [rear] is -1 in front and +1 behind, which is what gives the
         *    front and the rear of the arena different tonal colours.
         */
        fun fromOffset(lateral: Float, forward: Float): SpatialPlace {
            if (!lateral.isFinite() || !forward.isFinite()) return CENTRE
            val distance = sqrt(lateral * lateral + forward * forward)
            if (distance < 0.001f) return CENTRE
            val pan = VolumeMixer.panFor(lateral, distance)
            val rear = (-forward / distance).coerceIn(-1f, 1f)
            return SpatialPlace(pan = pan, distance = distance.coerceAtMost(Combat.MAX_RANGE), rear = rear)
        }

        /**
         * Places a source from the game's raw z coordinate, where a NEGATIVE z
         * is in front of the listener (`posZ = -distance` is how every
         * positional call site of the battle engine speaks).
         */
        fun fromZ(lateral: Float, z: Float): SpatialPlace = fromOffset(lateral, -z)

        /**
         * Places a projectile relative to its target: [remainingM] metres
         * away at launch, closing to zero at impact. The pan is centred -
         * a missile flies at the listener, not past him - while the distance
         * is what makes it "come from far away".
         */
        fun projectile(remainingM: Float): SpatialPlace {
            val safe = if (remainingM.isFinite()) remainingM.coerceIn(0f, Combat.MAX_RANGE) else 0f
            return SpatialPlace(pan = 0f, distance = safe, rear = 0f)
        }

        /** How close a projectile is, 0 at launch and 1 at impact. */
        fun approachProximity(remainingM: Float, launchM: Float): Float {
            if (!remainingM.isFinite() || !launchM.isFinite() || launchM <= 0.1f) return 1f
            return (1f - remainingM / launchM).coerceIn(0f, 1f)
        }
    }

    /** True when the source is genuinely to one side. */
    val sideways: Boolean get() = abs(pan) >= VolumeMixer.PAN_DEAD_ZONE
}
