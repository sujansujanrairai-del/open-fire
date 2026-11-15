package com.blindtechnexus.openfire

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blindtechnexus.openfire.audio.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loop ownership of the mixer: a fighter's held firing loop is his own, and
 * nothing the other fighter does can start, stop or mask it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioLoopLifecycleTest {

    private val automatic = listOf("submachineGun", "ak47", "machineGun")

    @Test
    fun aFreshManagerOwnsNoFiringLoop() {
        val audio = AudioManager(ApplicationProvider.getApplicationContext<Context>())
        try {
            assertFalse(audio.isAnyWeaponLoopActive())
            for (key in automatic) {
                assertFalse(audio.isWeaponLoopActive(key, AudioManager.LOOP_OWNER_PLAYER))
                assertFalse(audio.isWeaponLoopActive(key, AudioManager.LOOP_OWNER_ENEMY))
            }
        } finally {
            audio.release()
        }
    }

    @Test
    fun theOneShotCopyOfAnAutomaticWeaponNeverOpensALoop() {
        val audio = AudioManager(ApplicationProvider.getApplicationContext<Context>())
        try {
            for ((shot, loop) in AudioManager.AUTO_LOOP_OF) {
                audio.playAutomaticBurst(shot, 300L, owner = AudioManager.LOOP_OWNER_ENEMY)
                assertFalse(audio.isWeaponLoopActive(loop, AudioManager.LOOP_OWNER_ENEMY))
                assertFalse(audio.isWeaponLoopActive(shot, AudioManager.LOOP_OWNER_ENEMY))
            }
        } finally {
            audio.release()
        }
    }

    @Test
    fun eachFighterHoldsHisOwnChannelAndStoppingOneNeverSilencesTheOther() {
        val audio = AudioManager(ApplicationProvider.getApplicationContext<Context>())
        try {
            audio.startWeaponLoop("ak47", owner = AudioManager.LOOP_OWNER_PLAYER)
            audio.startWeaponLoop("ak47", owner = AudioManager.LOOP_OWNER_ENEMY)
            assertTrue(audio.isWeaponLoopActive("ak47", AudioManager.LOOP_OWNER_PLAYER))
            assertTrue(audio.isWeaponLoopActive("ak47", AudioManager.LOOP_OWNER_ENEMY))

            audio.stopPlayerWeaponLoop("ak47")
            assertFalse(audio.isWeaponLoopActive("ak47", AudioManager.LOOP_OWNER_PLAYER))
            assertTrue(audio.isWeaponLoopActive("ak47", AudioManager.LOOP_OWNER_ENEMY))
        } finally {
            audio.release()
        }
    }

    @Test
    fun repeatedStartsReuseTheSameLoopInsteadOfAddingOne() {
        val audio = AudioManager(ApplicationProvider.getApplicationContext<Context>())
        try {
            audio.startWeaponLoop("machineGun")
            audio.startWeaponLoop("machineGun")
            audio.keepWeaponLoopAlive("machineGun")
            audio.stopPlayerWeaponLoops()
            assertFalse(audio.isWeaponLoopActive("machineGun", AudioManager.LOOP_OWNER_PLAYER))
            assertFalse(audio.isAnyWeaponLoopActive())
        } finally {
            audio.release()
        }
    }

    @Test
    fun stoppingOneWeaponSoundOnlyTouchesThatOwner() {
        val audio = AudioManager(ApplicationProvider.getApplicationContext<Context>())
        try {
            audio.startWeaponLoop("submachineGun", owner = AudioManager.LOOP_OWNER_ENEMY)
            audio.startWeaponLoop("magicalSound", owner = AudioManager.LOOP_OWNER_PLAYER)
            audio.stopWeaponSound("submachineGunShot", AudioManager.LOOP_OWNER_PLAYER)
            assertTrue(audio.isWeaponLoopActive("submachineGun", AudioManager.LOOP_OWNER_ENEMY))
            assertTrue(audio.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER))
            audio.stopWeaponLoops()
            assertFalse(audio.isAnyWeaponLoopActive())
        } finally {
            audio.release()
        }
    }

    @Test
    fun theAutomaticKeyTablesAreRoundTrips() {
        for (loop in automatic) {
            val shot = AudioManager.AUTO_LOOP_OF.entries.first { it.value == loop }.key
            assertEquals(loop, AudioManager.AUTO_LOOP_OF[shot])
            assertTrue(loop in AudioManager.AUTO_LOOP_KEYS)
            assertTrue(loop in AudioManager.WEAPON_SOUND_KEYS)
            assertFalse(shot in AudioManager.AUTO_LOOP_KEYS)
        }
        assertEquals(setOf(AudioManager.LOOP_OWNER_PLAYER, AudioManager.LOOP_OWNER_ENEMY),
            AudioManager.LOOP_OWNERS.toSet())
    }
}
