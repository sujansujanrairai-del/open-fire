/*
 * Open Fire native audio engine - stable C binding.
 *
 * This header is the only entry point of the C++ engine. The JNI layer
 * (jni/JniBridge.cpp) speaks to the engine exclusively through these
 * functions, so the mixing core has no knowledge of Java, of JNI or of the
 * Kotlin call sites. That keeps the real-time code small, testable on the
 * host with a fake Oboe backend, and usable from plain C.
 *
 * Threading contract
 * ------------------
 *  - Every function except `of_engine_create`/`of_engine_destroy` may be
 *    called from any thread, including the UI thread. They never block and
 *    never allocate on the audio thread's critical path: they post a POD
 *    command into a lock-free ring buffer that the Oboe callback drains.
 *  - `of_engine_play` returns a voice id immediately. The id is stable for
 *    the life of the voice and is the handle used for updates and stops.
 *  - `of_engine_load_clip` copies the caller's samples into engine-owned
 *    storage. The caller may release its buffer as soon as the call returns.
 *
 * Latency contract
 * ----------------
 * The engine asks Oboe for a low-latency, exclusive, float stream at 48 kHz
 * with stereo output, and it accepts the device's recommended sample rate
 * and frame count instead of forcing a buffer size. Sounds therefore start
 * on the next audio callback (typically 2-10 ms) instead of on the next
 * platform player state change.
 */
#ifndef OPENFIRE_OF_AUDIO_H
#define OPENFIRE_OF_AUDIO_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Opaque engine handle. */
typedef struct of_engine of_engine;

/** Output buses. Gains are applied per bus, then by the master gain. */
enum of_bus {
    OF_BUS_SFX = 0,      /* footsteps, weapons, impacts, vehicles */
    OF_BUS_VOICE = 1,    /* reserved: TTS is spoken by the platform engine */
    OF_BUS_MUSIC = 2,    /* reserved: background music runs on its own player */
    OF_BUS_COUNT = 3
};

/** Bit flags of a voice. */
enum of_voice_flags {
    /** The clip repeats until it is stopped. */
    OF_FLAG_LOOP = 1 << 0,
    /** The clip is interleaved stereo; otherwise it is mono. */
    OF_FLAG_STEREO = 1 << 1,
    /** Apply distance attenuation and stereo placement from pan/distance. */
    OF_FLAG_SPATIAL = 1 << 2,
    /** A one-shot that is faded out after `deadline_ms` (a bounded burst). */
    OF_FLAG_DEADLINE = 1 << 3,
    /** A projectile: a slow pitch rise as it closes in (approach cue). */
    OF_FLAG_APPROACH = 1 << 4,
    /** Never evicted when the voice budget is full (held loops). */
    OF_FLAG_CRITICAL = 1 << 5
};

/** Owner ids, mirroring AudioManager.LOOP_OWNER_*. */
enum of_owner {
    OF_OWNER_NONE = 0,
    OF_OWNER_PLAYER = 1,
    OF_OWNER_ENEMY = 2
};

/**
 * Everything one voice needs. A plain structure so it can travel through the
 * lock-free command queue without allocation.
 */
typedef struct of_voice_params {
    int32_t clip_id;      /* handle returned by of_engine_load_clip */
    int32_t key_id;       /* 32-bit hash of the sound key, for group stops */
    int32_t owner;        /* of_owner */
    int32_t flags;        /* of_voice_flags */
    float volume;         /* 0..1 linear gain of the voice itself */
    float rate;           /* playback rate: 1 = natural pitch */
    float pan;            /* -1 hard left, 0 centre, +1 hard right */
    float distance;       /* metres from the listener (0 = at the ear) */
    float rear;           /* -1 fully in front, 0 side, +1 fully behind */
    float approach;       /* 0..1: how close a projectile is at launch */
    int32_t deadline_ms;  /* OF_FLAG_DEADLINE only: burst length */
    int32_t start_offset_ms; /* loop start offset, so two walkers never lock */
    int32_t start_delay_ms;  /* silent lead-in of a chained sound */
    int32_t max_distance_m;  /* distance beyond which the source is silent */
} of_voice_params;

/** Live stream information, for the diagnostics screen and the guide. */
typedef struct of_stream_info {
    int32_t sample_rate;
    int32_t frames_per_burst;
    int32_t channels;
    int32_t is_float;
    int32_t is_low_latency;   /* 1 when Oboe opened an exclusive low-latency stream */
    int32_t active_voices;
    int32_t peak_voices;
    int32_t xrun_count;
} of_stream_info;

/** Creates an engine. Returns NULL when memory is exhausted. */
of_engine* of_engine_create(void);

/** Stops every voice, closes the stream and frees the engine. */
void of_engine_destroy(of_engine* engine);

/**
 * Copies `frames * channels` interleaved 16-bit samples into the engine.
 * Returns a clip id > 0, or 0 on failure.
 */
int32_t of_engine_load_clip(of_engine* engine,
                            const int16_t* samples,
                            int32_t frames,
                            int32_t channels,
                            int32_t sample_rate);

/** Drops a clip. Voices still playing it finish on their own copy. */
void of_engine_unload_clip(of_engine* engine, int32_t clip_id);

/** Starts a voice and returns its id (> 0), or 0 when it could not start. */
int32_t of_engine_play(of_engine* engine, const of_voice_params* params);

/** Updates the live parameters of a voice (gains, pan, distance, rate). */
void of_engine_update_voice(of_engine* engine, int32_t voice_id, const of_voice_params* params);

/** Fades one voice out. */
void of_engine_stop(of_engine* engine, int32_t voice_id);

/** Fades out every voice of one sound key. */
void of_engine_stop_key(of_engine* engine, int32_t key_id);

/** Fades out one owner's voices of one sound key (the other fighter keeps his). */
void of_engine_stop_owner_key(of_engine* engine, int32_t owner, int32_t key_id);

/** Fades out every voice of one owner. */
void of_engine_stop_owner(of_engine* engine, int32_t owner);

/** Fades out every voice. */
void of_engine_stop_all(of_engine* engine);

/** Sets the gain of one bus (0..1). */
void of_engine_set_bus_gain(of_engine* engine, int32_t bus, float gain);

/** Sets the master gain (0..1). */
void of_engine_set_master_gain(of_engine* engine, float gain);

/** Freezes (paused != 0) or resumes the mix without closing the stream. */
void of_engine_set_paused(of_engine* engine, int32_t paused);

/** Opens and starts the Oboe stream. Idempotent. Returns 0 on success. */
int32_t of_engine_start(of_engine* engine);

/** Stops and closes the Oboe stream (the engine can be started again). */
void of_engine_stop_stream(of_engine* engine);

/** True (1) while a voice of this sound key is still sounding. */
int32_t of_engine_is_key_sounding(of_engine* engine, int32_t key_id);

/** Reads the current stream information. */
of_stream_info of_engine_info(of_engine* engine);

/** True (1) when the stream is running. */
int32_t of_engine_is_running(of_engine* engine);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif /* OPENFIRE_OF_AUDIO_H */
