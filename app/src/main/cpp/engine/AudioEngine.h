/*
 * The engine object behind every `of_engine*` call.
 *
 * Layout
 * ------
 *  - `Command` is a POD record of one request from the game thread. Commands
 *    travel through a bounded lock-free multi-producer/single-consumer ring,
 *    so the audio callback never takes a lock and never allocates.
 *  - The Oboe stream is opened on demand (`start`) and re-opened by the error
 *    callback when the device changes (a headset is plugged in, the route
 *    switches to Bluetooth), which is the one case where a stream really dies.
 *  - Mixer, ClipStore and the stream are the only members; the C API in
 *    AudioEngine.cpp is a thin translation layer over this class.
 */
#ifndef OPENFIRE_AUDIO_ENGINE_H
#define OPENFIRE_AUDIO_ENGINE_H

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
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
    of_voice_params params{};
};

/** Output sample rate the engine asks for; Oboe may hand back another one. */
constexpr int32_t kPreferredSampleRate = 48000;

/** Command ring capacity: comfortably more than one callback can consume. */
constexpr size_t kCommandCapacity = 4096;

class AudioEngine : public oboe::AudioStreamDataCallback,
                    public oboe::AudioStreamErrorCallback,
                    public std::enable_shared_from_this<AudioEngine> {
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

    bool isKeySounding(int32_t keyId) { return mixer_.isKeySounding(keyId); }

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
    int32_t nextVoiceId();
    void openStreamLocked();
    void closeStreamLocked();

    ClipStore clips_;
    Mixer mixer_;
    CommandRing<Command, kCommandCapacity> ring_;

    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex streamMutex_;
    std::atomic<bool> running_{false};
    std::atomic<bool> desiredRunning_{false};
    std::atomic<bool> paused_{false};
    std::atomic<int32_t> xruns_{0};
    std::atomic<int32_t> voiceCounter_{1};

    std::atomic<int32_t> sampleRate_{kPreferredSampleRate};
    std::atomic<int32_t> framesPerBurst_{0};
    std::atomic<bool> lowLatency_{false};

    /* Scratch buffers, sized when the stream opens; only the mixer touches them. */
    std::vector<float> mixBuffer_;
    int32_t mixCapacityFrames_ = 0;

};

}  // namespace openfire

#endif  // OPENFIRE_AUDIO_ENGINE_H
