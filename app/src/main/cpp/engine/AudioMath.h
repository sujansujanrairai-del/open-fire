/*
 * Pure audio arithmetic of the Open Fire engine - no Android, no Oboe.
 *
 * Everything here is a small, deterministic function so it can be verified
 * on the host (see app/src/test/cpp) and reused by the mixer without any
 * hidden state. The rules mirror the Kotlin model exactly:
 *
 *  - `distance_gain` is the one distance curve of the game: a source at the
 *    far edge of the arena is quiet but present, and it swells continuously
 *    as the source closes in, then fades again while it recedes. The curve is
 *    a squared proximity, which keeps far sounds unmistakably distant without
 *    disappearing.
 *  - `pan_gains` is a normalised constant-power law: a centred sound plays at
 *    full level in both ears (the same loudness as the mono speech voice)
 *    instead of dipping the way a raw sin/cos law does.
 *  - `pan_for` never pans a head-on source: inside the central cone the image
 *    stays exactly in the middle, so running straight at the enemy does not
 *    smear the sound between the ears.
 *  - `interaural_delay_samples` and `head_shadow` give two sources that sit
 *    on the same line - the player and the enemy running together - genuinely
 *    different arrival times and different spectra, so they stay
 *    distinguishable through headphones even when they use the same recording.
 */
#ifndef OPENFIRE_AUDIO_MATH_H
#define OPENFIRE_AUDIO_MATH_H

#include <cmath>
#include <cstdint>

namespace openfire {

/** Quietest a positional sound ever becomes at the far edge. */
constexpr float kDistanceFloor = 0.08f;

/** Only part of the geometric pan is used, so the sides stay gentle. */
constexpr float kPanStrength = 0.85f;

/** Pans smaller than this are centred: straight motion never wobbles. */
constexpr float kPanDeadZone = 0.12f;

/**
 * The natural window of every playback rate. A pace change inside this window
 * shifts the recording without ever making it sound chipped or shrill, and the
 * three running levels sit inside it with plenty of room between them.
 */
constexpr float kMinRate = 0.70f;
constexpr float kMaxRate = 1.60f;

/** Footstep playback-rate window per running level (see footstepRateFor). */
constexpr float kSlowFootstepRate = 0.78f;
constexpr float kNormalFootstepRate = 1.00f;
constexpr float kFastFootstepRate = 1.45f;

/** Maximum interaural delay used by the head model, in seconds. */
constexpr float kMaxItdSeconds = 0.00063f;

/** Ramp length of every gain change, in milliseconds (click-free moves). */
constexpr float kGainRampMs = 8.0f;

/** Fade used when a voice is stopped, in milliseconds. */
constexpr float kStopFadeMs = 12.0f;

inline float clamp01(float value) {
    if (!(value > 0.0f)) return 0.0f;  // also catches NaN
    return value > 1.0f ? 1.0f : value;
}

inline float clampFloat(float value, float low, float high) {
    if (!(value == value)) return low;  // NaN
    if (value < low) return low;
    if (value > high) return high;
    return value;
}

/**
 * Distance attenuation for a source `distance` metres away inside an arena
 * whose far edge is `maxRange` metres.
 */
inline float distanceGain(float distance, float maxRange) {
    const float range = maxRange > 1.0f ? maxRange : 1.0f;
    const float safe = clampFloat(distance, 0.0f, range);
    const float proximity = clamp01(1.0f - safe / range);
    const float gain = kDistanceFloor + proximity * proximity * (1.0f - kDistanceFloor);
    return clampFloat(gain, kDistanceFloor, 1.0f);
}

/** Stereo pan of a source `lateral` metres to the side at `distance` metres. */
inline float panFor(float lateral, float distance) {
    if (!(lateral == lateral) || !(distance == distance)) return 0.0f;
    if (distance <= 0.01f) return 0.0f;
    const float raw = clampFloat(lateral / distance, -1.0f, 1.0f) * kPanStrength;
    if (std::fabs(raw) < kPanDeadZone) return 0.0f;
    return clampFloat(raw, -1.0f, 1.0f);
}

/** Normalised constant-power left/right gains for `pan` in -1..1. */
inline void panGains(float pan, float* left, float* right) {
    const float safe = clampFloat(pan, -1.0f, 1.0f);
    const float angle = (safe + 1.0f) * 0.785398163f;  // (pan + 1) * pi/4
    const float normalise = 1.41421356f;                // sqrt(2)
    *left = clamp01(std::cos(angle) * normalise);
    *right = clamp01(std::sin(angle) * normalise);
}

/** Clamps any requested rate into the natural window. */
inline float naturalRate(float rate) {
    if (!(rate == rate) || rate <= 0.0f) return 1.0f;
    return clampFloat(rate, kMinRate, kMaxRate);
}

/**
 * Footstep playback rate of one running level.
 *
 * The three levels are deliberately distinct: a slow run drags the steps, a
 * normal run plays the recording at its natural speed and a fast run drives
 * it clearly quicker. They are three fixed, natural-sounding rates rather
 * than a continuous speed curve, so the player always hears which level is
 * selected - which is exactly what "slow, normal and fast sound different"
 * means.
 */
inline float footstepRateFor(float speedMps, float slowSpeed, float baseSpeed, float fastSpeed) {
    if (!(speedMps == speedMps) || speedMps <= 0.0f) return kNormalFootstepRate;
    const float slow = slowSpeed > 0.0f ? slowSpeed : 1.3f;
    const float base = baseSpeed > slow ? baseSpeed : slow * 2.0f;
    const float fast = fastSpeed > base ? fastSpeed : base * 3.0f;
    if (speedMps <= slow * 1.02f) return kSlowFootstepRate;
    if (speedMps >= fast * 0.98f) return kFastFootstepRate;
    if (speedMps <= base) {
        // Between the slow level and the normal level.
        const float span = base - slow;
        const float t = span > 0.0f ? clamp01((speedMps - slow) / span) : 0.0f;
        return kSlowFootstepRate + t * (kNormalFootstepRate - kSlowFootstepRate);
    }
    // Between the normal level and the fast level.
    const float span = fast - base;
    const float t = span > 0.0f ? clamp01((speedMps - base) / span) : 0.0f;
    return kNormalFootstepRate + t * (kFastFootstepRate - kNormalFootstepRate);
}

/**
 * Interaural time difference of a source at `pan` (-1 left, +1 right),
 * expressed in (fractional) samples.
 *
 * A source on the right reaches the right ear first: the right ear is
 * advanced by half the delay and the left ear is delayed by the other half.
 * Two sources that differ only in distance therefore still differ in arrival
 * time, which is what the auditory system uses to tell them apart.
 */
inline float interauralDelaySamples(float pan, int32_t sampleRate) {
    const float safe = clampFloat(pan, -1.0f, 1.0f);
    return safe * kMaxItdSeconds * static_cast<float>(sampleRate);
}

/**
 * Head-shadow gain of the far ear: the head is opaque to high frequencies,
 * so the ear away from the source hears a slightly quieter, duller signal.
 */
inline void headShadowGains(float pan, float* nearGain, float* farGain) {
    const float side = clampFloat(std::fabs(pan), 0.0f, 1.0f);
    *farGain = 1.0f - 0.35f * side;
    *nearGain = 1.0f;
}

/**
 * One-pole low-pass coefficient of the far ear for a given cut-off. A
 * shadowed ear loses its high end, so the two fighters never sound like the
 * same point source even when they stand on the same spot.
 */
inline float shadowCoefficient(float cutoffHz, int32_t sampleRate) {
    const float rate = sampleRate > 0 ? static_cast<float>(sampleRate) : 48000.0f;
    const float cutoff = clampFloat(cutoffHz, 200.0f, rate * 0.45f);
    const float x = std::exp(-2.0f * 3.14159265f * cutoff / rate);
    return clamp01(1.0f - x);
}

/**
 * Approach cue of a projectile: a slow, small pitch rise as the missile or
 * bomb closes in, in ADDITION to the loudness swell of [approachGain].
 *
 * The rate is applied by the engine's own approach branch, so the caller's
 * base rate is already a playback rate (it is clamped once, by the caller) and
 * this function only adds the cue: the result is never multiplied twice, which
 * is what would shrink the natural window and make the cue inaudible.
 */
inline float approachRate(float baseRate, float proximity) {
    const float base = (baseRate == baseRate && baseRate > 0.0f) ? baseRate : 1.0f;
    const float p = clamp01(proximity);
    return base * (1.0f + 0.16f * p * p);
}

/** Extra loudness of an approaching projectile at `proximity` (0..1). */
inline float approachGain(float proximity) {
    const float p = clamp01(proximity);
    return 1.0f + 0.55f * p;
}

/**
 * Soft-knee limiter applied once, on the mix bus. Peaks above `knee` bend
 * smoothly towards full scale instead of clipping, so a crowd of loud voices
 * converges on 1.0 without a click.
 */
inline float softLimit(float sample) {
    constexpr float knee = 0.8f;
    const float magnitude = std::fabs(sample);
    if (magnitude <= knee) return sample;
    const float bent = knee + (1.0f - knee) * (1.0f - std::exp(-(magnitude - knee) * 5.0f / (1.0f - knee)));
    return sample < 0.0f ? -bent : bent;
}

/**
 * Linear-interpolated Catmull-Rom read of one sample, used for every
 * non-unity playback rate. Linear interpolation alone is audible as a harsh
 * colour change; the four-point curve reconstructs the waveform smoothly.
 */
inline float catmullRom(float p0, float p1, float p2, float p3, float t) {
    if (t <= 0.0f) return p1;
    const float t2 = t * t;
    const float t3 = t2 * t;
    return 0.5f * ((2.0f * p1) +
                   (-p0 + p2) * t +
                   (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t2 +
                   (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t3);
}

/** Converts a linear mix sample to 16-bit PCM with the soft knee applied. */
inline int16_t toPcm16(float sample) {
    const float limited = clampFloat(softLimit(sample), -1.0f, 1.0f);
    const int32_t value = static_cast<int32_t>(limited * 32767.0f);
    return static_cast<int16_t>(value);
}

}  // namespace openfire

#endif  // OPENFIRE_AUDIO_MATH_H
