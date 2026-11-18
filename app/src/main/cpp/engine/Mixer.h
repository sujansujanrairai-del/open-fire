/*
 * The voice pool and the mix bus.
 *
 * The mixer owns every voice, renders them into one stereo block and applies
 * the master and bus gains. It has no Android dependency at all: the Oboe
 * callback in AudioEngine.cpp only calls `render`, so the whole mix can be
 * driven by a host test at any block size.
 *
 * The pool never grows while the audio thread is running: voices are fixed
 * objects in a pre-allocated array, reused by generation, so the real-time
 * path contains no allocation and no lock. A full pool evicts the quietest
 * non-looping voice instead of refusing the new sound, and a held loop is
 * never evicted - a firing weapon can never be silenced by a footstep.
 */
#ifndef OPENFIRE_MIXER_H
#define OPENFIRE_MIXER_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "AudioMath.h"
#include "ClipStore.h"
#include "Voice.h"

namespace openfire {

/** How many voices may sound at the same time. */
constexpr int32_t kMaxVoices = 128;

class Mixer {
public:
    Mixer() : voices_(kMaxVoices) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            voices_[static_cast<size_t>(i)].id = i + 1;
        }
    }

    void setClipStore(ClipStore* store) { clips_ = store; }

    /** True while a voice of this key is still in the pool. */
    bool isKeySounding(int32_t keyId) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            const Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip != nullptr && candidate.keyId == keyId &&
                !candidate.stopping && candidate.state != VOICE_FINISHED) {
                return true;
            }
        }
        return false;
    }

    /** Number of voices currently sounding. */
    int32_t activeVoices() const { return active_; }

    int32_t peakVoices() const { return peak_; }

    /**
     * Finds a free slot. Returns nullptr when every slot is in use; the
     * caller then tries to evict the quietest one-shot.
     */
    Voice* allocate(int32_t id, int32_t keyId, int32_t owner, int32_t flags,
                    const std::shared_ptr<const Clip>& clip) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.state == VOICE_PENDING || candidate.clip == nullptr) {
                prepare(candidate, id, keyId, owner, flags, clip);
                return &candidate;
            }
        }
        return nullptr;
    }

    /** Evicts the quietest non-looping voice. Returns true when one was freed. */
    bool evictQuietest() {
        Voice* victim = nullptr;
        float quietest = 1e9f;
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.state == VOICE_PENDING || candidate.loop() || candidate.stopping) continue;
            const float level = candidate.currentVolume +
                openfire::distanceGain(candidate.currentDistance,
                                       static_cast<float>(candidate.maxDistance));
            if (level < quietest) {
                quietest = level;
                victim = &candidate;
            }
        }
        if (victim == nullptr) return false;
        victim->stopping = true;
        victim->stopFade = 1.0f;
        return true;
    }

    Voice* find(int32_t id) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.id == id && candidate.clip != nullptr &&
                candidate.state != VOICE_FINISHED && !candidate.stopping) {
                return &candidate;
            }
        }
        return nullptr;
    }

    void stopId(int32_t id) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.id == id && candidate.clip != nullptr && !candidate.stopping) {
                candidate.stopping = true;
            }
        }
    }

    void stopKey(int32_t keyId, int32_t owner /* -1 = any */) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip == nullptr || candidate.stopping) continue;
            if (candidate.keyId != keyId) continue;
            if (owner >= 0 && candidate.owner != owner) continue;
            candidate.stopping = true;
        }
    }

    void stopOwner(int32_t owner) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip != nullptr && !candidate.stopping && candidate.owner == owner) {
                candidate.stopping = true;
            }
        }
    }

    void stopAll() {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip != nullptr) candidate.stopping = true;
        }
    }

    /** Drops every voice immediately (a released engine, a closed stream). */
    void clear() {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            candidate.clip = nullptr;
            candidate.state = VOICE_PENDING;
            candidate.stopping = false;
        }
        active_ = 0;
    }

    /**
     * Renders every voice into `output` (interleaved stereo floats, already
     * zeroed by the caller) and applies the bus and master gains.
     */
    void render(float* output, int32_t frames, int32_t sampleRate) {
        const float rampStep = 1.0f -
            std::exp(-1.0f / (openfire::kGainRampMs * 0.001f * static_cast<float>(sampleRate)));
        const float stopFadeStep = 1.0f /
            (openfire::kStopFadeMs * 0.001f * static_cast<float>(sampleRate) + 1.0f);

        int32_t live = 0;
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& voice = voices_[static_cast<size_t>(i)];
            if (voice.clip == nullptr) continue;
            if (voice.state == VOICE_FINISHED) {
                voice.clip = nullptr;
                voice.stopping = false;
                continue;
            }
            if (!renderVoice(&voice, output, frames, sampleRate, rampStep, stopFadeStep)) {
                voice.clip = nullptr;
                voice.stopping = false;
                continue;
            }
            ++live;
        }
        active_ = live;
        if (live > peak_) peak_ = live;

        const float master = master_;
        const float sfx = busGain_[0] * master;
        for (int32_t frame = 0; frame < frames; ++frame) {
            output[frame * 2] *= sfx;
            output[frame * 2 + 1] *= sfx;
        }
    }

    /** Applies the gains and the soft limiter, then writes 16-bit PCM. */
    static void toPcm16(const float* input, int32_t frames, int16_t* output) {
        for (int32_t i = 0; i < frames * 2; ++i) {
            output[i] = openfire::toPcm16(input[i]);
        }
    }

    void setBusGain(int32_t bus, float gain) {
        if (bus < 0 || bus >= kBusCount) return;
        busGain_[bus] = openfire::clamp01(gain);
    }

    void setMasterGain(float gain) { master_ = openfire::clamp01(gain); }

    float busGain(int32_t bus) const {
        if (bus < 0 || bus >= kBusCount) return 1.0f;
        return busGain_[bus];
    }

    static constexpr int32_t kBusCount = 3;

private:
    void prepare(Voice& voice, int32_t id, int32_t keyId, int32_t owner, int32_t flags,
                 const std::shared_ptr<const Clip>& clip) {
        voice.id = id;
        voice.keyId = keyId;
        voice.owner = owner;
        voice.flags = flags;
        voice.clip = clip;
        voice.position = 0.0;
        voice.playedFrames = 0;
        voice.deadlineFrames = INT64_MAX;
        voice.currentVolume = 0.0f;
        voice.currentPan = 0.0f;
        voice.currentDistance = 0.0f;
        voice.currentRear = 0.0f;
        voice.currentRate = 1.0f;
        voice.stopFade = 1.0f;
        voice.state = VOICE_PENDING;
        voice.farEarState = 0.0f;
        voice.currentDelay = 0.0f;
        voice.delayCursor = 0;
        voice.stopping = false;
    }

    std::vector<Voice> voices_;
    ClipStore* clips_ = nullptr;
    int32_t active_ = 0;
    int32_t peak_ = 0;
    float master_ = 1.0f;
    float busGain_[kBusCount] = {1.0f, 1.0f, 1.0f};
};

}  // namespace openfire

#endif  // OPENFIRE_MIXER_H
