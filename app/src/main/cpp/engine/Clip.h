/*
 * An immutable, decoded sound: interleaved 16-bit samples.
 *
 * Clips are owned by the ClipStore and never touched by the audio thread for
 * anything but reads. They remain alive until the stream stops and the engine
 * clears the store, so voices and queued commands can use raw immutable
 * pointers without refcounting or locks on the callback. Every clip is
 * normalised to the engine's output sample rate when loaded, so the mixer
 * never resamples on the fly and playback rate is purely a pitch/pace control.
 */
#ifndef OPENFIRE_CLIP_H
#define OPENFIRE_CLIP_H

#include <cstdint>
#include <utility>
#include <vector>

namespace openfire {

class Clip {
public:
    Clip(int32_t frames, int32_t channels, std::vector<int16_t> samples)
        : frames_(frames), channels_(channels), samples_(std::move(samples)) {}

    int32_t frames() const { return frames_; }
    int32_t channels() const { return channels_; }
    bool stereo() const { return channels_ > 1; }
    const int16_t* data() const { return samples_.data(); }

    /** One sample of one channel, wrapped for loops, clamped at the ends. */
    inline float sampleAt(int32_t frame, int32_t channel, bool loop) const {
        if (frames_ <= 0) return 0.0f;
        int32_t position = frame;
        if (loop) {
            position %= frames_;
            if (position < 0) position += frames_;
        } else if (position < 0) {
            position = 0;
        } else if (position >= frames_) {
            position = frames_ - 1;
        }
        const int32_t index = position * channels_ + (channel < channels_ ? channel : channels_ - 1);
        return static_cast<float>(samples_[static_cast<size_t>(index)]);
    }

    int64_t durationMs(int32_t sampleRate) const {
        if (sampleRate <= 0) return 0;
        return static_cast<int64_t>(frames_) * 1000 / sampleRate;
    }

private:
    int32_t frames_;
    int32_t channels_;
    std::vector<int16_t> samples_;
};

}  // namespace openfire

#endif  // OPENFIRE_CLIP_H
