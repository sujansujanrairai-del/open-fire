package com.blindtechnexus.openfire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.model.SoundTheme
import com.blindtechnexus.openfire.ui.components.ButtonType
import com.blindtechnexus.openfire.ui.components.GameButton
import com.blindtechnexus.openfire.ui.components.GameCard
import com.blindtechnexus.openfire.ui.theme.BtnBlue
import com.blindtechnexus.openfire.ui.theme.BtnPrimary
import com.blindtechnexus.openfire.ui.theme.CardBorder
import com.blindtechnexus.openfire.ui.theme.DarkBg
import com.blindtechnexus.openfire.ui.theme.TextAccent
import com.blindtechnexus.openfire.ui.theme.TextMuted
import com.blindtechnexus.openfire.ui.theme.WarnBright
import com.blindtechnexus.openfire.viewmodel.GameViewModel
import java.util.Locale

@Composable
fun SettingsScreen(viewModel: GameViewModel) {
    val currentSettings by viewModel.settings.collectAsState()
    val engines by viewModel.ttsManager.availableEngines.collectAsState()
    val voices by viewModel.ttsManager.availableVoices.collectAsState()
    val scrollState = rememberScrollState()

    var nameState by remember { mutableStateOf(currentSettings.name) }
    var langState by remember { mutableStateOf(currentSettings.lang) }
    var engineState by remember { mutableStateOf(currentSettings.ttsEnginePackage) }
    var ttsLangState by remember { mutableStateOf(currentSettings.ttsLanguage) }
    var voiceState by remember { mutableStateOf(currentSettings.voiceName) }
    var rateState by remember { mutableFloatStateOf(currentSettings.ttsRate) }
    var ttsVolState by remember { mutableFloatStateOf(currentSettings.ttsVolume) }
    var musicVolState by remember { mutableFloatStateOf(currentSettings.musicVolume) }
    var sfxVolState by remember { mutableFloatStateOf(currentSettings.sfxVolume) }
    var soundThemeState by remember { mutableStateOf(currentSettings.soundTheme) }
    var hapticsState by remember { mutableStateOf(currentSettings.hapticsEnabled) }
    var keepScreenOnState by remember { mutableStateOf(currentSettings.keepScreenOn) }
    var voiceGuidanceState by remember { mutableStateOf(currentSettings.voiceGuidance) }

    // The stored settings are the single source of truth: whenever they
    // change (a saved value, a language switch, a voice that had to be
    // dropped because it cannot speak the new language) the controls follow.
    // Without this the screen kept showing the values it remembered from the
    // first composition, which is what made a chosen volume appear to revert.
    LaunchedEffect(currentSettings) {
        nameState = currentSettings.name
        langState = currentSettings.lang
        engineState = currentSettings.ttsEnginePackage
        ttsLangState = currentSettings.ttsLanguage
        voiceState = currentSettings.voiceName
        rateState = currentSettings.ttsRate
        ttsVolState = currentSettings.ttsVolume
        musicVolState = currentSettings.musicVolume
        sfxVolState = currentSettings.sfxVolume
        soundThemeState = currentSettings.soundTheme
        hapticsState = currentSettings.hapticsEnabled
        keepScreenOnState = currentSettings.keepScreenOn
        voiceGuidanceState = currentSettings.voiceGuidance
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        GameCard {
            Text(
                text = viewModel.t("settings"),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Profile & Language
        GameCard {
            Text(
                text = viewModel.t("name"),
                fontSize = 14.sp,
                color = TextAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = nameState,
                onValueChange = { nameState = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("settings_name_input"),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = DarkBg,
                    unfocusedContainerColor = DarkBg,
                    focusedBorderColor = CardBorder,
                    unfocusedBorderColor = CardBorder
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = viewModel.t("language"),
                fontSize = 14.sp,
                color = TextAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            CustomDropdown(
                selectedValue = when (langState) {
                    "hi" -> "हिन्दी (Hindi)"
                    "ne" -> "नेपाली (Nepali)"
                    else -> "English"
                },
                options = listOf(
                    "en" to "English",
                    "hi" to "हिन्दी (Hindi)",
                    "ne" to "नेपाली (Nepali)"
                ),
                onSelect = {
                    langState = it
                    viewModel.setLanguage(it)
                }
            )

        }

        Spacer(modifier = Modifier.height(14.dp))

        // System TTS Engines and Voices
        GameCard {
            Text(
                text = viewModel.t("ttsVoice"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))

            // TTS Engines
            Text(
                text = viewModel.t("ttsEngine"),
                fontSize = 14.sp,
                color = TextAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            val engineOptions = mutableListOf("" to viewModel.t("defaultEngine"))
            engines.forEach { engineOptions.add(it.name to "${it.label} (${it.name})") }
            val selectedEngineLabel = engineOptions.find { it.first == engineState }?.second ?: viewModel.t("defaultEngine")
            CustomDropdown(
                selectedValue = selectedEngineLabel,
                options = engineOptions,
                onSelect = {
                    engineState = it
                    // Saved immediately, so the engine is loaded again with the
                    // right language and voice on the next start.
                    viewModel.setTtsEngine(it)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // TTS Language: the language the voice speaks. It is separate
            // from the interface language above, both are saved, and the
            // voice list below is always built for this language.
            Text(
                text = viewModel.t("ttsLanguage"),
                fontSize = 14.sp,
                color = TextAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            val ttsLangLabel = when (ttsLangState) {
                "en" -> "English"
                "hi" -> "हिन्दी (Hindi)"
                "ne" -> "नेपाली (Nepali)"
                else -> viewModel.t("ttsLanguageAuto")
            }
            CustomDropdown(
                selectedValue = ttsLangLabel,
                options = listOf(
                    "" to viewModel.t("ttsLanguageAuto"),
                    "en" to "English",
                    "hi" to "हिन्दी (Hindi)",
                    "ne" to "नेपाली (Nepali)"
                ),
                onSelect = {
                    ttsLangState = it
                    voiceState = ""
                    viewModel.setTtsLanguage(it)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // TTS Voices
            Text(
                text = viewModel.t("ttsVoice"),
                fontSize = 14.sp,
                color = TextAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            val voiceOptions = mutableListOf("" to viewModel.t("autoVoice"))
            voices.forEach { voiceOptions.add(it.name to it.displayName) }
            val selectedVoiceLabel =
                voiceOptions.find { it.first == voiceState }?.second ?: viewModel.t("autoVoice")
            if (voices.isEmpty()) {
                // Never leave the player with an empty, meaningless list: say
                // that the engine has no voice for this language yet.
                Text(
                    text = viewModel.t("ttsNoVoices"),
                    fontSize = 13.sp,
                    color = TextMuted
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            CustomDropdown(
                selectedValue = selectedVoiceLabel,
                options = voiceOptions,
                onSelect = {
                    voiceState = it
                    // Persisted at once and applied to the live engine.
                    viewModel.setVoice(it)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Speech Rate
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = viewModel.t("ttsRate"),
                    fontSize = 14.sp,
                    color = TextAccent,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = String.format(Locale.US, "%.1fx", rateState),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = WarnBright
                )
            }
            Slider(
                value = rateState,
                onValueChange = {
                    rateState = it
                    viewModel.setTtsRate(it)
                },
                valueRange = 0.5f..2.0f,
                steps = 14,
                colors = SliderDefaults.colors(
                    thumbColor = BtnPrimary,
                    activeTrackColor = BtnPrimary,
                    inactiveTrackColor = Color(0xFF101820)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))
            GameButton(
                text = viewModel.t("preview"),
                onClick = {
                    viewModel.playSound("click")
                    viewModel.ttsManager.previewVoice(viewModel.t("previewSample"), rateState, voiceState)
                },
                testTag = "preview_tts_button"
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Independent Audio Volume Mixer
        GameCard {
            Text(
                text = viewModel.t("audioMixer"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(14.dp))

            // Speech TTS Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = viewModel.t("ttsVolume"), fontSize = 14.sp, color = TextAccent, fontWeight = FontWeight.Bold)
                Text(text = "${(ttsVolState * 100).toInt()}%", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = ttsVolState,
                onValueChange = {
                    ttsVolState = it
                    // Persist immediately: the chosen level is used by the very
                    // next announcement, the next game and after a restart.
                    viewModel.setTtsVolume(it)
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(thumbColor = BtnPrimary, activeTrackColor = BtnPrimary)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Music Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = viewModel.t("musicVolume"), fontSize = 14.sp, color = TextAccent, fontWeight = FontWeight.Bold)
                Text(text = "${(musicVolState * 100).toInt()}%", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = musicVolState,
                onValueChange = {
                    musicVolState = it
                    viewModel.setMusicVolume(it)
                },
                valueRange = 0.0f..1.0f,
                colors = SliderDefaults.colors(thumbColor = WarnBright, activeTrackColor = WarnBright)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // SFX & 3D Spatial Audio Volume
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = viewModel.t("sfxVolume"), fontSize = 14.sp, color = TextAccent, fontWeight = FontWeight.Bold)
                Text(text = "${(sfxVolState * 100).toInt()}%", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = sfxVolState,
                onValueChange = {
                    sfxVolState = it
                    viewModel.setSfxVolume(it)
                },
                valueRange = 0.0f..1.0f,
                colors = SliderDefaults.colors(thumbColor = BtnBlue, activeTrackColor = BtnBlue)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Sound Themes & Haptics
        GameCard {
            Text(
                text = viewModel.t("soundTheme"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(10.dp))
            CustomDropdown(
                selectedValue = soundThemeState.title,
                options = listOf(
                    SoundTheme.TACTICAL to SoundTheme.TACTICAL.title,
                    SoundTheme.CYBER to SoundTheme.CYBER.title,
                    SoundTheme.STEALTH to SoundTheme.STEALTH.title
                ),
                onSelect = {
                    soundThemeState = it
                    viewModel.audioManager.updateTheme(it)
                }
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = viewModel.t("haptics"), fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(text = viewModel.t("hapticsDesc"), fontSize = 13.sp, color = TextMuted)
                }
                Switch(
                    checked = hapticsState,
                    onCheckedChange = {
                        hapticsState = it
                        viewModel.updateSettings(currentSettings.copy(hapticsEnabled = it))
                    },
                    modifier = Modifier.testTag("haptics_switch"),
                    colors = SwitchDefaults.colors(checkedThumbColor = BtnPrimary, checkedTrackColor = Color(0xFF002B18))
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = viewModel.t("keepScreenOn"), fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(text = viewModel.t("keepScreenOnDesc"), fontSize = 13.sp, color = TextMuted)
                }
                Switch(
                    checked = keepScreenOnState,
                    onCheckedChange = {
                        keepScreenOnState = it
                        viewModel.playSound("click")
                        viewModel.setKeepScreenOn(it)
                    },
                    modifier = Modifier.testTag("keep_screen_on_switch"),
                    colors = SwitchDefaults.colors(checkedThumbColor = BtnPrimary, checkedTrackColor = Color(0xFF002B18))
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Voice Guidance (new): the spoken feedback of the battle screen.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = viewModel.t("voiceGuidance"),
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = viewModel.t("voiceGuidanceDesc"),
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                }
                Switch(
                    checked = voiceGuidanceState,
                    onCheckedChange = {
                        voiceGuidanceState = it
                        // Applied at once and saved at once: the next battle
                        // already obeys it.
                        viewModel.setVoiceGuidance(it)
                        viewModel.playSound("click")
                        viewModel.speak(
                            if (it) viewModel.t("voiceGuidanceOn") else viewModel.t("voiceGuidanceOff")
                        )
                    },
                    modifier = Modifier.testTag("voice_guidance_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BtnPrimary,
                        checkedTrackColor = Color(0xFF002B18)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GameButton(
            text = viewModel.t("saveBack"),
            onClick = {
                val updated = currentSettings.copy(
                    name = if (nameState.trim().isNotEmpty()) nameState.trim() else currentSettings.name,
                    lang = langState,
                    ttsEnginePackage = engineState,
                    ttsLanguage = ttsLangState,
                    voiceName = voiceState,
                    ttsRate = rateState,
                    ttsVolume = ttsVolState,
                    musicVolume = musicVolState,
                    sfxVolume = sfxVolState,
                    soundTheme = soundThemeState,
                    hapticsEnabled = hapticsState,
                    keepScreenOn = keepScreenOnState,
                    voiceGuidance = voiceGuidanceState
                )
                viewModel.updateSettings(updated)
                viewModel.playSound("click")
                viewModel.navigateTo(Screen.MENU)
            },
            type = ButtonType.PRIMARY,
            testTag = "save_settings_button"
        )
        Spacer(modifier = Modifier.height(12.dp))
        GameButton(
            text = viewModel.t("backToMenu"),
            onClick = { viewModel.navigateTo(Screen.MENU) },
            type = ButtonType.DANGER,
            testTag = "settings_back_button"
        )
    }
}

@Composable
fun <T> CustomDropdown(
    selectedValue: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, CardBorder, RoundedCornerShape(10.dp))
            .background(DarkBg, RoundedCornerShape(10.dp))
            .clickable { expanded = true }
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedValue,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = "Expand options",
                tint = Color.White
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFF132238))
                .border(1.dp, CardBorder)
        ) {
            options.forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(text = label, color = Color.White, fontSize = 15.sp) },
                    onClick = {
                        onSelect(key)
                        expanded = false
                    }
                )
            }
        }
    }
}
