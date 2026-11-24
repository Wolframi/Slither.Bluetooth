package com.example.online

import android.content.Context
import com.example.R
import com.example.game.ArenaSizeOption
import com.example.game.ArenaSnapshot
import com.example.game.BotDifficulty
import com.example.game.DeathSummary
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

fun configureFastOnlineFirestore(db: FirebaseFirestore): FirebaseFirestore {
    runCatching {
        db.firestoreSettings = firestoreSettings {
            setLocalCacheSettings(memoryCacheSettings {})
        }
    }
    return db
}

fun translateOnlineError(error: Throwable, fallback: String): String {
    if (error is TimeoutCancellationException) {
        return "Сервер не отвечает (таймаут). Проверьте интернет — в РФ может потребоваться стабильный DNS или VPN."
    }
    val fsErr = generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    return when (fsErr?.code) {
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> {
            "Нет связи с сервером. Проверьте интернет (в РФ доступ к облаку может требовать смены сети или VPN)."
        }
        FirebaseFirestoreException.Code.PERMISSION_DENIED -> {
            "Доступ запрещён — войдите в Google-аккаунт заново."
        }
        FirebaseFirestoreException.Code.NOT_FOUND -> {
            "Комната больше не существует — сервер был закрыт."
        }
        else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
    }
}

class OnlineGameRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        check(!uid.isNullOrBlank()) {
            "User must be signed in with Google before accessing Firestore."
        }
        return uid
    }

    fun observeActiveRooms(): Flow<List<OnlineRoom>> = flow {
        val uid = requireUserId()
        val path = "rooms"
        emitAll(
            db.collection(path)
                .whereEqualTo("active", true)
                .snapshots()
                .map { querySnapshot ->
                    val nowMs = System.currentTimeMillis()
                    querySnapshot.toObjects(
                        OnlineRoom::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).filter { room ->
                        val updatedMs = room.updatedAt?.toDate()?.time
                        if (updatedMs == null) {
                            true
                        } else {
                            val ageMs = nowMs - updatedMs
                            // Hide abandoned rooms whose host stopped heartbeating (>45s ago)
                            ageMs < 45_000L || ageMs > 86_400_000L
                        }
                    }.sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeRoomMeta(roomId: String): Flow<OnlineRoom?> = flow {
        val uid = requireUserId()
        require(roomId.isNotBlank()) { "Room ID must not be blank" }
        val path = "rooms/$roomId"
        emitAll(
            db.collection("rooms")
                .document(roomId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(
                            OnlineRoom::class.java,
                            DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                        )
                    } else {
                        null
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeRoomPlayers(roomId: String, hostUid: String): Flow<List<OnlinePlayerInput>> = flow {
        val uid = requireUserId()
        require(roomId.isNotBlank()) { "Room ID must not be blank" }
        val path = "rooms/$roomId/players"
        emitAll(
            db.collection("rooms")
                .document(roomId)
                .collection("players")
                .whereEqualTo("hostUid", hostUid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        OnlinePlayerInput::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeLiveState(roomId: String): Flow<OnlineLiveSnapshot?> = flow {
        val uid = requireUserId()
        require(roomId.isNotBlank()) { "Room ID must not be blank" }
        val path = "rooms/$roomId/state/live"
        emitAll(
            db.collection("rooms")
                .document(roomId)
                .collection("state")
                .document("live")
                .snapshots()
                .map { snapshot -> OnlineStateCodec.decodeLiveSnapshot(snapshot) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createRoom(
        roomName: String,
        hostName: String,
        arenaSize: ArenaSizeOption,
        includeBots: Boolean,
        botDifficulty: BotDifficulty
    ): Result<OnlineRoom> {
        var docPath = "rooms"
        return try {
            val uid = requireUserId()
            val safeHostName = hostName.trim().take(24).ifEmpty { "Хост" }
            val safeRoomName = roomName.trim().take(36).ifEmpty { "Арена $safeHostName" }
                .let { if (it.length < 2) "$it-1" else it }
            val roomId = "room_${UUID.randomUUID().toString().replace("-", "").take(16)}"
            val docRef = db.collection("rooms").document(roomId)
            docPath = docRef.path

            val payload = mapOf(
                "hostUid" to uid,
                "hostName" to safeHostName,
                "roomName" to safeRoomName,
                "status" to "waiting",
                "active" to true,
                "arenaRadius" to arenaSize.radius.toLong(),
                "includeBots" to includeBots,
                "botDifficulty" to botDifficulty.name,
                "playerCount" to 1L,
                "maxPlayers" to 12L,
                "tick" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            withTimeout(9_000L) {
                docRef.set(payload).await()
            }

            Result.success(
                OnlineRoom(
                    id = roomId,
                    hostUid = uid,
                    hostName = safeHostName,
                    roomName = safeRoomName,
                    status = "waiting",
                    active = true,
                    arenaRadius = arenaSize.radius.toLong(),
                    includeBots = includeBots,
                    botDifficulty = botDifficulty.name,
                    playerCount = 1L,
                    maxPlayers = 12L,
                    tick = 0L
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateRoomMetadata(
        roomId: String,
        roomName: String,
        status: String,
        arenaSize: ArenaSizeOption,
        includeBots: Boolean,
        botDifficulty: BotDifficulty,
        playerCount: Int,
        tick: Int
    ): Result<Unit> {
        val docPath = "rooms/$roomId"
        return try {
            requireUserId()
            require(roomId.isNotBlank()) { "Room ID must not be blank" }
            val safeRoomName = roomName.trim().take(36).ifEmpty { "Онлайн-арена" }
                .let { if (it.length < 2) "$it-1" else it }
            val active = status != "closed"
            withTimeout(8_000L) {
                db.collection("rooms").document(roomId).update(
                    mapOf(
                        "roomName" to safeRoomName,
                        "status" to status,
                        "active" to active,
                        "arenaRadius" to arenaSize.radius.toLong(),
                        "includeBots" to includeBots,
                        "botDifficulty" to botDifficulty.name,
                        "playerCount" to playerCount.coerceIn(1, 16).toLong(),
                        "tick" to tick.coerceAtLeast(0).toLong(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun closeRoom(roomId: String): Result<Unit> = withContext(NonCancellable) {
        val docPath = "rooms/$roomId"
        try {
            if (auth.currentUser?.uid.isNullOrBlank() || roomId.isBlank()) {
                return@withContext Result.success(Unit)
            }
            db.collection("rooms").document(roomId).update(
                mapOf(
                    "status" to "closed",
                    "active" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun publishLiveState(
        roomId: String,
        snapshot: ArenaSnapshot,
        uidByWormId: Map<Int, String>,
        deathByUid: Map<String, Pair<Int, DeathSummary>>,
        isInitialWrite: Boolean,
        includeOrbs: Boolean = true,
        includeKillFeed: Boolean = true,
        includeDeaths: Boolean = true
    ): Result<Unit> {
        val docPath = "rooms/$roomId/state/live"
        return try {
            val uid = requireUserId()
            require(roomId.isNotBlank()) { "Room ID must not be blank" }
            val payload = OnlineStateCodec.encodeLivePayload(
                hostUid = uid,
                snapshot = snapshot,
                uidByWormId = uidByWormId,
                deathByUid = deathByUid
            ).toMutableMap()
            payload["updatedAt"] = FieldValue.serverTimestamp()

            val stateRef = db.collection("rooms").document(roomId)
                .collection("state").document("live")

            withTimeout(4_500L) {
                if (isInitialWrite) {
                    stateRef.set(payload).await()
                } else {
                    payload.remove("hostUid")
                    payload.remove("arenaRadius")
                    if (!includeOrbs) payload.remove("orbs")
                    if (!includeKillFeed) payload.remove("killFeed")
                    if (!includeDeaths) payload.remove("deaths")
                    stateRef.update(payload).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun joinRoom(
        roomId: String,
        playerName: String,
        skinId: Int
    ): Result<OnlineRoom> {
        var docPath = "rooms/$roomId"
        return try {
            val uid = requireUserId()
            require(roomId.isNotBlank()) { "Room ID must not be blank" }
            val roomSnap = withTimeout(9_000L) {
                db.collection("rooms").document(roomId).get(Source.SERVER).await()
            }
            val room = roomSnap.toObject(
                OnlineRoom::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: error("Комната не найдена — возможно, сервер уже закрыт")

            if (!room.active || room.status == "closed") {
                error("Сервер уже закрыт хостом")
            }
            if (room.playerCount >= room.maxPlayers) {
                error("Комната заполнена (${room.playerCount}/${room.maxPlayers})")
            }

            val playerRef = db.collection("rooms").document(roomId)
                .collection("players").document(uid)
            docPath = playerRef.path
            val safeName = playerName.trim().take(24).ifEmpty { "Игрок" }

            withTimeout(9_000L) {
                playerRef.set(
                    mapOf(
                        "uid" to uid,
                        "hostUid" to room.hostUid,
                        "name" to safeName,
                        "skinId" to skinId.coerceIn(0, 15).toLong(),
                        "targetAngle" to 0.0,
                        "boosting" to false,
                        "respawnSeq" to 0L,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            Result.success(room)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun sendPlayerInput(
        roomId: String,
        name: String,
        skinId: Int,
        targetAngle: Float,
        boosting: Boolean,
        respawnSeq: Long
    ): Result<Unit> {
        var docPath = "rooms/$roomId/players"
        return try {
            val uid = requireUserId()
            require(roomId.isNotBlank()) { "Room ID must not be blank" }
            val playerRef = db.collection("rooms").document(roomId)
                .collection("players").document(uid)
            docPath = playerRef.path
            val safeName = name.trim().take(24).ifEmpty { "Игрок" }
            val safeAngle = if (targetAngle.isFinite()) targetAngle.toDouble().coerceIn(-7.0, 7.0) else 0.0
            withTimeout(4_500L) {
                playerRef.update(
                    mapOf(
                        "name" to safeName,
                        "skinId" to skinId.coerceIn(0, 15).toLong(),
                        "targetAngle" to safeAngle,
                        "boosting" to boosting,
                        "respawnSeq" to respawnSeq.coerceIn(0L, 1_000_000L),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun leaveRoomAsClient(roomId: String): Result<Unit> = withContext(NonCancellable) {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank() || roomId.isBlank()) {
            return@withContext Result.success(Unit)
        }
        val docPath = "rooms/$roomId/players/$uid"
        try {
            db.collection("rooms").document(roomId)
                .collection("players").document(uid)
                .delete()
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
