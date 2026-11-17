package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.audio.VolumeMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the volume bug: the stored user setting must be stable
 * and the background-music gain must be applied exactly once.
 */
class VolumeMixerTest {

    @Test
    fun effectsMatchSpeechAndOnlyMusicIsTrimmedUnderIt() {
        for (setting in listOf(0f, 0.2f, 0.8f, 1f)) {
            assertEquals(setting, VolumeMixer.speech(setting), 0.0001f)
            assertEquals(setting, VolumeMixer.mix("ak47", setting, 1f, 1f), 0.0001f)
            assertEquals(setting, VolumeMixer.mix("enemyHit", setting, 1f, 1f), 0.0001f)
            // A loud weapon is played at exactly the speech level, so the
            // machine gun can never be quieter than the voice.
            assertEquals(setting, VolumeMixer.mix("machineGun", setting, 1f, 1f), 0.0001f)
            assertEquals(setting, VolumeMixer.mix("atomBomb", setting, 1f, 1f), 0.0001f)
            // Music is the only bus under the speech, and it follows the slider.
            assertEquals(setting * VolumeMixer.MUSIC_TRIM, VolumeMixer.music(setting), 0.0001f)
            assertTrue(setting < 0.0001f || VolumeMixer.music(setting) < VolumeMixer.speech(setting))
        }
        assertEquals(VolumeMixer.MUSIC_TRIM, VolumeMixer.music(4f), 0.0001f)
        assertEquals(0f, VolumeMixer.music(-3f), 0.0001f)
    }

    @Test
    fun theLoudWeaponsAreOnTheSpeechLevel() {
        for (key in listOf("machineGun", "submachineGun", "grenade", "atomBomb", "missile")) {
            assertTrue("$key must be loud", VolumeMixer.isLoudSfx(key))
            // The trim of a loud weapon is full scale, so it is never quieter
            // than the speech bus at the same slider position.
            assertEquals(key, VolumeMixer.LOUD_BUS_GAIN, VolumeMixer.busGainFor(key), 0.0001f)
            assertEquals(key, VolumeMixer.LOUD_BUS_GAIN, VolumeMixer.mix(key, 1f, 1f, 1f), 0.0001f)
            assertEquals(key, VolumeMixer.LOUD_BUS_GAIN * 0.5f, VolumeMixer.mix(key, 0.5f, 1f, 1f), 0.0001f)
        }
        assertFalse(VolumeMixer.isLoudSfx("click"))
        // A loud weapon is heard exactly as loudly as the voice at full volume.
        assertEquals(VolumeMixer.speech(1f), VolumeMixer.mix("machineGun", 1f, 1f, 1f), 0.0001f)
    }

    @Test
    fun everyAutomaticWeaponAndEveryImpactIsOnTheRightBus() {
        for (key in listOf("submachineGun", "ak47", "machineGun", "submachineGunShot", "ak47Shot", "machineGunShot")) {
            assertEquals(key, VolumeMixer.WEAPON_BUS_GAIN, VolumeMixer.busGainFor(key), 0.0001f)
        }
        for (key in listOf("playerHit", "enemyHit", "gunHit", "explosion", "knifeHit", "swordHit")) {
            assertEquals(key, VolumeMixer.HIT_BUS_GAIN, VolumeMixer.busGainFor(key), 0.0001f)
        }
    }

    @Test
    fun speechVolumeMatchesTheUserSetting() {
        assertEquals(0.7f, VolumeMixer.speech(0.7f), 0.0001f)
        assertEquals(1.0f, VolumeMixer.speech(1.0f), 0.0001f)
        assertEquals(0.0f, VolumeMixer.speech(0.0f), 0.0001f)
        // Out-of-range values are clamped, never amplified.
        assertEquals(1.0f, VolumeMixer.speech(4.0f), 0.0001f)
        assertEquals(0.0f, VolumeMixer.speech(-3.0f), 0.0001f)
    }

    @Test
    fun sfxMixesTheSettingAndDistanceGainOnce() {
        assertEquals(0.5f, VolumeMixer.sfx(0.5f, 1.0f, 1.0f), 0.0001f)
        assertEquals(0.125f, VolumeMixer.sfx(0.5f, 0.5f, 0.5f), 0.0001f)
        assertEquals(0.0f, VolumeMixer.sfx(0.0f, 1.0f, 1.0f), 0.0001f)
    }

    @Test
    fun walkingVolumeFallsWithDistance() {
        val close = VolumeMixer.walk(1.0f, 2f, 50f, 1.0f)
        val mid = VolumeMixer.walk(1.0f, 25f, 50f, 1.0f)
        val far = VolumeMixer.walk(1.0f, 50f, 50f, 1.0f)
        assertTrue("Close footsteps must be loudest", close > mid)
        assertTrue("Far footsteps must be quietest", mid > far)
        assertTrue("Far footsteps must still be audible", far > 0f)

        // The live distance gain used by the footstep tracks.
        assertEquals(1f, VolumeMixer.walkDistanceGain(0f, 100f), 0.0001f)
        assertTrue(VolumeMixer.walkDistanceGain(50f, 100f) < 0.5f)
        assertTrue(VolumeMixer.walkDistanceGain(100f, 100f) > 0f)
        assertTrue(
            VolumeMixer.walkDistanceGain(20f, 100f) >
                VolumeMixer.walkDistanceGain(80f, 100f)
        )
    }

    @Test
    fun footstepRateRisesWithMovementSpeedInsideTheNaturalWindow() {
        assertTrue(VolumeMixer.walkRate(2f) < VolumeMixer.walkRate(6f))
        assertTrue(VolumeMixer.walkRate(6f) < VolumeMixer.walkRate(12f))
        // Slow movement is slowed a little, a sprint is sped up, and both stay
        // inside the narrow natural window that never sounds chipped.
        assertTrue(VolumeMixer.walkRate(1f) < 1f)
        assertTrue(VolumeMixer.walkRate(14f) > 1f)
        assertEquals(0.90f, VolumeMixer.walkRate(0f), 0.0001f)
        assertEquals(VolumeMixer.MAX_WALK_RATE, VolumeMixer.walkRate(100f), 0.0001f)
        assertEquals(VolumeMixer.MAX_RATE, VolumeMixer.MAX_WALK_RATE, 0.0001f)
        for (speed in listOf(0f, 1f, 4f, 9f, 40f)) {
            val rate = VolumeMixer.walkRate(speed)
            assertTrue("rate $rate out of window at $speed", rate in VolumeMixer.MIN_RATE..VolumeMixer.MAX_RATE)
        }
    }

    @Test
    fun noPlaybackRateCanEverLeaveTheNaturalWindow() {
        assertEquals(VolumeMixer.MIN_RATE, VolumeMixer.naturalRate(0.1f), 0.0001f)
        assertEquals(VolumeMixer.MAX_RATE, VolumeMixer.naturalRate(3f), 0.0001f)
        assertEquals(1f, VolumeMixer.naturalRate(1f), 0.0001f)
        assertEquals(1f, VolumeMixer.naturalRate(Float.NaN), 0.0001f)
        assertTrue(VolumeMixer.naturalRate(2f) < 2f)
        assertTrue(VolumeMixer.enemyWalkRate(6f) <= VolumeMixer.MAX_RATE)
    }

    @Test
    fun footstepVolumeRefreshesEveryHalfSecondOrQuarterSecondWhenFast() {
        assertEquals(500L, VolumeMixer.walkUpdateIntervalMs(3f))
        assertEquals(500L, VolumeMixer.walkUpdateIntervalMs(7.9f))
        assertEquals(250L, VolumeMixer.walkUpdateIntervalMs(8f))
        assertEquals(250L, VolumeMixer.walkUpdateIntervalMs(20f))
    }

    @Test
    fun theThreeFingerRunningLevelsAreMixedSmoothly() {
        // Slow running is quieter, normal is the base, fast is the loudest the
        // user setting allows. The gain is clamped so a slow run never becomes
        // inaudible ([MIN_RUN_GAIN]) and a fast run never exceeds the chosen
        // volume.
        assertEquals(0.60f, VolumeMixer.runVolumeGain(0.6f), 0.0001f)
        assertTrue(VolumeMixer.runVolumeGain(0.8f) < VolumeMixer.runVolumeGain(1.0f))
        assertEquals(1.0f, VolumeMixer.runVolumeGain(1.0f), 0.0001f)
        assertTrue(VolumeMixer.runVolumeGain(0.8f) >= VolumeMixer.runVolumeGain(0.6f))
        // Anything below the floor is lifted, anything above the ceiling cut.
        assertEquals(VolumeMixer.MIN_RUN_GAIN, VolumeMixer.runVolumeGain(0.1f), 0.0001f)
        assertEquals(1.0f, VolumeMixer.runVolumeGain(3.0f), 0.0001f)
        assertEquals(1.0f, VolumeMixer.runVolumeGain(Float.NaN), 0.0001f)

        // The playback rate follows the chosen speed level too: slow steps are
        // stretched out and a sprint is sped up.
        assertTrue(VolumeMixer.runRate(0.6f) < VolumeMixer.runRate(1.0f))
        assertTrue(VolumeMixer.runRate(1.0f) < VolumeMixer.runRate(1.6f))
        assertEquals(1.0f, VolumeMixer.runRate(1.0f), 0.0001f)
        // An unknown (zero, negative or NaN) multiplier keeps the natural rate
        // instead of dragging the footsteps down.
        assertEquals(1.0f, VolumeMixer.runRate(0f), 0.0001f)
        assertEquals(1.0f, VolumeMixer.runRate(-2f), 0.0001f)
        assertEquals(1.0f, VolumeMixer.runRate(Float.NaN), 0.0001f)
        // A real but very slow level stays inside the window and a sprint is
        // capped, so the footsteps can never stop or race away.
        assertTrue(VolumeMixer.runRate(0.05f) >= VolumeMixer.MIN_RATE)
        assertTrue(VolumeMixer.runRate(0.05f) < VolumeMixer.runRate(1.0f))
        assertEquals(VolumeMixer.MAX_RATE, VolumeMixer.runRate(100f), 0.0001f)
        for (level in listOf(0.05f, 0.5f, 1f, 2f, 100f)) {
            val rate = VolumeMixer.runRate(level)
            assertTrue("rate $rate out of window at $level", rate in VolumeMixer.MIN_RATE..VolumeMixer.MAX_RATE)
        }
    }

    @Test
    fun headOnSoundsStayPerfectlyCentredAtFullVolume() {
        // Straight motion (no sideways offset) never pans at all.
        assertEquals(0f, VolumeMixer.panFor(0f, 30f), 0.0001f)
        assertEquals(0f, VolumeMixer.panFor(0f, 100f), 0.0001f)
        // Tiny offsets inside the head-on cone are centred too.
        assertEquals(0f, VolumeMixer.panFor(1f, 40f), 0.0001f)
        assertEquals(0f, VolumeMixer.panFor(-2f, 50f), 0.0001f)
        // A centred sound plays at full volume in both ears, exactly like
        // the mono speech voice — no harsh mixed dip in the middle.
        val (left, right) = VolumeMixer.panGains(0f)
        assertEquals(1f, left, 0.0001f)
        assertEquals(1f, right, 0.0001f)
    }

    @Test
    fun onlyAGenuineSidewaysOffsetPansAndEvenThenGently() {
        // A flank far to the right pans right, but never hard.
        val flankPan = VolumeMixer.panFor(60f, 65f)
        assertTrue(flankPan > 0f)
        assertTrue(flankPan < 1f)
        val (left, right) = VolumeMixer.panGains(flankPan)
        assertTrue(right > left)
        assertTrue(left > 0f)
        assertTrue(VolumeMixer.panFor(-60f, 65f) < 0f)
    }
}
