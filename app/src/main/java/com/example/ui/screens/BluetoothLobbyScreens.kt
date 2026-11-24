package com.example.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.bluetooth.BluetoothState
import com.example.bluetooth.BtDeviceItem
import com.example.game.ArenaSizeOption
import com.example.game.WormSkins
import com.example.ui.theme.AbyssBackground
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.MagentaPulse
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtHostSetupScreen(
    btState: BluetoothState,
    requiredPermissions: Array<String>,
    arenaSize: ArenaSizeOption,
    includeBots: Boolean,
    onRefreshBt: () -> Unit,
    onSelectArenaSize: (ArenaSizeOption) -> Unit,
    onToggleBots: (Boolean) -> Unit,
    onStartMatch: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshBt()
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshBt()
    }

    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshBt()
    }

    Scaffold(
        containerColor = AbyssBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Комната Bluetooth (Хост)") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("host_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AbyssBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                BluetoothReadinessCard(
                    btState = btState,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    onEnableBluetooth = {
                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }
                )

                if (btState.hasPermissions && btState.isBluetoothEnabled) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                            }
                            discoverableLauncher.launch(intent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("make_discoverable_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = NeonCyan
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Сделать устройство видимым (5 мин)")
                    }
                }

                // Connected Bluetooth Players
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Подключённые игроки (${btState.connectedClients.size})",
                                style = MaterialTheme.typography.titleMedium,
                                color = NeonCyan
                            )
                            if (btState.isHosting) {
                                Text(
                                    text = "СЕРВЕР АКТИВЕН",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NeonLime
                                )
                            }
                        }

                        if (btState.connectedClients.isEmpty()) {
                            Text(
                                text = "Пока никого нет. Попросите друзей нажать «Подключиться по Bluetooth» и выбрать ваш телефон (можно также подключиться и во время игры).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            btState.connectedClients.forEach { client ->
                                val skin = WormSkins.byId(client.skinId)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(skin.primaryColor)
                                    )
                                    Text(
                                        text = client.playerName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = client.deviceName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Arena Settings Card
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Размер арены",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ArenaSizeOption.entries.forEach { option ->
                                FilterChip(
                                    selected = arenaSize == option,
                                    onClick = { onSelectArenaSize(option) },
                                    label = { Text(option.label) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = NeonCyan
                                    ),
                                    modifier = Modifier.testTag("arena_size_${option.name.lowercase()}")
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Добавить AI-ботов",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Заполнят арену дополнительными червяками",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = includeBots,
                                onCheckedChange = onToggleBots,
                                modifier = Modifier.testTag("toggle_bots_switch")
                            )
                        }
                    }
                }

                Button(
                    onClick = onStartMatch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = Color(0xFF001F24)
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("start_host_arena_button")
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Запустить арену",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtJoinBrowserScreen(
    btState: BluetoothState,
    requiredPermissions: Array<String>,
    onRefreshBt: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClick: (BtDeviceItem) -> Unit,
    onClearError: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefreshBt()
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        onRefreshBt()
    }

    Scaffold(
        containerColor = AbyssBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Поиск Bluetooth-арены") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("join_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AbyssBackground,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    BluetoothReadinessCard(
                        btState = btState,
                        onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                        onEnableBluetooth = {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        }
                    )
                }

                if (!btState.errorMessage.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = DangerCrimson.copy(alpha = 0.18f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, DangerCrimson, MaterialTheme.shapes.medium)
                                .clickable(onClick = onClearError)
                        ) {
                            Text(
                                text = btState.errorMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }

                if (btState.hasPermissions && btState.isBluetoothEnabled) {
                    item {
                        Button(
                            onClick = {
                                if (btState.isScanning) onStopScan() else onStartScan()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (btState.isScanning) MagentaPulse else NeonCyan,
                                contentColor = if (btState.isScanning) Color.White else Color(0xFF001F24)
                            ),
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("scan_bt_button")
                        ) {
                            if (btState.isScanning) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("Поиск устройств... (нажмите для стопа)")
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Сканировать устройства рядом")
                            }
                        }
                    }
                }

                if (btState.discoveredDevices.isNotEmpty()) {
                    item {
                        Text(
                            text = "Найденные рядом",
                            style = MaterialTheme.typography.titleMedium,
                            color = NeonCyan,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    items(btState.discoveredDevices, key = { "disc_${it.address}" }) { dev ->
                        DeviceRowCard(
                            device = dev,
                            isConnecting = btState.isConnecting,
                            onClick = { onDeviceClick(dev) }
                        )
                    }
                }

                item {
                    Text(
                        text = "Сопряжённые устройства",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (btState.pairedDevices.isEmpty()) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "Нет сопряжённых устройств. Нажмите «Сканировать устройства рядом», пока друг открыл комнату хоста.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(btState.pairedDevices, key = { "pair_${it.address}" }) { dev ->
                        DeviceRowCard(
                            device = dev,
                            isConnecting = btState.isConnecting,
                            onClick = { onDeviceClick(dev) }
                        )
                    }
                }
            }

            if (btState.isConnecting) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AbyssBackground.copy(alpha = 0.78f))
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = NeonCyan)
                            Text(
                                text = btState.statusMessage ?: "Подключение по Bluetooth...",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRowCard(
    device: BtDeviceItem,
    isConnecting: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .clickable(enabled = !isConnecting, onClick = onClick)
            .testTag("bt_device_${device.address}")
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (device.isPaired) "Сопряжено" else "Рядом",
                style = MaterialTheme.typography.labelSmall,
                color = NeonLime
            )
        }
    }
}

@Composable
private fun BluetoothReadinessCard(
    btState: BluetoothState,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit
) {
    if (!btState.isHardwareSupported) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = DangerCrimson.copy(alpha = 0.16f),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DangerCrimson, MaterialTheme.shapes.medium)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BluetoothDisabled,
                    contentDescription = null,
                    tint = DangerCrimson
                )
                Text(
                    text = "Bluetooth-адаптер не обнаружен на этом устройстве. Для проверки игры на одном устройстве используйте «Тренировка с ботами» в лобби.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    if (!btState.hasPermissions) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(imageVector = Icons.Default.Bluetooth, contentDescription = null, tint = NeonCyan)
                    Text(
                        text = "Нужны разрешения Bluetooth",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Text(
                    text = "Разрешите поиск и подключение устройств поблизости для локальной игры.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.testTag("grant_bt_permissions_button")
                ) {
                    Text("Выдать разрешения")
                }
            }
        }
        return
    }

    if (!btState.isBluetoothEnabled) {
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Bluetooth выключен",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Включите Bluetooth, чтобы создать комнату или найти друзей.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(
                    onClick = onEnableBluetooth,
                    modifier = Modifier.testTag("enable_bt_button")
                ) {
                    Text("Включить Bluetooth")
                }
            }
        }
    }
}
