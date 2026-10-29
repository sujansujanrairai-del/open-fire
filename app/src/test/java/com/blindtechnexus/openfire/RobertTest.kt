package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.BombKind
import com.blindtechnexus.openfire.model.FireRange
import com.blindtechnexus.openfire.model.Progression
import com.blindtechnexus.openfire.model.RobertRules
import com.blindtechnexus.openfire.model.RunPressure
import com.blindtechnexus.openfire.model.RunSpeed
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Locks Robert's level health, round loadouts (including the MP40, the AWM,
 * the machine gun and his bombs), the special sniper rule, his defence
 * thresholds, his continuous fire and his round speed.
 */
class RobertTest {

    @Test
    fun profileHealthMatchesRoundAndLevel() {
        assertEquals(200, RobertRules.profile(1, 1).maxHp)
        assertEquals(600, RobertRules.profile(1, 5).maxHp)
        assertEquals(700, RobertRules.profile(2, 1).maxHp)
        assertEquals(900, RobertRules.profile(2, 3).maxHp)
        assertEquals(2600, RobertRules.profile(5, 5).maxHp)
        assertEquals(Progression.hpFor(3, 4), RobertRules.profile(3, 4).maxHp)
    }

    @Test
    fun roundOneLevelsOneAndTwoHaveTwoMedkitsAndOneCover() {
        for (level in listOf(1, 2)) {
            val profile = RobertRules.profile(1, level)
            assertEquals("level $level medikits", 2, profile.medikits)
            assertEquals("level $level glueWalls", 0, profile.glueWalls)
            assertEquals("level $level covers", 1, profile.covers)
            assertEquals(
                listOf(Weapon.ASSAULT_GUN, Weapon.SHORT_GUN, Weapon.SNIPER),
                profile.weapons
            )
            assertTrue(profile.sniperLocked)
            assertEquals(2, profile.fleeAfterHits)
            assertEquals(0, profile.missilePowers)
            assertEquals(0, profile.antiMissiles)
            // No bombs and no anti-bomb explosive before round three.
            assertEquals(0, profile.bombs)
            assertEquals(0, profile.antiBombs)
        }
    }

    @Test
    fun roundOneLevelsThreeToFiveHaveTwoMedkitsAndTwoCovers() {
        for (level in listOf(3, 4, 5)) {
            val profile = RobertRules.profile(1, level)
            assertEquals("level $level medikits", 2, profile.medikits)
            assertEquals("level $level glueWalls", 0, profile.glueWalls)
            assertEquals("level $level covers", 2, profile.covers)
            assertEquals(
                listOf(Weapon.ASSAULT_GUN, Weapon.SHORT_GUN, Weapon.SNIPER),
                profile.weapons
            )
            assertTrue(profile.sniperLocked)
        }
    }

    @Test
    fun roundTwoAddsKnifeSwordAndHisMp40() {
        val roundTwo = RobertRules.profile(2, 1)
        assertEquals(3, roundTwo.medikits)
        // Two gloo walls per game from round two on.
        assertEquals(2, roundTwo.glueWalls)
        assertEquals(2, roundTwo.covers)
        assertTrue(roundTwo.weapons.contains(Weapon.KNIFE))
        assertTrue(roundTwo.weapons.contains(Weapon.SWORD))
        // The enemy also receives the MP40 in round two.
        assertTrue(roundTwo.weapons.contains(Weapon.MP40))
        assertFalse(roundTwo.weapons.contains(Weapon.AWM))
        assertFalse(roundTwo.weapons.contains(Weapon.MACHINE_GUN))
        assertFalse(roundTwo.weapons.contains(Weapon.PISTOL))
        assertFalse(roundTwo.weapons.contains(Weapon.MISSILE))
        assertTrue(roundTwo.sniperLocked)
        assertEquals(0, roundTwo.missilePowers)
        assertEquals(0, roundTwo.antiMissiles)
        assertEquals(0, roundTwo.bombs)
        assertEquals(0, roundTwo.antiBombs)
        // The loadout is the same throughout the round.
        assertEquals(roundTwo.weapons, RobertRules.profile(2, 5).weapons)
        assertEquals(3, RobertRules.profile(2, 5).medikits)
    }

    @Test
    fun roundThreeAddsThePistolTheAwmAndHisBombs() {
        val roundThree = RobertRules.profile(3, 1)
        assertEquals(3, roundThree.medikits)
        assertEquals(2, roundThree.glueWalls)
        assertEquals(4, roundThree.covers)
        assertTrue(roundThree.weapons.contains(Weapon.PISTOL))
        assertTrue(roundThree.weapons.contains(Weapon.KNIFE))
        // Round three: the enemy gets the AWM too.
        assertTrue(roundThree.weapons.contains(Weapon.AWM))
        assertTrue(roundThree.weapons.contains(Weapon.MP40))
        // The machine gun only exists from round four.
        assertFalse(roundThree.weapons.contains(Weapon.MACHINE_GUN))
        assertEquals(
            RobertRules.ALL_STANDARD_WEAPONS.toSet() - Weapon.MACHINE_GUN,
            roundThree.weapons.toSet()
        )
        assertFalse(roundThree.sniperLocked)
        assertEquals(3, roundThree.fleeAfterHits)
        // Round three has no anti-missile system at all: a launched missile
        // simply makes him sprint until it kills him.
        assertEquals(0, roundThree.missilePowers)
        assertEquals(0, roundThree.antiMissiles)
        assertEquals(0, MissileRules.antiMissilesForRound(3))
        // Two grenades and two anti-bomb explosives per game from round three.
        assertEquals(2, roundThree.bombs)
        assertEquals(2, roundThree.antiBombs)
        assertEquals(2, roundThree.grenades)
        assertEquals(0, roundThree.atomBombs)
        assertEquals(0, roundThree.timerBombs)
        assertEquals(2, BombRules.enemyBombsForRound(3))
        assertEquals(2, BombRules.antiBombsForRound(3))
        assertEquals(2, BombRules.enemyBombUsesForRound(3, BombKind.GRENADE))
        assertEquals(0, BombRules.enemyBombUsesForRound(3, BombKind.ATOM))
        assertEquals(4, RobertRules.profile(3, 5).covers)
    }

    @Test
    fun roundFourHasEveryWeaponOneMissileAndOneInterceptor() {
        val roundFour = RobertRules.profile(4, 1)
        assertEquals(5, roundFour.medikits)
        assertEquals(3, roundFour.glueWalls)
        assertEquals(5, roundFour.covers)
        assertEquals(RobertRules.ALL_STANDARD_WEAPONS.toSet(), roundFour.weapons.toSet())
        // The missile is a once-per-game special, not a random rotation weapon.
        assertFalse(roundFour.weapons.contains(Weapon.MISSILE))
        assertEquals(1, roundFour.missilePowers)
        // Two anti-missile interceptors from round four.
        assertEquals(2, roundFour.antiMissiles)
        assertEquals(4, roundFour.fleeAfterHits)
        // All three bombs, each usable three times per game: nine throws.
        assertEquals(9, roundFour.bombs)
        assertEquals(3, roundFour.grenades)
        assertEquals(3, roundFour.atomBombs)
        assertEquals(3, roundFour.timerBombs)
        assertEquals(3, roundFour.antiBombs)
        assertTrue(roundFour.weapons.contains(Weapon.MACHINE_GUN))
    }

    @Test
    fun roundFiveIsStrongerWithTwoInterceptors() {
        val roundFive = RobertRules.profile(5, 1)
        assertEquals(5, roundFive.medikits)
        assertEquals(4, roundFive.glueWalls)
        assertEquals(7, roundFive.covers)
        assertEquals(RobertRules.ALL_STANDARD_WEAPONS.toSet(), roundFive.weapons.toSet())
        assertFalse(roundFive.weapons.contains(Weapon.MISSILE))
        // Round five holds two missiles, four anti-missiles and five anti-bombs.
        assertEquals(2, roundFive.missilePowers)
        assertEquals(4, roundFive.antiMissiles)
        assertEquals(5, roundFive.fleeAfterHits)
        assertEquals(5, roundFive.antiBombs)
        // Unlimited bombs in round five (represented as 99) — strategy over stats.
        assertEquals(99, roundFive.bombs)
        assertEquals(99, roundFive.grenades)
        assertEquals(99, roundFive.atomBombs)
        assertEquals(99, roundFive.timerBombs)
        assertTrue(roundFive.weapons.contains(Weapon.MACHINE_GUN))
    }

    @Test
    fun situationalRangePushesAnAdvantagedRobertClose() {
        fun range(robertHp: Int, playerHp: Int) = RobertRules.situationalRange(
            stored = FireRange.MID,
            playerWeaponDamaged = false,
            playerGlueWallActive = false,
            robertHp = robertHp,
            robertMaxHp = 1000,
            playerHp = playerHp,
            playerMaxHp = 1000,
            deceptionShotsLeft = 0
        )
        // Big advantage: the 90%-100% band. Moderate: the 70%-90% band.
        val bigProx = Combat.proximityOf(range(900, 400).targetDistance)
        assertTrue(bigProx >= 90f && bigProx <= 100f)
        val moderateProx = Combat.proximityOf(range(800, 650).targetDistance)
        assertTrue(moderateProx >= 70f && moderateProx <= 90f)
        // Even fight keeps the stored band; losing pulls back to 40%-70%.
        assertEquals(FireRange.MID, range(600, 600))
        val losingProx = Combat.proximityOf(range(300, 800).targetDistance)
        assertTrue(losingProx >= 40f && losingProx <= 70f)
        // Forced point blank always wins, deception always finishes.
        assertEquals(
            FireRange.CLOSE,
            RobertRules.situationalRange(FireRange.FAR, true, false, 100, 1000, 900, 1000, 2)
        )
        assertEquals(
            FireRange.FAR,
            RobertRules.situationalRange(FireRange.FAR, false, false, 900, 1000, 100, 1000, 1)
        )
    }

    @Test
    fun aGlueWallHeCannotBreakKeepsHimAway() {
        // With a working sniper he closes in to break the wall.
        assertEquals(
            FireRange.CLOSE,
            RobertRules.situationalRange(
                stored = FireRange.MID,
                playerWeaponDamaged = false,
                playerGlueWallActive = true,
                robertHp = 900,
                robertMaxHp = 1000,
                playerHp = 900,
                playerMaxHp = 1000,
                deceptionShotsLeft = 0,
                robertHasSniper = true
            )
        )
        // Without one he refuses to approach and stays at the far edge.
        assertEquals(
            FireRange.FAR,
            RobertRules.situationalRange(
                stored = FireRange.MID,
                playerWeaponDamaged = false,
                playerGlueWallActive = true,
                robertHp = 900,
                robertMaxHp = 1000,
                playerHp = 900,
                playerMaxHp = 1000,
                deceptionShotsLeft = 0,
                robertHasSniper = false
            )
        )
        assertTrue(RobertRules.shouldAvoidGlueWall(playerGlueWallActive = true, robertHasSniper = false))
        assertFalse(RobertRules.shouldAvoidGlueWall(playerGlueWallActive = true, robertHasSniper = true))
        assertFalse(RobertRules.shouldAvoidGlueWall(playerGlueWallActive = false, robertHasSniper = false))
        assertEquals(
            FireRange.CLOSE,
            RobertRules.fireRangeFor(FireRange.MID, false, true, robertHasSniper = true)
        )
        assertEquals(
            FireRange.FAR,
            RobertRules.fireRangeFor(FireRange.MID, false, true, robertHasSniper = false)
        )
        // A damaged sniper is no sniper at all.
        assertTrue(
            RobertRules.hasUsableSniper(listOf(Weapon.SNIPER, Weapon.MP40), sniperDamaged = false)
        )
        assertFalse(
            RobertRules.hasUsableSniper(listOf(Weapon.SNIPER, Weapon.MP40), sniperDamaged = true)
        )
        assertFalse(RobertRules.hasUsableSniper(listOf(Weapon.MP40), sniperDamaged = false))
    }

    @Test
    fun defenceThresholdsFollowTheRound() {
        // Rounds one and two: below 60 percent of health, never below 100.
        assertEquals(120, RobertRules.defenceThresholdHp(1, 200))
        assertEquals(360, RobertRules.defenceThresholdHp(1, 600))
        assertEquals(420, RobertRules.defenceThresholdHp(2, 700))
        assertEquals(100, RobertRules.defenceThresholdHp(1, 100))
        assertEquals(100, RobertRules.defenceThresholdHp(2, 50))
        // Round three: below 200 health.
        assertEquals(200, RobertRules.defenceThresholdHp(3, 900))
        assertEquals(200, RobertRules.defenceThresholdHp(3, 150))
        // Rounds four and five: below 300 health.
        assertEquals(300, RobertRules.defenceThresholdHp(4, 1300))
        assertEquals(300, RobertRules.defenceThresholdHp(5, 2600))
        assertEquals(300, RobertRules.defenceThresholdHp(5, 100))

        // The round aware threshold really drives the defence decision.
        assertTrue(
            RobertRules.shouldUseDefence(
                robertHp = 250,
                defenceArmed = true,
                thresholdHp = RobertRules.defenceThresholdHp(4, 1300)
            )
        )
        assertFalse(
            RobertRules.shouldUseDefence(
                robertHp = 350,
                defenceArmed = true,
                thresholdHp = RobertRules.defenceThresholdHp(4, 1300)
            )
        )
    }

    @Test
    fun hePicksHisDefenceForTheSituation() {
        // Close fire: a barrier first, the cover before the glue wall.
        assertEquals(
            DefenceItem.COVER,
            RobertRules.chooseDefence(1, 1, 1, robertHp = 900, robertMaxHp = 1000, proximityPercent = 95f)
        )
        assertEquals(
            DefenceItem.GLUE_WALL,
            RobertRules.chooseDefence(1, 0, 1, robertHp = 900, robertMaxHp = 1000, proximityPercent = 95f)
        )
        // Below half health he heals.
        assertEquals(
            DefenceItem.MEDKIT,
            RobertRules.chooseDefence(1, 1, 1, robertHp = 400, robertMaxHp = 1000, proximityPercent = 50f)
        )
        // Otherwise cover, glue wall and medkit in turn.
        assertEquals(
            DefenceItem.COVER,
            RobertRules.chooseDefence(1, 1, 1, robertHp = 800, robertMaxHp = 1000, proximityPercent = 50f)
        )
        assertEquals(
            DefenceItem.GLUE_WALL,
            RobertRules.chooseDefence(1, 0, 1, robertHp = 800, robertMaxHp = 1000, proximityPercent = 50f)
        )
        assertEquals(
            DefenceItem.MEDKIT,
            RobertRules.chooseDefence(1, 0, 0, robertHp = 800, robertMaxHp = 1000, proximityPercent = 50f)
        )
        assertEquals(
            null,
            RobertRules.chooseDefence(0, 0, 0, robertHp = 800, robertMaxHp = 1000, proximityPercent = 50f)
        )
    }

    @Test
    fun continuousPlayerFireIsAnsweredWithContinuousFire() {
        assertEquals(3, RobertRules.COUNTER_FIRE_BURST_SHOTS)
        assertFalse(RobertRules.isContinuousPlayerFire(2))
        assertTrue(RobertRules.isContinuousPlayerFire(3))
        assertTrue(RobertRules.isContinuousPlayerFire(9))

        // Continuous fire only happens while the player is pinned: a damaged
        // weapon or an extremely weak player, no matter the burst count.
        assertFalse(
            RobertRules.shouldFireContinuously(
                playerBurstShots = 4,
                playerHealthDropping = false,
                situation = RobertRules.CombatSituation.EVEN
            )
        )
        assertTrue(
            RobertRules.shouldFireContinuously(
                playerBurstShots = 0,
                playerHealthDropping = false,
                situation = RobertRules.CombatSituation.EVEN,
                playerWeaponDamaged = true
            )
        )
        assertTrue(
            RobertRules.shouldFireContinuously(
                playerBurstShots = 0,
                playerHealthDropping = false,
                situation = RobertRules.CombatSituation.ADVANTAGE,
                playerHpFraction = RobertRules.EXTREMELY_WEAK_FRACTION
            )
        )
        assertFalse(
            RobertRules.shouldFireContinuously(
                playerBurstShots = 9,
                playerHealthDropping = true,
                situation = RobertRules.CombatSituation.ADVANTAGE,
                playerHpFraction = 0.8f
            )
        )
        // One or two shots in a row, never a machine gun.
        val burstRandom = Random(5)
        repeat(100) { assertTrue(RobertRules.burstLimit(burstRandom) in 1..2) }
        assertTrue(RobertRules.burstFinished(2, 2))
        assertFalse(RobertRules.burstFinished(1, 2))

        // His own health dropping under sustained fire makes him retreat.
        assertTrue(
            RobertRules.shouldRetreatFromSustainedFire(
                playerBurstShots = 4,
                robertHealthDropping = true,
                playerHealthDropping = false
            )
        )
        assertFalse(
            RobertRules.shouldRetreatFromSustainedFire(
                playerBurstShots = 4,
                robertHealthDropping = true,
                playerHealthDropping = true
            )
        )
        assertFalse(
            RobertRules.shouldRetreatFromSustainedFire(
                playerBurstShots = 1,
                robertHealthDropping = true,
                playerHealthDropping = false
            )
        )

        // Continuous fire uses the tactical 1.5 / 2 second sequence.
        val random = Random(3)
        val continuous = (1..60).map {
            RobertRules.fireIntervalMs(
                RobertRules.CombatSituation.EVEN,
                fleeing = false,
                random = random,
                continuous = true
            )
        }
        assertTrue(continuous.all { it == 1_500L || it == 2_000L })
        // While fleeing he never answers continuously and stays on the 2 s beat.
        assertTrue(
            (1..30).map {
                RobertRules.fireIntervalMs(
                    RobertRules.CombatSituation.EVEN,
                    fleeing = true,
                    random = random,
                    continuous = true
                )
            }.all { it == 2_000L }
        )
    }

    @Test
    fun heMovesInAllFourDirectionsAndTurnsBackAtWalls() {
        val random = Random(11)
        var strafed = 0
        val directions = mutableSetOf<Int>()
        repeat(1000) {
            if (RobertRules.shouldStrafe(random)) strafed++
            directions.add(RobertRules.strafeDirection(random))
        }
        // About one step in five is a sideways strafe, to both sides.
        assertTrue("strafe chance", strafed in 120..290)
        assertEquals(setOf(1, -1), directions)
        assertEquals(0.20f, RobertRules.STRAFE_CHANCE, 0.0001f)
        assertEquals(6f, RobertRules.STRAFE_STEP_M, 0.0001f)

        // A wall makes him turn back for a few ticks instead of grinding.
        assertEquals(3, RobertRules.TURN_BACK_TICKS)
        assertTrue(RobertRules.TURN_BACK_STEP_M > 0f)
    }

    @Test
    fun hisSprintIsTwiceTheFastestSpeedThePlayerCanRun() {
        for (round in 1..5) {
            val fast = RobertRules.enemyFastSpeedMps(round)
            val sprint = RobertRules.enemyFleeSpeedMps(round)
            // The player's fast level reaches exactly Robert's fast speed ...
            assertEquals(
                fast,
                RunSpeed.FAST.speedMps(RunPressure.DEFAULT_SPEED_FACTOR, fast),
                0.01f
            )
            // ... and his sprint is twice that: twice the player's top speed.
            assertEquals(
                fast * RobertRules.FLEE_SPEED_MULTIPLIER,
                sprint,
                0.0001f
            )
            assertEquals(
                sprint,
                RobertRules.fleeStep(round) / (RobertRules.FLEE_TICK_MS / 1000f),
                0.0001f
            )
            assertTrue("Round $round must sprint", sprint > fast)
            assertTrue("Round $round sprint must be real", sprint > 10f)
        }
        assertTrue(
            RobertRules.enemyFastSpeedMps(5) > RobertRules.enemyFastSpeedMps(1)
        )
    }

    @Test
    fun oneHitIsAnsweredAndTwoHitsMakeHimRunFarAway() {
        // A single hit: he fires straight back.
        assertFalse(RobertRules.shouldFleeFromPlayerFire(1, 1, 0))
        assertTrue(RobertRules.shouldRetaliate(1))
        // The second hit in a row: he is out-fired and sprints away.
        assertTrue(RobertRules.shouldFleeFromPlayerFire(2, 2, 0))
        assertTrue(RobertRules.shouldFleeFromPlayerFire(2, 2, 1))
        assertFalse(RobertRules.shouldRetaliate(2))
        // Out-firing him inside one exchange runs him off as well.
        assertTrue(RobertRules.shouldFleeFromPlayerFire(1, 3, 1))
        // Answering properly keeps him in the fight.
        assertFalse(RobertRules.shouldFleeFromPlayerFire(1, 3, 3))
        assertFalse(RobertRules.shouldFleeFromPlayerFire(1, 2, 2))
        // Nothing happens before the first hit, and a sprint always lasts
        // long enough to really cross the zone.
        assertFalse(RobertRules.shouldRetaliate(0))
        assertTrue(RobertRules.SPRINT_TICKS >= 3)
        assertTrue(
            RobertRules.fleeStep(1) * RobertRules.SPRINT_TICKS >= Arena.SIZE
        )
    }

    @Test
    fun bombRulesBalanceTheEscapeRace() {
        assertEquals(50f, BombRules.BLAST_RADIUS, 0.0001f)
        assertEquals(50f, BombRules.SAFE_DISTANCE, 0.0001f)
        assertEquals(listOf(15, 30, 40, 50, 60), BombRules.TIMER_SECONDS)
        assertEquals(30, BombRules.timerSecondsAt(BombRules.DEFAULT_TIMER_INDEX))
        assertEquals(60, BombRules.MAX_TIMER_SECONDS)
        // A clean escape takes longer than an atom bomb flight and about as
        // long as a grenade flight, which is what makes the race real.
        val escape = BombRules.escapeSeconds()
        assertTrue(escape > BombRules.flightMs(BombKind.ATOM) / 1000f)
        assertTrue(escape < BombRules.flightMs(BombKind.GRENADE) / 1000f)
        // The timer bomb always lets its target out.
        assertTrue(escape < BombRules.flightMs(BombKind.TIMER, 15) / 1000f)
        for (round in 1..5) {
            assertTrue(BombRules.escapeStep(round) > 0f)
            assertEquals(
                BombRules.escapeSpeedMps(round) * (BombRules.BOMB_FLEE_TICK_MS / 1000f),
                BombRules.escapeStep(round),
                0.0001f
            )
        }
        // Inside 50 metres the blast kills, outside it does not.
        assertTrue(BombRules.blastKills(49.9f))
        assertFalse(BombRules.blastKills(50f))
        assertTrue(BombRules.timerBombKillsThrower(20f))
        assertFalse(BombRules.timerBombKillsThrower(60f))
        assertTrue(BombRules.mustEscape(30f))
        assertFalse(BombRules.mustEscape(60f))
        assertEquals(80f, BombRules.escapeDistance(30f), 0.0001f)
    }

    @Test
    fun timerDurationsWrapAroundBothEnds() {
        assertEquals(1, BombRules.DEFAULT_TIMER_INDEX)
        assertEquals(30, BombRules.timerSecondsAt(1))
        assertEquals(15, BombRules.timerSecondsAt(0))
        assertEquals(60, BombRules.timerSecondsAt(4))
        // Wrapping in both directions.
        assertEquals(4, BombRules.stepTimerIndex(0, -1))
        assertEquals(0, BombRules.stepTimerIndex(4, 1))
        assertEquals(2, BombRules.stepTimerIndex(1, 1))
        assertEquals(0, BombRules.stepTimerIndex(1, -1))
        assertEquals(60, BombRules.timerSecondsAt(-1))
        assertEquals(15, BombRules.timerSecondsAt(5))
        assertEquals(3, BombRules.wrapTimerIndex(-2))
    }

    @Test
    fun robertThrowsABombOnlyWhileClearlyLosing() {
        fun throws(
            round: Int = 3,
            bombs: Int = 1,
            robertHp: Int = 300,
            playerHp: Int = 900,
            shots: Int = 4,
            bombInFlight: Boolean = false,
            missileInFlight: Boolean = false
        ) = BombRules.shouldEnemyThrow(
            round = round,
            bombsLeft = bombs,
            robertHp = robertHp,
            robertMaxHp = 1000,
            playerHp = playerHp,
            playerMaxHp = 1000,
            robertShots = shots,
            bombInFlight = bombInFlight,
            missileInFlight = missileInFlight
        )

        assertTrue(throws())
        assertTrue(throws(round = 4, bombs = 2, robertHp = 200, playerHp = 500))
        // Never before round three, never without a bomb left.
        assertFalse(throws(round = 2))
        assertFalse(throws(round = 1))
        assertFalse(throws(bombs = 0))
        // A healthy or winning Robert keeps his bombs.
        assertFalse(throws(robertHp = 900, playerHp = 300))
        assertFalse(throws(robertHp = 300, playerHp = 300))
        assertFalse(throws(robertHp = 600, playerHp = 900))
        // Not before the fight is underway, and never twice at once.
        assertFalse(throws(shots = 2))
        assertFalse(throws(bombInFlight = true))
        assertFalse(throws(missileInFlight = true))
        assertFalse(throws(robertHp = 0))
    }

    @Test
    fun heDeploysHisSingleAntiBombExplosivePerGame() {
        assertTrue(BombRules.willIntercept(3, antiBombsLeft = 1, bombInFlight = true))
        assertTrue(BombRules.willIntercept(5, antiBombsLeft = 1, bombInFlight = true))
        // Once used, never again in the same game (except unlimited in round five represented as 99).
        assertFalse(BombRules.willIntercept(4, antiBombsLeft = 0, bombInFlight = true))
        // No bomb in the air, no explosive spent.
        assertFalse(BombRules.willIntercept(4, antiBombsLeft = 1, bombInFlight = false))
        // Bombs do not exist before round three.
        assertFalse(BombRules.willIntercept(2, antiBombsLeft = 1, bombInFlight = true))
        assertFalse(BombRules.bombsUnlocked(2))
        assertTrue(BombRules.bombsUnlocked(3))
        assertEquals(3, BombRules.FIRST_BOMB_ROUND)
        assertEquals(0, BombRules.antiBombsForRound(2))
        assertEquals(5, BombRules.antiBombsForRound(5))
        assertEquals(3, BombRules.antiBombsForRound(4))
        assertEquals(2, BombRules.antiBombsForRound(3))
        // The player's own anti-bomb explosive is free from round three.
        assertTrue(BombRules.playerAntiBombsFree(3))
        assertTrue(BombRules.playerAntiBombsFree(4))
        assertFalse(BombRules.playerAntiBombsFree(2))
    }

    @Test
    fun situationalFireIntervalsUseUnpredictableOneAndTwoSecondBeats() {
        val random = Random(7)
        for (situation in RobertRules.CombatSituation.entries) {
            val samples = (1..60).map { RobertRules.fireIntervalMs(situation, false, random) }
            assertTrue("$situation=$samples", samples.all { it == 1_500L || it == 2_000L })
            assertEquals(setOf(1_500L, 2_000L), samples.toSet())
        }
        val fleeing = (1..30).map {
            RobertRules.fireIntervalMs(RobertRules.CombatSituation.ADVANTAGE, true, random)
        }
        assertTrue(fleeing.all { it == 2_000L })

        val continuous = (1..60).map {
            RobertRules.tacticalFireInterval(
                enemyHasDefence = true,
                playerHasDefence = false,
                playerWeaponDamaged = false,
                playerHpFraction = 0.8f,
                random = random
            )
        }
        assertTrue(continuous.all { it == RobertRules.CONTINUOUS_FIRE_MS })
        val losing = (1..30).map {
            RobertRules.tacticalFireInterval(
                enemyHasDefence = true,
                playerHasDefence = false,
                playerWeaponDamaged = false,
                playerHpFraction = 0.8f,
                random = random,
                enemyHasAdvantage = false
            )
        }
        assertTrue(losing.all { it == 1_500L || it == 2_000L })
    }

    @Test
    fun aOneSidedExchangeMakesHimRunAwayWhenDisadvantaged() {
        // Two or three sustained hits against one or two answering shots while disadvantaged.
        assertTrue(RobertRules.shouldFleeFromExchange(2, 1, 55f, atDisadvantage = true))
        assertTrue(RobertRules.shouldFleeFromExchange(3, 2, 95f, atDisadvantage = true))
        assertTrue(RobertRules.shouldFleeFromExchange(2, 0, 40f, atDisadvantage = true))
        // Default parameter remains true.
        assertTrue(RobertRules.shouldFleeFromExchange(2, 1, 55f))
        // Never flees from exchange when holding the advantage.
        assertFalse(RobertRules.shouldFleeFromExchange(2, 1, 55f, atDisadvantage = false))
        assertFalse(RobertRules.shouldFleeFromExchange(3, 2, 95f, atDisadvantage = false))
        // Not after a single hit, not when he is answering properly, and
        // never for hits that could not hurt him (the 0%-40% miss band).
        assertFalse(RobertRules.shouldFleeFromExchange(1, 1, 95f))
        assertFalse(RobertRules.shouldFleeFromExchange(2, 3, 95f))
        assertFalse(RobertRules.shouldFleeFromExchange(3, 1, 20f))
    }

    @Test
    fun situationAssessmentFollowsTheHealthGap() {
        assertEquals(
            RobertRules.CombatSituation.PRESSURE,
            RobertRules.assessSituation(100, 1000, 900, 1000, playerWeaponDamaged = true)
        )
        assertEquals(
            RobertRules.CombatSituation.ADVANTAGE,
            RobertRules.assessSituation(900, 1000, 500, 1000, playerWeaponDamaged = false)
        )
        assertEquals(
            RobertRules.CombatSituation.EVEN,
            RobertRules.assessSituation(600, 1000, 550, 1000, playerWeaponDamaged = false)
        )
        assertEquals(
            RobertRules.CombatSituation.DISADVANTAGE,
            RobertRules.assessSituation(300, 1000, 800, 1000, playerWeaponDamaged = false)
        )
    }

    @Test
    fun lockedSniperIsAlwaysUnlockedAgainstAGlueWall() {
        val profile = RobertRules.profile(1, 1)
        assertFalse(RobertRules.sniperAllowed(profile, false, 50))
        // A gloo wall is always detected and answered with the sniper rifle.
        assertTrue(RobertRules.sniperAllowed(profile, true, 150))
        assertTrue(RobertRules.sniperAllowed(profile, true, 99))
        assertTrue(RobertRules.sniperAllowed(profile, true, 1))

        val withoutWall = RobertRules.allowedWeapons(profile, false, 200)
        assertFalse(withoutWall.contains(Weapon.SNIPER))
        val situationMet = RobertRules.allowedWeapons(profile, true, 90)
        assertTrue(situationMet.contains(Weapon.SNIPER))
    }

    @Test
    fun roundOneMirrorsThePlayersSniperRifleAndGlooWall() {
        // Without the player's sniper the rifle stays locked in round one.
        assertTrue(RobertRules.profile(1, 1).sniperLocked)
        // Buying one hands Robert an unlocked sniper rifle as well.
        assertFalse(RobertRules.profile(1, 1, playerOwnsSniper = true).sniperLocked)
        // The mirror only exists in round one; from round three it is free.
        assertFalse(RobertRules.profile(3, 1, playerOwnsSniper = true).sniperLocked)

        // A player gloo wall gives him exactly one of his own in round one.
        assertEquals(0, RobertRules.profile(1, 1, playerGlueWalls = 0).glueWalls)
        assertEquals(1, RobertRules.profile(1, 1, playerGlueWalls = 2).glueWalls)
        // From round two he always carries two per game.
        for (round in 2..3) {
            assertEquals(
                "round $round gloo walls",
                RobertRules.ENEMY_GLUE_WALLS_FROM_ROUND_TWO,
                RobertRules.profile(round, 1, playerGlueWalls = 0).glueWalls
            )
        }
    }

    @Test
    fun theEnemyMirrorsThePlayersOwnFiringCadence() {
        // Face to face with no cover: exactly the player's rhythm.
        assertEquals(500L, RobertRules.mirroredFireInterval(500L, false, false))
        assertEquals(250L, RobertRules.mirroredFireInterval(250L, false, false))
        // Only a gloo wall, or only a cover: still exactly the player's rhythm.
        assertEquals(500L, RobertRules.mirroredFireInterval(500L, true, false))
        assertEquals(500L, RobertRules.mirroredFireInterval(500L, false, true))
        assertEquals(500L, RobertRules.mirroredFireInterval(500L, true, true))
        // Coverless against a covered player: never slower than one second.
        assertEquals(1_000L, RobertRules.mirroredFireInterval(2_000L, false, true))
        // ... and any faster cadence is matched at once.
        assertEquals(400L, RobertRules.mirroredFireInterval(400L, false, true))
        // A coverless enemy against a coverless player keeps the slow rhythm.
        assertEquals(2_000L, RobertRules.mirroredFireInterval(2_000L, false, false))

        assertTrue(RobertRules.mirrorsPlayerCadence(true, true, true))
        assertTrue(RobertRules.mirrorsPlayerCadence(false, false, true))
        assertFalse(RobertRules.mirrorsPlayerCadence(false, false, false))
    }

    @Test
    fun defencesStartBelowSixtyHealthAndAlternate() {
        assertFalse(RobertRules.shouldUseDefence(60, true))
        assertTrue(RobertRules.shouldUseDefence(59, true))
        assertFalse(RobertRules.shouldUseDefence(59, false))
        assertEquals(DefenceItem.MEDKIT, RobertRules.nextDefence(1, 1, 1))
        assertEquals(DefenceItem.COVER, RobertRules.nextDefence(0, 1, 1))
        assertEquals(DefenceItem.GLUE_WALL, RobertRules.nextDefence(0, 0, 1))
        assertEquals(null, RobertRules.nextDefence(0, 0, 0))
    }

    @Test
    fun fleeingNeedsContinuousHits() {
        assertFalse(RobertRules.shouldFlee(1, 2))
        assertTrue(RobertRules.shouldFlee(2, 2))
        assertTrue(RobertRules.shouldFlee(3, 3))
        assertFalse(RobertRules.shouldFlee(2, 3))
    }

    @Test
    fun movementGetsFasterInEveryRound() {
        var previous = 0f
        for (round in 1..5) {
            val speed = RobertRules.movementSpeedMultiplier(round)
            assertTrue("Round $round must be faster", speed > previous)
            previous = speed
        }
        assertEquals(1.2f, RobertRules.movementSpeedMultiplier(1), 0.0001f)
        assertEquals(2.0f, RobertRules.movementSpeedMultiplier(5), 0.0001f)
    }

    @Test
    fun heRandomlyPrefersTheMidOrTheCloseBand() {
        val random = Random(20261022)
        val picked = mutableSetOf<FireRange>()
        repeat(200) { picked.add(RobertRules.preferredRange(random)) }
        assertEquals(setOf(FireRange.MID, FireRange.CLOSE), picked)
        assertFalse(picked.contains(FireRange.FAR))
    }

    @Test
    fun deceptionTripsLastOneOrTwoShotsAndHappenSometimes() {
        val random = Random(99)
        val shots = mutableSetOf<Int>()
        repeat(200) { shots.add(RobertRules.deceptionShots(random)) }
        assertEquals(setOf(1, 2), shots)

        var deceptive = 0
        repeat(1000) {
            if (RobertRules.shouldStartDeception(random)) deceptive++
        }
        assertTrue(deceptive > 100)
        assertTrue(deceptive < 400)
    }

    @Test
    fun damagedWeaponOrGlueWallAlwaysPullsHimToPointBlank() {
        assertEquals(FireRange.CLOSE, RobertRules.fireRangeFor(FireRange.MID, true, false))
        assertEquals(FireRange.CLOSE, RobertRules.fireRangeFor(FireRange.FAR, true, false))
        assertEquals(FireRange.CLOSE, RobertRules.fireRangeFor(FireRange.MID, false, true))
        assertEquals(FireRange.MID, RobertRules.fireRangeFor(FireRange.MID, false, false))

        val proximity = Combat.proximityOf(FireRange.CLOSE.targetDistance)
        assertTrue(proximity >= Combat.HEAVY_ZONE_MIN)
        assertTrue(proximity <= Combat.FULL_PROXIMITY)
    }
}
