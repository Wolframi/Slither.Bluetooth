package com.example.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.SavedPlayerProfile
import com.example.game.ArenaSizeOption
import com.example.game.BotDifficulty
import com.example.game.ControlMode
import com.example.game.OrbPalette
import com.example.game.PlayerIdentityGenerator
import com.example.game.WormSkin
import com.example.game.WormSkins
import com.example.ui.theme.AbyssBackground
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.MagentaPulse
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.SolarGold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun LobbyScreen(
    profile: SavedPlayerProfile,
    arenaSize: ArenaSizeOption,
    isSignedIn: Boolean,
    accountLabel: String?,
    activeOnlineRoomsCount: Int,
    isSigningIn: Boolean = false,
    authErrorMessage: String? = null,
    onNicknameChanged: (String) -> Unit,
    onSkinSelected: (Int) -> Unit,
    onBotDifficultySelected: (BotDifficulty) -> Unit,
    onArenaSizeSelected: (ArenaSizeOption) -> Unit,
    onControlModeSelected: (ControlMode) -> Unit,
    onHostOnlineClick: () -> Unit,
    onJoinOnlineClick: () -> Unit,
    onHostBluetoothClick: () -> Unit,
    onJoinBluetoothClick: () -> Unit,
    onSoloBotsClick: () -> Unit,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit
) {
    val selectedSkin = WormSkins.byId(profile.skinId)
    val scrollState = rememberScrollState()
    var selectedTab by rememberSaveable { mutableIntStateOf(if (isSignedIn) 0 else 1) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F15))
    ) {
        // Authentic Slither.io dark hex backdrop
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerR = 46f
            val innerR = 41f
            val dx = outerR * 1.5f
            val dy = outerR * sqrt(3f)
            val cols = (size.width / dx).toInt() + 2
            val rows = (size.height / dy).toInt() + 2
            val path = Path()
            for (c in -1..cols) {
                val cx = c * dx
                val yOff = if (c and 1 != 0) dy * 0.5f else 0f
                for (r in -1..rows) {
                    val cy = r * dy + yOff
                    path.reset()
                    for (v in 0 until 6) {
                        val a = (PI / 3.0 * v).toFloat()
                        val vx = cx + innerR * cos(a)
                        val vy = cy + innerR * sin(a)
                        if (v == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
                    }
                    path.close()
                    drawPath(path, color = Color(0xFF141A22))
                    drawPath(path, color = Color(0xFF1D2632), style = Stroke(width = 1.2f))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 24.dp, top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Sleek Top Bar: Brand + Record + Account Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "slither.bt",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = NeonLime
                        )
                        Text(
                            text = "Интернет • Bluetooth • Соло",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (profile.bestScore > 0) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                modifier = Modifier.border(
                                    1.dp,
                                    SolarGold.copy(alpha = 0.5f),
                                    RoundedCornerShape(50)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.EmojiEvents,
                                        contentDescription = "Рекорд",
                                        tint = SolarGold,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "${profile.bestScore}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = SolarGold
                                    )
                                }
                            }
                        }

                        if (isSignedIn) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(50))
                                    .clickable(onClick = onSignOutClick)
                                    .testTag("sign_out_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = (accountLabel ?: "Google").take(12),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonCyan,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = "Выйти из аккаунта",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = NeonCyan.copy(alpha = 0.16f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(50))
                                    .clickable(enabled = !isSigningIn, onClick = onSignInClick)
                                    .testTag("lobby_sign_in_chip")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    if (isSigningIn) {
                                        CircularProgressIndicator(
                                            color = NeonCyan,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.AccountCircle,
                                            contentDescription = "Войти",
                                            tint = NeonCyan,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    Text(
                                        text = if (isSigningIn) "Вход..." else "Войти",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NeonCyan
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. Clean & Ergonomic Worm Skin + Controls Studio Card
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.2.dp,
                            selectedSkin.primaryColor.copy(alpha = 0.48f),
                            MaterialTheme.shapes.large
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Nickname + Randomize Skin button side-by-side
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = profile.nickname,
                                onValueChange = onNicknameChanged,
                                label = { Text("Ваш никнейм") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = selectedSkin.primaryColor
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            onNicknameChanged(PlayerIdentityGenerator.randomNickname())
                                        },
                                        modifier = Modifier.testTag("random_nickname_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Casino,
                                            contentDescription = "Случайное имя",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = selectedSkin.primaryColor,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("nickname_input")
                            )

                            FilledTonalIconButton(
                                onClick = {
                                    val nextId = (selectedSkin.id + 1 + (1..4).random()) % WormSkins.all.size
                                    onSkinSelected(nextId)
                                },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = selectedSkin.primaryColor.copy(alpha = 0.16f),
                                    contentColor = selectedSkin.primaryColor
                                ),
                                modifier = Modifier
                                    .size(52.dp)
                                    .testTag("random_skin_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Casino,
                                    contentDescription = "Случайный скин"
                                )
                            }
                        }

                        // Live Slither Worm Showcase with Skin Name Badge inside
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(88.dp)
                        ) {
                            AnimatedWormPreview(
                                skin = selectedSkin,
                                modifier = Modifier.fillMaxSize()
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = AbyssBackground.copy(alpha = 0.78f),
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .border(1.dp, selectedSkin.primaryColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                            ) {
                                Text(
                                    text = selectedSkin.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = selectedSkin.primaryColor,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                            }
                        }

                        // Swatch Picker
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            items(WormSkins.all, key = { it.id }) { skin ->
                                val isSelected = skin.id == selectedSkin.id
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(skin.primaryColor, skin.secondaryColor)
                                            )
                                        )
                                        .border(
                                            width = if (isSelected) 3.dp else 1.dp,
                                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.25f),
                                            shape = CircleShape
                                        )
                                        .clickable { onSkinSelected(skin.id) }
                                        .testTag("skin_option_${skin.id}")
                                ) {
                                    if (isSelected) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                    }
                                }
                            }
                        }

                        // Compact Control Mode Segmented Switcher
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF0E141C))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ControlMode.entries.forEach { mode ->
                                val selected = profile.controlMode == mode
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onControlModeSelected(mode) }
                                        .testTag("control_mode_${mode.name.lowercase()}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (mode == ControlMode.TOUCH_FOLLOW) {
                                                Icons.Default.TouchApp
                                            } else {
                                                Icons.Default.Gamepad
                                            },
                                            contentDescription = null,
                                            tint = if (selected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) NeonCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Clean 3-Tab Mode Deck: Internet | Bluetooth | Solo Bots
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ModeTabPill(
                        label = if (activeOnlineRoomsCount > 0) "Сеть ($activeOnlineRoomsCount)" else "Интернет",
                        icon = Icons.Default.Public,
                        selected = selectedTab == 0,
                        accentColor = NeonCyan,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_internet")
                    )
                    ModeTabPill(
                        label = "Bluetooth",
                        icon = Icons.Default.Bluetooth,
                        selected = selectedTab == 1,
                        accentColor = MagentaPulse,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_bluetooth")
                    )
                    ModeTabPill(
                        label = "Боты",
                        icon = Icons.Default.SmartToy,
                        selected = selectedTab == 2,
                        accentColor = NeonLime,
                        onClick = { selectedTab = 2 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tab_bots")
                    )
                }

                when (selectedTab) {
                    0 -> {
                        // Internet Multiplayer Deck
                        if (!isSignedIn) {
                            Surface(
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.2.dp, NeonCyan.copy(alpha = 0.5f), MaterialTheme.shapes.large)
                            ) {
                                Column(
                                    modifier = Modifier.padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Онлайн-игра по интернету",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = NeonCyan
                                    )
                                    Text(
                                        text = "Войдите через Google, чтобы хостить сервер на телефоне и подключаться к списку открытых комнат.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!authErrorMessage.isNullOrBlank()) {
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = DangerCrimson.copy(alpha = 0.16f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(1.dp, DangerCrimson, MaterialTheme.shapes.medium)
                                        ) {
                                            Text(
                                                text = authErrorMessage,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = onSignInClick,
                                        enabled = !isSigningIn,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = NeonCyan,
                                            contentColor = Color(0xFF001F24)
                                        ),
                                        shape = MaterialTheme.shapes.large,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("unlock_online_signin_button")
                                    ) {
                                        if (isSigningIn) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = Color(0xFF001F24),
                                                strokeWidth = 2.5.dp
                                            )
                                        } else {
                                            Icon(imageVector = Icons.Default.AccountCircle, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Войти через Google",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                ModeActionCard(
                                    title = "Создать онлайн-сервер (Хост)",
                                    subtitle = "Запустите сервер на телефоне — другие игроки зайдут из списка",
                                    icon = Icons.Default.Public,
                                    accentColor = NeonLime,
                                    onClick = onHostOnlineClick,
                                    modifier = Modifier.testTag("host_online_button")
                                )
                                ModeActionCard(
                                    title = if (activeOnlineRoomsCount > 0) {
                                        "Список серверов ($activeOnlineRoomsCount онлайн)"
                                    } else {
                                        "Список серверов (Интернет)"
                                    },
                                    subtitle = "Нажмите на любую комнату в списке и врывайтесь в игру",
                                    icon = Icons.Default.Dns,
                                    accentColor = NeonCyan,
                                    onClick = onJoinOnlineClick,
                                    modifier = Modifier.testTag("join_online_button")
                                )
                            }
                        }
                    }

                    1 -> {
                        // Bluetooth Local Multiplayer Deck
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ModeActionCard(
                                title = "Создать Bluetooth-арену",
                                subtitle = "Станьте хостом и примите друзей рядом без интернета",
                                icon = Icons.Default.WifiTethering,
                                accentColor = NeonCyan,
                                onClick = onHostBluetoothClick,
                                modifier = Modifier.testTag("host_bt_button")
                            )
                            ModeActionCard(
                                title = "Подключиться по Bluetooth",
                                subtitle = "Найдите телефон друга поблизости и подключайтесь",
                                icon = Icons.AutoMirrored.Filled.BluetoothSearching,
                                accentColor = MagentaPulse,
                                onClick = onJoinBluetoothClick,
                                modifier = Modifier.testTag("join_bt_button")
                            )
                        }
                    }

                    else -> {
                        // Solo Bots Deck
                        Card(
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, NeonLime.copy(alpha = 0.55f), MaterialTheme.shapes.large)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Сложность ботов",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    BotDifficulty.entries.forEach { diff ->
                                        FilterChip(
                                            selected = profile.botDifficulty == diff,
                                            onClick = { onBotDifficultySelected(diff) },
                                            label = { Text(diff.label) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NeonLime.copy(alpha = 0.22f),
                                                selectedLabelColor = NeonLime
                                            ),
                                            modifier = Modifier.testTag("bot_diff_${diff.name.lowercase()}")
                                        )
                                    }
                                }

                                Text(
                                    text = "Размер арены",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    ArenaSizeOption.entries.forEach { size ->
                                        FilterChip(
                                            selected = arenaSize == size,
                                            onClick = { onArenaSizeSelected(size) },
                                            label = { Text(size.label) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                                                selectedLabelColor = NeonCyan
                                            )
                                        )
                                    }
                                }

                                Button(
                                    onClick = onSoloBotsClick,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonLime,
                                        contentColor = Color(0xFF062000)
                                    ),
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("solo_bots_button")
                                ) {
                                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Играть с ботами",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeTabPill(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accentColor.copy(alpha = 0.18f) else Color.Transparent,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (selected) {
                    Modifier.border(1.dp, accentColor.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ModeActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(1.5.dp, accentColor.copy(alpha = 0.55f), MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = 0.16f))
                    .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AnimatedWormPreview(
    skin: WormSkin,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "worm_preview")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "preview_wave"
    )

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color(0xFF0E131A))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (o in 0..5) {
                val ox = size.width * (0.12f + o * 0.16f)
                val oy = size.height * (0.24f + ((o % 2) * 0.52f)) + sin(phase + o) * 4f
                val orbColor = OrbPalette.byIndex(o)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(orbColor.copy(alpha = 0.65f), Color.Transparent),
                        center = Offset(ox, oy),
                        radius = 15f
                    ),
                    radius = 15f,
                    center = Offset(ox, oy)
                )
                drawCircle(color = orbColor, radius = 4.6f, center = Offset(ox, oy))
                drawCircle(
                    color = Color.White.copy(alpha = 0.85f),
                    radius = 1.6f,
                    center = Offset(ox - 1.4f, oy - 1.4f)
                )
            }

            val segCount = 34
            val startX = size.width * 0.16f
            val endX = size.width * 0.84f
            val centerY = size.height * 0.52f
            val amp = size.height * 0.17f
            val radius = size.height * 0.25f

            for (i in segCount - 1 downTo 0) {
                val t = 1f - (i.toFloat() / (segCount - 1))
                val x = startX + (endX - startX) * t
                val y = centerY + sin(phase - i * 0.24f) * amp
                val taper = if (i > segCount - 8) 0.44f + 0.56f * ((segCount - i) / 8f) else 1f
                val r = radius * taper * (if (i == 0) 1.04f else 1f)

                val col = when {
                    skin.rainbowMode -> Color.hsv(((i * 11) % 360).toFloat(), 0.86f, 1f)
                    skin.stripePattern -> {
                        val b = 0.5f - 0.5f * cos(i * 0.62f)
                        Color(
                            red = skin.primaryColor.red + (skin.secondaryColor.red - skin.primaryColor.red) * b,
                            green = skin.primaryColor.green + (skin.secondaryColor.green - skin.primaryColor.green) * b,
                            blue = skin.primaryColor.blue + (skin.secondaryColor.blue - skin.primaryColor.blue) * b
                        )
                    }
                    else -> {
                        val b = (0.5f + 0.5f * cos(i * 0.85f)) * 0.28f
                        Color(
                            red = skin.primaryColor.red + (skin.secondaryColor.red - skin.primaryColor.red) * b,
                            green = skin.primaryColor.green + (skin.secondaryColor.green - skin.primaryColor.green) * b,
                            blue = skin.primaryColor.blue + (skin.secondaryColor.blue - skin.primaryColor.blue) * b
                        )
                    }
                }
                val rim = Color(col.red * 0.55f, col.green * 0.55f, col.blue * 0.55f)

                drawCircle(
                    color = Color.Black.copy(alpha = 0.28f),
                    radius = r * 1.11f,
                    center = Offset(x, y + r * 0.13f)
                )
                drawCircle(color = rim, radius = r * 1.02f, center = Offset(x, y))
                drawCircle(
                    color = col,
                    radius = r * 0.90f,
                    center = Offset(x - r * 0.03f, y - r * 0.04f)
                )
                drawCircle(
                    color = Color.White.copy(alpha = if (i == 0) 0.14f else 0.085f),
                    radius = r * 0.56f,
                    center = Offset(x - r * 0.20f, y - r * 0.22f)
                )
            }

            val headX = endX
            val headY = centerY + sin(phase) * amp
            drawCircle(
                color = Color(0xFF1A222D),
                radius = radius * 0.37f,
                center = Offset(headX + radius * 0.24f, headY - radius * 0.40f)
            )
            drawCircle(
                color = Color(0xFF1A222D),
                radius = radius * 0.37f,
                center = Offset(headX + radius * 0.24f, headY + radius * 0.40f)
            )
            drawCircle(
                color = Color.White,
                radius = radius * 0.32f,
                center = Offset(headX + radius * 0.24f, headY - radius * 0.40f)
            )
            drawCircle(
                color = Color.White,
                radius = radius * 0.32f,
                center = Offset(headX + radius * 0.24f, headY + radius * 0.40f)
            )
            drawCircle(
                color = AbyssBackground,
                radius = radius * 0.17f,
                center = Offset(headX + radius * 0.34f, headY - radius * 0.40f)
            )
            drawCircle(
                color = AbyssBackground,
                radius = radius * 0.17f,
                center = Offset(headX + radius * 0.34f, headY + radius * 0.40f)
            )
            drawCircle(
                color = Color.White,
                radius = radius * 0.06f,
                center = Offset(headX + radius * 0.30f, headY - radius * 0.44f)
            )
            drawCircle(
                color = Color.White,
                radius = radius * 0.06f,
                center = Offset(headX + radius * 0.30f, headY + radius * 0.36f)
            )
        }
    }
}
