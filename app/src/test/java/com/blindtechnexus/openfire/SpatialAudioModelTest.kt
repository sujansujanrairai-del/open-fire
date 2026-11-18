package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.audio.SoundLibrary
import com.blindtechnexus.openfire.audio.SpatialPlace
import com.blindtechnexus.openfire.audio.VolumeMixer
import com.blindtechnexus.openfire.audio.native.OpenFireAudioEngine
import com.blindtechnexus.openfire.model.Combat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The placement rules the native engine is driven with, and the catalogue that
 * names every sound.
 *
 * These checks are pure Kotlin: they run without a device, and they pin the
 * three things the audio rewrite had to keep exactly:
 *
 *  - a sound that is straight ahead is centred, a sound to one side is on that
 *    side, and a source in front is in front of the listener;
 *  - every sound key of the game resolves to an asset, and the keys the native
 *    engine treats as loops or as projectiles are the ones the game starts and
 *    stops through those paths;
 *  - a playback rate can never leave the natural window, so no recording is
 *    ever heard chipped.
 */
class SpatialAudioModelTest {

    @Test
    fun headOnSourcesAreCentredAndSideSourcesAreOnTheirOwnSide() {
        assertEquals(0f, SpatialPlace.fromOffset(0f, 20f).pan, 0.0001f)
        assertTrue(SpatialPlace.fromOffset(8f, 3f).pan > 0f)
        assertTrue(SpatialPlace.fromOffset(-8f, 3f).pan < 0f)
        // Symmetric offsets are mirrored exactly.
        assertEquals(
            SpatialPlace.fromOffset(9f, 4f).pan,
            -SpatialPlace.fromOffset(-9f, 4f).pan,
            0.0001f
        )
        // A tiny sideways offset inside the head-on cone stays centred.
        assertEquals(0f, SpatialPlace.fromOffset(0.4f, 20f).pan, 0.0001f)
    }

    @Test
    fun frontAndRearHaveOppositeSignsAndTheDistanceIsTheRealDistance() {
        val front = SpatialPlace.fromOffset(0f, 10f)
        val behind = SpatialPlace.fromOffset(0f, -10f)
        assertTrue(front.rear < 0f)
        assertTrue(behind.rear > 0f)
        assertEquals(10f, front.distance, 0.001f)
        assertEquals(10f, behind.distance, 0.001f)
        // The distance never exceeds the arena, and an unknown offset is silent.
        assertEquals(Combat.MAX_RANGE, SpatialPlace.fromOffset(0f, 1_000f).distance, 0.001f)
        assertEquals(SpatialPlace.CENTRE, SpatialPlace.fromOffset(Float.NaN, 3f))
        assertEquals(0f, SpatialPlace.fromOffset(0f, 0f).distance, 0.0001f)
    }

    @Test
    fun theGamesOwnRootZIsFrontWhenItIsNegative() {
        // Every positional call site speaks `posZ = -distance`.
        val ahead = SpatialPlace.fromZ(0f, -12f)
        val behind = SpatialPlace.fromZ(0f, 12f)
        assertEquals(12f, ahead.distance, 0.001f)
        assertTrue("a negative z is in front of the listener", ahead.rear < 0f)
        assertTrue("a positive z is behind the listener", behind.rear > 0f)
    }

    @Test
    fun projectilesStartFarAndCloseInUntilImpact() {
        val launch = SpatialPlace.projectile(24f)
        assertEquals(24f, launch.distance, 0.001f)
        assertEquals(0f, launch.pan, 0.0001f)
        assertEquals(0f, SpatialPlace.approachProximity(24f, 24f), 0.001f)
        assertEquals(0.5f, SpatialPlace.approachProximity(12f, 24f), 0.001f)
        assertEquals(1f, SpatialPlace.approachProximity(0f, 24f), 0.001f)
        // An unknown launch distance is treated as an impact, never as NaN.
        assertEquals(1f, SpatialPlace.approachProximity(5f, 0f), 0.001f)
        assertEquals(0f, SpatialPlace.projectile(Float.NaN).distance, 0.001f)
    }

    @Test
    fun theApproachCueRaisesThePitchInsideTheNaturalWindow() {
        val far = VolumeMixer.approachRate(0f)
        val near = VolumeMixer.approachRate(1f)
        assertEquals(1f, far, 0.0001f)
        assertTrue(near > far)
        assertTrue(near <= VolumeMixer.MAX_RATE)
        assertEquals(1f, VolumeMixer.approachRate(Float.NaN), 0.0001f)
        // The rate the engine is actually given stays natural.
        assertTrue(VolumeMixer.naturalRate(near) <= VolumeMixer.MAX_RATE)
    }

    @Test
    fun theThreeRunningLevelsAreClearlyDifferentAndNatural() {
        val base = com.blindtechnexus.openfire.model.RunPressure.BASE_SPEED_MPS
        val slow = VolumeMixer.footstepRate(base * 0.5f, base * 0.5f, base, base * 3f)
        val normal = VolumeMixer.footstepRate(base, base * 0.5f, base, base * 3f)
        val fast = VolumeMixer.footstepRate(base * 3f, base * 0.5f, base, base * 3f)
        assertEquals(VolumeMixer.SLOW_FOOTSTEP_RATE, slow, 0.0001f)
        assertEquals(VolumeMixer.NORMAL_FOOTSTEP_RATE, normal, 0.0001f)
        assertEquals(VolumeMixer.FAST_FOOTSTEP_RATE, fast, 0.0001f)
        assertTrue(slow < normal)
        assertTrue(normal < fast)
        // Between two levels the rate always moves in the right direction.
        val between = VolumeMixer.footstepRate(base * 2f, base * 0.5f, base, base * 3f)
        assertTrue(between > normal)
        assertTrue(between < fast)
        for (speed in listOf(0.1f, 0.5f, 1f, 2.6f, 4f, 7.8f, 20f)) {
            val rate = VolumeMixer.footstepRate(speed, base * 0.5f, base, base * 3f)
            assertTrue("rate $rate out of window at $speed", rate in VolumeMixer.MIN_RATE..VolumeMixer.MAX_RATE)
        }
    }

    @Test
    fun theCatalogueCoversEverySoundAndTheKeysAreUniquePerFile() {
        assertTrue(SoundLibrary.SOUND_FILES.size >= 60)
        for ((key, file) in SoundLibrary.SOUND_FILES) {
            assertTrue("$key must point at an ogg file", file.endsWith(".ogg"))
            assertEquals(file, SoundLibrary.fileOf(key))
        }
        // Both fighters walk on the same recording, from different offsets.
        assertEquals(SoundLibrary.fileOf("playerWalk"), SoundLibrary.fileOf("enemyWalk"))
        assertNotEquals(
            SoundLibrary.WALK_START_OFFSET_MS.getValue("playerWalk"),
            SoundLibrary.WALK_START_OFFSET_MS.getValue("enemyWalk")
        )
        // Every distinct file is uploaded once, no matter how many keys use it.
        assertEquals(SoundLibrary.SOUND_FILES.values.distinct(), SoundLibrary.files())
        assertNull(SoundLibrary.fileOf("thereIsNoSuchSound"))
    }

    @Test
    fun theLoopAndProjectileKeysAreTheOnesTheGameUsesAsSuch() {
        assertTrue(SoundLibrary.isLoopKey("machineGun"))
        assertTrue(SoundLibrary.isLoopKey("playerWalk"))
        assertTrue(SoundLibrary.isLoopKey("missile"))
        assertTrue(SoundLibrary.isLoopKey("carMoving"))
        assertFalse(SoundLibrary.isLoopKey("click"))
        assertTrue(SoundLibrary.isProjectile("missile"))
        assertTrue(SoundLibrary.isProjectile("atomBomb"))
        assertFalse(SoundLibrary.isProjectile("gunHit"))
        // The three automatic weapons and their single-shot copies.
        for (loop in SoundLibrary.AUTO_LOOP_KEYS) {
            assertTrue(loop in SoundLibrary.HOLD_LOOP_KEYS)
            assertTrue(loop in SoundLibrary.WEAPON_SOUND_KEYS)
            val shot = SoundLibrary.AUTO_LOOP_OF.entries.first { it.value == loop }.key
            assertFalse(shot in SoundLibrary.AUTO_LOOP_KEYS)
            assertTrue(shot in SoundLibrary.AUTO_SHOT_KEYS)
        }
        // A genuinely stereo recording keeps its image; a mono one is centred.
        assertTrue(SoundLibrary.isStereoRecording("music"))
        assertFalse(SoundLibrary.isStereoRecording("click"))
    }

    @Test
    fun aSoundKeyHashesToTheSameIdOnBothSidesOfJni() {
        // The native engine compares sound keys as 32-bit FNV-1a hashes; the
        // value is pinned here so a change on either side is caught.
        assertEquals(-2_060_735_908, OpenFireAudioEngine.keyIdOf("machineGun"))
        assertEquals(OpenFireAudioEngine.keyIdOf("enemyWalk"), OpenFireAudioEngine.keyIdOf("enemyWalk"))
        assertNotEquals(OpenFireAudioEngine.keyIdOf("enemyWalk"), OpenFireAudioEngine.keyIdOf("playerWalk"))
    }

    @Test
    fun aSequenceLengthIsKnownForEverySoundThatDrivesAGameTimer() {
        for (key in listOf("missile", "explosion", "atomBomb", "grenade", "timerLoop", "carStart", "carBrake")) {
            assertTrue("$key must have a known length", SoundLibrary.durationMsOf(key) > 0L)
        }
        assertEquals(0L, SoundLibrary.durationMsOf("thereIsNoSuchSound"))
    }
}
