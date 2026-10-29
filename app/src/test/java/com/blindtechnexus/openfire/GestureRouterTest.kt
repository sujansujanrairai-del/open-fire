package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.GameAction
import com.blindtechnexus.openfire.model.GestureRouter
import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.MoveDirection
import com.blindtechnexus.openfire.model.RobertRules
import com.blindtechnexus.openfire.model.RunPressure
import com.blindtechnexus.openfire.model.RunSpeed
import com.blindtechnexus.openfire.model.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the battle gesture contract:
 *  - One finger: up fire, down reload, right/left switch weapon.
 *  - Two fingers: up/down move the quick menu, right/left speak the status.
 *  - Three or more fingers: right runs faster, left runs slower.
 *  - A single tap activates the highlighted quick-menu item.
 *  - Holding a zone runs in one of the four directions of the 100 m zone.
 */
class GestureRouterTest {

    @Test
    fun oneFingerBattleGestures() {
        assertEquals(GameAction.FIRE, GestureRouter.route(Screen.BATTLE, "up", 1))
        assertEquals(GameAction.RELOAD, GestureRouter.route(Screen.BATTLE, "down", 1))
        assertEquals(GameAction.NEXT_WEAPON, GestureRouter.route(Screen.BATTLE, "right", 1))
        assertEquals(GameAction.PREV_WEAPON, GestureRouter.route(Screen.BATTLE, "left", 1))
    }

    @Test
    fun twoFingersMoveTheQuickMenuOrSpeakStatus() {
        assertEquals(GameAction.MENU_NEXT, GestureRouter.route(Screen.BATTLE, "up", 2))
        assertEquals(GameAction.MENU_PREV, GestureRouter.route(Screen.BATTLE, "down", 2))
        assertEquals(GameAction.STATUS_OWN, GestureRouter.route(Screen.BATTLE, "right", 2))
        assertEquals(GameAction.STATUS_ENEMY, GestureRouter.route(Screen.BATTLE, "left", 2))
    }

    @Test
    fun threeFingerSwipesCycleTheRunningSpeed() {
        assertEquals(GameAction.SPEED_FASTER, GestureRouter.route(Screen.BATTLE, "right", 3))
        assertEquals(GameAction.SPEED_SLOWER, GestureRouter.route(Screen.BATTLE, "left", 3))
        assertEquals(GameAction.SPEED_FASTER, GestureRouter.route(Screen.BATTLE, "right", 4))
        assertEquals(GameAction.SPEED_SLOWER, GestureRouter.route(Screen.BATTLE, "left", 5))
        // Up and down stay reserved for firing and reloading.
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.BATTLE, "up", 3))
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.BATTLE, "down", 3))
        // The speed gesture only means something inside a battle.
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.MENU, "right", 3))
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.RESULT, "left", 3))
    }

    @Test
    fun theThreeSpeedLevelsCycleSlowNormalFast() {
        // A battle always starts at the normal level.
        assertEquals(RunSpeed.NORMAL, RunSpeed.fromId("normal"))
        assertEquals(RunSpeed.NORMAL, RunSpeed.fromId(null))
        assertEquals(RunSpeed.NORMAL, RunSpeed.fromId("nonsense"))

        assertEquals(RunSpeed.FAST, RunSpeed.NORMAL.faster())
        assertEquals(RunSpeed.FAST, RunSpeed.FAST.faster())
        assertEquals(RunSpeed.NORMAL, RunSpeed.FAST.slower())
        assertEquals(RunSpeed.SLOW, RunSpeed.NORMAL.slower())
        assertEquals(RunSpeed.SLOW, RunSpeed.SLOW.slower())
        assertEquals(RunSpeed.NORMAL, RunSpeed.SLOW.faster())
    }

    @Test
    fun theFastLevelMatchesTheEnemyFastSpeed() {
        val enemyFast = RobertRules.enemyFastSpeedMps(3)
        val fast = RunSpeed.FAST.speedMps(RunPressure.DEFAULT_SPEED_FACTOR, enemyFast)
        // The requirement: running fast covers ground at exactly the enemy's
        // own fast (fleeing) speed for the round.
        assertEquals(enemyFast, fast, 0.01f)
        // ... in every round, so the safety cap never cuts a sprint short.
        for (round in 1..5) {
            val enemy = RobertRules.enemyFastSpeedMps(round)
            assertEquals(
                "Round $round fast level must match Robert's fast speed",
                enemy,
                RunSpeed.FAST.speedMps(RunPressure.DEFAULT_SPEED_FACTOR, enemy),
                0.01f
            )
        }
        // Slow is half of the base walking speed, normal follows the press.
        assertEquals(
            RunPressure.BASE_SPEED_MPS * RunSpeed.SLOW_FACTOR,
            RunSpeed.SLOW.speedMps(2.0f, enemyFast),
            0.0001f
        )
        assertEquals(
            RunPressure.BASE_SPEED_MPS,
            RunSpeed.NORMAL.speedMps(0f, enemyFast),
            0.0001f
        )
        assertTrue(
            RunSpeed.FAST.speedMps(1f, enemyFast) >
                RunSpeed.NORMAL.speedMps(1f, enemyFast)
        )
    }

    @Test
    fun theRunningSoundVolumeFollowsTheSpeedLevel() {
        assertTrue(RunSpeed.SLOW.volumeGain() < RunSpeed.NORMAL.volumeGain())
        assertTrue(RunSpeed.NORMAL.volumeGain() < RunSpeed.FAST.volumeGain())
        assertEquals(1.0f, RunSpeed.FAST.volumeGain(), 0.0001f)
    }

    @Test
    fun theBattleZoneHasFourRunningDirections() {
        assertEquals(4, MoveDirection.entries.size)
        assertFalse(MoveDirection.FORWARD.lateral)
        assertFalse(MoveDirection.BACKWARD.lateral)
        assertTrue(MoveDirection.LEFT.lateral)
        assertTrue(MoveDirection.RIGHT.lateral)
        assertEquals(MoveDirection.LEFT, MoveDirection.fromId("left"))
        assertEquals(MoveDirection.FORWARD, MoveDirection.fromId("nonsense"))
        assertEquals(25f, Arena.SIZE, 0.0001f)
    }

    @Test
    fun aSingleTapActivatesTheQuickMenuItemOnlyInBattle() {
        assertEquals(GameAction.ACTIVATE_MENU, GestureRouter.tap(Screen.BATTLE))
        assertEquals(GameAction.NONE, GestureRouter.tap(Screen.MENU))
        assertEquals(GameAction.NONE, GestureRouter.tap(Screen.RESULT))
    }

    @Test
    fun flightAndResultGestures() {
        assertEquals(GameAction.CONTINUE, GestureRouter.route(Screen.READER, "up", 1))
        assertEquals(GameAction.BACK_TO_MENU, GestureRouter.route(Screen.READER, "down", 1))
        assertEquals(GameAction.JUMP, GestureRouter.route(Screen.JUMP, "up", 1))
        assertEquals(GameAction.OPEN_PARA, GestureRouter.route(Screen.PARA, "up", 1))
        assertEquals(GameAction.LAND, GestureRouter.route(Screen.LAND, "up", 1))
        assertEquals(GameAction.PLAY_AGAIN, GestureRouter.route(Screen.RESULT, "up", 1))
        assertEquals(GameAction.BACK_TO_MENU, GestureRouter.route(Screen.RESULT, "down", 1))
    }

    @Test
    fun menuScreensIgnoreGestures() {
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.MENU, "up", 1))
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.SHOP, "down", 2))
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.SETTINGS, "down", 2))
        assertEquals(GameAction.NONE, GestureRouter.route(Screen.BATTLE, "up", 0))
    }

    @Test
    fun theRunHoldIsRecognisedQuickly() {
        assertEquals(200L, RunPressure.LONG_PRESS_TIMEOUT_MS)
    }

    @Test
    fun strongerPressesRunFaster() {
        val light = RunPressure.speedFactor(0.2f)
        val medium = RunPressure.speedFactor(0.55f)
        val strong = RunPressure.speedFactor(0.9f)
        assertTrue(light < medium)
        assertTrue(medium < strong)
        // A strong press runs at well over twice a light press.
        assertTrue(strong > light * 2f)
        assertEquals(RunPressure.MIN_SPEED_FACTOR, RunPressure.speedFactor(0.05f), 0.0001f)
        assertEquals(RunPressure.MAX_SPEED_FACTOR, RunPressure.speedFactor(1.0f), 0.0001f)
        // Pressures beyond the usual sensor range are clamped, not faster.
        assertEquals(RunPressure.MAX_SPEED_FACTOR, RunPressure.speedFactor(2.0f), 0.0001f)
    }

    @Test
    fun unknownPressureFallsBackToADefaultRun() {
        // Devices without a usable pressure sensor must still run normally.
        assertEquals(RunPressure.DEFAULT_SPEED_FACTOR, RunPressure.speedFactor(0f), 0.0001f)
        assertEquals(RunPressure.DEFAULT_SPEED_FACTOR, RunPressure.speedFactor(-1f), 0.0001f)
        assertEquals(RunPressure.DEFAULT_SPEED_FACTOR, RunPressure.speedFactor(Float.NaN), 0.0001f)
        assertEquals(1.0f, RunPressure.DEFAULT_SPEED_FACTOR, 0.0001f)
    }
}
