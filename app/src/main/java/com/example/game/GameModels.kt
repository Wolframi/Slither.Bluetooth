package com.example.game

import androidx.compose.ui.graphics.Color

data class Vec2(
    val x: Float,
    val y: Float
)

data class WormSkin(
    val id: Int,
    val name: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val glowColor: Color,
    val accentColor: Color,
    val stripePattern: Boolean = true,
    val rainbowMode: Boolean = false
)

object WormSkins {
    val all: List<WormSkin> = listOf(
        WormSkin(
            id = 0,
            name = "Кибер-Циан",
            primaryColor = Color(0xFF00E5FF),
            secondaryColor = Color(0xFF0077B6),
            glowColor = Color(0xFF00F0FF),
            accentColor = Color(0xFFB8FFFF)
        ),
        WormSkin(
            id = 1,
            name = "Токсик-Лайм",
            primaryColor = Color(0xFF76FF03),
            secondaryColor = Color(0xFF1B8A2F),
            glowColor = Color(0xFF80FF3B),
            accentColor = Color(0xFFE2FFCC)
        ),
        WormSkin(
            id = 2,
            name = "Магма-Пульс",
            primaryColor = Color(0xFFFF5252),
            secondaryColor = Color(0xFFB71C1C),
            glowColor = Color(0xFFFF6E40),
            accentColor = Color(0xFFFFD08A)
        ),
        WormSkin(
            id = 3,
            name = "Неон-Маджента",
            primaryColor = Color(0xFFFF2E93),
            secondaryColor = Color(0xFF8E24AA),
            glowColor = Color(0xFFFF2E93),
            accentColor = Color(0xFFFFB3D9)
        ),
        WormSkin(
            id = 4,
            name = "Солнечный Голд",
            primaryColor = Color(0xFFFFD600),
            secondaryColor = Color(0xFFE65100),
            glowColor = Color(0xFFFFEA00),
            accentColor = Color(0xFFFFF6B3)
        ),
        WormSkin(
            id = 5,
            name = "Ультра-Фиолет",
            primaryColor = Color(0xFFB388FF),
            secondaryColor = Color(0xFF512DA8),
            glowColor = Color(0xFFCE93D8),
            accentColor = Color(0xFFEDE0FF)
        ),
        WormSkin(
            id = 6,
            name = "Арктический Лёд",
            primaryColor = Color(0xFFE0F7FA),
            secondaryColor = Color(0xFF0097A7),
            glowColor = Color(0xFF84FFFF),
            accentColor = Color(0xFFFFFFFF),
            stripePattern = false
        ),
        WormSkin(
            id = 7,
            name = "Призма",
            primaryColor = Color(0xFF00F0FF),
            secondaryColor = Color(0xFFFF2E93),
            glowColor = Color(0xFF80FF3B),
            accentColor = Color(0xFFFFFFFF),
            stripePattern = false,
            rainbowMode = true
        )
    )

    fun byId(id: Int): WormSkin = all.getOrElse(id.coerceIn(0, all.lastIndex)) { all.first() }
}

object OrbPalette {
    val colors: List<Color> = listOf(
        Color(0xFF3CE8FF), // Electric Cyan
        Color(0xFF72FF59), // Neon Lime
        Color(0xFFFF4DA6), // Hot Magenta
        Color(0xFFFFDC4A), // Solar Amber
        Color(0xFFFF7A45), // Magma Coral
        Color(0xFFB57CFF), // Neon Violet
        Color(0xFF36FFC2), // Mint Glow
        Color(0xFF5CA8FF)  // Sky Azure
    )

    fun byIndex(index: Int): Color = colors[index.mod(colors.size)]
}

data class FoodOrb(
    val id: Int,
    val x: Float,
    val y: Float,
    val value: Int,
    val radius: Float,
    val colorIndex: Int,
    val isDeathDrop: Boolean = false
)

data class WormState(
    val id: Int,
    val name: String,
    val skinId: Int,
    val isBot: Boolean,
    val isAlive: Boolean,
    val isSpawnProtected: Boolean,
    val headX: Float,
    val headY: Float,
    val angle: Float,
    val targetAngle: Float,
    val score: Int,
    val kills: Int,
    val isBoosting: Boolean,
    val radius: Float,
    val segments: List<Vec2>
)

data class LeaderboardEntry(
    val wormId: Int,
    val name: String,
    val score: Int,
    val skinId: Int,
    val isBot: Boolean
)

data class DeathSummary(
    val finalScore: Int,
    val kills: Int,
    val survivalSeconds: Int,
    val killerName: String?,
    val hitBoundary: Boolean
)

data class ArenaSnapshot(
    val tick: Int = 0,
    val arenaRadius: Float = 2200f,
    val worms: List<WormState> = emptyList(),
    val orbs: List<FoodOrb> = emptyList(),
    val leaderboard: List<LeaderboardEntry> = emptyList(),
    val killFeedText: String? = null
)

enum class GameMode {
    SOLO_BOTS,
    BT_HOST,
    BT_CLIENT,
    ONLINE_HOST,
    ONLINE_CLIENT
}

enum class ControlMode(val label: String) {
    TOUCH_FOLLOW("Свайп + 2× тап"),
    JOYSTICK("Джойстик")
}

enum class BotDifficulty(val label: String, val reactionScale: Float, val aggressionChance: Float) {
    CHILL("Спокойные", 0.72f, 0.15f),
    BALANCED("Умные", 1.0f, 0.45f),
    HARDCORE("Хищники", 1.35f, 0.80f)
}

enum class ArenaSizeOption(val label: String, val radius: Float, val maxOrbs: Int, val botCount: Int) {
    COMPACT("Компактная", 1600f, 260, 5),
    STANDARD("Стандарт", 2200f, 420, 8),
    MEGA("Мега-арена", 2900f, 620, 12)
}

object PlayerIdentityGenerator {
    private val prefixes = listOf(
        "Кобра", "Неон", "Вихрь", "Молния", "Призрак", "Питон",
        "Импульс", "Тайфун", "Ракета", "Альфа", "Вольт", "Спайк",
        "Феникс", "Комета", "Орбита", "Дрифт", "Зенит", "Лазер"
    )

    fun randomNickname(): String {
        val word = prefixes.random()
        val num = kotlin.random.Random.nextInt(10, 999)
        return "$word-$num"
    }

    fun randomSkinId(): Int = kotlin.random.Random.nextInt(WormSkins.all.size)
}

