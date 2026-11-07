package com.blindtechnexus.openfire

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.blindtechnexus.openfire.data.PreferencesManager
import com.blindtechnexus.openfire.model.GameSettings
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.viewmodel.GameViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the two new settings of release 2.6:
 *  - the speech language and the speech voice are separate values and both are
 *    saved, and the voice language falls back to the interface language;
 *  - Voice guidance mutes the spoken feedback of the in-game screens only,
 *    while every menu keeps speaking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsVoiceAndAudioTest {

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: GameViewModel, name: String): MutableStateFlow<T> {
        val field = GameViewModel::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(vm) as MutableStateFlow<T>
    }

    @Test
    fun voiceLanguageAndVoiceSurviveANewPreferencesInstance() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        PreferencesManager(context).saveSettings(
            GameSettings(
                name = "Voice Tester",
                lang = "ne",
                ttsEnginePackage = "com.example.engine",
                ttsLanguage = "hi",
                voiceName = "hi-in-x-sfg-local",
                ttsRate = 1.3f,
                ttsVolume = 0.4f,
                voiceGuidance = false
            )
        )

        val loaded = PreferencesManager(context).loadSettings()
        assertEquals("ne", loaded.lang)
        assertEquals("hi", loaded.ttsLanguage)
        assertEquals("hi", loaded.effectiveTtsLanguage)
        assertEquals("com.example.engine", loaded.ttsEnginePackage)
        assertEquals("hi-in-x-sfg-local", loaded.voiceName)
        assertEquals(1.3f, loaded.ttsRate, 0.0001f)
        assertEquals(0.4f, loaded.ttsVolume, 0.0001f)
        assertFalse(loaded.voiceGuidance)
    }

    @Test
    fun theVoiceLanguageFollowsTheAppLanguageUntilItIsChosen() {
        // Old profiles never stored a voice language: they keep following the
        // interface language, exactly as before.
        assertEquals("en", GameSettings(lang = "en").effectiveTtsLanguage)
        assertEquals("hi", GameSettings(lang = "hi").effectiveTtsLanguage)
        assertEquals("ne", GameSettings(lang = "ne").effectiveTtsLanguage)
        // As soon as it is chosen it wins over the interface language.
        assertEquals("en", GameSettings(lang = "ne", ttsLanguage = "en").effectiveTtsLanguage)
    }

    @Test
    fun voiceGuidanceIsOnByDefaultAndCanBeSwitchedOff() {
        assertTrue(GameSettings().voiceGuidance)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(context)
        try {
            // The battle screens are the only ones the setting silences.
            flow<Screen>(vm, "_currentScreen").value = Screen.BATTLE
            vm.setVoiceGuidance(false)
            assertFalse(flow<GameSettings>(vm, "_settings").value.voiceGuidance)
            assertFalse(vm.isSpeechAllowed())

            flow<Screen>(vm, "_currentScreen").value = Screen.MENU
            assertTrue("menus must keep speaking", vm.isSpeechAllowed())
            flow<Screen>(vm, "_currentScreen").value = Screen.SETTINGS
            assertTrue(vm.isSpeechAllowed())
            flow<Screen>(vm, "_currentScreen").value = Screen.SHOP
            assertTrue(vm.isSpeechAllowed())
            flow<Screen>(vm, "_currentScreen").value = Screen.RESULT
            assertTrue(vm.isSpeechAllowed())

            for (screen in listOf(Screen.JUMP, Screen.PARA, Screen.LAND, Screen.BATTLE)) {
                flow<Screen>(vm, "_currentScreen").value = screen
                assertFalse("$screen must be silent", vm.isSpeechAllowed())
            }

            vm.setVoiceGuidance(true)
            assertTrue(vm.isSpeechAllowed())
            // The choice is written to the profile straight away.
            assertEquals(
                true,
                PreferencesManager(context).loadSettings().voiceGuidance
            )
        } finally {
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }

    @Test
    fun choosingAVoiceLanguageDropsAVoiceThatCannotSpeakIt() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val vm = GameViewModel(context)
        try {
            vm.updateSettings(
                GameSettings(lang = "en", ttsLanguage = "en", voiceName = "en-us-x-sfg-local")
            )
            vm.setTtsLanguage("hi")
            val settings = flow<GameSettings>(vm, "_settings").value
            assertEquals("hi", settings.ttsLanguage)
            assertEquals("hi", settings.effectiveTtsLanguage)
            assertEquals("an English voice cannot voice Hindi", "", settings.voiceName)

            // An unknown or empty language falls back to following the app.
            vm.setTtsLanguage("")
            val following = flow<GameSettings>(vm, "_settings").value
            assertEquals("", following.ttsLanguage)
            assertEquals("en", following.effectiveTtsLanguage)
        } finally {
            vm.audioManager.release()
            vm.ttsManager.shutdown()
        }
    }
}
