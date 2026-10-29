package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.audio.AudioManager
import com.blindtechnexus.openfire.model.BombKind
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.Progression
import com.blindtechnexus.openfire.model.RobertRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Locks the round three to round five rules of the current specification:
 *
 *  - the grenade is free for the player, so only the atom bomb and the timer
 *    bomb spend a Bomb Power;
 *  - the player's anti-bomb explosive is free and always usable from round
 *    three;
 *  - the enemy carries two grenades and two anti-bomb explosives in round
 *    three, all three bombs three times each plus a missile and two
 *    interceptors in round four, and two missiles, four interceptors, five
 *    anti-bomb explosives and unlimited bombs and ammunition in round five;
 *  - round five is limited to seven minutes and a defeat costs 20 coins;
 *  - the enemy fires one or two shots at 2 s, 2.5 s or 3 s and repositions.
 */
class RoundRulesTest {

    @Test
    fun theGrenadeIsFreeAndOnlyTheBigBombsNeedPower() {
        // Before round three no bomb exists at all.
        assertFalse(BombRules.requiresBombPower(2, BombKind.GRENADE))
        // From round three the grenade never spends a Bomb Power.
        assertFalse(BombRules.requiresBombPower(3, BombKind.GRENADE))
        assertFalse(BombRules.requiresBombPower(4, BombKind.GRENADE))
        assertFalse(BombRules.requiresBombPower(5, BombKind.GRENADE))
        // The atom bomb and the timer bomb still do.
        assertTrue(BombRules.requiresBombPower(3, BombKind.ATOM))
        assertTrue(BombRules.requiresBombPower(3, BombKind.TIMER))
        assertTrue(BombRules.requiresBombPower(5, BombKind.ATOM))
        assertTrue(BombRules.requiresBombPower(5, BombKind.TIMER))
    }

    @Test
    fun thePlayersAntiBombExplosiveIsFreeFromRoundThree() {
        assertFalse(BombRules.playerAntiBombsFree(1))
        assertFalse(BombRules.playerAntiBombsFree(2))
        assertTrue(BombRules.playerAntiBombsFree(3))
        assertTrue(BombRules.playerAntiBombsFree(4))
        assertTrue(BombRules.playerAntiBombsFree(5))

        assertFalse(GameState(round = 2).playerAntiBombFree)
        assertTrue(GameState(round = 3).playerAntiBombFree)
        assertTrue(GameState(round = 5).playerAntiBombFree)
    }

    @Test
    fun enemyBombsFollowTheRoundTable() {
        // Round three: grenades only, twice per game.
        assertEquals(2, BombRules.enemyBombUsesForRound(3, BombKind.GRENADE))
        assertEquals(0, BombRules.enemyBombUsesForRound(3, BombKind.ATOM))
        assertEquals(0, BombRules.enemyBombUsesForRound(3, BombKind.TIMER))
        assertEquals(2, BombRules.enemyBombsForRound(3))
        // Round four: all three bombs, three times each.
        for (kind in BombKind.entries) {
            assertEquals(3, BombRules.enemyBombUsesForRound(4, kind))
        }
        assertEquals(9, BombRules.enemyBombsForRound(4))
        // Round five: unlimited bombs.
        assertEquals(99, BombRules.enemyBombsForRound(5))
        for (kind in BombKind.entries) {
            assertEquals(99, BombRules.enemyBombUsesForRound(5, kind))
        }
        // Rounds one and two have no bombs.
        assertEquals(0, BombRules.enemyBombsForRound(1))
        assertEquals(0, BombRules.enemyBombsForRound(2))
    }

    @Test
    fun theEnemyPicksTheBombHeStillOwns() {
        assertNull(BombRules.chooseEnemyBomb(2, 9, 9, 9, 0.5f, Random(1)))
        assertNull(BombRules.chooseEnemyBomb(3, 0, 0, 0, 0.5f, Random(1)))
        // Only grenades left: the grenade is thrown.
        assertEquals(
            BombKind.GRENADE,
            BombRules.chooseEnemyBomb(3, 2, 0, 0, 0.9f, Random(1))
        )
        // A weak player is finished with the atom bomb when he owns one.
        assertEquals(
            BombKind.ATOM,
            BombRules.chooseEnemyBomb(4, 3, 3, 3, 0.2f, Random(1))
        )
        // Without grenades and with a healthy player the timer bomb is used.
        val picked = BombRules.chooseEnemyBomb(4, 0, 0, 3, 0.9f, Random(1))
        assertEquals(BombKind.TIMER, picked)
    }

    @Test
    fun enemyInterceptorsAndMissilesFollowTheRoundTable() {
        assertEquals(0, MissileRules.enemyMissilePowersForRound(3))
        assertEquals(1, MissileRules.enemyMissilePowersForRound(4))
        assertEquals(2, MissileRules.enemyMissilePowersForRound(5))
        assertEquals(0, MissileRules.antiMissilesForRound(3))
        assertEquals(2, MissileRules.antiMissilesForRound(4))
        assertEquals(4, MissileRules.antiMissilesForRound(5))
        assertEquals(2, BombRules.antiBombsForRound(3))
        assertEquals(3, BombRules.antiBombsForRound(4))
        assertEquals(5, BombRules.antiBombsForRound(5))
    }

    @Test
    fun roundFiveHasSevenMinutesAndTheLimitIsOnlyThere() {
        assertEquals(7L * 60L * 1_000L, Progression.FINAL_ROUND_TIME_LIMIT_MS)
        assertEquals(0L, Progression.roundTimeLimitMs(1))
        assertEquals(0L, Progression.roundTimeLimitMs(4))
        assertEquals(Progression.FINAL_ROUND_TIME_LIMIT_MS, Progression.roundTimeLimitMs(5))

        assertFalse(GameState(round = 4).timeLimitActive)
        val state = GameState(
            round = 5,
            battleLimitMs = Progression.FINAL_ROUND_TIME_LIMIT_MS,
            battleRemainingMs = 1_000L
        )
        assertTrue(state.timeLimitActive)
        assertEquals(1_000L, state.battleRemainingMs)
    }

    @Test
    fun aDefeatCostsTwentyCoinsUnlessThePlayerHasNone() {
        assertEquals(20, Progression.DEFEAT_COIN_PENALTY)
        assertEquals(20, Progression.coinsLostOnDefeat(100))
        assertEquals(20, Progression.coinsLostOnDefeat(20))
        assertEquals(5, Progression.coinsLostOnDefeat(5))
        assertEquals(0, Progression.coinsLostOnDefeat(0))
        assertEquals(0, Progression.coinsLostOnDefeat(-30))
    }

    @Test
    fun roundFiveHasUnlimitedBombsAndAmmunition() {
        assertFalse(RobertRules.infiniteAmmo(4))
        assertTrue(RobertRules.infiniteAmmo(5))
        assertFalse(RobertRules.infiniteBombs(4))
        assertTrue(RobertRules.infiniteBombs(5))
    }

    @Test
    fun theEnemyFiresOneOrTwoShotsOnAHumanRhythm() {
        // Live and helper timing share the deliberately varied 1.5 s / 2 s rhythm.
        assertEquals(listOf(1_500L, 2_000L), RobertRules.NORMAL_INTERVALS_MS)
        assertEquals(2, RobertRules.MAX_SUCCESSIVE_SHOTS)

        val random = Random(17)
        repeat(200) {
            val limit = RobertRules.burstLimit(random)
            assertTrue(limit in 1..2)
            assertFalse(RobertRules.burstFinished(limit - 1, limit))
            assertTrue(RobertRules.burstFinished(limit, limit))
            val interval = RobertRules.normalFireInterval(random)
            assertTrue(interval == 1_500L || interval == 2_000L)
        }

        // Every situation uses the 1.5-2 second choice; fleeing holds the 2 second beat.
        for (situation in RobertRules.CombatSituation.entries) {
            val window = RobertRules.fireIntervalWindow(situation, fleeing = false)
            assertEquals(1_500L..2_000L, window)
            val fleeingWindow = RobertRules.fireIntervalWindow(situation, fleeing = true)
            assertEquals(2_000L..2_000L, fleeingWindow)
        }
    }

    @Test
    fun heFleesAsOftenAsHeShootsUnlessThePlayerIsPinned() {
        val random = Random(29)
        // A damaged weapon or an extremely weak player pins him down to firing.
        repeat(100) {
            assertFalse(
                RobertRules.shouldRepositionInsteadOfFiring(
                    situation = RobertRules.CombatSituation.EVEN,
                    playerWeaponDamaged = true,
                    playerHpFraction = 0.8f,
                    random = random
                )
            )
            assertFalse(
                RobertRules.shouldRepositionInsteadOfFiring(
                    situation = RobertRules.CombatSituation.EVEN,
                    playerWeaponDamaged = false,
                    playerHpFraction = RobertRules.EXTREMELY_WEAK_FRACTION,
                    random = random
                )
            )
        }
        // A healthy player makes him run a real share of the time.
        var runs = 0
        repeat(1_000) {
            if (RobertRules.shouldRepositionInsteadOfFiring(
                    situation = RobertRules.CombatSituation.EVEN,
                    playerWeaponDamaged = false,
                    playerHpFraction = 1f,
                    random = random
                )
            ) {
                runs++
            }
        }
        assertTrue("he must flee as often as he fires, runs=$runs", runs in 250..550)
        // A pinned player is always attacked from the 70-100 percent band.
        val close = RobertRules.proximityRangeFor(
            situation = RobertRules.CombatSituation.EVEN,
            playerWeaponDamaged = true,
            playerHpFraction = 1f,
            random = random
        )
        assertEquals(70f, close.start, 0.0001f)
        assertEquals(100f, close.endInclusive, 0.0001f)
        // A losing Robert shoots from the 20-40 percent band, an even one from
        // the 40-70 percent band.
        val far = RobertRules.proximityRangeFor(
            situation = RobertRules.CombatSituation.DISADVANTAGE,
            playerWeaponDamaged = false,
            playerHpFraction = 1f,
            random = random
        )
        assertEquals(20f, far.start, 0.0001f)
        assertEquals(40f, far.endInclusive, 0.0001f)
        val mid = RobertRules.proximityRangeFor(
            situation = RobertRules.CombatSituation.EVEN,
            playerWeaponDamaged = false,
            playerHpFraction = 1f,
            random = random
        )
        assertEquals(40f, mid.start, 0.0001f)
        assertEquals(70f, mid.endInclusive, 0.0001f)
    }

    @Test
    fun everyWeaponOwnsItsOwnChannelAndFootstepsStaySeparate() {
        val keys = AudioManager.WEAPON_SOUND_KEYS
        // Every weapon of the game is part of the weapon channel set: only
        // the same key is ever cut, so two different weapons really mix.
        for (weaponKey in listOf(
            "assault", "shortGun", "pistol", "sniperRifle", "awm", "mp40", "machineGun",
            "knifeSwish", "swordGlide", "glueWallBreaker"
        )) {
            assertTrue("$weaponKey must be an exclusive weapon sound", weaponKey in keys)
        }
        // Footsteps, hits and explosions never fight the weapon channel.
        for (other in listOf("playerWalk", "enemyWalk", "walk", "gunHit", "enemyHit", "explosion")) {
            assertFalse("$other must not cut a weapon sound", other in keys)
        }
    }

    @Test
    fun continuousFireNeedsAPinnedPlayer() {
        // A burst of player fire alone never turns him into a machine gun.
        assertFalse(
            RobertRules.shouldFireContinuously(
                playerBurstShots = 12,
                playerHealthDropping = true,
                situation = RobertRules.CombatSituation.ADVANTAGE
            )
        )
        assertTrue(
            RobertRules.shouldFireContinuously(
                playerBurstShots = 0,
                playerHealthDropping = false,
                situation = RobertRules.CombatSituation.DISADVANTAGE,
                playerWeaponDamaged = true
            )
        )
        assertTrue(
            RobertRules.shouldFireContinuously(
                playerBurstShots = 0,
                playerHealthDropping = false,
                situation = RobertRules.CombatSituation.DISADVANTAGE,
                playerHpFraction = 0.05f
            )
        )
    }
}
