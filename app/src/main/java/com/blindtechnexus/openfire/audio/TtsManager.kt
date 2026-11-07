package com.blindtechnexus.openfire.audio

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.blindtechnexus.openfire.model.TtsEngineInfo
import com.blindtechnexus.openfire.model.TtsVoiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

class TtsManager(private val context: Context) {
    companion object {
        private const val TAG = "OpenFireTts"
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _availableEngines = MutableStateFlow<List<TtsEngineInfo>>(emptyList())
    val availableEngines: StateFlow<List<TtsEngineInfo>> = _availableEngines.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceInfo>> = _availableVoices.asStateFlow()

    var speechRate: Float = 1.0f

    /**
     * The user's TTS volume setting (0..1). It is applied to every utterance
     * and is never mutated by previews or screen changes, so the level chosen
     * in Settings is kept for the whole session and every later game.
     */
    var speechVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.speech(value)
        }

    var currentVoiceName: String = ""
    var currentEnginePackage: String = ""

    /**
     * The language of the text that is spoken (the interface language). It
     * feeds the speech sanitizer, never the voice choice on its own.
     */
    var currentLanguage: String = "en"

    /**
     * The language the engine voice must speak. It is chosen in Settings on
     * its own ("Language" next to "Voice") and an empty value means "follow
     * the interface language", which is the default for older profiles. The
     * voice list is always rebuilt from this language, so the two settings
     * can no longer be confused with each other.
     */
    var currentTtsLanguage: String = ""

    /** The language that really decides the engine voice and the voice list. */
    val voiceLanguage: String
        get() = currentTtsLanguage.ifBlank { currentLanguage }

    /**
     * Signature of the voice configuration that is really applied right now.
     * Re-applying the voice, the language or the rate on every single
     * utterance made some engines flush (cut off) the sentence they were
     * speaking, so a configuration is only ever applied when it changes.
     */
    private var appliedVoiceSignature: String? = null

    /** Speech requested while the engine was still initialising. */
    private var pendingSpeech: String? = null

    init {
        initTts(null)
    }

    fun initTts(enginePackage: String?) {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}

        isInitialized = false
        currentEnginePackage = enginePackage ?: ""

        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {}
                })
                refreshEngines()
                refreshVoices()
                appliedVoiceSignature = null
                applyVoiceSettings()
                pendingSpeech?.let { queued ->
                    pendingSpeech = null
                    speak(queued)
                }
            } else {
                Log.e(TAG, "TTS initialization failed with status $status")
            }
        }, enginePackage)
    }

    fun refreshEngines() {
        val enginesList = mutableListOf<TtsEngineInfo>()
        try {
            tts?.engines?.forEach { engine ->
                enginesList.add(TtsEngineInfo(engine.name, engine.label))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TTS engines", e)
        }
        _availableEngines.value = enginesList
    }

    fun refreshVoices() {
        val voiceList = mutableListOf<TtsVoiceInfo>()
        try {
            tts?.voices?.forEach { voice ->
                val langStr = voice.locale.displayLanguage
                val countryStr = if (voice.locale.country.isNotEmpty()) " (${voice.locale.country})" else ""
                val disp = "${voice.name} - $langStr$countryStr"
                voiceList.add(TtsVoiceInfo(voice.name, disp, voice.locale.language))
            }
            voiceList.sortBy { it.displayName }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching TTS voices", e)
        }

        // Only offer voices that can actually speak the selected TTS
        // language. Picking a voice from another language family is what
        // garbles English pronunciation (and vice versa), and the list was
        // previously built from the interface language even when the player
        // had chosen a different voice language.
        val wanted = wantedLanguages(voiceLanguage)
        val filtered = voiceList.filter { it.language.lowercase() in wanted }
        _availableVoices.value = if (filtered.isNotEmpty()) filtered else voiceList
    }

    /**
     * Resolves the locale that should speak the current app language.
     * If Nepali ("ne_NP") is missing from the installed TTS engine (common on
     * Android), automatically fall back to Hindi ("hi_IN") which shares the
     * Devanagari script, so English phonetics never attempt to spell
     * Devanagari characters.
     */
    private fun resolveTargetLocale(currentTts: TextToSpeech): Locale {
        val language = voiceLanguage
        var targetLocale = when (language) {
            "hi" -> Locale("hi", "IN")
            "ne" -> Locale("ne", "NP")
            else -> Locale.US
        }
        val avail = currentTts.isLanguageAvailable(targetLocale)
        if (language == "ne" &&
            (avail == TextToSpeech.LANG_MISSING_DATA || avail == TextToSpeech.LANG_NOT_SUPPORTED)
        ) {
            targetLocale = Locale("hi", "IN")
        }
        return targetLocale
    }

    /**
     * The engine languages that may voice [language]. Nepali falls back to
     * Hindi, which shares the Devanagari script, so a device without a Nepali
     * voice still has a usable list instead of English entries.
     */
    private fun wantedLanguages(language: String): Set<String> = when (language) {
        "hi" -> setOf("hi")
        "ne" -> setOf("ne", "hi")
        else -> setOf("en")
    }

    /**
     * Rebuilds the voice list for a newly selected TTS language and drops a
     * previously saved voice that cannot speak it, so the Settings screen
     * always shows voices that really work.
     */
    fun setVoiceLanguage(language: String) {
        currentTtsLanguage = language
        val wanted = wantedLanguages(voiceLanguage)
        val savedLanguage = _availableVoices.value
            .find { it.name == currentVoiceName }
            ?.language
            ?.lowercase()
        val savedIsUsable = currentVoiceName.isNotEmpty() &&
            savedLanguage != null && savedLanguage in wanted
        if (!savedIsUsable) currentVoiceName = ""
        appliedVoiceSignature = null
        refreshVoices()
        applyVoiceSettings()
    }

    /**
     * Applies voice and language settings.
     *
     * English-specific fix: a persisted voice from another language (e.g. a
     * Nepali/Hindi voice saved earlier) is never applied to English text, and
     * the engine never silently falls back to the device locale — both were
     * distorting English pronunciation while Devanagari languages sounded fine.
     */
    fun applyVoiceSettings() {
        if (!isInitialized || tts == null) return
        val currentTts = tts ?: return

        val targetLocale = resolveTargetLocale(currentTts)
        // Applying the very same configuration again is what used to cut an
        // announcement off in the middle of a sentence, so it is skipped.
        val signature = "${currentEnginePackage}|${voiceLanguage}|$currentVoiceName|" +
            "${speechRate}_${targetLocale.toLanguageTag()}"
        if (signature == appliedVoiceSignature) return
        appliedVoiceSignature = signature

        currentTts.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        val avail = currentTts.isLanguageAvailable(targetLocale)
        if (avail >= TextToSpeech.LANG_AVAILABLE) {
            currentTts.language = targetLocale
        } else {
            // Keep the requested locale anyway: falling back to the device locale
            // can switch the whole language (e.g. device set to Nepali while the
            // app is in English) which garbles every English announcement.
            try {
                currentTts.language = targetLocale
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting locale $targetLocale", e)
            }
        }

        applyBestVoice(currentTts, targetLocale)
    }

    /**
     * Picks the best voice that can speak [target]:
     *  1. the user-selected voice, but only when it speaks the target language;
     *  2. the engine default voice when it matches;
     *  3. otherwise the highest-quality matching voice (same country preferred).
     */
    private fun applyBestVoice(currentTts: TextToSpeech, target: Locale) {
        val voices = try {
            currentTts.voices
        } catch (e: Exception) {
            Log.e(TAG, "Error reading voices", e)
            null
        } ?: return

        fun speaksTarget(voice: Voice): Boolean =
            voice.locale.language.equals(target.language, ignoreCase = true)

        // 1. Explicitly chosen voice — honored only if it speaks the current language.
        if (currentVoiceName.isNotEmpty()) {
            val selected = voices.find { it.name == currentVoiceName }
            if (selected != null && speaksTarget(selected)) {
                setVoiceIfChanged(currentTts, selected)
                return
            }
        }

        // 2. Engine default voice is usually the best match for its own language.
        val default = try {
            currentTts.defaultVoice
        } catch (e: Exception) {
            null
        }
        if (default != null && speaksTarget(default)) {
            setVoiceIfChanged(currentTts, default)
            return
        }

        // 3. Best remaining match: same country first, then highest quality.
        val candidates = voices.filter { speaksTarget(it) }
        val sameCountry = candidates.filter {
            it.locale.country.isNotEmpty() && it.locale.country.equals(target.country, ignoreCase = true)
        }
        val pool = if (sameCountry.isNotEmpty()) sameCountry else candidates
        val best = pool.sortedWith(
            compareByDescending<Voice> { it.quality }.thenBy { it.name }
        ).firstOrNull()
        if (best != null) {
            setVoiceIfChanged(currentTts, best)
        }
    }

    private fun setVoiceIfChanged(currentTts: TextToSpeech, voice: Voice) {
        val current = try {
            currentTts.voice
        } catch (e: Exception) {
            null
        }
        if (current?.name != voice.name) {
            try {
                currentTts.voice = voice
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting voice ${voice.name}", e)
            }
        }
    }

    /**
     * Sanitizes game strings before sending them to speech synthesis so TTS
     * engines never pronounce raw punctuation, pipes, slashes or abbreviations.
     */
    private fun sanitizeTextForSpeech(raw: String): String =
        SpeechSanitizer.sanitize(raw, currentLanguage)

    fun speak(text: String, interrupt: Boolean = true) {
        if (text.isBlank()) return

        val sanitized = sanitizeTextForSpeech(text)
        if (sanitized.isBlank()) return

        // An engine switch (or the very first launch) can happen while an
        // announcement is requested. Queue it instead of dropping it silently.
        if (!isInitialized || tts == null) {
            pendingSpeech = sanitized
            return
        }
        // Only applies the voice when it really changed: re-applying it here
        // flushed the utterance that was still being spoken.
        applyVoiceSettings()

        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VolumeMixer.speech(speechVolume))
        }
        val utteranceId = "utterance_${System.currentTimeMillis()}"
        val result = tts?.speak(sanitized, queueMode, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            pendingSpeech = sanitized
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun previewVoice(text: String, rate: Float, voiceName: String) {
        if (!isInitialized || tts == null) return
        val currentTts = tts ?: return
        currentTts.stop()
        currentTts.setSpeechRate(rate.coerceIn(0.5f, 2.0f))

        val target = resolveTargetLocale(currentTts)
        if (currentTts.isLanguageAvailable(target) >= TextToSpeech.LANG_AVAILABLE) {
            currentTts.language = target
        }
        if (voiceName.isNotEmpty()) {
            val v = try {
                currentTts.voices?.find { it.name == voiceName }
            } catch (e: Exception) {
                null
            }
            if (v != null && v.locale.language.equals(target.language, ignoreCase = true)) {
                setVoiceIfChanged(currentTts, v)
            } else {
                applyBestVoice(currentTts, target)
            }
        } else {
            applyBestVoice(currentTts, target)
        }

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, VolumeMixer.speech(speechVolume))
        }
        val sanitized = sanitizeTextForSpeech(text)
        if (sanitized.isNotBlank()) {
            currentTts.speak(sanitized, TextToSpeech.QUEUE_FLUSH, params, "preview")
        }
        // The preview temporarily changes the rate and the voice, so the real
        // configuration has to be re-applied on the next announcement.
        appliedVoiceSignature = null
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (_: Exception) {}
    }
}
