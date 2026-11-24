package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas as UiCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.example.game.ArenaEngine
import com.example.game.ArenaSnapshot
import com.example.game.OrbPalette
import com.example.game.Vec2
import com.example.game.WormSkin
import com.example.game.WormSkins
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.RubikFontFamily
import com.example.ui.theme.SolarGold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private val SlitherFloorGap = Color(0xFF0A0E14)
private val SlitherHexTile = Color(0xFF151C25)
private val SlitherHexHighlight = Color(0xFF202A37)
private val SlitherOuterVoid = Color(0xFF060408)

private const val CLIENT_TRAIL_CAP = 2048 // power of 2
private const val CLIENT_MAX_SEGS = 520
private const val CLIENT_TRAIL_STEP = 3.2f

private class RenderWormSmooth(
    var x: Float = 0f,
    var y: Float = 0f,
    var angle: Float = 0f,
    var angularVelocity: Float = 0f,
    var smoothSpeed: Float = ArenaEngine.BASE_SPEED,
    var wasAlive: Boolean = false,
    var estIntervalSec: Float = 0.11f,
    var lastSegments: List<Vec2> = emptyList(),
    val trailX: FloatArray = FloatArray(CLIENT_TRAIL_CAP),
    val trailY: FloatArray = FloatArray(CLIENT_TRAIL_CAP),
    var trailWriteIdx: Int = 0,
    var trailCount: Int = 0,
    val renderSegX: FloatArray = FloatArray(CLIENT_MAX_SEGS),
    val renderSegY: FloatArray = FloatArray(CLIENT_MAX_SEGS),
    var renderSegCount: Int = 0
) {
    fun pushTrail(px: Float, py: Float) {
        trailX[trailWriteIdx] = px
        trailY[trailWriteIdx] = py
        trailWriteIdx = (trailWriteIdx + 1) and (CLIENT_TRAIL_CAP - 1)
        if (trailCount < CLIENT_TRAIL_CAP) trailCount++
    }

    fun seedFromSegments(segs: List<Vec2>) {
        trailWriteIdx = 0
        trailCount = 0
        if (segs.isEmpty()) {
            renderSegCount = 0
            return
        }
        // Pad a small tail slack behind the final segment so rapid initial growth never bunches tip segments
        if (segs.size >= 2) {
            val tail = segs[segs.lastIndex]
            val beforeTail = segs[segs.lastIndex - 1]
            val dx = tail.x - beforeTail.x
            val dy = tail.y - beforeTail.y
            val len = hypot(dx, dy)
            if (len > 0.001f) {
                val ux = dx / len
                val uy = dy / len
                for (pad in 4 downTo 1) {
                    pushTrail(tail.x + ux * (pad * CLIENT_TRAIL_STEP), tail.y + uy * (pad * CLIENT_TRAIL_STEP))
                }
            }
        }
        for (i in segs.lastIndex downTo 0) {
            pushTrail(segs[i].x, segs[i].y)
        }
        val cnt = segs.size.coerceAtMost(CLIENT_MAX_SEGS)
        for (i in 0 until cnt) {
            renderSegX[i] = segs[i].x
            renderSegY[i] = segs[i].y
        }
        renderSegCount = cnt
    }

    fun advanceTrailAndSample(targetCount: Int, spacing: Float) {
        if (trailCount == 0) {
            pushTrail(x, y)
        } else {
            val headIdx = (trailWriteIdx - 1) and (CLIENT_TRAIL_CAP - 1)
            var hx = trailX[headIdx]
            var hy = trailY[headIdx]
            val dx = x - hx
            val dy = y - hy
            val dist = hypot(dx, dy)
            if (dist >= CLIENT_TRAIL_STEP) {
                val inv = CLIENT_TRAIL_STEP / dist
                val stepX = dx * inv
                val stepY = dy * inv
                var rem = dist
                var safety = 0
                while (rem >= CLIENT_TRAIL_STEP && safety < 18) {
                    hx += stepX
                    hy += stepY
                    pushTrail(hx, hy)
                    rem -= CLIENT_TRAIL_STEP
                    safety++
                }
            }
        }

        val count = targetCount.coerceIn(1, CLIENT_MAX_SEGS)
        renderSegCount = count
        renderSegX[0] = x
        renderSegY[0] = y
        if (count <= 1) return

        var prevX = x
        var prevY = y
        var accumulated = 0f
        var stepsBack = 1 // 1 = newest point at trailWriteIdx - 1
        val maxBack = trailCount

        for (segIdx in 1 until count) {
            val targetDist = segIdx * spacing
            var placed = false
            while (stepsBack <= maxBack) {
                val idx = (trailWriteIdx - stepsBack) and (CLIENT_TRAIL_CAP - 1)
                val tx = trailX[idx]
                val ty = trailY[idx]
                val segLen = hypot(tx - prevX, ty - prevY)
                if (accumulated + segLen >= targetDist) {
                    val t = if (segLen > 0.0001f) (targetDist - accumulated) / segLen else 0f
                    renderSegX[segIdx] = prevX + (tx - prevX) * t
                    renderSegY[segIdx] = prevY + (ty - prevY) * t
                    placed = true
                    break
                } else {
                    accumulated += segLen
                    prevX = tx
                    prevY = ty
                    stepsBack++
                }
            }
            if (!placed) {
                val tailIdx = (trailWriteIdx - maxBack) and (CLIENT_TRAIL_CAP - 1)
                renderSegX[segIdx] = trailX[tailIdx]
                renderSegY[segIdx] = trailY[tailIdx]
            }
        }
    }
}

private class VisualParticle(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var radius: Float = 4f,
    var age: Float = 0f,
    var maxAge: Float = 0.45f,
    var color: Color = NeonCyan,
    var isRing: Boolean = false
)

private class DyingWormAnim(
    val skinId: Int,
    val radius: Float,
    val segX: FloatArray,
    val segY: FloatArray,
    val count: Int,
    var age: Float = 0f,
    val duration: Float = 0.92f,
    var lastSparkSeg: Int = -1
)

@Composable
fun WormArenaCanvas(
    snapshotState: State<ArenaSnapshot>,
    localWormId: Int?,
    steeringAngleState: State<Float>,
    isNetworkClient: Boolean = false,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val latestLocalWormId by rememberUpdatedState(localWormId)
    val latestIsNetworkClient by rememberUpdatedState(isNetworkClient)

    // VSync-locked camera, client-side dead-reckoning smoothing, and lightweight particle effects
    var pulsePhase by remember { mutableFloatStateOf(0f) }
    var camX by remember { mutableFloatStateOf(0f) }
    var camY by remember { mutableFloatStateOf(0f) }
    var camZoom by remember { mutableFloatStateOf(1.18f) }
    var renderFrameNanos by remember { mutableLongStateOf(0L) }

    val smoothWorms = remember { HashMap<Int, RenderWormSmooth>(24) }
    val particles = remember { ArrayList<VisualParticle>(64) }
    val dyingWorms = remember { ArrayList<DyingWormAnim>(8) }
    val locallyEatenOrbs = remember { HashMap<Int, Long>(64) }
    val deathOrbSpawnNanos = remember { HashMap<Int, Long>(192) }

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        var lastSnapshotTick = -1
        var timeSinceSnapshot = 0f

        // Zero-allocation primitive buffers for eaten-orb particle detection
        var prevOrbCount = 0
        var prevOrbIds = IntArray(600)
        var prevOrbXs = FloatArray(600)
        var prevOrbYs = FloatArray(600)
        val currOrbIdTable = IntArray(2048)

        while (true) {
            withFrameNanos { frameNanos ->
                val dt = if (lastNanos == 0L) 0.016f
                else ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = frameNanos
                renderFrameNanos = frameNanos

                pulsePhase = (pulsePhase + dt * 2.6f) % (2f * PI.toFloat())
                val snap = snapshotState.value

                if (snap.tick != lastSnapshotTick) {
                    val measuredInterval = if (lastSnapshotTick >= 0) {
                        timeSinceSnapshot.coerceIn(0.035f, 0.35f)
                    } else {
                        0.11f
                    }
                    for (wIdx in 0 until snap.worms.size) {
                        val sm = smoothWorms[snap.worms[wIdx].id]
                        if (sm != null) {
                            sm.estIntervalSec = sm.estIntervalSec * 0.72f + measuredInterval * 0.28f
                        }
                    }
                    lastSnapshotTick = snap.tick
                    timeSinceSnapshot = 0f

                    // Zero-allocation O(N) hash table of current orb IDs (used for eat sparkles + cleaning eaten death orbs)
                    java.util.Arrays.fill(currOrbIdTable, -1)
                    val orbList = snap.orbs
                    for (i in 0 until orbList.size) {
                        val id = orbList[i].id
                        var slot = (id * 0x9E3779B9.toInt()) and 2047
                        while (currOrbIdTable[slot] != -1) {
                            slot = (slot + 1) and 2047
                        }
                        currOrbIdTable[slot] = id
                    }

                    if (prevOrbCount > 0 && particles.size < 48) {
                        val wormCount = snap.worms.size
                        for (pIdx in 0 until prevOrbCount) {
                            val oid = prevOrbIds[pIdx]
                            var slot = (oid * 0x9E3779B9.toInt()) and 2047
                            var found = false
                            while (currOrbIdTable[slot] != -1) {
                                if (currOrbIdTable[slot] == oid) {
                                    found = true
                                    break
                                }
                                slot = (slot + 1) and 2047
                            }
                            if (!found && !locallyEatenOrbs.containsKey(oid)) {
                                val ox = prevOrbXs[pIdx]
                                val oy = prevOrbYs[pIdx]
                                for (wIdx in 0 until wormCount) {
                                    val w = snap.worms[wIdx]
                                    if (!w.isAlive) continue
                                    val dx = ox - w.headX
                                    val dy = oy - w.headY
                                    val eatRange = w.radius + 75f
                                    if (dx * dx + dy * dy <= eatRange * eatRange && particles.size < 52) {
                                        particles.add(
                                            VisualParticle(
                                                x = ox,
                                                y = oy,
                                                vx = dx * 2.5f,
                                                vy = dy * 2.5f,
                                                radius = 8.5f,
                                                maxAge = 0.25f,
                                                color = WormSkins.byId(w.skinId).glowColor,
                                                isRing = true
                                            )
                                        )
                                        break
                                    }
                                }
                            }
                        }
                    }

                    // Remove ONLY death orbs that disappeared from the arena (never prune live ones, so they never re-bloom/blink!)
                    if (deathOrbSpawnNanos.isNotEmpty()) {
                        val pruneIter = deathOrbSpawnNanos.entries.iterator()
                        while (pruneIter.hasNext()) {
                            val oid = pruneIter.next().key
                            var slot = (oid * 0x9E3779B9.toInt()) and 2047
                            var alive = false
                            while (currOrbIdTable[slot] != -1) {
                                if (currOrbIdTable[slot] == oid) {
                                    alive = true
                                    break
                                }
                                slot = (slot + 1) and 2047
                            }
                            if (!alive) {
                                pruneIter.remove()
                            }
                        }
                    }

                    val currSize = snap.orbs.size
                    if (currSize > prevOrbIds.size) {
                        val nextCap = currSize + 128
                        prevOrbIds = IntArray(nextCap)
                        prevOrbXs = FloatArray(nextCap)
                        prevOrbYs = FloatArray(nextCap)
                    }
                    for (i in 0 until currSize) {
                        val o = snap.orbs[i]
                        prevOrbIds[i] = o.id
                        prevOrbXs[i] = o.x
                        prevOrbYs[i] = o.y
                        if (o.isDeathDrop && !deathOrbSpawnNanos.containsKey(o.id)) {
                            deathOrbSpawnNanos[o.id] = frameNanos
                        }
                    }
                    prevOrbCount = currSize

                    for (wIdx in 0 until snap.worms.size) {
                        val w = snap.worms[wIdx]
                        val smooth = smoothWorms[w.id]
                        if (smooth != null && smooth.wasAlive && !w.isAlive) {
                            val skin = WormSkins.byId(w.skinId)
                            if (dyingWorms.size < 10) {
                                val fallbackSegs = smooth.lastSegments.ifEmpty { w.segments }
                                val segCount = if (smooth.renderSegCount > 0) {
                                    smooth.renderSegCount
                                } else {
                                    fallbackSegs.size
                                }
                                if (segCount > 0) {
                                    val sxArr = FloatArray(segCount)
                                    val syArr = FloatArray(segCount)
                                    if (smooth.renderSegCount == segCount) {
                                        System.arraycopy(smooth.renderSegX, 0, sxArr, 0, segCount)
                                        System.arraycopy(smooth.renderSegY, 0, syArr, 0, segCount)
                                    } else {
                                        for (s in 0 until segCount) {
                                            sxArr[s] = fallbackSegs[s].x
                                            syArr[s] = fallbackSegs[s].y
                                        }
                                    }
                                    dyingWorms.add(
                                        DyingWormAnim(
                                            skinId = w.skinId,
                                            radius = w.radius,
                                            segX = sxArr,
                                            segY = syArr,
                                            count = segCount
                                        )
                                    )
                                }
                            }
                            if (particles.size < 84) {
                                val hx = smooth.x
                                val hy = smooth.y
                                particles.add(
                                    VisualParticle(
                                        x = hx,
                                        y = hy,
                                        radius = w.radius * 1.75f,
                                        maxAge = 0.58f,
                                        color = skin.glowColor,
                                        isRing = true
                                    )
                                )
                            }
                        }
                    }
                } else {
                    timeSinceSnapshot = (timeSinceSnapshot + dt).coerceAtMost(0.35f)
                }

                // Advance animated dying worms and emit cascading disintegration sparks
                var dwIdx = dyingWorms.lastIndex
                while (dwIdx >= 0) {
                    val anim = dyingWorms[dwIdx]
                    anim.age += dt
                    if (anim.age >= anim.duration) {
                        dyingWorms.removeAt(dwIdx)
                    } else {
                        val frontIdx = ((anim.age / (anim.duration * 0.82f)) * anim.count)
                            .toInt()
                            .coerceIn(0, anim.count - 1)
                        val sparkStep = (anim.count / 14).coerceAtLeast(1)
                        if (frontIdx - anim.lastSparkSeg >= sparkStep && particles.size < 84) {
                            anim.lastSparkSeg = frontIdx
                            val skin = WormSkins.byId(anim.skinId)
                            val px = anim.segX[frontIdx]
                            val py = anim.segY[frontIdx]
                            val a = (frontIdx * 2.15f) % (2f * PI.toFloat())
                            particles.add(
                                VisualParticle(
                                    x = px,
                                    y = py,
                                    vx = cos(a) * 115f,
                                    vy = sin(a) * 115f,
                                    radius = (anim.radius * 0.44f).coerceIn(4.5f, 10f),
                                    maxAge = 0.42f,
                                    color = if (frontIdx % 2 == 0) skin.glowColor else skin.accentColor,
                                    isRing = false
                                )
                            )
                        }
                    }
                    dwIdx--
                }

                // Update particles
                var pIdx = particles.lastIndex
                while (pIdx >= 0) {
                    val p = particles[pIdx]
                    p.age += dt
                    if (p.age >= p.maxAge) {
                        particles.removeAt(pIdx)
                    } else {
                        p.x += p.vx * dt
                        p.y += p.vy * dt
                    }
                    pIdx--
                }

                // Smooth / dead-reckon worm positions & 60/90/120 FPS continuous trails (Local + Online + BT)
                val isNet = latestIsNetworkClient
                val localId = latestLocalWormId
                val currSteerAngle = steeringAngleState.value
                for (wIdx in 0 until snap.worms.size) {
                    val w = snap.worms[wIdx]
                    val targetSpeed = if (w.isBoosting) ArenaEngine.BOOST_SPEED else ArenaEngine.BASE_SPEED
                    val smooth = smoothWorms.getOrPut(w.id) {
                        RenderWormSmooth(
                            x = w.headX,
                            y = w.headY,
                            angle = w.angle,
                            smoothSpeed = targetSpeed,
                            wasAlive = w.isAlive,
                            lastSegments = w.segments
                        ).also {
                            if (w.segments.isNotEmpty()) it.seedFromSegments(w.segments)
                        }
                    }
                    if (!w.isAlive) {
                        smooth.wasAlive = false
                        continue
                    }
                    if (w.segments.isNotEmpty()) {
                        smooth.lastSegments = w.segments
                    }

                    val accelAlpha = 1f - exp(-dt * 12f)
                    smooth.smoothSpeed += (targetSpeed - smooth.smoothSpeed) * accelAlpha
                    val speed = smooth.smoothSpeed
                    val maxTurnRate = (4.15f - (w.radius - 13f) * 0.048f).coerceIn(1.85f, 4.15f)
                    val desiredAngle = if (w.id == localId) currSteerAngle else w.targetAngle

                    val rawLead = if (isNet) {
                        val raw = timeSinceSnapshot
                            .coerceAtMost(smooth.estIntervalSec * 1.35f + 0.05f)
                            .coerceAtMost(0.30f)
                        if (raw > 0.14f) 0.14f + (raw - 0.14f) * 0.65f else raw
                    } else {
                        timeSinceSnapshot.coerceAtMost(0.026f)
                    }

                    val predServerAngle = stepAngleToward(w.angle, desiredAngle, maxTurnRate * rawLead)
                    val serverTargetX = w.headX + cos(predServerAngle) * speed * rawLead
                    val serverTargetY = w.headY + sin(predServerAngle) * speed * rawLead

                    val jumpThreshold = if (isNet) 260f else 180f
                    val jumpDist = hypot(serverTargetX - smooth.x, serverTargetY - smooth.y)
                    if (!smooth.wasAlive || jumpDist > jumpThreshold) {
                        smooth.x = serverTargetX
                        smooth.y = serverTargetY
                        smooth.angle = predServerAngle
                        smooth.angularVelocity = 0f
                        smooth.smoothSpeed = targetSpeed
                        smooth.seedFromSegments(w.segments)
                    } else {
                        // Exact match to ArenaEngine angular acceleration + continuous VSync forward motion
                        var angDiff = desiredAngle - smooth.angle
                        while (angDiff > PI) angDiff -= (2f * PI.toFloat())
                        while (angDiff < -PI) angDiff += (2f * PI.toFloat())
                        val desiredAngVel = (angDiff * 7.2f).coerceIn(-maxTurnRate, maxTurnRate)
                        val angAccelAlpha = 1f - exp(-dt * 17f)
                        smooth.angularVelocity += (desiredAngVel - smooth.angularVelocity) * angAccelAlpha
                        var nextAng = smooth.angle + smooth.angularVelocity * dt
                        if (w.id != localId || !isNet) {
                            var angErr = predServerAngle - nextAng
                            while (angErr > PI) angErr -= (2f * PI.toFloat())
                            while (angErr < -PI) angErr += (2f * PI.toFloat())
                            nextAng += angErr * (1f - exp(-dt * (if (isNet) 7.5f else 14f)))
                        }
                        while (nextAng > PI) nextAng -= (2f * PI.toFloat())
                        while (nextAng < -PI) nextAng += (2f * PI.toFloat())
                        smooth.angle = nextAng

                        smooth.x += cos(smooth.angle) * speed * dt
                        smooth.y += sin(smooth.angle) * speed * dt

                        val errDist = hypot(serverTargetX - smooth.x, serverTargetY - smooth.y)
                        val corrRate = if (!isNet) {
                            11f
                        } else {
                            when {
                                errDist > 65f -> 10f
                                errDist > 18f -> 5.8f
                                else -> 3.6f
                            }
                        }
                        val corrAlpha = 1f - exp(-dt * corrRate)
                        smooth.x += (serverTargetX - smooth.x) * corrAlpha
                        smooth.y += (serverTargetY - smooth.y) * corrAlpha

                        val segTargetCount = w.segments.size.coerceAtLeast(1)
                        smooth.advanceTrailAndSample(
                            targetCount = segTargetCount,
                            spacing = w.radius * 0.45f
                        )
                    }
                    smooth.wasAlive = true
                }

                // Instant visual orb-eat prediction for network clients so collecting food has zero lag
                if (isNet) {
                    if (locallyEatenOrbs.isNotEmpty()) {
                        val iter = locallyEatenOrbs.entries.iterator()
                        while (iter.hasNext()) {
                            if (frameNanos > iter.next().value) iter.remove()
                        }
                    }
                    val orbs = snap.orbs
                    val worms = snap.worms
                    for (oIdx in 0 until orbs.size) {
                        val orb = orbs[oIdx]
                        if (locallyEatenOrbs.containsKey(orb.id)) continue
                        for (wIdx in 0 until worms.size) {
                            val w = worms[wIdx]
                            if (!w.isAlive) continue
                            val sm = smoothWorms[w.id] ?: continue
                            val dx = orb.x - sm.x
                            val dy = orb.y - sm.y
                            val eatR = w.radius + orb.radius + 8f
                            if (dx * dx + dy * dy <= eatR * eatR) {
                                locallyEatenOrbs[orb.id] = frameNanos + 700_000_000L
                                if (particles.size < 52) {
                                    particles.add(
                                        VisualParticle(
                                            x = orb.x,
                                            y = orb.y,
                                            vx = dx * 2.5f,
                                            vy = dy * 2.5f,
                                            radius = 8.5f,
                                            maxAge = 0.25f,
                                            color = WormSkins.byId(w.skinId).glowColor,
                                            isRing = true
                                        )
                                    )
                                }
                                break
                            }
                        }
                    }
                } else if (locallyEatenOrbs.isNotEmpty()) {
                    locallyEatenOrbs.clear()
                }

                val localWorm = snap.worms.firstOrNull { it.id == latestLocalWormId }
                if (localWorm != null && localWorm.isAlive) {
                    val smoothHead = smoothWorms[localWorm.id]
                    val targetHeadX = smoothHead?.x ?: localWorm.headX
                    val targetHeadY = smoothHead?.y ?: localWorm.headY
                    val targetAng = smoothHead?.angle ?: localWorm.angle
                    val lookAhead = localWorm.radius * 1.15f + (if (localWorm.isBoosting) 34f else 14f)
                    val targetCamX = targetHeadX + cos(targetAng) * lookAhead
                    val targetCamY = targetHeadY + sin(targetAng) * lookAhead

                    val camJump = hypot(targetCamX - camX, targetCamY - camY)
                    if (camJump > 600f) {
                        camX = targetCamX
                        camY = targetCamY
                    } else {
                        val posAlpha = 1f - exp(-dt * 18f)
                        camX += (targetCamX - camX) * posAlpha
                        camY += (targetCamY - camY) * posAlpha
                    }

                    val boostZoomOut = if (localWorm.isBoosting) 0.05f else 0f
                    val targetZoom = (1.22f - (localWorm.radius - 13f) * 0.016f - boostZoomOut)
                        .coerceIn(0.44f, 1.22f)
                    val zoomAlpha = 1f - exp(-dt * 4.5f)
                    camZoom += (targetZoom - camZoom) * zoomAlpha
                } else {
                    // Gently center on elimination site and pull camera slightly out for cinematic death view
                    if (localWorm != null) {
                        val settleAlpha = 1f - exp(-dt * 2.6f)
                        camX += (localWorm.headX - camX) * settleAlpha
                        camY += (localWorm.headY - camY) * settleAlpha
                    }
                    val activeRadius = localWorm?.radius ?: 14f
                    val baseAliveZoom = (1.22f - (activeRadius - 13f) * 0.016f).coerceIn(0.44f, 1.22f)
                    val deathTargetZoom = (baseAliveZoom * 0.76f).coerceIn(0.36f, 0.92f)
                    val deathZoomAlpha = 1f - exp(-dt * 2.0f)
                    camZoom += (deathTargetZoom - camZoom) * deathZoomAlpha
                }
            }
        }
    }

    // Pre-bake seamless periodic Slither.io hex tile pattern once (1 drawRect call per frame!)
    val hexTileBrush = remember { createSlitherHexTileBrush() }

    // Pre-bake single radial glow + shaded sphere sprites + cached ColorFilters (0 per-frame shader allocations!)
    val orbGlowBitmap = remember { createOrbGlowBitmap() }
    val orbBodyBitmap = remember { createOrbBodyBitmap() }
    val orbColorFilters = remember {
        OrbPalette.colors.map { ColorFilter.tint(it, BlendMode.Modulate) }
    }

    // Cache measured player nameplates and reusable spine/body paths (zero per-frame alloc)
    val nameLayoutCache = remember { HashMap<Int, Pair<String, TextLayoutResult>>() }
    val glowSpinePath = remember { Path() }
    val bodyShadowPath = remember { Path() }
    val bodyRimPath = remember { Path() }
    val leaderCrownPath = remember { Path() }
    val labelStyle = remember {
        TextStyle(
            fontFamily = RubikFontFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 10.5.sp,
            letterSpacing = 0.sp,
            color = Color.White.copy(alpha = 0.92f)
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(SlitherFloorGap)
    ) {
        val snapshot = snapshotState.value
        val leaderWormId = snapshot.leaderboard.firstOrNull()?.wormId
        val centerX = size.width * 0.5f
        val centerY = size.height * 0.5f
        val baseScale = (minOf(size.width, size.height) / 900f).coerceAtLeast(0.55f) * camZoom
        val viewHalfW = (centerX / baseScale) + 200f
        val viewHalfH = (centerY / baseScale) + 200f

        withTransform({
            translate(left = centerX, top = centerY)
            scale(scaleX = baseScale, scaleY = baseScale, pivot = Offset.Zero)
            translate(left = -camX, top = -camY)
        }) {
            // 1. Entire Slither.io hex tile floor in ONE GPU draw call
            drawRect(
                brush = hexTileBrush,
                topLeft = Offset(camX - viewHalfW, camY - viewHalfH),
                size = Size(viewHalfW * 2f, viewHalfH * 2f)
            )

            // 2. Outer dark void & glowing red circular arena boundary
            val arenaR = snapshot.arenaRadius
            drawCircle(
                color = SlitherOuterVoid.copy(alpha = 0.88f),
                radius = arenaR + 1400f,
                center = Offset.Zero,
                style = Stroke(width = 2800f)
            )
            val borderAlpha = 0.80f + 0.20f * sin(pulsePhase)
            drawCircle(
                color = DangerCrimson.copy(alpha = 0.22f),
                radius = arenaR + 26f,
                center = Offset.Zero,
                style = Stroke(width = 52f)
            )
            drawCircle(
                color = DangerCrimson.copy(alpha = borderAlpha),
                radius = arenaR,
                center = Offset.Zero,
                style = Stroke(width = 11f)
            )

            // 3. 3D Bioluminescent energy orbs & shimmering death-drop loot (3 batched draw calls/orb)
            val netClientDraw = latestIsNetworkClient
            val nowDrawNanos = renderFrameNanos
            for (i in 0 until snapshot.orbs.size) {
                val orb = snapshot.orbs[i]
                if (netClientDraw && locallyEatenOrbs.containsKey(orb.id)) continue
                if (kotlin.math.abs(orb.x - camX) > viewHalfW ||
                    kotlin.math.abs(orb.y - camY) > viewHalfH
                ) {
                    continue
                }
                val colorIdx = orb.colorIndex.mod(OrbPalette.colors.size)
                val phase = pulsePhase + orb.id * 0.73f
                val pulse = 1f + (if (orb.isDeathDrop) 0.06f else 0.07f) * sin(phase)
                val driftAmp = if (orb.isDeathDrop) 1.5f else 1.2f
                val bobX = cos(phase * 0.85f) * driftAmp
                val bobY = sin(phase * 1.05f) * driftAmp

                var bloomScale = 1f
                var bloomAlpha = 1f
                if (orb.isDeathDrop) {
                    val spawnNs = deathOrbSpawnNanos[orb.id]
                    if (spawnNs != null && nowDrawNanos > spawnNs) {
                        val ageSec = (nowDrawNanos - spawnNs) / 1_000_000_000f
                        if (ageSec < 0.40f) {
                            val t = (ageSec / 0.40f).coerceIn(0f, 1f)
                            val inv = 1f - t
                            val easeOut = 1f - inv * inv * inv
                            bloomScale = 0.22f + 0.78f * easeOut + 0.10f * sin(t * PI.toFloat())
                            bloomAlpha = (ageSec / 0.14f).coerceIn(0.30f, 1f)
                        }
                    }
                }

                val r = orb.radius * pulse * bloomScale
                val cx = orb.x + bobX
                val cy = orb.y + bobY
                val haloR = r * (if (orb.isDeathDrop) 3.4f else 2.85f)
                val haloSize = (haloR * 2f).roundToInt().coerceAtLeast(4)

                // Outer soft bioluminescent aura
                drawImage(
                    image = orbGlowBitmap,
                    dstOffset = IntOffset((cx - haloR).roundToInt(), (cy - haloR).roundToInt()),
                    dstSize = IntSize(haloSize, haloSize),
                    alpha = (if (orb.isDeathDrop) 0.90f else 0.72f) * bloomAlpha,
                    colorFilter = orbColorFilters[colorIdx]
                )

                if (orb.isDeathDrop) {
                    // Steady soft loot star rays behind death-drop orbs (no harsh blinking)
                    val flareLen = r * 1.65f
                    val flareColor = Color.White.copy(alpha = 0.24f * bloomAlpha)
                    drawLine(
                        color = flareColor,
                        start = Offset(cx - flareLen, cy),
                        end = Offset(cx + flareLen, cy),
                        strokeWidth = 1.6f,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = flareColor,
                        start = Offset(cx, cy - flareLen),
                        end = Offset(cx, cy + flareLen),
                        strokeWidth = 1.6f,
                        cap = StrokeCap.Round
                    )
                }

                // Pre-baked 3D shaded orb sphere + crisp top-left glass glint
                val bodyR = r * 1.12f
                val bodySize = (bodyR * 2f).roundToInt().coerceAtLeast(3)
                drawImage(
                    image = orbBodyBitmap,
                    dstOffset = IntOffset((cx - bodyR).roundToInt(), (cy - bodyR).roundToInt()),
                    dstSize = IntSize(bodySize, bodySize),
                    alpha = bloomAlpha,
                    colorFilter = orbColorFilters[colorIdx]
                )
                drawCircle(
                    color = Color.White.copy(alpha = (if (orb.isDeathDrop) 0.88f else 0.72f) * bloomAlpha),
                    radius = (r * 0.25f).coerceAtLeast(1.1f),
                    center = Offset(cx - r * 0.28f, cy - r * 0.30f)
                )
            }

            // 3b. Orb-eat pop rings & full-body death burst sparks
            for (pIdx in 0 until particles.size) {
                val p = particles[pIdx]
                val progress = (p.age / p.maxAge).coerceIn(0f, 1f)
                val alpha = 1f - progress
                if (p.isRing) {
                    drawCircle(
                        color = p.color.copy(alpha = alpha * 0.78f),
                        radius = p.radius * (0.7f + progress * 2.5f),
                        center = Offset(p.x, p.y),
                        style = Stroke(width = (3.8f * (1f - progress)).coerceAtLeast(1f))
                    )
                } else {
                    drawCircle(
                        color = p.color.copy(alpha = alpha * 0.92f),
                        radius = p.radius * (1f - progress * 0.45f),
                        center = Offset(p.x, p.y)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = alpha * 0.65f),
                        radius = p.radius * 0.45f * (1f - progress * 0.45f),
                        center = Offset(p.x, p.y)
                    )
                }
            }

            // 3c. Animated dying worms (head-to-tail neon disintegration wave)
            for (dIdx in 0 until dyingWorms.size) {
                val anim = dyingWorms[dIdx]
                if (anim.count <= 0) continue
                val p = (anim.age / anim.duration).coerceIn(0f, 1f)
                val waveIndex = (p / 0.82f).coerceIn(0f, 1.18f) * anim.count
                val skin = WormSkins.byId(anim.skinId)
                val step = if (anim.count > 75) 2 else 1

                for (s in anim.count - 1 downTo 0 step step) {
                    val delta = waveIndex - s
                    if (delta > 3.8f) continue // already disintegrated
                    val sx = anim.segX[s]
                    val sy = anim.segY[s]
                    if (kotlin.math.abs(sx - camX) > viewHalfW + anim.radius * 2f ||
                        kotlin.math.abs(sy - camY) > viewHalfH + anim.radius * 2f
                    ) {
                        continue
                    }

                    val taper = tailTaper(s, anim.count)
                    val baseColor = segmentColor(skin, s, snapshot.tick)
                    var segAlpha = 1f - p * 0.25f
                    var scale = 1f
                    var drawCol = baseColor

                    if (delta >= -1.8f) {
                        val waveNorm = ((delta + 1.8f) / 5.6f).coerceIn(0f, 1f)
                        val swell = sin(waveNorm * PI.toFloat())
                        scale = if (waveNorm < 0.45f) {
                            1f + 0.32f * swell
                        } else {
                            ((1f - (waveNorm - 0.45f) / 0.55f) * 1.18f).coerceAtLeast(0.05f)
                        }
                        segAlpha *= (1f - waveNorm * 0.85f).coerceIn(0f, 1f)
                        drawCol = lerpColor(baseColor, Color.White, swell * 0.88f)
                    }

                    val segR = anim.radius * taper * scale
                    if (segR > 0.6f && segAlpha > 0.02f) {
                        drawCircle(
                            color = darkenColor(drawCol, 0.42f).copy(alpha = segAlpha),
                            radius = segR * 1.03f,
                            center = Offset(sx, sy)
                        )
                        drawCircle(
                            color = drawCol.copy(alpha = segAlpha),
                            radius = segR * 0.88f,
                            center = Offset(sx, sy)
                        )
                    }
                }

                // Bright energy ring sweeping along the dissolving segment
                val frontIdx = waveIndex.roundToInt().coerceIn(0, anim.count - 1)
                if (waveIndex < anim.count + 1.2f) {
                    val fx = anim.segX[frontIdx]
                    val fy = anim.segY[frontIdx]
                    drawCircle(
                        color = skin.glowColor.copy(alpha = (1f - p) * 0.85f),
                        radius = anim.radius * (1.45f + 0.45f * sin(p * 18f)),
                        center = Offset(fx, fy),
                        style = Stroke(width = 4.2f * (1f - p * 0.5f))
                    )
                }
            }

            // 4. Worms (rival worms first, local worm on top)
            for (pass in 0..1) {
                for (wIdx in 0 until snapshot.worms.size) {
                    val worm = snapshot.worms[wIdx]
                    val isLocal = worm.id == localWormId
                    if ((pass == 0 && isLocal) || (pass == 1 && !isLocal)) continue
                    if (!worm.isAlive || worm.segments.isEmpty()) continue

                    val smooth = smoothWorms[worm.id]
                    val activeSmooth = if (smooth != null && smooth.wasAlive && smooth.renderSegCount > 0) smooth else null
                    val drawSegCount = activeSmooth?.renderSegCount ?: worm.segments.size
                    val drawAngle = if (smooth != null && smooth.wasAlive) smooth.angle else worm.angle

                    // Fast AABB viewport cull for entire worm
                    var minX = Float.MAX_VALUE
                    var maxX = -Float.MAX_VALUE
                    var minY = Float.MAX_VALUE
                    var maxY = -Float.MAX_VALUE
                    val segs = worm.segments
                    for (s in 0 until drawSegCount step 3) {
                        val px = activeSmooth?.renderSegX?.get(s) ?: segs[s].x
                        val py = activeSmooth?.renderSegY?.get(s) ?: segs[s].y
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py
                    }
                    val tailRawX = activeSmooth?.renderSegX?.get(drawSegCount - 1) ?: segs.last().x
                    val tailRawY = activeSmooth?.renderSegY?.get(drawSegCount - 1) ?: segs.last().y
                    if (tailRawX < minX) minX = tailRawX
                    if (tailRawX > maxX) maxX = tailRawX
                    if (tailRawY < minY) minY = tailRawY
                    if (tailRawY > maxY) maxY = tailRawY

                    val pad = worm.radius * 2f + 80f
                    if (maxX + pad < camX - viewHalfW ||
                        minX - pad > camX + viewHalfW ||
                        maxY + pad < camY - viewHalfH ||
                        minY - pad > camY + viewHalfH
                    ) {
                        continue
                    }

                    val skin = WormSkins.byId(worm.skinId)
                    val bodyAlpha = if (worm.isSpawnProtected) {
                        0.58f + 0.32f * sin(pulsePhase * 4f)
                    } else {
                        1f
                    }

                    // Continuous smooth neon glow aura along spine when boosting (no flickering beads!)
                    if (worm.isBoosting && drawSegCount >= 2) {
                        glowSpinePath.reset()
                        for (s in 0 until drawSegCount step 2) {
                            val px = activeSmooth?.renderSegX?.get(s) ?: segs[s].x
                            val py = activeSmooth?.renderSegY?.get(s) ?: segs[s].y
                            if (s == 0) glowSpinePath.moveTo(px, py) else glowSpinePath.lineTo(px, py)
                        }
                        glowSpinePath.lineTo(tailRawX, tailRawY)

                        val auraPulse = 0.24f + 0.08f * sin(pulsePhase * 5f)
                        drawPath(
                            path = glowSpinePath,
                            color = skin.glowColor.copy(alpha = auraPulse * bodyAlpha),
                            style = Stroke(
                                width = worm.radius * 2.85f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                        drawPath(
                            path = glowSpinePath,
                            color = skin.accentColor.copy(alpha = 0.14f * bodyAlpha),
                            style = Stroke(
                                width = worm.radius * 2.25f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // Boost speed sparks behind tail
                        for (sp in 0..3) {
                            val sparkAngle = drawAngle + PI.toFloat() + (sp - 1.5f) * 0.35f
                            val sparkDist = worm.radius * 0.65f + ((snapshot.tick * 7 + sp * 11) % 32)
                            val sx = tailRawX + cos(sparkAngle) * sparkDist
                            val sy = tailRawY + sin(sparkAngle) * sparkDist
                            drawCircle(
                                color = skin.glowColor.copy(alpha = 0.62f),
                                radius = 4.5f - sp * 0.65f,
                                center = Offset(sx, sy)
                            )
                        }
                    }

                    // 3D Sculpted glossy body rendered in 3 clean passes so tail shadow/rim NEVER overlap tail color:
                    // Pass A: Torso floor shadow path + tapered tail shadows + head shadow
                    // Pass B: Torso outer contour rim path + tapered tail rims + head rim
                    // Pass C: Vibrant colored body cores + specular sheen (tail -> head)
                    val tailZone = (drawSegCount * 0.28f).roundToInt().coerceIn(5, 16)
                    val taperStart = (drawSegCount - tailZone).coerceAtLeast(1)
                    val torsoEnd = (taperStart - 1).coerceAtLeast(0)
                    val shadowColor = Color.Black.copy(alpha = 0.24f * bodyAlpha)
                    val torsoRimColor = darkenColor(
                        lerpColor(skin.primaryColor, skin.secondaryColor, 0.35f),
                        0.46f
                    ).copy(alpha = bodyAlpha)

                    if (torsoEnd >= 1) {
                        bodyShadowPath.reset()
                        bodyRimPath.reset()
                        val shadowOffY = worm.radius * 0.14f
                        val spineStep = if (torsoEnd > 60) 2 else 1
                        var firstPt = true
                        var idx = 0
                        while (idx <= torsoEnd) {
                            val px = activeSmooth?.renderSegX?.get(idx) ?: segs[idx].x
                            val py = activeSmooth?.renderSegY?.get(idx) ?: segs[idx].y
                            if (firstPt) {
                                bodyShadowPath.moveTo(px, py + shadowOffY)
                                bodyRimPath.moveTo(px, py)
                                firstPt = false
                            } else {
                                bodyShadowPath.lineTo(px, py + shadowOffY)
                                bodyRimPath.lineTo(px, py)
                            }
                            if (idx == torsoEnd) break
                            idx = (idx + spineStep).coerceAtMost(torsoEnd)
                        }
                        drawPath(
                            path = bodyShadowPath,
                            color = shadowColor,
                            style = Stroke(
                                width = worm.radius * 2.20f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }

                    // Pass A tail shadows + head shadow
                    for (s in drawSegCount - 1 downTo taperStart) {
                        val sx = activeSmooth?.renderSegX?.get(s) ?: segs[s].x
                        val sy = activeSmooth?.renderSegY?.get(s) ?: segs[s].y
                        if (kotlin.math.abs(sx - camX) > viewHalfW + pad ||
                            kotlin.math.abs(sy - camY) > viewHalfH + pad
                        ) continue
                        val segRadius = worm.radius * tailTaper(s, drawSegCount)
                        drawCircle(
                            color = shadowColor,
                            radius = segRadius * 1.10f,
                            center = Offset(sx, sy + segRadius * 0.14f)
                        )
                    }

                    // Pass B torso outer rim + tapered tail rims + head rim
                    if (torsoEnd >= 1) {
                        drawPath(
                            path = bodyRimPath,
                            color = torsoRimColor,
                            style = Stroke(
                                width = worm.radius * 2.02f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                    for (s in drawSegCount - 1 downTo taperStart) {
                        val sx = activeSmooth?.renderSegX?.get(s) ?: segs[s].x
                        val sy = activeSmooth?.renderSegY?.get(s) ?: segs[s].y
                        if (kotlin.math.abs(sx - camX) > viewHalfW + pad ||
                            kotlin.math.abs(sy - camY) > viewHalfH + pad
                        ) continue
                        val segRadius = worm.radius * tailTaper(s, drawSegCount)
                        val baseColor = segmentColor(skin, s, snapshot.tick)
                        drawCircle(
                            color = darkenColor(baseColor, 0.46f).copy(alpha = bodyAlpha),
                            radius = segRadius * 1.02f,
                            center = Offset(sx, sy)
                        )
                    }

                    // Pass C colored body cores + continuous specular sheen (tail -> head)
                    for (s in drawSegCount - 1 downTo 0) {
                        val sx = activeSmooth?.renderSegX?.get(s) ?: segs[s].x
                        val sy = activeSmooth?.renderSegY?.get(s) ?: segs[s].y
                        if (kotlin.math.abs(sx - camX) > viewHalfW + pad ||
                            kotlin.math.abs(sy - camY) > viewHalfH + pad
                        ) {
                            continue
                        }

                        val segPos = Offset(sx, sy)
                        val taper = tailTaper(s, drawSegCount)
                        val headScale = if (s == 0) 1.04f else 1f
                        val segRadius = worm.radius * taper * headScale
                        val baseColor = segmentColor(skin, s, snapshot.tick)

                        val boostWave = if (worm.isBoosting) {
                            0.5f + 0.5f * sin(s * 0.42f - pulsePhase * 4.5f)
                        } else {
                            0f
                        }
                        val litColor = if (boostWave > 0f) {
                            lerpColor(baseColor, skin.accentColor, boostWave * 0.44f)
                        } else {
                            baseColor
                        }
                        val bodyColor = litColor.copy(alpha = bodyAlpha)

                        if (s == 0) {
                            drawCircle(
                                color = darkenColor(litColor, 0.46f).copy(alpha = bodyAlpha),
                                radius = segRadius * 1.02f,
                                center = segPos
                            )
                        }

                        // Main vibrant body core shifted slightly up-left for 3D volume
                        drawCircle(
                            color = bodyColor,
                            radius = segRadius * 0.90f,
                            center = Offset(segPos.x - segRadius * 0.03f, segPos.y - segRadius * 0.04f)
                        )
                        // Continuous top-left specular sheen
                        drawCircle(
                            color = Color.White.copy(alpha = (if (s == 0) 0.16f else 0.095f) * bodyAlpha),
                            radius = segRadius * 0.54f,
                            center = Offset(segPos.x - segRadius * 0.21f, segPos.y - segRadius * 0.23f)
                        )
                    }

                    // Head details: shield, steering dot, snout nostrils, expressive eyes, crown & nameplate
                    val headX = activeSmooth?.renderSegX?.get(0) ?: segs.first().x
                    val headY = activeSmooth?.renderSegY?.get(0) ?: segs.first().y
                    if (worm.isSpawnProtected) {
                        drawCircle(
                            color = NeonCyan.copy(alpha = 0.65f),
                            radius = worm.radius * 1.56f,
                            center = Offset(headX, headY),
                            style = Stroke(width = 3.5f)
                        )
                    }

                    if (isLocal) {
                        val guideDist = worm.radius + 22f
                        val steerAng = steeringAngleState.value
                        drawCircle(
                            color = skin.accentColor.copy(alpha = 0.78f),
                            radius = 4.2f,
                            center = Offset(
                                headX + cos(steerAng) * guideDist,
                                headY + sin(steerAng) * guideDist
                            )
                        )
                    }

                    val cosA = cos(drawAngle)
                    val sinA = sin(drawAngle)
                    val perpX = -sinA
                    val perpY = cosA

                    // Subtle snout nostril dots
                    val snoutFwd = worm.radius * 0.76f
                    val nostrilSide = worm.radius * 0.17f
                    val nostrilR = (worm.radius * 0.075f).coerceAtLeast(1.3f)
                    val nostrilColor = darkenColor(skin.secondaryColor, 0.62f).copy(alpha = 0.7f * bodyAlpha)
                    drawCircle(
                        color = nostrilColor,
                        radius = nostrilR,
                        center = Offset(headX + cosA * snoutFwd + perpX * nostrilSide, headY + sinA * snoutFwd + perpY * nostrilSide)
                    )
                    drawCircle(
                        color = nostrilColor,
                        radius = nostrilR,
                        center = Offset(headX + cosA * snoutFwd - perpX * nostrilSide, headY + sinA * snoutFwd - perpY * nostrilSide)
                    )

                    val forwardOffset = worm.radius * 0.30f
                    val sideOffset = worm.radius * 0.44f
                    val eyeRadius = worm.radius * 0.32f
                    val pupilRadius = eyeRadius * (if (worm.isBoosting) 0.62f else 0.54f)

                    val leftEye = Offset(
                        headX + cosA * forwardOffset + perpX * sideOffset,
                        headY + sinA * forwardOffset + perpY * sideOffset
                    )
                    val rightEye = Offset(
                        headX + cosA * forwardOffset - perpX * sideOffset,
                        headY + sinA * forwardOffset - perpY * sideOffset
                    )

                    val socketColor = darkenColor(skin.secondaryColor, 0.55f).copy(alpha = 0.85f * bodyAlpha)
                    drawCircle(color = socketColor, radius = eyeRadius * 1.15f, center = leftEye)
                    drawCircle(color = socketColor, radius = eyeRadius * 1.15f, center = rightEye)
                    drawCircle(color = Color.White.copy(alpha = bodyAlpha), radius = eyeRadius, center = leftEye)
                    drawCircle(color = Color.White.copy(alpha = bodyAlpha), radius = eyeRadius, center = rightEye)

                    val lookCos = cos(worm.targetAngle)
                    val lookSin = sin(worm.targetAngle)
                    val pupilShift = eyeRadius * 0.34f
                    val leftPupil = Offset(leftEye.x + lookCos * pupilShift, leftEye.y + lookSin * pupilShift)
                    val rightPupil = Offset(rightEye.x + lookCos * pupilShift, rightEye.y + lookSin * pupilShift)

                    val irisColor = darkenColor(skin.primaryColor, 0.35f).copy(alpha = bodyAlpha)
                    drawCircle(color = irisColor, radius = pupilRadius * 1.18f, center = leftPupil)
                    drawCircle(color = irisColor, radius = pupilRadius * 1.18f, center = rightPupil)
                    drawCircle(color = Color(0xFF0A0D12).copy(alpha = bodyAlpha), radius = pupilRadius * 0.90f, center = leftPupil)
                    drawCircle(color = Color(0xFF0A0D12).copy(alpha = bodyAlpha), radius = pupilRadius * 0.90f, center = rightPupil)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f * bodyAlpha),
                        radius = pupilRadius * 0.34f,
                        center = Offset(leftPupil.x - pupilRadius * 0.25f, leftPupil.y - pupilRadius * 0.25f)
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f * bodyAlpha),
                        radius = pupilRadius * 0.34f,
                        center = Offset(rightPupil.x - pupilRadius * 0.25f, rightPupil.y - pupilRadius * 0.25f)
                    )

                    val displayName = if (worm.name.length > 11) {
                        worm.name.take(10) + "…"
                    } else {
                        worm.name
                    }
                    val cached = nameLayoutCache[worm.id]
                    val measured = if (cached != null && cached.first == displayName) {
                        cached.second
                    } else {
                        textMeasurer.measure(displayName, style = labelStyle).also {
                            nameLayoutCache[worm.id] = displayName to it
                        }
                    }
                    val labelY = headY - worm.radius - measured.size.height - 8f
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            x = headX - measured.size.width / 2f,
                            y = labelY
                        )
                    )

                    if (worm.id == leaderWormId) {
                        drawLeaderCrown(leaderCrownPath, center = Offset(headX, labelY - 9f))
                    }
                }
            }
        }

        // Danger vignette when approaching red arena boundary
        val localWorm = snapshot.worms.firstOrNull { it.id == localWormId }
        if (localWorm != null && localWorm.isAlive) {
            val distToWall = snapshot.arenaRadius - hypot(localWorm.headX, localWorm.headY)
            if (distToWall < 360f) {
                val dangerRatio = (1f - (distToWall / 360f)).coerceIn(0f, 1f)
                val pulse = 0.55f + 0.45f * sin(pulsePhase * 2.5f)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            DangerCrimson.copy(alpha = 0.36f * dangerRatio * pulse)
                        ),
                        center = Offset(centerX, centerY),
                        radius = minOf(size.width, size.height) * 0.65f
                    )
                )
            }
        }
    }
}

private fun createSlitherHexTileBrush(): ShaderBrush {
    val outerR = 68f
    val innerR = 61f
    val dx = outerR * 1.5f
    val dy = outerR * sqrt(3f)
    val tileW = (dx * 2f).roundToInt()
    val tileH = dy.roundToInt()

    val bitmap = ImageBitmap(tileW, tileH)
    val canvas = UiCanvas(bitmap)

    val bgPaint = Paint().apply { color = SlitherFloorGap }
    canvas.drawRect(0f, 0f, tileW.toFloat(), tileH.toFloat(), bgPaint)

    val fillPaint = Paint().apply {
        color = SlitherHexTile
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    val strokePaint = Paint().apply {
        color = SlitherHexHighlight
        style = PaintingStyle.Stroke
        strokeWidth = 1.6f
        isAntiAlias = true
    }

    val path = Path()
    for (col in -1..2) {
        val cx = col * dx
        val yOffset = if ((col and 1) != 0) dy * 0.5f else 0f
        for (row in -1..2) {
            val cy = row * dy + yOffset
            path.reset()
            for (v in 0 until 6) {
                val angle = (PI / 3.0 * v).toFloat()
                val vx = cx + innerR * cos(angle)
                val vy = cy + innerR * sin(angle)
                if (v == 0) path.moveTo(vx, vy) else path.lineTo(vx, vy)
            }
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, strokePaint)
        }
    }

    return ShaderBrush(
        ImageShader(
            image = bitmap,
            tileModeX = TileMode.Repeated,
            tileModeY = TileMode.Repeated
        )
    )
}

private fun createOrbGlowBitmap(): ImageBitmap {
    val size = 64
    val bitmap = ImageBitmap(size, size)
    val canvas = UiCanvas(bitmap)
    val center = Offset(size / 2f, size / 2f)
    val radius = size / 2f
    val brush = Brush.radialGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.88f),
            Color.White.copy(alpha = 0.32f),
            Color.Transparent
        ),
        center = center,
        radius = radius
    )
    val paint = Paint()
    brush.applyTo(Size(size.toFloat(), size.toFloat()), paint, 1f)
    canvas.drawCircle(center, radius, paint)
    return bitmap
}

private fun createOrbBodyBitmap(): ImageBitmap {
    val size = 64
    val bitmap = ImageBitmap(size, size)
    val canvas = UiCanvas(bitmap)
    val c = Offset(size / 2f, size / 2f)
    val radius = size * 0.46f

    // Dark outer rim + shaded core ready for BlendMode.Modulate color tinting
    val rimPaint = Paint().apply {
        color = Color(0xFFA2A2A2)
        isAntiAlias = true
    }
    canvas.drawCircle(c, radius, rimPaint)

    val coreBrush = Brush.radialGradient(
        colors = listOf(
            Color.White,
            Color(0xFFF0F0F0),
            Color(0xFFD2D2D2)
        ),
        center = Offset(c.x - radius * 0.18f, c.y - radius * 0.18f),
        radius = radius * 1.05f
    )
    val corePaint = Paint().apply { isAntiAlias = true }
    coreBrush.applyTo(Size(size.toFloat(), size.toFloat()), corePaint, 1f)
    canvas.drawCircle(c, radius * 0.88f, corePaint)
    return bitmap
}

private fun DrawScope.drawLeaderCrown(path: Path, center: Offset) {
    path.reset()
    path.moveTo(center.x - 8f, center.y + 4.5f)
    path.lineTo(center.x - 9.5f, center.y - 4.5f)
    path.lineTo(center.x - 3.5f, center.y)
    path.lineTo(center.x, center.y - 6.5f)
    path.lineTo(center.x + 3.5f, center.y)
    path.lineTo(center.x + 9.5f, center.y - 4.5f)
    path.lineTo(center.x + 8f, center.y + 4.5f)
    path.close()
    drawPath(path = path, color = SolarGold)
    drawPath(path = path, color = Color.White.copy(alpha = 0.6f), style = Stroke(width = 1.2f))
}

private fun tailTaper(index: Int, total: Int): Float {
    val tailZone = (total * 0.28f).roundToInt().coerceIn(5, 16)
    val startTail = total - tailZone
    return if (index >= startTail && tailZone > 1) {
        val t = ((total - 1 - index) / (tailZone - 1).toFloat()).coerceIn(0f, 1f)
        0.24f + 0.76f * sqrt(t)
    } else {
        1f
    }
}

private fun segmentColor(skin: WormSkin, index: Int, tick: Int): Color {
    if (skin.rainbowMode) {
        val hue = ((index * 11 - tick * 4) % 360 + 360) % 360
        return Color.hsv(hue.toFloat(), 0.86f, 1f)
    }
    if (!skin.stripePattern) {
        val wave = 0.5f + 0.5f * cos(index * 0.85f)
        return lerpColor(skin.primaryColor, skin.secondaryColor, wave * 0.28f)
    }
    val bandBlend = 0.5f - 0.5f * cos(index * 0.62f)
    return lerpColor(skin.primaryColor, skin.secondaryColor, bandBlend)
}

private fun darkenColor(c: Color, factor: Float): Color {
    val k = (1f - factor).coerceIn(0f, 1f)
    return Color(
        red = c.red * k,
        green = c.green * k,
        blue = c.blue * k,
        alpha = c.alpha
    )
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val clamped = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * clamped,
        green = a.green + (b.green - a.green) * clamped,
        blue = a.blue + (b.blue - a.blue) * clamped,
        alpha = a.alpha + (b.alpha - a.alpha) * clamped
    )
}

private fun stepAngleToward(current: Float, target: Float, maxDelta: Float): Float {
    var diff = target - current
    while (diff > PI) diff -= (2f * PI.toFloat())
    while (diff < -PI) diff += (2f * PI.toFloat())
    return if (kotlin.math.abs(diff) <= maxDelta) {
        target
    } else {
        current + kotlin.math.sign(diff) * maxDelta
    }
}
