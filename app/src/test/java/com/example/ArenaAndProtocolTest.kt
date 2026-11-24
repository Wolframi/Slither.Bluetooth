package com.example

import com.example.bluetooth.BtProtocol
import com.example.game.ArenaEngine
import com.example.game.BotDifficulty
import com.example.game.DeathSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArenaAndProtocolTest {

    @Test
    fun `arena engine moves worm and maintains dense overlapping segments`() {
        val engine = ArenaEngine(arenaRadius = 1500f, targetAmbientOrbs = 60)
        val id = engine.addPlayerWorm("Тестер", skinId = 2, isBot = false)

        val snap1 = engine.step(0.016f)
        val w1 = snap1.worms.first { it.id == id }
        assertTrue(w1.isAlive)
        assertTrue(w1.isSpawnProtected)
        assertTrue(w1.score >= 10)
        assertTrue(w1.segments.size >= 9)

        engine.updatePlayerInput(id, targetAngle = 0f, isBoosting = true)
        var snap2 = snap1
        repeat(20) {
            snap2 = engine.step(0.016f)
        }
        val w2 = snap2.worms.first { it.id == id }
        assertTrue(w2.headX != w1.headX || w2.headY != w1.headY)
    }

    @Test
    fun `worm grows significantly in both width and length as score increases`() {
        val smallRadius = ArenaEngine.computeWormRadius(10)
        val largeRadius = ArenaEngine.computeWormRadius(800)
        val smallSegs = ArenaEngine.computeSegmentCount(10)
        val largeSegs = ArenaEngine.computeSegmentCount(800)

        assertTrue("Expected worm width to grow significantly ($smallRadius -> $largeRadius)", largeRadius > smallRadius * 2.5f)
        assertTrue("Expected worm length to grow significantly ($smallSegs -> $largeSegs)", largeSegs > smallSegs * 8)
    }

    @Test
    fun `worm dies when hitting arena boundary`() {
        val engine = ArenaEngine(arenaRadius = 350f, targetAmbientOrbs = 10)
        val id = engine.addPlayerWorm("Гонщик", skinId = 0, isBot = false)
        engine.updatePlayerInput(id, targetAngle = 0f, isBoosting = true)

        repeat(150) {
            engine.step(0.03f)
        }

        val death = engine.getDeathSummary(id)
        assertNotNull(death)
        assertTrue(death!!.hitBoundary)
        val lastSnap = engine.step(0.016f)
        assertFalse(lastSnap.worms.first { it.id == id }.isAlive)
    }

    @Test
    fun `smart bots steer away from boundary and stay active`() {
        val engine = ArenaEngine(
            arenaRadius = 1200f,
            targetAmbientOrbs = 80,
            botDifficulty = BotDifficulty.HARDCORE
        )
        val botIds = (1..4).map { idx ->
            engine.addPlayerWorm("Бот-$idx", skinId = idx, isBot = true)
        }

        var snap = engine.step(0.016f)
        repeat(220) {
            snap = engine.step(0.03f)
        }

        val aliveBots = snap.worms.count { it.id in botIds && it.isAlive }
        assertTrue("Expected smart bots to survive and navigate arena", aliveBots >= 2)
    }

    @Test
    fun `bluetooth protocol snapshot roundtrip preserves worms orbs targetAngle and survival time`() {
        val engine = ArenaEngine(arenaRadius = 1800f, targetAmbientOrbs = 45)
        val p1 = engine.addPlayerWorm("Хост", skinId = 1, isBot = false)
        engine.updatePlayerInput(p1, targetAngle = 1.25f, isBoosting = false)
        engine.addPlayerWorm("Бот", skinId = 4, isBot = true)

        val snap = engine.step(0.016f)
        val death = DeathSummary(
            finalScore = 125,
            kills = 3,
            survivalSeconds = 84,
            killerName = "Вайпер",
            hitBoundary = false
        )

        val encoded = BtProtocol.encodeSnapshot(snap, death)
        val (decodedSnap, decodedDeath) = BtProtocol.decodeSnapshot(encoded)

        assertEquals(snap.tick, decodedSnap.tick)
        assertEquals(snap.arenaRadius, decodedSnap.arenaRadius, 0.1f)
        assertEquals(snap.worms.size, decodedSnap.worms.size)
        assertEquals(snap.orbs.size, decodedSnap.orbs.size)
        val decodedHost = decodedSnap.worms.first { it.id == p1 }
        assertEquals("Хост", decodedHost.name)
        assertEquals(1.25f, decodedHost.targetAngle, 0.01f)
        assertNotNull(decodedDeath)
        assertEquals(125, decodedDeath?.finalScore)
        assertEquals(3, decodedDeath?.kills)
        assertEquals(84, decodedDeath?.survivalSeconds)
        assertEquals("Вайпер", decodedDeath?.killerName)
    }

    @Test
    fun `online state codec encodes native maps and packed integer orbs without json strings`() {
        val engine = ArenaEngine(arenaRadius = 1600f, targetAmbientOrbs = 30)
        val p1 = engine.addPlayerWorm("Хост", skinId = 0, isBot = false)
        val snap = engine.step(0.016f)

        val payload = com.example.online.OnlineStateCodec.encodeLivePayload(
            hostUid = "uid_host_1",
            snapshot = snap,
            uidByWormId = mapOf(p1 to "uid_host_1"),
            deathByUid = emptyMap()
        )

        assertEquals("uid_host_1", payload["hostUid"])
        val worms = payload["worms"] as List<*>
        assertEquals(1, worms.size)
        val orbs = payload["orbs"] as List<*>
        assertEquals(snap.orbs.size * 5, orbs.size)
        assertTrue(orbs.first() is Long)
    }

    @Test
    fun `player identity generator returns valid random nicknames and skins`() {
        val names = (1..20).map { com.example.game.PlayerIdentityGenerator.randomNickname() }.toSet()
        assertTrue(names.size >= 10)
        assertTrue(names.all { it.isNotBlank() && it.length <= 16 })
        val skins = (1..40).map { com.example.game.PlayerIdentityGenerator.randomSkinId() }.toSet()
        assertTrue(skins.all { it in com.example.game.WormSkins.all.indices })
        assertTrue(skins.size >= 3)
    }

    @Test
    fun `bluetooth protocol compresses and reconstructs large worm segments accurately`() {
        val engine = ArenaEngine(arenaRadius = 2000f, targetAmbientOrbs = 40)
        val bigWormId = engine.addPlayerWorm("Гигант", skinId = 3, isBot = false, startingBonusScore = 800)
        val snap = engine.step(0.016f)
        val bigWorm = snap.worms.first { it.id == bigWormId }
        assertTrue(bigWorm.segments.size > 100)

        val core = BtProtocol.encodeArenaCore(snap)
        val encoded = BtProtocol.appendDeathSummary(core, null)
        val (decoded, _) = BtProtocol.decodeSnapshot(encoded)
        val decodedBig = decoded.worms.first { it.id == bigWormId }
        assertEquals(bigWorm.segments.size, decodedBig.segments.size)
    }

    @Test
    fun `turning worm preserves curved body segments across 60fps steps`() {
        val engine = ArenaEngine(arenaRadius = 1800f, targetAmbientOrbs = 20)
        val id = engine.addPlayerWorm("Змейка", skinId = 1, isBot = false, startingBonusScore = 80)

        engine.updatePlayerInput(id, targetAngle = 0f, isBoosting = false)
        repeat(20) { engine.step(0.016f) }

        engine.updatePlayerInput(id, targetAngle = 1.57f, isBoosting = false)
        var snap = engine.step(0.016f)
        repeat(25) { snap = engine.step(0.016f) }

        val worm = snap.worms.first { it.id == id }
        val head = worm.segments.first()
        val tail = worm.segments.last()
        val mid = worm.segments[worm.segments.size / 2]

        // Distance from midpoint to the line segment connecting head and tail must be clearly > 0 (not straight)
        val dx = tail.x - head.x
        val dy = tail.y - head.y
        val lineLen = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val perpDist = kotlin.math.abs((mid.x - head.x) * dy - (mid.y - head.y) * dx) / lineLen
        assertTrue("Expected worm body to curve smoothly during turns (perpDist=$perpDist)", perpDist > 3.5f)
    }
}


