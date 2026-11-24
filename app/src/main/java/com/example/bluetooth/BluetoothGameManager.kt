package com.example.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.game.ArenaSnapshot
import com.example.game.DeathSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs

data class BtDeviceItem(
    val address: String,
    val name: String,
    val isPaired: Boolean,
    val device: BluetoothDevice
)

data class ConnectedClientInfo(
    val wormId: Int,
    val playerName: String,
    val deviceName: String,
    val skinId: Int
)

data class BluetoothState(
    val isHardwareSupported: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val hasPermissions: Boolean = false,
    val isScanning: Boolean = false,
    val isHosting: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnectedToHost: Boolean = false,
    val hostDeviceName: String? = null,
    val pairedDevices: List<BtDeviceItem> = emptyList(),
    val discoveredDevices: List<BtDeviceItem> = emptyList(),
    val connectedClients: List<ConnectedClientInfo> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val assignedWormId: Int? = null,
    val remoteSnapshot: ArenaSnapshot? = null,
    val remoteDeathSummary: DeathSummary? = null
)

interface HostGameBridge {
    fun onRemoteClientJoined(name: String, skinId: Int): Int
    fun onRemoteClientInput(wormId: Int, targetAngle: Float, isBoosting: Boolean)
    fun onRemoteClientRespawn(wormId: Int, name: String, skinId: Int)
    fun onRemoteClientDisconnected(wormId: Int)
    fun getArenaRadius(): Float
    fun getDeathSummaryForWorm(wormId: Int): DeathSummary?
}

class BluetoothGameManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = btManager?.adapter

    private val _state = MutableStateFlow(
        BluetoothState(
            isHardwareSupported = adapter != null,
            isBluetoothEnabled = adapter?.isEnabled == true,
            hasPermissions = checkPermissions()
        )
    )
    val state: StateFlow<BluetoothState> = _state.asStateFlow()

    private class ClientSession(
        val wormId: Int,
        val info: ConnectedClientInfo,
        val socket: BluetoothSocket,
        val output: DataOutputStream,
        val readerJob: Job
    )

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private var hostBroadcastJob: Job? = null
    private val broadcastChannel = Channel<ArenaSnapshot>(Channel.CONFLATED)
    private val clientSessions = CopyOnWriteArrayList<ClientSession>()
    private var hostBridge: HostGameBridge? = null

    private var clientSocket: BluetoothSocket? = null
    private var clientOutput: DataOutputStream? = null
    private var clientReaderJob: Job? = null
    private var clientInputWriterJob: Job? = null
    private val clientInputChannel = Channel<Pair<Float, Boolean>>(Channel.CONFLATED)

    private var discoveryReceiverRegistered = false
    private var lastInputSentMs = 0L
    private var lastSentAngle = 0f
    private var lastSentBoosting = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    if (device != null) {
                        val name = safeDeviceName(device)
                        val addr = device.address ?: return
                        val item = BtDeviceItem(
                            address = addr,
                            name = name,
                            isPaired = safeIsBonded(device),
                            device = device
                        )
                        _state.update { current ->
                            if (current.discoveredDevices.any { it.address == addr }) current
                            else current.copy(discoveredDevices = current.discoveredDevices + item)
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _state.update { it.copy(isScanning = false) }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val btState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    _state.update {
                        it.copy(isBluetoothEnabled = btState == BluetoothAdapter.STATE_ON)
                    }
                    if (btState == BluetoothAdapter.STATE_ON) {
                        refreshPairedDevices()
                    }
                }
            }
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    fun checkPermissions(): Boolean {
        return requiredPermissions().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun refreshState() {
        val perms = checkPermissions()
        val enabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            false
        }
        _state.update {
            it.copy(
                isHardwareSupported = adapter != null,
                isBluetoothEnabled = enabled,
                hasPermissions = perms
            )
        }
        if (perms && enabled) {
            refreshPairedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshPairedDevices() {
        if (!checkPermissions() || adapter == null || !adapter.isEnabled) return
        val paired = try {
            adapter.bondedDevices?.mapNotNull { dev ->
                val addr = dev.address ?: return@mapNotNull null
                BtDeviceItem(
                    address = addr,
                    name = safeDeviceName(dev),
                    isPaired = true,
                    device = dev
                )
            }?.sortedBy { it.name.lowercase() } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        _state.update { it.copy(pairedDevices = paired) }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (adapter == null) {
            _state.update { it.copy(errorMessage = "На устройстве отсутствует модуль Bluetooth") }
            return
        }
        if (!checkPermissions()) {
            _state.update { it.copy(errorMessage = "Не выданы разрешения на поиск по Bluetooth") }
            return
        }
        if (!adapter.isEnabled) {
            _state.update { it.copy(errorMessage = "Включите Bluetooth перед поиском") }
            return
        }

        registerDiscoveryReceiverIfNeeded()
        refreshPairedDevices()

        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            val started = adapter.startDiscovery()
            _state.update {
                it.copy(
                    isScanning = started,
                    discoveredDevices = emptyList(),
                    errorMessage = if (started) null else "Не удалось запустить поиск устройств"
                )
            }
        } catch (e: SecurityException) {
            _state.update {
                it.copy(isScanning = false, errorMessage = "Нет прав для сканирования Bluetooth: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        if (!checkPermissions()) return
        try {
            if (adapter?.isDiscovering == true) {
                adapter.cancelDiscovery()
            }
        } catch (_: SecurityException) {
        }
        _state.update { it.copy(isScanning = false) }
    }

    @SuppressLint("MissingPermission")
    fun startHosting(bridge: HostGameBridge) {
        if (adapter == null) {
            _state.update { it.copy(errorMessage = "Bluetooth не поддерживается на этом устройстве") }
            return
        }
        if (!checkPermissions()) {
            _state.update { it.copy(errorMessage = "Выдайте разрешения Bluetooth для создания комнаты") }
            return
        }
        if (!adapter.isEnabled) {
            _state.update { it.copy(errorMessage = "Сначала включите Bluetooth") }
            return
        }

        stopAll()
        hostBridge = bridge

        hostBroadcastJob = scope.launch {
            for (snapshot in broadcastChannel) {
                if (clientSessions.isEmpty()) continue
                val activeBridge = hostBridge ?: continue
                val arenaCore = BtProtocol.encodeArenaCore(snapshot)
                for (session in clientSessions) {
                    try {
                        val death = activeBridge.getDeathSummaryForWorm(session.wormId)
                        val bytes = BtProtocol.appendDeathSummary(arenaCore, death)
                        BtProtocol.writeFrame(session.output, BtProtocol.MSG_SNAPSHOT, bytes)
                    } catch (_: Exception) {
                        removeClientSession(session.wormId, activeBridge)
                    }
                }
            }
        }

        acceptJob = scope.launch {
            try {
                val srv = adapter.listenUsingRfcommWithServiceRecord(
                    BtProtocol.SERVICE_NAME,
                    BtProtocol.APP_UUID
                )
                serverSocket = srv
                _state.update {
                    it.copy(
                        isHosting = true,
                        errorMessage = null,
                        statusMessage = "Ожидание игроков по Bluetooth..."
                    )
                }

                while (isActive) {
                    val socket = try {
                        srv.accept()
                    } catch (e: IOException) {
                        if (isActive) {
                            _state.update { it.copy(statusMessage = "Приём подключений завершён") }
                        }
                        break
                    }
                    handleIncomingClient(socket, bridge)
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isHosting = false,
                        errorMessage = "Ошибка Bluetooth-сервера: ${e.localizedMessage ?: "неизвестно"}"
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleIncomingClient(socket: BluetoothSocket, bridge: HostGameBridge) {
        scope.launch {
            var assignedId: Int? = null
            try {
                val input = DataInputStream(BufferedInputStream(socket.inputStream, 16384))
                val output = DataOutputStream(BufferedOutputStream(socket.outputStream, 16384))

                val (type, payload) = BtProtocol.readFrame(input)
                if (type != BtProtocol.MSG_HELLO) {
                    socket.close()
                    return@launch
                }
                val (playerName, skinId) = BtProtocol.decodeHello(payload)
                val wormId = bridge.onRemoteClientJoined(playerName, skinId)
                assignedId = wormId

                BtProtocol.writeFrame(
                    output,
                    BtProtocol.MSG_WELCOME,
                    BtProtocol.encodeWelcome(wormId, bridge.getArenaRadius())
                )

                val deviceName = safeDeviceName(socket.remoteDevice)
                val info = ConnectedClientInfo(
                    wormId = wormId,
                    playerName = playerName,
                    deviceName = deviceName,
                    skinId = skinId
                )

                val readerJob = launch {
                    try {
                        while (isActive && socket.isConnected) {
                            val (msgType, msgPayload) = BtProtocol.readFrame(input)
                            when (msgType) {
                                BtProtocol.MSG_INPUT -> {
                                    val (angle, boosting) = BtProtocol.decodeInput(msgPayload)
                                    bridge.onRemoteClientInput(wormId, angle, boosting)
                                }

                                BtProtocol.MSG_RESPAWN -> {
                                    val (newName, newSkin) = BtProtocol.decodeHello(msgPayload)
                                    bridge.onRemoteClientRespawn(wormId, newName, newSkin)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        removeClientSession(wormId, bridge)
                    }
                }

                val session = ClientSession(
                    wormId = wormId,
                    info = info,
                    socket = socket,
                    output = output,
                    readerJob = readerJob
                )
                clientSessions.add(session)
                syncConnectedClientsState()
            } catch (_: Exception) {
                if (assignedId != null) {
                    removeClientSession(assignedId, bridge)
                } else {
                    try {
                        socket.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private fun removeClientSession(wormId: Int, bridge: HostGameBridge) {
        val target = clientSessions.firstOrNull { it.wormId == wormId }
        if (target != null) {
            clientSessions.remove(target)
            try {
                target.socket.close()
            } catch (_: IOException) {
            }
        }
        bridge.onRemoteClientDisconnected(wormId)
        syncConnectedClientsState()
    }

    private fun syncConnectedClientsState() {
        val list = clientSessions.map { it.info }
        _state.update {
            it.copy(
                connectedClients = list,
                statusMessage = if (list.isEmpty()) {
                    "Ожидание игроков по Bluetooth..."
                } else {
                    "Подключено по BT: ${list.size}"
                }
            )
        }
    }

    fun broadcastSnapshot(snapshot: ArenaSnapshot) {
        if (clientSessions.isEmpty() || hostBridge == null) return
        broadcastChannel.trySend(snapshot)
    }

    @SuppressLint("MissingPermission")
    fun connectToHost(deviceItem: BtDeviceItem, playerName: String, skinId: Int) {
        if (!checkPermissions() || adapter == null || !adapter.isEnabled) {
            _state.update { it.copy(errorMessage = "Включите Bluetooth и выдайте разрешения") }
            return
        }

        stopAll()
        stopDiscovery()

        _state.update {
            it.copy(
                isConnecting = true,
                errorMessage = null,
                statusMessage = "Подключение к ${deviceItem.name}...",
                remoteSnapshot = null,
                remoteDeathSummary = null
            )
        }

        clientReaderJob = scope.launch {
            try {
                try {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                } catch (_: SecurityException) {
                }

                val socket = deviceItem.device.createRfcommSocketToServiceRecord(BtProtocol.APP_UUID)
                socket.connect()
                clientSocket = socket

                val input = DataInputStream(BufferedInputStream(socket.inputStream, 16384))
                val output = DataOutputStream(BufferedOutputStream(socket.outputStream, 16384))
                clientOutput = output
                clientInputWriterJob?.cancel()
                clientInputWriterJob = scope.launch {
                    for ((angle, boosting) in clientInputChannel) {
                        val activeOut = clientOutput ?: break
                        try {
                            BtProtocol.writeFrame(
                                activeOut,
                                BtProtocol.MSG_INPUT,
                                BtProtocol.encodeInput(angle, boosting)
                            )
                        } catch (_: Exception) {
                            break
                        }
                    }
                }

                BtProtocol.writeFrame(
                    output,
                    BtProtocol.MSG_HELLO,
                    BtProtocol.encodeHello(playerName, skinId)
                )

                val (welcomeType, welcomePayload) = BtProtocol.readFrame(input)
                if (welcomeType != BtProtocol.MSG_WELCOME) {
                    throw IOException("Неверный ответ хоста")
                }
                val (wormId, _) = BtProtocol.decodeWelcome(welcomePayload)

                _state.update {
                    it.copy(
                        isConnecting = false,
                        isConnectedToHost = true,
                        hostDeviceName = deviceItem.name,
                        assignedWormId = wormId,
                        statusMessage = "Подключено к ${deviceItem.name}",
                        errorMessage = null
                    )
                }

                while (isActive && socket.isConnected) {
                    val (msgType, msgPayload) = BtProtocol.readFrame(input)
                    if (msgType == BtProtocol.MSG_SNAPSHOT) {
                        val (snapshot, death) = BtProtocol.decodeSnapshot(msgPayload)
                        _state.update {
                            it.copy(
                                remoteSnapshot = snapshot,
                                remoteDeathSummary = death
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isConnecting = false,
                        isConnectedToHost = false,
                        errorMessage = "Соединение потеряно: ${e.localizedMessage ?: "хост недоступен"}",
                        statusMessage = null
                    )
                }
            }
        }
    }

    fun sendClientInput(targetAngle: Float, isBoosting: Boolean) {
        if (clientOutput == null) return
        val now = System.currentTimeMillis()
        var angleDiff = abs(targetAngle - lastSentAngle)
        if (angleDiff > PI) angleDiff = (2.0 * PI - angleDiff).toFloat()
        val boostChanged = isBoosting != lastSentBoosting
        if (!boostChanged) {
            if (now - lastInputSentMs < 24L) return
            if (angleDiff < 0.025f && now - lastInputSentMs < 140L) return
        }
        lastInputSentMs = now
        lastSentAngle = targetAngle
        lastSentBoosting = isBoosting
        clientInputChannel.trySend(targetAngle to isBoosting)
    }

    fun sendClientRespawn(playerName: String, skinId: Int) {
        val output = clientOutput ?: return
        scope.launch {
            try {
                BtProtocol.writeFrame(
                    output,
                    BtProtocol.MSG_RESPAWN,
                    BtProtocol.encodeHello(playerName, skinId)
                )
            } catch (_: Exception) {
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun stopAll() {
        stopDiscovery()
        acceptJob?.cancel()
        acceptJob = null
        hostBroadcastJob?.cancel()
        hostBroadcastJob = null
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null

        for (session in clientSessions) {
            session.readerJob.cancel()
            try {
                session.socket.close()
            } catch (_: IOException) {
            }
        }
        clientSessions.clear()
        hostBridge = null

        clientInputWriterJob?.cancel()
        clientInputWriterJob = null
        clientReaderJob?.cancel()
        clientReaderJob = null
        clientOutput = null
        try {
            clientSocket?.close()
        } catch (_: IOException) {
        }
        clientSocket = null

        _state.update {
            it.copy(
                isHosting = false,
                isConnecting = false,
                isConnectedToHost = false,
                hostDeviceName = null,
                connectedClients = emptyList(),
                assignedWormId = null,
                remoteSnapshot = null,
                remoteDeathSummary = null,
                statusMessage = null
            )
        }
    }

    fun cleanup() {
        stopAll()
        if (discoveryReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (_: IllegalArgumentException) {
            }
            discoveryReceiverRegistered = false
        }
    }

    private fun registerDiscoveryReceiverIfNeeded() {
        if (discoveryReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(discoveryReceiver, filter)
        }
        discoveryReceiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "Неизвестное устройство"
        return try {
            device.name?.takeIf { it.isNotBlank() } ?: device.address ?: "Устройство BT"
        } catch (_: SecurityException) {
            "Устройство BT"
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeIsBonded(device: BluetoothDevice): Boolean {
        return try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
    }
}
