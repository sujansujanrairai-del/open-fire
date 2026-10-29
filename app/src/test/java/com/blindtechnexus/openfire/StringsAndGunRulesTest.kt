package com.blindtechnexus.openfire

import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.Weapon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps translations, weapon rules, the rewritten user guide and the lowered
 * shop prices standardised.
 */
class StringsAndGunRulesTest {

    @Test
    fun everyEnglishKeyExistsInHindiAndNepali() {
        val keys = Strings.availableKeys()
        assertTrue("English table should define keys", keys.isNotEmpty())
        for (key in keys) {
            assertTrue("Missing Hindi translation for '$key'", Strings.hasTranslation(key, "hi"))
            assertTrue("Missing Nepali translation for '$key'", Strings.hasTranslation(key, "ne"))
        }
    }

    @Test
    fun lookupsNeverLeakTheRawKey() {
        for (key in Strings.availableKeys()) {
            for (lang in listOf("en", "hi", "ne")) {
                val value = Strings.get(key, lang)
                assertTrue("Blank translation for '$key' in '$lang'", value.isNotBlank())
                if (lang != "en") {
                    assertTrue("Raw key '$key' leaked into '$lang'", value != key)
                }
            }
        }
    }

    @Test
    fun everyMagazineMatchesTheDocumentedSize() {
        // The four classic firearms still hold exactly six rounds.
        for (weapon in listOf(
            Weapon.SHORT_GUN,
            Weapon.ASSAULT_GUN,
            Weapon.PISTOL,
            Weapon.SNIPER
        )) {
            assertEquals("Weapon '${weapon.id}' must hold six rounds", 6, weapon.magazine)
            assertTrue("Firearms need a reload", weapon.needsReload)
        }
        // The new weapons carry their own magazines.
        assertEquals(5, Weapon.AWM.magazine)
        assertEquals(20, Weapon.MP40.magazine)
        assertEquals(30, Weapon.MACHINE_GUN.magazine)
        assertTrue(Weapon.AWM.needsReload)
        assertTrue(Weapon.MP40.needsReload)
        assertTrue(Weapon.MACHINE_GUN.needsReload)

        // Melee weapons never reload.
        for (weapon in Weapon.entries.filter { it.melee }) {
            assertEquals(0, weapon.magazine)
            assertFalse("Melee weapons never reload", weapon.needsReload)
        }

        // A bomb carries exactly one charge per battle and never reloads, so
        // the automatic reload extension cannot refill it.
        for (weapon in Weapon.entries.filter { it.bomb }) {
            assertEquals("Bomb '${weapon.id}' must hold one charge", 1, weapon.magazine)
            assertFalse("Bombs never reload", weapon.needsReload)
        }

        // The missile launcher has no magazine at all: a launch spends one
        // stackable missile power.
        assertEquals(0, Weapon.MISSILE.magazine)
        assertFalse(Weapon.MISSILE.needsReload)
        assertTrue(Weapon.MISSILE.missileLauncher)

        // Every battle weapon is either a firearm, melee, a bomb or the
        // launcher: nothing slipped through untested.
        val counted = listOf(
            Weapon.SHORT_GUN, Weapon.ASSAULT_GUN, Weapon.PISTOL, Weapon.SNIPER,
            Weapon.AWM, Weapon.MP40, Weapon.MACHINE_GUN
        ) + Weapon.entries.filter { it.melee } +
            Weapon.entries.filter { it.bomb } + Weapon.MISSILE
        assertEquals(Weapon.entries.size, counted.size)
    }

    @Test
    fun shopCatalogMatchesTheLoweredPrices() {
        // Every weapon is 200 coins cheaper than before.
        assertEquals(300, Weapon.KNIFE.price)
        assertEquals(550, Weapon.PISTOL.price)
        assertEquals(750, Weapon.SWORD.price)
        assertEquals(1000, Weapon.SNIPER.price)
        assertEquals(3300, Weapon.MISSILE.price)
        assertEquals(900, Weapon.MP40.price)
        assertEquals(1400, Weapon.AWM.price)
        assertEquals(1900, Weapon.MACHINE_GUN.price)
        assertEquals(700, Weapon.GRENADE.price)
        assertEquals(2400, Weapon.ATOM_BOMB.price)
        assertEquals(2000, Weapon.TIMER_BOMB.price)
        // Every defence is 200 coins cheaper than before.
        assertEquals(300, DefenceItem.MEDKIT.price)
        assertEquals(650, DefenceItem.GLUE_WALL.price)
        assertEquals(450, DefenceItem.COVER.price)
        assertEquals(2800, DefenceItem.ANTI_MISSILE.price)
        assertEquals(2600, DefenceItem.ANTI_BOMB.price)
        assertEquals(1600, MissileRules.MISSILE_POWER_PRICE)
        // Extensions: the four automatic helpers plus Max Speed.
        assertEquals(100, Extension.AUTO_RELOAD.price)
        assertEquals(190, Extension.AUTO_MEDKIT.price)
        assertEquals(250, Extension.AUTO_COVER.price)
        assertEquals(250, Extension.AUTO_GLUE_WALL.price)
        assertEquals(300, Extension.MAX_SPEED.price)
        // The catalogue is complete: 13 weapons, 5 defences, 5 extensions.
        assertEquals(13, Weapon.entries.size)
        assertEquals(5, DefenceItem.entries.size)
        assertEquals(5, Extension.entries.size)
    }

    @Test
    fun guidesExistForEveryLanguageAndAreDetailed() {
        // The rewritten guide covers the whole game structure in 38 entries.
        assertEquals(38, Strings.getGuide("en").size)
        assertEquals(38, Strings.getGuide("hi").size)
        assertEquals(38, Strings.getGuide("ne").size)
        for (lang in listOf("en", "hi", "ne")) {
            val guide = Strings.getGuide(lang)
            assertTrue("Guide must be detailed", guide.joinToString(" ").length > 8000)
            for (entry in guide) {
                assertTrue("Guide entry must not be blank", entry.isNotBlank())
                // A guide entry is read out loud, so it must stay plain text.
                assertFalse("Guide entry must not use quotes", entry.contains("\""))
                assertFalse("Guide entry must not use a backslash", entry.contains("\\"))
            }
        }
    }

    @Test
    fun guideDocumentsTheNewRules() {
        val en = Strings.getGuide("en").joinToString(" ")
        // Structure, progression and rewards.
        assertTrue("Guide must document the five rounds", en.contains("five rounds"))
        assertTrue("Guide must document 5 wins per level", en.contains("five wins complete one level"))
        assertTrue("Guide must document five levels per round", en.contains("five levels complete one round"))
        assertTrue("Guide must document level health", en.contains("starts at 200 health"))
        assertTrue("Guide must document the next round's health", en.contains("round 2 level 1 has 700"))
        assertTrue("Guide must document the top health", en.contains("reaches 2600 health"))
        assertTrue("Guide must document the level reward bundles", en.contains("never contains more than one gloo wall"))
        assertTrue("Guide must document the round reward", en.contains("two gloo walls, two medikits and three covers"))
        assertTrue("Guide must document the completion sounds", en.contains("level complete sound"))
        assertTrue("Guide must document the reward being spoken", en.contains("exactly what you received"))
        // The arena, the walls and the movement.
        assertTrue("Guide must document the 100 meter zone", en.contains("real 100 meter zone with four boundaries"))
        assertTrue("Guide must document the wall sound", en.contains("the wall impact sound plays"))
        assertTrue("Guide must document the spoken wall", en.contains("the word Wall is spoken"))
        assertTrue("Guide must document Robert turning back", en.contains("turns back towards the open middle"))
        assertTrue("Guide must document three screen portions", en.contains("divided into three portions"))
        assertTrue("Guide must document the center split", en.contains("center portion is split in two halves"))
        assertTrue("Guide must document the three-finger speed", en.contains("Three-finger swipes change your running speed"))
        assertTrue("Guide must document the speed default", en.contains("every battle starts at normal"))
        assertTrue("Guide must document the fast level", en.contains("matches Robert's own fast fleeing speed"))
        assertTrue("Guide must document the running volume", en.contains("a fast run is louder and quicker"))
        assertTrue("Guide must document pressure running", en.contains("stronger you press"))
        // The damage table and the caps.
        assertTrue("Guide must document the bands", en.contains("70 to 90 percent is the strong band"))
        assertTrue("Guide must document the knife row", en.contains("knife 0, 8, 12 and 14 health points"))
        assertTrue("Guide must document the shotgun row", en.contains("shotgun 0, 6, 11 and 16"))
        assertTrue("Guide must document the MP40 row", en.contains("MP40 0, 5, 8 and 11"))
        assertTrue("Guide must document the machine gun row", en.contains("machine gun 0, 7, 11 and 15"))
        assertTrue("Guide must document the AWM row", en.contains("AWM 14, 16, 26 and 40"))
        assertTrue("Guide must document the 40 percent cap", en.contains("more than 40 health points in one hit"))
        assertTrue("Guide must document the AWM long range band", en.contains("20 percent to 40 percent band"))
        assertTrue("Guide must document the AWM damage", en.contains("never less than 14 health points"))
        assertTrue("Guide must document the explosives", en.contains("full 100 percent capacity"))
        // Magazines and the submachine guns.
        assertTrue("Guide must document the six round magazines", en.contains("hold exactly six rounds"))
        assertTrue("Guide must document the AWM magazine", en.contains("the AWM holds five"))
        assertTrue("Guide must document the MP40 magazine", en.contains("the MP40 holds twenty"))
        assertTrue("Guide must document the machine gun magazine", en.contains("the machine gun holds thirty"))
        assertTrue("Guide must document the single bomb charge", en.contains("exactly one charge per battle"))
        assertTrue("Guide must document the auto fire", en.contains("one bullet every half second"))
        assertTrue("Guide must document stopping the burst", en.contains("stops the bullets and the looped sound together"))
        assertTrue("Guide must document the MP40 detection", en.contains("switches to his own MP40 automatically"))
        assertTrue("Guide must document four direction movement", en.contains("moves in all four directions"))
        // Per-round unlocks.
        assertTrue("Guide must document the round two issue", en.contains("issued to you for free at the start of the battle"))
        assertTrue("Guide must document Robert's MP40", en.contains("Robert receives the MP40 in round two"))
        assertTrue("Guide must document the free grenade", en.contains("issues you a free grenade"))
        assertTrue("Guide must document Robert's AWM", en.contains("gives Robert the AWM plus his own bombs"))
        assertTrue("Guide must document the machine gun round", en.contains("never part of any reward"))
        // Missiles.
        assertTrue("Guide must document the Missile", en.contains("The Missile costs 3300 coins"))
        assertTrue("Guide must document missile powers", en.contains("missile power for 1600 coins"))
        assertTrue("Guide must document guaranteed tracking", en.contains("100 percent accuracy"))
        assertTrue("Guide must document the anti-missile rule", en.contains("anti-missile system"))
        assertTrue("Guide must document anti-missile price", en.contains("Anti-missile system costs 2800 coins"))
        assertTrue("Guide must document round three without interceptors", en.contains("no anti-missile system at all"))
        assertTrue("Guide must document the sprint away", en.contains("sprints away at full speed"))
        assertTrue("Guide must document the explosion before the end", en.contains("after the explosion has completely finished"))
        assertTrue("Guide must document the intercepted blast", en.contains("also plays its explosion sound right there in the air"))
        // Bombs.
        assertTrue("Guide must document the fixed destination", en.contains("lands on a fixed destination"))
        assertTrue("Guide must document the 50 meter blast", en.contains("inside 50 meters of the destination"))
        assertTrue("Guide must document the escape race", en.contains("sprints away from the destination"))
        assertTrue("Guide must document the timer picker", en.contains("Select your timer"))
        assertTrue("Guide must document the timer durations", en.contains("15, 30, 40, 50 and 60 seconds"))
        assertTrue("Guide must document the timer explosion", en.contains("then stops, and the explosion sound plays"))
        assertTrue("Guide must document the thrower death", en.contains("destroys its own thrower"))
        assertTrue("Guide must document the safe distance", en.contains("at least 50 meters away"))
        assertTrue("Guide must document the bomb destination display", en.contains("shown and spoken next to the enemy health"))
        assertTrue("Guide must document Robert's bombs", en.contains("two grenades per game in round three"))
        assertTrue("Guide must document the anti-bomb price", en.contains("anti-bomb explosive defence costs 2600 coins"))
        assertTrue("Guide must document the enemy anti-bomb table", en.contains("two anti-bomb explosives per game in round three"))
        // Defences, barriers and Robert's tactics.
        assertTrue("Guide must document the glue wall", en.contains("gloo wall"))
        assertTrue("Guide must document the cover", en.contains("Cover costs 450 coins"))
        assertTrue("Guide must document the 11 hit cover", en.contains("stops 11 hits"))
        assertTrue("Guide must document the barrier clash", en.contains("The cover is already activated"))
        assertTrue("Guide must document the glue wall tactic", en.contains("he prefers to flee and will not approach"))
        assertTrue("Guide must document the sniper tactic", en.contains("fires continuously at the wall"))
        assertTrue("Guide must document the cover tactic", en.contains("raises the cover first"))
        assertTrue("Guide must document the round two threshold", en.contains("never below 100 health"))
        assertTrue("Guide must document the round three threshold", en.contains("round three triggers below 200 health"))
        assertTrue("Guide must document the round five threshold", en.contains("rounds four and five trigger below 300 health"))
        // Continuous fire exchange.
        assertTrue("Guide must document the answered fire", en.contains("makes him fire continuously back"))
        assertTrue("Guide must document his retreat", en.contains("he retreats and runs for the far edge"))
        assertTrue("Guide must document the enemy firing bands", en.contains("40 to 70 percent"))
        // Weapon damage, the quick menu and its protection.
        assertTrue("Guide must document the 100 coin recovery", en.contains("100 coins for Recovery"))
        assertTrue("Guide must document the recover menu item", en.contains("anti-bomb explosive, exit and recover"))
        assertTrue("Guide must document the quick menu", en.contains("quick menu"))
        assertTrue("Guide must document the status reads", en.contains("running speed, position, items"))
        assertTrue("Guide must document menu protection", en.contains("only 2 health points"))
        assertTrue("Guide must document the protection expiry", en.contains("after six seconds"))
        // The shop switch and the second chance.
        assertTrue("Guide must document the enable switch", en.contains("Enable / Disable button"))
        assertTrue("Guide must document the skipped weapon", en.contains("skipped by the left and right weapon swipes"))
        assertTrue("Guide must document the lowered prices", en.contains("every weapon costs 200 coins less"))
        assertTrue("Guide must document the lowered extensions", en.contains("every extension costs 50 coins less"))
        assertTrue("Guide must document the extensions", en.contains("automatic reload for 100 coins"))
        assertTrue("Guide must document the second chance", en.contains("one second chance with half of your maximum health"))
        assertTrue("Guide must document the four second wait", en.contains("four seconds"))
        // Audio engine, gestures and settings.
        assertTrue("Guide must document the streamed audio", en.contains("streamed instead of loaded"))
        assertTrue("Guide must document the footstep level", en.contains("Footsteps play at the same level as the speech voice"))
        assertTrue("Guide must document gesture-only gameplay", en.contains("no button controls"))
        assertTrue("Guide must document keep screen on", en.contains("Keep screen always on"))
        // The v2.4 upgrades: flat damage, human fire rhythm, hit-exchange
        // retreat, shop unlock lines, clean audio and the round four reward.
        assertTrue("Guide must document flat damage", en.contains("fixed health points"))
        assertTrue("Guide must document the 14 HP fix", en.contains("always takes 18 health and never 180"))
        assertTrue("Guide must document the 1.5 to 2 s rhythm", en.contains("between 1.5 and 2 seconds"))
        assertTrue("Guide must document the one second press", en.contains("once every second"))
        assertTrue("Guide must document the hit exchange", en.contains("retreats immediately and very quickly"))
        assertTrue("Guide must document shop unlock lines", en.contains("separate line under the description"))
        assertTrue("Guide must document the anti-missile reward", en.contains("one free interceptor"))
        assertTrue("Guide must document the exclusive weapon channel", en.contains("exclusive channel"))
        assertTrue("Guide must document the gapless loop", en.contains("gapless"))
        assertTrue("Guide must document the mirrored cadence", en.contains("fires at exactly your own rate"))
        assertTrue("Guide must document the coverless press", en.contains("never waits longer than one second"))
        assertTrue("Guide must document the round one sniper mirror", en.contains("you buy is mirrored to him"))
        assertTrue("Guide must document the enemy gloo walls", en.contains("two gloo walls per game"))
        assertTrue("Guide must document Max Speed", en.contains("Max Speed for 300 coins"))
        assertTrue("Guide must document no stray gun on a tap", en.contains("never fires a stray gun"))
        assertTrue("Guide must document no vibration", en.contains("battle screen never vibrates"))
    }

    @Test
    fun guidesAreTranslatedIntoHindiAndNepali() {
        val en = Strings.getGuide("en")
        for (lang in listOf("hi", "ne")) {
            val guide = Strings.getGuide(lang)
            assertEquals("Guide '$lang' must have one entry per English entry", en.size, guide.size)
            for (index in en.indices) {
                val translated = guide[index]
                assertTrue("Blank '$lang' guide entry $index", translated.isNotBlank())
                assertFalse(
                    "Guide entry $index was not translated into '$lang'",
                    translated == en[index]
                )
            }
            val text = guide.joinToString(" ")
            // Every new feature must be covered in the translation too. Hindi
            // keeps the Latin digits, Nepali spells them in Devanagari, so the
            // numbers are compared in the digits of that language.
            fun number(value: String) = digitsOf(lang, value)
            assertTrue("'$lang' guide must document the 100 meter zone", text.contains(number("100")))
            assertTrue("'$lang' guide must document the five wins", text.contains(number("5")))
            assertTrue("'$lang' guide must document the timer bomb", text.contains(number("15")))
            assertTrue("'$lang' guide must document the timer maximum", text.contains(number("60")))
            assertTrue("'$lang' guide must document the 50 meter blast", text.contains(number("50")))
            assertTrue("'$lang' guide must document the 40 percent cap", text.contains(number("40")))
            assertTrue("'$lang' guide must document the AWM band", text.contains(number("20")))
            assertTrue("'$lang' guide must document the AWM damage", text.contains(number("10")))
            assertTrue("'$lang' guide must document the health ladder", text.contains(number("200")))
            assertTrue("'$lang' guide must document the top health", text.contains(number("2600")))
            assertTrue("'$lang' guide must document the defence threshold", text.contains(number("300")))
            assertTrue("'$lang' guide must document the anti-bomb price", text.contains(number("2600")))
            assertTrue("'$lang' guide must document the missile price", text.contains(number("3300")))
            assertTrue("'$lang' guide must document the missile power", text.contains(number("1600")))
            assertTrue("'$lang' guide must document the machine gun price", text.contains(number("1900")))
            assertTrue("'$lang' guide must document the recovery cost", text.contains(number("100")))
        }
    }

    /**
     * The same number written in the digits the language really uses: Hindi
     * keeps the Latin digits, Nepali uses Devanagari.
     */
    private fun digitsOf(lang: String, value: String): String {
        if (lang != "ne") return value
        val devanagari = "०१२३४५६७८९"
        return value.map { char ->
            val index = "0123456789".indexOf(char)
            if (index < 0) char else devanagari[index]
        }.joinToString("")
    }

    @Test
    fun theOldPlayItemAndItsTextsAreGone() {
        // The quick menu item "play" was replaced by Recovery.
        assertFalse(Strings.availableKeys().contains("playState"))
        assertFalse(Strings.availableKeys().contains("alreadyPlaying"))
        for (lang in listOf("en", "hi", "ne")) {
            assertTrue(Strings.get("recovery", lang).contains("100") || Strings.get("recovery", lang).contains("१००"))
            assertTrue(Strings.get("defenceCoverInfo", lang).contains("11") || Strings.get("defenceCoverInfo", lang).contains("११"))
        }
    }

    @Test
    fun theGuideNeverMentionsTechnologyOrArchitecture() {
        val en = Strings.getGuide("en").joinToString(" ").lowercase()
        for (forbidden in listOf("aes", "encryption", "sqlite", "database", "kotlin", "api", "keystore")) {
            assertTrue("Guide must not mention '$forbidden'", !en.contains(forbidden))
        }
    }

    @Test
    fun newShopAndBattleStringsAreTranslated() {
        for (lang in listOf("en", "hi", "ne")) {
            assertTrue(Strings.get("shop", lang).isNotBlank())
            assertTrue(Strings.get("shopSubtitle", lang).isNotBlank())
            assertTrue(Strings.get("weapons", lang).isNotBlank())
            assertTrue(Strings.get("defences", lang).isNotBlank())
            assertTrue(Strings.get("extensions", lang).isNotBlank())
            assertTrue(Strings.get("notEnoughCoins", lang).isNotBlank())
            assertTrue(Strings.get("recoverWeapon", lang).isNotBlank())
            assertTrue(Strings.get("robertPressing", lang).isNotBlank())
            assertTrue(Strings.get("robertPressureOver", lang).isNotBlank())
            assertTrue(Strings.get("specialSupport", lang).isNotBlank())
            assertTrue(Strings.get("specialSupportName", lang).isNotBlank())
            assertTrue(Strings.get("builtInNepal", lang).isNotBlank())
            assertTrue(Strings.get("itemMissingTail", lang).isNotBlank())
            assertTrue(Strings.get("robertFled", lang).isNotBlank())
            assertTrue(Strings.get("matchWon", lang).isNotBlank())
            assertTrue(Strings.get("version", lang).contains("2.4") || Strings.get("version", lang).contains("२.४"))
            for (key in listOf(
                "unlockAfterRound1", "unlockAfterRound2", "unlockAfterRound3",
                "unlockAfterRound4", "antiMissileReward", "flatDamageInfo"
            )) {
                assertTrue("Missing '$key' in $lang", Strings.get(key, lang).isNotBlank())
                if (lang != "en") {
                    assertTrue("Untranslated '$key' in $lang", Strings.get(key, lang) != key)
                }
            }
        }
    }

    @Test
    fun everyDefenceAndExtensionHasADescription() {
        for (item in DefenceItem.entries) {
            for (lang in listOf("en", "hi", "ne")) {
                assertTrue(Strings.get(item.descriptionKey, lang).isNotBlank())
            }
        }
        for (extension in Extension.entries) {
            for (lang in listOf("en", "hi", "ne")) {
                assertTrue(Strings.get(extension.nameKey, lang).isNotBlank())
                assertTrue(Strings.get(extension.descriptionKey, lang).isNotBlank())
            }
        }
    }
}
