package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.game.ControlMode
import com.example.game.GameMode
import com.example.online.OnlineGameRepository
import com.example.online.OnlineUiState
import com.example.online.OnlineViewModel
import com.example.online.authStateFlow
import com.example.online.configureFastOnlineFirestore
import com.example.online.onGoogleSignInClicked
import com.example.online.signOutFromGoogle
import kotlinx.coroutines.isActive
import com.example.ui.AppScreen
import com.example.ui.GameViewModel
import com.example.ui.screens.ArenaScreen
import com.example.ui.screens.BtHostSetupScreen
import com.example.ui.screens.BtJoinBrowserScreen
import com.example.ui.screens.LaunchSplashScreen
import com.example.ui.screens.LobbyScreen
import com.example.ui.screens.OnlineHostSetupScreen
import com.example.ui.screens.OnlineJoinBrowserScreen
import com.example.ui.screens.OnlineWaitingRoomScreen
import com.example.ui.theme.SlitherBtTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SlitherBtTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val auth = Firebase.auth
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    var showSplash by rememberSaveable { mutableStateOf(true) }
    var isSigningIn by remember { mutableStateOf(false) }
    var authErrorMessage by remember { mutableStateOf<String?>(null) }

    val startGoogleSignIn: () -> Unit = {
        if (!isSigningIn) {
            isSigningIn = true
            authErrorMessage = null
            onGoogleSignInClicked(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                    isSigningIn = false
                    authErrorMessage = null
                },
                onAuthError = { msg ->
                    isSigningIn = false
                    authErrorMessage = msg
                },
                scope = scope,
                onAuthCancelled = {
                    isSigningIn = false
                }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val user = currentUser
        if (user != null) {
            key(user.uid) {
                AuthenticatedAppContent(
                    currentUserId = user.uid,
                    accountLabel = user.displayName ?: user.email ?: "Google",
                    onRequestSignIn = startGoogleSignIn
                )
            }
        } else {
            OfflineOnlyAppContent(
                isSigningIn = isSigningIn,
                authErrorMessage = authErrorMessage,
                onRequestSignIn = startGoogleSignIn
            )
        }

        AnimatedVisibility(
            visible = showSplash,
            exit = fadeOut(animationSpec = tween(420)) +
                scaleOut(targetScale = 1.06f, animationSpec = tween(420))
        ) {
            LaunchSplashScreen(
                onFinished = { showSplash = false }
            )
        }
    }
}

@Composable
private fun AuthenticatedAppContent(
    currentUserId: String,
    accountLabel: String,
    onRequestSignIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: GameViewModel = viewModel()
    val onlineVm: OnlineViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = configureFastOnlineFirestore(FirebaseFirestore.getInstance(databaseId))
                OnlineViewModel(OnlineGameRepository(db), currentUserId)
            }
        }
    )

    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val btState by viewModel.bluetoothState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val activeRoomsState by onlineVm.activeRooms.collectAsStateWithLifecycle()
    val hostedRoom by onlineVm.hostedRoom.collectAsStateWithLifecycle()
    val hostPlayers by onlineVm.hostPlayers.collectAsStateWithLifecycle()
    val joinedRoom by onlineVm.joinedRoom.collectAsStateWithLifecycle()
    val liveSnapshot by onlineVm.liveSnapshot.collectAsStateWithLifecycle()
    val onlineError by onlineVm.actionError.collectAsStateWithLifecycle()
    val onlineServerClosed by onlineVm.serverClosedMessage.collectAsStateWithLifecycle()
    val isOnlineBusy by onlineVm.isBusy.collectAsStateWithLifecycle()

    var roomNameInput by rememberSaveable(currentUserId) {
        mutableStateOf("Арена ${profile.nickname}")
    }

    val activeRoomsCount = (activeRoomsState as? OnlineUiState.Success)?.data
        ?.count { it.hostUid != currentUserId } ?: 0

    // Keep host engine synchronized with remote Firestore players
    LaunchedEffect(hostPlayers, uiState.gameMode, uiState.screen) {
        if (uiState.gameMode == GameMode.ONLINE_HOST &&
            (uiState.screen == AppScreen.OnlineHostSetup || uiState.screen == AppScreen.Playing)
        ) {
            viewModel.syncOnlinePlayersToHostEngine(hostPlayers)
        }
    }

    // Feed host room metadata updates (room name, player count & settings) while hosting
    LaunchedEffect(
        hostedRoom?.id,
        roomNameInput,
        hostPlayers.size,
        uiState.arenaSize,
        profile.hostIncludeBots,
        profile.botDifficulty
    ) {
        val room = hostedRoom ?: return@LaunchedEffect
        val trimmedName = roomNameInput.trim()
        if (trimmedName.length < 2) return@LaunchedEffect
        if (uiState.gameMode == GameMode.ONLINE_HOST && uiState.screen == AppScreen.OnlineHostSetup) {
            kotlinx.coroutines.delay(250L)
            onlineVm.updateHostRoomSettings(
                roomName = trimmedName,
                status = room.status,
                arenaSize = uiState.arenaSize,
                includeBots = profile.hostIncludeBots,
                botDifficulty = profile.botDifficulty,
                playerCount = hostPlayers.size + 1,
                tick = room.tick.toInt()
            )
        }
    }

    // Periodic heartbeat while waiting on OnlineHostSetup so server stays visible and clients know host is alive
    LaunchedEffect(hostedRoom?.id, uiState.screen) {
        if (uiState.gameMode == GameMode.ONLINE_HOST && uiState.screen == AppScreen.OnlineHostSetup) {
            while (isActive) {
                kotlinx.coroutines.delay(4_000L)
                val room = hostedRoom ?: break
                onlineVm.updateHostRoomSettings(
                    roomName = roomNameInput,
                    status = room.status,
                    arenaSize = uiState.arenaSize,
                    includeBots = profile.hostIncludeBots,
                    botDifficulty = profile.botDifficulty,
                    playerCount = hostPlayers.size + 1,
                    tick = room.tick.toInt()
                )
            }
        }
    }

    // Feed client live snapshots & room status from Firestore to GameViewModel
    LaunchedEffect(joinedRoom?.status, liveSnapshot, uiState.gameMode) {
        if (uiState.gameMode == GameMode.ONLINE_CLIENT) {
            viewModel.onOnlineClientStateReceived(
                roomStatus = joinedRoom?.status,
                live = liveSnapshot,
                myUid = currentUserId
            )
        }
    }

    when (uiState.screen) {
        AppScreen.Lobby -> {
            LobbyScreen(
                profile = profile,
                arenaSize = uiState.arenaSize,
                isSignedIn = true,
                accountLabel = accountLabel,
                activeOnlineRoomsCount = activeRoomsCount,
                onNicknameChanged = viewModel::updateNickname,
                onSkinSelected = viewModel::selectSkin,
                onBotDifficultySelected = viewModel::selectBotDifficulty,
                onArenaSizeSelected = viewModel::setArenaSize,
                onControlModeSelected = viewModel::selectControlMode,
                onHostOnlineClick = {
                    roomNameInput = "Арена ${profile.nickname}"
                    viewModel.openOnlineHostSetup()
                    onlineVm.openOrCreateHostRoom(
                        roomName = roomNameInput,
                        hostName = profile.nickname,
                        arenaSize = uiState.arenaSize,
                        includeBots = profile.hostIncludeBots,
                        botDifficulty = profile.botDifficulty
                    )
                },
                onJoinOnlineClick = {
                    onlineVm.clearError()
                    viewModel.openOnlineJoinBrowser()
                },
                onHostBluetoothClick = viewModel::openHostSetup,
                onJoinBluetoothClick = viewModel::openJoinBrowser,
                onSoloBotsClick = viewModel::startSoloBotMatch,
                onSignInClick = onRequestSignIn,
                onSignOutClick = {
                    viewModel.returnToLobby()
                    signOutFromGoogle(
                        credentialManager = credentialManager,
                        scope = scope,
                        beforeFirebaseSignOut = { onlineVm.cleanupSessionSuspend() }
                    )
                }
            )
        }

        AppScreen.OnlineHostSetup -> {
            OnlineHostSetupScreen(
                room = hostedRoom,
                roomName = roomNameInput,
                connectedPlayers = hostPlayers,
                arenaSize = uiState.arenaSize,
                includeBots = profile.hostIncludeBots,
                isBusy = isOnlineBusy,
                errorMessage = onlineError,
                onRoomNameChanged = { updated ->
                    roomNameInput = updated.take(36)
                },
                onSelectArenaSize = viewModel::setArenaSize,
                onToggleBots = viewModel::toggleHostBots,
                onStartMatch = {
                    onlineVm.updateHostRoomSettings(
                        roomName = roomNameInput,
                        status = "playing",
                        arenaSize = uiState.arenaSize,
                        includeBots = profile.hostIncludeBots,
                        botDifficulty = profile.botDifficulty,
                        playerCount = hostPlayers.size + 1,
                        tick = 0
                    )
                    viewModel.startOnlineHostMatch(
                        hostUid = currentUserId,
                        remotePlayers = hostPlayers,
                        onPublishLive = { snap, uidByWormId, deaths ->
                            onlineVm.publishLiveStateAsync(snap, uidByWormId, deaths)
                        },
                        onUpdateRoomTick = { playerCount, tick ->
                            onlineVm.updateHostRoomSettings(
                                roomName = roomNameInput,
                                status = "playing",
                                arenaSize = uiState.arenaSize,
                                includeBots = profile.hostIncludeBots,
                                botDifficulty = profile.botDifficulty,
                                playerCount = playerCount,
                                tick = tick
                            )
                        }
                    )
                },
                onBack = {
                    onlineVm.cleanupSession()
                    viewModel.returnToLobby()
                }
            )
        }

        AppScreen.OnlineJoinBrowser -> {
            OnlineJoinBrowserScreen(
                roomsState = activeRoomsState,
                myUid = currentUserId,
                isConnecting = isOnlineBusy,
                errorMessage = onlineError,
                onRoomClick = { room ->
                    viewModel.setOnlineInputSender { angle, boosting, respawnSeq, force ->
                        onlineVm.sendClientInputThrottled(
                            name = profile.nickname,
                            skinId = profile.skinId,
                            targetAngle = angle,
                            boosting = boosting,
                            respawnSeq = respawnSeq,
                            forceImmediate = force
                        )
                    }
                    onlineVm.joinRoom(
                        roomId = room.id,
                        playerName = profile.nickname,
                        skinId = profile.skinId,
                        onJoined = { joined ->
                            viewModel.onOnlineClientJoinedRoom(joined.status)
                        }
                    )
                },
                onClearError = onlineVm::clearError,
                onBack = {
                    onlineVm.cleanupSession()
                    viewModel.returnToLobby()
                }
            )
        }

        AppScreen.OnlineWaitingRoom -> {
            OnlineWaitingRoomScreen(
                room = joinedRoom,
                errorMessage = onlineError ?: onlineServerClosed,
                onLeave = {
                    onlineVm.cleanupSession()
                    viewModel.returnToLobby()
                }
            )
        }

        AppScreen.BtHostSetup -> {
            BtHostSetupScreen(
                btState = btState,
                requiredPermissions = viewModel.btManager.requiredPermissions(),
                arenaSize = uiState.arenaSize,
                includeBots = profile.hostIncludeBots,
                onRefreshBt = viewModel::retryHostServerIfReady,
                onSelectArenaSize = viewModel::setArenaSize,
                onToggleBots = viewModel::toggleHostBots,
                onStartMatch = viewModel::startHostArenaMatch,
                onBack = viewModel::returnToLobby
            )
        }

        AppScreen.BtJoinBrowser -> {
            BtJoinBrowserScreen(
                btState = btState,
                requiredPermissions = viewModel.btManager.requiredPermissions(),
                onRefreshBt = { viewModel.btManager.refreshState() },
                onStartScan = { viewModel.btManager.startDiscovery() },
                onStopScan = { viewModel.btManager.stopDiscovery() },
                onDeviceClick = viewModel::connectToBtHost,
                onClearError = { viewModel.btManager.clearError() },
                onBack = viewModel::returnToLobby
            )
        }

        AppScreen.Playing -> {
            val snapshotState = viewModel.snapshot.collectAsStateWithLifecycle()
            val steeringAngleState = viewModel.steeringAngle.collectAsStateWithLifecycle()
            val remotePlayersCount = when (uiState.gameMode) {
                GameMode.ONLINE_HOST -> hostPlayers.size
                GameMode.BT_HOST -> btState.connectedClients.size
                else -> 0
            }
            ArenaScreen(
                snapshotState = snapshotState,
                localWormId = uiState.localWormId,
                gameMode = uiState.gameMode,
                controlMode = profile.controlMode,
                connectedBtCount = remotePlayersCount,
                steeringAngleState = steeringAngleState,
                isBoosting = uiState.isBoosting,
                deathSummary = uiState.deathSummary,
                serverDisconnectMessage = onlineServerClosed ?: uiState.serverDisconnectMessage,
                onSteeringChanged = viewModel::setSteeringAngle,
                onBoostChanged = viewModel::setBoosting,
                onToggleControlMode = {
                    val next = if (profile.controlMode == ControlMode.TOUCH_FOLLOW) {
                        ControlMode.JOYSTICK
                    } else {
                        ControlMode.TOUCH_FOLLOW
                    }
                    viewModel.selectControlMode(next)
                },
                onRespawnClick = viewModel::respawnLocalPlayer,
                onExitToLobby = {
                    if (uiState.gameMode == GameMode.ONLINE_HOST || uiState.gameMode == GameMode.ONLINE_CLIENT) {
                        onlineVm.cleanupSession()
                    }
                    viewModel.returnToLobby()
                }
            )
        }
    }
}

@Composable
private fun OfflineOnlyAppContent(
    isSigningIn: Boolean,
    authErrorMessage: String?,
    onRequestSignIn: () -> Unit
) {
    val viewModel: GameViewModel = viewModel()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val btState by viewModel.bluetoothState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (uiState.screen) {
        AppScreen.Lobby,
        AppScreen.OnlineHostSetup,
        AppScreen.OnlineJoinBrowser,
        AppScreen.OnlineWaitingRoom -> {
            LobbyScreen(
                profile = profile,
                arenaSize = uiState.arenaSize,
                isSignedIn = false,
                accountLabel = null,
                activeOnlineRoomsCount = 0,
                isSigningIn = isSigningIn,
                authErrorMessage = authErrorMessage,
                onNicknameChanged = viewModel::updateNickname,
                onSkinSelected = viewModel::selectSkin,
                onBotDifficultySelected = viewModel::selectBotDifficulty,
                onArenaSizeSelected = viewModel::setArenaSize,
                onControlModeSelected = viewModel::selectControlMode,
                onHostOnlineClick = onRequestSignIn,
                onJoinOnlineClick = onRequestSignIn,
                onHostBluetoothClick = viewModel::openHostSetup,
                onJoinBluetoothClick = viewModel::openJoinBrowser,
                onSoloBotsClick = viewModel::startSoloBotMatch,
                onSignInClick = onRequestSignIn,
                onSignOutClick = onRequestSignIn
            )
        }

        AppScreen.BtHostSetup -> {
            BtHostSetupScreen(
                btState = btState,
                requiredPermissions = viewModel.btManager.requiredPermissions(),
                arenaSize = uiState.arenaSize,
                includeBots = profile.hostIncludeBots,
                onRefreshBt = viewModel::retryHostServerIfReady,
                onSelectArenaSize = viewModel::setArenaSize,
                onToggleBots = viewModel::toggleHostBots,
                onStartMatch = viewModel::startHostArenaMatch,
                onBack = viewModel::returnToLobby
            )
        }

        AppScreen.BtJoinBrowser -> {
            BtJoinBrowserScreen(
                btState = btState,
                requiredPermissions = viewModel.btManager.requiredPermissions(),
                onRefreshBt = { viewModel.btManager.refreshState() },
                onStartScan = { viewModel.btManager.startDiscovery() },
                onStopScan = { viewModel.btManager.stopDiscovery() },
                onDeviceClick = viewModel::connectToBtHost,
                onClearError = { viewModel.btManager.clearError() },
                onBack = viewModel::returnToLobby
            )
        }

        AppScreen.Playing -> {
            val snapshotState = viewModel.snapshot.collectAsStateWithLifecycle()
            val steeringAngleState = viewModel.steeringAngle.collectAsStateWithLifecycle()
            ArenaScreen(
                snapshotState = snapshotState,
                localWormId = uiState.localWormId,
                gameMode = uiState.gameMode,
                controlMode = profile.controlMode,
                connectedBtCount = btState.connectedClients.size,
                steeringAngleState = steeringAngleState,
                isBoosting = uiState.isBoosting,
                deathSummary = uiState.deathSummary,
                serverDisconnectMessage = uiState.serverDisconnectMessage,
                onSteeringChanged = viewModel::setSteeringAngle,
                onBoostChanged = viewModel::setBoosting,
                onToggleControlMode = {
                    val next = if (profile.controlMode == ControlMode.TOUCH_FOLLOW) {
                        ControlMode.JOYSTICK
                    } else {
                        ControlMode.TOUCH_FOLLOW
                    }
                    viewModel.selectControlMode(next)
                },
                onRespawnClick = viewModel::respawnLocalPlayer,
                onExitToLobby = viewModel::returnToLobby
            )
        }
    }
}
