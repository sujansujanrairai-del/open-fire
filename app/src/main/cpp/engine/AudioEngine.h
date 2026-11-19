/*
 * The engine object behind every `of_engine*` call.
 *
 * Layout
 * ------
 *  - `Command` is a POD record of one request from the game thread. Commands
 *    travel through a bounded lock-free multi-producer/single-consumer ring,
 *    so the audio callback never takes a lock and never allocates.
 *  - The Oboe stream is opened on demand (`start`) and *supervised* for the
 *    whole life of the engine: a route change, a vendor stack that reclaims
 *    the device, or a stream that simply goes quiet is noticed by a dedicated
 *    supervisor thread, which reopens the stream without ever blocking the
 *    audio callback, the game thread or the UI thread. This is what removes
 *    the "the game is silent until it is restarted" failure that an unhandled
 *    stream error used to cause.
 *  - Mixer, ClipStore and the stream are the only members; the C API in
 *    AudioEngine.cpp is a thin translation layer over this class.
 *
 * Latency contract
 * ----------------
 * The stream is a low-latency float stereo output whose buffer is pinned to
 * twice the burst size (the configuration Google recommends for games). The
 * callback renders whatever frame count Oboe asks for, straight into the
 * device buffer, and drains the command ring at the start of every callback.
 * A voice posted by the game thread therefore starts within one burst -
 * typically 2-10 ms - and a stop is heard as a 12 ms fade.
 */
#ifndef OPENFIRE_AUDIO_ENGINE_H
#define OPENFIRE_AUDIO_ENGINE_H

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include <cstring>
#include <oboe/Oboe.h>

#include "openfire/of_audio.h"

#include "AudioMath.h"
#include "ClipStore.h"
#include "Mixer.h"
#include "RingBuffer.h"

namespace openfire {

/** Commands the game thread may post to the audio thread. */
enum CommandType {
    CMD_NONE = 0,
    CMD_PLAY,
    CMD_UPDATE,
    CMD_STOP,
    CMD_STOP_KEY,
    CMD_STOP_OWNER_KEY,
    CMD_STOP_OWNER,
    CMD_STOP_ALL,
    CMD_SET_PAUSED
};

struct Command {
    int32_t type = CMD_NONE;
    int32_t voiceId = 0;
    int32_t keyId = 0;
    int32_t owner = 0;
    int32_t paused = 0;
    /* When the game thread posted it. A command that waited longer than a
     * frame or two is stale: playing it then would drop a sound into the mix
     * seconds after the interaction that asked for it. */
    int64_t postedAtMs = 0;
    of_voice_params params{};
};

/** Output sample rate the engine asks for; Oboe may hand back another one. */
constexpr int32_t kPreferredSampleRate = 48000;

/** Command ring capacity: comfortably more than one callback can consume. */
constexpr size_t kCommandCapacity = 4096;

/** How often the supervisor checks the stream, in milliseconds. */
constexpr int32_t kSupervisorIntervalMs = 250;

/** A stream that has produced no callback for this long is considered dead. */
constexpr int32_t kSilenceLimitMs = 1500;

/** Never reopen the stream more often than this, in milliseconds. */
constexpr int32_t kReopenCooldownMs = 400;

/**
 * A one-shot that has waited longer than this is dropped instead of played.
 *
 * The engine can legitimately be unable to render for a moment (a route
 * change, an app returning from the background, the first stream opening).
 * Without this limit every shot fired during that moment would be heard at
 * once when the stream comes back - the "the sound arrived three seconds
 * late" failure - instead of being forgotten, as a game should.
 */
constexpr int64_t kMaxOneShotAgeMs = 300;

/** A held loop may wait a little longer: it is meant to keep sounding. */
constexpr int64_t kMaxLoopAgeMs = 2000;

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    /* ------------------------------------------------------------ lifecycle */

    /** Opens and starts the stream. Idempotent; returns 0 on success. */
    int32_t start();
    void stopStream();

    bool running() const { return running_.load(std::memory_order_acquire); }

    /* --------------------------------------------------------------- clips */

    int32_t loadClip(const int16_t* samples, int32_t frames, int32_t channels, int32_t sampleRate);
    void unloadClip(int32_t clipId);

    /* -------------------------------------------------------------- voices */

    int32_t play(const of_voice_params& params);
    void update(int32_t voiceId, const of_voice_params& params);
    void stop(int32_t voiceId);
    void stopKey(int32_t keyId, int32_t owner /* -1 any */);
    void stopOwner(int32_t owner);
    void stopAll();

    /* ---------------------------------------------------------------- buses */

    void setBusGain(int32_t bus, float gain) { mixer_.setBusGain(bus, gain); }
    void setMasterGain(float gain) { mixer_.setMasterGain(gain); }
    void setPaused(bool paused);

    /** True for the headphone model, false for the loudspeaker (crossfeed) one. */
    void setOutputMode(bool headphones) { mixer_.setHeadphones(headphones); }
    bool headphones() const { return mixer_.headphones(); }

    bool isKeySounding(int32_t keyId) const { return mixer_.isKeySounding(keyId); }

    bool isVoiceSounding(int32_t voiceId) const { return mixer_.isVoiceSounding(voiceId); }

    of_stream_info info() const;

    /* -------------------------------------------------------- Oboe callbacks */

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override;

    bool onError(oboe::AudioStream* stream, oboe::Result error) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    void post(const Command& command);
    void drainCommands();
    void apply(const Command& command);
    void startVoice(int32_t voiceId, const of_voice_params& params);
    int32_t nextVoiceId();
    bool openStreamLocked();
    void closeStreamLocked();
    void startSupervisor();
    void stopSupervisor();
    void supervisorLoop();
    void renderBlock(void* audioData, int32_t numFrames, int32_t channels, bool isFloat);

    ClipStore clips_;
    Mixer mixer_;
    CommandRing<Command, kCommandCapacity> ring_;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamMutex_;
    std::atomic<bool> running_{false};
    std::atomic<bool> desiredRunning_{false};
    std::atomic<bool> paused_{false};
    std::atomic<bool> shuttingDown_{false};
    std::atomic<bool> reopenRequested_{false};
    std::atomic<int32_t> xruns_{0};
    std::atomic<int32_t> voiceCounter_{1};

    /* Supervisor: reopens a dead or lost stream without blocking anyone. */
    std::thread supervisor_;
    std::mutex supervisorMutex_;
    std::condition_variable supervisorWake_;
    std::atomic<bool> supervisorStop_{false};

    std::atomic<int64_t> lastCallbackMs_{0};
    std::atomic<int64_t> lastReopenMs_{0};
    std::atomic<int32_t> callbacks_{0};

    std::atomic<int32_t> sampleRate_{kPreferredSampleRate};
    std::atomic<int32_t> framesPerBurst_{0};
    std::atomic<int32_t> bufferFrames_{0};
    std::atomic<int32_t> streamChannels_{2};
    std::atomic<bool> streamFloat_{true};
    std::atomic<bool> lowLatency_{false};

    /* Render scratch for a stream that is not float stereo (mono device or a
     * vendor stack that ignores the requested format). Pre-allocated, so the
     * audio callback never allocates. */
    std::vector<float> scratch_;
};

}  // namespace openfire

#endif  // OPENFIRE_AUDIO_ENGINE_H
