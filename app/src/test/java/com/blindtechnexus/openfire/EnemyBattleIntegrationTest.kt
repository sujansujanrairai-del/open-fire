package com.blindtechnexus.openfire

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.blindtechnexus.openfire.audio.AudioManager
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.model.Weapon
import com.blindtechnexus.openfire.viewmodel.GameViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual battle entry points, not just the strategy helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EnemyBattleIntegrationTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: GameViewModel, name: String): MutableStateFlow<T> {
        val field = GameViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(vm) as MutableStateFlow<T>
    }

    private fun invoke(vm: GameViewModel, name: String, vararg args: Any): Any? {
        val method = GameViewModel::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        return method.invoke(vm, *args)
    }

    @Test
    fun shockIsADuelInTheCloseBandAndRobertAnswersOrFlees() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(
                round = 3,
                distance = 5f,
                playerHp = 200,
                playerMaxHp = 200,
                robertHp = 200,
                robertMaxHp = 200,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id),
                ammo = listOf(0),
                damaged = listOf(false),
                robertWeapons = listOf(Weapon.ASSAULT_GUN.id),
                robertAmmo = listOf(20),
                robertDamaged = listOf(false)
            )
            // Robert never raises a shock on his own: the shock is a counter.
            invoke(vm, "startRobertShock")
            assertFalse(state.value.robertShocking)
            vm.startHoldFire()
            assertTrue(state.value.shockHolding)
            assertTrue(
                "inside the 40 to 100 band the shock hum must play",
                vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER)
            )
            // The enemy answered this shock, either with a duel or by fleeing.
            val duel = state.value.robertShocking
            val lastTick = GameViewModel::class.java.getDeclaredField("shockLastTickMs").apply {
                isAccessible = true
            }
            val startRobert = state.value.robertHp
            val startPlayer = state.value.playerHp
            // Ten one-second ticks: outside a duel that is 60 percent of the
            // bar; in a duel it is five two-second windows of 10 percent for
            // both fighters.
            if (duel) {
                assertTrue(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_ENEMY))
            }
            var now = android.os.SystemClock.elapsedRealtime()
            repeat(10) {
                now += 1_000L
                lastTick.setLong(vm, now - 1_000L)
                val before = state.value
                invoke(vm, "shockTick")
                val after = state.value
                assertTrue("the shock must take health", after.robertHp <= before.robertHp)
                assertTrue("the shock must never heal", after.playerHp <= before.playerHp)
            }
            assertTrue("the shock must really hurt", state.value.robertHp < startRobert)
            assertTrue("the player must not be healed", state.value.playerHp <= startPlayer)
            assertTrue("the shock must be paid", state.value.shockDamageDealt > 0)
            vm.stopHoldFire()
            assertFalse(state.value.shockHolding)
            assertFalse(state.value.robertShocking)
            assertFalse(state.value.electricShockInPlay)
            assertFalse(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_ENEMY))
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun theShockIsRefusedOutsideTheCloseBand() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            // 25 metres is zero proximity: the shock cannot reach him.
            state.value = GameState(
                round = 3,
                distance = Combat.MAX_RANGE,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id),
                ammo = listOf(0),
                damaged = listOf(false)
            )
            vm.startHoldFire()
            assertFalse(state.value.shockHolding)
            assertFalse(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER))
            // Inside the 40 to 100 percent band the very same gesture works.
            state.value = state.value.copy(distance = 5f)
            vm.startHoldFire()
            assertTrue(state.value.shockHolding)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun noWeaponFiresWhileAProjectileIsInTheAir() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            // While a missile or a bomb is in the air no gun fires: the shot
            // is refused, so the weapon is held and then drifts back, which is
            // the grace time to deploy an anti-missile or anti-bomb defence.
            for (missile in listOf(false, true)) {
                state.value = GameState(distance = 5f, missileInFlight = missile, bombInFlight = !missile)
                vm.fire()
                assertEquals(0, state.value.playerShots)
            }
            // With a clear sky the very same weapon fires normally.
            state.value = GameState(distance = 5f)
            vm.fire()
            assertEquals(1, state.value.playerShots)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun defenceDeploymentAndPlayerWallReactionRespectTheHealthBoundary() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            for (hp in listOf(150, 151, 500)) {
                for (item in listOf(DefenceItem.MEDKIT, DefenceItem.COVER, DefenceItem.GLUE_WALL)) {
                    val initial = GameState(robertHp = hp, robertMaxHp = 500,
                        robertMedkits = 1, robertCovers = 1, robertGlueWalls = 1)
                    state.value = initial
                    assertEquals("", invoke(vm, "deployRobertDefence", item))
                    assertEquals(initial, state.value)
                }
                state.value = GameState(robertHp = hp, robertMaxHp = 500,
                    glueWallActive = true, robertGlueWalls = 1)
                invoke(vm, "onPlayerGlueWallRaised")
                assertFalse(state.value.robertGlueWallActive)
                assertEquals(1, state.value.robertGlueWalls)
            }
            state.value = GameState(robertHp = 149, robertMaxHp = 500,
                glueWallActive = true, robertGlueWalls = 1)
            invoke(vm, "onPlayerGlueWallRaised")
            assertTrue(state.value.robertGlueWallActive)
            assertEquals(0, state.value.robertGlueWalls)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun protectedEnemyStandsGroundAgainstBreakerThreat() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            flow<Inventory>(vm, "_inventory").value = Inventory(weaponIds = setOf(Weapon.GLUE_BREAKER.id))
            state.value = GameState(robertHp = 149, robertMaxHp = 500,
                glueWallActive = true, robertGlueWallActive = true, robertGlueBreakers = 1)
            invoke(vm, "onPlayerGlueWallRaised")
            assertEquals("advance", state.value.robertState)
            assertEquals(1, state.value.robertGlueBreakers)
            assertTrue(state.value.robertBreakerArmed)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }
    @Test
    fun realAttacksKeepFiringAndRespectDeadlineEvenAfterReload() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(distance = 5f, robertGlueWallActive = true,
                robertWeapons = listOf(Weapon.ASSAULT_GUN.id, Weapon.SHORT_GUN.id, Weapon.SNIPER.id),
                robertAmmo = listOf(0, 0, 0), robertDamaged = listOf(false, false, false))
            val deadline = GameViewModel::class.java.getDeclaredField("robertNextShotAtMs").apply { isAccessible = true }
            repeat(6) { index ->
                deadline.setLong(vm, 0L)
                invoke(vm, "robertAttack")
                assertEquals(index + 1, state.value.robertShots)
                assertTrue(deadline.getLong(vm) > android.os.SystemClock.elapsedRealtime())
                invoke(vm, "robertAttack")
                assertEquals(index + 1, state.value.robertShots)
            }
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun hitExchangeRetreatsImmediatelyButCoverKeepsEnemyFighting() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            val initial = GameState(distance = 5f, robertHp = 180, fightPlayerHits = 2,
                fightRobertHits = 1, robertConsecutiveHits = 1)
            state.value = initial
            invoke(vm, "robertAfterHit", 21)
            assertEquals("retreat", state.value.robertState)
            for (wall in listOf(false, true)) {
                state.value = initial.copy(robertCoverActive = !wall, robertGlueWallActive = wall)
                invoke(vm, "robertAfterHit", 0)
                assertEquals("advance", state.value.robertState)
            }
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun enemyAutomaticFireIsHeldOnItsOwnChannelAndStopsOnReleaseOrWeaponChange() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            invoke(vm, "playEnemyFireSound", Weapon.AK47)
            assertTrue(vm.audioManager.isWeaponLoopActive("ak47", AudioManager.LOOP_OWNER_ENEMY))
            assertFalse(vm.audioManager.isWeaponLoopActive("ak47", AudioManager.LOOP_OWNER_PLAYER))

            // Repeated bullet ticks reuse the channel. Changing guns releases
            // the old gun before opening the new one.
            invoke(vm, "playEnemyFireSound", Weapon.AK47)
            invoke(vm, "playEnemyFireSound", Weapon.MACHINE_GUN)
            assertFalse(vm.audioManager.isWeaponLoopActive("ak47", AudioManager.LOOP_OWNER_ENEMY))
            assertTrue(vm.audioManager.isWeaponLoopActive("machineGun", AudioManager.LOOP_OWNER_ENEMY))

            invoke(vm, "stopEnemyFireSound")
            assertFalse(vm.audioManager.isAnyWeaponLoopActive())
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun sustainedHitsRetreatBeforePanicDefenceResetsCountersEvenInAnEvenExchange() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            val state = flow<GameState>(vm, "_gameState")
            state.value = GameState(distance = 5f, robertHp = 100, robertMaxHp = 200,
                fightPlayerHits = 2, fightRobertHits = 2, robertConsecutiveHits = 2,
                playerBurstShots = 2, playerBurstRobertHp = 150, robertMedkits = 1)
            invoke(vm, "robertAfterHit", 21)
            assertEquals("retreat", state.value.robertState)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

}
