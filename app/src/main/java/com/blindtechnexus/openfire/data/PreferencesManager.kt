package com.blindtechnexus.openfire.data

import android.content.Context
import android.content.SharedPreferences
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.ExtensionState
import com.blindtechnexus.openfire.model.GameSettings
import com.blindtechnexus.openfire.model.GameStats
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.Progression
import com.blindtechnexus.openfire.model.RunSpeed
import com.blindtechnexus.openfire.model.SoundTheme
import com.blindtechnexus.openfire.model.Weapon

/**
 * Reads and writes the complete game profile.
 *
 * Everything (settings, statistics, shop inventory and the remembered last
 * weapon) is persisted through [EncryptedStore] inside `/.open_fire/data/` on
 * external storage, protected by AES-256-GCM. SharedPreferences is only read
 * once, to migrate profiles written by version 1.x, and is cleared afterwards.
 */
class PreferencesManager(context: Context) {
    private val appContext = context.applicationContext
    private val store = EncryptedStore(appContext)
    private val legacy: SharedPreferences =
        appContext.getSharedPreferences("open_fire_prefs", Context.MODE_PRIVATE)

    private companion object {
        const val FILE_SETTINGS = "settings"
        const val FILE_PROFILE = "profile"
        const val FILE_INVENTORY = "inventory"
        const val SCHEMA_VERSION = 7

        const val KEY_SCHEMA = "schema"

        // Pre-missile profile keys, read once for migration: the missile was
        // a shop-only "future weapon" and its power was a permanent
        // extension. Owners keep the missile and receive one power for it.
        const val LEGACY_FUTURE_WEAPONS = "futureWeapons"
        const val LEGACY_MISSILE_POWERS_EXTENSION = "ext.missilePowers"
        const val LEGACY_MISSILE_ID = "missile"
    }

    init {
        migrateLegacyProfileIfNeeded()
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    fun loadSettings(): GameSettings {
        val map = store.read(FILE_SETTINGS)
        if (map.isEmpty()) return GameSettings()
        val theme = try {
            SoundTheme.valueOf(map["soundTheme"] ?: SoundTheme.TACTICAL.name)
        } catch (_: Exception) {
            SoundTheme.TACTICAL
        }
        return GameSettings(
            name = map["name"] ?: "",
            lang = map["lang"] ?: "en",
            ttsEnginePackage = map["ttsEngine"] ?: "",
            // Older profiles only stored the interface language: an empty TTS
            // language keeps following it, which is the previous behaviour.
            ttsLanguage = map["ttsLanguage"] ?: "",
            voiceName = map["voice"] ?: "",
            ttsRate = map["rate"]?.toFloatOrNull() ?: 1.0f,
            ttsVolume = map["ttsVolume"]?.toFloatOrNull() ?: 1.0f,
            musicVolume = map["musicVolume"]?.toFloatOrNull() ?: 0.5f,
            sfxVolume = map["sfxVolume"]?.toFloatOrNull() ?: 1.0f,
            soundTheme = theme,
            hapticsEnabled = map["haptics"]?.toBooleanStrictOrNull() ?: true,
            keepScreenOn = map["keepScreenOn"]?.toBooleanStrictOrNull() ?: false,
            voiceGuidance = map["voiceGuidance"]?.toBooleanStrictOrNull() ?: true
        )
    }

    fun saveSettings(s: GameSettings) {
        store.write(
            FILE_SETTINGS,
            mapOf(
                KEY_SCHEMA to SCHEMA_VERSION.toString(),
                "name" to s.name,
                "lang" to s.lang,
                "ttsEngine" to s.ttsEnginePackage,
                "ttsLanguage" to s.ttsLanguage,
                "voice" to s.voiceName,
                "rate" to s.ttsRate.toString(),
                "ttsVolume" to s.ttsVolume.toString(),
                "musicVolume" to s.musicVolume.toString(),
                "sfxVolume" to s.sfxVolume.toString(),
                "soundTheme" to s.soundTheme.name,
                "haptics" to s.hapticsEnabled.toString(),
                "keepScreenOn" to s.keepScreenOn.toString(),
                "voiceGuidance" to s.voiceGuidance.toString()
            )
        )
    }

    // ------------------------------------------------------------------
    // Statistics and progression
    // ------------------------------------------------------------------

    fun loadStats(): GameStats {
        val map = store.read(FILE_PROFILE)
        if (map.isEmpty()) return Progression.normalize(GameStats())
        val wins = map["wins"]?.toIntOrNull() ?: 0
        val round = map["round"]?.toIntOrNull() ?: 1
        val migratedLevelWins = wins % Progression.WINS_PER_LEVEL
        return Progression.normalize(
            GameStats(
                games = map["games"]?.toIntOrNull() ?: 0,
                wins = wins,
                kills = map["kills"]?.toIntOrNull() ?: 0,
                coins = map["coins"]?.toIntOrNull() ?: 100,
                loanBalance = map["loanBalance"]?.toIntOrNull() ?: 0,
                round = round,
                level = map["level"]?.toIntOrNull() ?: 1,
                levelWins = map["levelWins"]?.toIntOrNull() ?: migratedLevelWins,
                // Older profiles did not store this counter. Their current
                // round still proves how many earlier rounds were completed.
                completedRounds = map["completedRounds"]?.toIntOrNull()
                    ?: (round - 1).coerceAtLeast(0)
            )
        )
    }

    fun saveStats(s: GameStats): Boolean {
        val normalized = Progression.normalize(s)
        return store.write(
            FILE_PROFILE,
            mapOf(
                KEY_SCHEMA to SCHEMA_VERSION.toString(),
                "games" to normalized.games.toString(),
                "wins" to normalized.wins.toString(),
                "kills" to normalized.kills.toString(),
                "coins" to normalized.coins.toString(),
                "loanBalance" to normalized.loanBalance.toString(),
                "round" to normalized.round.toString(),
                "level" to normalized.level.toString(),
                "levelWins" to normalized.levelWins.toString(),
                "completedRounds" to normalized.completedRounds.toString()
            )
        )
    }

    /**
     * Erases every stored statistic and the complete shop inventory, then
     * writes the cleared profile back. The player's name and audio/TTS
     * preferences are kept so the app remains usable.
     */
    fun clearStats(cleared: GameStats = GameStats.cleared()) {
        store.delete(FILE_PROFILE)
        store.delete(FILE_INVENTORY)
        saveStats(cleared)
        saveInventory(Inventory())
    }

    // ------------------------------------------------------------------
    // Shop inventory
    // ------------------------------------------------------------------

    fun loadInventory(): Inventory {
        val map = store.read(FILE_INVENTORY)
        if (map.isEmpty()) return Inventory()
        val weaponIds = map["weapons"]
            ?.split(',')
            ?.mapNotNull { Weapon.fromId(it.trim()) }
            ?.map { it.id }
            ?.toSet()
            ?: Weapon.defaults().map { it.id }.toSet()
        val extensions = mutableMapOf<String, ExtensionState>()
        for (extension in Extension.entries) {
            val raw = map["ext.${extension.id}"] ?: continue
            val state = try {
                ExtensionState.valueOf(raw)
            } catch (_: Exception) {
                ExtensionState.NOT_OWNED
            }
            if (state != ExtensionState.NOT_OWNED) extensions[extension.id] = state
        }
        // Migration: a missile bought while it was a shop-only future
        // weapon becomes the real battle weapon, and a bought (permanent)
        // Missile Powers extension becomes one consumable missile power.
        val migratedWeaponIds = if (
            map[LEGACY_FUTURE_WEAPONS]
                ?.split(',')
                ?.map { it.trim() }
                ?.contains(LEGACY_MISSILE_ID) == true
        ) {
            weaponIds + LEGACY_MISSILE_ID
        } else {
            weaponIds
        }
        val legacyPowerOwned = map[LEGACY_MISSILE_POWERS_EXTENSION]
            ?.let { raw ->
                try {
                    ExtensionState.valueOf(raw) != ExtensionState.NOT_OWNED
                } catch (_: Exception) {
                    false
                }
            } ?: false
        val missilePowers = map["missilePowers"]?.toIntOrNull()?.coerceAtLeast(0)
            ?: if (legacyPowerOwned) 1 else 0
        val bombPowers = map["bombPowers"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        // Weapons switched off with the shop's Enable / Disable button. An
        // unknown or removed id is simply dropped.
        val disabledWeapons = map["disabledWeapons"]
            ?.split(',')
            ?.mapNotNull { Weapon.fromId(it.trim()) }
            ?.map { it.id }
            ?.toSet()
            ?: emptySet()
        return Inventory(
            weaponIds = migratedWeaponIds + Weapon.defaults().map { it.id },
            medikits = map["medikits"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            glueWalls = map["glueWalls"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            covers = map["covers"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            antiMissiles = map["antiMissiles"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            antiBombs = map["antiBombs"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            extensions = extensions,
            lastWeaponId = Weapon.fromId(map["lastWeapon"])?.id ?: Weapon.SHORT_GUN.id,
            lastRunSpeedId = RunSpeed.fromId(map["lastRunSpeed"]).id,
            missilePowers = missilePowers,
            bombPowers = bombPowers,
            antiMissileRewardGranted =
                map["antiMissileRewardGranted"]?.toBooleanStrictOrNull() ?: false,
            disabledWeapons = disabledWeapons
        )
    }

    fun saveInventory(inventory: Inventory) {
        val values = mutableMapOf(
            KEY_SCHEMA to SCHEMA_VERSION.toString(),
            "weapons" to inventory.weaponIds.joinToString(","),
            "medikits" to inventory.medikits.toString(),
            "glueWalls" to inventory.glueWalls.toString(),
            "covers" to inventory.covers.toString(),
            "antiMissiles" to inventory.antiMissiles.toString(),
            "antiBombs" to inventory.antiBombs.toString(),
            "lastWeapon" to inventory.lastWeaponId,
            "lastRunSpeed" to RunSpeed.fromId(inventory.lastRunSpeedId).id,
            "missilePowers" to inventory.missilePowers.toString(),
            "bombPowers" to inventory.bombPowers.toString(),
            "antiMissileRewardGranted" to inventory.antiMissileRewardGranted.toString(),
            "disabledWeapons" to inventory.disabledWeapons.joinToString(",")
        )
        for (extension in Extension.entries) {
            values["ext.${extension.id}"] = inventory.extensionState(extension).name
        }
        store.write(FILE_INVENTORY, values)
    }

    /** Adds one defence item directly (used by automatic battle helpers). */
    fun grantDefence(item: DefenceItem, amount: Int = 1) {
        val inventory = loadInventory()
        saveInventory(inventory.withCount(item, inventory.count(item) + amount.coerceAtLeast(0)))
    }

    // ------------------------------------------------------------------
    // One-time migration from the version 1.x SharedPreferences profile
    // ------------------------------------------------------------------

    private fun migrateLegacyProfileIfNeeded() {
        if (store.exists(FILE_SETTINGS) || store.exists(FILE_PROFILE)) return
        if (legacy.all.isEmpty()) return
        try {
            val theme = try {
                SoundTheme.valueOf(legacy.getString("of_sound_theme", "TACTICAL") ?: "TACTICAL")
            } catch (_: Exception) {
                SoundTheme.TACTICAL
            }
            saveSettings(
                GameSettings(
                    name = legacy.getString("of_name", "") ?: "",
                    lang = legacy.getString("of_lang", "en") ?: "en",
                    ttsEnginePackage = legacy.getString("of_tts_engine", "") ?: "",
                    voiceName = legacy.getString("of_voice", "") ?: "",
                    ttsRate = legacy.getFloat("of_rate", 1.0f),
                    ttsVolume = legacy.getFloat("of_ttsvol", 1.0f),
                    musicVolume = legacy.getFloat("of_musicvol", 0.5f),
                    sfxVolume = legacy.getFloat("of_sfxvol", 1.0f),
                    soundTheme = theme,
                    hapticsEnabled = legacy.getBoolean("of_haptics", true)
                )
            )
            val wins = legacy.getInt("of_wins", 0)
            saveStats(
                Progression.normalize(
                    GameStats(
                        games = legacy.getInt("of_games", 0),
                        wins = wins,
                        kills = legacy.getInt("of_kills", 0),
                        coins = legacy.getInt("of_coins", 100),
                        round = legacy.getInt("of_round", 1),
                        level = legacy.getInt("of_level", 1),
                        levelWins = if (legacy.contains("of_level_wins")) {
                            legacy.getInt("of_level_wins", wins % Progression.WINS_PER_LEVEL)
                        } else {
                            wins % Progression.WINS_PER_LEVEL
                        }
                    )
                )
            )
            saveInventory(Inventory())
            legacy.edit().clear().apply()
        } catch (_: Exception) {
            // A corrupt legacy profile must never block the app from starting.
        }
    }
}
