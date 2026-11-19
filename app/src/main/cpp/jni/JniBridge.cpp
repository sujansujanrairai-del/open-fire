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
 *
 * Load-time contract
 * ------------------
 * `System.loadLibrary` fails - and the whole game loses every effect - when
 * `JNI_OnLoad` returns anything but `JNI_VERSION_1_6`. The two things that can
 * make it fail are a wrong method descriptor and a class that cannot be found,
 * so this file:
 *
 *  - spells every descriptor out as one named constant, next to the Kotlin
 *    declaration it mirrors (a Kotlin `Boolean` is `Z`, not `I`; a Kotlin
 *    `Int` is `I`),
 *  - binds the natives to the class that actually declares them (the engine
 *    class, or its companion object when the compiler places them there),
 *  - treats the *diagnostics* value class as optional: a missing
 *    `NativeStreamInfo` disables the diagnostics line, never the audio, and
 *  - logs every failure with `__android_log_print`, so a silent device shows
 *    the reason in logcat instead of playing nothing.
 */
#include <jni.h>

#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <memory>

#include "openfire/of_audio.h"

#define OF_LOG_TAG "OpenFireJni"
#define OF_LOGI(...) __android_log_print(ANDROID_LOG_INFO, OF_LOG_TAG, __VA_ARGS__)
#define OF_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, OF_LOG_TAG, __VA_ARGS__)

namespace {

/** `com.blindtechnexus.openfire.audio.native.OpenFireAudioEngine`. */
constexpr const char* kEngineClass =
    "com/blindtechnexus/openfire/audio/native/OpenFireAudioEngine";

/**
 * The companion object of the engine. Kotlin may declare an `external fun`
 * of a companion either as a static method of the class or as a method of
 * `OpenFireAudioEngine$Companion`; the bridge binds whichever one exists.
 */
constexpr const char* kCompanionClass =
    "com/blindtechnexus/openfire/audio/native/OpenFireAudioEngine$Companion";

/** `com.blindtechnexus.openfire.audio.native.NativeStreamInfo`. */
constexpr const char* kInfoClass =
    "com/blindtechnexus/openfire/audio/native/NativeStreamInfo";

/**
 * Constructor descriptor of `NativeStreamInfo`:
 *
 *   NativeStreamInfo(
 *       sampleRate: Int, framesPerBurst: Int, channels: Int,
 *       isFloat: Boolean, isLowLatency: Boolean,
 *       activeVoices: Int, peakVoices: Int, xrunCount: Int,
 *       bufferFrames: Int, headphones: Boolean
 *   )
 *
 * `Int` is `I` and Kotlin's `Boolean` is `Z`, so the two flags in the middle
 * are `ZZ` - not `IZ`, and not `II` - the four voice counters are `IIII` and
 * the last flag is `Z`. This constant must stay in lockstep with the Kotlin declaration:
 * when it drifts, `GetMethodID` returns null, the diagnostics value object
 * disappears (the audio itself keeps working, because the value object is
 * optional by design).
 */
constexpr const char* kInfoConstructorSignature = "(IIIZZIIIIZ)V";

/** Cached ids of the two Kotlin classes the bridge touches. */
struct JniCache {
    jclass engineClass = nullptr;   /* the class Kotlin loads the library from */
    jclass engineTarget = nullptr;  /* the class the natives are bound to */
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

/**
 * Finds a class and keeps a global reference to it, or null.
 *
 * `FindClass` leaves a pending `ClassNotFoundException` behind when it fails.
 * The exception is cleared here on purpose: a pending exception would travel
 * out of `JNI_OnLoad` and surface from `System.loadLibrary` as an unrelated
 * error, hiding the real reason.
 */
jclass toGlobalClass(JNIEnv* env, const char* name) {
    jclass local = env->FindClass(name);
    if (local == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }
    auto global = static_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (global == nullptr && env->ExceptionCheck()) env->ExceptionClear();
    return global;
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
    if (samples == nullptr || frames <= 0 || channels <= 0 || channels > 8) return 0;
    const jsize length = env->GetArrayLength(samples);
    if (length <= 0) return 0;
    /* The engine copies `frames * channels` samples: never read past the
     * array, whatever the caller says the geometry is. */
    const jlong expected = static_cast<jlong>(frames) * static_cast<jlong>(channels);
    if (expected > static_cast<jlong>(length)) {
        OF_LOGE("loadClip: %lld samples requested but the array holds %d",
                static_cast<long long>(expected), static_cast<int>(length));
        return 0;
    }
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

/** Chooses the binaural (headphone) or the crossfeed (speaker) spatial model. */
void nativeSetOutputMode(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle,
                         jboolean headphones) {
    of_engine_set_output_mode(fromHandle(handle), headphones == JNI_TRUE ? 1 : 0);
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
                          info.active_voices, info.peak_voices, info.xrun_count,
                          info.buffer_frames, info.headphones != 0);
}

/** Voices the mixer is rendering right now. */
jint nativeActiveVoices(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    return of_engine_info(fromHandle(handle)).active_voices;
}

/** True while a voice of this sound key is still in the pool. */
jboolean nativeIsKeySounding(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint keyId) {
    return of_engine_is_key_sounding(fromHandle(handle), keyId) != 0 ? JNI_TRUE : JNI_FALSE;
}

/** True while this exact voice id is still in the pool. */
jboolean nativeIsVoiceSounding(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jint voiceId) {
    return of_engine_is_voice_sounding(fromHandle(handle), voiceId) != 0 ? JNI_TRUE : JNI_FALSE;
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
    {const_cast<char*>("setOutputMode"), const_cast<char*>("(JZ)V"),
     reinterpret_cast<void*>(nativeSetOutputMode)},
    {const_cast<char*>("info"), const_cast<char*>("(J)Lcom/blindtechnexus/openfire/audio/native/NativeStreamInfo;"),
     reinterpret_cast<void*>(nativeInfo)},
    {const_cast<char*>("activeVoices"), const_cast<char*>("(J)I"),
     reinterpret_cast<void*>(nativeActiveVoices)},
    {const_cast<char*>("isKeySounding"), const_cast<char*>("(JI)Z"),
     reinterpret_cast<void*>(nativeIsKeySounding)},
    {const_cast<char*>("isVoiceSounding"), const_cast<char*>("(JI)Z"),
     reinterpret_cast<void*>(nativeIsVoiceSounding)},
};

constexpr jint kMethodCount = static_cast<jint>(sizeof(gMethods) / sizeof(gMethods[0]));

/** Binds every method of `clazz`. Leaves no pending exception behind. */
bool bindNatives(JNIEnv* env, jclass clazz) {
    if (env->RegisterNatives(clazz, gMethods, kMethodCount) == JNI_OK) return true;
    /* RegisterNatives throws NoSuchMethodError for the first method it cannot
     * find; clear it before trying the other class so the fallback can run. */
    if (env->ExceptionCheck()) env->ExceptionClear();
    return false;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        OF_LOGE("JNI_OnLoad: this VM does not speak JNI 1.6");
        return JNI_ERR;
    }

    gCache.engineClass = toGlobalClass(env, kEngineClass);
    if (gCache.engineClass == nullptr) {
        OF_LOGE("JNI_OnLoad: class %s was not found", kEngineClass);
        return JNI_ERR;
    }

    if (bindNatives(env, gCache.engineClass)) {
        gCache.engineTarget = gCache.engineClass;
    } else {
        /* The Kotlin compiler of this build keeps the `external` methods of a
         * companion object on the companion class instead of on the engine
         * class. Bind them where they really are. */
        jclass companion = toGlobalClass(env, kCompanionClass);
        if (companion == nullptr || !bindNatives(env, companion)) {
            if (companion != nullptr) env->DeleteGlobalRef(companion);
            OF_LOGE("JNI_OnLoad: RegisterNatives failed for %s and %s; "
                    "the game will run without sound",
                    kEngineClass, kCompanionClass);
            return JNI_ERR;
        }
        gCache.engineTarget = companion;
        OF_LOGI("JNI_OnLoad: native methods bound to %s", kCompanionClass);
    }

    /* Diagnostics are optional. A device that cannot build the value object
     * still gets every sound; only the diagnostics line is missing. */
    gCache.infoClass = toGlobalClass(env, kInfoClass);
    if (gCache.infoClass != nullptr) {
        gCache.infoConstructor = env->GetMethodID(gCache.infoClass, "<init>",
                                                  kInfoConstructorSignature);
        if (gCache.infoConstructor == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            OF_LOGE("JNI_OnLoad: %s has no constructor %s; diagnostics are off",
                    kInfoClass, kInfoConstructorSignature);
            env->DeleteGlobalRef(gCache.infoClass);
            gCache.infoClass = nullptr;
        }
    } else {
        OF_LOGE("JNI_OnLoad: class %s was not found; diagnostics are off", kInfoClass);
    }

    OF_LOGI("JNI_OnLoad: the native audio engine is ready");
    return JNI_VERSION_1_6;
}
