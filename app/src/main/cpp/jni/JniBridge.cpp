/*
 * The JNI layer of the Open Fire engine.
 *
 * Only `JNI_OnLoad` is exported; every Java method is bound through
 * `RegisterNatives`, and the class/method ids are cached once, so a play call
 * during gameplay does no name lookup at all.
 *
 * The bridge is deliberately thin: it converts Kotlin's arguments into the
 * C structures of `of_audio.h`, calls the engine, and converts the status
 * structure back into a Kotlin object. Every function is null-safe and never
 * lets an exception escape into the audio or Kotlin thread.
 */
#include <jni.h>

#include <cstdint>
#include <cstring>
#include <memory>

#include "openfire/of_audio.h"

namespace {

constexpr const char* kEngineClass = "com/blindtechnexus/openfire/audio/native/OpenFireAudioEngine";
constexpr const char* kInfoClass = "com/blindtechnexus/openfire/audio/native/NativeStreamInfo";

/** Cached ids of the two Kotlin classes the bridge touches. */
struct JniCache {
    jclass engineClass = nullptr;
    jclass infoClass = nullptr;
    jmethodID infoConstructor = nullptr;
};

JniCache gCache;

of_engine* fromHandle(jlong handle) {
    return reinterpret_cast<of_engine*>(static_cast<intptr_t>(handle));
}

jlong toHandle(of_engine* engine) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(engine));
}

/** Reads a Kotlin `IntArray` of size 15 into the C voice structure. */
void readVoiceParams(JNIEnv* env, jintArray array, of_voice_params* params) {
    if (array == nullptr || params == nullptr) return;
    jint values[15] = {0};
    const jsize length = env->GetArrayLength(array);
    env->GetIntArrayRegion(array, 0, length > 15 ? 15 : length, values);
    params->clip_id = values[0];
    params->key_id = values[1];
    params->owner = values[2];
    params->flags = values[3];
    params->volume = static_cast<jint>(values[4]) / 10000.0f;
    params->rate = static_cast<jint>(values[5]) / 10000.0f;
    params->pan = static_cast<jint>(values[6]) / 10000.0f;
    params->distance = static_cast<jint>(values[7]) / 100.0f;
    params->rear = static_cast<jint>(values[8]) / 10000.0f;
    params->approach = static_cast<jint>(values[9]) / 10000.0f;
    params->deadline_ms = values[10];
    params->start_offset_ms = values[11];
    params->max_distance_m = values[12];
    params->start_delay_ms = values[13];
    /* values[14] is reserved for a future cue. */
}

/* --------------------------------------------------------------- natives */

jlong nativeCreate(JNIEnv* /*env*/, jclass /*clazz*/) {
    return toHandle(of_engine_create());
}

void nativeDestroy(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    of_engine_destroy(fromHandle(handle));
}

jint nativeStart(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    return of_engine_start(fromHandle(handle));
}

void nativeStopStream(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    of_engine_stop_stream(fromHandle(handle));
}

jboolean nativeIsRunning(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    return of_engine_is_running(fromHandle(handle)) != 0 ? JNI_TRUE : JNI_FALSE;
}

jint nativeLoadClip(JNIEnv* env, jclass /*clazz*/, jlong handle,
                    jshortArray samples, jint frames, jint channels, jint sampleRate) {
    if (samples == nullptr) return 0;
    const jsize length = env->GetArrayLength(samples);
    if (length <= 0) return 0;
    jshort* buffer = env->GetShortArrayElements(samples, nullptr);
    if (buffer == nullptr) return 0;
    const jint clip = of_engine_load_clip(fromHandle(handle), buffer, frames, channels, sampleRate);
    env->ReleaseShortArrayElements(samples, buffer, JNI_ABORT);
    return clip;
}

void nativeUnloadClip(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint clipId) {
    of_engine_unload_clip(fromHandle(handle), clipId);
}

jint nativePlay(JNIEnv* env, jclass /*clazz*/, jlong handle, jintArray params) {
    of_voice_params voice{};
    readVoiceParams(env, params, &voice);
    return of_engine_play(fromHandle(handle), &voice);
}

void nativeUpdate(JNIEnv* env, jclass /*clazz*/, jlong handle, jint voiceId, jintArray params) {
    of_voice_params voice{};
    readVoiceParams(env, params, &voice);
    of_engine_update_voice(fromHandle(handle), voiceId, &voice);
}

void nativeStop(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint voiceId) {
    of_engine_stop(fromHandle(handle), voiceId);
}

void nativeStopKey(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint keyId) {
    of_engine_stop_key(fromHandle(handle), keyId);
}

void nativeStopOwnerKey(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint owner, jint keyId) {
    of_engine_stop_owner_key(fromHandle(handle), owner, keyId);
}

void nativeStopOwner(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint owner) {
    of_engine_stop_owner(fromHandle(handle), owner);
}

void nativeStopAll(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    of_engine_stop_all(fromHandle(handle));
}

void nativeSetBusGain(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint bus, jfloat gain) {
    of_engine_set_bus_gain(fromHandle(handle), bus, gain);
}

void nativeSetMasterGain(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jfloat gain) {
    of_engine_set_master_gain(fromHandle(handle), gain);
}

void nativeSetPaused(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jboolean paused) {
    of_engine_set_paused(fromHandle(handle), paused == JNI_TRUE ? 1 : 0);
}

/**
 * Builds a `NativeStreamInfo` value object. The constructor is cached in
 * `JNI_OnLoad`, so this path does a single NewObject and no lookup.
 */
jobject nativeInfo(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    const of_stream_info info = of_engine_info(fromHandle(handle));
    if (gCache.infoClass == nullptr || gCache.infoConstructor == nullptr) return nullptr;
    return env->NewObject(gCache.infoClass, gCache.infoConstructor,
                          info.sample_rate, info.frames_per_burst, info.channels,
                          info.is_float != 0, info.is_low_latency != 0,
                          info.active_voices, info.peak_voices, info.xrun_count);
}

/** Voices the mixer is rendering right now. */
jint nativeActiveVoices(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    return of_engine_info(fromHandle(handle)).active_voices;
}

/** True while a voice of this sound key is still in the pool. */
jboolean nativeIsKeySounding(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint keyId) {
    return of_engine_is_key_sounding(fromHandle(handle), keyId) != 0 ? JNI_TRUE : JNI_FALSE;
}

JNINativeMethod gMethods[] = {
    /* name, signature, function pointer (JNI mutates neither string). */
    {const_cast<char*>("create"), const_cast<char*>("()J"),
     reinterpret_cast<void*>(nativeCreate)},
    {const_cast<char*>("destroy"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(nativeDestroy)},
    {const_cast<char*>("start"), const_cast<char*>("(J)I"),
     reinterpret_cast<void*>(nativeStart)},
    {const_cast<char*>("stopStream"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(nativeStopStream)},
    {const_cast<char*>("isRunning"), const_cast<char*>("(J)Z"),
     reinterpret_cast<void*>(nativeIsRunning)},
    {const_cast<char*>("loadClip"), const_cast<char*>("(J[SIII)I"),
     reinterpret_cast<void*>(nativeLoadClip)},
    {const_cast<char*>("unloadClip"), const_cast<char*>("(JI)V"),
     reinterpret_cast<void*>(nativeUnloadClip)},
    {const_cast<char*>("play"), const_cast<char*>("(J[I)I"),
     reinterpret_cast<void*>(nativePlay)},
    {const_cast<char*>("update"), const_cast<char*>("(JI[I)V"),
     reinterpret_cast<void*>(nativeUpdate)},
    {const_cast<char*>("stop"), const_cast<char*>("(JI)V"),
     reinterpret_cast<void*>(nativeStop)},
    {const_cast<char*>("stopKey"), const_cast<char*>("(JI)V"),
     reinterpret_cast<void*>(nativeStopKey)},
    {const_cast<char*>("stopOwnerKey"), const_cast<char*>("(JII)V"),
     reinterpret_cast<void*>(nativeStopOwnerKey)},
    {const_cast<char*>("stopOwner"), const_cast<char*>("(JI)V"),
     reinterpret_cast<void*>(nativeStopOwner)},
    {const_cast<char*>("stopAll"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(nativeStopAll)},
    {const_cast<char*>("setBusGain"), const_cast<char*>("(JIF)V"),
     reinterpret_cast<void*>(nativeSetBusGain)},
    {const_cast<char*>("setMasterGain"), const_cast<char*>("(JF)V"),
     reinterpret_cast<void*>(nativeSetMasterGain)},
    {const_cast<char*>("setPaused"), const_cast<char*>("(JZ)V"),
     reinterpret_cast<void*>(nativeSetPaused)},
    {const_cast<char*>("info"), const_cast<char*>("(J)Lcom/blindtechnexus/openfire/audio/native/NativeStreamInfo;"),
     reinterpret_cast<void*>(nativeInfo)},
    {const_cast<char*>("activeVoices"), const_cast<char*>("(J)I"),
     reinterpret_cast<void*>(nativeActiveVoices)},
    {const_cast<char*>("isKeySounding"), const_cast<char*>("(JI)Z"),
     reinterpret_cast<void*>(nativeIsKeySounding)},
};

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass engineClass = env->FindClass(kEngineClass);
    if (engineClass == nullptr) return JNI_ERR;
    gCache.engineClass = static_cast<jclass>(env->NewGlobalRef(engineClass));
    env->DeleteLocalRef(engineClass);
    if (gCache.engineClass == nullptr) return JNI_ERR;

    if (env->RegisterNatives(gCache.engineClass, gMethods,
                             static_cast<jint>(sizeof(gMethods) / sizeof(gMethods[0]))) != JNI_OK) {
        return JNI_ERR;
    }

    jclass infoClass = env->FindClass(kInfoClass);
    if (infoClass == nullptr) return JNI_ERR;
    gCache.infoClass = static_cast<jclass>(env->NewGlobalRef(infoClass));
    env->DeleteLocalRef(infoClass);
    if (gCache.infoClass == nullptr) return JNI_ERR;

    const jmethodID constructor = env->GetMethodID(gCache.infoClass, "<init>", "(IIIIZIII)V");
    if (constructor == nullptr) return JNI_ERR;
    gCache.infoConstructor = constructor;

    return JNI_VERSION_1_6;
}
