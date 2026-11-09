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
import com.blindtechnexus.openfire.model.FireCadence
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
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance, stable Audio Mixer / Player architecture — enhanced.
 *
 * Architecture (stabilized):
 * 1. SoundPool engine for ultra-low latency short SFX (gunshots, reloads, hits, clicks, gloo wall).
 *    Enhanced with retry queue, TTL, load-attempt cap and starvation guard — never drops a requested effect.
 * 2. Managed MediaPlayer Channel Pool for streamed, long audio clips (win, lose, parachute, airplane, music, walk).
 *    Completely prevents SoundPool native buffer starvation, freezing and premature cutoffs.
 *    Every streamed track is generation-guarded and prepared off the UI thread, so a slow prepare never blocks gestures.
 * 3. A preloaded SoundPool firing channel per automatic weapon and per fighter
 *    (submachine gun, AK-47, machine gun). [AutomaticWeaponMixer] reuses one
 *    gapless stream while held and stops it immediately on release; late loads
 *    are generation-guarded so they can never produce phantom gunfire.
 * 4. 3D Positional Spatial Audio with realistic distance attenuation and azimuth stereo panning.
 * 5. Granular sound management: any sound or screen audio can be stopped instantly at any time.
 * 6. Stabilized mixer: synchronized player access, leak pruning, volume smoothing and rate adaptation ensure
 *    the mixer never glitches, even under rapid weapon swaps or continuous automatic fire.
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
            // Automatic requests are intercepted before the generic long-track
            // path. Their loop samples are preloaded into SoundPool and legacy
            // single-shot requests use the same bounded AutomaticWeaponMixer.
            "submachineGun",
            "ak47",
            "machineGun",
            "submachineGunShot",
            "ak47Shot",
            "machineGunShot"
        )

        /**
         * The three continuous-fire weapons whose firing sound is a real loop.
         *
         * A firing loop exists only while one of the two fighters is really
         * firing that weapon, and it belongs to exactly one owner:
         * [LOOP_OWNER_PLAYER] or [LOOP_OWNER_ENEMY]. Both fighters may hold
         * the same gun at the same time, so each of them owns his own channel
         * and neither can start, restart or mask the other's burst.
         *
         * The hard rule of this whole file is: **while nobody is firing, no
         * looping player of an automatic weapon exists at all.** Lifting the
         * trigger does not pause a prepared loop and keep it alive for the
         * next burst - a live, paused `isLooping` player was precisely the
         * thing that could be started again by a resume, a watchdog or a
         * racing prepare and then fire forever with nobody holding anything.
         * Releasing the player costs a few milliseconds of preparation on the
         * next hold and removes that failure mode for good.
         */
        val AUTO_LOOP_WEAPONS: Set<String> = setOf("submachineGun", "ak47", "machineGun")

        /** The one-shot copies of the three automatic weapons. */
        val AUTO_SHOT_KEYS: Set<String> = setOf(
            "submachineGunShot",
            "ak47Shot",
            "machineGunShot"
        )

        /**
         * Every key an automatic weapon can be heard with: the loop channels
         * and their single-shot copies. Exclusivity, stopping and screen
         * clean-up all use this set, so no automatic weapon sound can ever be
         * orphaned.
         */
        val AUTO_WEAPON_KEYS: Set<String> = AUTO_LOOP_WEAPONS + AUTO_SHOT_KEYS

        /** The canonical loop channels (kept as the documented public name). */
        val AUTO_LOOP_KEYS: Set<String> = AUTO_LOOP_WEAPONS

        /**
         * The one-shot recording of an automatic weapon. It is the very same
         * MP3 as the loop, kept so that a stray single-shot request can never
         * touch - or cut - the burst a fighter is holding.
         */
        val AUTO_LOOP_SHOT_KEYS: Map<String, String> = mapOf(
            "submachineGun" to "submachineGunShot",
            "ak47" to "ak47Shot",
            "machineGun" to "machineGunShot"
        )

        /** The other direction: every automatic key mapped to its loop channel. */
        val AUTO_LOOP_OF: Map<String, String> =
            AUTO_LOOP_SHOT_KEYS.entries.associate { (loop, shot) -> shot to loop }

        /** The fighter whose continuous-fire channel is meant: the local player. */
        const val LOOP_OWNER_PLAYER = "player"

        /** The fighter whose continuous-fire channel is meant: Robert. */
        const val LOOP_OWNER_ENEMY = "enemy"

        /** Both owners, in the order every bulk clean-up walks them in. */
        val LOOP_OWNERS: List<String> = listOf(LOOP_OWNER_PLAYER, LOOP_OWNER_ENEMY)

        /**
         * How long an automatic recording may ring at all when nobody is
         * holding the trigger. The three assets are multi-second bursts (the
         * AK-47 alone runs for three seconds), and letting one of them finish
         * was what turned a single shot into an endless, overlapping
         * firefight that kept sounding after both fighters had stopped.
         */
        const val MAX_STRAY_AUTOMATIC_SHOT_MS = 900L

        /** The shortest burst that still reads as one shot instead of a click. */
        const val MIN_BURST_MS = 260L

        /** Slack that lets the next beat take a burst over without a seam. */
        const val BURST_TAKEOVER_GRACE_MS = 60L

        /**
         * The burst length of an automatic shot when the caller does not know
         * the firing rhythm that caused it. It is the fastest beat of the game,
         * so one bullet is always heard as one bullet.
         */
        val DEFAULT_TAP_BURST_MS = FireCadence.AUTO_FAST_MS

        /**
         * Start offsets of the two footstep loops. Both fighters use the same
         * walk recording, so starting both at byte zero made them phase-lock
         * into one single sound source. Seeking each channel to a different
         * point of the clip keeps the two loops independent and lets the
         * listener hear two separate walkers even when they move together.
         */
        val WALK_START_OFFSET_MS: Map<String, Long> = mapOf(
            "playerWalk" to 0L,
            "enemyWalk" to 3_600L,
            "walk" to 7_200L
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

        /** Weapon keys used for explicit screen-change cleanup, never for exclusivity. */
        val WEAPON_SOUND_KEYS: Set<String> = setOf(
            "assault",
            "pistol",
            "shortGun",
            "sniperRifle",
            "glueWallBreaker",
            "awm",
            "m10",
            "submachineGun",
            "ak47",
            "machineGun",
            // Single-shot copies of the three automatic weapons. Every key of
            // the family is listed, so a screen change can never leave a
            // recorded burst of them ringing. The firing channels
            // ([AUTO_LOOP_KEYS]) are not part of an exclusive set at all: a
            // held burst must never be cut by the other fighter's answering
            // shot, and each of them owns its own channel anyway.
            "submachineGunShot",
            "ak47Shot",
            "machineGunShot",
            "knifeSwish",
            "knifeMiss",
            "swordGlide",
            "swordMiss"
        )

        /**
         * How long a player may wait for the single `prepare()` slot. Local
         * assets prepare in a few milliseconds, so this only ever matters
         * under extreme load, where dropping a late sound is better than
         * preparing a dozen of them at once and freezing playback.
         */
        private const val PRESENTATION_WAIT_MS = 400L

        /**
         * Total stream budget of the pool.
         *
         * Two held weapon loops exist at most (one per fighter), each of them
         * on a priority above every effect. The remaining streams carry the
         * one-shot effects: footsteps, hits, explosions, menu clicks.
         */
        const val MAX_SFX_STREAMS = 48

        /**
         * How many streams the one-shot effects may hold at once. The rest of
         * [MAX_SFX_STREAMS] is reserved for the held weapon loops, so a dense
         * hit pattern can never starve - and therefore cut - a continuous
         * machine-gun burst.
         */
        const val SFX_STREAM_BUDGET = MAX_SFX_STREAMS - 8

        /** Pool priority of a held weapon loop: above every one-shot effect. */
        const val LOOP_STREAM_PRIORITY = 5

        /** Pool priority of one-shot effects. */
        const val SFX_STREAM_PRIORITY = 1

        /** Assumed length of an effect whose recording length is unknown. */
        const val DEFAULT_SFX_LENGTH_MS = 2_000L

        /** Extra time a stream is assumed to sound after its recording ends. */
        const val SFX_DEADLINE_SLACK_MS = 250L

        /** Delay before a refused stream is retried once. */
        private const val STREAM_RETRY_DELAY_MS = 90L

        /** How many concurrent streamed tracks are allowed before one is dropped. */
        private const val MAX_MANAGED_PLAYERS = 12

        /**
         * How many independent one-shot players may exist at once. Every shot
         * releases itself when its clip ends, so this is only the safety net
         * for a device that stops reporting completion.
         */
        private const val MAX_MANAGED_SHOTS = 24

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
            "submachineGun" to SoundDurations.SUBMACHINE_GUN_MS,
            "ak47" to SoundDurations.AK47_MS,
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

    /** Serialize positional request setup; playback itself is independent and overlapping. */
    private val weaponAudioLock = Any()

    /** True once [release] ran: every later request is ignored. */
    @Volatile
    private var released = false

    /**
     * Stream budget of the pool.
     *
     * [MAX_SFX_STREAMS] is the real limit handed to SoundPool. The extra room
     * above [SFX_STREAM_BUDGET] is a hard reserve for the held weapon loops: a
     * continuous machine-gun burst must never lose its stream to a flood of
     * hit sounds, so the one-shot effects are kept inside their own budget and
     * the loops always have a free stream of their own.
     */
    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(MAX_SFX_STREAMS)
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

    /**
     * Deadline of every one-shot stream currently counted against
     * [SFX_STREAM_BUDGET], and the lock that keeps the effect ledger atomic.
     */
    private val sfxStreamDeadlines = ConcurrentHashMap<Int, Long>()
    private val sfxBudgetLock = Any()

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

    /**
     * Automatic weapons use preloaded SoundPool samples, not a MediaPlayer
     * created for every burst. The mixer owns at most six loop streams (three
     * weapons for each fighter) and all sample loading remains asynchronous.
     */
    private val automaticWeaponMixer = AutomaticWeaponMixer(
        backend = object : AutomaticAudioBackend {
            override fun play(
                sampleId: Int,
                left: Float,
                right: Float,
                priority: Int
            ): Int =
                synchronized(soundPool) {
                    try {
                        // Priority above every one-shot effect: when the pool
                        // is full, SoundPool frees a hit sound first, never
                        // the gun the player is holding.
                        soundPool.play(sampleId, left, right, priority, -1, 1.0f)
                    } catch (_: Exception) {
                        0
                    }
                }

            override fun stop(streamId: Int) {
                synchronized(soundPool) {
                    try {
                        soundPool.stop(streamId)
                    } catch (_: Exception) {}
                }
            }

            override fun setVolume(streamId: Int, left: Float, right: Float) {
                synchronized(soundPool) {
                    try {
                        soundPool.setVolume(streamId, left, right)
                    } catch (_: Exception) {}
                }
            }
        },
        scope = audioScope,
        sampleId = { key -> soundMap[key]?.takeIf { it in loadedSounds } },
        requestSample = { key -> maybeLoadOnDemand(key) },
        minimumBurstMs = MIN_BURST_MS,
        takeoverGraceMs = BURST_TAKEOVER_GRACE_MS
    )

    // Managed MediaPlayers for long / streaming audio
    // Loops are keyed by sound; each one-shot has its own independent player.
    private val managedPlayers = ConcurrentHashMap<String, MediaPlayer>()
    /**
     * One independent streamed shot. The player is null while the request is
     * still being prepared: registering the request first is what lets a stop
     * (or a shot's own deadline) cancel a sound that has not started yet,
     * instead of having it appear a moment later.
     */
    private data class ManagedShot(val key: String, val player: MediaPlayer?)
    private val managedShots = ConcurrentHashMap<Long, ManagedShot>()
    private val shotIds = AtomicLong()

    /**
     * The players [pauseAllSounds] parked, each one tagged with the *owner*
     * that still wants it: `"track:<key>"` for a streamed track or `"shot:<id>"`
     * for one independent one-shot. Automatic loops use SoundPool.autoPause.
     *
     * The tag is not bookkeeping for its own sake - it is the answer to the
     * question "[resumeAllSounds] may I start this again?" A player is only
     * ever resumed while its owner still exists and still wants it, so a
     * sound that was released, stopped or simply nobody asked for any more
     * can never come back on its own. An untagged player is never resumed.
     */
    private val pausedPlayers = ConcurrentHashMap<MediaPlayer, String>()
    private val playerLock = Any()
    @Volatile private var soundsPaused = false
    private var audioPausedAtMs = 0L

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
     * Serializes `prepare()` of every MediaPlayer of the app (loops, streamed
     * tracks and one-shots). Android allows only a handful of players to be
     * prepared at the same time and answers the surplus with MEDIA_ERROR_*,
     * which is exactly what used to cut sounds off: the player was released
     * before it had played a single sample. Preparing one player at a time
     * removes that failure mode completely.
     */
    private val presentationLock = ReentrantLock()

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
        "assault" to listOf("assault-gun.mp3"),
        "pistol" to listOf("pistol-gun.mp3"),
        "shortGun" to listOf("shotgun.mp3"),
        "sniperRifle" to listOf("sniper-rifle.mp3"),
        // The dedicated Gloo Wall Breaker gun: the only sound that destroys a
        // gloo wall. Three consecutive shots from the 90%-100% band break it.
        "glueWallBreaker" to listOf("glue-wall-breaker.mp3"),
        // The M10 handgun, available from round one.
        "m10" to listOf("m10-gun.mp3"),
        // Long-range automatics: the submachine gun (0.5 s beat), the AK-47
        // (1 s beat) and the machine gun (1.5 s beat). Their whole firing
        // sound is one gapless loop, with no silence between two rounds.
        "submachineGun" to listOf("submachine-gun.mp3"),
        "ak47" to listOf("ak-47-gun.mp3"),
        "machineGun" to listOf("machine-gun.mp3"),
        // One-shot copies of the three automatic weapons: the same recordings,
        // played as independently mixed single shots for the enemy.
        "submachineGunShot" to listOf("submachine-gun.mp3"),
        "ak47Shot" to listOf("ak-47-gun.mp3"),
        "machineGunShot" to listOf("machine-gun.mp3"),
        "awm" to listOf("awm-rifle.mp3"),
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
        // Hitting one of the four boundaries of the 25 m square zone: the dedicated
        // wall impact sound. It belongs to the player alone — only a manually
        // controlled fighter can ever run into a boundary, because Robert
        // detects the wall before he reaches it and changes heading instead.
        "wall" to listOf("wall-hit.mp3"),
        "cover" to listOf("cover.mp3"),
        "coverHit" to listOf("cover-hit.mp3"),
        "coverCrack" to listOf("cover-crack.mp3"),
        "medkit" to listOf("medkit.mp3"),
        // Impacts: the weapon sound plus the fighter that was hit.
        "gunHit" to listOf("gun-hit.mp3"),
        "enemyHit" to listOf("enemy-hit.mp3"),
        "playerHit" to listOf("player-hit.mp3"),
        "ammo" to listOf("ammo.mp3"),
        "spend" to listOf("spend.mp3"),
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
        "missile" to listOf("missile.mp3"),
        "explosion" to listOf("explosion.mp3"),
        // Bombs: each bomb flies for exactly as long as its own sound, and
        // every blast - a hit, an intercepted bomb or a disposed one - uses
        // the explosion. The timer bomb counts down with a looped timer sound
        // and then explodes with the same explosion sound.
        "atomBomb" to listOf("atom-bomb.mp3"),
        "grenade" to listOf("grenade.mp3"),
        "timerLoop" to listOf("timer-countdown.mp3"),
        "timerBomb" to listOf("explosion.mp3"),
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
                    if (key in AUTO_LOOP_KEYS) automaticWeaponMixer.onSoundReady(key)
                    flushPendingPlays(key)
                }
            } else if (key != null) {
                handleLoadFailure(key, sampleId)
            } else {
                loadedSounds.remove(sampleId)
            }
        }
        preloadShortSounds()
        // Deliberately no weapon-loop warm-up here: a looping player exists
        // only while an automatic weapon is really being held, so the app can
        // never render a gun loop that nobody asked for.
    }

    private fun preloadShortSounds() {
        audioScope.launch {
            for ((key, files) in soundAliases) {
                // Preload short SFX and the three gapless automatic samples.
                // Other long tracks are streamed via MediaPlayer on demand.
                if (key in LONG_TRACK_KEYS && key !in AUTO_LOOP_KEYS) continue
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
                val soundId = context.assets.openFd("sounds/$fileName").use { soundPool.load(it, 1) }
                if (soundId != 0) {
                    soundMap[key] = soundId
                    sampleToKey[soundId] = key
                    // Loading can finish on another thread before the reverse
                    // lookup is registered; do not strand those requests.
                    if (soundId in loadedSounds) {
                        if (key in AUTO_LOOP_KEYS) automaticWeaponMixer.onSoundReady(key)
                        flushPendingPlays(key)
                    }
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
                        if (soundId in loadedSounds) {
                            if (key in AUTO_LOOP_KEYS) automaticWeaponMixer.onSoundReady(key)
                            flushPendingPlays(key)
                        }
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
        if (key in LONG_TRACK_KEYS && key !in AUTO_LOOP_KEYS) return
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
            if (!soundsPaused) pendingPlays.removeAll { now - it.enqueuedAt > PENDING_PLAY_TTL_MS }
            if (pendingPlays.size >= MAX_PENDING_PLAYS) {
                pendingPlays.removeFirstOrNull()
            }
            pendingPlays.addLast(pending)
        }
    }

    /** Replays (as a simultaneous mix) every queued request for [key]. */
    private fun flushPendingPlays(key: String) {
        if (soundsPaused) return
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
        baseVol: Float = 1.0f,
        key: String = ""
    ): Pair<Float, Float> {
        val dist = sqrt(x * x + y * y + z * z)
        // Smooth inverse distance attenuation
        val distGain = 1.0f / (1.0f + 0.022f * max(0f, dist - 5f))
        val clampedDistGain = distGain.coerceIn(0.10f, 1.0f)

        val (leftGain, rightGain) = VolumeMixer.panGains(VolumeMixer.panFor(x, dist))

        // [VolumeMixer.mix] applies the player's SFX setting, the distance
        // gain and the mixer trim of this sound: weapon fire sits below the
        // impacts, so every hit is audibly louder than the shot it belongs to.
        val totalVol = VolumeMixer.mix(key, sfxVolume, baseVol, clampedDistGain)
        val left = (totalVol * leftGain).coerceIn(0f, 1f)
        val right = (totalVol * rightGain).coerceIn(0f, 1f)
        return Pair(left, right)
    }

    /**
     * Plays a weapon sound at a real two-dimensional position inside the
     * arena, relative to the listener (the player).
     *
     * [lateralOffset] is the enemy's sideways offset in metres (positive =
     * his right, the player's right too) and [depthOffset] is his forward
     * offset along the depth axis. The stereo pan and the distance
     * attenuation are both derived from those real numbers, so an enemy
     * running along the right half of the square is heard on the right, one
     * running along the left half on the left, and one running straight at
     * the player stays centred and simply grows louder.
     */
    fun playPositionalWeaponSound(
        key: String,
        lateralOffset: Float,
        depthOffset: Float,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        holdMs: Long = DEFAULT_TAP_BURST_MS
    ) {
        synchronized(weaponAudioLock) {
            if (canonicalAutoKey(key) != null) {
                // Robert's automatic fire is a burst of his own channel, as
                // long as the firing that caused it and not a second longer:
                // one enemy answer can no longer sound like a whole clip.
                playAutomaticBurst(
                    key,
                    holdMs,
                    owner = LOOP_OWNER_ENEMY,
                    volume = volume,
                    posX = lateralOffset,
                    posY = 0f,
                    posZ = -depthOffset
                )
                return
            }
            playSound(
                key,
                volume = volume,
                rate = rate,
                posX = lateralOffset,
                posY = 0f,
                posZ = -depthOffset
            )
        }
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
        // An automatic weapon is never played as an unbounded clip here: its
        // recording is a multi-second burst, and one shot must not keep ringing
        // after the fighter who fired it has stopped. It plays as a bounded
        // burst instead - see [playBoundedAutomaticShot].
        if (key in AUTO_WEAPON_KEYS) {
            playBoundedAutomaticShot(
                key,
                MAX_STRAY_AUTOMATIC_SHOT_MS,
                volume = volume,
                posX = posX,
                posY = posY,
                posZ = posZ
            )
            return 1
        }
        // Every other key plays under its own name. No automatic recording is
        // reachable from here, so a single shot can never be doubled, extended
        // or mixed into a burst somebody is holding.
        val playKey = key
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
        // One-shot effects stay inside their own stream budget: the pool keeps
        // a hard reserve for the held weapon loops, so a flood of hit sounds
        // can never take the stream of a continuous machine-gun burst.
        if (!reserveSfxStream(playKey)) return -1
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, playKey)
        val pitch = when (soundTheme) {
            SoundTheme.CYBER -> rate * 1.10f
            SoundTheme.STEALTH -> rate * 0.92f
            else -> rate
        }.coerceIn(0.5f, 2.0f)

        // Check pause and start under the same lock used by autoPause. A
        // request racing with pause is queued rather than leaking audible SFX.
        val streamId = synchronized(playerLock) {
            if (soundsPaused) {
                queuePendingPlay(PendingPlay(playKey, volume, rate, posX, posY, posZ,
                    loop = false, enqueuedAt = System.currentTimeMillis()))
                -1
            } else try {
                synchronized(soundPool) {
                    soundPool.play(soundId, left, right, SFX_STREAM_PRIORITY, 0, pitch)
                }
            } catch (_: Exception) { 0 }
        }
        if (streamId == -1) return -1
        if (streamId != 0) {
            activeSfxStreams.getOrPut(playKey) { ConcurrentHashMap.newKeySet() }.add(streamId)
            bookSfxStream(playKey, streamId)
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
     * The three automatic weapons are never handled here: their firing sound is
     * a channel of its own (see [startWeaponLoop]) that only exists while a
     * fighter is firing. Every other long loop is streamed via MediaPlayer so it
     * starts instantly, runs without SoundPool buffer starvation and stops
     * deterministically when the game releases it. Short loops still use
     * SoundPool but are guarded with retry and synchronization to guarantee they
     * never leak.
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
        // The single-shot copy of an automatic weapon is never looped: an
        // enemy answer plays one shot and can never start a permanent burst.
        if (key in AUTO_SHOT_KEYS) {
            playSound(key, volume = volume, rate = rate, posX = posX, posY = posY, posZ = posZ)
            return
        }
        // The three automatic weapons own a dedicated, gapless channel.
        if (key in AUTO_LOOP_KEYS) {
            startWeaponLoop(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
            return
        }
        if (key in LONG_TRACK_KEYS) {
            // Ensure any previous instance is fully released before re-looping; this is what
            // keeps a hold-to-repeat loop stable across rapid hold/release cycles without overlapping.
            if (isTrackActive(key)) {
                // Already looping correctly — just refresh volume/rate instead of restarting.
                val player = managedPlayers[key]
                if (player != null) {
                    try {
                        val (l, r) = if (key == "music") Pair(VolumeMixer.music(musicVolume), VolumeMixer.music(musicVolume))
                        else calculate3dVolume(posX, posY, posZ, volume, key)
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
        // A short looping effect is still a one-shot-class stream: it obeys
        // the same budget, so it can never crowd out a held weapon loop.
        if (!reserveSfxStream(key)) return
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, key)
        val pitch = rate.coerceIn(0.5f, 2.0f)
        val streamId = synchronized(playerLock) {
            if (soundsPaused) {
                queuePendingPlay(PendingPlay(key, volume, rate, posX, posY, posZ,
                    loop = true, enqueuedAt = System.currentTimeMillis()))
                -1
            } else try {
                synchronized(soundPool) {
                    soundPool.play(soundId, left, right, SFX_STREAM_PRIORITY, -1, pitch)
                }
            } catch (_: Exception) { 0 }
        }
        if (streamId == -1) return
        if (streamId != 0) {
            activeSfxStreams.getOrPut(key) { ConcurrentHashMap.newKeySet() }.add(streamId)
            // A short looping effect is booked as long-lived: it holds its slot
            // until it is explicitly stopped (which releases the slot below).
            bookSfxStream(key, streamId, looping = true)
        } else {
            queuePendingPlay(PendingPlay(key, volume, rate, posX, posY, posZ, loop = true, enqueuedAt = System.currentTimeMillis()))
            scheduleRetry(key)
        }
    }

    /** Plays one independent shot of a weapon without interrupting other streams. */
    fun playWeaponSound(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        synchronized(weaponAudioLock) {
            if (canonicalAutoKey(key) != null) {
                // An automatic weapon never plays an unbounded clip: the shot is
                // one bounded burst of the player's own firing channel, cut with
                // the firing that asked for it.
                playAutomaticBurst(
                    key,
                    DEFAULT_TAP_BURST_MS,
                    owner = LOOP_OWNER_PLAYER,
                    volume = volume,
                    posX = posX,
                    posY = posY,
                    posZ = posZ
                )
                return
            }
            playSound(key, volume = volume, rate = rate, posX = posX, posY = posY, posZ = posZ)
        }
    }

    /** The looping variant of [playWeaponSound]: a real, held burst. */
    fun playWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f,
        owner: String = LOOP_OWNER_PLAYER
    ) {
        synchronized(weaponAudioLock) {
            startWeaponLoop(
                key,
                volume = volume,
                posX = posX,
                posY = posY,
                posZ = posZ,
                owner = owner
            )
        }
    }

    /** The canonical firing-channel key of [key], or null when it is not automatic. */
    private fun canonicalAutoKey(key: String): String? =
        if (key in AUTO_LOOP_KEYS) key else AUTO_LOOP_OF[key]

    /**
     * Starts or refreshes one held, gapless automatic-fire stream. Calling this
     * for every bullet is safe: the mixer reuses the existing stream.
     */
    fun startWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f,
        owner: String = LOOP_OWNER_PLAYER
    ) {
        if (released) return
        val canonical = canonicalAutoKey(key)
        if (canonical == null) {
            playLoop(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
            return
        }
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, canonical)
        automaticWeaponMixer.start(canonical, owner, left, right)
    }

    /** Held continuous fire of Robert, panned by his real arena position. */
    fun startEnemyWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        lateralOffset: Float = 0f,
        depthOffset: Float = 0f
    ) {
        startWeaponLoop(
            key,
            volume = volume,
            posX = lateralOffset,
            posY = 0f,
            posZ = -depthOffset,
            owner = LOOP_OWNER_ENEMY
        )
    }

    /**
     * Watchdog of a held burst: re-arms the loop when it lost its stream while
     * the trigger is still down, and refreshes its position every bullet.
     *
     * This is the guarantee that a continuous machine-gun burst is never cut
     * by anything - not by a hit sound, not by a pool that ran out of streams,
     * not by a glitch of the audio system itself. Called once per bullet, it is
     * a no-op while the loop is healthy.
     */
    fun keepWeaponLoopAlive(
        key: String,
        owner: String = LOOP_OWNER_PLAYER,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        if (released) return
        val canonical = canonicalAutoKey(key) ?: return
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, canonical)
        automaticWeaponMixer.keepAlive(canonical, owner, left, right)
    }

    /**
     * A bounded burst for a tap or a legacy caller. Repeated bursts replace one
     * timeout; they never accumulate deadline coroutines or native players.
     */
    fun playAutomaticBurst(
        key: String,
        holdMs: Long,
        owner: String = LOOP_OWNER_PLAYER,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        if (released) return
        val canonical = canonicalAutoKey(key)
        if (canonical == null) {
            playSound(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
            return
        }
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, canonical)
        automaticWeaponMixer.playBurst(canonical, owner, holdMs, left, right)
    }

    /** Stops one weapon for one fighter, or for both when [owner] is null. */
    fun stopWeaponLoop(key: String, owner: String? = null) {
        val canonical = canonicalAutoKey(key)
        if (canonical == null) {
            stopSoundInternal(key)
            return
        }
        automaticWeaponMixer.stop(canonical, owner)
        if (owner == null) {
            AUTO_LOOP_SHOT_KEYS[canonical]?.let { stopSoundInternal(it) }
            stopSoundInternal(canonical)
        }
    }

    fun stopEnemyWeaponLoop(key: String) = stopWeaponLoop(key, LOOP_OWNER_ENEMY)

    fun stopPlayerWeaponLoop(key: String) = stopWeaponLoop(key, LOOP_OWNER_PLAYER)

    private fun stopWeaponLoopsOf(owner: String) = automaticWeaponMixer.stopOwner(owner)

    fun stopPlayerWeaponLoops() = stopWeaponLoopsOf(LOOP_OWNER_PLAYER)

    fun stopEnemyWeaponLoops() = stopWeaponLoopsOf(LOOP_OWNER_ENEMY)

    fun stopWeaponLoops() = automaticWeaponMixer.stopAll()

    /** True while this fighter's trigger still owns a playing/loading stream. */
    fun isWeaponLoopActive(key: String, owner: String = LOOP_OWNER_PLAYER): Boolean {
        val canonical = canonicalAutoKey(key) ?: return isTrackActive(key)
        return automaticWeaponMixer.isActive(canonical, owner)
    }

    fun isAnyWeaponLoopActive(): Boolean = automaticWeaponMixer.isAnyActive()

    fun refreshWeaponLoop(key: String, volume: Float = 1.0f, posZ: Float = 0f) {
        refreshLoop(key, LOOP_OWNER_PLAYER, 0f, 0f, posZ, volume)
    }

    fun refreshEnemyWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        lateralOffset: Float = 0f,
        depthOffset: Float = 0f
    ) {
        refreshLoop(key, LOOP_OWNER_ENEMY, lateralOffset, 0f, -depthOffset, volume)
    }

    private fun refreshLoop(
        key: String,
        owner: String,
        posX: Float,
        posY: Float,
        posZ: Float,
        volume: Float
    ) {
        val canonical = canonicalAutoKey(key) ?: return
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, canonical)
        automaticWeaponMixer.refresh(canonical, owner, left, right)
    }

    /** Cuts every weapon sound that is still playing, loops included. */
    fun stopWeaponSounds() {
        stopWeaponLoops()
        for (weaponKey in WEAPON_SOUND_KEYS + AUTO_WEAPON_KEYS) {
            stopSoundInternal(weaponKey)
        }
    }

    /** Full mixer shutdown. Ordinary battle stops use [stopWeaponLoops] instead. */
    private fun releaseWeaponLoops() = automaticWeaponMixer.release()

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
            speedMps = effectiveSpeed,
            // A small rate difference keeps his loop from ever locking onto
            // the player's own footsteps while both are walking.
            rateScale = 0.93f
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

    private fun updateWalkingTrack(
        key: String,
        left: Float,
        right: Float,
        speedMps: Float,
        rateScale: Float = 1f
    ) {
        if (released) return
        val player = managedPlayers[key]
        val targetLeft = left.coerceIn(0f, 1f)
        val targetRight = right.coerceIn(0f, 1f)
        if (player == null || !player.isPlaying) {
            // The track is prepared on a background thread: ask for it once
            // with the gains it should start at, then let the next ticks
            // refine the volume. Nothing here ever blocks the game thread.
            if (!preparingTracks.contains(key)) {
                walkLastApplyAt.remove(key)
                walkCurrentGains.remove(key)
                trackGains[key] = Pair(targetLeft, targetRight)
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
                Pair(targetLeft, targetRight)
            } else {
                Pair(
                    current.first + (targetLeft - current.first) * 0.6f,
                    current.second + (targetRight - current.second) * 0.6f
                )
            }
            try {
                player.setVolume(smoothed.first.coerceIn(0f, 1f), smoothed.second.coerceIn(0f, 1f))
            } catch (_: Exception) {}
            applyWalkRate(player, speedMps, rateScale)
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
    private fun applyWalkRate(player: MediaPlayer, speedMps: Float, rateScale: Float = 1f) {
        try {
            player.playbackParams = PlaybackParams()
                .setSpeed((VolumeMixer.walkRate(speedMps) * rateScale).coerceIn(0.5f, 2.0f))
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
        if (!loop) {
            playManagedOneShot(key, volume, posX, posY, posZ, rate)
            return
        }

        // Music is not spatialised and does not use the SFX slider: the caller
        // passes the already mixed absolute volume (setting * background gain).
        val (left, right) = if (key == "music") {
            val v = VolumeMixer.clamp(volume)
            Pair(v, v)
        } else {
            calculate3dVolume(posX, posY, posZ, volume, key)
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
                // Preparing two players at the same time is what made Android
                // reject one of them and release it before it ever played.
                // One prepare at a time, but the wait is bounded: a track that
                // cannot get its turn is dropped, never left half-prepared.
                val acquired = acquirePresentationSlot()
                if (!acquired) {
                    safeRelease(created)
                    player = null
                    return@launch
                }
                try {
                    created.prepare()
                } finally {
                    presentationLock.unlock()
                }
                // Both fighters share the walk recording. Starting each
                // footstep channel at its own point of the clip breaks the
                // phase lock that made two walkers sound like one source.
                WALK_START_OFFSET_MS[key]?.let { offset ->
                    try {
                        if (offset > 0L) created.seekTo(offset.toInt())
                    } catch (_: Exception) {}
                }

                val superseded = synchronized(playerLock) {
                    if (released || playerGeneration[key] != generation) {
                        true
                    } else {
                        managedPlayers[key] = created
                        // Parked with its owner recorded, never blindly started:
                        // only a track somebody still wants may come back.
                        if (soundsPaused) markPaused(created, "track:$key") else created.start()
                        false
                    }
                }
                if (superseded) {
                    safeRelease(created)
                    player = null
                    return@launch
                }
                if (rate != 1.0f && !soundsPaused) {
                    try {
                        created.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2.0f))
                    } catch (_: Exception) {}
                }
                if (!soundsPaused) triggerHapticForSound(key)
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

    /**
     * Independent long one-shots, including overlapping copies of the same key.
     *
     * Returns the id of the registered shot. The request is registered before
     * it is prepared, so a matching key stop also cancels an in-flight request:
     * cancelling it by id while it is still loading therefore removes the
     * request itself, and a shot can never appear a moment after it was called
     * off.
     */
    private fun playManagedOneShot(
        key: String, volume: Float, posX: Float, posY: Float, posZ: Float, rate: Float
    ): Long {
        val id = shotIds.incrementAndGet()
        val generation = synchronized(playerLock) { playerGeneration[key] ?: 0 }
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, key)
        managedShots[id] = ManagedShot(key, null)
        audioScope.launch {
            var player: MediaPlayer? = null
            try {
                if (!managedShots.containsKey(id)) return@launch
                val created = MediaPlayer()
                player = created
                val afd = getAssetFd(key)
                if (afd != null) {
                    afd.use { created.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                } else {
                    val synth = generateSyntheticSoundFile(key)
                    if (synth == null) {
                        safeRelease(created)
                        player = null
                        managedShots.remove(id)
                        return@launch
                    }
                    created.setDataSource(synth.absolutePath)
                }
                created.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                created.isLooping = false
                created.setVolume(left, right)
                created.setOnCompletionListener {
                    synchronized(playerLock) { managedShots.remove(id) }
                    safeRelease(created)
                }
                created.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "Managed shot $key failed ($what/$extra)")
                    synchronized(playerLock) { managedShots.remove(id) }
                    safeRelease(created)
                    true
                }
                if (!acquirePresentationSlot()) {
                    safeRelease(created)
                    player = null
                    managedShots.remove(id)
                    return@launch
                }
                try {
                    created.prepare()
                } finally {
                    presentationLock.unlock()
                }
                val accepted = synchronized(playerLock) {
                    // The request must still be there: an id that was cut - by
                    // its own deadline or by a stop call - is never started.
                    if (released || managedShots[id]?.player != null ||
                        (playerGeneration[key] ?: 0) != generation
                    ) false
                    else {
                        managedShots[id] = ManagedShot(key, created)
                        if (soundsPaused) markPaused(created, "shot:$id") else created.start()
                        pruneManagedShots()
                        true
                    }
                }
                if (!accepted) {
                    managedShots.remove(id)
                    safeRelease(created)
                    player = null
                    return@launch
                }
                if (rate != 1.0f && !soundsPaused) {
                    try { created.playbackParams = PlaybackParams().setSpeed(rate.coerceIn(0.5f, 2.0f)) }
                    catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed playing managed shot $key", e)
                synchronized(playerLock) { managedShots.remove(id) }
                player?.let { safeRelease(it) }
            }
        }
        return id
    }

    /**
     * Bound on the independent one-shot players. Every shot releases itself
     * when its clip ends, so this only ever fires on a device that stops
     * reporting completion: the oldest requests are dropped, playback stays
     * responsive and no player can pile up for the rest of the session.
     */
    private fun pruneManagedShots() {
        while (managedShots.size > MAX_MANAGED_SHOTS) {
            val oldest = managedShots.keys.minOrNull() ?: break
            managedShots.remove(oldest)?.player?.let { safeRelease(it) }
        }
    }

    /**
     * Plays one recorded clip of an automatic weapon as a *bounded* one-shot.
     *
     * Every automatic key that arrives outside a fighter's own firing channel -
     * a queued request, a preview, a call from anywhere else - ends up here and
     * is guaranteed to be cut after [maxMs]. No path in the app can leave a
     * multi-second gun burst ringing on its own any more.
     */
    fun playBoundedAutomaticShot(
        key: String,
        maxMs: Long,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        if (released) return
        val loopKey = canonicalAutoKey(key)
        if (loopKey == null && key !in AUTO_SHOT_KEYS) {
            // Not an automatic weapon at all: an ordinary, short sound effect.
            playSound(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
            return
        }
        val canonical = loopKey ?: AUTO_LOOP_OF[key] ?: return
        val (left, right) = calculate3dVolume(posX, posY, posZ, volume, canonical)
        // Even a stray/legacy request stays inside the trigger-aware mixer.
        // There is no unmanaged MediaPlayer clip that can outlive a release.
        automaticWeaponMixer.playBurst(
            canonical,
            LOOP_OWNER_PLAYER,
            maxMs,
            left,
            right
        )
    }

    /** Marks a player as parked by [pauseAllSounds] under the given owner tag. */
    private fun markPaused(player: MediaPlayer, tag: String) {
        synchronized(playerLock) { pausedPlayers[player] = tag }
    }

    /**
     * Whether the owner that parked [player] still wants to hear it.
     *
     * A track is wanted while it is still the current player of its key and a
     * shot while it is still the player of a live shot. Automatic weapon loops
     * are SoundPool streams and are guarded separately by their mixer intent.
     */
    private fun isPlayerStillWanted(player: MediaPlayer): Boolean =
        when (val owner = pausedPlayers[player]) {
            null -> false
            else -> when {
                owner.startsWith("track:") ->
                    managedPlayers[owner.removePrefix("track:")] === player
                owner.startsWith("shot:") ->
                    managedShots[owner.removePrefix("shot:").toLongOrNull()]?.player === player
                else -> false
            }
        }

    /** Freeze every active stream and any in-flight preparation without releasing it. */
    fun pauseAllSounds() {
        synchronized(playerLock) {
            if (soundsPaused) return
            soundsPaused = true
            audioPausedAtMs = System.currentTimeMillis()
            automaticWeaponMixer.pause()
            try { soundPool.autoPause() } catch (_: Exception) {}
            managedPlayers.forEach { (key, player) ->
                try { if (player.isPlaying) markPaused(player, "track:$key") } catch (_: Exception) {}
            }
            managedShots.forEach { (id, shot) ->
                // A shot that is still being prepared is not audible, so it is
                // not parked: the resume starts nothing that was not playing.
                val player = shot.player ?: return@forEach
                try { if (player.isPlaying) markPaused(player, "shot:$id") } catch (_: Exception) {}
            }
        }
    }

    /**
     * Resume only the streams that somebody still wants to hear: each parked
     * player is checked against its owner before it is started again, so a
     * sound that was stopped, released or simply never asked for cannot be
     * revived by an unpausing gesture.
     */
    fun resumeAllSounds() {
        val keys: Set<String>
        val doomed = ArrayList<MediaPlayer>()
        synchronized(playerLock) {
            if (!soundsPaused) return
            val pauseDuration = (System.currentTimeMillis() - audioPausedAtMs).coerceAtLeast(0L)
            synchronized(pendingLock) {
                val adjusted = pendingPlays.map { it.copy(enqueuedAt = it.enqueuedAt + pauseDuration) }
                pendingPlays.clear()
                pendingPlays.addAll(adjusted)
                keys = adjusted.map { it.key }.toSet()
            }
            soundsPaused = false
            try { soundPool.autoResume() } catch (_: Exception) {}
            automaticWeaponMixer.resume()
            for (player in pausedPlayers.keys.toList()) {
                if (!isPlayerStillWanted(player)) {
                    doomed.add(player)
                    continue
                }
                pausedPlayers.remove(player)
                try {
                    player.start()
                } catch (_: Exception) {
                    doomed.add(player)
                }
            }
        }
        // Nobody wants them: they leave the pipeline instead of waiting for a
        // future resume to fire them into a silent game.
        doomed.forEach { safeRelease(it) }
        keys.forEach { if (isReady(it)) flushPendingPlays(it) }
    }

    /**
     * Takes the single preparation slot, waiting at most
     * [PRESENTATION_WAIT_MS] for it. Returns true when the caller owns the
     * lock and must unlock it after `prepare()`; false when the slot stayed
     * busy, in which case the request is dropped instead of failing noisily.
     */
    private fun acquirePresentationSlot(): Boolean = try {
        presentationLock.tryLock(PRESENTATION_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        false
    }

    /** Releases a player without ever throwing into the audio pipeline. */
    private fun safeRelease(player: MediaPlayer) {
        pausedPlayers.remove(player)
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
                if (player.isPlaying || pausedPlayers.containsKey(player)) return true
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
     * For an automatic weapon this destroys the firing channel of that weapon -
     * of both fighters - together with every recorded copy of it, so lifting a
     * trigger, switching a weapon or leaving a screen can never leave a single
     * burst behind. Another weapon is never touched: a gun only ever cuts its
     * own sound.
     */
    fun stopSound(key: String) {
        if (key in AUTO_WEAPON_KEYS) {
            // Stops this weapon's own firing channels and its one-shot copy. It
            // never touches another weapon's loop: the other automatic
            // weapons keep playing exactly as they were.
            stopWeaponLoop(key)
            AUTO_LOOP_SHOT_KEYS[canonicalAutoKey(key) ?: key]?.let { stopSoundInternal(it) }
        }
        stopSoundInternal(key)
    }

    /**
     * Stops one weapon of **one** fighter.
     *
     * This is the owner-aware form of [stopSound] and the one every in-battle
     * caller must use: a sound of the player's own drawing, a hit marker or a
     * screen clean-up of Robert can never silence a held burst of the other
     * fighter, and - above all - nothing can cut a continuous machine gun that
     * somebody is still holding. Only an explicit [stopWeaponSounds] or a
     * screen change stops every channel.
     */
    fun stopWeaponSound(key: String, owner: String) {
        val canonical = canonicalAutoKey(key)
        if (canonical != null) {
            stopWeaponLoop(canonical, owner)
            return
        }
        // A one-shot copy belongs to the channel of its own weapon: when the
        // fighter still holds that weapon, its recording is not cut either.
        val loopKey = AUTO_LOOP_OF[key]
        if (loopKey != null) {
            if (!isWeaponLoopActive(loopKey, owner)) stopSoundInternal(key)
            return
        }
        stopSoundInternal(key)
    }

    /**
     * Keeps the one-shot effects inside [SFX_STREAM_BUDGET] streams.
     *
     * SoundPool frees a stream when its limit is reached, and priority alone
     * is not enough: a long firefight can flood the pool with hit, footstep
     * and explosion streams until nothing but one-shots is left. This gate
     * makes that impossible. The effects stop at their own budget, so the
     * reserve at the top of the pool is always free for the gun a fighter is
     * holding. A sound refused here is simply dropped - a hit marker is never
     * worth cutting the continuous machine gun of the player.
     *
     * Returns true when the request may take a stream.
     */
    private fun reserveSfxStream(key: String): Boolean = synchronized(sfxBudgetLock) {
        pruneFinishedSfxStreamsLocked()
        if (sfxStreamDeadlines.size < SFX_STREAM_BUDGET) return true
        // The effects are at their budget: drop this one instead of letting it
        // displace a stream of the held loop.
        Log.d(TAG, "SFX budget reached, '$key' dropped instead of cutting a held loop")
        false
    }

    /**
     * Forgets the one-shot streams that already finished. SoundPool cannot be
     * asked whether a stream still plays, so every effect is booked with the
     * deadline of its own recording: once that deadline has passed the stream
     * is certainly over and its slot is free again.
     */
    private fun pruneFinishedSfxStreamsLocked() {
        if (sfxStreamDeadlines.isEmpty()) return
        val now = System.currentTimeMillis()
        val done = sfxStreamDeadlines.filterValues { it <= now }.keys
        if (done.isEmpty()) return
        for (streamId in done) {
            sfxStreamDeadlines.remove(streamId)
            for ((key, streams) in activeSfxStreams) {
                if (streams.remove(streamId)) {
                    if (streams.isEmpty()) activeSfxStreams.remove(key, streams)
                    break
                }
            }
        }
    }

    /**
     * Books a stream against the effect budget. A one-shot is released again at
     * the end of its own recording; a short looping effect holds its slot until
     * it is stopped and the slot is released in [stopSoundInternal].
     */
    private fun bookSfxStream(key: String, streamId: Int, looping: Boolean = false) {
        val deadline = if (looping) {
            Long.MAX_VALUE
        } else {
            val lengthMs = soundDurationMs(key).takeIf { it > 0L } ?: DEFAULT_SFX_LENGTH_MS
            System.currentTimeMillis() + lengthMs + SFX_DEADLINE_SLACK_MS
        }
        synchronized(sfxBudgetLock) { sfxStreamDeadlines[streamId] = deadline }
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
                // The stream leaves the effect budget together with the pool.
                synchronized(sfxBudgetLock) { sfxStreamDeadlines.remove(streamId) }
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
        synchronized(playerLock) {
            nextGeneration(key)
            releaseManagedTrack(key)
            val matching = managedShots.filterValues { it.key == key }
            matching.forEach { (id, shot) ->
                managedShots.remove(id)
                shot.player?.let { safeRelease(it) }
            }
        }
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
        // Every automatic weapon channel is destroyed the moment a screen
        // changes, together with every recorded copy of it: outside the firing
        // that owns it, the app holds no gun player at all - not a playing one,
        // not a paused one, not one that is still being prepared.
        stopWeaponSounds()
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
        // Weapon first: a flight or a sting may end after the battle does, but
        // a gun must not. This cuts both fighter's channels and the recorded
        // bursts of all three automatics (loop keys and shot keys alike).
        stopWeaponSounds()
        stopSound("missile")
        stopSound("explosion")
        stopSound("atomBomb")
        stopSound("grenade")
        stopSound("timerLoop")
        stopSound("timerBomb")
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
        synchronized(sfxBudgetLock) { sfxStreamDeadlines.clear() }
    }

    /**
     * Stops all sounds completely including music.
     */
    fun stopAllSounds() {
        stopAllLoops()
        stopMusic()
        synchronized(playerLock) {
            // Invalidate every pending one-shot preparation as well as active shots.
            (LONG_TRACK_KEYS + managedShots.values.map { it.key }).forEach { key ->
                stopManagedTrack(key)
            }
        }
        synchronized(pendingLock) { pendingPlays.clear() }
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
                    "machineGun", "awm" -> {
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
                    "machineGun", "awm" -> vibrator.vibrate(60)
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
            // The three automatics fall back to a single shot of their own
            // weight, never to a beep: a firing loop of one of them is audible
            // for as long as a trigger is down and must still sound like a gun.
            "submachineGun" -> generateGunshotWave(sampleRate, 0.12f, 400f, 120f)
            "ak47" -> generateGunshotWave(sampleRate, 0.16f, 330f, 100f)
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
        // Nothing may stay parked for a resume that can never come: every
        // player the mixer still knows about is gone with the mixer.
        pausedPlayers.clear()
        preparingTracks.clear()
        trackGains.clear()
        audioScope.cancel()
        try {
            soundPool.release()
        } catch (_: Exception) {}
    }
}
