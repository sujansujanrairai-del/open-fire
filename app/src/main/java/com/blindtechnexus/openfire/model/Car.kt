package com.blindtechnexus.openfire.model

/**
 * Where the bulletproof car is in its cycle, from the first click in the
 * defence menu to the moment it is destroyed.
 *
 * The car is fully automatic: closing the door starts the engine and the car
 * drives toward the enemy on its own, so the player keeps playing normally -
 * firing, reloading, switching weapons and using the quick menu - while the
 * car protects and drives. When the enemy stands still the car brakes itself
 * into an engine-on, non-moving state so the fighters can fire from cover of
 * the car; the moment the enemy moves again the car follows him.
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

    /** Inside with the door closed and the engine off (just braked to exit). */
    INSIDE_ENGINE_OFF("insideEngineOff"),

    /** Inside with the engine running but the car standing still (auto-braked). */
    INSIDE_ENGINE_ON("insideEngineOn"),

    /** Inside while the car drives itself toward the enemy. */
    INSIDE_MOVING("insideMoving"),

    /** The door is open while the user gets out (exposed). */
    DOOR_OPEN_EXITING("doorOpenExiting"),

    /** The car is out of fuel: the next swipe up buys a refuel. */
    REFUEL_PENDING("refuelPending"),

    /** Destroyed by a missile or two bomb hits. Lost for the rest of the battle. */
    DESTROYED("destroyed")
}

/**
 * Pure car rules: stages, the swipe map, health, fuel and destruction.
 *
 * The car is completely automatic: it deploys, opens and closes its door on
 * the user's prompts, starts its own engine and drives toward the enemy with
 * no steering at all. Leaving it is just as automatic: the quick menu shows
 * "Exit Car" in place of "Car", one click brakes the car, and the door
 * prompts walk the user out. The same car is then reused from that menu.
 */
object CarRules {
    /**
     * Price of the car in the shop (see [DefenceItem.CAR]). The bulletproof
     * car is the cheapest permanent defence of the shop at 1000 coins.
     */
    const val PRICE = 1_000

    /** Price of one refuel once the car is out of fuel. */
    const val REFUEL_PRICE = 500

    /** Battles the car can be used in before it must be refuelled. */
    const val GAMES_PER_FUEL = 5

    /** Health of a fresh car. Only missiles and bombs can destroy it. */
    const val MAX_HP = 1000

    /**
     * Below this health the car is weak: Robert spends his missiles and bombs
     * to finish it, and the robot reaches for its own car. The alarm is the
     * 400 health line of the design.
     */
    const val ALARM_HP = 400

    /** The robot enters its own bulletproof car once its health drops below this. */
    const val ROBOT_CAR_TRIGGER_HP = 400

    /** The robot is granted a bulletproof car from this round (round five). */
    const val ROBOT_CAR_ROUND = 5

    /** Two consecutive bomb hits destroy the car; one missile destroys it. */
    const val BOMB_HITS_TO_DESTROY = 2

    /** Driving speed in metres per second. */
    const val SPEED_MPS = 6f

    /** One driving step lasts this long. */
    const val TICK_MS = 100L

    /** Ticks the car drives back the other way after it hits a wall. */
    const val TURN_BACK_TICKS = 10

    /** The car stops short of the enemy instead of driving over him. */
    const val STOP_DISTANCE_M = 2f

    /** Ticks without any enemy movement before the car brakes itself. */
    const val STATIONARY_TICKS = 10

    /** True while the user is inside the car with the door closed. */
    fun isInside(stage: CarStage): Boolean =
        stage == CarStage.INSIDE_ENGINE_OFF ||
            stage == CarStage.INSIDE_ENGINE_ON ||
            stage == CarStage.INSIDE_MOVING

    /** True while the car shields the user from bullets, missiles and bombs. */
    fun shieldsUser(stage: CarStage): Boolean = isInside(stage)

    /**
     * True when a one-finger swipe up is a car action (open the door, close
     * it, or refuel) instead of a normal shot. While the car drives itself
     * every gesture is the normal battle gesture again.
     */
    fun awaitsSwipeUp(stage: CarStage): Boolean =
        stage == CarStage.DOOR_CLOSED_OUTSIDE ||
            stage == CarStage.DOOR_OPEN_ENTERING ||
            stage == CarStage.DOOR_OPEN_EXITING ||
            stage == CarStage.INSIDE_ENGINE_OFF ||
            stage == CarStage.REFUEL_PENDING

    /** The spoken prompt of [stage], as a translation key. */
    fun promptKey(stage: CarStage): String = when (stage) {
        CarStage.NOT_DEPLOYED -> "carOpenDoor"
        CarStage.DOOR_CLOSED_OUTSIDE -> "carOpenDoor"
        CarStage.DOOR_OPEN_ENTERING -> "carCloseDoor"
        CarStage.INSIDE_ENGINE_OFF -> "carOpenDoor"
        CarStage.INSIDE_ENGINE_ON -> "carEngineOnPrompt"
        CarStage.INSIDE_MOVING -> "carDrivingPrompt"
        CarStage.DOOR_OPEN_EXITING -> "carCloseDoor"
        CarStage.REFUEL_PENDING -> "carRefuelPrompt"
        CarStage.DESTROYED -> "carDestroyedPrompt"
    }

    /** True once the car is weak enough for Robert to start using explosives. */
    fun alarmed(hp: Int): Boolean = hp < ALARM_HP

    /** True when the robot may climb into its own bulletproof car. */
    fun robotMayUseCar(round: Int, robertHp: Int): Boolean =
        round >= ROBOT_CAR_ROUND && robertHp in 1 until ROBOT_CAR_TRIGGER_HP

    /** True when the robot is granted a car in this round at all. */
    fun robotHasCar(round: Int): Boolean = round >= ROBOT_CAR_ROUND

    /**
     * The car takes one hit of a bomb. The first hit is absorbed, the second
     * consecutive one destroys the car. A missile destroys it in one hit.
     */
    fun destroyedAfterBombHit(previousBombHits: Int): Boolean =
        previousBombHits + 1 >= BOMB_HITS_TO_DESTROY

    /** Games left after the car is deployed in a battle (never below zero). */
    fun gamesAfterDeploy(gamesLeft: Int): Int = (gamesLeft - 1).coerceAtLeast(0)
}
