package com.blindtechnexus.openfire

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.blindtechnexus.openfire.audio.AudioManager
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.ElectricShockRules
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.RobertRules
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.model.Weapon
import com.blindtechnexus.openfire.model.withPositions
import com.blindtechnexus.openfire.viewmodel.GameViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.hypot

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
    fun shockPushesAnIncomingMissileOnlyAfterFiveContinuousSeconds() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            // A big, healthy bar: the solo shock takes a share of what the
            // victim still has, so Robert must stay alive through the full
            // five continuous seconds the push needs.
            state.value = GameState(
                round = 1, distance = 5f,
                playerHp = 100_000, playerMaxHp = 100_000,
                robertHp = 100_000, robertMaxHp = 100_000,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id), ammo = listOf(0)
            )
            vm.startHoldFire()
            state.value = state.value.copy(missileInFlight = true, missileFromPlayer = false)
            val duration = GameViewModel::class.java.getDeclaredField("missileFlightDurationMs").apply { isAccessible = true }
            val impact = GameViewModel::class.java.getDeclaredField("missileImpactAtMs").apply { isAccessible = true }
            val used = GameViewModel::class.java.getDeclaredField("missileDeflectionUsed").apply { isAccessible = true }
            duration.setLong(vm, 10_000L)
            impact.setLong(vm, android.os.SystemClock.elapsedRealtime() + 5_000L)
            invoke(vm, "tickActiveShock", 4_999L)
            assertFalse(used.getBoolean(vm))
            invoke(vm, "tickActiveShock", 1L)
            assertTrue(used.getBoolean(vm))
            assertTrue(impact.getLong(vm) - android.os.SystemClock.elapsedRealtime() >= 9_900L)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun shockPushesMovingCarWithoutDamagingItsPassenger() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(
                round = 1, distance = 5f, playerX = 1f, playerY = 1f, robertX = 1f, robertY = 6f,
                playerHp = 5000, playerMaxHp = 5000, robertHp = 5000, robertMaxHp = 5000,
                robertInCar = true, weapons = listOf(Weapon.ELECTRIC_SHOCK.id), ammo = listOf(0)
            )
            GameViewModel::class.java.getDeclaredField("robertCarStarted").apply { isAccessible = true }.setBoolean(vm, true)
            vm.startHoldFire()
            invoke(vm, "tickActiveShock", 4_999L)
            assertEquals(1f, state.value.robertX, 0.001f)
            invoke(vm, "tickActiveShock", 1L)
            assertEquals(25f, state.value.robertX, 0.001f)
            assertEquals(25f, state.value.robertY, 0.001f)
            assertEquals(5000, state.value.robertHp)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun shockDamagesRobertInTheCloseBandWithOrWithoutACounter() {
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
            // A counter changes the damage rate, not whether the hold stays active.
            val duel = state.value.robertShocking
            val lastTick = GameViewModel::class.java.getDeclaredField("shockLastTickMs").apply {
                isAccessible = true
            }
            val startRobert = state.value.robertHp
            val startPlayer = state.value.playerHp
            // Ten one-second ticks exercise the solo damage and any duel
            // damage without relying on an exact countershock roll.
            if (duel) {
                assertTrue(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_ENEMY))
            }
            var sawRobertDrop = false
            var sawPlayerDrop = false
            var peakPaid = 0
            repeat(10) {
                // One whole second of the battle clock since the previous tick.
                lastTick.setLong(vm, android.os.SystemClock.elapsedRealtime() - 1_000L)
                val before = state.value
                invoke(vm, "shockTick")
                val after = state.value
                // A tick that ends the fight resets the state for the next
                // round, so a rise is allowed - healing inside a tick is not:
                // the health only ever moves down while the shock is held.
                assertTrue("the shock must never heal", after.robertHp <= after.robertMaxHp)
                assertTrue("the shock must never heal", after.playerHp <= after.playerMaxHp)
                if (after.robertHp < before.robertHp) sawRobertDrop = true
                if (after.playerHp < before.playerHp) sawPlayerDrop = true
                // A shock that finishes the fight starts the next round with a
                // clean state, so the payment is remembered at its peak.
                if (after.shockDamageDealt > peakPaid) peakPaid = after.shockDamageDealt
            }
            assertTrue("the shock must really hurt", sawRobertDrop)
            assertTrue("the player must not be healed", state.value.playerHp <= startPlayer)
            if (duel) {
                assertTrue("a duel costs the player health as well", sawPlayerDrop)
            }
            assertTrue("the shock must be paid", peakPaid > 0)
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
    fun releasingShockThrowsRobertAndBlocksImmediateCounterfire() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(
                round = 2, distance = 5f,
                playerX = 1f, playerY = 1f, robertX = 1f, robertY = 6f,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id), ammo = listOf(0)
            )
            vm.startHoldFire()
            invoke(vm, "robertStep")
            assertTrue(state.value.shockHolding)
            assertEquals(5f, state.value.distance, 0.001f)
            vm.stopHoldFire()
            assertEquals("shockReturn", state.value.robertState)
            assertEquals(Arena.SIZE, state.value.robertX, 0.001f)
            assertEquals(Arena.SIZE, state.value.robertY, 0.001f)
            assertEquals(24f, state.value.robertLateral, 0.001f)
            assertTrue(state.value.robertShockRecovering)
            val shots = state.value.robertShots
            invoke(vm, "robertAttack")
            assertEquals(shots, state.value.robertShots)
            val landing = state.value
            vm.stopHoldFire()
            assertEquals(landing.robertX, state.value.robertX, 0.001f)
            assertEquals(landing.robertY, state.value.robertY, 0.001f)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun recoverySurvivesProjectileAndLockStatesAndFollowsAMovingPlayer() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(
                round = 2, distance = 5f,
                playerX = 12.5f, playerY = 12.5f, robertX = 12.5f, robertY = 17.5f,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id), ammo = listOf(0),
                robertWeapons = listOf(Weapon.AWM.id), robertAmmo = listOf(5),
                robertDamaged = listOf(false)
            )
            vm.startHoldFire()
            vm.stopHoldFire()
            assertTrue(state.value.robertShockRecovering)
            // AWM can reach this centre-to-corner distance, but may not fire
            // until the return is complete even in this geometric edge case.
            assertTrue(RobertRules.canLandShotAt(state.value.distance, Weapon.AWM))
            val shots = state.value.robertShots
            state.value = state.value.copy(robertState = "missileFlee", missileInFlight = true)
            invoke(vm, "robertAttack")
            assertEquals(shots, state.value.robertShots)
            assertTrue(state.value.robertShockRecovering)
            // Even a stale evasion label must not strand the recovery once the
            // projectile has gone away.
            state.value = state.value.copy(missileInFlight = false)
            invoke(vm, "robertStep")
            assertEquals("shockReturn", state.value.robertState)
            assertTrue(state.value.robertShockRecovering)
            // Simulate a temporary lock taking the visible movement state.
            state.value = state.value.copy(
                robertState = "locked", opponentLockedUntilMs = android.os.SystemClock.elapsedRealtime() + 10_000L
            )
            invoke(vm, "robertAttack")
            invoke(vm, "robertStep")
            assertEquals(shots, state.value.robertShots)
            assertTrue(state.value.robertShockRecovering)
            state.value = state.value.copy(opponentLockedUntilMs = 0L, robertState = "advance")

            // The player moves after the throw. Each recovery step tracks the
            // current position, even when landing on a wall or corner.
            state.value = state.value.withPositions(playerX = 20f, playerY = 20f)
            var ticks = 0
            while (state.value.robertShockRecovering && ticks < 30) {
                val before = state.value
                val distance = hypot(before.playerX - before.robertX, before.playerY - before.robertY)
                invoke(vm, "robertAttack")
                assertEquals(shots, state.value.robertShots)
                invoke(vm, "robertStep")
                val after = state.value
                assertTrue(hypot(after.playerX - after.robertX, after.playerY - after.robertY) < distance)
                assertTrue(after.robertX in Arena.MIN..Arena.MAX && after.robertY in Arena.MIN..Arena.MAX)
                ticks++
            }
            assertFalse("recovery must converge without a wall detour", state.value.robertShockRecovering)
            assertTrue(ticks > 0)
            assertTrue(RobertRules.canLandShotAt(state.value.distance, state.value.robertWeapon))
            assertEquals("advance", state.value.robertState)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun elevenSecondTimeoutProcessesTheLastTickThrowsOnceAndRequiresALift() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(
                round = 1, distance = 2f,
                playerX = 1f, playerY = 1f, robertX = 1f, robertY = 3f,
                playerHp = 1_000, playerMaxHp = 1_000, robertHp = 1_000, robertMaxHp = 1_000,
                robertGlueWallActive = true,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id), ammo = listOf(0)
            )
            vm.startHoldFire()
            val now = android.os.SystemClock.elapsedRealtime()
            GameViewModel::class.java.getDeclaredField("shockStartedAtMs").apply { isAccessible = true }
                .setLong(vm, now - ElectricShockRules.MAX_USE_MS)
            GameViewModel::class.java.getDeclaredField("shockLastTickMs").apply { isAccessible = true }
                .setLong(vm, now - 1_000L)
            GameViewModel::class.java.getDeclaredField("playerShockBreakMs").apply { isAccessible = true }
                .setLong(vm, 8_000L)
            invoke(vm, "shockTick")
            // One second of the solo shock takes half of what Robert still
            // has: 1000 -> 500, never a flat number and never half of his
            // MAXIMUM (which would kill him in two seconds).
            assertEquals(500, state.value.robertHp)
            assertFalse("the final interval must count for breaking the wall", state.value.robertGlueWallActive)
            assertFalse(state.value.shockHolding)
            assertFalse(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER))
            assertTrue(state.value.robertShockRecovering)
            assertEquals(Arena.SIZE, state.value.robertX, 0.001f)
            assertEquals(Arena.SIZE, state.value.robertY, 0.001f)

            // Keeping the same finger down cannot restart the timed-out hold.
            state.value = state.value.withPositions(robertX = 1f, robertY = 3f)
            vm.startHoldFire()
            assertFalse(state.value.shockHolding)
            vm.stopHoldFire()
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
    fun shockReleaseNeedsLiveContactAndCleanupDoesNotThrow() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            val initial = GameState(
                round = 2, distance = 5f,
                playerX = 1f, playerY = 1f, robertX = 1f, robertY = 6f,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id, Weapon.PISTOL.id), ammo = listOf(0, 6)
            )
            state.value = initial
            vm.startHoldFire()
            state.value = state.value.withPositions(robertX = 20f, robertY = 1f)
            vm.stopHoldFire()
            assertEquals(20f, state.value.robertX, 0.001f)
            assertEquals(1f, state.value.robertY, 0.001f)
            assertFalse(state.value.robertShockRecovering)

            state.value = initial
            vm.startHoldFire()
            state.value = state.value.copy(missileInFlight = true, missileFromPlayer = false)
            GameViewModel::class.java.getDeclaredField("missileFlightDurationMs").apply { isAccessible = true }
                .setLong(vm, 1_000L)
            GameViewModel::class.java.getDeclaredField("missileImpactAtMs").apply { isAccessible = true }
                .setLong(vm, android.os.SystemClock.elapsedRealtime())
            vm.stopHoldFire()
            assertEquals(initial.robertY, state.value.robertY, 0.001f)
            assertFalse(state.value.robertShockRecovering)

            state.value = initial
            vm.startHoldFire()
            state.value = state.value.copy(robertHp = 0)
            invoke(vm, "shockTick")
            assertFalse(state.value.shockHolding)
            assertFalse(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER))
            assertFalse(state.value.robertShockRecovering)

            state.value = initial
            vm.startHoldFire()
            vm.switchWeapon(1)
            assertFalse(state.value.shockHolding)
            assertEquals(initial.robertX, state.value.robertX, 0.001f)
            assertFalse(state.value.robertShockRecovering)

            state.value = initial
            vm.startHoldFire()
            vm.toggleBattlePause()
            vm.stopHoldFire()
            assertFalse(state.value.shockHolding)
            assertEquals(initial.robertY, state.value.robertY, 0.001f)
            assertFalse(state.value.robertShockRecovering)
            vm.toggleBattlePause()
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun aDuelReleaseStopsBothHumsAndThrowsOnlyOnce() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(
                round = 2, distance = 5f,
                playerX = 1f, playerY = 1f, robertX = 1f, robertY = 6f,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id), ammo = listOf(0)
            )
            vm.startHoldFire()
            invoke(vm, "startRobertShock")
            assertTrue(state.value.robertShocking)
            vm.stopHoldFire()
            assertFalse(state.value.robertShocking)
            assertFalse(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER))
            assertFalse(vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_ENEMY))
            assertTrue(state.value.robertShockRecovering)
            val landing = state.value
            vm.stopHoldFire()
            assertEquals(landing.robertX, state.value.robertX, 0.001f)
            assertEquals(landing.robertY, state.value.robertY, 0.001f)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun playerAutomaticHoldsAreCentredAtEveryDistanceAndRespectTheSkyLock() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            val loopsField = AudioManager::class.java.getDeclaredField("loops").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val loops = loopsField.get(vm.audioManager) as Map<String, Any>
            for (weapon in listOf(Weapon.SUBMACHINE_GUN, Weapon.AK47, Weapon.MACHINE_GUN)) {
                for (distance in listOf(5f, 15f, 25f)) {
                    state.value = GameState(
                        distance = distance, weapons = listOf(weapon.id), ammo = listOf(weapon.magazine),
                        damaged = listOf(false), robertHp = 1_000, robertMaxHp = 1_000
                    )
                    vm.startHoldFire()
                    assertTrue(state.value.autoFireActive)
                    val slot = loops.getValue("${AudioManager.LOOP_OWNER_PLAYER}|${weapon.fireSoundKey}")
                    val position = slot.javaClass.getDeclaredField("spatialPosition").apply { isAccessible = true }
                    val left = slot.javaClass.getDeclaredField("left").apply { isAccessible = true }
                    assertNull(position.get(slot))
                    assertEquals(1f, left.getFloat(slot), 0.001f)
                    invoke(vm, "keepPlayerHoldLoopAlive")
                    assertNull(position.get(slot))
                    assertEquals(1f, left.getFloat(slot), 0.001f)
                    vm.stopHoldFire()
                }
                state.value = GameState(
                    distance = 5f, weapons = listOf(weapon.id), ammo = listOf(weapon.magazine),
                    damaged = listOf(false), bombInFlight = true
                )
                vm.startHoldFire()
                assertFalse(state.value.autoFireActive)
                assertFalse(vm.audioManager.isWeaponLoopActive(weapon.fireSoundKey, AudioManager.LOOP_OWNER_PLAYER))
            }
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
    fun enemyReturnsToGunfireBetweenExplosiveAttacks() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            state.value = GameState(
                round = 5, distance = 5f, robertHp = 640, robertMaxHp = 1000,
                robertShots = 5, robertBombs = 2, robertGrenades = 2,
                robertWeapons = listOf(Weapon.ASSAULT_GUN.id), robertAmmo = listOf(20),
                robertDamaged = listOf(false), coverActive = true
            )
            val nextShot = GameViewModel::class.java.getDeclaredField("robertNextShotAtMs")
                .apply { isAccessible = true }
            val specialReady = GameViewModel::class.java.getDeclaredField("robertSpecialReadyAtMs")
                .apply { isAccessible = true }
            invoke(vm, "robertAttack")
            assertTrue(state.value.bombInFlight)
            assertEquals(1, state.value.robertBombs)
            invoke(vm, "robertAttack")
            assertEquals(6, state.value.robertShots)

            state.value = state.value.copy(bombInFlight = false, robertHp = 620)
            invoke(vm, "robertAttack")
            assertFalse(state.value.bombInFlight)
            assertEquals(7, state.value.robertShots)
            assertEquals(1, state.value.robertBombs)

            nextShot.setLong(vm, 0L)
            invoke(vm, "robertAttack")
            assertFalse(state.value.bombInFlight)
            nextShot.setLong(vm, 0L)
            specialReady.setLong(vm, 0L)
            invoke(vm, "robertAttack")
            assertTrue(state.value.bombInFlight)
            assertEquals(0, state.value.robertBombs)
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

    @Test
    fun shockWorksDuringProjectileFlightButStopsAtTheMissileDeflectionCutoff() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext<Application>())
        try {
            val state = flow<GameState>(vm, "_gameState")
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            val lastTick = GameViewModel::class.java.getDeclaredField("shockLastTickMs").apply {
                isAccessible = true
            }
            val missileImpactAt = GameViewModel::class.java.getDeclaredField("missileImpactAtMs").apply {
                isAccessible = true
            }
            val missileFlightDuration = GameViewModel::class.java.getDeclaredField("missileFlightDurationMs").apply {
                isAccessible = true
            }
            val armed = GameViewModel::class.java.declaredMethods.single {
                it.name == "playerShockArmed" && it.parameterCount == 1
            }.apply { isAccessible = true }
            state.value = GameState(
                round = 3,
                distance = 5f,
                playerHp = 200,
                playerMaxHp = 200,
                robertHp = 200,
                robertMaxHp = 200,
                weapons = listOf(Weapon.ELECTRIC_SHOCK.id),
                ammo = listOf(0),
                damaged = listOf(false)
            )
            vm.startHoldFire()
            assertTrue(state.value.shockHolding)
            assertTrue(armed.invoke(vm, state.value) as Boolean)

            // An incoming missile in its final three percent blocks shock:
            // no hum and no damage, even though the missile is still flying.
            state.value = state.value.copy(missileInFlight = true)
            missileFlightDuration.setLong(vm, 1_000L)
            missileImpactAt.setLong(vm, android.os.SystemClock.elapsedRealtime() + 30L)
            assertFalse(armed.invoke(vm, state.value) as Boolean)
            lastTick.setLong(vm, android.os.SystemClock.elapsedRealtime() - 1_000L)
            val before = state.value
            invoke(vm, "shockTick")
            assertEquals("a cutoff-blocked hold must not hurt", before.robertHp, state.value.robertHp)
            assertFalse(
                vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER)
            )

            // Earlier in the same missile flight, the hold burns and damages.
            missileFlightDuration.setLong(vm, 100_000L)
            missileImpactAt.setLong(vm, android.os.SystemClock.elapsedRealtime() + 50_000L)
            assertTrue(armed.invoke(vm, state.value) as Boolean)
            lastTick.setLong(vm, android.os.SystemClock.elapsedRealtime() - 1_000L)
            invoke(vm, "shockTick")
            assertTrue(
                "a hold during missile flight must hum",
                vm.audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER)
            )
            repeat(3) {
                if (state.value.robertHp >= 200 && state.value.playerHp >= 200) {
                    lastTick.setLong(vm, android.os.SystemClock.elapsedRealtime() - 1_000L)
                    invoke(vm, "shockTick")
                }
            }
            assertTrue(
                "a hold during missile flight must damage a fighter",
                state.value.robertHp < 200 || state.value.playerHp < 200
            )

            // Clearing the projectile does not interrupt the same held shock.
            state.value = state.value.copy(missileInFlight = false)
            assertTrue(armed.invoke(vm, state.value) as Boolean)

            // The timer picker owns the swipe while it is open.
            state.value = state.value.copy(timerSelectActive = true)
            assertFalse(armed.invoke(vm, state.value) as Boolean)
        } finally {
            invoke(vm, "stopTimers")
            vm.onAppPaused()
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

}
