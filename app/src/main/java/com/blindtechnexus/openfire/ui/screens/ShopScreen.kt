package com.blindtechnexus.openfire.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindtechnexus.openfire.model.BombRules
import com.blindtechnexus.openfire.model.DefenceItem
import com.blindtechnexus.openfire.model.Extension
import com.blindtechnexus.openfire.model.Inventory
import com.blindtechnexus.openfire.model.Loans
import com.blindtechnexus.openfire.model.MissileRules
import com.blindtechnexus.openfire.model.Progression
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.model.Shop
import com.blindtechnexus.openfire.model.Weapon
import com.blindtechnexus.openfire.ui.components.ButtonType
import com.blindtechnexus.openfire.ui.components.GameButton
import com.blindtechnexus.openfire.ui.components.GameCard
import com.blindtechnexus.openfire.ui.components.StatBadge
import com.blindtechnexus.openfire.ui.theme.CardBg
import com.blindtechnexus.openfire.ui.theme.TextAccent
import com.blindtechnexus.openfire.ui.theme.TextMuted
import com.blindtechnexus.openfire.ui.theme.WarnBright
import com.blindtechnexus.openfire.viewmodel.GameViewModel

/**
 * The shop: buy or remove weapons, buy defences (repeatable) and manage the
 * automatic extensions. Every purchase plays the coin sound, and every failed
 * purchase shows and speaks "Not enough coins."
 */
@Composable
fun ShopScreen(viewModel: GameViewModel) {
    val stats by viewModel.stats.collectAsState()
    val inventory by viewModel.inventory.collectAsState()
    val scrollState = rememberScrollState()

    // Pending confirmation for a removal.
    var pendingWeapon by remember { mutableStateOf<Weapon?>(null) }
    var pendingDefence by remember { mutableStateOf<DefenceItem?>(null) }
    var pendingExtension by remember { mutableStateOf<Extension?>(null) }
    var pendingMissilePowerRemove by remember { mutableStateOf(false) }
    var pendingBombPowerRemove by remember { mutableStateOf(false) }
    // Every removal dialog puts the keyboard / switch focus on its Cancel
    // button, so confirming a removal is always a deliberate second step.
    val cancelFocus = remember { FocusRequester() }
    // The dialog only attaches the requester while it is composed, so the
    // request is made once that composition is live.
    val dialogOpen = pendingWeapon != null || pendingDefence != null ||
        pendingExtension != null || pendingMissilePowerRemove || pendingBombPowerRemove
    if (dialogOpen) {
        LaunchedEffect(cancelFocus) {
            // A frame for the dialog to attach its focus modifier.
            kotlinx.coroutines.delay(60L)
            try {
                cancelFocus.requestFocus()
            } catch (_: Throwable) {
                // No focus system on this device (or a dialog closed already):
                // the browse order alone is still correct.
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        GameButton(
            text = viewModel.t("back"),
            onClick = { viewModel.navigateTo(Screen.MENU) },
            type = ButtonType.DANGER,
            testTag = "shop_back_button"
        )

        Spacer(modifier = Modifier.height(14.dp))

        GameCard {
            Text(
                text = viewModel.t("shopTitle"),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = viewModel.t("shopSubtitle"),
                fontSize = 16.sp,
                color = Color.White,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                StatBadge(
                    label = viewModel.t("coins"),
                    value = stats.coins.toString()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------------
        // Loans are an accessible heading, with status and all three choices.
        GameCard {
            Text(
                text = viewModel.t("loans"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }.testTag("shop_loans_heading")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (stats.loanBalance > 0) {
                    "${viewModel.t("loanActive")} ${viewModel.t("loanRemaining")}: ${stats.loanBalance} ${viewModel.t("coins")}."
                } else viewModel.t("loanNone"),
                color = Color.White,
                modifier = Modifier.testTag("shop_loan_status")
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = viewModel.t("loanRules"), color = TextMuted, lineHeight = 20.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Loans.AMOUNTS.forEach { amount ->
                GameButton(
                    text = "${viewModel.t("takeLoan")} $amount ${viewModel.t("coins")}",
                    onClick = { viewModel.takeLoan(amount) },
                    enabled = stats.loanBalance == 0 && stats.coins < Loans.ELIGIBILITY_LIMIT,
                    testTag = "shop_loan_$amount"
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        // Weapons
        // ------------------------------------------------------------------
        GameCard {
            Text(
                text = viewModel.t("weapons"),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = viewModel.t("flatDamageInfo"),
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 18.sp,
                modifier = Modifier.testTag("shop_flat_damage_info")
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Every listed gun keeps its own keyed slot, so a row is never
            // rebuilt (and its TalkBack focus never dropped) when a neighbour changes.
            Weapon.shopListed().forEach { weapon -> key(weapon.id) {
                val owned = inventory.ownsWeapon(weapon)
                val weaponLocked = Shop.isLocked(
                    weapon.requiredCompletedRounds,
                    stats.completedRounds
                )
                // A separate, dynamic sentence below the description: it
                // disappears as soon as the required round is completed.
                val unlockMessage = if (weaponLocked) {
                    viewModel.t(Shop.unlockReasonKey(weapon.requiredCompletedRounds))
                } else {
                    null
                }
                val detail = if (weapon.missileLauncher) {
                    if (!weaponLocked) {
                        "${viewModel.t("missileInfo")} ${viewModel.t("missileRequiresPowers")}"
                    } else {
                        viewModel.t("missileLocked")
                    }
                } else if (weapon.bomb) {
                    // Show the capped flat damage for each tracking bomb.
                    "${viewModel.t("explosiveLethal")} ${weapon.powerPercent} ${viewModel.t("hp")}. " +
                        "${viewModel.t("ammo")}: ${weapon.magazine}."
                } else {
                    // Damage is flat health points, never a percentage of the
                    // enemy's maximum health.
                    "${viewModel.t("weaponPower")}: ${weapon.powerPercent} ${viewModel.t("hp")}. " +
                        "${viewModel.t("closeRangePower")}: " +
                        "${weapon.closeRangePowerPercent} ${viewModel.t("hp")}. " +
                        "${viewModel.t("ammo")}: ${weapon.magazine}."
                }
                val weaponOn = inventory.weaponEnabled(weapon)
                ShopItemRow(
                    title = "${viewModel.t(weapon.nameKey)}, " +
                        (if (weapon.price > 0) "${weapon.price} ${viewModel.t("coins")}" else viewModel.t("freeItem")) +
                        ".",
                    detail = detail + (if (weapon.ownedByDefault) " ${viewModel.t("shopBuiltInGunNote")}" else "") + if (owned) {
                        " ${viewModel.t("weaponState")}: " +
                            if (weaponOn) {
                                viewModel.t("enabledState")
                            } else {
                                viewModel.t("disabledState")
                            } + "."
                    } else {
                        ""
                    },
                    unlockMessage = unlockMessage,
                    owned = owned,
                    buyLabel = viewModel.t("buy"),
                    removeLabel = viewModel.t("remove"),
                    // The two free guns can be disabled but never removed or bought.
                    canRemove = !weapon.ownedByDefault,
                    onBuy = { viewModel.buyWeapon(weapon.id) },
                    onRemove = { pendingWeapon = weapon },
                    testTagPrefix = "shop_weapon_${weapon.id}",
                    // The Enable / Disable switch below buy and remove: a
                    // disabled weapon is skipped by the game screen swipes.
                    toggleLabel = if (weaponOn) {
                        viewModel.t("disableWeapon")
                    } else {
                        viewModel.t("enableWeapon")
                    },
                    onToggle = { viewModel.toggleWeaponEnabled(weapon.id) }
                )
                Spacer(modifier = Modifier.height(10.dp))
            } }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------------
        // Missile powers: stackable consumables, exactly like defences.
        // ------------------------------------------------------------------
        GameCard {
            Text(
                text = viewModel.t("extMissilePowers"),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            MissilePowerRow(
                viewModel = viewModel,
                inventory = inventory,
                unlocked = Progression.missileUnlocked(stats),
                unlockMessage = if (!Progression.missileUnlocked(stats)) {
                    viewModel.t(
                        Shop.unlockReasonKey(Progression.MISSILE_UNLOCK_COMPLETED_ROUNDS)
                    )
                } else {
                    null
                },
                onRemove = { pendingMissilePowerRemove = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------------
        // Bomb powers: stackable consumables for atom/timer bombs — 850 coins each.
        // 1 Bomb Power = 1 use of any bomb type, tracking like missiles.
        // ------------------------------------------------------------------
        GameCard {
            Text(
                text = viewModel.t("extBombPowers"),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))
            BombPowerRow(
                viewModel = viewModel,
                inventory = inventory,
                unlocked = stats.completedRounds >= Weapon.ATOM_BOMB.requiredCompletedRounds,
                unlockMessage = if (stats.completedRounds < Weapon.ATOM_BOMB.requiredCompletedRounds) {
                    viewModel.t(
                        Shop.unlockReasonKey(Weapon.ATOM_BOMB.requiredCompletedRounds)
                    )
                } else {
                    null
                },
                onRemove = { pendingBombPowerRemove = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------------
        // Defences
        // ------------------------------------------------------------------
        GameCard {
            Text(
                text = viewModel.t("defences"),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = defenceTotalsText(viewModel, inventory),
                fontSize = 16.sp,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = viewModel.t("defencesNote"),
                fontSize = 14.sp,
                color = TextMuted,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            DefenceItem.entries.sortedBy { it.price }.forEach { item -> key(item.id) {
                val unlocked = stats.completedRounds >= item.requiredCompletedRounds
                DefenceRow(
                    viewModel = viewModel,
                    item = item,
                    inventory = inventory,
                    unlocked = unlocked,
                    unlockMessage = if (!unlocked) {
                        viewModel.t(Shop.unlockReasonKey(item.requiredCompletedRounds))
                    } else {
                        null
                    },
                    onRemove = { pendingDefence = item }
                )
                Spacer(modifier = Modifier.height(10.dp))
            } }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ------------------------------------------------------------------
        // Extensions
        // ------------------------------------------------------------------
        GameCard {
            Text(
                text = viewModel.t("extensions"),
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))

            Extension.entries.sortedBy { it.price }.forEach { extension ->
                val state = inventory.extensionState(extension)
                val owned = state != com.blindtechnexus.openfire.model.ExtensionState.NOT_OWNED
                val enabled = state == com.blindtechnexus.openfire.model.ExtensionState.ENABLED

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${viewModel.t(extension.nameKey)}, ${extension.price} " +
                            "${viewModel.t("coins")}.",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = viewModel.t(extension.descriptionKey),
                        fontSize = 14.sp,
                        color = TextMuted,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (owned) {
                            if (enabled) {
                                viewModel.t("enabledState")
                            } else {
                                viewModel.t("disabledState")
                            }
                        } else {
                            viewModel.t("notOwnedLabel")
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (enabled) WarnBright else TextAccent
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (owned) {
                        GameButton(
                            text = viewModel.t("remove"),
                            onClick = { pendingExtension = extension },
                            type = ButtonType.DANGER,
                            testTag = "shop_extension_${extension.id}_remove"
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // Disabled extensions can be enabled again and vice versa.
                        GameButton(
                            text = if (enabled) viewModel.t("disable") else viewModel.t("enable"),
                            onClick = { viewModel.toggleExtension(extension.id) },
                            type = ButtonType.WARN,
                            testTag = "shop_extension_${extension.id}_toggle",
                            enabled = true
                        )
                    } else {
                        GameButton(
                            text = viewModel.t("buy"),
                            onClick = { viewModel.buyExtension(extension.id) },
                            type = ButtonType.PRIMARY,
                            testTag = "shop_extension_${extension.id}_buy"
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        GameButton(
            text = viewModel.t("back"),
            onClick = { viewModel.navigateTo(Screen.MENU) },
            type = ButtonType.PRIMARY,
            testTag = "shop_back_bottom_button"
        )
    }

    // Confirmation before removing a bought weapon.
    pendingWeapon?.let { weapon ->
        AlertDialog(
            onDismissRequest = { pendingWeapon = null },
            title = {
                Text(
                    text = viewModel.t("removeConfirmTitle"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "${viewModel.t(weapon.nameKey)}. ${viewModel.t("removeConfirmMessage")}",
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingWeapon = null
                        viewModel.removeWeapon(weapon.id)
                    },
                    modifier = Modifier.testTag("shop_weapon_confirm_remove")
                ) {
                    Text(
                        text = viewModel.t("confirmRemove"),
                        color = WarnBright,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingWeapon = null },
                    modifier = Modifier.focusRequester(cancelFocus)
                ) {
                    Text(text = viewModel.t("cancel"), color = TextAccent)
                }
            },
            containerColor = CardBg,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Confirmation before removing a defence item.
    pendingDefence?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDefence = null },
            title = {
                Text(
                    text = viewModel.t("removeConfirmTitle"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "${viewModel.t(item.nameKey)}. ${viewModel.t("removeConfirmMessage")}",
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDefence = null
                        viewModel.removeDefence(item.id)
                    },
                    modifier = Modifier.testTag("shop_defence_confirm_remove")
                ) {
                    Text(
                        text = viewModel.t("confirmRemove"),
                        color = WarnBright,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            // The focus lands on Cancel, never on the destructive confirm:
            // a stray switch press can therefore never remove an item.
            dismissButton = {
                TextButton(
                    onClick = { pendingDefence = null },
                    modifier = Modifier.focusRequester(cancelFocus)
                ) {
                    Text(text = viewModel.t("cancel"), color = TextAccent)
                }
            },
            containerColor = CardBg,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Confirmation before removing one stacked missile power.
    if (pendingMissilePowerRemove) {
        AlertDialog(
            onDismissRequest = { pendingMissilePowerRemove = false },
            title = {
                Text(
                    text = viewModel.t("removeConfirmTitle"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "${viewModel.t("extMissilePowers")}. ${viewModel.t("removeConfirmMessage")}",
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingMissilePowerRemove = false
                        viewModel.removeMissilePower()
                    },
                    modifier = Modifier.testTag("shop_missile_power_confirm_remove")
                ) {
                    Text(
                        text = viewModel.t("confirmRemove"),
                        color = WarnBright,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingMissilePowerRemove = false },
                    modifier = Modifier.focusRequester(cancelFocus)
                ) {
                    Text(text = viewModel.t("cancel"), color = TextAccent)
                }
            },
            containerColor = CardBg,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Confirmation before removing one stacked bomb power.
    if (pendingBombPowerRemove) {
        AlertDialog(
            onDismissRequest = { pendingBombPowerRemove = false },
            title = {
                Text(
                    text = viewModel.t("removeConfirmTitle"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "${viewModel.t("extBombPowers")}. ${viewModel.t("removeConfirmMessage")}",
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingBombPowerRemove = false
                        viewModel.removeBombPower()
                    },
                    modifier = Modifier.testTag("shop_bomb_power_confirm_remove")
                ) {
                    Text(
                        text = viewModel.t("confirmRemove"),
                        color = WarnBright,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingBombPowerRemove = false },
                    modifier = Modifier.focusRequester(cancelFocus)
                ) {
                    Text(text = viewModel.t("cancel"), color = TextAccent)
                }
            },
            containerColor = CardBg,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    // Confirmation before removing an extension.
    pendingExtension?.let { extension ->
        AlertDialog(
            onDismissRequest = { pendingExtension = null },
            title = {
                Text(
                    text = viewModel.t("removeConfirmTitle"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "${viewModel.t(extension.nameKey)}. ${viewModel.t("removeConfirmMessage")}",
                    fontSize = 15.sp,
                    lineHeight = 21.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingExtension = null
                        viewModel.removeExtension(extension.id)
                    },
                    modifier = Modifier.testTag("shop_extension_confirm_remove")
                ) {
                    Text(
                        text = viewModel.t("confirmRemove"),
                        color = WarnBright,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingExtension = null },
                    modifier = Modifier.focusRequester(cancelFocus)
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

/** "You've total 5 medikits, 10 glue walls, 2 covers, 1 anti-missile systems." */
private fun defenceTotalsText(viewModel: GameViewModel, inventory: Inventory): String {
    val medkits = "${inventory.medikits} ${viewModel.t("medkitsPlural")}"
    val glueWalls = "${inventory.glueWalls} ${viewModel.t("glueWallsPlural")}"
    val covers = "${inventory.covers} ${viewModel.t("coversPlural")}"
    val antiMissiles = "${inventory.antiMissiles} ${viewModel.t("antiMissilesPlural")}"
    return "${viewModel.t("youHaveTotal")} $medkits, $glueWalls, $covers, $antiMissiles."
}

@Composable
private fun DefenceRow(
    viewModel: GameViewModel,
    item: DefenceItem,
    inventory: Inventory,
    unlocked: Boolean,
    unlockMessage: String?,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${viewModel.t(item.nameKey)}, ${item.price} ${viewModel.t("coins")}.",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (item == DefenceItem.ANTI_MISSILE) WarnBright else Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = viewModel.t(item.descriptionKey),
            fontSize = 14.sp,
            color = TextMuted,
            lineHeight = 20.sp
        )
        if (unlockMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = unlockMessage,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                lineHeight = 18.sp,
                modifier = Modifier.testTag("shop_defence_${item.id}_unlock_msg")
            )
        }
        if (!unlocked) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = viewModel.t(item.lockedKey),
                fontSize = 13.sp,
                color = TextMuted,
                lineHeight = 18.sp
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${viewModel.t("owned")}: ${inventory.count(item)}",
            fontSize = 14.sp,
            color = WarnBright,
            fontWeight = FontWeight.Bold
        )
        // The anti-bomb explosive is the one defence that wears out. A charged
        // one announces the stops it has left and says why a second one cannot
        // be bought yet; an exhausted one says the shop is open again.
        if (item == DefenceItem.ANTI_BOMB) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (inventory.holdsUsedAntiBomb) {
                    "${viewModel.t("antiBombStopsLeft")}: " +
                        "${inventory.antiBombCapacityLeft}. " +
                        viewModel.t("antiBombCharged")
                } else {
                    if (inventory.count(item) > 0) {
                        viewModel.t("antiBombExhausted")
                    } else {
                        viewModel.t("antiBombChargedEmpty")
                    }
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (inventory.holdsUsedAntiBomb) TextMuted else WarnBright,
                lineHeight = 18.sp,
                modifier = Modifier.testTag("shop_defence_${item.id}_state")
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameButton(
                text = viewModel.t("buy"),
                onClick = { viewModel.buyDefence(item.id) },
                type = ButtonType.PRIMARY,
                modifier = Modifier.weight(1f),
                testTag = "shop_defence_${item.id}_buy",
                // A charged explosive blocks the purchase of a second one.
                enabled = unlocked && !(item == DefenceItem.ANTI_BOMB && inventory.holdsUsedAntiBomb)
            )
            GameButton(
                text = viewModel.t("remove"),
                onClick = onRemove,
                type = ButtonType.DANGER,
                modifier = Modifier.weight(1f),
                testTag = "shop_defence_${item.id}_remove",
                enabled = inventory.count(item) > 0
            )
        }
    }
}

@Composable
private fun MissilePowerRow(
    viewModel: GameViewModel,
    inventory: Inventory,
    unlocked: Boolean,
    unlockMessage: String?,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${viewModel.t("extMissilePowers")}, ${MissileRules.MISSILE_POWER_PRICE} " +
                "${viewModel.t("coins")}.",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = viewModel.t("extMissilePowersInfo"),
            fontSize = 14.sp,
            color = TextMuted,
            lineHeight = 20.sp
        )
        if (unlockMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = unlockMessage,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                lineHeight = 18.sp,
                modifier = Modifier.testTag("shop_missile_power_unlock_msg")
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${viewModel.t("owned")}: ${inventory.missilePowers}",
            fontSize = 14.sp,
            color = WarnBright,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Keep the locked row clickable so its unlock condition is spoken.
            // Buying is repeatable: powers stack like defences.
            GameButton(
                text = viewModel.t("buy"),
                onClick = { viewModel.buyMissilePower() },
                type = ButtonType.PRIMARY,
                modifier = Modifier.weight(1f),
                testTag = "shop_missile_power_buy"
            )
            GameButton(
                text = viewModel.t("remove"),
                onClick = onRemove,
                type = ButtonType.DANGER,
                modifier = Modifier.weight(1f),
                testTag = "shop_missile_power_remove",
                enabled = inventory.missilePowers > 0
            )
        }
    }
}

@Composable
private fun BombPowerRow(
    viewModel: GameViewModel,
    inventory: Inventory,
    unlocked: Boolean,
    unlockMessage: String?,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${viewModel.t("extBombPowers")}, ${BombRules.BOMB_POWER_PRICE} " +
                "${viewModel.t("coins")}.",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = viewModel.t("extBombPowersInfo"),
            fontSize = 14.sp,
            color = TextMuted,
            lineHeight = 20.sp
        )
        if (unlockMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = unlockMessage,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                lineHeight = 18.sp,
                modifier = Modifier.testTag("shop_bomb_power_unlock_msg")
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${viewModel.t("owned")}: ${inventory.bombPowers}",
            fontSize = 14.sp,
            color = WarnBright,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GameButton(
                text = viewModel.t("buy"),
                onClick = { viewModel.buyBombPower() },
                type = ButtonType.PRIMARY,
                modifier = Modifier.weight(1f),
                testTag = "shop_bomb_power_buy"
            )
            GameButton(
                text = viewModel.t("remove"),
                onClick = onRemove,
                type = ButtonType.DANGER,
                modifier = Modifier.weight(1f),
                testTag = "shop_bomb_power_remove",
                enabled = inventory.bombPowers > 0
            )
        }
    }
}

@Composable
private fun ShopItemRow(
    title: String,
    detail: String,
    unlockMessage: String?,
    owned: Boolean,
    buyLabel: String,
    removeLabel: String,
    onBuy: () -> Unit,
    onRemove: () -> Unit,
    testTagPrefix: String,
    /** Optional Enable / Disable switch shown under an owned item. */
    toggleLabel: String? = null,
    onToggle: (() -> Unit)? = null,
    /** False for a gun the shop never removes (it can only be disabled). */
    canRemove: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = detail,
            fontSize = 14.sp,
            color = TextMuted,
            lineHeight = 20.sp
        )
        // Round-gated items get their own text view. It is driven by the live
        // profile, so the sentence disappears forever once the item unlocks.
        if (unlockMessage != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = unlockMessage,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = WarnBright,
                lineHeight = 18.sp,
                modifier = Modifier.testTag("${testTagPrefix}_unlock_msg")
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (owned) {
            if (canRemove) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    GameButton(
                        text = removeLabel,
                        onClick = onRemove,
                        type = ButtonType.DANGER,
                        testTag = "${testTagPrefix}_remove"
                    )
                }
            }
            if (toggleLabel != null && onToggle != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    GameButton(
                        text = toggleLabel,
                        onClick = onToggle,
                        type = ButtonType.WARN,
                        testTag = "${testTagPrefix}_toggle"
                    )
                }
            }
        } else {
            GameButton(
                text = buyLabel,
                onClick = onBuy,
                type = ButtonType.PRIMARY,
                testTag = "${testTagPrefix}_buy"
            )
        }
    }
}

