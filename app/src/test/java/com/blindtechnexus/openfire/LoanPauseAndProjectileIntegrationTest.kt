package com.blindtechnexus.openfire

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.blindtechnexus.openfire.data.EncryptedStore
import com.blindtechnexus.openfire.data.PreferencesManager
import com.blindtechnexus.openfire.model.BombKind
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.GameStats
import com.blindtechnexus.openfire.model.MoveDirection
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.viewmodel.GameViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoanPauseAndProjectileIntegrationTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: GameViewModel, name: String): MutableStateFlow<T> =
        GameViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun invoke(vm: GameViewModel, name: String, vararg args: Any): Any? =
        GameViewModel::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(vm, *args)

    private inline fun withViewModel(test: (Application, GameViewModel) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PreferencesManager(app).clearStats()
        val vm = GameViewModel(app)
        try {
            test(app, vm)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
            PreferencesManager(app).clearStats()
        }
    }

    @Test
    fun issueAndRepayAcrossWinsSurvivesNewPreferencesInstances() = withViewModel { app, vm ->
        flow<Screen>(vm, "_currentScreen").value = Screen.SHOP
        flow<GameStats>(vm, "_stats").value = GameStats(coins = 100)
        vm.takeLoan(1500)
        assertEquals(1600, vm.stats.value.coins)
        assertEquals(1500, PreferencesManager(app).loadStats().loanBalance)
        vm.takeLoan(500)
        assertEquals(1600, vm.stats.value.coins)
        assertEquals(1500, vm.stats.value.loanBalance)

        // Spend the loan before the fight; repayment must come from winnings,
        // not from the 90 coins still in the wallet.
        flow<GameStats>(vm, "_stats").value = vm.stats.value.copy(coins = 90)
        flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
        flow<GameState>(vm, "_gameState").value = GameState(
            playerHp = 1900, playerMaxHp = 2600, robertHp = 0, robertMaxHp = 2600, playerHits = 0
        )
        invoke(vm, "endGame", true, false) // 150 + half of 1900 = 1100 gross
        assertEquals(Screen.RESULT, vm.currentScreen.value)
        assertEquals(1100, vm.resultData.value.coins)
        assertEquals(1100, vm.resultData.value.loanDeducted)
        assertEquals(400, vm.resultData.value.loanRemaining)
        assertEquals(90, PreferencesManager(app).loadStats().coins)
        assertEquals(400, PreferencesManager(app).loadStats().loanBalance)

        flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
        flow<GameState>(vm, "_gameState").value = GameState(
            playerHp = 600, playerMaxHp = 2600, robertHp = 0, robertMaxHp = 2600
        )
        invoke(vm, "endGame", true, false) // 450 gross, 400 withheld, 50 credited
        assertEquals(400, vm.resultData.value.loanDeducted)
        assertEquals(0, vm.resultData.value.loanRemaining)
        assertEquals(140, PreferencesManager(app).loadStats().coins)
        assertEquals(0, PreferencesManager(app).loadStats().loanBalance)
        flow<Screen>(vm, "_currentScreen").value = Screen.SHOP
        vm.takeLoan(500)
        assertEquals(500, PreferencesManager(app).loadStats().loanBalance)
    }

    @Test
    fun oldProfileDefaultsToNoLoanAndFailedAtomicWriteKeepsOldProfile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = EncryptedStore(app)
        try {
            assertTrue(store.write("profile", mapOf("schema" to "5", "coins" to "300")))
            assertEquals(0, PreferencesManager(app).loadStats().loanBalance)
            assertTrue(store.write("profile", mapOf("schema" to "6", "coins" to "900", "loanBalance" to "500")))
            val blockingDirectory = java.io.File(store.storageDirectory(), "profile.tmp")
            assertTrue(blockingDirectory.mkdir())
            try {
                assertFalse(store.write("profile", mapOf("coins" to "1", "loanBalance" to "0")))
                val after = PreferencesManager(app).loadStats()
                assertEquals(900, after.coins)
                assertEquals(500, after.loanBalance)
            } finally {
                blockingDirectory.delete()
            }
        } finally {
            PreferencesManager(app).clearStats()
        }
    }

    @Test
    fun pauseBlocksBattleInputAndRemainsPausedAfterBackgrounding() = withViewModel { _, vm ->
        flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
        val initial = GameState(playerHp = 180, robertHp = 170, distance = 5f,
            missileInFlight = true, missileFromPlayer = true, battleRemainingMs = 42_000L)
        flow<GameState>(vm, "_gameState").value = initial
        vm.handleGesture("down", 3)
        assertTrue(vm.battlePaused.value)
        vm.fire()
        vm.startHoldFire()
        vm.startPlayerMove(MoveDirection.BACKWARD)
        vm.handleGesture("down", 1)
        vm.handleGesture("right", 3)
        assertEquals(initial, vm.gameState.value)
        vm.onAppPaused()
        vm.onAppResumed()
        assertTrue(vm.battlePaused.value)
        assertEquals(initial, vm.gameState.value)
        vm.handleGesture("down", 3)
        assertFalse(vm.battlePaused.value)
        assertEquals(initial, vm.gameState.value)
    }

    @Test
    fun threeFingersUpExitsBattleToMenu() = withViewModel { _, vm ->
        flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
        val initial = GameState(playerHp = 180, robertHp = 170, distance = 5f)
        flow<GameState>(vm, "_gameState").value = initial
        vm.handleGesture("up", 3)
        assertEquals(Screen.MENU, vm.currentScreen.value)
    }

    @Test
    fun missileAndTimerBombImpactsUseTheNewLethalRules() = withViewModel { _, vm ->
        flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
        val state = flow<GameState>(vm, "_gameState")
        state.value = GameState(playerHp = 200, robertHp = 200,
            missileInFlight = true, missileFromPlayer = true)
        invoke(vm, "onMissileImpact")
        // A direct missile hit now destroys every remaining health point.
        assertEquals(0, state.value.robertHp)
        assertEquals(200, state.value.playerHp)
        assertEquals(Screen.BATTLE, vm.currentScreen.value)

        state.value = GameState(playerHp = 200, robertHp = 200,
            missileInFlight = true, missileFromPlayer = false)
        invoke(vm, "onMissileImpact")
        assertEquals(0, state.value.playerHp)
        assertEquals(200, state.value.robertHp)

        state.value = GameState(playerHp = 200, robertHp = 200,
            playerX = 10f, playerY = 10f, robertX = 11f, robertY = 10f,
            bombX = 11f, bombY = 10f, bombInFlight = true,
            bombFromPlayer = true, bombKind = BombKind.TIMER.id)
        invoke(vm, "onBombBlast")
        // The timer bomb takes 68% of remaining health from both fighters
        // inside its safety radius: 136 of 200, leaving 64 each.
        assertEquals(64, state.value.robertHp)
        assertEquals(64, state.value.playerHp)
        assertEquals(Screen.BATTLE, vm.currentScreen.value)
    }
}
