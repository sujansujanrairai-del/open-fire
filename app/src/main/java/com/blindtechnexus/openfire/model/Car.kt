package com.blindtechnexus.openfire.model

/**
 * Where the bulletproof car is in its cycle, from the first click in the
 * defence menu to the moment it is destroyed.
 *
 * The user is protected only in the three INSIDE stages: the door is closed
 * and the user is in the car. While the door is open, bullets hit the user.
 */
enum class CarStage(val id: String) {
    /** Not on the battlefield. The menu click deploys it. */
    NOT_DEPLOYED("notDeployed"),

    /** Deployed next to the user, door closed, user outside. */
    DOOR_CLOSED_OUTSIDE("doorClosedOutside"),

    /** The door is open while the user gets in (exposed). */
    DOOR_OPEN_ENTERING("doorOpenEntering"),

    /** Inside with the door closed and the engine off. */
    INSIDE_ENGINE_OFF("insideEngineOff"),

    /** Inside with the engine running but the car standing still. */
    INSIDE_ENGINE_ON("insideEngineOn"),

    /** Inside, driving towards Robert while the swipe is held. */
    INSIDE_MOVING("insideMoving"),

    /** The door is open while the user gets out (exposed). */
    DOOR_OPEN_EXITING("doorOpenExiting"),

    /** The car is out of fuel: the next swipe up buys a refuel. */
    REFUEL_PENDING("refuelPending"),

    /** Destroyed by a missile or a bomb. Lost for the rest of the battle. */
    DESTROYED("destroyed")
}

/** Pure car rules: stages, the swipe map, health and the fuel limit. */
object CarRules {
    /** Price of the car in the shop (see [DefenceItem.CAR]). */
    const val PRICE = 3500

    /** Price of one refuel once the car is out of fuel. */
    const val REFUEL_PRICE = 2000

    /** Battles the car can be used in before it must be refuelled. */
    const val GAMES_PER_FUEL = 2

    /** Health of a fresh car. Only missiles and bombs can destroy it. */
    const val MAX_HP = 1000

    /** Below this health Robert uses his missiles and bombs against the car. */
    const val ALARM_HP = 300

    /** Driving speed in metres per second. */
    const val SPEED_MPS = 6f

    /** One driving step lasts this long. */
    const val TICK_MS = 100L

    /** Ticks the car drives back the other way after it hits a wall. */
    const val TURN_BACK_TICKS = 10

    /** The car stops short of Robert instead of driving over him. */
    const val STOP_DISTANCE_M = 2f

    /** True while the user is inside the car with the door closed. */
    fun isInside(stage: CarStage): Boolean =
        stage == CarStage.INSIDE_ENGINE_OFF ||
            stage == CarStage.INSIDE_ENGINE_ON ||
            stage == CarStage.INSIDE_MOVING

    /** True while the car shields the user from bullets, missiles and bombs. */
    fun shieldsUser(stage: CarStage): Boolean = isInside(stage)

    /**
     * True when a one-finger swipe up is a car action (open the door, close
     * it, start the engine or refuel) instead of a normal shot.
     */
    fun awaitsSwipeUp(stage: CarStage): Boolean =
        stage == CarStage.DOOR_CLOSED_OUTSIDE ||
            stage == CarStage.DOOR_OPEN_ENTERING ||
            stage == CarStage.INSIDE_ENGINE_OFF ||
            stage == CarStage.REFUEL_PENDING

    /** The spoken prompt of [stage], as a translation key. */
    fun promptKey(stage: CarStage): String = when (stage) {
        CarStage.NOT_DEPLOYED -> "carOpenDoor"
        CarStage.DOOR_CLOSED_OUTSIDE -> "carOpenDoor"
        CarStage.DOOR_OPEN_ENTERING -> "carCloseDoor"
        CarStage.INSIDE_ENGINE_OFF -> "carStartPrompt"
        CarStage.INSIDE_ENGINE_ON -> "carEngineOnPrompt"
        CarStage.INSIDE_MOVING -> "carDrivingPrompt"
        CarStage.DOOR_OPEN_EXITING -> "carCloseDoor"
        CarStage.REFUEL_PENDING -> "carRefuelPrompt"
        CarStage.DESTROYED -> "carDestroyedPrompt"
    }

    /** True once the car is weak enough for Robert to start using explosives. */
    fun alarmed(hp: Int): Boolean = hp < ALARM_HP

    /** Games left after the car is deployed in a battle (never below zero). */
    fun gamesAfterDeploy(gamesLeft: Int): Int = (gamesLeft - 1).coerceAtLeast(0)
}
