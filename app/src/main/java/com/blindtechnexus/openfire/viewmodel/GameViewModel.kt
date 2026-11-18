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
import com.blindtechnexus.openfire.model.AutomaticFirePowerMap
import com.blindtechnexus.openfire.model.BattleMenuItem
import com.blindtechnexus.openfire.model.BattlePerformance
import com.blindtechnexus.openfire.model.CarRules
import com.blindtechnexus.openfire.model.CarStage
import com.blindtechnexus.openfire.model.ElectricShockRules
import com.blindtechnexus.openfire.model.BattleClock
import com.blindtechnexus.openfire.model.BombKind
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.DamageTable
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.EnemyFireStrategy
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.Facing
import com.blindtechnexus.openfire.model.FireRange
import com.blindtechnexus.openfire.model.FireCadence
import com.blindtechnexus.openfire.model.GameAction
import com.blindtechnexus.openfire.model.GameSettings
import com.blindtechnexus.openfire.model.GameState
import com.blindtechnexus.openfire.model.GameStats
import com.blindtechnexus.openfire.model.GestureRouter
import com.blindtechnexus.openfire.model.GraceRules
import com.blindtechnexus.openfire.model.HitZone
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.LockRules
import com.blindtechnexus.openfire.model.Loans
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.MoveDirection
import com.blindtechnexus.openfire.model.Progression
import com.blindtechnexus.openfire.model.ResultData
import com.blindtechnexus.openfire.model.RewardBundle
import com.blindtechnexus.openfire.model.RetreatRules
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

    /** Watches a running opponent lock and lifts it the moment its minute ends. */
    private var lockJob: Job? = null
    private var robertFireJob: Job? = null
    private var resultJob: Job? = null
    private var playerMoveJob: Job? = null
    private var missileJob: Job? = null
    private var bombJob: Job? = null
    private var bombTrackJob: Job? = null
    private var autoFireJob: Job? = null
    /** Invalidates an old hold coroutine before another hold can begin. */
    private var autoFireSession = 0L
    private var endingJob: Job? = null
    private var matchLimitJob: Job? = null
    private var parachuteAudioJob: Job? = null
    private var foreground = true
    private val battleClock = BattleClock { android.os.SystemClock.elapsedRealtime() }
    val battlePaused: StateFlow<Boolean> = battleClock.paused
    private var manuallyPaused = false

    /**
     * Flee ticks left of Robert's current sprint. A sprint always runs for
     * [RobertRules.SPRINT_TICKS] ticks at [RobertRules.enemyFleeSpeedMps] -
     * twice the fastest speed the player can run - before he turns it into a
     * flanking re-approach, so "running far away" really means crossing the
     * zone instead of stepping back a couple of metres.
     */
    private var robertSprintTicks = 0

    /** When Robert last announced that he is closing the distance, in battle ms. */
    private var lastEnemyRangeNoticeAtMs = 0L

    /** Whether Robert was outside gun range on the previous attack decision. */
    private var enemyPreviouslyOutsideFireRange = true

    /** Monotonic deadline shared by all enemy gun and breaker shots. */
    private var robertNextShotAtMs = 0L

    /**
     * The automatic weapon Robert is firing right now, or null while no
     * continuous-fire burst of his is open. His burst is cut the moment this
     * changes - to another weapon, to a single shot, or to nothing at all when
     * he stops firing - so his gun is only ever heard while he really is
     * firing it.
     */
    private var robertFireWeaponKey: String? = null

    /**
     * The player's recent shot timestamps (oldest first). [EnemyFireStrategy]
     * reads the gaps between them to answer the player's real firing rhythm.
     */
    private val playerShotTimes = ArrayDeque<Long>()

    /** Shots still to fire in Robert's three-shot closing burst. */
    private var stopBurstShotsLeft = 0

    /** When the last closing burst finished, so it cannot restart at once. */
    private var lastStopBurstAtMs = 0L

    /** Set when the third closing shot has fired: the loop starts the run. */
    private var stopBurstFinished = false

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
    private var missileFlightDurationMs: Long = 0L
    private var missileLaunchDistanceM: Float = 0f
    private var missileDeflectionUsed = false
    private var missileApproachJob: Job? = null

    /** Wall-clock moment the bomb in the air is due to explode. */
    private var bombBlastAtMs: Long = 0L
    private var bombFlightDurationMs: Long = 0L
    private var bombApproachJob: Job? = null

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

    /** Last voice-related settings applied, to avoid resetting TTS while dragging volume. */
    private var syncedVoiceSignature: String? = null

    /** Debounce so one physical tap / swipe can never fire (and spend) more than one bullet. */
    private var lastFireAt = 0L
    private var nextPlayerShotAtMs = 0L
    private var lastIncomingHitAtMs = -1L
    private var recentIncomingHits = 0
    private var lastIncomingDamage = 0

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
            // The pistol is mirrored: the moment the player owns one, the
            // enemy carries one too. The sniper rifle is never mirrored any
            // more - a gloo wall is destroyed by the Gloo Wall Breaker, not
            // by a sniper rifle.
            playerOwnsPistol = _inventory.value.ownsWeapon(Weapon.PISTOL),
            playerGlueWalls = _inventory.value.glueWalls
        )

    private companion object {
        /** The screens that count as "in game" for Voice Guidance. */
        val IN_GAME_SCREENS: Set<Screen> =
            setOf(Screen.JUMP, Screen.PARA, Screen.LAND, Screen.BATTLE)

        /** The languages the speech voice can be set to. */
        val VOICE_LANGUAGES: Set<String> = setOf("en", "hi", "ne")

        /** Fast retreat: metres gained per flee tick, and the tick length. */
        const val FLEE_STEP = RobertRules.FLEE_STEP_M
        const val FLEE_TICK_MS = RobertRules.FLEE_TICK_MS

        /** The step interval of Robert's ordinary advance (the standard tick). */
        const val ROBERT_STEP_INTERVAL_MS = 100L

        /**
         * How often he may say that he is closing the distance, so an approach
         * across the arena is announced once instead of ten times a second.
         */
        const val ENEMY_RANGE_NOTICE_MS = 2_500L

        /**
         * Player movement at the normal speed level: [RunPressure.BASE_STEP_METRES]
         * metres per [PLAYER_MOVE_TICK_MS]. The live step is scaled by the
         * three-finger speed level and, in the normal level, by the touch
         * pressure: a stronger press runs faster, a lighter press runs slower.
         */
        const val PLAYER_MOVE_STEP = RunPressure.BASE_STEP_METRES
        const val PLAYER_MOVE_TICK_MS = 100L
        /** One step of the electric shock damage: a tenth of a second. */
        const val SHOCK_TICK_MS = 100L

        /** Fastest and slowest movement tick, so a sprint still looks smooth. */
        const val MIN_MOVE_TICK_MS = 30L
        const val MAX_MOVE_TICK_MS = 220L

        /** The wall announcement is repeated at most once per this window. */
        const val WALL_ANNOUNCE_COOLDOWN_MS = 1_400L


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

        /**
         * Flight and blast volumes of the launchers. Every one of them sits at
         * full scale: a grenade and an atom bomb are as audible as the speech,
         * and the SFX slider scales them down exactly once if the player asks.
         */
        const val MISSILE_VOLUME = 1.0f
        const val BOMB_VOLUME = 1.0f
        const val WALL_VOLUME = 1.0f

        /**
         * The machine gun, the submachine gun, the AK-47 and every automatic
         * weapon play at full scale for both fighters, so a burst is never
         * quieter than the voice that reports it.
         */
        const val AUTOMATIC_WEAPON_VOLUME = 1.0f

        /** Progression stings play at the level of every other effect. */
        const val LEVEL_COMPLETE_VOLUME = 1.0f
        const val ROUND_COMPLETE_VOLUME = 1.0f

        /** The hum of an electric shock runs for as long as it is held. */
        const val SHOCK_HUM_VOLUME = 0.8f

        /**
         * True until the opening greeting of this process has been announced.
         * It lives here, not on the instance, so a configuration change cannot
         * make the app talk over itself on every rotation.
         */
        private var launchGreetingPending = true

    }

    init {
        syncSettingsToManagers(_settings.value)
        updateAudioForScreen(_currentScreen.value)
        // The app greets the user as soon as it opens. The greeting is spoken
        // by the TTS engine (which queues it until it is ready), so a fresh
        // launch is never silent, and it is announced exactly once per process
        // - never repeated when the view model is rebuilt for a configuration
        // change.
        if (launchGreetingPending) {
            launchGreetingPending = false
            speak(launchGreeting())
        }
    }

    /**
     * The opening announcement: the title of the game, who made it, and what
     * to do next. It is built from the same translated strings the screens
     * use, so it follows the user's language from the very first second.
     */
    private fun launchGreeting(): String {
        val name = _settings.value.name.trim()
        return if (name.isEmpty()) t("welcome") else "${t("welcome")} $name."
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

        ttsManager.speechRate = s.ttsRate
        ttsManager.speechVolume = s.ttsVolume
        // Two independent values: the interface language of the text and the
        // language of the engine voice. Both are saved and both are applied,
        // so opening Settings can never load one in place of the other.
        ttsManager.currentLanguage = s.lang
        ttsManager.currentTtsLanguage = s.effectiveTtsLanguage
        ttsManager.currentVoiceName = s.voiceName
        if (s.ttsEnginePackage != ttsManager.currentEnginePackage) {
            ttsManager.initTts(s.ttsEnginePackage.ifEmpty { null })
            syncedVoiceSignature = null
        } else {
            val signature =
                "${s.lang}|${s.effectiveTtsLanguage}|${s.voiceName}|${s.ttsRate}|${s.ttsEnginePackage}"
            if (signature != syncedVoiceSignature) {
                // Rebuild the voice list for the selected voice language first,
                // then apply the saved voice: the list the player sees in
                // Settings is always the list that belongs to that language.
                ttsManager.refreshVoices()
                ttsManager.applyVoiceSettings()
                syncedVoiceSignature = signature
            }
        }
    }

    /**
     * True while spoken game feedback is allowed. With voice guidance off the
     * in-game flow (drop, parachute, landing and the battle itself) speaks
     * nothing, while the menus, the guide, the shop and the result screen keep
     * announcing everything, so the app stays blind-usable.
     */
    fun isSpeechAllowed(): Boolean =
        _settings.value.voiceGuidance || _currentScreen.value !in IN_GAME_SCREENS


    fun t(key: String): String = Strings.get(key, _settings.value.lang)

    /**
     * Logs a line and announces it. The log line is always written; the
     * spoken part follows the Voice Guidance setting, so the battle can be
     * played silently while every menu still speaks.
     */
    fun msg(text: String) {
        _gameState.update { it.copy(log = text) }
        if (isSpeechAllowed()) ttsManager.speak(text, interrupt = true)
    }

    private fun setLog(text: String) {
        _gameState.update { it.copy(log = text) }
    }

    fun speak(text: String, interrupt: Boolean = true) {
        if (!isSpeechAllowed()) return
        ttsManager.speak(text, interrupt)
    }

    fun playSound(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f,
        rate: Float = 1.0f
    ) {
        audioManager.playSound(
            key,
            volume = volume,
            rate = rate,
            posX = posX,
            posY = posY,
            posZ = posZ
        )
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
        // The interface language also moves the voice language when the player
        // left the voice language on "follow the app language".
        val current = _settings.value
        val updated = if (current.ttsLanguage.isBlank()) {
            current.copy(lang = lang, voiceName = "")
        } else {
            current.copy(lang = lang)
        }
        updateSettings(updated)
        ttsManager.refreshVoices()
    }

    /**
     * Chooses the language of the speech voice. An empty value means "follow
     * the interface language". The voice list is rebuilt for the new
     * language and a saved voice that cannot speak it is dropped, so the two
     * Settings dropdowns always agree with each other.
     */
    fun setTtsLanguage(language: String) {
        val chosen = if (language in VOICE_LANGUAGES) language else ""
        val current = _settings.value
        // A voice only survives when it speaks the newly chosen language.
        val keepVoice = chosen.isNotBlank() && chosen == current.effectiveTtsLanguage
        val updated = current.copy(
            ttsLanguage = chosen,
            voiceName = if (keepVoice) current.voiceName else ""
        )
        updateSettings(updated)
        ttsManager.setVoiceLanguage(chosen)
        ttsManager.refreshVoices()
    }

    /**
     * Chooses the speech engine and saves it at once, so reopening Settings -
     * or the next app start - loads the same engine with the same language and
     * the same voice instead of falling back to the system default.
     */
    fun setTtsEngine(enginePackage: String) {
        // [syncSettingsToManagers] notices the engine change, re-initialises
        // the engine and rebuilds its language and voice once it is ready.
        updateSettings(_settings.value.copy(ttsEnginePackage = enginePackage))
        ttsManager.refreshEngines()
    }

    fun setTtsRate(rate: Float) {
        updateSettings(_settings.value.copy(ttsRate = rate.coerceIn(0.5f, 2.0f)))
    }

    fun setVoice(voiceName: String) {
        updateSettings(_settings.value.copy(voiceName = voiceName))
        ttsManager.currentVoiceName = voiceName
        ttsManager.applyVoiceSettings()
    }

    /** Voice Guidance: spoken in-game feedback on or off. */
    fun setVoiceGuidance(enabled: Boolean) {
        updateSettings(_settings.value.copy(voiceGuidance = enabled))
        if (!enabled && _currentScreen.value in IN_GAME_SCREENS) {
            // Stop the announcement that is playing right now as well.
            ttsManager.stop()
        }
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
        if (screen != Screen.PARA) {
            parachuteAudioJob?.cancel()
            parachuteAudioJob = null
        }
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
                parachuteAudioJob = viewModelScope.launch {
                    delay(1_000L)
                    if (_currentScreen.value == Screen.PARA) audioManager.stopSound("parachute")
                    parachuteAudioJob = null
                }
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
                // Deliberately no weapon-loop warm-up here. A firing loop of
                // the submachine gun, the AK-47 or the machine gun is created
                // by the hold itself and destroyed by its release, so the
                // battle can never own a gun player that nobody is firing.
                // The few milliseconds a loop needs to prepare are paid on the
                // audio thread and never delay a bullet: the hold starts the
                // sound before the first shot is resolved.
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

    /** The three-finger gesture toggles a real, in-place pause. */
    fun toggleBattlePause() {
        if (_currentScreen.value != Screen.BATTLE || !foreground) return
        manuallyPaused = !manuallyPaused
        if (manuallyPaused) {
            battleClock.pause()
            audioManager.pauseAllSounds()
            ttsManager.stop()
            speak(t("battlePaused"))
        } else {
            battleClock.resume()
            audioManager.resumeAllSounds()
            speak(t("battleResumed"))
        }
    }

    fun onAppResumed() {
        foreground = true
        when (_currentScreen.value) {
            Screen.WELCOME, Screen.MENU, Screen.SETTINGS, Screen.GUIDE, Screen.PROFILE,
            Screen.SHOP, Screen.ABOUT, Screen.CHANGELOG, Screen.READER -> audioManager.startMusic()
            Screen.JUMP -> startAirplaneAudio()
            Screen.BATTLE -> if (!manuallyPaused && battleClock.paused.value) {
                battleClock.resume()
                audioManager.resumeAllSounds()
            }
            else -> {}
        }
    }

    fun onAppPaused() {
        foreground = false
        if (_currentScreen.value == Screen.BATTLE) {
            // Backgrounding uses the same frozen clock; a manually paused
            // battle remains paused when the app returns to the foreground.
            battleClock.pause()
            audioManager.pauseAllSounds()
        } else {
            stopTimers()
            audioManager.stopAllSounds()
        }
        ttsManager.stop()
    }

    // ---------------------------------------------------------------------
    // Shop
    // ---------------------------------------------------------------------

    /** Issue and persist principal and coins as one profile transaction. */
    fun takeLoan(amount: Int) {
        if (_currentScreen.value != Screen.SHOP) return
        val result = Loans.issue(_stats.value, amount)
        if (!result.success) {
            msg(t(result.reasonKey ?: "loanInvalidAmount"))
            return
        }
        if (!prefs.saveStats(result.stats)) {
            msg(t("profileSaveFailed"))
            return
        }
        _stats.value = result.stats
        playSound("spend")
        msg("${t("loanIssued")} $amount ${t("coins")}. ${t("loanRemaining")}: $amount.")
    }

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

    /** Complete, share-ready profile summary; contains no device/account data. */
    fun formattedStatsForSharing(): String {
        val s = _stats.value
        val inv = _inventory.value
        val player = _settings.value.name.ifBlank { t("statsPlayer") }
        val weapons = inv.ownedWeapons().joinToString(", ") { t(it.nameKey) }.ifBlank { t("noneOwned") }
        val defences = listOf(
            "${t("medkitsPlural")}: ${inv.medikits}",
            "${t("glueWallsPlural")}: ${inv.glueWalls}",
            "${t("coversPlural")}: ${inv.covers}",
            "${t("antiMissilesPlural")}: ${inv.antiMissiles}",
            "${t("carsPlural")}: ${inv.cars} (${inv.carGamesLeft} ${t("fuelUses")})"
        ).joinToString("  |  ")
        val extensions = Extension.entries.filter { inv.extensionOwned(it) }
            .joinToString(", ") { t(it.nameKey) }.ifBlank { t("noneOwned") }
        return buildString {
            appendLine(t("statsShareTitle"))
            appendLine("${t("statsPlayer")}: $player")
            appendLine("${t("statsProgress")} ${s.round}, ${t("statsLevel")} ${s.level} (${s.levelWins}/${Progression.WINS_PER_LEVEL} ${t("statsLevelWins")})")
            appendLine("${t("statsGames")}: ${s.games}  |  ${t("statsWins")}: ${s.wins}  |  ${t("statsKills")}: ${s.kills}")
            appendLine("${t("statsCompletedRounds")}: ${s.completedRounds}")
            appendLine("${t("statsCoins")}: ${s.coins}  |  ${t("statsLoanBalance")}: ${s.loanBalance}")
            appendLine("${t("statsWeapons")}: $weapons")
            appendLine("${t("statsDefences")}: $defences")
            append("${t("statsExtensions")}: $extensions")
        }
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
        matchDeadlineMs = battleClock.nowMs() + limit
        _gameState.update {
            it.copy(battleLimitMs = limit, battleRemainingMs = limit)
        }
        matchLimitJob = viewModelScope.launch {
            var warnedTwoMinutes = false
            var warnedOneMinute = false
            var warnedThirtySeconds = false
            while (true) {
                battleClock.awaitDelay(1_000L)
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val current = _gameState.value
                if (current.playerHp <= 0 || current.robertHp <= 0 || endingJob != null) break
                val remaining = (matchDeadlineMs - battleClock.nowMs()).coerceAtLeast(0L)
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
        lastHitSpeechAtMs = 0L
        lastEnemyRangeNoticeAtMs = 0L
        enemyPreviouslyOutsideFireRange = true
        robertNextShotAtMs = 0L
        playerShotTimes.clear()
        stopBurstShotsLeft = 0
        lastStopBurstAtMs = 0L
        stopBurstFinished = false
        lastFireAt = 0L
        nextPlayerShotAtMs = 0L
        lastIncomingHitAtMs = -1L
        recentIncomingHits = 0
        lastIncomingDamage = 0

        val round = _stats.value.round.coerceIn(1, Progression.ROUNDS_PER_MATCH)
        val level = _stats.value.level.coerceIn(1, Progression.LEVELS_PER_ROUND)
        val profile = RobertRules.profile(
            round = round,
            level = level,
            playerOwnsPistol = _inventory.value.ownsWeapon(Weapon.PISTOL),
            playerGlueWalls = _inventory.value.glueWalls
        )
        val hp = Progression.hpFor(round, level)
        // Weapons handed out for free in this round are issued before the
        // battle rotation is built (the AWM from round two, the grenade from
        // round three).
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
            // 25 m square, exactly one full side apart.
            playerX = Arena.PLAYER_START_X,
            playerY = Arena.PLAYER_START_Y,
            robertX = Arena.ROBERT_START_X,
            robertY = Arena.ROBERT_START_Y,
            runSpeed = RunSpeed.fromId(_inventory.value.lastRunSpeedId).id,
            // The player starts aiming into the arena, towards Robert.
            playerFacing = Facing.FORWARD.id,
            coverActive = false,
            coverHits = 0,
            glueWallActive = false,
            glueWallProgress = 0,
            robertCoverActive = false,
            robertCoverHits = 0,
            robertGlueWallActive = false,
            robertGlueWallProgress = 0,
            // The enemy always carries his Gloo Wall Breaker charges: round
            // one allows one use, round two and later two uses. He only ever
            // spends one when the player raises a gloo wall.
            robertGlueBreakers = RobertRules.glueWallBreakerLimitForRound(round),
            robertBreakerArmed = false,
            robertBreakerHits = 0,
            robertWeapons = profile.weapons
                .filter { RobertRules.isEnemyAllowed(it, round) }
                .map { it.id },
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
            // Robert's bulletproof car is granted from round five.
            robertCarAvailable = RobertRules.robotCarGranted(round),
            robertCarHp = if (RobertRules.robotCarGranted(round)) CarRules.MAX_HP else 0,
            log = t("ready"),
            matchWon = false,
            // A battle always starts with a clear sky, no guard left over from
            // the previous fight and no opponent lock.
            opponentLockedUntilMs = 0L,
            antiMissileDeployed = false,
            playerInterceptorsLeft = 0,
            antiBombDeployed = false,
            shockDamageDealt = 0,
            weaponDriftM = 0f
        )
        lockJob?.cancel()
        lockJob = null
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
     * Hands out the weapons the round provides for free: the AWM from round
     * two, and the grenade from round three. Weapons that are only
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
        val (lateral, depth) = enemyAudioOffsets()
        audioManager.updateEnemyWalk3dAudio(
            distance = distance,
            lateralOffset = lateral,
            speedMps = speedMps,
            fleeing = fleeing,
            depthOffset = depth
        )
    }

    /** Stores the live touch pressure of the running hold. */
    fun updateMovePressure(pressure: Float) {
        if (battlePaused.value) return
        movePressure = pressure
    }

    /** Robert's fast running speed for this round, in metres per second. */
    private fun enemyFastSpeedMps(): Float =
        RobertRules.enemyFastSpeedMps(_gameState.value.round)

    /**
     * Robert's real 3D offsets from the player, in metres: the sideways
     * (lateral) offset first, then the depth offset along the forward axis.
     * Everything he does is mixed from these two numbers, so the stereo image
     * of the square arena is always the truth.
     */
    private fun enemyAudioOffsets(): Pair<Float, Float> {
        val state = _gameState.value
        return Pair(state.robertX - state.playerX, state.robertY - state.playerY)
    }

    /**
     * Plays any enemy sound at his real position inside the square arena:
     * on the right ear when he really stands on the right, on the left ear
     * when he stands on the left, quieter as he moves away and louder as he
     * closes in.
     */
    private fun playEnemySound(key: String, volume: Float = 1.0f, rate: Float = 1.0f) {
        val (lateral, depth) = enemyAudioOffsets()
        playSound(
            key,
            volume = volume,
            posX = lateral,
            posY = 0f,
            posZ = -depth,
            rate = rate
        )
    }

    /** Plays one of Robert's weapon sounds at his real position. */
    private fun playEnemyWeaponSound(key: String, volume: Float = 1.0f, rate: Float = 1.0f) {
        val (lateral, depth) = enemyAudioOffsets()
        audioManager.playPositionalWeaponSound(
            key,
            lateralOffset = lateral,
            depthOffset = depth,
            volume = volume,
            rate = rate
        )
    }

    /**
     * Opens Robert's continuous-fire channel for an automatic weapon. Repeated
     * bullet ticks refresh the same stream, never layer another recording.
     * Switching to a single shot, retreating, launching or ending the exchange
     * calls [stopEnemyFireSound], which cuts his stream immediately.
     */
    private fun playEnemyFireSound(chosen: Weapon) {
        val key = chosen.fireSoundKey
        if (!chosen.autoFire) {
            stopEnemyFireSound()
            playEnemyWeaponSound(key, volume = 0.9f)
            return
        }
        val held = robertFireWeaponKey
        if (held != null && held != key) {
            audioManager.stopEnemyWeaponLoop(held)
        }
        val (lateral, depth) = enemyAudioOffsets()
        audioManager.startEnemyWeaponLoop(
            key,
            volume = 0.9f,
            lateralOffset = lateral,
            depthOffset = depth
        )
        // Same per-bullet watchdog on his side: a hit sound of the player can
        // never cut a burst the enemy is still firing.
        audioManager.keepWeaponLoopAlive(
            key,
            owner = AudioManager.LOOP_OWNER_ENEMY,
            volume = 0.9f,
            posX = lateral,
            posZ = -depth
        )
        robertFireWeaponKey = key
    }

    /** Cuts any automatic burst Robert was firing. His single shots are never looped. */
    private fun stopEnemyFireSound() {
        robertFireWeaponKey?.let { audioManager.stopWeaponLoop(it, AudioManager.LOOP_OWNER_ENEMY) }
        robertFireWeaponKey = null
    }

    /** True while Robert still has a Gloo Wall Breaker charge for a wall. */
    private fun robertHasBreaker(state: GameState = _gameState.value): Boolean =
        RobertRules.hasUsableGlueBreaker(state.glueWallActive, state.robertGlueBreakers)

    /**
     * The three-finger running speed gesture: swiping right steps one level
     * faster, swiping left one level slower, cycling slow / normal / fast.
     * The fast level matches Robert's own fast (fleeing) speed for the round
     * exactly, and the running sound gets louder or quieter with the level,
     * just like his footsteps do.
     */
    fun cycleRunSpeed(direction: Int) {
        if (battlePaused.value) return
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val current = _gameState.value.runSpeedLevel
        val next = if (direction >= 0) {
            if (current == RunSpeed.FAST) RunSpeed.SLOW else current.faster()
        } else {
            if (current == RunSpeed.SLOW) RunSpeed.FAST else current.slower()
        }
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
            )
            audioManager.updatePlayerWalkAudio(speedMps, next.volumeGain())
        }
        msg("${t("runSpeed")}: ${t(next.nameKey)}.")
    }

    /**
     * Continuous player movement inside the 25 m square battle zone, in all four
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
     * nobody ever gets beyond the 25 m perimeter.
     */
    fun startPlayerMove(direction: MoveDirection) {
        if (battlePaused.value) return
        if (_currentScreen.value != Screen.BATTLE) return
        // Inside the bulletproof car movement is automatic: the car drives
        // itself toward the enemy and manual run holds are ignored.
        if (CarRules.isInside(_gameState.value.carStage)) return
        playerMoveJob?.cancel()
        moveDirection = direction
        playerMoveJob = viewModelScope.launch {
            while (true) {
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val state = _gameState.value
                if (state.playerHp <= 0 || state.robertHp <= 0) break

                val speed = state.runSpeedLevel
                val selectedFactor = speed.factor(RunPressure.speedFactor(movePressure), enemyFastSpeedMps())
                // Retreat is still controlled by the player: holding the back
                // zone becomes a sprint under recent, damaging heavy fire.
                val threatened = lastIncomingHitAtMs >= 0 && RetreatRules.underHeavyFire(
                    recentIncomingHits, lastIncomingDamage, battleClock.nowMs() - lastIncomingHitAtMs
                )
                val factor = if (direction == MoveDirection.BACKWARD && threatened) {
                    max(selectedFactor, RunSpeed.fastFactor(enemyFastSpeedMps()))
                } else selectedFactor
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
                    // A boundary of the 25 m square zone: no movement, wall sound.
                    audioManager.stopSound("playerWalk")
                    announceWall()
                    playerAtWall = true
                    battleClock.awaitDelay(tickMs)
                    continue
                }
                playerAtWall = false

                // Our own footsteps are centred at the speech level, follow the
                // running speed and get louder or quieter with the speed level.
                audioManager.updatePlayerWalkAudio(speedMps, speed.volumeGain())
                val (lateral, depth) = enemyAudioOffsets()
                audioManager.refreshEnemyWalkPosition(_gameState.value.distance, lateral, depth)
                if (stepJob == null || stepJob?.isActive != true) {
                    planRobertStep(80L)
                }
                battleClock.awaitDelay(tickMs)
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
        val now = battleClock.nowMs()
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
            battleClock.awaitDelay(delayMs)
            robertStep()
        }
    }

    /**
     * Stops Robert's movement loop completely. Used by the opponent lock: a
     * frozen opponent takes no step at all until the minute has run out.
     */
    private fun cancelRobertStep() {
        stepJob?.cancel()
        stepJob = null
    }

    /** Robert's answer to an incoming player missile: a sprint inside the zone. */
    private fun startRobertMissileFlee() {
        robertSprintTicks = RobertRules.SPRINT_TICKS
        if (_gameState.value.robertState != "missileFlee") {
            _gameState.update { it.copy(robertState = "missileFlee", robertLateral = 0f) }
        }
        planRobertStep(MissileRules.FLEE_TICK_MS)
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
            robertHasBreaker = robertHasBreaker(state),
            playerWeapon = state.activeWeapon
        )
    }

    /** The arena distance Robert is walking to for his current band. */
    private fun robertTargetDistance(state: GameState): Float =
        currentFireRange(state).targetDistance

    /**
     * Picks the band Robert fights from next: at random either the 40%-70%
     * band or the 90%-100% band. He never fires from an empty band to fake an
     * attack, so every shot he fires is a real one.
     */
    private fun rollRobertFireRange() {
        val range = RobertRules.preferredRange(Random)
        _gameState.update { it.copy(robertFireRange = range.id) }
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
        // Running away ends his burst: no enemy gun may keep firing while he
        // is already sprinting out of reach.
        stopEnemyFireSound()
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
        rollRobertFireRange()
        planRobertStep(FLEE_TICK_MS)
    }

    /** Result of one of Robert's movement steps. */
    private data class StepOutcome(val state: GameState, val blocked: Boolean)

    /**
     * Robert's movement step. He really walks inside the 25 m square zone with all
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
        // "Lock the Opponent": a frozen Robert takes no step at all, and the
        // loop simply ends. The lock lifts by itself a minute later, and the
        // next plan of the engine wakes him again.
        if (state.opponentLocked(battleClock.nowMs())) return

        // A damaged weapon in the player's hands stops every retreat: Robert
        // turns around and walks straight back to point blank. Nothing stops a
        // missile or a bomb escape: those runs continue until they resolve.
        val pressure = isPlayerWeaponLocked(state)
        val running = state.robertState
        if (pressure && running == "flank") {
            _gameState.update {
                it.copy(robertState = "advance", robertTurnBackTicks = 0)
            }
            robertSprintTicks = 0
        }

        // While the player's glue wall is up and Robert has no Gloo Wall
        // Breaker charge to break it, he refuses to approach and keeps
        // running away instead.
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
                    rollRobertFireRange()
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
                var blocked = false
                _gameState.update { current ->
                    val outcome = runAwayStep(current, metres)
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
                    rollRobertFireRange()
                    planRobertStep(FLANK_DELAY_MS + Random.nextLong(400L))
                }
            }
            "flank" -> {
                val current = _gameState.value
                val target = robertTargetDistance(current)
                val speed = (0.60f + round * 0.06f) * speedMultiplier
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
                val speed = (0.35f + prox * 0.90f + round * 0.04f) *
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
     * Keeps the "avoid" state in sync with the gloo wall: while the player's
     * wall is up and Robert has no Gloo Wall Breaker charge left to destroy
     * it, he runs away and stays away. As soon as the wall drops (or while he
     * still owns a breaker charge) he approaches again and uses it.
     */
    private fun ensureGlueWallAvoidance() {
        val state = _gameState.value
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        if (state.missileInFlight || state.bombInFlight) return
        val hasBreaker = robertHasBreaker(state)
        val shouldAvoid = RobertRules.shouldAvoidGlueWall(state.glueWallActive, hasBreaker) && !state.robertHasActiveDefence
        val avoiding = state.robertState == "avoid"
        if (shouldAvoid && !avoiding && state.robertState != "turnBack") {
            stopEnemyFireSound()
            _gameState.update {
                it.copy(robertState = "avoid", robertTurnBackTicks = 0)
            }
            rollRobertFireRange()
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
            battleClock.awaitDelay(initialDelayMs)
            while (true) {
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val state = _gameState.value
                if (state.playerHp <= 0 || state.robertHp <= 0) break
                robertAttack()
                if (stopBurstFinished) {
                    stopBurstFinished = false
                    if (_gameState.value.robertHp > 0) beginRobertSprint()
                }
                battleClock.awaitDelay(robertFireInterval())
            }
            // The exchange is over - he died, the fight ended or the screen
            // changed. Whatever beat his last shot was fired on, his gun is
            // silent from here on.
            stopEnemyFireSound()
        }
    }

    private fun isRobertWeaponDamaged(state: GameState, weapon: Weapon): Boolean {
        val index = state.robertWeapons.indexOf(weapon.id)
        if (index < 0) return false
        return state.robertDamaged.getOrElse(index) { false }
    }

    /** A held action may delay a shot, but cannot move its deadline earlier. */
    private fun robertFireInterval(): Long {
        if (_gameState.value.timerSelectActive) return TIMER_SELECTION_HOLD_MS
        val remaining = robertNextShotAtMs - battleClock.nowMs()
        return if (remaining > 0) remaining else FLEE_TICK_MS
    }

    /**
     * Schedules Robert's next shot. The interval comes from
     * [EnemyFireStrategy]: it is the beat that answers the player's own last
     * firing gaps, or the 0.5 second beat of his closing three-shot burst.
     */
    private fun markRobertShot(intervalMs: Long) {
        robertNextShotAtMs = battleClock.nowMs() + intervalMs.coerceAtLeast(120L)
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

    private fun playerFiringNow(): Boolean =
        lastPlayerShotAtMs > 0L &&
            battleClock.nowMs() - lastPlayerShotAtMs <= RobertRules.MIRROR_EXCHANGE_WINDOW_MS

    private fun shouldRobertRetreat(state: GameState, damageFromLastHit: Int = 0): Boolean = RobertRules.shouldRetreatFromFight(
        playerHits = state.fightPlayerHits,
        enemyHits = state.fightRobertHits,
        consecutiveHits = state.robertConsecutiveHits,
        playerBurstShots = state.playerBurstShots,
        enemyHealthDropping = robertHealthDropping(state),
        enemyWall = state.robertHasActiveDefence,
        playerWall = state.glueWallActive,
        playerHasBreaker = _inventory.value.ownsWeapon(Weapon.GLUE_BREAKER),
        proximityPercent = Combat.proximityOf(state.distance),
        damageFromLastHit = damageFromLastHit,
        playerHealthDropping = playerHealthDropping(state),
        // A held submachine gun or machine gun is what can pin him: only that
        // kind of fire bypasses the exchange counters and the defence gate.
        playerFiringAutomatic = state.activeWeapon.autoFire
    )

    /**
     * Registers one player shot in the burst counter that Robert reads. Shots
     * further apart than [RobertRules.PLAYER_BURST_WINDOW_MS] start a new
     * burst, and the health both fighters had at the start of the burst is
     * remembered so Robert can tell whose health is really dropping.
     * If the player starts firing back continuously, Robert retreats immediately.
     */
    private fun registerPlayerBurstShot() {
        val now = battleClock.nowMs()
        val fresh = lastPlayerShotAtMs == 0L || now - lastPlayerShotAtMs > RobertRules.PLAYER_BURST_WINDOW_MS
        lastPlayerShotAtMs = now
        // Keep the newest timestamps: the enemy reads the gaps between them.
        playerShotTimes.addLast(now)
        while (playerShotTimes.size > EnemyFireStrategy.SAMPLE_WINDOW + 1) {
            playerShotTimes.removeFirst()
        }
        // Firing again ends any closing burst: the exchange is live again.
        stopBurstShotsLeft = 0
        _gameState.update { state ->
            if (fresh) playerBurstStartedAtMs = now
            state.copy(
                playerBurstShots = if (fresh) 1 else state.playerBurstShots + 1,
                playerBurstRobertHp = if (fresh) state.robertHp else state.playerBurstRobertHp,
                playerBurstPlayerHp = if (fresh) state.playerHp else state.playerBurstPlayerHp
            )
        }
    }

    /** Drops a burst that has gone quiet, so Robert stops answering it. */
    private fun expirePlayerBurstIfStale() {
        val state = _gameState.value
        if (state.playerBurstShots <= 0) return
        if (battleClock.nowMs() - lastPlayerShotAtMs <= RobertRules.PLAYER_BURST_WINDOW_MS) return
        _gameState.update {
            it.copy(
                playerBurstShots = 0,
                playerBurstRobertHp = it.robertHp,
                playerBurstPlayerHp = it.playerHp
            )
        }
    }

    /** Normal attacks and breaker shots share one cadence; movement takes priority. */
    private fun robertAttack() {
        if (!foreground || _currentScreen.value != Screen.BATTLE) {
            stopEnemyFireSound()
            return
        }
        var state = _gameState.value
        if (state.opponentLocked(battleClock.nowMs())) {
            // A locked opponent cannot fire, throw, launch or defend.
            stopEnemyFireSound()
            return
        }
        if (state.robertHp <= 0 || state.playerHp <= 0 || state.timerSelectActive || state.missileInFlight ||
            (state.bombInFlight && state.bombFromPlayer)
        ) {
            // A held sound belongs only to a real firing exchange. Selection,
            // projectile flight, death and screen changes all release it now.
            stopEnemyFireSound()
            return
        }
        expirePlayerBurstIfStale()
        state = _gameState.value
        ensureGlueWallAvoidance()
        state = _gameState.value
        // From round five Robert can enter his bulletproof car when injured.
        if (state.robertCarAvailable &&
            RobertRules.shouldEnterCar(state.round, state.robertHp, state.robertInCar)
        ) {
            robertEnterCar()
            state = _gameState.value
        }
        if (state.robertState in setOf("retreat", "avoid", "missileFlee", "bombFlee", "turnBack")) {
            stopEnemyFireSound()
            return
        }

        // The player's rhythm decides the enemy's rhythm. Once a sustained
        // burst has gone quiet, he answers with three quick shots and then
        // breaks contact instead of standing still or firing forever.
        val nowMs = battleClock.nowMs()
        if (EnemyFireStrategy.shouldStartStopBurst(
                hasPlayerRhythm = EnemyFireStrategy.playerInterval(playerShotTimes.toList()) != null,
                lastShotAtMs = lastPlayerShotAtMs,
                nowMs = nowMs,
                shotsLeft = stopBurstShotsLeft,
                lastStopBurstAtMs = lastStopBurstAtMs
            )
        ) {
            stopBurstShotsLeft = EnemyFireStrategy.STOP_BURST_SHOTS
            playerShotTimes.clear()
        }
        if (nowMs < robertNextShotAtMs) return
        // A car the user sits in is attacked only with explosives: missiles
        // and bombs are the only way through it. Robert checks his own stocks
        // and, once his health drops below the 400 line - or the car is weak
        // enough to finish - decides automatically to spend them on the car.
        val hasExplosives = state.robertMissilePowers > 0 || state.robertBombs > 0
        if (RobertRules.shouldBlastPlayerCar(
                playerInCar = CarRules.shieldsUser(state.carStage),
                robotHp = state.robertHp,
                playerCarHp = state.carHp,
                hasExplosives = hasExplosives
            )
        ) {
            if (state.robertMissilePowers > 0 && !state.missileInFlight && !state.bombInFlight) {
                launchEnemyMissile()
                return
            }
            if (state.robertBombs > 0 && !state.missileInFlight && !state.bombInFlight) {
                launchEnemyBomb()
                return
            }
        }
        // During a shock Robert defends; normal attacks resume on release.
        // A shock that cannot reach him (a missile or a bomb in the air, or a
        // player out of the 40 to 100 percent band) does not hold him back.
        if (state.shockHolding || state.robertShocking) {
            stopEnemyFireSound()
            return
        }
        if (state.glueWallActive && fireRobertBreakerAtWall()) {
            markRobertShot(RobertRules.CONTINUOUS_FIRE_MS)
            return
        }
        val mirrorDuel = playerFiringNow()

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

        // From round three he also carries finite bomb stocks: grenades in
        // round three, three of each kind in round four and level-specific
        // totals in round five. Like his missile they are desperation weapons.
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

        // The absolute enemy gate is applied again here: a weapon the round
        // forbids (the machine gun, the AK-47 and the submachine gun are never
        // his) can never enter the firing pool, whatever built the profile.
        val allowed = RobertRules.allowedWeapons(
            robertProfile,
            state.glueWallActive,
            state.robertHp
        ).filter { RobertRules.isEnemyAllowed(it, state.round) }
            .ifEmpty { robertProfile.weapons.filter { RobertRules.isEnemyAllowed(it, state.round) } }
        var pool = allowed.filter { !isRobertWeaponDamaged(state, it) }
        if (pool.isEmpty()) {
            // Every weapon is broken: Robert repairs his arsenal for free.
            _gameState.update { it.copy(robertDamaged = it.robertDamaged.map { false }) }
            state = _gameState.value
            pool = allowed
        }
        // He never fires from a band where the shot cannot land: the far half
        // of the square is walking ground, not fighting ground. Closing the
        // distance is the answer, and his next trigger pull is a real one.
        if (!RobertRules.canLandShotAt(state.distance, state.robertWeapon)) {
            enemyPreviouslyOutsideFireRange = true
            stopEnemyFireSound()
            if (state.robertState != "advance") {
                _gameState.update { it.copy(robertState = "advance") }
                planRobertStep(ROBERT_STEP_INTERVAL_MS)
            }
            // He says it once per approach, so the user knows the silence is
            // movement and not a missing gun.
            if (nowMs - lastEnemyRangeNoticeAtMs > ENEMY_RANGE_NOTICE_MS) {
                lastEnemyRangeNoticeAtMs = nowMs
                setLog(t("robertClosingIn"))
            }
            return
        }
        if (enemyPreviouslyOutsideFireRange) {
            enemyPreviouslyOutsideFireRange = false
            speak(t("enemyEnteredFireRange"))
        }
        val proximityNow = Combat.proximityOf(state.distance)
        // Panic check: falling health really spends one of his defences at
        // once instead of only arming a flag nobody reads.
        robertPanicDefence(state)?.let { defence ->
            if (deployRobertDefence(defence).isNotBlank()) {
                state = _gameState.value
            }
        }
        // The player raised a gloo wall and Robert has no Gloo Wall Breaker
        // charge left: he does not trade shots, he runs away and stays away
        // until the wall drops.
        if (state.glueWallActive && !state.robertHasActiveDefence) {
            stopEnemyFireSound()
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
            hasSniper = false
        )
        // Round 5 strategic logic: equal power — prioritize positioning over brute force
        val isStrategyRound = state.round == 5
        val chosen = when {
            smartChoice != null && (Random.nextFloat() < 0.75f || isStrategyRound) -> smartChoice
            else -> pool[Random.nextInt(pool.size)]
        }
        // The weapon he actually pulls the trigger on decides what happens: a
        // gun fired from a band where it cannot connect is still a shot, and
        // it still costs a round of its magazine. Without this the user hears
        // (and reads) an enemy who shoots from useless range for a whole match.
        val storedIndex = state.robertWeapons.indexOf(state.robertWeapon?.id)
        val chosenIndex = if (state.robertWeapon != null && storedIndex >= 0) {
            storedIndex
        } else {
            state.robertWeapons.indexOf(chosen.id).coerceAtLeast(0)
        }
        // `robertWeapons` holds weapon ids; the trigger pull fires the gun he is
        // really holding, which is the one the index points at.
        val firingWeapon: Weapon = Weapon.fromId(state.robertWeapons.getOrNull(chosenIndex)) ?: chosen

        if (chosenIndex != state.robertWeaponIndex) {
            // Changing weapon is silent for Robert: a stray gun sound on the
            // enemy side has no gameplay meaning and used to confuse players.
            _gameState.update { it.copy(robertWeaponIndex = chosenIndex) }
            state = _gameState.value
        }

        // Six rounds per magazine, then a free reload. From round five every
        // gun is unlimited, so he never reloads and never runs dry.
        val unlimitedAmmo = RobertRules.infiniteAmmo(state.round)
        if (!unlimitedAmmo && firingWeapon.needsReload && (state.robertAmmo.getOrNull(chosenIndex) ?: 0) <= 0) {
            _gameState.update { s ->
                s.copy(
                    robertAmmo = s.robertAmmo.toMutableList().also {
                        it[chosenIndex] = firingWeapon.magazine
                    }
                )
            }
            // Robert reloads silently: only the player ever hears the
            // mechanical gun-switch/reload recording.
            setLog(t("robertReloaded"))
            state = _gameState.value
        }

        // A melee attack must not play its swing/switch sound before the
        // outcome is known: a miss uses only *-miss and a hit uses *-hit.
        // Firearm sounds are exclusive: his shot cuts whatever weapon sound
        // the player still has in the air, so the two never mix.
        // Single-shot answers use the tactical counter rhythm. Automatic guns
        // always use their own power map: 0.5 s / 1 s / 1.5 s for both fighters.
        val closingBurst = EnemyFireStrategy.inStopBurst(stopBurstShotsLeft)
        val answerInterval = if (closingBurst) {
            EnemyFireStrategy.stopBurstInterval(stopBurstShotsLeft)
        } else {
            EnemyFireStrategy.counterInterval(
                EnemyFireStrategy.playerInterval(playerShotTimes.toList())
            )
        }
        val fireInterval = AutomaticFirePowerMap.intervalFor(firingWeapon) ?: answerInterval
        markRobertShot(fireInterval)
        if (closingBurst) {
            stopBurstShotsLeft -= 1
            if (stopBurstShotsLeft <= 0) {
                stopBurstShotsLeft = 0
                lastStopBurstAtMs = battleClock.nowMs()
                lastPlayerShotAtMs = 0L
                stopBurstFinished = true
            }
        }
        if (!firingWeapon.melee) {
            playEnemyFireSound(firingWeapon)
        } else {
            // A melee attack owns no firing channel either: cut any burst that
            // was still sounding before his swing.
            stopEnemyFireSound()
        }
        _gameState.update { s ->
            val nextAmmo = if (firingWeapon.needsReload && !unlimitedAmmo) {
                s.robertAmmo.toMutableList().also {
                    it[chosenIndex] = ((it.getOrNull(chosenIndex) ?: firingWeapon.magazine) - 1)
                        .coerceAtLeast(0)
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

        val proxPercent = Combat.proximityOf(state.distance)
        val zone = Combat.zoneFor(proxPercent)

        // The AWM is the one long-range exception: inside its precision band
        // (20%-40% proximity) its shot still connects. Every other weapon - and
        // the AWM elsewhere - needs a real hit zone. The check reads the same
        // long-range rule the damage table and the player's own shot use, so
        // the enemy can never connect through a band the player cannot.
        val longRange = DamageTable.isAwmLongRangeBand(firingWeapon, proxPercent)
        if (zone == HitZone.MISS && !longRange) {
            // A shot that cannot land is still a shot: the user hears the
            // miss - the weapon's own miss sound, or the shot itself for a
            // gun fired from a band where it cannot connect - and hears him
            // say it. A silent miss read as "Robert is not firing at all".
            if (firingWeapon.melee) {
                playEnemyWeaponSound(firingWeapon.missSoundKey, volume = 0.55f)
            } else if (state.distance <= RobertRules.MISS_IS_AUDIBLE_DISTANCE_M) {
                playEnemyWeaponSound(firingWeapon.fireSoundKey, volume = 0.45f, rate = 0.96f)
            }
            _gameState.update { it.copy(consecutiveHeavyHits = 0) }
            setLog("${t("robertMissed")} ${t(firingWeapon.nameKey)}.")
            return
        }

        // The player's gloo wall stops everything: only the dedicated Gloo
        // Wall Breaker hurts it (see fireRobertBreakerAtWall), and that is
        // resolved before the enemy picks a weapon at all.
        if (state.glueWallActive) return

        // Inside the car no bullet, blade or swing reaches the user: it bounces off.
        if (CarRules.shieldsUser(state.carStage)) {
            playSound("coverHit", volume = 0.6f)
            setLog(t("carBlockedBullet"))
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
        var dealt = if (firingWeapon.autoFire) {
            AutomaticFirePowerMap.damageAt(firingWeapon, proxPercent, state.playerMaxHp)
        } else {
            RobertRules.weaponDamage(firingWeapon, proxPercent, state.playerMaxHp)
        }
        // While the quick menu is genuinely being navigated every hit is cut
        // down to a fixed percentage of maximum health. The protection expires
        // on its own, so browsing the menu can never become invulnerability.
        if (isMenuProtectionActive()) {
            dealt = min(dealt, Combat.menuProtectedDamage(state.playerMaxHp))
        }

        val newPlayerHp = max(0, state.playerHp - dealt)
        recordIncomingDamage(dealt)

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
        playSound(firingWeapon.hitSoundKey, volume = 1.0f)
        playSound("playerHit", volume = 1.0f)

        val hitHead = if (zone == HitZone.HEAVY) t("youWereHit") else t("youWereGrazed")
        var text = "$hitHead ${t(firingWeapon.nameKey)}. ${t("hitFor")} $dealt. " +
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

    private fun recordIncomingDamage(damage: Int) {
        if (damage <= 0) return
        val now = battleClock.nowMs()
        recentIncomingHits = if (lastIncomingHitAtMs >= 0 &&
            now - lastIncomingHitAtMs <= RetreatRules.PRESSURE_WINDOW_MS) recentIncomingHits + 1 else 1
        lastIncomingHitAtMs = now
        lastIncomingDamage = damage
    }

    /**
     * Speaks the enemy hit report, keeping a fast exchange readable: the
     * sentence is always shown, but it is only read out loud at most once per
     * [FAST_EXCHANGE_SPEECH_MS] while the shots arrive faster than that.
     */
    private fun announceEnemyHit(text: String, fastExchange: Boolean) {
        val now = battleClock.nowMs()
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
     * The moment the player raises a gloo wall.
     *
     * He may deploy his own wall only below 150 HP. A player who owns a
     * breaker and has a wall up makes a walled-in Robert avoid the fight.
     * Otherwise Robert arms his own breaker when he has a charge.
     */
    private fun onPlayerGlueWallRaised() {
        val state = _gameState.value
        if (RobertRules.shouldUseDefence(state.robertHp, state.robertDefenceArmed) &&
            state.robertGlueWalls > 0 && !state.robertHasActiveDefence) {
            deployRobertDefence(DefenceItem.GLUE_WALL)
        }
        val afterWall = _gameState.value
        if (afterWall.robertGlueBreakers > 0) {
            _gameState.update {
                it.copy(
                    robertBreakerArmed = true,
                    robertBreakerHits = 0,
                    robertState = if (it.robertState == "avoid") "advance" else it.robertState
                )
            }
            ensureRobertPositioned()
        }
    }

    /**
     * Fires the enemy's Gloo Wall Breaker at the player's wall.
     *
     * Every shot that lands from the 90%-100% band cracks the wall, and the
     * third consecutive one destroys it. The charge is spent only when a wall
     * really comes down, and the number of charges is fixed per round: one in
     * round one, two from round two. Returns true when the wall owned the
     * enemy's action this tick, so no ordinary shot is fired.
     */
    private fun fireRobertBreakerAtWall(): Boolean {
        val state = _gameState.value
        if (!state.glueWallActive) return false
        if (state.robertGlueBreakers <= 0) return false
        if (state.robertHp <= 0 || state.playerHp <= 0) return false
        if (state.robertState == "missileFlee" || state.robertState == "bombFlee") return false

        val proxPercent = Combat.proximityOf(state.distance)
        stopEnemyFireSound()
        playEnemyWeaponSound(Weapon.GLUE_BREAKER.fireSoundKey, volume = 0.95f)
        val breaks = Combat.canBreakGlueWall(Weapon.GLUE_BREAKER, proxPercent)
        val progress = if (breaks) state.robertBreakerHits + 1 else 0
        _gameState.update {
            it.copy(robertBreakerArmed = true, robertBreakerHits = progress)
        }
        if (progress >= Combat.GLUE_WALL_BREAK_HITS) {
            _gameState.update {
                it.copy(
                    glueWallActive = false,
                    glueWallProgress = 0,
                    robertBreakerHits = 0,
                    robertBreakerArmed = false,
                    robertGlueBreakers = (it.robertGlueBreakers - 1).coerceAtLeast(0)
                )
            }
            playSound("glueWallCrack", volume = 1.0f)
            msg("${t("glueWallDown")} ${t("enemyBreakerSpent")}")
            ensureRobertPositioned()
        } else if (breaks) {
            playSound("glueHit", volume = 0.9f)
            msg("${t("enemyBreakerCrack")} ${t("glueWallBreaks")} $progress.")
        } else {
            playSound("glueHit", volume = 0.6f)
            msg(t("enemyBreakerTooFar"))
        }
        return true
    }

    /**
     * Deploys one of Robert's defences for real: the stock is spent, the
     * barrier is raised (or the medkit heals) and the matching sound plays.
     * The two interceptors are consumed when a projectile is in the air, so
     * they are never spent here.
     */
    private fun deployRobertDefence(defence: DefenceItem): String {
        if (_gameState.value.opponentLocked(battleClock.nowMs())) return ""

        val current = _gameState.value
        if (!RobertRules.shouldUseDefence(current.robertHp, current.robertDefenceArmed)) return ""
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
                playEnemySound("medkit")
                ensureRobertPositioned()
                " ${t("robertMedkit")} ${t("enemyHealth")} ${_gameState.value.robertHp}."
            }
            DefenceItem.COVER -> {
                if (current.robertCovers <= 0 || current.robertHasActiveDefence) return ""
                _gameState.update {
                    it.copy(
                        robertCoverActive = true,
                        robertCoverHits = 0,
                        robertCovers = (it.robertCovers - 1).coerceAtLeast(0),
                        robertDefenceArmed = false,
                        robertConsecutiveHits = 0
                    )
                }
                playEnemySound("cover")
                " ${t("robertCoverUp")}"
            }
            DefenceItem.GLUE_WALL -> {
                if (current.robertGlueWalls <= 0 || current.robertHasActiveDefence) return ""
                _gameState.update {
                    it.copy(
                        robertGlueWallActive = true,
                        robertGlueWallProgress = 0,
                        robertGlueWalls = (it.robertGlueWalls - 1).coerceAtLeast(0),
                        robertDefenceArmed = false,
                        robertConsecutiveHits = 0
                    )
                }
                playEnemySound("glueSound")
                " ${t("robertGlueWall")}"
            }
            // Robert never locks the player and never takes a lock charge.
            DefenceItem.ANTI_MISSILE, DefenceItem.ANTI_BOMB, DefenceItem.CAR,
            DefenceItem.LOCK -> ""
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
            covers = if (state.robertHasActiveDefence) 0 else state.robertCovers,
            glueWalls = if (state.robertHasActiveDefence) 0 else state.robertGlueWalls,
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

    /** React immediately to damage, without scheduling a shot outside the fire loop. */
    private fun robertAfterHit(damageFromLastHit: Int = 0): String {
        val state = _gameState.value
        if (state.robertHp <= 0) return ""
        if (state.robertHp >= RobertRules.DEFENCE_THRESHOLD_HP || state.robertConsecutiveHits >= 2) {
            _gameState.update { it.copy(robertDefenceArmed = true) }
        }
        // Decide from the damaging hit before panic defence resets hit counters
        // or erects a wall. Continuous automatic fire must never pin him in
        // place.
        val retreat = shouldRobertRetreat(state, damageFromLastHit)
        // A burst that is being landed on him must not be interrupted by his
        // own defences either. Spending a medkit, erecting a cover or raising a
        // glue wall resets his hit streak and hands the trigger a fresh
        // exchange - exactly the state in which he used to stand still and be
        // shot for a whole magazine. So while a held automatic weapon is really
        // taking his health down, he spends nothing: he runs first, and
        // defends himself once the trigger is released.
        if (isPinnedByAutomaticFire(state)) {
            val retreatText = retreatOutOfFire(state)
            ensureGlueWallAvoidance()
            return retreatText
        }
        val defence = robertPanicDefence(_gameState.value)?.let { deployRobertDefence(it) }.orEmpty()
        val after = _gameState.value
        var fledText = ""
        if (retreat) {
            if (after.robertState == "retreat") {
                robertSprintTicks = RobertRules.SPRINT_TICKS
                planRobertStep(FLEE_TICK_MS)
            } else if (after.robertState in setOf("advance", "flank", "turnBack")) {
                beginRobertSprint()
                fledText = " ${t("robertFled")}"
            }
        }
        ensureGlueWallAvoidance()
        return fledText + defence
    }

    /**
     * True while a held automatic weapon is really taking Robert's health down.
     *
     * This is the state in which he must not spend a defence: a medkit, a
     * cover or a glue wall resets his hit streak and would leave him standing
     * in the stream for another magazine. While this is true he runs instead,
     * and only defends himself after the trigger is released.
     */
    private fun isPinnedByAutomaticFire(state: GameState): Boolean =
        state.activeWeapon.autoFire &&
            state.playerBurstShots >= RobertRules.SUSTAINED_FIRE_HITS_TO_FLEE &&
            robertHealthDropping(state) &&
            !playerHealthDropping(state)

    /**
     * Breaks contact at once: sprints out of the stream and returns the line
     * the battle log shows. Used instead of a defence while a held automatic
     * weapon is landing on him.
     */
    private fun retreatOutOfFire(state: GameState): String =
        if (state.robertState == "retreat") {
            // Already running: keep the sprint alive for a full stretch.
            robertSprintTicks = RobertRules.SPRINT_TICKS
            planRobertStep(FLEE_TICK_MS)
            ""
        } else if (state.robertState in setOf("advance", "flank", "turnBack")) {
            beginRobertSprint()
            " ${t("robertFled")}"
        } else {
            ""
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
        val intercept = RobertRules.shouldUseDefence(state.robertHp, true) && MissileRules.willIntercept(
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
        // From round three Robert owns no anti-missile system at all: his only
        // answer to a missile is to sprint away at his fast speed, running and
        // dodging inside the zone until the missile catches him.
        if (fleeing) startRobertMissileFlee()
        // Keep the missile in the native spatial mix for its entire flight;
        // it starts distant and is retuned smoothly toward the listener.
        audioManager.startProjectileFlight("missile", state.distance, loop = true)
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
        // His gun goes silent the moment he launches: a burst of it must never
        // be heard while he is already spending a missile.
        stopEnemyFireSound()
        _gameState.update {
            it.copy(
                robertMissilePowers = (it.robertMissilePowers - 1).coerceAtLeast(0),
                missileInFlight = true,
                missileFromPlayer = false,
                missileInterceptPlanned = false,
                robertShots = it.robertShots + 1
            )
        }
        audioManager.startProjectileFlight("missile", state.distance, loop = true)
        msg(t("robertMissileLaunched"))
        scheduleMissileImpact(MissileRules.flightMs(state.distance))
    }

    /**
     * One interceptor of the player's deployed anti-missile system destroys
     * an incoming missile in the air.
     *
     * The magazine is spent one interceptor at a time: a bought system covers
     * two missiles ([MissileRules.PLAYER_INTERCEPTS_PER_SYSTEM]). Once the
     * last interceptor is gone the system stands down and has to be bought
     * (or pre-armed) again.
     */
    private fun firePlayerInterceptor() {
        releaseWeaponDrift()
        missileJob?.cancel()
        missileJob = null
        missileApproachJob?.cancel()
        missileApproachJob = null
        missileImpactAtMs = 0L
        missileFlightDurationMs = 0L
        missileDeflectionUsed = false
        audioManager.stopSound("missile")
        audioManager.stopSound("enemyWalk")
        val left = (_gameState.value.playerInterceptorsLeft - 1).coerceAtLeast(0)
        _gameState.update {
            it.copy(
                missileInFlight = false,
                missileFromPlayer = false,
                missileInterceptPlanned = false,
                playerInterceptorsLeft = left,
                antiMissileDeployed = left > 0,
                robertState = if (it.robertState == "missileFlee") "advance" else it.robertState,
                robertLateral = 0f
            )
        }
        playSound("explosion")
        msg(t("antiMissileDestroyed"))
        planRobertStep(500L)
    }

    private fun scheduleMissileImpact(delayMs: Long, preserveFlight: Boolean = false) {
        val now = battleClock.nowMs()
        if (!preserveFlight) {
            missileLaunchDistanceM = _gameState.value.distance
            missileFlightDurationMs = delayMs.coerceAtLeast(1L)
            missileDeflectionUsed = false
        } else {
            missileFlightDurationMs += MissileRules.SHOCK_DEFLECTION_DELAY_MS
        }
        missileJob?.cancel()
        missileApproachJob?.cancel()
        missileImpactAtMs = now + delayMs
        missileJob = viewModelScope.launch {
            battleClock.awaitDelay(delayMs)
            onMissileImpact()
        }
        missileApproachJob = viewModelScope.launch {
            while (_currentScreen.value == Screen.BATTLE && _gameState.value.missileInFlight) {
                val remainingMs = (missileImpactAtMs - battleClock.nowMs()).coerceAtLeast(0L)
                val progress = MissileRules.flightProgress(remainingMs, missileFlightDurationMs)
                val remainingM = missileLaunchDistanceM * (1f - progress)
                audioManager.updateProjectileApproach(remainingM, missileLaunchDistanceM)
                battleClock.awaitDelay(80L)
            }
        }
    }

    /** A held shock pushes one incoming missile back, without breaking tracking. */
    private fun deflectIncomingMissileWithShock() {
        val state = _gameState.value
        if (!state.missileInFlight || state.missileFromPlayer || missileDeflectionUsed) return
        val now = battleClock.nowMs()
        val remaining = (missileImpactAtMs - now).coerceAtLeast(0L)
        if (!MissileRules.canShockDeflect(remaining, missileFlightDurationMs)) return
        missileDeflectionUsed = true
        scheduleMissileImpact(
            delayMs = remaining + MissileRules.SHOCK_DEFLECTION_DELAY_MS,
            preserveFlight = true
        )
        msg(t("missileShockPushed"))
    }

    /**
     * Missile impact. The looping flight sound stops at once and the
     * explosion plays, mixed with the hit sound of the destroyed fighter.
     * A direct hit removes every remaining health point and ends the battle
     * at once; only an anti-missile interceptor can destroy the projectile in
     * the air and prevent it.
     */
    private fun onMissileImpact() {
        val state = _gameState.value
        if (_currentScreen.value != Screen.BATTLE || !state.missileInFlight) return
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        // The sky is clear again from here on, so a held weapon walks back in.
        releaseWeaponDrift()
        missileApproachJob?.cancel()
        missileApproachJob = null
        missileImpactAtMs = 0L
        missileFlightDurationMs = 0L
        missileDeflectionUsed = false
        audioManager.stopSound("missile")
        audioManager.stopSound("enemyWalk")

        if (state.missileFromPlayer) {
            // Robert fires an interceptor of his own whenever one is left in
            // his battery - he may answer a missile that is really in the air
            // at any time, not only while he is nearly dead - and he always
            // spends a planned interception. Player activations are handled
            // before his answer, so a pre-armed system always wins the race.
            val intercepted = state.missileInterceptPlanned ||
                MissileRules.enemyIntercepts(
                    round = state.round,
                    antiMissilesLeft = state.robertAntiMissiles,
                    missileInFlight = state.missileInFlight,
                    robertHp = state.robertHp
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
                rollRobertFireRange()
                msg(t("antiMissileIntercept"))
                planRobertStep(500L)
                return
            }
            // Inside his bulletproof car the missile hits the car: one missile
            // destroys it, and Robert is out in the open again. An anti-missile
            // interception already returned above and never reaches this.
            val robertCarHit = robertCarTakesMissile()
            if (robertCarHit == CarHit.DESTROYED) {
                audioManager.playSequence(listOf("explosion", "carCrash"))
                _gameState.update {
                    it.copy(
                        missileInFlight = false,
                        missileFromPlayer = false,
                        missileInterceptPlanned = false,
                        playerHits = it.playerHits + 1,
                        fightPlayerHits = it.fightPlayerHits + 1,
                        playerFirePower = it.playerFirePower + Rewards.powerPointsFor(Weapon.MISSILE)
                    )
                }
                setLog("${t("missileHitEnemy")} ${t("enemyCarDestroyed")}")
                ensureRobertPositioned()
                return
            }
            // A direct missile hit destroys Robert completely: the blast
            // removes every health point he still has.
            val damage = MissileRules.impactDamage(state.robertHp)
            val remaining = (state.robertHp - damage).coerceAtLeast(0)
            audioManager.playMixed(listOf("explosion", "enemyHit"))
            _gameState.update {
                it.copy(
                    robertHp = remaining,
                    missileInFlight = false,
                    missileFromPlayer = false,
                    missileInterceptPlanned = false,
                    robertState = "advance",
                    robertCoverActive = if (remaining == 0) false else it.robertCoverActive,
                    robertGlueWallActive = if (remaining == 0) false else it.robertGlueWallActive,
                    playerHits = it.playerHits + 1,
                    fightPlayerHits = it.fightPlayerHits + 1,
                    playerFirePower = it.playerFirePower + Rewards.powerPointsFor(Weapon.MISSILE)
                )
            }
            val reaction = if (remaining > 0) robertAfterHit(damage) else ""
            msg("${t("missileHitEnemy")} ${t("hitFor")} $damage. ${t("enemyHealth")} $remaining.$reaction")
            if (remaining == 0) endGameAfterExplosion(won = true)
            else ensureRobertPositioned()
        } else {
            // A deployed anti-missile system fires by itself the moment an
            // enemy missile appears: the player does not have to react in time.
            if (MissileRules.autoInterceptPlanned(
                    activated = state.antiMissileDeployed,
                    missilesLeft = state.playerInterceptorsLeft,
                    missileInFlight = state.missileInFlight
                )
            ) {
                firePlayerInterceptor()
                return
            }
            // Inside the car a missile hits the car, not the user. One missile
            // destroys the car. Anti-missile interception already happened above.
            val carHit = carTakesMissile()
            if (carHit != CarHit.NONE) {
                if (carHit == CarHit.DESTROYED) {
                    audioManager.playSequence(listOf("explosion", "carBrake", "carCrash"))
                } else {
                    playSound("explosion")
                }
                _gameState.update {
                    it.copy(
                        missileInFlight = false,
                        missileFromPlayer = false,
                        missileInterceptPlanned = false
                    )
                }
                msg(t(if (carHit == CarHit.DESTROYED) "carDestroyed" else "carHit"))
                ensureRobertPositioned()
                return
            }
            // A direct missile hit destroys the player completely as well.
            // Only an anti-missile interception can stop it.
            val damage = MissileRules.impactDamage(state.playerHp)
            val remaining = (state.playerHp - damage).coerceAtLeast(0)
            recordIncomingDamage(damage)
            audioManager.playMixed(listOf("explosion", "playerHit"))
            _gameState.update {
                it.copy(
                    playerHp = remaining,
                    missileInFlight = false,
                    missileFromPlayer = false,
                    missileInterceptPlanned = false,
                    coverActive = if (remaining == 0) false else it.coverActive,
                    glueWallActive = if (remaining == 0) false else it.glueWallActive,
                    robertHits = it.robertHits + 1,
                    fightRobertHits = it.fightRobertHits + 1,
                    robertDamage = it.robertDamage + damage
                )
            }
            val defence = if (remaining > 0) applyAutomaticDefences() else ""
            msg("${t("missileHitPlayer")} ${t("hitFor")} $damage. ${t("yourHealth")} $remaining.$defence")
            if (remaining == 0) endGameAfterExplosion(won = false)
            else ensureRobertPositioned()
        }
    }

    /**
     * Swiping down with the missile selected buys one missile power for
     * 1000 coins, exactly as announced when no power is owned.
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
        if (battlePaused.value) return
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
        if (battlePaused.value) return
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
     * Requires one Bomb Power (850 coins) for an atom or timer throw; grenades are free.
     * The bomb tracks the enemy, falls onto them, and detonates — a capped hit
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
            val intercept = RobertRules.shouldUseDefence(s.robertHp, true) && BombRules.willIntercept(
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
        bombBlastAtMs = battleClock.nowMs() + flightMs

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
     * Robert throws one of his own bombs from his finite round/level stock.
     * The destination is the player's position and the
     * player must sprint out of the 12 metres or deploy the anti-bomb
     * explosive defence while the bomb sound is still running.
     */
    private fun launchEnemyBomb() {
        val state = _gameState.value
        if (state.robertBombs <= 0 || state.bombInFlight || state.missileInFlight) return
        // Throwing ends his burst, exactly like his missile: the explosion and
        // his gun are never heard as one continuous fire.
        stopEnemyFireSound()
        // Round three: two grenades. Round four: three of every kind. Round
        // five: finite per-level stock, with the bomb types kept separate.
        val kind = BombRules.chooseEnemyBomb(
            round = state.round,
            grenades = state.robertGrenades,
            atomBombs = state.robertAtomBombs,
            timerBombs = state.robertTimerBombs,
            playerHpFraction = if (state.playerMaxHp <= 0) 1f
            else state.playerHp.toFloat() / state.playerMaxHp.toFloat(),
            random = Random
        ) ?: return // Empty stocks never silently become another grenade.
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
                // A pre-armed anti-bomb explosive destroys the bomb the
                // instant it leaves the enemy's hand, with no reaction needed.
                bombInterceptedByPlayer = it.antiBombDeployed,
                robertShots = it.robertShots + 1
            )
        }
        startBombAudio(kind, distance = state.distance, timer = false)
        bombBlastAtMs = battleClock.nowMs() + flightMs
        startBombTracking(fromPlayer = false)
        val bombWarning = "${t("bombRunAway")} ${BombRules.SAFE_DISTANCE.toInt()} ${t("meters")}. ${t("bombTrackingPlayer")}"
        msg("${t("robertBombThrown")} ${t(kind.nameKey)}. $bombWarning")
        speak(bombWarning)
        scheduleBombBlast(flightMs)
    }

    /** Starts the countdown audio of a thrown bomb. */
    private fun startBombAudio(kind: BombKind, distance: Float, timer: Boolean) {
        // Every bomb uses a spatial native loop, including timer bombs. It is
        // updated during flight and stopped on interception or detonation.
        audioManager.startProjectileFlight(kind.flightSoundKey, distance, loop = true)
    }

    private fun scheduleBombBlast(delayMs: Long) {
        bombJob?.cancel()
        bombApproachJob?.cancel()
        bombFlightDurationMs = delayMs.coerceAtLeast(1L)
        val launchDistance = _gameState.value.distance
        bombBlastAtMs = battleClock.nowMs() + delayMs
        bombJob = viewModelScope.launch {
            battleClock.awaitDelay(delayMs)
            onBombBlast()
        }
        bombApproachJob = viewModelScope.launch {
            while (_currentScreen.value == Screen.BATTLE && _gameState.value.bombInFlight) {
                val remainingMs = (bombBlastAtMs - battleClock.nowMs()).coerceAtLeast(0L)
                val progress = MissileRules.flightProgress(remainingMs, bombFlightDurationMs)
                audioManager.updateProjectileApproach(launchDistance * (1f - progress), launchDistance)
                battleClock.awaitDelay(BombRules.TRACKING_UPDATE_MS)
            }
        }
    }

    /** Tracking bomb: follows the target until detonation, guaranteeing hit like a missile. */
    private fun startBombTracking(fromPlayer: Boolean) {
        bombTrackJob?.cancel()
        bombTrackJob = viewModelScope.launch {
            while (true) {
                battleClock.awaitDelay(BombRules.TRACKING_UPDATE_MS)
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
     * outcome: a hit or an anti-bomb interception in the air. A finished battle is only ended after the explosion has run out.
     */
    private fun onBombBlast() {
        // The blast resolves the projectile: the held weapon drifts back in.
        releaseWeaponDrift()
        val state = _gameState.value
        if (_currentScreen.value != Screen.BATTLE || !state.bombInFlight) return
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        bombApproachJob?.cancel()
        bombApproachJob = null
        bombBlastAtMs = 0L
        bombFlightDurationMs = 0L
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
            // Robert spends one of his explosives whenever one is left, and
            // always answers a planned interception.
            state.bombInterceptPlanned ||
                BombRules.willIntercept(state.round, state.robertAntiBombs, bombInFlight = true)
        } else {
            state.bombInterceptedByPlayer ||
                BombRules.autoInterceptPlanned(
                    activated = state.antiBombDeployed,
                    enemyBombInFlight = state.bombInFlight && !state.bombFromPlayer
                )
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
                    // A paid explosive covers exactly one bomb; the free one of
                    // rounds three to five re-arms itself, because the player
                    // never has to buy it.
                    antiBombDeployed = if (!fromPlayer) it.playerAntiBombFree else it.antiBombDeployed,
                    robertState = if (fromPlayer) "advance" else it.robertState,
                    robertTurnBackTicks = 0
                )
            }
            msg(if (fromPlayer) t("antiBombIntercept") else t("antiBombSavedYou"))
            rollRobertFireRange()
            ensureRobertPositioned()
            planRobertStep(400L)
            return
        }

        // A tracking bomb always reaches its target. Its blast takes the
        // bomb's documented share of the health the victim STILL has:
        // grenade 60%, timer bomb 68% and atom bomb 75%. A timer bomb can
        // also catch its own thrower inside the safety radius, with exactly
        // the same share of that fighter's remaining health.
        val targetDamage = BombRules.blastDamage(
            kind,
            if (fromPlayer) state.robertHp else state.playerHp
        )
        val throwerHit = kind == BombKind.TIMER && BombRules.timerBombKillsThrower(throwerDistance)
        val rawPlayerDamage = if (fromPlayer) {
            if (throwerHit) BombRules.blastDamage(kind, state.playerHp) else 0
        } else {
            targetDamage
        }
        // Inside the car the blast goes to the car: the user takes nothing.
        val carHit = if (rawPlayerDamage > 0) {
            carTakesBomb(BombRules.blastDamage(kind, state.carHp))
        } else {
            CarHit.NONE
        }
        val playerDamage = if (carHit != CarHit.NONE) 0 else rawPlayerDamage
        // Robert's bulletproof car takes the blast the same way: the first
        // bomb hit is absorbed, the second consecutive one destroys it. An
        // activated anti-bomb explosive neutralises the bomb first, so the
        // counter never climbs while the defences are up.
        val robertCarHit = if (fromPlayer) {
            robertCarTakesBomb(BombRules.blastDamage(kind, state.robertCarHp))
        } else {
            CarHit.NONE
        }
        val robertDamage = when {
            robertCarHit != CarHit.NONE -> 0
            fromPlayer -> targetDamage
            throwerHit -> BombRules.blastDamage(kind, state.robertHp)
            else -> 0
        }
        val playerRemaining = (state.playerHp - playerDamage).coerceAtLeast(0)
        val robertRemaining = (state.robertHp - robertDamage).coerceAtLeast(0)
        recordIncomingDamage(playerDamage)
        val hitSounds = mutableListOf(kind.blastSoundKey)
        if (robertDamage > 0) hitSounds.add("enemyHit")
        if (playerDamage > 0) hitSounds.add("playerHit")
        if (carHit == CarHit.DESTROYED) {
            audioManager.playSequence(listOf(kind.blastSoundKey, "carBrake", "carCrash"))
        } else if (robertCarHit == CarHit.DESTROYED) {
            audioManager.playSequence(listOf(kind.blastSoundKey, "carCrash"))
        } else {
            audioManager.playMixed(hitSounds)
        }

        val landedOnRobert = fromPlayer && (robertDamage > 0 || robertCarHit != CarHit.NONE)
        _gameState.update {
            it.copy(
                robertHp = robertRemaining,
                playerHp = playerRemaining,
                playerHits = if (landedOnRobert) it.playerHits + 1 else it.playerHits,
                fightPlayerHits = if (landedOnRobert) it.fightPlayerHits + 1 else it.fightPlayerHits,
                playerFirePower = if (landedOnRobert) {
                    it.playerFirePower + Rewards.powerPointsFor(kind.weapon)
                } else {
                    it.playerFirePower
                },
                robertHits = if (!fromPlayer && playerDamage > 0) it.robertHits + 1 else it.robertHits,
                fightRobertHits = if (!fromPlayer && playerDamage > 0) it.fightRobertHits + 1 else it.fightRobertHits,
                robertDamage = it.robertDamage + if (!fromPlayer) playerDamage else 0,
                bombInFlight = false,
                bombFromPlayer = false,
                bombKind = "",
                bombInterceptPlanned = false,
                bombInterceptedByPlayer = false,
                coverActive = if (playerRemaining == 0) false else it.coverActive,
                glueWallActive = if (playerRemaining == 0) false else it.glueWallActive,
                robertCoverActive = if (robertRemaining == 0) false else it.robertCoverActive,
                robertGlueWallActive = if (robertRemaining == 0) false else it.robertGlueWallActive,
                robertState = "advance",
                robertTurnBackTicks = 0
            )
        }
        val reaction = if (fromPlayer && robertRemaining > 0) robertAfterHit(robertDamage) else ""
        val defence = if (playerRemaining > 0 && playerDamage > 0) applyAutomaticDefences() else ""
        val carText = when (carHit) {
            CarHit.DESTROYED -> " ${t("carDestroyed")}"
            CarHit.ABSORBED -> " ${t("carHit")}"
            CarHit.NONE -> ""
        }
        val robertCarText = when (robertCarHit) {
            CarHit.DESTROYED -> " ${t("enemyCarDestroyed")}"
            CarHit.ABSORBED -> " ${t("carHit")}"
            CarHit.NONE -> ""
        }
        val text = "${t("bombExploded")} " +
            (if (fromPlayer) t("bombHitEnemy") else t("bombHitPlayer")) +
            " ${t("hitFor")} $targetDamage. ${t("enemyHealth")} $robertRemaining. " +
            "${t("yourHealth")} $playerRemaining." +
            (if (throwerHit) " ${if (fromPlayer) t("bombKilledYou") else t("bombKilledRobert")}" else "") +
            reaction + defence + carText + robertCarText
        msg(text)

        when {
            playerRemaining == 0 && robertRemaining == 0 -> endGameAfterExplosion(won = !fromPlayer)
            robertRemaining == 0 -> endGameAfterExplosion(won = true)
            playerRemaining == 0 -> endGameAfterExplosion(won = false)
            else -> {
                rollRobertFireRange()
                ensureRobertPositioned()
            }
        }
    }

    /**
     * Ends the battle only once the explosion has finished. The hit sound and
     * the explosion are already playing together, and cutting them short to
     * speak the result would swallow the blast, so the result waits for the
     * exact length of explosion.ogg.
     */
    private fun endGameAfterExplosion(won: Boolean) {
        if (endingJob != null) return
        endingJob = viewModelScope.launch {
            battleClock.awaitDelay(SoundDurations.EXPLOSION_MS)
            endingJob = null
            if (foreground && _currentScreen.value == Screen.BATTLE) endGame(won)
        }
    }

    /** True while the closing explosion of a missile or a bomb is running. */
    private fun isBattleEnding(): Boolean = endingJob != null

    // ---------------------------------------------------------------------
    // Player firing
    // ---------------------------------------------------------------------

    fun fire(fromAutomatic: Boolean = false) {
        if (battlePaused.value) return
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()

        val now = battleClock.nowMs()
        lastFireAt = now

        var state = _gameState.value
        if (state.playerHp <= 0 || state.robertHp <= 0) return
        // The electric shock has no bullets: it is only used by holding the swipe.
        if (state.activeWeapon.electricShock) {
            playSound("click")
            msg(t("electricHoldHint"))
            return
        }
        if (isBattleEnding()) {
            playSound("click")
            msg(t("waitExplosion"))
            return
        }

        // A locked opponent freezes the fight: no offensive weapon may be
        // aimed at him, and only the defences stay usable.
        if (LockRules.blocksOffensiveWeapons(state.opponentLocked(now))) {
            playSound("click")
            msg("${t("lockOpponentActive")} ${t("lockOffenceBlocked")}")
            return
        }

        // While a missile or a bomb is in the air no gun fires and no second
        // projectile starts: the weapon is held aiming at the opponent and
        // then drifts back toward the player, so the fight pauses until the
        // sky is clear. This is what gives the player the grace time to
        // deploy the anti-missile system or the anti-bomb explosive.
        if (skyNotEmpty(state)) {
            // The weapon is HELD aiming at the opponent and then drifts back
            // toward the player ([GraceRules]), which is the grace time to
            // deploy the anti-missile system or the anti-bomb explosive.
            _gameState.update {
                it.copy(weaponDriftM = GraceRules.driftAfter(it.weaponDriftM))
            }
            playSound("click")
            msg(t("waitProjectile"))
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
            if (weapon.autoFire) {
                // One shot of an automatic weapon is one bullet of sound - as
                // long as this beat and not as long as the recording. Its clip
                // is a multi-second burst, and letting it finish is what made a
                // single tap ring on and on, over the next tap and over the
                // enemy, until the whole battlefield sounded like one
                // unbroken firefight. A tap is therefore a bounded burst on the
                // player's own firing channel.
                audioManager.playAutomaticBurst(
                    weapon.fireSoundKey,
                    holdMs = weapon.burstIntervalMs,
                    owner = AudioManager.LOOP_OWNER_PLAYER,
                    volume = 1.0f,
                    posZ = -state.distance
                )
            } else {
                // Every other shot is independent and mixes with all other
                // effects.
                audioManager.playWeaponSound(weapon.fireSoundKey, volume = 1.0f)
            }
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

        // Robert's gloo wall: only three consecutive Gloo Wall Breaker shots
        // from the 90%-100% band destroy it. Every other weapon bounces off.
        if (state.robertGlueWallActive) {
            val breaks = Combat.canBreakGlueWall(weapon, proxPercent)
            val progress = if (breaks) state.robertGlueWallProgress + 1 else 0
            _gameState.update { it.copy(robertGlueWallProgress = progress) }
            playEnemySound("glueHit", volume = 0.9f)
            if (progress >= Combat.GLUE_WALL_BREAK_HITS) {
                _gameState.update {
                    it.copy(robertGlueWallActive = false, robertGlueWallProgress = 0)
                }
                playSound("glueWallCrack")
                msg(t("robertGlueDown"))
            } else if (breaks) {
                msg("${t("robertGlueCrack")} ${t("glueWallBreaks")} $progress.")
            } else if (weapon != Weapon.GLUE_BREAKER) {
                msg(t("robertGlueNeedsBreaker"))
            } else {
                msg(t("robertGlueBlocked"))
            }
            return
        }

        // Robert's bulletproof car stops every bullet, blade and swing: the
        // shot bounces off exactly like it does off the user's car.
        if (state.robertInCar && state.robertCarHp > 0) {
            playSound("gunHit", volume = 0.6f)
            playEnemySound("coverHit", volume = 0.6f)
            msg(t("carBlockedBullet"))
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
            return
        }

        val damage = AutomaticFirePowerMap.damageAt(weapon, proxPercent, state.robertMaxHp)
        val newRobertHp = max(0, state.robertHp - damage)
        _gameState.update {
            it.copy(
                robertHp = newRobertHp,
                playerHits = it.playerHits + 1,
                fightPlayerHits = it.fightPlayerHits + 1,
                robertConsecutiveHits = it.robertConsecutiveHits + 1,
                // Coins follow the power of the firing, never the shot count.
                playerFirePower = it.playerFirePower + Rewards.powerPointsFor(weapon)
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
        playEnemySound("enemyHit", volume = if (zone == HitZone.HEAVY) 1f else 0.7f)

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

        text += robertAfterHit(damage)
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
    }

    /**
     * Holding the up-swipe with an automatic weapon (submachine gun, AK-47
     * or machine gun) selected keeps firing: one bullet per
     * [Weapon.burstIntervalMs] for as long as the trigger is held, with the
     * weapon's own sound on the player's firing channel.
     *
     * The channel is opened on the very same gesture that registers the hold,
     * before the first bullet is even resolved, and it belongs to the player
     * alone: Robert's answer is mixed on his own channel and can never cut or
     * restart this one. Lifting the finger destroys the channel in the same
     * millisecond, so "held" and "audible" are one and the same fact - a burst
     * that keeps firing after the hand went up is not a state this game can be
     * in, because nothing is kept alive between two bursts any more.
     */
    fun startHoldFire() {
        if (battlePaused.value) return
        if (_currentScreen.value != Screen.BATTLE) return
        val state = _gameState.value
        val weapon = state.activeWeapon
        if (weapon.electricShock) {
            startElectricShock()
            return
        }
        if (!weapon.autoFire) {
            fire()
            return
        }
        // Repeated pointer events for the same hold are idempotent.
        if (state.autoFireActive) return
        if (!holdFireAllowed()) {
            fire()
            return
        }
        autoFireSession++
        val session = autoFireSession
        autoFireJob?.cancel()
        markExplicitAction()
        _gameState.update { it.copy(autoFireActive = true) }
        // Sound first, bullets second: the gun is already firing the moment
        // the hand goes down.
        startPlayerHoldLoop()
        val cadenceMs = AutomaticFirePowerMap.intervalFor(weapon) ?: weapon.burstIntervalMs
        autoFireJob = viewModelScope.launch {
            var nextShotAtMs = battleClock.nowMs()
            while (session == autoFireSession && holdFireAllowed()) {
                fireOneAutomaticShot()
                nextShotAtMs += cadenceMs
                val nowMs = battleClock.nowMs()
                // Do not create a tight catch-up loop after a busy UI frame.
                if (nextShotAtMs <= nowMs) nextShotAtMs = nowMs + cadenceMs
                battleClock.awaitDelay(nextShotAtMs - nowMs)
            }
            if (session == autoFireSession) stopHoldFire()
        }
    }

    /**
     * Opens (or keeps) the player's own firing loop for the weapon that is
     * currently selected. Called by the hold itself and by the watchdog of
     * every bullet, so a held burst is audible from the first millisecond.
     */
    private fun startPlayerHoldLoop() {
        val state = _gameState.value
        audioManager.startWeaponLoop(
            state.activeWeapon.fireSoundKey,
            volume = 1.0f,
            posZ = -state.distance,
            owner = AudioManager.LOOP_OWNER_PLAYER
        )
    }

    /**
     * Watchdog of the player's held burst.
     *
     * Called on every bullet, so a continuous machine-gun burst can never be
     * cut by a hit sound, by a crowded pool or by a glitch of the audio
     * system: the loop is re-armed on the next beat and keeps playing exactly
     * as long as the trigger is held. Other sounds are mixed on top of it and
     * are never allowed to take its stream.
     */
    private fun keepPlayerHoldLoopAlive() {
        val state = _gameState.value
        audioManager.keepWeaponLoopAlive(
            state.activeWeapon.fireSoundKey,
            owner = AudioManager.LOOP_OWNER_PLAYER,
            volume = 1.0f,
            posZ = -state.distance
        )
    }

    /**
     * Releasing the swipe: bullets and the gun sound stop together and
     * immediately, with no tail and no silence in between.
     */
    fun stopHoldFire() {
        stopElectricShock()
        shockSpent = false
        autoFireSession++
        autoFireJob?.cancel()
        autoFireJob = null
        val state = _gameState.value
        // The sound is cut first, so lifting the hand is heard as silence at
        // once. Every loop of the *player* is destroyed - not only the one of
        // the weapon that is selected now: after a weapon switch during a
        // burst the old channel would otherwise be left running, which is
        // exactly the phantom gunfire that used to be heard with no trigger
        // held. Robert keeps his own bursts: the player's finger never stops
        // the enemy's gun.
        audioManager.stopPlayerWeaponLoops()
        if (!state.autoFireActive) return
        _gameState.update { it.copy(autoFireActive = false) }
    }

    /** One bullet of a held automatic burst. */
    private fun fireOneAutomaticShot() {
        if (!holdFireAllowed()) return
        val state = _gameState.value
        val weapon = state.activeWeapon
        // Idempotent start doubles as the watchdog and positional-gain refresh.
        // It never adds a second stream and cannot run after holdFireAllowed().
        startPlayerHoldLoop()
        // Hard guarantee per bullet: a lost stream is re-armed at once instead
        // of silencing the rest of the burst.
        keepPlayerHoldLoopAlive()
        fire(fromAutomatic = true)
    }

    /** True while a held automatic burst may keep firing. */
    private fun holdFireAllowed(): Boolean {
        if (!foreground || _currentScreen.value != Screen.BATTLE) return false
        val state = _gameState.value
        if (state.playerHp <= 0 || state.robertHp <= 0) return false
        if (isBattleEnding()) return false
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
        if (battlePaused.value) return
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val state = _gameState.value
        val weapon = state.activeWeapon
        if (weapon.missileLauncher) {
            // The missile has no magazine: swipe down buys one missile
            // power for 1000 coins, as announced when none is owned.
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
        if (battlePaused.value) return
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
        if (battlePaused.value) return
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
        menuProtectUntilMs = battleClock.nowMs() + DamageTable.MENU_PROTECTION_MS
    }

    /** True while the menu-protection window is still open. */
    private fun isMenuProtectionActive(): Boolean =
        menuProtectUntilMs > 0L && battleClock.nowMs() < menuProtectUntilMs

    private fun speakBattleMenu() {
        val item = _gameState.value.menuItem
        when (item) {
            // While the car is active its slot becomes "Exit Car": one click
            // brakes the car and the door prompts walk the user out.
            BattleMenuItem.CAR -> {
                if (CarRules.isInside(_gameState.value.carStage)) {
                    speak("${t("menuNow")}: ${t("exitCar")}.")
                } else {
                    speak("${t("menuNow")}: ${t(DefenceItem.CAR.nameKey)}. ${_inventory.value.count(DefenceItem.CAR)}.")
                }
            }
            BattleMenuItem.MEDKIT,
            BattleMenuItem.COVER,
            BattleMenuItem.GLUE_WALL,
            BattleMenuItem.ANTI_MISSILE,
            BattleMenuItem.ANTI_BOMB,
            BattleMenuItem.LOCK -> {
                val defence = item.defence ?: return
                val free = defence == DefenceItem.ANTI_BOMB &&
                    _gameState.value.playerAntiBombFree
                val count = if (free) t("freeItem") else "${_inventory.value.count(defence)}"
                val lockState = if (item == BattleMenuItem.LOCK) {
                    val remaining = _gameState.value.lockRemainingMs(battleClock.nowMs())
                    if (remaining > 0L) {
                        " ${t("lockOpponentActive")} ${(remaining / 1000L).coerceAtLeast(1L)} ${t("seconds")}."
                    } else {
                        ""
                    }
                } else {
                    ""
                }
                speak("${t("menuNow")}: ${t(defence.nameKey)}. $count.$lockState")
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
        if (battlePaused.value) return
        if (_currentScreen.value != Screen.BATTLE) return
        markExplicitAction()
        val state = _gameState.value
        val item = state.menuItem
        when (item) {
            BattleMenuItem.CAR -> {
                if (CarRules.isInside(_gameState.value.carStage)) {
                    carExitFlow()
                    resetMenuSelection()
                } else {
                    useDefenceFromMenu(DefenceItem.CAR)
                }
            }
            BattleMenuItem.MEDKIT,
            BattleMenuItem.COVER,
            BattleMenuItem.GLUE_WALL,
            BattleMenuItem.ANTI_MISSILE,
            BattleMenuItem.ANTI_BOMB,
            BattleMenuItem.LOCK -> {
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
        // The anti-missile system may be armed before an attack; it then
        // automatically intercepts the next incoming missile. Anti-bomb is
        // still only useful against an enemy bomb already in flight unless
        // the round grants it for free (handled above).
        val antiBombIdle = defence == DefenceItem.ANTI_BOMB &&
            (!state.bombInFlight || state.bombFromPlayer)
        if (antiBombIdle) {
            if (inventory.count(defence) <= 0 &&
                _stats.value.completedRounds < defence.requiredCompletedRounds
            ) {
                playSound("ammo")
                val text = t(defence.lockedKey)
                toast(text)
                msg(text)
            } else {
                playSound("click")
                msg(t("noActiveBomb"))
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

    /**
     * Starts the countdown of "Lock the Opponent".
     *
     * The lock is a real minute of the battle clock: pausing the battle pauses
     * the lock as well, and it lifts itself the moment the minute is over,
     * without the player having to do anything. The remaining time is
     * announced every fifteen seconds so a Voice Guidance user always knows
     * how much of the freeze is left.
     */
    private fun startLockCountdown() {
        lockJob?.cancel()
        lockJob = viewModelScope.launch {
            var nextAnnouncement = LockRules.ANNOUNCE_EVERY_MS
            while (true) {
                battleClock.awaitDelay(500L)
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val state = _gameState.value
                val remaining = state.lockRemainingMs(battleClock.nowMs())
                if (state.opponentLockedUntilMs <= 0L) break
                if (remaining <= 0L) {
                    // The minute is over: Robert is free again and walks back
                    // into the fight by himself.
                    _gameState.update {
                        it.copy(
                            opponentLockedUntilMs = 0L,
                            robertState = if (it.robertState == "locked") "advance" else it.robertState
                        )
                    }
                    msg(t("lockOpponentExpired"))
                    rollRobertFireRange()
                    ensureRobertPositioned()
                    break
                }
                val elapsed = LockRules.DURATION_MS - remaining
                if (elapsed >= nextAnnouncement) {
                    nextAnnouncement += LockRules.ANNOUNCE_EVERY_MS
                    val seconds = (remaining / 1000L).coerceAtLeast(1L)
                    msg("${t("lockOpponentRemaining")} $seconds ${t("seconds")}.")
                }
            }
            lockJob = null
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
                // Robert may deploy his wall only below the defence threshold.
                onPlayerGlueWallRaised()
                ensureRobertPositioned()
                return if (automatic) t("autoGlueWallUsed") else t("glooWallDeployed")
            }
            DefenceItem.CAR -> return deployCar()
            DefenceItem.ANTI_MISSILE -> {
                // A system is ALWAYS deployable, with or without a missile in
                // the air. Deployed early it stands guard and destroys the
                // next incoming missiles by itself; deployed with a missile
                // already in the air it fires at once.
                if (state.antiMissileDeployed && state.playerInterceptorsLeft > 0 && !state.missileInFlight) {
                    playSound("click")
                    return t("antiMissileAlreadyDeployed")
                }
                val enemyMissile = state.missileInFlight && !state.missileFromPlayer
                _gameState.update {
                    it.copy(
                        antiMissileDeployed = true,
                        playerInterceptorsLeft = MissileRules.PLAYER_INTERCEPTS_PER_SYSTEM
                    )
                }
                if (!automatic) consumeDefence(item)
                if (enemyMissile) {
                    firePlayerInterceptor()
                    return t("antiMissileDestroyed")
                }
                playSound("click")
                return t("antiMissileArmed")
            }
            DefenceItem.ANTI_BOMB -> {
                // The anti-bomb explosive is deployable at any moment as well.
                // Deployed before a bomb is thrown it destroys that bomb in
                // the air by itself; deployed while the bomb already flies it
                // marks that bomb as destroyed at once, and the explosion
                // sound still plays when the countdown ends. From round three
                // the player's one is free, so no inventory is spent.
                if (state.antiBombDeployed && !state.bombInFlight) {
                    playSound("click")
                    return t("antiBombAlreadyDeployed")
                }
                _gameState.update { it.copy(antiBombDeployed = true) }
                if (!freeAntiBomb && !automatic) consumeDefence(item)
                if (state.bombInFlight && !state.bombFromPlayer) {
                    _gameState.update { it.copy(bombInterceptedByPlayer = true) }
                }
                playSound("click")
                return t("antiBombDeployed")
            }
            DefenceItem.LOCK -> {
                // "Lock the Opponent": one minute of complete freeze. It is a
                // pure state change - no offence is allowed while he is frozen,
                // every defence stays usable.
                val now = battleClock.nowMs()
                if (state.opponentLocked(now)) {
                    playSound("click")
                    val remaining = (state.lockRemainingMs(now) / 1000L).coerceAtLeast(1L)
                    return "${t("lockOpponentActive")} $remaining ${t("seconds")}."
                }
                _gameState.update {
                    it.copy(
                        opponentLockedUntilMs = LockRules.endsAt(now),
                        robertShocking = false,
                        robertState = "locked",
                        robertGlueWallProgress = 0,
                        robertExchangeShots = 0,
                        robertConsecutiveHits = 0
                    )
                }
                if (!automatic) consumeDefence(item)
                stopEnemyFireSound()
                audioManager.stopWeaponLoop("magicalSound", AudioManager.LOOP_OWNER_ENEMY)
                cancelRobertStep()
                audioManager.stopSound("enemyWalk")
                startLockCountdown()
                playSound("click")
                return "${t("lockOpponentActivated")} ${LockRules.DURATION_MS / 1000L} ${t("seconds")}."
            }
        }
    }

    private fun consumeDefence(item: DefenceItem) {
        val updated = Shop.consume(_inventory.value, item)
        _inventory.value = updated
        prefs.saveInventory(updated)
    }

    fun speakStatus(own: Boolean) {
        if (battlePaused.value) return
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
            // 25 metre square belong to the own status too.
            val speedText = "${t("runSpeed")} ${t(state.runSpeedLevel.nameKey)}."
            val positionText = "${t("yourPosition")} ${state.playerX.roundToInt()}, " +
                "${state.playerY.roundToInt()} ${t("meters")}."
            // The direction the player is really aiming at: only an enemy
            // inside this cone can be hit.
            val facingText = "${t("yourFacing")} ${t(state.facing.nameKey)}."
            val bombText = if (state.bombInFlight) {
                " ${t("bombDestination")} ${state.playerDistanceFromBomb.roundToInt()} ${t("meters")}."
            } else {
                ""
            }
            speak(
                "${t("yourHealth")} ${state.playerHp}. ${t("ammo")} ${state.activeAmmo}. " +
                    "$weaponState. $speedText $positionText $facingText $weapons. " +
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
                " ${t("glooWall")} ${t("glueWallBreaks")} ${state.robertGlueWallProgress}."
            } else ""
            // The enemy's own gloo wall and the breaker charges he has left
            // are part of the enemy status: both are limited per match.
            val enemyWallText = if (state.robertGlueBreakers > 0 && state.glueWallActive) {
                " ${t("enemyBreakerArmed")} ${state.robertGlueBreakers}."
            } else if (state.robertGlueBreakers <= 0) {
                " ${t("enemyBreakerSpent")}"
            } else {
                ""
            }
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
                    "$coverText$glueText$enemyWallText$bombText"
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
     * more than one glue wall) with level-complete.ogg, and a completed round
     * always pays two glue walls, two medikits and three covers with
     * round-complete.ogg.
     */
    private fun endGame(won: Boolean, timeUp: Boolean = false) {
        stopTimers()
        audioManager.stopFootsteps()

        val state = _gameState.value
        val oldStats = _stats.value
        // A defeat costs the player 20 coins instead of earning any. A player
        // with no coins is the exception: nothing can be taken from them.
        val coinsLost = if (won) 0 else Progression.coinsLostOnDefeat(oldStats.coins)
        // Coins follow the CAPABILITY AND POWER of the played battle, never
        // the number of plays: the power points of the landed weapons, the
        // share the electric shock really took (700 to 1000 coins on its own
        // for one real use), the health that survived the fight and the real
        // accuracy of the firing. A win pays at most MAX_BATTLE_COINS.
        val shockCoins = Rewards.shockCoins(state.shockDamageDealt, state.robertMaxHp)
        val performance = BattlePerformance(
            won = won,
            firePower = state.playerFirePower,
            shockCoins = shockCoins,
            playerHp = state.playerHp,
            playerMaxHp = state.playerMaxHp,
            hits = state.playerHits,
            shots = state.playerShots
        )
        val grossWinnings = Rewards.battleCoins(performance)
        val powerCoins = grossWinnings - shockCoins.coerceIn(0, Rewards.SHOCK_MAX_COINS)
        val repayment = Loans.repay(oldStats.loanBalance, grossWinnings)
        val gain = if (won) repayment.credited else -coinsLost

        var updatedStats = oldStats.copy(
            coins = (oldStats.coins.toLong() + gain).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            loanBalance = repayment.remaining,
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
        }

        // Coins and the loan principal are saved in the same encrypted file.
        // Do not show a completed result or grant rewards if that transaction
        // failed; retry from the unchanged battle snapshot instead.
        if (!prefs.saveStats(updatedStats)) {
            setLog(t("profileSaveFailed"))
            speak(t("profileSaveFailed"))
            endingJob = viewModelScope.launch {
                battleClock.awaitDelay(3_000L)
                endingJob = null
                if (_currentScreen.value == Screen.BATTLE) endGame(won, timeUp)
            }
            return
        }
        _stats.value = updatedStats
        if (!reward.isEmpty) {
            var granted = Shop.grantBundle(_inventory.value, reward)
            if (antiMissileReward) granted = granted.copy(antiMissileRewardGranted = true)
            _inventory.value = granted
            prefs.saveInventory(granted)
        }

        _resultData.value = ResultData(
            won = won,
            coins = if (won) grossWinnings else gain,
            shockCoins = if (won) shockCoins else 0,
            powerCoins = if (won) powerCoins.coerceAtLeast(0) else 0,
            loanDeducted = repayment.deducted,
            loanRemaining = repayment.remaining,
            loanWasActive = won && oldStats.loanBalance > 0,
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
            // The clean result: how many coins were received, and - only when
            // a loan is really active - how much of it was deducted and what
            // is still owed. No "Loans deducted: 0", no filler lines.
            speech += " ${t("earned")} ${repayment.credited}."
            // The shock share is announced when the electric shock really
            // took health, so its 700 to 1000 coins are never a silent part
            // of the total.
            if (shockCoins > 0) {
                speech += " ${t("gunElectricShock")}: $shockCoins ${t("coins")}."
            }
            if (oldStats.loanBalance > 0) {
                speech += " ${t("loanDeducted")} ${repayment.deducted}."
                if (repayment.remaining > 0) {
                    speech += " ${t("loanRemaining")} ${repayment.remaining}."
                }
            }
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

    /** Restart a selected round while retaining coins and lifetime profile stats. */
    fun playRoundAgain(round: Int) {
        if (!_resultReady.value) {
            playAgain()
            return
        }
        val updated = _stats.value.copy(
            round = round.coerceIn(1, Progression.ROUNDS_PER_MATCH),
            level = 1,
            levelWins = 0
        )
        if (!prefs.saveStats(updated)) {
            msg(t("profileSaveFailed"))
            return
        }
        _stats.value = updated
        playAgain()
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

    /** Clears the profile and immediately begins a fresh match from round one. */
    fun clearStatsAndRestart() {
        if (!_resultReady.value) {
            playSound("ammo")
            speak(t("waitPlease"))
            return
        }
        clearStats()
        playAgain()
    }

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

    // ---------------------------------------------------------------------
    // Bulletproof car
    //
    // Fully automatic: deploying it from the defence menu prompts "Open the
    // door", then "Close the door", and the moment the door closes the engine
    // starts and the car drives toward the enemy on its own. The player keeps
    // playing normally - firing, reloading, switching weapons, the quick menu
    // - while the car protects from bullets. When the enemy stands still the
    // car brakes itself into an engine-on idle and follows him again the
    // moment he moves. Leaving is just as automatic: while the car is active
    // the quick menu shows "Exit Car" in place of "Car"; one click brakes the
    // car and the door prompts walk the user out. The same car is then reused
    // from that menu. One refuel lasts five uses and costs 500 coins.
    // ---------------------------------------------------------------------

    private var carDriveJob: Job? = null
    private var carTurnBackTicks = 0

    /** Ticks without any enemy movement; the car brakes itself when they run out. */
    private var carStationaryTicks = 0

    /** The enemy position the car saw on its previous tick (stationary detection). */
    private var carLastEnemyX = Float.NaN
    private var carLastEnemyY = Float.NaN

    /** Result of a missile or bomb meeting a car. */
    private enum class CarHit { NONE, ABSORBED, DESTROYED }

    /**
     * A missile meeting the user's car destroys it outright - unless an
     * anti-missile system neutralised the missile in the air, which always
     * happens before this is reached. Only a user inside with the door closed
     * is shielded. Returns what happened; the caller plays the sound.
     */
    private fun carTakesMissile(): CarHit {
        val state = _gameState.value
        if (!CarRules.shieldsUser(state.carStage) || state.carHp <= 0) return CarHit.NONE
        destroyCar()
        return CarHit.DESTROYED
    }

    /**
     * A bomb blast meeting the user's car. The first hit is absorbed and the
     * second consecutive one destroys the car. An activated anti-bomb
     * explosive neutralises the bomb before the blast ever happens, so the
     * counter never climbs while the defences are up.
     */
    private fun carTakesBomb(damage: Int): CarHit {
        val state = _gameState.value
        if (!CarRules.shieldsUser(state.carStage) || state.carHp <= 0) return CarHit.NONE
        val remaining = (state.carHp - damage).coerceAtLeast(0)
        if (CarRules.destroyedAfterBombHit(state.carBombHits) || remaining <= 0) {
            destroyCar()
            return CarHit.DESTROYED
        }
        _gameState.update { it.copy(carHp = remaining, carBombHits = it.carBombHits + 1) }
        return CarHit.ABSORBED
    }

    /** A missile meeting Robert's car: one missile destroys it. */
    private fun robertCarTakesMissile(): CarHit {
        if (!_gameState.value.robertInCar || _gameState.value.robertCarHp <= 0) return CarHit.NONE
        destroyRobertCar()
        return CarHit.DESTROYED
    }

    /** A bomb blast meeting Robert's car: the second consecutive hit destroys it. */
    private fun robertCarTakesBomb(damage: Int): CarHit {
        val state = _gameState.value
        if (!state.robertInCar || state.robertCarHp <= 0) return CarHit.NONE
        val remaining = (state.robertCarHp - damage).coerceAtLeast(0)
        if (CarRules.destroyedAfterBombHit(state.robertCarBombHits) || remaining <= 0) {
            destroyRobertCar()
            return CarHit.DESTROYED
        }
        _gameState.update {
            it.copy(robertCarHp = remaining, robertCarBombHits = it.robertCarBombHits + 1)
        }
        return CarHit.ABSORBED
    }

    /** Destroys the car for the rest of the match. The user is out in the open. */
    private fun destroyCar() {
        stopCarDriveJob()
        audioManager.stopCarSounds()
        _gameState.update {
            it.copy(carStage = CarStage.DESTROYED, carHp = 0, carBombHits = 0)
        }
        _inventory.value = Shop.loseCar(_inventory.value)
        prefs.saveInventory(_inventory.value)
    }

    /** Destroys Robert's car. He is out in the open again. */
    private fun destroyRobertCar() {
        _gameState.update {
            it.copy(robertInCar = false, robertCarHp = 0, robertCarBombHits = 0)
        }
        playEnemySound("carCrash")
        msg(t("enemyCarDestroyed"))
    }

    /**
     * Deploys the owned car from the defence menu. A battle uses up one of its
     * five uses. With no uses left the car asks for a refuel instead. The
     * deployment prompt is "Open the door": the door, the close, the engine
     * start and the driving all follow automatically from there.
     */
    private fun deployCar(): String {
        val inventory = _inventory.value
        val current = _gameState.value.carStage
        val stage = if (current == CarStage.DESTROYED) CarStage.NOT_DEPLOYED else current
        if (stage != CarStage.NOT_DEPLOYED) return t(CarRules.promptKey(stage))
        if (inventory.carGamesLeft <= 0) {
            _gameState.update { it.copy(carStage = CarStage.REFUEL_PENDING) }
            playSound("click")
            return t("carRefuelPrompt")
        }
        _inventory.value = Shop.useCarGame(inventory)
        prefs.saveInventory(_inventory.value)
        _gameState.update {
            it.copy(carStage = CarStage.DOOR_CLOSED_OUTSIDE, carHp = CarRules.MAX_HP, carBombHits = 0)
        }
        playSound("click")
        return t("carOpenDoor")
    }

    /**
     * One-finger swipe up while the car waits for it: opens the door, closes
     * it (and the car then starts and drives on its own), gets out, or refuels.
     */
    private fun carSwipeUp() {
        when (_gameState.value.carStage) {
            CarStage.DOOR_CLOSED_OUTSIDE -> {
                playSound("carDoorOpen")
                _gameState.update { it.copy(carStage = CarStage.DOOR_OPEN_ENTERING) }
                msg(t("carCloseDoor"))
            }
            CarStage.DOOR_OPEN_ENTERING -> {
                playSound("carDoorClose")
                startCarAutomatically()
            }
            CarStage.INSIDE_ENGINE_OFF -> {
                // Stopped after "Exit Car": the door opens on the way out.
                playSound("carDoorOpen")
                _gameState.update { it.copy(carStage = CarStage.DOOR_OPEN_EXITING) }
                msg(t("carCloseDoor"))
            }
            CarStage.DOOR_OPEN_EXITING -> {
                playSound("carDoorClose")
                _gameState.update { it.copy(carStage = CarStage.NOT_DEPLOYED) }
                msg(t("carExited"))
            }
            CarStage.REFUEL_PENDING -> refuelCar()
            else -> Unit
        }
    }

    /**
     * The door is closed: the engine starts and the car drives itself toward
     * the enemy, with no steering and no hold. The user keeps playing
     * normally while the car moves and shields.
     */
    private fun startCarAutomatically() {
        // The start sound runs into the moving loop with no gap.
        audioManager.playSequence(listOf("carStart"), loopKey = "carMoving")
        carTurnBackTicks = 0
        carStationaryTicks = 0
        carLastEnemyX = Float.NaN
        carLastEnemyY = Float.NaN
        _gameState.update { it.copy(carStage = CarStage.INSIDE_MOVING) }
        msg(t("carDrivingPrompt"))
        startCarDriveJob()
    }

    /** The car's automatic brain: drive, follow, brake for a standing enemy. */
    private fun startCarDriveJob() {
        stopCarDriveJob()
        carDriveJob = viewModelScope.launch {
            while (true) {
                if (!foreground || _currentScreen.value != Screen.BATTLE) break
                val state = _gameState.value
                if (!CarRules.isInside(state.carStage)) break
                if (state.playerHp <= 0 || state.robertHp <= 0) break
                if (!battlePaused.value) carAutoTick()
                battleClock.awaitDelay(CarRules.TICK_MS)
            }
        }
    }

    /**
     * One automatic tick. While the car drives it chases the enemy and follows
     * his sidesteps. An enemy that does not move makes the car brake itself
     * into the engine-on idle - both fighters can stay there and fire - and
     * his next step starts the car again behind him.
     */
    private fun carAutoTick() {
        val state = _gameState.value
        val enemyMoved = java.lang.Float.isNaN(carLastEnemyX) ||
            kotlin.math.abs(state.robertX - carLastEnemyX) > 0.05f ||
            kotlin.math.abs(state.robertY - carLastEnemyY) > 0.05f
        carLastEnemyX = state.robertX
        carLastEnemyY = state.robertY
        carStationaryTicks = if (enemyMoved) 0 else carStationaryTicks + 1

        when (state.carStage) {
            CarStage.INSIDE_MOVING -> {
                val closeEnough = state.distance <= CarRules.STOP_DISTANCE_M
                // Do not brake just because the enemy paused across the arena:
                // that caused a brake/restart loop on every tick. A stationary
                // target is a braking case only once the car is already within
                // its short stopping envelope.
                val stationaryInBrakingZone =
                    carStationaryTicks >= CarRules.STATIONARY_TICKS &&
                        state.distance <= CarRules.STOP_DISTANCE_M + CarRules.GAP_TOLERANCE_M
                if (closeEnough || stationaryInBrakingZone) {
                    carAutoBrake()
                } else {
                    driveCarOneTick()
                }
            }
            CarStage.INSIDE_ENGINE_ON -> {
                // The engine idles under the brake. The car only starts again
                // when there is a reason to move: the enemy stepped, or the
                // enemy is still on the other side of a wall and the car has
                // to close the gap. The mere fact that the distance is larger
                // than the braking distance is NOT such a reason - an enemy
                // who stands still 15 m away used to keep the car starting and
                // braking every second, which sounded as if the brake were
                // broken. A standing enemy means a standing car.
                if (enemyMoved || carNeedsToCloseGap(state)) carResumeDriving()
            }
            else -> Unit
        }
    }

    /**
     * True when the idle car really has to set off: the enemy stands further
     * away than the car's reach and is not moving toward the player either.
     * A car that is already parked within its own braking distance stays
     * parked, so the brake never fires twice for a standing enemy.
     */
    private fun carNeedsToCloseGap(state: GameState): Boolean =
        state.distance > CarRules.STOP_DISTANCE_M + CarRules.GAP_TOLERANCE_M

    /** The automatic brake: the moving loop stops and the engine idles. */
    private fun carAutoBrake() {
        audioManager.stopSound("carMoving")
        // The brake is one single play: it is never chained into a sequence and
        // never repeated per movement tick, so the car brakes exactly once and
        // then idles. `playOnce` also refuses a second copy of the same sound,
        // so a repeated automatic brake cannot stutter the recording.
        audioManager.playOnce("carBrake")
        audioManager.startWeaponLoop("carEngine", owner = AudioManager.LOOP_OWNER_PLAYER)
        _gameState.update { it.copy(carStage = CarStage.INSIDE_ENGINE_ON) }
    }

    /** The enemy moved again: the engine note gives way to the moving loop. */
    private fun carResumeDriving() {
        audioManager.stopSound("carEngine")
        audioManager.startWeaponLoop("carMoving", owner = AudioManager.LOOP_OWNER_PLAYER)
        _gameState.update { it.copy(carStage = CarStage.INSIDE_MOVING) }
    }

    /**
     * One step of driving: toward the enemy, and a sideways nudge to follow
     * his flanking steps, so the car really runs behind him. After a wall it
     * drives back the other way for a few steps.
     */
    private fun driveCarOneTick() {
        val metres = CarRules.SPEED_MPS * CarRules.TICK_MS / 1000f
        val forward = carTurnBackTicks == 0
        if (!forward) carTurnBackTicks--
        if (forward && _gameState.value.distance <= CarRules.STOP_DISTANCE_M) return
        var blocked = false
        _gameState.update { current ->
            val move = current.movePlayer(forward, metres)
            blocked = move.blockedByWall
            if (move.moved) move.state else current
        }
        if (blocked && forward) carTurnBackTicks = CarRules.TURN_BACK_TICKS
        // Follow the enemy sideways as well, so the car stays right behind him.
        val after = _gameState.value
        val dx = after.robertX - after.playerX
        if (kotlin.math.abs(dx) > 1f) {
            _gameState.update { current ->
                val move = current.movePlayerLateral(dx > 0f, minOf(2f, metres))
                if (move.moved) move.state else current
            }
        }
    }

    private fun stopCarDriveJob() {
        carDriveJob?.cancel()
        carDriveJob = null
    }

    /** Refuels the empty car when the user has the money, then deploys it again. */
    private fun refuelCar() {
        val purchase = Shop.refuelCar(_inventory.value, _stats.value.coins)
        if (!purchase.success) {
            when (purchase.reasonKey) {
                "notEnoughCoins" -> notEnoughCoins()
                "notOwned" -> msg(t("carNotOwned"))
                else -> msg(t("carHasFuel"))
            }
            return
        }
        _inventory.value = purchase.inventory
        prefs.saveInventory(purchase.inventory)
        _stats.value = _stats.value.copy(coins = purchase.coins)
        prefs.saveStats(_stats.value)
        playSound("spend")
        _gameState.update { it.copy(carStage = CarStage.NOT_DEPLOYED) }
        msg("${t("carRefuelled")} ${deployCar()}")
    }

    /**
     * The quick menu's "Exit Car" (shown in place of "Car" while the car is
     * active): the car brakes with the brake sound exactly like the automatic
     * brake, then the door prompts walk the user out - open, then close - and
     * the same car can be reused from that menu.
     */
    private fun carExitFlow() {
        val stage = _gameState.value.carStage
        when {
            CarRules.isInside(stage) -> {
                stopCarDriveJob()
                audioManager.stopSound("carMoving")
                audioManager.stopSound("carEngine")
                playSound("carBrake")
                _gameState.update { it.copy(carStage = CarStage.INSIDE_ENGINE_OFF) }
                msg(t("carOpenDoor"))
            }
            stage == CarStage.DOOR_OPEN_EXITING -> {
                playSound("carDoorClose")
                _gameState.update { it.copy(carStage = CarStage.NOT_DEPLOYED) }
                msg(t("carExited"))
            }
            else -> Unit
        }
    }

    /**
     * Routes the car's gestures. Only the door and refuel prompts own a
     * gesture (the one-finger swipe up). While the car drives itself every
     * gesture is the normal battle gesture again: one finger fires, two
     * fingers drive the quick menu, three fingers pause or exit.
     */
    private fun handleCarGesture(direction: String, fingers: Int): Boolean {
        val stage = _gameState.value.carStage
        if (fingers == 1 && direction == "up" && CarRules.awaitsSwipeUp(stage)) {
            carSwipeUp()
            return true
        }
        return false
    }

    /**
     * Robert climbs into his own bulletproof car (granted from round five)
     * once his health drops below the 400 line: the door opens, closes, the
     * engine starts - and he drives and fires from cover of the car.
     */
    private fun robertEnterCar() {
        if (_gameState.value.robertInCar) return
        val shockWasActive = _gameState.value.shockHolding || _gameState.value.robertShocking
        if (_gameState.value.shockHolding) stopElectricShock(playRelease = false)
        if (_gameState.value.robertShocking) stopRobertShock()
        if (shockWasActive) msg(t("enemyProtectedByCar"))
        _gameState.update {
            it.copy(
                robertInCar = true,
                robertCarHp = if (it.robertCarHp > 0) it.robertCarHp else CarRules.MAX_HP,
                robertCarBombHits = 0
            )
        }
        playEnemySound("carDoorOpen")
        msg(t("enemyCarEntered"))
        viewModelScope.launch {
            battleClock.awaitDelay(600L)
            if (!_gameState.value.robertInCar) return@launch
            playEnemySound("carDoorClose")
            battleClock.awaitDelay(400L)
            if (!_gameState.value.robertInCar) return@launch
            playEnemySound("carStart")
        }
    }

    // ---------------------------------------------------------------------
    // Electric shock
    //
    // The shock is a proximity weapon, not a ray:
    //  - holding the up-swipe with the shock selected is allowed only while
    //    the opponent is really inside the 40%-100% band. Out of range nothing
    //    happens at all - no hum, no hold state, no damage - until he walks
    //    into the band;
    //  - while a missile or a bomb is in the air the shock cannot target the
    //    enemy: the weapon is held aiming at him and then drifts back toward
    //    the player, giving time to deploy the anti-bomb or anti-missile
    //    defence instead;
    //  - Robert detects the shock and answers at random with a counter of his
    //    own or with a sprint out of the band;
    //  - while both shocks run, both fighters lose 10% of their maximum health
    //    every two seconds, and the share the player really took is paid in
    //    coins at the end of the battle.
    // ---------------------------------------------------------------------
    private var shockJob: Job? = null
    private var shockStartedAtMs = 0L
    private var shockLastTickMs = 0L
    private var shockCounterAnnounced = false
    private var playerShockDamageMs = 0L
    private var duelShockDamageMs = 0L

    /** When Robert's own shock started. */
    private var robertShockStartedAtMs = 0L

    /** True while Robert's shock is an answer to the player's shock. */
    private var robertShockIsCounter = false

    /** Continuous milliseconds the player's shock has held the 90%-100% band. */
    private var playerShockBreakMs = 0L

    /** Continuous milliseconds Robert's shock has held the 90%-100% band. */
    private var robertShockBreakMs = 0L

    /** True after the 30 second cap, until the finger is lifted again. */
    private var shockSpent = false

    /**
     * True while a held shock is temporarily ineffective because Robert is
     * outside its range or an incoming missile has crossed the final-three-
     * percent cutoff. Other in-flight projectiles do not block the shock.
     */
    private var shockDeployLocked = false

    /** True while Robert is sprinting out of the player's shock band. */
    private var robertFleeingShock = false

    /** True while the proximity gate has already refused one hold. */
    private var shockOutOfRangeAnnounced = false

    /**
     * True when the electric shock can reach the opponent right now. The shock
     * only works - and only produces its hum - between 40% and 100% distance.
     */
    private fun shockInRange(state: GameState = _gameState.value): Boolean =
        !state.robertInCar && ElectricShockRules.inCloseBand(Combat.proximityOf(state.distance))

    /** True once an incoming missile has crossed the shock's 97% cutoff. */
    private fun incomingMissileTooClose(state: GameState = _gameState.value): Boolean {
        if (!state.missileInFlight || state.missileFromPlayer) return false
        val remaining = (missileImpactAtMs - battleClock.nowMs()).coerceAtLeast(0L)
        return !MissileRules.canShockDeflect(remaining, missileFlightDurationMs)
    }

    /** True while a guided missile or a bomb is in the air; guns wait for clear sky. */
    private fun skyNotEmpty(state: GameState = _gameState.value): Boolean =
        state.missileInFlight || state.bombInFlight

    /**
     * Walks a held weapon back in once the sky is clear again: the drift of
     * [GraceRules] is released, so the next swipe aims at the opponent at the
     * distance the fighters really have.
     */
    private fun releaseWeaponDrift() {
        if (_gameState.value.weaponDriftM <= 0f) return
        _gameState.update { it.copy(weaponDriftM = GraceRules.driftReleased(it.weaponDriftM)) }
    }

    /**
     * The one authority on whether the player's shock is burning right now.
     *
     * Everything - the hum, the per-tick damage and the barrier break - reads
     * this single test. The shock works during projectile flight, except when
     * an incoming missile has crossed its final-three-percent cutoff. Two
     * separate conditions used to drift apart, which is what let a shock look
     * armed while it dealt nothing.
     */
    private fun playerShockArmed(state: GameState = _gameState.value): Boolean =
        state.shockHolding && !state.timerSelectActive && shockInRange(state) &&
            !incomingMissileTooClose(state)

    /**
     * Warns the user that a hold could not be armed, once per hold: a refused
     * swipe is never silent, because silence is what makes a blind player swipe
     * again and again without knowing why nothing happens.
     */
    private fun refuseShock(messageKey: String) {
        if (shockOutOfRangeAnnounced) return
        shockOutOfRangeAnnounced = true
        playSound("click")
        msg(t(messageKey))
    }

    /**
     * A held up-swipe with the electric shock selected.
     *
     * The action is refused if Robert is protected by his car, outside the
     * 40%-100% band, or an incoming missile is already past the deflection
     * cutoff. Earlier missiles and bombs do not prevent a shock.
     */
    private fun startElectricShock() {
        if (battlePaused.value || _currentScreen.value != Screen.BATTLE) return
        if (_gameState.value.shockHolding || shockSpent) return
        val state = _gameState.value
        if (state.timerSelectActive || state.playerHp <= 0 || state.robertHp <= 0) return
        if (state.robertInCar) {
            playSound("click")
            msg(t("enemyProtectedByCar"))
            return
        }
        if (!shockInRange(state)) {
            refuseShock("shockOutOfRange")
            return
        }
        if (incomingMissileTooClose(state)) {
            playSound("click")
            msg(t("shockBlockedByProjectile"))
            return
        }
        shockOutOfRangeAnnounced = false
        markExplicitAction()
        val now = battleClock.nowMs()
        shockStartedAtMs = now
        shockLastTickMs = now
        shockCounterAnnounced = false
        playerShockBreakMs = 0L
        playerShockDamageMs = 0L
        duelShockDamageMs = 0L
        robertFleeingShock = false
        shockDeployLocked = false
        _gameState.update {
            it.copy(
                shockHolding = true,
                electricShockInPlay = true,
                shockFirstActivator = if (it.robertShocking) it.shockFirstActivator else "player"
            )
        }
        // The shock delays, but does not destroy or de-target, one incoming
        // missile. The same missile resumes its guided approach after the pause.
        deflectIncomingMissileWithShock()
        audioManager.startWeaponLoop(
            "magicalSound",
            volume = SHOCK_HUM_VOLUME,
            posZ = 0.1f,
            owner = AudioManager.LOOP_OWNER_PLAYER
        )
        respondToPlayerShock()
        ensureShockTicker()
    }

    /**
     * Robert detects the player's shock and answers it at random: either with
     * his own shock (a duel, in which both lose 10% of maximum health every two
     * seconds) or with a sprint out of the 40% shock band. Both options are
     * always possible; a badly hurt robot merely prefers running.
     */
    private fun respondToPlayerShock() {
        if (!shockInRange() || incomingMissileTooClose() || shockDeployLocked) return
        val state = _gameState.value
        if (state.robertShocking || state.robertHp <= 0) return
        if (!RobertRules.robotShockUnlocked(state.round)) {
            // Before round three Robert owns no shock at all: he simply runs
            // out of the band, which is the silence the player hears.
            fleeFromShock()
            return
        }
        if (!RobertRules.shouldCounterShock(
                playerShocking = state.shockHolding,
                playerHeldMs = battleClock.nowMs() - shockStartedAtMs,
                proximityPercent = Combat.proximityOf(state.distance)
            )
        ) {
            return
        }
        val answer = RobertRules.chooseShockAnswer(
            round = state.round,
            robertHp = state.robertHp,
            robertMaxHp = state.robertMaxHp,
            random = Random
        )
        when (answer) {
            RobertRules.ShockAnswer.COUNTER -> startRobertShock()
            RobertRules.ShockAnswer.FLEE -> fleeFromShock()
        }
    }

    /**
     * Robert's half of the duel: his own shock rises at once and both hums are
     * mixed together. It only ever runs while the player's shock is held.
     */
    private fun startRobertShock() {
        val state = _gameState.value
        if (state.robertShocking || state.robertHp <= 0 || state.playerHp <= 0) return
        if (!state.shockHolding) return
        if (LockRules.blocksOpponent(state.opponentLocked(battleClock.nowMs()))) return
        robertShockStartedAtMs = battleClock.nowMs()
        robertShockIsCounter = state.shockHolding
        robertShockBreakMs = 0L
        robertFleeingShock = false
        _gameState.update {
            it.copy(
                robertShocking = true,
                shockFirstActivator = if (it.shockHolding) it.shockFirstActivator else "enemy"
            )
        }
        stopEnemyFireSound()
        audioManager.startWeaponLoop(
            "magicalSound",
            volume = SHOCK_HUM_VOLUME,
            posZ = -0.1f,
            owner = AudioManager.LOOP_OWNER_ENEMY,
            startOffsetMs = 337L
        )
        if (!shockCounterAnnounced) {
            shockCounterAnnounced = true
            setLog(t("electricCounter"))
        }
        ensureShockTicker()
    }

    /**
     * Robert's other answer: he sprints out of the 40% band instead of trading
     * shocks. The moment he leaves the band the shock can no longer reach him,
     * and the player has to close the distance again.
     */
    private fun fleeFromShock() {
        if (_gameState.value.robertShocking) return
        robertFleeingShock = true
        msg(t("enemyFleesShock"))
        stopEnemyFireSound()
        if (_gameState.value.robertState != "retreat") {
            _gameState.update {
                it.copy(robertState = "retreat", robertConsecutiveHits = 0, robertExchangeShots = 0)
            }
            robertSprintTicks = RobertRules.SPRINT_TICKS
            planRobertStep(FLEE_TICK_MS)
        }
    }

    /**
     * True while the player's own shock hum is open. Kept as a cheap query so
     * the ticker re-arms the hum of a hold that had to go quiet (a projectile
     * in the air) without restarting a hum that is already playing.
     */
    private fun mixerHasShockHum(): Boolean =
        audioManager.isWeaponLoopActive("magicalSound", AudioManager.LOOP_OWNER_PLAYER)

    /** The single damage ticker of every active shock, whichever side holds it. */
    private fun ensureShockTicker() {
        if (shockJob != null) return
        shockLastTickMs = battleClock.nowMs()
        shockJob = viewModelScope.launch {
            while (true) {
                battleClock.awaitDelay(SHOCK_TICK_MS)
                if (!foreground || _currentScreen.value != Screen.BATTLE) {
                    stopAllShocks(playRelease = false)
                    break
                }
                if (!shockTick()) break
            }
            shockJob = null
        }
    }

    /**
     * One step of every active shock.
     *
     * Returns false once no shock is left. The proximity gate is re-checked on
     * every tick: an opponent who runs out of the band stops being hurt, and
     * the hum of a weapon that is out of reach is silenced at once.
     */
    private fun shockTick(): Boolean {
        val now = battleClock.nowMs()
        val dtMs = (now - shockLastTickMs).coerceAtLeast(0L)
        shockLastTickMs = now
        if (battlePaused.value) return true
        val startState = _gameState.value
        if (startState.playerHp <= 0 || startState.robertHp <= 0) return false
        if (!startState.shockHolding && !startState.robertShocking) return false
        if (startState.opponentLocked(now) && !startState.shockHolding) return false

        // The thirty second cap ends one use at a time; the fight goes on.
        if (startState.shockHolding && ElectricShockRules.timeUp(now - shockStartedAtMs)) {
            stopElectricShock(timedOut = true)
        }
        if (_gameState.value.robertShocking && ElectricShockRules.timeUp(now - robertShockStartedAtMs)) {
            stopRobertShock()
        }

        val state = _gameState.value
        if (!state.shockHolding && !state.robertShocking) return false

        // The ONE authority on whether the player's shock is burning: inside
        // the 40%-100% band, with no car protection and no incoming missile past
        // its cutoff. Other projectile flight does not block the shock.
        val burning = playerShockArmed(state)
        shockDeployLocked = state.shockHolding && !burning &&
            (incomingMissileTooClose(state) || !shockInRange(state))

        // The range gate: out of the 40%-100% band the shock neither sounds nor
        // hurts. Robert also stops fleeing once he is really out of the band.
        val proximity = Combat.proximityOf(state.distance)
        val inBand = ElectricShockRules.inCloseBand(proximity)
        if (!inBand) {
            if (state.robertShocking) {
                audioManager.stopWeaponLoop("magicalSound", AudioManager.LOOP_OWNER_ENEMY)
                _gameState.update { it.copy(robertShocking = false, shockFirstActivator = "") }
            }
            if (state.shockHolding) {
                audioManager.stopWeaponLoop("magicalSound", AudioManager.LOOP_OWNER_PLAYER)
                refuseShock("shockOutOfRange")
            }
            if (robertFleeingShock) robertFleeingShock = false
            playerShockDamageMs = 0L
            duelShockDamageMs = 0L
            return state.shockHolding || state.robertShocking
        }

        // An incoming missile in its final three percent is too close to
        // deflect. Bombs, earlier missiles, and outgoing missiles do not
        // disable the shock against Robert.
        if (state.shockHolding && state.robertInCar) {
            stopElectricShock(playRelease = false)
            msg(t("enemyProtectedByCar"))
            return state.robertShocking
        }
        if (state.shockHolding && incomingMissileTooClose(state)) {
            audioManager.stopWeaponLoop("magicalSound", AudioManager.LOOP_OWNER_PLAYER)
            if (state.robertShocking) stopRobertShock()
            if (!shockOutOfRangeAnnounced) {
                shockOutOfRangeAnnounced = true
                msg(t("shockBlockedByProjectile"))
            }
            playerShockDamageMs = 0L
            duelShockDamageMs = 0L
            return true
        }
        // A hold that is burning again is a new state: a later refusal has to
        // be spoken again, because it is about a different moment of the fight.
        shockOutOfRangeAnnounced = false
        if (burning && !mixerHasShockHum()) {
            audioManager.startWeaponLoop(
                "magicalSound",
                volume = SHOCK_HUM_VOLUME,
                posZ = 0.1f,
                owner = AudioManager.LOOP_OWNER_PLAYER
            )
        }

        val live = _gameState.value
        // Robert answers once, a moment after the shock really started to burn.
        if (live.shockHolding && !live.robertShocking && !robertFleeingShock) {
            respondToPlayerShock()
        }
        val duel = _gameState.value.shockHolding && _gameState.value.robertShocking

        var robertLoss = 0
        var playerLoss = 0
        if (duel) {
            // A duel of colliding shocks: BOTH fighters lose 10% of their
            // maximum health every two seconds, applied as whole pulses.
            duelShockDamageMs += dtMs
            playerShockDamageMs = 0L
            val pulses = duelShockDamageMs / ElectricShockRules.DUEL_WINDOW_MS
            duelShockDamageMs %= ElectricShockRules.DUEL_WINDOW_MS
            if (pulses > 0) {
                // DUEL_PERCENT_PER_SECOND is derived from the window, so one
                // full window is exactly DUEL_PERCENT_PER_WINDOW of the bar.
                robertLoss = ElectricShockRules.damageFor(
                    live.robertMaxHp,
                    ElectricShockRules.DUEL_PERCENT_PER_SECOND,
                    pulses * ElectricShockRules.DUEL_WINDOW_MS
                ).roundToInt()
                playerLoss = ElectricShockRules.damageFor(
                    live.playerMaxHp,
                    ElectricShockRules.DUEL_PERCENT_PER_SECOND,
                    pulses * ElectricShockRules.DUEL_WINDOW_MS
                ).roundToInt()
            }
        } else {
            // Alone: the single-striker law, applied in whole one-second pulses.
            playerShockDamageMs += dtMs
            duelShockDamageMs = 0L
            val pulses = playerShockDamageMs / 1_000L
            playerShockDamageMs %= 1_000L
            if (pulses > 0) {
                val rate = ElectricShockRules.playerDrainPercentPerSecond(proximity)
                robertLoss = ElectricShockRules.damageFor(
                    live.robertMaxHp, rate, pulses * 1_000L
                ).roundToInt()
            }
        }
        // Electric shock cannot damage a fighter protected by a bulletproof car.
        if (live.robertInCar) robertLoss = 0
        if (CarRules.shieldsUser(live.carStage)) playerLoss = 0
        val newRobertHp = (live.robertHp - robertLoss).coerceAtLeast(0)
        val newPlayerHp = (live.playerHp - playerLoss).coerceAtLeast(0)
        val dealt = (live.robertHp - newRobertHp).coerceAtLeast(0)

        // Continuous close-band shock breaks a gloo wall after 15 seconds or
        // a cover after 7 seconds; leaving the band resets the accumulated time.
        playerShockBreakMs = if (!duel && ElectricShockRules.inBreakBand(proximity) &&
            shockTargetHasBarrier(live, enemySide = true)
        ) playerShockBreakMs + dtMs else 0L
        robertShockBreakMs = if (live.robertShocking && ElectricShockRules.inBreakBand(proximity) &&
            shockTargetHasBarrier(live, enemySide = false)
        ) robertShockBreakMs + dtMs else 0L
        val brokeEnemyBarrier = playerShockBreakMs > 0L &&
            ElectricShockRules.barrierBreakReady(
                playerShockBreakMs,
                isGlueWall = _gameState.value.robertGlueWallActive
            ) && breakBarrierWithShock(enemySide = true)
        val brokePlayerBarrier = robertShockBreakMs > 0L &&
            ElectricShockRules.barrierBreakReady(
                robertShockBreakMs,
                isGlueWall = _gameState.value.glueWallActive
            ) && breakBarrierWithShock(enemySide = false)
        if (brokeEnemyBarrier) playerShockBreakMs = 0L
        if (brokePlayerBarrier) robertShockBreakMs = 0L

        _gameState.update {
            it.copy(
                robertHp = newRobertHp,
                playerHp = newPlayerHp,
                shockDamageDealt = it.shockDamageDealt + dealt
            )
        }
        if (newRobertHp <= 0) {
            stopAllShocks(playRelease = true)
            endGame(won = true)
            return false
        }
        if (newPlayerHp <= 0) {
            stopAllShocks(playRelease = true)
            if (!live.playerSecondChanceUsed) {
                val revivedHp = Combat.secondChanceHealth(live.playerMaxHp)
                _gameState.update {
                    it.copy(playerHp = revivedHp, playerSecondChanceUsed = true)
                }
                msg("${t("playerSecondChance")} ${t("yourHealth")} $revivedHp.")
            } else {
                endGame(won = false)
            }
            return false
        }
        return true
    }

    private fun shockTargetHasBarrier(state: GameState, enemySide: Boolean): Boolean =
        if (enemySide) state.robertGlueWallActive || state.robertCoverActive
        else state.glueWallActive || state.coverActive

    /**
     * The shock breaks one barrier of [enemySide] (Robert's when true, the
     * player's when false): the glue wall first, then the cover. Returns true
     * when something actually broke.
     */
    private fun breakBarrierWithShock(enemySide: Boolean): Boolean {
        val state = _gameState.value
        if (enemySide) {
            if (state.robertGlueWallActive) {
                _gameState.update {
                    it.copy(robertGlueWallActive = false, robertGlueWallProgress = 0)
                }
                playSound("glueWallCrack")
                msg(t("glueWallShocked"))
                return true
            }
            if (state.robertCoverActive) {
                _gameState.update { it.copy(robertCoverActive = false, robertCoverHits = 0) }
                playSound("coverCrack")
                msg(t("coverShocked"))
                return true
            }
        } else {
            if (state.glueWallActive) {
                _gameState.update {
                    it.copy(glueWallActive = false, glueWallProgress = 0)
                }
                playSound("glueWallCrack")
                msg(t("glueWallShocked"))
                return true
            }
            if (state.coverActive) {
                _gameState.update { it.copy(coverActive = false, coverHits = 0) }
                playSound("coverCrack")
                msg(t("coverShocked"))
                return true
            }
        }
        return false
    }

    /**
     * Ends the player's shock. Releasing it plays the shock sound mixed with
     * the enemy hit sound. The 30 second cap ends it without a finger lift;
     * the user must lift and swipe again. Robert's counter stops with it.
     */
    private fun stopElectricShock(timedOut: Boolean = false, playRelease: Boolean = true) {
        if (timedOut) shockSpent = true
        val wasHolding = _gameState.value.shockHolding
        val wasLocked = shockDeployLocked
        shockDeployLocked = false
        if (wasHolding) {
            audioManager.stopWeaponLoop("magicalSound", AudioManager.LOOP_OWNER_PLAYER)
            _gameState.update { it.copy(shockHolding = false, electricShockInPlay = false) }
            if (playRelease && !wasLocked) {
                audioManager.playMixed(listOf("electricShock", "enemyHit"))
            }
        }
        if (robertShockIsCounter) stopRobertShock()
        val stopShockRetreat = wasHolding && robertFleeingShock
        robertFleeingShock = false
        if (stopShockRetreat && _currentScreen.value == Screen.BATTLE && _gameState.value.robertHp > 0) {
            robertSprintTicks = 0
            stepJob?.cancel()
            _gameState.update { it.copy(robertState = "advance", robertTurnBackTicks = 0) }
            planRobertStep(200L)
        }
        playerShockBreakMs = 0L
        playerShockDamageMs = 0L
        duelShockDamageMs = 0L
        if (wasHolding && !_gameState.value.robertShocking) {
            _gameState.update { it.copy(shockFirstActivator = "") }
        }
    }

    /** Ends Robert's shock and silences his hum. */
    private fun stopRobertShock() {
        robertShockIsCounter = false
        robertShockBreakMs = 0L
        duelShockDamageMs = 0L
        if (!_gameState.value.robertShocking) return
        audioManager.stopWeaponLoop("magicalSound", AudioManager.LOOP_OWNER_ENEMY)
        _gameState.update {
            it.copy(
                robertShocking = false,
                shockFirstActivator = if (it.shockHolding) it.shockFirstActivator else ""
            )
        }
    }

    /** Ends every shock at once (screen changes, pauses, the end of a battle). */
    private fun stopAllShocks(playRelease: Boolean = false) {
        stopElectricShock(playRelease = playRelease)
        stopRobertShock()
        playerShockBreakMs = 0L
        robertShockBreakMs = 0L
        shockSpent = false
        shockDeployLocked = false
        robertFleeingShock = false
        shockOutOfRangeAnnounced = false
        playerShockDamageMs = 0L
        duelShockDamageMs = 0L
        shockJob?.cancel()
        shockJob = null
        _gameState.update {
            it.copy(
                shockHolding = false,
                robertShocking = false,
                electricShockInPlay = false,
                shockFirstActivator = ""
            )
        }
    }

    /** True when a held up-swipe starts something: the shock or an automatic burst. */
    fun holdUpSwipeStarts(): Boolean {
        val state = _gameState.value
        // The door and refuel prompts own their own swipe-up action.
        if (CarRules.awaitsSwipeUp(state.carStage)) return false
        return state.activeWeapon.autoFire || state.activeWeapon.electricShock
    }

    /** True while the selected weapon is the electric shock. */
    fun activeWeaponIsElectricShock(): Boolean = _gameState.value.activeWeapon.electricShock

    fun handleGesture(direction: String, fingers: Int) {
        val action = GestureRouter.route(_currentScreen.value, direction, fingers)
        if (_currentScreen.value == Screen.BATTLE && battlePaused.value && action != GameAction.TOGGLE_PAUSE && action != GameAction.EXIT_BATTLE) return
        if (_currentScreen.value == Screen.BATTLE && handleCarGesture(direction, fingers)) return
        when (action) {
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
            // Right cycles speed faster; left cycles speed slower; down toggles pause; up exits.
            GameAction.SPEED_FASTER -> cycleRunSpeed(1)
            GameAction.SPEED_SLOWER -> cycleRunSpeed(-1)
            GameAction.TOGGLE_PAUSE -> toggleBattlePause()
            GameAction.NONE -> {}
        }
    }

    fun handleCenterTap() {
        if (battlePaused.value) return
        // Buttons and gestures set this marker; a tap immediately afterwards
        // belongs to that action and must not activate the menu as well.
        if (battleClock.nowMs() - lastExplicitActionAt < TAP_ACTION_DEBOUNCE_MS) return
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
        lastExplicitActionAt = battleClock.nowMs()
    }

    private fun stopTimers() {
        if (battleClock.paused.value) {
            // Do not restart paused effects when abandoning a battle.
            audioManager.stopAllSounds()
            battleClock.resume()
            audioManager.resumeAllSounds()
        }
        manuallyPaused = false
        playerMoveJob?.cancel()
        playerMoveJob = null
        // A screen change stops the car and every shock silently. An engine
        // that was running is off afterwards, so the car stage says so too.
        stopCarDriveJob()
        stopAllShocks(playRelease = false)
        // Leaving the battle lifts a running opponent lock as well, so the next
        // battle starts with a free Robert.
        lockJob?.cancel()
        lockJob = null
        audioManager.stopCarSounds()
        _gameState.update { current ->
            when (current.carStage) {
                CarStage.INSIDE_MOVING, CarStage.INSIDE_ENGINE_ON ->
                    current.copy(carStage = CarStage.INSIDE_ENGINE_OFF)
                else -> current
            }
        }
        stepJob?.cancel()
        stepJob = null
        robertFireJob?.cancel()
        robertFireJob = null
        missileJob?.cancel()
        missileJob = null
        missileApproachJob?.cancel()
        missileApproachJob = null
        missileImpactAtMs = 0L
        missileFlightDurationMs = 0L
        missileDeflectionUsed = false
        bombJob?.cancel()
        bombJob = null
        bombApproachJob?.cancel()
        bombApproachJob = null
        bombBlastAtMs = 0L
        bombFlightDurationMs = 0L
        bombTrackJob?.cancel()
        bombTrackJob = null
        autoFireSession++
        autoFireJob?.cancel()
        autoFireJob = null
        endingJob?.cancel()
        endingJob = null
        matchLimitJob?.cancel()
        matchLimitJob = null
        matchDeadlineMs = 0L
        robertSprintTicks = 0
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
        robertFireWeaponKey = null
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
