package com.blindtechnexus.openfire.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindtechnexus.openfire.model.BattleMenuItem
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.Weapon
import com.blindtechnexus.openfire.model.Combat
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.HitZone
import com.blindtechnexus.openfire.ui.components.AlertBox
import com.blindtechnexus.openfire.ui.components.HpBar
import com.blindtechnexus.openfire.ui.components.LogBox
import com.blindtechnexus.openfire.ui.components.RadarBar
import com.blindtechnexus.openfire.ui.theme.TextAccent
import com.blindtechnexus.openfire.ui.theme.TextMuted
import com.blindtechnexus.openfire.ui.theme.WarnBright
import com.blindtechnexus.openfire.viewmodel.GameViewModel
import kotlin.math.roundToInt

/**
 * Gesture-only battle screen split into three equal portions:
 * top = hold to run forward, bottom = hold to retreat, and the center portion
 * is split in two halves (left = run left, right = run right) with a tap
 * activating the quick menu or confirming a timer bomb duration.
 *
 * One-finger swipes fire, reload and switch weapons, two-finger swipes move
 * the menu and read the status, and three-finger swipes step the running
 * speed (left/right), pause or resume the battle (down), and exit the game (up).
 */
@Composable
fun BattleScreen(viewModel: GameViewModel) {
    val stats by viewModel.stats.collectAsState()
    val inventory by viewModel.inventory.collectAsState()
    val gameState by viewModel.gameState.collectAsState()
    val paused by viewModel.battlePaused.collectAsState()

    val weapon = gameState.activeWeapon
    val enemyHp = maxOf(0, gameState.robertHp)
    val playerHp = maxOf(0, gameState.playerHp)
    val proxPercent = Combat.proximityOf(gameState.distance)
    val hitZone = Combat.zoneFor(proxPercent)
    val zoneText = when (hitZone) {
        HitZone.MISS -> viewModel.t("zoneMiss")
        HitZone.LIGHT -> viewModel.t("zoneLight")
        HitZone.STRONG -> viewModel.t("zoneStrong")
        HitZone.HEAVY -> viewModel.t("zoneHeavy")
    }
    val zoneColor = when (hitZone) {
        HitZone.MISS -> TextMuted
        HitZone.LIGHT -> Color(0xFF9FD4FF)
        HitZone.STRONG -> WarnBright
        HitZone.HEAVY -> Color(0xFFFF6B6B)
    }

    val menuItem = gameState.menuItem
    val menuCount = when (menuItem) {
        BattleMenuItem.MEDKIT -> inventory.medikits
        BattleMenuItem.COVER -> inventory.covers
        BattleMenuItem.GLUE_WALL -> inventory.glueWalls
        BattleMenuItem.ANTI_MISSILE -> inventory.antiMissiles
        BattleMenuItem.CAR -> inventory.cars
        // A charged anti-bomb is one item with its own stops left, so the menu
        // shows the weapon count and the status line reads the stops.
        BattleMenuItem.ANTI_BOMB -> inventory.antiBombs
        BattleMenuItem.LOCK -> inventory.lockCharges
        else -> 0
    }
    val menuLabel = when (menuItem) {
        BattleMenuItem.MEDKIT -> viewModel.t("medkit")
        BattleMenuItem.COVER -> viewModel.t("cover")
        BattleMenuItem.GLUE_WALL -> viewModel.t("glooWall")
        BattleMenuItem.ANTI_MISSILE -> viewModel.t("antiMissile")
        BattleMenuItem.CAR -> viewModel.t("car")
        BattleMenuItem.ANTI_BOMB -> viewModel.t("antiBomb")
        BattleMenuItem.LOCK -> viewModel.t("lockOpponent")
        BattleMenuItem.EXIT -> viewModel.t("exitGame")
        BattleMenuItem.RECOVER -> viewModel.t("recoverWeapon")
    }
    // The anti-bomb read-out: a player who carries an explosive (or plays a
    // round where it is free) also hears how many bomb stops it still has; a
    // player who carries none gets the plain count and nothing to decode.
    val antiBombLine = when {
        inventory.antiBombs <= 0 && !gameState.playerAntiBombFree ->
            "${viewModel.t("antiBomb")}: ${inventory.antiBombs}"
        else ->
            "${viewModel.t("antiBomb")}: ${inventory.antiBombs} " +
                "(${viewModel.t("antiBombStopsLeft")} " +
                "${gameState.playerAntiBombCapacity})"
    }
    val ammoStatus = when {
        weapon.missileLauncher ->
            "${viewModel.t("extMissilePowers")}: ${inventory.missilePowers}"
        // The gift grenade carries its own two charges in round two: the
        // countdown of that stock is announced instead of a silent magazine.
        weapon.bomb && BombRules.grenadeGiftForRound(gameState.round) ->
            "${viewModel.t("grenadeChargesLeft")}: ${viewModel.grenadeChargesLeft()}"
        weapon.bomb -> "${viewModel.t("bombCharges")}: ${gameState.activeAmmo}"
        weapon.needsReload -> "${viewModel.t("ammo")}: ${gameState.activeAmmo}"
        else -> "${viewModel.t("ammo")}: ${viewModel.t("noAmmoNeeded")}"
    }
    // The bomb destination is shown next to the enemy health while a bomb
    // counts down, together with the distance both fighters still have to it.
    val bombStatus = if (gameState.bombInFlight) {
        "${viewModel.t("bombDestination")}: " +
            "${viewModel.t("devil")} ${gameState.robertDistanceFromBomb.roundToInt()} " +
            "${viewModel.t("meters")}, ${viewModel.t("you")} " +
            "${gameState.playerDistanceFromBomb.roundToInt()} ${viewModel.t("meters")}"
    } else {
        null
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (paused) {
            Text(
                text = viewModel.t("battlePaused"),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF563800))
                    .padding(8.dp).testTag("battle_paused")
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        // --------------------------------------------------------------
        // Top third: long press to run toward Robert.
        // --------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF10251A))
                .testTag("move_forward_zone")
                .semantics {
                    contentDescription = "${viewModel.t("moveForward")}. ${viewModel.t("forwardHint")}"
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "▲ ${viewModel.t("moveForward")}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF81C784)
                )
                Text(
                    text = viewModel.t("forwardHint"),
                    fontSize = 13.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "${viewModel.t("devil")} ${viewModel.t("hp")}: $enemyHp/" +
                        "${gameState.robertMaxHp}  |  ${viewModel.t("round")} ${gameState.round}, " +
                        "${viewModel.t("level")} ${gameState.level}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(5.dp))
                HpBar(current = enemyHp, max = gameState.robertMaxHp, isDevil = true)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${viewModel.t("distance")}: ${gameState.distance.roundToInt()} " +
                        "${viewModel.t("meters")} | $zoneText",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = zoneColor,
                    textAlign = TextAlign.Center
                )
                if (bombStatus != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bombStatus,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarnBright,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${viewModel.t("enemyPosition")}: ${gameState.robertX.roundToInt()}, " +
                        "${gameState.robertY.roundToInt()} ${viewModel.t("meters")}",
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
                // From round three Robert carries bombs: the HUD always shows
                // exactly how many grenades, atom bombs, timer bombs and
                // anti-bomb explosives he still owns (unlimited reads as ∞).
                if (gameState.round >= Weapon.ATOM_BOMB.unlockRound) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${viewModel.t("gunGrenade")} ${enemyCount(gameState.robertGrenades)} | " +
                            "${viewModel.t("gunAtomBomb")} ${enemyCount(gameState.robertAtomBombs)} | " +
                            "${viewModel.t("gunTimerBomb")} ${enemyCount(gameState.robertTimerBombs)} | " +
                            "${viewModel.t("antiBomb")} ${enemyCount(gameState.robertAntiBombs)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarnBright,
                        textAlign = TextAlign.Center
                    )
                }
                // The final round has a seven minute limit, so the remaining
                // time is part of the enemy status as well.
                if (gameState.timeLimitActive) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${viewModel.t("timeRemaining")}: " +
                            formatRemaining(gameState.battleRemainingMs),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = WarnBright,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // --------------------------------------------------------------
        // Center third: status and quick-menu activation by a single tap.
        // --------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF0A0E1A))
                .testTag("menu_activate_gesture_zone")
                .semantics { contentDescription = viewModel.t("centerHint") }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "${viewModel.t("you")} ${viewModel.t("hp")}: $playerHp/" +
                        "${gameState.playerMaxHp} | $ammoStatus | ${viewModel.t("coins")}: ${stats.coins}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                HpBar(current = playerHp, max = gameState.playerMaxHp, isDevil = false)
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = viewModel.t(weapon.nameKey) +
                        if (gameState.activeWeaponDamaged) " (${viewModel.t("damaged")})" else "",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (gameState.activeWeaponDamaged) WarnBright else TextAccent
                )
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = "${viewModel.t("menuNow")}: " +
                        if (menuItem.defence != null) "$menuLabel ($menuCount)" else menuLabel,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = WarnBright,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = viewModel.t("centerHint"),
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${viewModel.t("runSpeed")}: " +
                        viewModel.t(gameState.runSpeedLevel.nameKey) +
                        "   |   ${viewModel.t("yourPosition")}: " +
                        "${gameState.playerX.roundToInt()}, " +
                        "${gameState.playerY.roundToInt()} ${viewModel.t("meters")}",
                    fontSize = 12.sp,
                    color = TextAccent,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = viewModel.t("centerMoveHint"),
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
                if (gameState.timerSelectActive) {
                    Spacer(modifier = Modifier.height(5.dp))
                    AlertBox(
                        text = "${viewModel.t("selectTimer")}: ${gameState.timerSeconds} " +
                            viewModel.t("seconds") + ". " + viewModel.t("timerHowTo")
                    )
                }
                if (gameState.missileInFlight) {
                    Spacer(modifier = Modifier.height(5.dp))
                    AlertBox(
                        text = if (gameState.missileFromPlayer) {
                            viewModel.t("missileLaunched")
                        } else {
                            viewModel.t("robertMissileLaunched")
                        }
                    )
                }
                if (weapon.missileLauncher && inventory.missilePowers <= 0) {
                    Spacer(modifier = Modifier.height(5.dp))
                    AlertBox(text = viewModel.t("missileNoPower"))
                }
                if (gameState.pendingPurchaseId != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    AlertBox(
                        text = "${viewModel.t("itemMissingPrompt")} $menuLabel. " +
                            viewModel.t("itemMissingTail")
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "◀ ${viewModel.t("moveLeft")}   |   ${viewModel.t("moveRight")} ▶",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF9FD4FF),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(7.dp))
                LogBox(text = gameState.log)
                Spacer(modifier = Modifier.height(5.dp))
                RadarBar(distance = gameState.distance, extraDesc = zoneText)
            }
        }

        // --------------------------------------------------------------
        // Bottom third: long press to run away from Robert.
        // --------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF101D30))
                .testTag("move_backward_zone")
                .semantics {
                    contentDescription = "${viewModel.t("moveBackward")}. ${viewModel.t("backwardHint")}"
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${viewModel.t("medkits")}: ${inventory.medikits} | " +
                        "${viewModel.t("glooWall")}: ${inventory.glueWalls} | " +
                        "${viewModel.t("cover")}: ${inventory.covers} | " +
                        "${viewModel.t("antiMissile")}: ${inventory.antiMissiles} | " +
                        antiBombLine +
                        "${viewModel.t("extMissilePowers")}: ${inventory.missilePowers}",
                    fontSize = 14.sp,
                    color = TextAccent,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = BattleMenuItem.entries.joinToString("  ") { item ->
                        when (item) {
                            BattleMenuItem.MEDKIT -> viewModel.t("medkit")
                            BattleMenuItem.COVER -> viewModel.t("cover")
                            BattleMenuItem.GLUE_WALL -> viewModel.t("glooWall")
                            BattleMenuItem.ANTI_MISSILE -> viewModel.t("antiMissile")
                            BattleMenuItem.CAR -> viewModel.t("car")
                            BattleMenuItem.ANTI_BOMB -> viewModel.t("antiBomb")
                            BattleMenuItem.LOCK -> viewModel.t("lockOpponent")
                            BattleMenuItem.EXIT -> viewModel.t("exitGame")
                            BattleMenuItem.RECOVER -> viewModel.t("recoverWeapon")
                        }
                    },
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )
                val activeExtensions = Extension.entries.filter {
                    inventory.extensionEnabled(it)
                }
                if (activeExtensions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = "${viewModel.t("upgrades")}: " +
                            activeExtensions.joinToString(", ") { viewModel.t(it.nameKey) },
                        fontSize = 12.sp,
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = viewModel.t("menuHint"),
                    fontSize = 12.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "▼ ${viewModel.t("moveBackward")}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64B5F6)
                )
                Text(
                    text = viewModel.t("backwardHint"),
                    fontSize = 13.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** An enemy bomb count, where the unlimited round five stock reads as ∞. */
private fun enemyCount(count: Int): String = if (count >= UNLIMITED_COUNT) "\u221E" else "$count"

/** Remaining milliseconds of the final round as mm:ss. */
private fun formatRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/** Bomb and ammunition stocks at or above this count are unlimited. */
private const val UNLIMITED_COUNT = 90
