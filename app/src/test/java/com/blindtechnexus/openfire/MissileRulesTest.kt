package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.BombKind
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.SoundDurations
import com.blindtechnexus.openfire.model.Weapon
import com.blindtechnexus.openfire.model.Progression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the missile flight, interception and enemy-launch rules. */
class MissileRulesTest {

    @Test
    fun pricesAndUnlockMatchTheRequest() {
        // Every missile price dropped by 200 coins.
        assertEquals(3300, MissileRules.MISSILE_PRICE)
        assertEquals(1600, MissileRules.MISSILE_POWER_PRICE)
        assertEquals(2800, MissileRules.ANTI_MISSILE_PRICE)
        assertEquals(2, Progression.MISSILE_UNLOCK_COMPLETED_ROUNDS)
        assertEquals(3300, Weapon.MISSILE.price)
        // The flight sound is about fourteen seconds long.
        assertTrue(MissileRules.SOUND_DURATION_MS in 13_000L..16_000L)
        assertEquals(SoundDurations.MISSILE_MS, MissileRules.SOUND_DURATION_MS)
    }

    @Test
    fun theBattleEndsOnlyAfterTheExplosionFinished() {
        // The impact mixes the hit sound with explosion-sound.mp3 and the
        // result is announced only once that sound has really run out.
        assertEquals(SoundDurations.EXPLOSION_MS, MissileRules.EXPLOSION_MS)
        assertTrue(MissileRules.EXPLOSION_MS in 5_000L..8_000L)
        assertEquals("explosion", BombKind.GRENADE.blastSoundKey)
        assertEquals("explosion", BombKind.ATOM.blastSoundKey)
        assertEquals("explosion", BombKind.TIMER.blastSoundKey)
    }

    @Test
    fun aTargetWithRoomToRunIsChasedWithALoopingSound() {
        assertTrue(MissileRules.targetWillFlee(5f))
        assertTrue(MissileRules.targetWillFlee(20f))
        assertTrue(MissileRules.soundLoops(20f))
        // A chase always lasts at least one full play of the sound and
        // grows with the room the target has left to run (25 m square).
        assertTrue(MissileRules.flightMs(20f) > MissileRules.SOUND_DURATION_MS)
        assertTrue(MissileRules.flightMs(5f) > MissileRules.flightMs(20f))
        assertTrue(MissileRules.flightMs(0f) <= MissileRules.MAX_FLIGHT_MS)
    }

    @Test
    fun aPinnedTargetIsHitDirectlyAsTheSoundEnds() {
        assertFalse(MissileRules.targetWillFlee(Combat.MAX_RANGE))
        assertFalse(MissileRules.soundLoops(Combat.MAX_RANGE))
        assertEquals(
            MissileRules.SOUND_DURATION_MS,
            MissileRules.flightMs(Combat.MAX_RANGE)
        )
        // Out-of-arena inputs are clamped, never a zero or endless flight.
        assertTrue(MissileRules.flightMs(-50f) in 1..MissileRules.MAX_FLIGHT_MS)
        assertEquals(
            MissileRules.SOUND_DURATION_MS,
            MissileRules.flightMs(500f)
        )
    }

    @Test
    fun aLaunchNeedsTheMissileAPowerAndClearAir() {
        assertTrue(MissileRules.canLaunch(ownsMissile = true, missilePowers = 2, missileInFlight = false))
        assertFalse(MissileRules.canLaunch(ownsMissile = false, missilePowers = 2, missileInFlight = false))
        assertFalse(MissileRules.canLaunch(ownsMissile = true, missilePowers = 0, missileInFlight = false))
        assertFalse(MissileRules.canLaunch(ownsMissile = true, missilePowers = 2, missileInFlight = true))
    }

    @Test
    fun interceptorsGrowFromRoundFourToRoundFive() {
        assertEquals(0, MissileRules.antiMissilesForRound(1))
        assertEquals(0, MissileRules.antiMissilesForRound(3))
        assertEquals(2, MissileRules.antiMissilesForRound(4))
        assertEquals(4, MissileRules.antiMissilesForRound(5))
        assertEquals(0, MissileRules.enemyMissilePowersForRound(3))
        assertEquals(1, MissileRules.enemyMissilePowersForRound(4))
        assertEquals(2, MissileRules.enemyMissilePowersForRound(5))
    }

    @Test
    fun interceptionIsAnEndGameOnlySave() {
        // Below 100 health with a missile in the air: he fires it.
        assertTrue(MissileRules.willIntercept(4, 99, 2, missileInFlight = true))
        assertTrue(MissileRules.willIntercept(5, 1, 4, missileInFlight = true))
        // A healthy Robert never wastes an interceptor.
        assertFalse(MissileRules.willIntercept(4, 100, 2, missileInFlight = true))
        assertFalse(MissileRules.willIntercept(5, 1500, 4, missileInFlight = true))
        // No missile in the air, no interceptor.
        assertFalse(MissileRules.willIntercept(4, 50, 1, missileInFlight = false))
        // No interceptors left (round four already used its only one).
        assertFalse(MissileRules.willIntercept(4, 50, 0, missileInFlight = true))
        // Earlier rounds have no anti-missile system at all.
        assertFalse(MissileRules.willIntercept(3, 50, 1, missileInFlight = true))
        // A dead enemy cannot intercept.
        assertFalse(MissileRules.willIntercept(5, 0, 2, missileInFlight = true))
    }

    @Test
    fun robertLaunchesHisMissileOnlyWhileClearlyLosing() {
        fun launches(
            round: Int = 4,
            powers: Int = 1,
            robertHp: Int = 300,
            playerHp: Int = 900,
            shots: Int = 5,
            inFlight: Boolean = false
        ) = MissileRules.shouldEnemyLaunch(
            round = round,
            missilePowers = powers,
            robertHp = robertHp,
            robertMaxHp = 1000,
            playerHp = playerHp,
            playerMaxHp = 1000,
            robertShots = shots,
            missileInFlight = inFlight
        )

        assertTrue(launches())
        assertTrue(launches(round = 5, robertHp = 200, playerHp = 400))
        // A winning or healthy Robert never launches it.
        assertFalse(launches(robertHp = 900, playerHp = 300))
        assertFalse(launches(robertHp = 600, playerHp = 900))
        assertFalse(launches(robertHp = 300, playerHp = 300))
        // Once per game, from round four, once the fight is underway.
        assertFalse(launches(powers = 0))
        assertFalse(launches(round = 3))
        assertFalse(launches(shots = 2))
        assertFalse(launches(inFlight = true))
        assertFalse(launches(robertHp = 0))
    }
}
