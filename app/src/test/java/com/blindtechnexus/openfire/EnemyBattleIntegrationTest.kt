package com.blindtechnexus.openfire

import android.app.Application
import androidx.test.core.app.ApplicationProvider
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
            invoke(vm, "robertAfterHit")
            assertEquals("retreat", state.value.robertState)
            for (wall in listOf(false, true)) {
                state.value = initial.copy(robertCoverActive = !wall, robertGlueWallActive = wall)
                invoke(vm, "robertAfterHit")
                assertEquals("advance", state.value.robertState)
            }
        } finally {
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

}
