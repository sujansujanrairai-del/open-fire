package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.BattleMenuItem
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.FireRange
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.MoveDirection
import com.blindtechnexus.openfire.model.RunSpeed
import com.blindtechnexus.openfire.model.movePlayer
import com.blindtechnexus.openfire.model.movePlayerLateral
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the quick menu contract.
 *
 * The old "play" item was replaced by Recovery, which repairs every damaged
 * weapon for [Combat.GUN_RECOVERY_COST] coins, and the anti-bomb explosive
 * sits in the menu right after the anti-missile system.
 */
class BattleMenuTest {

    @Test
    fun theMenuRunsMedikitCoverGlueWallAntiMissileAntiBombExitAndRecover() {
        assertEquals(
            listOf(
                BattleMenuItem.MEDKIT,
                BattleMenuItem.COVER,
                BattleMenuItem.GLUE_WALL,
                BattleMenuItem.ANTI_MISSILE,
                BattleMenuItem.ANTI_BOMB,
                BattleMenuItem.EXIT,
                BattleMenuItem.RECOVER
            ),
            BattleMenuItem.entries
        )
    }

    @Test
    fun theOldPlayItemIsGone() {
        assertFalse(BattleMenuItem.entries.any { it.name == "PLAY" })
        assertEquals("recoverWeapon", BattleMenuItem.RECOVER.nameKey)
    }

    @Test
    fun recoverIsTheSafeDefaultSelection() {
        assertEquals(BattleMenuItem.RECOVER.ordinal, BattleMenuItem.RECOVER_INDEX)
        assertEquals(6, BattleMenuItem.RECOVER_INDEX)
        assertEquals(BattleMenuItem.RECOVER_INDEX, GameState().menuIndex)
        assertEquals(BattleMenuItem.RECOVER, GameState().menuItem)
        // Nothing is damaged in a fresh battle, so the default tap is free.
        assertFalse(GameState().anyWeaponDamaged)
        // Recovery is the cheap 100 coin action the menu item offers.
        assertEquals(100, Combat.GUN_RECOVERY_COST)
    }

    @Test
    fun cyclingWrapsAroundBothEnds() {
        assertEquals(BattleMenuItem.MEDKIT, BattleMenuItem.at(0))
        assertEquals(BattleMenuItem.ANTI_MISSILE, BattleMenuItem.at(3))
        assertEquals(BattleMenuItem.ANTI_BOMB, BattleMenuItem.at(4))
        assertEquals(BattleMenuItem.EXIT, BattleMenuItem.at(5))
        assertEquals(BattleMenuItem.RECOVER, BattleMenuItem.at(6))
        assertEquals(BattleMenuItem.MEDKIT, BattleMenuItem.at(7))
        assertEquals(BattleMenuItem.RECOVER, BattleMenuItem.at(-1))
        assertEquals(BattleMenuItem.EXIT, BattleMenuItem.at(-2))
        assertEquals(BattleMenuItem.ANTI_BOMB, BattleMenuItem.at(-3))
        assertEquals(BattleMenuItem.ANTI_MISSILE, BattleMenuItem.at(-4))
        assertEquals(BattleMenuItem.GLUE_WALL, BattleMenuItem.at(-5))
    }

    @Test
    fun onlyTheFiveDefencesCarryAnItem() {
        assertEquals(DefenceItem.MEDKIT, BattleMenuItem.MEDKIT.defence)
        assertEquals(DefenceItem.COVER, BattleMenuItem.COVER.defence)
        assertEquals(DefenceItem.GLUE_WALL, BattleMenuItem.GLUE_WALL.defence)
        assertEquals(DefenceItem.ANTI_MISSILE, BattleMenuItem.ANTI_MISSILE.defence)
        assertEquals(DefenceItem.ANTI_BOMB, BattleMenuItem.ANTI_BOMB.defence)
        assertEquals(null, BattleMenuItem.EXIT.defence)
        assertEquals(null, BattleMenuItem.RECOVER.defence)
    }

    @Test
    fun aFreshBattleStartsInTheMiddleBandWithoutDeception() {
        val state = GameState()
        assertEquals(FireRange.MID.id, state.robertFireRange)
        assertEquals(FireRange.MID, state.robertRange)
        assertEquals(0, state.robertDeceptionShots)
        assertEquals("advance", state.robertState)
        assertFalse(state.robertFleeing)
        assertFalse(state.activeWeaponDamaged)
        // Head-on by default, with no missile activity in the air.
        assertEquals(0f, state.robertLateral, 0.0001f)
        assertFalse(state.missileInFlight)
        assertFalse(state.missileFromPlayer)
        assertFalse(state.missileInterceptPlanned)
        assertEquals(0, state.robertMissilePowers)
        assertEquals(0, state.robertAntiMissiles)
        assertEquals(0, state.robertExchangeShots)
    }

    @Test
    fun aFreshBattleStartsInsideTheTwentyFiveMeterSquareAtNormalSpeed() {
        val state = GameState()
        // Both fighters start on the centre line at opposite edges, one full
        // range apart, and the stored distance agrees with the positions.
        assertEquals(12.5f, state.playerX, 0.0001f)
        assertEquals(Arena.MIN, state.playerY, 0.0001f)
        assertEquals(12.5f, state.robertX, 0.0001f)
        assertEquals(Arena.MAX, state.robertY, 0.0001f)
        assertEquals(25f, state.distance, 0.0001f)
        assertEquals(25f, Arena.SIZE, 0.0001f)

        // The battle always starts at the normal running speed.
        assertEquals(RunSpeed.NORMAL, state.runSpeedLevel)
        assertEquals(RunSpeed.NORMAL.id, state.runSpeed)

        // No bomb, no timer picker and no burst in a fresh battle.
        assertFalse(state.bombInFlight)
        assertEquals(null, state.bomb)
        assertFalse(state.timerSelectActive)
        assertEquals(BombRules.DEFAULT_TIMER_INDEX, state.timerIndex)
        assertEquals(30, state.timerSeconds)
        assertEquals(0, state.playerBurstShots)
        assertFalse(state.autoFireActive)
        assertEquals(0, state.robertTurnBackTicks)
    }

    @Test
    fun nobodyCanLeaveTheTwentyFiveMeterPerimeter() {
        val state = GameState()
        // Running away from Robert at the far edge is a wall hit: the state
        // never changes and the move is reported as blocked.
        val backward = state.movePlayer(forward = false, metres = 5f)
        assertTrue(backward.blockedByWall)
        assertEquals(state.playerY, backward.state.playerY, 0.0001f)
        assertEquals(state.distance, backward.state.distance, 0.0001f)

        // Running towards Robert really moves and shortens the distance.
        val forward = state.movePlayer(forward = true, metres = 5f)
        assertFalse(forward.blockedByWall)
        assertEquals(20f, forward.state.distance, 0.001f)
        assertEquals(5f, forward.state.playerY, 0.001f)

        // The two sideways directions run along the width of the zone.
        val left = state.movePlayerLateral(right = false, metres = 10f)
        assertTrue(MoveDirection.LEFT.lateral)
        assertEquals(2.5f, left.state.playerX, 0.001f)
        // A step longer than the room left is shortened to the wall: the
        // fighter touches it, and the next step is blocked completely.
        val right = left.state.movePlayerLateral(right = true, metres = 200f)
        assertTrue(right.touchedWall)
        assertFalse(right.blockedByWall)
        assertEquals(Arena.MAX, right.state.playerX, 0.0001f)
        val intoWall = right.state.movePlayerLateral(right = true, metres = 5f)
        assertTrue(intoWall.blockedByWall)
        assertEquals(Arena.MAX, intoWall.state.playerX, 0.0001f)

        // Positions can never be pushed outside the zone.
        for (metres in listOf(1f, 25f, 500f)) {
            for (direction in MoveDirection.entries) {
                val moved = if (direction.lateral) {
                    state.movePlayerLateral(direction == MoveDirection.RIGHT, metres)
                } else {
                    state.movePlayer(direction == MoveDirection.FORWARD, metres)
                }
                val next = moved.state
                assertTrue(next.playerX in Arena.MIN..Arena.MAX)
                assertTrue(next.playerY in Arena.MIN..Arena.MAX)
                assertTrue(next.distance in 0f..Arena.SIZE)
            }
        }
    }

    @Test
    fun aDamagedWeaponIsReportedForTheActiveSlot() {
        val state = GameState().copy(
            weapons = listOf("shortGun", "assaultGun"),
            damaged = listOf(false, true),
            weaponIndex = 1
        )
        assertTrue(state.activeWeaponDamaged)
        assertTrue(state.anyWeaponDamaged)

        val switched = state.copy(weaponIndex = 0)
        assertFalse(switched.activeWeaponDamaged)
        assertTrue(switched.anyWeaponDamaged)

        val recovered = state.copy(damaged = listOf(false, false))
        assertFalse(recovered.anyWeaponDamaged)
    }
}
