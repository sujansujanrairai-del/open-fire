package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.ExtensionState
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.Rewards
import com.blindtechnexus.openfire.model.Shop
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks every shop price, purchase, removal and consumption rule. */
class ShopTest {

    @Test
    fun defaultInventoryOwnsOnlyTheFreeWeapons() {
        val inventory = Inventory()
        assertTrue(inventory.ownsWeapon(Weapon.SHORT_GUN))
        assertTrue(inventory.ownsWeapon(Weapon.ASSAULT_GUN))
        assertFalse(inventory.ownsWeapon(Weapon.PISTOL))
        assertFalse(inventory.ownsWeapon(Weapon.SNIPER))
        assertFalse(inventory.ownsWeapon(Weapon.KNIFE))
        assertFalse(inventory.ownsWeapon(Weapon.SWORD))
        assertFalse(inventory.ownsWeapon(Weapon.MISSILE))
        assertEquals(0, inventory.missilePowers)
        assertFalse(inventory.hasMissilePower)
        assertEquals(2, inventory.ownedWeapons().size)
    }

    @Test
    fun weaponPricesMatchTheShop() {
        // Every weapon costs 200 coins less than before.
        assertEquals(300, Weapon.KNIFE.price)
        assertEquals(550, Weapon.PISTOL.price)
        assertEquals(750, Weapon.SWORD.price)
        assertEquals(1000, Weapon.SNIPER.price)
        assertEquals(900, Weapon.MP40.price)
        assertEquals(1400, Weapon.AWM.price)
        assertEquals(1900, Weapon.MACHINE_GUN.price)
        assertEquals(700, Weapon.GRENADE.price)
        assertEquals(2400, Weapon.ATOM_BOMB.price)
        assertEquals(2000, Weapon.TIMER_BOMB.price)
        for (weapon in Weapon.forSale()) {
            assertTrue("${weapon.id} must be cheaper", weapon.price > 0)
        }
    }

    @Test
    fun buyingAWeaponDeductsCoinsAndAddsIt() {
        val result = Shop.buyWeapon(Inventory(), 1000, Weapon.PISTOL)
        assertTrue(result.success)
        assertEquals(450, result.coins)
        assertTrue(result.inventory.ownsWeapon(Weapon.PISTOL))
    }

    @Test
    fun buyingWithoutEnoughCoinsFailsWithoutChanges() {
        val result = Shop.buyWeapon(Inventory(), 549, Weapon.PISTOL)
        assertFalse(result.success)
        assertEquals(549, result.coins)
        assertEquals("notEnoughCoins", result.reasonKey)
        assertFalse(result.inventory.ownsWeapon(Weapon.PISTOL))
    }

    @Test
    fun aWeaponCannotBeBoughtTwice() {
        val owned = Shop.buyWeapon(Inventory(), 2000, Weapon.SNIPER).inventory
        val again = Shop.buyWeapon(owned, 2000, Weapon.SNIPER)
        assertFalse(again.success)
        assertEquals("alreadyOwned", again.reasonKey)
        assertEquals(2000, again.coins)
    }

    @Test
    fun boughtWeaponsCanBeRemovedButFreeWeaponsCannot() {
        val owned = Shop.buyWeapon(Inventory(), 1000, Weapon.SWORD).inventory
        val removed = Shop.removeWeapon(owned, 1000, Weapon.SWORD)
        assertTrue(removed.success)
        assertFalse(removed.inventory.ownsWeapon(Weapon.SWORD))

        val free = Shop.removeWeapon(Inventory(), 1000, Weapon.SHORT_GUN)
        assertFalse(free.success)
        assertEquals("cannotRemoveWeapon", free.reasonKey)
    }

    @Test
    fun removingTheRememberedWeaponFallsBackToTheDefault() {
        val owned = Shop.buyWeapon(Inventory(), 1000, Weapon.KNIFE).inventory
            .copy(lastWeaponId = Weapon.KNIFE.id)
        val removed = Shop.removeWeapon(owned, 0, Weapon.KNIFE).inventory
        assertEquals(Weapon.SHORT_GUN.id, removed.lastWeaponId)
        assertEquals(Weapon.SHORT_GUN, removed.startingWeapon())
    }

    @Test
    fun defencesAreRepeatableAndDecreaseOnUse() {
        var inventory = Inventory()
        var coins = 2000
        repeat(3) {
            val bought = Shop.buyDefence(inventory, coins, DefenceItem.MEDKIT)
            assertTrue(bought.success)
            inventory = bought.inventory
            coins = bought.coins
        }
        assertEquals(3, inventory.medikits)
        assertEquals(1100, coins)

        inventory = Shop.consume(inventory, DefenceItem.MEDKIT)
        assertEquals(2, inventory.medikits)
        assertTrue(inventory.count(DefenceItem.MEDKIT) >= 0)
    }

    @Test
    fun antiMissileDefenceCostsTwentyEightHundredAndUnlocksAfterRoundThree() {
        assertEquals(2800, DefenceItem.ANTI_MISSILE.price)
        assertEquals(3, DefenceItem.ANTI_MISSILE.requiredCompletedRounds)

        val locked = Shop.buyDefence(
            Inventory(),
            5000,
            DefenceItem.ANTI_MISSILE,
            completedRounds = 1
        )
        assertFalse(locked.success)
        assertEquals("antiMissileLocked", locked.reasonKey)
        assertEquals(5000, locked.coins)
        assertEquals(0, locked.inventory.antiMissiles)

        val lockedAfterTwo = Shop.buyDefence(
            Inventory(),
            5000,
            DefenceItem.ANTI_MISSILE,
            completedRounds = 2
        )
        assertFalse(lockedAfterTwo.success)

        val unlocked = Shop.buyDefence(
            Inventory(),
            5000,
            DefenceItem.ANTI_MISSILE,
            completedRounds = 3
        )
        assertTrue(unlocked.success)
        assertEquals(2200, unlocked.coins)
        assertEquals(1, unlocked.inventory.antiMissiles)

        val consumed = Shop.consume(unlocked.inventory, DefenceItem.ANTI_MISSILE)
        assertEquals(0, consumed.antiMissiles)

        val granted = Shop.grant(Inventory(), DefenceItem.ANTI_MISSILE, 2)
        assertEquals(2, granted.antiMissiles)
        assertEquals(0, granted.withCount(DefenceItem.ANTI_MISSILE, 0).antiMissiles)
    }

    @Test
    fun antiBombDefenceIsStackableAndUnlocksAfterRoundTwo() {
        assertEquals(2600, DefenceItem.ANTI_BOMB.price)
        assertEquals(BombRules.ANTI_BOMB_PRICE, DefenceItem.ANTI_BOMB.price)
        assertEquals(BombRules.FIRST_BOMB_ROUND - 1, DefenceItem.ANTI_BOMB.requiredCompletedRounds)

        val locked = Shop.buyDefence(
            Inventory(),
            6000,
            DefenceItem.ANTI_BOMB,
            completedRounds = 1
        )
        assertFalse(locked.success)
        assertEquals("antiBombLocked", locked.reasonKey)
        assertEquals(0, locked.inventory.antiBombs)

        val bought = Shop.buyDefence(
            Inventory(),
            6000,
            DefenceItem.ANTI_BOMB,
            completedRounds = 2
        )
        assertTrue(bought.success)
        assertEquals(3400, bought.coins)
        assertEquals(1, bought.inventory.antiBombs)
        assertEquals(1, bought.inventory.count(DefenceItem.ANTI_BOMB))
        assertEquals(0, Shop.consume(bought.inventory, DefenceItem.ANTI_BOMB).antiBombs)
        // It never goes negative.
        assertEquals(0, Shop.consume(Inventory(), DefenceItem.ANTI_BOMB).antiBombs)
    }

    @Test
    fun aDefenceCanNeverGoNegative() {
        val inventory = Shop.consume(Inventory(), DefenceItem.COVER)
        assertEquals(0, inventory.covers)
        assertEquals(0, Shop.consume(Inventory(), DefenceItem.ANTI_MISSILE).antiMissiles)
    }

    @Test
    fun extensionsCanBeBoughtDisabledEnabledAndRemoved() {
        val bought = Shop.buyExtension(Inventory(), 500, Extension.AUTO_RELOAD)
        assertTrue(bought.success)
        assertEquals(400, bought.coins)
        assertTrue(bought.inventory.extensionEnabled(Extension.AUTO_RELOAD))

        val disabled = Shop.toggleExtension(bought.inventory, 400, Extension.AUTO_RELOAD)
        assertTrue(disabled.success)
        assertEquals(
            ExtensionState.DISABLED,
            disabled.inventory.extensionState(Extension.AUTO_RELOAD)
        )
        assertFalse(disabled.inventory.extensionEnabled(Extension.AUTO_RELOAD))
        assertEquals(400, disabled.coins)

        val enabled = Shop.toggleExtension(disabled.inventory, 400, Extension.AUTO_RELOAD)
        assertTrue(enabled.inventory.extensionEnabled(Extension.AUTO_RELOAD))

        val removed = Shop.removeExtension(enabled.inventory, 0, Extension.AUTO_RELOAD)
        assertTrue(removed.success)
        assertEquals(
            ExtensionState.NOT_OWNED,
            removed.inventory.extensionState(Extension.AUTO_RELOAD)
        )
    }

    @Test
    fun buyingAnExtensionWithoutEnoughCoinsFails() {
        val result = Shop.buyExtension(Inventory(), 249, Extension.AUTO_COVER)
        assertFalse(result.success)
        assertEquals("notEnoughCoins", result.reasonKey)
        assertEquals(249, result.coins)
    }

    @Test
    fun extensionPricesAreFiftyCoinsCheaper() {
        assertEquals(100, Extension.AUTO_RELOAD.price)
        assertEquals(190, Extension.AUTO_MEDKIT.price)
        assertEquals(250, Extension.AUTO_COVER.price)
        assertEquals(250, Extension.AUTO_GLUE_WALL.price)
    }

    @Test
    fun maxSpeedIsTheFifthExtension() {
        assertEquals(5, Extension.entries.size)
        assertEquals(300, Extension.MAX_SPEED.price)
        assertEquals("maxSpeed", Extension.MAX_SPEED.id)
        assertEquals("extMaxSpeed", Extension.MAX_SPEED.nameKey)
        assertEquals("extMaxSpeedInfo", Extension.MAX_SPEED.descriptionKey)
        assertNotEquals(
            Extension.MAX_SPEED.nameKey,
            Strings.get(Extension.MAX_SPEED.nameKey, "en")
        )
        assertNotEquals(
            Extension.MAX_SPEED.descriptionKey,
            Strings.get(Extension.MAX_SPEED.descriptionKey, "en")
        )
        for (lang in listOf("hi", "ne")) {
            assertNotEquals(
                Extension.MAX_SPEED.nameKey,
                Strings.get(Extension.MAX_SPEED.nameKey, lang)
            )
            assertNotEquals(
                Extension.MAX_SPEED.descriptionKey,
                Strings.get(Extension.MAX_SPEED.descriptionKey, lang)
            )
        }
        // The speed gain is a real multiplier on top of the selected level.
        assertEquals(1.30f, Extension.MAX_SPEED_GAIN, 0.0001f)
    }

    @Test
    fun maxSpeedCanBeBoughtAndChanged() {
        val bought = Shop.buyExtension(Inventory(), 300, Extension.MAX_SPEED)
        assertTrue(bought.success)
        assertEquals(0, bought.coins)
        assertTrue(bought.inventory.extensionEnabled(Extension.MAX_SPEED))
        val disabled = Shop.toggleExtension(bought.inventory, 0, Extension.MAX_SPEED)
        assertTrue(disabled.success)
        assertFalse(disabled.inventory.extensionEnabled(Extension.MAX_SPEED))
    }

    @Test
    fun buyingMaxSpeedWithoutEnoughCoinsFails() {
        val result = Shop.buyExtension(Inventory(), 299, Extension.MAX_SPEED)
        assertFalse(result.success)
        assertEquals("notEnoughCoins", result.reasonKey)
    }

    @Test
    fun missileAndMissilePowerHaveTheRequestedPrices() {
        assertEquals(3300, Weapon.MISSILE.price)
        assertEquals(3300, MissileRules.MISSILE_PRICE)
        assertEquals(1600, MissileRules.MISSILE_POWER_PRICE)
        assertEquals(2, Weapon.MISSILE.requiredCompletedRounds)
        assertTrue(Weapon.MISSILE.missileLauncher)
        assertEquals(0, Weapon.MISSILE.magazine)
        assertFalse(Weapon.MISSILE.needsReload)
        // Switching to the missile uses the shared gun-switch sound.
        assertEquals("gunReload", Weapon.MISSILE.switchSoundKey)
        // Missile power is a consumable, never a permanent extension.
        assertFalse(Extension.entries.any { it.id == "missilePowers" })
    }

    @Test
    fun missileIsLockedUntilRoundTwoIsCompleted() {
        val locked = Shop.buyWeapon(Inventory(), 5000, Weapon.MISSILE, completedRounds = 1)
        assertFalse(locked.success)
        assertEquals("missileLocked", locked.reasonKey)
        assertEquals(5000, locked.coins)
        assertFalse(locked.inventory.ownsWeapon(Weapon.MISSILE))

        val unlocked = Shop.buyWeapon(Inventory(), 5000, Weapon.MISSILE, completedRounds = 2)
        assertTrue(unlocked.success)
        assertEquals(1700, unlocked.coins)
        assertTrue(unlocked.inventory.ownsWeapon(Weapon.MISSILE))
        // The missile is a real battle weapon in the switch inventory.
        assertEquals(3, unlocked.inventory.ownedWeapons().size)
        assertTrue(unlocked.inventory.ownedWeapons().contains(Weapon.MISSILE))

        val again = Shop.buyWeapon(unlocked.inventory, 5000, Weapon.MISSILE, completedRounds = 2)
        assertFalse(again.success)
        assertEquals("alreadyOwned", again.reasonKey)
    }

    @Test
    fun missilePowersStackLikeDefencesAndAreConsumedOnePerLaunch() {
        val locked = Shop.buyMissilePower(Inventory(), 6000, completedRounds = 1)
        assertFalse(locked.success)
        assertEquals("missileLocked", locked.reasonKey)
        assertEquals(6000, locked.coins)
        assertEquals(0, locked.inventory.missilePowers)

        var inventory = Inventory()
        var coins = 6000
        repeat(3) {
            val bought = Shop.buyMissilePower(inventory, coins, completedRounds = 2)
            assertTrue(bought.success)
            inventory = bought.inventory
            coins = bought.coins
        }
        assertEquals(3, inventory.missilePowers)
        assertTrue(inventory.hasMissilePower)
        assertEquals(1200, coins)

        // Exactly one power is consumed per use; the rest stay stacked.
        inventory = Shop.consumeMissilePower(inventory)
        assertEquals(2, inventory.missilePowers)
        inventory = Shop.consumeMissilePower(inventory)
        inventory = Shop.consumeMissilePower(inventory)
        assertEquals(0, inventory.missilePowers)
        assertFalse(inventory.hasMissilePower)
        // The count can never go negative.
        assertEquals(0, Shop.consumeMissilePower(inventory).missilePowers)

        val broke = Shop.buyMissilePower(Inventory(), 1599, completedRounds = 2)
        assertFalse(broke.success)
        assertEquals("notEnoughCoins", broke.reasonKey)
        assertEquals(1599, broke.coins)
    }

    @Test
    fun disabledWeaponsAreSkippedByTheBattleRotation() {
        val bought = Shop.buyWeapon(Inventory(), 2000, Weapon.PISTOL).inventory
        assertEquals(3, bought.battleWeapons().size)
        assertTrue(bought.weaponEnabled(Weapon.PISTOL))

        val result = Shop.toggleWeaponEnabled(bought, 2000, Weapon.PISTOL)
        assertTrue(result.success)
        // The switch is free: it never charges coins.
        assertEquals(2000, result.coins)
        val disabled = result.inventory
        assertFalse(disabled.weaponEnabled(Weapon.PISTOL))
        // Still owned and still shown by the shop, but out of the rotation.
        assertTrue(disabled.ownsWeapon(Weapon.PISTOL))
        assertEquals(3, disabled.ownedWeapons().size)
        assertEquals(2, disabled.battleWeapons().size)

        val enabled = Shop.toggleWeaponEnabled(disabled, 0, Weapon.PISTOL).inventory
        assertTrue(enabled.weaponEnabled(Weapon.PISTOL))
        assertEquals(3, enabled.battleWeapons().size)

        val notOwned = Shop.toggleWeaponEnabled(Inventory(), 0, Weapon.SWORD)
        assertFalse(notOwned.success)
        assertEquals("notOwned", notOwned.reasonKey)
    }

    @Test
    fun disablingTheRememberedWeaponFallsBackToAnEnabledOne() {
        val bought = Shop.buyWeapon(Inventory(), 2000, Weapon.PISTOL).inventory
            .copy(lastWeaponId = Weapon.PISTOL.id)
        val disabled = Shop.toggleWeaponEnabled(bought, 0, Weapon.PISTOL).inventory
        assertEquals(Weapon.SHORT_GUN, disabled.startingWeapon())
        // It is still owned, only switched off - and because it was the
        // remembered weapon the memory moves to an enabled one, so a battle can
        // never start on a hidden weapon.
        assertTrue(disabled.ownsWeapon(Weapon.PISTOL))
        assertEquals(Weapon.SHORT_GUN.id, disabled.lastWeaponId)
        assertEquals(Weapon.SHORT_GUN, disabled.rememberedWeapon())
        assertFalse(disabled.battleWeapons().contains(Weapon.PISTOL))

        // Buying an owned weapon never flips the switch: it is refused as
        // already owned, spends nothing, and the Enable button stays the only
        // way back into the rotation.
        val again = Shop.buyWeapon(disabled, 2000, Weapon.PISTOL)
        assertFalse(again.success)
        assertEquals("alreadyOwned", again.reasonKey)
        assertEquals(2000, again.coins)
        assertFalse(again.inventory.weaponEnabled(Weapon.PISTOL))

        // The Enable switch puts it back into the rotation. The memory stayed
        // where disabling it moved it, and as soon as the pistol is the
        // remembered weapon again the next battle really starts with it.
        val enabled = Shop.toggleWeaponEnabled(disabled, 0, Weapon.PISTOL).inventory
        assertTrue(enabled.weaponEnabled(Weapon.PISTOL))
        assertTrue(enabled.battleWeapons().contains(Weapon.PISTOL))
        assertEquals(Weapon.SHORT_GUN, enabled.startingWeapon())
        assertEquals(
            Weapon.PISTOL,
            enabled.copy(lastWeaponId = Weapon.PISTOL.id).startingWeapon()
        )

        // A free grant of a not yet owned weapon always arrives enabled, even
        // when its id was left over in the disabled set.
        val granted = Shop.grantWeapon(
            Inventory(disabledWeapons = setOf(Weapon.AWM.id)),
            Weapon.AWM
        ).inventory
        assertTrue(granted.ownsWeapon(Weapon.AWM))
        assertTrue(granted.weaponEnabled(Weapon.AWM))
        assertTrue(granted.disabledWeapons.isEmpty())
    }

    @Test
    fun battleWeaponsOnlyContainWhatTheRoundUnlocks() {
        val everything = Inventory(weaponIds = Weapon.entries.map { it.id }.toSet())
        // Round one: the two free guns plus every round-one shop weapon.
        assertEquals(
            setOf("shortGun", "assaultGun", "pistol", "sniper", "knife", "sword"),
            everything.battleWeapons(1).map { it.id }.toSet()
        )
        assertTrue(everything.battleWeapons(2).contains(Weapon.MP40))
        assertTrue(everything.battleWeapons(2).contains(Weapon.AWM))
        assertFalse(everything.battleWeapons(2).contains(Weapon.GRENADE))
        assertFalse(everything.battleWeapons(2).contains(Weapon.MISSILE))
        assertTrue(everything.battleWeapons(3).contains(Weapon.GRENADE))
        assertTrue(everything.battleWeapons(3).contains(Weapon.ATOM_BOMB))
        assertTrue(everything.battleWeapons(3).contains(Weapon.TIMER_BOMB))
        assertTrue(everything.battleWeapons(3).contains(Weapon.MISSILE))
        assertFalse(everything.battleWeapons(3).contains(Weapon.MACHINE_GUN))
        assertTrue(everything.battleWeapons(4).contains(Weapon.MACHINE_GUN))
        assertTrue(everything.battleWeapons(5).contains(Weapon.MACHINE_GUN))
    }

    @Test
    fun freeWeaponsAreIssuedByRoundAndNeverTwice() {
        assertEquals(emptyList<Weapon>(), Weapon.issuedForRound(1))
        assertEquals(listOf(Weapon.MP40, Weapon.AWM), Weapon.issuedForRound(2))
        assertEquals(listOf(Weapon.MP40, Weapon.AWM, Weapon.GRENADE), Weapon.issuedForRound(3))
        // The machine gun is unlockable in the shop but is never handed out
        // for free and is never part of any reward.
        assertEquals(0, Weapon.MACHINE_GUN.issuedFreeFromRound)
        assertFalse(Weapon.issuedForRound(5).contains(Weapon.MACHINE_GUN))
        assertTrue(Weapon.MACHINE_GUN.price > 0)
        assertEquals(4, Weapon.MACHINE_GUN.unlockRound)

        val granted = Shop.grantWeapon(Inventory(), Weapon.MP40)
        assertTrue(granted.success)
        assertTrue(granted.inventory.ownsWeapon(Weapon.MP40))
        val twice = Shop.grantWeapon(granted.inventory, Weapon.MP40)
        assertFalse(twice.success)
        assertEquals("alreadyOwned", twice.reasonKey)
        // Granting a weapon also re-enables it when it was disabled.
        val disabled = granted.inventory.copy(
            disabledWeapons = setOf(Weapon.MP40.id)
        )
        val removed = Shop.removeWeapon(disabled, 0, Weapon.MP40).inventory
        assertFalse(removed.ownsWeapon(Weapon.MP40))
        assertTrue(Shop.grantWeapon(removed, Weapon.MP40).inventory.weaponEnabled(Weapon.MP40))
    }

    @Test
    fun rewardBundlesAreGrantedWithoutCharge() {
        val bundle = Rewards.ROUND_REWARD
        val granted = Shop.grantBundle(Inventory(), bundle)
        assertEquals(2, granted.glueWalls)
        assertEquals(2, granted.medikits)
        assertEquals(3, granted.covers)
        assertEquals(0, granted.antiBombs)
        assertEquals(7, bundle.total)

        for (level in Rewards.LEVEL_REWARDS) {
            val inv = Shop.grantBundle(Inventory(), level)
            assertEquals(level.glueWalls, inv.glueWalls)
            assertEquals(level.medikits, inv.medikits)
            assertEquals(level.covers, inv.covers)
            assertTrue(level.total > 0)
        }
        // A random level reward never carries more than one gloo wall.
        assertTrue(Rewards.levelRewardsRespectTheGlueWallLimit())
        assertEquals(5, Rewards.LEVEL_REWARDS.size)
        assertEquals(Rewards.ROUND_REWARD, Rewards.forPromotion(false, true, kotlin.random.Random))
        assertTrue(Rewards.forPromotion(false, false, kotlin.random.Random).isEmpty)
    }

    @Test
    fun theRememberedWeaponIsOnlyUsedWhenItIsStillOwned() {
        assertNull(Inventory(lastWeaponId = Weapon.SWORD.id).rememberedWeapon())
        val owned = Shop.buyWeapon(Inventory(), 1000, Weapon.SWORD).inventory
            .copy(lastWeaponId = Weapon.SWORD.id)
        assertEquals(Weapon.SWORD, owned.rememberedWeapon())
        assertEquals(Weapon.SWORD, owned.startingWeapon())
    }
}
