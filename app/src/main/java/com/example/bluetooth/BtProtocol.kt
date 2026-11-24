package com.example.bluetooth

import com.example.game.ArenaEngine
import com.example.game.ArenaSnapshot
import com.example.game.DeathSummary
import com.example.game.FoodOrb
import com.example.game.LeaderboardEntry
import com.example.game.Vec2
import com.example.game.WormState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.roundToInt

object BtProtocol {
    val APP_UUID: UUID = UUID.fromString("8f6c2b40-9e3d-4a8e-b71d-5c9e4a123456")
    const val SERVICE_NAME = "SlitherBT_Arena"

    const val MSG_HELLO: Byte = 1
    const val MSG_WELCOME: Byte = 2
    const val MSG_INPUT: Byte = 3
    const val MSG_RESPAWN: Byte = 4
    const val MSG_SNAPSHOT: Byte = 5

    private const val MAX_FRAME_BYTES = 256 * 1024
    private const val MAX_BT_POLYLINE_SAMPLES = 30
    private const val MAX_BT_ORBS = 320

    fun writeFrame(out: DataOutputStream, type: Byte, payload: ByteArray) {
        synchronized(out) {
            out.writeInt(1 + payload.size)
            out.writeByte(type.toInt())
            out.write(payload)
            out.flush()
        }
    }

    fun readFrame(input: DataInputStream): Pair<Byte, ByteArray> {
        val totalLen = input.readInt()
        if (totalLen <= 0 || totalLen > MAX_FRAME_BYTES) {
            throw IllegalStateException("Invalid Bluetooth frame size: $totalLen")
        }
        val type = input.readByte()
        val payload = ByteArray(totalLen - 1)
        input.readFully(payload)
        return type to payload
    }

    fun encodeHello(name: String, skinId: Int): ByteArray {
        val bos = ByteArrayOutputStream(32)
        val dos = DataOutputStream(bos)
        dos.writeUTF(name.take(16))
        dos.writeByte(skinId)
        dos.flush()
        return bos.toByteArray()
    }

    fun decodeHello(payload: ByteArray): Pair<String, Int> {
        val dis = DataInputStream(ByteArrayInputStream(payload))
        val name = dis.readUTF()
        val skinId = dis.readByte().toInt()
        return name to skinId
    }

    fun encodeWelcome(wormId: Int, arenaRadius: Float): ByteArray {
        val bos = ByteArrayOutputStream(8)
        val dos = DataOutputStream(bos)
        dos.writeInt(wormId)
        dos.writeFloat(arenaRadius)
        dos.flush()
        return bos.toByteArray()
    }

    fun decodeWelcome(payload: ByteArray): Pair<Int, Float> {
        val dis = DataInputStream(ByteArrayInputStream(payload))
        return dis.readInt() to dis.readFloat()
    }

    fun encodeInput(targetAngle: Float, isBoosting: Boolean): ByteArray {
        val bos = ByteArrayOutputStream(5)
        val dos = DataOutputStream(bos)
        dos.writeFloat(targetAngle)
        dos.writeBoolean(isBoosting)
        dos.flush()
        return bos.toByteArray()
    }

    fun decodeInput(payload: ByteArray): Pair<Float, Boolean> {
        val dis = DataInputStream(ByteArrayInputStream(payload))
        return dis.readFloat() to dis.readBoolean()
    }

    fun encodeArenaCore(snapshot: ArenaSnapshot): ByteArray {
        val bos = ByteArrayOutputStream(3072)
        val dos = DataOutputStream(bos)

        dos.writeInt(snapshot.tick)
        dos.writeFloat(snapshot.arenaRadius)
        dos.writeUTF(snapshot.killFeedText ?: "")

        val activeWorms = snapshot.worms.filter { it.isAlive || !it.isBot }
        dos.writeShort(activeWorms.size.coerceAtMost(Short.MAX_VALUE.toInt()))
        for (w in activeWorms) {
            dos.writeInt(w.id)
            dos.writeUTF(w.name.take(16))
            dos.writeByte(w.skinId)
            var flags = 0
            if (w.isAlive) flags = flags or 1
            if (w.isBoosting) flags = flags or 2
            if (w.isBot) flags = flags or 4
            if (w.isSpawnProtected) flags = flags or 8
            dos.writeByte(flags)
            dos.writeShort(w.headX.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            dos.writeShort(w.headY.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            dos.writeFloat(w.angle)
            dos.writeFloat(w.targetAngle)
            dos.writeInt(w.score)
            dos.writeShort(w.kills.coerceAtMost(Short.MAX_VALUE.toInt()))
            dos.writeFloat(w.radius)

            val compressed = if (w.isAlive) compressSegments(w.segments) else emptyList()
            val segCount = compressed.size.coerceAtMost(Short.MAX_VALUE.toInt())
            dos.writeShort(segCount)
            for (i in 0 until segCount) {
                val seg = compressed[i]
                dos.writeShort(seg.x.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
                dos.writeShort(seg.y.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            }
        }

        val orbsToSend = if (snapshot.orbs.size <= MAX_BT_ORBS) {
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
            }.take(MAX_BT_ORBS)
        }
        val orbCount = orbsToSend.size.coerceAtMost(Short.MAX_VALUE.toInt())
        dos.writeShort(orbCount)
        for (i in 0 until orbCount) {
            val o = orbsToSend[i]
            dos.writeInt(o.id)
            dos.writeShort(o.x.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            dos.writeShort(o.y.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
            dos.writeByte(o.value.coerceIn(1, 127))
            dos.writeByte((o.radius * 10f).roundToInt().coerceIn(1, 127))
            dos.writeByte(o.colorIndex)
            dos.writeBoolean(o.isDeathDrop)
        }

        dos.flush()
        return bos.toByteArray()
    }

    fun appendDeathSummary(arenaCore: ByteArray, deathSummaryForClient: DeathSummary?): ByteArray {
        val bos = ByteArrayOutputStream(arenaCore.size + 40)
        bos.write(arenaCore)
        val dos = DataOutputStream(bos)
        if (deathSummaryForClient != null) {
            dos.writeBoolean(true)
            dos.writeInt(deathSummaryForClient.finalScore)
            dos.writeShort(deathSummaryForClient.kills.coerceAtMost(Short.MAX_VALUE.toInt()))
            dos.writeShort(deathSummaryForClient.survivalSeconds.coerceAtMost(Short.MAX_VALUE.toInt()))
            dos.writeUTF(deathSummaryForClient.killerName ?: "")
            dos.writeBoolean(deathSummaryForClient.hitBoundary)
        } else {
            dos.writeBoolean(false)
        }
        dos.flush()
        return bos.toByteArray()
    }

    fun encodeSnapshot(
        snapshot: ArenaSnapshot,
        deathSummaryForClient: DeathSummary?
    ): ByteArray = appendDeathSummary(encodeArenaCore(snapshot), deathSummaryForClient)

    fun decodeSnapshot(payload: ByteArray): Pair<ArenaSnapshot, DeathSummary?> {
        val dis = DataInputStream(ByteArrayInputStream(payload))

        val tick = dis.readInt()
        val arenaRadius = dis.readFloat()
        val killFeedRaw = dis.readUTF()
        val killFeed = killFeedRaw.ifEmpty { null }

        val wormCount = dis.readUnsignedShort()
        val worms = ArrayList<WormState>(wormCount)
        for (i in 0 until wormCount) {
            val id = dis.readInt()
            val name = dis.readUTF()
            val skinId = dis.readByte().toInt()
            val flags = dis.readByte().toInt()
            val isAlive = (flags and 1) != 0
            val isBoosting = (flags and 2) != 0
            val isBot = (flags and 4) != 0
            val isSpawnProtected = (flags and 8) != 0
            val headX = dis.readShort().toFloat()
            val headY = dis.readShort().toFloat()
            val angle = dis.readFloat()
            val targetAngle = dis.readFloat()
            val score = dis.readInt()
            val kills = dis.readUnsignedShort()
            val radius = dis.readFloat()

            val segCount = dis.readUnsignedShort()
            val polyline = ArrayList<Vec2>(segCount)
            for (s in 0 until segCount) {
                polyline.add(Vec2(dis.readShort().toFloat(), dis.readShort().toFloat()))
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
                    isSpawnProtected = isSpawnProtected,
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

        val orbCount = dis.readUnsignedShort()
        val orbs = ArrayList<FoodOrb>(orbCount)
        for (i in 0 until orbCount) {
            orbs.add(
                FoodOrb(
                    id = dis.readInt(),
                    x = dis.readShort().toFloat(),
                    y = dis.readShort().toFloat(),
                    value = dis.readByte().toInt(),
                    radius = dis.readByte().toFloat() / 10f,
                    colorIndex = dis.readByte().toInt(),
                    isDeathDrop = dis.readBoolean()
                )
            )
        }

        val hasDeath = dis.readBoolean()
        val deathSummary = if (hasDeath) {
            val finalScore = dis.readInt()
            val kills = dis.readUnsignedShort()
            val survivalSeconds = dis.readUnsignedShort()
            val killerRaw = dis.readUTF()
            val hitBoundary = dis.readBoolean()
            DeathSummary(
                finalScore = finalScore,
                kills = kills,
                survivalSeconds = survivalSeconds,
                killerName = killerRaw.ifEmpty { null },
                hitBoundary = hitBoundary
            )
        } else {
            null
        }

        val leaderboard = worms
            .filter { it.isAlive }
            .sortedByDescending { it.score }
            .take(5)
            .map { LeaderboardEntry(it.id, it.name, it.score, it.skinId, it.isBot) }

        return ArenaSnapshot(
            tick = tick,
            arenaRadius = arenaRadius,
            worms = worms,
            orbs = orbs,
            leaderboard = leaderboard,
            killFeedText = killFeed
        ) to deathSummary
    }

    private fun compressSegments(segments: List<Vec2>): List<Vec2> {
        if (segments.size <= MAX_BT_POLYLINE_SAMPLES) return segments
        val total = segments.size
        val step = ((total - 1) / MAX_BT_POLYLINE_SAMPLES.toFloat()).coerceAtLeast(1f)
        val out = ArrayList<Vec2>(MAX_BT_POLYLINE_SAMPLES + 2)
        var cursor = 0f
        var lastIdx = -1
        while (cursor < total - 1) {
            val idx = cursor.toInt()
            if (idx != lastIdx) {
                out.add(segments[idx])
                lastIdx = idx
            }
            cursor += step
        }
        if (lastIdx != total - 1) {
            out.add(segments.last())
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
