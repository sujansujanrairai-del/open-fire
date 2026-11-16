package com.blindtechnexus.openfire.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.FireCadence
import com.blindtechnexus.openfire.model.RunPressure
import com.blindtechnexus.openfire.model.SoundDurations
import com.blindtechnexus.openfire.model.SoundTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The game's sound system, built on one software mixer ([MixEngine]).
 *
 * - Every sound effect is a voice in the mixer. Any number of voices play at
 *   the same time and are summed, so a sound never stops another sound to
 *   make room for itself, and there is no platform stream budget to run out.
 * - Sounds are decoded from their OGG assets once, on a background thread,
 *   and kept in memory as mono samples. A request never waits for a decoder:
 *   a cached clip starts on the very next 10 ms mixing block, and a cold clip
 *   decodes on its own priority lane instead of queuing behind anything else.
 * - Every sound plays at its natural pitch. Only the walking footsteps change
 *   their rate, and only to follow the real movement speed.
 * - A held weapon, a footstep or a charging device is a *loop voice* owned by
 *   the fighter that started it. Loops are keyed by owner, so the player's
 *   channel and Robert's channel can never start, stop or mask each other.
 * - Positional sounds follow one distance curve: low from far away, swelling
 *   as the source closes in and fading again as it recedes.
 * - Background music is the only stream left: one [MediaPlayer] that loops.
 * - Stops are immediate: a stopped voice fades out inside one 10 ms block.
 */
class AudioManager(private val context: Context) {

    companion object {
        const val LOOP_OWNER_PLAYER = "player"
        const val LOOP_OWNER_ENEMY = "enemy"

        /** Both owners, in the order every bulk clean-up walks them in. */
        val LOOP_OWNERS: List<String> = listOf(LOOP_OWNER_PLAYER, LOOP_OWNER_ENEMY)

        /**
         * Every sound key and the asset it plays, in `assets/sounds`.
         *
         * Each file of the directory is used by at least one key, and every key
         * is used by the game code. Keys that share a file (the one-shot copies of
         * the automatic weapons, the two footstep tracks, the land and the jump)
         * are listed explicitly so the mapping stays readable.
         */
        val SOUND_FILES: Map<String, String> = mapOf(
            "click" to "click.ogg",
            "gunReload" to "gun-reload.ogg",
            "assault" to "assault-gun.ogg",
            "pistol" to "pistol-gun.ogg",
            "shortGun" to "shotgun.ogg",
            "sniperRifle" to "sniper-rifle.ogg",
            "glueWallBreaker" to "glue-wall-breaker.ogg",
            "m10" to "m10-gun.ogg",
            "ar15" to "ar-15.ogg",
            "awm" to "awm-rifle.ogg",
            // Automatic weapons: the loop channel and its single-shot copy use the same recording.
            "submachineGun" to "submachine-gun.ogg",
            "ak47" to "ak-47-gun.ogg",
            "machineGun" to "machine-gun.ogg",
            "submachineGunShot" to "submachine-gun.ogg",
            "ak47Shot" to "ak-47-gun.ogg",
            "machineGunShot" to "machine-gun.ogg",
            "knifeHit" to "knife-hit.ogg",
            "knifeMiss" to "knife-miss.ogg",
            "knifeSwitch" to "knife-switch.ogg",
            "knifeSwish" to "knife-switch.ogg",
            "swordHit" to "sword-hit.ogg",
            "swordMiss" to "sword-miss.ogg",
            "swordSwitch" to "sword-switch.ogg",
            "swordGlide" to "sword-switch.ogg",
            "glueSound" to "glue-sound.ogg",
            "glueHit" to "glue-hit.ogg",
            "glueWallCrack" to "glue-wall-crack.ogg",
            "wall" to "wall-hit.ogg",
            "cover" to "cover.ogg",
            "coverHit" to "cover-hit.ogg",
            "coverCrack" to "cover-crack.ogg",
            "medkit" to "medkit.ogg",
            "gunHit" to "gun-hit.ogg",
            "enemyHit" to "enemy-hit.ogg",
            "playerHit" to "player-hit.ogg",
            "ammo" to "ammo.ogg",
            "spend" to "spend.ogg",
            "jump" to "jump.ogg",
            "land" to "jump.ogg",
            "airplane" to "airplane.ogg",
            "airplaneEngine" to "airplane-engine.ogg",
            "parachute" to "parachute.ogg",
            "win" to "win.ogg",
            "lose" to "lose.ogg",
            "music" to "music.ogg",
            "walk" to "walk.ogg",
            "playerWalk" to "walk.ogg",
            "enemyWalk" to "walk.ogg",
            "missile" to "missile.ogg",
            "explosion" to "explosion.ogg",
            "atomBomb" to "atom-bomb.ogg",
            "grenade" to "grenade.ogg",
            "timerLoop" to "timer-countdown.ogg",
            "timerBomb" to "explosion.ogg",
            "levelComplete" to "level-complete.ogg",
            "roundComplete" to "round-complete.ogg",
            "carBrake" to "car-brake.ogg",
            "carStart" to "car-start.ogg",
            "carEngine" to "car-engine.ogg",
            "carMoving" to "car-moving.ogg",
            "carDoorOpen" to "car-door-open.ogg",
            "carDoorClose" to "car-door-close.ogg",
            "carCrash" to "car-crash.ogg",
            "electricShock" to "electric-shock.ogg",
            "magicalSound" to "magical-sound.ogg"
        )

        /** The one-shot copy of each automatic weapon, mapped to its loop key. */
        val AUTO_LOOP_OF: Map<String, String> = mapOf(
            "submachineGunShot" to "submachineGun",
            "ak47Shot" to "ak47",
            "machineGunShot" to "machineGun"
        )

        /** The three continuous-fire weapons. Each one fires on a held loop. */
        val AUTO_LOOP_KEYS: Set<String> = setOf("submachineGun", "ak47", "machineGun")

        /** The single-shot copies of the automatic weapons. */
        val AUTO_SHOT_KEYS: Set<String> = AUTO_LOOP_OF.keys

        /** Every key an automatic weapon can be heard with. */
        val AUTO_WEAPON_KEYS: Set<String> = AUTO_LOOP_KEYS + AUTO_SHOT_KEYS

        /**
         * Held loops: the automatic weapons plus the electric shock, whose
         * charging sound runs for as long as the trigger is held.
         */
        val HOLD_LOOP_KEYS: Set<String> = AUTO_LOOP_KEYS + setOf("magicalSound", "carMoving")

        /** Footstep tracks: each fighter walks on its own loop of the same recording. */
        val WALK_KEYS: Set<String> = setOf("walk", "playerWalk", "enemyWalk")

        /**
         * Start offsets of the footstep loops. Both fighters use the same walk
         * recording, so starting both at zero would phase-lock them into one source.
         */
        val WALK_START_OFFSET_MS: Map<String, Long> = mapOf(
            "playerWalk" to 0L,
            "enemyWalk" to 3_600L,
            "walk" to 7_200L
        )

        /** Every key that belongs to a weapon. Screen changes stop all of them. */
        val WEAPON_SOUND_KEYS: Set<String> = setOf(
            "assault", "pistol", "shortGun", "sniperRifle", "glueWallBreaker", "awm",
            "m10", "ar15", "submachineGun", "ak47", "machineGun",
            "submachineGunShot", "ak47Shot", "machineGunShot",
            "knifeSwish", "knifeMiss", "swordGlide", "swordMiss",
            "magicalSound", "electricShock"
        )

        /** Keys of the car sounds. They stop together when the battle ends. */
        val CAR_KEYS: Set<String> = setOf(
            "carBrake", "carStart", "carEngine", "carMoving",
            "carDoorOpen", "carDoorClose", "carCrash"
        )

        /** Measured length of the sounds that drive a game timer. */
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

        /**
         * How long an automatic shot may ring when nobody says how long it
         * should be: a single shot is never a whole multi-second recording.
         */
        const val MAX_STRAY_AUTOMATIC_SHOT_MS = 900L

        /** The shortest burst that still reads as one shot instead of a click. */
        const val MIN_BURST_MS = 260L

        /** The burst length of an automatic shot when the caller does not know the rhythm. */
        val DEFAULT_TAP_BURST_MS: Long = FireCadence.AUTO_FAST_MS

        private const val MUSIC_KEY = "music"
    }

    /** A held loop: its owner, its sound and the gains it currently plays at. */
    private class LoopSlot(
        val key: String,
        val owner: String,
        val id: Int,
        @Volatile var left: Float,
        @Volatile var right: Float,
        @Volatile var rate: Float,
        val startMs: Long
    )

    /**
     * Three dedicated lanes, so no audio work can ever queue behind another:
     *
     *  - the warm-up lane quietly decodes the whole library at the lowest
     *    thread priority and is the only place that may take its time;
     *  - the decode lane immediately decodes the clip of a sound that was
     *    requested before its warm-up finished, at high priority;
     *  - the chain lane schedules back-to-back sounds (the car door, the
     *    engine start) and the trailing stop of [stopSound].
     */
    private val warmExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenFireWarmup").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }

    private val warmDispatcher = warmExecutor.asCoroutineDispatcher()

    private val decodeExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "OpenFireDecode").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    private val decodeDispatcher = decodeExecutor.asCoroutineDispatcher()

    private val chainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenFireChain").apply {
            priority = Thread.MAX_PRIORITY - 1
            isDaemon = true
        }
    }

    private val chainDispatcher = chainExecutor.asCoroutineDispatcher()

    private val audioScope = CoroutineScope(SupervisorJob() + decodeDispatcher)

    /** Decoded clips by asset file. Each file is decoded once, lanes never block each other. */
    private val clips = ConcurrentHashMap<String, PcmClip>()

    private val engine = MixEngine()

    /** Held loops by `owner|key`. */
    private val loops = ConcurrentHashMap<String, LoopSlot>()
    private val loopLock = Any()

    @Volatile
    private var released = false

    private var music: MediaPlayer? = null

    @Volatile
    private var musicWanted = false

    /** The user's settings. These are the single source of truth. */
    var sfxVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.clamp(value)
        }

    var musicVolume: Float = 0.5f
        set(value) {
            field = VolumeMixer.clamp(value)
            applyMusicVolume()
        }

    /** Walking footsteps play at the speech level, attenuated by their 3D distance. */
    var walkVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.clamp(value)
        }

    /**
     * The sound theme names the flavour of the battlefield. It never changes
     * the pitch of anything: every sound always plays at its natural pitch.
     */
    var soundTheme: SoundTheme = SoundTheme.TACTICAL

    /** Haptics belong to the menus only. */
    var hapticsEnabled: Boolean = false

    /** True while the battle screen is open: the hard switch that mutes every vibration. */
    @Volatile
    var battleActive: Boolean = false

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        // Spin the mixer up before anything is requested: the AudioTrack is
        // already running when the first click happens, so it is heard on the
        // next 10 ms block instead of after the track starts up.
        engine.start()
        // Decode every sound on the quiet warm-up lane, so the first shot is
        // never late and no decode can ever block an interaction.
        audioScope.launch(warmDispatcher) {
            for (file in SOUND_FILES.values.distinct()) {
                if (released) break
                if (file != "music.ogg") clipFor(file)
            }
        }
    }

    // ------------------------------------------------------------ decoding

    /**
     * Returns the decoded clip of [file], decoding it on first use. Null if it
     * cannot be decoded. Each file is decoded exactly once; different files
     * decode in parallel and a request for a warm clip never waits at all.
     */
    private fun clipFor(file: String): PcmClip? {
        clips[file]?.let { return it }
        return try {
            clips.computeIfAbsent(file) { name ->
                val decoded = context.assets.openFd("sounds/$name").use { PcmDecoder.decode(it) }
                    ?: error("undecodable sound: $name")
                PcmClip(decoded)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- spatial

    /**
     * 3D spatial panning and distance attenuation.
     *
     * [x] is the sideways offset, [z] the distance along the line of sight. A
     * sound with no real sideways offset is centred exactly, so running straight
     * at the enemy never smears the image between the channels. The distance
     * gain follows the one distance curve of the game: quiet from far away and
     * swelling continuously as the source approaches, so an enemy heard from
     * afar grows louder as he closes in and fades again as he moves away.
     */
    fun calculate3dVolume(
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 0f,
        baseVol: Float = 1.0f,
        key: String = ""
    ): Pair<Float, Float> {
        val dist = sqrt(x * x + y * y + z * z)
        val clampedDistGain = VolumeMixer.distanceGain(dist, Combat.MAX_RANGE)
        val (leftGain, rightGain) = VolumeMixer.panGains(VolumeMixer.panFor(x, dist))
        val totalVol = VolumeMixer.mix(key, sfxVolume, baseVol, clampedDistGain)
        val left = (totalVol * leftGain).coerceIn(0f, 1f)
        val right = (totalVol * rightGain).coerceIn(0f, 1f)
        return Pair(left, right)
    }

    /**
     * The playback rate of a sound. Every sound plays at its natural pitch:
     * [rate] is passed through unchanged apart from the safety clamp, and the
     * sound theme never shifts the pitch. Only the walking loops change their
     * rate, and only to follow the real movement speed.
     */
    private fun pitchFor(rate: Float): Float = rate.coerceIn(0.5f, 2.0f)

    // ------------------------------------------------------- one-shot sounds

    /**
     * Plays a sound effect as a new voice. Any number of the same sound may
     * overlap. Returns a voice id, or -1 when the sound is unknown or the
     * engine is released.
     */
    fun playSound(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ): Int {
        if (released || !SOUND_FILES.containsKey(key)) return -1
        // An automatic weapon is never a whole multi-second recording here.
        if (key in AUTO_WEAPON_KEYS) {
            return playAutomaticBurst(
                key,
                MAX_STRAY_AUTOMATIC_SHOT_MS,
                volume = volume,
                posX = posX,
                posY = posY,
                posZ = posZ
            ).let { if (it) 1 else -1 }
        }
        return oneShot(key, owner = "", volume = volume, rate = rate, posX = posX, posY = posY, posZ = posZ, deadlineMs = 0L)
    }

    /**
     * Plays [keys] one after another with no gap: each sound starts on the
     * very frame the previous one ends. When [loopKey] is given, that loop
     * starts on the frame the last sound ends and keeps going (the car start
     * followed by the engine, or the brake followed by the engine).
     *
     * The whole chain is scheduled in one pass, so no sample of the chain can
     * drift behind another, and the chain lane runs at top priority so a door
     * or an engine sequence is never queued behind anything.
     */
    fun playSequence(keys: List<String>, loopKey: String? = null, volume: Float = 1.0f) {
        if (released || keys.isEmpty()) return
        if (keys.any { !SOUND_FILES.containsKey(it) }) return
        if (loopKey != null && !SOUND_FILES.containsKey(loopKey)) return
        audioScope.launch(chainDispatcher) {
            if (released) return@launch
            var offsetFrames = 0L
            for (key in keys) {
                val file = SOUND_FILES[key] ?: break
                val clip = clips[file] ?: clipFor(file) ?: break
                addChainVoice(key, clip, loop = false, offsetFrames = offsetFrames, volume = volume)
                offsetFrames += clip.frames.toLong()
            }
            if (loopKey != null) {
                val file = SOUND_FILES[loopKey] ?: return@launch
                val clip = clips[file] ?: clipFor(file) ?: return@launch
                addChainVoice(loopKey, clip, loop = true, offsetFrames = offsetFrames, volume = volume)
            }
        }
    }

    private fun addChainVoice(
        key: String,
        clip: PcmClip,
        loop: Boolean,
        offsetFrames: Long,
        volume: Float
    ) {
        val gains = calculate3dVolume(0f, 0f, 0f, volume, key)
        val voice = Voice(
            id = engine.newId(),
            key = key,
            owner = "",
            clip = clip,
            loop = loop,
            startFrame = -offsetFrames.toDouble(),
            deadlineFrames = Long.MAX_VALUE,
            enqueuedAtMs = System.currentTimeMillis()
        )
        voice.targetLeft = gains.first
        voice.targetRight = gains.second
        voice.currentLeft = gains.first
        voice.currentRight = gains.second
        engine.start()
        engine.add(voice)
    }

    /** Plays several sounds at once, as one mix. */
    fun playMixed(keys: List<String>, volume: Float = 1.0f, posX: Float = 0f, posY: Float = 0f, posZ: Float = 0f) {
        for (key in keys) {
            playSound(key, volume = volume, posX = posX, posY = posY, posZ = posZ)
        }
    }

    /** Plays a weapon sound at a real position in the arena, relative to the listener. */
    fun playPositionalWeaponSound(
        key: String,
        lateralOffset: Float,
        depthOffset: Float,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        holdMs: Long = DEFAULT_TAP_BURST_MS
    ) {
        if (released) return
        if (key in AUTO_WEAPON_KEYS) {
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
        playSound(key, volume = volume, rate = rate, posX = lateralOffset, posY = 0f, posZ = -depthOffset)
    }

    /** Plays a weapon sound. Automatic weapons play as a bounded single shot. */
    fun playWeaponSound(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        playSound(key, volume = volume, rate = rate, posX = posX, posY = posY, posZ = posZ)
    }

    /**
     * One bounded shot of an automatic weapon, owned by [owner]. It fades out
     * after [holdMs] (at least [MIN_BURST_MS]) so one shot never rings for the
     * whole recording, and [stopWeaponSound] can cut it.
     */
    fun playAutomaticBurst(
        key: String,
        holdMs: Long,
        owner: String = LOOP_OWNER_PLAYER,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ): Boolean {
        if (released || !SOUND_FILES.containsKey(key)) return false
        val burstMs = max(holdMs, MIN_BURST_MS)
        return oneShot(key, owner, volume, 1f, posX, posY, posZ, deadlineMs = burstMs) >= 0
    }

    private fun oneShot(
        key: String,
        owner: String,
        volume: Float,
        rate: Float,
        posX: Float,
        posY: Float,
        posZ: Float,
        deadlineMs: Long
    ): Int {
        val file = SOUND_FILES[key] ?: return -1
        val id = engine.newId()
        val gains = calculate3dVolume(posX, posY, posZ, volume, key)
        val pitch = pitchFor(rate)
        withClip(file) { clip ->
            if (released) return@withClip
            val voice = Voice(
                id = id,
                key = key,
                owner = owner,
                clip = clip,
                loop = false,
                startFrame = 0.0,
                deadlineFrames = if (deadlineMs > 0L) deadlineMs * MixEngine.SAMPLE_RATE / 1000L else Long.MAX_VALUE,
                enqueuedAtMs = System.currentTimeMillis()
            )
            voice.targetLeft = gains.first
            voice.targetRight = gains.second
            voice.currentLeft = gains.first
            voice.currentRight = gains.second
            voice.rate = pitch
            engine.start()
            engine.add(voice)
        }
        triggerHapticForSound(key)
        return id
    }

    /**
     * Runs [then] with the clip of [file]: at once when cached, otherwise on
     * the high-priority decode lane. A warm clip is therefore heard within one
     * mixing block of the request, and even a cold clip never waits behind the
     * library warm-up or any other audio work.
     */
    private fun withClip(file: String, then: (PcmClip) -> Unit) {
        val cached = clips[file]
        if (cached != null) {
            then(cached)
            return
        }
        audioScope.launch(decodeDispatcher) {
            if (released) return@launch
            val clip = clipFor(file) ?: return@launch
            then(clip)
        }
    }

    // --------------------------------------------------------- held loops

    /** Plays a looping sound that runs until it is stopped. */
    fun playLoop(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        if (released || !SOUND_FILES.containsKey(key)) return
        val gains = calculate3dVolume(posX, posY, posZ, volume, key)
        loopSlot(key, owner = "", left = gains.first, right = gains.second, rate = pitchFor(rate), startMs = 0L)
    }

    /**
     * Starts, or keeps, the held firing loop of an automatic weapon for one
     * fighter. Calling it again for every bullet is safe: the same loop is
     * reused, and only its position and volume are refreshed.
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
        val loopKey = holdKeyOf(key) ?: return
        val gains = calculate3dVolume(posX, posY, posZ, volume, loopKey)
        loopSlot(loopKey, owner, gains.first, gains.second, 1f, 0L)
    }

    /** Robert's held firing loop, placed by his real offset from the player. */
    fun startEnemyWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        lateralOffset: Float = 0f,
        depthOffset: Float = 0f
    ) {
        startWeaponLoop(key, volume, lateralOffset, 0f, -depthOffset, LOOP_OWNER_ENEMY)
    }

    /** Keeps a held loop alive and refreshes it; identical to [startWeaponLoop]. */
    fun keepWeaponLoopAlive(
        key: String,
        owner: String = LOOP_OWNER_PLAYER,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        startWeaponLoop(key, volume, posX, posY, posZ, owner)
    }

    fun refreshWeaponLoop(key: String, volume: Float = 1.0f, posZ: Float = 0f) {
        startWeaponLoop(key, volume, 0f, 0f, posZ, LOOP_OWNER_PLAYER)
    }

    fun refreshEnemyWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        lateralOffset: Float = 0f,
        depthOffset: Float = 0f
    ) {
        startEnemyWeaponLoop(key, volume, lateralOffset, depthOffset)
    }

    /** The held-loop key of [key] (a loop key or a single-shot copy), or null. */
    private fun holdKeyOf(key: String): String? {
        val loopKey = AUTO_LOOP_OF[key] ?: key
        return if (loopKey in HOLD_LOOP_KEYS) loopKey else null
    }

    /**
     * Creates the loop of `owner|key` or updates its gains and rate. A loop is
     * created at most once per owner and key, and the decoder never blocks it.
     */
    private fun loopSlot(
        key: String,
        owner: String,
        left: Float,
        right: Float,
        rate: Float,
        startMs: Long
    ) {
        val file = SOUND_FILES[key] ?: return
        synchronized(loopLock) {
            val slotKey = "$owner|$key"
            val existing = loops[slotKey]
            if (existing != null) {
                existing.left = left
                existing.right = right
                existing.rate = rate
                engine.live[existing.id]?.let { voice ->
                    voice.targetLeft = left
                    voice.targetRight = right
                    voice.rate = rate
                }
                return
            }
            val slot = LoopSlot(key, owner, engine.newId(), left, right, rate, startMs)
            loops[slotKey] = slot
            withClip(file) { clip ->
                // The slot may have been stopped while the clip was decoding.
                if (released || loops[slotKey] !== slot) return@withClip
                val offsetFrames = if (clip.frames > 0) {
                    (slot.startMs * MixEngine.SAMPLE_RATE / 1000L) % clip.frames
                } else {
                    0L
                }
                val voice = Voice(
                    id = slot.id,
                    key = key,
                    owner = owner,
                    clip = clip,
                    loop = true,
                    startFrame = offsetFrames.toDouble(),
                    deadlineFrames = Long.MAX_VALUE,
                    enqueuedAtMs = System.currentTimeMillis()
                )
                voice.targetLeft = slot.left
                voice.targetRight = slot.right
                voice.currentLeft = slot.left
                voice.currentRight = slot.right
                voice.rate = slot.rate
                engine.start()
                engine.add(voice)
            }
        }
    }

    private fun stopLoopsWhere(predicate: (LoopSlot) -> Boolean) {
        synchronized(loopLock) {
            val doomed = loops.values.filter(predicate)
            for (slot in doomed) {
                loops.remove("${slot.owner}|${slot.key}", slot)
                engine.stop(slot.id)
            }
        }
    }

    /** True while [owner] (player or enemy) still holds the loop of [key]. */
    fun isWeaponLoopActive(key: String, owner: String = LOOP_OWNER_PLAYER): Boolean {
        val loopKey = holdKeyOf(key) ?: return false
        return loops.containsKey("$owner|$loopKey")
    }

    /** True while any fighter holds a firing loop. */
    fun isAnyWeaponLoopActive(): Boolean = loops.values.any { it.key in HOLD_LOOP_KEYS }

    /** Stops the held loop of one fighter, or of both when [owner] is null. */
    fun stopWeaponLoop(key: String, owner: String? = null) {
        val loopKey = holdKeyOf(key) ?: return
        stopLoopsWhere { it.key == loopKey && (owner == null || it.owner == owner) }
    }

    fun stopEnemyWeaponLoop(key: String) = stopWeaponLoop(key, LOOP_OWNER_ENEMY)

    fun stopPlayerWeaponLoop(key: String) = stopWeaponLoop(key, LOOP_OWNER_PLAYER)

    fun stopPlayerWeaponLoops() {
        stopLoopsWhere { it.owner == LOOP_OWNER_PLAYER && it.key in HOLD_LOOP_KEYS }
    }

    fun stopEnemyWeaponLoops() {
        stopLoopsWhere { it.owner == LOOP_OWNER_ENEMY && it.key in HOLD_LOOP_KEYS }
    }

    /** Stops every held firing loop of both fighters. */
    fun stopWeaponLoops() {
        stopLoopsWhere { it.key in HOLD_LOOP_KEYS }
        engine.stopWhere { it.loop && it.key in HOLD_LOOP_KEYS }
    }

    /**
     * Stops one owner's weapon sounds: its held loop and its bounded shots.
     * The other fighter's sounds are never touched.
     */
    fun stopWeaponSound(key: String, owner: String) {
        val canonical = AUTO_LOOP_OF[key] ?: key
        stopLoopsWhere { it.owner == owner && it.key == canonical }
        engine.stopWhere { it.owner == owner && (AUTO_LOOP_OF[it.key] ?: it.key) == canonical }
    }

    /** Stops every weapon sound, both fighters and all held loops. */
    fun stopWeaponSounds() {
        stopWeaponLoops()
        engine.stopWhere { it.key in WEAPON_SOUND_KEYS }
    }

    // ------------------------------------------------------------ footsteps

    /**
     * Updates Robert's footsteps: louder as he approaches, quieter as he
     * retreats, panned by a genuine sideways offset, and the rate follows his
     * speed in metres per second. The rate changes ONLY with the speed, so the
     * recording is never shifted off its natural pitch while he walks.
     */
    fun updateEnemyWalk3dAudio(
        distance: Float,
        lateralOffset: Float = 0f,
        speedMps: Float = 0f,
        fleeing: Boolean = false
    ) {
        val safeDistance = distance.coerceIn(0f, Combat.MAX_RANGE)
        val effectiveSpeed = when {
            speedMps > 0f -> speedMps
            fleeing -> 12f
            else -> 5f
        }
        val base = walkVolume * VolumeMixer.walkDistanceGain(safeDistance, Combat.MAX_RANGE)
        val (panLeft, panRight) = VolumeMixer.panGains(VolumeMixer.panFor(lateralOffset, safeDistance))
        updateWalkingTrack(
            key = "enemyWalk",
            left = (base * panLeft).coerceIn(0f, 1f),
            right = (base * panRight).coerceIn(0f, 1f),
            speedMps = effectiveSpeed
        )
    }

    /**
     * The local player's footsteps stay centred at the speech level. The rate
     * follows the running speed and [volumeGain] is the running-level gain.
     */
    fun updatePlayerWalkAudio(
        speedMps: Float = RunPressure.BASE_SPEED_MPS,
        volumeGain: Float = 1.0f
    ) {
        val base = VolumeMixer.walkLevel(walkVolume, distanceGain = 1f, speedGain = volumeGain)
        updateWalkingTrack(key = "playerWalk", left = base, right = base, speedMps = speedMps)
    }

    private fun updateWalkingTrack(
        key: String,
        left: Float,
        right: Float,
        speedMps: Float,
        rateScale: Float = 1f
    ) {
        if (released) return
        loopSlot(
            key = key,
            owner = "",
            left = left.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            rate = VolumeMixer.walkRate(speedMps) * rateScale,
            startMs = WALK_START_OFFSET_MS[key] ?: 0L
        )
    }

    /** Stops both footstep tracks. */
    fun stopFootsteps() {
        stopLoopsWhere { it.key in WALK_KEYS }
        engine.stopWhere { it.key in WALK_KEYS }
    }

    // ------------------------------------------------------- stop and pause

    /** Stops one sound key: every voice and loop of it. */
    fun stopSound(key: String) {
        stopLoopsWhere { it.key == key }
        engine.stopWhere { it.key == key }
        // A chain requested just before this stop may not be in the mixer yet;
        // the chain queue runs this second stop after it, at top priority.
        audioScope.launch(chainDispatcher) {
            engine.stopWhere { it.key == key }
        }
    }

    /** Stops a held loop of [key] (both owners' loops of that key are stopped). */
    fun stopLoop(key: String) = stopSound(key)

    /** Stops the sounds of the current screen without stopping the music. */
    fun stopScreenAudio() {
        stopWeaponSounds()
        for (key in listOf("win", "lose", "parachute", "airplane", "airplaneEngine", "jump", "land")) {
            stopSound(key)
        }
        stopFootsteps()
        stopBattleProjectiles()
        stopCarSounds()
    }

    /** Stops every projectile sound of a battle, including the progression stings. */
    fun stopBattleProjectiles() {
        stopWeaponSounds()
        for (key in listOf(
            "missile", "explosion", "atomBomb", "grenade", "timerLoop",
            "timerBomb", "levelComplete", "roundComplete"
        )) {
            stopSound(key)
        }
    }

    /** Stops the bulletproof car: its engine, movement, doors and any crash. */
    fun stopCarSounds() {
        for (key in CAR_KEYS) stopSound(key)
    }

    /** Stops every loop and battle sound, leaving the background music running. */
    fun stopAllLoops() {
        stopScreenAudio()
        stopLoopsWhere { true }
        engine.stopWhere { true }
    }

    /** Stops every sound, including the background music. */
    fun stopAllSounds() {
        stopAllLoops()
        stopMusic()
    }

    /**
     * Freezes the battle: every voice stops advancing and new requests wait
     * in the queue until [resumeAllSounds]. The music pauses too.
     */
    fun pauseAllSounds() {
        engine.paused = true
        val player = music ?: return
        try {
            if (player.isPlaying) player.pause()
        } catch (_: Exception) {
        }
    }

    /** Continues a frozen battle exactly where it stopped. */
    fun resumeAllSounds() {
        engine.paused = false
        if (!musicWanted) return
        val player = music ?: return
        try {
            if (!player.isPlaying) player.start()
        } catch (_: Exception) {
        }
    }

    /** True while a sound or loop of [key] is playing. */
    fun isTrackActive(key: String): Boolean {
        if (key == MUSIC_KEY) return musicWanted
        return engine.live.values.any { it.key == key } || loops.values.any { it.key == key }
    }

    /** Measured length of a sound in milliseconds, or 0 when it is unknown. */
    fun soundDurationMs(key: String): Long {
        val known = SOUND_DURATIONS_MS[key]
        if (known != null && known > 0L) return known
        val file = SOUND_FILES[key] ?: return 0L
        return clips[file]?.durationMs ?: 0L
    }

    // ------------------------------------------------------------ music

    /** Starts the looping background music at the level of the user's music setting. */
    fun startMusic() {
        if (released) return
        musicWanted = true
        val existing = music
        if (existing != null) {
            applyMusicVolume()
            if (!engine.paused) {
                try {
                    if (!existing.isPlaying) existing.start()
                } catch (_: Exception) {
                }
            }
            return
        }
        val player = MediaPlayer()
        try {
            context.assets.openFd("sounds/music.ogg").use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.isLooping = true
            val level = VolumeMixer.music(musicVolume)
            player.setVolume(level, level)
            player.setOnPreparedListener { prepared ->
                if (musicWanted && !engine.paused) {
                    try {
                        prepared.start()
                    } catch (_: Exception) {
                    }
                }
            }
            player.prepareAsync()
            music = player
        } catch (_: Exception) {
            player.release()
        }
    }

    fun stopMusic() {
        musicWanted = false
        val player = music ?: return
        music = null
        try {
            player.stop()
        } catch (_: Exception) {
        }
        player.release()
    }

    /** Applies the stored music setting to the live player. */
    private fun applyMusicVolume() {
        val level = VolumeMixer.music(musicVolume)
        try {
            music?.setVolume(level, level)
        } catch (_: Exception) {
        }
    }

    fun updateTheme(theme: SoundTheme) {
        // The theme is stored for the settings screen; it never changes the
        // pitch of any sound. Every recording plays at its natural pitch.
        soundTheme = theme
    }

    // ------------------------------------------------------------ haptics

    private fun triggerHapticForSound(key: String) {
        if (battleActive || !hapticsEnabled) return
        val target = vibrator ?: return
        val durationMs = when (key) {
            "assault", "shortGun", "pistol", "playerHit", "machineGun", "awm" -> 60L
            "gunHit", "glueSound", "cover", "gunReload", "wall" -> 35L
            else -> return
        }
        try {
            target.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ lifecycle

    /** Releases every sound. Later requests are ignored. */
    fun release() {
        if (released) return
        released = true
        stopMusic()
        stopLoopsWhere { true }
        engine.shutdown()
        audioScope.cancel()
        warmExecutor.shutdownNow()
        decodeExecutor.shutdownNow()
        chainExecutor.shutdownNow()
        clips.clear()
    }
}
