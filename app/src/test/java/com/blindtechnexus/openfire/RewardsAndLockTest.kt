package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.model.BattlePerformance
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.GraceRules
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.HitZone
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.LockRules
import com.blindtechnexus.openfire.model.Rewards
import com.blindtechnexus.openfire.model.Shop
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Locks the 1.2 Beta coin law, the reward ladder and the "Lock the Opponent"
 * rules: coins follow the capability and power of a battle, one real electric
 * shock pays 700 to 1000 coins on its own, a win pays at most 2500 coins, and
 * a lock freezes the opponent for exactly one minute.
 */
class RewardsAndLockTest {

    private fun performance(
        won: Boolean = true,
        firePower: Int = 0,
        shockCoins: Int = 0,
        playerHp: Int = 0,
        playerMaxHp: Int = 200,
        hits: Int = 0,
        shots: Int = 0
    ) = BattlePerformance(
        won = won,
        firePower = firePower,
        shockCoins = shockCoins,
        playerHp = playerHp,
        playerMaxHp = playerMaxHp,
        hits = hits,
        shots = shots
    )

    @Test
    fun aWinIsBuiltFromPowerShockHealthAndAccuracy() {
        // A bare win pays the base 200 coins and nothing else.
        assertEquals(Rewards.WIN_BASE_COINS, Rewards.battleCoins(performance()))
        // A defeat pays nothing at all, whatever the battle looked like.
        assertEquals(0, Rewards.battleCoins(performance(won = false, firePower = 5000)))
        // Every component adds its own share.
        val full = Rewards.battleCoins(
            performance(firePower = 600, playerHp = 200, hits = 10, shots = 10)
        )
        assertEquals(
            Rewards.WIN_BASE_COINS + 600 + Rewards.MAX_HEALTH_BONUS + Rewards.MAX_ACCURACY_BONUS,
            full
        )
        // A win is capped, however long the fight was.
        assertEquals(
            Rewards.MAX_BATTLE_COINS,
            Rewards.battleCoins(performance(firePower = 99_999, playerHp = 2600, playerMaxHp = 2600, hits = 500, shots = 500))
        )
        assertEquals(2500, Rewards.MAX_BATTLE_COINS)
        // Accuracy is only paid for shots that were really fired: a fight
        // without a single shot earns no accuracy credit, and the health that
        // survived is what pays instead.
        assertEquals(0f, BattlePerformance(shots = 0).accuracyFraction, 0.0001f)
        assertEquals(Rewards.WIN_BASE_COINS, Rewards.battleCoins(performance(shots = 0, hits = 0)))
        assertEquals(0.5f, BattlePerformance(hits = 5, shots = 10).accuracyFraction, 0.0001f)
        assertEquals(
            Rewards.WIN_BASE_COINS + Rewards.MAX_ACCURACY_BONUS,
            Rewards.battleCoins(performance(hits = 10, shots = 10))
        )
    }

    @Test
    fun oneRealElectricShockPaysBetweenSevenHundredAndAThousand() {
        assertEquals(700, Rewards.SHOCK_MIN_COINS)
        assertEquals(1000, Rewards.SHOCK_MAX_COINS)
        // Anything below one drained health point is not a use at all.
        assertEquals(0, Rewards.shockCoins(0, 400))
        assertEquals(0, Rewards.shockCoins(-5, 400))
        // One point of drained health already pays the minimum.
        assertEquals(700, Rewards.shockCoins(1, 400))
        // Half the bar pays in between, never below the minimum.
        val half = Rewards.shockCoins(200, 400)
        assertTrue("half=$half", half in 700..1000)
        // Draining the whole bar alone pays the maximum...
        assertEquals(1000, Rewards.shockCoins(400, 400))
        assertEquals(1000, Rewards.shockCoins(900, 400))
        // ...and the shock share is never the old 150 coins.
        assertTrue(Rewards.shockCoins(1, 400) > 150)
    }

    @Test
    fun weaponsPayByPowerNotByShotCount() {
        assertEquals(600, Rewards.powerPointsFor(Weapon.MISSILE))
        assertEquals(500, Rewards.powerPointsFor(Weapon.ATOM_BOMB))
        assertEquals(350, Rewards.powerPointsFor(Weapon.GRENADE))
        assertEquals(350, Rewards.powerPointsFor(Weapon.TIMER_BOMB))
        // Guns scale with their heavy-zone damage, so one heavy hit of the
        // strongest gun always pays more than one heavy hit of the knife.
        assertTrue(Rewards.powerPointsFor(Weapon.AWM) > Rewards.powerPointsFor(Weapon.KNIFE))
        assertTrue(Rewards.powerPointsFor(Weapon.MACHINE_GUN) > Rewards.powerPointsFor(Weapon.SHORT_GUN))
        for (weapon in listOf(Weapon.KNIFE, Weapon.SHORT_GUN, Weapon.ASSAULT_GUN, Weapon.AWM, Weapon.MACHINE_GUN)) {
            assertEquals(
                weapon.id,
                com.blindtechnexus.openfire.model.DamageTable.percentOf(weapon, HitZone.HEAVY) * 3,
                Rewards.powerPointsFor(weapon)
            )
        }
        // The shock is paid by the shock law alone, never as a gun.
        assertEquals(0, Rewards.powerPointsFor(Weapon.ELECTRIC_SHOCK))
    }

    @Test
    fun theRewardLadderIsUnchangedAndNeverGivesTwoGlueWalls() {
        assertEquals(2, Rewards.ROUND_REWARD.glueWalls)
        assertEquals(2, Rewards.ROUND_REWARD.medikits)
        assertEquals(3, Rewards.ROUND_REWARD.covers)
        assertEquals(5, Rewards.LEVEL_REWARDS.size)
        for (bundle in Rewards.LEVEL_REWARDS) {
            assertTrue(bundle.glueWalls <= 1)
            assertTrue(bundle.total > 0)
        }
        assertTrue(Rewards.levelRewardsRespectTheGlueWallLimit())
        // A promotion picks the right bundle.
        assertEquals(Rewards.ROUND_REWARD, Rewards.forPromotion(levelCompleted = true, roundCompleted = true, Random(1)))
        assertTrue(Rewards.LEVEL_REWARDS.contains(Rewards.forPromotion(true, false, Random(1))))
        assertTrue(Rewards.forPromotion(false, false, Random(1)).isEmpty)
    }

    @Test
    fun aHeldWeaponDriftsBackWhileAProjectileIsInTheAir() {
        assertEquals(GraceRules.DRIFT_PER_TICK_M, GraceRules.driftAfter(-3f), 0.0001f)
        assertEquals(GraceRules.DRIFT_PER_TICK_M, GraceRules.driftAfter(0f), 0.0001f)
        assertTrue(GraceRules.isHeld(GraceRules.driftAfter(0f)))
        assertFalse(GraceRules.isHeld(0f))
        // The drift is capped and it walks back in once the sky is clear.
        assertEquals(GraceRules.MAX_DRIFT_M, GraceRules.driftAfter(GraceRules.MAX_DRIFT_M), 0.0001f)
        assertEquals(0f, GraceRules.driftReleased(GraceRules.DRIFT_PER_TICK_M), 0.0001f)
        assertEquals(
            GraceRules.DRIFT_PER_TICK_M * 2f,
            GraceRules.driftAfter(GraceRules.driftAfter(0f)),
            0.0001f
        )
        // A held weapon aims further away than the opponent really is.
        assertEquals(5f, GraceRules.aimedDistance(4f, 1f), 0.0001f)
        assertEquals(Combat.MAX_RANGE, GraceRules.aimedDistance(Combat.MAX_RANGE, 5f), 0.0001f)
        // The guaranteed grace window covers a whole bomb flight.
        assertTrue(GraceRules.graceOpen(0L))
        assertTrue(GraceRules.graceOpen(GraceRules.MIN_GRACE_MS))
        assertFalse(GraceRules.graceOpen(GraceRules.MIN_GRACE_MS + 1L))
        assertTrue(GraceRules.driftFor(1_000L) > 0f)
    }

    @Test
    fun theLockFreezesTheOpponentForExactlyOneMinute() {
        assertEquals(60_000L, LockRules.DURATION_MS)
        val now = 10_000L
        val until = LockRules.endsAt(now)
        assertEquals(now + 60_000L, until)
        assertTrue(LockRules.isLocked(until, now))
        assertTrue(LockRules.isLocked(until, now + 59_999L))
        assertFalse(LockRules.isLocked(until, now + 60_000L))
        assertEquals(60_000L, LockRules.remainingMs(until, now))
        assertEquals(1L, LockRules.remainingMs(until, now + 59_999L))
        assertEquals(0L, LockRules.remainingMs(until, now + 99_999L))
        assertFalse(LockRules.isLocked(0L, now))
    }

    @Test
    fun theLockBlocksOffenceAndKeepsEveryDefence() {
        assertTrue(LockRules.blocksOpponent(locked = true))
        assertFalse(LockRules.blocksOpponent(locked = false))
        assertTrue(LockRules.blocksOffensiveWeapons(locked = true))
        assertFalse(LockRules.blocksOffensiveWeapons(locked = false))
        assertTrue(LockRules.weaponIsOffensive(Weapon.MACHINE_GUN))
        assertTrue(LockRules.weaponIsOffensive(Weapon.ELECTRIC_SHOCK))
        assertTrue(LockRules.allowsDefence(locked = true))
        for (item in DefenceItem.entries) {
            assertTrue("$item must stay usable", LockRules.isDefensive(item))
        }
        for (item in listOf(DefenceItem.CAR, DefenceItem.ANTI_MISSILE, DefenceItem.ANTI_BOMB, DefenceItem.COVER, DefenceItem.GLUE_WALL)) {
            assertTrue(DefenceItem.defensive().contains(item))
        }
    }

    @Test
    fun theLockIsBoughtUsedAndNeverStacked() {
        assertEquals(1500, LockRules.PRICE)
        assertEquals(1500, DefenceItem.LOCK.price)
        assertEquals(DefenceItem.LOCK, DefenceItem.entries.first { it.id == LockRules.ITEM_ID })
        // It is unlocked from the second battle of a profile onwards.
        assertEquals(1, DefenceItem.LOCK.requiredCompletedRounds)
        val bought = Shop.buyDefence(Inventory(), 1500, DefenceItem.LOCK, completedRounds = 1)
        assertTrue(bought.success)
        assertEquals(0, bought.coins)
        assertEquals(1, bought.inventory.lockCharges)
        // A missing wallet is refused, and one lock is one charge.
        assertFalse(Shop.buyDefence(Inventory(), 1499, DefenceItem.LOCK, completedRounds = 1).success)
        val used = Shop.consume(bought.inventory, DefenceItem.LOCK)
        assertEquals(0, used.lockCharges)
        // The shop sorts its defences by price, and the lock lands in it.
        assertEquals(1500, Shop.buyDefence(Inventory(), 5000, DefenceItem.LOCK, completedRounds = 1).coins.let { 5000 - it })
        assertTrue(LockRules.canActivate(alreadyLocked = false, charges = 1))
        assertFalse(LockRules.canActivate(alreadyLocked = true, charges = 1))
        assertFalse(LockRules.canActivate(alreadyLocked = false, charges = 0))
    }
}
