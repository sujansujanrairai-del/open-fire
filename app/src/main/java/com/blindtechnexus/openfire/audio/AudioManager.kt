package com.blindtechnexus.openfire.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.blindtechnexus.openfire.audio.native.OpenFireAudioEngine
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.FireCadence
import com.blindtechnexus.openfire.model.RunPressure
import com.blindtechnexus.openfire.model.SoundDurations
import com.blindtechnexus.openfire.model.SoundTheme
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The game's sound system, built on the native Oboe engine ([NativeMixer]).
 *
 * Design
 * ------
 *  - **Mixing is C++.** Every effect is a voice inside `libopenfire_audio.so`.
 *    One Oboe callback renders all of them on the low-latency, exclusive
 *    float stream, so a sound starts on the very next callback (a few
 *    milliseconds) instead of on the next platform-player state change.
 *  - **Placement belongs to the engine.** A voice carries a pan, a distance
 *    and a front/rear sign; the engine applies the distance curve, the
 *    constant-power pan, a fractional interaural delay and a head shadow. A
 *    source therefore really travels from far away to nearby and back out.
 *  - **Two fighters, one recording.** Robert walks on the same `walk.ogg` as
 *    the player. They stay distinguishable through headphones because they
 *    differ in placement, in arrival time, in pitch (the enemy is always a
 *    little lower) and in loop phase (each start offset is different). No
 *    mono sum of the two ever cancels them into one source.
 *  - **Pacing is a rate.** Footsteps follow the three running levels with
 *    three clearly different rates - slow, natural, fast - and a projectile
 *    rises in pitch as it closes in.
 *  - **This class keeps the game's vocabulary** so no call site of the battle
 *    engine had to change: sound keys, loop ownership, bounded automatic
 *    bursts, wall and car sequences, speech/effects balance and haptics.
 *  - **Music is the one platform track.** The long background tune loops on a
 *    [MediaPlayer]; it is the only streamed sound and it keeps its own volume
 *    setting, separate from the effects bus.
 *
 * A device whose ABI is absent, or whose stream cannot open, ends up with
 * [nativeAvailable] false: the game runs silently instead of crashing, and
 * the music still plays.
 */
class AudioManager(private val context: Context) {

    companion object {
        const val LOOP_OWNER_PLAYER = "player"
        const val LOOP_OWNER_ENEMY = "enemy"

        /** Both owners, in the order every bulk clean-up walks them. */
        val LOOP_OWNERS: List<String> = listOf(LOOP_OWNER_PLAYER, LOOP_OWNER_ENEMY)

        /** Every sound key and the asset it plays, in `assets/sounds`. */
        val SOUND_FILES: Map<String, String> = SoundLibrary.SOUND_FILES

        /** The one-shot copy of each automatic weapon, mapped to its loop key. */
        val AUTO_LOOP_OF: Map<String, String> = SoundLibrary.AUTO_LOOP_OF

        /** The three continuous-fire weapons. Each one fires on a held loop. */
        val AUTO_LOOP_KEYS: Set<String> = SoundLibrary.AUTO_LOOP_KEYS

        /** The single-shot copies of the automatic weapons. */
        val AUTO_SHOT_KEYS: Set<String> = SoundLibrary.AUTO_SHOT_KEYS

        /** Every key an automatic weapon can be heard with. */
        val AUTO_WEAPON_KEYS: Set<String> = SoundLibrary.AUTO_WEAPON_KEYS

        /** Held loops: the automatic weapons, the shock hum and the car. */
        val HOLD_LOOP_KEYS: Set<String> = SoundLibrary.HOLD_LOOP_KEYS

        /** Footstep tracks: each fighter walks on his own loop of the same recording. */
        val WALK_KEYS: Set<String> = SoundLibrary.WALK_KEYS

        /**
         * Start offsets of the footstep loops. Both fighters use the same walk
         * recording, so starting both at zero would phase-lock them into one
         * source.
         */
        val WALK_START_OFFSET_MS: Map<String, Long> = SoundLibrary.WALK_START_OFFSET_MS

        /** Every key that belongs to a weapon. Screen changes stop all of them. */
        val WEAPON_SOUND_KEYS: Set<String> = SoundLibrary.WEAPON_SOUND_KEYS

        /** Keys of the car sounds. They stop together when the battle ends. */
        val CAR_KEYS: Set<String> = SoundLibrary.CAR_KEYS

        /** The car loops that are not firing loops: the idle engine note. */
        val CAR_IDLE_LOOP_KEYS: Set<String> = SoundLibrary.CAR_IDLE_LOOP_KEYS

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

        /**
         * Pitch separation of the enemy's footsteps: he uses the same walk
         * recording as the player, so a small, deliberate rate difference is
         * what keeps the two loops from ever fusing into one source. Six
         * percent is clearly audible and still natural.
         */
        const val ENEMY_WALK_RATE_FACTOR = 0.94f

        private const val MUSIC_KEY = "music"
    }

    /**
     * A held loop: its owner, its sound, the two channel gains it is heard
     * with, its playback rate and where it sits in the head model.
     *
     * The channel gains and the binaural position are what the game reads back
     * (a walk that comes closer is louder, a source behind the listener has a
     * positive [spatialPosition].rear). The native engine is the owner of the
     * voice itself; this slot is the game-level record of one loop.
     */
    private class LoopSlot(
        val key: String,
        val owner: String,
        @Volatile var left: Float,
        @Volatile var right: Float,
        @Volatile var rate: Float,
        @Volatile var spatialPosition: BinauralPosition?,
        val startMs: Long
    )

    /** The native mix bus. Every effect goes through it. */
    internal val mixer = NativeMixer(context)

    /** True when the native engine is open: effects are audible. */
    val nativeAvailable: Boolean get() = mixer.available

    /**
     * The mix bus, under the name the audio tests and the settings screen use.
     * [NativeMixer] is the engine of this build: it owns the Oboe stream, the
     * clip cache and the SFX bus gain.
     */
    internal val engine: NativeMixer get() = mixer

    /** Held loops by `owner|key`, in the order they were opened. */
    private val loops = ConcurrentHashMap<String, LoopSlot>()

    /** Guards the create-or-move of a loop, so two callers cannot open two. */
    private val loopLock = Any()

    @Volatile
    private var released = false

    private var music: MediaPlayer? = null

    @Volatile
    private var musicWanted = false

    /** True once a broken music player has been rebuilt at this level. */
    @Volatile
    private var musicRebuildTried = false

    /** The handle of the projectile voice that carries the approach cue. */
    @Volatile
    private var projectileHandle: Int = 0

    @Volatile
    private var projectileLaunchM: Float = 0f

    /**
     * The user's effects setting. It is the single source of truth and it is
     * applied LIVE: the engine applies the SFX bus gain on every block, so a
     * change is heard on the sounds that are already playing and no voice is
     * ever stuck at the level it was created with.
     */
    var sfxVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.clamp(value)
            mixer.sfxVolume = field
        }

    var musicVolume: Float = 1.0f
        set(value) {
            field = VolumeMixer.clamp(value)
            applyMusicVolume()
        }

    /** Compatibility alias: footsteps are on the same SFX bus as other effects. */
    var walkVolume: Float
        get() = sfxVolume
        set(value) { sfxVolume = value }

    /**
     * The sound theme names the flavour of the battlefield. It never changes
     * the pitch of anything: every recording plays at its natural pitch.
     */
    var soundTheme: SoundTheme = SoundTheme.TACTICAL

    /** Haptics belong to the menus only. */
    var hapticsEnabled: Boolean = false

    /** True while the battle screen is open: the hard switch that mutes every vibration. */
    @Volatile
    var battleActive: Boolean = false

    /**
     * The platform audio manager, used only to follow the output route: the
     * engine renders a binaural mix on headphones and a crossfeed mix on the
     * phone's own speaker, because the two need genuinely different spatial
     * models to sound right. Without this switch a hard-panned enemy is heard
     * in one ear only on a speaker, and the arena collapses onto one side.
     */
    private val platformAudio: android.media.AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    /** Told when a headset is plugged in or out, so the model can follow. */
    private var deviceCallback: AudioDeviceCallback? = null

    /** The route the engine is currently rendering for. */
    @Volatile
    var headphoneMode: Boolean = true
        private set

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        // The SFX bus is applied once per audio block, so the very first click
        // is already at the level the user chose.
        mixer.sfxVolume = sfxVolume
        followOutputRoute()
    }

    // ------------------------------------------------------- output route

    /**
     * Applies the spatial model that matches the current output device and
     * keeps following it: plugging in a headset switches the engine to the
     * binaural model, unplugging it switches back to the speaker crossfeed.
     */
    private fun followOutputRoute() {
        headphoneMode = detectHeadphones()
        mixer.setOutputMode(headphoneMode)
        val manager = platformAudio ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                applyOutputRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                applyOutputRoute()
            }
        }
        deviceCallback = callback
        try {
            manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {
            deviceCallback = null
        }
    }

    /** Re-reads the route and applies it to the engine. */
    private fun applyOutputRoute() {
        val now = detectHeadphones()
        if (now == headphoneMode) return
        headphoneMode = now
        mixer.setOutputMode(now)
    }

    /**
     * True when a headphone-style output (wired headset, Bluetooth headset,
     * USB or BLE headset) is connected. Anything else - the phone's own
     * speaker, a Bluetooth speaker, a dock - gets the crossfeed model, which
     * is what makes the 3D mix usable without headphones.
     */
    @Suppress("DEPRECATION")
    private fun detectHeadphones(): Boolean {
        val manager = platformAudio ?: return true
        val devices = try {
            manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        } catch (_: Throwable) {
            return true
        }
        for (device in devices) {
            if (isHeadphoneRoute(device.type)) return true
        }
        return false
    }

    /**
     * True for the device types that put one driver on each ear.
     *
     * The newer types are read inside a version check instead of being named
     * in a `when`, so this file compiles and passes lint against the whole
     * supported range (24..34) without an `@RequiresApi` escape hatch.
     */
    private fun isHeadphoneRoute(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            type == AudioDeviceInfo.TYPE_USB_HEADSET
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            type == AudioDeviceInfo.TYPE_BLE_HEADSET
        ) {
            return true
        }
        return false
    }

    // ------------------------------------------------------------- spatial

    /**
     * 3D panning and distance attenuation of one source.
     *
     * [x] is the sideways offset, [z] the distance along the line of sight. A
     * sound with no real sideways offset is centred exactly, so running
     * straight at the enemy never smears the image between the channels.
     */
    fun calculate3dVolume(
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 0f,
        baseVol: Float = 1.0f,
        key: String = ""
    ): Pair<Float, Float> = calculateSourceGains(x, y, z, baseVol, key)

    private fun calculateSourceGains(
        x: Float,
        y: Float,
        z: Float,
        baseVol: Float,
        key: String
    ): Pair<Float, Float> {
        val dist = sqrt(x * x + y * y + z * z)
        val distanceGain = VolumeMixer.distanceGain(dist, Combat.MAX_RANGE)
        val (left, right) = VolumeMixer.panGains(VolumeMixer.panFor(x, dist))
        val total = VolumeMixer.mix(key, 1f, effectiveBaseVolume(key, baseVol), distanceGain)
        return Pair(total * left, total * right)
    }

    /**
     * The level one requested sound is played with. The loud weapons are
     * always played at full scale, so they can never be quieter than the
     * speech that announces the battle; the SFX slider still scales the whole
     * bus exactly once, inside the engine.
     */
    private fun effectiveBaseVolume(key: String, requested: Float): Float =
        if (VolumeMixer.isLoudSfx(key)) 1.0f else requested

    /** Distance gain of a placed source, on the arena's own scale. */
    private fun distanceGainOf(place: SpatialPlace): Float =
        VolumeMixer.distanceGain(place.distance, Combat.MAX_RANGE)

    /**
     * Where a positional call site's raw coordinates sit: [x] is the sideways
     * offset and [z] follows the game's own convention, a negative z being in
     * front of the listener (`posZ = -distance`).
     */
    private fun placeOf(x: Float, z: Float): SpatialPlace = SpatialPlace.fromZ(x, z)

    /** The player's automatic gun is in their hands, never at the target. */
    private fun automaticWeaponPlace(key: String, owner: String, x: Float, z: Float): SpatialPlace? {
        if (owner == LOOP_OWNER_PLAYER && key in AUTO_WEAPON_KEYS) return null
        return if (x == 0f && z == 0f) null else placeOf(x, z)
    }

    /** Where a source sits from its sideways offset and its forward distance. */
    private fun placeAhead(lateral: Float, forward: Float): SpatialPlace =
        SpatialPlace.fromOffset(lateral, forward)

    /** The owner id the engine uses for a loop owner string. */
    private fun ownerIdOf(owner: String): Int = when (owner) {
        LOOP_OWNER_PLAYER -> OpenFireAudioEngine.OWNER_PLAYER
        LOOP_OWNER_ENEMY -> OpenFireAudioEngine.OWNER_ENEMY
        else -> OpenFireAudioEngine.OWNER_NONE
    }

    // ------------------------------------------------------- one-shot sounds

    /**
     * Plays a sound effect as a new voice. Any number of the same sound may
     * overlap. Returns a voice handle, or -1 when the sound is unknown.
     *
     * The position is (sideways, forward) relative to the listener, so a
     * source on the right is heard on the right and a source in front of the
     * listener is heard in front of him.
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
        if (key in AUTO_WEAPON_KEYS) {
            return if (playAutomaticBurst(
                    key,
                    MAX_STRAY_AUTOMATIC_SHOT_MS,
                    volume = volume,
                    posX = posX,
                    posY = posY,
                    posZ = posZ
                )
            ) 1 else -1
        }
        val place = if (posX == 0f && posZ == 0f) null else placeOf(posX, posZ)
        val approach = if (SoundLibrary.isProjectile(key)) 0f else -1f
        val handle = mixer.playOneShot(
            key = key,
            volume = effectiveBaseVolume(key, volume),
            rate = VolumeMixer.naturalRate(rate),
            place = place,
            deadlineMs = if (key == "parachute") 2_000 else 0,
            approachProximity = approach
        )
        if (SoundLibrary.isProjectile(key)) {
            projectileHandle = handle
            projectileLaunchM = sqrt(posX * posX + posY * posY + posZ * posZ)
        }
        triggerHapticForSound(key)
        return handle
    }

    /**
     * Plays [keys] one after another with no gap and, when [loopKey] is
     * given, starts that loop on the frame the last sound ends: the engine
     * start followed by the moving loop, a blast followed by the crash.
     */
    fun playSequence(keys: List<String>, loopKey: String? = null, volume: Float = 1.0f) {
        if (released || keys.isEmpty()) return
        if (keys.any { !SOUND_FILES.containsKey(it) }) return
        if (loopKey != null && !SOUND_FILES.containsKey(loopKey)) return
        mixer.playSequence(keys, loopKey, volume)
    }

    /**
     * Plays one sound exactly once, never twice on top of itself. A repeated
     * request - a brake that fires on every movement tick, a wall that is
     * touched again and again - plays the recording once.
     */
    fun playOnce(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        if (released) return
        val place = if (posX == 0f && posZ == 0f) null else placeOf(posX, posZ)
        mixer.playOnce(key, volume = effectiveBaseVolume(key, volume), place = place)
    }

    /** Prepare a group of clips without blocking the game thread. */
    fun prepareSounds(keys: List<String>) {
        if (!released) mixer.prepareSounds(keys)
    }

    /** Plays several sounds at once, as one mix. */
    fun playMixed(
        keys: List<String>,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) {
        val place = if (posX == 0f && posZ == 0f) null else placeOf(posX, posZ)
        mixer.playMixed(keys, volume = volume, place = place)
    }

    /** Plays a weapon sound at a real position, relative to the listener. */
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
                posZ = -depthOffset
            )
            return
        }
        playSound(key, volume = volume, rate = rate, posX = lateralOffset, posZ = -depthOffset)
    }

    /** Plays a weapon sound. Automatic weapons play as a bounded single shot. */
    fun playWeaponSound(
        key: String,
        volume: Float = 1.0f,
        rate: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f
    ) = playSound(key, volume = volume, rate = rate, posX = posX, posY = posY, posZ = posZ)

    /**
     * One bounded shot of an automatic weapon. It fades out after [holdMs]
     * (at least [MIN_BURST_MS]) so one shot never rings for a whole
     * multi-second recording, and [stopWeaponSound] can cut it.
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
        val burstMs = max(holdMs, MIN_BURST_MS).toInt()
        val place = automaticWeaponPlace(key, owner, posX, posZ)
        val handle = mixer.playOneShot(
            key = key,
            volume = effectiveBaseVolume(key, volume),
            rate = 1f,
            place = place,
            owner = ownerIdOf(owner),
            deadlineMs = burstMs
        )
        return handle >= 0
    }

    /**
     * Starts the flight sound of a projectile - the missile, a thrown bomb.
     * The distance is where it was launched from, which is what makes it
     * "come from far away" and swell as it closes in.
     */
    fun startProjectileFlight(key: String, launchDistance: Float, loop: Boolean = true) {
        if (released || !SOUND_FILES.containsKey(key)) return
        val distance = launchDistance.coerceIn(0f, Combat.MAX_RANGE)
        projectileLaunchM = distance
        val place = SpatialPlace.projectile(distance)
        if (loop) {
            // The flight sound is a loop: it ends when the projectile does, and
            // it is retuned on every approach step.
            startHeldLoop(key = key, volume = 1f, rate = 1f, place = place, owner = "")
            projectileHandle = 0
        } else {
            projectileHandle = mixer.playOneShot(
                key = key,
                volume = 1f,
                rate = 1f,
                place = place,
                approachProximity = 0f
            )
        }
    }

    /**
     * Moves the projectile's approach cue: [remainingM] metres away, closing
     * to zero at impact. The engine raises the pitch and the level as it comes.
     */
    fun updateProjectileApproach(remainingM: Float, launchM: Float = projectileLaunchM) {
        if (released) return
        val proximity = SpatialPlace.approachProximity(remainingM, launchM)
        val place = SpatialPlace.projectile(remainingM)
        val rate = VolumeMixer.naturalRate(VolumeMixer.approachRate(proximity))
        val loopSlot = loops.entries.firstOrNull { it.value.key in SoundLibrary.PROJECTILE_KEYS }
        if (loopSlot != null) {
            val slot = loopSlot.value
            slot.rate = rate
            val gain = distanceGainOf(place)
            slot.left = gain
            slot.right = gain
            slot.spatialPosition = BinauralPosition.fromCoordinates(0f, -place.distance)
            mixer.updateLoop(
                key = slot.key,
                volume = 1f,
                rate = rate,
                place = place,
                owner = ownerIdOf(slot.owner)
            )
            return
        }
        mixer.updateHandle(projectileHandle, rate = rate, place = place, approachProximity = proximity)
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
        val place = if (posX == 0f && posZ == 0f) null else placeOf(posX, posZ)
        startHeldLoop(
            key = key,
            volume = effectiveBaseVolume(key, volume),
            rate = VolumeMixer.naturalRate(rate),
            place = place,
            owner = ""
        )
    }

    /**
     * Starts, or keeps, the held firing loop of an automatic weapon for one
     * fighter. Calling it again for every bullet is safe: the same native
     * voice is reused and only its position and level are refreshed.
     */
    fun startWeaponLoop(
        key: String,
        volume: Float = 1.0f,
        posX: Float = 0f,
        posY: Float = 0f,
        posZ: Float = 0f,
        owner: String = LOOP_OWNER_PLAYER,
        startOffsetMs: Long = 0L
    ) {
        if (released) return
        val loopKey = holdKeyOf(key) ?: return
        val place = automaticWeaponPlace(loopKey, owner, posX, posZ)
        startHeldLoop(
            key = loopKey,
            volume = effectiveBaseVolume(loopKey, volume),
            rate = 1f,
            place = place,
            owner = owner,
            startOffsetMs = startOffsetMs
        )
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

    /**
     * The held-loop key of [key] (a loop key or a single-shot copy), or null
     * when the key is not a loop at all. The car's idle note and the shock hum
     * are held loops too, so any catalogue key may be started as a loop.
     */
    private fun holdKeyOf(key: String): String? {
        val loopKey = AUTO_LOOP_OF[key] ?: key
        if (loopKey in HOLD_LOOP_KEYS || loopKey in CAR_IDLE_LOOP_KEYS) return loopKey
        return if (SoundLibrary.isLoopKey(loopKey)) loopKey else null
    }

    /** The key of a loop slot, `owner|key`. */
    private fun loopSlotKey(owner: String, key: String): String = "$owner|$key"

    /**
     * Opens the loop of `owner|key`, or moves the one that is already open.
     *
     * The channel gains and the position the game sees are always recomputed
     * from [volume] and [place] here, so a loop that comes closer is louder and
     * a loop that crosses the listener changes ear - the same behaviour the
     * software mixer had, now rendered by the native engine.
     *
     * @param volume the level of the loop before its distance gain
     * @param place where the loop is, or null when it is not positional
     * @param rate the playback rate, already inside the natural window
     * @return the native voice id, or 0 while the clip is still decoding
     */
    private fun startHeldLoop(
        key: String,
        volume: Float,
        rate: Float,
        place: SpatialPlace?,
        owner: String,
        startOffsetMs: Long = 0L,
        startDelayMs: Long = 0L,
        critical: Boolean = false
    ): Int {
        if (released || !SOUND_FILES.containsKey(key)) return 0
        val slotKey = loopSlotKey(owner, key)
        val engineOwner = ownerIdOf(owner)
        // A loop is heard exactly like a one-shot at the same place: the same
        // distance curve, the same centre dead zone, the same once-only slider.
        val gains = if (place != null) {
            val lateral = place.pan * place.distance
            val depth = -place.rear * place.distance
            calculateSourceGains(lateral, 0f, depth, volume, key)
        } else {
            // A centred / in-hand loop is normalised by the asset's own
            // measured loudness, exactly like a placed one: a quiet recording
            // such as the car engine is heard at the level of a gun.
            val level = (effectiveBaseVolume(key, volume) *
                VolumeMixer.assetGainFor(key)).coerceIn(0f, 1f)
            Pair(level, level)
        }
        val left = gains.first
        val right = gains.second
        // The game's own z convention: a negative z is in front of the
        // listener, and [SpatialPlace.rear] already carries that sign.
        val position = if (place != null) {
            BinauralPosition.fromCoordinates(
                place.pan * place.distance,
                place.rear * place.distance
            )
        } else {
            null
        }

        synchronized(loopLock) {
            val existing = loops[slotKey]
            if (existing != null) {
                existing.left = left
                existing.right = right
                existing.rate = rate
                existing.spatialPosition = position
                mixer.startLoop(
                    key = key,
                    volume = volume,
                    rate = rate,
                    place = place,
                    owner = engineOwner,
                    startOffsetMs = startOffsetMs.toInt(),
                    critical = critical || key in HOLD_LOOP_KEYS
                )
                return mixer.nativeLoopId(key, engineOwner)
            }
            val slot = LoopSlot(key, owner, left, right, rate, position, startOffsetMs)
            loops[slotKey] = slot
            // The slot exists before the voice does: a loop requested on a cold
            // cache is started by the mixer as soon as its clip is decoded, and
            // a stop in the meantime simply removes the slot.
            // The mixer starts the voice now, or queues it for the moment its
            // clip is decoded (a cold cache never loses a loop).
            return mixer.startLoop(
                key = key,
                volume = volume,
                rate = rate,
                place = place,
                owner = engineOwner,
                startOffsetMs = startOffsetMs.toInt(),
                startDelayMs = startDelayMs.toInt(),
                critical = critical || key in HOLD_LOOP_KEYS
            )
        }
    }

    private fun stopLoopsWhere(predicate: (LoopSlot) -> Boolean) {
        synchronized(loopLock) {
            val doomed = loops.entries.filter { predicate(it.value) }
            for (entry in doomed) {
                loops.remove(entry.key, entry.value)
                mixer.stopLoop(entry.value.key, ownerIdOf(entry.value.owner))
            }
        }
    }

    /** True while [owner] (player or enemy) still holds the loop of [key]. */
    fun isWeaponLoopActive(key: String, owner: String = LOOP_OWNER_PLAYER): Boolean {
        val loopKey = holdKeyOf(key) ?: return false
        return loops.containsKey(loopSlotKey(owner, loopKey))
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
    }

    /**
     * Stops one owner's weapon sounds: its held loop and its bounded shots.
     * The other fighter's sounds are never touched.
     */
    fun stopWeaponSound(key: String, owner: String) {
        val canonical = AUTO_LOOP_OF[key] ?: key
        stopLoopsWhere { it.owner == owner && it.key == canonical }
        mixer.stopOwnerKey(ownerIdOf(owner), canonical)
        mixer.stopOwnerKey(ownerIdOf(owner), key)
    }

    /** Stops every weapon sound, both fighters and all held loops. */
    fun stopWeaponSounds() {
        // A group still decoding must not start after leaving the battle.
        mixer.cancelPendingMixes()
        stopWeaponLoops()
        for (key in WEAPON_SOUND_KEYS) mixer.stopKey(key)
    }

    // ------------------------------------------------------------ footsteps

    /**
     * Updates Robert's footsteps: louder as he approaches, quieter as he
     * retreats, on the correct ear, and paced by his real speed. He is always
     * slightly lower in pitch than the player ([ENEMY_WALK_RATE_FACTOR]) and
     * always starts on a different offset of the same recording, so the two
     * walkers never fuse into one source.
     */
    fun updateEnemyWalk3dAudio(
        distance: Float,
        lateralOffset: Float = 0f,
        speedMps: Float = 0f,
        fleeing: Boolean = false,
        depthOffset: Float = distance
    ) {
        val effectiveSpeed = when {
            speedMps > 0f -> speedMps
            fleeing -> RunPressure.BASE_SPEED_MPS * 3f
            else -> RunPressure.BASE_SPEED_MPS
        }
        updateWalkingTrack(
            key = "enemyWalk",
            volume = 1f,
            speedMps = effectiveSpeed,
            place = placeAhead(lateralOffset, depthOffset),
            rateScale = ENEMY_WALK_RATE_FACTOR
        )
    }

    /**
     * Refreshes a moving enemy when the listener moves, without ever
     * restarting his loop: only his gains and his position change.
     */
    fun refreshEnemyWalkPosition(
        distance: Float,
        lateralOffset: Float,
        depthOffset: Float = distance
    ) {
        val slot = loops[loopSlotKey("", "enemyWalk")] ?: return
        val safeDistance = distance.coerceIn(0f, Combat.MAX_RANGE)
        val place = placeAhead(lateralOffset, depthOffset)
        val base = VolumeMixer.walkDistanceGain(safeDistance, Combat.MAX_RANGE)
        val pan = VolumeMixer.panGains(VolumeMixer.panFor(lateralOffset, safeDistance))
        slot.left = (base * pan.first).coerceIn(0f, 1f)
        slot.right = (base * pan.second).coerceIn(0f, 1f)
        slot.spatialPosition = BinauralPosition.fromCoordinates(lateralOffset, -depthOffset)
        // The mixer applies spatial attenuation itself; do not apply that curve twice.
        mixer.updateLoop(key = "enemyWalk", volume = 1f, rate = slot.rate, place = place)
    }

    /**
     * The local player's footsteps: centred, like a sound from the listener's
     * own body. The rate follows the running level, which is what makes a
     * slow, a normal and a fast run sound different.
     */
    fun updatePlayerWalkAudio(
        speedMps: Float = RunPressure.BASE_SPEED_MPS,
        volumeGain: Float = 1.0f,
        playbackRate: Float = VolumeMixer.walkRateForSpeed(speedMps)
    ) {
        val base = VolumeMixer.walkLevel(1f, distanceGain = 1f, speedGain = volumeGain)
        updateWalkingTrack(key = "playerWalk", volume = base, speedMps = speedMps, place = null, playbackRate = playbackRate)
    }

    private fun updateWalkingTrack(
        key: String,
        volume: Float,
        speedMps: Float,
        place: SpatialPlace?,
        rateScale: Float = 1f,
        playbackRate: Float = VolumeMixer.walkRateForSpeed(speedMps) * rateScale
    ) {
        if (released) return
        // The steps follow the real speed smoothly, inside the natural window.
        // The three-level ladder of footstepRate() stays available for a caller
        // that wants a hard step between two paces.
        val rate = playbackRate
        startHeldLoop(
            key = key,
            volume = volume.coerceIn(0f, 1f),
            rate = VolumeMixer.naturalRate(rate),
            place = place,
            owner = "",
            startOffsetMs = WALK_START_OFFSET_MS[key] ?: 0L
        )
    }

    /** Stops both footstep tracks. */
    fun stopFootsteps() {
        stopLoopsWhere { it.key in WALK_KEYS }
        for (key in WALK_KEYS) mixer.stopKey(key)
    }

    // ------------------------------------------------------- stop and pause

    /** Stops one sound key: every voice and loop of it. */
    fun stopSound(key: String) {
        stopLoopsWhere { it.key == key }
        mixer.stopKey(key)
        if (key in SoundLibrary.PROJECTILE_KEYS) projectileHandle = 0
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
        projectileHandle = 0
    }

    /** Stops the bulletproof car: its engine, movement, doors and any crash. */
    fun stopCarSounds() {
        for (key in CAR_KEYS) stopSound(key)
    }

    /** Stops every loop and battle sound, leaving the background music running. */
    fun stopAllLoops() {
        loops.clear()
        mixer.stopAll()
    }

    /** Stops every sound, including the background music. */
    fun stopAllSounds() {
        stopAllLoops()
        stopMusic()
    }

    /** Freezes the battle: the engine stops advancing every voice, and so does the music. */
    fun pauseAllSounds() {
        mixer.pause()
        val player = music ?: return
        try {
            if (player.isPlaying) player.pause()
        } catch (_: Exception) {
        }
    }

    /** Continues a frozen battle exactly where it stopped. */
    fun resumeAllSounds() {
        mixer.resume()
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
        return loops.values.any { it.key == key } || mixer.isKeyActive(key)
    }

    /** Measured length of a sound in milliseconds, or 0 when it is unknown. */
    fun soundDurationMs(key: String): Long {
        val known = SOUND_DURATIONS_MS[key]
        if (known != null && known > 0L) return known
        return SoundDurations.forKey(key)
    }

    // ------------------------------------------------------------ music

    /** Starts the looping background music at the level of the user's music setting. */
    fun startMusic() {
        if (released) return
        musicWanted = true
        // A menu is never a frozen battle. A match paused with the three-finger
        // gesture leaves the whole engine paused, and without resuming it here
        // the theme - and every menu effect after it - would stay silent for
        // the rest of the process.
        mixer.resume()
        val existing = music
        if (existing != null) {
            applyMusicVolume()
            if (!mixer.paused) {
                try {
                    if (!existing.isPlaying) existing.start()
                } catch (_: Exception) {
                }
            }
            return
        }
        buildMusicPlayer()
    }

    /**
     * Creates a looping music player and starts it as soon as it is prepared.
     *
     * A track the platform refuses (a codec fault, a route that just moved)
     * drops the player and rebuilds it once instead of leaving the game silent
     * until the next screen change.
     */
    private fun buildMusicPlayer() {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            context.assets.openFd("sounds/music.ogg").use { fd ->
                player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            player.isLooping = true
            val level = VolumeMixer.music(musicVolume)
            player.setVolume(level, level)
            player.setOnPreparedListener(object : MediaPlayer.OnPreparedListener {
                override fun onPrepared(prepared: MediaPlayer) {
                    musicRebuildTried = false
                    if (musicWanted && !mixer.paused) {
                        try {
                            prepared.start()
                        } catch (_: Exception) {
                        }
                    }
                }
            })
            player.setOnErrorListener(object : MediaPlayer.OnErrorListener {
                override fun onError(broken: MediaPlayer, what: Int, extra: Int): Boolean {
                    // Only the player that is still current may be replaced.
                    if (music === broken) handleMusicFailure()
                    return true
                }
            })
            player.prepareAsync()
            music = player
        } catch (_: Throwable) {
            try {
                player.release()
            } catch (_: Throwable) {
            }
            handleMusicFailure()
        }
    }

    /** Drops a broken player and rebuilds it once, never in an endless loop. */
    private fun handleMusicFailure() {
        val broken = music
        music = null
        if (broken != null) {
            try {
                broken.release()
            } catch (_: Throwable) {
            }
        }
        if (!musicWanted || released || musicRebuildTried) return
        musicRebuildTried = true
        buildMusicPlayer()
    }

    fun stopMusic() {
        musicWanted = false
        musicRebuildTried = false
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

    /** Live diagnostics of the native stream, for the guide. */
    fun audioDiagnostics(): String = mixer.diagnostics()

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
            target.vibrate(
                VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ lifecycle

    /** Releases every sound. Later requests are ignored. */
    fun release() {
        if (released) return
        released = true
        val callback = deviceCallback
        deviceCallback = null
        if (callback != null) {
            try {
                platformAudio?.unregisterAudioDeviceCallback(callback)
            } catch (_: Throwable) {
            }
        }
        stopMusic()
        loops.clear()
        mixer.release()
    }
}
