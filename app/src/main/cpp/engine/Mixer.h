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

    /** Lock-free query: never inspect callback-owned voice state from callers. */
    bool isKeySounding(int32_t keyId) const {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            const Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.publishedActive.load(std::memory_order_acquire) &&
                candidate.publishedKeyId.load(std::memory_order_relaxed) == keyId) {
                return true;
            }
        }
        return false;
    }

    /** Number of voices currently sounding. */
    int32_t activeVoices() const { return active_.load(std::memory_order_relaxed); }

    int32_t peakVoices() const { return peak_.load(std::memory_order_relaxed); }

    /**
     * Finds a free slot. Returns nullptr when every slot is in use; the
     * caller then tries to evict the quietest one-shot.
     */
    Voice* allocate(int32_t id, int32_t keyId, int32_t owner, int32_t flags,
                    const Clip* clip) {
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
        victim->publishedActive.store(false, std::memory_order_release);
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
                candidate.publishedActive.store(false, std::memory_order_release);
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
            candidate.publishedActive.store(false, std::memory_order_release);
        }
    }

    void stopOwner(int32_t owner) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip != nullptr && !candidate.stopping && candidate.owner == owner) {
                candidate.stopping = true;
                candidate.publishedActive.store(false, std::memory_order_release);
            }
        }
    }

    void stopAll() {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip != nullptr) {
                candidate.stopping = true;
                candidate.publishedActive.store(false, std::memory_order_release);
            }
        }
    }

    /** Drops every voice immediately (a released engine, a closed stream). */
    void clear() {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            candidate.publishedActive.store(false, std::memory_order_release);
            candidate.publishedKeyId.store(0, std::memory_order_relaxed);
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
                voice.publishedActive.store(false, std::memory_order_release);
                voice.publishedKeyId.store(0, std::memory_order_relaxed);
                voice.clip = nullptr;
                voice.stopping = false;
                continue;
            }
            if (!renderVoice(&voice, output, frames, sampleRate, rampStep, stopFadeStep)) {
                voice.publishedActive.store(false, std::memory_order_release);
                voice.publishedKeyId.store(0, std::memory_order_relaxed);
                voice.clip = nullptr;
                voice.stopping = false;
                continue;
            }
            ++live;
        }
        active_.store(live, std::memory_order_relaxed);
        int32_t peak = peak_.load(std::memory_order_relaxed);
        while (live > peak && !peak_.compare_exchange_weak(
                   peak, live, std::memory_order_relaxed)) {}

        const float master = master_.load(std::memory_order_relaxed);
        const float sfx = busGain_[0].load(std::memory_order_relaxed) * master;
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
        busGain_[bus].store(openfire::clamp01(gain), std::memory_order_relaxed);
    }

    void setMasterGain(float gain) {
        master_.store(openfire::clamp01(gain), std::memory_order_relaxed);
    }

    float busGain(int32_t bus) const {
        if (bus < 0 || bus >= kBusCount) return 1.0f;
        return busGain_[bus].load(std::memory_order_relaxed);
    }

    static constexpr int32_t kBusCount = 3;

private:
    void prepare(Voice& voice, int32_t id, int32_t keyId, int32_t owner, int32_t flags,
                 const Clip* clip) {
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
        voice.publishedKeyId.store(keyId, std::memory_order_relaxed);
        voice.publishedActive.store(true, std::memory_order_release);
    }

    std::vector<Voice> voices_;
    ClipStore* clips_ = nullptr;
    std::atomic<int32_t> active_{0};
    std::atomic<int32_t> peak_{0};
    std::atomic<float> master_{1.0f};
    std::atomic<float> busGain_[kBusCount] = {1.0f, 1.0f, 1.0f};
};

}  // namespace openfire

#endif  // OPENFIRE_MIXER_H
