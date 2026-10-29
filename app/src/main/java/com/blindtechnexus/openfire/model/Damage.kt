package com.blindtechnexus.openfire.model

import kotlin.math.roundToInt

/**
 * The single health-reduction table of the whole game.
 *
 * Every weapon - the player's and Robert's - takes its damage from this one
 * table. The numbers are **flat health points**, not percentages: an assault
 * rifle that says 14 at point blank removes exactly 14 health from a 200 HP
 * fighter and exactly the same 14 health from a 2600 HP fighter. The old
 * implementation multiplied the table by the victim's maximum health, which
 * turned a documented 14 into 140 (or more) in the later rounds. Both
 * fighters now call [damage] and automatically agree on every number shown
 * in the shop, the guide and the spoken battle report.
 *
 *  | weapon          | 0-40% | 40-70% | 70-90% | 90-100% |
 *  |-----------------|-------|--------|--------|---------|
 *  | knife           |   0   |    8   |   12   |    14   |
 *  | shotgun         |   0   |    6   |   11   |    16   |
 *  | assault rifle   |   0   |    8   |   13   |    18   |
 *  | pistol          |   0   |    9   |   14   |    20   |
 *  | sniper rifle    |   0   |   10   |   16   |    22   |
 *  | sword           |   0   |   10   |   16   |    22   |
 *  | AWM             |  14 * |   16   |   26   |    40   |
 *  | MP40            |   0   |    5   |    8   |    11   |
 *  | machine gun     |   0   |    7   |   11   |    15   |
 *  | explosives      |  kill |  kill  |  kill  |   kill  |
 *
 * The table is laid out by capacity: the cheaper the weapon, the smaller its
 * row, and the AWM - the most expensive rifle - is the only normal weapon
 * that reaches the full [MAX_WEAPON_DAMAGE_HP] cap of 40 health points, which
 * is exactly the maximum 40 percent health reduction of the game.
 *
 *  * The AWM is a long-range precision rifle: inside the 20% to 40%
 *    proximity band - where every other weapon misses completely - it still
 *    lands for exactly [AWM_LONG_RANGE_DAMAGE_HP] health points.
 *
 * Normal weapons are capped at [MAX_WEAPON_DAMAGE_HP] health points per hit,
 * independent of how much health the victim has. Explosives (bombs and the
 * missile) are lethal: they reduce the target to zero health outright.
 */
object DamageTable {
    /** Hard cap for every non-explosive weapon, in health points. */
    const val MAX_WEAPON_DAMAGE_HP = 40

    /** Legacy name kept for callers that still speak in "power percent". */
    const val MAX_WEAPON_PERCENT = MAX_WEAPON_DAMAGE_HP

    /** Sentinel used by the explosive rows: an explosive is always lethal. */
    const val EXPLOSIVE_PERCENT = 100

    /** The AWM's long-range precision band, in proximity percent. */
    const val AWM_LONG_RANGE_MIN_PROXIMITY = 20f
    const val AWM_LONG_RANGE_MAX_PROXIMITY = 40f

    /** AWM long-range hit: exactly this many health points. */
    const val AWM_LONG_RANGE_DAMAGE_HP = 14

    /** Legacy name of the flat AWM long-range hit. */
    const val AWM_LONG_RANGE_MIN_HP = AWM_LONG_RANGE_DAMAGE_HP

    /**
     * While the quick menu is genuinely being navigated every incoming hit is
     * reduced to this many health points, so browsing items stays safe
     * without becoming an invulnerability cheat at high round health.
     */
    const val MENU_PROTECTION_HP = 2

    /** Legacy name for the flat menu-protection value. */
    const val MENU_PROTECTION_PERCENT = MENU_PROTECTION_HP

    /** How long the menu protection stays alive after the last menu swipe. */
    const val MENU_PROTECTION_MS = 6_000L

    /** One row per weapon, indexed by [HitZone.ordinal]. Values are HP. */
    private val table: Map<Weapon, IntArray> = mapOf(
        Weapon.KNIFE to intArrayOf(0, 8, 12, 14),
        Weapon.SHORT_GUN to intArrayOf(0, 6, 11, 16),
        Weapon.ASSAULT_GUN to intArrayOf(0, 8, 13, 18),
        Weapon.PISTOL to intArrayOf(0, 9, 14, 20),
        Weapon.SNIPER to intArrayOf(0, 10, 16, 22),
        Weapon.SWORD to intArrayOf(0, 10, 16, 22),
        Weapon.AWM to intArrayOf(0, 16, 26, 40),
        Weapon.MP40 to intArrayOf(0, 5, 8, 11),
        Weapon.MACHINE_GUN to intArrayOf(0, 7, 11, 15),
        Weapon.GRENADE to intArrayOf(
            EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT
        ),
        Weapon.ATOM_BOMB to intArrayOf(
            EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT
        ),
        Weapon.TIMER_BOMB to intArrayOf(
            EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT
        ),
        Weapon.MISSILE to intArrayOf(
            EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT, EXPLOSIVE_PERCENT
        )
    )

    /** True for the weapons that ignore the normal hit-point cap. */
    fun isExplosive(weapon: Weapon): Boolean =
        weapon.missileLauncher || weapon.bomb

    /** Applies the cap that belongs to this weapon class. */
    fun capFor(weapon: Weapon): Int =
        if (isExplosive(weapon)) EXPLOSIVE_PERCENT else MAX_WEAPON_DAMAGE_HP

    /** The exact flat health points this weapon takes in this zone. */
    fun percentOf(weapon: Weapon, zone: HitZone): Int {
        val row = table[weapon] ?: return 0
        val value = row.getOrElse(zone.ordinal) { 0 }
        return value.coerceIn(0, capFor(weapon))
    }

    /** The exact flat health points for a proximity value (0..100). */
    fun percentAt(weapon: Weapon, proximityPercent: Float): Int =
        percentOf(weapon, Combat.zoneFor(proximityPercent))

    /** True inside the AWM's special long-range band. */
    fun isAwmLongRangeBand(weapon: Weapon, proximityPercent: Float): Boolean =
        weapon == Weapon.AWM &&
            proximityPercent >= AWM_LONG_RANGE_MIN_PROXIMITY &&
            proximityPercent < AWM_LONG_RANGE_MAX_PROXIMITY

    /**
     * The AWM's long-range precision damage: exactly
     * [AWM_LONG_RANGE_DAMAGE_HP] health points, never less and never more
     * than the target's maximum health.
     */
    fun awmLongRangeDamage(victimMaxHp: Int): Int {
        if (victimMaxHp <= 0) return 0
        return AWM_LONG_RANGE_DAMAGE_HP.coerceAtMost(victimMaxHp)
    }

    /**
     * The damage of one hit, in health points. A connected hit is never
     * larger than the victim's maximum health and never scales with it:
     * what the table states is exactly what the fighter loses.
     */
    fun damage(weapon: Weapon, proximityPercent: Float, victimMaxHp: Int): Int {
        if (victimMaxHp <= 0) return 0
        if (isAwmLongRangeBand(weapon, proximityPercent)) {
            return awmLongRangeDamage(victimMaxHp)
        }
        // Explosives (bombs and missiles) destroy their target outright.
        if (isExplosive(weapon)) return victimMaxHp
        val points = percentAt(weapon, proximityPercent)
        if (points <= 0) return 0
        return points.coerceIn(1, victimMaxHp)
    }

    /** Menu-protection damage: a fixed, tiny number of health points. */
    fun menuProtectedDamage(victimMaxHp: Int): Int {
        if (victimMaxHp <= 0) return 0
        return MENU_PROTECTION_HP.coerceIn(1, victimMaxHp)
    }

    /**
     * A weapon's row as human readable health points, in the order
     * miss / light / strong / heavy. Used by the shop and the guide.
     */
    fun zonesText(weapon: Weapon): String =
        "%d, %d, %d, %d".format(
            percentOf(weapon, HitZone.MISS),
            percentOf(weapon, HitZone.LIGHT),
            percentOf(weapon, HitZone.STRONG),
            percentOf(weapon, HitZone.HEAVY)
        )

    /** True when the whole table respects the documented caps. */
    fun isValid(): Boolean =
        table.values.all { row -> row.size == HitZone.entries.size } &&
            table.keys.all { weapon ->
                isExplosive(weapon) || percentOf(weapon, HitZone.HEAVY) <= MAX_WEAPON_DAMAGE_HP
            }

    /** Legacy zone calculation kept for callers that pass an explicit power. */
    fun scaledPower(powerPercent: Int, proximityPercent: Float): Int {
        val fraction = when (Combat.zoneFor(proximityPercent)) {
            HitZone.MISS -> return 0
            HitZone.LIGHT -> Combat.LIGHT_DAMAGE_FRACTION
            HitZone.STRONG -> Combat.STRONG_DAMAGE_FRACTION
            HitZone.HEAVY -> Combat.HEAVY_DAMAGE_FRACTION
        }
        return (powerPercent * fraction).roundToInt()
    }
}
