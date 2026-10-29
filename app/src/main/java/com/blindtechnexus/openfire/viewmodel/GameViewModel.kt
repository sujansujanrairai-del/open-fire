package com.blindtechnexus.openfire.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blindtechnexus.openfire.audio.AudioManager
import com.blindtechnexus.openfire.audio.TtsManager
import com.blindtechnexus.openfire.data.PreferencesManager
import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.Arena
import com.blindtechnexus.openfire.model.BattleMenuItem
import com.blindtechnexus.openfire.model.BombKind
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DamageTable
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.FireRange
import com.blindtechnexus.openfire.model.GameAction
import com.blindtechnexus.openfire.model.GameSettings
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.GameStats
import com.blindtechnexus.openfire.model.GestureRouter
import com.blindtechnexus.openfire.model.HitZone
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.MoveDirection
import com.blindtechnexus.openfire.model.Progression
import com.blindtechnexus.openfire.model.ResultData
import com.blindtechnexus.openfire.model.RewardBundle
import com.blindtechnexus.openfire.model.Rewards
import com.blindtechnexus.openfire.model.RobertProfile
import com.blindtechnexus.openfire.model.RobertRules
import com.blindtechnexus.openfire.model.RunPressure
import com.blindtechnexus.openfire.model.RunSpeed
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.model.Shop
import com.blindtechnexus.openfire.model.ShopResult
import com.blindtechnexus.openfire.model.Weapon
import com.blindtechnexus.openfire.model.movePlayer
import com.blindtechnexus.openfire.model.movePlayerLateral
import com.blindtechnexus.openfire.model.moveRobertSafely
import com.blindtechnexus.openfire.model.SoundDurations
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

class GameViewModel(application: Application) : AndroidViewModel(application) {
    val audioManager = AudioManager(application)
    val ttsManager = TtsManager(application)
    private val prefs = PreferencesManager(application)

    private val _settings = MutableStateFlow(prefs.loadSettings())
    val settings: StateFlow<GameSettings> = _settings.asStateFlow()

    private val _stats = MutableStateFlow(Progression.normalize(prefs.loadStats()))
    val stats: StateFlow<GameStats> = _stats.asStateFlow()

    private val _inventory = MutableStateFlow(prefs.loadInventory())
    val inventory: StateFlow<Inventory> = _inventory.asStateFlow()

    private val _currentScreen = MutableStateFlow(
        if (_settings.value.name.isNotBlank()) Screen.MENU else Screen.WELCOME
    )
    val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

    private val _gameState = MutableStateFlow(GameState())
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private val _resultData = MutableStateFlow(ResultData())
    val resultData: StateFlow<ResultData> = _resultData.asStateFlow()

    /** False during the four second pause after a victory or a defeat. */
    private val _resultReady = MutableStateFlow(true)
    val resultReady: StateFlow<Boolean> = _resultReady.asStateFlow()

    private var stepJob: Job? = null
    private var counterJob: Job? = null
    private var robertFireJob: Job? = null
    private var resultJob: Job? = null
    private var playerMoveJob: Job? = null
    private var missileJob: Job? = null
    private var bombJob: Job? = null
    private var bombTrackJob: Job? = null
    private var autoFireJob: Job? = null
    private var endingJob: Job? = null
    private var matchLimitJob: Job? = null
    private var foreground = true

    /**
     * Flee ticks left of Robert's current sprint. A sprint always runs for
     * [RobertRules.SPRINT_TICKS] ticks at [RobertRules.enemyFleeSpeedMps] -
     * twice the fastest speed the player can run - before he turns it into a
     * flanking re-approach, so "running far away" really means crossing the
     * zone instead of stepping back a couple of metres.
     */
    private var robertSprintTicks = 0

    /** Wall-clock moment of Robert's last answering (retaliation) shot. */
    private var robertRetaliationAtMs = 0L

    /** Wall-clock moment the round five seven minute limit expires (0 = none). */
    private var matchDeadlineMs: Long = 0L


    /** Latest touch pressure of the running hold (0 = unknown / released). */
    @Volatile
    private var movePressure: Float = 0f

    /** The running direction of the current hold (null while nothing is held). */
    @Volatile
    private var moveDirection: MoveDirection? = null

    /** Wall-clock moment the missile in the air is due to impact. */
    private var missileImpactAtMs: Long = 0L

    /** Wall-clock moment the bomb in the air is due to explode. */
    private var bombBlastAtMs: Long = 0L

    /** Last time the boundary wall was announced, so it never spams. */
    private var lastWallAnnounceAtMs: Long = 0L

    /** True while the player's current run is already stopped by a boundary. */
    private var playerAtWall = false

    /** Wall-clock moment the quick-menu hit protection expires. */
    private var menuProtectUntilMs: Long = 0L

    /** Start of the player's current fire burst (continuous fire detection). */
    private var playerBurstStartedAtMs: Long = 0L

    /** Wall-clock moment of the player's most recent shot. */
    private var lastPlayerShotAtMs = 0L

    /** Last time the enemy hit report was spoken out loud. */
    private var lastHitSpeechAtMs = 0L

    /**
     * The player's own firing rhythm, in milliseconds, averaged over the
     * shots of the current exchange. Robert fires at exactly this cadence
     * whenever the two fighters are trading shots.
     */
    private var playerShotIntervalMs = RobertRules.DEFAULT_PLAYER_INTERVAL_MS

    /** Last voice-related settings applied, to avoid resetting TTS while dragging volume. */
    private var syncedVoiceSignature: String? = null

    /** Debounce so one physical tap / swipe can never fire (and spend) more than one bullet. */
    private var lastFireAt = 0L

    /**
     * Timestamp of the last explicit button / gesture action. A tap that
     * immediately follows a button press must not also activate the quick
     * menu, otherwise a Fire or Reload button would trigger two actions.
     */
    private var lastExplicitActionAt = 0L

    private val robertProfile: RobertProfile
        get() = RobertRules.profile(
            round = _stats.value.round,
            level = _stats.value.level,
            // Round one mirrors the player's sniper rifle, and the enemy may
            // answer a player gloo wall with one of his own.
            playerOwnsSniper = _inventory.value.ownsWeapon(Weapon.SNIPER),
            playerGlueWalls = _inventory.value.glueWalls
        )

    private companion object {
        /** Fast retreat: metres gained per flee tick, and the tick length. */
        const val FLEE_STEP = RobertRules.FLEE_STEP_M
        const val FLEE_TICK_MS = RobertRules.FLEE_TICK_MS

        /**
         * Player movement at the normal speed level: [RunPressure.BASE_STEP_METRES]
         * metres per [PLAYER_MOVE_TICK_MS]. The live step is scaled by the
         * three-finger speed level and, in the normal level, by the touch
         * pressure: a stronger press runs faster, a lighter press runs slower.
         */
        const val PLAYER_MOVE_STEP = RunPressure.BASE_STEP_METRES
        const val PLAYER_MOVE_TICK_MS = 100L

        /** Fastest and slowest movement tick, so a sprint still looks smooth. */
        const val MIN_MOVE_TICK_MS = 30L
        const val MAX_MOVE_TICK_MS = 220L

        /** The wall announcement is repeated at most once per this window. */
        const val WALL_ANNOUNCE_COOLDOWN_MS = 1_400L

        /** Bullets per second of a held automatic weapon come from the weapon. */
        const val AUTO_FIRE_FIRST_SHOT_DELAY_MS = 0L

        /** Advance tick length. */
        const val ADVANCE_TICK_MS = 550L

        /** How long Robert holds the far edge while he avoids a glue wall. */
        const val AVOID_HOLD_MS = 700L

        /** Pause before a finished sprint turns into the flanking re-approach. */
        const val FLANK_DELAY_MS = 600L

        /** Result screen lock-out before another game may start. */
        const val RESULT_LOCK_MS = 4000L

        /**
         * A tap that immediately follows a button or gesture belongs to that
         * action. Without this window one physical tap could both fire a gun
         * (or recover a weapon) and activate the quick menu.
         */
        const val TAP_ACTION_DEBOUNCE_MS = 350L

        /**
         * While the timer bomb picker is open the enemy holds his fire and
         * simply re-checks this often, so he resumes the moment the player
         * confirms or deselects.
         */
        const val TIMER_SELECTION_HOLD_MS = 250L

        /** Health percentage considered the "last stage" for automatic defences. */
        const val LAST_STAGE_FRACTION = 0.20f

        /** Robert stops walking once he is this close to his band distance. */
        const val BAND_TOLERANCE_M = 1.5f

        /**
         * In a fast exchange the enemy hits land much faster than a sentence
         * can be spoken. The report is then kept on screen and read out at
         * most once per this window, so the speech never chops itself into
         * silence while the gunfire stays clear.
         */
        const val FAST_EXCHANGE_SPEECH_MS = 1_200L

        /**
         * Pressure mode. While the weapon in the player's hands is damaged the
         * Robert closes in faster and fires almost continuously from the
         * 90%-100% band until a working weapon is used or Recovery is paid.
         */
        const val PRESSURE_SPEED_FACTOR = 1.6f
        const val PRESSURE_ADVANCE_TICK_MS = 320L

        /** Airplane menu: the cabin sound and the engine sound are mixed. */
        const val AIRPLANE_VOLUME = 0.45f
        const val AIRPLANE_ENGINE_VOLUME = 0.32f

        /** Flight and blast volumes of the launchers. */
        const val MISSILE_VOLUME = MissileRules.SOUND_VOLUME
        const val BOMB_VOLUME = 0.95f
        const val WALL_VOLUME = 1.0f

        /** Progression stings, kept a little under the win / lose sounds. */
        const val LEVEL_COMPLETE_VOLUME = 0.9f
        const val ROUND_COMPLETE_VOLUME = 0.9f

    }

    init {
        syncSettingsToManagers(_settings.value)
        updateAudioForScreen(_currentScreen.value)
    }

    // ---------------------------------------------------------------------
    // Settings, volume and TTS lifecycle
    // ---------------------------------------------------------------------

    private fun syncSettingsToManagers(s: GameSettings) {
        audioManager.sfxVolume = s.sfxVolume
        audioManager.musicVolume = s.musicVolume
        audioManager.soundTheme = s.soundTheme
        // The game screen never vibrates: haptics belong to the menus only,
        // so the battle stays pure speech and sound.
        audioManager.hapticsEnabled = s.hapticsEnabled && !audioManager.battleActive
        // Footsteps play at exactly the voice level so movement is always
        // as clear as the speech announcements.
        audioManager.walkVolume = s.ttsVolume

        ttsManager.speechRate = s.ttsRate
        ttsManager.speechVolume = s.ttsVolume
        ttsManager.currentLanguage = s.lang
        ttsManager.currentVoiceName = s.voiceName
        if (s.ttsEnginePackage != ttsManager.currentEnginePackage) {
            ttsManager.initTts(s.ttsEnginePackage.ifEmpty { null })
            syncedVoiceSignature = null
        } else {
            val signature = "${s.lang}|${s.voiceName}|${s.ttsRate}|${s.ttsEnginePackage}"
            if (signature != syncedVoiceSignature) {
                ttsManager.applyVoiceSettings()
                syncedVoiceSignature = signature
            }
        }
    }

    fun t(key: String): String = Strings.get(key, _settings.value.lang)

    fun msg(text: String) {
        _gameState.update { it.copy(log = text) }
        ttsManager.speak(text, interrupt = true)
    }

    private fun setLog(text: String) {
        _gameState.update { it.copy(log = text) }
    }

    fun speak(text: String, interrupt: Boolean = true) {
        ttsManager.speak(text, interrupt)
    }

    fun playSound(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        audioManager.playSound(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun notEnoughCoins() {
        playSound("ammo")
        val text = t("notEnoughCoins")
        toast(text)
        msg(text)
    }

    fun submitName(inputName: String) {
        val trimmed = inputName.trim()
        val defaultName = when (_settings.value.lang) {
            "hi" -> "खिलाड़ी"
            "ne" -> "खेलाडी"
            else -> "Player"
        }
        val finalName = if (trimmed.isNotEmpty()) trimmed else defaultName
        val updated = _settings.value.copy(name = finalName)
        _settings.value = updated
        prefs.saveSettings(updated)
        playSound("click")
        _currentScreen.value = Screen.MENU
        speak("${t("welcome")}, $finalName")
        updateAudioForScreen(Screen.MENU)
    }

    fun updateSettings(newSettings: GameSettings) {
        _settings.value = newSettings
        prefs.saveSettings(newSettings)
        syncSettingsToManagers(newSettings)
    }

    fun setMusicVolume(volume: Float) {
        updateSettings(_settings.value.copy(musicVolume = volume.coerceIn(0f, 1f)))
    }

    fun setSfxVolume(volume: Float) {
        updateSettings(_settings.value.copy(sfxVolume = volume.coerceIn(0f, 1f)))
    }

    fun setTtsVolume(volume: Float) {
        updateSettings(_settings.value.copy(ttsVolume = volume.coerceIn(0f, 1f)))
    }

    fun setLanguage(lang: String) {
        updateSettings(_settings.value.copy(lang = lang))
        ttsManager.refreshVoices()
    }

    fun setKeepScreenOn(enabled: Boolean) {
        updateSettings(_settings.value.copy(keepScreenOn = enabled))
    }

    // ---------------------------------------------------------------------
    // Navigation and screen audio
    // ---------------------------------------------------------------------

    fun navigateTo(screen: Screen) {
        stopTimers()
        resultJob?.cancel()
        resultJob = null
        _resultReady.value = true
        ttsManager.stop()
        audioManager.stopScreenAudio()
        playSound("click")
        _currentScreen.value = screen
        updateAudioForScreen(screen)

        when (screen) {
            Screen.SETTINGS -> {
                ttsManager.refreshEngines()
                ttsManager.refreshVoices()
                speak(t("settings"))
            }
            Screen.GUIDE -> speak(t("guideTitle"))
            Screen.SHOP -> speak("${t("shopTitle")}. ${t("shopSubtitle")}. ${coinsSpeech()}")
            Screen.PROFILE -> speak(
                "${t("profileTitle")}. ${t("round")} ${_stats.value.round}. " +
                    "${t("level")} ${_stats.value.level}. " +
                    "${t("needWin")} ${_stats.value.needWinsForNext}. ${coinsSpeech()}. " +
                    "${profileInventorySpeech()}"
            )
            Screen.ABOUT -> speak(
                "${t("aboutTitle")}. ${t("developed")}. " +
                    "${t("specialSupport")}: ${t("specialSupportName")}. " +
                    "${t("builtInNepal")} ${t("version")}"
            )
            Screen.CHANGELOG -> speak(t("changelogTitle"))
            Screen.MENU -> speak(t("mainMenu"))
            Screen.READER -> speak("${t("readerMsg")} ${t("readerUp")}. ${t("readerDown")}.")
            // The battle screen starts silently: no instructive message is
            // spoken when the game screen opens.
            else -> {}
        }
    }

    private fun coinsSpeech(): String = "${t("coins")} ${_stats.value.coins}."

    private fun updateAudioForScreen(screen: Screen) {
        when (screen) {
            Screen.WELCOME, Screen.MENU, Screen.SETTINGS, Screen.GUIDE, Screen.PROFILE,
            Screen.SHOP, Screen.ABOUT, Screen.CHANGELOG, Screen.READER -> {
                audioManager.battleActive = false
                audioManager.hapticsEnabled = _settings.value.hapticsEnabled
                audioManager.stopScreenAudio()
                audioManager.startMusic()
            }
            Screen.JUMP -> {
                // The whole game flow - flight screens included - is speech
                // and sound only. Haptics belong to the menus.
                audioManager.battleActive = true
                audioManager.hapticsEnabled = false
                audioManager.stopMusic()
                audioManager.stopScreenAudio()
                startAirplaneAudio()
            }
            Screen.PARA -> {
                audioManager.battleActive = true
                audioManager.hapticsEnabled = false
                audioManager.stopMusic()
                stopAirplaneAudio()
                audioManager.playSound("parachute")
            }
            Screen.LAND -> {
                audioManager.battleActive = true
                audioManager.hapticsEnabled = false
                audioManager.stopMusic()
                audioManager.stopSound("parachute")
            }
            Screen.BATTLE -> {
                // From here on there is no vibration at all: the battle screen
                // is played with sound and speech only.
                audioManager.battleActive = true
                audioManager.hapticsEnabled = false
                audioManager.stopMusic()
                audioManager.stopScreenAudio()
                // Footsteps start only when a fighter is actually running.
                audioManager.stopFootsteps()
            }
            Screen.RESULT -> {
                // The result screen is still part of the match: no vibration
                // there either, just the spoken result and its sound.
                audioManager.battleActive = true
                audioManager.hapticsEnabled = false
                audioManager.stopMusic()
                audioManager.stopFootsteps()
                audioManager.stopWeaponSounds()
            }
        }
    }

    /**
     * The airplane menu mixes two loops: the cabin ambience and the engine.
     * Both are managed MediaPlayer tracks, so they run at the same time.
     */
    private fun startAirplaneAudio() {
        audioManager.playLoop("airplane", volume = AIRPLANE_VOLUME)
        audioManager.playLoop("airplaneEngine", volume = AIRPLANE_ENGINE_VOLUME)
    }

    private fun stopAirplaneAudio() {
        audioManager.stopSound("airplane")
        audioManager.stopSound("airplaneEngine")
    }

    fun onAppResumed() {
        foreground = true
        when (_currentScreen.value) {
            Screen.WELCOME, Screen.MENU, Screen.SETTINGS, Screen.GUIDE, Screen.PROFILE,
            Screen.SHOP, Screen.ABOUT, Screen.CHANGELOG, Screen.READER -> audioManager.startMusic()
            Screen.JUMP -> startAirplaneAudio()
            Screen.BATTLE -> {
                val state = _gameState.value
                audioManager.stopFootsteps()
                if (state.playerHp > 0 && state.robertHp > 0) {
                    startRobertFireLoop(1200L)
                    if (state.missileInFlight) {
                        // A paused missile resumes its flight: restart the
                        // sound and the remaining time to impact.
                        if (MissileRules.soundLoops(state.distance)) {
                            audioManager.playLoop("missile")
                        } else {
                            playSound("missile")
                        }
                        val remaining = missileImpactAtMs - System.currentTimeMillis()
                        scheduleMissileImpact(remaining.coerceIn(800L, MissileRules.MAX_FLIGHT_MS))
                    }
                    val needsMovement = state.robertState == "retreat" ||
                        state.robertState == "flank" ||
                        state.robertState == "missileFlee" ||
                        (
                            state.robertState == "advance" &&
                                abs(state.distance - robertTargetDistance(state)) > BAND_TOLERANCE_M
                            )
                    if (needsMovement) {
                        planRobertStep(400L)
                    }
                }
            }
            else -> {}
        }
    }

    fun onAppPaused() {
        foreground = false
        stopTimers()
        audioManager.stopAllSounds()
        ttsManager.stop()
    }

    // ---------------------------------------------------------------------
    // Shop
    // ---------------------------------------------------------------------

    private fun applyPurchase(result: ShopResult, nameKey: String, successKey: String) {
        if (!result.success) {
            when (result.reasonKey) {
                "notEnoughCoins" -> notEnoughCoins()
                "missileLocked", "antiMissileLocked" -> {
                    playSound("ammo")
                    val text = t(result.reasonKey)
                    toast(text)
                    msg(text)
                }
                null -> {}
                else -> msg(t(result.reasonKey))
            }
            return
        }
        _inventory.value = result.inventory
        prefs.saveInventory(result.inventory)
        _stats.value = _stats.value.copy(coins = result.coins)
        prefs.saveStats(_stats.value)
        playSound("spend")
        msg("${t(nameKey)}. ${t(successKey)}")
    }

    fun buyWeapon(weaponId: String) {
        val weapon = Weapon.fromId(weaponId) ?: return
        applyPurchase(
            Shop.buyWeapon(
                _inventory.value,
                _stats.value.coins,
                weapon,
                _stats.value.completedRounds
            ),
            weapon.nameKey,
            "bought"
        )
    }

    /** Buys one stackable missile power (repeatable, exactly like a defence). */
    fun buyMissilePower() {
        applyPurchase(
            Shop.buyMissilePower(
                _inventory.value,
                _stats.value.coins,
                _stats.value.completedRounds
            ),
            "extMissilePowers",
            "bought"
        )
    }

    /** Discards one stacked missile power, after the shop confirmed it. */
    fun removeMissilePower() {
        if (_inventory.value.missilePowers <= 0) {
            playSound("ammo")
            msg(t("nothingToRemove"))
            return
        }
        val updated = Shop.consumeMissilePower(_inventory.value)
        _inventory.value = updated
        prefs.saveInventory(updated)
        playSound("click")
        msg("${t("extMissilePowers")}. ${t("removedOne")} ${updated.missilePowers}.")
    }

    fun buyBombPower() {
        applyPurchase(
            Shop.buyBombPower(
                _inventory.value,
                _stats.value.coins,
                _stats.value.completedRounds
            ),
            "extBombPowers",
            "bought"
        )
    }

    fun removeBombPower() {
        if (_inventory.value.bombPowers <= 0) {
            playSound("ammo")
            msg(t("nothingToRemove"))
            return
        }
        val updated = Shop.consumeBombPower(_inventory.value)
        _inventory.value = updated
        prefs.saveInventory(updated)
        playSound("click")
        msg("${t("extBombPowers")}. ${t("removedOne")} ${updated.bombPowers}.")
    }

    fun removeWeapon(weaponId: String) {
        val weapon = Weapon.fromId(weaponId) ?: return
        applyPurchase(
            Shop.removeWeapon(_inventory.value, _stats.value.coins, weapon),
            weapon.nameKey,
            "removed"
        )
    }

    fun buyDefence(itemId: String) {
        val item = DefenceItem.fromId(itemId) ?: return
        applyPurchase(
            Shop.buyDefence(
                _inventory.value,
                _stats.value.coins,
                item,
                _stats.value.completedRounds
            ),
            item.nameKey,
            "bought"
        )
    }

    /** Discards one owned defence, after the shop confirmed the removal. */
    fun removeDefence(itemId: String) {
        val item = DefenceItem.fromId(itemId) ?: return
        if (_inventory.value.count(item) <= 0) {
            playSound("ammo")
            msg(t("nothingToRemove"))
            return
        }
        val updated = Shop.consume(_inventory.value, item)
        _inventory.value = updated
        prefs.saveInventory(updated)
        playSound("click")
        msg("${t(item.nameKey)}. ${t("removedOne")} ${updated.count(item)}.")
    }

    fun buyExtension(extensionId: String) {
        val extension = Extension.fromId(extensionId) ?: return
        applyPurchase(
            Shop.buyExtension(_inventory.value, _stats.value.coins, extension),
            extension.nameKey,
            "extensionBought"
        )
    }

    fun removeExtension(extensionId: String) {
        val extension = Extension.fromId(extensionId) ?: return
        applyPurchase(
            Shop.removeExtension(_inventory.value, _stats.value.coins, extension),
            extension.nameKey,
            "extensionRemoved"
        )
    }

    /**
     * The Enable / Disable switch under every weapon row of the shop. It works
     * like an extension switch: a disabled weapon is never handed out at the
     * start of a battle and is skipped by the left and right swipe on the game
     * screen until it is enabled again.
     */
    fun toggleWeaponEnabled(weaponId: String) {
        val weapon = Weapon.fromId(weaponId) ?: return
        val result = Shop.toggleWeaponEnabled(_inventory.value, _stats.value.coins, weapon)
        if (!result.success) {
            playSound("ammo")
            msg(t(result.reasonKey ?: "notOwned"))
            return
        }
        _inventory.value = result.inventory
        prefs.saveInventory(result.inventory)
        playSound("click")
        val enabled = result.inventory.weaponEnabled(weapon)
        msg(
            "${t(weapon.nameKey)}: " +
                if (enabled) t("weaponEnabledMsg") else t("weaponDisabledMsg")
        )
    }

    fun toggleExtension(extensionId: String) {
        val extension = Extension.fromId(extensionId) ?: return
        val result = Shop.toggleExtension(_inventory.value, _stats.value.coins, extension)
        if (!result.success) {
            msg(t(result.reasonKey ?: "notOwned"))
            return
        }
        _inventory.value = result.inventory
        prefs.saveInventory(result.inventory)
        playSound("click")
        val enabled = result.inventory.extensionEnabled(extension)
        msg(if (enabled) t("extensionEnabledMsg") else t("extensionDisabledMsg"))
    }

    /** Spoken summary of everything the player owns. */
    private fun profileInventorySpeech(): String {
        val inv = _inventory.value
        val weapons = inv.ownedWeapons().joinToString(", ") { t(it.nameKey) }
        val extensions = Extension.entries
            .filter { inv.extensionOwned(it) }
            .joinToString(", ") { extension ->
                val state = if (inv.extensionEnabled(extension)) t("enabledState") else t("disabledState")
                "${t(extension.nameKey)} ${state}"
            }
            .ifEmpty { t("noneOwned") }
        return "${t("profileWeapons")}: $weapons. ${t("profileDefences")}: " +
            "${inv.medikits} ${t("medkitsPlural")}, ${inv.glueWalls} ${t("glueWallsPlural")}, " +
            "${inv.covers} ${t("coversPlural")}, ${inv.antiMissiles} ${t("antiMissilesPlural")}. " +
            "${t("extMissilePowers")} ${inv.missilePowers}. " +
            "${t("profileExtensions")}: $extensions."
    }

    fun speakInventory() {
        if (_currentScreen.value != Screen.BATTLE && _currentScreen.value != Screen.SHOP) return
        speak(profileInventorySpeech())
    }

    // ---------------------------------------------------------------------
    // Game flow
    // ---------------------------------------------------------------------

    fun startNewGame() {
        resetBattleState()
        navigateTo(Screen.READER)
    }

    fun continueToJump() {
        stopTimers()
        ttsManager.stop()
        audioManager.stopScreenAudio()
        playSound("click")
        _gameState.update { it.copy(log = t("jumpMsg")) }
        _currentScreen.value = Screen.JUMP
        updateAudioForScreen(Screen.JUMP)
        speak(t("jumpMsg"))
    }

    fun doJump() {
        stopTimers()
        stopAirplaneAudio()
        ttsManager.stop()
        playSound("jump")
        _gameState.update { it.copy(log = t("paraMsg")) }
        _currentScreen.value = Screen.PARA
        updateAudioForScreen(Screen.PARA)
        speak(t("paraMsg"))
    }

    fun doPara() {
        stopTimers()
        audioManager.stopSound("parachute")
        ttsManager.stop()
        _gameState.update { it.copy(log = t("landMsg")) }
        _currentScreen.value = Screen.LAND
        updateAudioForScreen(Screen.LAND)
        speak(t("landMsg"))
    }

    fun doLand() {
        stopTimers()
        ttsManager.stop()
        playSound("land")
        _gameState.update { it.copy(log = t("aim")) }
        _currentScreen.value = Screen.BATTLE
        updateAudioForScreen(Screen.BATTLE)
        // Nothing is spoken when the game screen starts. The hint stays on
        // screen only, so the battle always begins in silence.
        planRobertStep(1300L)
        startMatchTimeLimit()
        startRobertFireLoop(2600L)
    }

    /**
     * Arms the seven minute limit of the final round. Round five is the only
     * timed round: if the time runs out while Robert is still alive, the
     * player loses the game.
     */
    private fun startMatchTimeLimit() {
        matchLimitJob?.cancel()
        matchLimitJob = null
        val state = _gameState.value
        val limit = Progression.roundTimeLimitMs(state.round)
        if (limit <= 0L) {
            matchDeadlineMs = 0L
            return
        }
        matchDeadlineMs = System.currentTimeMillis() + limit
        _gameState.update {
            it.copy(battleLimitMs = limit, battleRemainingMs = limit)
        }
        matchLimitJob = viewModelScope.launch {
            var warnedTwoMinutes = false
            var warnedOneMinute = false
            var warnedThirtySeconds = false
            while (true) {
                delay(1_000L)
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val current = _gameState.value
                if (current.playerHp <= 0 || current.robertHp <= 0 || endingJob != null) break
                val remaining = (matchDeadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
                _gameState.update { it.copy(battleRemainingMs = remaining) }
                if (remaining <= 0L) {
                    matchDeadlineMs = 0L
                    _gameState.update { it.copy(battleLimitMs = 0L) }
                    setLog(t("timeUpDefeat"))
                    endGame(won = false, timeUp = true)
                    break
                }
                // Spoken warnings: two minutes, one minute and thirty seconds
                // left. Each is spoken exactly once.
                val seconds = remaining / 1_000L
                if (!warnedTwoMinutes && seconds <= 120L) {
                    warnedTwoMinutes = true
                    speak("${t("timeRemaining")} 2 ${t("minutes")}.")
                } else if (!warnedOneMinute && seconds <= 60L) {
                    warnedOneMinute = true
                    speak("${t("timeRemaining")} 1 ${t("minute")}.")
                } else if (!warnedThirtySeconds && seconds <= 30L) {
                    warnedThirtySeconds = true
                    speak("${t("timeRemaining")} 30 ${t("seconds")}.")
                }
            }
        }
    }

    private fun resetBattleState() {
        stopTimers()
        resultJob?.cancel()
        resultJob = null
        endingJob?.cancel()
        endingJob = null
        _resultReady.value = true
        audioManager.stopScreenAudio()
        movePressure = 0f
        moveDirection = null
        missileImpactAtMs = 0L
        bombBlastAtMs = 0L
        matchDeadlineMs = 0L
        lastWallAnnounceAtMs = 0L
        playerAtWall = false
        menuProtectUntilMs = 0L
        playerBurstStartedAtMs = 0L
        lastPlayerShotAtMs = 0L
        playerShotIntervalMs = RobertRules.DEFAULT_PLAYER_INTERVAL_MS
        lastHitSpeechAtMs = 0L
        lastFireAt = 0L

        val round = _stats.value.round.coerceIn(1, Progression.ROUNDS_PER_MATCH)
        val level = _stats.value.level.coerceIn(1, Progression.LEVELS_PER_ROUND)
        val profile = RobertRules.profile(
            round = round,
            level = level,
            playerOwnsSniper = _inventory.value.ownsWeapon(Weapon.SNIPER),
            playerGlueWalls = _inventory.value.glueWalls
        )
        val hp = Progression.hpFor(round, level)
        // Weapons handed out for free in this round are issued before the
        // battle rotation is built (the MP40 and the AWM from round two, the
        // grenade from round three).
        val issued = issueRoundWeapons(round)
        // Disabled weapons are never revealed by a left or right swipe, so the
        // battle rotation only contains owned AND enabled weapons.
        val owned = _inventory.value.battleWeapons(round).ifEmpty { Weapon.defaults() }
        val starting = _inventory.value.startingWeapon()
        val startIndex = owned.indexOfFirst { it.id == starting.id }.coerceAtLeast(0)

        _gameState.value = GameState(
            playerHp = hp,
            playerMaxHp = hp,
            robertHp = hp,
            robertMaxHp = hp,
            round = round,
            level = level,
            weapons = owned.map { it.id },
            weaponIndex = startIndex,
            ammo = owned.map { it.magazine },
            damaged = owned.map { false },
            distance = Combat.MAX_RANGE,
            // Both fighters start on the centre line at opposite edges of the
            // 100 m zone, exactly one full range apart.
            playerX = Arena.PLAYER_START_X,
            playerY = Arena.PLAYER_START_Y,
            robertX = Arena.ROBERT_START_X,
            robertY = Arena.ROBERT_START_Y,
            runSpeed = RunSpeed.fromId(_inventory.value.lastRunSpeedId).id,
            coverActive = false,
            coverHits = 0,
            glueWallActive = false,
            glueWallProgress = 0,
            robertCoverActive = false,
            robertCoverHits = 0,
            robertGlueWallActive = false,
            robertGlueWallProgress = 0,
            robertWeapons = profile.weapons.map { it.id },
            robertWeaponIndex = 0,
            robertAmmo = profile.weapons.map { it.magazine },
            robertDamaged = profile.weapons.map { false },
            robertMedkits = profile.medikits,
            robertCovers = profile.covers,
            robertGlueWalls = profile.glueWalls,
            robertDefenceArmed = true,
            robertConsecutiveHits = 0,
            robertExchangeShots = 0,
            robertLateral = 0f,
            robertState = "advance",
            robertTurnBackTicks = 0,
            missileInFlight = false,
            missileFromPlayer = false,
            missileInterceptPlanned = false,
            robertMissilePowers = profile.missilePowers,
            robertAntiMissiles = profile.antiMissiles,
            bombInFlight = false,
            bombFromPlayer = false,
            bombKind = "",
            bombX = Arena.PLAYER_START_X,
            bombY = Arena.PLAYER_START_Y,
            bombInterceptPlanned = false,
            robertBombs = profile.bombs,
            robertGrenades = profile.grenades,
            robertAtomBombs = profile.atomBombs,
            robertTimerBombs = profile.timerBombs,
            robertAntiBombs = profile.antiBombs,
            robertSuccessiveShots = 0,
            robertBurstLimit = RobertRules.burstLimit(Random),
            battleLimitMs = Progression.roundTimeLimitMs(round),
            battleRemainingMs = Progression.roundTimeLimitMs(round),
            timerSelectActive = false,
            timerIndex = BombRules.DEFAULT_TIMER_INDEX,
            autoFireActive = false,
            playerBurstShots = 0,
            playerBurstRobertHp = hp,
            playerBurstPlayerHp = hp,
            robertFireRange = RobertRules.preferredRange(Random).id,
            robertDeceptionShots = 0,
            robertHits = 0,
            robertShots = 0,
            robertDamage = 0,
            playerShots = 0,
            playerHits = 0,
            fightPlayerHits = 0,
            fightRobertHits = 0,
            consecutiveHeavyHits = 0,
            playerSecondChanceUsed = false,
            menuIndex = BattleMenuItem.RECOVER_INDEX,
            menuActive = false,
            pendingPurchaseId = null,
            log = t("ready"),
            matchWon = false
        )
        // The battle screen itself starts silently: an issued weapon is only
        // shown on screen and in the status, never spoken over the opening.
        if (issued.isNotEmpty()) {
            setLog(
                "${t("weaponIssued")}: " + issued.joinToString(", ") { weapon -> t(weapon.nameKey) }
            )
        }
        // Profiles that reached round four before this reward existed receive
        // their free anti-missile system once, silently, at the next battle.
        ensureAntiMissileReward(round)
    }

    /**
     * Completing round three unlocks the player's anti-missile system and
     * hands out one free interceptor. The flag lives in the inventory, so the
     * reward can never be collected twice.
     */
    private fun ensureAntiMissileReward(round: Int) {
        val current = _inventory.value
        if (!Progression.antiMissileRewardDue(round, current.antiMissileRewardGranted)) return
        val updated = Shop.claimAntiMissileReward(current) ?: return
        _inventory.value = updated
        prefs.saveInventory(updated)
        setLog(t("antiMissileReward"))
    }

    /**
     * Hands out the weapons the round provides for free: the MP40 and the AWM
     * from round two, and the grenade from round three. Weapons that are only
     * unlockable (the atom bomb, the timer bomb and the machine gun) are never
     * issued here, and no weapon is ever issued twice.
     */
    private fun issueRoundWeapons(round: Int): List<Weapon> {
        var updated = _inventory.value
        val issued = mutableListOf<Weapon>()
        for (weapon in Weapon.issuedForRound(round)) {
            if (updated.ownsWeapon(weapon)) continue
            val result = Shop.grantWeapon(updated, weapon)
            if (result.success) {
                updated = result.inventory
                issued.add(weapon)
            }
        }
        if (issued.isEmpty()) return emptyList()
        _inventory.value = updated
        prefs.saveInventory(updated)
        return issued
    }

    // ---------------------------------------------------------------------
    // Robert movement, 3D footsteps, continuous fire
    // ---------------------------------------------------------------------

    /**
     * Updates Robert's positional loop only while he is actually moving.
     * He faces the player head-on, so straight running always uses the
     * stored sideways offset (zero except mid-flank) and never alternates
     * between the ears. The playback rate follows his real speed.
     */
    private fun updateRobertWalkAudio(
        distance: Float,
        fleeing: Boolean = false,
        speedMps: Float = 0f
    ) {
        if (_currentScreen.value != Screen.BATTLE) {
            audioManager.stopSound("enemyWalk")
            return
        }
        audioManager.updateEnemyWalk3dAudio(
            distance = distance,
            lateralOffset = _gameState.value.robertLateral,
            speedMps = speedMps,
            fleeing = fleeing
        )
    }

    /** Stores the live touch pressure of the running hold. */
    fun updateMovePressure(pressure: Float) {
        movePressure = pressure
    }

    /** Robert's fast running speed for this round, in metres per second. */
    private fun enemyFastSpeedMps(): Float =
        RobertRules.enemyFastSpeedMps(_gameState.value.round)

    /**
     * The Max Speed extension: while it is owned and enabled every running
     * direction is 30 percent faster than the level chosen with the
     * three-finger gesture. It is exactly 1.0 while it is missing, disabled
     * or removed, so the three running levels stay untouched for everybody
     * who does not own it.
     */
    private fun maxSpeedFactor(): Float =
        if (_inventory.value.extensionEnabled(Extension.MAX_SPEED)) {
            Extension.MAX_SPEED_GAIN
        } else {
            1.0f
        }

    /**
     * The three-finger running speed gesture: swiping right steps one level
     * faster, swiping left one level slower, cycling slow / normal / fast.
     * The fast level matches Robert's own fast (fleeing) speed for the round
     * exactly, and the running sound gets louder or quieter with the level,
     * just like his footsteps do.
     */
    fun cycleRunSpeed(direction: Int) {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val current = _gameState.value.runSpeedLevel
        val next = if (direction >= 0) current.faster() else current.slower()
        playSound("click")
        if (next == current) {
            msg("${t("runSpeed")}: ${t(next.nameKey)}. ${t("runSpeedLimit")}")
            return
        }
        _gameState.update { it.copy(runSpeed = next.id) }
        val savedInventory = _inventory.value.copy(lastRunSpeedId = next.id)
        _inventory.value = savedInventory
        prefs.saveInventory(savedInventory)
        // Apply the new level to the live footstep loop immediately.
        val held = moveDirection
        if (held != null) {
            val speedMps = next.speedMps(
                RunPressure.speedFactor(movePressure),
                enemyFastSpeedMps()
            ) * maxSpeedFactor()
            audioManager.updatePlayerWalkAudio(speedMps, next.volumeGain())
        }
        val maxSpeedNote = if (_inventory.value.extensionEnabled(Extension.MAX_SPEED)) {
            " ${t("maxSpeedOn")}"
        } else {
            ""
        }
        msg("${t("runSpeed")}: ${t(next.nameKey)}.$maxSpeedNote")
    }

    /**
     * Continuous player movement inside the 100 m battle zone, in all four
     * directions:
     *  - forward / backward: towards or away from Robert (top and bottom
     *    portions of the battle screen);
     *  - left / right: along the width of the zone (the two halves of the
     *    center portion).
     *
     * The speed comes from the three-finger level (slow, normal, fast) and, in
     * the normal level, from how strongly the screen is pressed. The running
     * sound follows the same level: faster and louder when running fast,
     * slower and quieter when running slow. Touching one of the four
     * boundaries stops the run, plays the wall sound and announces "Wall";
     * nobody ever gets beyond the 100 m perimeter.
     */
    fun startPlayerMove(direction: MoveDirection) {
        if (_currentScreen.value != Screen.BATTLE) return
        playerMoveJob?.cancel()
        moveDirection = direction
        playerMoveJob = viewModelScope.launch {
            while (true) {
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val state = _gameState.value
                if (state.playerHp <= 0 || state.robertHp <= 0) break

                val speed = state.runSpeedLevel
                val factor = speed.factor(RunPressure.speedFactor(movePressure), enemyFastSpeedMps()) *
                    maxSpeedFactor()
                val speedMps = RunPressure.BASE_SPEED_MPS * factor
                val tickMs = (PLAYER_MOVE_TICK_MS * (RunPressure.BASE_SPEED_MPS / speedMps))
                    .toLong()
                    .coerceIn(MIN_MOVE_TICK_MS, MAX_MOVE_TICK_MS)
                val stepMetres = speedMps * (tickMs / 1000f)

                var blocked = false
                _gameState.update { current ->
                    val move = if (direction.lateral) {
                        current.movePlayerLateral(direction == MoveDirection.RIGHT, stepMetres)
                    } else {
                        current.movePlayer(direction == MoveDirection.FORWARD, stepMetres)
                    }
                    blocked = move.blockedByWall
                    if (move.moved) move.state else current
                }

                if (blocked) {
                    // A boundary of the 100 m zone: no movement, wall sound.
                    audioManager.stopSound("playerWalk")
                    announceWall()
                    playerAtWall = true
                    delay(tickMs)
                    continue
                }
                playerAtWall = false

                // Our own footsteps are centred at the speech level, follow the
                // running speed and get louder or quieter with the speed level.
                audioManager.updatePlayerWalkAudio(speedMps, speed.volumeGain())
                if (stepJob == null || stepJob?.isActive != true) {
                    planRobertStep(80L)
                }
                delay(tickMs)
            }
        }
    }

    /**
     * The boundary announcement: the wall impact sound, the word "Wall" spoken
     * aloud, and a hint on screen.
     *
     * One impact is one sound. The clip is cut and started at most once per
     * [WALL_ANNOUNCE_COOLDOWN_MS], which is what stops a finger held against
     * a boundary from retriggering it on every movement tick - that retrigger
     * is exactly what used to smear the impact into a continuous, stretched
     * wall noise. The spoken word is repeated even less often: only when the
     * run is *newly* stopped, so a long hold stays quiet.
     */
    private fun announceWall() {
        val now = System.currentTimeMillis()
        if (now - lastWallAnnounceAtMs < WALL_ANNOUNCE_COOLDOWN_MS) return
        lastWallAnnounceAtMs = now
        audioManager.stopSound("wall")
        playSound("wall", volume = WALL_VOLUME)
        if (playerAtWall) return
        setLog(t("wallBlocked"))
        speak(t("wallSpeech"), interrupt = true)
    }

    fun stopPlayerMove() {
        playerMoveJob?.cancel()
        playerMoveJob = null
        moveDirection = null
        movePressure = 0f
        playerAtWall = false
        audioManager.stopSound("playerWalk")
    }

    private fun planRobertStep(delayMs: Long) {
        stepJob?.cancel()
        stepJob = viewModelScope.launch {
            delay(delayMs)
            robertStep()
        }
    }

    /**
     * Wakes Robert's movement loop whenever his situational target band shifts
     * (for example after gaining an advantage or when a glue wall or damaged
     * weapon forces him into close range).
     */
    private fun ensureRobertPositioned(delayMs: Long = 180L) {
        val state = _gameState.value
        if (state.playerHp <= 0 || state.robertHp <= 0 || state.missileInFlight) return
        if (stepJob?.isActive == true) return
        if (abs(state.distance - robertTargetDistance(state)) > BAND_TOLERANCE_M) {
            planRobertStep(delayMs)
        }
    }

    /**
     * True while the weapon in the player's hands is damaged. Robert answers
     * that by closing in to the 90%-100% band and firing continuously, until
     * the player switches to a working weapon or pays for Recovery.
     */
    private fun isPlayerWeaponLocked(state: GameState): Boolean = state.activeWeaponDamaged

    /**
     * True while Robert is allowed to stop repositioning and press the
     * player: his own barrier is up while the player's is not, the player's
     * weapon is damaged, or the player is extremely weak.
     */
    private fun robertPressing(state: GameState): Boolean =
        RobertRules.shouldPressFromDefence(
            enemyHasDefence = state.robertHasActiveDefence,
            playerHasDefence = state.playerHasActiveDefence,
            playerWeaponDamaged = isPlayerWeaponLocked(state),
            playerHpFraction = playerHpFraction(state)
        )

    /**
     * The band Robert holds right now: a damaged weapon, a protected Robert
     * or a glue wall forces point blank; otherwise whoever is winning decides
     * - an advantaged Robert pushes into the 70%-90% or 90%-100% band, a
     * losing one falls back to 40%-70%.
     */
    private fun currentFireRange(state: GameState): FireRange {
        // Protected Robert closes into the 90%-100% band and keeps firing:
        // a cover or glue wall of his own is exactly the moment to press a
        // defenceless player instead of running away.
        if (robertPressing(state) && !state.playerHasActiveDefence) {
            return FireRange.CLOSE
        }
        return RobertRules.situationalRange(
            stored = state.robertRange,
            playerWeaponDamaged = isPlayerWeaponLocked(state),
            playerGlueWallActive = state.glueWallActive,
            robertHp = state.robertHp,
            robertMaxHp = state.robertMaxHp,
            playerHp = state.playerHp,
            playerMaxHp = state.playerMaxHp,
            deceptionShotsLeft = state.robertDeceptionShots,
            robertHasSniper = robertHasUsableSniper(state),
            playerWeapon = state.activeWeapon
        )
    }

    /** True when Robert owns a sniper rifle he may use and that is not damaged. */
    private fun robertHasUsableSniper(state: GameState): Boolean {
        val allowed = RobertRules.allowedWeapons(robertProfile, state.glueWallActive, state.robertHp)
        return RobertRules.hasUsableSniper(allowed, isRobertWeaponDamaged(state, Weapon.SNIPER))
    }

    /** The arena distance Robert is walking to for his current band. */
    private fun robertTargetDistance(state: GameState): Float =
        currentFireRange(state).targetDistance

    /**
     * Picks the band Robert fights from next. He randomly prefers either the
     * 40%-70% band or the 90%-100% band, and once in a while he goes to the
     * 0%-40% band instead to fire one or two shots that always miss, purely
     * to confuse the real player.
     */
    private fun rollRobertFireRange(allowDeception: Boolean) {
        val deception = allowDeception && RobertRules.shouldStartDeception(Random)
        val range = if (deception) FireRange.FAR else RobertRules.preferredRange(Random)
        val shots = if (deception) RobertRules.deceptionShots(Random) else 0
        _gameState.update {
            it.copy(robertFireRange = range.id, robertDeceptionShots = shots)
        }
    }

    /**
     * Starts one of Robert's sprints: [RobertRules.SPRINT_TICKS] ticks of
     * running at twice the player's top speed, far away from the fight.
     *
     * Every sprint clears the current hit exchange, so the next fight is
     * judged on its own: two player hits against one of Robert's always make
     * him run, and he only stands his ground again once he is landing the
     * greater number of hits.
     */
    private fun beginRobertSprint() {
        // A sprint is a movement decision, so cut the enemy's long
        // automatic-weapon tail immediately.
        _gameState.value.robertWeapon?.fireSoundKey?.let(audioManager::stopSound)
        audioManager.stopSound("mp40")
        audioManager.stopSound("machineGun")
        _gameState.update {
            it.copy(
                robertState = "retreat",
                robertConsecutiveHits = 0,
                robertExchangeShots = 0,
                fightPlayerHits = 0,
                fightRobertHits = 0,
                robertLateral = 0f
            )
        }
        robertSprintTicks = RobertRules.SPRINT_TICKS
        rollRobertFireRange(allowDeception = true)
        planRobertStep(120L)
    }

    /** Result of one of Robert's movement steps. */
    private data class StepOutcome(val state: GameState, val blocked: Boolean)

    /**
     * Robert's movement step. He really walks inside the 100 m zone with all
     * four directions available (towards the player, away from him and both
     * sideways), and every single step is simulated against the four
     * boundaries *before* it is taken ([GameState.moveRobertSafely]). The
     * moment a heading would end on - or even close to - a boundary he
     * silently cuts sideways, turns back or heads for the open middle of the
     * zone, so an automated fighter never collides with a wall. Only the
     * player, who is steered by hand, can ever touch a boundary: that is why
     * the wall impact sound belongs to the player alone.
     *
     * States:
     *  - "advance":     walk to the distance his current firing band wants;
     *  - "retreat":     sprint far away at twice the player's running speed;
     *  - "flank":       re-approach from the side after a finished sprint;
     *  - "avoid":       a glue wall he cannot break: keep away until it drops;
     *  - "missileFlee": sprint from a missile in the air;
     *  - "bombFlee":    sprint out of the 50 m blast radius of a bomb;
     *  - "turnBack":    a heading change: he walks back into the open zone.
     */
    private fun robertStep() {
        if (!foreground || _currentScreen.value != Screen.BATTLE) return
        val state = _gameState.value
        if (state.robertHp <= 0 || state.playerHp <= 0) return

        // A damaged weapon in the player's hands stops every retreat: Robert
        // turns around and walks straight back to point blank. Nothing stops a
        // missile or a bomb escape: those runs continue until they resolve.
        val pressure = isPlayerWeaponLocked(state)
        val running = state.robertState
        if (pressure && running != "advance" && running != "missileFlee" && running != "bombFlee") {
            _gameState.update {
                it.copy(robertState = "advance", robertTurnBackTicks = 0)
            }
            robertSprintTicks = 0
        }

        // While the player's glue wall is up and Robert has no sniper rifle to
        // break it, he refuses to approach and keeps running away instead.
        ensureGlueWallAvoidance()

        val round = state.round
        val speedMultiplier = RobertRules.movementSpeedMultiplier(round)
        when (_gameState.value.robertState) {
            "turnBack" -> {
                val metres = RobertRules.TURN_BACK_STEP_M * speedMultiplier
                _gameState.update { turnBackStep(it, metres) }
                val after = _gameState.value
                if (after.robertState == "turnBack") {
                    updateRobertWalkAudio(after.distance, fleeing = true, speedMps = metres / 0.3f)
                    planRobertStep(300L)
                } else {
                    audioManager.stopSound("enemyWalk")
                    rollRobertFireRange(allowDeception = false)
                    ensureRobertPositioned()
                }
            }
            "bombFlee" -> {
                val metres = BombRules.escapeStep(round)
                _gameState.update { bombFleeStep(it, metres) }
                val after = _gameState.value
                if (after.robertState == "bombFlee") {
                    updateRobertWalkAudio(
                        after.distance,
                        fleeing = true,
                        speedMps = metres / (BombRules.BOMB_FLEE_TICK_MS / 1000f)
                    )
                    planRobertStep(BombRules.BOMB_FLEE_TICK_MS)
                } else {
                    audioManager.stopSound("enemyWalk")
                }
            }
            "missileFlee" -> {
                // A missile is in the air: he sprints for the far edge of the
                // zone at his fast speed. It will still catch him.
                val metres = MissileRules.fleeStep(round, FLEE_TICK_MS)
                val fleeing = runAwayAndUpdate(metres, keepState = "missileFlee")
                if (fleeing) {
                    val after = _gameState.value
                    updateRobertWalkAudio(
                        after.distance,
                        fleeing = true,
                        speedMps = metres / (FLEE_TICK_MS / 1000f)
                    )
                    planRobertStep(FLEE_TICK_MS)
                } else {
                    audioManager.stopSound("enemyWalk")
                }
            }
            "avoid" -> {
                // He cannot break the glue wall: stay as far away as possible.
                val metres = RobertRules.fleeStep(round)
                val avoiding = runAwayAndUpdate(metres, keepState = "avoid")
                val after = _gameState.value
                if (!avoiding) {
                    audioManager.stopSound("enemyWalk")
                    ensureRobertPositioned()
                } else if (after.distance >= Arena.SIZE - BAND_TOLERANCE_M) {
                    // The far edge is reached: hold it until the wall drops.
                    audioManager.stopSound("enemyWalk")
                    planRobertStep(AVOID_HOLD_MS)
                } else {
                    updateRobertWalkAudio(
                        after.distance,
                        fleeing = true,
                        speedMps = RobertRules.enemyFleeSpeedMps(round)
                    )
                    planRobertStep(FLEE_TICK_MS)
                }
            }
            "retreat" -> {
                // A full sprint: [RobertRules.SPRINT_TICKS] of running at
                // twice the player's top speed before he comes back around.
                val metres = RobertRules.fleeStep(round)
                val strafing = RobertRules.shouldStrafe(Random)
                var blocked = false
                _gameState.update { current ->
                    val outcome = if (strafing) {
                        strafeStep(current, metres, RobertRules.strafeDirection(Random))
                    } else {
                        runAwayStep(current, metres)
                    }
                    blocked = outcome.blocked
                    outcome.state
                }
                if (blocked) {
                    startRobertRehead()
                    audioManager.stopSound("enemyWalk")
                    return
                }
                robertSprintTicks = (robertSprintTicks - 1).coerceAtLeast(0)
                val after = _gameState.value
                if (robertSprintTicks > 0) {
                    updateRobertWalkAudio(
                        after.distance,
                        fleeing = true,
                        speedMps = RobertRules.enemyFleeSpeedMps(round)
                    )
                    planRobertStep(FLEE_TICK_MS)
                } else {
                    // The sprint is over: he comes back from a new angle.
                    audioManager.stopSound("enemyWalk")
                    _gameState.update {
                        it.copy(
                            robertState = "flank",
                            robertFlankSide = if (Random.nextBoolean()) 1 else -1
                        )
                    }
                    rollRobertFireRange(allowDeception = true)
                    planRobertStep(FLANK_DELAY_MS + Random.nextLong(400L))
                }
            }
            "flank" -> {
                val current = _gameState.value
                val target = robertTargetDistance(current)
                val speed = (4.5f + round * 0.35f) * speedMultiplier
                var blocked = false
                _gameState.update { now ->
                    val side = now.robertFlankSide
                    val strafed = strafeStep(now, speed * 0.6f, side)
                    val base = if (strafed.blocked) now else strafed.state
                    val wanted = max(target, base.distance - speed * 0.4f)
                    val outcome = approachStep(base, max(0f, base.distance - wanted))
                    blocked = outcome.blocked
                    if (blocked) base else outcome.state
                }
                if (blocked) {
                    startRobertRehead()
                    audioManager.stopSound("enemyWalk")
                    return
                }
                val after = _gameState.value
                if (after.distance <= target + BAND_TOLERANCE_M) {
                    _gameState.update { it.copy(robertState = "advance", robertTurnBackTicks = 0) }
                    audioManager.stopSound("enemyWalk")
                    msg(t("devilFlank"))
                } else {
                    updateRobertWalkAudio(after.distance, fleeing = false, speedMps = speed / 0.3f)
                    planRobertStep(300L)
                }
            }
            else -> {
                val current = _gameState.value
                val target = robertTargetDistance(current)
                val delta = current.distance - target
                if (abs(delta) <= BAND_TOLERANCE_M) {
                    // Robert has reached his firing band and is stationary.
                    audioManager.stopSound("enemyWalk")
                    return
                }
                val prox = Combat.proximityOf(current.distance) / 100f
                val speed = (2.0f + prox * 4.0f + round * 0.25f) *
                    speedMultiplier *
                    if (pressure) PRESSURE_SPEED_FACTOR else 1f
                val metres = min(abs(delta), speed)
                val tickMs = if (pressure) PRESSURE_ADVANCE_TICK_MS else ADVANCE_TICK_MS

                // All four directions: most steps walk the line to the player,
                // but once in a while he strafes sideways around him.
                val strafe = RobertRules.shouldStrafe(Random) && !pressure
                val side = RobertRules.strafeDirection(Random)
                var blocked = false
                _gameState.update { now ->
                    val outcome = when {
                        strafe -> strafeStep(now, metres, side)
                        delta > 0f -> approachStep(now, metres)
                        else -> runAwayStep(now, metres, keepState = "advance")
                    }
                    blocked = outcome.blocked
                    outcome.state
                }
                if (blocked) {
                    startRobertRehead()
                    return
                }
                val after = _gameState.value
                updateRobertWalkAudio(
                    after.distance,
                    fleeing = delta < 0f,
                    speedMps = metres / (tickMs / 1000f)
                )
                planRobertStep(tickMs)
            }
        }
    }

    /** Runs one "away from the player" step; false when a wall stopped him. */
    private fun runAwayAndUpdate(metres: Float, keepState: String): Boolean {
        var blocked = false
        _gameState.update { current ->
            val outcome = runAwayStep(current, metres, keepState)
            blocked = outcome.blocked
            outcome.state
        }
        if (blocked) startRobertRehead()
        return !blocked && _gameState.value.robertState == keepState
    }

    /**
     * Robert detected a wall: he changes heading at once and walks back into
     * the open zone for a few ticks before fighting on.
     *
     * Because every step of his is simulated against the perimeter before it
     * is taken ([GameState.moveRobertSafely]), this is only ever a safety net
     * and it is completely silent: the enemy never collides with a boundary,
     * so the wall impact sound - a sound of the manually steered player -
     * never plays on the enemy side.
     */
    private fun startRobertRehead() {
        _gameState.update {
            it.copy(
                robertState = "turnBack",
                robertTurnBackTicks = RobertRules.TURN_BACK_TICKS
            )
        }
        robertSprintTicks = 0
        setLog(t("robertTurnedBack"))
        planRobertStep(280L)
    }

    /** Walks back towards the middle of the zone until the wall is behind him. */
    private fun turnBackStep(current: GameState, metres: Float): GameState {
        val ticks = (current.robertTurnBackTicks - 1).coerceAtLeast(0)
        val move = current.moveRobertSafely(
            Arena.SIZE / 2f - current.robertX,
            Arena.SIZE / 2f - current.robertY,
            metres
        )
        val base = if (move.moved) move.state else current
        return if (ticks <= 0) {
            base.copy(robertState = "advance", robertTurnBackTicks = 0)
        } else {
            base.copy(robertTurnBackTicks = ticks)
        }
    }

    /** One step straight towards the player (closing the distance). */
    private fun approachStep(current: GameState, metres: Float): StepOutcome {
        val move = current.moveRobertSafely(
            current.playerX - current.robertX,
            current.playerY - current.robertY,
            metres
        )
        return StepOutcome(if (move.moved) move.state else current, move.blockedByWall)
    }

    /** One sideways step around the player: left or right along the zone. */
    private fun strafeStep(current: GameState, metres: Float, side: Int): StepOutcome {
        val dx = current.playerX - current.robertX
        val dy = current.playerY - current.robertY
        val move = current.moveRobertSafely(-dy * side, dx * side, metres)
        return StepOutcome(if (move.moved) move.state else current, move.blockedByWall)
    }

    /**
     * One step away from the player.
     *
     * A wall can never stop him: [GameState.moveRobertSafely] swaps the
     * heading for a clear one before the step is taken, so the run simply
     * continues along the open ground - sideways, backwards or into the
     * middle of the zone - instead of ending in a collision.
     */
    private fun runAwayStep(
        current: GameState,
        metres: Float,
        keepState: String = "retreat"
    ): StepOutcome {
        val move = current.moveRobertSafely(
            current.robertX - current.playerX,
            current.robertY - current.playerY,
            metres
        )
        return if (move.moved) {
            StepOutcome(move.state.copy(robertState = keepState), blocked = false)
        } else {
            StepOutcome(
                current.copy(
                    robertState = "turnBack",
                    robertTurnBackTicks = RobertRules.TURN_BACK_TICKS
                ),
                blocked = true
            )
        }
    }

    /**
     * Sprint out of a bomb's 50 m blast radius, in whichever direction really
     * increases the distance from the destination. If the bomb explodes before
     * he crosses the line, the blast kills him. A wall never stops the run:
     * the heading is corrected before the step, so he keeps looking for open
     * ground until the blast.
     */
    private fun bombFleeStep(current: GameState, metres: Float): GameState {
        if (!current.bombInFlight) return current.copy(robertState = "advance")
        if (current.robertDistanceFromBomb >= BombRules.SAFE_DISTANCE) {
            return current.copy(robertState = "advance", robertTurnBackTicks = 0)
        }
        val escape = Arena.escapeDirection(
            current.bombX, current.bombY, current.robertX, current.robertY
        )
        val move = current.moveRobertSafely(escape.first, escape.second, metres)
        return if (move.moved) {
            move.state.copy(robertState = "bombFlee")
        } else {
            // Nowhere left to run: he changes heading and takes his chances.
            current.copy(
                robertState = "turnBack",
                robertTurnBackTicks = RobertRules.TURN_BACK_TICKS
            )
        }
    }

    /**
     * Keeps the "avoid" state in sync with the glue wall: while the player's
     * glue wall is up and Robert owns no usable sniper rifle to break it, he
     * runs away and stays away. As soon as the wall drops (or he does own a
     * working sniper) he approaches again and fires continuously.
     */
    private fun ensureGlueWallAvoidance() {
        val state = _gameState.value
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        if (state.missileInFlight || state.bombInFlight) return
        val allowed = RobertRules.allowedWeapons(robertProfile, state.glueWallActive, state.robertHp)
        val sniperDamaged = isRobertWeaponDamaged(state, Weapon.SNIPER)
        val hasSniper = RobertRules.hasUsableSniper(allowed, sniperDamaged)
        val shouldAvoid = RobertRules.shouldAvoidGlueWall(state.glueWallActive, hasSniper)
        val avoiding = state.robertState == "avoid"
        if (shouldAvoid && !avoiding && state.robertState != "turnBack") {
            _gameState.update {
                it.copy(robertState = "avoid", robertDeceptionShots = 0, robertTurnBackTicks = 0)
            }
            rollRobertFireRange(allowDeception = false)
            msg(t("robertAvoidingGlueWall"))
            planRobertStep(140L)
        } else if (!shouldAvoid && avoiding) {
            _gameState.update { it.copy(robertState = "advance", robertTurnBackTicks = 0) }
            msg(t("robertApproachingAgain"))
            ensureRobertPositioned()
        }
    }

    private fun startRobertFireLoop(initialDelayMs: Long) {
        robertFireJob?.cancel()
        robertFireJob = viewModelScope.launch {
            delay(initialDelayMs)
            while (true) {
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val state = _gameState.value
                if (state.playerHp <= 0 || state.robertHp <= 0) break
                robertAttack()
                delay(robertFireInterval())
            }
        }
    }

    private fun isRobertWeaponDamaged(state: GameState, weapon: Weapon): Boolean {
        val index = state.robertWeapons.indexOf(weapon.id)
        if (index < 0) return false
        return state.robertDamaged.getOrElse(index) { false }
    }

    /**
     * The delay before Robert's next shot.
     *
     *  - while the timer picker is open he holds his fire completely (a short
     *    internal tick keeps the loop alive without shooting);
     *  - while he is fleeing he keeps a tactical 1.5 s or 2 s beat;
     *  - while an exchange is running - or while he is coverless against a
     *    covered player - he answers at exactly the player's own cadence,
     *    never slower than one second in the coverless case;
     *  - in every other case he presses only when the player's weapon is
     *    damaged or the player is extremely weak, and otherwise waits a
     *    random 1.5 s to 2 s, a bit longer while he is running.
     */
    private fun robertFireInterval(): Long {
        val state = _gameState.value
        // The player is choosing a timer: Robert does not fire at all until
        // the choice is locked in or the picker is dismissed.
        if (state.timerSelectActive) return TIMER_SELECTION_HOLD_MS
        val fleeing = state.robertFleeing ||
            state.robertState == "missileFlee" ||
            state.robertState == "bombFlee" ||
            state.robertState == "avoid"
        if (fleeing) return RobertRules.normalTacticalInterval(Random)
        // The enemy answers with the player's own rhythm. Face to face with
        // no cover, only a gloo wall, or only a cover, he fires exactly as
        // fast as the player; coverless against a covered player he never
        // fires slower than once a second and matches any faster cadence.
        if (mirrorDuelActive(state)) {
            return RobertRules.mirroredFireInterval(
                playerIntervalMs = playerShotIntervalMs,
                enemyHasCover = state.robertCoverActive,
                playerHasCover = state.coverActive
            )
        }
        val situation = RobertRules.assessSituation(
            state.robertHp, state.robertMaxHp, state.playerHp, state.playerMaxHp,
            isPlayerWeaponLocked(state)
        )
        return RobertRules.tacticalFireInterval(
            enemyHasDefence = state.robertHasActiveDefence,
            playerHasDefence = state.playerHasActiveDefence,
            playerWeaponDamaged = isPlayerWeaponLocked(state),
            playerHpFraction = playerHpFraction(state),
            random = Random,
            enemyHasAdvantage = situation != RobertRules.CombatSituation.DISADVANTAGE
        )
    }

    /** The player's health as a fraction of their own maximum. */
    private fun playerHpFraction(state: GameState): Float =
        if (state.playerMaxHp <= 0) 0f else state.playerHp.toFloat() / state.playerMaxHp.toFloat()

    /** True while the player's health went down inside the current burst. */
    private fun playerHealthDropping(state: GameState): Boolean =
        state.playerBurstShots > 0 && state.playerHp < state.playerBurstPlayerHp

    /** True while Robert's health went down inside the current burst. */
    private fun robertHealthDropping(state: GameState): Boolean =
        state.playerBurstShots > 0 && state.robertHp < state.playerBurstRobertHp

    /**
     * Learns the player's real firing rhythm.
     *
     * Every shot is timed and folded into a running average, so Robert can
     * answer a 0.5 second burst, a 0.25 second burst or any other cadence
     * with exactly the same rhythm instead of a fixed clock. Gaps below
     * [RobertRules.MIN_PLAYER_INTERVAL_MS] (one tap producing two events) and
     * above [RobertRules.MAX_MIRRORED_INTERVAL_MS] (two unrelated single
     * shots) are ignored, so a stray event can never wreck the average.
     */
    private fun recordPlayerShotCadence(now: Long) {
        val previous = lastPlayerShotAtMs
        lastPlayerShotAtMs = now
        if (previous <= 0L) return
        val gap = now - previous
        if (gap < RobertRules.MIN_PLAYER_INTERVAL_MS) return
        if (gap > RobertRules.MAX_MIRRORED_INTERVAL_MS) {
            // A long pause starts a fresh rhythm on the next shot.
            playerShotIntervalMs = RobertRules.DEFAULT_PLAYER_INTERVAL_MS
            return
        }
        playerShotIntervalMs = (playerShotIntervalMs + gap) / 2L
    }

    /** True while the player is still inside his current firing exchange. */
    private fun playerFiringNow(): Boolean =
        lastPlayerShotAtMs > 0L &&
            System.currentTimeMillis() - lastPlayerShotAtMs <= RobertRules.MIRROR_EXCHANGE_WINDOW_MS

    /**
     * True while Robert must trade shots at the player's own cadence: while
     * an exchange is running, or whenever he is coverless and the player is
     * standing behind a cover (he then presses at one second or faster).
     */
    private fun mirrorDuelActive(state: GameState): Boolean =
        RobertRules.mirrorsPlayerCadence(
            playerFiringNow = playerFiringNow(),
            enemyHasCover = state.robertCoverActive,
            playerHasCover = state.coverActive
        )

    /**
     * Registers one player shot in the burst counter that Robert reads. Shots
     * further apart than [RobertRules.PLAYER_BURST_WINDOW_MS] start a new
     * burst, and the health both fighters had at the start of the burst is
     * remembered so Robert can tell whose health is really dropping.
     * If the player starts firing back continuously, Robert retreats immediately.
     */
    private fun registerPlayerBurstShot() {
        val now = System.currentTimeMillis()
        recordPlayerShotCadence(now)
        var shouldRetreat = false
        _gameState.update { state ->
            val fresh = now - playerBurstStartedAtMs > RobertRules.PLAYER_BURST_WINDOW_MS
            if (fresh) playerBurstStartedAtMs = now
            val newShots = if (fresh) 1 else state.playerBurstShots + 1
            shouldRetreat = RobertRules.shouldRetreatWhenPlayerFiresBack(newShots)
            state.copy(
                playerBurstShots = newShots,
                playerBurstRobertHp = if (fresh) state.robertHp else state.playerBurstRobertHp,
                playerBurstPlayerHp = if (fresh) state.playerHp else state.playerBurstPlayerHp
            )
        }
        if (shouldRetreat) {
            val state = _gameState.value
            if (state.robertState == "advance" || state.robertState == "flank") {
                beginRobertSprint()
            }
        }
    }

    /** Drops a burst that has gone quiet, so Robert stops answering it. */
    private fun expirePlayerBurstIfStale() {
        val state = _gameState.value
        if (state.playerBurstShots <= 0) return
        if (System.currentTimeMillis() - playerBurstStartedAtMs <= RobertRules.PLAYER_BURST_WINDOW_MS) return
        _gameState.update {
            it.copy(
                playerBurstShots = 0,
                playerBurstRobertHp = it.robertHp,
                playerBurstPlayerHp = it.playerHp
            )
        }
    }

    /**
     * Robert's attack. It picks one of the weapons it is allowed to use right
     * now (switching often to confuse the player), reloads after six shots
     * without paying coins, and obeys the exact same proximity zones.
     *
     * [retaliate] marks the answering shot of [robertOnHit]: it bypasses
     * every "maybe run instead of shooting" roll, because the one thing an
     * enemy under fire must never do is stand there and take it. Only a
     * projectile in the air, the timer picker or a gloo wall he cannot break
     * can still keep him quiet.
     */
    private fun robertAttack(retaliate: Boolean = false) {
        if (!foreground || _currentScreen.value != Screen.BATTLE) return
        var state = _gameState.value
        if (state.robertHp <= 0 || state.playerHp <= 0) return
        // While the player is choosing a timer bomb duration he neither fires
        // nor lands a hit: only a locked-in choice or a dismissed picker lets
        // him start again.
        if (state.timerSelectActive) return
        // While a missile is in the air all normal fire is suspended: the
        // target is running for its life and the outcome is already sealed.
        if (state.missileInFlight) return
        // The same holds for a bomb counting down towards him: he only runs.
        if (state.bombInFlight && state.bombFromPlayer) return
        expirePlayerBurstIfStale()
        // An exchange is running (or he is coverless against a covered
        // player): he trades shots at the player's own cadence instead of
        // pausing after one or two bullets or running away.
        val mirrorDuel = mirrorDuelActive(state)

        val situation = RobertRules.assessSituation(
            robertHp = state.robertHp,
            robertMaxHp = state.robertMaxHp,
            playerHp = state.playerHp,
            playerMaxHp = state.playerMaxHp,
            playerWeaponDamaged = isPlayerWeaponLocked(state)
        )
        // A retreat is movement, not a hidden opportunity to keep shooting -
        // unless the shot is a retaliation: an enemy that is being shot at
        // answers over his shoulder while he runs.
        if (!retaliate &&
            state.robertState in setOf("retreat", "avoid", "missileFlee", "bombFlee")
        ) return
        if (!retaliate && !mirrorDuel &&
            state.robertState == "turnBack" &&
            !RobertRules.isContinuousPlayerFire(state.playerBurstShots)
        ) return
        val playerWeakFraction = playerHpFraction(state)
        val pressing = robertPressing(state)

        // Hit-exchange rule: as soon as the player lands more hits than
        // Robert in this exchange (two against one, for example) Robert runs
        // away immediately. A protected Robert is the one exception - he
        // keeps firing from behind his barrier.
        if (!retaliate && !pressing && !mirrorDuel && (
                RobertRules.shouldFleeFight(state.fightPlayerHits, state.fightRobertHits) ||
                    (situation == RobertRules.CombatSituation.DISADVANTAGE &&
                        (state.robertState == "advance" || state.robertState == "flank"))
            )
        ) {
            beginRobertSprint()
            return
        }

        // A finished burst is never extended: he fired one or two shots, so
        // now he pauses and very often repositions instead of shooting again.
        // Only the pressing situations are allowed to fire continuously.
        if (!retaliate && !pressing && !mirrorDuel &&
            RobertRules.burstFinished(state.robertSuccessiveShots, state.robertBurstLimit)
        ) {
            _gameState.update {
                it.copy(
                    robertSuccessiveShots = 0,
                    robertBurstLimit = RobertRules.burstLimit(Random)
                )
            }
            val fresh = _gameState.value
            if (RobertRules.shouldRunAfterBurst(Random) &&
                (fresh.robertState == "advance" || fresh.robertState == "flank")
            ) {
                beginRobertSprint()
            }
            return
        }

        // He runs as readily as he shoots: unless the player is pinned down,
        // a good share of his decisions is a reposition instead of a shot.
        if (!retaliate && !pressing && !mirrorDuel && RobertRules.shouldRepositionInsteadOfFiring(
                situation = situation,
                playerWeaponDamaged = isPlayerWeaponLocked(state),
                playerHpFraction = playerWeakFraction,
                random = Random
            )
        ) {
            if (state.robertState == "advance" || state.robertState == "flank") {
                beginRobertSprint()
                return
            }
        }

        // Round four and later he may spend his one missile power, but only
        // as a desperation weapon while he is clearly losing.
        if (
            MissileRules.shouldEnemyLaunch(
                round = state.round,
                missilePowers = state.robertMissilePowers,
                robertHp = state.robertHp,
                robertMaxHp = state.robertMaxHp,
                playerHp = state.playerHp,
                playerMaxHp = state.playerMaxHp,
                robertShots = state.robertShots,
                missileInFlight = state.missileInFlight
            )
        ) {
            launchEnemyMissile()
            return
        }

        // From round three he also carries bombs: twice-used grenades in round
        // three, all three bombs three times each in round four and unlimited
        // bombs in round five. Like his missile they are desperation weapons.
        if (
            BombRules.shouldEnemyThrow(
                round = state.round,
                bombsLeft = state.robertBombs,
                robertHp = state.robertHp,
                robertMaxHp = state.robertMaxHp,
                playerHp = state.playerHp,
                playerMaxHp = state.playerMaxHp,
                robertShots = state.robertShots,
                bombInFlight = state.bombInFlight,
                missileInFlight = state.missileInFlight
            )
        ) {
            launchEnemyBomb()
            return
        }

        val allowed = RobertRules.allowedWeapons(
            robertProfile,
            state.glueWallActive,
            state.robertHp
        ).ifEmpty { robertProfile.weapons }
        var pool = allowed.filter { !isRobertWeaponDamaged(state, it) }
        if (pool.isEmpty()) {
            // Every weapon is broken: Robert repairs his arsenal for free.
            _gameState.update { it.copy(robertDamaged = it.robertDamaged.map { false }) }
            state = _gameState.value
            pool = allowed
        }
        val proximityNow = Combat.proximityOf(state.distance)
        // Panic check: falling health really spends one of his defences at
        // once instead of only arming a flag nobody reads.
        robertPanicDefence(state)?.let { defence ->
            if (deployRobertDefence(defence).isNotBlank()) {
                state = _gameState.value
            }
        }
        // Smart weapon selection based on health bands: 40-70%, 90-100%, 20-40%
        // If enemy feels unsafe (low health or heavy damage streak) he retreats.
        if (!retaliate && !mirrorDuel && RobertRules.shouldRetreatUnsafe(
                state.robertHp,
                state.robertMaxHp,
                state.robertConsecutiveHits,
                proximityNow
            )
        ) {
            if (state.robertState == "advance" || state.robertState == "flank") {
                beginRobertSprint()
                return
            }
        }
        // The player raised a gloo wall and Robert has no sniper rifle to
        // break it: he does not trade shots, he runs away and stays away
        // until the wall drops.
        val robertHasSniperNow = RobertRules.hasUsableSniper(allowed, isRobertWeaponDamaged(state, Weapon.SNIPER))
        if (state.glueWallActive && !robertHasSniperNow) {
            if (state.robertState != "avoid") {
                _gameState.update { it.copy(robertState = "avoid") }
                planRobertStep(140L)
            }
            return
        }
        // Smart logic: select best weapon for current health band before firing
        val smartChoice = RobertRules.smartWeaponForSituation(
            allowed = pool,
            robertHp = state.robertHp,
            robertMaxHp = state.robertMaxHp,
            proximityPercent = proximityNow,
            playerGlueWallActive = state.glueWallActive,
            hasSniper = robertHasSniperNow
        )
        // Round 5 strategic logic: equal power — prioritize positioning over brute force
        val isStrategyRound = state.round == 5
        val chosen = when {
            state.glueWallActive && pool.contains(Weapon.SNIPER) -> Weapon.SNIPER
            !isStrategyRound && state.activeWeapon.autoFire &&
                pool.contains(Weapon.MP40) &&
                Combat.zoneFor(proximityNow) != HitZone.MISS -> Weapon.MP40
            smartChoice != null && (Random.nextFloat() < 0.75f || isStrategyRound) -> smartChoice
            else -> pool[Random.nextInt(pool.size)]
        }
        val chosenIndex = state.robertWeapons.indexOf(chosen.id).coerceAtLeast(0)

        if (chosenIndex != state.robertWeaponIndex) {
            // Changing weapon is silent for Robert: a stray gun sound on the
            // enemy side has no gameplay meaning and used to confuse players.
            _gameState.update { it.copy(robertWeaponIndex = chosenIndex) }
            state = _gameState.value
        }

        // Six rounds per magazine, then a free reload. From round five every
        // gun is unlimited, so he never reloads and never runs dry.
        val unlimitedAmmo = RobertRules.infiniteAmmo(state.round)
        if (!unlimitedAmmo && chosen.needsReload && state.robertAmmoNow <= 0) {
            _gameState.update { s ->
                s.copy(
                    robertAmmo = s.robertAmmo.toMutableList().also {
                        it[s.robertWeaponIndex] = chosen.magazine
                    }
                )
            }
            // Robert reloads silently: only the player ever hears the
            // mechanical gun-switch/reload recording.
            setLog(t("robertReloaded"))
            return
        }

        // A melee attack must not play its swing/switch sound before the
        // outcome is known: a miss uses only *-miss and a hit uses *-hit.
        // Firearm sounds are exclusive: his shot cuts whatever weapon sound
        // the player still has in the air, so the two never mix.
        if (!chosen.melee) {
            audioManager.playWeaponSound(
                chosen.fireSoundKey,
                volume = 0.9f,
                // The recorded MP40 sample is a long burst; slightly faster
                // playback gives a distinct, finite shot instead of a near-continuous rumble.
                rate = if (chosen == Weapon.MP40) 1.8f else 1.0f,
                posZ = -state.distance
            )
        }
        _gameState.update { s ->
            val nextAmmo = if (chosen.needsReload && !unlimitedAmmo) {
                s.robertAmmo.toMutableList().also {
                    it[s.robertWeaponIndex] = (it[s.robertWeaponIndex] - 1).coerceAtLeast(0)
                }
            } else {
                s.robertAmmo
            }
            s.copy(
                robertShots = s.robertShots + 1,
                robertExchangeShots = s.robertExchangeShots + 1,
                robertSuccessiveShots = s.robertSuccessiveShots + 1,
                robertAmmo = nextAmmo
            )
        }
        state = _gameState.value

        // The 0%-40% deception band is only held for one or two shots; every
        // one of them misses on purpose, then Robert picks a real band again.
        if (currentFireRange(state) == FireRange.FAR && state.robertDeceptionShots > 0) {
            val shotsLeft = state.robertDeceptionShots - 1
            _gameState.update { it.copy(robertDeceptionShots = shotsLeft) }
            if (shotsLeft <= 0) {
                rollRobertFireRange(allowDeception = false)
                ensureRobertPositioned()
            }
        }

        val proxPercent = Combat.proximityOf(state.distance)
        val zone = Combat.zoneFor(proxPercent)

        if (zone == HitZone.MISS) {
            if (chosen.melee) {
                audioManager.playWeaponSound(chosen.missSoundKey, volume = 0.55f, posZ = -state.distance)
            }
            _gameState.update { it.copy(consecutiveHeavyHits = 0) }
            setLog("${t("robertMissed")} ${t(chosen.nameKey)}.")
            return
        }

        // The player's glue wall stops everything until three sniper hits break it.
        if (state.glueWallActive) {
            val breaks = Combat.canBreakGlueWall(chosen, proxPercent)
            val progress = if (breaks) state.glueWallProgress + 1 else 0
            _gameState.update { it.copy(glueWallProgress = progress) }
            playSound("glueHit", volume = 0.9f)
            if (progress >= Combat.GLUE_WALL_BREAK_HITS) {
                _gameState.update {
                    it.copy(glueWallActive = false, glueWallProgress = 0)
                }
                playSound("glueWallCrack", volume = 1.0f)
                msg(t("glueWallDown"))
            } else {
                val progressText = if (breaks) {
                    " ${t("glueWallBreaks")} $progress."
                } else {
                    ""
                }
                msg("${t("glueWallBlocked")}$progressText")
            }
            return
        }

        // The player's cover stops eleven hits and breaks on the twelfth.
        if (state.coverActive) {
            // The first eleven hits are absorbed; the twelfth breaks it.
            val hits = state.coverHits + 1
            if (hits > Combat.COVER_MAX_HITS) {
                _gameState.update { it.copy(coverActive = false, coverHits = 0) }
                playSound("coverCrack", volume = 1.0f)
                msg(t("coverBroken"))
            } else {
                _gameState.update { it.copy(coverHits = hits) }
                playSound("coverHit", volume = 0.8f)
                setLog("${t("coverHitsLeft")} ${Combat.COVER_MAX_HITS - hits}.")
            }
            return
        }

        // Robert's assault rifle and shotgun use his explicit per-zone table;
        // every other weapon uses the standard player table.
        var dealt = RobertRules.weaponDamage(chosen, proxPercent, state.playerMaxHp)
        // While the quick menu is genuinely being navigated every hit is cut
        // down to a fixed percentage of maximum health. The protection expires
        // on its own, so browsing the menu can never become invulnerability.
        if (isMenuProtectionActive()) {
            dealt = min(dealt, Combat.menuProtectedDamage(state.playerMaxHp))
        }

        val newPlayerHp = max(0, state.playerHp - dealt)

        var streak = Combat.nextHeavyHitStreak(state.consecutiveHeavyHits, proxPercent)
        var brokeWeapon = false
        val damaged = state.damaged.toMutableList()
        val activeIndex = state.weaponIndex.coerceIn(0, max(0, damaged.lastIndex))
        if (damaged.isNotEmpty() && !damaged[activeIndex] && Combat.gunBreaks(streak)) {
            damaged[activeIndex] = true
            streak = 0
            brokeWeapon = true
        }

        _gameState.update {
            it.copy(
                playerHp = newPlayerHp,
                robertHits = it.robertHits + 1,
                fightRobertHits = it.fightRobertHits + 1,
                robertDamage = it.robertDamage + dealt,
                consecutiveHeavyHits = streak,
                // Landing a hit ends the one-sided exchange.
                robertConsecutiveHits = 0,
                robertExchangeShots = 0,
                damaged = damaged
            )
        }

        // The weapon's own hit sound is always mixed with the sound of the
        // fighter that was hit: here the player, so player-hit.
        playSound(chosen.hitSoundKey, volume = 1.0f)
        playSound("playerHit", volume = 1.0f)

        val hitHead = if (zone == HitZone.HEAVY) t("youWereHit") else t("youWereGrazed")
        var text = "$hitHead ${t(chosen.nameKey)}. ${t("hitFor")} $dealt. " +
            "${t("yourHealth")} $newPlayerHp."
        if (brokeWeapon) {
            text += " ${t("gunDamaged")} ${t("recovery")}. " +
                "${t("coins")}: ${Combat.GUN_RECOVERY_COST}. ${t("robertPressing")}"
        }

        if (newPlayerHp <= 0) {
            if (!state.playerSecondChanceUsed) {
                val revivedHp = Combat.secondChanceHealth(state.playerMaxHp)
                _gameState.update {
                    it.copy(
                        playerHp = revivedHp,
                        playerSecondChanceUsed = true,
                        consecutiveHeavyHits = 0,
                        coverActive = false,
                        glueWallActive = false
                    )
                }
                playSound("medkit")
                text += " ${t("playerSecondChance")} ${t("yourHealth")} $revivedHp."
                ensureRobertPositioned()
                msg(text)
            } else {
                msg(text)
                endGame(won = false)
            }
            return
        }

        text += applyAutomaticDefences()
        ensureRobertPositioned()
        announceEnemyHit(text, mirrorDuel)
    }

    /**
     * Speaks the enemy hit report, keeping a fast exchange readable: the
     * sentence is always shown, but it is only read out loud at most once per
     * [FAST_EXCHANGE_SPEECH_MS] while the shots arrive faster than that.
     */
    private fun announceEnemyHit(text: String, fastExchange: Boolean) {
        val now = System.currentTimeMillis()
        if (fastExchange && now - lastHitSpeechAtMs < FAST_EXCHANGE_SPEECH_MS) {
            setLog(text)
            return
        }
        lastHitSpeechAtMs = now
        msg(text)
    }

    /**
     * Automatic helpers: reload, medikit under 50 health, cover and glue wall
     * in the last stage. They only ever use items the player owns and only
     * while the extension is enabled.
     */
    private fun applyAutomaticDefences(): String {
        val inv = _inventory.value
        var extra = ""
        val state = _gameState.value

        val lastStage = state.playerHp <= (state.playerMaxHp * LAST_STAGE_FRACTION).roundToInt()

        if (inv.extensionEnabled(Extension.AUTO_MEDKIT) &&
            state.playerHp < 50 &&
            inv.medikits > 0
        ) {
            useDefence(DefenceItem.MEDKIT, automatic = true)?.let { extra += " $it" }
        }
        if (inv.extensionEnabled(Extension.AUTO_GLUE_WALL) &&
            lastStage &&
            !_gameState.value.glueWallActive &&
            !_gameState.value.coverActive &&
            _inventory.value.glueWalls > 0
        ) {
            useDefence(DefenceItem.GLUE_WALL, automatic = true)?.let { extra += " $it" }
        }
        if (inv.extensionEnabled(Extension.AUTO_COVER) &&
            lastStage &&
            !_gameState.value.coverActive &&
            !_gameState.value.glueWallActive &&
            _inventory.value.covers > 0
        ) {
            useDefence(DefenceItem.COVER, automatic = true)?.let { extra += " $it" }
        }
        return extra
    }

    /**
     * Schedules Robert's answering shot.
     *
     * A single hit is always answered: the shot leaves after
     * [RobertRules.RETALIATION_DELAY_MS], so being shot at is never free and
     * the enemy is never a passive punching bag.
     */
    private fun scheduleRobertRetaliation() {
        // The first hit of an exchange is always answered; further hits inside
        // the cooldown window do not stack extra answering shots on top of it.
        val now = System.currentTimeMillis()
        if (now - robertRetaliationAtMs < RobertRules.RETALIATION_COOLDOWN_MS) return
        robertRetaliationAtMs = now
        counterJob?.cancel()
        counterJob = viewModelScope.launch {
            delay(RobertRules.RETALIATION_DELAY_MS)
            if (foreground &&
                _currentScreen.value == Screen.BATTLE &&
                _gameState.value.robertHp > 0 &&
                _gameState.value.playerHp > 0
            ) {
                robertRetaliationAtMs = System.currentTimeMillis()
                robertAttack(retaliate = true)
            }
        }
    }

    /**
     * Robert's reaction to a hit.
     *
     * The rule is deliberately simple and human:
     *  - the player hit him once  -> he fires straight back ([scheduleRobertRetaliation]);
     *  - the player hit him twice, or is clearly out-firing him -> he sprints
     *    far away at twice the player's running speed ([beginRobertSprint]);
     *  - the player hid behind a gloo wall he cannot break -> he runs away and
     *    stays away until the wall drops.
     *
     * [direct] is false for a shot that was stopped by one of his barriers:
     * those do not count towards the hit streak, but he still answers them.
     */
    private fun robertOnHit(direct: Boolean = true) {
        val state = _gameState.value
        if (state.robertHp <= 0 || state.playerHp <= 0) return
        // While the player is choosing a timer bomb duration he holds his fire
        // completely, and while a projectile is in the air he only runs.
        if (state.timerSelectActive || state.missileInFlight) return
        if (state.bombInFlight && state.bombFromPlayer) return
        if (!direct) {
            scheduleRobertRetaliation()
            return
        }
        // A gloo wall he cannot break: he refuses the exchange entirely.
        if (state.glueWallActive && !robertHasUsableSniper(state)) {
            if (state.robertState != "avoid") {
                _gameState.update { it.copy(robertState = "avoid") }
                planRobertStep(140L)
            }
            return
        }
        // While he is trading shots at the player's own cadence he stands
        // his ground: breaking contact here would silence the answer the
        // player is hearing back.
        if (!mirrorDuelActive(state) && RobertRules.shouldFleeFromPlayerFire(
                hitsTakenInRow = state.robertConsecutiveHits,
                playerHits = state.fightPlayerHits,
                robertHits = state.fightRobertHits
            )
        ) {
            beginRobertSprint()
            return
        }
        if (RobertRules.shouldRetaliate(state.robertConsecutiveHits)) {
            scheduleRobertRetaliation()
        }
    }

    /**
     * Deploys one of Robert's defences for real: the stock is spent, the
     * barrier is raised (or the medkit heals) and the matching sound plays.
     * The two interceptors are consumed when a projectile is in the air, so
     * they are never spent here.
     */
    private fun deployRobertDefence(defence: DefenceItem): String {
        val current = _gameState.value
        return when (defence) {
            DefenceItem.MEDKIT -> {
                if (current.robertMedkits <= 0) return ""
                _gameState.update {
                    it.copy(
                        robertHp = it.robertMaxHp,
                        robertMedkits = (it.robertMedkits - 1).coerceAtLeast(0),
                        robertDefenceArmed = false,
                        robertConsecutiveHits = 0
                    )
                }
                playSound("medkit", posZ = -current.distance)
                ensureRobertPositioned()
                " ${t("robertMedkit")} ${t("enemyHealth")} ${_gameState.value.robertHp}."
            }
            DefenceItem.COVER -> {
                if (current.robertCovers <= 0 || current.robertCoverActive) return ""
                _gameState.update {
                    it.copy(
                        robertCoverActive = true,
                        robertCoverHits = 0,
                        robertCovers = (it.robertCovers - 1).coerceAtLeast(0),
                        robertDefenceArmed = false,
                        robertConsecutiveHits = 0
                    )
                }
                playSound("cover", posZ = -current.distance)
                " ${t("robertCoverUp")}"
            }
            DefenceItem.GLUE_WALL -> {
                if (current.robertGlueWalls <= 0 || current.robertGlueWallActive) return ""
                _gameState.update {
                    it.copy(
                        robertGlueWallActive = true,
                        robertGlueWallProgress = 0,
                        robertGlueWalls = (it.robertGlueWalls - 1).coerceAtLeast(0),
                        robertDefenceArmed = false,
                        robertConsecutiveHits = 0
                    )
                }
                playSound("glueSound", posZ = -current.distance)
                " ${t("robertGlueWall")}"
            }
            DefenceItem.ANTI_MISSILE, DefenceItem.ANTI_BOMB -> ""
        }
    }

    /** True while a panic defence may be spent at once. */
    private fun robertPanicDefence(state: GameState): DefenceItem? {
        if (!state.robertDefenceArmed) return null
        if (!RobertRules.shouldPanic(state.robertHp, state.robertMaxHp, state.playerBurstRobertHp)) {
            return null
        }
        val defence = RobertRules.panicDefence(
            medikits = state.robertMedkits,
            covers = state.robertCovers,
            glueWalls = state.robertGlueWalls,
            antiBombs = state.robertAntiBombs,
            antiMissiles = state.robertAntiMissiles,
            robertHp = state.robertHp,
            robertMaxHp = state.robertMaxHp,
            proximityPercent = Combat.proximityOf(state.distance),
            bombInFlight = state.bombInFlight && !state.bombFromPlayer,
            missileInFlight = state.missileInFlight && !state.missileFromPlayer
        )
        // The interceptors are spent at the impact of a real projectile.
        return when (defence) {
            DefenceItem.MEDKIT, DefenceItem.COVER, DefenceItem.GLUE_WALL -> defence
            else -> null
        }
    }

    /** Robert's reaction after taking a hit: panic deploy, flee, heal, cover or glue wall. */
    private fun robertAfterHit(): String {
        val state = _gameState.value
        if (state.robertHp <= 0) return ""

        val profile = robertProfile
        val consecutive = state.robertConsecutiveHits

        // The hit exchange is judged before anything else: when the player has
        // landed more hits than Robert did (two against one, for example) he
        // retreats very quickly, unless one of his own barriers is protecting
        // him and the player has none.
        val protectedPress = robertPressing(state) &&
            state.robertHasActiveDefence &&
            !state.playerHasActiveDefence
        // An exchange Robert is answering at the player's exact cadence
        // keeps him in the fight; only a genuine lull lets him reposition.
        val answeringExchange = mirrorDuelActive(state)
        if (!protectedPress && !answeringExchange &&
            RobertRules.shouldFleeFight(state.fightPlayerHits, state.fightRobertHits)
        ) {
            beginRobertSprint()
            return " ${t("robertFled")}"
        }

        // Panic deployment happens on the same frame as the hit, so a falling
        // health bar really makes him spend a medkit or raise a barrier.
        val panicSpoken = robertPanicDefence(state)?.let { deployRobertDefence(it) }
        if (!panicSpoken.isNullOrBlank()) return panicSpoken

        // He runs as fast as possible either after the round's hit streak, or
        // immediately when a one-sided exchange turns against him.
        val situation = RobertRules.assessSituation(
            robertHp = state.robertHp,
            robertMaxHp = state.robertMaxHp,
            playerHp = state.playerHp,
            playerMaxHp = state.playerMaxHp,
            playerWeaponDamaged = isPlayerWeaponLocked(state)
        )
        val disadvantaged = situation == RobertRules.CombatSituation.DISADVANTAGE ||
            state.robertHp < state.playerHp
        val mustFlee = RobertRules.shouldFlee(consecutive, profile.fleeAfterHits) ||
            RobertRules.shouldFleeFromExchange(
                hitsTakenInRow = consecutive,
                shotsFiredInExchange = state.robertExchangeShots,
                proximityPercent = Combat.proximityOf(state.distance),
                atDisadvantage = disadvantaged
            )
        // His own health dropping under continuous fire makes him run; the
        // player's health dropping does the opposite, so Robert keeps firing.
        val sustained = RobertRules.shouldRetreatFromSustainedFire(
            playerBurstShots = state.playerBurstShots,
            robertHealthDropping = robertHealthDropping(state),
            playerHealthDropping = playerHealthDropping(state)
        )
        if (!answeringExchange &&
            (state.robertState == "advance" || state.robertState == "flank") &&
            (mustFlee || sustained)
        ) {
            beginRobertSprint()
            return " ${t("robertFled")}"
        }

        // Re-arm the defence once Robert is healthy again for this round or
        // after two hits. The health level depends on the round.
        val threshold = RobertRules.defenceThresholdHp(state.round, state.robertMaxHp)
        if (state.robertHp > threshold || consecutive >= 2) {
            _gameState.update { it.copy(robertDefenceArmed = true) }
        }

        val current = _gameState.value
        if (!RobertRules.shouldUseDefence(
                robertHp = current.robertHp,
                defenceArmed = current.robertDefenceArmed,
                thresholdHp = RobertRules.defenceThresholdHp(current.round, current.robertMaxHp)
            )
        ) {
            return ""
        }

        // He now picks situationally instead of always in the same order: a
        // barrier when the player is close, a medkit when he is below half.
        val defence = RobertRules.chooseDefence(
            medikits = current.robertMedkits,
            covers = current.robertCovers,
            glueWalls = current.robertGlueWalls,
            robertHp = current.robertHp,
            robertMaxHp = current.robertMaxHp,
            proximityPercent = Combat.proximityOf(current.distance)
        ) ?: return ""

        return deployRobertDefence(defence)
    }

    // ---------------------------------------------------------------------
    // Guided missiles (player and Robert)
    // ---------------------------------------------------------------------

    /**
     * Swiping up with the missile selected launches it, consuming exactly
     * one stacked missile power. Without a power nothing is spent and the
     * swipe-down purchase is announced instead.
     */
    private fun launchPlayerMissile() {
        val state = _gameState.value
        if (!MissileRules.canLaunch(
                ownsMissile = _inventory.value.ownsWeapon(Weapon.MISSILE),
                missilePowers = _inventory.value.missilePowers,
                missileInFlight = state.missileInFlight
            )
        ) {
            playSound("ammo")
            msg(t("missileNoPower"))
            return
        }
        val updated = Shop.consumeMissilePower(_inventory.value)
        _inventory.value = updated
        prefs.saveInventory(updated)

        val fleeing = MissileRules.targetWillFlee(state.distance)
        val intercept = MissileRules.willIntercept(
            round = state.round,
            robertHp = state.robertHp,
            antiMissilesLeft = state.robertAntiMissiles,
            missileInFlight = true
        )
        _gameState.update {
            it.copy(
                missileInFlight = true,
                missileFromPlayer = true,
                missileInterceptPlanned = intercept,
                playerShots = it.playerShots + 1,
                robertState = if (fleeing) "missileFlee" else it.robertState,
                robertLateral = 0f
            )
        }
        // A fleeing target is chased with the sound on loop; a target with
        // nowhere to run is hit as the single play of the sound ends.
        if (MissileRules.soundLoops(state.distance)) {
            audioManager.playLoop("missile")
        } else {
            playSound("missile")
        }
        var text = t("missileLaunched")
        if (fleeing) {
            text += " ${t("missileEnemyFleeing")}"
            planRobertStep(120L)
        }
        msg(text)
        scheduleMissileImpact(MissileRules.flightMs(state.distance))
    }

    /** Robert's one desperation missile per game (rounds four and five). */
    private fun launchEnemyMissile() {
        val state = _gameState.value
        _gameState.update {
            it.copy(
                robertMissilePowers = (it.robertMissilePowers - 1).coerceAtLeast(0),
                missileInFlight = true,
                missileFromPlayer = false,
                missileInterceptPlanned = false,
                robertShots = it.robertShots + 1
            )
        }
        if (MissileRules.soundLoops(state.distance)) {
            audioManager.playLoop("missile")
        } else {
            playSound("missile")
        }
        msg(t("robertMissileLaunched"))
        scheduleMissileImpact(MissileRules.flightMs(state.distance))
    }

    private fun scheduleMissileImpact(delayMs: Long) {
        missileJob?.cancel()
        missileImpactAtMs = System.currentTimeMillis() + delayMs
        missileJob = viewModelScope.launch {
            delay(delayMs)
            onMissileImpact()
        }
    }

    /**
     * Missile impact. The looping flight sound stops at once and the
     * explosion plays, mixed with the hit sound of the destroyed fighter.
     * The target drops directly to zero health through running, glue walls
     * and covers alike — unless Robert's end-game anti-missile system
     * (round four once, round five twice, his health below 100) destroys
     * the player's missile in the air instead.
     */
    private fun onMissileImpact() {
        val state = _gameState.value
        if (_currentScreen.value != Screen.BATTLE || !state.missileInFlight) return
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        missileImpactAtMs = 0L
        audioManager.stopSound("missile")
        audioManager.stopSound("enemyWalk")

        if (state.missileFromPlayer) {
            val intercepted = state.missileInterceptPlanned ||
                MissileRules.willIntercept(
                    round = state.round,
                    robertHp = state.robertHp,
                    antiMissilesLeft = state.robertAntiMissiles,
                    missileInFlight = state.missileInFlight
                )
            if (intercepted) {
                playSound("explosion")
                _gameState.update {
                    it.copy(
                        missileInFlight = false,
                        missileFromPlayer = false,
                        missileInterceptPlanned = false,
                        robertAntiMissiles = (it.robertAntiMissiles - 1).coerceAtLeast(0),
                        robertState = "advance",
                        robertLateral = 0f
                    )
                }
                rollRobertFireRange(allowDeception = false)
                msg(t("antiMissileIntercept"))
                planRobertStep(500L)
                return
            }
            audioManager.playMixed(listOf("explosion", "enemyHit"))
            _gameState.update {
                it.copy(
                    robertHp = 0,
                    missileInFlight = false,
                    missileFromPlayer = false,
                    missileInterceptPlanned = false,
                    robertCoverActive = false,
                    robertGlueWallActive = false,
                    playerHits = it.playerHits + 1
                )
            }
            msg(t("missileHitEnemy"))
            // The enemy-hit sound and the explosion play together and the
            // battle is only ended once the explosion has finished.
            endGameAfterExplosion(won = true)
        } else {
            // Enemy missile hit: play player-hit.mp3 alongside explosion-sound.mp3.
            audioManager.playMixed(listOf("explosion", "playerHit"))
            _gameState.update {
                it.copy(
                    playerHp = 0,
                    missileInFlight = false,
                    missileFromPlayer = false,
                    missileInterceptPlanned = false,
                    coverActive = false,
                    glueWallActive = false,
                    robertHits = it.robertHits + 1
                )
            }
            msg(t("missileHitPlayer"))
            // A missile destroys completely: even the second chance does
            // not apply to a direct missile hit. The result is spoken only
            // after the explosion has run out.
            endGameAfterExplosion(won = false)
        }
    }

    /**
     * Swiping down with the missile selected buys one missile power for
     * 600 coins, exactly as announced when no power is owned.
     */
    private fun buyMissilePowerInBattle() {
        if (_gameState.value.missileInFlight) {
            msg(t("missileInFlight"))
            return
        }
        val result = Shop.buyMissilePower(
            _inventory.value,
            _stats.value.coins,
            _stats.value.completedRounds
        )
        if (!result.success) {
            when (result.reasonKey) {
                "notEnoughCoins" -> notEnoughCoins()
                null -> {}
                else -> msg(t(result.reasonKey))
            }
            return
        }
        _inventory.value = result.inventory
        prefs.saveInventory(result.inventory)
        _stats.value = _stats.value.copy(coins = result.coins)
        prefs.saveStats(_stats.value)
        playSound("spend")
        msg("${t("missilePowerBought")} ${t("extMissilePowers")} ${result.inventory.missilePowers}.")
    }

    // ---------------------------------------------------------------------
    // Bombs: grenade, atom bomb and timer bomb (round three onwards)
    // ---------------------------------------------------------------------

    /**
     * Opens the timer bomb duration picker. Swiping up with the timer bomb
     * selected never throws it straight away: first the player hears
     * "Select your timer" and picks 15, 30, 40, 50 or 60 seconds with a left
     * or right swipe, then locks the choice with a tap in the centre.
     */
    private fun startTimerSelection() {
        val state = _gameState.value
        if (state.bombInFlight || state.missileInFlight) {
            playSound("click")
            msg(if (state.missileInFlight) t("missileInFlight") else t("bombInFlight"))
            return
        }
        if (state.timerSelectActive) {
            speakTimerSelection()
            return
        }
        _gameState.update {
            it.copy(timerSelectActive = true, timerIndex = BombRules.DEFAULT_TIMER_INDEX)
        }
        playSound("click")
        msg(t("selectTimer"))
        // The timer instructions are the only thing that is spoken here: the
        // extra "the enemy holds his fire while you choose" announcement was
        // removed, because it interrupted the duration itself and said
        // nothing the player can act on. Robert simply keeps quiet until the
        // choice is locked in or the picker is dismissed.
        speakTimerSelection(instructions = true)
    }

    /** Walks the timer picker one duration left (-1) or right (+1). */
    fun cycleTimerSelection(direction: Int) {
        val state = _gameState.value
        if (!state.timerSelectActive) return
        markExplicitAction()
        _gameState.update {
            it.copy(timerIndex = BombRules.stepTimerIndex(it.timerIndex, direction))
        }
        playSound("click")
        speakTimerSelection()
    }

    /** A tap in the centre locks the highlighted duration and throws the bomb. */
    fun confirmTimerSelection() {
        val state = _gameState.value
        if (!state.timerSelectActive) return
        markExplicitAction()
        _gameState.update { it.copy(timerSelectActive = false) }
        msg("${t("timerSet")} ${state.timerSeconds} ${t("seconds")}.")
        launchPlayerBomb()
        resumeEnemyFireAfterSelection()
    }

    /**
     * Cancels the picker without throwing (used when leaving the battle).
     * The enemy holds his fire for as long as the picker is open and starts
     * again only here, that is after the player deselected it.
     */
    fun cancelTimerSelection() {
        if (!_gameState.value.timerSelectActive) return
        _gameState.update { it.copy(timerSelectActive = false) }
        playSound("click")
        msg(t("timerCancelled"))
        resumeEnemyFireAfterSelection()
    }

    /**
     * Restarts the enemy fire rhythm after the timer picker closed, so his
     * first shot after the choice comes at a fresh, human interval instead of
     * instantly or with a stale delay.
     */
    private fun resumeEnemyFireAfterSelection() {
        if (_currentScreen.value != Screen.BATTLE) return
        startRobertFireLoop(1_600L)
    }

    /** Speaks the highlighted duration, and how to change it on first open. */
    private fun speakTimerSelection(instructions: Boolean = false) {
        val seconds = _gameState.value.timerSeconds
        val text = if (instructions) {
            "${t("selectTimer")} $seconds ${t("seconds")}. ${t("timerHowTo")}"
        } else {
            "$seconds ${t("seconds")}"
        }
        setLog(text)
        speak(text)
    }

    /**
     * Throws the selected bomb — now tracking the enemy like a missile.
     * Requires one Bomb Power (1500 coins) per throw, similar to Missile Power.
     * The bomb tracks the enemy, falls onto them, and detonates — guaranteed kill
     * like a missile unless intercepted by anti-bomb explosive.
     */
    private fun launchPlayerBomb() {
        val state = _gameState.value
        val weapon = state.activeWeapon
        val kind = BombKind.fromWeapon(weapon) ?: return
        if (state.bombInFlight) {
            playSound("click")
            msg(t("bombInFlight"))
            return
        }
        if (state.missileInFlight) {
            playSound("click")
            msg(t("missileInFlight"))
            return
        }
        // Bomb Power check — similar to Missile Power. The grenade is free in
        // round three onwards (Robert receives one too), so only the atom bomb
        // and the timer bomb consume a stacked Bomb Power.
        val needsPower = BombRules.requiresBombPower(state.round, kind)
        if (needsPower && _inventory.value.bombPowers <= 0) {
            playSound("ammo")
            msg(t("bombNoCharge") + " " + t("bombPowerNeeded"))
            return
        }
        // Also check ammo for legacy magazine (bombs carry one charge per battle in inventory)
        // but bomb power is the primary gate.

        var spent = false
        _gameState.update { s ->
            // Legacy ammo check retained for weapon magazine, but bomb power is also required
            val idx = s.weaponIndex.coerceIn(0, s.ammo.lastIndex)
            // Allow throw even if ammo is 0 when bomb power exists (power gives charge)
            val hasCharge = s.ammo.getOrElse(idx) { 0 } > 0 || (needsPower && _inventory.value.bombPowers > 0)
            if (!hasCharge) return@update s
            spent = true
            val intercept = BombRules.willIntercept(
                round = s.round,
                antiBombsLeft = s.robertAntiBombs,
                bombInFlight = true
            )
            s.copy(
                bombInFlight = true,
                bombFromPlayer = true,
                bombKind = kind.id,
                bombX = s.robertX,
                bombY = s.robertY,
                bombInterceptPlanned = intercept,
                bombInterceptedByPlayer = false,
                timerSelectActive = false,
                playerShots = s.playerShots + 1,
                ammo = s.ammo.toMutableList().also { list -> if (list[idx] > 0) list[idx] = 0 },
                robertState = if (intercept) s.robertState else "bombFlee",
                robertTurnBackTicks = 0
            )
        }
        if (!spent) {
            playSound("ammo")
            msg(t("bombNoCharge"))
            return
        }
        // Consume one Bomb Power per throw
        if (needsPower) {
            val updated = Shop.consumeBombPower(_inventory.value)
            _inventory.value = updated
            prefs.saveInventory(updated)
        }

        val after = _gameState.value
        val flightMs = BombRules.flightMs(kind, after.timerSeconds)
        startBombAudio(kind, distance = after.distance, timer = kind == BombKind.TIMER)
        bombBlastAtMs = System.currentTimeMillis() + flightMs

        var text = "${t("bombThrown")} ${t(kind.nameKey)}."
        if (kind == BombKind.TIMER) {
            text += " ${t("timerCountdown")} ${after.timerSeconds} ${t("seconds")}."
        } else {
            text += " ${t("bombTrackingEnemy")}"
        }
        msg(text)
        // Tracking bomb: update destination to follow enemy until blast
        startBombTracking(fromPlayer = true)
        planRobertStep(120L)
        scheduleBombBlast(flightMs)
    }

    /**
     * Robert throws one of his own bombs (round three: one per game, rounds
     * four and five: two). The destination is the player's position and the
     * player must sprint out of the 50 metres or deploy the anti-bomb
     * explosive defence while the bomb sound is still running.
     */
    private fun launchEnemyBomb() {
        val state = _gameState.value
        if (state.robertBombs <= 0 || state.bombInFlight || state.missileInFlight) return
        // Round three: grenades only, twice per game. Round four: all three
        // bombs, three times each. Round five: unlimited bombs of every kind.
        val kind = BombRules.chooseEnemyBomb(
            round = state.round,
            grenades = state.robertGrenades,
            atomBombs = state.robertAtomBombs,
            timerBombs = state.robertTimerBombs,
            playerHpFraction = if (state.playerMaxHp <= 0) 1f
            else state.playerHp.toFloat() / state.playerMaxHp.toFloat(),
            random = Random
        ) ?: BombKind.GRENADE
        val flightMs = BombRules.flightMs(kind, state.timerSeconds)
        _gameState.update {
            it.copy(
                robertBombs = (it.robertBombs - 1).coerceAtLeast(0),
                robertGrenades = if (kind == BombKind.GRENADE) {
                    (it.robertGrenades - 1).coerceAtLeast(0)
                } else it.robertGrenades,
                robertAtomBombs = if (kind == BombKind.ATOM) {
                    (it.robertAtomBombs - 1).coerceAtLeast(0)
                } else it.robertAtomBombs,
                robertTimerBombs = if (kind == BombKind.TIMER) {
                    (it.robertTimerBombs - 1).coerceAtLeast(0)
                } else it.robertTimerBombs,
                bombInFlight = true,
                bombFromPlayer = false,
                bombKind = kind.id,
                bombX = it.playerX,
                bombY = it.playerY,
                bombInterceptPlanned = false,
                bombInterceptedByPlayer = false,
                robertShots = it.robertShots + 1
            )
        }
        startBombAudio(kind, distance = state.distance, timer = false)
        bombBlastAtMs = System.currentTimeMillis() + flightMs
        startBombTracking(fromPlayer = false)
        val bombWarning = "${t("bombRunAway")} ${BombRules.SAFE_DISTANCE.toInt()} ${t("meters")}. ${t("bombTrackingPlayer")}"
        msg("${t("robertBombThrown")} ${t(kind.nameKey)}. $bombWarning")
        speak(bombWarning)
        scheduleBombBlast(flightMs)
    }

    /** Starts the countdown audio of a thrown bomb. */
    private fun startBombAudio(kind: BombKind, distance: Float, timer: Boolean) {
        if (timer) {
            // timer-sound.mp3 loops for the whole countdown, then stops and
            // the explosion takes over.
            audioManager.playLoop(kind.flightSoundKey)
        } else {
            // The grenade and atom bomb sounds are exactly as long as their
            // flight, so a single play ends with the blast.
            playSound(kind.flightSoundKey, volume = BOMB_VOLUME, posZ = -distance)
        }
    }

    private fun scheduleBombBlast(delayMs: Long) {
        bombJob?.cancel()
        bombJob = viewModelScope.launch {
            delay(delayMs)
            onBombBlast()
        }
    }

    /** Tracking bomb: follows the target until detonation, guaranteeing hit like a missile. */
    private fun startBombTracking(fromPlayer: Boolean) {
        bombTrackJob?.cancel()
        bombTrackJob = viewModelScope.launch {
            while (true) {
                delay(BombRules.TRACKING_UPDATE_MS)
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val s = _gameState.value
                if (!s.bombInFlight) break
                // Update bomb destination to current target position — tracking
                _gameState.update { cur ->
                    if (fromPlayer) cur.copy(bombX = cur.robertX, bombY = cur.robertY)
                    else cur.copy(bombX = cur.playerX, bombY = cur.playerY)
                }
            }
        }
    }

    /**
     * The blast. The countdown sound stops at once and the explosion plays
     * mixed with the hit sound of whoever it caught - and it plays in every
     * outcome: when the bomb destroys its target, when the target escaped the
     * 50 metres, and when an anti-bomb explosive destroyed the bomb in the
     * air. A finished battle is only ended after the explosion has run out.
     */
    private fun onBombBlast() {
        val state = _gameState.value
        if (_currentScreen.value != Screen.BATTLE || !state.bombInFlight) return
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        bombBlastAtMs = 0L
        val kind = state.bomb ?: BombKind.GRENADE
        val fromPlayer = state.bombFromPlayer
        audioManager.stopLoop("timerLoop")
        audioManager.stopSound("timerLoop")
        audioManager.stopSound(kind.flightSoundKey)
        audioManager.stopSound("enemyWalk")
        audioManager.stopFootsteps()
        audioManager.stopSound("playerWalk")

        // Tracking bomb: destination already followed target, so distance is near zero — guaranteed hit
        bombTrackJob?.cancel()
        bombTrackJob = null
        val targetDistance =
            if (fromPlayer) state.robertDistanceFromBomb else state.playerDistanceFromBomb
        val throwerDistance =
            if (fromPlayer) state.playerDistanceFromBomb else state.robertDistanceFromBomb
        val intercepted = if (fromPlayer) {
            state.bombInterceptPlanned ||
                BombRules.willIntercept(state.round, state.robertAntiBombs, bombInFlight = true)
        } else {
            state.bombInterceptedByPlayer
        }

        // An anti-bomb explosive destroys the bomb in the air: the explosion
        // still plays, exactly like an intercepted missile, but nobody is hit.
        if (intercepted) {
            playSound("explosion")
            _gameState.update {
                it.copy(
                    bombInFlight = false,
                    bombFromPlayer = false,
                    bombKind = "",
                    bombInterceptPlanned = false,
                    bombInterceptedByPlayer = false,
                    robertAntiBombs = if (fromPlayer) {
                        (it.robertAntiBombs - 1).coerceAtLeast(0)
                    } else {
                        it.robertAntiBombs
                    },
                    robertState = if (fromPlayer) "advance" else it.robertState,
                    robertTurnBackTicks = 0
                )
            }
            msg(if (fromPlayer) t("antiBombIntercept") else t("antiBombSavedYou"))
            rollRobertFireRange(allowDeception = false)
            ensureRobertPositioned()
            planRobertStep(400L)
            return
        }

        // Bombs now kill enemies at any cost, tracking like missiles — guaranteed unless intercepted
        val killsTarget = if (intercepted) false else BombRules.trackingKillGuaranteed()
        // The timer bomb is the one bomb that also destroys its own thrower
        // when the thrower stayed inside the 50 metre safety distance.
        val killsThrower = kind == BombKind.TIMER &&
            BombRules.timerBombKillsThrower(throwerDistance)

        // Explosion and the hit sound of the destroyed fighter play together.
        audioManager.playMixed(
            listOf(kind.blastSoundKey, if (fromPlayer) "enemyHit" else "playerHit")
        )

        _gameState.update {
            it.copy(
                // The target of the bomb, and - for a timer bomb only - its
                // own thrower when the thrower stayed inside the 50 metres.
                robertHp = when {
                    fromPlayer && killsTarget -> 0
                    !fromPlayer && killsThrower -> 0
                    else -> it.robertHp
                },
                playerHp = when {
                    !fromPlayer && killsTarget -> 0
                    fromPlayer && killsThrower -> 0
                    else -> it.playerHp
                },
                playerHits = if (fromPlayer && killsTarget) it.playerHits + 1 else it.playerHits,
                robertHits = if (!fromPlayer && killsTarget) it.robertHits + 1 else it.robertHits,
                bombInFlight = false,
                bombFromPlayer = false,
                bombKind = "",
                bombInterceptPlanned = false,
                bombInterceptedByPlayer = false,
                coverActive = if (!fromPlayer && killsTarget) false else it.coverActive,
                glueWallActive = if (!fromPlayer && killsTarget) false else it.glueWallActive,
                robertCoverActive = if (fromPlayer && killsTarget) false else it.robertCoverActive,
                robertGlueWallActive = if (fromPlayer && killsTarget) false else it.robertGlueWallActive,
                robertState = "advance",
                robertTurnBackTicks = 0
            )
        }

        val text = buildString {
            append(t("bombExploded"))
            append(" ")
            append(
                when {
                    fromPlayer && killsTarget -> t("bombHitEnemy")
                    !fromPlayer && killsTarget -> t("bombHitPlayer")
                    else -> t("bombEscaped") +
                        " ${targetDistance.toInt()} ${t("meters")}."
                }
            )
            if (killsThrower) {
                append(" ")
                append(if (fromPlayer) t("bombKilledYou") else t("bombKilledRobert"))
            }
        }
        msg(text)

        val after = _gameState.value
        val playerDead = after.playerHp <= 0
        val robertDead = after.robertHp <= 0
        when {
            // Both destroyed by a timer bomb: the thrower loses.
            playerDead && robertDead -> endGameAfterExplosion(won = !fromPlayer)
            robertDead -> endGameAfterExplosion(won = true)
            playerDead -> endGameAfterExplosion(won = false)
            else -> {
                // The bomb exploded harmlessly: the battle simply goes on.
                rollRobertFireRange(allowDeception = false)
                ensureRobertPositioned()
                planRobertStep(500L)
            }
        }
    }

    /**
     * Ends the battle only once the explosion has finished. The hit sound and
     * the explosion are already playing together, and cutting them short to
     * speak the result would swallow the blast, so the result waits for the
     * exact length of explosion-sound.mp3.
     */
    private fun endGameAfterExplosion(won: Boolean) {
        if (endingJob != null) return
        endingJob = viewModelScope.launch {
            delay(SoundDurations.EXPLOSION_MS)
            endingJob = null
            if (foreground && _currentScreen.value == Screen.BATTLE) endGame(won)
        }
    }

    /** True while the closing explosion of a missile or a bomb is running. */
    private fun isBattleEnding(): Boolean = endingJob != null

    // ---------------------------------------------------------------------
    // Player firing
    // ---------------------------------------------------------------------

    fun fire() {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()

        val now = System.currentTimeMillis()
        if (now - lastFireAt < 220L) return
        lastFireAt = now

        var state = _gameState.value
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        if (state.missileInFlight) {
            playSound("click")
            msg(t("missileInFlight"))
            return
        }
        // A bomb counting down owns the battlefield: nothing else is fired
        // until it explodes, exactly like a missile in the air.
        if (state.bombInFlight || isBattleEnding()) {
            playSound("click")
            msg(if (state.bombInFlight) t("bombInFlight") else t("waitExplosion"))
            return
        }

        val weapon = state.activeWeapon
        if (state.activeWeaponDamaged) {
            playSound("ammo")
            msg("${t("gunDamaged")} ${t("recovery")}. ${t("coins")}: ${Combat.GUN_RECOVERY_COST}.")
            return
        }

        // Swipe up with the missile selected launches the missile instead
        // of a normal shot (one stacked missile power is consumed).
        if (weapon.missileLauncher) {
            launchPlayerMissile()
            return
        }

        // A bomb is thrown, not fired. The timer bomb first asks for its
        // duration; the grenade and the atom bomb fly straight away.
        if (weapon.bomb) {
            if (weapon.needsTimerSelection && !state.timerSelectActive) {
                startTimerSelection()
            } else if (state.timerSelectActive) {
                confirmTimerSelection()
            } else {
                launchPlayerBomb()
            }
            return
        }

        if (weapon.needsReload && state.activeAmmo <= 0) {
            playSound("ammo")
            val reloaded = autoReloadIfNeeded()
            if (reloaded == null) {
                msg(t("outOfAmmo"))
            } else {
                msg(reloaded)
            }
            return
        }

        var decremented = false
        _gameState.update { s ->
            val idx = s.weaponIndex.coerceIn(0, s.weapons.lastIndex)
            val current = s.ammo.getOrElse(idx) { 0 }
            if (weapon.needsReload && current <= 0) {
                s
            } else {
                decremented = true
                s.copy(
                    ammo = if (weapon.needsReload) {
                        s.ammo.toMutableList().also { it[idx] = current - 1 }
                    } else {
                        s.ammo
                    },
                    playerShots = s.playerShots + 1
                )
            }
        }
        if (!decremented) {
            playSound("ammo")
            msg(t("outOfAmmo"))
            return
        }

        state = _gameState.value
        // One shot of a burst, so Robert can tell continuous fire from a
        // single tap and answer it with continuous fire of his own.
        registerPlayerBurstShot()
        // Knife and sword sounds are selected strictly after hit/miss is known.
        // A held automatic burst already has its sound on loop, so the single
        // shot sound is only played for taps and for every other weapon.
        if (!weapon.melee && !(weapon.autoFire && state.autoFireActive)) {
            // Exclusive weapon channel: this shot cuts any weapon sound that is
            // still playing (the enemy's, or an earlier one of ours), so two
            // gunshots never mix or leak.
            audioManager.playWeaponSound(weapon.fireSoundKey, volume = 1.0f)
        }

        val proxPercent = Combat.proximityOf(state.distance)
        val zone = Combat.zoneFor(proxPercent)
        // The AWM is the long-range rifle: inside its 20% to 40% band it hits
        // where every other weapon misses completely.
        val longRange = DamageTable.isAwmLongRangeBand(weapon, proxPercent)

        if (zone == HitZone.MISS && !longRange) {
            // Firearms already played their shot; only melee weapons have a
            // dedicated miss sound, so the shot is never played twice.
            if (weapon.melee) {
                audioManager.playWeaponSound(weapon.missSoundKey, volume = 0.6f)
            }
            msg(autoReloadIfNeeded()?.let { "$it ${t("missed")}" } ?: t("missed"))
            return
        }

        // Robert's glue wall: only three continuous sniper hits break it.
        if (state.robertGlueWallActive) {
            val breaks = Combat.canBreakGlueWall(weapon, proxPercent)
            val progress = if (breaks) state.robertGlueWallProgress + 1 else 0
            _gameState.update { it.copy(robertGlueWallProgress = progress) }
            playSound("glueHit", volume = 0.9f)
            if (progress >= Combat.GLUE_WALL_BREAK_HITS) {
                _gameState.update {
                    it.copy(robertGlueWallActive = false, robertGlueWallProgress = 0)
                }
                playSound("glueWallCrack")
                msg(t("robertGlueDown"))
            } else if (breaks) {
                msg("${t("robertGlueCrack")} ${t("glueWallBreaks")} $progress.")
            } else {
                msg(t("robertGlueBlocked"))
            }
            robertOnHit(direct = false)
            return
        }

        // Robert's cover: eleven hits, broken on the twelfth.
        if (state.robertCoverActive) {
            val hits = state.robertCoverHits + 1
            if (hits > Combat.COVER_MAX_HITS) {
                _gameState.update { it.copy(robertCoverActive = false, robertCoverHits = 0) }
                playSound("coverCrack", volume = 1.0f)
                msg(t("robertCoverDown"))
            } else {
                _gameState.update { it.copy(robertCoverHits = hits) }
                playSound("coverHit", volume = 0.8f)
                msg("${t("coverHitsLeft")} ${Combat.COVER_MAX_HITS - hits}.")
            }
            robertOnHit(direct = false)
            return
        }

        val damage = Combat.weaponDamage(weapon, proxPercent, state.robertMaxHp)
        val newRobertHp = max(0, state.robertHp - damage)
        _gameState.update {
            it.copy(
                robertHp = newRobertHp,
                playerHits = it.playerHits + 1,
                fightPlayerHits = it.fightPlayerHits + 1,
                robertConsecutiveHits = it.robertConsecutiveHits + 1
            )
        }

        // Three consecutive point-blank hits also damage Robert's weapon. He
        // switches to another one automatically on his next attack.
        var robertWeaponBroke = false
        val heavyStreak = if (zone == HitZone.HEAVY) state.playerHeavyHits + 1 else 0
        if (heavyStreak >= Combat.HITS_TO_DAMAGE_GUN) {
            val robertIndex = state.robertWeaponIndex
            val damagedList = state.robertDamaged.toMutableList()
            if (robertIndex in damagedList.indices && !damagedList[robertIndex]) {
                damagedList[robertIndex] = true
                robertWeaponBroke = true
            }
            _gameState.update { it.copy(playerHeavyHits = 0, robertDamaged = damagedList) }
        } else {
            _gameState.update { it.copy(playerHeavyHits = heavyStreak) }
        }

        playSound(weapon.hitSoundKey, volume = if (zone == HitZone.HEAVY) 1f else 0.7f)
        playSound("enemyHit", volume = if (zone == HitZone.HEAVY) 1f else 0.7f, posZ = -state.distance)

        var text = "${
            when {
                longRange -> t("awmLongRangeHit")
                zone == HitZone.HEAVY -> t("enemyHit")
                else -> t("enemyGrazed")
            }
        } " + "${t("hitFor")} $damage. ${t("enemyHealth")} $newRobertHp."
        if (robertWeaponBroke) {
            // A broken enemy gun is announced in words, never with a stray
            // gunshot/reload sample on the enemy side.
            text += " ${t("robertWeaponDamaged")}"
        }

        if (newRobertHp <= 0) {
            msg(text)
            endGame(won = true)
            return
        }

        text += robertAfterHit()
        text += autoReloadIfNeeded()?.let { " $it" } ?: ""

        // Ammo pickup chance (15%): merged into the hit report.
        if (Random.nextFloat() < 0.15f && weapon.needsReload) {
            var pickedUp = false
            _gameState.update { s ->
                val idx = s.weaponIndex
                val mag = weapon.magazine
                val current = s.ammo.getOrElse(idx) { 0 }
                if (current >= mag) {
                    s
                } else {
                    pickedUp = true
                    s.copy(ammo = s.ammo.toMutableList().also { it[idx] = min(mag, current + 2) })
                }
            }
            if (pickedUp) text += " ${t("ammoPickup")}"
        }

        msg(text)
        robertOnHit()
    }

    /**
     * Holding the up-swipe with a submachine gun (MP40 or machine gun)
     * selected keeps firing: one bullet per [Weapon.burstIntervalMs] for as
     * long as the trigger is held, with the weapon's own sound on a truly
     * gapless loop.
     *
     * The loop is started on the very same gesture that registers the hold,
     * before the first bullet is even resolved, and it is played on the
     * dedicated hold channel of [AudioManager]. That channel is pre-prepared
     * and never cut by the enemy's answering shot, so no silence can appear
     * between two repeats of the gun sound. Lifting the finger pauses it in
     * the same millisecond and the next hold resumes it instantly.
     */
    fun startHoldFire() {
        if (_currentScreen.value != Screen.BATTLE) return
        val state = _gameState.value
        val weapon = state.activeWeapon
        if (!weapon.autoFire || state.autoFireActive) {
            fire()
            return
        }
        if (!holdFireAllowed()) {
            fire()
            return
        }
        markExplicitAction()
        _gameState.update { it.copy(autoFireActive = true) }
        // Sound first, bullets second: the gun is already firing the moment
        // the hand goes down.
        audioManager.startWeaponLoop(
            weapon.fireSoundKey,
            volume = 1.0f,
            posZ = -state.distance
        )
        autoFireJob?.cancel()
        autoFireJob = viewModelScope.launch {
            // The first bullet leaves at once, the rest follow the interval.
            fireOneAutomaticShot()
            while (holdFireAllowed()) {
                delay(weapon.burstIntervalMs)
                if (!holdFireAllowed()) break
                fireOneAutomaticShot()
            }
            stopHoldFire()
        }
    }

    /**
     * Releasing the swipe: bullets and the looped gun sound stop together and
     * immediately, with no tail and no silence in between.
     */
    fun stopHoldFire() {
        autoFireJob?.cancel()
        autoFireJob = null
        val state = _gameState.value
        // The sound is cut first, so lifting the hand is heard as silence at
        // once. Both automatic loops are paused, whatever the current weapon
        // is, which also covers a weapon switch during a burst.
        audioManager.stopWeaponLoop(state.activeWeapon.fireSoundKey)
        audioManager.stopMp40Loop()
        if (!state.autoFireActive) return
        _gameState.update { it.copy(autoFireActive = false) }
    }

    /** One bullet of a held automatic burst. */
    private fun fireOneAutomaticShot() {
        if (!holdFireAllowed()) return
        val state = _gameState.value
        val weapon = state.activeWeapon
        // Watchdog: the trigger is still down, so the loop must be audible.
        // Re-arming it here restores a burst that a stalled stream or a
        // paused player may have interrupted, and refreshes the distance
        // gain while the fighters move.
        if (!audioManager.isWeaponLoopActive(weapon.fireSoundKey)) {
            audioManager.startWeaponLoop(
                weapon.fireSoundKey,
                volume = 1.0f,
                posZ = -state.distance
            )
        } else {
            audioManager.refreshWeaponLoop(weapon.fireSoundKey, volume = 1.0f, posZ = -state.distance)
        }
        lastFireAt = 0L
        fire()
    }

    /** True while a held automatic burst may keep firing. */
    private fun holdFireAllowed(): Boolean {
        if (!foreground || _currentScreen.value != Screen.BATTLE) return false
        val state = _gameState.value
        if (state.playerHp <= 0 || state.robertHp <= 0) return false
        if (state.missileInFlight || state.bombInFlight || isBattleEnding()) return false
        if (state.activeWeaponDamaged) return false
        if (!state.activeWeapon.autoFire) return false
        if (state.timerSelectActive) return false
        // An empty magazine only ends the burst when nothing can refill it:
        // the automatic reload extension keeps a held burst alive.
        if (state.activeAmmo > 0) return true
        return _inventory.value.extensionEnabled(Extension.AUTO_RELOAD)
    }

    /** True while the selected weapon is a hold-to-fire submachine gun. */
    fun activeWeaponIsAutomatic(): Boolean = _gameState.value.activeWeapon.autoFire

    /** Reloads the empty magazine automatically when the extension is owned. */
    private fun autoReloadIfNeeded(): String? {
        val state = _gameState.value
        val weapon = state.activeWeapon
        if (!weapon.needsReload || state.activeAmmo > 0) return null
        if (!_inventory.value.extensionEnabled(Extension.AUTO_RELOAD)) return null
        _gameState.update { s ->
            val idx = s.weaponIndex.coerceIn(0, s.ammo.lastIndex)
            s.copy(ammo = s.ammo.toMutableList().also { it[idx] = weapon.magazine })
        }
        playSound("gunReload")
        return t("autoReloaded")
    }

    fun doReload() {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val state = _gameState.value
        val weapon = state.activeWeapon
        if (weapon.missileLauncher) {
            // The missile has no magazine: swipe down buys one missile
            // power for 600 coins, as announced when none is owned.
            buyMissilePowerInBattle()
            return
        }
        if (!weapon.needsReload) {
            // Melee weapons are always ready: they have no magazine at all.
            playSound("click")
            msg(t("meleeNoReload"))
            return
        }
        if (state.activeAmmo >= weapon.magazine) {
            playSound("ammo")
            msg(t("magFull"))
            return
        }
        _gameState.update { s ->
            val idx = s.weaponIndex.coerceIn(0, s.ammo.lastIndex)
            s.copy(ammo = s.ammo.toMutableList().also { it[idx] = weapon.magazine })
        }
        playSound("gunReload")
        msg(t("reloaded"))
    }

    /**
     * Right and left swipes walk through the weapons the player actually owns.
     * The chosen weapon is remembered for the next game.
     */
    fun switchWeapon(direction: Int) {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        // Switching weapons always ends a held burst first: the old gun's
        // loop must never bleed into the new weapon.
        if (_gameState.value.autoFireActive) stopHoldFire()
        val state = _gameState.value
        // While the timer bomb picker is open the same left / right swipe
        // walks through the durations instead of changing weapon.
        if (state.timerSelectActive) {
            cycleTimerSelection(direction)
            return
        }
        if (state.weapons.isEmpty()) return
        val next = ((state.weaponIndex + direction) % state.weapons.size + state.weapons.size) %
            state.weapons.size
        _gameState.update { it.copy(weaponIndex = next) }
        val weapon = _gameState.value.activeWeapon

        playSound(weapon.switchSoundKey)
        rememberWeapon(weapon)

        val damagedNote = if (_gameState.value.activeWeaponDamaged) {
            " ${t("damaged")}. ${t("recovery")}: ${Combat.GUN_RECOVERY_COST}."
        } else {
            ""
        }
        // Moving from a broken weapon to a working one ends Robert's pressure.
        val pressureNote = if (state.activeWeaponDamaged && !_gameState.value.activeWeaponDamaged) {
            " ${t("robertPressureOver")}"
        } else {
            ""
        }
        val ammoNote = if (weapon.needsReload) {
            " ${t("ammo")} ${_gameState.value.activeAmmo}."
        } else {
            ""
        }
        // The missile announces its stacked powers, or the swipe-down
        // purchase when the player owns none.
        val bombNote = if (weapon.bomb) {
            val needsPower = BombRules.requiresBombPower(_gameState.value.round, BombKind.fromWeapon(weapon))
            if (needsPower) " ${t("bombPowerNeeded")}" else " ${t("grenadeFreeMsg")}"
        } else {
            ""
        }
        val missileNote = if (weapon.missileLauncher) {
            val powers = _inventory.value.missilePowers
            if (powers > 0) {
                " ${t("extMissilePowers")} $powers. ${t("missileReady")}"
            } else {
                " ${t("missileNoPower")}"
            }
        } else {
            ""
        }
        msg("${t("switchedTo")} ${t(weapon.nameKey)}.$ammoNote$damagedNote$pressureNote$bombNote$missileNote")
    }

    private fun rememberWeapon(weapon: Weapon) {
        if (_inventory.value.lastWeaponId == weapon.id) return
        val updated = _inventory.value.copy(lastWeaponId = weapon.id)
        _inventory.value = updated
        prefs.saveInventory(updated)
    }

    fun recoverGun() {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val state = _gameState.value
        if (!state.anyWeaponDamaged) {
            playSound("click")
            msg(t("gunNotDamaged"))
            return
        }
        if (_stats.value.coins < Combat.GUN_RECOVERY_COST) {
            notEnoughCoins()
            return
        }
        _stats.value = _stats.value.copy(coins = _stats.value.coins - Combat.GUN_RECOVERY_COST)
        prefs.saveStats(_stats.value)
        _gameState.update { it.copy(damaged = it.damaged.map { false }) }
        // Recovery is a shop action, not a shot: only the coin sound plays.
        // A tap in the center of the battle screen must never sound like a gun.
        playSound("spend")
        msg("${t("gunRecovered")} ${t("coinsSpent")}: ${Combat.GUN_RECOVERY_COST}.")
    }

    // ---------------------------------------------------------------------
    // Quick menu and defences
    // ---------------------------------------------------------------------

    /**
     * Cycles the quick menu: medikit, cover, glue wall, anti-missile,
     * anti-bomb explosive, exit, recover.
     */
    fun cycleMenu(direction: Int) {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val state = _gameState.value
        val nextIndex = state.menuIndex + direction
        _gameState.update {
            it.copy(menuIndex = nextIndex, menuActive = true, pendingPurchaseId = null)
        }
        // Browsing the items is a real navigation, so it is protected.
        armMenuProtection()
        playSound("click")
        speakBattleMenu()
    }

    /**
     * Starts the short window in which Robert's hits are cut down to
     * [DamageTable.MENU_PROTECTION_PERCENT] percent of maximum health. It is
     * armed by every genuine quick-menu step and expires by itself after
     * [DamageTable.MENU_PROTECTION_MS], so it can never become invulnerability.
     */
    private fun armMenuProtection() {
        menuProtectUntilMs = System.currentTimeMillis() + DamageTable.MENU_PROTECTION_MS
    }

    /** True while the menu-protection window is still open. */
    private fun isMenuProtectionActive(): Boolean =
        menuProtectUntilMs > 0L && System.currentTimeMillis() < menuProtectUntilMs

    private fun speakBattleMenu() {
        val item = _gameState.value.menuItem
        when (item) {
            BattleMenuItem.MEDKIT,
            BattleMenuItem.COVER,
            BattleMenuItem.GLUE_WALL,
            BattleMenuItem.ANTI_MISSILE,
            BattleMenuItem.ANTI_BOMB -> {
                val defence = item.defence ?: return
                val free = defence == DefenceItem.ANTI_BOMB &&
                    _gameState.value.playerAntiBombFree
                val count = if (free) t("freeItem") else "${_inventory.value.count(defence)}"
                speak("${t("menuNow")}: ${t(defence.nameKey)}. $count.")
            }
            BattleMenuItem.EXIT -> speak("${t("menuNow")}: ${t("exitGame")}.")
            BattleMenuItem.RECOVER -> speak(
                "${t("menuNow")}: ${t("recoverWeapon")}. " +
                    "${Combat.GUN_RECOVERY_COST} ${t("coins")}."
            )
        }
    }

    /**
     * A single tap activates the highlighted menu item. A defence that is not
     * owned asks for a confirming second tap and is then bought and used.
     */
    fun activateMenuItem() {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val state = _gameState.value
        val item = state.menuItem
        when (item) {
            BattleMenuItem.MEDKIT,
            BattleMenuItem.COVER,
            BattleMenuItem.GLUE_WALL,
            BattleMenuItem.ANTI_MISSILE,
            BattleMenuItem.ANTI_BOMB -> {
                val defence = item.defence ?: return
                useDefenceFromMenu(defence)
            }
            BattleMenuItem.EXIT -> exitBattle()
            BattleMenuItem.RECOVER -> {
                // Recovery replaces the old "play" item and costs 100 coins.
                recoverGun()
                resetMenuSelection()
            }
        }
    }

    private fun useDefenceFromMenu(defence: DefenceItem) {
        val inventory = _inventory.value
        val state = _gameState.value
        // From round three the anti-bomb explosive is free, so it is deployed
        // straight from the menu with no count and no purchase prompt.
        if (defence == DefenceItem.ANTI_BOMB && state.playerAntiBombFree) {
            _gameState.update { it.copy(pendingPurchaseId = null) }
            val spoken = useDefence(defence)
            if (spoken != null) msg(spoken)
            resetMenuSelection()
            return
        }
        // Both interceptors only make sense with a projectile in the air.
        val antiMissileIdle = defence == DefenceItem.ANTI_MISSILE && !state.missileInFlight
        val antiBombIdle = defence == DefenceItem.ANTI_BOMB &&
            (!state.bombInFlight || state.bombFromPlayer)
        if (antiMissileIdle || antiBombIdle) {
            if (inventory.count(defence) <= 0 &&
                _stats.value.completedRounds < defence.requiredCompletedRounds
            ) {
                playSound("ammo")
                val text = t(defence.lockedKey)
                toast(text)
                msg(text)
            } else {
                playSound("click")
                msg(if (antiMissileIdle) t("noActiveMissile") else t("noActiveBomb"))
            }
            resetMenuSelection()
            return
        }

        if (inventory.count(defence) > 0) {
            _gameState.update { it.copy(pendingPurchaseId = null) }
            val spoken = useDefence(defence)
            if (spoken != null) msg(spoken)
            resetMenuSelection()
            return
        }

        if (_stats.value.completedRounds < defence.requiredCompletedRounds) {
            _gameState.update { it.copy(pendingPurchaseId = null) }
            playSound("ammo")
            val text = t(defence.lockedKey)
            toast(text)
            msg(text)
            resetMenuSelection()
            return
        }

        val pending = _gameState.value.pendingPurchaseId
        if (pending == defence.id) {
            // Second tap: buy one at the shop price, then use it immediately.
            val purchase = Shop.buyDefence(
                _inventory.value,
                _stats.value.coins,
                defence,
                _stats.value.completedRounds
            )
            if (!purchase.success) {
                _gameState.update { it.copy(pendingPurchaseId = null) }
                val locked = purchase.reasonKey == "antiMissileLocked" ||
                    purchase.reasonKey == defence.lockedKey
                if (locked) {
                    playSound("ammo")
                    val text = t(defence.lockedKey)
                    toast(text)
                    msg(text)
                } else {
                    notEnoughCoins()
                }
                return
            }
            _inventory.value = purchase.inventory
            prefs.saveInventory(purchase.inventory)
            _stats.value = _stats.value.copy(coins = purchase.coins)
            prefs.saveStats(_stats.value)
            playSound("spend")
            _gameState.update { it.copy(pendingPurchaseId = null) }
            val spoken = useDefence(defence)
            val text = "${t(defence.nameKey)} ${t("itemAutoActivated")}" +
                (spoken?.let { " $it" } ?: "")
            msg(text)
            resetMenuSelection()
        } else {
            _gameState.update { it.copy(pendingPurchaseId = defence.id, menuActive = true) }
            playSound("ammo")
            val text = "${t("itemMissingPrompt")} ${t(defence.nameKey)}. ${t("itemMissingTail")}"
            msg(text)
        }
    }

    /** Puts the quick menu back on its safe default item. */
    private fun resetMenuSelection() {
        // The navigation is over, so its protection ends with it.
        menuProtectUntilMs = 0L
        _gameState.update {
            it.copy(
                menuIndex = BattleMenuItem.RECOVER_INDEX,
                menuActive = false,
                pendingPurchaseId = null
            )
        }
    }

    /**
     * Consumes exactly one defence and applies its effect. Returns the spoken
     * description, or null when the item could not be used.
     */
    private fun useDefence(item: DefenceItem, automatic: Boolean = false): String? {
        val state = _gameState.value
        val inventory = _inventory.value
        // From round three the player's anti-bomb explosive is free and always
        // usable, so it never needs an inventory count.
        val freeAntiBomb = item == DefenceItem.ANTI_BOMB && state.playerAntiBombFree
        if (!freeAntiBomb && inventory.count(item) <= 0) return null

        when (item) {
            DefenceItem.MEDKIT -> {
                if (state.playerHp >= state.playerMaxHp) {
                    playSound("click")
                    return "${t("yourHealth")} ${state.playerHp}."
                }
                _gameState.update { it.copy(playerHp = it.playerMaxHp) }
                consumeDefence(item)
                playSound("medkit")
                return (if (automatic) t("autoMedkitUsed") else t("medkitUsed")) +
                    " ${_gameState.value.playerHp}."
            }
            DefenceItem.COVER -> {
                // The two barriers are mutually exclusive: raising one while
                // the other is already up says which one is already active.
                if (state.coverActive || state.glueWallActive) {
                    if (automatic) return null
                    playSound("click")
                    return t(
                        if (state.coverActive) "coverAlreadyActivated"
                        else "glueWallAlreadyActivated"
                    )
                }
                _gameState.update { it.copy(coverActive = true, coverHits = 0) }
                consumeDefence(item)
                playSound("cover")
                return (if (automatic) t("autoCoverUsed") else t("coverDeployed")) +
                    " ${Combat.COVER_MAX_HITS}."
            }
            DefenceItem.GLUE_WALL -> {
                if (state.glueWallActive || state.coverActive) {
                    if (automatic) return null
                    playSound("click")
                    return t(
                        if (state.glueWallActive) "glueWallAlreadyActivated"
                        else "coverAlreadyActivated"
                    )
                }
                _gameState.update { it.copy(glueWallActive = true, glueWallProgress = 0) }
                consumeDefence(item)
                playSound("glueSound")
                ensureRobertPositioned()
                return if (automatic) t("autoGlueWallUsed") else t("glooWallDeployed")
            }
            DefenceItem.ANTI_MISSILE -> {
                if (!state.missileInFlight) {
                    playSound("click")
                    return t("noActiveMissile")
                }
                missileJob?.cancel()
                missileJob = null
                missileImpactAtMs = 0L
                audioManager.stopSound("missile")
                audioManager.stopSound("enemyWalk")
                _gameState.update {
                    it.copy(
                        missileInFlight = false,
                        missileFromPlayer = false,
                        missileInterceptPlanned = false,
                        robertState = if (it.robertState == "missileFlee") "advance" else it.robertState,
                        robertLateral = 0f
                    )
                }
                consumeDefence(item)
                playSound("explosion")
                planRobertStep(500L)
                return t("antiMissileDestroyed")
            }
            DefenceItem.ANTI_BOMB -> {
                // The anti-bomb explosive is deployed on hearing a bomb: it
                // marks the bomb in the air as destroyed, and the explosion
                // sound still plays when the countdown ends. From round three
                // the player's one is free, so no inventory is spent.
                if (!state.bombInFlight || state.bombFromPlayer) {
                    playSound("click")
                    return t("noActiveBomb")
                }
                if (state.bombInterceptedByPlayer) {
                    playSound("click")
                    return t("antiBombAlreadyDeployed")
                }
                _gameState.update { it.copy(bombInterceptedByPlayer = true) }
                if (!freeAntiBomb) consumeDefence(item)
                playSound("click")
                return t("antiBombDeployed")
            }
        }
    }

    private fun consumeDefence(item: DefenceItem) {
        val updated = Shop.consume(_inventory.value, item)
        _inventory.value = updated
        prefs.saveInventory(updated)
    }

    fun speakStatus(own: Boolean) {
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val state = _gameState.value
        if (own) {
            val weapon = state.activeWeapon
            val weaponState = if (state.activeWeaponDamaged) {
                "${t("damaged")}. ${t("recovery")}: ${Combat.GUN_RECOVERY_COST}"
            } else {
                t(weapon.nameKey)
            }
            val weapons = state.weapons
                .mapNotNull { Weapon.fromId(it) }
                .joinToString(", ") { owned ->
                    val load = if (owned.missileLauncher) {
                        _inventory.value.missilePowers
                    } else {
                        state.ammo.getOrElse(state.weapons.indexOf(owned.id)) { 0 }
                    }
                    "${t(owned.nameKey)} $load"
                }
            val inv = _inventory.value
            val extensions = Extension.entries
                .filter { inv.extensionOwned(it) }
                .joinToString(", ") {
                    "${t(it.nameKey)} ${if (inv.extensionEnabled(it)) t("enabledState") else t("disabledState")}"
                }
                .ifEmpty { t("noneOwned") }
            val chance = if (state.playerSecondChanceUsed) " ${t("secondChanceUsed")}." else ""
            // The final round is timed: the remaining time belongs to the
            // own status read as well.
            val timeText = if (state.timeLimitActive) {
                " ${t("timeRemaining")} ${(state.battleRemainingMs / 1000L)} ${t("seconds")}."
            } else {
                ""
            }
            val antiBombText = if (state.playerAntiBombFree) {
                " ${t("playerAntiBombFree")}"
            } else {
                ""
            }
            // The three-finger running speed and the position inside the
            // 100 metre zone belong to the own status too.
            val speedText = "${t("runSpeed")} ${t(state.runSpeedLevel.nameKey)}." +
                if (inv.extensionEnabled(Extension.MAX_SPEED)) " ${t("maxSpeedOn")}" else ""
            val positionText = "${t("yourPosition")} ${state.playerX.roundToInt()}, " +
                "${state.playerY.roundToInt()} ${t("meters")}."
            val bombText = if (state.bombInFlight) {
                " ${t("bombDestination")} ${state.playerDistanceFromBomb.roundToInt()} ${t("meters")}."
            } else {
                ""
            }
            speak(
                "${t("yourHealth")} ${state.playerHp}. ${t("ammo")} ${state.activeAmmo}. " +
                    "$weaponState. $speedText $positionText $weapons. " +
                    "${t("medkits")} ${inv.medikits}. ${t("glooWall")} ${inv.glueWalls}. " +
                    "${t("cover")} ${inv.covers}. ${t("antiMissile")} ${inv.antiMissiles}. " +
                    "${t("antiBomb")} ${inv.antiBombs}.$antiBombText$bombText $timeText " +
                    "${if (state.coverActive) t("coverOn") else t("coverOff")}. " +
                    "${t("profileExtensions")} $extensions. ${t("coins")} ${_stats.value.coins}.$chance"
            )
        } else {
            val coverText = if (state.robertCoverActive) {
                " ${t("cover")} ${Combat.COVER_MAX_HITS - state.robertCoverHits}."
            } else ""
            val glueText = if (state.robertGlueWallActive) {
                " ${t("glooWall")} ${state.robertGlueWallProgress}."
            } else ""
            val weaponText = state.robertWeapon?.let { t(it.nameKey) } ?: ""
            val positionText = "${t("enemyPosition")} ${state.robertX.roundToInt()}, " +
                "${state.robertY.roundToInt()} ${t("meters")}."
            // While a bomb counts down its destination is announced next to
            // the enemy health: the distance from Robert to the blast, and
            // the distance the player still has to cover to be safe.
            val bombText = if (state.bombInFlight) {
                " ${t("bombDestination")}: ${t("enemy")} " +
                    "${state.robertDistanceFromBomb.roundToInt()} ${t("meters")}, " +
                    "${t("you")} ${state.playerDistanceFromBomb.roundToInt()} ${t("meters")}."
            } else {
                ""
            }
            speak(
                "${t("enemyHealth")} ${state.robertHp}. ${t("distance")} " +
                    "${state.distance.roundToInt()} ${t("meters")}. $positionText $weaponText." +
                    "$coverText$glueText$bombText"
            )
        }
    }

    fun exitBattle() {
        cancelTimerSelection()
        stopTimers()
        audioManager.stopFootsteps()
        audioManager.stopScreenAudio()
        ttsManager.stop()
        playSound("click")
        _currentScreen.value = Screen.MENU
        updateAudioForScreen(Screen.MENU)
        speak(t("exited"))
    }

    // ---------------------------------------------------------------------
    // End game and progression
    // ---------------------------------------------------------------------

    /**
     * Ends a battle: coins are awarded, five wins complete a level, five
     * levels complete a round, the fifth round wins the match, and the result
     * screen is locked for four seconds.
     *
     * A completed level pays one of the five random reward bundles (never
     * more than one glue wall) with level-complete.mp3, and a completed round
     * always pays two glue walls, two medikits and three covers with
     * round-complete.mp3.
     */
    private fun endGame(won: Boolean, timeUp: Boolean = false) {
        stopTimers()
        audioManager.stopFootsteps()

        val state = _gameState.value
        val oldStats = _stats.value
        // A defeat costs the player 20 coins instead of earning any. A player
        // with no coins is the exception: nothing can be taken from them.
        val coinsLost = if (won) 0 else Progression.coinsLostOnDefeat(oldStats.coins)
        val gain = if (won) {
            val earned = (150 + state.playerHits * 10 + max(0, state.playerHp) * 0.5f).roundToInt()
            max(earned, 20)
        } else {
            -coinsLost
        }

        var updatedStats = oldStats.copy(
            coins = (oldStats.coins + gain).coerceAtLeast(0),
            games = oldStats.games + 1,
            wins = if (won) oldStats.wins + 1 else oldStats.wins,
            kills = if (won) oldStats.kills + 1 else oldStats.kills
        )

        var leveled = false
        var roundCompleted = false
        var matchWon = false
        var antiMissileReward = false
        var reward = RewardBundle(emptyMap())
        if (won) {
            val promotion = Progression.applyWin(updatedStats)
            updatedStats = promotion.stats
            leveled = promotion.leveledUp
            roundCompleted = promotion.roundCompleted
            matchWon = promotion.matchWon
            // The reward of the promotion: the fixed round bundle when a
            // round was completed, otherwise one random level bundle.
            reward = Rewards.forPromotion(
                levelCompleted = leveled,
                roundCompleted = roundCompleted,
                random = Random
            )
            // Completing round three (entering round four) also unlocks the
            // anti-missile system by handing out one free interceptor.
            val enteringRoundFour = roundCompleted && !matchWon &&
                updatedStats.round >= Progression.ANTI_MISSILE_REWARD_ROUND
            if (enteringRoundFour && !_inventory.value.antiMissileRewardGranted) {
                reward += RewardBundle(mapOf(DefenceItem.ANTI_MISSILE to 1))
                antiMissileReward = true
            }
            if (!reward.isEmpty) {
                var granted = Shop.grantBundle(_inventory.value, reward)
                if (antiMissileReward) {
                    granted = granted.copy(antiMissileRewardGranted = true)
                }
                _inventory.value = granted
                prefs.saveInventory(granted)
            }
        }

        _stats.value = updatedStats
        prefs.saveStats(updatedStats)

        _resultData.value = ResultData(
            won = won,
            coins = gain,
            hits = state.playerHits,
            shots = state.playerShots,
            robertShots = state.robertShots,
            hp = state.playerHp,
            levelUp = leveled,
            roundComplete = roundCompleted || matchWon,
            matchWon = matchWon,
            round = updatedStats.round,
            level = updatedStats.level,
            rewardText = if (reward.isEmpty) "" else rewardSpeech(reward),
            rewardMedkits = reward.medikits,
            rewardCovers = reward.covers,
            rewardGlueWalls = reward.glueWalls,
            coinsLost = coinsLost,
            timeUp = timeUp
        )

        _gameState.update { it.copy(matchWon = matchWon) }
        _currentScreen.value = Screen.RESULT
        updateAudioForScreen(Screen.RESULT)
        // Distinct progression sounds as requested: round completion plays only
        // round-complete, level completion plays only level-complete — not combined
        // with the win sound. Win/lose only plays when no level/round was completed.
        when {
            matchWon || roundCompleted -> playSound("roundComplete", volume = ROUND_COMPLETE_VOLUME)
            leveled -> playSound("levelComplete", volume = LEVEL_COMPLETE_VOLUME)
            won -> playSound("win")
            else -> playSound("lose")
        }
        startResultLock()

        var speech = if (won) {
            t("victoryMsg")
        } else if (timeUp) {
            t("timeUpDefeat")
        } else {
            t("defeatMsg")
        }
        if (won) {
            speech += " ${t("earned")} $gain."
        } else if (coinsLost > 0) {
            speech += " ${t("coinsLostOnDefeat")} $coinsLost."
        } else {
            speech += " ${t("noCoinsToLose")}"
        }
        if (matchWon) {
            speech += " ${t("matchWonMsg")}"
        } else if (roundCompleted) {
            speech += " ${t("roundCompleted")} ${t("round")} ${updatedStats.round}!"
        } else if (leveled) {
            speech += " ${t("newLevel")} ${updatedStats.level}."
        }
        if (!reward.isEmpty) {
            speech += " ${t("rewardReceived")} ${rewardSpeech(reward)}."
        }
        if (antiMissileReward) {
            speech += " ${t("antiMissileReward")}"
        }
        speech += " ${t("resultWait")}"
        speak(speech)
    }

    /** A reward bundle as a spoken list, in the language of the player. */
    private fun rewardSpeech(bundle: RewardBundle): String =
        bundle.parts().joinToString(", ") { part ->
            "${t(part.first.pluralKey)} ${part.second}"
        }

    private fun startResultLock() {
        resultJob?.cancel()
        _resultReady.value = false
        resultJob = viewModelScope.launch {
            delay(RESULT_LOCK_MS)
            _resultReady.value = true
        }
    }

    /** Play Again: only possible after the four second result lock. */
    fun playAgain() {
        if (!_resultReady.value) {
            playSound("ammo")
            speak(t("waitPlease"))
            return
        }
        resultJob?.cancel()
        resultJob = null
        stopTimers()
        audioManager.stopSound("win")
        audioManager.stopSound("lose")
        audioManager.stopScreenAudio()
        ttsManager.stop()
        playSound("click")
        resetBattleState()
        _currentScreen.value = Screen.JUMP
        _gameState.update { it.copy(log = t("jumpMsg")) }
        updateAudioForScreen(Screen.JUMP)
        speak(t("jumpMsg"))
    }

    // ---------------------------------------------------------------------
    // Stats
    // ---------------------------------------------------------------------

    fun clearStats() {
        stopTimers()
        audioManager.stopFootsteps()
        ttsManager.stop()

        val cleared = GameStats.cleared()
        prefs.clearStats(cleared)
        _stats.value = cleared
        _inventory.value = Inventory()
        _resultData.value = ResultData()
        resetBattleState()
        updateAudioForScreen(_currentScreen.value)

        playSound("spend", volume = 0.9f)
        msg(t("statsCleared"))
    }

    // ---------------------------------------------------------------------
    // Gestures
    // ---------------------------------------------------------------------

    fun handleGesture(direction: String, fingers: Int) {
        when (GestureRouter.route(_currentScreen.value, direction, fingers)) {
            GameAction.CONTINUE -> continueToJump()
            GameAction.BACK_TO_MENU -> navigateTo(Screen.MENU)
            GameAction.JUMP -> doJump()
            GameAction.OPEN_PARA -> doPara()
            GameAction.LAND -> doLand()
            GameAction.FIRE -> fire()
            GameAction.RELOAD -> doReload()
            GameAction.NEXT_WEAPON -> switchWeapon(1)
            GameAction.PREV_WEAPON -> switchWeapon(-1)
            GameAction.MENU_NEXT -> cycleMenu(1)
            GameAction.MENU_PREV -> cycleMenu(-1)
            GameAction.ACTIVATE_MENU -> activateMenuItem()
            GameAction.STATUS_OWN -> speakStatus(own = true)
            GameAction.STATUS_ENEMY -> speakStatus(own = false)
            GameAction.EXIT_BATTLE -> exitBattle()
            GameAction.PLAY_AGAIN -> playAgain()
            // Three fingers: the running speed cycles slow, normal, fast.
            GameAction.SPEED_FASTER -> cycleRunSpeed(1)
            GameAction.SPEED_SLOWER -> cycleRunSpeed(-1)
            GameAction.NONE -> {}
        }
    }

    fun handleCenterTap() {
        // Buttons and gestures set this marker; a tap immediately afterwards
        // belongs to that action and must not activate the menu as well.
        if (System.currentTimeMillis() - lastExplicitActionAt < TAP_ACTION_DEBOUNCE_MS) return
        // While the timer bomb picker is open a tap locks the highlighted
        // duration in and throws the bomb.
        if (_gameState.value.timerSelectActive) {
            confirmTimerSelection()
            return
        }
        // The center third is the menu activation surface. Recover is the
        // safe default, so a tap is valid even before the first menu swipe.
        // Menu actions only ever play neutral clicks, coins or defence
        // sounds - never a gunshot.
        activateMenuItem()
    }

    fun handleTap() {
        handleCenterTap()
    }

    private fun markExplicitAction() {
        lastExplicitActionAt = System.currentTimeMillis()
    }

    private fun stopTimers() {
        playerMoveJob?.cancel()
        playerMoveJob = null
        stepJob?.cancel()
        stepJob = null
        counterJob?.cancel()
        counterJob = null
        robertFireJob?.cancel()
        robertFireJob = null
        missileJob?.cancel()
        missileJob = null
        bombJob?.cancel()
        bombJob = null
        autoFireJob?.cancel()
        autoFireJob = null
        endingJob?.cancel()
        endingJob = null
        matchLimitJob?.cancel()
        matchLimitJob = null
        matchDeadlineMs = 0L
        robertSprintTicks = 0
        robertRetaliationAtMs = 0L
        playerAtWall = false
        // Stop a missile or a bomb still in the air (their countdown sounds
        // included), but never cut an explosion that has just impacted:
        // screen changes stop that separately. Every weapon stream and every
        // queued weapon request is cleared too, so no gunshot can leak into
        // the next screen or the next battle.
        audioManager.stopBattleProjectiles()
        audioManager.stopWeaponSounds()
        audioManager.stopWeaponLoops()
        audioManager.stopFootsteps()
        // A held burst never survives a screen change or an app pause.
        if (_gameState.value.autoFireActive) {
            _gameState.update { it.copy(autoFireActive = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTimers()
        resultJob?.cancel()
        audioManager.release()
        ttsManager.shutdown()
    }
}
