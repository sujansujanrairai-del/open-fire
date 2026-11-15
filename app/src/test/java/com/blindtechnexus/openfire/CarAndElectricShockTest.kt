package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.CarRules
import com.blindtechnexus.openfire.model.CarStage
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.ElectricShockRules
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.RobertRules
import com.blindtechnexus.openfire.model.Shop
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules of the bulletproof car, the AR-15 and the electric shock. */
class CarAndElectricShockTest {

    // ------------------------------------------------------------------
    // Car: stages, prices and fuel
    // ------------------------------------------------------------------

    @Test
    fun theCarCostsThreeThousandFiveHundredAndRefuelsForTwoThousand() {
        assertEquals(3500, CarRules.PRICE)
        assertEquals(3500, DefenceItem.CAR.price)
        assertEquals(2000, CarRules.REFUEL_PRICE)
        assertEquals(2, CarRules.GAMES_PER_FUEL)
        assertEquals(1, DefenceItem.CAR.requiredCompletedRounds)
    }

    @Test
    fun onlyTheInsideStagesShieldTheUser() {
        for (stage in CarStage.entries) {
            val inside = stage == CarStage.INSIDE_ENGINE_OFF ||
                stage == CarStage.INSIDE_ENGINE_ON ||
                stage == CarStage.INSIDE_MOVING
            assertEquals("stage $stage", inside, CarRules.shieldsUser(stage))
        }
        // The door being open exposes the user, even when the car is deployed.
        assertFalse(CarRules.shieldsUser(CarStage.DOOR_OPEN_ENTERING))
        assertFalse(CarRules.shieldsUser(CarStage.DOOR_OPEN_EXITING))
    }

    @Test
    fun aSwipeUpIsACarActionOnlyWhileTheCarAwaitsIt() {
        assertTrue(CarRules.awaitsSwipeUp(CarStage.DOOR_CLOSED_OUTSIDE))
        assertTrue(CarRules.awaitsSwipeUp(CarStage.DOOR_OPEN_ENTERING))
        assertTrue(CarRules.awaitsSwipeUp(CarStage.INSIDE_ENGINE_OFF))
        assertTrue(CarRules.awaitsSwipeUp(CarStage.REFUEL_PENDING))
        // Driving and the engine idling use a held swipe, not a tap.
        assertFalse(CarRules.awaitsSwipeUp(CarStage.INSIDE_ENGINE_ON))
        assertFalse(CarRules.awaitsSwipeUp(CarStage.INSIDE_MOVING))
        assertFalse(CarRules.awaitsSwipeUp(CarStage.NOT_DEPLOYED))
    }

    @Test
    fun everyCarStageHasAPromptThatExistsInEveryLanguage() {
        for (stage in CarStage.entries) {
            val key = CarRules.promptKey(stage)
            for (lang in listOf("en", "hi", "ne")) {
                // Strings.get falls back to the key itself when a translation is missing.
                assertNotEquals("$key in $lang", key, Strings.get(key, lang))
            }
        }
        assertEquals("Open the car door", Strings.get("carOpenDoor", "en"))
        assertEquals("Close the car door", Strings.get("carCloseDoor", "en"))
        assertEquals("Start car", Strings.get("carStartPrompt", "en"))
        assertEquals("Engine on. Swipe up and hold to drive. Two-finger swipe up fires, two-finger swipe down reloads, one-finger swipe down stops the car.", Strings.get("carEngineOnPrompt", "en"))
        assertEquals("Refuel, swipe up to refuel", Strings.get("carRefuelPrompt", "en"))
    }

    @Test
    fun theAlarmIsBelowThreeHundredHealth() {
        assertTrue(CarRules.alarmed(299))
        assertFalse(CarRules.alarmed(300))
        assertFalse(CarRules.alarmed(CarRules.MAX_HP))
    }

    @Test
    fun aDeployedCarUsesOneGameAndNeverGoesNegative() {
        assertEquals(1, CarRules.gamesAfterDeploy(2))
        assertEquals(0, CarRules.gamesAfterDeploy(1))
        assertEquals(0, CarRules.gamesAfterDeploy(0))
    }

    // ------------------------------------------------------------------
    // Car: buying, fuel and loss in the shop model
    // ------------------------------------------------------------------

    @Test
    fun theCarIsLockedUntilRoundOneIsComplete() {
        val locked = Shop.buyDefence(Inventory(), 10_000, DefenceItem.CAR, completedRounds = 0)
        assertFalse(locked.success)
        assertEquals(DefenceItem.CAR.lockedKey, locked.reasonKey)
        assertEquals(10_000, locked.coins)
    }

    @Test
    fun buyingTheCarGivesOneCarWithTwoGames() {
        val bought = Shop.buyDefence(Inventory(), 3500, DefenceItem.CAR, completedRounds = 1)
        assertTrue(bought.success)
        assertEquals(0, bought.coins)
        assertEquals(1, bought.inventory.cars)
        assertEquals(2, bought.inventory.carGamesLeft)
    }

    @Test
    fun theCarCannotBeBoughtTwiceAndNeedsTheMoney() {
        val owned = Inventory(cars = 1, carGamesLeft = 1)
        val again = Shop.buyDefence(owned, 99_999, DefenceItem.CAR, completedRounds = 5)
        assertFalse(again.success)
        assertEquals("alreadyOwned", again.reasonKey)

        val poor = Shop.buyDefence(Inventory(), 3499, DefenceItem.CAR, completedRounds = 5)
        assertFalse(poor.success)
        assertEquals("notEnoughCoins", poor.reasonKey)
    }

    @Test
    fun aCarCannotHoldMoreThanOneAndHasNoFuelWithoutOne() {
        assertEquals(1, Inventory().withCount(DefenceItem.CAR, 5).cars)
        val sold = Inventory(cars = 1, carGamesLeft = 2).withCount(DefenceItem.CAR, 0)
        assertEquals(0, sold.cars)
        assertEquals(0, sold.carGamesLeft)
    }

    @Test
    fun refuelingNeedsAnOwnedEmptyCarAndTwoThousandCoins() {
        assertEquals("notOwned", Shop.refuelCar(Inventory(), 9_999).reasonKey)

        val fuelled = Inventory(cars = 1, carGamesLeft = 1)
        assertEquals("carHasFuel", Shop.refuelCar(fuelled, 9_999).reasonKey)

        val empty = Inventory(cars = 1, carGamesLeft = 0)
        val broke = Shop.refuelCar(empty, 1999)
        assertFalse(broke.success)
        assertEquals("notEnoughCoins", broke.reasonKey)
        assertEquals(1999, broke.coins)

        val refuelled = Shop.refuelCar(empty, 2500)
        assertTrue(refuelled.success)
        assertEquals(500, refuelled.coins)
        assertEquals(2, refuelled.inventory.carGamesLeft)
    }

    @Test
    fun aCarIsUsableInTwoGamesThenNeedsFuel() {
        var inventory = Shop.buyDefence(Inventory(), 3500, DefenceItem.CAR, completedRounds = 1).inventory
        inventory = Shop.useCarGame(inventory)
        assertEquals(1, inventory.carGamesLeft)
        inventory = Shop.useCarGame(inventory)
        assertEquals(0, inventory.carGamesLeft)
        assertEquals(1, inventory.cars)
        // Out of fuel now: the next refuel is allowed and costs the full price.
        val refuelled = Shop.refuelCar(inventory, 2_000)
        assertTrue(refuelled.success)
        assertEquals(0, refuelled.coins)
        assertEquals(2, refuelled.inventory.carGamesLeft)
    }

    @Test
    fun aDestroyedCarIsLostForGood() {
        val lost = Shop.loseCar(Inventory(cars = 1, carGamesLeft = 2))
        assertEquals(0, lost.cars)
        assertEquals(0, lost.carGamesLeft)
    }

    // ------------------------------------------------------------------
    // Car: health
    // ------------------------------------------------------------------

    @Test
    fun theCarHasAThousandHealth() {
        assertEquals(1000, CarRules.MAX_HP)
        assertEquals(300, CarRules.ALARM_HP)
    }

    // ------------------------------------------------------------------
    // AR-15 and the electric shock
    // ------------------------------------------------------------------

    @Test
    fun theAr15IsAPlayerFirearmFromRoundOne() {
        assertEquals(1000, Weapon.AR15.price)
        assertEquals(1, Weapon.AR15.unlockRound)
        assertEquals("ar15", Weapon.AR15.fireSoundKey)
        assertTrue(Weapon.AR15.isFirearm)
        assertTrue(Weapon.AR15.needsReload)
        assertFalse(Weapon.AR15.ownedByDefault)
        assertFalse(RobertRules.isEnemyAllowed(Weapon.AR15, 5))
    }

    @Test
    fun theElectricShockIsNeverAGunAndNeverGivenToRobert() {
        assertEquals(1500, Weapon.ELECTRIC_SHOCK.price)
        assertEquals(3, Weapon.ELECTRIC_SHOCK.unlockRound)
        assertTrue(Weapon.ELECTRIC_SHOCK.electricShock)
        assertFalse(Weapon.ELECTRIC_SHOCK.isFirearm)
        assertFalse(Weapon.ELECTRIC_SHOCK.needsReload)
        for (round in 1..5) {
            assertFalse(RobertRules.isEnemyAllowed(Weapon.ELECTRIC_SHOCK, round))
        }
        assertFalse(RobertRules.ENEMY_WEAPONS.contains(Weapon.ELECTRIC_SHOCK))
    }

    @Test
    fun theElectricShockIsOnlyUnlockedAfterRoundTwo() {
        assertEquals(2, Weapon.ELECTRIC_SHOCK.requiredCompletedRounds)
        assertTrue(Weapon.forSale().contains(Weapon.ELECTRIC_SHOCK))
    }

    @Test
    fun theShockDrainsEightyPercentPerSecondInTheCloseBand() {
        assertEquals(80f, ElectricShockRules.playerDrainPercentPerSecond(40f), 0.001f)
        assertEquals(80f, ElectricShockRules.playerDrainPercentPerSecond(70f), 0.001f)
        assertEquals(80f, ElectricShockRules.playerDrainPercentPerSecond(100f), 0.001f)
        assertEquals(10f, ElectricShockRules.playerDrainPercentPerSecond(39.9f), 0.001f)
        assertEquals(10f, ElectricShockRules.playerDrainPercentPerSecond(0f), 0.001f)
    }

    @Test
    fun robertAnswersOnlyAfterFiveSeconds() {
        assertFalse(ElectricShockRules.counterOpen(4_999))
        assertTrue(ElectricShockRules.counterOpen(5_000))
        assertEquals(10f, ElectricShockRules.ROBERT_COUNTER_PERCENT_PER_SECOND, 0.001f)
    }

    @Test
    fun aUseEndsAtThirtySeconds() {
        assertFalse(ElectricShockRules.timeUp(29_999))
        assertTrue(ElectricShockRules.timeUp(30_000))
        assertEquals(30_000L, ElectricShockRules.MAX_USE_MS)
    }

    @Test
    fun damagePerTickIsThePercentageOfMaximumHealth() {
        // 80 percent of 200 health per second: 16 health in a tenth of a second.
        assertEquals(16f, ElectricShockRules.damageFor(200, 80f, 100L), 0.001f)
        assertEquals(160f, ElectricShockRules.damageFor(200, 80f, 1000L), 0.001f)
        assertEquals(0f, ElectricShockRules.damageFor(200, 80f, -5L), 0.001f)
    }

    @Test
    fun theElectricShockDealsNoBulletDamage() {
        for (zone in com.blindtechnexus.openfire.model.HitZone.entries) {
            assertEquals(0, com.blindtechnexus.openfire.model.DamageTable.percentOf(Weapon.ELECTRIC_SHOCK, zone))
        }
    }

    // ------------------------------------------------------------------
    // Shop: the free guns and the one-gun rule
    // ------------------------------------------------------------------

    @Test
    fun theShotgunAndAssaultRifleCanBeDisabledButNeverRemoved() {
        assertTrue(Weapon.SHORT_GUN.ownedByDefault)
        assertTrue(Weapon.ASSAULT_GUN.ownedByDefault)
        assertFalse(Shop.removeWeapon(Inventory(), 0, Weapon.SHORT_GUN).success)
        assertFalse(Shop.removeWeapon(Inventory(), 0, Weapon.ASSAULT_GUN).success)
        val off = Shop.toggleWeaponEnabled(Inventory(), 0, Weapon.SHORT_GUN)
        assertTrue(off.success)
        assertFalse(off.inventory.weaponEnabled(Weapon.SHORT_GUN))
        assertTrue(Weapon.shopListed().contains(Weapon.SHORT_GUN))
        assertTrue(Weapon.shopListed().contains(Weapon.ASSAULT_GUN))
    }

    @Test
    fun atLeastOneGunAlwaysStaysEnabled() {
        val oneLeft = Inventory(disabledWeapons = setOf(Weapon.SHORT_GUN.id))
        val refused = Shop.toggleWeaponEnabled(oneLeft, 0, Weapon.ASSAULT_GUN)
        assertFalse(refused.success)
        assertEquals("keepOneGunEnabled", refused.reasonKey)
        assertTrue(refused.inventory.weaponEnabled(Weapon.ASSAULT_GUN))

        // With two guns on, one can be switched off.
        val both = Shop.toggleWeaponEnabled(Inventory(), 0, Weapon.ASSAULT_GUN)
        assertTrue(both.success)
    }

    @Test
    fun aDisabledGunCanBeSwitchedBackOn() {
        val off = Inventory(disabledWeapons = setOf(Weapon.SHORT_GUN.id))
        val on = Shop.toggleWeaponEnabled(off, 0, Weapon.SHORT_GUN)
        assertTrue(on.success)
        assertTrue(on.inventory.weaponEnabled(Weapon.SHORT_GUN))
    }
}
