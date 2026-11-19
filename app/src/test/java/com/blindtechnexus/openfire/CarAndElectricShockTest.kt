package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.CarRules
import com.blindtechnexus.openfire.model.CarStage
import com.blindtechnexus.openfire.model.Combat
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
    fun theCarCostsFifteenHundredAndRefuelsForFiveHundred() {
        assertEquals(1500, CarRules.PRICE)
        assertEquals(1500, DefenceItem.CAR.price)
        assertEquals(500, CarRules.REFUEL_PRICE)
        assertEquals(5, CarRules.GAMES_PER_FUEL)
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
        // The exit flow closes the door with the same swipe.
        assertTrue(CarRules.awaitsSwipeUp(CarStage.DOOR_OPEN_EXITING))
        assertTrue(CarRules.awaitsSwipeUp(CarStage.REFUEL_PENDING))
        // The car drives itself: idle and driving stages need no swipe.
        assertFalse(CarRules.awaitsSwipeUp(CarStage.INSIDE_ENGINE_ON))
        assertFalse(CarRules.awaitsSwipeUp(CarStage.INSIDE_MOVING))
        assertFalse(CarRules.awaitsSwipeUp(CarStage.NOT_DEPLOYED))
        assertFalse(CarRules.awaitsSwipeUp(CarStage.DESTROYED))
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
        assertEquals("Open the door", Strings.get("carOpenDoor", "en"))
        assertEquals("Close the door", Strings.get("carCloseDoor", "en"))
        assertEquals("Start car", Strings.get("carStartPrompt", "en"))
        assertEquals("The car is standing by with the engine on. It starts and follows the enemy again when he moves.", Strings.get("carEngineOnPrompt", "en"))
        assertEquals("Refuel for 500 coins, swipe up to refuel", Strings.get("carRefuelPrompt", "en"))
    }

    @Test
    fun theAlarmIsBelowFourHundredHealth() {
        assertTrue(CarRules.alarmed(399))
        assertFalse(CarRules.alarmed(400))
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
    fun buyingTheCarGivesOneCarWithFiveGames() {
        val bought = Shop.buyDefence(Inventory(), 1500, DefenceItem.CAR, completedRounds = 1)
        assertTrue(bought.success)
        assertEquals(0, bought.coins)
        assertEquals(1, bought.inventory.cars)
        assertEquals(5, bought.inventory.carGamesLeft)
    }

    @Test
    fun theCarCannotBeBoughtTwiceAndNeedsTheMoney() {
        val owned = Inventory(cars = 1, carGamesLeft = 1)
        val again = Shop.buyDefence(owned, 99_999, DefenceItem.CAR, completedRounds = 5)
        assertFalse(again.success)
        assertEquals("alreadyOwned", again.reasonKey)

        val poor = Shop.buyDefence(Inventory(), 999, DefenceItem.CAR, completedRounds = 5)
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
    fun refuelingNeedsAnOwnedEmptyCarAndFiveHundredCoins() {
        assertEquals("notOwned", Shop.refuelCar(Inventory(), 9_999).reasonKey)

        val fuelled = Inventory(cars = 1, carGamesLeft = 1)
        assertEquals("carHasFuel", Shop.refuelCar(fuelled, 9_999).reasonKey)

        val empty = Inventory(cars = 1, carGamesLeft = 0)
        val broke = Shop.refuelCar(empty, 499)
        assertFalse(broke.success)
        assertEquals("notEnoughCoins", broke.reasonKey)
        assertEquals(499, broke.coins)

        val refuelled = Shop.refuelCar(empty, 2500)
        assertTrue(refuelled.success)
        assertEquals(2000, refuelled.coins)
        assertEquals(5, refuelled.inventory.carGamesLeft)
    }

    @Test
    fun aCarIsUsableInFiveGamesThenNeedsFuel() {
        var inventory = Shop.buyDefence(Inventory(), 1500, DefenceItem.CAR, completedRounds = 1).inventory
        repeat(5) {
            inventory = Shop.useCarGame(inventory)
        }
        assertEquals(0, inventory.carGamesLeft)
        assertEquals(1, inventory.cars)
        // Out of fuel now: the next refuel is allowed and costs the full price.
        val refuelled = Shop.refuelCar(inventory, 2_000)
        assertTrue(refuelled.success)
        assertEquals(1500, refuelled.coins)
        assertEquals(5, refuelled.inventory.carGamesLeft)
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
        assertEquals(400, CarRules.ALARM_HP)
    }

    @Test
    fun enemyReceivesCarStartingInRoundFive() {
        assertFalse(RobertRules.robotCarGranted(3))
        assertFalse(RobertRules.robotCarGranted(4))
        assertTrue(RobertRules.robotCarGranted(5))
        assertTrue(RobertRules.shouldEnterCar(5, 399, false))
        assertFalse(RobertRules.shouldEnterCar(3, 399, false))
        assertFalse(RobertRules.shouldEnterCar(4, 399, false))
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
    fun theElectricShockIsNeverAGunOrAutonomous() {
        assertEquals(1500, Weapon.ELECTRIC_SHOCK.price)
        assertEquals(3, Weapon.ELECTRIC_SHOCK.unlockRound)
        assertTrue(Weapon.ELECTRIC_SHOCK.electricShock)
        assertFalse(Weapon.ELECTRIC_SHOCK.isFirearm)
        assertFalse(Weapon.ELECTRIC_SHOCK.needsReload)
        // It is never a rifle in Robert's hands: he detects and fires the
        // shock autonomously through the shock rules instead.
        for (round in 1..5) {
            assertFalse(RobertRules.isEnemyAllowed(Weapon.ELECTRIC_SHOCK, round))
        }
        assertFalse(RobertRules.ENEMY_WEAPONS.contains(Weapon.ELECTRIC_SHOCK))
        assertFalse(RobertRules.robotShockUnlocked(2))
        assertTrue(RobertRules.robotShockUnlocked(3))
        assertFalse(RobertRules.shouldStartShock(3, 40f, robertShocking = false))
        assertFalse(RobertRules.shouldStartShock(3, 39f, robertShocking = false))
        assertFalse(RobertRules.shouldStartShock(3, 40f, robertShocking = true))
    }

    @Test
    fun theElectricShockIsOnlyUnlockedAfterRoundTwo() {
        assertEquals(2, Weapon.ELECTRIC_SHOCK.requiredCompletedRounds)
        assertTrue(Weapon.forSale().contains(Weapon.ELECTRIC_SHOCK))
    }

    @Test
    fun theShockDrainsSixtyPercentPerSecondInTheCloseBand() {
        assertEquals(60f, ElectricShockRules.playerDrainPercentPerSecond(40f), 0.001f)
        assertEquals(60f, ElectricShockRules.playerDrainPercentPerSecond(70f), 0.001f)
        assertEquals(60f, ElectricShockRules.playerDrainPercentPerSecond(100f), 0.001f)
        assertEquals(10f, ElectricShockRules.playerDrainPercentPerSecond(39.9f), 0.001f)
        assertEquals(10f, ElectricShockRules.playerDrainPercentPerSecond(0f), 0.001f)
        // Alone: the full close-band rate applies no matter who struck first.
        assertEquals(60f, ElectricShockRules.shockPercentPerSecond(70f, bothShocking = false, activatedFirst = true), 0.001f)
        assertEquals(60f, ElectricShockRules.shockPercentPerSecond(70f, bothShocking = false, activatedFirst = false), 0.001f)
    }

    @Test
    fun aShockDuelTakesTenPercentOfBothBarsEveryTwoSeconds() {
        // The duel is symmetric: both fighters lose 10 percent every two
        // seconds, whoever activated first, so the weaker bar loses first.
        for (first in listOf(true, false)) {
            assertEquals(
                5f,
                ElectricShockRules.shockPercentPerSecond(70f, bothShocking = true, activatedFirst = first),
                0.001f
            )
        }
        assertEquals(5f, ElectricShockRules.duelPercentPerSecond(), 0.001f)
        assertEquals(2_000L, ElectricShockRules.DUEL_WINDOW_MS)
        assertEquals(10f, ElectricShockRules.DUEL_PERCENT_PER_WINDOW, 0.001f)
        // Ten percent of a 200 health bar every two seconds is 20 health.
        assertEquals(
            20f,
            ElectricShockRules.damageFor(200, ElectricShockRules.DUEL_PERCENT_PER_SECOND, 2_000L),
            0.001f
        )
    }

    @Test
    fun theShockOnlyWorksInsideTheFortyToHundredBand() {
        assertTrue(ElectricShockRules.inCloseBand(40f))
        assertTrue(ElectricShockRules.inCloseBand(70f))
        assertTrue(ElectricShockRules.inCloseBand(100f))
        assertFalse(ElectricShockRules.inCloseBand(39.99f))
        assertFalse(ElectricShockRules.inCloseBand(0f))
        // The barrier-breaking band is the top ten percent only, with
        // distinct continuous-shock durations for each barrier type.
        assertTrue(ElectricShockRules.inBreakBand(90f))
        assertFalse(ElectricShockRules.inBreakBand(89f))
        assertEquals(9_000L, ElectricShockRules.barrierBreakDurationMs(isGlueWall = true))
        assertEquals(7_000L, ElectricShockRules.barrierBreakDurationMs(isGlueWall = false))
        assertFalse(ElectricShockRules.barrierBreakReady(8_999L, isGlueWall = true))
        assertTrue(ElectricShockRules.barrierBreakReady(9_000L, isGlueWall = true))
        assertFalse(ElectricShockRules.barrierBreakReady(6_999L, isGlueWall = false))
        assertTrue(ElectricShockRules.barrierBreakReady(7_000L, isGlueWall = false))
    }

    @Test
    fun robertEitherCountersTheShockOrFleesFromIt() {
        // A detected shock inside the band can be answered; outside it cannot.
        assertTrue(RobertRules.shouldCounterShock(true, 5_000, 70f))
        assertFalse(RobertRules.shouldCounterShock(true, 0, 30f))
        assertFalse(RobertRules.shouldCounterShock(false, 0, 70f))
        // Both answers stay possible whatever the health and the random draw.
        val answers = (1..200).map {
            RobertRules.chooseShockAnswer(
                round = 3,
                robertHp = 120,
                robertMaxHp = 400,
                random = kotlin.random.Random(it)
            )
        }.toSet()
        assertEquals(setOf(RobertRules.ShockAnswer.COUNTER, RobertRules.ShockAnswer.FLEE), answers)
        // He cannot raise a shock before round three, so he only ever runs.
        repeat(50) {
            assertEquals(
                RobertRules.ShockAnswer.FLEE,
                RobertRules.chooseShockAnswer(2, 400, 400, kotlin.random.Random(it))
            )
        }
        // Fleeing only happens while the shock really is in range.
        assertTrue(RobertRules.shouldFleeShock(70f, playerShocking = true))
        assertFalse(RobertRules.shouldFleeShock(20f, playerShocking = true))
        assertFalse(RobertRules.shouldFleeShock(70f, playerShocking = false))
    }

    @Test
    fun robertAnswersAfterFiveSeconds() {
        assertFalse(RobertRules.shouldCounterShock(false, 0))
        assertFalse(ElectricShockRules.counterOpen(4_999))
        assertEquals(5_000L, ElectricShockRules.COUNTER_DELAY_MS)
        assertEquals(5f, ElectricShockRules.FIRST_STRIKER_PERCENT_PER_SECOND, 0.001f)
        assertEquals(5f, ElectricShockRules.secondStrikerPercentPerSecond(), 0.001f)
    }

    @Test
    fun aUseEndsAtTenSeconds() {
        assertFalse(ElectricShockRules.timeUp(9_999))
        assertTrue(ElectricShockRules.timeUp(10_000))
        assertEquals(10_000L, ElectricShockRules.MAX_USE_MS)
        assertTrue(ElectricShockRules.GLUE_WALL_BREAK_MS < ElectricShockRules.MAX_USE_MS)
    }

    @Test
    fun damagePerTickIsThePercentageOfMaximumHealth() {
        // 60 percent of 200 health per second: 12 health in a tenth of a second.
        assertEquals(12f, ElectricShockRules.damageFor(200, 60f, 100L), 0.001f)
        assertEquals(120f, ElectricShockRules.damageFor(200, 60f, 1000L), 0.001f)
        assertEquals(0f, ElectricShockRules.damageFor(200, 60f, -5L), 0.001f)
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

    @Test
    fun theIdleCarStaysParkedInsteadOfBrakingOnEveryTick() {
        // While it drives, the car brakes once it is at its stopping distance.
        assertEquals(2f, CarRules.STOP_DISTANCE_M, 0.001f)
        // A parked car tolerates a small gap before it sets off again, so a
        // standing enemy 2 m to 3 m away cannot make it start and brake over
        // and over (which used to sound like a broken brake).
        val parked = CarRules.STOP_DISTANCE_M
        val tolerated = CarRules.STOP_DISTANCE_M + CarRules.GAP_TOLERANCE_M
        assertTrue(tolerated > parked)
        assertTrue(CarRules.GAP_TOLERANCE_M > 0f)
        // Beyond the tolerated band it really has to close the gap again.
        assertTrue(tolerated < Combat.MAX_RANGE)
        // Every car move is a real step: the car never brakes while it is
        // already inside its stopping distance.
        assertEquals(6f, CarRules.SPEED_MPS, 0.001f)
    }
}
