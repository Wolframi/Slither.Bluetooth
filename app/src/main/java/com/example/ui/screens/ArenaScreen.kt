package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.ArenaSnapshot
import com.example.game.ControlMode
import com.example.game.DeathSummary
import com.example.game.GameMode
import com.example.ui.components.ArenaHudOverlay
import com.example.ui.components.WormArenaCanvas
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.SolarGold
import kotlinx.coroutines.delay

@Composable
fun ArenaScreen(
    snapshotState: State<ArenaSnapshot>,
    localWormId: Int?,
    gameMode: GameMode,
    controlMode: ControlMode,
    connectedBtCount: Int,
    steeringAngleState: State<Float>,
    isBoosting: Boolean,
    deathSummary: DeathSummary?,
    serverDisconnectMessage: String? = null,
    onSteeringChanged: (Float) -> Unit,
    onBoostChanged: (Boolean) -> Unit,
    onToggleControlMode: () -> Unit,
    onRespawnClick: () -> Unit,
    onExitToLobby: () -> Unit
) {
    BackHandler(onBack = onExitToLobby)
    val haptic = LocalHapticFeedback.current
    var showDeathMenu by remember { mutableStateOf(false) }
    var hudSnapshot by remember { mutableStateOf(snapshotState.value) }

    // Throttle HUD (score/leaderboard/minimap) updates to ~10 Hz so eating orb clusters causes zero UI relayout stutter
    LaunchedEffect(Unit) {
        var lastHudEmitMs = 0L
        snapshotFlow { snapshotState.value }.collect { latest ->
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastHudEmitMs >= 95L || latest.tick <= 2) {
                lastHudEmitMs = now
                hudSnapshot = latest
            }
        }
    }

    LaunchedEffect(deathSummary) {
        if (deathSummary != null) {
            showDeathMenu = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            // Let the death burst, scattered orbs, and camera zoom-out play out first
            delay(1450L)
            showDeathMenu = true
        } else {
            showDeathMenu = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        WormArenaCanvas(
            snapshotState = snapshotState,
            localWormId = localWormId,
            steeringAngleState = steeringAngleState,
            isNetworkClient = gameMode == GameMode.BT_CLIENT || gameMode == GameMode.ONLINE_CLIENT,
            modifier = Modifier.fillMaxSize()
        )

        if (deathSummary == null && serverDisconnectMessage == null) {
            ArenaHudOverlay(
                snapshot = hudSnapshot,
                localWormId = localWormId,
                gameMode = gameMode,
                controlMode = controlMode,
                connectedBtCount = connectedBtCount,
                isBoosting = isBoosting,
                onSteeringChanged = onSteeringChanged,
                onBoostChanged = onBoostChanged,
                onToggleControlMode = onToggleControlMode,
                onExitClick = onExitToLobby
            )
        }

        // Subtle, breathable radial scrim (never overly darkened so live arena stays visible)
        AnimatedVisibility(
            visible = (showDeathMenu && deathSummary != null) || serverDisconnectMessage != null,
            enter = fadeIn(animationSpec = tween(420)),
            exit = fadeOut(animationSpec = tween(180))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF060913).copy(alpha = 0.22f),
                                Color(0xFF060913).copy(alpha = 0.56f)
                            )
                        )
                    )
            )
        }

        // Animated floating death card appearing after ~1.5s camera zoom-out
        AnimatedVisibility(
            visible = showDeathMenu && deathSummary != null && serverDisconnectMessage == null,
            enter = fadeIn(animationSpec = tween(360)) +
                scaleIn(
                    initialScale = 0.88f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) +
                slideInVertically(
                    initialOffsetY = { it / 10 },
                    animationSpec = tween(360)
                ),
            exit = fadeOut(animationSpec = tween(180)) +
                scaleOut(targetScale = 0.94f, animationSpec = tween(180)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val summary = deathSummary ?: return@AnimatedVisibility
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF101827).copy(alpha = 0.88f),
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .widthIn(max = 410.dp)
                        .fillMaxWidth()
                        .border(
                            1.2.dp,
                            Brush.linearGradient(
                                colors = listOf(
                                    DangerCrimson.copy(alpha = 0.55f),
                                    NeonCyan.copy(alpha = 0.35f)
                                )
                            ),
                            RoundedCornerShape(28.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = DangerCrimson.copy(alpha = 0.15f),
                            modifier = Modifier.border(
                                1.dp,
                                DangerCrimson.copy(alpha = 0.45f),
                                RoundedCornerShape(50)
                            )
                        ) {
                            Text(
                                text = "МАТЧ ЗАВЕРШЁН",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                ),
                                color = DangerCrimson,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }

                        val reason = when {
                            summary.hitBoundary -> "Вы врезались в границу арены"
                            !summary.killerName.isNullOrBlank() -> "Вас подрезал ${summary.killerName}"
                            else -> "Столкновение на арене"
                        }
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )

                        val mins = summary.survivalSeconds / 60
                        val secs = summary.survivalSeconds % 60
                        val timeFormatted = "%d:%02d".format(mins, secs)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DeathStatTile(
                                label = "ДЛИНА",
                                value = "${summary.finalScore}",
                                accent = NeonCyan,
                                modifier = Modifier.weight(1f)
                            )
                            DeathStatTile(
                                label = "ФРАГИ",
                                value = "${summary.kills}",
                                accent = NeonLime,
                                modifier = Modifier.weight(1f)
                            )
                            DeathStatTile(
                                label = "ВРЕМЯ",
                                value = timeFormatted,
                                accent = SolarGold,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        // Redesigned balanced dual action bar: Lobby (secondary glass) + Respawn (neon gradient pill)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF1A2436))
                                    .border(
                                        1.dp,
                                        Color(0xFF334766),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable(onClick = onExitToLobby)
                                    .testTag("gameover_lobby_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = null,
                                        tint = Color(0xFFD6E2FF),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "В лобби",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = Color(0xFFD6E2FF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(NeonCyan, NeonLime)
                                        )
                                    )
                                    .clickable(onClick = onRespawnClick)
                                    .testTag("respawn_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Replay,
                                        contentDescription = null,
                                        tint = Color(0xFF041C16),
                                        modifier = Modifier.size(19.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Возродиться",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFF041C16),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Server shutdown / host disconnect overlay
        AnimatedVisibility(
            visible = serverDisconnectMessage != null,
            enter = fadeIn(animationSpec = tween(280)) +
                scaleIn(
                    initialScale = 0.90f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ),
            exit = fadeOut(animationSpec = tween(160)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val msg = serverDisconnectMessage ?: return@AnimatedVisibility
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF101827).copy(alpha = 0.94f),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth()
                        .border(
                            1.4.dp,
                            DangerCrimson.copy(alpha = 0.65f),
                            RoundedCornerShape(28.dp)
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(DangerCrimson.copy(alpha = 0.16f))
                                .border(1.dp, DangerCrimson.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = DangerCrimson,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = "Сервер отключился",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(NeonCyan, NeonLime)
                                    )
                                )
                                .clickable(onClick = onExitToLobby)
                                .testTag("server_closed_lobby_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = null,
                                    tint = Color(0xFF041C16),
                                    modifier = Modifier.size(19.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Вернуться в лобби",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF041C16)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeathStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF152033).copy(alpha = 0.85f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = accent,
            maxLines = 1
        )
    }
}
