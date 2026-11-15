package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.audio.PcmDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmDecoderResampleTest {

    @Test
    fun sameRateIsReturnedUnchanged() {
        val input = shortArrayOf(1, 2, 3, 4)
        assertEquals(input.toList(), PcmDecoder.resample(input, 44_100, 44_100).toList())
    }

    @Test
    fun upsamplingDoublesTheFrameCountAndInterpolates() {
        val output = PcmDecoder.resample(shortArrayOf(0, 100), 22_050, 44_100)
        assertEquals(4, output.size)
        assertEquals(0, output[0].toInt())
        assertEquals(50, output[1].toInt())
        assertEquals(100, output[2].toInt())
    }

    @Test
    fun downsamplingKeepsTheDurationOfTheClip() {
        val input = ShortArray(48_000) { 7 }
        val output = PcmDecoder.resample(input, 48_000, 44_100)
        assertEquals(44_100, output.size)
    }
}
