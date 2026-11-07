package com.blindtechnexus.openfire.model

/**
 * Every action a gesture can trigger. Keeping the mapping pure makes it
 * unit-testable and guarantees one consistent gesture contract:
 *
 *  - One finger:  up fire (or hold-fire an automatic weapon), down reload,
 *                 right next weapon, left previous weapon.
 *  - Two fingers: up / down move the quick menu selection, right own status,
 *                 left enemy status.
 *  - Three fingers: up exits the game; down toggles pause/resume;
 *                 right / left change running speed. Vertical and horizontal
 *                 three-finger swipes are both supported by
 *                 [SwipeClassifier].
 *  - A single tap activates the highlighted quick-menu item (and confirms a
 *                 timer-bomb duration while the timer picker is open).
 */
enum class GameAction {
    NONE,
    CONTINUE,
    BACK_TO_MENU,
    JUMP,
    OPEN_PARA,
    LAND,
    FIRE,
    RELOAD,
    NEXT_WEAPON,
    PREV_WEAPON,
    MENU_NEXT,
    MENU_PREV,
    ACTIVATE_MENU,
    STATUS_OWN,
    STATUS_ENEMY,
    EXIT_BATTLE,
    PLAY_AGAIN,
    /** Three-finger swipe right: one running speed step faster. */
    SPEED_FASTER,
    /** Three-finger swipe left: one running speed step slower. */
    SPEED_SLOWER,
    /** Three-finger swipe down: freeze or resume the current battle. */
    TOGGLE_PAUSE
}

object GestureRouter {
    /** Three-finger swipes only mean something inside a battle. */
    fun route(screen: Screen, direction: String, fingers: Int): GameAction = when (screen) {
        Screen.READER -> when (direction) {
            "up" -> GameAction.CONTINUE
            "down" -> GameAction.BACK_TO_MENU
            else -> GameAction.NONE
        }
        Screen.JUMP -> when (direction) {
            "up" -> GameAction.JUMP
            "down" -> GameAction.BACK_TO_MENU
            else -> GameAction.NONE
        }
        Screen.PARA -> when (direction) {
            "up" -> GameAction.OPEN_PARA
            "down" -> GameAction.BACK_TO_MENU
            else -> GameAction.NONE
        }
        Screen.LAND -> when (direction) {
            "up" -> GameAction.LAND
            "down" -> GameAction.BACK_TO_MENU
            else -> GameAction.NONE
        }
        Screen.BATTLE -> when {
            fingers <= 0 -> GameAction.NONE
            // Three (or more) fingers: up exits, down pauses/resumes, right/left changes speed.
            fingers >= 3 -> when (direction) {
                "up" -> GameAction.EXIT_BATTLE
                "down" -> GameAction.TOGGLE_PAUSE
                "right" -> GameAction.SPEED_FASTER
                "left" -> GameAction.SPEED_SLOWER
                else -> GameAction.NONE
            }
            fingers == 2 -> when (direction) {
                "up" -> GameAction.MENU_NEXT
                "down" -> GameAction.MENU_PREV
                "right" -> GameAction.STATUS_OWN
                "left" -> GameAction.STATUS_ENEMY
                else -> GameAction.NONE
            }
            else -> when (direction) {
                "up" -> GameAction.FIRE
                "down" -> GameAction.RELOAD
                "right" -> GameAction.NEXT_WEAPON
                "left" -> GameAction.PREV_WEAPON
                else -> GameAction.NONE
            }
        }
        Screen.RESULT -> when (direction) {
            "up" -> GameAction.PLAY_AGAIN
            "down" -> GameAction.BACK_TO_MENU
            else -> GameAction.NONE
        }
        else -> GameAction.NONE
    }

    /** A single tap only means something inside a battle. */
    fun tap(screen: Screen): GameAction =
        if (screen == Screen.BATTLE) GameAction.ACTIVATE_MENU else GameAction.NONE
}

/**
 * Pure swipe arithmetic shared by the touch handler.
 *
 * The handler used to read the displacement of the single finger that moved
 * the most and to wait for every finger to leave the screen before deciding
 * anything. A vertical three-finger swipe therefore often lost its direction
 * (one finger drifting sideways won the comparison) or was swallowed when the
 * fingers were lifted one after the other, while a horizontal one worked.
 *
 * [SwipeClassifier] fixes that by working on the *average* displacement of
 * every finger that is down, by using a dedicated, smaller threshold for
 * three-finger swipes, and by letting the caller dispatch a three-finger
 * swipe the moment the threshold is crossed instead of at the end of the
 * gesture.
 */
object SwipeClassifier {
    /** Movement below this is a tap, never a swipe. */
    const val TAP_SLOP = 24f

    /** Distance one finger must travel to count as a swipe. */
    const val ONE_FINGER_THRESHOLD = 60f

    /** Two fingers swipe with a shorter movement: the gesture is deliberate. */
    const val TWO_FINGER_THRESHOLD = 36f

    /**
     * Three fingers need the shortest travel of all. The three-finger
     * gestures are the accessibility shortcuts of the game, so they must be
     * easy to perform in any direction: up and down (exit / pause) exactly
     * like left and right (running speed).
     */
    const val THREE_FINGER_THRESHOLD = 28f

    /** The swipe distance that applies to a gesture with [fingers] fingers. */
    fun threshold(fingers: Int): Float = when {
        fingers >= 3 -> THREE_FINGER_THRESHOLD
        fingers == 2 -> TWO_FINGER_THRESHOLD
        else -> ONE_FINGER_THRESHOLD
    }

    /** Euclidean length of a movement. */
    fun distance(dx: Float, dy: Float): Float {
        val x = if (dx.isNaN()) 0f else dx
        val y = if (dy.isNaN()) 0f else dy
        return kotlin.math.sqrt(x * x + y * y)
    }

    /** True when a movement this short is a tap instead of a swipe. */
    fun isTap(dx: Float, dy: Float): Boolean = distance(dx, dy) < TAP_SLOP

    /** True when the movement really is a swipe for this finger count. */
    fun isSwipe(dx: Float, dy: Float, fingers: Int): Boolean =
        distance(dx, dy) >= threshold(fingers)

    /** True when the vertical component dominates this movement. */
    fun isVertical(dx: Float, dy: Float): Boolean {
        val x = if (dx.isNaN()) 0f else kotlin.math.abs(dx)
        val y = if (dy.isNaN()) 0f else kotlin.math.abs(dy)
        return y >= x
    }

    /**
     * The direction id of a movement: "up", "down", "left" or "right".
     * Vertical wins a tie, so a straight up or down swipe is never read as a
     * sideways one.
     */
    fun direction(dx: Float, dy: Float): String {
        val x = if (dx.isNaN()) 0f else dx
        val y = if (dy.isNaN()) 0f else dy
        return if (isVertical(x, y)) {
            if (y > 0f) "down" else "up"
        } else {
            if (x > 0f) "right" else "left"
        }
    }

    /**
     * The average displacement of the fingers of one gesture, so a finger
     * that never moves cannot decide the direction on its own.
     */
    fun average(displacements: List<Pair<Float, Float>>): Pair<Float, Float> {
        if (displacements.isEmpty()) return Pair(0f, 0f)
        var sumX = 0f
        var sumY = 0f
        for ((dx, dy) in displacements) {
            sumX += if (dx.isNaN()) 0f else dx
            sumY += if (dy.isNaN()) 0f else dy
        }
        val count = displacements.size.toFloat()
        return Pair(sumX / count, sumY / count)
    }
}

/**
 * The four running directions of the 25 m square battle zone.
 *
 * The top and bottom portions of the battle screen run towards and away from
 * the enemy; the center portion is split in half, so holding its left half
 * runs left and holding its right half runs right.
 */
enum class MoveDirection(val id: String) {
    FORWARD("forward"),
    BACKWARD("backward"),
    LEFT("left"),
    RIGHT("right");

    /** True for the two sideways directions of the center portion. */
    val lateral: Boolean
        get() = this == LEFT || this == RIGHT

    companion object {
        fun fromId(id: String?): MoveDirection =
            entries.firstOrNull { it.id == id } ?: FORWARD
    }
}

/**
 * Pressure-sensitive running.
 *
 * Holding the top or bottom third of the battle screen runs; the hold is
 * recognised after [LONG_PRESS_TIMEOUT_MS] (much sooner than the platform
 * long-press default) and the reported touch pressure drives the speed:
 * the stronger the press, the faster the run. Devices that report no
 * usable pressure (a constant or non-positive value from the first sample)
 * fall back to a firm default speed, so running always works.
 */
object RunPressure {
    /** How quickly a held touch becomes a run. */
    const val LONG_PRESS_TIMEOUT_MS = 200L

    /** Speed multiplier for the lightest / strongest recognised press. */
    const val MIN_SPEED_FACTOR = 0.45f
    const val MAX_SPEED_FACTOR = 2.2f

    /** Speed used while no pressure is known (pre-press / unknown sensor). */
    const val DEFAULT_SPEED_FACTOR = 1.0f

    /** Pressures below this are treated as the lightest touch. */
    const val LIGHT_PRESSURE = 0.15f

    /** Pressures at (or above) this are treated as a full-force press. */
    const val FULL_PRESSURE = 1.0f

    /**
     * The player's walking speed in metres per second at the default press.
     * Every running speed level is expressed as a multiple of it, which is
     * what lets the fast level match Robert's real fleeing speed exactly.
     *
     * It is tuned for the 25 m square arena: a normal run crosses the whole
     * battlefield in about ten seconds, a fast run in about three, and the
     * Max Speed extension can push it under two.
     */
    const val BASE_SPEED_MPS = 2.6f

    /** One movement step per 100 ms tick at the default speed. */
    const val BASE_STEP_METRES = 0.26f

    /**
     * Maps a raw touch pressure (0..1 on most devices, sometimes larger)
     * to a running speed multiplier. The mapping is deliberately strong:
     * pressing notably harder almost doubles the running speed.
     */
    fun speedFactor(pressure: Float): Float {
        if (pressure.isNaN() || pressure <= 0f) return DEFAULT_SPEED_FACTOR
        val normalized = ((pressure - LIGHT_PRESSURE) / (FULL_PRESSURE - LIGHT_PRESSURE))
            .coerceIn(0f, 1f)
        return MIN_SPEED_FACTOR + normalized * (MAX_SPEED_FACTOR - MIN_SPEED_FACTOR)
    }
}

/**
 * The three running speed levels toggled by a three-finger swipe.
 *
 * Swiping right with three fingers cycles through the three levels. The
 * last selected speed is remembered between battles. The fast level
 * matches Robert's own fast (fleeing) speed for the current round exactly, so
 * a sprinting player and a sprinting enemy cover ground at the same rate; the
 * slow level is half of the player's base walking speed.
 *
 * The running sound follows the level the same way the enemy's footsteps do:
 * a faster run plays louder and with quicker steps, a slower run plays softer
 * and with dragged steps (see [VolumeGain]).
 */
enum class RunSpeed(val id: String, val nameKey: String) {
    SLOW("slow", "speedSlow"),
    NORMAL("normal", "speedNormal"),
    FAST("fast", "speedFast");

    /** One step faster, clamped at [FAST]. */
    fun faster(): RunSpeed = when (this) {
        SLOW -> NORMAL
        NORMAL -> FAST
        FAST -> FAST
    }

    /** One step slower, clamped at [SLOW]. */
    fun slower(): RunSpeed = when (this) {
        FAST -> NORMAL
        NORMAL -> SLOW
        SLOW -> SLOW
    }

    /**
     * The speed multiplier of this level. [NORMAL] keeps the classic
     * pressure-sensitive behaviour; [SLOW] and [FAST] are fixed levels so the
     * three-finger gesture always gives a predictable, announced speed.
     */
    fun factor(pressureFactor: Float, enemyFastSpeedMps: Float): Float = when (this) {
        SLOW -> SLOW_FACTOR
        NORMAL -> if (pressureFactor.isNaN() || pressureFactor <= 0f) {
            1f
        } else {
            pressureFactor
        }
        FAST -> fastFactor(enemyFastSpeedMps)
    }

    /** Running speed in metres per second for this level. */
    fun speedMps(pressureFactor: Float, enemyFastSpeedMps: Float): Float =
        RunPressure.BASE_SPEED_MPS * factor(pressureFactor, enemyFastSpeedMps)

    /**
     * Volume gain of the running sound for this level: a fast run is heard at
     * full level, a normal run slightly under it and a slow run clearly
     * quieter, exactly like the enemy's distance-scaled footsteps.
     */
    fun volumeGain(): Float = when (this) {
        SLOW -> SLOW_VOLUME_GAIN
        NORMAL -> NORMAL_VOLUME_GAIN
        FAST -> FAST_VOLUME_GAIN
    }

    companion object {
        /** Half of the base walking speed. */
        const val SLOW_FACTOR = 0.5f

        /** Fast running always matches Robert's fleeing speed. */
        fun fastFactor(enemyFastSpeedMps: Float): Float {
            if (enemyFastSpeedMps.isNaN() || enemyFastSpeedMps <= 0f) return DEFAULT_FAST_FACTOR
            return (enemyFastSpeedMps / RunPressure.BASE_SPEED_MPS)
                .coerceIn(1.5f, MAX_FAST_FACTOR)
        }

        /** Used when Robert's speed is unknown (round not resolved yet). */
        const val DEFAULT_FAST_FACTOR = 3.0f

        /**
         * Safety cap so a sprint can never become unplayable. It sits above the
         * fastest speed Robert ever flees at (round five: 75 metres a second
         * over the 6.5 metre base walking speed, a factor of about 11.6), so
         * the fast level always reaches his exact speed for every round instead
         * of being cut short by the cap.
         */
        const val MAX_FAST_FACTOR = 12.0f

        /** Running-sound volume per level. */
        const val SLOW_VOLUME_GAIN = 0.70f
        const val NORMAL_VOLUME_GAIN = 0.85f
        const val FAST_VOLUME_GAIN = 1.00f

        fun fromId(id: String?): RunSpeed =
            entries.firstOrNull { it.id == id } ?: NORMAL
    }
}
