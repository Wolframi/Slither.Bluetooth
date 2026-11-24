package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.ArenaEngine
import com.example.game.ArenaSnapshot
import com.example.game.ControlMode
import com.example.game.GameMode
import com.example.game.WormSkins
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.SolarGold
import kotlin.math.atan2
import kotlin.math.hypot

@Composable
fun ArenaHudOverlay(
    snapshot: ArenaSnapshot,
    localWormId: Int?,
    gameMode: GameMode,
    controlMode: ControlMode,
    connectedBtCount: Int,
    isBoosting: Boolean,
    onSteeringChanged: (angle: Float) -> Unit,
    onBoostChanged: (boosting: Boolean) -> Unit,
    onToggleControlMode: () -> Unit,
    onExitClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val localWorm = remember(snapshot.worms, localWormId) {
        snapshot.worms.firstOrNull { it.id == localWormId }
    }

    var previousKills by remember { mutableIntStateOf(0) }
    LaunchedEffect(localWorm?.kills) {
        val k = localWorm?.kills ?: 0
        if (k > previousKills) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        previousKills = k
    }

    val currentScore = localWorm?.score ?: ArenaEngine.INITIAL_SCORE
    val boostReserveRatio = ((currentScore - ArenaEngine.MIN_BOOST_SCORE).toFloat() / 60f)
        .coerceIn(0f, 1f)
    val canBoost = currentScore > ArenaEngine.MIN_BOOST_SCORE

    Box(modifier = modifier.fillMaxSize()) {
        // Full-screen Slither.io Touch-Follow + Double-Tap-Hold Boost surface
        if (controlMode == ControlMode.TOUCH_FOLLOW) {
            TouchFollowControlSurface(
                canBoost = canBoost,
                isBoosting = isBoosting,
                onSteeringChanged = onSteeringChanged,
                onBoostChanged = { active ->
                    if (active && canBoost && !isBoosting) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    onBoostChanged(active)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("touch_follow_surface")
            )
        }

        // Safe-area HUD controls & info
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp)
        ) {
            // Top-Left: Exit, Length/Kills, Control Mode Toggle, Shield/BT status & Minimap
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                        tonalElevation = 4.dp,
                        modifier = Modifier
                            .size(48.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    ) {
                        IconButton(
                            onClick = onExitClick,
                            modifier = Modifier.testTag("exit_arena_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = "Выйти в лобби",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.84f),
                        modifier = Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.85f),
                            RoundedCornerShape(16.dp)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = "ДЛИНА",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "$currentScore",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = NeonCyan
                                )
                            }
                            Column {
                                Text(
                                    text = "ФРАГИ",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${localWorm?.kills ?: 0}",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = NeonLime
                                )
                            }
                        }
                    }
                }

                // Quick toggle between Finger Follow and Joystick
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                        .clickable(onClick = onToggleControlMode)
                        .testTag("toggle_control_mode_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (controlMode == ControlMode.TOUCH_FOLLOW) {
                                Icons.Default.TouchApp
                            } else {
                                Icons.Default.Gamepad
                            },
                            contentDescription = "Сменить управление",
                            tint = NeonCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = controlMode.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (localWorm?.isSpawnProtected == true) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = NeonCyan.copy(alpha = 0.2f),
                        modifier = Modifier.border(
                            1.dp,
                            NeonCyan.copy(alpha = 0.7f),
                            RoundedCornerShape(50)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "ЩИТ СПАВНА",
                                style = MaterialTheme.typography.labelSmall,
                                color = NeonCyan
                            )
                        }
                    }
                }

                if (gameMode != GameMode.SOLO_BOTS) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (
                                    gameMode == GameMode.ONLINE_HOST ||
                                    gameMode == GameMode.ONLINE_CLIENT
                                ) {
                                    Icons.Default.Public
                                } else {
                                    Icons.Default.BluetoothConnected
                                },
                                contentDescription = null,
                                tint = NeonCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = when (gameMode) {
                                    GameMode.BT_HOST -> "BT Хост • Игроков: ${connectedBtCount + 1}"
                                    GameMode.BT_CLIENT -> "BT Клиент • Онлайн"
                                    GameMode.ONLINE_HOST -> "Онлайн Хост • Игроков: ${connectedBtCount + 1}"
                                    GameMode.ONLINE_CLIENT -> "Онлайн • Интернет"
                                    GameMode.SOLO_BOTS -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                ArenaMinimap(
                    snapshot = snapshot,
                    localWormId = localWormId,
                    modifier = Modifier.size(96.dp)
                )
            }

            // Top-Right: Live Leaderboard + Kill Feed
            Column(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    modifier = Modifier
                        .width(156.dp)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.75f),
                            RoundedCornerShape(14.dp)
                        )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEvents,
                                contentDescription = null,
                                tint = SolarGold,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "ЛИДЕРЫ",
                                style = MaterialTheme.typography.labelSmall,
                                color = SolarGold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        snapshot.leaderboard.forEachIndexed { idx, entry ->
                            val isMe = entry.wormId == localWormId
                            val skin = WormSkins.byId(entry.skinId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(skin.primaryColor)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "${idx + 1}. ${entry.name}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (isMe) NeonCyan else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${entry.score}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = if (idx == 0) SolarGold else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (!snapshot.killFeedText.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = DangerCrimson.copy(alpha = 0.85f)
                    ) {
                        Text(
                            text = snapshot.killFeedText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            if (controlMode == ControlMode.TOUCH_FOLLOW) {
                // Unobtrusive bottom hint for Slither.io finger gesture
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isBoosting && canBoost) NeonLime else NeonCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isBoosting && canBoost) {
                                "ТУРБО АКТИВНО"
                            } else {
                                "Двигайте пальцем для поворота • 2× тап и держите — ускорение"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isBoosting && canBoost) NeonLime else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Classic Joystick + Turbo button layout
                VirtualJoystick(
                    onAngleChanged = onSteeringChanged,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 8.dp)
                        .size(148.dp)
                        .testTag("virtual_joystick")
                )

                TurboBoostButton(
                    isBoosting = isBoosting && canBoost,
                    canBoost = canBoost,
                    reserveRatio = boostReserveRatio,
                    onBoostChanged = { active ->
                        if (active && canBoost) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        onBoostChanged(active)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = 14.dp)
                        .size(106.dp)
                        .testTag("turbo_boost_button")
                )
            }
        }
    }
}

@Composable
private fun TouchFollowControlSurface(
    canBoost: Boolean,
    isBoosting: Boolean,
    onSteeringChanged: (Float) -> Unit,
    onBoostChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var anchorPos by remember { mutableStateOf<Offset?>(null) }
    var fingerPos by remember { mutableStateOf<Offset?>(null) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }

    Box(
        modifier = modifier.pointerInput(Unit) {
            val maxAnchorRadiusPx = 56.dp.toPx()
            val deadzonePx = 10f

            awaitEachGesture {
                val firstDown = awaitFirstDown(requireUnconsumed = false)
                val now = System.currentTimeMillis()
                val isDoubleTap = (now - lastTapTimeMs) <= 360L
                lastTapTimeMs = now

                var currentAnchor = firstDown.position
                anchorPos = currentAnchor
                fingerPos = firstDown.position
                val boostFromDoubleTap = isDoubleTap

                // First finger press sets steering anchor without jerking worm toward touch corner;
                // dragging in any direction steers the worm that way, and 2x tap+hold boosts!
                onBoostChanged(boostFromDoubleTap)

                while (true) {
                    val event = awaitPointerEvent()
                    val pressedChanges = event.changes.filter { it.pressed }
                    if (pressedChanges.isEmpty()) {
                        anchorPos = null
                        fingerPos = null
                        onBoostChanged(false)
                        break
                    }

                    val primary = pressedChanges.firstOrNull { it.id == firstDown.id } ?: pressedChanges.first()
                    val pos = primary.position
                    fingerPos = pos

                    val dx = pos.x - currentAnchor.x
                    val dy = pos.y - currentAnchor.y
                    val dist = hypot(dx, dy)
                    if (dist > deadzonePx) {
                        onSteeringChanged(atan2(dy, dx))
                        if (dist > maxAnchorRadiusPx) {
                            currentAnchor = Offset(
                                x = pos.x - (dx / dist) * maxAnchorRadiusPx,
                                y = pos.y - (dy / dist) * maxAnchorRadiusPx
                            )
                            anchorPos = currentAnchor
                        }
                    }

                    val multiTouchBoost = pressedChanges.size >= 2
                    onBoostChanged(boostFromDoubleTap || multiTouchBoost)
                    pressedChanges.forEach { it.consume() }
                }
            }
        }
    ) {
        val anchor = anchorPos
        val pos = fingerPos
        if (anchor != null && pos != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val ringColor = if (isBoosting && canBoost) NeonLime else NeonCyan
                drawCircle(
                    color = ringColor.copy(alpha = 0.14f),
                    radius = 56.dp.toPx(),
                    center = anchor
                )
                drawCircle(
                    color = ringColor.copy(alpha = 0.48f),
                    radius = 56.dp.toPx(),
                    center = anchor,
                    style = Stroke(width = 1.8.dp.toPx())
                )
                drawLine(
                    color = ringColor.copy(alpha = 0.55f),
                    start = anchor,
                    end = pos,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = ringColor.copy(alpha = 0.28f),
                    radius = 20.dp.toPx(),
                    center = pos
                )
                drawCircle(
                    color = ringColor,
                    radius = 9.dp.toPx(),
                    center = pos
                )
            }
        }
    }
}

@Composable
private fun ArenaMinimap(
    snapshot: ArenaSnapshot,
    localWormId: Int?,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.9f)
    val leaderId = snapshot.leaderboard.firstOrNull()?.wormId

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = surfaceColor, radius = radius, center = center)
        drawCircle(
            color = borderColor,
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )
        drawCircle(
            color = borderColor.copy(alpha = 0.3f),
            radius = radius * 0.5f,
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        val arenaR = snapshot.arenaRadius.coerceAtLeast(100f)

        for (i in 0 until snapshot.orbs.size step 3) {
            val o = snapshot.orbs[i]
            if (!o.isDeathDrop) continue
            val nx = (o.x / arenaR).coerceIn(-1f, 1f)
            val ny = (o.y / arenaR).coerceIn(-1f, 1f)
            drawCircle(
                color = SolarGold.copy(alpha = 0.65f),
                radius = 1.6.dp.toPx(),
                center = Offset(
                    center.x + nx * (radius - 6.dp.toPx()),
                    center.y + ny * (radius - 6.dp.toPx())
                )
            )
        }

        for (w in snapshot.worms) {
            if (!w.isAlive) continue
            val nx = (w.headX / arenaR).coerceIn(-1f, 1f)
            val ny = (w.headY / arenaR).coerceIn(-1f, 1f)
            val pt = Offset(
                center.x + nx * (radius - 6.dp.toPx()),
                center.y + ny * (radius - 6.dp.toPx())
            )
            val isMe = w.id == localWormId
            val isLeader = w.id == leaderId
            val skinColor = WormSkins.byId(w.skinId).primaryColor
            drawCircle(
                color = when {
                    isMe -> Color.White
                    isLeader -> SolarGold
                    else -> skinColor.copy(alpha = 0.85f)
                },
                radius = when {
                    isMe -> 4.2.dp.toPx()
                    isLeader -> 3.4.dp.toPx()
                    else -> 2.4.dp.toPx()
                },
                center = pt
            )
        }
    }
}

@Composable
private fun VirtualJoystick(
    onAngleChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    val ringColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.52f)
    val bgColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.54f)
    val knobColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxDist = minOf(size.width, size.height) * 0.36f

                fun updateFromPos(pos: Offset) {
                    val dx = pos.x - center.x
                    val dy = pos.y - center.y
                    val dist = hypot(dx, dy)
                    if (dist > 6f) {
                        onAngleChanged(atan2(dy, dx))
                    }
                    knobOffset = if (dist > maxDist && dist > 0.001f) {
                        Offset(dx / dist * maxDist, dy / dist * maxDist)
                    } else {
                        Offset(dx, dy)
                    }
                }

                updateFromPos(down.position)
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        knobOffset = Offset.Zero
                        break
                    }
                    change.consume()
                    updateFromPos(change.position)
                }
            }
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension * 0.44f
            drawCircle(color = bgColor, radius = outerRadius, center = center)
            drawCircle(
                color = ringColor,
                radius = outerRadius,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = knobColor.copy(alpha = 0.28f),
                radius = outerRadius * 0.48f,
                center = center + knobOffset
            )
            drawCircle(
                color = knobColor,
                radius = outerRadius * 0.32f,
                center = center + knobOffset
            )
        }
    }
}

@Composable
private fun TurboBoostButton(
    isBoosting: Boolean,
    canBoost: Boolean,
    reserveRatio: Float,
    onBoostChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = when {
        !canBoost -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        isBoosting -> NeonLime
        else -> NeonCyan
    }
    val surfaceBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        activeColor.copy(alpha = if (isBoosting) 0.50f else 0.20f),
                        surfaceBg
                    )
                )
            )
            .pointerInput(canBoost) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onBoostChanged(true)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            onBoostChanged(false)
                            break
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeW = 4.dp.toPx()
            drawCircle(
                color = activeColor.copy(alpha = 0.22f),
                radius = size.minDimension / 2f - strokeW,
                style = Stroke(width = strokeW)
            )
            if (reserveRatio > 0.01f) {
                drawArc(
                    color = activeColor,
                    startAngle = -90f,
                    sweepAngle = 360f * reserveRatio,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Bolt,
                contentDescription = "Турбо ускорение",
                tint = activeColor,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = if (canBoost) "ТУРБО" else "МАЛО МАССЫ",
                fontSize = if (canBoost) 12.sp else 9.sp,
                fontWeight = FontWeight.Bold,
                color = activeColor
            )
        }
    }
}
