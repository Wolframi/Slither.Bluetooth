package com.example.game

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ArenaEngine(
    private val arenaRadius: Float = 2200f,
    private val targetAmbientOrbs: Int = 420,
    private val botDifficulty: BotDifficulty = BotDifficulty.BALANCED
) {
    companion object {
        const val INITIAL_SCORE = 10
        const val MIN_BOOST_SCORE = 13
        const val BASE_SPEED = 235f
        const val BOOST_SPEED = 435f
        const val TRAIL_STEP = 3.2f
        const val MAGNET_RANGE = 52f
        const val MAGNET_PULL_SPEED = 410f
        const val MAX_ORBS = 860

        /**
         * Authentic Slither.io growth:
         * Start small & nimble (score=10 -> radius=13f), grow noticeably in width up to 58f!
         */
        fun computeWormRadius(score: Int): Float {
            val growth = (score - INITIAL_SCORE).coerceAtLeast(0).toFloat()
            return (13f + sqrt(growth) * 0.96f).coerceIn(13f, 58f)
        }

        /**
         * Start very short in length (9 overlapping segments at score=10, ~2x head diameter),
         * then steadily stretch up to 165 segments as mass grows.
         */
        fun computeSegmentCount(score: Int): Int {
            val growth = (score - INITIAL_SCORE).coerceAtLeast(0)
            return (9 + (sqrt(growth.toFloat()) * 4.2f).toInt()).coerceIn(9, 165)
        }
    }

    private enum class BotPersonality {
        HUNTER,
        FORAGER,
        SURVIVOR
    }

    private class InternalWorm(
        val id: Int,
        var name: String,
        var skinId: Int,
        val isBot: Boolean,
        val personality: BotPersonality = BotPersonality.FORAGER,
        var isAlive: Boolean = true,
        var headX: Float = 0f,
        var headY: Float = 0f,
        var angle: Float = 0f,
        var targetAngle: Float = 0f,
        var angularVelocity: Float = 0f,
        var currentSpeed: Float = BASE_SPEED,
        var score: Float = INITIAL_SCORE.toFloat(),
        var kills: Int = 0,
        var aliveTimeSeconds: Float = 0f,
        var isBoosting: Boolean = false,
        var boostDropAccumulator: Float = 0f,
        var spawnGraceSeconds: Float = 2.0f,
        var botDecisionTimer: Float = 0f,
        var botBoostTimer: Float = 0f,
        var botRespawnTimer: Float = 0f,
        var botNoGrowthTimer: Float = 0f,
        var botPrevScore: Float = INITIAL_SCORE.toFloat(),
        var botEscapeLockTimer: Float = 0f,
        var botEscapeAngle: Float = 0f,
        var botPreferredTurnSign: Float = 1f,
        var lastDeathSummary: DeathSummary? = null,
        val trail: ArrayList<Vec2> = ArrayList(260),
        var cachedSegments: List<Vec2> = emptyList(),
        var minX: Float = 0f,
        var maxX: Float = 0f,
        var minY: Float = 0f,
        var maxY: Float = 0f
    )

    private class InternalOrb(
        val id: Int,
        var x: Float,
        var y: Float,
        val value: Int,
        val radius: Float,
        val colorIndex: Int,
        val isDeathDrop: Boolean,
        var cachedOrb: FoodOrb? = null
    )

    private val lock = Any()
    private val worms = LinkedHashMap<Int, InternalWorm>()
    private val orbs = ArrayList<InternalOrb>(MAX_ORBS)
    private val orbGrid = HashMap<Long, ArrayList<Int>>(256)
    private val cellListPool = ArrayList<ArrayList<Int>>(256)
    private var nextWormId = 1
    private var nextOrbId = 1
    private var tickCounter = 0
    private var lastKillFeed: String? = null
    private var killFeedTimer = 0f

    init {
        repeat(targetAmbientOrbs) {
            spawnAmbientOrb()
        }
    }

    private fun packCellKey(cx: Int, cy: Int): Long {
        return (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFF_FFFFL)
    }

    private fun rebuildOrbGrid() {
        for (list in orbGrid.values) {
            list.clear()
            cellListPool.add(list)
        }
        orbGrid.clear()

        val invCell = 1f / 180f
        for (i in 0 until orbs.size) {
            val o = orbs[i]
            val cx = kotlin.math.floor(o.x * invCell).toInt()
            val cy = kotlin.math.floor(o.y * invCell).toInt()
            val key = packCellKey(cx, cy)
            val bucket = orbGrid[key] ?: (if (cellListPool.isNotEmpty()) {
                cellListPool.removeAt(cellListPool.lastIndex)
            } else {
                ArrayList(8)
            }).also { orbGrid[key] = it }
            bucket.add(i)
        }
    }

    fun addPlayerWorm(
        name: String,
        skinId: Int,
        isBot: Boolean = false,
        startingBonusScore: Int = 0
    ): Int = synchronized(lock) {
        val id = nextWormId++
        val personalities = BotPersonality.entries
        val worm = InternalWorm(
            id = id,
            name = name.trim().take(16).ifEmpty { "Игрок $id" },
            skinId = skinId.coerceIn(0, WormSkins.all.lastIndex),
            isBot = isBot,
            personality = personalities[id % personalities.size]
        )
        spawnWormAtSafePoint(worm, startingBonusScore)
        worms[id] = worm
        id
    }

    fun removeWorm(wormId: Int) {
        synchronized(lock) {
            worms.remove(wormId)
        }
    }

    fun updatePlayerInput(wormId: Int, targetAngle: Float, isBoosting: Boolean) {
        synchronized(lock) {
            val worm = worms[wormId] ?: return
            if (targetAngle.isFinite()) {
                worm.targetAngle = targetAngle
            }
            worm.isBoosting = isBoosting
        }
    }

    fun respawnPlayer(wormId: Int, name: String? = null, skinId: Int? = null) {
        synchronized(lock) {
            val worm = worms[wormId] ?: return
            if (name != null) {
                worm.name = name.trim().take(16).ifEmpty { worm.name }
            }
            if (skinId != null) {
                worm.skinId = skinId.coerceIn(0, WormSkins.all.lastIndex)
            }
            if (!worm.isAlive) {
                worm.lastDeathSummary = null
                spawnWormAtSafePoint(worm, 0)
            }
        }
    }

    fun getDeathSummary(wormId: Int): DeathSummary? = synchronized(lock) {
        worms[wormId]?.lastDeathSummary
    }

    fun step(dtSeconds: Float): ArenaSnapshot = synchronized(lock) {
        val dt = dtSeconds.coerceIn(0.001f, 0.05f)
        tickCounter++

        if (killFeedTimer > 0f) {
            killFeedTimer -= dt
            if (killFeedTimer <= 0f) lastKillFeed = null
        }

        // Maintain ambient food supply briskly
        var ambientCount = 0
        for (i in 0 until orbs.size) {
            if (!orbs[i].isDeathDrop) ambientCount++
        }
        val missingAmbient = (targetAmbientOrbs - ambientCount).coerceAtMost(18)
        repeat(missingAmbient) {
            if (orbs.size < MAX_ORBS) spawnAmbientOrb()
        }

        // Update bots & smooth movement
        for (worm in worms.values) {
            if (!worm.isAlive) {
                if (worm.isBot) {
                    worm.botRespawnTimer -= dt
                    if (worm.botRespawnTimer <= 0f) {
                        val bonus = if (Random.nextFloat() < 0.35f) Random.nextInt(15, 90) else 0
                        spawnWormAtSafePoint(worm, bonus)
                    }
                }
                continue
            }

            worm.aliveTimeSeconds += dt
            if (worm.spawnGraceSeconds > 0f) {
                worm.spawnGraceSeconds = (worm.spawnGraceSeconds - dt).coerceAtLeast(0f)
            }

            if (worm.isBot) {
                updateSmartBotAi(worm, dt)
            }

            val scoreInt = worm.score.toInt()
            val radius = computeWormRadius(scoreInt)
            val canBoost = worm.isBoosting && worm.score > MIN_BOOST_SCORE
            val targetSpeed = if (canBoost) BOOST_SPEED else BASE_SPEED
            val accelAlpha = 1f - kotlin.math.exp(-dt * 12f)
            worm.currentSpeed += (targetSpeed - worm.currentSpeed) * accelAlpha

            val maxTurnRate = (4.15f - (radius - 13f) * 0.048f).coerceIn(1.85f, 4.15f)
            var angleDiff = worm.targetAngle - worm.angle
            while (angleDiff > PI) angleDiff -= (2f * PI.toFloat())
            while (angleDiff < -PI) angleDiff += (2f * PI.toFloat())
            val desiredAngVel = (angleDiff * 7.2f).coerceIn(-maxTurnRate, maxTurnRate)
            val angAccelAlpha = 1f - kotlin.math.exp(-dt * 17f)
            worm.angularVelocity += (desiredAngVel - worm.angularVelocity) * angAccelAlpha
            var nextAngle = worm.angle + worm.angularVelocity * dt
            while (nextAngle > PI) nextAngle -= (2f * PI.toFloat())
            while (nextAngle < -PI) nextAngle += (2f * PI.toFloat())
            worm.angle = nextAngle

            worm.headX += cos(worm.angle) * worm.currentSpeed * dt
            worm.headY += sin(worm.angle) * worm.currentSpeed * dt

            if (canBoost) {
                val massLoss = 3.5f * dt
                worm.score = (worm.score - massLoss).coerceAtLeast(MIN_BOOST_SCORE.toFloat())
                worm.boostDropAccumulator += massLoss
                if (worm.boostDropAccumulator >= 1.6f) {
                    worm.boostDropAccumulator -= 1.6f
                    val tail = worm.cachedSegments.lastOrNull() ?: Vec2(worm.headX, worm.headY)
                    if (orbs.size < MAX_ORBS) {
                        orbs.add(
                            InternalOrb(
                                id = nextOrbId++,
                                x = tail.x + Random.nextFloat() * 10f - 5f,
                                y = tail.y + Random.nextFloat() * 10f - 5f,
                                value = 2,
                                radius = 7.5f,
                                colorIndex = worm.skinId,
                                isDeathDrop = false
                            )
                        )
                    }
                }
            }

            // Smooth overlapping segments (spacing = 0.45 * radius)
            updateTrailAndSegments(worm, computeSegmentCount(worm.score.toInt()), radius * 0.45f)
        }

        // Spatial grid magnetic pull & orb eating (O(worms) local 3x3 cell scan!)
        rebuildOrbGrid()
        val eatenIndices = ArrayList<Int>(16)
        val invCell = 1f / 180f
        for (worm in worms.values) {
            if (!worm.isAlive) continue
            val radius = computeWormRadius(worm.score.toInt())
            val wcx = kotlin.math.floor(worm.headX * invCell).toInt()
            val wcy = kotlin.math.floor(worm.headY * invCell).toInt()
            val magnetDist = radius + MAGNET_RANGE
            val magnetDistSq = magnetDist * magnetDist

            for (gx in (wcx - 1)..(wcx + 1)) {
                for (gy in (wcy - 1)..(wcy + 1)) {
                    val bucket = orbGrid[packCellKey(gx, gy)] ?: continue
                    for (bIdx in 0 until bucket.size) {
                        val orbIdx = bucket[bIdx]
                        val orb = orbs[orbIdx]
                        if (orb.value <= 0) continue
                        val dx = worm.headX - orb.x
                        val dy = worm.headY - orb.y
                        val distSq = dx * dx + dy * dy
                        val eatDist = radius + orb.radius + 7f
                        if (distSq <= eatDist * eatDist) {
                            worm.score += orb.value
                            orb.x = Float.NaN
                            eatenIndices.add(orbIdx)
                        } else if (distSq <= magnetDistSq && distSq > 1f) {
                            val dist = sqrt(distSq)
                            val pull = MAGNET_PULL_SPEED * dt / dist
                            orb.x += dx * pull
                            orb.y += dy * pull
                            orb.cachedOrb = null
                        }
                    }
                }
            }
        }
        if (eatenIndices.isNotEmpty()) {
            eatenIndices.sortDescending()
            var prevRemoved = -1
            for (idx in eatenIndices) {
                if (idx != prevRemoved && idx < orbs.size) {
                    val lastIdx = orbs.lastIndex
                    if (idx != lastIdx) {
                        orbs[idx] = orbs[lastIdx]
                    }
                    orbs.removeAt(lastIdx)
                    prevRemoved = idx
                }
            }
        }

        // Collision detection (boundary + AABB prefiltered other worms' bodies)
        val killedWorms = ArrayList<Pair<InternalWorm, InternalWorm?>>()
        for (worm in worms.values) {
            if (!worm.isAlive) continue
            val radius = computeWormRadius(worm.score.toInt())

            val distFromCenter = hypot(worm.headX, worm.headY)
            if (distFromCenter + radius * 0.65f >= arenaRadius) {
                killedWorms.add(worm to null)
                continue
            }

            if (worm.spawnGraceSeconds > 0f) continue

            var killer: InternalWorm? = null
            for (other in worms.values) {
                if (other.id == worm.id || !other.isAlive || other.spawnGraceSeconds > 0f) continue
                val otherRadius = computeWormRadius(other.score.toInt())
                val hitDist = radius * 0.70f + otherRadius * 0.78f

                // O(1) AABB reject before scanning segments
                if (worm.headX < other.minX - hitDist ||
                    worm.headX > other.maxX + hitDist ||
                    worm.headY < other.minY - hitDist ||
                    worm.headY > other.maxY + hitDist
                ) {
                    continue
                }

                val hitDistSq = hitDist * hitDist
                val segs = other.cachedSegments
                for (s in 0 until segs.size) {
                    val seg = segs[s]
                    val dx = worm.headX - seg.x
                    val dy = worm.headY - seg.y
                    if (dx * dx + dy * dy <= hitDistSq) {
                        if (s == 0 && worm.score > other.score * 1.15f) {
                            continue
                        }
                        killer = other
                        break
                    }
                }
                if (killer != null) break
            }

            if (killer != null) {
                killedWorms.add(worm to killer)
            }
        }

        for ((victim, killer) in killedWorms) {
            killWorm(victim, killer)
        }

        return buildSnapshotLocked()
    }

    private fun killWorm(victim: InternalWorm, killer: InternalWorm?) {
        if (!victim.isAlive) return
        victim.isAlive = false
        victim.isBoosting = false
        victim.botRespawnTimer = Random.nextFloat() * 2.0f + 1.4f
        victim.lastDeathSummary = DeathSummary(
            finalScore = victim.score.toInt(),
            kills = victim.kills,
            survivalSeconds = victim.aliveTimeSeconds.toInt(),
            killerName = killer?.name,
            hitBoundary = killer == null
        )

        if (killer != null) {
            killer.kills += 1
            lastKillFeed = "${killer.name} устранил ${victim.name}"
            killFeedTimer = 3.5f
        } else {
            lastKillFeed = "${victim.name} врезался в границу"
            killFeedTimer = 3.0f
        }

        val segs = victim.cachedSegments
        val totalDropMass = (victim.score * 0.78f).toInt().coerceAtLeast(14)
        val dropCount = min(segs.size, 48).coerceAtLeast(7)
        val basePerOrb = (totalDropMass / dropCount).coerceAtLeast(2)
        val step = (segs.size / dropCount.toFloat()).coerceAtLeast(1f)
        val victimRadius = computeWormRadius(victim.score.toInt())
        val scatter = (victimRadius * 0.75f).coerceIn(10f, 26f)
        val skin = WormSkins.byId(victim.skinId)

        val maxDeathOrbCap = (MAX_ORBS - 80).coerceAtLeast(100)
        var i = 0f
        var dropIdx = 0
        while (i < segs.size && orbs.size < maxDeathOrbCap) {
            val seg = segs[i.toInt().coerceAtMost(segs.lastIndex)]
            val sizeJitter = 0.85f + Random.nextFloat() * 0.42f
            val orbRadius = (10.5f + (victimRadius - 13f) * 0.11f) * sizeJitter
            val orbValue = (basePerOrb + if (sizeJitter > 1.12f) 1 else 0).coerceAtLeast(2)
            val colorIdx = when {
                skin.rainbowMode -> dropIdx % OrbPalette.colors.size
                dropIdx % 3 == 0 -> (victim.skinId + 3) % OrbPalette.colors.size
                else -> victim.skinId % OrbPalette.colors.size
            }
            orbs.add(
                InternalOrb(
                    id = nextOrbId++,
                    x = seg.x + (Random.nextFloat() - 0.5f) * scatter * 2f,
                    y = seg.y + (Random.nextFloat() - 0.5f) * scatter * 2f,
                    value = orbValue,
                    radius = orbRadius.coerceIn(9.5f, 16.5f),
                    colorIndex = colorIdx,
                    isDeathDrop = true
                )
            )
            i += step
            dropIdx++
        }
    }

    private fun updateSmartBotAi(bot: InternalWorm, dt: Float) {
        if (bot.botBoostTimer > 0f) {
            bot.botBoostTimer -= dt
            if (bot.botBoostTimer <= 0f) bot.isBoosting = false
        }
        if (bot.botEscapeLockTimer > 0f) {
            bot.botEscapeLockTimer -= dt
        }

        if (bot.score > bot.botPrevScore + 0.8f) {
            bot.botNoGrowthTimer = 0f
            bot.botPrevScore = bot.score
        } else {
            bot.botNoGrowthTimer += dt
            if (bot.score < bot.botPrevScore) {
                bot.botPrevScore = bot.score
            }
        }

        val botRadius = computeWormRadius(bot.score.toInt())
        val distCenter = hypot(bot.headX, bot.headY)
        val boundaryMargin = 320f + botRadius * 2.5f

        if (distCenter > arenaRadius - boundaryMargin) {
            val inwardAngle = atan2(-bot.headY, -bot.headX)
            val leftClear = evaluateRayClearance(bot, inwardAngle - 0.45f, 240f)
            val rightClear = evaluateRayClearance(bot, inwardAngle + 0.45f, 240f)
            val centerClear = evaluateRayClearance(bot, inwardAngle, 240f)
            bot.targetAngle = when {
                centerClear >= leftClear && centerClear >= rightClear -> inwardAngle
                leftClear > rightClear -> inwardAngle - 0.45f
                else -> inwardAngle + 0.45f
            }
            bot.isBoosting = false
            bot.botEscapeLockTimer = 0.20f
            bot.botEscapeAngle = bot.targetAngle
            return
        }

        // Cancel boost immediately if forward corridor narrows unexpectedly
        if (bot.isBoosting && evaluateRayClearance(bot, bot.angle, 175f + botRadius * 1.5f) < 155f) {
            bot.isBoosting = false
            bot.botBoostTimer = 0f
        }

        val speedFactor = (bot.currentSpeed / BASE_SPEED).coerceIn(1f, 1.85f)
        val rayRange = (225f * speedFactor + botRadius * 2.0f) * botDifficulty.reactionScale
        val rayOffsets = floatArrayOf(0f, -0.38f, 0.38f, -0.78f, 0.78f, -1.24f, 1.24f, -1.78f, 1.78f)
        var immediateHazard = false
        var bestEscapeAngle = bot.angle
        var bestEscapeScore = -1f
        var bestRawClearance = 0f
        var bestOffset = 0f

        for (rIdx in rayOffsets.indices) {
            val off = rayOffsets[rIdx]
            val testAngle = bot.angle + off
            val clearance = evaluateRayClearance(bot, testAngle, rayRange)
            if (rIdx <= 2 && clearance < rayRange * (if (rIdx == 0) 0.94f else 0.68f)) {
                immediateHazard = true
            }
            val probeX = bot.headX + cos(testAngle) * (clearance * 0.62f)
            val probeY = bot.headY + sin(testAngle) * (clearance * 0.62f)
            val centerBias = ((arenaRadius - hypot(probeX, probeY)) / arenaRadius).coerceIn(0f, 1f) * 42f
            val turnPenalty = kotlin.math.abs(off) * 16f
            val hysteresisBonus = if (off != 0f && kotlin.math.sign(off) == bot.botPreferredTurnSign) 20f else 0f
            val escapeScore = clearance + centerBias + hysteresisBonus - turnPenalty
            if (escapeScore > bestEscapeScore) {
                bestEscapeScore = escapeScore
                bestEscapeAngle = testAngle
                bestRawClearance = clearance
                bestOffset = off
            }
        }

        if (immediateHazard) {
            if (bot.botEscapeLockTimer > 0f && evaluateRayClearance(bot, bot.botEscapeAngle, 155f) > 130f) {
                bot.targetAngle = bot.botEscapeAngle
            } else {
                bot.targetAngle = bestEscapeAngle
                bot.botEscapeAngle = bestEscapeAngle
                bot.botEscapeLockTimer = 0.22f
                if (bestOffset != 0f) {
                    bot.botPreferredTurnSign = kotlin.math.sign(bestOffset)
                }
            }
            if (bestRawClearance > 175f && bot.score > 26f && botDifficulty != BotDifficulty.CHILL) {
                bot.isBoosting = true
                bot.botBoostTimer = 0.25f
            } else {
                bot.isBoosting = false
            }
            return
        }

        // Dodge incoming predators trying to cut across the bot's nose
        val dodgeAngle = detectIncomingCutOffAngle(bot, botRadius)
        if (dodgeAngle != null) {
            bot.targetAngle = dodgeAngle
            bot.botEscapeAngle = dodgeAngle
            bot.botEscapeLockTimer = 0.24f
            bot.isBoosting = false
            return
        }

        bot.botDecisionTimer -= dt
        if (bot.botDecisionTimer > 0f) return
        bot.botDecisionTimer = (0.12f + Random.nextFloat() * 0.12f) / botDifficulty.reactionScale

        // Break out of aimless circling if bot hasn't eaten recently
        if (bot.botNoGrowthTimer > 2.8f) {
            bot.botNoGrowthTimer = 1.2f
            val toCenter = atan2(-bot.headY, -bot.headX) + (Random.nextFloat() - 0.5f) * 0.85f
            if (evaluateRayClearance(bot, toCenter, 200f) >= 172f) {
                bot.targetAngle = toCenter
                bot.isBoosting = false
                return
            }
        }

        // Tactical cut-off ambush on balanced/hardcore difficulty
        val canAmbush = bot.score > 22f &&
            (bot.personality == BotPersonality.HUNTER || Random.nextFloat() < botDifficulty.aggressionChance)
        if (canAmbush) {
            val prey = findAmbushTarget(bot)
            if (prey != null) {
                val distToPrey = hypot(prey.headX - bot.headX, prey.headY - bot.headY)
                val leadDist = (distToPrey * 0.42f + prey.currentSpeed * 0.34f).coerceIn(115f, 240f)
                val leadX = prey.headX + cos(prey.angle) * leadDist
                val leadY = prey.headY + sin(prey.angle) * leadDist
                val interceptAngle = atan2(leadY - bot.headY, leadX - bot.headX)
                if (evaluateRayClearance(bot, interceptAngle, 230f) >= 200f) {
                    bot.targetAngle = interceptAngle
                    bot.isBoosting = true
                    bot.botBoostTimer = 0.58f
                    return
                }
            }
        }

        // Cluster-aware foraging using spatial orb grid + death-drop priority
        var bestOrb: InternalOrb? = null
        var bestUtility = Float.MIN_VALUE
        val searchRadiusSq = 920f * 920f
        val invCell = 1f / 180f

        for (i in 0 until orbs.size) {
            val orb = orbs[i]
            val dx = orb.x - bot.headX
            val dy = orb.y - bot.headY
            val distSq = dx * dx + dy * dy
            if (distSq > searchRadiusSq) continue
            if (hypot(orb.x, orb.y) > arenaRadius - 155f) continue

            val dropMult = when (bot.personality) {
                BotPersonality.FORAGER -> if (orb.isDeathDrop) 7.5f else 1.25f
                BotPersonality.SURVIVOR -> if (orb.isDeathDrop) 3.8f else 1.0f
                BotPersonality.HUNTER -> if (orb.isDeathDrop) 5.8f else 0.95f
            }
            val cx = kotlin.math.floor(orb.x * invCell).toInt()
            val cy = kotlin.math.floor(orb.y * invCell).toInt()
            val clusterBonus = ((orbGrid[packCellKey(cx, cy)]?.size ?: 1) - 1).coerceAtMost(7) * 0.55f
            val utility = ((orb.value * dropMult) + clusterBonus) / (sqrt(distSq) + 28f)
            if (utility > bestUtility) {
                val angleToOrb = atan2(dy, dx)
                if (evaluateRayClearance(bot, angleToOrb, 190f) >= 162f) {
                    bestUtility = utility
                    bestOrb = orb
                }
            }
        }

        if (bestOrb != null) {
            bot.targetAngle = atan2(bestOrb.y - bot.headY, bestOrb.x - bot.headX)
            val rushDeathDrop = bestOrb.isDeathDrop &&
                bot.score > 22f &&
                bot.personality != BotPersonality.SURVIVOR &&
                Random.nextFloat() < (botDifficulty.aggressionChance + 0.25f) &&
                evaluateRayClearance(bot, bot.targetAngle, 220f) >= 195f
            if (rushDeathDrop) {
                bot.isBoosting = true
                bot.botBoostTimer = 0.48f
            }
        } else {
            val drift = bot.angle + (Random.nextFloat() - 0.5f) * 0.65f
            bot.targetAngle = if (evaluateRayClearance(bot, drift, 180f) >= 152f) drift else bestEscapeAngle
        }
    }

    private fun detectIncomingCutOffAngle(bot: InternalWorm, botRadius: Float): Float? {
        val lookAheadSec = 0.48f * botDifficulty.reactionScale
        val noseX = bot.headX + cos(bot.angle) * bot.currentSpeed * lookAheadSec
        val noseY = bot.headY + sin(bot.angle) * bot.currentSpeed * lookAheadSec

        for (other in worms.values) {
            if (other.id == bot.id || !other.isAlive || other.spawnGraceSeconds > 0f) continue
            val dx = other.headX - bot.headX
            val dy = other.headY - bot.headY
            val dist = hypot(dx, dy)
            if (dist > 290f || dist < 10f) continue

            val otherRadius = computeWormRadius(other.score.toInt())
            val otherFutureX = other.headX + cos(other.angle) * other.currentSpeed * lookAheadSec
            val otherFutureY = other.headY + sin(other.angle) * other.currentSpeed * lookAheadSec
            val crossDist = hypot(noseX - otherFutureX, noseY - otherFutureY)
            val dangerDist = botRadius + otherRadius + 68f

            if (crossDist < dangerDist && (other.isBoosting || other.score >= bot.score * 0.8f)) {
                // Tuck away perpendicular / behind enemy's trajectory
                val awayFromEnemy = atan2(bot.headY - otherFutureY, bot.headX - otherFutureX)
                val leftOpt = awayFromEnemy - 0.35f
                val rightOpt = awayFromEnemy + 0.35f
                val leftClear = evaluateRayClearance(bot, leftOpt, 195f)
                val rightClear = evaluateRayClearance(bot, rightOpt, 195f)
                return if (leftClear >= rightClear) leftOpt else rightOpt
            }
        }
        return null
    }

    private fun evaluateRayClearance(bot: InternalWorm, angle: Float, maxRange: Float): Float {
        val cosA = cos(angle)
        val sinA = sin(angle)
        val botRadius = computeWormRadius(bot.score.toInt())
        val endX = bot.headX + cosA * maxRange
        val endY = bot.headY + sinA * maxRange
        val pad = botRadius + 115f
        val rayMinX = minOf(bot.headX, endX) - pad
        val rayMaxX = maxOf(bot.headX, endX) + pad
        val rayMinY = minOf(bot.headY, endY) - pad
        val rayMaxY = maxOf(bot.headY, endY) + pad

        var minClearance = maxRange
        val checkSteps = 8

        for (step in 1..checkSteps) {
            val distAlong = maxRange * (step / checkSteps.toFloat())
            val px = bot.headX + cosA * distAlong
            val py = bot.headY + sinA * distAlong

            if (hypot(px, py) > arenaRadius - 120f) {
                return distAlong * 0.5f
            }

            for (other in worms.values) {
                if (other.id == bot.id || !other.isAlive) continue
                if (other.maxX < rayMinX || other.minX > rayMaxX ||
                    other.maxY < rayMinY || other.minY > rayMaxY
                ) {
                    continue
                }

                val otherRadius = computeWormRadius(other.score.toInt())
                val safeRadius = botRadius + otherRadius + 50f
                val safeSq = safeRadius * safeRadius

                // Predictive enemy head check (+0.20s and +0.40s forward)
                val predScale = 0.38f * botDifficulty.reactionScale
                val predHeadX = other.headX + cos(other.angle) * other.currentSpeed * predScale
                val predHeadY = other.headY + sin(other.angle) * other.currentSpeed * predScale
                val hdx = px - predHeadX
                val hdy = py - predHeadY
                if (hdx * hdx + hdy * hdy < safeSq * 1.18f && bot.score <= other.score * 1.15f) {
                    minClearance = min(minClearance, distAlong * 0.66f)
                    return minClearance
                }

                val segs = other.cachedSegments
                val segStep = if (segs.size > 40) 2 else 1
                for (s in 0 until segs.size step segStep) {
                    val seg = segs[s]
                    val dx = px - seg.x
                    val dy = py - seg.y
                    if (dx * dx + dy * dy < safeSq) {
                        minClearance = min(minClearance, distAlong * 0.72f)
                        return minClearance
                    }
                }
            }
        }
        return minClearance
    }

    private fun findAmbushTarget(bot: InternalWorm): InternalWorm? {
        var bestPrey: InternalWorm? = null
        var bestScore = -Float.MAX_VALUE
        val maxHuntRange = 520f * botDifficulty.reactionScale
        for (other in worms.values) {
            if (other.id == bot.id || !other.isAlive || other.spawnGraceSeconds > 0f) continue
            val dx = other.headX - bot.headX
            val dy = other.headY - bot.headY
            val d = hypot(dx, dy)
            if (d in 125f..maxHuntRange) {
                val otherDirX = cos(other.angle)
                val otherDirY = sin(other.angle)
                val relDot = ((-dx) * otherDirX + (-dy) * otherDirY) / d
                if (relDot > -0.45f) {
                    val reward = (other.score.coerceAtMost(350f) * 0.4f) - d * 0.55f
                    if (reward > bestScore) {
                        bestScore = reward
                        bestPrey = other
                    }
                }
            }
        }
        return bestPrey
    }

    private fun spawnWormAtSafePoint(worm: InternalWorm, bonusScore: Int) {
        var bestX = 0f
        var bestY = 0f
        var bestClearance = -1f
        val safeRadius = arenaRadius * 0.68f

        repeat(14) {
            val r = sqrt(Random.nextFloat()) * safeRadius
            val theta = Random.nextFloat() * (2f * PI.toFloat())
            val cx = cos(theta) * r
            val cy = sin(theta) * r
            var minDist = Float.MAX_VALUE
            for (other in worms.values) {
                if (other.id == worm.id || !other.isAlive) continue
                val d = hypot(cx - other.headX, cy - other.headY)
                if (d < minDist) minDist = d
            }
            if (minDist > bestClearance) {
                bestClearance = minDist
                bestX = cx
                bestY = cy
            }
        }

        val startAngle = Random.nextFloat() * (2f * PI.toFloat()) - PI.toFloat()
        val initialScore = INITIAL_SCORE + bonusScore
        worm.isAlive = true
        worm.headX = bestX
        worm.headY = bestY
        worm.angle = startAngle
        worm.targetAngle = startAngle
        worm.angularVelocity = 0f
        worm.currentSpeed = BASE_SPEED
        worm.score = initialScore.toFloat()
        worm.aliveTimeSeconds = 0f
        worm.isBoosting = false
        worm.botBoostTimer = 0f
        worm.boostDropAccumulator = 0f
        worm.spawnGraceSeconds = 2.0f
        worm.botEscapeLockTimer = 0f
        worm.botNoGrowthTimer = 0f
        worm.trail.clear()

        val initialSegs = computeSegmentCount(initialScore)
        val spacing = computeWormRadius(initialScore) * 0.45f
        val totalPoints = ((initialSegs * spacing) / TRAIL_STEP).toInt() + 6
        for (i in totalPoints downTo 0) {
            val dist = i * TRAIL_STEP
            worm.trail.add(
                Vec2(
                    bestX - cos(startAngle) * dist,
                    bestY - sin(startAngle) * dist
                )
            )
        }
        updateTrailAndSegments(worm, initialSegs, spacing)
    }

    private fun updateTrailAndSegments(worm: InternalWorm, segmentCount: Int, spacing: Float) {
        val head = Vec2(worm.headX, worm.headY)
        if (worm.trail.isEmpty()) {
            worm.trail.add(head)
        } else {
            var latest = worm.trail[worm.trail.lastIndex]
            var dx = head.x - latest.x
            var dy = head.y - latest.y
            var dist = hypot(dx, dy)
            // Deposit exact TRAIL_STEP breadcrumbs without overwriting the previous vertex
            var safety = 0
            while (dist >= TRAIL_STEP && safety < 16) {
                val inv = TRAIL_STEP / dist
                val pt = Vec2(latest.x + dx * inv, latest.y + dy * inv)
                worm.trail.add(pt)
                latest = pt
                dx = head.x - latest.x
                dy = head.y - latest.y
                dist = hypot(dx, dy)
                safety++
            }
        }

        val segments = ArrayList<Vec2>(segmentCount)
        segments.add(head)

        var trailIdx = worm.trail.lastIndex
        var prevPt = head
        var accumulated = 0f

        for (segIndex in 1 until segmentCount) {
            val targetDist = segIndex * spacing
            while (trailIdx >= 0) {
                val nextPt = worm.trail[trailIdx]
                val segLen = hypot(nextPt.x - prevPt.x, nextPt.y - prevPt.y)
                if (accumulated + segLen >= targetDist) {
                    val t = if (segLen > 0.0001f) (targetDist - accumulated) / segLen else 0f
                    segments.add(
                        Vec2(
                            prevPt.x + (nextPt.x - prevPt.x) * t,
                            prevPt.y + (nextPt.y - prevPt.y) * t
                        )
                    )
                    break
                } else {
                    accumulated += segLen
                    trailIdx--
                    prevPt = nextPt
                }
            }
            if (segments.size <= segIndex) {
                segments.add(worm.trail.first())
            }
        }

        // Trim obsolete tail breadcrumbs in bulk when buffer grows beyond slack
        val removeCount = (trailIdx - 14).coerceAtLeast(0)
        if (removeCount >= 24 && removeCount < worm.trail.size - 16) {
            worm.trail.subList(0, removeCount).clear()
        }

        var minX = head.x
        var maxX = head.x
        var minY = head.y
        var maxY = head.y
        for (i in 1 until segments.size) {
            val s = segments[i]
            if (s.x < minX) minX = s.x
            if (s.x > maxX) maxX = s.x
            if (s.y < minY) minY = s.y
            if (s.y > maxY) maxY = s.y
        }
        worm.minX = minX
        worm.maxX = maxX
        worm.minY = minY
        worm.maxY = maxY
        worm.cachedSegments = segments
    }

    private fun spawnAmbientOrb() {
        val r = sqrt(Random.nextFloat()) * (arenaRadius - 60f)
        val theta = Random.nextFloat() * (2f * PI.toFloat())
        val roll = Random.nextFloat()
        val (value, radius) = when {
            roll < 0.12f -> 4 to (9.2f + Random.nextFloat() * 1.6f)
            roll < 0.38f -> 2 to (7.0f + Random.nextFloat() * 1.4f)
            else -> 1 to (5.0f + Random.nextFloat() * 1.2f)
        }
        orbs.add(
            InternalOrb(
                id = nextOrbId++,
                x = cos(theta) * r,
                y = sin(theta) * r,
                value = value,
                radius = radius,
                colorIndex = Random.nextInt(OrbPalette.colors.size),
                isDeathDrop = false
            )
        )
    }

    private fun buildSnapshotLocked(): ArenaSnapshot {
        val wormList = worms.values.map { w ->
            val scoreInt = w.score.toInt()
            WormState(
                id = w.id,
                name = w.name,
                skinId = w.skinId,
                isBot = w.isBot,
                isAlive = w.isAlive,
                isSpawnProtected = w.spawnGraceSeconds > 0f,
                headX = w.headX,
                headY = w.headY,
                angle = w.angle,
                targetAngle = w.targetAngle,
                score = scoreInt,
                kills = w.kills,
                isBoosting = w.isBoosting && w.score > MIN_BOOST_SCORE,
                radius = computeWormRadius(scoreInt),
                segments = w.cachedSegments
            )
        }

        val orbList = ArrayList<FoodOrb>(orbs.size)
        for (i in 0 until orbs.size) {
            val o = orbs[i]
            val cached = o.cachedOrb ?: FoodOrb(
                id = o.id,
                x = o.x,
                y = o.y,
                value = o.value,
                radius = o.radius,
                colorIndex = o.colorIndex,
                isDeathDrop = o.isDeathDrop
            ).also { o.cachedOrb = it }
            orbList.add(cached)
        }

        val leaderboard = wormList
            .filter { it.isAlive }
            .sortedByDescending { it.score }
            .take(5)
            .map { LeaderboardEntry(it.id, it.name, it.score, it.skinId, it.isBot) }

        return ArenaSnapshot(
            tick = tickCounter,
            arenaRadius = arenaRadius,
            worms = wormList,
            orbs = orbList,
            leaderboard = leaderboard,
            killFeedText = lastKillFeed
        )
    }
}

object BotNames {
    val pool = listOf(
        "Кобра-Х", "Вайпер", "Нитро-Змей", "Пиксель", "Лазер",
        "Тень-99", "Вспышка", "Фантом", "Гидра", "Импульс",
        "Спайк", "Комета", "Зенит", "Вольт"
    )
}
