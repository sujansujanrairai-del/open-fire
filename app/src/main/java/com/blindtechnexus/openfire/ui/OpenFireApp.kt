package com.blindtechnexus.openfire.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.blindtechnexus.openfire.model.MoveDirection
import com.blindtechnexus.openfire.model.RunPressure
import com.blindtechnexus.openfire.model.Screen
import com.blindtechnexus.openfire.ui.screens.AboutScreen
import com.blindtechnexus.openfire.ui.screens.BattleScreen
import com.blindtechnexus.openfire.ui.screens.ChangelogScreen
import com.blindtechnexus.openfire.ui.screens.GuideScreen
import com.blindtechnexus.openfire.ui.screens.JumpScreen
import com.blindtechnexus.openfire.ui.screens.LandScreen
import com.blindtechnexus.openfire.ui.screens.MenuScreen
import com.blindtechnexus.openfire.ui.screens.ParaScreen
import com.blindtechnexus.openfire.ui.screens.ProfileScreen
import com.blindtechnexus.openfire.ui.screens.ReaderScreen
import com.blindtechnexus.openfire.ui.screens.ResultScreen
import com.blindtechnexus.openfire.ui.screens.SettingsScreen
import com.blindtechnexus.openfire.ui.screens.ShopScreen
import com.blindtechnexus.openfire.ui.screens.WelcomeScreen
import com.blindtechnexus.openfire.ui.theme.DarkBg
import com.blindtechnexus.openfire.viewmodel.GameViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** A movement smaller than this is treated as a tap instead of a swipe. */
private const val TAP_SLOP = 24f
private const val SWIPE_THRESHOLD_ONE_FINGER = 60f
private const val SWIPE_THRESHOLD_TWO_FINGERS = 36f

@Composable
fun OpenFireApp(viewModel: GameViewModel) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val view = LocalView.current

    DisposableEffect(view, settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    BackHandler(enabled = currentScreen != Screen.WELCOME && currentScreen != Screen.MENU) {
        if (currentScreen == Screen.BATTLE) {
            viewModel.exitBattle()
        } else {
            viewModel.navigateTo(Screen.MENU)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkBg)
                .pointerInput(currentScreen) {
                    coroutineScope {
                        awaitEachGesture {
                            val firstDown = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )

                            val screenHeight = size.height.toFloat().takeIf { it > 0f } ?: 1000f
                            val screenWidth = size.width.toFloat().takeIf { it > 0f } ?: 1000f
                            val isBattle = currentScreen == Screen.BATTLE
                            val isTop = isBattle && firstDown.position.y < screenHeight / 3f
                            val isBottom = isBattle && firstDown.position.y >= screenHeight * 2f / 3f
                            val isCenter = isBattle && !isTop && !isBottom
                            // The center portion is split in two halves: the
                            // left half runs left, the right half runs right,
                            // so all four directions of the 25 m square zone are
                            // reachable. A tap in the center still activates
                            // the quick menu (or confirms a timer bomb).
                            val isCenterLeft = isCenter && firstDown.position.x < screenWidth / 2f
                            val isCenterRight = isCenter && !isCenterLeft
                            val moveDirection: MoveDirection? = when {
                                isTop -> MoveDirection.FORWARD
                                isBottom -> MoveDirection.BACKWARD
                                isCenterLeft -> MoveDirection.LEFT
                                isCenterRight -> MoveDirection.RIGHT
                                else -> null
                            }
                            val isRunZone = moveDirection != null

                            // Every finger is tracked from its own touch-down point,
                            // so a two-finger swipe never reports a bogus direction.
                            val startPositions = mutableMapOf(firstDown.id to firstDown.position)
                            var maxFingers = 1
                            var bestDx = 0f
                            var bestDy = 0f
                            var maxDelta = 0f
                            var longPressActive = false
                            var longPressCancelled = false
                            // The delayed hold task must not outlive a quick tap/release.
                            var touchHeld = true
                            // A held up-swipe with a submachine gun keeps
                            // firing until the finger is lifted again.
                            var holdFireStarted = false

                            // Sample the touch pressure from the very first
                            // sample so the run can start at the pressed
                            // intensity: stronger press, faster run.
                            if (isRunZone) {
                                viewModel.updateMovePressure(firstDown.pressure)
                            }

                            // The hold is recognised quickly. Movement still
                            // never starts on touch-down, and a swipe cancels
                            // the pending hold.
                            val longPressJob = if (isRunZone) {
                                launch {
                                    delay(RunPressure.LONG_PRESS_TIMEOUT_MS)
                                    if (touchHeld && !longPressCancelled && maxFingers == 1 && maxDelta < TAP_SLOP) {
                                        longPressActive = true
                                        moveDirection?.let { viewModel.startPlayerMove(it) }
                                    }
                                }
                            } else {
                                null
                            }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                                    val pressedNow = event.changes.count { it.pressed }
                                    if (pressedNow > maxFingers) {
                                        maxFingers = pressedNow
                                        if (maxFingers > 1) {
                                            longPressCancelled = true
                                            longPressJob?.cancel()
                                            if (longPressActive) {
                                                viewModel.stopPlayerMove()
                                                longPressActive = false
                                            }
                                        }
                                    }

                                    for (change in event.changes) {
                                        val start = startPositions.getOrPut(change.id) { change.position }
                                        val dx = change.position.x - start.x
                                        val dy = change.position.y - start.y
                                        val distance = sqrt(dx * dx + dy * dy)
                                        if (distance > maxDelta) {
                                            maxDelta = distance
                                            bestDx = dx
                                            bestDy = dy
                                        }
                                        // Live pressure of the primary finger
                                        // keeps driving the running speed.
                                        if (isRunZone &&
                                            change.id == firstDown.id &&
                                            change.pressed
                                        ) {
                                            viewModel.updateMovePressure(change.pressure)
                                        }
                                    }

                                    // Holding an up-swipe with the MP40 or the
                                    // machine gun selected starts the
                                    // continuous burst right away: one bullet
                                    // per interval with the gun sound on loop.
                                    if (isBattle && !holdFireStarted && !longPressActive &&
                                        maxFingers == 1 &&
                                        bestDy < 0f && abs(bestDy) > abs(bestDx) &&
                                        abs(bestDy) >= SWIPE_THRESHOLD_ONE_FINGER &&
                                        viewModel.activeWeaponIsAutomatic()
                                    ) {
                                        holdFireStarted = true
                                        viewModel.startHoldFire()
                                    }

                                    if (maxDelta >= TAP_SLOP && !longPressActive) {
                                        longPressCancelled = true
                                        longPressJob?.cancel()
                                    }
                                    if (longPressActive && maxDelta >= SWIPE_THRESHOLD_ONE_FINGER) {
                                        viewModel.stopPlayerMove()
                                        longPressActive = false
                                    }

                                    if (event.changes.none { it.pressed }) {
                                        touchHeld = false
                                        break
                                    }
                                }
                            } finally {
                                touchHeld = false
                                longPressJob?.cancel()
                                if (isRunZone) {
                                    // Release, cancellation, a second finger, or a swipe
                                    // always stops the player's own footstep loop.
                                    viewModel.stopPlayerMove()
                                    longPressActive = false
                                }
                                if (holdFireStarted) {
                                    // Lifting the finger stops the bullets and
                                    // the looped gun sound together.
                                    viewModel.stopHoldFire()
                                }
                            }

                            // The burst already fired every bullet it had, so
                            // the same swipe must not fire another one.
                            if (holdFireStarted) {
                                holdFireStarted = false
                                return@awaitEachGesture
                            }

                            val threshold = if (maxFingers >= 2) {
                                SWIPE_THRESHOLD_TWO_FINGERS
                            } else {
                                SWIPE_THRESHOLD_ONE_FINGER
                            }
                            if (maxDelta >= threshold) {
                                val direction = if (abs(bestDx) >= abs(bestDy)) {
                                    if (bestDx > 0) "right" else "left"
                                } else {
                                    if (bestDy > 0) "down" else "up"
                                }
                                viewModel.handleGesture(direction, maxFingers)
                                return@awaitEachGesture
                            }

                            if (maxDelta < TAP_SLOP && maxFingers == 1) {
                                if (isCenter) {
                                    viewModel.handleCenterTap()
                                } else if (!isBattle) {
                                    viewModel.handleTap()
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = if (currentScreen == Screen.BATTLE) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxWidth().widthIn(max = 560.dp)
                }
            ) {
                when (currentScreen) {
                    Screen.WELCOME -> WelcomeScreen(viewModel)
                    Screen.MENU -> MenuScreen(viewModel)
                    Screen.SHOP -> ShopScreen(viewModel)
                    Screen.READER -> ReaderScreen(viewModel)
                    Screen.JUMP -> JumpScreen(viewModel)
                    Screen.PARA -> ParaScreen(viewModel)
                    Screen.LAND -> LandScreen(viewModel)
                    Screen.BATTLE -> BattleScreen(viewModel)
                    Screen.SETTINGS -> SettingsScreen(viewModel)
                    Screen.GUIDE -> GuideScreen(viewModel)
                    Screen.PROFILE -> ProfileScreen(viewModel)
                    Screen.ABOUT -> AboutScreen(viewModel)
                    Screen.CHANGELOG -> ChangelogScreen(viewModel)
                    Screen.RESULT -> ResultScreen(viewModel)
                }
            }
        }
    }
}
