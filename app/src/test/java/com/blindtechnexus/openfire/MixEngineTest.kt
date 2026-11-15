package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.audio.MixEngine
import com.blindtechnexus.openfire.audio.PcmClip
import com.blindtechnexus.openfire.audio.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests of the software mixer: no Android classes are involved. */
class MixEngineTest {

    private val block = MixEngine.BLOCK_FRAMES

    private fun constantClip(frames: Int, value: Short = 1000): PcmClip =
        PcmClip(ShortArray(frames) { value })

    private fun voice(
        engine: MixEngine,
        key: String,
        clip: PcmClip,
        loop: Boolean = false,
        left: Float = 1f,
        right: Float = 1f,
        deadlineFrames: Long = Long.MAX_VALUE,
        startFrame: Double = 0.0
    ): Voice {
        val created = Voice(
            id = engine.newId(),
            key = key,
            owner = "",
            clip = clip,
            loop = loop,
            startFrame = startFrame,
            deadlineFrames = deadlineFrames,
            enqueuedAtMs = 0L
        )
        created.targetLeft = left
        created.targetRight = right
        created.currentLeft = left
        created.currentRight = right
        engine.add(created)
        return created
    }

    private fun render(engine: MixEngine, frames: Int = block): FloatArray {
        val out = FloatArray(frames * 2)
        engine.renderBlock(out, frames, nowMs = 0L)
        return out
    }

    @Test
    fun hundredsOfSimultaneousVoicesAreAllMixedAndSummed() {
        val engine = MixEngine()
        val clip = constantClip(block * 4, value = 100)
        repeat(300) { voice(engine, "click", clip, left = 1f, right = 1f) }
        val out = render(engine)
        assertEquals(300, engine.activeVoiceCount())
        // Each voice contributes 100 / 32768 at full gain once the gain has settled.
        val single = 100f / 32768f
        assertTrue("summed level ${out[block]} should be near ${300 * single}", out[block] > 300 * single * 0.5f)
    }

    @Test
    fun aNewVoiceNeverCutsAnOlderOneWhileThereIsRoom() {
        val engine = MixEngine()
        val clip = constantClip(block * 10)
        val first = voice(engine, "gunHit", clip)
        render(engine)
        voice(engine, "gunHit", clip)
        render(engine)
        assertTrue(engine.live.containsKey(first.id))
        assertEquals(2, engine.activeVoiceCount())
    }

    @Test
    fun aStoppedVoiceFadesOutAndIsRemoved() {
        val engine = MixEngine()
        val held = voice(engine, "walk", constantClip(block * 100), loop = true)
        render(engine)
        engine.stop(held.id)
        assertFalse(engine.live.containsKey(held.id))
        // Enough blocks to finish the fade-out.
        repeat(3) { render(engine) }
        assertEquals(0, engine.activeVoiceCount())
    }

    @Test
    fun aFiniteSoundEndsAtTheEndOfItsClip() {
        val engine = MixEngine()
        val one = voice(engine, "click", constantClip(block))
        render(engine)
        render(engine)
        assertFalse(engine.live.containsKey(one.id))
        assertEquals(0, engine.activeVoiceCount())
    }

    @Test
    fun aLoopKeepsPlayingUntilItIsStopped() {
        val engine = MixEngine()
        val loop = voice(engine, "machineGun", constantClip(block * 2), loop = true)
        repeat(20) { render(engine) }
        assertTrue(engine.live.containsKey(loop.id))
        assertEquals(1, engine.activeVoiceCount())
    }

    @Test
    fun aBoundedShotFadesOutAtItsDeadline() {
        val engine = MixEngine()
        val shot = voice(engine, "ak47Shot", constantClip(block * 100), deadlineFrames = (block * 2).toLong())
        repeat(6) { render(engine) }
        assertFalse(engine.live.containsKey(shot.id))
    }

    @Test
    fun pausingFreezesEveryVoiceAndResumingContinuesIt() {
        val engine = MixEngine()
        val clip = constantClip(block * 10)
        val sound = voice(engine, "click", clip)
        render(engine)
        val afterOneBlock = sound.playedFrames
        engine.paused = true
        val silent = render(engine)
        assertEquals(afterOneBlock, sound.playedFrames)
        assertTrue(silent.all { it == 0f })
        engine.paused = false
        render(engine)
        assertEquals(afterOneBlock + block, sound.playedFrames)
    }

    @Test
    fun requestsMadeWhilePausedWaitAndStartOnResume() {
        val engine = MixEngine()
        engine.paused = true
        voice(engine, "click", constantClip(block * 4))
        render(engine)
        assertEquals(0, engine.activeVoiceCount())
        engine.paused = false
        render(engine)
        assertEquals(1, engine.activeVoiceCount())
    }

    @Test
    fun aFullMixerEvictsAQuietOneShotButNeverALoop() {
        val engine = MixEngine(maxVoices = 4)
        val loop = voice(engine, "walk", constantClip(block * 50), loop = true, left = 0.9f, right = 0.9f)
        val quiet = voice(engine, "click", constantClip(block * 50), left = 0.01f, right = 0.01f)
        voice(engine, "click", constantClip(block * 50), left = 0.5f, right = 0.5f)
        voice(engine, "click", constantClip(block * 50), left = 0.5f, right = 0.5f)
        render(engine)
        voice(engine, "gunHit", constantClip(block * 50), left = 0.9f, right = 0.9f)
        render(engine)
        assertTrue("loops are never evicted", engine.live.containsKey(loop.id))
        assertEquals(4, engine.activeVoiceCount())
        repeat(3) { render(engine) }
        assertFalse("the quietest one-shot is the one that gives way", engine.live.containsKey(quiet.id))
    }

    @Test
    fun loudMixesAreSoftLimitedBelowFullScale() {
        val engine = MixEngine()
        for (level in listOf(0.5f, 1f, 2f, 10f, 1000f)) {
            val limited = engine.softLimit(level)
            assertTrue("$level must stay within full scale, was $limited", limited <= 1f)
        }
        assertEquals(0.5f, engine.softLimit(0.5f), 0.0001f)
    }

    @Test
    fun toPcmClipsIntoTheSixteenBitRange() {
        val engine = MixEngine()
        val input = floatArrayOf(5f, -5f, 0.25f, -0.25f)
        val output = ShortArray(4)
        engine.toPcm16(input, 2, output)
        assertTrue(output[0].toInt() in 1..Short.MAX_VALUE.toInt())
        assertTrue(output[1].toInt() in Short.MIN_VALUE.toInt() + 1..-1)
        // 0.25 of full scale is 8192 (within rounding).
        assertEquals(8192.0, output[2].toDouble(), 2.0)
    }
}
