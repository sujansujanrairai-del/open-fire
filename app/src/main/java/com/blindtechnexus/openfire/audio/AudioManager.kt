package com.blindtechnexus.openfire.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.RunPressure
import com.blindtechnexus.openfire.model.SoundDurations
import com.blindtechnexus.openfire.model.SoundTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance, stable Audio Mixer / Player architecture — enhanced.
 *
 * Architecture (stabilized):
 * 1. SoundPool engine for ultra-low latency short SFX (gunshots, reloads, hits, clicks, gloo wall).
 *    Enhanced with retry queue, TTL, load-attempt cap and starvation guard — never drops a requested effect.
 * 2. Managed MediaPlayer Channel Pool for streamed, long audio clips (win, lose, parachute, airplane, music, walk,
 *    MP40 / machine-gun loops). Completely prevents SoundPool native buffer starvation, freezing and premature cutoffs.
 *    Every streamed track is generation-guarded and prepared off the UI thread, so a slow prepare never blocks gestures.
 * 3. 3D Positional Spatial Audio with realistic distance attenuation and azimuth stereo panning.
 * 4. Granular sound management: any sound or screen audio can be stopped instantly at any time.
 * 5. Stabilized mixer: synchronized player access, leak pruning, volume smoothing and rate adaptation ensure
 *    the mixer never glitches, even under rapid weapon swaps or continuous MP40 fire.
 */
class AudioManager(private val context: Context) {
    companion object {
        private const val TAG = "OpenFireAudio"
        val LONG_TRACK_KEYS = setOf(
            "win",
            "lose",
            "parachute",
            "airplane",
            "airplaneEngine",
            "music",
            "walk",
            "playerWalk",
            "enemyWalk",
            // The missile loop must be stoppable the instant it impacts,
            // and the explosion is a multi-second tail: both stream.
            "missile",
            "explosion",
            // Bombs: long flight sounds plus the looped timer countdown.
            "atomBomb",
            "grenade",
            "timerLoop",
            "timerBomb",
            // Progression stings; the round sting alone is 28 seconds long.
            "levelComplete",
            "roundComplete",
            // Automatic weapons loop on their own dedicated, pre-warmed
            // channel (see [AUTO_LOOP_KEYS]) so a held burst is truly
            // gapless. Their one-shot recordings stream like the missile.
            "mp40",
            "machineGun",
            "mp40Shot",
            "machineGunShot"
        )

        /**
         * The automatic weapons whose firing sound is a real, gapless loop.
         *
         * Each of them owns one permanent, pre-prepared looping MediaPlayer:
         * holding the trigger starts it at once (no prepare delay, so no
         * silence before the first shot) and lifting the finger pauses it in
         * the same millisecond. Pausing instead of releasing is what removes
         * the silence that used to appear between two repeats: the next hold
         * resumes the already decoded loop instantly.
         */
        val AUTO_LOOP_KEYS: Set<String> = setOf("mp40", "machineGun")

        /**
         * The one-shot recording of an automatic weapon. It is the very same
         * MP3 as the loop, but it plays as a single shot so an enemy answer
         * can never cut - or restart - the loop the player is holding.
         */
        val AUTO_LOOP_SHOT_KEYS: Map<String, String> = mapOf(
            "mp40" to "mp40Shot",
            "machineGun" to "machineGunShot"
        )

        /**
         * Every key that is streamed instead of decoded into the SoundPool.
         * SoundPool refuses samples above roughly one megabyte of decoded PCM,
         * which is exactly why the long tracks live here: putting them in the
         * pool is what used to starve the native buffers and freeze playback.
         */
        val STREAMED_KEYS: Set<String> = LONG_TRACK_KEYS

        /** Pending play requests older than this are dropped (stale intent). */
        private const val PENDING_PLAY_TTL_MS = 5_000L

        /** Max queued play requests waiting for their sound to finish loading. */
        private const val MAX_PENDING_PLAYS = 32

        /** Max load attempts (asset retry + synthetic fallback) per sound key. */
        private const val MAX_LOAD_ATTEMPTS = 3

        /**
         * Every sound that belongs to a weapon being fired or swung. Only one
         * of them may ever sound at a time: the moment a new weapon sound
         * starts, the previous one is cut, so the player's AWM shot and
         * Robert's answering gunshot never overlap and never leak.
         */
        val WEAPON_SOUND_KEYS: Set<String> = setOf(
            "assault",
            "pistol",
            "shortGun",
            "sniperRifle",
            "awm",
            "mp40",
            "machineGun",
            // Single-shot copies of the two automatic weapons. The looping
            // hold channel ([AUTO_LOOP_KEYS]) is deliberately NOT part of the
            // exclusive set any more: a held burst must never be cut by the
            // enemy's answering shot, otherwise the firing goes silent
            // between two bullets.
            "mp40Shot",
            "machineGunShot",
            "knifeSwish",
            "knifeMiss",
            "swordGlide",
            "swordMiss"
        )

        /** Footsteps are ducked for this long while a weapon sounds. */
        private const val WALK_DUCK_MS = 450L

        /** Footstep gain multiplier during that window. */
        const val WALK_DUCK_FACTOR = 0.25f

        /** Delay before a refused stream is retried once. */
        private const val STREAM_RETRY_DELAY_MS = 90L

        /** How many concurrent streamed tracks are allowed before one is dropped. */
        private const val MAX_MANAGED_PLAYERS = 12

        /**
         * Measured length of the sounds that drive a game timer. Callers use it
         * to wait for a blast, a sting or a countdown to really finish.
         */
        val SOUND_DURATIONS_MS: Map<String, Long> = mapOf(
            "missile" to SoundDurations.MISSILE_MS,
            "explosion" to SoundDurations.EXPLOSION_MS,
            "timerBomb" to SoundDurations.EXPLOSION_MS,
            "atomBomb" to SoundDurations.ATOM_BOMB_MS,
            "grenade" to SoundDurations.GRENADE_MS,
            "timerLoop" to SoundDurations.TIMER_LOOP_MS,
            "mp40" to SoundDurations.MP40_MS,
            "machineGun" to SoundDurations.MACHINE_GUN_MS,
            "awm" to SoundDurations.AWM_MS,
            "levelComplete" to SoundDurations.LEVEL_COMPLETE_MS,
            "roundComplete" to SoundDurations.ROUND_COMPLETE_MS,
            "wall" to SoundDurations.WALL_MS,
            "walk" to SoundDurations.WALK_MS
        )
    }

    /**
     * One supervisor scope for every background audio task. A failing load,
     * prepare or retry can never cancel the others, and the whole scope is
     * cancelled on [release] so nothing keeps running after the game closes.
     */
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serializes the shared weapon channel so rapid taps cannot race into overlapping gun streams. */
    private val weaponAudioLock = Any()

    /** True once [release] ran: every later request is ignored. */
    @Volatile
    private var released = false

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(48)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // SoundPool sound map and active streams
    private val soundMap = ConcurrentHashMap<String, Int>()
    private val loadedSounds = ConcurrentHashMap.newKeySet<Int>()
    private val activeSfxStreams = ConcurrentHashMap<String, MutableSet<Int>>()

    // Reliable playback pipeline: map every sample id back to its key, count
    // load attempts for retries, and queue play requests that arrive while a
    // sound is still loading so no sound is ever silently dropped.
    private val sampleToKey = ConcurrentHashMap<Int, String>()
    private val loadAttempts = ConcurrentHashMap<String, Int>()
    private val pendingLock = Any()
    private val pendingPlays = ArrayDeque<PendingPlay>()

    private data class PendingPlay(
        val key: String,
        val volume: Float,
        val rate: Float,
        val posX: Float,
        val posY: Float,
        val posZ: Float,
        val loop: Boolean,
        val enqueuedAt: Long
    )

    // Managed MediaPlayers for long / streaming audio
    private val managedPlayers = ConcurrentHashMap<String, MediaPlayer>()
    private val playerLock = Any()

    /**
     * Reliability bookkeeping for the streamed tracks. Every start request
     * owns a generation token, so a slow `prepare()` that finishes after a
     * newer request (or after a stop) is discarded instead of playing late or
     * leaking a player. Keys currently being prepared are tracked separately,
     * which is what stops the same track from being started twice in a row.
     */
    private val playerGeneration = ConcurrentHashMap<String, Int>()
    private val preparingTracks = ConcurrentHashMap.newKeySet<String>()
    private val trackGains = ConcurrentHashMap<String, Pair<Float, Float>>()

    /**
     * The permanent, pre-prepared looping players of the two automatic
     * weapons ([AUTO_LOOP_KEYS]). Unlike [managedPlayers] they are never
     * released between bursts: they stay prepared and paused, which is what
     * makes a restart instantaneous and therefore gapless.
     */
    private val weaponLoopPlayers = ConcurrentHashMap<String, MediaPlayer>()
    private val weaponLoopLock = Any()

    /** Keys whose loop is currently wanted (the trigger is down). */
    private val activeWeaponLoops = ConcurrentHashMap.newKeySet<String>()

    /** Keys whose loop player is still being prepared in the background. */
    private val preparingWeaponLoops = ConcurrentHashMap.newKeySet<String>()

    /** Launch / start of the loop on the pre-warmed channel. */
    @Volatile
    private var weaponLoopPrewarmStarted = false

    /**
     * User settings. These are the single source of truth and are NEVER
     * overwritten by a screen-scaled playback value — that was the cause of
     * the music volume reverting to the previous lower level.
     */
    var sfxVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.clamp(value)
        }

    var musicVolume: Float = 0.5f
        set(value) {
            field = VolumeMixer.clamp(value)
            applyMusicVolume()
        }

    /**
     * Walking footsteps play at the same level as the speech (TTS) volume,
     * so approaching and receding movement is always as clear as the voice
     * the player chose. Only the 3D distance gain is applied on top.
     */
    var walkVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.clamp(value)
        }

    var soundTheme: SoundTheme = SoundTheme.TACTICAL

    /**
     * Haptics belong to the menus only. While an actual game screen is being
     * played this stays false, so the battle is pure sound and speech: no
     * vibration at all, however many shots, hits and explosions happen.
     */
    var hapticsEnabled: Boolean = false

    /**
     * True while the battle screen is open. It is the hard switch that mutes
     * every vibration during play, independent of the settings toggle.
     */
    @Volatile
    var battleActive: Boolean = false

    /** Moment until which footsteps stay ducked under a weapon sound. */
    private var walkDuckUntilMs: Long = 0L

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Canonical sound keys mapped to their asset files.
     *
     * Every entry points at a file that really exists in `assets/sounds`, and
     * every key is reused consistently across the whole codebase:
     *
     *  - a weapon hit plays the weapon's own hit sound (knife-hit, sword-hit
     *    or gun-hit) mixed with the sound of the fighter that was hit
     *    (enemy-hit or player-hit);
     *  - a knife or a sword that misses plays knife-miss or sword-miss;
     *  - the glue wall plays glue-sound when it is raised, glue-hit when it
     *    is hit and glue-wall-crack when it breaks;
     *  - the cover plays cover when it is raised, cover-hit when it is hit
     *    and cover-crack when it breaks;
     *  - the airplane menu mixes airplane with airplane-engine.
     */
    private val soundAliases = mapOf(
        "click" to listOf("click.mp3"),
        "gunReload" to listOf("gun-reload.mp3"),
        // Firearms. Every alias is used by a real weapon: the decorative
        // "fire", "shotgun" and "reload" duplicates were removed so no stray
        // gunshot can be triggered by an unused key.
        "assault" to listOf("assault-sound.mp3"),
        "pistol" to listOf("pistol-sound.mp3"),
        "shortGun" to listOf("short-gun.mp3"),
        "sniperRifle" to listOf("sniper-rifle.mp3"),
        // Round two weapons: the MP40 submachine gun and the AWM long-range
        // rifle, each with its own recorded shot.
        "mp40" to listOf("mp-40-gun.mp3"),
        // One-shot copies of the two automatic weapons: the same recordings,
        // played as single shots on the exclusive channel.
        "mp40Shot" to listOf("mp-40-gun.mp3"),
        "machineGunShot" to listOf("machine-gun.mp3"),
        "awm" to listOf("AWM-sound.mp3"),
        // Round four weapon: the machine gun, looped during a burst exactly
        // like the MP40.
        "machineGun" to listOf("machine-gun.mp3"),
        // Melee weapons.
        "knifeHit" to listOf("knife-hit.mp3"),
        "knifeMiss" to listOf("knife-miss.mp3"),
        "knifeSwitch" to listOf("knife-switch.mp3"),
        "knifeSwish" to listOf("knife-switch.mp3"),
        "swordHit" to listOf("sword-hit.mp3"),
        "swordMiss" to listOf("sword-miss.mp3"),
        // There is no sword-glide asset: the sword swing uses the same whoosh
        // as the sword switch, exactly like the knife uses knife-switch.
        "swordGlide" to listOf("sword-switch.mp3"),
        "swordSwitch" to listOf("sword-switch.mp3"),
        // Barriers and defences.
        "glueSound" to listOf("glue-sound.mp3"),
        "glueHit" to listOf("glue-hit.mp3"),
        "glueWallCrack" to listOf("glue-wall-crack.mp3"),
        // Hitting one of the four boundaries of the 100 m zone: the dedicated
        // wall impact sound. It belongs to the player alone — only a manually
        // controlled fighter can ever run into a boundary, because Robert
        // detects the wall before he reaches it and changes heading instead.
        "wall" to listOf("wall-sound.mp3"),
        "cover" to listOf("cover.mp3"),
        "coverHit" to listOf("cover-hit.mp3"),
        "coverCrack" to listOf("cover-crack.mp3"),
        "medkit" to listOf("medkit.mp3"),
        // Impacts: the weapon sound plus the fighter that was hit.
        "gunHit" to listOf("gun-hit.mp3"),
        "enemyHit" to listOf("enemy-hit.mp3"),
        "playerHit" to listOf("player-hit.mp3"),
        "ammo" to listOf("ammo.mp3"),
        "spend" to listOf("spend-sound.mp3"),
        // Flight and results.
        "jump" to listOf("jump.mp3"),
        "land" to listOf("jump.mp3"),
        "airplane" to listOf("airplane.mp3"),
        "airplaneEngine" to listOf("airplane-engine.mp3"),
        "parachute" to listOf("parachute.mp3"),
        "win" to listOf("win.mp3"),
        "lose" to listOf("lose.mp3"),
        "music" to listOf("music.mp3"),
        "walk" to listOf("walk.mp3"),
        // Two independent players mix the same footstep asset: the local
        // player stays centred while Robert is attenuated by his 3D distance.
        "playerWalk" to listOf("walk.mp3"),
        "enemyWalk" to listOf("walk.mp3"),
        // Guided missile: the launch flight sound (looped while the target
        // flees) and the impact explosion, always mixed with enemy-hit or
        // player-hit for the destroyed fighter.
        "missile" to listOf("missile-sound.mp3"),
        "explosion" to listOf("explosion-sound.mp3"),
        // Bombs: each bomb flies for exactly as long as its own sound, and
        // every blast - a hit, an intercepted bomb or a disposed one - uses
        // the explosion. The timer bomb counts down with a looped timer sound
        // and then explodes with the same explosion sound.
        "atomBomb" to listOf("atom-bom-sound.mp3"),
        "grenade" to listOf("grenade-sound.mp3"),
        "timerLoop" to listOf("timer-sound.mp3"),
        "timerBomb" to listOf("explosion-sound.mp3"),
        // Progression stings: one per completed level, one per completed round.
        "levelComplete" to listOf("level-complete.mp3"),
        "roundComplete" to listOf("round-complete.mp3")
    )

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            val key = sampleToKey[sampleId]
            if (status == 0) {
                loadedSounds.add(sampleId)
                if (key != null) {
                    flushPendingPlays(key)
                }
            } else if (key != null) {
                handleLoadFailure(key, sampleId)
            } else {
                loadedSounds.remove(sampleId)
            }
        }
        preloadShortSounds()
        // The two automatic weapons are prepared once, up front, so the very
        // first hold of the trigger already has an instant, gapless loop.
        prewarmWeaponLoops()
    }

    private fun preloadShortSounds() {
        audioScope.launch {
            for ((key, files) in soundAliases) {
                // Preload only short SFX into SoundPool. Long audio tracks are streamed via MediaPlayer on-demand.
                if (key in LONG_TRACK_KEYS) continue
                loadShortSound(key, files, allowSynthetic = true)
            }
        }
    }

    /**
     * Loads one short SFX into the SoundPool from the first asset file that
     * opens, optionally falling back to a procedurally generated WAV so the
     * key is always playable.
     */
    private fun loadShortSound(key: String, files: List<String>, allowSynthetic: Boolean) {
        for (fileName in files) {
            try {
                val afd = context.assets.openFd("sounds/$fileName")
                val soundId = soundPool.load(afd, 1)
                if (soundId != 0) {
                    soundMap[key] = soundId
                    sampleToKey[soundId] = key
                    return
                }
            } catch (_: Exception) {}
        }
        if (allowSynthetic) {
            try {
                val synthFile = generateSyntheticSoundFile(key)
                if (synthFile != null && synthFile.exists()) {
                    val soundId = soundPool.load(synthFile.absolutePath, 1)
                    if (soundId != 0) {
                        soundMap[key] = soundId
                        sampleToKey[soundId] = key
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating fallback for $key", e)
            }
        }
    }

    /**
     * A sample failed to decode/load inside SoundPool. Retry once from the
     * asset files, then once from the synthetic generator, then give up —
     * this guarantees sounds like reload/money-spend keep playing even if a
     * single decode hiccups, instead of failing silently forever.
     */
    private fun handleLoadFailure(key: String, failedSampleId: Int) {
        loadedSounds.remove(failedSampleId)
        soundMap.remove(key, failedSampleId)
        val attempts = loadAttempts.merge(key, 1) { old, one -> old + one } ?: 1
        if (attempts >= MAX_LOAD_ATTEMPTS) {
            Log.e(TAG, "Giving up loading sound '$key' after $attempts attempts")
            return
        }
        val files = soundAliases[key] ?: emptyList()
        audioScope.launch {
            if (released) return@launch
            if (attempts <= 1) {
                loadShortSound(key, files, allowSynthetic = false)
            } else {
                loadShortSound(key, files, allowSynthetic = true)
            }
        }
    }

    /**
     * Kicks off an on-demand load when a sound is requested before the
     * preload pass has mapped it (or after a failed load was cleared).
     */
    private fun maybeLoadOnDemand(key: String) {
        if (key in LONG_TRACK_KEYS) return
        if (soundMap.containsKey(key)) return
        val files = soundAliases[key] ?: return
        val attempts = loadAttempts[key] ?: 0
        if (attempts >= MAX_LOAD_ATTEMPTS) return
        audioScope.launch {
            if (released) return@launch
            if (!soundMap.containsKey(key)) {
                loadShortSound(key, files, allowSynthetic = true)
            }
        }
    }

    private fun queuePendingPlay(pending: PendingPlay) {
        synchronized(pendingLock) {
            val now = System.currentTimeMillis()
            pendingPlays.removeAll { now - it.enqueuedAt > PENDING_PLAY_TTL_MS }
            if (pendingPlays.size >= MAX_PENDING_PLAYS) {
                pendingPlays.removeFirstOrNull()
            }
            pendingPlays.addLast(pending)
        }
    }

    /** Replays (as a simultaneous mix) every queued request for [key]. */
    private fun flushPendingPlays(key: String) {
        val now = System.currentTimeMillis()
        val ready = synchronized(pendingLock) {
            val matched = pendingPlays.filter { it.key == key }
            pendingPlays.removeAll(matched.toSet())
            matched
        }
        for (pending in ready) {
            if (now - pending.enqueuedAt > PENDING_PLAY_TTL_MS) continue
            if (pending.loop) {
                playLoop(pending.key, pending.volume, pending.rate, pending.posX, pending.posY, pending.posZ)
            } else {
                playSound(pending.key, pending.volume, pending.rate, pending.posX, pending.posY, pending.posZ)
            }
        }
    }

    private fun dropPendingPlays(key: String) {
        synchronized(pendingLock) {
            pendingPlays.removeAll { it.key == key }
        }
    }

    private fun isReady(key: String): Boolean {
        val soundId = soundMap[key] ?: return false
        return soundId in loadedSounds
    }

    private fun getAssetFd(key: String): AssetFileDescriptor? {
        val files = soundAliases[key] ?: return null
        for (fileName in files) {
            try {
                return context.assets.openFd("sounds/$fileName")
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * 3D spatial panning & distance attenuation.
     * x: -100 (far left) to +100 (far right)
     * z: distance away along line of sight (devil approaches from 100 to 0)
     *
     * The player and Robert fight head-on: any sound with no real sideways
     * offset is centred exactly (full volume in both ears, like the speech
     * voice), so running straight forward or backward never smears the
     * image between the channels. Only a genuine sideways offset pans,
     * and even then gently.
     */
    fun calculate3dVolume(
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 0f,
        baseVol: Float = 1.0f
    ): Pair<Float, Float> {
        val dist = sqrt(x * x + y * y + z * z)
        // Smooth inverse distance attenuation
        val distGain = 1.0f / (1.0f + 0.022f * max(0f, dist - 5f))
        val clampedDistGain = distGain.coerceIn(0.10f, 1.0f)

        val (leftGain, rightGain) = VolumeMixer.panGains(VolumeMixer.panFor(x, dist))

        val totalVol = VolumeMixer.sfx(sfxVolume, baseVol, clampedDistGain)
        val left = (totalVol * leftGain).coerceIn(0f, 1f)
        val right = (totalVol * rightGain).coerceIn(0f, 1f)
        return Pair(left, right)
    }

    /**
     * Plays a sound effect with optional 3D spatial coordinates.
     * Routes short sounds through SoundPool and long sounds through managed MediaPlayer.
     *
     * If the underlying sample has not finished loading yet, the request is
     * queued and played automatically the moment the sound is ready, so a
     * requested effect is never silently dropped.
     */
    fun playSound(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ): Int {
        if (released) return -1
        // A single shot of an automatic weapon is played from its own
        // one-shot key, never from the looping hold channel: firing one
        // bullet must never cut or restart the burst the player is holding.
        val playKey = AUTO_LOOP_SHOT_KEYS[key] ?: key
        // One weapon channel: starting any gunshot cuts every other weapon
        // sound (and any earlier tail of the same gun) before this one is
        // allowed to start. Queued weapon requests are dropped as well, so a
        // slowly loading AWM can never leak into Robert's answering shot.
        enforceWeaponExclusivity(playKey)
        if (playKey in LONG_TRACK_KEYS) {
            playManagedTrack(playKey, volume, posX, posY, posZ, loop = false, rate = rate)
            return 1
        }

        if (!isReady(playKey)) {
            queuePendingPlay(PendingPlay(playKey, volume, rate, posX, posY, posZ, loop = false, enqueuedAt = System.currentTimeMillis()))
            maybeLoadOnDemand(playKey)
            return -1
        }

        val soundId = soundMap[playKey] ?: return -1
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume)
        val pitch = when (soundTheme) {
            SoundTheme.CYBER -> rate * 1.10f
            SoundTheme.STEALTH -> rate * 0.92f
            else -> rate
        }.coerceIn(0.5f, 2.0f)

        val streamId = try {
            synchronized(soundPool) { soundPool.play(soundId, left, right, 1, 0, pitch) }
        } catch (_: Exception) { 0 }
        if (streamId != 0) {
            activeSfxStreams.getOrPut(playKey) { ConcurrentHashMap.newKeySet() }.add(streamId)
        } else {
            // The pool could not hand out a stream right now. Queue the
            // request and schedule one short retry, so a busy pool never
            // swallows a gunshot, a click or a hit sound.
            queuePendingPlay(
                PendingPlay(
                    playKey, volume, rate, posX, posY, posZ,
                    loop = false,
                    enqueuedAt = System.currentTimeMillis()
                )
            )
            scheduleRetry(playKey)
            return -1
        }
        triggerHapticForSound(playKey)
        return streamId
    }

    /**
     * Retries the queued requests for [key] once, a beat later. This is what
     * keeps sounds alive on low-end devices where the pool is momentarily out
     * of free streams instead of dropping them silently.
     */
    private fun scheduleRetry(key: String) {
        audioScope.launch {
            delay(STREAM_RETRY_DELAY_MS)
            if (released) return@launch
            if (isReady(key)) flushPendingPlays(key)
        }
    }

    /**
     * Measured length of a streamed sound in milliseconds, or 0 when the key
     * has no known length. The battle engine uses it to wait for a blast or a
     * progression sting to really finish before it continues.
     */
    fun soundDurationMs(key: String): Long {
        val known = SOUND_DURATIONS_MS[key]
        if (known != null && known > 0L) return known
        val player = managedPlayers[key] ?: return 0L
        return try {
            player.duration.toLong().coerceAtLeast(0L)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Plays several sound effects at the same time so they are mixed together
     * (e.g. the coin "money spent" sound together with the reload sound).
     */
    fun playMixed(keys: List<String>, volume: Float = 1.0f, posX: Float = 0f, posY: Float = 0f, posZ: Float = 0f) {
        for (key in keys) {
            playSound(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
        }
    }

    /**
     * Plays a looping sound effect — stabilized.
     *
     * Looped weapons (MP40, machine gun) are now streamed via MediaPlayer so the loop
     * starts instantly, runs without SoundPool buffer starvation, and stops deterministically
     * when the trigger is released. Short loops still use SoundPool but are guarded with
     * retry and synchronization to guarantee they never leak.
     */
    fun playLoop(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        if (released) return
        // The two automatic weapons own a dedicated, gapless channel.
        if (key in AUTO_LOOP_KEYS) {
            startWeaponLoop(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
            return
        }
        // A fresh weapon loop takes the exclusive weapon channel. An already
        // running loop only refreshes its volume, so a held burst never
        // restarts itself or leaks a second copy of the same gun.
        if (key in WEAPON_SOUND_KEYS && !isTrackActive(key)) {
            enforceWeaponExclusivity(key)
        }
        if (key in LONG_TRACK_KEYS) {
            // Ensure any previous instance is fully released before re-looping; this is what
            // makes the MP40 loop stable across rapid hold/release cycles without overlapping.
            if (isTrackActive(key)) {
                // Already looping correctly — just refresh volume/rate instead of restarting.
                val player = managedPlayers[key]
                if (player != null) {
                    try {
                        val (l, r) = if (key == "music") Pair(VolumeMixer.music(musicVolume), VolumeMixer.music(musicVolume))
                        else calculate3dVolume(posX, posY, posZ, volume)
                        player.setVolume(l.coerceIn(0f, 1f), r.coerceIn(0f, 1f))
                        if (key == "enemyWalk" || key == "playerWalk" || key == "walk") {
                            // walking loops manage rate separately
                        } else {
                            try { player.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2.0f)) } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    return
                }
            }
            playManagedTrack(key, volume, posX, posY, posZ, loop = true)
            return
        }

        if (!isReady(key)) {
            queuePendingPlay(PendingPlay(key, volume, rate, posX, posY, posZ, loop = true, enqueuedAt = System.currentTimeMillis()))
            maybeLoadOnDemand(key)
            return
        }

        val soundId = soundMap[key] ?: return
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume)
        val pitch = rate.coerceIn(0.5f, 2.0f)
        val streamId = try {
            synchronized(soundPool) { soundPool.play(soundId, left, right, 1, -1, pitch) }
        } catch (_: Exception) { 0 }
        if (streamId != 0) {
            activeSfxStreams.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(streamId)
        } else {
            queuePendingPlay(PendingPlay(key, volume, rate, posX, posY, posZ, loop = true, enqueuedAt = System.currentTimeMillis()))
            scheduleRetry(key)
        }
    }

    /**
     * Plays one weapon sound exclusively: every other weapon sound that is
     * still playing (a looping submachine gun, a long AWM tail, the other
     * fighter's gunshot) is stopped first, so weapon sounds never mix and
     * never leak. Footsteps are ducked under it for a moment as well, which
     * keeps a running enemy from bleeding into the gunfire.
     */
    fun playWeaponSound(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        // Serialize rapid cross-fighter taps; without this, two callers could
        // both clear the weapon set and then start different streams together.
        synchronized(weaponAudioLock) {
            playSound(key, volume = volume, rate = rate, posX = posX, posY = posY, posZ = posZ)
        }
    }

    /** The looping variant of [playWeaponSound] (held submachine gun bursts). */
    fun playWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        synchronized(weaponAudioLock) {
            startWeaponLoop(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
        }
    }

    /**
     * Starts (or resumes) the gapless firing loop of an automatic weapon.
     *
     * The whole point of this channel is that no silence can ever appear
     * between two repeats of the gun sound:
     *
     *  - the looping player is prepared once, ahead of time, on the audio
     *    thread and stays prepared for the whole session;
     *  - the very first hold of the trigger starts it immediately (and, when
     *    the preparation is still running, the moment preparation finishes -
     *    never one bullet later);
     *  - every later hold simply resumes the paused player, which is an
     *    instantaneous operation on an already decoded stream;
     *  - a request while it is already playing only refreshes the volume, so
     *    the loop is never restarted and never doubles up.
     */
    fun startWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        if (released) return
        if (key !in AUTO_LOOP_KEYS) {
            playLoop(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
            return
        }
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume)
        trackGains[key] = Pair(left, right)
        activeWeaponLoops.add(key)
        // Footsteps step aside under the burst, exactly like every other
        // weapon sound, without ever stopping the gun itself. The duck window
        // is renewed on every bullet by [refreshWeaponLoop], so a long hold
        // never lets a footstep climb back over the gunfire.
        walkDuckUntilMs = System.currentTimeMillis() + WALK_DUCK_MS
        duckActiveFootsteps()
        val existing = synchronized(weaponLoopLock) { weaponLoopPlayers[key] }
        if (existing != null) {
            try {
                existing.setVolume(left, right)
                if (!existing.isPlaying) existing.start()
                return
            } catch (_: Exception) {
                // A dead player is dropped and rebuilt below.
                synchronized(weaponLoopLock) {
                    if (weaponLoopPlayers[key] === existing) weaponLoopPlayers.remove(key)
                }
                safeRelease(existing)
            }
        }
        if (preparingWeaponLoops.contains(key)) return
        buildWeaponLoopPlayer(key, left, right)
    }

    /**
     * Stops the firing loop the instant the trigger is released. The player
     * is only paused, never released, so the next hold resumes with zero
     * delay and zero silence.
     */
    fun stopWeaponLoop(key: String) {
        if (key !in AUTO_LOOP_KEYS) {
            stopSound(key)
            return
        }
        activeWeaponLoops.remove(key)
        val player = synchronized(weaponLoopLock) { weaponLoopPlayers[key] } ?: return
        try {
            if (player.isPlaying) player.pause()
        } catch (_: Exception) {
            synchronized(weaponLoopLock) {
                if (weaponLoopPlayers[key] === player) weaponLoopPlayers.remove(key)
            }
            safeRelease(player)
        }
    }

    /** Stops both automatic weapon loops at once. */
    fun stopWeaponLoops() {
        for (key in AUTO_LOOP_KEYS) stopWeaponLoop(key)
    }

    /**
     * True while the loop of [key] is really running (or is being prepared
     * with the trigger still down). The battle engine uses it as a watchdog
     * so a held burst can never be left silent.
     */
    fun isWeaponLoopActive(key: String): Boolean {
        if (key !in AUTO_LOOP_KEYS) return isTrackActive(key)
        if (key in preparingWeaponLoops && key in activeWeaponLoops) return true
        val player = synchronized(weaponLoopLock) { weaponLoopPlayers[key] } ?: return false
        return try {
            player.isPlaying
        } catch (_: Exception) {
            false
        }
    }

    /** Live volume of a running loop (the distance to the enemy can change). */
    fun refreshWeaponLoop(key: String, volume: Float = 1.0f, posZ: Float = 0f) {
        if (key !in AUTO_LOOP_KEYS) return
        val (left, right) = calculate3dVolume(0f, 0f, posZ, volume)
        trackGains[key] = Pair(left, right)
        // Keep the footsteps under the gun for the whole hold.
        walkDuckUntilMs = System.currentTimeMillis() + WALK_DUCK_MS
        val player = synchronized(weaponLoopLock) { weaponLoopPlayers[key] } ?: return
        try {
            player.setVolume(left.coerceIn(0f, 1f), right.coerceIn(0f, 1f))
        } catch (_: Exception) {}
    }

    /**
     * Prepares the permanent looping player of one automatic weapon. The
     * prepared stream is kept, paused, for the whole session.
     */
    private fun buildWeaponLoopPlayer(key: String, left: Float, right: Float) {
        preparingWeaponLoops.add(key)
        audioScope.launch {
            var created: MediaPlayer? = null
            try {
                val player = MediaPlayer()
                created = player
                val afd = getAssetFd(key)
                if (afd != null) {
                    player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    try {
                        afd.close()
                    } catch (_: Exception) {}
                } else {
                    val synth = generateSyntheticSoundFile(key)
                    if (synth == null) return@launch
                    player.setDataSource(synth.absolutePath)
                }
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                player.isLooping = true
                player.setVolume(left.coerceIn(0f, 1f), right.coerceIn(0f, 1f))
                player.setOnErrorListener { failed, what, extra ->
                    Log.w(TAG, "Weapon loop $key failed ($what/$extra)")
                    synchronized(weaponLoopLock) {
                        if (weaponLoopPlayers[key] === failed) weaponLoopPlayers.remove(key)
                    }
                    safeRelease(failed)
                    preparingWeaponLoops.remove(key)
                    true
                }
                player.prepare()
                synchronized(weaponLoopLock) {
                    if (released || weaponLoopPlayers.containsKey(key)) {
                        safeRelease(player)
                        return@launch
                    }
                    weaponLoopPlayers[key] = player
                }
                // The trigger may still be down (or the player was pre-warmed
                // and simply waits, paused, for the next hold).
                if (key in activeWeaponLoops && !released) player.start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed preparing weapon loop $key", e)
                created?.let { safeRelease(it) }
                synchronized(weaponLoopLock) {
                    if (weaponLoopPlayers[key] === created) weaponLoopPlayers.remove(key)
                }
            } finally {
                preparingWeaponLoops.remove(key)
                // A release that arrived during the preparation is honoured
                // here, once the player exists.
                if (key !in activeWeaponLoops) stopWeaponLoop(key)
            }
        }
    }

    /** Drops the two permanent loop players (full shutdown only). */
    private fun releaseWeaponLoops() {
        activeWeaponLoops.clear()
        for (key in weaponLoopPlayers.keys.toList()) {
            val player = synchronized(weaponLoopLock) { weaponLoopPlayers.remove(key) }
            player?.let { safeRelease(it) }
        }
    }

    /** Warms both automatic weapon loops right after the first load pass. */
    private fun prewarmWeaponLoops() {
        if (weaponLoopPrewarmStarted) return
        weaponLoopPrewarmStarted = true
        for (key in AUTO_LOOP_KEYS) {
            val (left, right) = calculate3dVolume(0f, 0f, 0f, 1.0f)
            buildWeaponLoopPlayer(key, left, right)
        }
    }

    /** Cuts every weapon sound that is still playing, loops included. */
    fun stopWeaponSounds() {
        for (weaponKey in WEAPON_SOUND_KEYS) {
            stopSound(weaponKey)
        }
    }

    /**
     * Makes weapon playback genuinely exclusive and leak free.
     *
     * Every active streamed track, SoundPool stream *and queued request* of
     * every weapon key is stopped - including the key that is about to start,
     * so a second shot of the same gun cuts its own previous tail. Walk loops
     * are then ducked immediately (and stay ducked for [WALK_DUCK_MS]), so a
     * running fighter can never bleed into a gunshot. Footsteps themselves
     * are never in this set, so walking never stops a weapon sound.
     */
    private fun enforceWeaponExclusivity(key: String) {
        if (released || key !in WEAPON_SOUND_KEYS) return
        for (weaponKey in WEAPON_SOUND_KEYS) {
            // A held automatic burst is never cut by another shot: silencing
            // it here is exactly what used to leave a silent gap between two
            // repeats of the MP40 or the machine gun.
            if (weaponKey in AUTO_LOOP_KEYS) continue
            stopSound(weaponKey)
        }
        walkDuckUntilMs = System.currentTimeMillis() + WALK_DUCK_MS
        duckActiveFootsteps()
    }

    /** Lowers live footstep tracks the instant a weapon sound starts. */
    private fun duckActiveFootsteps() {
        for (key in listOf("walk", "playerWalk", "enemyWalk")) {
            val player = managedPlayers[key] ?: continue
            val gains = walkCurrentGains[key] ?: continue
            try {
                player.setVolume(
                    (gains.first * WALK_DUCK_FACTOR).coerceIn(0f, 1f),
                    (gains.second * WALK_DUCK_FACTOR).coerceIn(0f, 1f)
                )
            } catch (_: Exception) {
            }
        }
    }

    /** Current footstep ducking multiplier. */
    private fun walkDuckFactor(): Float =
        if (System.currentTimeMillis() < walkDuckUntilMs) WALK_DUCK_FACTOR else 1f

    /**
     * Dedicated stable start/stop for the MP40 submachine gun loop.
     * The MP40 loops until [stopMp40Loop] is called on trigger release — no leakage, no overlap.
     */
    fun startMp40Loop(volume: Float = 1.0f, posZ: Float = 0f) {
        playWeaponLoop("mp40", volume = volume, posZ = posZ)
    }
    fun stopMp40Loop() {
        stopSound("mp40")
        stopSound("machineGun")
    }

    /**
     * Live footstep mix state: when each track last refreshed its volume
     * (every 0.5 s, or every 0.25 s while running fast) and the gains it
     * currently plays at, so approach / retreat swells stay smooth instead
     * of stepping harshly on every movement tick.
     */
    private val walkLastApplyAt = ConcurrentHashMap<String, Long>()
    private val walkCurrentGains = ConcurrentHashMap<String, Pair<Float, Float>>()

    /**
     * Updates only Robert's positional walking loop. His footsteps sit at
     * the speech level, attenuated once by his real distance: louder as he
     * approaches, quieter as he retreats, centred while he runs straight
     * and panned only by a genuine sideways offset. The playback rate
     * follows his movement speed in metres per second.
     */
    fun updateEnemyWalk3dAudio(
        distance: Float,
        lateralOffset: Float = 0f,
        speedMps: Float = 0f,
        fleeing: Boolean = false
    ) {
        val safeDistance = distance.coerceIn(0f, Combat.MAX_RANGE)
        // Legacy callers without a speed still get a sensible rate.
        val effectiveSpeed = when {
            speedMps > 0f -> speedMps
            fleeing -> 12f
            else -> 5f
        }
        val base = walkVolume * VolumeMixer.walkDistanceGain(safeDistance, Combat.MAX_RANGE)
        val (panLeft, panRight) = VolumeMixer.panGains(
            VolumeMixer.panFor(lateralOffset, safeDistance)
        )
        updateWalkingTrack(
            key = "enemyWalk",
            left = (base * panLeft).coerceIn(0f, 1f),
            right = (base * panRight).coerceIn(0f, 1f),
            speedMps = effectiveSpeed
        )
    }

    /**
     * The local player's own footsteps stay centred at the speech level
     * regardless of Robert's distance, exactly as heard while running
     * yourself. The playback rate follows the real running speed in metres per
     * second, and [volumeGain] is the running-speed level gain from
     * [com.blindtechnexus.openfire.model.RunSpeed]: a fast run is louder, a
     * slow run is clearly quieter, exactly like Robert's distance-scaled steps.
     */
    fun updatePlayerWalkAudio(
        speedMps: Float = RunPressure.BASE_SPEED_MPS,
        volumeGain: Float = 1.0f
    ) {
        val base = VolumeMixer.clamp(walkVolume * VolumeMixer.runVolumeGain(volumeGain))
        updateWalkingTrack(
            key = "playerWalk",
            left = base,
            right = base,
            speedMps = speedMps
        )
    }

    private fun updateWalkingTrack(key: String, left: Float, right: Float, speedMps: Float) {
        if (released) return
        val player = managedPlayers[key]
        val duck = walkDuckFactor()
        val duckedLeft = (left * duck).coerceIn(0f, 1f)
        val duckedRight = (right * duck).coerceIn(0f, 1f)
        if (player == null || !player.isPlaying) {
            // The track is prepared on a background thread: ask for it once
            // with the gains it should start at, then let the next ticks
            // refine the volume. Nothing here ever blocks the game thread.
            if (!preparingTracks.contains(key)) {
                walkLastApplyAt.remove(key)
                walkCurrentGains.remove(key)
                trackGains[key] = Pair(duckedLeft, duckedRight)
                playManagedTrack(key, volume = 1.0f, posX = 0f, posY = 0f, posZ = 0f, loop = true)
            }
            return
        }
        synchronized(playerLock) {
            // Refresh the live volume every 0.5 s (0.25 s at a fast run);
            // between refreshes the player simply keeps its current level.
            val now = System.currentTimeMillis()
            val lastApplied = walkLastApplyAt[key]
            if (lastApplied != null && now - lastApplied < VolumeMixer.walkUpdateIntervalMs(speedMps)) {
                return
            }
            // Glide toward the target gains so distance changes sound like
            // continuous approach / retreat, never like volume steps.
            val current = walkCurrentGains[key]
            val smoothed = if (current == null) {
                Pair(duckedLeft, duckedRight)
            } else {
                Pair(
                    current.first + (duckedLeft - current.first) * 0.6f,
                    current.second + (duckedRight - current.second) * 0.6f
                )
            }
            try {
                player.setVolume(smoothed.first.coerceIn(0f, 1f), smoothed.second.coerceIn(0f, 1f))
            } catch (_: Exception) {}
            applyWalkRate(player, speedMps)
            walkCurrentGains[key] = smoothed
            walkLastApplyAt[key] = now
        }
    }

    /** Stops both independent footstep channels (and the legacy channel). */
    fun stopFootsteps() {
        stopSound("walk")
        stopSound("playerWalk")
        stopSound("enemyWalk")
    }

    /** Faster movement plays the steps faster; slow movement slows them. */
    private fun applyWalkRate(player: MediaPlayer, speedMps: Float) {
        try {
            player.playbackParams = PlaybackParams()
                .setSpeed(VolumeMixer.walkRate(speedMps))
        } catch (_: Exception) {}
    }

    /**
     * Streams longer audio tracks via isolated, managed MediaPlayers.
     *
     * The whole create / prepare / start sequence runs on the background audio
     * scope: a synchronous `MediaPlayer.prepare()` on the game thread is what
     * used to freeze the app on slower devices while a long track (music, win,
     * lose, airplane, a bomb flight or the round sting) was loading. Requests
     * are generation-guarded, so stopping a track while it is still preparing
     * discards it cleanly instead of starting it late.
     */
    private fun playManagedTrack(
        key: String,
        volume: Float,
        posX: Float,
        posY: Float,
        posZ: Float,
        loop: Boolean,
        rate: Float = 1.0f
    ) {
        if (released) return

        // Music is not spatialised and does not use the SFX slider: the caller
        // passes the already mixed absolute volume (setting * background gain).
        val (left, right) = if (key == "music") {
            val v = VolumeMixer.clamp(volume)
            Pair(v, v)
        } else {
            calculate3dVolume(posX, posY, posZ, volume)
        }
        trackGains[key] = Pair(left, right)

        val generation = nextGeneration(key)
        releaseManagedTrack(key)
        preparingTracks.add(key)

        audioScope.launch {
            var player: MediaPlayer? = null
            try {
                val created = MediaPlayer()
                player = created
                val afd = getAssetFd(key)
                if (afd != null) {
                    created.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                    try {
                        afd.close()
                    } catch (_: Exception) {}
                } else {
                    val synth = generateSyntheticSoundFile(key)
                    if (synth == null) {
                        safeRelease(created)
                        player = null
                        return@launch
                    }
                    created.setDataSource(synth.absolutePath)
                }

                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                created.isLooping = loop
                val gains = trackGains[key] ?: Pair(left, right)
                created.setVolume(gains.first.coerceIn(0f, 1f), gains.second.coerceIn(0f, 1f))
                created.setOnCompletionListener {
                    // A released shot may report completion after its replacement
                    // has started. Only the current player may invalidate the key.
                    val isCurrent = synchronized(playerLock) { managedPlayers[key] === created }
                    if (!loop && isCurrent) stopManagedTrack(key)
                }
                created.setOnErrorListener { failed, what, extra ->
                    Log.w(TAG, "Managed track $key failed ($what/$extra)")
                    synchronized(playerLock) {
                        if (managedPlayers[key] === failed) managedPlayers.remove(key)
                    }
                    safeRelease(failed)
                    preparingTracks.remove(key)
                    true
                }
                created.prepare()

                val superseded = synchronized(playerLock) {
                    if (released || playerGeneration[key] != generation) {
                        true
                    } else {
                        managedPlayers[key] = created
                        false
                    }
                }
                if (superseded) {
                    safeRelease(created)
                    player = null
                    return@launch
                }
                created.start()
                if (rate != 1.0f) {
                    try {
                        created.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2.0f))
                    } catch (_: Exception) {}
                }
                triggerHapticForSound(key)
            } catch (e: Exception) {
                Log.e(TAG, "Failed playing managed track $key", e)
                player?.let { safeRelease(it) }
                synchronized(playerLock) {
                    if (managedPlayers[key] === player) managedPlayers.remove(key)
                }
            } finally {
                preparingTracks.remove(key)
                pruneManagedPlayers()
            }
        }
    }

    /** Releases a player without ever throwing into the audio pipeline. */
    private fun safeRelease(player: MediaPlayer) {
        try {
            if (player.isPlaying) player.stop()
        } catch (_: Exception) {}
        try {
            player.reset()
        } catch (_: Exception) {}
        try {
            player.release()
        } catch (_: Exception) {}
    }

    /** The next generation token of a track; every older result is discarded. */
    private fun nextGeneration(key: String): Int {
        val next = (playerGeneration[key] ?: 0) + 1
        playerGeneration[key] = next
        return next
    }

    /** True while a streamed track is playing or still being prepared. */
    fun isTrackActive(key: String): Boolean {
        val player = managedPlayers[key]
        if (player != null) {
            try {
                if (player.isPlaying) return true
            } catch (_: Exception) {}
        }
        return preparingTracks.contains(key)
    }

    /**
     * Hard guard against a player leak: if too many streamed tracks pile up
     * (a device that refuses to release them), the oldest non-music track is
     * dropped so playback always stays responsive.
     */
    private fun pruneManagedPlayers() {
        if (managedPlayers.size <= MAX_MANAGED_PLAYERS) return
        val victims = managedPlayers.keys.filter { it != "music" }
        for (key in victims.take(managedPlayers.size - MAX_MANAGED_PLAYERS)) {
            releaseManagedTrack(key)
        }
    }

    /**
     * Immediately stops playback of a specific sound.
     *
     * For the two automatic weapons this stops the looping hold channel and
     * its one-shot copy together, so lifting the trigger can never leave a
     * single stray burst behind.
     */
    fun stopSound(key: String) {
        if (key in AUTO_LOOP_KEYS) {
            stopWeaponLoop(key)
            AUTO_LOOP_SHOT_KEYS[key]?.let { stopSoundInternal(it) }
        }
        stopSoundInternal(key)
    }

    /** The real stop path: every stream, streamed track and queued request. */
    private fun stopSoundInternal(key: String) {
        dropPendingPlays(key)
        walkLastApplyAt.remove(key)
        walkCurrentGains.remove(key)
        stopManagedTrack(key)
        val streams = activeSfxStreams.remove(key)
        if (streams != null) {
            for (streamId in streams) {
                try {
                    soundPool.stop(streamId)
                } catch (_: Exception) {}
            }
        }
    }

    fun stopLoop(key: String) {
        stopSound(key)
    }

    /**
     * Stops a streamed track and invalidates every in-flight prepare of it, so
     * a sound that is stopped while still loading can never start afterwards.
     */
    private fun stopManagedTrack(key: String) {
        nextGeneration(key)
        releaseManagedTrack(key)
    }

    private fun releaseManagedTrack(key: String) {
        val player = synchronized(playerLock) { managedPlayers.remove(key) } ?: return
        safeRelease(player)
    }

    /**
     * Stops all active screen-transition and long sounds (victory, defeat, parachute, airplane, walk).
     * This fixes the issue where victory, defeat, or parachute sounds continue playing after navigating away.
     */
    fun stopScreenAudio() {
        // Both automatic weapon loops are paused the moment a screen changes.
        stopWeaponLoops()
        stopSound("win")
        stopSound("lose")
        stopSound("parachute")
        stopSound("airplane")
        stopSound("airplaneEngine")
        stopFootsteps()
        stopSound("missile")
        stopSound("explosion")
        stopBattleProjectiles()
        stopSound("jump")
        stopSound("land")
    }

    /**
     * Stops every projectile sound of a battle: the missile flight, the three
     * bomb flights, the looped timer countdown and the progression stings.
     * Called on every screen change, so nothing keeps playing after a game.
     */
    fun stopBattleProjectiles() {
        stopWeaponLoops()
        stopSound("missile")
        stopSound("explosion")
        stopSound("atomBomb")
        stopSound("grenade")
        stopSound("timerLoop")
        stopSound("timerBomb")
        stopSound("mp40")
        stopSound("machineGun")
        stopSound("levelComplete")
        stopSound("roundComplete")
    }

    /**
     * Stops all loops and battle sounds without stopping background music.
     */
    fun stopAllLoops() {
        stopScreenAudio()
        for ((key, streams) in activeSfxStreams) {
            if (key == "music") continue
            for (streamId in streams) {
                try {
                    soundPool.stop(streamId)
                } catch (_: Exception) {}
            }
        }
        activeSfxStreams.clear()
    }

    /**
     * Stops all sounds completely including music.
     */
    fun stopAllSounds() {
        stopAllLoops()
        stopMusic()
    }

    /**
     * Starts (or keeps) the looping background music at the volume derived
     * from the user's music setting. Calling this again never changes the
     * stored setting, so switching screens can no longer lower the volume.
     */
    fun startMusic() {
        if (released) return
        if (isTrackActive("music")) {
            applyMusicVolume()
            return
        }
        playManagedTrack(
            "music",
            volume = VolumeMixer.music(musicVolume),
            posX = 0f,
            posY = 0f,
            posZ = 0f,
            loop = true
        )
    }

    fun stopMusic() {
        stopManagedTrack("music")
    }

    // The property setter above is the single entry point for changing the
    // music setting: it clamps the value and immediately applies it. A
    // separate setMusicVolume() function would clash with the generated JVM
    // setter for the musicVolume property.

    /**
     * Re-applies the stored music setting to the live player. The gain is also
     * remembered, so a music track that is still preparing starts at the right
     * level instead of blasting at full volume for a moment.
     */
    private fun applyMusicVolume() {
        val v = VolumeMixer.music(musicVolume)
        trackGains["music"] = Pair(v, v)
        val player = managedPlayers["music"] ?: return
        try {
            player.setVolume(v, v)
        } catch (_: Exception) {}
    }

    fun updateTheme(theme: SoundTheme) {
        soundTheme = theme
    }

    private fun triggerHapticForSound(key: String) {
        // No vibration while a game is being played: the game screen is
        // speech and sound only.
        if (battleActive) return
        if (!hapticsEnabled || vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (key) {
                    "assault", "shortGun", "pistol", "sniperRifle",
                    "mp40", "machineGun", "awm" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                    "wall" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(90, 230))
                    }
                    "timerLoop" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(18, 90))
                    }
                    "levelComplete" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 90, 50, 140), -1))
                    }
                    "roundComplete" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 60, 120, 60, 200), -1))
                    }
                    "gunReload" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 40, 50), -1))
                    }
                    "playerHit" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 90), -1))
                    }
                    "gunHit", "enemyHit", "knifeHit", "swordHit", "glueHit", "coverHit" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(30, 180))
                    }
                    "glueWallCrack", "coverCrack" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(80, 220))
                    }
                    "glueSound", "cover" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(60, 200))
                    }
                    "medkit" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(35, 120))
                    }
                    "jump", "parachute", "land" -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(25, 100))
                    }
                    "missile", "atomBomb", "grenade" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 60, 40, 90), -1))
                    }
                    "explosion", "timerBomb" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 140, 60, 200, 60, 260), -1))
                    }
                    "win" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 80, 50, 120, 60, 180), -1))
                    }
                    "lose" -> {
                        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 150, 80, 200), -1))
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                when (key) {
                    "assault", "shortGun", "pistol", "playerHit",
                    "mp40", "machineGun", "awm" -> vibrator.vibrate(60)
                    "gunHit", "glueSound", "cover", "gunReload", "wall" -> vibrator.vibrate(35)
                }
            }
        } catch (_: Exception) {}
    }

    private fun generateSyntheticSoundFile(key: String): File? {
        val cacheDir = File(context.cacheDir, "synth_sounds")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val file = File(cacheDir, "${key}_${soundTheme.name.lowercase()}.wav")
        if (file.exists() && file.length() > 0) return file

        val sampleRate = 22050
        val pcm = when (key) {
            "assault" -> generateGunshotWave(sampleRate, 0.16f, 320f, 90f)
            "shortGun" -> generateGunshotWave(sampleRate, 0.28f, 180f, 60f)
            "pistol" -> generateGunshotWave(sampleRate, 0.12f, 440f, 140f)
            "sniperRifle" -> generateGunshotWave(sampleRate, 0.34f, 260f, 70f)
            "gunReload" -> generateReloadWave(sampleRate, 0.45f)
            "knifeHit", "knifeMiss", "swordHit", "swordMiss" -> generateHitWave(sampleRate, 0.10f)
            "knifeSwish", "knifeSwitch", "swordGlide", "swordSwitch" ->
                generateChirpWave(sampleRate, 0.16f, 900f, 320f)
            "playerHit" -> generateDamageWave(sampleRate, 0.25f)
            "gunHit", "enemyHit", "coverHit", "glueHit" -> generateHitWave(sampleRate, 0.10f)
            "glueWallCrack", "coverCrack" -> generateNoiseThudWave(sampleRate, 0.30f, 160f)
            "click" -> generateBeepWave(sampleRate, 0.035f, 1200f)
            "jump" -> generateChirpWave(sampleRate, 0.20f, 220f, 660f)
            "land" -> generateNoiseThudWave(sampleRate, 0.25f, 85f)
            "parachute" -> generateWindWave(sampleRate, 0.5f)
            "airplane" -> generateEngineLoopWave(sampleRate, 1.2f, 95f)
            "airplaneEngine" -> generateEngineLoopWave(sampleRate, 1.6f, 72f)
            "walk", "playerWalk", "enemyWalk" -> generateFootstepWave(sampleRate, 0.14f)
            "medkit" -> generateChimeWave(sampleRate, 0.35f, doubleArrayOf(523.25, 659.25, 783.99))
            "glueSound" -> generateWallClankWave(sampleRate, 0.3f)
            "cover" -> generateCoverWave(sampleRate, 0.5f)
            "ammo" -> generateBeepWave(sampleRate, 0.08f, 900f)
            "spend" -> generateCoinWave(sampleRate, 0.18f)
            "missile" -> generateChirpWave(sampleRate, 1.2f, 300f, 1400f)
            "explosion", "timerBomb" -> generateNoiseThudWave(sampleRate, 0.9f, 70f)
            "mp40" -> generateGunshotWave(sampleRate, 0.14f, 360f, 110f)
            "machineGun" -> generateGunshotWave(sampleRate, 0.18f, 300f, 95f)
            "awm" -> generateGunshotWave(sampleRate, 0.40f, 240f, 62f)
            "atomBomb" -> generateNoiseThudWave(sampleRate, 1.1f, 58f)
            "grenade" -> generateNoiseThudWave(sampleRate, 0.7f, 95f)
            "timerLoop" -> generateBeepWave(sampleRate, 0.22f, 1046f)
            "wall" -> generateNoiseThudWave(sampleRate, 0.32f, 120f)
            "levelComplete" -> generateFanfareWave(sampleRate, 0.5f, true)
            "roundComplete" -> generateFanfareWave(sampleRate, 0.8f, true)
            "win" -> generateFanfareWave(sampleRate, 0.7f, true)
            "lose" -> generateFanfareWave(sampleRate, 0.7f, false)
            else -> generateBeepWave(sampleRate, 0.05f, 800f)
        }

        try {
            writeWavFile(file, pcm, sampleRate)
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing synthetic WAV for $key", e)
            return null
        }
    }

    private fun generateGunshotWave(sr: Int, duration: Float, startFreq: Float, endFreq: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples
            val decay = (1.0f - progress) * (1.0f - progress)
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            val freq = startFreq + (endFreq - startFreq) * progress
            val tone = sin(2.0 * Math.PI * freq * i / sr).toFloat()
            val mixed = (0.7f * noise + 0.3f * tone) * decay
            data[i] = (mixed * 30000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateReloadWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples
            val env = sin(progress * Math.PI.toFloat())
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            val tone = sin(2.0 * Math.PI * (500.0 + 300.0 * progress) * i / sr).toFloat()
            val mixed = (0.6f * noise + 0.4f * tone) * env
            data[i] = (mixed * 26000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateFootstepWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples
            val env = sin(progress * Math.PI.toFloat()) * (1f - progress)
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            val tone = sin(2.0 * Math.PI * 75.0 * i / sr).toFloat()
            val mixed = (0.4f * noise + 0.6f * tone) * env
            data[i] = (mixed * 24000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateDamageWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples
            val env = (1.0f - progress)
            val tone = sin(2.0 * Math.PI * (160.0 - 90.0 * progress) * i / sr).toFloat()
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            val mixed = (tone * 0.7f + noise * 0.3f) * env
            data[i] = (mixed * 31000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateHitWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples
            val env = (1.0f - progress) * (1.0f - progress)
            val tone = sin(2.0 * Math.PI * 650.0 * i / sr).toFloat()
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            data[i] = ((tone * 0.5f + noise * 0.5f) * env * 28000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateBeepWave(sr: Int, duration: Float, freq: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val progress = i.toFloat() / samples
            val env = sin(progress * Math.PI.toFloat())
            val tone = sin(2.0 * Math.PI * freq * i / sr).toFloat()
            data[i] = (tone * env * 22000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateChirpWave(sr: Int, duration: Float, f1: Float, f2: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val p = i.toFloat() / samples
            val freq = f1 + (f2 - f1) * p
            val env = sin(p * Math.PI.toFloat())
            val tone = sin(2.0 * Math.PI * freq * i / sr).toFloat()
            data[i] = (tone * env * 25000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateNoiseThudWave(sr: Int, duration: Float, freq: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val p = i.toFloat() / samples
            val env = (1f - p) * (1f - p)
            val tone = sin(2.0 * Math.PI * freq * i / sr).toFloat()
            val noise = (Math.random() * 2.0 - 1.0).toFloat()
            data[i] = ((tone * 0.6f + noise * 0.4f) * env * 28000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateWindWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        var last = 0f
        for (i in 0 until samples) {
            val p = i.toFloat() / samples
            val env = sin(p * Math.PI.toFloat())
            val white = (Math.random() * 2.0 - 1.0).toFloat()
            last = last + 0.12f * (white - last)
            data[i] = (last * env * 24000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateEngineLoopWave(sr: Int, duration: Float, freq: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val tone1 = sin(2.0 * Math.PI * freq * i / sr).toFloat()
            val tone2 = sin(2.0 * Math.PI * (freq * 1.5) * i / sr).toFloat()
            val noise = (Math.random() * 2.0 - 1.0).toFloat() * 0.25f
            data[i] = ((tone1 * 0.5f + tone2 * 0.3f + noise) * 20000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateChimeWave(sr: Int, duration: Float, freqs: DoubleArray): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val p = i.toFloat() / samples
            val env = (1.0f - p)
            var sum = 0.0
            for (f in freqs) {
                sum += sin(2.0 * Math.PI * f * i / sr)
            }
            val norm = (sum / freqs.size).toFloat()
            data[i] = (norm * env * 26000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateWallClankWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        for (i in 0 until samples) {
            val p = i.toFloat() / samples
            val env = (1f - p)
            val tone = sin(2.0 * Math.PI * 340.0 * i / sr).toFloat()
            val metallic = sin(2.0 * Math.PI * 1120.0 * i / sr).toFloat() * 0.4f
            val noise = (Math.random() * 2.0 - 1.0).toFloat() * 0.3f
            data[i] = ((tone + metallic + noise) * env * 26000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    /**
     * Cover / Gloo Wall deployment: a low slam, a rising energy whoosh and a
     * metallic shimmer that locks the wall in place.
     */
    private fun generateCoverWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        val thunkLen = (sr * 0.18f).toInt()
        val whooshStart = (sr * 0.03f).toInt()
        val whooshEnd = (sr * 0.40f).toInt()
        val shimmerStart = (sr * 0.12f).toInt()
        for (i in 0 until samples) {
            val t = i.toFloat() / sr
            var v = 0f

            if (i < thunkLen) {
                val env = kotlin.math.exp(-t * 26f)
                val freq = 145f - 120f * (i.toFloat() / thunkLen)
                v += 0.85f * sin(2.0 * Math.PI * freq * t).toFloat() * env
            }

            if (i in whooshStart until whooshEnd) {
                val p = (i - whooshStart).toFloat() / (whooshEnd - whooshStart)
                val env = sin(p * Math.PI.toFloat()) * (0.35f + 0.65f * p)
                val freq = 320f + 1150f * p
                val noise = (Math.random() * 2.0 - 1.0).toFloat()
                v += 0.34f * env * (0.55f * sin(2.0 * Math.PI * freq * t).toFloat() + 0.27f * noise)
            }

            if (i >= shimmerStart) {
                val p = (i - shimmerStart).toFloat() / (samples - shimmerStart)
                val env = kotlin.math.exp(-p * 4.2f)
                v += 0.30f * env * (
                    0.5f * sin(2.0 * Math.PI * 988.0 * t).toFloat() +
                        0.35f * sin(2.0 * Math.PI * 1318.5 * t).toFloat() +
                        0.25f * sin(2.0 * Math.PI * 1568.0 * t).toFloat()
                    )
            }

            data[i] = (kotlin.math.tanh(v * 1.15f) * 28000f).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateCoinWave(sr: Int, duration: Float): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        val f1 = 987.77
        val f2 = 1318.51
        val half = samples / 2
        for (i in 0 until samples) {
            val f = if (i < half) f1 else f2
            val p = (i % half).toFloat() / half
            val env = (1f - p)
            val tone = sin(2.0 * Math.PI * f * i / sr).toFloat()
            data[i] = (tone * env * 25000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun generateFanfareWave(sr: Int, duration: Float, isWin: Boolean): ShortArray {
        val samples = (sr * duration).toInt()
        val data = ShortArray(samples)
        val chords = if (isWin) {
            doubleArrayOf(440.0, 554.37, 659.25, 880.0)
        } else {
            doubleArrayOf(392.0, 349.23, 311.13, 261.63)
        }
        val step = samples / chords.size
        for (i in 0 until samples) {
            val idx = (i / step).coerceIn(0, chords.size - 1)
            val f = chords[idx]
            val p = (i % step).toFloat() / step
            val env = 1.0f - p * 0.5f
            val tone = sin(2.0 * Math.PI * f * i / sr).toFloat()
            data[i] = (tone * env * 25000).toInt().coerceIn(-32767, 32767).toShort()
        }
        return data
    }

    private fun writeWavFile(file: File, pcm: ShortArray, sampleRate: Int) {
        val totalAudioLen = (pcm.size * 2).toLong()
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = (sampleRate * channels * 2).toLong()

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val byteBuffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) {
            byteBuffer.putShort(s)
        }

        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(byteBuffer.array())
        }
    }

    fun release() {
        released = true
        try {
            stopAllSounds()
        } catch (_: Exception) {}
        releaseWeaponLoops()
        preparingTracks.clear()
        trackGains.clear()
        audioScope.cancel()
        try {
            soundPool.release()
        } catch (_: Exception) {}
    }
}
