package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.ExtensionState
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.Loans
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
        assertFalse(inventory.ownsWeapon(Weapon.GLUE_BREAKER))
        assertFalse(inventory.ownsWeapon(Weapon.KNIFE))
        assertFalse(inventory.ownsWeapon(Weapon.SWORD))
        assertFalse(inventory.ownsWeapon(Weapon.MISSILE))
        assertEquals(0, inventory.missilePowers)
        assertFalse(inventory.hasMissilePower)
        assertEquals(2, inventory.ownedWeapons().size)
    }

    @Test
    fun weaponPricesMatchTheShop() {
        // Every paid weapon is less expensive than before.
        assertEquals(220, Weapon.KNIFE.price)
        assertEquals(450, Weapon.PISTOL.price)
        assertEquals(650, Weapon.SWORD.price)
        assertEquals(850, Weapon.SNIPER.price)
        // The dedicated wall breaker remains available from round one.
        assertEquals(750, Weapon.GLUE_BREAKER.price)
        assertEquals(6, Weapon.GLUE_BREAKER.magazine)
        assertEquals("glueBreaker", Weapon.GLUE_BREAKER.id)
        assertEquals("gunGlueBreaker", Weapon.GLUE_BREAKER.nameKey)
        assertEquals(1200, Weapon.AWM.price)
        assertEquals(1700, Weapon.MACHINE_GUN.price)
        // The three new guns keep the ladder under the missile.
        assertEquals(600, Weapon.M10.price)
        assertEquals(900, Weapon.SUBMACHINE_GUN.price)
        assertEquals(1250, Weapon.AK47.price)
        assertEquals(1, Weapon.M10.unlockRound)
        assertEquals(1, Weapon.AK47.unlockRound)
        assertEquals(2, Weapon.SUBMACHINE_GUN.unlockRound)
        assertEquals(600, Weapon.GRENADE.price)
        assertEquals(2000, Weapon.ATOM_BOMB.price)
        assertEquals(1700, Weapon.TIMER_BOMB.price)
        for (weapon in Weapon.forSale()) {
            assertTrue("${weapon.id} must be cheaper", weapon.price > 0)
        }
    }

    @Test
    fun buyingAWeaponDeductsCoinsAndAddsIt() {
        val result = Shop.buyWeapon(Inventory(), 1000, Weapon.PISTOL)
        assertTrue(result.success)
        assertEquals(550, result.coins)
        assertTrue(result.inventory.ownsWeapon(Weapon.PISTOL))
    }

    @Test
    fun buyingWithoutEnoughCoinsFailsWithoutChanges() {
        val result = Shop.buyWeapon(Inventory(), 449, Weapon.PISTOL)
        assertFalse(result.success)
        assertEquals(449, result.coins)
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
        assertEquals(1250, coins)

        inventory = Shop.consume(inventory, DefenceItem.MEDKIT)
        assertEquals(2, inventory.medikits)
        assertTrue(inventory.count(DefenceItem.MEDKIT) >= 0)
    }

    @Test
    fun antiMissileDefenceUnlocksAfterRoundThree() {
        assertEquals(1000, DefenceItem.ANTI_MISSILE.price)
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
        assertEquals(4000, unlocked.coins)
        assertEquals(1, unlocked.inventory.antiMissiles)

        val consumed = Shop.consume(unlocked.inventory, DefenceItem.ANTI_MISSILE)
        assertEquals(0, consumed.antiMissiles)

        val granted = Shop.grant(Inventory(), DefenceItem.ANTI_MISSILE, 2)
        assertEquals(2, granted.antiMissiles)
        assertEquals(0, granted.withCount(DefenceItem.ANTI_MISSILE, 0).antiMissiles)
    }

    @Test
    fun antiBombDefenceIsStackableAndUnlocksAfterRoundTwo() {
        assertEquals(1700, DefenceItem.ANTI_BOMB.price)
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
        assertEquals(4300, bought.coins)
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
        assertEquals(425, bought.coins)
        assertTrue(bought.inventory.extensionEnabled(Extension.AUTO_RELOAD))

        val disabled = Shop.toggleExtension(bought.inventory, 425, Extension.AUTO_RELOAD)
        assertTrue(disabled.success)
        assertEquals(
            ExtensionState.DISABLED,
            disabled.inventory.extensionState(Extension.AUTO_RELOAD)
        )
        assertFalse(disabled.inventory.extensionEnabled(Extension.AUTO_RELOAD))
        assertEquals(425, disabled.coins)

        val enabled = Shop.toggleExtension(disabled.inventory, 425, Extension.AUTO_RELOAD)
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
        val result = Shop.buyExtension(Inventory(), 179, Extension.AUTO_COVER)
        assertFalse(result.success)
        assertEquals("notEnoughCoins", result.reasonKey)
        assertEquals(179, result.coins)
    }

    @Test
    fun extensionPricesAreReduced() {
        assertEquals(75, Extension.AUTO_RELOAD.price)
        assertEquals(140, Extension.AUTO_MEDKIT.price)
        assertEquals(180, Extension.AUTO_COVER.price)
        assertEquals(200, Extension.AUTO_GLUE_WALL.price)
    }

    @Test
    fun missileAndMissilePowerHaveTheRequestedPrices() {
        assertEquals(2200, Weapon.MISSILE.price)
        assertEquals(2200, MissileRules.MISSILE_PRICE)
        assertEquals(1000, MissileRules.MISSILE_POWER_PRICE)
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
    fun atomBombAndShockAndBombPowerUnlockAfterRoundOne() {
        for (weapon in listOf(Weapon.ATOM_BOMB, Weapon.ELECTRIC_SHOCK)) {
            assertFalse(Shop.buyWeapon(Inventory(), 5000, weapon, 0).success)
            assertTrue(Shop.buyWeapon(Inventory(), 5000, weapon, 1).success)
        }
        assertFalse(Shop.buyBombPower(Inventory(), 5000, 0).success)
        assertTrue(Shop.buyBombPower(Inventory(), 5000, 1).success)
        assertFalse(Shop.buyWeapon(Inventory(), 5000, Weapon.GRENADE, 1).success)
        assertFalse(Shop.buyWeapon(Inventory(), 5000, Weapon.TIMER_BOMB, 1).success)
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
        assertEquals(2800, unlocked.coins)
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
        assertEquals(3000, coins)

        // Exactly one power is consumed per use; the rest stay stacked.
        inventory = Shop.consumeMissilePower(inventory)
        assertEquals(2, inventory.missilePowers)
        inventory = Shop.consumeMissilePower(inventory)
        inventory = Shop.consumeMissilePower(inventory)
        assertEquals(0, inventory.missilePowers)
        assertFalse(inventory.hasMissilePower)
        // The count can never go negative.
        assertEquals(0, Shop.consumeMissilePower(inventory).missilePowers)

        val broke = Shop.buyMissilePower(Inventory(), 999, completedRounds = 2)
        assertFalse(broke.success)
        assertEquals("notEnoughCoins", broke.reasonKey)
        assertEquals(999, broke.coins)
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
        // Round one: the two free guns, the M10, the AK-47 and every other
        // round-one shop weapon.
        assertEquals(
            setOf(
                "shortGun", "assaultGun", "pistol", "sniper", "glueBreaker",
                "knife", "sword", "m10", "ak47", "ar15"
            ),
            everything.battleWeapons(1).map { it.id }.toSet()
        )
        // The MP40 was removed from the game: no round hands it out.
        for (round in 1..5) {
            assertFalse(everything.battleWeapons(round).any { it.id == "mp40" })
        }
        assertTrue(everything.battleWeapons(2).contains(Weapon.AWM))
        assertTrue(everything.battleWeapons(2).contains(Weapon.SUBMACHINE_GUN))
        // The grenade IS in round two - that is the round of its free gift -
        // even though it only becomes buyable in round three.
        assertTrue(everything.battleWeapons(2).contains(Weapon.GRENADE))
        assertFalse(everything.battleWeapons(2).contains(Weapon.MISSILE))
        assertTrue(everything.battleWeapons(3).contains(Weapon.GRENADE))
        // The electric shock is unlocked once round two is completed.
        assertTrue(everything.battleWeapons(2).contains(Weapon.ELECTRIC_SHOCK))
        assertTrue(everything.battleWeapons(2).contains(Weapon.ATOM_BOMB))
        assertTrue(everything.battleWeapons(3).contains(Weapon.ELECTRIC_SHOCK))
        assertTrue(everything.battleWeapons(3).contains(Weapon.ATOM_BOMB))
        assertTrue(everything.battleWeapons(3).contains(Weapon.TIMER_BOMB))
        assertTrue(everything.battleWeapons(3).contains(Weapon.MISSILE))
        // The machine gun moved to the round-three ladder with the bombs.
        assertTrue(everything.battleWeapons(3).contains(Weapon.MACHINE_GUN))
        assertTrue(everything.battleWeapons(4).contains(Weapon.MACHINE_GUN))
        assertTrue(everything.battleWeapons(5).contains(Weapon.MACHINE_GUN))
    }

    @Test
    fun freeWeaponsAreIssuedByRoundAndNeverTwice() {
        assertEquals(emptyList<Weapon>(), Weapon.issuedForRound(1))
        // Round two hands out the AWM and, as the round-two gift, the grenade:
        // two free grenade charges are issued with the weapon itself.
        assertEquals(listOf(Weapon.AWM, Weapon.GRENADE), Weapon.issuedForRound(2))
        assertEquals(listOf(Weapon.AWM, Weapon.GRENADE), Weapon.issuedForRound(3))
        assertEquals(2, Weapon.GRENADE.issuedFreeFromRound)
        assertEquals(3, Weapon.GRENADE.unlockRound)
        // The machine gun is unlockable in the shop but is never handed out
        // for free and is never part of any reward.
        assertEquals(0, Weapon.MACHINE_GUN.issuedFreeFromRound)
        assertFalse(Weapon.issuedForRound(5).contains(Weapon.MACHINE_GUN))
        assertTrue(Weapon.MACHINE_GUN.price > 0)
        assertEquals(3, Weapon.MACHINE_GUN.unlockRound)

        val granted = Shop.grantWeapon(Inventory(), Weapon.SWORD)
        assertTrue(granted.success)
        assertTrue(granted.inventory.ownsWeapon(Weapon.SWORD))
        val twice = Shop.grantWeapon(granted.inventory, Weapon.SWORD)
        assertFalse(twice.success)
        assertEquals("alreadyOwned", twice.reasonKey)
        // Granting a weapon also re-enables it when it was disabled.
        val disabled = granted.inventory.copy(
            disabledWeapons = setOf(Weapon.SWORD.id)
        )
        val removed = Shop.removeWeapon(disabled, 0, Weapon.SWORD).inventory
        assertFalse(removed.ownsWeapon(Weapon.SWORD))
        assertTrue(Shop.grantWeapon(removed, Weapon.SWORD).inventory.weaponEnabled(Weapon.SWORD))
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
    fun aChargedAntiBombCannotBeBoughtTwiceButAnExhaustedOneCan() {
        // A carried explosive with stops left blocks a second purchase: the
        // button would be a wasted double and the shop says so.
        val charged = Inventory(antiBombs = 1)
        assertTrue(charged.holdsUsedAntiBomb)
        assertEquals(BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE, charged.antiBombCapacityLeft)
        val refused = Shop.buyDefence(charged, 5_000, DefenceItem.ANTI_BOMB)
        assertFalse(refused.success)
        assertEquals("antiBombStillCharged", refused.reasonKey)
        assertEquals(5_000, refused.coins)

        // After every stop has been used the row opens again and the bought
        // explosive arrives full.
        val spent = charged.withAntiBombSpent(spent = 3, freeForRound = false)
        assertEquals(0, spent.antiBombs)
        assertEquals(0, spent.antiBombCapacityLeft)
        assertFalse(spent.holdsUsedAntiBomb)
        val bought = Shop.buyDefence(spent, 5_000, DefenceItem.ANTI_BOMB)
        assertTrue(bought.success)
        assertEquals(
            BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE,
            bought.inventory.antiBombCapacityLeft
        )
        assertEquals(1, bought.inventory.antiBombs)
        // A partial use really lowers the capacity that is carried on.
        val partial = charged.withAntiBombSpent(spent = 1, freeForRound = false)
        assertEquals(BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE - 1, partial.antiBombRemaining)
        // ...and it survives a save and reload as the same number.
        assertEquals(
            BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE - 1,
            Inventory(antiBombs = 1, antiBombRemaining = partial.antiBombRemaining)
                .antiBombCapacityLeft
        )
    }

    @Test
    fun theBattleLoanNeedsAnOpenPromptAndTheFullDoubleTapWindow() {
        // Only a tap that answered an open prompt can arm the window, and only
        // the second tap inside the window takes the loan.
        assertTrue(Loans.doubleTapTakes(true, 1_000L, 1_900L, eligible = true))
        assertTrue(Loans.doubleTapTakes(true, 1_000L, 1_000L, eligible = true))
        assertFalse(Loans.doubleTapTakes(true, 1_000L, 1_901L, eligible = true))
        // No prompt: an ordinary tap, so two menu taps can never take a loan.
        assertFalse(Loans.doubleTapTakes(false, 1_000L, 1_100L, eligible = true))
        // The prompt is stale once the loan is no longer available.
        assertFalse(Loans.doubleTapTakes(true, 1_000L, 1_100L, eligible = false))
        // An unarmed window (0) or a clock that reset is never a double tap.
        assertFalse(Loans.doubleTapTakes(true, 0L, 1_100L, eligible = true))
        assertFalse(Loans.doubleTapTakes(true, 5_000L, 1_000L, eligible = true))
        // The default window is the shared 900 ms and it is the real bound.
        assertEquals(900L, Loans.BATTLE_DOUBLE_TAP_WINDOW_MS)
        assertFalse(Loans.doubleTapTakes(true, 1L, 1L + 901L, eligible = true))
    }

    @Test
    fun aSpentAntiBombIsNeverRefilledByAReload() {
        // -1 means "never recorded" (a pre-magazine profile) and starts full;
        // 0 means "spent" and must stay spent, or a restart would hand the
        // player a free explosive.
        assertEquals(
            BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE,
            Inventory(antiBombs = 1).antiBombCapacityLeft
        )
        assertEquals(BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE, BombRules.deployedCapacity(-1, 1))
        assertEquals(0, BombRules.deployedCapacity(0, 0))
        assertEquals(0, BombRules.deployedCapacity(0, 1))
        assertEquals(0, Inventory(antiBombs = 0, antiBombRemaining = 0).antiBombCapacityLeft)
        assertFalse(Inventory(antiBombs = 0, antiBombRemaining = 0).holdsUsedAntiBomb)
        // A partly used explosive reloads with the stops it had left.
        assertEquals(2, BombRules.deployedCapacity(2, 1))
        assertEquals(1, BombRules.deployedCapacity(1, 1))
        // No capacity ever exceeds the magazine or goes negative.
        assertEquals(BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE, BombRules.deployedCapacity(99, 1))
        assertEquals(0, BombRules.deployedCapacity(99, 0))
        assertEquals(0, BombRules.deployedCapacity(-1, 0))
    }

    @Test
    fun theFreeAntiBombOfLaterRoundsAlwaysRearmsItself() {
        val owned = Inventory(antiBombs = 1)
        val used = owned.withAntiBombSpent(spent = 3, freeForRound = true)
        assertEquals(
            BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE,
            used.antiBombRemaining
        )
        assertEquals(1, used.antiBombs)
        // Capacity never exceeds the magazine and never goes negative.
        assertEquals(0, BombRules.capacityAfter(1, 5, freeForRound = false))
        assertEquals(
            BombRules.PLAYER_INTERCEPTS_PER_EXPLOSIVE,
            BombRules.capacityAfter(99, 0, freeForRound = false)
        )
        assertEquals(0, BombRules.carriedCapacity(-4))
    }

    @Test
    fun aSpentGrenadeLeavesItsOwnSlotAndKeepsTheHeldWeapon() {
        val state = com.blindtechnexus.openfire.model.GameState(
            weapons = listOf("shortGun", "grenade", "pistol"),
            ammo = listOf(6, 1, 6),
            damaged = listOf(false, true, false),
            weaponIndex = 0
        )
        // Throwing the grenade and switching away before the charge is spent
        // must still drop the grenade's own slot, and the held slot must keep
        // holding the same gun.
        val after = state.withoutWeaponAt(1)
        assertEquals(listOf("shortGun", "pistol"), after.weapons)
        assertEquals(listOf(6, 6), after.ammo)
        assertEquals(listOf(false, false), after.damaged)
        assertEquals(0, after.weaponIndex)
        assertEquals("shortGun", after.activeWeapon.id)
        // Holding the grenade itself rolls over to the slot it occupied.
        val holding = state.copy(weaponIndex = 1).withoutWeaponAt(1)
        assertEquals(listOf("shortGun", "pistol"), holding.weapons)
        assertEquals("pistol", holding.activeWeapon.id)
        // The last remaining weapon is never dropped, and a bad index is a
        // no-op, so the rotation can never become empty.
        val single = com.blindtechnexus.openfire.model.GameState(
            weapons = listOf("shortGun"), ammo = listOf(6), damaged = listOf(false)
        )
        assertEquals(single, single.withoutWeaponAt(0))
        assertEquals(state, state.withoutWeaponAt(9))
        assertEquals(state, state.withoutWeaponAt(-1))
    }

    @Test
    fun everyWeaponGrantedFreeIsReallyUsableInThatRound() {
        // A gift that cannot be thrown is not a gift: the round-two grenade is
        // issued a whole round before it can be bought, so the battle rotation
        // must honour the grant as well as the shop unlock.
        val all = Inventory(weaponIds = Weapon.entries.map { it.id }.toSet())
        assertTrue(all.battleWeapons(2).contains(Weapon.GRENADE))
        assertTrue(all.battleWeapons(2).contains(Weapon.AWM))
        // Round one has no bombs and no round-two gifts yet.
        assertFalse(all.battleWeapons(1).contains(Weapon.GRENADE))
        // The grenade later stays available on its own shop unlock.
        for (round in 3..6) assertTrue(all.battleWeapons(round).contains(Weapon.GRENADE))
        // Whatever a round grants must be in that round's rotation, always.
        for (round in 1..6) {
            val available = all.battleWeapons(round).toSet()
            for (granted in Weapon.issuedForRound(round)) {
                assertTrue("round $round grants ${granted.id} but hides it", granted in available)
            }
        }
    }

    @Test
    fun theRoundTwoGiftHandsTwoSellableGrenadesAndNoMore() {
        assertEquals(2, BombRules.grenadeChargesForRound(2))
        assertEquals(0, BombRules.grenadeChargesForRound(1))
        assertEquals(0, BombRules.grenadeChargesForRound(3))
        assertTrue(BombRules.grenadeSpent(2, 0))
        assertFalse(BombRules.grenadeSpent(2, 1))
        assertFalse(BombRules.grenadeSpent(3, 0))
        // The grenade itself is still sold at its own price and unlocked in
        // round three, so a spent gift can be bought back.
        assertEquals(600, Weapon.GRENADE.price)
        assertEquals(3, Weapon.GRENADE.unlockRound)
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
