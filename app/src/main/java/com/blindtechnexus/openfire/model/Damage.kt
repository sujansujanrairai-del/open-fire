package com.blindtechnexus.openfire.model

import kotlin.math.roundToInt

/**
 * Shared flat-HP damage table for both fighters. Proximity determines the
 * row for ordinary weapons; tracking explosives hit for their listed damage
 * regardless of range. No single hit or blast can remove more than 60 HP,
 * even when a fighter's maximum health grows in later rounds.
 */
object DamageTable {
    const val MAX_WEAPON_DAMAGE_HP = 60
    const val MAX_WEAPON_PERCENT = MAX_WEAPON_DAMAGE_HP // legacy API name
    const val EXPLOSIVE_PERCENT = MAX_WEAPON_DAMAGE_HP // legacy API name
    const val AWM_LONG_RANGE_MIN_PROXIMITY = 20f
    const val AWM_LONG_RANGE_MAX_PROXIMITY = 40f
    const val AWM_LONG_RANGE_DAMAGE_HP = 24
    const val AWM_LONG_RANGE_MIN_HP = AWM_LONG_RANGE_DAMAGE_HP
    const val MENU_PROTECTION_HP = 2
    const val MENU_PROTECTION_PERCENT = MENU_PROTECTION_HP
    const val MENU_PROTECTION_MS = 6_000L

    // Columns: miss / light / strong / heavy. Explosives track their targets.
    private val table: Map<Weapon, IntArray> = mapOf(
        Weapon.KNIFE to intArrayOf(0, 12, 18, 26),
        Weapon.SHORT_GUN to intArrayOf(0, 14, 22, 30),
        Weapon.ASSAULT_GUN to intArrayOf(0, 16, 26, 34),
        Weapon.PISTOL to intArrayOf(0, 18, 28, 36),
        Weapon.SNIPER to intArrayOf(0, 22, 36, 48),
        Weapon.GLUE_BREAKER to intArrayOf(0, 12, 20, 28),
        Weapon.SWORD to intArrayOf(0, 20, 30, 42),
        Weapon.AWM to intArrayOf(0, 28, 44, 60),
        Weapon.MP40 to intArrayOf(0, 14, 21, 28),
        Weapon.M10 to intArrayOf(0, 17, 26, 34),
        Weapon.SUBMACHINE_GUN to intArrayOf(0, 10, 16, 22),
        Weapon.AK47 to intArrayOf(0, 20, 32, 44),
        Weapon.MACHINE_GUN to intArrayOf(0, 26, 38, 50),
        Weapon.GRENADE to intArrayOf(40, 40, 40, 40),
        Weapon.TIMER_BOMB to intArrayOf(50, 50, 50, 50),
        Weapon.ATOM_BOMB to intArrayOf(60, 60, 60, 60),
        Weapon.MISSILE to intArrayOf(60, 60, 60, 60)
    )

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
