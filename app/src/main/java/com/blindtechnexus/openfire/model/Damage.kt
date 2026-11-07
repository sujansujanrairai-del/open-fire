package com.blindtechnexus.openfire.model

import kotlin.math.roundToInt

/**
 * Shared flat-HP damage table for both fighters.
 *
 * The table is a real power ladder: every weapon is placed on the ladder by
 * its own point-blank power, and its three hit bands are derived from that
 * power, so the numbers can never contradict the weapon description again.
 *
 *  - light band  (40%-70% proximity)  = half of the weapon's point-blank power
 *  - strong band (70%-90% proximity)  = 80% of the weapon's point-blank power
 *  - heavy band  (90%-100% proximity) = the full power
 *
 * The heaviest hand-held weapon (the AWM) removes 80 HP in one point-blank
 * hit, which is the ceiling of the whole table; the lightest automatic (the
 * submachine gun) removes 26 HP. No single hit or blast can remove more than
 * [MAX_WEAPON_DAMAGE_HP] health points, even when a fighter's maximum health
 * grows in later rounds. Proximity determines the band for ordinary weapons;
 * tracking explosives hit for their listed damage regardless of range.
 */
object DamageTable {
    /**
     * Hard ceiling of one ordinary attack: 80 health points.
     *
     * This was raised from the old 60 HP cap so the strongest weapons can
     * take a real share of a fighter's health bar again, and every row of
     * [table] is capped by it.
     */
    const val MAX_WEAPON_DAMAGE_HP = 80

    /** Legacy API name kept for callers that speak of percentages. */
    const val MAX_WEAPON_PERCENT = MAX_WEAPON_DAMAGE_HP

    /** Legacy API name kept for the explosive rows. */
    const val EXPLOSIVE_PERCENT = 60

    const val AWM_LONG_RANGE_MIN_PROXIMITY = 20f
    const val AWM_LONG_RANGE_MAX_PROXIMITY = 40f

    /**
     * The AWM stays lethal inside its long-range band: 40% of its own
     * point-blank power, where every other weapon misses completely.
     */
    const val AWM_LONG_RANGE_DAMAGE_HP = 32
    const val AWM_LONG_RANGE_MIN_HP = AWM_LONG_RANGE_DAMAGE_HP
    const val MENU_PROTECTION_HP = 2
    const val MENU_PROTECTION_PERCENT = MENU_PROTECTION_HP
    const val MENU_PROTECTION_MS = 6_000L

    /**
     * Point-blank (heavy) power of every hand-held weapon, in HP.
     *
     * The ladder follows the shop and the weapon descriptions: melee and the
     * light automatics sit at the bottom, the long rifles at the top.
     */
    const val SUBMACHINE_GUN_POWER_HP = 26
    const val KNIFE_POWER_HP = 32
    const val GLUE_BREAKER_POWER_HP = 34
    const val MP40_POWER_HP = 36
    const val SHORT_GUN_POWER_HP = 38
    const val M10_POWER_HP = 42
    const val ASSAULT_GUN_POWER_HP = 44
    const val PISTOL_POWER_HP = 50
    const val SWORD_POWER_HP = 54
    const val AK47_POWER_HP = 58
    const val SNIPER_POWER_HP = 64
    const val MACHINE_GUN_POWER_HP = 70
    const val AWM_POWER_HP = 80

    /** Half of the point-blank power: the 40%-70% band. */
    fun lightFromHeavy(heavy: Int): Int = (heavy / 2).coerceIn(1, MAX_WEAPON_DAMAGE_HP)

    /** 80% of the point-blank power: the 70%-90% band. */
    fun strongFromHeavy(heavy: Int): Int =
        (heavy * Combat.STRONG_DAMAGE_FRACTION).roundToInt().coerceIn(1, MAX_WEAPON_DAMAGE_HP)

    // Columns: miss / light / strong / heavy. Explosives track their targets.
    private val table: Map<Weapon, IntArray> = mapOf(
        Weapon.SUBMACHINE_GUN to row(SUBMACHINE_GUN_POWER_HP),
        Weapon.KNIFE to row(KNIFE_POWER_HP),
        Weapon.GLUE_BREAKER to row(GLUE_BREAKER_POWER_HP),
        Weapon.MP40 to row(MP40_POWER_HP),
        Weapon.SHORT_GUN to row(SHORT_GUN_POWER_HP),
        Weapon.M10 to row(M10_POWER_HP),
        Weapon.ASSAULT_GUN to row(ASSAULT_GUN_POWER_HP),
        Weapon.PISTOL to row(PISTOL_POWER_HP),
        Weapon.SWORD to row(SWORD_POWER_HP),
        Weapon.AK47 to row(AK47_POWER_HP),
        Weapon.SNIPER to row(SNIPER_POWER_HP),
        Weapon.MACHINE_GUN to row(MACHINE_GUN_POWER_HP),
        Weapon.AWM to row(AWM_POWER_HP),
        // Explosives: the missile and every bomb are resolved by their own
        // rules; these rows are the documented fallback powers.
        Weapon.GRENADE to intArrayOf(40, 40, 40, 40),
        Weapon.TIMER_BOMB to intArrayOf(50, 50, 50, 50),
        Weapon.ATOM_BOMB to intArrayOf(60, 60, 60, 60),
        Weapon.MISSILE to intArrayOf(60, 60, 60, 60)
    )

    /** Builds a full table row from the point-blank power of a weapon. */
    private fun row(heavy: Int): IntArray =
        intArrayOf(0, lightFromHeavy(heavy), strongFromHeavy(heavy), heavy)

    fun isExplosive(weapon: Weapon): Boolean = weapon.missileLauncher || weapon.bomb

    fun capFor(weapon: Weapon): Int = MAX_WEAPON_DAMAGE_HP

    fun percentOf(weapon: Weapon, zone: HitZone): Int =
        (table[weapon]?.getOrElse(zone.ordinal) { 0 } ?: 0).coerceIn(0, MAX_WEAPON_DAMAGE_HP)

    fun percentAt(weapon: Weapon, proximityPercent: Float): Int =
        percentOf(weapon, Combat.zoneFor(proximityPercent))

    fun isAwmLongRangeBand(weapon: Weapon, proximityPercent: Float): Boolean =
        weapon == Weapon.AWM && proximityPercent >= AWM_LONG_RANGE_MIN_PROXIMITY &&
            proximityPercent < AWM_LONG_RANGE_MAX_PROXIMITY

    fun awmLongRangeDamage(victimMaxHp: Int): Int =
        AWM_LONG_RANGE_DAMAGE_HP.coerceAtMost(victimMaxHp.coerceAtLeast(0))

    fun damage(weapon: Weapon, proximityPercent: Float, victimMaxHp: Int): Int {
        if (victimMaxHp <= 0) return 0
        if (isAwmLongRangeBand(weapon, proximityPercent)) return awmLongRangeDamage(victimMaxHp)
        return percentAt(weapon, proximityPercent).coerceAtMost(victimMaxHp)
    }

    fun menuProtectedDamage(victimMaxHp: Int): Int =
        MENU_PROTECTION_HP.coerceAtMost(victimMaxHp.coerceAtLeast(0))

    fun zonesText(weapon: Weapon): String =
        HitZone.entries.joinToString(", ") { percentOf(weapon, it).toString() }

    fun isValid(): Boolean = table.keys.containsAll(Weapon.entries) &&
        table.values.all { row -> row.size == HitZone.entries.size && row.all { it in 0..MAX_WEAPON_DAMAGE_HP } }

    fun scaledPower(powerPercent: Int, proximityPercent: Float): Int {
        val fraction = when (Combat.zoneFor(proximityPercent)) {
            HitZone.MISS -> return 0
            HitZone.LIGHT -> Combat.LIGHT_DAMAGE_FRACTION
            HitZone.STRONG -> Combat.STRONG_DAMAGE_FRACTION
            HitZone.HEAVY -> Combat.HEAVY_DAMAGE_FRACTION
        }
        return (powerPercent * fraction).roundToInt().coerceIn(0, MAX_WEAPON_DAMAGE_HP)
    }
}
