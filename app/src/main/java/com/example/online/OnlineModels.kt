package com.example.online

import androidx.annotation.Keep
import com.example.game.ArenaEngine
import com.example.game.ArenaSnapshot
import com.example.game.BotDifficulty
import com.example.game.DeathSummary
import com.example.game.FoodOrb
import com.example.game.LeaderboardEntry
import com.example.game.Vec2
import com.example.game.WormState
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Keep
@IgnoreExtraProperties
data class OnlineRoom(
    @DocumentId val id: String = "",
    val hostUid: String = "",
    val hostName: String = "",
    val roomName: String = "",
    val status: String = "waiting",
    val active: Boolean = true,
    val arenaRadius: Long = 2200L,
    val includeBots: Boolean = true,
    val botDifficulty: String = BotDifficulty.BALANCED.name,
    val playerCount: Long = 1L,
    val maxPlayers: Long = 12L,
    val tick: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun botDifficultyOrDefault(): BotDifficulty =
        enumValues<BotDifficulty>().firstOrNull { it.name == botDifficulty } ?: BotDifficulty.BALANCED
}

@Keep
@IgnoreExtraProperties
data class OnlinePlayerInput(
    @DocumentId val id: String = "",
    val uid: String = "",
    val hostUid: String = "",
    val name: String = "",
    val skinId: Long = 0L,
    val targetAngle: Double = 0.0,
    val boosting: Boolean = false,
    val respawnSeq: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class OnlineLiveSnapshot(
    val snapshot: ArenaSnapshot,
    val wormIdByUid: Map<String, Int>,
    val deathByUid: Map<String, DeathSummary>
)

object OnlineStateCodec {
    private const val MAX_PACKED_ORBS = 185
    private const val MAX_SENT_WORMS = 16
    private const val MAX_POLYLINE_SAMPLES = 18

    fun encodeLivePayload(
        hostUid: String,
        snapshot: ArenaSnapshot,
        uidByWormId: Map<Int, String>,
        deathByUid: Map<String, Pair<Int, DeathSummary>>
    ): Map<String, Any> {
        val activeWorms = snapshot.worms
            .filter { it.isAlive || !it.isBot || uidByWormId.containsKey(it.id) }
            .take(MAX_SENT_WORMS)

        val wormMaps = activeWorms.map { w ->
            val pts = if (w.isAlive) compressSegments(w.segments) else emptyList()
            mapOf(
                "id" to w.id.toLong(),
                "uid" to (uidByWormId[w.id] ?: ""),
                "name" to w.name.take(16),
                "skinId" to w.skinId.toLong(),
                "bot" to w.isBot,
                "alive" to w.isAlive,
                "shield" to w.isSpawnProtected,
                "x" to (w.headX * 10f).roundToInt() / 10.0,
                "y" to (w.headY * 10f).roundToInt() / 10.0,
                "a" to (w.angle * 1000f).roundToInt() / 1000.0,
                "ta" to (w.targetAngle * 1000f).roundToInt() / 1000.0,
                "s" to w.score.toLong(),
                "k" to w.kills.toLong(),
                "b" to w.isBoosting,
                "r" to (w.radius * 10f).roundToInt() / 10.0,
                "pts" to pts
            )
        }

        val orbsToPack = if (snapshot.orbs.size <= MAX_PACKED_ORBS) {
            snapshot.orbs
        } else {
            val humanHeads = activeWorms.filter { !it.isBot && it.isAlive }
            snapshot.orbs.sortedByDescending { orb ->
                var priority = if (orb.isDeathDrop) 2_400f else orb.value * 80f
                for (h in humanHeads) {
                    val dx = orb.x - h.headX
                    val dy = orb.y - h.headY
                    val dSq = dx * dx + dy * dy
                    if (dSq < 2_250_000f) {
                        priority += 8_000f - (dSq * 0.0032f)
                    }
                }
                priority
            }.take(MAX_PACKED_ORBS)
        }

        val packedOrbs = ArrayList<Long>(orbsToPack.size * 5)
        for (o in orbsToPack) {
            val colorIdx = o.colorIndex.coerceIn(0, 15)
            val radDeci = (o.radius * 10f).roundToInt().coerceIn(1, 255)
            val dropBit = if (o.isDeathDrop) 1 else 0
            val flags = (colorIdx or (radDeci shl 4) or (dropBit shl 12)).toLong()
            packedOrbs.add(o.id.toLong())
            packedOrbs.add(o.x.roundToLong())
            packedOrbs.add(o.y.roundToLong())
            packedOrbs.add(o.value.toLong())
            packedOrbs.add(flags)
        }

        val deathList = deathByUid.entries.take(16).map { (uid, pair) ->
            val (wormId, summary) = pair
            mapOf(
                "uid" to uid,
                "wormId" to wormId.toLong(),
                "finalScore" to summary.finalScore.toLong(),
                "kills" to summary.kills.toLong(),
                "survivalSeconds" to summary.survivalSeconds.toLong(),
                "killerName" to (summary.killerName ?: "").take(24),
                "hitBoundary" to summary.hitBoundary
            )
        }

        return mapOf(
            "hostUid" to hostUid,
            "tick" to snapshot.tick.toLong(),
            "arenaRadius" to snapshot.arenaRadius.toDouble(),
            "killFeed" to (snapshot.killFeedText ?: "").take(80),
            "worms" to wormMaps,
            "orbs" to packedOrbs,
            "deaths" to deathList
        )
    }

    fun decodeLiveSnapshot(doc: DocumentSnapshot): OnlineLiveSnapshot? {
        if (!doc.exists()) return null
        val data = doc.data ?: return null

        val tick = (data["tick"] as? Number)?.toInt() ?: 0
        val arenaRadius = (data["arenaRadius"] as? Number)?.toFloat() ?: 2200f
        val killFeed = (data["killFeed"] as? String)?.ifBlank { null }

        val wormIdByUid = HashMap<String, Int>()
        val worms = ArrayList<WormState>()
        val rawWorms = data["worms"] as? List<*> ?: emptyList<Any>()
        for (item in rawWorms) {
            val map = item as? Map<*, *> ?: continue
            val id = (map["id"] as? Number)?.toInt() ?: continue
            val uid = map["uid"] as? String ?: ""
            if (uid.isNotBlank()) {
                wormIdByUid[uid] = id
            }
            val name = map["name"] as? String ?: "Игрок"
            val skinId = (map["skinId"] as? Number)?.toInt() ?: 0
            val isBot = map["bot"] as? Boolean ?: false
            val isAlive = map["alive"] as? Boolean ?: true
            val isShield = map["shield"] as? Boolean ?: false
            val headX = (map["x"] as? Number)?.toFloat() ?: 0f
            val headY = (map["y"] as? Number)?.toFloat() ?: 0f
            val angle = (map["a"] as? Number)?.toFloat() ?: 0f
            val targetAngle = (map["ta"] as? Number)?.toFloat() ?: angle
            val score = (map["s"] as? Number)?.toInt() ?: ArenaEngine.INITIAL_SCORE
            val kills = (map["k"] as? Number)?.toInt() ?: 0
            val isBoosting = map["b"] as? Boolean ?: false
            val radius = (map["r"] as? Number)?.toFloat() ?: ArenaEngine.computeWormRadius(score)

            val rawPts = map["pts"] as? List<*> ?: emptyList<Any>()
            val polyline = ArrayList<Vec2>(rawPts.size / 2 + 1)
            var p = 0
            while (p + 1 < rawPts.size) {
                val px = (rawPts[p] as? Number)?.toFloat() ?: headX
                val py = (rawPts[p + 1] as? Number)?.toFloat() ?: headY
                polyline.add(Vec2(px, py))
                p += 2
            }
            val segments = if (isAlive) {
                expandPolyline(
                    polyline = polyline,
                    head = Vec2(headX, headY),
                    targetCount = ArenaEngine.computeSegmentCount(score),
                    spacing = radius * 0.45f
                )
            } else {
                emptyList()
            }

            worms.add(
                WormState(
                    id = id,
                    name = name,
                    skinId = skinId,
                    isBot = isBot,
                    isAlive = isAlive,
                    isSpawnProtected = isShield,
                    headX = headX,
                    headY = headY,
                    angle = angle,
                    targetAngle = targetAngle,
                    score = score,
                    kills = kills,
                    isBoosting = isBoosting,
                    radius = radius,
                    segments = segments
                )
            )
        }

        val rawOrbs = data["orbs"] as? List<*> ?: emptyList<Any>()
        val orbs = ArrayList<FoodOrb>(rawOrbs.size / 5)
        var i = 0
        while (i + 4 < rawOrbs.size) {
            val id = (rawOrbs[i] as? Number)?.toInt() ?: 0
            val x = (rawOrbs[i + 1] as? Number)?.toFloat() ?: 0f
            val y = (rawOrbs[i + 2] as? Number)?.toFloat() ?: 0f
            val value = (rawOrbs[i + 3] as? Number)?.toInt() ?: 1
            val flags = (rawOrbs[i + 4] as? Number)?.toInt() ?: 0
            val colorIndex = flags and 0xF
            val radius = ((flags ushr 4) and 0xFF).coerceAtLeast(10) / 10f
            val isDeathDrop = ((flags ushr 12) and 1) == 1
            orbs.add(
                FoodOrb(
                    id = id,
                    x = x,
                    y = y,
                    value = value,
                    radius = radius,
                    colorIndex = colorIndex,
                    isDeathDrop = isDeathDrop
                )
            )
            i += 5
        }

        val deathByUid = HashMap<String, DeathSummary>()
        val rawDeaths = data["deaths"] as? List<*> ?: emptyList<Any>()
        for (item in rawDeaths) {
            val map = item as? Map<*, *> ?: continue
            val uid = map["uid"] as? String ?: continue
            val killerName = (map["killerName"] as? String)?.ifBlank { null }
            deathByUid[uid] = DeathSummary(
                finalScore = (map["finalScore"] as? Number)?.toInt() ?: 0,
                kills = (map["kills"] as? Number)?.toInt() ?: 0,
                survivalSeconds = (map["survivalSeconds"] as? Number)?.toInt() ?: 0,
                killerName = killerName,
                hitBoundary = map["hitBoundary"] as? Boolean ?: false
            )
        }

        val leaderboard = worms
            .filter { it.isAlive }
            .sortedByDescending { it.score }
            .take(5)
            .map { LeaderboardEntry(it.id, it.name, it.score, it.skinId, it.isBot) }

        return OnlineLiveSnapshot(
            snapshot = ArenaSnapshot(
                tick = tick,
                arenaRadius = arenaRadius,
                worms = worms,
                orbs = orbs,
                leaderboard = leaderboard,
                killFeedText = killFeed
            ),
            wormIdByUid = wormIdByUid,
            deathByUid = deathByUid
        )
    }

    private fun compressSegments(segments: List<Vec2>): List<Long> {
        if (segments.isEmpty()) return emptyList()
        val total = segments.size
        val step = ((total - 1) / MAX_POLYLINE_SAMPLES.toFloat()).coerceAtLeast(1f)
        val out = ArrayList<Long>(minOf(total, MAX_POLYLINE_SAMPLES + 2) * 2)
        var cursor = 0f
        var lastIdx = -1
        while (cursor < total - 1) {
            val idx = cursor.toInt()
            if (idx != lastIdx) {
                val pt = segments[idx]
                out.add(pt.x.roundToLong())
                out.add(pt.y.roundToLong())
                lastIdx = idx
            }
            cursor += step
        }
        if (lastIdx != total - 1) {
            val tail = segments.last()
            out.add(tail.x.roundToLong())
            out.add(tail.y.roundToLong())
        }
        return out
    }

    private fun expandPolyline(
        polyline: List<Vec2>,
        head: Vec2,
        targetCount: Int,
        spacing: Float
    ): List<Vec2> {
        if (polyline.isEmpty()) return listOf(head)
        if (polyline.size == 1 || targetCount <= 1) return listOf(head)

        // Build smooth Catmull-Rom curve points from the compressed polyline
        val control = ArrayList<Vec2>(polyline.size)
        control.add(head)
        for (i in 1 until polyline.size) {
            control.add(polyline[i])
        }
        val smoothPath = ArrayList<Vec2>(control.size * 4)
        smoothPath.add(head)
        val subSteps = 4
        for (i in 0 until control.size - 1) {
            val p0 = control[(i - 1).coerceAtLeast(0)]
            val p1 = control[i]
            val p2 = control[i + 1]
            val p3 = control[(i + 2).coerceAtMost(control.lastIndex)]
            for (step in 1..subSteps) {
                val t = step / subSteps.toFloat()
                val t2 = t * t
                val t3 = t2 * t
                val cx = 0.5f * (
                    (2f * p1.x) +
                        (-p0.x + p2.x) * t +
                        (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                        (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3
                    )
                val cy = 0.5f * (
                    (2f * p1.y) +
                        (-p0.y + p2.y) * t +
                        (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                        (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3
                    )
                smoothPath.add(Vec2(cx, cy))
            }
        }

        val result = ArrayList<Vec2>(targetCount)
        result.add(head)

        var polyIdx = 0
        var prevPt = head
        var accumulated = 0f

        for (segIndex in 1 until targetCount) {
            val targetDist = segIndex * spacing
            while (polyIdx + 1 < smoothPath.size) {
                val nextPt = smoothPath[polyIdx + 1]
                val segLen = hypot(nextPt.x - prevPt.x, nextPt.y - prevPt.y)
                if (accumulated + segLen >= targetDist) {
                    val t = if (segLen > 0.0001f) (targetDist - accumulated) / segLen else 0f
                    result.add(
                        Vec2(
                            prevPt.x + (nextPt.x - prevPt.x) * t,
                            prevPt.y + (nextPt.y - prevPt.y) * t
                        )
                    )
                    break
                } else {
                    accumulated += segLen
                    polyIdx++
                    prevPt = nextPt
                }
            }
            if (result.size <= segIndex) {
                result.add(smoothPath.last())
            }
        }
        return result
    }
}
