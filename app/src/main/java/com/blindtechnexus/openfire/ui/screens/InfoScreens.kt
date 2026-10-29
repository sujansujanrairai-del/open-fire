package com.blindtechnexus.openfire.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindtechnexus.openfire.data.Strings
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.ui.components.ButtonType
import com.blindtechnexus.openfire.ui.components.GameButton
import com.blindtechnexus.openfire.ui.components.GameCard
import com.blindtechnexus.openfire.ui.components.HintBox
import com.blindtechnexus.openfire.ui.components.TipBox
import com.blindtechnexus.openfire.ui.theme.CardBg
import com.blindtechnexus.openfire.ui.theme.CardBorder
import com.blindtechnexus.openfire.ui.theme.TextAccent
import com.blindtechnexus.openfire.ui.theme.TextMuted
import com.blindtechnexus.openfire.ui.theme.WarnBright
import com.blindtechnexus.openfire.viewmodel.GameViewModel

@Composable
fun GuideScreen(viewModel: GameViewModel) {
    val settings by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()
    val guideLines = Strings.getGuide(settings.lang)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        GameCard {
            Text(
                text = viewModel.t("guideTitle"),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(14.dp))
            guideLines.forEachIndexed { index, line ->
                Text(
                    text = "${index + 1}. $line",
                    fontSize = 15.sp,
                    color = Color.White,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GameButton(
            text = viewModel.t("back"),
            onClick = { viewModel.navigateTo(Screen.MENU) },
            type = ButtonType.PRIMARY,
            testTag = "guide_back_button"
        )
    }
}

@Composable
fun ProfileScreen(viewModel: GameViewModel) {
    val settings by viewModel.settings.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val inventory by viewModel.inventory.collectAsState()
    val scrollState = rememberScrollState()
    var showClearDialog by remember { mutableStateOf(false) }

    val weapons = inventory.ownedWeapons()
    val extensions = Extension.entries.filter { inventory.extensionOwned(it) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        GameCard {
            Text(
                text = viewModel.t("profileTitle"),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(text = "${viewModel.t("name")}: ${settings.name}", fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("round")}: ${stats.round}",
                fontSize = 16.sp,
                color = WarnBright,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "${viewModel.t("level")}: ${stats.level}", fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("needWin")}: ${stats.needWinsForNext}",
                fontSize = 16.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("coins")}: ${stats.coins}",
                fontSize = 16.sp,
                color = WarnBright,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "${viewModel.t("games")}: ${stats.games}", fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "${viewModel.t("wins")}: ${stats.wins}", fontSize = 16.sp, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "${viewModel.t("kills")}: ${stats.kills}", fontSize = 16.sp, color = Color.White)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Everything the player owns is listed here, exactly as announced by
        // the spoken status.
        GameCard {
            Text(
                text = viewModel.t("profileWeapons"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (weapons.isEmpty()) {
                    viewModel.t("noneOwned")
                } else {
                    weapons.joinToString(", ") { viewModel.t(it.nameKey) }
                },
                fontSize = 15.sp,
                color = Color.White,
                lineHeight = 21.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${viewModel.t("lastWeapon")}: " +
                    viewModel.t(inventory.startingWeapon().nameKey),
                fontSize = 14.sp,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = viewModel.t("profileDefences"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${inventory.medikits} ${viewModel.t("medkitsPlural")}, " +
                    "${inventory.glueWalls} ${viewModel.t("glueWallsPlural")}, " +
                    "${inventory.covers} ${viewModel.t("coversPlural")}, " +
                    "${inventory.antiMissiles} ${viewModel.t("antiMissilesPlural")}",
                fontSize = 15.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${viewModel.t("extMissilePowers")}: ${inventory.missilePowers}",
                fontSize = 15.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${viewModel.t("extBombPowers")}: ${inventory.bombPowers}",
                fontSize = 15.sp,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = viewModel.t("profileExtensions"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (extensions.isEmpty()) {
                    viewModel.t("noneOwned")
                } else {
                    extensions.joinToString(", ") { extension ->
                        val state = if (inventory.extensionEnabled(extension)) {
                            viewModel.t("enabledState")
                        } else {
                            viewModel.t("disabledState")
                        }
                        "${viewModel.t(extension.nameKey)} ($state)"
                    }
                },
                fontSize = 15.sp,
                color = Color.White,
                lineHeight = 21.sp
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        GameButton(
            text = viewModel.t("clearStats"),
            onClick = {
                showClearDialog = true
                viewModel.speak("${viewModel.t("clearStatsTitle")} ${viewModel.t("clearStatsMessage")}")
            },
            type = ButtonType.DANGER,
            testTag = "clear_stats_button"
        )

        Spacer(modifier = Modifier.height(12.dp))

        GameButton(
            text = viewModel.t("back"),
            onClick = { viewModel.navigateTo(Screen.MENU) },
            type = ButtonType.PRIMARY,
            testTag = "profile_back_button"
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = {
                Text(
                    text = viewModel.t("clearStatsTitle"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = viewModel.t("clearStatsMessage"),
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearStats()
                    },
                    modifier = Modifier.testTag("clear_stats_confirm_button")
                ) {
                    Text(
                        text = viewModel.t("confirmClear"),
                        color = WarnBright,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    modifier = Modifier.testTag("clear_stats_cancel_button")
                ) {
                    Text(text = viewModel.t("cancel"), color = TextAccent)
                }
            },
            containerColor = CardBg,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }
}

@Composable
fun AboutScreen(viewModel: GameViewModel) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    fun openUrl(url: String) {
        try {
            val fullUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        GameCard {
            Text(
                text = viewModel.t("aboutTitle"),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = viewModel.t("aboutText"),
                fontSize = 16.sp,
                color = Color.White,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CardBorder)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = viewModel.t("developed"),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = viewModel.t("version"),
                fontSize = 14.sp,
                color = TextAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CardBorder)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = viewModel.t("specialSupport"),
                fontSize = 14.sp,
                color = TextAccent,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = viewModel.t("specialSupportName"),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.testTag("about_special_support_name")
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = viewModel.t("builtInNepal"),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.testTag("about_built_in_nepal")
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        GameButton(
            text = viewModel.t("joinTelegram"),
            onClick = { openUrl("t.me/blindtechnexus") },
            type = ButtonType.DEFAULT,
            testTag = "join_telegram_button"
        )

        Spacer(modifier = Modifier.height(12.dp))

        GameButton(
            text = viewModel.t("contactWhatsApp"),
            onClick = { openUrl("wa.me/9779741825274") },
            type = ButtonType.PRIMARY,
            testTag = "contact_whatsapp_button"
        )

        Spacer(modifier = Modifier.height(14.dp))

        GameButton(
            text = viewModel.t("back"),
            onClick = { viewModel.navigateTo(Screen.MENU) },
            type = ButtonType.DANGER,
            testTag = "about_back_button"
        )
    }
}

@Composable
fun ChangelogScreen(viewModel: GameViewModel) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        GameCard {
            Text(
                text = viewModel.t("changelogTitle"),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Latest balance and accessibility update",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright
            )
            Spacer(modifier = Modifier.height(8.dp))
            val latestNotes = listOf(
                "Weapon damage is now a fixed number of health points. A 14 point rifle always takes 14 health, never 140 on a high-round fighter. The quick menu protection is a flat 2 health points and the AWM long range hit is exactly 10 health points.",
                "Robert fires one or two shots at random intervals between 1.5 and 2 seconds, or once a second while his own cover or glue wall protects him and you have none. He compares hits in the current exchange: two of your hits against one of his make him retreat immediately, while landing more hits keeps him fighting.",
                "Every defence Robert carries is really deployed when his health falls, and his weapon is chosen for the situation. Stray reload, jump and unused gunshot samples no longer play on the enemy side, and a tap in the center of the battle screen never fires a gun.",
                "Weapon sounds own one exclusive channel: a new shot stops every other weapon sound and every queued gunshot, and ducks the footsteps, so two guns or a gun and a footstep never mix or leak.",
                "The battle, flight and result screens never vibrate - they are speech and sound only. The shop now shows a separate, dynamic unlock line under every round-gated weapon, defence and power; it disappears once the item is unlocked.",
                "Completing round three unlocks the anti-missile system and pays one free interceptor. In round four Robert has one missile power and two interceptors; in round five he has two missile powers, four interceptors, five anti-bomb explosives and unlimited bombs and gun ammunition.",
                "The final round keeps its seven minute limit: if Robert survives that long you lose the game. A defeat still costs 20 coins unless you have none, and the timer bomb picker still freezes all enemy fire until you confirm or cancel it.",
                "Wall sound fixed: the dedicated wall-sound.mp3 is the one and only boundary impact, it belongs to the player alone and it plays once when a run is first stopped, so holding a finger against a wall can no longer smear it into a continuous, stretched noise.",
                "Robert never touches a wall again. Every one of his steps is simulated against the four boundaries before he takes it: the moment a heading would bring him near a boundary he cuts sideways, turns back or runs at the open middle of the zone, silently. The repeated and stretched wall sound on the enemy side is gone for good.",
                "Machine gun and MP40 fire is now one truly gapless loop: the gun sound starts on the very instant of the hold, it never breaks between two repeats and no enemy shot can cut it. Lifting the hand pauses it in the same millisecond and the next hold resumes it instantly; a held burst now lasts as long as the trigger and the magazine allow.",
                "Robert now answers with your own trigger speed: face to face with no cover, with only a cover, or with only a gloo wall between you, he fires at exactly your cadence - half a second, a quarter of a second, whatever he hears. Coverless against a covered player he never waits longer than one second and matches any faster rate at once. A gloo wall is always detected and answered with his sniper rifle.",
                "Round one mirrors you: a sniper rifle you buy is handed to Robert as well, and a gloo wall of yours allows him one gloo wall per game. From round two he always carries two gloo walls per game.",
                "Weapon powers were rebalanced upward by capacity and now reach the full 40 point ceiling: knife 0, 8, 12 and 14; shotgun 0, 6, 11 and 16; assault rifle 0, 8, 13 and 18; pistol 0, 9, 14 and 20; sniper rifle 0, 10, 16 and 22; sword 0, 10, 16 and 22; AWM 14, 16, 26 and 40; MP40 0, 5, 8 and 11; machine gun 0, 7, 11 and 15. The AWM long range hit is 14 health points and the new Max Speed extension for 300 coins makes you run 30 percent faster than the speed level you selected.",
                "Enemy combat rebuilt: one hit and Robert fires straight back at once; two hits in a row, or simply out-firing him, and he sprints right across the 100 metre zone at twice your own top running speed before flanking you from a new angle. Raise a gloo wall he cannot break and he refuses the fight entirely and runs away until the wall drops. The 'the enemy holds his fire while you choose the timer' prompt is no longer spoken over the timer itself."
            )
            latestNotes.forEach { note ->
                Text(
                    text = "• $note",
                    fontSize = 14.sp,
                    color = Color.White,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CardBorder)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Version 2.4 (Current)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright
            )
            Spacer(modifier = Modifier.height(8.dp))
            val v23Notes = listOf(
                "Bomb Power: new stackable consumable for 1500 coins - 1 Bomb Power allows 1 use of any bomb type (1 Atom Bomb + 1 Timer Bomb + 1 Grenade use), exactly like Missile Powers. Buy and stack in the shop, one is consumed per throw. Bombs now track the enemy like missiles and guarantee destruction at any cost unless intercepted by an anti-bomb explosive.",
                "Tracking bombs: grenade, atom bomb and timer bomb all follow the target continuously until detonation. The old 50 metre escape race is gone - without an anti-bomb you cannot outrun a tracking bomb. The timer bomb still counts 15 to 60 seconds with its looping timer sound, but now also tracks.",
                "Enemy bomb and anti-bomb limits aligned per round: round 3 = two grenades and two anti-bombs per game, round 4 = three uses of each bomb and three anti-bombs, round 5 = five anti-bombs and unlimited bombs. Grenades unlock together with atom and timer bombs in round 3.",
                "Enemy firing rebuilt: Robert fires continuously only when you are vulnerable (damaged, trapped or locked); otherwise he fires at random intervals of 2, 2.5 or 3 seconds (now 1.5 to 2 seconds in the latest update). When you fire back while he is firing he immediately retreats.",
                "Panic and defence deployment: below 150 percent health difference Robert panics and instantly deploys a defence; below 200 percent even a 1 percent dip triggers panic. Strategic weapon picking: 40-70 percent health prefers mid-range, 90-100 percent prefers close heavy weapons, 20-40 percent prefers AWM long range, about 75 percent of the time.",
                "Retreat logic: Robert retreats when he took significant damage, when the position is unsafe, or when you have a Gloo Wall up and he has no usable sniper rifle - then he avoids you until the wall drops.",
                "Round 5 strategy over stats: both sides fight with equal power and only tactics decide the win.",
                "Distinct end-game sounds: level completion plays only level-complete, round completion plays only round-complete, a normal victory plays win and a defeat plays lose - they are never mixed together.",
                "MP40 loop stabilized: the hold-to-fire sound now runs stably as streamed audio until you release the swipe, with proper cleanup on release.",
                "Stable sound mixer: long sounds such as missiles, bombs, timer countdowns and progression stings are streamed via the enhanced player, prepared in the background, retried on failure, and fully stopped on screen changes."
            )
            v23Notes.forEach { note ->
                Text(
                    text = "• $note",
                    fontSize = 14.sp,
                    color = Color.White,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CardBorder)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Version 2.2",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            val v22Notes = listOf(
                "Running speeds: swipe right with three fingers for one step faster and left for one step slower. The three levels are slow, normal (the default) and fast, and the fast level matches Robert's own fast fleeing speed for the round exactly. Your running sound now follows the level too: louder and quicker when fast, quieter and dragged when slow.",
                "A real 100 metre battlefield with four boundaries. Running into one stops you completely, plays the wall impact sound and speaks Wall; nobody can leave the perimeter. Robert detects the same walls, turns back towards the open middle and then fights on.",
                "The center portion of the battle screen is split in two halves: hold the left half to run left and the right half to run right, so all four directions are reachable. Your own and Robert's real positions are shown and spoken in the status.",
                "Damage table: one single table now drives every hit, in percent of maximum health. Knife 0/5/8/10, shotgun 0/5/8/12, assault rifle 0/7/10/14, pistol 0/7/11/15, sniper rifle 0/8/12/16, sword 0/8/12/16, AWM 10/12/18/24, MP40 0/4/6/8 and machine gun 0/5/8/11. No normal weapon takes more than 40 percent in one hit, so a burst can no longer erase an enemy while you lose two percent.",
                "The AWM is the long-range rifle: inside the 20 to 40 percent band, where everything else misses, it still lands for 10 percent of maximum health and never less than 10 health.",
                "New weapons by round: MP40 (900) and AWM (1400) unlock and are issued free in round two, the grenade (700) is issued free in round three while the atom bomb (2400) and timer bomb (2000) are shop weapons, and the machine gun (1900) unlocks in round four and is never a reward. Robert receives the MP40 in round two, the AWM and his bombs in round three, and the machine gun in round four.",
                "Submachine guns: hold the up-swipe and the MP40 fires one bullet every half second for up to four seconds and the machine gun one bullet every 0.4 seconds for up to five, with the gun sound looped for the whole burst. Lifting the finger stops the bullets and the sound together, at any moment. Robert answers a closing MP40 with his own MP40 automatically.",
                "Bombs: thrown at a fixed destination and only destructive inside 50 metres. A target with no anti-bomb explosive sprints for the safety line, so the atom bomb usually catches him, the grenade is a photo finish decided by walls, and a timer bomb always lets him out.",
                "Timer bomb: swipe up speaks Select your timer, swipe right or left walks through 15, 30, 40, 50 and 60 seconds and a tap throws it. The timer sound loops for the whole countdown, then the explosion plays. Stay at least 50 metres away or both fighters are destroyed and you lose. The bomb destination is shown and spoken next to the enemy health.",
                "Anti-bomb explosive defence (2600 coins, after round two): deploy it while an enemy bomb is in the air and it destroys that bomb, with the explosion still playing. Robert carries one per game from round three and deploys it automatically the moment he hears your bomb, never twice in the same game.",
                "Every explosion is now heard in full: a missile or bomb impact mixes the hit sound with the explosion and the battle only ends after the explosion has finished. From round four an intercepted missile plays its explosion in the air too, and round three Robert has no anti-missile at all, so he simply sprints until the missile kills him.",
                "Progression and rewards: five wins complete a level and pay one of five random bundles (two covers; three medikits; one medkit and two covers; one gloo wall and two medikits; one gloo wall and two covers, never more than one gloo wall), while a completed round pays two gloo walls, two medikits and three covers, with level-complete and round-complete sounds.",
                "Robert fights back properly: your continuous fire makes him fire continuously, his own health dropping makes him retreat, and your health dropping makes him press on. He strafes sideways, uses his barriers and medkits situationally, and his defence threshold is below 60 percent (never under 100 health) in rounds one and two, below 200 health in round three and below 300 health in rounds four and five.",
                "Gloo wall tactics: with no usable sniper Robert flees and refuses to approach until your gloo wall drops; with a working sniper he closes in and fires continuously, and he raises a cover first when he has one.",
                "The gloo wall and the cover are mutually exclusive: activating one while the other is up consumes nothing and simply tells you which one is already activated.",
                "Shop: an Enable / Disable button under every weapon row, below buy and remove. A disabled weapon is never issued at the start of a battle and is skipped by the left and right weapon swipes until you enable it again.",
                "Balanced prices: every weapon 200 coins cheaper, every defence 200 coins cheaper and every extension 50 coins cheaper. Knife 300, pistol 550, sword 750, MP40 900, sniper rifle 1000, AWM 1400, machine gun 1900, grenade 700, timer bomb 2000, atom bomb 2400, missile 3300, missile power 1600, medikit 300, cover 450, gloo wall 650, anti-missile 2800, anti-bomb 2600 and extensions 100, 190, 250 and 250.",
                "Menu protection now costs 2 percent of maximum health per hit and expires by itself after six seconds, so browsing the quick menu is safe but can never become invulnerability.",
                "Sound engine rebuilt for smooth playback on every device: long sounds such as the missile, bombs, timer countdown and the progression stings are streamed and prepared in the background instead of loaded into the small sound pool, refused streams are retried automatically, and every projectile sound stops at once on a screen change.",
                "The complete user guide was rewritten in English, Hindi and Nepali for the whole new game structure."
            )
            v22Notes.forEach { note ->
                Text(
                    text = "• $note",
                    fontSize = 14.sp,
                    color = Color.White,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CardBorder)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "Version 2.1",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            val v21Notes = listOf(
                "Sound design: a knife or a sword that misses plays its own miss sound, and every hit mixes the weapon sound (knife-hit, sword-hit or gun-hit) with the sound of the fighter that was hit (enemy-hit or player-hit).",
                "Barriers: the glue wall now plays glue-sound when raised, glue-hit when it is hit and glue-wall-crack when it breaks. The cover plays cover, cover-hit and cover-crack. The airplane menu mixes the airplane sound with the airplane engine.",
                "The battle screen now starts in complete silence: the instructive message is no longer spoken when the game screen opens.",
                "Five levels now complete one round. Health starts at 200 in round 1 level 1 and rises by 100 at every level without resetting between rounds.",
                "New base and close-range weapon powers: knife 10/10, shotgun 10/12, assault rifle 13/14, pistol 14/15, sniper rifle 15/16 and sword 15/16. Robert has dedicated assault rifle and shotgun zone tables.",
                "Gesture-only gameplay: no buttons appear after Play Game. Hold the top or bottom third to run, release to stop, and tap the center third for the quick menu.",
                "Independent mixed footsteps keep your own running sound centered while Robert approaches or retreats in 3D, with faster movement in every later round.",
                "The Missile (3500 coins) unlocks after round two and fights for real: switch to it like any weapon, swipe up to launch, and it always tracks and completely destroys its target through running, glue walls and covers — the looping missile sound plays while the enemy flees to the far edge, then stops for the explosion and hit sound at impact. Every launch consumes one stackable Missile Power (1800 coins, bought and used like a defence; swipe down with the missile selected to buy one mid-battle).",
                "Anti-Missile System in Shop Defences (3000 coins, unlocked after completing Round 2) and in the quick battle menu right after Glue Wall before Exit Game: deploying it while a missile is active destroys the missile in the air with an explosion sound.",
                "New round loadouts: round one levels one and two have two medikits and one cover, and levels three to five have two medikits and two covers; round two carries three medikits, a glue wall, two covers, the knife and the sword; round three carries three medikits, two glue walls, four covers and the pistol; from round four every weapon is available, including Robert's one desperation missile per game (playing player-hit alongside explosion-sound on impact) and his end-game anti-missile interceptors (one in round four, two in round five, only below 100 health).",
                "Smarter Robert: he reads the fight instead of firing on a fixed clock — continuous close fire in the 70 to 90 percent or 90 to 100 percent range while advantaged, and a full-speed escape when disadvantaged after sustaining two or three hits against only one or two shots in combat range.",
                "Positional audio rework: head-on fighting stays perfectly centred (only flanks pan), footsteps play at the speech level with a speed-scaled rate and distance volume refreshed every half second (quarter second at a sprint), and pressing the running zones harder makes you run faster thanks to quicker, pressure-sensitive long-press detection.",
                "Balanced prices: knife 500 coins, pistol 750 coins, sword 950 coins, sniper rifle 1200 coins, missile 3500 coins, medikit 500 coins, cover 650 coins, glue wall 850 coins, anti-missile system 3000 coins, and tripled extension prices (150, 240, 300 and 300 coins).",
                "Settings: new Keep screen always on switch to keep the screen awake and prevent screen locking, and protected profile storage moved to .open_fire/data/ with automatic migration.",
                "A cover now stops only 11 hits and breaks on the 12th.",
                "The quick menu item Play was replaced by Recover, which repairs every damaged weapon for 100 coins instead of 300.",
                "While the weapon in your hands is damaged, Robert closes in to the 90 to 100 percent band and fires continuously until you switch to a working weapon or pay for Recovery.",
                "Robert now randomly prefers to fight either from the 40 to 70 percent band or from the 90 to 100 percent band, and once in a while he fires one or two shots from the 0 to 40 percent band, which always miss, to confuse you.",
                "About screen: special support in testing management head, Rajip Ghatani, and built and maintained in Nepal."
            )
            v21Notes.forEach { note ->
                Text(
                    text = "• $note",
                    fontSize = 14.sp,
                    color = Color.White,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CardBorder)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Version 2.0",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            val v20Notes = listOf(
                "Shop: a new Shop button opens a full shop before Play Game. It sells the pistol for 200 coins, the sniper rifle for 300, the knife for 250 and the sword for 340, with a Buy button or a Remove button protected by a confirmation.",
                "Defences: medikits (200 coins), glue walls (400 coins) and covers (350 coins) can be bought more than once and every use decreases the count by exactly one. The shop speaks your totals and reminds you that defences are repeatable.",
                "Extensions: automatic reload (50), automatic medikit under 50 health (80), automatic cover (100) and automatic glue wall (100). Each one can be removed after a confirmation and switched off or on; a disabled extension never triggers.",
                "Weapon powers: assault gun 10, sniper rifle 9, knife 8, sword 12, pistol 15 and short gun 6 (10 for Robert). Distance still decides the damage: miss below 40 percent, half from 40 to 70, 80 percent from 70 to 90 and full power from 90 to 100.",
                "Barriers: a cover stops 16 hits and breaks on the 17th. A glue wall stops every bullet and weapon and only breaks after three continuous sniper rifle hits from 90 to 100 percent range.",
                "Redesigned battle screen: one-finger and three-finger gestures and the old two-finger item gestures are gone. The new quick menu runs medikit, cover, glue wall, exit and play; two fingers up or down move the selection and a single tap activates it.",
                "Missing items in battle: you hear that you do not have the item, and a second tap buys it at the shop price and activates it immediately. Not enough coins is shown as a toast and spoken aloud.",
                "Menu protection: while you are navigating the quick menu every hit costs only 2 health.",
                "Rounds: 10 wins equal a level, 10 levels equal a round and the fifth round wins the whole match. Health is 300, 400, 500, 600 and 750 for rounds one to five.",
                "Robert: his medkits, covers, glue walls and weapons grow every round, he uses a medikit first then a cover then a glue wall below 60 health, he reloads freely after six shots, and his sniper stays locked in the first two rounds unless you raised a glue wall while he is under 100 health.",
                "Robert runs far away after two continuous hits in round one, three in round three, four in round four and five in round five.",
                "Automatic reload, remembered last weapon, free reloads, new sword, knife and barrier sounds, and the walking sound now plays at 75 percent of the TTS volume.",
                "Shop inventory, weapons, defences and extensions are now shown in the profile and in the spoken status.",
                "The result screen now waits four seconds before another game can be started.",
                "Storage moved to encrypted external storage for the whole profile, with strong integrity protection against editing.",
                "Nepali and Hindi translations and the complete user guide were rewritten for every new rule."
            )
            v20Notes.forEach { note ->
                Text(
                    text = "• $note",
                    fontSize = 14.sp,
                    color = Color.White,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(CardBorder)
            )
            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Version 1.3",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Four-zone damage, second chance, devil survival items, continuous enemy fire, " +
                    "high speed flee, gun damage and 300 coin recovery, three-finger gesture fix, " +
                    "clear my stats and the 10 wins per level ladder.",
                fontSize = 14.sp,
                color = TextMuted,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Version 1.2",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Reliable sound mixer, never dropped sound effects, coin sound mixed with every " +
                    "purchase, 3D footsteps, six bullet magazines and stable voice volume.",
                fontSize = 14.sp,
                color = TextMuted,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Version 1.0",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextAccent
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "• Initial audio battle royale with accessible controls and screen reader guidance.",
                fontSize = 14.sp,
                color = TextMuted,
                lineHeight = 20.sp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        GameButton(
            text = viewModel.t("back"),
            onClick = { viewModel.navigateTo(Screen.MENU) },
            type = ButtonType.PRIMARY,
            testTag = "changelog_back_button"
        )
    }
}

@Composable
fun ResultScreen(viewModel: GameViewModel) {
    val stats by viewModel.stats.collectAsState()
    val result by viewModel.resultData.collectAsState()
    val ready by viewModel.resultReady.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        GameCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        result.matchWon -> viewModel.t("matchWon")
                        result.won -> viewModel.t("victory")
                        else -> viewModel.t("gameOver")
                    },
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (result.matchWon) {
                        viewModel.t("matchWonMsg")
                    } else if (result.won) {
                        viewModel.t("victoryMsg")
                    } else {
                        viewModel.t("defeatMsg")
                    },
                    fontSize = 16.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (result.matchWon) {
            Spacer(modifier = Modifier.height(12.dp))
            TipBox(text = viewModel.t("matchWonMsg"))
        } else if (result.roundComplete) {
            Spacer(modifier = Modifier.height(12.dp))
            TipBox(text = "${viewModel.t("roundCompleted")} ${viewModel.t("round")}: ${result.round}")
        } else if (result.levelUp) {
            Spacer(modifier = Modifier.height(12.dp))
            TipBox(text = "${viewModel.t("levelUp")} ${viewModel.t("newLevel")}: ${result.level}")
        }

        // The promotion reward: two gloo walls, two medikits and three covers
        // for a completed round, or one of the five random bundles for a
        // completed level (never more than one gloo wall).
        if (result.hasReward && result.rewardText.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            TipBox(text = "${viewModel.t("rewardReceived")}: ${result.rewardText}")
        }

        Spacer(modifier = Modifier.height(12.dp))

        GameCard {
            Text(
                text = "${viewModel.t("earned")}: ${result.coins}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("hits")}: ${result.hits} / ${result.shots}",
                fontSize = 16.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("devilShots")}: ${result.robertShots}",
                fontSize = 16.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("yourHealth")}: ${maxOf(0, result.hp)}",
                fontSize = 16.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("round")}: ${stats.round}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("level")}: ${stats.level}",
                fontSize = 16.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${viewModel.t("needWin")}: ${stats.needWinsForNext}",
                fontSize = 16.sp,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (!ready) {
            HintBox(text = viewModel.t("resultWait"))
        } else {
            HintBox(text = "${viewModel.t("readerUp")}: ${viewModel.t("playAgain")}\n" +
                "${viewModel.t("readerDown")}: ${viewModel.t("mainMenu")}")
        }

        // Result actions are gesture-only: swipe up to play again or swipe
        // down for the main menu. No in-game buttons are rendered.
    }
}
