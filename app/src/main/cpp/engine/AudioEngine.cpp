#include "AudioEngine.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#define OF_LOG_TAG "OpenFireAudio"
#define OF_LOGI(...) __android_log_print(ANDROID_LOG_INFO, OF_LOG_TAG, __VA_ARGS__)
#define OF_LOGW(...) __android_log_print(ANDROID_LOG_WARN, OF_LOG_TAG, __VA_ARGS__)
#define OF_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, OF_LOG_TAG, __VA_ARGS__)

namespace openfire {

namespace {

int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
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
    voice->targetRate = (params.flags & kFlagApproach) != 0
        ? openfire::approachRate(requested, params.approach)
        : openfire::naturalRate(requested);
    voice->approachProximity = openfire::clamp01(params.approach);
    voice->maxDistance = params.max_distance_m > 0 ? params.max_distance_m : 25;
    if ((params.flags & kFlagDeadline) != 0 && params.deadline_ms > 0) {
        const int64_t frames = static_cast<int64_t>(params.deadline_ms) *
            (sampleRate > 0 ? sampleRate : kPreferredSampleRate) / 1000;
        /* A deadline always ends the voice; never shorten one that is already
         * running because of a later update. */
        if (voice->deadlineFrames == INT64_MAX) voice->deadlineFrames = frames;
    }
}

}  // namespace

AudioEngine::AudioEngine()
    : scratch_(static_cast<size_t>(kMaxBlockFrames) * 2, 0.0f) {
    mixer_.setClipStore(&clips_);
    /* The mixer renders straight into the buffer Oboe hands to the callback,
     * whatever its frame count; the scratch buffer is only used for a stream
     * that is not float stereo. */
}

AudioEngine::~AudioEngine() {
    shuttingDown_.store(true, std::memory_order_release);
    stopStream();
    stopSupervisor();
    mixer_.clear();
    clips_.clear();
}

/* ---------------------------------------------------------------- supervise */

void AudioEngine::startSupervisor() {
    if (supervisor_.joinable()) return;
    supervisorStop_.store(false, std::memory_order_release);
    supervisor_ = std::thread([this] { supervisorLoop(); });
}

void AudioEngine::stopSupervisor() {
    supervisorStop_.store(true, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(supervisorMutex_);
        supervisorWake_.notify_all();
    }
    if (supervisor_.joinable()) {
        if (supervisor_.get_id() == std::this_thread::get_id()) {
            supervisor_.detach();
        } else {
            supervisor_.join();
        }
    }
}

/**
 * The stream watchdog.
 *
 * Two failures are handled here, and both used to end the sound of the whole
 * process:
 *
 *  - Oboe reported an error and closed the stream (`reopenRequested_`), and
 *  - the stream is nominally open but has stopped calling the callback (a
 *    vendor AAudio stack that was reclaimed, an audio route that moved
 *    without a disconnect notification).
 *
 * The reopen itself happens on this thread, never on the audio callback and
 * never on the caller's thread, so neither the mix nor the UI can ever be
 * blocked by it.
 */
void AudioEngine::supervisorLoop() {
    while (!supervisorStop_.load(std::memory_order_acquire) &&
           !shuttingDown_.load(std::memory_order_acquire)) {
        {
            std::unique_lock<std::mutex> lock(supervisorMutex_);
            supervisorWake_.wait_for(lock, std::chrono::milliseconds(kSupervisorIntervalMs));
        }
        if (supervisorStop_.load(std::memory_order_acquire) ||
            shuttingDown_.load(std::memory_order_acquire)) {
            return;
        }
        if (!desiredRunning_.load(std::memory_order_acquire)) continue;

        const bool requested = reopenRequested_.exchange(false, std::memory_order_acq_rel);
        const bool dead = !running_.load(std::memory_order_acquire);
        const int64_t last = lastCallbackMs_.load(std::memory_order_relaxed);
        const bool silent = running_.load(std::memory_order_acquire) && last > 0 &&
            nowMs() - last > kSilenceLimitMs;
        if (!requested && !dead && !silent) continue;

        const int64_t now = nowMs();
        if (now - lastReopenMs_.load(std::memory_order_relaxed) < kReopenCooldownMs) {
            /* Try again on the next tick instead of hammering the device. */
            reopenRequested_.store(true, std::memory_order_release);
            continue;
        }
        lastReopenMs_.store(now, std::memory_order_release);
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (!desiredRunning_.load(std::memory_order_acquire) ||
            shuttingDown_.load(std::memory_order_acquire)) {
            continue;
        }
        if (silent) {
            OF_LOGW("the output stream went quiet for %lld ms; reopening it",
                    static_cast<long long>(now - last));
            closeStreamLocked();
        }
        if (!running_.load(std::memory_order_acquire)) openStreamLocked();
    }
}

/* ------------------------------------------------------------------ stream */

int32_t AudioEngine::start() {
    desiredRunning_.store(true, std::memory_order_release);
    startSupervisor();
    bool started = false;
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (running_.load(std::memory_order_acquire)) return 0;
        started = openStreamLocked();
    }
    if (!started) {
        OF_LOGE("start: the output stream could not be started yet; the "
                "supervisor keeps trying in the background");
        /* Deliberately keep `desiredRunning_` set: the supervisor retries, so a
         * device that needs a moment (or a route that is still settling) still
         * gets sound instead of a permanently silent engine. */
        return -1;
    }
    return 0;
}

bool AudioEngine::openStreamLocked() {
    // Low latency, exclusive mode and a pinned 48 kHz rate are desirable, not
    // prerequisites. A number of devices (notably Bluetooth routes and vendor
    // AAudio stacks) reject that combination. Retry with shared mode, then
    // with the device-native rate, then without low latency at all, before
    // declaring audio unavailable. Keep a fresh builder per attempt because
    // openStream may mutate its negotiated configuration.
    struct Attempt {
        oboe::SharingMode sharing;
        oboe::PerformanceMode performance;
        int32_t sampleRate;
        oboe::ChannelCount channels;
    };
    constexpr Attempt attempts[] = {
        {oboe::SharingMode::Exclusive, oboe::PerformanceMode::LowLatency, kPreferredSampleRate,
         oboe::ChannelCount::Stereo},
        {oboe::SharingMode::Shared, oboe::PerformanceMode::LowLatency, kPreferredSampleRate,
         oboe::ChannelCount::Stereo},
        {oboe::SharingMode::Shared, oboe::PerformanceMode::LowLatency, oboe::kUnspecified,
         oboe::ChannelCount::Stereo},
        {oboe::SharingMode::Shared, oboe::PerformanceMode::None, oboe::kUnspecified,
         oboe::ChannelCount::Stereo},
        /* Last resort: a mono stream. The callback down-mixes into it, so a
         * device that refuses stereo still gets every sound. */
        {oboe::SharingMode::Shared, oboe::PerformanceMode::None, oboe::kUnspecified,
         oboe::ChannelCount::Mono}
    };

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = oboe::Result::ErrorBase;
    for (const auto& attempt : attempts) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(attempt.performance)
            ->setSharingMode(attempt.sharing)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(attempt.channels)
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
        OF_LOGW("open: attempt (sharing %d, performance %d, rate %d) failed with %d",
                static_cast<int>(attempt.sharing),
                static_cast<int>(attempt.performance),
                attempt.sampleRate,
                static_cast<int>(result));
    }
    if (result != oboe::Result::OK || stream == nullptr) {
        OF_LOGE("open: no output stream could be opened, so the game is silent "
                "for now (AAudio/OpenSL ES refused every configuration)");
        running_.store(false, std::memory_order_release);
        return false;
    }

    const int32_t rate = stream->getSampleRate();
    const int32_t framesPerBurst = stream->getFramesPerBurst();
    const int32_t channels = stream->getChannelCount();
    const bool isFloat = stream->getFormat() == oboe::AudioFormat::Float;

    /*
     * The buffer size is the whole latency budget of the engine: audio written
     * into a buffer of N milliseconds is heard N milliseconds later. Google's
     * own guidance for games is to pin the buffer to exactly two bursts - some
     * vendor AAudio stacks default to dozens of bursts, which is what made
     * every shot arrive a fraction of a second late. The size is never
     * increased here, only trimmed, so a device that already negotiated a
     * tiny buffer keeps it.
     */
    if (framesPerBurst > 0) {
        const int32_t target = framesPerBurst * 2;
        const int32_t current = stream->getBufferSizeInFrames();
        if (current != target) {
            auto set = stream->setBufferSizeInFrames(target);
            if (set.error() != oboe::Result::OK) {
                OF_LOGW("open: could not pin the buffer to %d frames (%d); keeping %d",
                        target, static_cast<int>(set.error()), current);
            }
        }
    }

    sampleRate_.store(rate, std::memory_order_release);
    framesPerBurst_.store(framesPerBurst, std::memory_order_release);
    bufferFrames_.store(stream->getBufferSizeInFrames(), std::memory_order_release);
    streamChannels_.store(channels > 0 ? channels : 2, std::memory_order_release);
    streamFloat_.store(isFloat, std::memory_order_release);
    const bool lowLatency =
        stream->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
    lowLatency_.store(lowLatency, std::memory_order_release);
    mixer_.resetCrossfeed();

    stream_ = stream;
    lastCallbackMs_.store(nowMs(), std::memory_order_release);
    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        OF_LOGE("open: the stream opened at %d Hz but requestStart failed with %d",
                rate, static_cast<int>(result));
        auto failed = stream_;
        stream_.reset();
        failed->close();
        running_.store(false, std::memory_order_release);
        return false;
    }
    running_.store(true, std::memory_order_release);
    const float latencyMs = rate > 0
        ? 1000.0f * static_cast<float>(stream->getBufferSizeInFrames()) / static_cast<float>(rate)
        : 0.0f;
    OF_LOGI("open: streaming %d Hz, %d frames per burst, %d channels, %s%s, "
            "buffer %d frames (%.1f ms)",
            rate, framesPerBurst, channels, isFloat ? "float" : "int16",
            lowLatency ? ", low latency" : ", shared",
            stream->getBufferSizeInFrames(), latencyMs);
    return true;
}

void AudioEngine::closeStreamLocked() {
    auto stream = stream_;
    stream_.reset();
    running_.store(false, std::memory_order_release);
    if (stream != nullptr) {
        stream->requestStop();
        stream->close();
    }
    mixer_.clear();
    mixer_.resetCrossfeed();
}

void AudioEngine::stopStream() {
    desiredRunning_.store(false, std::memory_order_release);
    std::lock_guard<std::mutex> lock(streamMutex_);
    closeStreamLocked();
}

/* ------------------------------------------------------------------- clips */

int32_t AudioEngine::loadClip(const int16_t* samples, int32_t frames, int32_t channels,
                              int32_t sampleRate) {
    if (samples == nullptr || frames <= 0 || channels <= 0 || channels > 8) return 0;
    const size_t expected = static_cast<size_t>(frames) * static_cast<size_t>(channels);

    std::vector<int16_t> copy(expected);
    std::memcpy(copy.data(), samples, expected * sizeof(int16_t));

    /* Resample to the output rate once, here, so the mixer never resamples. */
    const int32_t outputRate = sampleRate_.load(std::memory_order_acquire);
    const int32_t target = outputRate > 0 ? outputRate : kPreferredSampleRate;
    if (sampleRate <= 0 || sampleRate == target) {
        return clips_.add(frames, channels, std::move(copy));
    }

    const int64_t scaled = static_cast<int64_t>(frames) * target / sampleRate;
    if (scaled <= 0) return 0;
    const int32_t outFrames = static_cast<int32_t>(std::min<int64_t>(scaled, INT32_MAX));
    std::vector<int16_t> resampled(static_cast<size_t>(outFrames) * channels);
    const double ratio = static_cast<double>(sampleRate) / static_cast<double>(target);
    for (int32_t frame = 0; frame < outFrames; ++frame) {
        const double source = frame * ratio;
        const int32_t index = static_cast<int32_t>(source);
        if (index < 0 || index >= frames) break;
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
    Command stamped = command;
    stamped.postedAtMs = nowMs();
    if (!ring_.try_push(stamped)) {
        OF_LOGW("the command ring is full; one control request was dropped");
    }
}

void AudioEngine::drainCommands() {
    Command command;
    const int64_t now = nowMs();
    int32_t stale = 0;
    while (ring_.try_pop(&command)) {
        if (command.type == CMD_PLAY && command.postedAtMs > 0) {
            const int64_t age = now - command.postedAtMs;
            const bool held = (command.params.flags & kFlagLoop) != 0;
            if (age > (held ? kMaxLoopAgeMs : kMaxOneShotAgeMs)) {
                /* Too old to belong to the interaction that asked for it. */
                ++stale;
                continue;
            }
        }
        apply(command);
    }
    if (stale > 0) {
        OF_LOGW("%d stale sound request(s) dropped instead of played late", stale);
    }
}

void AudioEngine::startVoice(int32_t voiceId, const of_voice_params& params) {
    const int32_t sampleRate = sampleRate_.load(std::memory_order_acquire);
    const Clip* clip = clips_.get(params.clip_id);
    if (clip == nullptr) return;
    Voice* voice = mixer_.allocate(voiceId, params.key_id,
                                   params.owner, params.flags, clip);
    if (voice == nullptr) {
        OF_LOGW("the voice pool is full of critical loops; one shot was dropped");
        return;
    }
    const int32_t rate = sampleRate > 0 ? sampleRate : kPreferredSampleRate;
    const int32_t offsetFrames = params.start_offset_ms > 0
        ? static_cast<int32_t>(
              static_cast<int64_t>(params.start_offset_ms) * rate / 1000) %
              std::max(1, clip->frames())
        : 0;
    const int32_t delayFrames = params.start_delay_ms > 0
        ? static_cast<int32_t>(static_cast<int64_t>(params.start_delay_ms) * rate / 1000)
        : 0;
    voice->position = static_cast<double>(offsetFrames) - delayFrames;
    voice->playedFrames = 0;
    voice->deadlineFrames = INT64_MAX;
    /* Start at the requested level instead of ramping up from silence: a
     * one-shot must be at full level on its very first sample, otherwise every
     * click and every shot begins with an inaudible attack. */
    voice->currentVolume = openfire::clampFloat(params.volume, 0.0f, 8.0f);
    voice->currentPan = openfire::clampFloat(params.pan, -1.0f, 1.0f);
    voice->currentDistance = params.distance > 0.0f ? params.distance : 0.0f;
    voice->currentRear = openfire::clampFloat(params.rear, -1.0f, 1.0f);
    voice->currentRate = params.rate <= 0.0f ? 1.0f : params.rate;
    voice->approachProximity = openfire::clamp01(params.approach);
    voice->maxDistance = params.max_distance_m > 0 ? params.max_distance_m : 25;
    voice->flags = params.flags;
    applyParams(voice, params, rate);
    voice->stopFade = 1.0f;
    voice->stopping = false;
    voice->state = VOICE_ACTIVE;
}

void AudioEngine::apply(const Command& command) {
    const int32_t sampleRate = sampleRate_.load(std::memory_order_acquire);
    switch (command.type) {
        case CMD_PLAY:
            startVoice(command.voiceId, command.params);
            return;
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

/**
 * Renders one callback.
 *
 * The fast path - a float stereo stream - writes the mix straight into the
 * device buffer. Anything else (a vendor stack that opened mono, or int16
 * despite the request) is rendered into the pre-allocated scratch buffer and
 * converted here, in fixed chunks, so the callback can never write past the
 * memory the device really handed it.
 */
void AudioEngine::renderBlock(void* audioData, int32_t numFrames, int32_t channels,
                              bool isFloat) {
    const int32_t rate = sampleRate_.load(std::memory_order_acquire);
    const int32_t safeRate = rate > 0 ? rate : kPreferredSampleRate;

    if (isFloat && channels == 2 && numFrames <= kMaxBlockFrames) {
        float* output = static_cast<float*>(audioData);
        std::memset(output, 0, sizeof(float) * static_cast<size_t>(numFrames) * 2);
        mixer_.render(output, numFrames, safeRate);
        return;
    }

    int32_t remaining = numFrames;
    while (remaining > 0) {
        const int32_t chunk = std::min(remaining, kMaxBlockFrames);
        float* scratch = scratch_.data();
        std::memset(scratch, 0, sizeof(float) * static_cast<size_t>(chunk) * 2);
        mixer_.render(scratch, chunk, safeRate);
        const int32_t offset = numFrames - remaining;
        if (isFloat) {
            float* output = static_cast<float*>(audioData);
            for (int32_t frame = 0; frame < chunk; ++frame) {
                for (int32_t channel = 0; channel < channels; ++channel) {
                    const int32_t source = channel == 0 ? 0 : 1;
                    output[static_cast<size_t>(offset + frame) * channels + channel] =
                        scratch[frame * 2 + source];
                }
            }
        } else {
            int16_t* output = static_cast<int16_t*>(audioData);
            for (int32_t frame = 0; frame < chunk; ++frame) {
                for (int32_t channel = 0; channel < channels; ++channel) {
                    const int32_t source = channel == 0 ? 0 : 1;
                    output[static_cast<size_t>(offset + frame) * channels + channel] =
                        openfire::toPcm16(scratch[frame * 2 + source]);
                }
            }
        }
        remaining -= chunk;
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* /*stream*/,
                                                   void* audioData,
                                                   int32_t numFrames) {
    if (audioData == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Continue;
    if (shuttingDown_.load(std::memory_order_acquire)) {
        return oboe::DataCallbackResult::Stop;
    }

    lastCallbackMs_.store(nowMs(), std::memory_order_relaxed);
    callbacks_.fetch_add(1, std::memory_order_relaxed);

    /* Control requests are drained even while the mix is frozen, so a stop or
     * a new voice is never lost to a paused battle. */
    drainCommands();

    if (paused_.load(std::memory_order_acquire)) {
        const int32_t channels = streamChannels_.load(std::memory_order_acquire);
        const bool isFloat = streamFloat_.load(std::memory_order_acquire);
        const size_t samples = static_cast<size_t>(numFrames) *
            static_cast<size_t>(channels > 0 ? channels : 2);
        std::memset(audioData, 0, samples * (isFloat ? sizeof(float) : sizeof(int16_t)));
        return oboe::DataCallbackResult::Continue;
    }

    renderBlock(audioData, numFrames,
                streamChannels_.load(std::memory_order_acquire),
                streamFloat_.load(std::memory_order_acquire));
    return oboe::DataCallbackResult::Continue;
}

bool AudioEngine::onError(oboe::AudioStream* /*stream*/, oboe::Result error) {
    if (error != oboe::Result::OK) {
        xruns_.fetch_add(1, std::memory_order_relaxed);
        OF_LOGW("stream error %d; letting Oboe close it and asking the "
                "supervisor to reopen a fresh stream", static_cast<int>(error));
    }
    running_.store(false, std::memory_order_release);
    /* Let Oboe close the broken stream; `onErrorAfterClose` runs afterwards. */
    return false;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream* failedStream, oboe::Result error) {
    // This runs on Oboe's management thread after the failed stream is closed.
    // Only drop the matching stream and ask the supervisor to reopen: opening
    // a stream here would block Oboe's own error thread, and a user-requested
    // shutdown must not be undone by an asynchronous disconnect callback.
    {
        std::lock_guard<std::mutex> lock(streamMutex_);
        if (stream_.get() == failedStream) {
            stream_.reset();
            running_.store(false, std::memory_order_release);
            mixer_.clear();
        }
    }
    if (desiredRunning_.load(std::memory_order_acquire) &&
        !shuttingDown_.load(std::memory_order_acquire)) {
        OF_LOGI("the audio route changed or the stream failed (%d); asking the "
                "supervisor for a fresh stream", static_cast<int>(error));
        reopenRequested_.store(true, std::memory_order_release);
        std::lock_guard<std::mutex> lock(supervisorMutex_);
        supervisorWake_.notify_all();
    }
}

of_stream_info AudioEngine::info() const {
    of_stream_info result{};
    result.sample_rate = sampleRate_.load(std::memory_order_acquire);
    result.frames_per_burst = framesPerBurst_.load(std::memory_order_acquire);
    result.channels = streamChannels_.load(std::memory_order_acquire);
    result.is_float = streamFloat_.load(std::memory_order_acquire) ? 1 : 0;
    result.is_low_latency = lowLatency_.load(std::memory_order_acquire) ? 1 : 0;
    result.active_voices = mixer_.activeVoices();
    result.peak_voices = mixer_.peakVoices();
    result.xrun_count = xruns_.load(std::memory_order_relaxed);
    result.buffer_frames = bufferFrames_.load(std::memory_order_acquire);
    result.headphones = mixer_.headphones() ? 1 : 0;
    return result;
}

}  // namespace openfire
