package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.ElectricShockRules
import com.blindtechnexus.openfire.model.SkyLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** Projectile locks and the geometry and travel time of a shock throw. */
class ProjectileLockAndShockThrowTest {
    @Test
    fun theClearSkyRuleHoldsBothFightersExactlyAlike() {
        assertFalse(SkyLock.blocksWeapons(missileInFlight = false, bombInFlight = false))
        assertTrue(SkyLock.blocksWeapons(missileInFlight = true, bombInFlight = false))
        assertTrue(SkyLock.blocksWeapons(missileInFlight = false, bombInFlight = true))
        assertTrue(SkyLock.blocksWeapons(missileInFlight = true, bombInFlight = true))
    }

    @Test
    fun aProjectileCannotHoldTheSkyForever() {
        val launch = 1_000L
        assertFalse(SkyLock.expired(launch, launch + 1))
        assertFalse(SkyLock.expired(launch, launch + SkyLock.MAX_HOLD_MS))
        assertTrue(SkyLock.expired(launch, launch + SkyLock.MAX_HOLD_MS + 1))
        assertFalse(SkyLock.expired(0L, 9_000_000L))
    }

    @Test
    fun theRemainingHoldCountsDownAndNeverGoesNegative() {
        assertEquals(SkyLock.MAX_HOLD_MS, SkyLock.remainingMs(500L, 500L))
        assertEquals(4_000L, SkyLock.remainingMs(500L, 500L + SkyLock.MAX_HOLD_MS - 4_000L))
        assertEquals(0L, SkyLock.remainingMs(500L, 500L + SkyLock.MAX_HOLD_MS * 2))
        assertEquals(0L, SkyLock.remainingMs(0L, 1L))
    }

    @Test
    fun theThrowLandsAtTheFarthestCornerFromEveryPartOfTheArena() {
        val corners = listOf(0f to 0f, 0f to Arena.SIZE, Arena.SIZE to 0f, Arena.SIZE to Arena.SIZE)
        for (x in 0..25) for (y in 0..25) {
            val px = x.toFloat()
            val py = y.toFloat()
            val (ex, ey) = ElectricShockRules.farthestLanding(px, py)
            assertTrue((ex to ey) in corners)
            val actual = hypot(px - ex, py - ey)
            for ((cx, cy) in corners) {
                assertTrue("($px,$py) -> ($ex,$ey) is not farthest", actual + 0.001f >= hypot(px - cx, py - cy))
            }
            // Even the centre's farthest corner is beyond all ordinary guns.
            assertTrue(Combat.proximityOf(actual) < 40f)
        }
        assertEquals(0f to 0f, ElectricShockRules.farthestLanding(Arena.SIZE / 2f, Arena.SIZE / 2f))
        assertEquals(Arena.SIZE to 0f, ElectricShockRules.farthestLanding(0f, Arena.SIZE))
        assertEquals(0f to 0f, ElectricShockRules.farthestLanding(Float.NaN, Float.POSITIVE_INFINITY))
    }

    @Test
    fun countershockHalvesTheThrowDistance() {
        assertEquals(5_000L, ElectricShockRules.PUSH_DELAY_MS)
        val full = ElectricShockRules.pushLanding(1f, 1f, 3f, 5f, false)
        val countered = ElectricShockRules.pushLanding(1f, 1f, 3f, 5f, true)
        assertEquals(25f to 25f, full)
        assertEquals(14f to 15f, countered)
    }

    @Test
    fun aCornerReturnMakesRealProgressOnEveryTickWithoutCrossingWalls() {
        val players = listOf(0f to 0f, 1f to 24f, 12.5f to 12.5f, 25f to 25f)
        for ((px, py) in players) {
            var (x, y) = ElectricShockRules.farthestLanding(px, py)
            val startDistance = hypot(px - x, py - y)
            val expectedTicks = ElectricShockRules.returnTicks(startDistance)
            var ticks = 0
            while (hypot(px - x, py - y) > 0.001f && ticks < 100) {
                val before = hypot(px - x, py - y)
                val (nextX, nextY) = ElectricShockRules.returnPosition(px, py, x, y)
                assertTrue(nextX in Arena.MIN..Arena.MAX && nextY in Arena.MIN..Arena.MAX)
                val step = hypot(nextX - x, nextY - y)
                assertTrue(step <= ElectricShockRules.RETURN_STEP_M + 0.001f)
                assertTrue(hypot(px - nextX, py - nextY) < before)
                x = nextX
                y = nextY
                ticks++
            }
            assertEquals(expectedTicks, ticks)
            assertEquals(0f, hypot(px - x, py - y), 0.001f)
        }
    }

    @Test
    fun returnTimingUsesTheTrueDiagonalAndIsNotArtificiallyCapped() {
        val diagonal = hypot(Arena.SIZE, Arena.SIZE)
        assertEquals(0, ElectricShockRules.returnTicks(0f))
        assertEquals(0, ElectricShockRules.returnTicks(2f, 5f))
        assertEquals(2, ElectricShockRules.returnTicks(2.5f))
        assertEquals(3, ElectricShockRules.returnTicks(3.5f))
        assertEquals(23, ElectricShockRules.returnTicks(diagonal))
        assertEquals(6_900L, ElectricShockRules.returnTimeMs(diagonal))
        assertEquals(0, ElectricShockRules.returnTicks(Float.NaN))
        assertEquals(1f to 1f, ElectricShockRules.returnPosition(1f, 1f, 1f, 1f))
    }

    @Test
    fun aThrowSilencesTheEnemyForTheWholeWalkPlusTheRecovery() {
        // The promise of a throw is breathing room: the enemy keeps his gun
        // lowered for every tick of the walk back AND for a further recovery
        // silence, so a throw can never be answered one tick after it lands.
        val diagonal = hypot(Arena.SIZE, Arena.SIZE)
        assertEquals(
            ElectricShockRules.returnTimeMs(diagonal) + ElectricShockRules.RECOVERY_SILENCE_MS,
            ElectricShockRules.throwSilenceMs(diagonal)
        )
        // A point-blank throw still buys the whole recovery silence.
        assertEquals(
            ElectricShockRules.RECOVERY_SILENCE_MS,
            ElectricShockRules.throwSilenceMs(0f)
        )
        assertEquals(1_500L, ElectricShockRules.RECOVERY_SILENCE_MS)
        // The shortest real throw already buys more than six seconds: far
        // more than a gun cooldown, which is what the silence is for.
        assertTrue(ElectricShockRules.throwSilenceMs(Arena.SIZE) >= 6_000L)
        // Silence only grows with the distance, never shrinks.
        var previous = ElectricShockRules.throwSilenceMs(0f)
        for (metres in 1..25) {
            val current = ElectricShockRules.throwSilenceMs(metres.toFloat())
            assertTrue(current >= previous)
            previous = current
        }
        // The farthest corner stays a long walk: six seconds of movement plus
        // the recovery, never a one-second round trip.
        assertEquals(6_900L, ElectricShockRules.returnTimeMs(diagonal))
        assertEquals(8_400L, ElectricShockRules.throwSilenceMs(diagonal))
    }
}
