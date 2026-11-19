package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.ElectricShockRules
import com.blindtechnexus.openfire.model.SkyLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that used to be one-sided.
 *
 *  - [SkyLock]: while a projectile is in the air **both** fighters are held,
 *    and the hold can never outlive any real flight.
 *  - [ElectricShockRules] knockback: the throw is bounded, always lands on a
 *    point that exists, and the way back is proportional to the distance the
 *    throw really created.
 */
class ProjectileLockAndShockThrowTest {

    // ------------------------------------------------------------ sky lock

    @Test
    fun theClearSkyRuleHoldsBothFightersExactlyAlike() {
        // No projectile: both may fire.
        assertFalse(SkyLock.blocksWeapons(missileInFlight = false, bombInFlight = false))
        // Any projectile, of either owner, holds both fighters. This is the
        // asymmetry the fix removes: the old check asked whose bomb it was.
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
        // A clear sky (no launch time at all) is never "expired".
        assertFalse(SkyLock.expired(0L, 9_000_000L))
    }

    @Test
    fun theRemainingHoldCountsDownAndNeverGoesNegative() {
        assertEquals(SkyLock.MAX_HOLD_MS, SkyLock.remainingMs(500L, 500L))
        assertEquals(4_000L, SkyLock.remainingMs(500L, 500L + SkyLock.MAX_HOLD_MS - 4_000L))
        assertEquals(0L, SkyLock.remainingMs(500L, 500L + SkyLock.MAX_HOLD_MS * 2))
        assertEquals(0L, SkyLock.remainingMs(0L, 1L))
    }

    // --------------------------------------------------------- shock throw

    @Test
    fun theThrowIsBoundedAndLandsInsideTheArena() {
        // The throw moves him away by a fixed step...
        assertEquals(
            ElectricShockRules.KNOCKBACK_M,
            ElectricShockRules.knockbackTarget(0f) - 0f,
            0.001f
        )
        assertEquals(
            ElectricShockRules.KNOCKBACK_M,
            ElectricShockRules.knockbackTarget(10f) - 10f,
            0.001f
        )
        // ...but never past the ceiling of a single shock.
        assertEquals(ElectricShockRules.KNOCKBACK_CEILING_M, ElectricShockRules.knockbackTarget(24f), 0.001f)
        // ...and never outside the arena, whatever it is asked to do.
        val far = ElectricShockRules.knockbackTarget(1_000f, Arena.SIZE)
        assertTrue(far <= Arena.SIZE)
        assertTrue(far <= ElectricShockRules.KNOCKBACK_CEILING_M)
        assertTrue(ElectricShockRules.knockbackTarget(0f, 0f) >= 0f)
    }

    @Test
    fun insideTheCeilingTheThrowOnlyEverMovesHimAway() {
        var previous = -1f
        var distance = 0f
        while (distance <= ElectricShockRules.KNOCKBACK_CEILING_M) {
            val target = ElectricShockRules.knockbackTarget(distance)
            assertTrue(target >= distance)
            assertTrue(target >= previous)
            previous = target
            distance += 0.5f
        }
        // Beyond the ceiling the throw is capped instead of pushing him out:
        // an opponent already far away is not launched further by a shock.
        assertEquals(
            ElectricShockRules.KNOCKBACK_CEILING_M,
            ElectricShockRules.knockbackTarget(20f),
            0.001f
        )
    }

    @Test
    fun theWayBackIsProportionalToTheThrow() {
        // A short throw is answered in a couple of steps, the longest throw in
        // a few more - and never in a walk back across the whole square.
        assertEquals(1, ElectricShockRules.returnTicks(0f))
        assertEquals(2, ElectricShockRules.returnTicks(2.5f))
        assertEquals(3, ElectricShockRules.returnTicks(3.5f))
        assertTrue(ElectricShockRules.returnTicks(14f) <= 8)
        assertTrue(
            ElectricShockRules.returnTicks(14f) > ElectricShockRules.returnTicks(3.5f)
        )
        // The whole return stays inside a few seconds.
        assertTrue(ElectricShockRules.returnTimeMs(14f) <= 8 * ElectricShockRules.RETURN_TICK_MS)
        assertTrue(ElectricShockRules.returnTimeMs(14f) > 1_000L)
        // Asking for a distance already reached still costs one step, never zero.
        assertTrue(ElectricShockRules.returnTicks(0f, 5f) >= 1)
    }

    @Test
    fun oneShockIsOneThrowNotAFlightAcrossTheArena() {
        // Whatever distance the shock starts from, one use of it moves the
        // opponent by exactly one bounded step: no sprint, no teleport.
        var distance = 0f
        while (distance <= Arena.SIZE) {
            val after = ElectricShockRules.knockbackTarget(distance)
            val moved = after - distance
            assertTrue(moved <= ElectricShockRules.KNOCKBACK_M + 0.001f)
            assertTrue(after <= ElectricShockRules.KNOCKBACK_CEILING_M)
            if (distance <= ElectricShockRules.KNOCKBACK_CEILING_M) assertTrue(moved >= 0f)
            distance += 1f
        }
    }
}
