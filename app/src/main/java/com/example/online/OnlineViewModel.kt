package com.example.online

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.game.ArenaSizeOption
import com.example.game.ArenaSnapshot
import com.example.game.BotDifficulty
import com.example.game.DeathSummary
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs

sealed interface OnlineUiState<out T> {
    data object Loading : OnlineUiState<Nothing>
    data class Success<T>(val data: T) : OnlineUiState<T>
    data class Error(val message: String) : OnlineUiState<Nothing>
}

class OnlineViewModel(
    private val repository: OnlineGameRepository,
    val currentUserId: String
) : ViewModel() {

    val activeRooms: StateFlow<OnlineUiState<List<OnlineRoom>>> = repository.observeActiveRooms()
        .map<List<OnlineRoom>, OnlineUiState<List<OnlineRoom>>> { OnlineUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing active rooms", rootError)
            emit(OnlineUiState.Error(translateOnlineError(rootError, "Не удалось загрузить список серверов")))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = OnlineUiState.Loading
        )

    private val _hostedRoom = MutableStateFlow<OnlineRoom?>(null)
    val hostedRoom: StateFlow<OnlineRoom?> = _hostedRoom.asStateFlow()

    private val _hostPlayers = MutableStateFlow<List<OnlinePlayerInput>>(emptyList())
    val hostPlayers: StateFlow<List<OnlinePlayerInput>> = _hostPlayers.asStateFlow()

    private val _joinedRoom = MutableStateFlow<OnlineRoom?>(null)
    val joinedRoom: StateFlow<OnlineRoom?> = _joinedRoom.asStateFlow()

    private val _liveSnapshot = MutableStateFlow<OnlineLiveSnapshot?>(null)
    val liveSnapshot: StateFlow<OnlineLiveSnapshot?> = _liveSnapshot.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val _serverClosedMessage = MutableStateFlow<String?>(null)
    val serverClosedMessage: StateFlow<String?> = _serverClosedMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private data class QueuedOnlineInput(
        val roomId: String,
        val name: String,
        val skinId: Int,
        val targetAngle: Float,
        val boosting: Boolean,
        val respawnSeq: Long
    )

    private data class PendingLivePublish(
        val roomId: String,
        val snapshot: ArenaSnapshot,
        val uidByWormId: Map<Int, String>,
        val deathByUid: Map<String, Pair<Int, DeathSummary>>
    )

    private var hostPlayersJob: Job? = null
    private var clientRoomJob: Job? = null
    private var clientLiveJob: Job? = null
    private var clientWatchdogJob: Job? = null
    private var publishJob: Job? = null
    private var inputJob: Job? = null
    private val publishChannel = Channel<PendingLivePublish>(Channel.CONFLATED)
    private val clientInputChannel = Channel<QueuedOnlineInput>(Channel.CONFLATED)
    private var hasPublishedInitialState = false
    private var lastPublishedTick = -1
    private var publishSeqCount = 0
    private var lastSentOrbCount = -1
    private var lastSentDeathDropCount = 0
    private var lastSentKillFeed: String? = null
    private var lastSentHadDeaths = false
    private var lastServerPulseMs = 0L
    private var lastInputSentMs = 0L
    private var lastSentAngle = 0f
    private var lastSentBoosting = false
    private var lastSentRespawnSeq = 0L

    fun clearError() {
        _actionError.value = null
        _serverClosedMessage.value = null
    }

    fun openOrCreateHostRoom(
        roomName: String,
        hostName: String,
        arenaSize: ArenaSizeOption,
        includeBots: Boolean,
        botDifficulty: BotDifficulty
    ) {
        if (_hostedRoom.value != null || _isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            _actionError.value = null
            _serverClosedMessage.value = null
            hasPublishedInitialState = false
            lastPublishedTick = -1
            val result = repository.createRoom(
                roomName = roomName,
                hostName = hostName,
                arenaSize = arenaSize,
                includeBots = includeBots,
                botDifficulty = botDifficulty
            )
            _isBusy.value = false
            result.onSuccess { room ->
                _hostedRoom.value = room
                startObservingHostPlayers(room.id)
            }.onFailure { err ->
                _actionError.value = translateOnlineError(err, "Не удалось создать онлайн-комнату")
            }
        }
    }

    private fun startObservingHostPlayers(roomId: String) {
        hostPlayersJob?.cancel()
        hostPlayersJob = viewModelScope.launch {
            repository.observeRoomPlayers(roomId, currentUserId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing host players", firestoreError ?: error)
                }
                .collectLatest { list ->
                    _hostPlayers.value = list
                }
        }
    }

    fun updateHostRoomSettings(
        roomName: String,
        status: String,
        arenaSize: ArenaSizeOption,
        includeBots: Boolean,
        botDifficulty: BotDifficulty,
        playerCount: Int,
        tick: Int
    ) {
        val room = _hostedRoom.value ?: return
        viewModelScope.launch {
            repository.updateRoomMetadata(
                roomId = room.id,
                roomName = roomName,
                status = status,
                arenaSize = arenaSize,
                includeBots = includeBots,
                botDifficulty = botDifficulty,
                playerCount = playerCount,
                tick = tick
            ).onSuccess {
                _hostedRoom.value = room.copy(
                    roomName = roomName,
                    status = status,
                    active = status != "closed",
                    arenaRadius = arenaSize.radius.toLong(),
                    includeBots = includeBots,
                    botDifficulty = botDifficulty.name,
                    playerCount = playerCount.toLong(),
                    tick = tick.toLong()
                )
            }
        }
    }

    private fun ensurePublishLoopRunning() {
        if (publishJob?.isActive == true) return
        publishJob = viewModelScope.launch(Dispatchers.IO) {
            for (pending in publishChannel) {
                val isFirst = !hasPublishedInitialState
                val deathDropCount = pending.snapshot.orbs.count { it.isDeathDrop }
                val orbCount = pending.snapshot.orbs.size
                val includeOrbs = isFirst ||
                    (publishSeqCount % 3 == 0) ||
                    kotlin.math.abs(orbCount - lastSentOrbCount) >= 3 ||
                    deathDropCount != lastSentDeathDropCount
                val includeKillFeed = isFirst || (pending.snapshot.killFeedText != lastSentKillFeed)
                val hasDeaths = pending.deathByUid.isNotEmpty()
                val includeDeaths = isFirst || hasDeaths || lastSentHadDeaths

                val res = repository.publishLiveState(
                    roomId = pending.roomId,
                    snapshot = pending.snapshot,
                    uidByWormId = pending.uidByWormId,
                    deathByUid = pending.deathByUid,
                    isInitialWrite = isFirst,
                    includeOrbs = includeOrbs,
                    includeKillFeed = includeKillFeed,
                    includeDeaths = includeDeaths
                )
                if (res.isSuccess) {
                    if (isFirst) hasPublishedInitialState = true
                    publishSeqCount++
                    if (includeOrbs) {
                        lastSentOrbCount = orbCount
                        lastSentDeathDropCount = deathDropCount
                    }
                    if (includeKillFeed) {
                        lastSentKillFeed = pending.snapshot.killFeedText
                    }
                    lastSentHadDeaths = hasDeaths
                }
                delay(45L)
            }
        }
    }

    fun publishLiveStateAsync(
        snapshot: ArenaSnapshot,
        uidByWormId: Map<Int, String>,
        deathByUid: Map<String, Pair<Int, DeathSummary>>
    ) {
        val roomId = _hostedRoom.value?.id ?: return
        if (snapshot.tick == lastPublishedTick) return
        lastPublishedTick = snapshot.tick
        ensurePublishLoopRunning()
        publishChannel.trySend(
            PendingLivePublish(
                roomId = roomId,
                snapshot = snapshot,
                uidByWormId = uidByWormId,
                deathByUid = deathByUid
            )
        )
    }

    fun joinRoom(
        roomId: String,
        playerName: String,
        skinId: Int,
        onJoined: (OnlineRoom) -> Unit
    ) {
        if (_isBusy.value) return
        viewModelScope.launch {
            _isBusy.value = true
            _actionError.value = null
            _serverClosedMessage.value = null
            lastSentRespawnSeq = 0L
            lastSentBoosting = false
            val res = repository.joinRoom(roomId, playerName, skinId)
            _isBusy.value = false
            res.onSuccess { room ->
                _joinedRoom.value = room
                _liveSnapshot.value = null
                lastServerPulseMs = android.os.SystemClock.uptimeMillis()
                startClientListeners(room.id)
                onJoined(room)
            }.onFailure { err ->
                _actionError.value = translateOnlineError(err, "Не удалось войти в комнату")
            }
        }
    }

    private fun startClientListeners(roomId: String) {
        lastServerPulseMs = android.os.SystemClock.uptimeMillis()

        clientRoomJob?.cancel()
        clientRoomJob = viewModelScope.launch {
            repository.observeRoomMeta(roomId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing room meta", firestoreError ?: error)
                    val msg = translateOnlineError(firestoreError ?: error, "Связь с сервером потеряна")
                    _actionError.value = msg
                    _serverClosedMessage.value = msg
                }
                .collectLatest { room ->
                    _joinedRoom.value = room
                    if (room == null || !room.active || room.status == "closed") {
                        _actionError.value = "Хост закрыл комнату"
                        _serverClosedMessage.value = "Сервер отключился — хост закрыл комнату"
                    } else {
                        lastServerPulseMs = android.os.SystemClock.uptimeMillis()
                    }
                }
        }

        clientLiveJob?.cancel()
        clientLiveJob = viewModelScope.launch {
            repository.observeLiveState(roomId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing live state", firestoreError ?: error)
                }
                .collectLatest { live ->
                    if (live != null) {
                        lastServerPulseMs = android.os.SystemClock.uptimeMillis()
                        _liveSnapshot.value = live
                    }
                }
        }

        clientWatchdogJob?.cancel()
        clientWatchdogJob = viewModelScope.launch {
            while (true) {
                delay(1500L)
                val room = _joinedRoom.value ?: continue
                if (!room.active || room.status == "closed") {
                    _serverClosedMessage.value = "Сервер отключился — хост закрыл комнату"
                    break
                }
                val timeoutMs = if (room.status == "playing") 12_000L else 22_000L
                val silentMs = android.os.SystemClock.uptimeMillis() - lastServerPulseMs
                if (silentMs > timeoutMs) {
                    _serverClosedMessage.value = "Сервер вырубился или потеряна связь с хостом"
                    _actionError.value = _serverClosedMessage.value
                    break
                }
            }
        }
    }

    private fun ensureOnlineInputSenderRunning() {
        if (inputJob?.isActive == true) return
        inputJob = viewModelScope.launch(Dispatchers.IO) {
            for (queued in clientInputChannel) {
                repository.sendPlayerInput(
                    roomId = queued.roomId,
                    name = queued.name,
                    skinId = queued.skinId,
                    targetAngle = queued.targetAngle,
                    boosting = queued.boosting,
                    respawnSeq = queued.respawnSeq
                )
                delay(40L)
            }
        }
    }

    fun sendClientInputThrottled(
        name: String,
        skinId: Int,
        targetAngle: Float,
        boosting: Boolean,
        respawnSeq: Long,
        forceImmediate: Boolean = false
    ) {
        val roomId = _joinedRoom.value?.id ?: return
        if (_serverClosedMessage.value != null) return
        val now = System.currentTimeMillis()
        var angleDiff = abs(targetAngle - lastSentAngle)
        if (angleDiff > PI) angleDiff = (2.0 * PI - angleDiff).toFloat()

        val urgentChange = forceImmediate ||
            boosting != lastSentBoosting ||
            respawnSeq != lastSentRespawnSeq
        if (!urgentChange) {
            if ((now - lastInputSentMs) < 55L) return
            if (angleDiff < 0.038f && (now - lastInputSentMs) < 220L) return
        }

        lastInputSentMs = now
        lastSentAngle = targetAngle
        lastSentBoosting = boosting
        lastSentRespawnSeq = respawnSeq

        ensureOnlineInputSenderRunning()
        clientInputChannel.trySend(
            QueuedOnlineInput(
                roomId = roomId,
                name = name,
                skinId = skinId,
                targetAngle = targetAngle,
                boosting = boosting,
                respawnSeq = respawnSeq
            )
        )
    }

    suspend fun cleanupSessionSuspend() {
        withContext(NonCancellable) {
            hostPlayersJob?.cancel()
            clientRoomJob?.cancel()
            clientLiveJob?.cancel()
            clientWatchdogJob?.cancel()
            publishJob?.cancel()
            inputJob?.cancel()

            val hosted = _hostedRoom.value
            if (hosted != null) {
                repository.closeRoom(hosted.id)
            }
            val joined = _joinedRoom.value
            if (joined != null) {
                repository.leaveRoomAsClient(joined.id)
            }

            _hostedRoom.value = null
            _hostPlayers.value = emptyList()
            _joinedRoom.value = null
            _liveSnapshot.value = null
            _serverClosedMessage.value = null
            hasPublishedInitialState = false
            lastPublishedTick = -1
            publishSeqCount = 0
            lastSentOrbCount = -1
            lastSentDeathDropCount = 0
            lastSentKillFeed = null
            lastSentHadDeaths = false
        }
    }

    fun cleanupSession() {
        viewModelScope.launch {
            cleanupSessionSuspend()
        }
    }

    override fun onCleared() {
        super.onCleared()
        hostPlayersJob?.cancel()
        clientRoomJob?.cancel()
        clientLiveJob?.cancel()
        clientWatchdogJob?.cancel()
        publishJob?.cancel()
        inputJob?.cancel()
        val hostedId = _hostedRoom.value?.id
        val joinedId = _joinedRoom.value?.id
        if (hostedId != null || joinedId != null) {
            kotlinx.coroutines.CoroutineScope(NonCancellable).launch {
                if (hostedId != null) repository.closeRoom(hostedId)
                if (joinedId != null) repository.leaveRoomAsClient(joinedId)
            }
        }
    }

    private companion object {
        const val TAG = "OnlineVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
