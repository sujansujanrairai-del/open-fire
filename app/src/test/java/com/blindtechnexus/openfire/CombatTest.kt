package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DamageTable
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.FireRange
import com.blindtechnexus.openfire.model.HitZone
import com.blindtechnexus.openfire.model.RobertRules
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies proximity zones, the single flat health-point table and defence
 * rules. A hit must always remove exactly the documented number of health
 * points, whether the victim has 200 or 2600 maximum health.
 */
class CombatTest {

    @Test
    fun zoneBoundaries() {
        assertEquals(HitZone.MISS, Combat.zoneFor(0f))
        assertEquals(HitZone.MISS, Combat.zoneFor(39.9f))
        assertEquals(HitZone.LIGHT, Combat.zoneFor(40f))
        assertEquals(HitZone.LIGHT, Combat.zoneFor(69.9f))
        assertEquals(HitZone.STRONG, Combat.zoneFor(70f))
        assertEquals(HitZone.STRONG, Combat.zoneFor(89.9f))
        assertEquals(HitZone.HEAVY, Combat.zoneFor(90f))
        assertEquals(HitZone.HEAVY, Combat.zoneFor(100f))
    }

    @Test
    fun missesDealNoDamageAndConnectedHitsNeverDealZero() {
        assertEquals(0, Combat.damageFor(20, 0f, 300))
        assertEquals(0, Combat.damageFor(20, 39.9f, 300))
        assertEquals(1, Combat.damageFor(1, 50f, 100))
        assertEquals(1, Combat.damageFor(1, 75f, 100))
        assertEquals(0, Combat.damageFor(20, 100f, 0))
    }

    @Test
    fun standardZoneMultipliersRemainFiftyAndEightyPercent() {
        // 20 stated health points: 10 / 16 / 20 by zone, never scaled by HP.
        assertEquals(10, Combat.damageFor(20, 40f, 500))
        assertEquals(16, Combat.damageFor(20, 70f, 500))
        assertEquals(20, Combat.damageFor(20, 90f, 500))
        assertEquals(20, Combat.damageFor(20, 100f, 500))
        assertEquals(20, Combat.damageFor(20, 100f, 2600))
    }

    @Test
    fun weaponPowersMatchTheSingleDamageTable() {
        // light band HP, strong band HP, heavy band HP
        val expected = mapOf(
            Weapon.KNIFE to Triple(8, 12, 14),
            Weapon.SHORT_GUN to Triple(6, 11, 16),
            Weapon.ASSAULT_GUN to Triple(8, 13, 18),
            Weapon.PISTOL to Triple(9, 14, 20),
            Weapon.SNIPER to Triple(10, 16, 22),
            Weapon.SWORD to Triple(10, 16, 22),
            Weapon.AWM to Triple(16, 26, 40),
            Weapon.MP40 to Triple(5, 8, 11),
            Weapon.MACHINE_GUN to Triple(7, 11, 15)
        )
        for ((weapon, row) in expected) {
            assertEquals("${weapon.id} light", row.first, weapon.powerPercent)
            assertEquals("${weapon.id} heavy", row.third, weapon.closeRangePowerPercent)
            assertEquals(0, Combat.powerPercentOf(weapon, 20f))
            assertEquals(row.first, Combat.powerPercentOf(weapon, 50f))
            assertEquals(row.second, Combat.powerPercentOf(weapon, 80f))
            assertEquals(row.third, Combat.powerPercentOf(weapon, 90f))
            assertEquals(row.third, Combat.powerPercentOf(weapon, 100f))
        }
    }

    @Test
    fun everyHitDeductsExactlyTheTableNumberOfHealthPoints() {
        // Point blank: the flat HP value of the table, independent of max HP.
        assertEquals(14, Combat.weaponDamage(Weapon.KNIFE, 100f, 1000))
        assertEquals(16, Combat.weaponDamage(Weapon.SHORT_GUN, 100f, 1000))
        assertEquals(18, Combat.weaponDamage(Weapon.ASSAULT_GUN, 100f, 1000))
        assertEquals(20, Combat.weaponDamage(Weapon.PISTOL, 100f, 1000))
        assertEquals(22, Combat.weaponDamage(Weapon.SNIPER, 100f, 1000))
        assertEquals(22, Combat.weaponDamage(Weapon.SWORD, 100f, 1000))
        assertEquals(40, Combat.weaponDamage(Weapon.AWM, 100f, 1000))
        assertEquals(11, Combat.weaponDamage(Weapon.MP40, 100f, 1000))
        assertEquals(15, Combat.weaponDamage(Weapon.MACHINE_GUN, 100f, 1000))

        assertEquals(6, Combat.weaponDamage(Weapon.SHORT_GUN, 50f, 1000))
        assertEquals(11, Combat.weaponDamage(Weapon.SHORT_GUN, 80f, 1000))
        assertEquals(8, Combat.weaponDamage(Weapon.ASSAULT_GUN, 50f, 1000))
        assertEquals(13, Combat.weaponDamage(Weapon.ASSAULT_GUN, 80f, 1000))
        assertEquals(0, Combat.weaponDamage(Weapon.SWORD, 20f, 1000))

        // The old bug: a "14" weapon used to deal 140 damage to a 1000 HP
        // victim. Maximum health must not change a single hit any more.
        for (maxHp in listOf(200, 1000, 2600)) {
            assertEquals("assault at $maxHp", 18, Combat.weaponDamage(Weapon.ASSAULT_GUN, 100f, maxHp))
            assertEquals("AWM at $maxHp", 40, Combat.weaponDamage(Weapon.AWM, 100f, maxHp))
        }

        // The same table drives Robert: both fighters deduct exactly the
        // documented health points, never 100 or 150 by surprise.
        for (weapon in RobertRules.ALL_STANDARD_WEAPONS) {
            for (proximity in listOf(10f, 50f, 80f, 95f)) {
                assertEquals(
                    "${weapon.id} at $proximity",
                    Combat.weaponDamage(weapon, proximity, 1000),
                    RobertRules.weaponDamage(weapon, proximity, 1000)
                )
                assertEquals(
                    Combat.powerPercentOf(weapon, proximity),
                    RobertRules.powerFor(weapon, proximity)
                )
            }
        }
    }

    @Test
    fun theAwmReachesTheFullFortyPointCeiling() {
        // The rebalance makes the AWM the only normal weapon that reaches the
        // documented maximum 40 percent health reduction of one hit.
        assertEquals(40, DamageTable.percentOf(Weapon.AWM, HitZone.HEAVY))
        assertEquals(40, Combat.weaponDamage(Weapon.AWM, 100f, 200))
        // Every other normal weapon stays strictly below it, in capacity order.
        val heavy = listOf(
            Weapon.MP40, Weapon.KNIFE, Weapon.MACHINE_GUN, Weapon.SHORT_GUN,
            Weapon.ASSAULT_GUN, Weapon.PISTOL, Weapon.SNIPER, Weapon.SWORD
        ).map { DamageTable.percentOf(it, HitZone.HEAVY) }
        assertEquals(heavy.sorted(), heavy)
        assertTrue(heavy.all { it < 40 })
    }

    @Test
    fun noNormalWeaponCanTakeMoreThanFortyHealthPointsInOneHit() {
        assertEquals(40, DamageTable.MAX_WEAPON_DAMAGE_HP)
        assertEquals(DamageTable.MAX_WEAPON_DAMAGE_HP, DamageTable.MAX_WEAPON_PERCENT)
        assertTrue(DamageTable.isValid())
        for (weapon in RobertRules.ALL_STANDARD_WEAPONS) {
            for (zone in HitZone.entries) {
                assertTrue(
                    "${weapon.id} ${zone.name} must stay under the cap",
                    DamageTable.percentOf(weapon, zone) <= DamageTable.MAX_WEAPON_DAMAGE_HP
                )
            }
            // Even a point blank hit of the strongest rifle stays capped.
            assertTrue(Combat.weaponDamage(weapon, 100f, 2600) <= 40)
        }
    }

    @Test
    fun explosivesAreLethalButNormalWeaponsNeverAre() {
        assertEquals(100, DamageTable.EXPLOSIVE_PERCENT)
        for (weapon in listOf(
            Weapon.MISSILE, Weapon.GRENADE, Weapon.ATOM_BOMB, Weapon.TIMER_BOMB
        )) {
            assertTrue(DamageTable.isExplosive(weapon))
            for (zone in HitZone.entries) {
                assertEquals(100, DamageTable.percentOf(weapon, zone))
            }
            assertEquals(1000, Combat.weaponDamage(weapon, 100f, 1000))
        }
        assertFalse(DamageTable.isExplosive(Weapon.SNIPER))
    }

    @Test
    fun theAwmHitsInsideItsLongRangeBandOnly() {
        assertTrue(DamageTable.isAwmLongRangeBand(Weapon.AWM, 20f))
        assertTrue(DamageTable.isAwmLongRangeBand(Weapon.AWM, 39.9f))
        assertFalse(DamageTable.isAwmLongRangeBand(Weapon.AWM, 19.9f))
        assertFalse(DamageTable.isAwmLongRangeBand(Weapon.AWM, 40f))
        assertFalse(DamageTable.isAwmLongRangeBand(Weapon.SNIPER, 30f))

        // Exactly 14 health points in the long-range band, at any max health.
        assertEquals(14, Combat.weaponDamage(Weapon.AWM, 30f, 1000))
        assertEquals(14, DamageTable.awmLongRangeDamage(1000))
        assertEquals(14, DamageTable.awmLongRangeDamage(200))
        assertEquals(14, DamageTable.awmLongRangeDamage(50))
        assertEquals(DamageTable.AWM_LONG_RANGE_DAMAGE_HP, DamageTable.awmLongRangeDamage(1000))
        // Outside the band the normal table applies.
        assertEquals(0, Combat.weaponDamage(Weapon.AWM, 10f, 1000))
        assertEquals(16, Combat.weaponDamage(Weapon.AWM, 50f, 1000))
    }

    @Test
    fun menuProtectionIsAFixedTinyNumberOfHealthPoints() {
        assertEquals(2, DamageTable.MENU_PROTECTION_HP)
        assertEquals(6_000L, DamageTable.MENU_PROTECTION_MS)
        assertEquals(2, Combat.menuProtectedDamage(1000))
        assertEquals(2, Combat.menuProtectedDamage(200))
        assertEquals(1, Combat.menuProtectedDamage(1))
        assertEquals(0, Combat.menuProtectedDamage(0))
    }

    @Test
    fun theWallToleranceDecidesWhenABoundaryBlocksAMove() {
        assertEquals(1.5f, Combat.WALL_TOLERANCE_M, 0.0001f)
    }

    @Test
    fun proximityUsesTheOneHundredMeterArena() {
        assertEquals(0f, Combat.proximityOf(100f), 0.001f)
        assertEquals(40f, Combat.proximityOf(60f), 0.001f)
        assertEquals(70f, Combat.proximityOf(30f), 0.001f)
        assertEquals(90f, Combat.proximityOf(10f), 0.001f)
        assertEquals(100f, Combat.proximityOf(0f), 0.001f)
        assertEquals(0f, Combat.proximityOf(150f), 0.001f)
        assertEquals(100f, Combat.proximityOf(-20f), 0.001f)
    }

    @Test
    fun distanceAndProximityAreInverseOperations() {
        for (proximity in listOf(0f, 15f, 40f, 55f, 70f, 95f, 100f)) {
            assertEquals(proximity, Combat.proximityOf(Combat.distanceFor(proximity)), 0.001f)
        }
        assertEquals(80f, FireRange.FAR.targetDistance, 0.001f)
        assertEquals(45f, FireRange.MID.targetDistance, 0.001f)
        assertEquals(20f, FireRange.STRONG.targetDistance, 0.001f)
        assertEquals(5f, FireRange.CLOSE.targetDistance, 0.001f)
    }

    @Test
    fun secondChanceRestoresExactlyHalfHealth() {
        assertEquals(100, Combat.secondChanceHealth(200))
        assertEquals(450, Combat.secondChanceHealth(900))
        assertEquals(1300, Combat.secondChanceHealth(2600))
        assertEquals(1, Combat.secondChanceHealth(1))
    }

    @Test
    fun onlySniperShotsAtPointBlankCanBreakAGlueWall() {
        assertTrue(Combat.canBreakGlueWall(Weapon.SNIPER, 90f))
        assertTrue(Combat.canBreakGlueWall(Weapon.SNIPER, 100f))
        assertFalse(Combat.canBreakGlueWall(Weapon.SNIPER, 89.9f))
        assertFalse(Combat.canBreakGlueWall(Weapon.ASSAULT_GUN, 100f))
        assertEquals(3, Combat.GLUE_WALL_BREAK_HITS)
    }

    @Test
    fun coverAndRecoveryRulesAreStable() {
        assertEquals(11, Combat.COVER_MAX_HITS)
        assertEquals(100, Combat.GUN_RECOVERY_COST)
        // Every defence costs 200 coins less than before.
        assertEquals(300, DefenceItem.MEDKIT.price)
        assertEquals(650, DefenceItem.GLUE_WALL.price)
        assertEquals(450, DefenceItem.COVER.price)
        assertEquals(2800, DefenceItem.ANTI_MISSILE.price)
        assertEquals(2600, DefenceItem.ANTI_BOMB.price)
    }

    @Test
    fun threeConsecutivePointBlankHitsDamageTheWeapon() {
        var streak = 0
        streak = Combat.nextHeavyHitStreak(streak, 95f)
        assertEquals(1, streak)
        assertFalse(Combat.gunBreaks(streak))
        streak = Combat.nextHeavyHitStreak(streak, 90f)
        streak = Combat.nextHeavyHitStreak(streak, 100f)
        assertTrue(Combat.gunBreaks(streak))
        assertEquals(0, Combat.nextHeavyHitStreak(streak, 80f))
    }
}
