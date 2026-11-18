/*
 * The C binding of the Open Fire engine.
 *
 * Every function here is a straight translation of one call into the C++
 * engine, so the JNI layer (and any host test) can drive the engine without
 * ever seeing a C++ type. Errors never throw: a null handle is a harmless
 * no-op and a value of 0/false reports the failure.
 */
#include "openfire/of_audio.h"

#include <memory>

#include "AudioEngine.h"

struct of_engine {
    std::shared_ptr<openfire::AudioEngine> engine;
};

extern "C" {

of_engine* of_engine_create(void) {
    auto handle = new (std::nothrow) of_engine();
    if (handle == nullptr) return nullptr;
    handle->engine = std::make_shared<openfire::AudioEngine>();
    if (handle->engine == nullptr) {
        delete handle;
        return nullptr;
    }
    return handle;
}

void of_engine_destroy(of_engine* engine) {
    if (engine == nullptr) return;
    engine->engine.reset();
    delete engine;
}

int32_t of_engine_start(of_engine* engine) {
    if (engine == nullptr) return -1;
    return engine->engine->start();
}

void of_engine_stop_stream(of_engine* engine) {
    if (engine == nullptr) return;
    engine->engine->stopStream();
}

int32_t of_engine_is_running(of_engine* engine) {
    if (engine == nullptr) return 0;
    return engine->engine->running() ? 1 : 0;
}

int32_t of_engine_load_clip(of_engine* engine, const int16_t* samples, int32_t frames,
                            int32_t channels, int32_t sample_rate) {
    if (engine == nullptr) return 0;
    return engine->engine->loadClip(samples, frames, channels, sample_rate);
}

void of_engine_unload_clip(of_engine* engine, int32_t clip_id) {
    if (engine == nullptr || clip_id <= 0) return;
    engine->engine->unloadClip(clip_id);
}

int32_t of_engine_play(of_engine* engine, const of_voice_params* params) {
    if (engine == nullptr || params == nullptr) return 0;
    return engine->engine->play(*params);
}

void of_engine_update_voice(of_engine* engine, int32_t voice_id, const of_voice_params* params) {
    if (engine == nullptr || params == nullptr || voice_id <= 0) return;
    engine->engine->update(voice_id, *params);
}

void of_engine_stop(of_engine* engine, int32_t voice_id) {
    if (engine == nullptr || voice_id <= 0) return;
    engine->engine->stop(voice_id);
}

void of_engine_stop_key(of_engine* engine, int32_t key_id) {
    if (engine == nullptr) return;
    engine->engine->stopKey(key_id, -1);
}

void of_engine_stop_owner_key(of_engine* engine, int32_t owner, int32_t key_id) {
    if (engine == nullptr) return;
    engine->engine->stopKey(key_id, owner);
}

void of_engine_stop_owner(of_engine* engine, int32_t owner) {
    if (engine == nullptr) return;
    engine->engine->stopOwner(owner);
}

void of_engine_stop_all(of_engine* engine) {
    if (engine == nullptr) return;
    engine->engine->stopAll();
}

void of_engine_set_bus_gain(of_engine* engine, int32_t bus, float gain) {
    if (engine == nullptr) return;
    engine->engine->setBusGain(bus, gain);
}

void of_engine_set_master_gain(of_engine* engine, float gain) {
    if (engine == nullptr) return;
    engine->engine->setMasterGain(gain);
}

void of_engine_set_paused(of_engine* engine, int32_t paused) {
    if (engine == nullptr) return;
    engine->engine->setPaused(paused != 0);
}

int32_t of_engine_is_key_sounding(of_engine* engine, int32_t key_id) {
    if (engine == nullptr) return 0;
    return engine->engine->isKeySounding(key_id) ? 1 : 0;
}

of_stream_info of_engine_info(of_engine* engine) {
    if (engine == nullptr) {
        of_stream_info empty{};
        return empty;
    }
    return engine->engine->info();
}

}  // extern "C"
