package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.EnemyFireStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the enemy's rhythm mirroring and his closing three-shot burst. */
class EnemyFireStrategyTest {

    @Test
    fun onlyRealRhythmGapsBecomeSamples() {
        assertEquals(
            listOf(250L, 250L, 260L),
            EnemyFireStrategy.intervalsFrom(listOf(0L, 250L, 500L, 760L, 5_000L))
        )
        // A double tap and a silence are not a rhythm.
        assertTrue(EnemyFireStrategy.intervalsFrom(listOf(0L, 100L)).isEmpty())
        assertTrue(EnemyFireStrategy.intervalsFrom(listOf(0L, 4_000L)).isEmpty())
    }

    @Test
    fun thePlayerRhythmIsTheMedianOfTheNewestSamples() {
        assertNull(EnemyFireStrategy.playerInterval(emptyList()))
        assertNull(EnemyFireStrategy.playerInterval(listOf(1_000L)))
        assertEquals(
            500L,
            EnemyFireStrategy.playerInterval(listOf(0L, 500L, 1_000L, 1_500L))
        )
        // One accidental double tap never turns the enemy into a machine gun.
        assertEquals(
            1_000L,
            EnemyFireStrategy.playerInterval(listOf(0L, 130L, 1_130L, 2_130L, 3_130L))
        )
    }

    @Test
    fun theEnemyAnswersFasterFireWithTheOneSecondBeat() {
        assertEquals(1_000L, EnemyFireStrategy.counterInterval(250L))
        assertEquals(1_000L, EnemyFireStrategy.counterInterval(500L))
        assertEquals(1_000L, EnemyFireStrategy.counterInterval(999L))
    }

    @Test
    fun oneAndOnePointFiveSecondFireIsAnsweredAtOnePointFive() {
        assertEquals(1_500L, EnemyFireStrategy.counterInterval(1_000L))
        assertEquals(1_500L, EnemyFireStrategy.counterInterval(1_500L))
        assertEquals(1_500L, EnemyFireStrategy.counterInterval(null))
    }

    @Test
    fun slowFireIsAnsweredAtTwoPointFiveSeconds() {
        assertEquals(2_500L, EnemyFireStrategy.counterInterval(1_501L))
        assertEquals(2_500L, EnemyFireStrategy.counterInterval(2_000L))
        assertEquals(2_500L, EnemyFireStrategy.counterInterval(2_500L))
        assertEquals(1_500L, EnemyFireStrategy.counterInterval(2_501L))
    }

    @Test
    fun stoppingASustainedBurstOpensThreeQuickShots() {
        assertFalse(EnemyFireStrategy.playerStopped(0L, 10_000L))
        assertFalse(EnemyFireStrategy.playerStopped(8_000L, 10_000L))
        assertTrue(EnemyFireStrategy.playerStopped(7_000L, 10_000L))

        // Not while the player is still firing, and not during a burst.
        assertFalse(EnemyFireStrategy.shouldStartStopBurst(true, 9_000L, 10_000L, 0, 0L))
        assertFalse(EnemyFireStrategy.shouldStartStopBurst(true, 7_000L, 10_000L, 3, 0L))
        assertTrue(EnemyFireStrategy.shouldStartStopBurst(true, 7_000L, 10_000L, 0, 0L))
        // It cannot restart immediately after it just finished.
        assertFalse(EnemyFireStrategy.shouldStartStopBurst(true, 7_000L, 10_000L, 0, 8_000L))

        assertEquals(3, EnemyFireStrategy.STOP_BURST_SHOTS)
        assertEquals(500L, EnemyFireStrategy.stopBurstInterval(3))
        assertEquals(500L, EnemyFireStrategy.stopBurstInterval(1))
        assertTrue(EnemyFireStrategy.inStopBurst(3))
        assertFalse(EnemyFireStrategy.inStopBurst(0))
    }
}
