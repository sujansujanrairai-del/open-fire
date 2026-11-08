package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.audio.AutomaticAudioBackend
import com.blindtechnexus.openfire.audio.AutomaticWeaponMixer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticWeaponMixerTest {
    private data class Played(val sampleId: Int, val streamId: Int, val left: Float, val right: Float)

    private class FakeBackend : AutomaticAudioBackend {
        val played = mutableListOf<Played>()
        val stopped = mutableListOf<Int>()
        val volumes = mutableListOf<Triple<Int, Float, Float>>()
        var failuresBeforeSuccess = 0
        private var nextStream = 100

        override fun play(sampleId: Int, left: Float, right: Float): Int {
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--
                return 0
            }
            val stream = nextStream++
            played += Played(sampleId, stream, left, right)
            return stream
        }

        override fun stop(streamId: Int) {
            stopped += streamId
        }

        override fun setVolume(streamId: Int, left: Float, right: Float) {
            volumes += Triple(streamId, left, right)
        }
    }

    private fun mixer(
        scope: kotlinx.coroutines.CoroutineScope,
        backend: FakeBackend,
        samples: MutableMap<String, Int>,
        requested: MutableList<String> = mutableListOf()
    ) = AutomaticWeaponMixer(
        backend = backend,
        scope = scope,
        sampleId = { samples[it] },
        requestSample = { requested += it },
        minimumBurstMs = 260L,
        takeoverGraceMs = 60L
    )

    @Test
    fun repeatedStartsUseOneGaplessStreamAndStopImmediately() = runTest {
        val backend = FakeBackend()
        val mixer = mixer(this, backend, mutableMapOf("ak47" to 7))

        repeat(1_000) { mixer.start("ak47", "player", 0.4f, 0.6f) }
        assertEquals(1, backend.played.size)
        assertTrue(mixer.isActive("ak47", "player"))

        mixer.stop("ak47", "player")
        assertEquals(listOf(100), backend.stopped)
        assertFalse(mixer.isActive("ak47", "player"))
        assertFalse(mixer.isAnyActive())

        // A load callback or resume after release cannot resurrect the stream.
        mixer.onSoundReady("ak47")
        mixer.resume()
        assertEquals(1, backend.played.size)
    }

    @Test
    fun lateLoadOnlyStartsChannelsWhoseTriggersAreStillHeld() = runTest {
        val backend = FakeBackend()
        val samples = mutableMapOf<String, Int>()
        val requested = mutableListOf<String>()
        val mixer = mixer(this, backend, samples, requested)

        mixer.start("machineGun", "enemy", 0.8f, 0.8f)
        mixer.start("ak47", "player", 1f, 1f)
        mixer.stop("ak47", "player")
        assertEquals(listOf("machineGun", "ak47"), requested)
        assertTrue(backend.played.isEmpty())

        samples["machineGun"] = 9
        samples["ak47"] = 8
        mixer.onSoundReady("machineGun")
        mixer.onSoundReady("ak47")
        assertEquals(listOf(9), backend.played.map { it.sampleId })
        assertFalse(mixer.isActive("ak47", "player"))
        assertTrue(mixer.isActive("machineGun", "enemy"))
    }

    @Test
    fun fightersAndWeaponsHaveIndependentStreams() = runTest {
        val backend = FakeBackend()
        val mixer = mixer(
            this, backend,
            mutableMapOf("ak47" to 7, "submachineGun" to 6, "machineGun" to 9)
        )
        mixer.start("ak47", "player", 1f, 1f)
        mixer.start("ak47", "enemy", 0.5f, 0.7f)
        mixer.start("machineGun", "enemy", 0.4f, 0.4f)
        mixer.start("submachineGun", "player", 1f, 1f)
        assertEquals(4, backend.played.size)

        mixer.stopOwner("player")
        assertEquals(setOf(100, 103), backend.stopped.toSet())
        assertTrue(mixer.isActive("ak47", "enemy"))
        assertTrue(mixer.isActive("machineGun", "enemy"))
        assertFalse(mixer.isActive("submachineGun", "player"))

        mixer.stop("ak47", "enemy")
        assertTrue(mixer.isActive("machineGun", "enemy"))
        mixer.stopAll()
        assertFalse(mixer.isAnyActive())
        assertEquals(4, backend.stopped.size)
    }

    @Test
    fun boundedBurstsReplaceTheirTimeoutInsteadOfAccumulatingJobs() = runTest {
        val backend = FakeBackend()
        val mixer = mixer(this, backend, mutableMapOf("submachineGun" to 6))

        mixer.playBurst("submachineGun", "player", 500L, 1f, 1f)
        advanceTimeBy(300L)
        mixer.playBurst("submachineGun", "player", 500L, 1f, 1f)
        advanceTimeBy(300L)
        runCurrent()
        // The first 560 ms deadline has passed, but the replacement owns the stream.
        assertTrue(backend.stopped.isEmpty())
        assertEquals(1, backend.played.size)

        advanceTimeBy(259L)
        runCurrent()
        assertTrue(backend.stopped.isEmpty())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(100), backend.stopped)
        assertFalse(mixer.isAnyActive())
    }

    @Test
    fun realHoldTakesOverABoundedBurstWithoutATailOrRestart() = runTest {
        val backend = FakeBackend()
        val mixer = mixer(this, backend, mutableMapOf("ak47" to 7))

        mixer.playBurst("ak47", "player", 500L, 1f, 1f)
        advanceTimeBy(100L)
        mixer.start("ak47", "player", 0.5f, 0.5f)
        advanceTimeBy(10_000L)
        runCurrent()
        assertTrue(backend.stopped.isEmpty())
        assertEquals(1, backend.played.size)

        // Bounded legacy requests cannot shorten that real hold either.
        mixer.playBurst("ak47", "player", 500L, 1f, 1f)
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(backend.stopped.isEmpty())
        mixer.stop("ak47", "player")
        assertEquals(listOf(100), backend.stopped)
    }

    @Test
    fun pauseWaitsForResumeAndReleaseBeforeResumeStaysSilent() = runTest {
        val backend = FakeBackend()
        val mixer = mixer(this, backend, mutableMapOf("machineGun" to 9, "ak47" to 7))
        mixer.pause()
        mixer.start("machineGun", "enemy", 1f, 1f)
        mixer.start("ak47", "player", 1f, 1f)
        mixer.stop("ak47", "player")
        assertTrue(backend.played.isEmpty())

        mixer.resume()
        assertEquals(listOf(9), backend.played.map { it.sampleId })
        mixer.stopAll()
        assertEquals(listOf(100), backend.stopped)
    }

    @Test
    fun busyBackendRetriesWithoutDuplicatingOrBlockingTheCaller() = runTest {
        val backend = FakeBackend().apply { failuresBeforeSuccess = 2 }
        val mixer = mixer(this, backend, mutableMapOf("ak47" to 7))
        mixer.start("ak47", "player", 1f, 1f)
        assertTrue(backend.played.isEmpty())

        advanceTimeBy(40L)
        runCurrent()
        assertTrue(backend.played.isEmpty())
        advanceTimeBy(40L)
        runCurrent()
        assertEquals(1, backend.played.size)
        mixer.stopAll()
        assertEquals(listOf(100), backend.stopped)
    }

    @Test
    fun rapidHoldReleaseCyclesLeaveAtMostOneLiveChannel() = runTest {
        val backend = FakeBackend()
        val samples = mutableMapOf<String, Int>()
        val mixer = mixer(this, backend, samples)

        repeat(500) {
            mixer.start("ak47", "player", 1f, 1f)
            mixer.stop("ak47", "player")
        }
        mixer.start("ak47", "player", 1f, 1f)
        assertEquals(1, mixer.channels.size)
        samples["ak47"] = 7
        mixer.onSoundReady("ak47")
        assertEquals(1, backend.played.size)
        mixer.release()
        assertTrue(mixer.channels.isEmpty())
        assertEquals(listOf(100), backend.stopped)
    }
}
