/*
 * One playing sound.
 *
 * A voice owns its read position, its ramped gains, its own short delay line
 * and its own rear-cue filter, so the placement of one sound can never leak
 * into another one and a crash of one voice can never exist. Everything the
 * game thread changes (gains, pan, distance, rate) is a plain float written
 * through the engine's command ring and picked up once per block, then glided
 * towards with a few-millisecond ramp - a moving source therefore never
 * clicks, and a stop is heard as a short clean fade instead of a cut.
 *
 * The spatial model, in order of importance:
 *
 *  1. **Distance** - the one distance curve of the game. It is what makes an
 *     enemy audible far away, swelling as he closes in and fading as he
 *     recedes. It is the cue a listener localises most reliably, and it works
 *     on any output: speaker, headphones or a single earpiece.
 *  2. **Pan (ILD)** - constant-power left/right placement. On speakers the pan
 *     is gently scaled and the mixer adds crossfeed, so the image never
 *     collapses into one speaker.
 *  3. **Front/rear** - a source behind the listener loses high end and a
 *     little level. This is a spectral cue: it survives a mono speaker, which
 *     is exactly why it is preferred over anything inter-channel here.
 *  4. **Interaural delay and head shadow (ITD)** - the two binaural refinements
 *     that separate two fighters standing on the same spot through
 *     headphones. They are applied only to a genuinely sideways source, and
 *     only in headphone mode: on loudspeakers the two channels are summed by
 *     the room anyway, so an ITD would only smear the image and comb-filter
 *     the sound.
 *
 * A head-on source is centred exactly and bypasses every inter-channel trick,
 * so a fighter running straight at the listener is heard in the middle of the
 * head with no comb filtering - the clean image the game needs for aiming.
 */
#ifndef OPENFIRE_VOICE_H
#define OPENFIRE_VOICE_H

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>

#include "AudioMath.h"
#include "Clip.h"

namespace openfire {

/** Delay-line length: 6 ms at 48 kHz, far more than the 0.63 ms needed. */
constexpr int32_t kDelayLineFrames = 288;

/** Voice flags, mirroring `enum of_voice_flags` in of_audio.h. */
enum VoiceFlag {
    kFlagLoop = 1 << 0,
    kFlagStereo = 1 << 1,
    kFlagSpatial = 1 << 2,
    kFlagDeadline = 1 << 3,
    kFlagApproach = 1 << 4,
    kFlagCritical = 1 << 5
};

enum VoiceState {
    VOICE_PENDING = 0,   /* created, not yet mixed */
    VOICE_ACTIVE = 1,
    VOICE_FINISHED = 2
};

/**
 * The published mirror of a voice.
 *
 * Only the audio thread writes these fields and only game threads read them,
 * so `isVoiceSounding` / `isKeySounding` never touch callback-owned state and
 * never take a lock.
 */
struct VoicePublication {
    std::atomic<int32_t> id{0};
    std::atomic<int32_t> keyId{0};
    std::atomic<bool> active{false};
};

class Voice {
public:
    int32_t id = 0;
    int32_t keyId = 0;
    int32_t owner = 0;
    int32_t flags = 0;
    const Clip* clip = nullptr;

    /* Lock-free query mirror; only the mixer writes, API callers read. */
    VoicePublication published;

    /* Callback-owned targets, populated from commands drained by the mixer. */
    float targetVolume = 1.0f;   /* the voice's own level */
    float targetPan = 0.0f;      /* -1 left .. +1 right */
    float targetDistance = 0.0f; /* metres from the listener */
    float targetRear = 0.0f;     /* -1 in front .. +1 behind */
    float targetRate = 1.0f;     /* playback rate (1 = natural pitch) */
    float approachProximity = 0.0f; /* 0 far .. 1 arrived (projectiles) */
    int32_t maxDistance = 25;
    bool stopping = false;

    /* Owned by the mixer thread. */
    double position = 0.0;
    int64_t playedFrames = 0;
    int64_t deadlineFrames = INT64_MAX;
    float currentVolume = 0.0f;
    float currentPan = 0.0f;
    float currentDistance = 0.0f;
    float currentRear = 0.0f;
    float currentRate = 1.0f;
    float stopFade = 1.0f;
    VoiceState state = VOICE_PENDING;

    /* Spatial state of the head model. */
    float farEarState = 0.0f;
    float rearState = 0.0f;
    float rearCoeff = 1.0f;
    float currentDelay = 0.0f;
    int32_t delayCursor = 0;
    float delayLine[kDelayLineFrames] = {0.0f};

    bool loop() const { return (flags & kFlagLoop) != 0; }
    bool spatial() const { return (flags & kFlagSpatial) != 0; }
    bool stereo() const { return (flags & kFlagStereo) != 0; }
    bool approaching() const { return (flags & kFlagApproach) != 0; }

    /** Resets every field that belongs to a previous voice generation. */
    void resetForReuse() {
        id = 0;
        keyId = 0;
        owner = 0;
        flags = 0;
        clip = nullptr;
        targetVolume = 1.0f;
        targetPan = 0.0f;
        targetDistance = 0.0f;
        targetRear = 0.0f;
        targetRate = 1.0f;
        approachProximity = 0.0f;
        maxDistance = 25;
        stopping = false;
        position = 0.0;
        playedFrames = 0;
        deadlineFrames = INT64_MAX;
        currentVolume = 0.0f;
        currentPan = 0.0f;
        currentDistance = 0.0f;
        currentRear = 0.0f;
        currentRate = 1.0f;
        stopFade = 1.0f;
        state = VOICE_PENDING;
        farEarState = 0.0f;
        rearState = 0.0f;
        rearCoeff = 1.0f;
        currentDelay = 0.0f;
        delayCursor = 0;
        published.active.store(false, std::memory_order_release);
        published.id.store(0, std::memory_order_relaxed);
        published.keyId.store(0, std::memory_order_relaxed);
    }
};

/** Options of one render pass; the mixer fills it once per callback. */
struct RenderContext {
    int32_t sampleRate = 48000;
    float rampStep = 0.01f;
    float stopFadeStep = 0.001f;
    bool headphones = true;
};

/**
 * Renders one voice into an interleaved stereo float block.
 *
 * Returns false when the voice has finished and must be removed. The block is
 * added to, never overwritten, so any number of voices mix together.
 */
inline bool renderVoice(Voice* voice,
                        float* output,
                        int32_t frames,
                        const RenderContext& context) {
    if (voice == nullptr || voice->clip == nullptr || output == nullptr || frames <= 0) {
        return false;
    }
    const Clip& clip = *voice->clip;
    const int32_t total = clip.frames();
    if (total <= 0 || clip.channels() <= 0) return false;

    const bool looping = voice->loop();
    const bool spatial = voice->spatial();
    const bool isStereo = voice->stereo();
    const bool approach = voice->approaching();
    const int32_t sampleRate = context.sampleRate > 0 ? context.sampleRate : 48000;

    /* The two inter-channel refinements are only used by a genuinely
     * sideways source on headphones. A centred source keeps a clean,
     * symmetric image on every output. */
    const bool sideways = spatial && std::fabs(voice->currentPan) >= kPanDeadZone;
    const bool binaural = sideways && context.headphones;
    const bool rightSide = voice->currentPan > 0.0f;
    const float shadowCoefficient = openfire::shadowCoefficient(kShadowCutoffHz, sampleRate);

    /* Delay target of the far ear, in fractional samples. */
    float delayTarget = 0.0f;
    if (binaural) {
        delayTarget = std::fabs(
            openfire::interauralDelaySamples(voice->currentPan, sampleRate));
        const float limit = static_cast<float>(kDelayLineFrames - 4);
        if (delayTarget > limit) delayTarget = limit;
    }

    /*
     * A stereo recording keeps its own image unless it has to be placed at a
     * real position in the arena; a positional source of the game is a point
     * source, so its channels are blended before it is placed.
     */
    const bool keepStereo = isStereo && (!spatial || !sideways);

    for (int32_t frame = 0; frame < frames; ++frame) {
        if (!looping && voice->position >= static_cast<double>(total)) {
            voice->state = VOICE_FINISHED;
            return false;
        }
        if (!looping && voice->deadlineFrames != INT64_MAX &&
            voice->playedFrames >= voice->deadlineFrames && !voice->stopping) {
            voice->stopping = true;
        }

        /* Glide every live parameter: no step is ever applied at once. */
        const float step = context.rampStep;
        voice->currentVolume += (voice->targetVolume - voice->currentVolume) * step;
        voice->currentPan += (voice->targetPan - voice->currentPan) * step;
        voice->currentDistance += (voice->targetDistance - voice->currentDistance) * step;
        voice->currentRear += (voice->targetRear - voice->currentRear) * step;
        voice->currentRate += (voice->targetRate - voice->currentRate) * step;
        voice->currentDelay += (delayTarget - voice->currentDelay) * step;
        if (voice->rearCoeff < 1.0f || voice->currentRear > 0.0f) {
            const float wanted = openfire::rearCoefficient(voice->currentRear, sampleRate);
            voice->rearCoeff += (wanted - voice->rearCoeff) * step;
        }

        if (voice->stopping) {
            voice->stopFade -= context.stopFadeStep;
            if (voice->stopFade <= 0.0f) {
                voice->stopFade = 0.0f;
                voice->state = VOICE_FINISHED;
                return false;
            }
        }

        if (voice->position < 0.0) {
            /* A delayed start (a chained sound): silent until the first sample. */
            voice->position += 1.0;
            voice->playedFrames++;
            continue;
        }

        if (looping && voice->position >= static_cast<double>(total)) {
            const double wrapped = std::fmod(voice->position, static_cast<double>(total));
            voice->position = (wrapped >= 0.0) ? wrapped : 0.0;
        }

        const int32_t index = static_cast<int32_t>(voice->position);
        const float fraction = static_cast<float>(voice->position - static_cast<double>(index));

        const float left = clip.sampleAt(index, 0, looping) / 32768.0f;
        const float right = isStereo ? clip.sampleAt(index, 1, looping) / 32768.0f : left;

        float source;
        if (keepStereo) {
            source = 0.5f * (left + right);
        } else if (fraction <= 0.0f) {
            source = left;
        } else {
            /* Catmull-Rom reconstruction keeps a shifted rate transparent. */
            const float p0 = clip.sampleAt(index - 1, 0, looping) / 32768.0f;
            const float p1 = left;
            const float p2 = clip.sampleAt(index + 1, 0, looping) / 32768.0f;
            const float p3 = clip.sampleAt(index + 2, 0, looping) / 32768.0f;
            source = openfire::catmullRom(p0, p1, p2, p3, fraction);
        }

        if (voice->stopFade < 1.0f) source *= voice->stopFade;

        /* Level of the whole source: own volume, distance, front/rear and the
         * approach cue of a projectile. */
        float level = voice->currentVolume;
        if (spatial) {
            level *= openfire::distanceGain(
                voice->currentDistance, static_cast<float>(voice->maxDistance));
            level *= openfire::rearGain(voice->currentRear);
        }
        if (approach) level *= openfire::approachGain(voice->approachProximity);
        level = openfire::clampFloat(level, 0.0f, 8.0f);

        /*
         * The rear cue: a one-pole low-pass whose cut-off falls as the source
         * moves behind the listener. It is mono-compatible, so it still works
         * on a phone speaker; `rearCoeff` of 1 is exactly "no filtering".
         */
        if (!keepStereo) {
            if (voice->rearCoeff < 0.9999f) {
                voice->rearState += voice->rearCoeff * (source - voice->rearState);
                source = voice->rearState;
            } else {
                voice->rearState = source;
            }
        }

        float outLeft;
        float outRight;
        if (keepStereo) {
            /* A non-positional stereo clip keeps its recording's own image. */
            outLeft = left * level;
            outRight = right * level;
        } else {
            /* Constant-power placement, then the binaural refinement. */
            const float pan = openfire::panForOutput(
                sideways ? voice->currentPan : 0.0f, context.headphones);
            float panLeft = 1.0f;
            float panRight = 1.0f;
            openfire::panGains(pan, &panLeft, &panRight);
            outLeft = source * level * panLeft;
            outRight = source * level * panRight;

            /*
             * The delay line advances for every point source, even while it
             * is centred: a source that moves from the middle to one side
             * then reads recent audio instead of a buffer that was frozen the
             * last time it was off-centre. A centred source never *reads* it,
             * so the head-on image stays perfectly symmetric.
             */
            voice->delayLine[voice->delayCursor] = source;

            if (binaural) {
                /*
                 * The ear away from the source hears the sound through the
                 * head: later (fractional delay) and duller (shadow filter).
                 */
                const int32_t whole = static_cast<int32_t>(voice->currentDelay);
                const float frac = voice->currentDelay - static_cast<float>(whole);
                int32_t readA = (voice->delayCursor - whole) % kDelayLineFrames;
                if (readA < 0) readA += kDelayLineFrames;
                int32_t readB = readA - 1;
                if (readB < 0) readB += kDelayLineFrames;
                const float delayed =
                    voice->delayLine[readA] * (1.0f - frac) + voice->delayLine[readB] * frac;

                voice->farEarState += shadowCoefficient * (delayed - voice->farEarState);
                float nearGain = 1.0f;
                float farGain = 1.0f;
                openfire::headShadowGains(voice->currentPan, &nearGain, &farGain);
                if (rightSide) {
                    outLeft = voice->farEarState * level * panLeft * farGain;
                    outRight = source * level * panRight;
                } else {
                    outRight = voice->farEarState * level * panRight * farGain;
                    outLeft = source * level * panLeft;
                }
            } else {
                /* Dead centre (or a speaker mix): symmetric, no comb filter,
                 * no shadow. The pan above already carries the placement. */
                voice->farEarState = source;
            }
            voice->delayCursor = (voice->delayCursor + 1) % kDelayLineFrames;
        }

        output[frame * 2] += outLeft;
        output[frame * 2 + 1] += outRight;

        voice->position += static_cast<double>(openfire::naturalRate(voice->currentRate));
        voice->playedFrames++;
    }

    voice->state = VOICE_ACTIVE;
    return true;
}

}  // namespace openfire

#endif  // OPENFIRE_VOICE_H
