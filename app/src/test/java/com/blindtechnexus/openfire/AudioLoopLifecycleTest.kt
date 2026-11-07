package com.blindtechnexus.openfire

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.blindtechnexus.openfire.audio.AudioManager
import com.blindtechnexus.openfire.audio.VolumeMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/**
 * Regression cover for the phantom weapon loops and for the routing rules that
 * keep a single shot away from a held burst:
 *
 *  - a fresh manager owns no playing loop at all (nothing is warmed up that
 *    could be heard without a trigger being held);
 *  - stopping a loop only ever touches that one weapon;
 *  - the one-shot copies of the three automatic weapons are never loop
 *    channels, so an enemy answer or a feint can never start a permanent
 *    burst;
 *  - releasing a trigger destroys its looping player completely, so no pause,
 *    resume, watchdog or late preparation can bring a burst back;
 *  - the player's firing channel and Robert's are separate, so neither
 *    fighter's stop can silence the other's gun;
 *  - a single shot of an automatic weapon never opens a channel of its own:
 *    only a hold may do that, and every other automatic request is bounded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioLoopLifecycleTest {

    private val automatic = listOf("submachineGun", "ak47", "machineGun")

    @Suppress("UNCHECKED_CAST")
    private fun channels(audio: AudioManager): Collection<Any> =
        (AudioManager::class.java.getDeclaredField("weaponLoopChannels").apply { isAccessible = true }
            .get(audio) as ConcurrentHashMap<String, Any>).values

    private fun fieldOf(channel: Any, name: String): Any? =
        channel.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(channel)

    /** How many looping players of an automatic weapon exist right now. */
    private fun liveLoopPlayers(audio: AudioManager): Int =
        channels(audio).count { fieldOf(it, "player") != null }

    /** How many fighters are firing an automatic weapon right now. */
    private fun heldChannels(audio: AudioManager): Int =
        channels(audio).count { fieldOf(it, "desired") as Boolean }

    /** True while exactly this fighter is firing exactly this weapon. */
    private fun isHeld(audio: AudioManager, owner: String, key: String): Boolean =
        channels(audio).any { fieldOf(it, "id") == "$owner:$key" && fieldOf(it, "desired") as Boolean }

    @Test
    fun aFreshManagerHasNoLoopRunning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            assertEquals(automatic.toSet(), AudioManager.AUTO_LOOP_KEYS)
            for (key in automatic) {
                assertFalse("$key must not loop before the trigger is held", audio.isWeaponLoopActive(key))
            }
            assertFalse(audio.isAnyWeaponLoopActive())
            assertEquals(0, liveLoopPlayers(audio))
            assertEquals(0, heldChannels(audio))
        } finally {
            audio.release()
        }
    }

    @Test
    fun stoppingOneLoopNeverTouchesAnotherWeapon() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            // Releasing a trigger that was never pressed is a no-op for every
            // weapon, and it never starts or stops a different one.
            for (key in automatic) {
                audio.stopWeaponLoop(key)
                for (other in automatic) {
                    assertFalse(audio.isWeaponLoopActive(other))
                }
            }
            audio.stopWeaponLoops()
            for (key in automatic) assertFalse(audio.isWeaponLoopActive(key))
        } finally {
            audio.release()
        }
    }

    @Test
    fun aReleasedTriggerLeavesNoLoopingPlayerBehind() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            // Holding a trigger opens exactly one channel, with one player.
            audio.startWeaponLoop("ak47")
            assertEquals(1, heldChannels(audio))
            assertTrue(isHeld(audio, AudioManager.LOOP_OWNER_PLAYER, "ak47"))
            // Releasing it destroys that player. Nothing is parked, paused or
            // prepared any more, so there is no object left that a resume, a
            // watchdog or a late preparation could fire on its own.
            audio.stopWeaponLoop("ak47")
            assertEquals(0, heldChannels(audio))
            assertEquals(0, liveLoopPlayers(audio))
            assertFalse(audio.isWeaponLoopActive("ak47"))
            assertFalse(audio.isAnyWeaponLoopActive())
        } finally {
            audio.release()
        }
    }

    @Test
    fun pauseAndResumeNeverReviveALoopNobodyHolds() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            // A burst that is released - before, during or after a pause - is
            // over for good: no resume may ever start it again.
            audio.startWeaponLoop("ak47")
            audio.pauseAllSounds()
            audio.stopWeaponLoop("ak47")
            audio.resumeAllSounds()
            audio.pauseAllSounds()
            audio.resumeAllSounds()
            assertFalse(audio.isWeaponLoopActive("ak47"))
            assertEquals(0, heldChannels(audio))
            assertEquals(0, liveLoopPlayers(audio))

            // The same holds for a burst that was never held at all.
            audio.pauseAllSounds()
            audio.resumeAllSounds()
            assertEquals(0, liveLoopPlayers(audio))
            assertFalse(audio.isAnyWeaponLoopActive())
        } finally {
            audio.release()
        }
    }

    @Test
    fun eachFighterOwnsHisOwnFiringChannel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            audio.startWeaponLoop("ak47", owner = AudioManager.LOOP_OWNER_ENEMY)
            audio.startWeaponLoop("submachineGun", owner = AudioManager.LOOP_OWNER_PLAYER)
            assertEquals(2, heldChannels(audio))

            // The player's release can never silence Robert, and Robert closing
            // his burst can never silence the player's held one.
            audio.stopPlayerWeaponLoops()
            assertFalse(isHeld(audio, AudioManager.LOOP_OWNER_PLAYER, "submachineGun"))
            assertTrue(isHeld(audio, AudioManager.LOOP_OWNER_ENEMY, "ak47"))
            audio.stopEnemyWeaponLoops()
            assertFalse(audio.isAnyWeaponLoopActive())
            assertEquals(0, liveLoopPlayers(audio))
            assertEquals(0, heldChannels(audio))

            // Stopping one weapon of one fighter leaves his other weapons and
            // the whole other fighter alone.
            audio.startWeaponLoop("ak47", owner = AudioManager.LOOP_OWNER_ENEMY)
            audio.startEnemyWeaponLoop("machineGun")
            audio.stopEnemyWeaponLoop("ak47")
            assertFalse(isHeld(audio, AudioManager.LOOP_OWNER_ENEMY, "ak47"))
            assertTrue(isHeld(audio, AudioManager.LOOP_OWNER_ENEMY, "machineGun"))
            audio.stopWeaponSounds()
            assertEquals(0, heldChannels(audio))
        } finally {
            audio.release()
        }
    }

    @Test
    fun aSingleAutomaticShotIsNeverAPermanentLoop() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            // One shot on a channel of its own is bounded by the mixer itself,
            // and it ends with the firing that asked for it.
            audio.playAutomaticBurst("ak47", holdMs = 500L)
            assertEquals(1, heldChannels(audio))
            audio.stopPlayerWeaponLoops()
            assertEquals(0, heldChannels(audio))

            // A stray request for an automatic recording - a menu, a queued
            // play, a feint - plays a bounded clip and never opens a channel.
            audio.playSound("ak47Shot")
            audio.playSound("ak47")
            audio.playLoop("ak47Shot")
            assertEquals(0, heldChannels(audio))
            // A tapped automatic weapon does open one, and the fighter's own
            // stop is what closes it again.
            audio.playWeaponSound("submachineGun")
            assertEquals(1, heldChannels(audio))
            audio.stopPlayerWeaponLoops()
            assertEquals(0, heldChannels(audio))

            // A screen change clears both fighters completely.
            audio.startWeaponLoop("machineGun", owner = AudioManager.LOOP_OWNER_ENEMY)
            audio.startWeaponLoop("submachineGun")
            audio.stopWeaponSounds()
            assertFalse(audio.isAnyWeaponLoopActive())
            assertEquals(0, liveLoopPlayers(audio))
            assertEquals(0, heldChannels(audio))
        } finally {
            audio.release()
        }
    }

    @Test
    fun theAutomaticKeyTablesAreRoundTrips() {
        // Every loop key owns a shot copy and every shot copy maps back to it,
        // so a stop can never miss one of the two spellings of the same gun.
        for (key in automatic) {
            val shot = AudioManager.AUTO_LOOP_SHOT_KEYS[key]
                ?: error("$key owns no one-shot copy")
            assertEquals(shot, AudioManager.AUTO_LOOP_OF[shot])
            assertTrue(key in AudioManager.AUTO_LOOP_KEYS)
            assertTrue(key in AudioManager.WEAPON_SOUND_KEYS)
            assertFalse("$shot must never be a loop channel", shot in AudioManager.AUTO_LOOP_KEYS)
        }
        assertEquals(
            AudioManager.LOOP_OWNERS.toSet(),
            setOf(AudioManager.LOOP_OWNER_PLAYER, AudioManager.LOOP_OWNER_ENEMY)
        )
    }

    @Test
    fun aSingleShotCopyIsNeverALoopChannel() {
        for (shot in listOf("submachineGunShot", "ak47Shot", "machineGunShot")) {
            assertFalse("$shot must never be looped", shot in AudioManager.AUTO_LOOP_KEYS)
            assertTrue("$shot belongs to the automatic weapon family", shot in AudioManager.AUTO_WEAPON_KEYS)
        }
        assertEquals(
            automatic.toSet(),
            AudioManager.AUTO_WEAPON_KEYS - AudioManager.AUTO_SHOT_KEYS
        )
        // Every weapon key of the family is mixed on the weapon bus, so the
        // impacts always stay louder than the gunfire.
        for (key in AudioManager.AUTO_WEAPON_KEYS) {
            assertEquals(key, VolumeMixer.WEAPON_BUS_GAIN, VolumeMixer.busGainFor(key), 0.0001f)
        }
    }

    @Test
    fun theStoredLevelsAreAppliedAndClamped() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            // The Music, SFX and speech sliders are applied once, exactly as
            // chosen, and never fall back to a fixed default.
            audio.musicVolume = 0.2f
            audio.sfxVolume = 0.2f
            audio.walkVolume = 0.2f
            assertEquals(0.2f, audio.musicVolume, 0.0001f)
            assertEquals(0.2f, audio.sfxVolume, 0.0001f)
            assertEquals(0.2f, audio.walkVolume, 0.0001f)

            // A held weapon loop and a hit at the same position: the hit is
            // louder than the gun at the very same slider position.
            val (shotLeft, shotRight) = audio.calculate3dVolume(0f, 0f, 0f, 1.0f, "ak47")
            val (hitLeft, hitRight) = audio.calculate3dVolume(0f, 0f, 0f, 1.0f, "enemyHit")
            assertTrue(hitLeft > shotLeft)
            assertTrue(hitRight > shotRight)
            assertEquals(shotLeft, shotRight, 0.0001f)

            audio.musicVolume = 0.9f
            audio.sfxVolume = 1.0f
            assertEquals(0.9f, audio.musicVolume, 0.0001f)
            assertEquals(1.0f, audio.sfxVolume, 0.0001f)
        } finally {
            audio.release()
        }
    }
}
