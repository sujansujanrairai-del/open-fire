package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DamageTable
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Facing
import com.blindtechnexus.openfire.model.FireRange
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.movePlayerLateral
import com.blindtechnexus.openfire.model.withPositions
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
        // The ladder: half of the point-blank power, 80 percent of it, then
        // the full power. The ceiling is 80 HP (the AWM), the floor is the
        // submachine gun at 26 HP point-blank.
        val expected = mapOf(
            Weapon.KNIFE to Triple(16, 26, 32),
            Weapon.SHORT_GUN to Triple(19, 30, 38),
            Weapon.ASSAULT_GUN to Triple(22, 35, 44),
            Weapon.PISTOL to Triple(25, 40, 50),
            Weapon.SNIPER to Triple(32, 51, 64),
            Weapon.GLUE_BREAKER to Triple(17, 27, 34),
            Weapon.SWORD to Triple(27, 43, 54),
            Weapon.AWM to Triple(40, 64, 80),
            Weapon.M10 to Triple(21, 34, 42),
            Weapon.SUBMACHINE_GUN to Triple(13, 21, 26),
            Weapon.AK47 to Triple(29, 46, 58),
            Weapon.MACHINE_GUN to Triple(35, 56, 70)
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
        for (weapon in RobertRules.ALL_STANDARD_WEAPONS) {
            for (proximity in listOf(10f, 50f, 80f, 95f)) {
                val expected = DamageTable.percentAt(weapon, proximity)
                for (maxHp in listOf(200, 1000, 2600)) {
                    assertEquals("${weapon.id} at $proximity and $maxHp", expected, Combat.weaponDamage(weapon, proximity, maxHp))
                    assertEquals(expected, RobertRules.weaponDamage(weapon, proximity, maxHp))
                }
            }
        }
        assertEquals(44, Combat.weaponDamage(Weapon.ASSAULT_GUN, 100f, 2600))
        assertEquals(16, Combat.weaponDamage(Weapon.KNIFE, 50f, 200))
    }

    @Test
    fun theAwmReachesTheEightyPointCeiling() {
        assertEquals(80, DamageTable.percentOf(Weapon.AWM, HitZone.HEAVY))
        assertEquals(80, Combat.weaponDamage(Weapon.AWM, 100f, 200))
        assertTrue(RobertRules.ALL_STANDARD_WEAPONS.filter { it != Weapon.AWM }
            .all { DamageTable.percentOf(it, HitZone.HEAVY) < 80 })
    }

    @Test
    fun noWeaponCanTakeMoreThanEightyHealthPointsInOneHit() {
        assertEquals(80, DamageTable.MAX_WEAPON_DAMAGE_HP)
        assertTrue(DamageTable.isValid())
        for (weapon in Weapon.entries) {
            for (zone in HitZone.entries) {
                assertTrue("${weapon.id} ${zone.name}", DamageTable.percentOf(weapon, zone) in 0..80)
            }
            assertTrue(Combat.weaponDamage(weapon, 100f, 2600) <= 80)
        }
    }

    @Test
    fun theWeaponTableIsARealPowerLadder() {
        // Every weapon is placed on the ladder by its own power: light hits
        // are half of the point-blank power, strong hits 80 percent of it, and
        // the weakest automatic is the only weapon under 30 HP point-blank.
        for (weapon in RobertRules.ALL_STANDARD_WEAPONS) {
            val heavy = weapon.closeRangePowerPercent
            assertEquals("${weapon.id} light", heavy / 2, DamageTable.percentOf(weapon, HitZone.LIGHT))
            assertEquals(
                "${weapon.id} strong",
                Math.round(heavy * Combat.STRONG_DAMAGE_FRACTION),
                DamageTable.percentOf(weapon, HitZone.STRONG)
            )
            assertEquals("${weapon.id} heavy", heavy, DamageTable.percentOf(weapon, HitZone.HEAVY))
        }
        assertEquals(26, Weapon.SUBMACHINE_GUN.closeRangePowerPercent)
        assertEquals(80, Weapon.AWM.closeRangePowerPercent)
        assertEquals(
            80,
            RobertRules.ALL_STANDARD_WEAPONS.maxOf { it.closeRangePowerPercent }
        )
    }

    @Test
    fun explosivesAreCappedLikeOtherHits() {
        val damages = mapOf(Weapon.GRENADE to 40, Weapon.TIMER_BOMB to 50,
            Weapon.ATOM_BOMB to 60, Weapon.MISSILE to 60)
        for ((weapon, expected) in damages) {
            assertTrue(DamageTable.isExplosive(weapon))
            for (zone in HitZone.entries) assertEquals(expected, DamageTable.percentOf(weapon, zone))
            assertEquals(expected, Combat.weaponDamage(weapon, 100f, 1000))
            assertEquals(20, Combat.weaponDamage(weapon, 100f, 20))
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
        for (hp in listOf(200, 1000, 2600)) assertEquals(32, Combat.weaponDamage(Weapon.AWM, 30f, hp))
        assertEquals(0, Combat.weaponDamage(Weapon.AWM, 10f, 1000))
        assertEquals(40, Combat.weaponDamage(Weapon.AWM, 50f, 1000))
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
        assertEquals(0.75f, Combat.WALL_TOLERANCE_M, 0.0001f)
    }

    @Test
    fun proximityUsesTheTwentyFiveMeterSquare() {
        assertEquals(25f, Combat.MAX_RANGE, 0.001f)
        assertEquals(0f, Combat.proximityOf(25f), 0.001f)
        assertEquals(40f, Combat.proximityOf(15f), 0.001f)
        assertEquals(70f, Combat.proximityOf(7.5f), 0.001f)
        assertEquals(90f, Combat.proximityOf(2.5f), 0.001f)
        assertEquals(100f, Combat.proximityOf(0f), 0.001f)
        assertEquals(0f, Combat.proximityOf(40f), 0.001f)
        assertEquals(100f, Combat.proximityOf(-20f), 0.001f)
    }

    @Test
    fun distanceAndProximityAreInverseOperations() {
        for (proximity in listOf(0f, 15f, 40f, 55f, 70f, 95f, 100f)) {
            assertEquals(proximity, Combat.proximityOf(Combat.distanceFor(proximity)), 0.001f)
        }
        assertEquals(20f, FireRange.FAR.targetDistance, 0.001f)
        assertEquals(11.25f, FireRange.MID.targetDistance, 0.001f)
        assertEquals(5f, FireRange.STRONG.targetDistance, 0.001f)
        assertEquals(1.25f, FireRange.CLOSE.targetDistance, 0.001f)
    }

    @Test
    fun secondChanceRestoresExactlyHalfHealth() {
        assertEquals(100, Combat.secondChanceHealth(200))
        assertEquals(450, Combat.secondChanceHealth(900))
        assertEquals(1300, Combat.secondChanceHealth(2600))
        assertEquals(1, Combat.secondChanceHealth(1))
    }

    @Test
    fun onlyTheGlooWallBreakerAtPointBlankCanBreakAGlueWall() {
        assertTrue(Combat.canBreakGlueWall(Weapon.GLUE_BREAKER, 90f))
        assertTrue(Combat.canBreakGlueWall(Weapon.GLUE_BREAKER, 100f))
        assertFalse(Combat.canBreakGlueWall(Weapon.GLUE_BREAKER, 89.9f))
        assertFalse(Combat.canBreakGlueWall(Weapon.ASSAULT_GUN, 100f))
        // The sniper rifle no longer breaks walls: the breaker replaced it.
        assertFalse(Combat.canBreakGlueWall(Weapon.SNIPER, 90f))
        assertFalse(Combat.canBreakGlueWall(Weapon.SNIPER, 100f))
        assertEquals(3, Combat.GLUE_WALL_BREAK_HITS)
        assertEquals(90f, Combat.GLUE_WALL_BREAK_PROXIMITY, 0.001f)
    }

    @Test
    fun shotsUseDistanceNotMovementOrFacing() {
        // Robert is to the right while the player has just moved left. The
        // enemy can also move further right: neither direction blocks a hit.
        val initial = GameState().withPositions(
            playerX = 10f, playerY = 12f, robertX = 15f, robertY = 12f
        )
        val movingLeft = initial.movePlayerLateral(right = false, metres = 1f).state
            .withPositions(robertX = 16f)
        assertEquals(Facing.LEFT, movingLeft.facing)
        assertEquals(7f, movingLeft.distance, 0.001f)

        for (facing in Facing.entries) {
            val state = movingLeft.copy(playerFacing = facing.id)
            val proximity = Combat.proximityOf(state.distance)
            assertEquals(HitZone.STRONG, Combat.zoneFor(proximity))
            // 72 percent proximity is the strong band: 80 percent of the
            // assault rifle's 44 HP point-blank power.
            assertEquals(35, Combat.weaponDamage(Weapon.ASSAULT_GUN, proximity, 200))
        }

        // Being genuinely out of range still misses, regardless of facing.
        val far = movingLeft.withPositions(robertX = 25f, robertY = 25f)
        assertEquals(HitZone.MISS, Combat.zoneFor(Combat.proximityOf(far.distance)))
        assertEquals(0, Combat.weaponDamage(Weapon.ASSAULT_GUN, Combat.proximityOf(far.distance), 200))
        // The AWM's existing long-range exception still applies.
        val awmRange = initial.withPositions(playerX = 6f, robertX = 25f, robertY = 12f)
        assertEquals(32, Combat.weaponDamage(Weapon.AWM, Combat.proximityOf(awmRange.distance), 200))
    }

    @Test
    fun coverAndRecoveryRulesAreStable() {
        assertEquals(11, Combat.COVER_MAX_HITS)
        assertEquals(100, Combat.GUN_RECOVERY_COST)
        // Defences have been rebalanced and remain below the price cap.
        assertEquals(250, DefenceItem.MEDKIT.price)
        assertEquals(550, DefenceItem.GLUE_WALL.price)
        assertEquals(400, DefenceItem.COVER.price)
        assertEquals(1800, DefenceItem.ANTI_MISSILE.price)
        assertEquals(1700, DefenceItem.ANTI_BOMB.price)
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
