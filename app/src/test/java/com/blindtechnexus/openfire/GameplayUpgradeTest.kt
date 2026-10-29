package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.audio.AudioManager
import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.DamageTable
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.Progression
import com.blindtechnexus.openfire.model.RewardBundle
import com.blindtechnexus.openfire.model.RobertRules
import com.blindtechnexus.openfire.model.Shop
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Regression coverage for the v2.4 gameplay, AI, audio and shop upgrades. */
class GameplayUpgradeTest {

    @Test
    fun damageIsFlatHealthPointsAtEveryRoundHealth() {
        for (weapon in RobertRules.ALL_STANDARD_WEAPONS) {
            val power = DamageTable.percentOf(weapon, com.blindtechnexus.openfire.model.HitZone.HEAVY)
            for (maxHp in listOf(200, 700, 1200, 2600)) {
                assertEquals(
                    "${weapon.id} must deal its stated $power HP at $maxHp max HP",
                    power,
                    Combat.weaponDamage(weapon, 100f, maxHp)
                )
            }
        }
        // The exact bug that was reported: 18 must never become 180.
        assertEquals(18, Combat.weaponDamage(Weapon.ASSAULT_GUN, 100f, 1000))
        assertEquals(18, Combat.weaponDamage(Weapon.ASSAULT_GUN, 100f, 2600))
        assertEquals(18, RobertRules.weaponDamage(Weapon.ASSAULT_GUN, 100f, 2600))
    }

    @Test
    fun normalFireIsBetweenOneAndAHalfAndTwoSeconds() {
        val random = Random(99)
        repeat(300) {
            val interval = RobertRules.tacticalFireInterval(
                enemyHasDefence = false,
                playerHasDefence = false,
                playerWeaponDamaged = false,
                playerHpFraction = 1f,
                random = random
            )
            assertTrue("normal interval=$interval", interval in 1_500L..2_000L)
        }
        assertTrue(
            "normal fire must use both tactical beats",
            (1..100).map { RobertRules.normalTacticalInterval(random) }.toSet() == setOf(1_500L, 2_000L)
        )
    }

    @Test
    fun protectedRobertFiresAtTheFastContinuousBeat() {
        val random = Random(5)
        assertEquals(
            1_500L,
            RobertRules.tacticalFireInterval(
                enemyHasDefence = true,
                playerHasDefence = false,
                playerWeaponDamaged = false,
                playerHpFraction = 1f,
                random = random
            )
        )
        // Damaged weapon and an extremely weak player also allow the press.
        assertTrue(
            RobertRules.shouldPressFromDefence(
                enemyHasDefence = false,
                playerHasDefence = false,
                playerWeaponDamaged = true,
                playerHpFraction = 1f
            )
        )
        assertTrue(
            RobertRules.shouldPressFromDefence(
                enemyHasDefence = false,
                playerHasDefence = true,
                playerWeaponDamaged = false,
                playerHpFraction = 0.05f
            )
        )
        // Matching barriers cancel the advantage: back to the human rhythm.
        val interval = RobertRules.tacticalFireInterval(
            enemyHasDefence = true,
            playerHasDefence = true,
            playerWeaponDamaged = false,
            playerHpFraction = 1f,
            random = random
        )
        assertTrue("matched barriers interval=$interval", interval in 1_500L..2_000L)
    }

    @Test
    fun hitExchangeMakesRobertFleeOnlyWhenThePlayerIsAhead() {
        // Two hits taken against one landed: immediate retreat.
        assertTrue(RobertRules.shouldFleeFight(2, 1))
        assertTrue(RobertRules.shouldFleeFight(3, 1))
        // Landing more hits means he stays and fights.
        assertFalse(RobertRules.shouldFleeFight(1, 2))
        assertFalse(RobertRules.shouldFleeFight(2, 2))
        assertFalse(RobertRules.shouldFleeFight(2, 3))
        // A single hit is never enough to panic.
        assertFalse(RobertRules.shouldFleeFight(1, 0))
    }

    @Test
    fun roundFourEnemyRulesAndThePlayerReward() {
        // Round four: one enemy missile power, two interceptors.
        assertEquals(1, MissileRules.enemyMissilePowersForRound(4))
        assertEquals(2, MissileRules.antiMissilesForRound(4))
        assertEquals(2, BombRules.antiBombsForRound(3))
        assertEquals(5, BombRules.antiBombsForRound(5))
        assertEquals(2, MissileRules.enemyMissilePowersForRound(5))
        assertEquals(4, MissileRules.antiMissilesForRound(5))

        // The anti-missile system unlocks after round three and grants one
        // free interceptor exactly once.
        assertEquals(3, DefenceItem.ANTI_MISSILE.requiredCompletedRounds)
        assertFalse(Progression.antiMissileUnlocked(com.blindtechnexus.openfire.model.GameStats(completedRounds = 2)))
        assertTrue(Progression.antiMissileUnlocked(com.blindtechnexus.openfire.model.GameStats(completedRounds = 3)))
        assertTrue(Progression.antiMissileRewardDue(4, granted = false))
        assertFalse(Progression.antiMissileRewardDue(4, granted = true))
        assertFalse(Progression.antiMissileRewardDue(3, granted = false))

        val claimed = Shop.claimAntiMissileReward(Inventory())
        assertNotNull(claimed)
        assertEquals(1, claimed!!.antiMissiles)
        assertTrue(claimed.antiMissileRewardGranted)
        assertNull(Shop.claimAntiMissileReward(claimed))

        val bundle = RewardBundle(mapOf(DefenceItem.COVER to 2)) +
            RewardBundle(mapOf(DefenceItem.ANTI_MISSILE to 1))
        assertEquals(2, bundle.covers)
        assertEquals(1, bundle.count(DefenceItem.ANTI_MISSILE))
    }

    @Test
    fun lockedShopRowsHaveTheirOwnDynamicUnlockSentence() {
        assertEquals("unlockAfterRound1", Shop.unlockReasonKey(1))
        assertEquals("unlockAfterRound2", Shop.unlockReasonKey(2))
        assertEquals("unlockAfterRound3", Shop.unlockReasonKey(3))
        assertEquals("unlockAfterRound4", Shop.unlockReasonKey(4))
        assertTrue(Shop.isLocked(Weapon.MACHINE_GUN.requiredCompletedRounds, 2))
        assertFalse(Shop.isLocked(Weapon.MACHINE_GUN.requiredCompletedRounds, 3))
        assertFalse(Shop.isLocked(0, 0))
        // Every round-gated item has a real translated sentence in all three
        // languages, and the sentence is not the raw key.
        for (rounds in listOf(1, 2, 3, 4)) {
            val key = Shop.unlockReasonKey(rounds)
            for (lang in listOf("en", "hi", "ne")) {
                val text = Strings.get(key, lang)
                assertTrue("$key/$lang is blank", text.isNotBlank())
                if (lang != "en") assertTrue("$key/$lang leaked", text != key)
            }
        }
    }

    @Test
    fun weaponSoundsAreExclusiveAndNoStrayAliasesRemain() {
        for (key in listOf(
            "assault", "shortGun", "pistol", "sniperRifle", "awm",
            "mp40", "machineGun", "knifeSwish", "knifeMiss", "swordGlide", "swordMiss"
        )) {
            assertTrue("$key must be exclusive", key in AudioManager.WEAPON_SOUND_KEYS)
        }
        // Retired duplicate gunshot aliases can never fire a stray shot.
        for (retired in listOf("fire", "shotgun", "reload", "gloowall")) {
            assertFalse("$retired must not be a weapon key", retired in AudioManager.WEAPON_SOUND_KEYS)
        }
        for (footstep in listOf("walk", "playerWalk", "enemyWalk")) {
            assertFalse("$footstep must not cut a weapon", footstep in AudioManager.WEAPON_SOUND_KEYS)
        }
    }
}
