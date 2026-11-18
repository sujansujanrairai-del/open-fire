#include "AudioEngine.h"

#include <algorithm>
#include <cstring>

namespace openfire {

namespace {

/** Clamps a requested frame count so the scratch buffer can never overflow. */
int32_t clampFrames(int32_t frames) {
    if (frames <= 0) return 0;
    return frames > 8192 ? 8192 : frames;
}

/* The C structure is reused inside the engine, so one helper keeps the copy
 * of every field in a single place. */
void applyParams(Voice* voice, const of_voice_params& params, int32_t sampleRate) {
    if (voice == nullptr) return;
    voice->keyId = params.key_id;
    voice->owner = params.owner;
    voice->flags = params.flags;
    voice->targetVolume = openfire::clampFloat(params.volume, 0.0f, 8.0f);
    voice->targetPan = openfire::clampFloat(params.pan, -1.0f, 1.0f);
    voice->targetDistance = params.distance > 0.0f ? params.distance : 0.0f;
    voice->targetRear = openfire::clampFloat(params.rear, -1.0f, 1.0f);
    const float requested = params.rate <= 0.0f ? 1.0f : params.rate;
    /* A projectile rises slightly in pitch as it closes in (approach cue). */
    voice->targetRate = (params.flags & 16) != 0
        ? openfire::approachRate(requested, params.approach)
        : openfire::naturalRate(requested);
    voice->approachProximity = openfire::clamp01(params.approach);
    voice->maxDistance = params.max_distance_m > 0 ? params.max_distance_m : 25;
    if ((params.flags & 8) != 0 && params.deadline_ms > 0) {
        voice->deadlineFrames = static_cast<int64_t>(params.deadline_ms) * sampleRate / 1000;
    }
}

}  // namespace

AudioEngine::AudioEngine() {
    mixer_.setClipStore(&clips_);
    // Preallocate for the largest callback this engine accepts. No resize or
    // allocation is permitted on Oboe's real-time callback thread.
    mixBuffer_.resize(static_cast<size_t>(8192) * 2);
    mixCapacityFrames_ = 8192;
}

AudioEngine::~AudioEngine() {
    stopStream();
    mixer_.clear();
    clips_.clear();
}

/* ------------------------------------------------------------------ stream */

int32_t AudioEngine::start() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (running_.load(std::memory_order_acquire)) return 0;
    desiredRunning_.store(true, std::memory_order_release);
    openStreamLocked();
    const bool started = running_.load(std::memory_order_acquire);
    if (!started) desiredRunning_.store(false, std::memory_order_release);
    return started ? 0 : -1;
}

void AudioEngine::openStreamLocked() {
    // Exclusive mode and a pinned 48 kHz rate are desirable, not prerequisites.
    // A number of devices (notably Bluetooth routes and vendor AAudio stacks)
    // reject that combination. Retry with shared mode and the device-native
    // rate before declaring audio unavailable; keep a fresh builder per attempt
    // because openStream may mutate its negotiated configuration.
    struct Attempt {
        oboe::SharingMode sharing;
        int32_t sampleRate;
    };
    constexpr Attempt attempts[] = {
        {oboe::SharingMode::Exclusive, kPreferredSampleRate},
        {oboe::SharingMode::Shared, kPreferredSampleRate},
        {oboe::SharingMode::Shared, oboe::kUnspecified}
    };

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = oboe::Result::ErrorBase;
    for (const auto& attempt : attempts) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(attempt.sharing)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(attempt.sampleRate)
            ->setUsage(oboe::Usage::Game)
            ->setContentType(oboe::ContentType::Sonification)
            ->setDataCallback(this)
            ->setErrorCallback(this)
            ->setFormatConversionAllowed(true)
            ->setChannelConversionAllowed(true)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        result = builder.openStream(stream);
        if (result == oboe::Result::OK && stream != nullptr) break;
        if (stream != nullptr) {
            stream->close();
            stream.reset();
        }
    }
    if (result != oboe::Result::OK || stream == nullptr) {
        running_.store(false, std::memory_order_release);
        return;
    }

    sampleRate_.store(stream->getSampleRate(), std::memory_order_release);
    const int32_t framesPerBurst = stream->getFramesPerBurst();
    framesPerBurst_.store(framesPerBurst, std::memory_order_release);
    lowLatency_.store(stream->getPerformanceMode() == oboe::PerformanceMode::LowLatency,
                      std::memory_order_release);

    const int32_t bufferSize = stream->getBufferSizeInFrames();
    if (framesPerBurst > 0 && bufferSize >= framesPerBurst && bufferSize < framesPerBurst * 2) {
        stream->setBufferSizeInFrames(framesPerBurst * 2);
    }

    stream_ = stream;
    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        auto failed = stream_;
        stream_.reset();
        failed->close();
        running_.store(false, std::memory_order_release);
        return;
    }
    running_.store(true, std::memory_order_release);
}

void AudioEngine::closeStreamLocked() {
    auto stream = stream_;
    stream_.reset();
    running_.store(false, std::memory_order_release);
    if (stream != nullptr) {
        stream->requestStop();
        stream->close();
    }
}

void AudioEngine::stopStream() {
    std::lock_guard<std::mutex> lock(streamMutex_);
    desiredRunning_.store(false, std::memory_order_release);
    closeStreamLocked();
    mixer_.clear();
}

/* ------------------------------------------------------------------- clips */

int32_t AudioEngine::loadClip(const int16_t* samples, int32_t frames, int32_t channels,
                              int32_t sampleRate) {
    if (samples == nullptr || frames <= 0 || channels <= 0) return 0;
    const size_t expected = static_cast<size_t>(frames) * static_cast<size_t>(channels);

    std::vector<int16_t> copy(expected);
    std::memcpy(copy.data(), samples, expected * sizeof(int16_t));

    /* Resample to the output rate once, here, so the mixer never resamples. */
    const int32_t outputRate = sampleRate_.load(std::memory_order_acquire);
    const int32_t target = outputRate > 0 ? outputRate : kPreferredSampleRate;
    int32_t outFrames = frames;
    if (sampleRate > 0 && sampleRate != target) {
        outFrames = static_cast<int32_t>(
            static_cast<int64_t>(frames) * target / sampleRate);
        if (outFrames <= 0) return 0;
        std::vector<int16_t> resampled(static_cast<size_t>(outFrames) * channels);
        const double ratio = static_cast<double>(sampleRate) / static_cast<double>(target);
        for (int32_t frame = 0; frame < outFrames; ++frame) {
            const double source = frame * ratio;
            const int32_t index = static_cast<int32_t>(source);
            const int32_t next = std::min(index + 1, frames - 1);
            const float fraction = static_cast<float>(source - index);
            for (int32_t channel = 0; channel < channels; ++channel) {
                const float a = copy[static_cast<size_t>(index) * channels + channel];
                const float b = copy[static_cast<size_t>(next) * channels + channel];
                resampled[static_cast<size_t>(frame) * channels + channel] =
                    static_cast<int16_t>(a + (b - a) * fraction);
            }
        }
        return clips_.add(outFrames, channels, std::move(resampled));
    }
    return clips_.add(frames, channels, std::move(copy));
}

void AudioEngine::unloadClip(int32_t clipId) { clips_.remove(clipId); }

/* ------------------------------------------------------------------ voices */

int32_t AudioEngine::nextVoiceId() {
    int32_t value = voiceCounter_.fetch_add(1, std::memory_order_relaxed);
    if (value <= 0) {
        voiceCounter_.store(2, std::memory_order_relaxed);
        value = 1;
    }
    return value;
}

int32_t AudioEngine::play(const of_voice_params& params) {
    Command command;
    command.type = CMD_PLAY;
    command.voiceId = nextVoiceId();
    command.params = params;
    post(command);
    return command.voiceId;
}

void AudioEngine::update(int32_t voiceId, const of_voice_params& params) {
    Command command;
    command.type = CMD_UPDATE;
    command.voiceId = voiceId;
    command.params = params;
    post(command);
}

void AudioEngine::stop(int32_t voiceId) {
    Command command;
    command.type = CMD_STOP;
    command.voiceId = voiceId;
    post(command);
}

void AudioEngine::stopKey(int32_t keyId, int32_t owner) {
    Command command;
    command.type = owner >= 0 ? CMD_STOP_OWNER_KEY : CMD_STOP_KEY;
    command.keyId = keyId;
    command.owner = owner;
    post(command);
}

void AudioEngine::stopOwner(int32_t owner) {
    Command command;
    command.type = CMD_STOP_OWNER;
    command.owner = owner;
    post(command);
}

void AudioEngine::stopAll() {
    Command command;
    command.type = CMD_STOP_ALL;
    post(command);
}

void AudioEngine::setPaused(bool paused) {
    paused_.store(paused, std::memory_order_release);
    Command command;
    command.type = CMD_SET_PAUSED;
    command.paused = paused ? 1 : 0;
    post(command);
}

/* --------------------------------------------------------------- commands */

void AudioEngine::post(const Command& command) {
    // Producers may be the UI, gameplay, and more than one coroutine thread.
    // Never mutate Mixer directly here: it is single-owned by the Oboe callback.
    // The large bounded MPMC queue drains every callback; a pathological full
    // queue drops this one control request rather than racing audio state.
    ring_.try_push(command);
}

void AudioEngine::drainCommands() {
    Command command;
    while (ring_.try_pop(&command)) {
        apply(command);
    }
}

void AudioEngine::apply(const Command& command) {
    const int32_t sampleRate = sampleRate_.load(std::memory_order_acquire);
    switch (command.type) {
        case CMD_PLAY: {
            const Clip* clip = clips_.get(command.params.clip_id);
            if (clip == nullptr) return;
            Voice* voice = mixer_.allocate(command.voiceId, command.params.key_id,
                                           command.params.owner, command.params.flags, clip);
            if (voice == nullptr) {
                /* Full pool: make room by fading the quietest one-shot. */
                if (!mixer_.evictQuietest()) return;
                voice = mixer_.allocate(command.voiceId, command.params.key_id,
                                        command.params.owner, command.params.flags, clip);
                if (voice == nullptr) return;
            }
            const int32_t offsetFrames = command.params.start_offset_ms > 0
                ? static_cast<int32_t>(
                      static_cast<int64_t>(command.params.start_offset_ms) *
                      sampleRate / 1000) % std::max(1, clip->frames())
                : 0;
            const int32_t delayFrames = command.params.start_delay_ms > 0
                ? static_cast<int32_t>(
                      static_cast<int64_t>(command.params.start_delay_ms) *
                      sampleRate / 1000)
                : 0;
            voice->position = static_cast<double>(offsetFrames) - delayFrames;
            voice->currentVolume = 0.0f;
            voice->currentPan = openfire::clampFloat(command.params.pan, -1.0f, 1.0f);
            voice->currentDistance = command.params.distance;
            voice->currentRear = command.params.rear;
            voice->currentRate = command.params.rate <= 0.0f ? 1.0f : command.params.rate;
            voice->approachProximity = openfire::clamp01(command.params.approach);
            voice->maxDistance = command.params.max_distance_m > 0
                ? command.params.max_distance_m : 25;
            voice->flags = command.params.flags;
            applyParams(voice, command.params, sampleRate);
            return;
        }
        case CMD_UPDATE: {
            Voice* voice = mixer_.find(command.voiceId);
            applyParams(voice, command.params, sampleRate);
            return;
        }
        case CMD_STOP:
            mixer_.stopId(command.voiceId);
            return;
        case CMD_STOP_KEY:
            mixer_.stopKey(command.keyId, -1);
            return;
        case CMD_STOP_OWNER_KEY:
            mixer_.stopKey(command.keyId, command.owner);
            return;
        case CMD_STOP_OWNER:
            mixer_.stopOwner(command.owner);
            return;
        case CMD_STOP_ALL:
            mixer_.stopAll();
            return;
        case CMD_SET_PAUSED:
            return;  /* `paused_` is an atomic and already applied */
        default:
            return;
    }
}

/* --------------------------------------------------------------- rendering */

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream,
                                                   void* audioData,
                                                   int32_t numFrames) {
    if (audioData == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;
    drainCommands();
    if (paused_.load(std::memory_order_acquire)) {
        std::memset(audioData, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);
        return oboe::DataCallbackResult::Continue;
    }

    const int32_t frames = clampFrames(numFrames);
    if (mixCapacityFrames_ < frames) {
        /* A device handed us more frames than the scratch buffer holds: the
         * tail is left silent for one callback instead of overflowing. */
        std::memset(audioData, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);
        return oboe::DataCallbackResult::Continue;
    }

    float* output = static_cast<float*>(audioData);
    std::memset(output, 0, sizeof(float) * static_cast<size_t>(frames) * 2);
    const int32_t outputRate = stream != nullptr
        ? stream->getSampleRate()
        : sampleRate_.load(std::memory_order_acquire);
    mixer_.render(output, frames, outputRate);
    if (frames < numFrames) {
        std::memset(output + frames * 2, 0,
                    sizeof(float) * static_cast<size_t>(numFrames - frames) * 2);
    }
    return oboe::DataCallbackResult::Continue;
}

bool AudioEngine::onError(oboe::AudioStream* /*stream*/, oboe::Result error) {
    if (error != oboe::Result::OK) {
        xruns_.fetch_add(1, std::memory_order_relaxed);
    }
    /* Let Oboe close the broken stream; `onErrorAfterClose` re-opens it. */
    running_.store(false, std::memory_order_release);
    return false;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* failedStream, oboe::Result error) {
    // This runs on Oboe's management thread after the failed stream is closed.
    // Serialize against explicit stop/start, and ignore stale callbacks from a
    // stream that has already been replaced. A user-requested shutdown must not
    // be undone by an asynchronous disconnect callback.
    std::lock_guard<std::mutex> lock(streamMutex_);
    if (stream_ == nullptr || stream_.get() != failedStream) return;
    stream_.reset();
    running_.store(false, std::memory_order_release);
    if (error == oboe::Result::ErrorDisconnected &&
        desiredRunning_.load(std::memory_order_acquire)) {
        openStreamLocked();
    }
}

of_stream_info AudioEngine::info() const {
    of_stream_info result{};
    result.sample_rate = sampleRate_.load(std::memory_order_acquire);
    result.frames_per_burst = framesPerBurst_.load(std::memory_order_acquire);
    result.channels = 2;
    result.is_float = 1;
    result.is_low_latency = lowLatency_.load(std::memory_order_acquire) ? 1 : 0;
    result.active_voices = mixer_.activeVoices();
    result.peak_voices = mixer_.peakVoices();
    result.xrun_count = xruns_.load(std::memory_order_relaxed);
    return result;
}

}  // namespace openfire
