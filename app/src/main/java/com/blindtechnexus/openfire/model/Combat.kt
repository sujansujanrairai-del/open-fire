package com.blindtechnexus.openfire.model

import kotlin.math.roundToInt

/**
 * The four proximity zones that decide how much of a weapon's power lands.
 *
 * The arena is exactly [Combat.MAX_RANGE] metres across. Proximity is the
 * closeness of the target as a percentage of that range (100% = point blank,
 * 0% = the far edge of the arena).
 */
enum class HitZone {
    /** 0% - 40% proximity: the shot misses completely. */
    MISS,
    /** 40% - 70% proximity: half of the weapon's power. */
    LIGHT,
    /** 70% - 90% proximity: 80% of the weapon's power. */
    STRONG,
    /** 90% - 100% proximity: the full weapon's power. */
    HEAVY
}

/**
 * The bands Robert fights from.
 *
 *  - [FAR]    (0% to 40% proximity): every shot misses. He holds it for only
 *             one or two shots at a time, purely to confuse the real player.
 *  - [MID]    (40% to 70% proximity): half of the weapon power.
 *  - [STRONG] (70% to 90% proximity): 80% of the weapon power. Robert pushes
 *             into this band while he holds a moderate advantage.
 *  - [CLOSE]  (90% to 100% proximity): the full weapon power. Robert pushes
 *             into this band while he holds a big advantage, and whenever
 *             the player's weapon is damaged or a glue wall is up.
 *
 * The randomly stored preference is still [MID] or [CLOSE]; the situational
 * AI in [RobertRules.situationalRange] promotes it to [STRONG] or [CLOSE]
 * while Robert is winning and demotes it to [MID] while he is losing.
 */
enum class FireRange(
    val id: String,
    val minProximity: Float,
    val maxProximity: Float
) {
    FAR("far", Combat.MISS_ZONE_MIN, Combat.MISS_ZONE_MAX),
    MID("mid", Combat.MISS_ZONE_MAX, Combat.LIGHT_ZONE_MAX),
    STRONG("strong", Combat.LIGHT_ZONE_MAX, Combat.STRONG_ZONE_MAX),
    CLOSE("close", Combat.HEAVY_ZONE_MIN, Combat.FULL_PROXIMITY);

    /** The proximity the Robert aims at while he holds this band. */
    val midProximity: Float
        get() = (minProximity + maxProximity) / 2f

    /** The arena distance that matches [midProximity]. */
    val targetDistance: Float
        get() = Combat.distanceFor(midProximity)

    companion object {
        fun fromId(id: String?): FireRange =
            FireRange.entries.firstOrNull { it.id == id } ?: MID
    }
}

/**
 * Standard combat rules shared by the player and the Robert.
 *
 * Zone rules (proximity = how close the target is, in percent):
 *  - 0%  to 40%  -> miss (no damage at all)
 *  - 40% to 70%  -> the light column of the damage table
 *  - 70% to 90%  -> the strong column of the damage table
 *  - 90% to 100% -> the heavy column of the damage table
 *
 * The exact health-point values per weapon and per zone live in one single
 * place, [DamageTable], and both fighters read their damage from it. Normal
 * weapons are flat HP values capped at 40 health points per hit - they never
 * scale with the victim's maximum health - while bombs and the missile are
 * lethal. The AWM is the one long-range exception: inside the 20% to 40% band
 * it still lands a precision hit.
 *
 * There is no preset where a combatant loses after a fixed number of hits:
 * a combatant is defeated only when their health actually reaches zero.
 *
 * Three consecutive enemy hits received inside the 90%-100% band damage the
 * weapon in the player's hands. A damaged weapon cannot fire until it is
 * repaired by the Recovery action, which costs [GUN_RECOVERY_COST] coins.
 */
object Combat {
    /** Full combat area width in metres (the arena is a 100 m range). */
    const val MAX_RANGE = 100f

    /**
     * A movement that lands within this many metres of a wall counts as
     * "blocked by the wall": the fighter stops and has to turn back.
     */
    const val WALL_TOLERANCE_M = 1.5f

    const val MISS_ZONE_MIN = 0f
    const val MISS_ZONE_MAX = 40f
    const val LIGHT_ZONE_MAX = 70f
    const val STRONG_ZONE_MAX = 90f
    const val HEAVY_ZONE_MIN = 90f
    const val FULL_PROXIMITY = 100f

    /** Fraction of the weapon power that lands in each zone. */
    const val LIGHT_DAMAGE_FRACTION = 0.50f
    const val STRONG_DAMAGE_FRACTION = 0.80f
    const val HEAVY_DAMAGE_FRACTION = 1.00f

    /** Point-blank hits in a row needed to damage the weapon in hand. */
    const val HITS_TO_DAMAGE_GUN = 3

    /** Coins required to repair every damaged weapon. */
    const val GUN_RECOVERY_COST = 100

    /** Fraction of maximum health granted by the single second chance. */
    const val SECOND_CHANCE_FRACTION = 0.5f

    /** A cover blocks this many hits; the next (12th) hit breaks it. */
    const val COVER_MAX_HITS = 11

    /** Sniper hits at 90%-100% proximity needed to break a glue wall. */
    const val GLUE_WALL_BREAK_HITS = 3

    /** Only a sniper shot at or above this proximity can damage a glue wall. */
    const val GLUE_WALL_BREAK_PROXIMITY = 90f

    /** Close-range shotgun power retained as a named combat constant. */
    const val SHORT_GUN_CLOSE_POWER_PERCENT = 16

    /** Converts a distance (50 = far edge, 0 = point-blank) into 0..100% proximity. */
    fun proximityOf(distance: Float): Float =
        ((1f - distance / MAX_RANGE) * 100f).coerceIn(MISS_ZONE_MIN, FULL_PROXIMITY)

    /** Converts 0..100% proximity back into an arena distance in metres. */
    fun distanceFor(proximityPercent: Float): Float =
        ((1f - proximityPercent.coerceIn(MISS_ZONE_MIN, FULL_PROXIMITY) / 100f) * MAX_RANGE)
            .coerceIn(0f, MAX_RANGE)

    /** Returns the hit zone for the given proximity percentage. */
    fun zoneFor(proximityPercent: Float): HitZone = when {
        proximityPercent >= STRONG_ZONE_MAX -> HitZone.HEAVY
        proximityPercent >= LIGHT_ZONE_MAX -> HitZone.STRONG
        proximityPercent >= MISS_ZONE_MAX -> HitZone.LIGHT
        else -> HitZone.MISS
    }

    /**
     * The power a weapon really uses at this proximity. The value is the flat
     * health-point cost from the single [DamageTable], so the number shown in
     * the shop and the number deducted in battle are always identical.
     */
    fun powerPercentOf(weapon: Weapon, proximityPercent: Float): Int =
        DamageTable.percentAt(weapon, proximityPercent)

    /**
     * Damage of an explicit power value at the given proximity, applied to a
     * fighter with [maxHp] maximum health.
     *
     * [powerPercent] is a flat health-point value, never a percentage of the
     * victim's health. Misses deal 0, light hits half of the power, strong
     * hits 80% and point-blank hits the full power (rounded, with a minimum
     * of 1 so a connected hit is never fully ignored). [maxHp] only caps the
     * result so a hit can never deal more than the fighter actually has.
     */
    fun damageFor(powerPercent: Int, proximityPercent: Float, maxHp: Int): Int {
        if (maxHp <= 0 || powerPercent <= 0) return 0
        val points = DamageTable.scaledPower(powerPercent, proximityPercent)
        if (points <= 0) return 0
        return points.coerceAtLeast(1).coerceAtMost(maxHp)
    }

    /**
     * Damage for a power value that already belongs to a specific hit zone.
     * Unlike [damageFor], this does not apply the 50% / 80% zone multiplier a
     * second time. Robert's custom assault rifle and shotgun tables use this.
     */
    fun damageForDirectPower(powerPercent: Int, proximityPercent: Float, maxHp: Int): Int {
        if (maxHp <= 0 || powerPercent <= 0) return 0
        if (zoneFor(proximityPercent) == HitZone.MISS) return 0
        return powerPercent.coerceAtMost(maxHp)
    }

    /**
     * Damage dealt by a specific weapon at the given proximity. Every weapon
     * of both fighters reads the exact flat value from [DamageTable], so a
     * hit always deducts precisely the documented health points.
     */
    fun weaponDamage(weapon: Weapon, proximityPercent: Float, victimMaxHp: Int): Int =
        DamageTable.damage(weapon, proximityPercent, victimMaxHp)

    /** Damage of an incoming hit while the quick menu protection is alive. */
    fun menuProtectedDamage(victimMaxHp: Int): Int =
        DamageTable.menuProtectedDamage(victimMaxHp)

    /** True when this shot can damage a glue wall (sniper rifle, point blank). */
    fun canBreakGlueWall(weapon: Weapon, proximityPercent: Float): Boolean =
        weapon == Weapon.SNIPER && proximityPercent >= GLUE_WALL_BREAK_PROXIMITY

    /** Health granted by the one-per-fight second chance. */
    fun secondChanceHealth(maxHp: Int): Int =
        (maxHp * SECOND_CHANCE_FRACTION).roundToInt().coerceAtLeast(1)

    /**
     * Advances the "consecutive point-blank hits received" streak.
     * A point-blank hit (90%-100% proximity) increments it; every other
     * outcome (miss, injury, strong hit) resets it to zero.
     */
    fun nextHeavyHitStreak(currentStreak: Int, proximityPercent: Float): Int =
        if (zoneFor(proximityPercent) == HitZone.HEAVY) currentStreak + 1 else 0

    /** True when [streak] consecutive point-blank hits damage the weapon. */
    fun gunBreaks(streak: Int): Boolean = streak >= HITS_TO_DAMAGE_GUN
}
