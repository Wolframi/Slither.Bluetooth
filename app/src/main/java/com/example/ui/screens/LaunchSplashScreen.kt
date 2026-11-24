package com.example.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.OrbPalette
import com.example.ui.theme.AbyssBackground
import com.example.ui.theme.MagentaPulse
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonLime
import com.example.ui.theme.SolarGold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private data class SplashMote(
    val xFrac: Float,
    val yFrac: Float,
    val radius: Float,
    val speedMult: Float,
    val phaseOffset: Float,
    val colorIndex: Int
)

@Composable
fun LaunchSplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.80f) }
    val contentAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch {
            contentAlpha.animateTo(1f, animationSpec = tween(380))
        }
        launch {
            logoScale.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1900, easing = FastOutSlowInEasing)
        )
        delay(100L)
        onFinished()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "splash_loop")
    val phaseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_phase"
    )
    val pulseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_pulse"
    )

    // Derived milestone states so Compose recomposes only ~20 times max, not 60x/sec
    val statusStage by remember {
        derivedStateOf {
            when {
                progress.value < 0.34f -> 0
                progress.value < 0.72f -> 1
                else -> 2
            }
        }
    }
    val progressPercent by remember {
        derivedStateOf {
            ((progress.value * 20f).toInt().coerceIn(0, 20)) * 5
        }
    }

    // Pre-baked GPU resources & reusable buffers (0 per-frame allocations -> locked 60 FPS!)
    val hexTileBrush = remember { createSplashHexBrush() }
    val glowSprite = remember { createSplashGlowSprite() }
    val orbColorFilters = remember {
        OrbPalette.colors.map { ColorFilter.tint(it, BlendMode.Modulate) }
    }
    val cyanFilter = remember { ColorFilter.tint(NeonCyan, BlendMode.Modulate) }
    val limeFilter = remember { ColorFilter.tint(NeonLime, BlendMode.Modulate) }
    val magentaFilter = remember { ColorFilter.tint(MagentaPulse, BlendMode.Modulate) }

    val trackPath = remember { Path() }
    val spinePath = remember { Path() }
    val segXs = remember { FloatArray(32) }
    val segYs = remember { FloatArray(32) }
    val cachedTrackDims = remember { floatArrayOf(-1f, -1f) }

    val motes = remember {
        List(16) { idx ->
            SplashMote(
                xFrac = ((idx * 37 + 13) % 100) / 100f,
                yFrac = ((idx * 53 + 29) % 100) / 100f,
                radius = 1.8f + (idx % 3) * 1.1f,
                speedMult = 0.7f + (idx % 4) * 0.25f,
                phaseOffset = idx * 0.85f,
                colorIndex = idx % OrbPalette.colors.size
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070B12))
            .testTag("launch_splash_screen")
    ) {
        // 60 FPS GPU-friendly Canvas: 1 drawRect hex grid + cached glow sprites + reusable paths
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cxCenter = w * 0.5f
            val cyCenter = h * 0.43f
            val phase = phaseState.value
            val pulse = pulseState.value
            val prog = progress.value

            // 1. Pre-baked seamless hex arena floor in ONE GPU draw call
            drawRect(brush = hexTileBrush)

            // 2. Soft ambient dual-tone core glow via pre-baked sprite (zero shader allocations)
            val centerGlowR = minOf(w, h) * (0.48f + pulse * 0.05f)
            val centerGlowD = (centerGlowR * 2f).roundToInt().coerceAtLeast(8)
            drawImage(
                image = glowSprite,
                dstOffset = IntOffset((cxCenter - centerGlowR).roundToInt(), (cyCenter - centerGlowR).roundToInt()),
                dstSize = IntSize(centerGlowD, centerGlowD),
                alpha = 0.30f + pulse * 0.08f,
                colorFilter = cyanFilter
            )
            val secGlowR = centerGlowR * 0.62f
            val secGlowD = (secGlowR * 2f).roundToInt().coerceAtLeast(8)
            drawImage(
                image = glowSprite,
                dstOffset = IntOffset(
                    (cxCenter - secGlowR + cos(phase) * 28f).roundToInt(),
                    (cyCenter - secGlowR + sin(phase) * 18f).roundToInt()
                ),
                dstSize = IntSize(secGlowD, secGlowD),
                alpha = 0.20f,
                colorFilter = magentaFilter
            )

            // 3. Expanding neon sonar pulse ring across the hex arena
            val sonarRadius = prog * minOf(w, h) * 0.78f + 42f
            val sonarAlpha = (1f - prog * 0.75f).coerceIn(0f, 1f) * 0.36f
            drawCircle(
                color = NeonCyan.copy(alpha = sonarAlpha),
                radius = sonarRadius,
                center = Offset(cxCenter, cyCenter),
                style = Stroke(width = 3.2f)
            )
            drawCircle(
                color = NeonLime.copy(alpha = sonarAlpha * 0.45f),
                radius = (sonarRadius - 22f).coerceAtLeast(8f),
                center = Offset(cxCenter, cyCenter),
                style = Stroke(width = 1.6f)
            )

            // 4. Floating bokeh energy motes for atmospheric depth
            for (mIdx in motes.indices) {
                val m = motes[mIdx]
                val mx = m.xFrac * w + sin(phase * m.speedMult + m.phaseOffset) * 16f
                val my = m.yFrac * h + cos(phase * m.speedMult * 0.8f + m.phaseOffset) * 14f
                val moteAlpha = 0.22f + 0.25f * sin(phase * 1.5f + m.phaseOffset)
                drawCircle(
                    color = OrbPalette.colors[m.colorIndex].copy(alpha = moteAlpha.coerceIn(0.05f, 0.55f)),
                    radius = m.radius,
                    center = Offset(mx, my)
                )
            }

            // 5. Figure-eight infinity track (cached Path rebuilt ONLY when dimensions change)
            val ampX = minOf(w * 0.36f, 290f)
            val ampY = minOf(h * 0.145f, 134f)
            if (cachedTrackDims[0] != w || cachedTrackDims[1] != h) {
                cachedTrackDims[0] = w
                cachedTrackDims[1] = h
                trackPath.reset()
                val trackSteps = 72
                for (s in 0..trackSteps) {
                    val t = (s.toFloat() / trackSteps) * (2f * PI.toFloat())
                    val px = cxCenter + sin(t) * ampX
                    val py = cyCenter + sin(2f * t) * ampY
                    if (s == 0) trackPath.moveTo(px, py) else trackPath.lineTo(px, py)
                }
                trackPath.close()
            }
            drawPath(
                path = trackPath,
                color = NeonCyan.copy(alpha = 0.10f),
                style = Stroke(width = 3.2f)
            )

            // 6. Counter-rotating HUD orbital arcs framing the center title
            val ringRadius = minOf(w, h) * 0.19f
            val sweepDeg = phase * (180f / PI.toFloat())
            drawArc(
                color = NeonCyan.copy(alpha = 0.42f),
                startAngle = sweepDeg,
                sweepAngle = 92f,
                useCenter = false,
                topLeft = Offset(cxCenter - ringRadius, cyCenter - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
            drawArc(
                color = NeonLime.copy(alpha = 0.38f),
                startAngle = sweepDeg + 180f,
                sweepAngle = 78f,
                useCenter = false,
                topLeft = Offset(cxCenter - ringRadius, cyCenter - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = 4f, cap = StrokeCap.Round)
            )
            val innerRingR = ringRadius * 0.78f
            drawArc(
                color = MagentaPulse.copy(alpha = 0.32f),
                startAngle = -sweepDeg * 1.35f,
                sweepAngle = 115f,
                useCenter = false,
                topLeft = Offset(cxCenter - innerRingR, cyCenter - innerRingR),
                size = Size(innerRingR * 2f, innerRingR * 2f),
                style = Stroke(width = 2.6f, cap = StrokeCap.Round)
            )

            // 7. Collectible 3D orbs along the infinity loop (zero radialGradient allocations!)
            val heroHeadX = cxCenter + sin(phase) * ampX
            val heroHeadY = cyCenter + sin(2f * phase) * ampY
            val orbCount = 10
            for (o in 0 until orbCount) {
                val ot = (o.toFloat() / orbCount) * (2f * PI.toFloat())
                val ox = cxCenter + sin(ot) * ampX
                val oy = cyCenter + sin(2f * ot) * ampY + sin(phase * 2f + o * 0.9f) * 3.5f
                val distToHead = hypot(ox - heroHeadX, oy - heroHeadY)
                val eatFactor = ((56f - distToHead) / 56f).coerceIn(0f, 1f)
                val colorIdx = o % OrbPalette.colors.size
                val orbColor = OrbPalette.colors[colorIdx]
                val orbRadius = (6.4f + (o % 3) * 1.6f) * (1f - eatFactor * 0.55f)

                val haloR = orbRadius * 3.1f
                val haloD = (haloR * 2f).roundToInt().coerceAtLeast(4)
                drawImage(
                    image = glowSprite,
                    dstOffset = IntOffset((ox - haloR).roundToInt(), (oy - haloR).roundToInt()),
                    dstSize = IntSize(haloD, haloD),
                    alpha = 0.78f,
                    colorFilter = orbColorFilters[colorIdx]
                )

                if (eatFactor > 0.14f) {
                    for (sp in 0 until 6) {
                        val sa = sp * (PI / 3.0).toFloat() + phase
                        val sr = orbRadius * (1.2f + eatFactor * 2.6f)
                        drawCircle(
                            color = orbColor.copy(alpha = eatFactor * 0.92f),
                            radius = 2.5f,
                            center = Offset(ox + cos(sa) * sr, oy + sin(sa) * sr)
                        )
                    }
                }

                val rimColor = Color(orbColor.red * 0.58f, orbColor.green * 0.58f, orbColor.blue * 0.58f)
                drawCircle(color = rimColor, radius = orbRadius, center = Offset(ox, oy))
                drawCircle(color = orbColor, radius = orbRadius * 0.85f, center = Offset(ox, oy))
                drawCircle(
                    color = Color.White.copy(alpha = 0.88f),
                    radius = orbRadius * 0.27f,
                    center = Offset(ox - orbRadius * 0.28f, oy - orbRadius * 0.28f)
                )
            }

            // 8. Two sleek 3D slither worms using pre-allocated buffers (0 heap allocations per frame)
            drawSplashWormFast(
                headT = phase + PI.toFloat(),
                cxCenter = cxCenter,
                cyCenter = cyCenter,
                ampX = ampX,
                ampY = ampY,
                primary = MagentaPulse,
                secondary = Color(0xFF7B2CBF),
                segCount = 24,
                baseRadius = 13.2f,
                pulse = pulse,
                showCrown = false,
                spinePath = spinePath,
                segXs = segXs,
                segYs = segYs
            )
            drawSplashWormFast(
                headT = phase,
                cxCenter = cxCenter,
                cyCenter = cyCenter,
                ampX = ampX,
                ampY = ampY,
                primary = NeonLime,
                secondary = NeonCyan,
                segCount = 28,
                baseRadius = 16.4f,
                pulse = pulse,
                showCrown = true,
                spinePath = spinePath,
                segXs = segXs,
                segYs = segYs
            )
        }

        // Foreground Brand + Animated Progress Deck (GPU-layered transforms, zero per-frame relayouts)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 32.dp)
                .graphicsLayer { alpha = contentAlpha.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = NeonCyan.copy(alpha = 0.12f),
                modifier = Modifier.border(1.dp, NeonCyan.copy(alpha = 0.38f), RoundedCornerShape(50))
            ) {
                Text(
                    text = "ИНТЕРНЕТ • BLUETOOTH • БОТЫ",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                    color = NeonCyan,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer {
                        val s = logoScale.value
                        scaleX = s
                        scaleY = s
                    }
                    .padding(bottom = 18.dp)
            ) {
                Text(
                    text = "slither.bt",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = NeonLime
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "НЕОНОВАЯ АРЕНА",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.6.sp
                    ),
                    color = Color.White.copy(alpha = 0.86f)
                )
            }

            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedContent(
                        targetState = statusStage,
                        transitionSpec = {
                            fadeIn(tween(240)) togetherWith fadeOut(tween(180))
                        },
                        label = "splash_status_text",
                        modifier = Modifier.weight(1f)
                    ) { stage ->
                        val label = when (stage) {
                            0 -> "Разогреваем неоновую арену..."
                            1 -> "Заряжаем энергетические сферы..."
                            else -> "Готовим ботов и мультиплеер..."
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start
                        )
                    }
                    Text(
                        text = "$progressPercent%",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NeonCyan
                    )
                }

                // 60 FPS GPU-drawn neon progress bar with glowing comet flare at leading tip
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFF111926))
                        .border(1.dp, Color(0xFF263852), RoundedCornerShape(50))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val frac = progress.value.coerceIn(0.02f, 1f)
                        val barW = size.width * frac
                        val corner = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(NeonCyan, NeonLime),
                                startX = 0f,
                                endX = size.width
                            ),
                            topLeft = Offset.Zero,
                            size = Size(barW, size.height),
                            cornerRadius = corner
                        )
                        val tipX = (barW - 2f).coerceIn(4f, size.width - 2f)
                        val tipY = size.height * 0.5f
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = size.height * 0.36f,
                            center = Offset(tipX, tipY)
                        )
                        val flareR = size.height * 1.6f
                        val flareD = (flareR * 2f).roundToInt().coerceAtLeast(4)
                        drawImage(
                            image = glowSprite,
                            dstOffset = IntOffset((tipX - flareR).roundToInt(), (tipY - flareR).roundToInt()),
                            dstSize = IntSize(flareD, flareD),
                            alpha = 0.9f,
                            colorFilter = limeFilter
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSplashWormFast(
    headT: Float,
    cxCenter: Float,
    cyCenter: Float,
    ampX: Float,
    ampY: Float,
    primary: Color,
    secondary: Color,
    segCount: Int,
    baseRadius: Float,
    pulse: Float,
    showCrown: Boolean,
    spinePath: Path,
    segXs: FloatArray,
    segYs: FloatArray
) {
    spinePath.reset()
    for (i in 0 until segCount) {
        val t = headT - i * 0.068f
        val px = cxCenter + sin(t) * ampX
        val py = cyCenter + sin(2f * t) * ampY
        segXs[i] = px
        segYs[i] = py
        if (i == 0) spinePath.moveTo(px, py) else spinePath.lineTo(px, py)
    }

    // Continuous neon spine boost aura
    drawPath(
        path = spinePath,
        color = primary.copy(alpha = 0.22f + pulse * 0.10f),
        style = Stroke(
            width = baseRadius * 2.55f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )

    val tailStart = (segCount * 0.74f).toInt()
    for (i in segCount - 1 downTo 0) {
        val px = segXs[i]
        val py = segYs[i]
        val taper = if (i >= tailStart) {
            val frac = (segCount - 1 - i).toFloat() / (segCount - 1 - tailStart).coerceAtLeast(1)
            0.40f + 0.60f * frac
        } else 1f
        val r = baseRadius * taper * (if (i == 0) 1.07f else 1f)

        val blend = 0.5f - 0.5f * cos(i * 0.58f)
        val bodyColor = Color(
            red = primary.red + (secondary.red - primary.red) * blend,
            green = primary.green + (secondary.green - primary.green) * blend,
            blue = primary.blue + (secondary.blue - primary.blue) * blend
        )
        val rimColor = Color(
            red = bodyColor.red * 0.54f,
            green = bodyColor.green * 0.54f,
            blue = bodyColor.blue * 0.54f
        )

        drawCircle(
            color = Color.Black.copy(alpha = 0.26f),
            radius = r * 1.12f,
            center = Offset(px, py + r * 0.14f)
        )
        drawCircle(color = rimColor, radius = r * 1.02f, center = Offset(px, py))
        drawCircle(
            color = bodyColor,
            radius = r * 0.90f,
            center = Offset(px - r * 0.03f, py - r * 0.04f)
        )
        drawCircle(
            color = Color.White.copy(alpha = if (i == 0) 0.16f else 0.085f),
            radius = r * 0.54f,
            center = Offset(px - r * 0.20f, py - r * 0.22f)
        )
    }

    val hx = segXs[0]
    val hy = segYs[0]
    val heading = atan2(hy - segYs[1], hx - segXs[1])
    val cosH = cos(heading)
    val sinH = sin(heading)
    val eyeFwd = baseRadius * 0.30f
    val eyeSide = baseRadius * 0.44f

    for (side in -1..1 step 2) {
        val ex = hx + cosH * eyeFwd - sinH * eyeSide * side
        val ey = hy + sinH * eyeFwd + cosH * eyeSide * side
        drawCircle(color = Color(0xFF1A222D), radius = baseRadius * 0.37f, center = Offset(ex, ey))
        drawCircle(color = Color.White, radius = baseRadius * 0.32f, center = Offset(ex, ey))
        val px = ex + cosH * baseRadius * 0.11f
        val py = ey + sinH * baseRadius * 0.11f
        drawCircle(color = AbyssBackground, radius = baseRadius * 0.17f, center = Offset(px, py))
        drawCircle(
            color = Color.White,
            radius = baseRadius * 0.06f,
            center = Offset(px - baseRadius * 0.05f, py - baseRadius * 0.05f)
        )
    }

    if (showCrown) {
        val cy = hy - baseRadius - 9f
        spinePath.reset()
        spinePath.moveTo(hx - 7.5f, cy + 4f)
        spinePath.lineTo(hx - 9f, cy - 4f)
        spinePath.lineTo(hx - 3f, cy)
        spinePath.lineTo(hx, cy - 6f)
        spinePath.lineTo(hx + 3f, cy)
        spinePath.lineTo(hx + 9f, cy - 4f)
        spinePath.lineTo(hx + 7.5f, cy + 4f)
        spinePath.close()
        drawPath(path = spinePath, color = SolarGold)
        drawPath(path = spinePath, color = Color.White.copy(alpha = 0.65f), style = Stroke(width = 1.1f))
    }
}

private fun createSplashHexBrush(): ShaderBrush {
    val outerR = 54f
    val innerR = 48.5f
    val dx = outerR * 1.5f
    val dy = outerR * sqrt(3f)
    val tileW = (dx * 2f).roundToInt()
    val tileH = dy.roundToInt()

    val bitmap = ImageBitmap(tileW, tileH)
    val canvas = UiCanvas(bitmap)

    val bgPaint = Paint().apply { color = Color(0xFF070B12) }
    canvas.drawRect(0f, 0f, tileW.toFloat(), tileH.toFloat(), bgPaint)

    val fillPaint = Paint().apply {
        color = Color(0xFF0F151F)
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    val strokePaint = Paint().apply {
        color = Color(0xFF1A2535)
        style = PaintingStyle.Stroke
        strokeWidth = 1.4f
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

private fun createSplashGlowSprite(): ImageBitmap {
    val size = 64
    val bitmap = ImageBitmap(size, size)
    val canvas = UiCanvas(bitmap)
    val center = Offset(size / 2f, size / 2f)
    val radius = size / 2f
    val brush = Brush.radialGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.88f),
            Color.White.copy(alpha = 0.30f),
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
