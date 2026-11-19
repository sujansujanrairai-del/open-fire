#include "Mixer.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
constexpr int kFrames = 32;
constexpr float kSample = 0.25f; // 8192 / 32768, below the mixer's limiter knee

void expectNear(float actual, float expected) {
    if (std::fabs(actual - expected) > 0.0001f) {
        std::cerr << "Expected " << expected << ", got " << actual << '\n';
        std::exit(1);
    }
}

void startVoice(openfire::Mixer& mixer, const openfire::Clip& clip, int id,
                bool spatial, float distance) {
    auto* voice = mixer.allocate(id, id, 0, spatial ? openfire::kFlagSpatial : 0, &clip);
    if (voice == nullptr) std::exit(1);
    // Start at the stable levels so this test measures the gain rather than
    // the short, deliberate fade-in of a newly created native voice.
    voice->currentVolume = voice->targetVolume = 1.0f;
    voice->currentDistance = voice->targetDistance = distance;
}

float renderOne(float distance, bool spatial, float slider) {
    const openfire::Clip clip(kFrames, 1, std::vector<int16_t>(kFrames, 8192));
    openfire::Mixer mixer;
    mixer.setHeadphones(true);
    mixer.setBusGain(0, slider);
    startVoice(mixer, clip, 1, spatial, distance);
    std::vector<float> output(kFrames * 2, 0.0f);
    mixer.render(output.data(), kFrames, 48000);
    expectNear(output[0], output[1]);
    return output[0];
}
} // namespace

int main() {
    for (float distance : {5.0f, 15.0f, 25.0f}) {
        // A non-positional player gun ignores the enemy's distance, while an
        // enemy voice at the same distance keeps the normal spatial curve.
        expectNear(renderOne(distance, false, 1.0f), kSample);
        expectNear(renderOne(distance, true, 1.0f), kSample * openfire::distanceGain(distance, 25.0f));
        // The effects slider is applied once on the native bus.
        expectNear(renderOne(distance, false, 0.35f), kSample * 0.35f);
    }

    // A centred stereo gun keeps both recorded channels rather than copying
    // only the left channel into both ears.
    std::vector<int16_t> stereoSamples;
    for (int i = 0; i < kFrames; ++i) {
        stereoSamples.push_back(8192);
        stereoSamples.push_back(4096);
    }
    const openfire::Clip stereoGun(kFrames, 2, stereoSamples);
    openfire::Mixer stereoMixer;
    auto* stereoVoice = stereoMixer.allocate(1, 1, 0, openfire::kFlagStereo, &stereoGun);
    if (stereoVoice == nullptr) return 1;
    stereoVoice->currentVolume = stereoVoice->targetVolume = 1.0f;
    std::vector<float> stereoOutput(kFrames * 2, 0.0f);
    stereoMixer.render(stereoOutput.data(), kFrames, 48000);
    expectNear(stereoOutput[0], 0.25f);
    expectNear(stereoOutput[1], 0.125f);
    stereoVoice->position = 0.0;
    stereoVoice->stopping = true;
    std::fill(stereoOutput.begin(), stereoOutput.end(), 0.0f);
    stereoMixer.render(stereoOutput.data(), kFrames, 48000);
    if (!(stereoOutput[0] > stereoOutput[(kFrames - 1) * 2] &&
          stereoOutput[1] > stereoOutput[(kFrames - 1) * 2 + 1])) {
        std::cerr << "Stereo stop did not fade both channels\n";
        return 1;
    }

    // Two centred release clips are added into the same output block.
    const openfire::Clip shock(kFrames, 1, std::vector<int16_t>(kFrames, 8192));
    const openfire::Clip throwSound(kFrames, 1, std::vector<int16_t>(kFrames, 4096));
    openfire::Mixer mixer;
    startVoice(mixer, shock, 1, false, 0.0f);
    startVoice(mixer, throwSound, 2, false, 0.0f);
    std::vector<float> output(kFrames * 2, 0.0f);
    mixer.render(output.data(), kFrames, 48000);
    expectNear(output[0], 0.375f);
    expectNear(output[1], 0.375f);
    std::cout << "Native spatial and release-mix tests passed\n";
}
