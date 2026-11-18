/*
 * One playing sound.
 *
 * A voice owns its read position, its ramped gains and its own short delay
 * line, so the binaural placement of one sound can never leak into another
 * one. Everything the game thread changes (gains, pan, distance, rate) is a
 * plain float written through the engine's command ring and picked up once
 * per block, then glided towards with a few-millisecond ramp - a moving
 * source therefore never clicks, and a stop is heard as a short clean fade
 * instead of a cut.
 *
 * Two features make the player and the enemy distinguishable through
 * headphones even when they play the exact same recording at the same rate:
 *
 *  - a fractional interaural delay (an interpolated delay line) places the
 *    sound at the correct side and time-of-arrival, and
 *  - a head-shadow one-pole filter on the far ear removes the high end of the
 *    sound that reaches the ear away from the source.
 *
 * A head-on source is centred exactly and bypasses both, so a fighter running
 * straight at the listener is heard in the middle of the head with no comb
 * filtering - the clean image the game needs for aiming.
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

/** Delay-line length: 5 ms at 48 kHz, far more than the 0.63 ms needed. */
constexpr int32_t kDelayLineFrames = 256;

enum VoiceState {
    VOICE_PENDING = 0,   /* created, not yet mixed */
    VOICE_ACTIVE = 1,
    VOICE_FINISHED = 2
};

class Voice {
public:
    int32_t id = 0;
    int32_t keyId = 0;
    int32_t owner = 0;
    int32_t flags = 0;
    const Clip* clip = nullptr;

    /* Lock-free query mirror; only the mixer writes, API callers read. */
    std::atomic<int32_t> publishedKeyId{0};
    std::atomic<bool> publishedActive{false};

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

    /* Spatial state of the binaural model. */
    float farEarState = 0.0f;
    float currentDelay = 0.0f;
    int32_t delayCursor = 0;
    float delayLine[kDelayLineFrames] = {0.0f};

    bool loop() const { return (flags & 1) != 0; }  /* OF_FLAG_LOOP */
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
                        int32_t sampleRate,
                        float rampStep,
                        float stopFadeStep) {
    if (voice == nullptr || voice->clip == nullptr) return false;
    const Clip& clip = *voice->clip;
    const int32_t total = clip.frames();
    if (total <= 0) return false;

    const bool looping = voice->loop();
    const bool spatial = (voice->flags & (1 << 2)) != 0;   /* OF_FLAG_SPATIAL */
    const bool isStereo = (voice->flags & (1 << 1)) != 0;  /* OF_FLAG_STEREO */
    const bool approach = (voice->flags & (1 << 4)) != 0;  /* OF_FLAG_APPROACH */

    /*
     * The two spatial features are only ever used by a genuinely sideways
     * source. A centred one keeps a clean, symmetric image.
     */
    const bool sideways = spatial && std::fabs(voice->currentPan) >= kPanDeadZone;
    const bool rightSide = voice->currentPan > 0.0f;
    const float shadowCoefficient = openfire::shadowCoefficient(3200.0f, sampleRate);

    /* Delay target of the far ear, in fractional samples. */
    float delayTarget = 0.0f;
    if (sideways) {
        delayTarget = std::fabs(
            openfire::interauralDelaySamples(voice->currentPan, sampleRate));
        if (delayTarget > static_cast<float>(kDelayLineFrames - 4)) {
            delayTarget = static_cast<float>(kDelayLineFrames - 4);
        }
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
        if (!looping && voice->playedFrames >= voice->deadlineFrames && !voice->stopping) {
            voice->stopping = true;
        }

        /* Glide every live parameter: no step is ever applied at once. */
        voice->currentVolume += (voice->targetVolume - voice->currentVolume) * rampStep;
        voice->currentPan += (voice->targetPan - voice->currentPan) * rampStep;
        voice->currentDistance += (voice->targetDistance - voice->currentDistance) * rampStep;
        voice->currentRear += (voice->targetRear - voice->currentRear) * rampStep;
        voice->currentRate += (voice->targetRate - voice->currentRate) * rampStep;
        voice->currentDelay += (delayTarget - voice->currentDelay) * rampStep;

        if (voice->stopping) {
            voice->stopFade -= stopFadeStep;
            if (voice->stopFade <= 0.0f) {
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
            voice->position = std::fmod(voice->position, static_cast<double>(total));
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

        /* Level of the whole source: own volume, distance and approach cue. */
        float level = voice->currentVolume;
        if (spatial) {
            level *= openfire::distanceGain(
                voice->currentDistance, static_cast<float>(voice->maxDistance));
        }
        if (approach) level *= openfire::approachGain(voice->approachProximity);
        level = openfire::clampFloat(level, 0.0f, 8.0f);

        float outLeft;
        float outRight;
        if (keepStereo) {
            /* A non-positional stereo clip keeps its recording's own image. */
            outLeft = left * level;
            outRight = right * level;
        } else {
            /* Constant-power placement, then the binaural refinement. */
            float panLeft = 1.0f;
            float panRight = 1.0f;
            openfire::panGains(voice->currentPan, &panLeft, &panRight);
            outLeft = source * level * panLeft;
            outRight = source * level * panRight;

            if (sideways) {
                /*
                 * The ear away from the source hears the sound through the
                 * head: later (fractional delay) and duller (shadow filter).
                 */
                voice->delayLine[voice->delayCursor] = source;
                const int32_t whole = static_cast<int32_t>(voice->currentDelay);
                const float frac = voice->currentDelay - static_cast<float>(whole);
                int32_t readA = (voice->delayCursor - whole) % kDelayLineFrames;
                if (readA < 0) readA += kDelayLineFrames;
                int32_t readB = readA - 1;
                if (readB < 0) readB += kDelayLineFrames;
                const float delayed =
                    voice->delayLine[readA] * (1.0f - frac) + voice->delayLine[readB] * frac;
                voice->delayCursor = (voice->delayCursor + 1) % kDelayLineFrames;

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
                /* Dead centre: perfectly symmetric, no comb filter, no shadow. */
                const float centre = 0.5f * (outLeft + outRight);
                outLeft = centre;
                outRight = centre;
            }
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
