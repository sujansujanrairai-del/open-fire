package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.moveRobertSafely
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * The enemy is an automated fighter, so it must never touch a boundary:
 * every one of its steps is simulated against the perimeter before it is
 * taken, and the heading is changed while there is still open ground ahead.
 *
 * These tests drive Robert from every corner, edge and the middle of the zone
 * in every direction with every step size the game uses and assert that not
 * one of those steps is stopped or shortened by a wall - which is exactly why
 * the enemy side never plays the wall impact sound.
 */
class ArenaWallTest {

    /** Every starting position: the four corners, the four edges and the middle. */
    private val startPositions = listOf(
        0f to 0f,
        Arena.SIZE to 0f,
        0f to Arena.SIZE,
        Arena.SIZE to Arena.SIZE,
        Arena.SIZE / 2f to 0f,
        Arena.SIZE / 2f to Arena.SIZE,
        0f to Arena.SIZE / 2f,
        Arena.SIZE to Arena.SIZE / 2f,
        Arena.SIZE / 2f to Arena.SIZE / 2f
    )

    /** Every heading, including the four straight walls and the diagonals. */
    private val headings = listOf(
        1f to 0f,
        -1f to 0f,
        0f to 1f,
        0f to -1f,
        1f to 1f,
        -1f to -1f,
        1f to -1f,
        -1f to 1f
    )

    /** Step sizes from a careful walk up to a round five sprint. */
    private val stepSizes = listOf(0.5f, 1f, 2f, 3.6f, 6f)

    @Test
    fun aPredictedStepKnowsWhenTheWallIsInTheWay() {
        val mid = Arena.SIZE / 2f
        // Walking straight at the far boundary from two metres away.
        assertTrue(Arena.stepHitsWall(mid, Arena.SIZE - 2f, 0f, 1f, 10f))
        // The same walk in open ground does not.
        assertFalse(Arena.stepHitsWall(mid, mid, 0f, 1f, 10f))
        // The safety margin keeps him off the boundary before he touches it.
        assertTrue(Arena.stepHitsWall(mid, Arena.SIZE - 5f, 0f, 1f, 8f, Arena.ROBERT_WALL_MARGIN_M))
        assertFalse(Arena.stepHitsWall(mid, Arena.SIZE - 5f, 0f, 1f, 4f, 0f))
        assertTrue(Arena.nearWall(mid, Arena.SIZE - 1f, 2f))
        assertFalse(Arena.nearWall(mid, mid, 2f))
    }

    @Test
    fun everyAutomatedStepFromEverywhereStaysOffTheWall() {
        for ((x, y) in startPositions) {
            for ((dx, dy) in headings) {
                for (metres in stepSizes) {
                    val label = "step from ($x,$y) towards ($dx,$dy) by $metres"
                    val heading = Arena.safeHeading(x, y, dx, dy, metres)
                    // The corrected heading is never stopped or shortened by
                    // a boundary: he really does walk somewhere else instead.
                    assertFalse(label, Arena.stepHitsWall(x, y, heading.first, heading.second, metres))
                    val move = GameState(robertX = x, robertY = y).moveRobertSafely(dx, dy, metres)
                    assertFalse(label, move.blockedByWall || move.touchedWall)
                    assertTrue(label, move.moved)
                    // And he still moves: an automated fighter is never frozen
                    // against a wall.
                    val moved = hypot(
                        move.state.robertX - Arena.clampX(x),
                        move.state.robertY - Arena.clampY(y)
                    )
                    assertTrue("$label did not move", moved > 0f)
                }
            }
        }
    }

    @Test
    fun aRunHeldAgainstAWallKeepsHimMovingInsteadOfGrinding() {
        // Start in a corner and keep running straight into the boundary:
        // he must never stop, and he must never report a wall hit.
        var state = GameState(
            robertX = Arena.SIZE,
            robertY = Arena.SIZE,
            playerX = Arena.SIZE / 2f,
            playerY = 0f
        )
        var previous = Arena.SIZE to Arena.SIZE
        var moved = 0
        repeat(40) {
            val move = state.moveRobertSafely(0f, 1f, 3.6f)
            assertFalse(move.blockedByWall)
            assertFalse(move.touchedWall)
            state = move.state
            val here = state.robertX to state.robertY
            if (here != previous) moved++
            previous = here
        }
        // He keeps changing position instead of being clamped in the corner.
        assertTrue("Robert froze against the wall ($moved moves)", moved >= 20)
    }

    @Test
    fun heStillClosesInOnAPlayerWhoCampsInACorner() {
        // The player camps in a corner. Robert starts at the opposite corner
        // and always tries to walk straight at him: he must still get close,
        // walking around the last metres instead of grinding into them.
        var state = GameState(
            playerX = 1f,
            playerY = 1f,
            robertX = Arena.SIZE,
            robertY = Arena.SIZE
        )
        fun gap(): Float = Arena.distanceBetween(
            state.playerX,
            state.playerY,
            state.robertX,
            state.robertY
        )
        val start = gap()
        var closest = start
        repeat(30) {
            val move = state.moveRobertSafely(
                state.playerX - state.robertX,
                state.playerY - state.robertY,
                3.6f
            )
            assertFalse(move.blockedByWall)
            assertFalse(move.touchedWall)
            state = move.state
            closest = minOf(closest, gap())
        }
        assertTrue("Robert never closed in ($closest)", closest < 4f)
        assertTrue("Robert did not approach at all ($closest)", closest < start - 15f)
    }
}
