package com.blindtechnexus.openfire.model

/**
 * Combat timing and positional damage for the three continuous-fire weapons.
 *
 * Lighter bullets are fired more frequently; heavier bullets hit harder at a
 * slower cadence. Both the local player and Robert read this same map, so an
 * enemy cannot accidentally fire a machine gun at the submachine gun's beat.
 * Damage still comes from [DamageTable], the single combat-power source.
 */
data class AutomaticFireProfile(
    val intervalMs: Long,
    val missDamageHp: Int,
    val lightDamageHp: Int,
    val strongDamageHp: Int,
    val heavyDamageHp: Int
) {
    fun damageAt(proximityPercent: Float, victimMaxHp: Int): Int =
        when (Combat.zoneFor(proximityPercent)) {
            HitZone.MISS -> 0
            HitZone.LIGHT -> lightDamageHp
            HitZone.STRONG -> strongDamageHp
            HitZone.HEAVY -> heavyDamageHp
        }.coerceAtMost(victimMaxHp.coerceAtLeast(0))
}

object AutomaticFirePowerMap {
    private fun profile(weapon: Weapon, intervalMs: Long) = AutomaticFireProfile(
        intervalMs = intervalMs,
        missDamageHp = DamageTable.percentOf(weapon, HitZone.MISS),
        lightDamageHp = DamageTable.percentOf(weapon, HitZone.LIGHT),
        strongDamageHp = DamageTable.percentOf(weapon, HitZone.STRONG),
        heavyDamageHp = DamageTable.percentOf(weapon, HitZone.HEAVY)
    )

    val profiles: Map<Weapon, AutomaticFireProfile> = mapOf(
        // Light 26 HP bullet: fastest cadence, two bullets per second.
        Weapon.SUBMACHINE_GUN to profile(Weapon.SUBMACHINE_GUN, FireCadence.AUTO_FAST_MS),
        // Medium 58 HP bullet: one bullet per second.
        Weapon.AK47 to profile(Weapon.AK47, FireCadence.AUTO_MEDIUM_MS),
        // Heavy 70 HP bullet: one bullet every one and a half seconds.
        Weapon.MACHINE_GUN to profile(Weapon.MACHINE_GUN, FireCadence.AUTO_SLOW_MS)
    )

    fun profileFor(weapon: Weapon): AutomaticFireProfile? = profiles[weapon]

    /** Automatic cadence, or null for every single-shot/melee/launcher weapon. */
    fun intervalFor(weapon: Weapon): Long? = profiles[weapon]?.intervalMs

    /** Positional damage shared by player and enemy automatic bullets. */
    fun damageAt(weapon: Weapon, proximityPercent: Float, victimMaxHp: Int): Int =
        profiles[weapon]?.damageAt(proximityPercent, victimMaxHp)
            ?: Combat.weaponDamage(weapon, proximityPercent, victimMaxHp)
}
