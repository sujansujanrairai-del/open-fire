package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.AutomaticFirePowerMap
import com.blindtechnexus.openfire.model.DamageTable
import com.blindtechnexus.openfire.model.FireCadence
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticFirePowerMapTest {
    @Test
    fun cadenceAndDamageIncreaseWithBulletPower() {
        val expected = linkedMapOf(
            Weapon.SUBMACHINE_GUN to Pair(500L, listOf(0, 13, 21, 26)),
            Weapon.AK47 to Pair(1_000L, listOf(0, 29, 46, 58)),
            Weapon.MACHINE_GUN to Pair(1_500L, listOf(0, 35, 56, 70))
        )
        assertEquals(expected.keys, AutomaticFirePowerMap.profiles.keys)

        for ((weapon, cadenceAndDamage) in expected) {
            val profile = AutomaticFirePowerMap.profileFor(weapon)!!
            assertEquals(cadenceAndDamage.first, profile.intervalMs)
            assertEquals(cadenceAndDamage.first, AutomaticFirePowerMap.intervalFor(weapon))
            assertEquals(cadenceAndDamage.first, weapon.burstIntervalMs)
            assertEquals(
                cadenceAndDamage.second,
                listOf(
                    profile.missDamageHp,
                    profile.lightDamageHp,
                    profile.strongDamageHp,
                    profile.heavyDamageHp
                )
            )
        }
        assertEquals(FireCadence.AUTO_FAST_MS, AutomaticFirePowerMap.intervalFor(Weapon.SUBMACHINE_GUN))
        assertEquals(FireCadence.AUTO_MEDIUM_MS, AutomaticFirePowerMap.intervalFor(Weapon.AK47))
        assertEquals(FireCadence.AUTO_SLOW_MS, AutomaticFirePowerMap.intervalFor(Weapon.MACHINE_GUN))
    }

    @Test
    fun automaticBulletsHitByPositionWithTheSharedDamageTable() {
        for (weapon in Weapon.automatic()) {
            for ((proximity, expectedZoneDamage) in listOf(
                20f to 0,
                50f to DamageTable.percentAt(weapon, 50f),
                80f to DamageTable.percentAt(weapon, 80f),
                95f to DamageTable.percentAt(weapon, 95f)
            )) {
                assertEquals(
                    "${weapon.id} at $proximity%",
                    expectedZoneDamage,
                    AutomaticFirePowerMap.damageAt(weapon, proximity, 2_600)
                )
            }
            // Damage is flat HP, never scaled by the victim's maximum health.
            assertEquals(
                AutomaticFirePowerMap.damageAt(weapon, 95f, 2_600),
                AutomaticFirePowerMap.damageAt(weapon, 95f, 200)
            )
            assertTrue(AutomaticFirePowerMap.damageAt(weapon, 95f, 10) <= 10)
        }
    }

    @Test
    fun singleShotWeaponsHaveNoAutomaticCadenceAndKeepTheirOriginalDamage() {
        for (weapon in Weapon.entries - Weapon.automatic().toSet()) {
            assertNull(AutomaticFirePowerMap.intervalFor(weapon))
            for (proximity in listOf(20f, 50f, 80f, 95f)) {
                assertEquals(
                    com.blindtechnexus.openfire.model.Combat.weaponDamage(weapon, proximity, 200),
                    AutomaticFirePowerMap.damageAt(weapon, proximity, 200)
                )
            }
        }
    }
}
