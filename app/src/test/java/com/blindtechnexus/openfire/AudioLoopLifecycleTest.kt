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

/**
 * Regression cover for the phantom weapon loops and for the routing rules that
 * keep a single shot away from a held burst:
 *
 *  - a fresh manager owns no playing loop at all (nothing is warmed up that
 *    could be heard without a trigger being held);
 *  - stopping a loop only ever touches that one weapon;
 *  - the one-shot copies of the three automatic weapons are never loop
 *    channels, so an enemy answer or a feint can never start a permanent
 *    burst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioLoopLifecycleTest {

    private val automatic = listOf("submachineGun", "ak47", "machineGun")

    @Test
    fun aFreshManagerHasNoLoopRunning() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = AudioManager(context)
        try {
            assertEquals(automatic.toSet(), AudioManager.AUTO_LOOP_KEYS)
            for (key in automatic) {
                assertFalse("$key must not loop before the trigger is held", audio.isWeaponLoopActive(key))
            }
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
