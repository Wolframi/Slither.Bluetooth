package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.BluetoothGameManager
import com.example.bluetooth.BluetoothState
import com.example.bluetooth.BtDeviceItem
import com.example.bluetooth.HostGameBridge
import com.example.data.PlayerPreferences
import com.example.data.SavedPlayerProfile
import com.example.game.ArenaEngine
import com.example.game.ArenaSizeOption
import com.example.game.ArenaSnapshot
import com.example.game.BotDifficulty
import com.example.game.BotNames
import com.example.game.ControlMode
import com.example.game.DeathSummary
import com.example.game.GameMode
import com.example.online.OnlineLiveSnapshot
import com.example.online.OnlinePlayerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface AppScreen {
    data object Lobby : AppScreen
    data object BtHostSetup : AppScreen
    data object BtJoinBrowser : AppScreen
    data object OnlineHostSetup : AppScreen
    data object OnlineJoinBrowser : AppScreen
    data object OnlineWaitingRoom : AppScreen
    data object Playing : AppScreen
}

data class GameUiState(
    val screen: AppScreen = AppScreen.Lobby,
    val gameMode: GameMode = GameMode.SOLO_BOTS,
    val arenaSize: ArenaSizeOption = ArenaSizeOption.STANDARD,
    val localWormId: Int? = null,
    val deathSummary: DeathSummary? = null,
    val isBoosting: Boolean = false,
    val onlineRespawnSeq: Long = 0L,
    val serverDisconnectMessage: String? = null
)

class GameViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PlayerPreferences(application)
    val btManager = BluetoothGameManager(application)

    val profile: StateFlow<SavedPlayerProfile> = prefs.profileFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), prefs.initialDefaultProfile())

    val bluetoothState: StateFlow<BluetoothState> = btManager.state

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState: StateFlow<GameUiState> = _uiState.asStateFlow()

    private val _steeringAngle = MutableStateFlow(0f)
    val steeringAngle: StateFlow<Float> = _steeringAngle.asStateFlow()

    private val _snapshot = MutableStateFlow(ArenaSnapshot())
    val snapshot: StateFlow<ArenaSnapshot> = _snapshot.asStateFlow()

    private var engine: ArenaEngine? = null
    private var gameLoopJob: Job? = null
    private var recordedCurrentDeath = false

    // Online host player tracking: uid -> (wormId, lastRespawnSeq)
    private val onlinePlayerWormMap = LinkedHashMap<String, Pair<Int, Long>>()
    private val onlineWormToUid = HashMap<Int, String>()
    private var onlinePublishCallback: ((ArenaSnapshot, Map<Int, String>, Map<String, Pair<Int, DeathSummary>>) -> Unit)? = null
    private var onlineMetaTickCallback: ((Int, Int) -> Unit)? = null
    private var onlineInputCallback: ((angle: Float, boosting: Boolean, respawnSeq: Long, force: Boolean) -> Unit)? = null

    init {
        viewModelScope.launch {
            prefs.ensureInitialIdentity()
        }
        viewModelScope.launch {
            btManager.state.collectLatest { bt ->
                if (_uiState.value.gameMode == GameMode.BT_CLIENT) {
                    if (bt.isConnectedToHost && _uiState.value.screen == AppScreen.BtJoinBrowser) {
                        _uiState.update {
                            it.copy(
                                screen = AppScreen.Playing,
                                localWormId = bt.assignedWormId
                            )
                        }
                    }
                    if (bt.remoteSnapshot != null && _uiState.value.screen == AppScreen.Playing) {
                        val death = bt.remoteDeathSummary
                        if (death != null && !recordedCurrentDeath) {
                            recordedCurrentDeath = true
                            prefs.recordMatchStats(death.finalScore, death.kills)
                        } else if (death == null) {
                            recordedCurrentDeath = false
                        }
                        _snapshot.value = bt.remoteSnapshot
                        val nextLocalId = bt.assignedWormId ?: _uiState.value.localWormId
                        if (_uiState.value.localWormId != nextLocalId || _uiState.value.deathSummary != death) {
                            _uiState.update {
                                it.copy(
                                    localWormId = nextLocalId,
                                    deathSummary = death
                                )
                            }
                        }
                    }
                    if (_uiState.value.screen == AppScreen.Playing &&
                        !bt.isConnectedToHost &&
                        !bt.errorMessage.isNullOrBlank() &&
                        _uiState.value.serverDisconnectMessage == null
                    ) {
                        _uiState.update {
                            it.copy(serverDisconnectMessage = "Сервер Bluetooth отключился")
                        }
                    }
                }
            }
        }
    }

    fun updateNickname(nickname: String) {
        viewModelScope.launch { prefs.saveNickname(nickname) }
    }

    fun selectSkin(skinId: Int) {
        viewModelScope.launch { prefs.saveSkinId(skinId) }
    }

    fun toggleHostBots(include: Boolean) {
        viewModelScope.launch { prefs.saveHostIncludeBots(include) }
    }

    fun selectBotDifficulty(difficulty: BotDifficulty) {
        viewModelScope.launch { prefs.saveBotDifficulty(difficulty) }
    }

    fun selectControlMode(mode: ControlMode) {
        viewModelScope.launch { prefs.saveControlMode(mode) }
    }

    fun setArenaSize(option: ArenaSizeOption) {
        _uiState.update { it.copy(arenaSize = option) }
    }

    // --- Bluetooth Mode ---

    fun openHostSetup() {
        btManager.refreshState()
        val size = _uiState.value.arenaSize
        val newEngine = ArenaEngine(
            arenaRadius = size.radius,
            targetAmbientOrbs = size.maxOrbs,
            botDifficulty = profile.value.botDifficulty
        )
        engine = newEngine

        _uiState.update {
            it.copy(
                screen = AppScreen.BtHostSetup,
                gameMode = GameMode.BT_HOST,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }

        if (btManager.checkPermissions() && btManager.state.value.isBluetoothEnabled) {
            startBtHostServer(newEngine)
        }
    }

    fun retryHostServerIfReady() {
        btManager.refreshState()
        val currentEngine = engine ?: ArenaEngine(
            arenaRadius = _uiState.value.arenaSize.radius,
            targetAmbientOrbs = _uiState.value.arenaSize.maxOrbs,
            botDifficulty = profile.value.botDifficulty
        ).also { engine = it }
        if (!btManager.state.value.isHosting) {
            startBtHostServer(currentEngine)
        }
    }

    private fun startBtHostServer(targetEngine: ArenaEngine) {
        btManager.startHosting(object : HostGameBridge {
            override fun onRemoteClientJoined(name: String, skinId: Int): Int {
                return targetEngine.addPlayerWorm(name, skinId, isBot = false)
            }

            override fun onRemoteClientInput(wormId: Int, targetAngle: Float, isBoosting: Boolean) {
                targetEngine.updatePlayerInput(wormId, targetAngle, isBoosting)
            }

            override fun onRemoteClientRespawn(wormId: Int, name: String, skinId: Int) {
                targetEngine.respawnPlayer(wormId, name, skinId)
            }

            override fun onRemoteClientDisconnected(wormId: Int) {
                targetEngine.removeWorm(wormId)
            }

            override fun getArenaRadius(): Float = _uiState.value.arenaSize.radius

            override fun getDeathSummaryForWorm(wormId: Int): DeathSummary? {
                return targetEngine.getDeathSummary(wormId)
            }
        })
    }

    fun openJoinBrowser() {
        btManager.refreshState()
        _uiState.update {
            it.copy(
                screen = AppScreen.BtJoinBrowser,
                gameMode = GameMode.BT_CLIENT,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }
        if (btManager.checkPermissions() && btManager.state.value.isBluetoothEnabled) {
            btManager.refreshPairedDevices()
        }
    }

    fun connectToBtHost(device: BtDeviceItem) {
        val currentProfile = profile.value
        recordedCurrentDeath = false
        _uiState.update {
            it.copy(
                gameMode = GameMode.BT_CLIENT,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }
        btManager.connectToHost(
            deviceItem = device,
            playerName = currentProfile.nickname,
            skinId = currentProfile.skinId
        )
    }

    fun startHostArenaMatch() {
        val size = _uiState.value.arenaSize
        val currentProfile = profile.value
        val activeEngine = engine ?: ArenaEngine(
            arenaRadius = size.radius,
            targetAmbientOrbs = size.maxOrbs,
            botDifficulty = currentProfile.botDifficulty
        ).also { engine = it }

        if (currentProfile.hostIncludeBots) {
            repeat(size.botCount) { idx ->
                val botName = BotNames.pool[idx % BotNames.pool.size]
                val bonus = (idx % 4) * 20
                activeEngine.addPlayerWorm(
                    name = botName,
                    skinId = (idx + 1) % 8,
                    isBot = true,
                    startingBonusScore = bonus
                )
            }
        }

        val hostWormId = activeEngine.addPlayerWorm(
            name = currentProfile.nickname,
            skinId = currentProfile.skinId,
            isBot = false
        )
        recordedCurrentDeath = false

        _uiState.update {
            it.copy(
                screen = AppScreen.Playing,
                gameMode = GameMode.BT_HOST,
                localWormId = hostWormId,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }

        startSimulationLoop(activeEngine, hostWormId, broadcastToBt = true, broadcastOnline = false)
    }

    // --- Internet Online Mode ---

    fun openOnlineHostSetup() {
        stopSimulation()
        btManager.stopAll()
        onlinePlayerWormMap.clear()
        onlineWormToUid.clear()
        val size = _uiState.value.arenaSize
        engine = ArenaEngine(
            arenaRadius = size.radius,
            targetAmbientOrbs = size.maxOrbs,
            botDifficulty = profile.value.botDifficulty
        )
        _uiState.update {
            it.copy(
                screen = AppScreen.OnlineHostSetup,
                gameMode = GameMode.ONLINE_HOST,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }
    }

    fun openOnlineJoinBrowser() {
        stopSimulation()
        btManager.stopAll()
        engine = null
        _uiState.update {
            it.copy(
                screen = AppScreen.OnlineJoinBrowser,
                gameMode = GameMode.ONLINE_CLIENT,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }
    }

    fun onOnlineClientJoinedRoom(roomStatus: String) {
        recordedCurrentDeath = false
        _snapshot.value = ArenaSnapshot()
        _uiState.update {
            it.copy(
                screen = if (roomStatus == "playing") AppScreen.Playing else AppScreen.OnlineWaitingRoom,
                gameMode = GameMode.ONLINE_CLIENT,
                localWormId = null,
                deathSummary = null,
                onlineRespawnSeq = 0L,
                serverDisconnectMessage = null
            )
        }
    }

    fun startOnlineHostMatch(
        hostUid: String,
        remotePlayers: List<OnlinePlayerInput>,
        onPublishLive: (ArenaSnapshot, Map<Int, String>, Map<String, Pair<Int, DeathSummary>>) -> Unit,
        onUpdateRoomTick: (playerCount: Int, tick: Int) -> Unit
    ) {
        val size = _uiState.value.arenaSize
        val currentProfile = profile.value
        val activeEngine = ArenaEngine(
            arenaRadius = size.radius,
            targetAmbientOrbs = size.maxOrbs,
            botDifficulty = currentProfile.botDifficulty
        ).also { engine = it }

        onlinePlayerWormMap.clear()
        onlineWormToUid.clear()
        onlinePublishCallback = onPublishLive
        onlineMetaTickCallback = onUpdateRoomTick

        if (currentProfile.hostIncludeBots) {
            repeat(size.botCount) { idx ->
                val botName = BotNames.pool[idx % BotNames.pool.size]
                val bonus = (idx % 4) * 20
                activeEngine.addPlayerWorm(
                    name = botName,
                    skinId = (idx + 1) % 8,
                    isBot = true,
                    startingBonusScore = bonus
                )
            }
        }

        val hostWormId = activeEngine.addPlayerWorm(
            name = currentProfile.nickname,
            skinId = currentProfile.skinId,
            isBot = false
        )
        onlineWormToUid[hostWormId] = hostUid
        syncOnlinePlayersToHostEngine(remotePlayers)

        recordedCurrentDeath = false
        _uiState.update {
            it.copy(
                screen = AppScreen.Playing,
                gameMode = GameMode.ONLINE_HOST,
                localWormId = hostWormId,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }

        startSimulationLoop(activeEngine, hostWormId, broadcastToBt = false, broadcastOnline = true)
    }

    fun syncOnlinePlayersToHostEngine(players: List<OnlinePlayerInput>) {
        if (_uiState.value.gameMode != GameMode.ONLINE_HOST) return
        val activeEngine = engine ?: return

        val currentUids = HashSet<String>(players.size)
        for (p in players) {
            if (p.uid.isBlank()) continue
            currentUids.add(p.uid)
            val existing = onlinePlayerWormMap[p.uid]
            if (existing == null) {
                val wormId = activeEngine.addPlayerWorm(
                    name = p.name,
                    skinId = p.skinId.toInt(),
                    isBot = false
                )
                onlinePlayerWormMap[p.uid] = wormId to p.respawnSeq
                onlineWormToUid[wormId] = p.uid
            } else {
                val (wormId, prevRespawnSeq) = existing
                activeEngine.updatePlayerInput(wormId, p.targetAngle.toFloat(), p.boosting)
                if (p.respawnSeq > prevRespawnSeq) {
                    activeEngine.respawnPlayer(wormId, p.name, p.skinId.toInt())
                    onlinePlayerWormMap[p.uid] = wormId to p.respawnSeq
                }
            }
        }

        val removedUids = onlinePlayerWormMap.keys.filter { it !in currentUids }
        for (uid in removedUids) {
            val pair = onlinePlayerWormMap.remove(uid)
            if (pair != null) {
                onlineWormToUid.remove(pair.first)
                activeEngine.removeWorm(pair.first)
            }
        }
    }

    fun setOnlineInputSender(sender: (angle: Float, boosting: Boolean, respawnSeq: Long, force: Boolean) -> Unit) {
        onlineInputCallback = sender
    }

    fun onOnlineClientStateReceived(
        roomStatus: String?,
        live: OnlineLiveSnapshot?,
        myUid: String
    ) {
        if (_uiState.value.gameMode != GameMode.ONLINE_CLIENT) return
        if (_uiState.value.screen == AppScreen.OnlineWaitingRoom && roomStatus == "playing") {
            _uiState.update { it.copy(screen = AppScreen.Playing) }
        }
        if (live != null) {
            val myWormId = live.wormIdByUid[myUid] ?: _uiState.value.localWormId
            val death = live.deathByUid[myUid]
            if (death != null && !recordedCurrentDeath) {
                recordedCurrentDeath = true
                viewModelScope.launch {
                    prefs.recordMatchStats(death.finalScore, death.kills)
                }
            } else if (death == null) {
                recordedCurrentDeath = false
            }

            val nextScreen = if (_uiState.value.screen == AppScreen.OnlineWaitingRoom) {
                AppScreen.Playing
            } else {
                _uiState.value.screen
            }

            _snapshot.value = live.snapshot
            if (_uiState.value.screen != nextScreen ||
                _uiState.value.localWormId != myWormId ||
                _uiState.value.deathSummary != death
            ) {
                _uiState.update {
                    it.copy(
                        screen = nextScreen,
                        localWormId = myWormId,
                        deathSummary = death
                    )
                }
            }
        }
    }

    // --- Offline Solo Mode ---

    fun startSoloBotMatch() {
        stopSimulation()
        btManager.stopAll()

        val size = _uiState.value.arenaSize
        val currentProfile = profile.value
        val soloEngine = ArenaEngine(
            arenaRadius = size.radius,
            targetAmbientOrbs = size.maxOrbs,
            botDifficulty = currentProfile.botDifficulty
        )
        engine = soloEngine

        repeat(size.botCount + 2) { idx ->
            val botName = BotNames.pool[idx % BotNames.pool.size]
            val bonus = (idx % 5) * 24
            soloEngine.addPlayerWorm(
                name = botName,
                skinId = (idx + 2) % 8,
                isBot = true,
                startingBonusScore = bonus
            )
        }

        val playerWormId = soloEngine.addPlayerWorm(
            name = currentProfile.nickname,
            skinId = currentProfile.skinId,
            isBot = false
        )
        recordedCurrentDeath = false

        _uiState.update {
            it.copy(
                screen = AppScreen.Playing,
                gameMode = GameMode.SOLO_BOTS,
                localWormId = playerWormId,
                deathSummary = null,
                serverDisconnectMessage = null
            )
        }

        startSimulationLoop(soloEngine, playerWormId, broadcastToBt = false, broadcastOnline = false)
    }

    private fun startSimulationLoop(
        activeEngine: ArenaEngine,
        localWormId: Int,
        broadcastToBt: Boolean,
        broadcastOnline: Boolean
    ) {
        gameLoopJob?.cancel()
        gameLoopJob = viewModelScope.launch(Dispatchers.Default) {
            var lastTimeNs = System.nanoTime()
            var nextFrameNs = lastTimeNs
            var networkTick = 0
            while (isActive) {
                val frameStartNs = System.nanoTime()
                val dt = ((frameStartNs - lastTimeNs) / 1_000_000_000f).coerceIn(0.005f, 0.035f)
                lastTimeNs = frameStartNs

                val snap = activeEngine.step(dt)
                val death = activeEngine.getDeathSummary(localWormId)

                if (death != null && !recordedCurrentDeath) {
                    recordedCurrentDeath = true
                    prefs.recordMatchStats(death.finalScore, death.kills)
                } else if (death == null) {
                    recordedCurrentDeath = false
                }

                _snapshot.value = snap
                if (_uiState.value.deathSummary != death) {
                    _uiState.update {
                        it.copy(deathSummary = death)
                    }
                }

                if (broadcastToBt) {
                    networkTick++
                    if (networkTick % 3 == 0) { // ~20 Hz over Bluetooth RFCOMM (offloaded to conflated Channel)
                        btManager.broadcastSnapshot(snap)
                    }
                } else if (broadcastOnline) {
                    networkTick++
                    val hasRemoteClients = onlinePlayerWormMap.isNotEmpty()
                    val publishModulo = if (hasRemoteClients) 4 else 45
                    if (networkTick % publishModulo == 0) {
                        val uidMapCopy = HashMap(onlineWormToUid)
                        val deathsMap = HashMap<String, Pair<Int, DeathSummary>>()
                        for ((uid, pair) in onlinePlayerWormMap) {
                            val d = activeEngine.getDeathSummary(pair.first)
                            if (d != null) {
                                deathsMap[uid] = pair.first to d
                            }
                        }
                        onlinePublishCallback?.invoke(snap, uidMapCopy, deathsMap)
                    }
                    if (networkTick % 180 == 0) {
                        onlineMetaTickCallback?.invoke(1 + onlinePlayerWormMap.size, snap.tick)
                    }
                }

                nextFrameNs += 16_666_667L
                val nowNs = System.nanoTime()
                val waitMs = (nextFrameNs - nowNs) / 1_000_000L
                if (waitMs > 1L) {
                    delay(waitMs)
                } else if (nowNs - nextFrameNs > 66_000_000L) {
                    // Resync deadline if OS paused/throttled the coroutine
                    nextFrameNs = nowNs
                }
            }
        }
    }

    fun setSteeringAngle(angle: Float) {
        updateControls(angle, _uiState.value.isBoosting)
    }

    fun updateControls(angle: Float, isBoosting: Boolean) {
        _steeringAngle.value = angle
        val currentState = _uiState.value
        if (currentState.isBoosting != isBoosting) {
            _uiState.update { it.copy(isBoosting = isBoosting) }
        }
        when (currentState.gameMode) {
            GameMode.SOLO_BOTS, GameMode.BT_HOST, GameMode.ONLINE_HOST -> {
                val wormId = currentState.localWormId ?: return
                engine?.updatePlayerInput(wormId, angle, isBoosting)
            }

            GameMode.BT_CLIENT -> {
                btManager.sendClientInput(angle, isBoosting)
            }

            GameMode.ONLINE_CLIENT -> {
                onlineInputCallback?.invoke(angle, isBoosting, currentState.onlineRespawnSeq, false)
            }
        }
    }

    fun setBoosting(boosting: Boolean) {
        updateControls(_steeringAngle.value, boosting)
    }

    fun respawnLocalPlayer() {
        val p = profile.value
        recordedCurrentDeath = false
        when (_uiState.value.gameMode) {
            GameMode.SOLO_BOTS, GameMode.BT_HOST, GameMode.ONLINE_HOST -> {
                val wormId = _uiState.value.localWormId ?: return
                engine?.respawnPlayer(wormId, p.nickname, p.skinId)
                _uiState.update { it.copy(deathSummary = null) }
            }

            GameMode.BT_CLIENT -> {
                btManager.sendClientRespawn(p.nickname, p.skinId)
            }

            GameMode.ONLINE_CLIENT -> {
                val nextSeq = _uiState.value.onlineRespawnSeq + 1L
                _uiState.update { it.copy(onlineRespawnSeq = nextSeq, deathSummary = null) }
                onlineInputCallback?.invoke(_steeringAngle.value, false, nextSeq, true)
            }
        }
    }

    fun returnToLobby() {
        stopSimulation()
        btManager.stopAll()
        engine = null
        onlinePlayerWormMap.clear()
        onlineWormToUid.clear()
        onlinePublishCallback = null
        onlineMetaTickCallback = null
        _snapshot.value = ArenaSnapshot()
        _uiState.update {
            it.copy(
                screen = AppScreen.Lobby,
                localWormId = null,
                deathSummary = null,
                isBoosting = false,
                serverDisconnectMessage = null
            )
        }
    }

    private fun stopSimulation() {
        gameLoopJob?.cancel()
        gameLoopJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopSimulation()
        btManager.cleanup()
    }
}
