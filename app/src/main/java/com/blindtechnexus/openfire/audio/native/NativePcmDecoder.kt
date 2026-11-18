package com.blindtechnexus.openfire.audio.native

import android.content.res.AssetFileDescriptor
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One decoded sound in the shape the native engine wants it: interleaved
 * 16-bit samples at the recording's own sample rate and channel count.
 *
 * The engine resamples to its output rate once, on load, and keeps its own
 * copy of the samples. Keeping the original rate here means the C++ side is
 * the single owner of the "everything is at 48 kHz" invariant.
 */
class DecodedClip(
    val samples: ShortArray,
    val frames: Int,
    val channels: Int,
    val sampleRate: Int
)

/**
 * Decodes an OGG Vorbis asset with the platform decoder.
 *
 * Only the decode touches Android; the engine never decodes anything at play
 * time. Both mono and stereo recordings are kept in their own channel count,
 * so a genuine stereo recording (the car engine, the music) keeps its image.
 *
 * The decoder is driven to completion here rather than on a timer, and the
 * frame count that is reported back always matches the samples that were
 * actually copied: the native loader trusts `frames * channels` and would
 * otherwise read past the array.
 */
object NativePcmDecoder {

    private const val TIMEOUT_US = 10_000L

    /**
     * How many consecutive 10 ms stalls are tolerated before the decode is
     * abandoned. A healthy decoder never stalls for long; a vendor codec that
     * does must not pin a background thread of the game forever.
     */
    private const val MAX_STALLS = 500

    /** Decodes the asset behind [afd], or null when it is not an audio file. */
    fun decode(afd: AssetFileDescriptor): DecodedClip? {
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

            val builder = SampleBuilder()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var inputDone = false
            var outputDone = false
            var stalls = 0
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                var progressed = false
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        progressed = true
                        val buffer = codec.getInputBuffer(inputIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val changed = codec.outputFormat
                        sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            changed.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                            AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outputIndex >= 0 -> {
                        progressed = true
                        val buffer = codec.getOutputBuffer(outputIndex)
                        if (buffer != null && bufferInfo.size > 0) {
                            val start = bufferInfo.offset
                            val end = start + bufferInfo.size
                            if (start >= 0 && end <= buffer.capacity()) {
                                buffer.position(start)
                                buffer.limit(end)
                                appendFrames(
                                    builder,
                                    buffer.slice().order(ByteOrder.LITTLE_ENDIAN),
                                    channels,
                                    floatPcm
                                )
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
                if (progressed) {
                    stalls = 0
                } else if (++stalls > MAX_STALLS) {
                    return null
                }
            }

            val samples = builder.toArray()
            // One channel count is used for the whole clip: a recording with
            // more than two channels is folded to stereo by `appendFrames`,
            // and the frame count has to describe exactly that layout.
            val outputChannels = channels.coerceIn(1, 2)
            if (samples.isEmpty() || sampleRate <= 0 || outputChannels <= 0) return null
            if (samples.size % outputChannels != 0) return null
            return DecodedClip(
                samples = samples,
                frames = samples.size / outputChannels,
                channels = outputChannels,
                sampleRate = sampleRate
            )
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

    /** Copies interleaved decoder frames into mono or stereo 16-bit samples. */
    private fun appendFrames(
        builder: SampleBuilder,
        buffer: ByteBuffer,
        channels: Int,
        floatPcm: Boolean
    ) {
        val channelCount = channels.coerceIn(1, 2)
        val bytesPerSample = if (floatPcm) 4 else 2
        val frameBytes = channelCount * bytesPerSample
        while (buffer.remaining() >= frameBytes) {
            builder.add(readSample(buffer, floatPcm).toShort())
            if (channelCount > 1) builder.add(readSample(buffer, floatPcm).toShort())
        }
    }

    private fun readSample(buffer: ByteBuffer, floatPcm: Boolean): Int =
        if (floatPcm) {
            (buffer.float * 32767f).toInt().coerceIn(-32768, 32767)
        } else {
            buffer.short.toInt()
        }

    /** Growable buffer of decoded samples. */
    private class SampleBuilder {
        private var data = ShortArray(1 shl 16)
        private var size = 0

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }
}
