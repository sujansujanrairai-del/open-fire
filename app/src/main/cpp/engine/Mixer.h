/*
 * The voice pool and the mix bus.
 *
 * The mixer owns every voice, renders them into one stereo block and applies
 * the master and bus gains. It has no Android dependency at all: the Oboe
 * callback in AudioEngine.cpp only calls `render`, so the whole mix can be
 * driven by a host test at any block size.
 *
 * Two output modes exist, and the difference is real:
 *
 *  - **Headphones**: the full binaural placement of Voice.h (interaural delay
 *    and head shadow) on top of pan and distance.
 *  - **Speakers**: a gentler pan plus a crossfeed stage, so both ears hear
 *    both speakers the way a room does. Without it, a hard-panned enemy is
 *    heard only by one ear and the image collapses onto the nearest speaker;
 *    with it, the whole arena is audible on a phone speaker and the enemy's
 *    position is still unmistakable.
 *
 * The pool never grows while the audio thread is running: voices are fixed
 * objects in a pre-allocated array, reused by generation, so the real-time
 * path contains no allocation and no lock. A full pool *steals* the quietest
 * non-critical voice instead of refusing the new sound: a whole battle of
 * explosions can never silence the gun the player just fired.
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

/** Longest block the mixer ever renders through its scratch buffer. */
constexpr int32_t kMaxBlockFrames = 2048;

class Mixer {
public:
    Mixer() : voices_(kMaxVoices) {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            voices_[static_cast<size_t>(i)].id = i + 1;
        }
    }

    void setClipStore(ClipStore* store) { clips_ = store; }

    /** Switches between the headphone and the loudspeaker spatial model. */
    void setHeadphones(bool headphones) {
        headphones_.store(headphones, std::memory_order_release);
    }

    bool headphones() const { return headphones_.load(std::memory_order_acquire); }

    /** Lock-free query: never inspect callback-owned voice state from callers. */
    bool isKeySounding(int32_t keyId) const {
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            const Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.published.active.load(std::memory_order_acquire) &&
                candidate.published.keyId.load(std::memory_order_relaxed) == keyId) {
                return true;
            }
        }
        return false;
    }

    /**
     * True while this exact voice id is still in the pool. Lock-free, like
     * [isKeySounding]: a caller that remembers an id (a held loop) can tell a
     * live voice from one that already ended, without touching callback state.
     */
    bool isVoiceSounding(int32_t voiceId) const {
        if (voiceId <= 0) return false;
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            const Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.published.active.load(std::memory_order_acquire) &&
                candidate.published.id.load(std::memory_order_relaxed) == voiceId) {
                return true;
            }
        }
        return false;
    }

    /** Number of voices currently sounding. */
    int32_t activeVoices() const { return active_.load(std::memory_order_relaxed); }

    int32_t peakVoices() const { return peak_.load(std::memory_order_relaxed); }

    /**
     * Finds a free slot, or steals the quietest one.
     *
     * A critical voice (a held loop, the player's own weapon) is never stolen;
     * the quietest one-shot gives way instead, and its stop fade starts in the
     * very same block, so the newcomer is heard immediately and the victim
     * disappears without a click.
     */
    Voice* allocate(int32_t id, int32_t keyId, int32_t owner, int32_t flags,
                    const Clip* clip) {
        Voice* free = nullptr;
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip == nullptr || candidate.state == VOICE_FINISHED) {
                free = &candidate;
                break;
            }
        }
        if (free == nullptr) free = stealQuietVoice();
        if (free == nullptr) return nullptr;
        prepare(*free, id, keyId, owner, flags, clip);
        return free;
    }

    /** Evicts the quietest non-critical voice. Returns true when one was freed. */
    bool evictQuietest() { return stealQuietVoice() != nullptr; }

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
            candidate.resetForReuse();
            candidate.id = i + 1;
        }
        active_.store(0, std::memory_order_relaxed);
        peak_.store(0, std::memory_order_relaxed);
        resetCrossfeed();
    }

    /** Forgets the smoothed state of the crossfeed (after a gap in playback). */
    void resetCrossfeed() {
        crossStateL_ = 0.0f;
        crossStateR_ = 0.0f;
        crossGain_ = -1.0f;
    }

    /**
     * Renders every voice into `output` (interleaved stereo floats, already
     * zeroed by the caller) and applies the bus, the crossfeed and the master
     * gain.
     */
    void render(float* output, int32_t frames, int32_t sampleRate) {
        if (output == nullptr || frames <= 0) return;
        const int32_t rate = sampleRate > 0 ? sampleRate : 48000;

        RenderContext context;
        context.sampleRate = rate;
        context.headphones = headphones();
        context.rampStep = 1.0f -
            std::exp(-1.0f / (openfire::kGainRampMs * 0.001f * static_cast<float>(rate)));
        context.stopFadeStep = 1.0f /
            (openfire::kStopFadeMs * 0.001f * static_cast<float>(rate) + 1.0f);

        int32_t live = 0;
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& voice = voices_[static_cast<size_t>(i)];
            if (voice.clip == nullptr) continue;
            if (voice.state == VOICE_FINISHED) {
                retire(voice);
                continue;
            }
            if (!renderVoice(&voice, output, frames, context)) {
                retire(voice);
                continue;
            }
            ++live;
        }
        active_.store(live, std::memory_order_relaxed);
        int32_t peak = peak_.load(std::memory_order_relaxed);
        while (live > peak && !peak_.compare_exchange_weak(
                   peak, live, std::memory_order_relaxed)) {}

        /* The crossfeed is a speaker-only stage: it restores the centre image
         * a room would otherwise destroy. On headphones it is bypassed, so the
         * binaural image stays exactly as the voice rendered it. */
        if (!context.headphones) applyCrossfeed(output, frames, rate);

        const float master = master_.load(std::memory_order_relaxed);
        const float sfx = busGain_[0].load(std::memory_order_relaxed) * master;
        // The bus gain is applied once, here, and the same pass bends peaks
        // above the knee smoothly towards full scale. The device clips a float
        // stream hard, so without this a crowd of loud voices would crackle.
        for (int32_t frame = 0; frame < frames; ++frame) {
            output[frame * 2] = openfire::softLimit(output[frame * 2] * sfx);
            output[frame * 2 + 1] = openfire::softLimit(output[frame * 2 + 1] * sfx);
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
    void retire(Voice& voice) {
        voice.published.active.store(false, std::memory_order_release);
        voice.published.keyId.store(0, std::memory_order_relaxed);
        voice.published.id.store(0, std::memory_order_relaxed);
        voice.clip = nullptr;
        voice.stopping = false;
        voice.state = VOICE_PENDING;
    }

    /** Frees the quietest non-critical voice and starts its stop fade. */
    Voice* stealQuietVoice() {
        Voice* victim = nullptr;
        float quietest = 1e9f;
        for (int32_t i = 0; i < kMaxVoices; ++i) {
            Voice& candidate = voices_[static_cast<size_t>(i)];
            if (candidate.clip == nullptr || candidate.stopping) continue;
            if (candidate.loop() && (candidate.flags & kFlagCritical) != 0) continue;
            const float level = candidate.currentVolume *
                openfire::distanceGain(candidate.currentDistance,
                                       static_cast<float>(candidate.maxDistance));
            if (level < quietest) {
                quietest = level;
                victim = &candidate;
            }
        }
        if (victim == nullptr) return nullptr;
        /* A one-shot that never became audible is freed at once; anything the
         * listener can already hear is faded out over the stop fade instead. */
        victim->published.active.store(false, std::memory_order_release);
        if (victim->currentVolume <= 0.0001f && victim->playedFrames <= 0) {
            retire(*victim);
        } else {
            victim->stopping = true;
        }
        return victim;
    }

    /**
     * Speaker crossfeed: each ear receives a quiet, slightly delayed and
     * slightly dark copy of the opposite channel, which is what a room does.
     * A single smoothing stage supplies both the delay (one sample) and the
     * low-pass, and the whole stage is bypassed when the gain is zero.
     */
    void applyCrossfeed(float* output, int32_t frames, int32_t sampleRate) {
        const float target = openfire::kCrossfeedGain;
        if (crossGain_ < 0.0f) {
            crossGain_ = target;
            crossStateL_ = 0.0f;
            crossStateR_ = 0.0f;
        }
        const float coefficient = openfire::shadowCoefficient(
            openfire::kCrossfeedCutoffHz, sampleRate);
        for (int32_t frame = 0; frame < frames; ++frame) {
            const float left = output[frame * 2];
            const float right = output[frame * 2 + 1];
            crossStateL_ += coefficient * (left - crossStateL_);
            crossStateR_ += coefficient * (right - crossStateR_);
            output[frame * 2] = left + crossStateR_ * target;
            output[frame * 2 + 1] = right + crossStateL_ * target;
        }
    }

    void prepare(Voice& voice, int32_t id, int32_t keyId, int32_t owner, int32_t flags,
                 const Clip* clip) {
        voice.resetForReuse();
        voice.id = id;
        voice.keyId = keyId;
        voice.owner = owner;
        voice.flags = flags;
        voice.clip = clip;
        voice.published.id.store(id, std::memory_order_relaxed);
        voice.published.keyId.store(keyId, std::memory_order_relaxed);
        voice.published.active.store(true, std::memory_order_release);
    }

    std::vector<Voice> voices_;
    ClipStore* clips_ = nullptr;
    std::atomic<int32_t> active_{0};
    std::atomic<int32_t> peak_{0};
    std::atomic<float> master_{1.0f};
    std::atomic<bool> headphones_{true};
    std::atomic<float> busGain_[kBusCount] = {1.0f, 1.0f, 1.0f};

    /* Crossfeed state; audio thread only. */
    float crossStateL_ = 0.0f;
    float crossStateR_ = 0.0f;
    float crossGain_ = -1.0f;
};

}  // namespace openfire

#endif  // OPENFIRE_MIXER_H
