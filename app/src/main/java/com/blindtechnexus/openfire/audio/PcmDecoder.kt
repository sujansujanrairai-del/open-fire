package com.blindtechnexus.openfire.audio

import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes an encoded asset (the game ships OGG Vorbis) into mono 16-bit PCM
 * at [MixEngine.SAMPLE_RATE].
 *
 * Every sound is decoded once, off the UI thread, and then lives in memory as
 * plain samples. The mixer reads those samples directly, so playing a sound
 * never touches a platform player, a decoder or a stream budget.
 */
object PcmDecoder {

    private const val TIMEOUT_US = 10_000L

    /** Returns the decoded mono samples, or null when the asset cannot be decoded. */
    fun decode(afd: AssetFileDescriptor): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val builder = PcmBuilder()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val changed = codec.outputFormat
                        sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            changed.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            appendFrames(builder, buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, floatPcm)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                }
            }
            return resample(builder.toArray(), sampleRate, MixEngine.SAMPLE_RATE)
        } catch (_: Exception) {
            return null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    /** Converts interleaved decoder frames into mono 16-bit samples (channels are averaged). */
    private fun appendFrames(
        builder: PcmBuilder,
        buffer: ByteBuffer,
        channels: Int,
        floatPcm: Boolean
    ) {
        val channelCount = channels.coerceAtLeast(1)
        val bytesPerSample = if (floatPcm) 4 else 2
        val frameBytes = channelCount * bytesPerSample
        while (buffer.remaining() >= frameBytes) {
            val left = readSample(buffer, floatPcm)
            var right = left
            for (channel in 1 until channelCount) {
                val value = readSample(buffer, floatPcm)
                if (channel == 1) right = value
            }
            builder.add(((left + right) / 2).toShort())
        }
    }

    private fun readSample(buffer: ByteBuffer, floatPcm: Boolean): Int =
        if (floatPcm) {
            (buffer.float * 32767f).toInt().coerceIn(-32768, 32767)
        } else {
            buffer.short.toInt()
        }

    /** Linear resampling of mono samples. */
    internal fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || fromRate <= 0 || input.isEmpty()) return input
        val outFrames = (input.size.toLong() * toRate / fromRate).toInt()
        val output = ShortArray(outFrames)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (frame in 0 until outFrames) {
            val position = frame * ratio
            val index = position.toInt().coerceAtMost(input.size - 1)
            val next = (index + 1).coerceAtMost(input.size - 1)
            val fraction = position - index
            val a = input[index].toDouble()
            val b = input[next].toDouble()
            output[frame] = (a + (b - a) * fraction).toInt().toShort()
        }
        return output
    }

    /** Growable buffer of decoded samples. */
    private class PcmBuilder {
        private var data = ShortArray(1 shl 16)
        private var size = 0

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}
