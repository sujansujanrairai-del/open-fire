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
            Weapon.SNIPER,
            Weapon.GLUE_BREAKER
        )) {
            assertEquals("Weapon '${weapon.id}' must hold six rounds", 6, weapon.magazine)
            assertTrue("Firearms need a reload", weapon.needsReload)
        }
        // The new weapons carry their own magazines.
        assertEquals(5, Weapon.AWM.magazine)
        assertEquals(20, Weapon.MP40.magazine)
        assertEquals(8, Weapon.M10.magazine)
        assertEquals(40, Weapon.SUBMACHINE_GUN.magazine)
        assertEquals(30, Weapon.AK47.magazine)
        assertEquals(50, Weapon.MACHINE_GUN.magazine)
        for (weapon in listOf(
            Weapon.AWM, Weapon.MP40, Weapon.M10,
            Weapon.SUBMACHINE_GUN, Weapon.AK47, Weapon.MACHINE_GUN
        )) assertTrue("${weapon.id} must reload", weapon.needsReload)

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
            Weapon.GLUE_BREAKER, Weapon.AWM, Weapon.MP40, Weapon.M10,
            Weapon.SUBMACHINE_GUN, Weapon.AK47, Weapon.MACHINE_GUN
        ) + Weapon.entries.filter { it.melee } +
            Weapon.entries.filter { it.bomb } + Weapon.MISSILE
        assertEquals(Weapon.entries.size, counted.size)
    }

    @Test
    fun shopCatalogMatchesTheLoweredPrices() {
        val prices = mapOf(
            Weapon.KNIFE to 220, Weapon.PISTOL to 450, Weapon.GRENADE to 600,
            Weapon.M10 to 600, Weapon.SWORD to 650, Weapon.GLUE_BREAKER to 750,
            Weapon.MP40 to 800, Weapon.SNIPER to 850, Weapon.SUBMACHINE_GUN to 900,
            Weapon.AWM to 1200, Weapon.AK47 to 1250, Weapon.MACHINE_GUN to 1700,
            Weapon.TIMER_BOMB to 1700, Weapon.ATOM_BOMB to 2000, Weapon.MISSILE to 2200
        )
        for ((weapon, price) in prices) assertEquals(price, weapon.price)
        assertEquals(listOf(250, 400, 550, 1700, 1800), DefenceItem.entries.map { it.price }.sorted())
        assertEquals(listOf(75, 140, 180, 200, 250), Extension.entries.map { it.price }.sorted())
        assertEquals(1000, MissileRules.MISSILE_POWER_PRICE)
        assertEquals(Weapon.forSale().map { it.price }.sorted(), Weapon.forSale().map { it.price })
        assertTrue(Weapon.forSale().all { it.price <= 2200 })
        assertTrue(DefenceItem.entries.all { it.price <= 2200 })
    }

    @Test
    fun guidesExistForEveryLanguageAndAreDetailed() {
        for (lang in listOf("en", "hi", "ne")) {
            val guide = Strings.getGuide(lang)
            assertEquals(29, guide.size)
            assertTrue("Guide must be detailed", guide.joinToString(" ").length > 4000)
            for (entry in guide) {
                assertTrue(entry.isNotBlank())
                assertFalse(entry.contains("\""))
                assertFalse(entry.contains("\\"))
            }
        }
    }

    @Test
    fun guideDocumentsTheNewRules() {
        val en = Strings.getGuide("en").joinToString(" ")
        for (required in listOf(
            "Five wins complete one level", "five levels complete one round", "2600",
            "25 by 25 metre", "centre left", "Swipe left with three fingers", "sound",
            "pause", "resume", "1.5 seconds", "every 0.5 seconds", "60 HP",
            "knife 0/12/18/26", "AWM 0/28/44/60", "60, 68 or 75 percent",
            "M10", "AK-47", "submachine gun", "instantly",
            "three quick shots", "unlock and can be bought from round 1",
            "heavy hit or repeated", "500, 1000 or 1500", "fewer than 500",
            "1500-coin loan and 1100", "400 owed", "remaining debt", "no interest",
            "missile 2200", "anti-missile 1800", "interceptor", "timer bomb",
            "gloo wall", "second chance", "quick menu", "Settings"
        )) assertTrue("Missing guide rule: $required", en.contains(required, ignoreCase = true))
        assertFalse(en.contains("exclusive channel", ignoreCase = true))
        assertFalse(en.contains("guarantee a kill", ignoreCase = false) && !en.contains("no longer guarantee a kill"))
    }

    @Test
    fun guidesAreTranslatedIntoHindiAndNepali() {
        val en = Strings.getGuide("en")
        for (lang in listOf("hi", "ne")) {
            val guide = Strings.getGuide(lang)
            assertEquals(en.size, guide.size)
            for (index in en.indices) {
                assertTrue(guide[index].isNotBlank())
                assertFalse("Guide entry $index was not translated into $lang", guide[index] == en[index])
            }
            val text = guide.joinToString(" ")
            for (number in listOf("25", "12", "60", "200", "2600", "1500", "1100", "400", "2200", "1800")) {
                assertTrue("$lang must document $number", text.contains(digitsOf(lang, number)))
            }
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
            assertTrue(Strings.get("version", lang).contains("2.5") || Strings.get("version", lang).contains("२.५"))
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
