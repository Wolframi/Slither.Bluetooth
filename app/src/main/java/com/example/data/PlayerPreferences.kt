package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.game.BotDifficulty
import com.example.game.ControlMode
import com.example.game.PlayerIdentityGenerator
import com.example.game.WormSkins
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.playerDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "slither_bt_prefs"
)

data class SavedPlayerProfile(
    val nickname: String = "Игрок",
    val skinId: Int = 0,
    val bestScore: Int = 0,
    val totalKills: Int = 0,
    val hostIncludeBots: Boolean = true,
    val botDifficulty: BotDifficulty = BotDifficulty.BALANCED,
    val controlMode: ControlMode = ControlMode.TOUCH_FOLLOW
)

class PlayerPreferences(private val context: Context) {

    private object Keys {
        val NICKNAME = stringPreferencesKey("nickname")
        val SKIN_ID = intPreferencesKey("skin_id")
        val BEST_SCORE = intPreferencesKey("best_score")
        val TOTAL_KILLS = intPreferencesKey("total_kills")
        val HOST_INCLUDE_BOTS = booleanPreferencesKey("host_include_bots")
        val BOT_DIFFICULTY = intPreferencesKey("bot_difficulty")
        val CONTROL_MODE = intPreferencesKey("control_mode")
    }

    private val generatedFallbackNickname: String = PlayerIdentityGenerator.randomNickname()
    private val generatedFallbackSkinId: Int = PlayerIdentityGenerator.randomSkinId()

    fun initialDefaultProfile(): SavedPlayerProfile = SavedPlayerProfile(
        nickname = generatedFallbackNickname,
        skinId = generatedFallbackSkinId
    )

    val profileFlow: Flow<SavedPlayerProfile> = context.playerDataStore.data.map { prefs ->
        val diffIndex = (prefs[Keys.BOT_DIFFICULTY] ?: BotDifficulty.BALANCED.ordinal)
            .coerceIn(0, BotDifficulty.entries.lastIndex)
        val ctrlIndex = (prefs[Keys.CONTROL_MODE] ?: ControlMode.TOUCH_FOLLOW.ordinal)
            .coerceIn(0, ControlMode.entries.lastIndex)
        val rawSkinId = prefs[Keys.SKIN_ID] ?: generatedFallbackSkinId
        SavedPlayerProfile(
            nickname = prefs[Keys.NICKNAME]?.takeIf { it.isNotBlank() } ?: generatedFallbackNickname,
            skinId = rawSkinId.coerceIn(0, WormSkins.all.lastIndex),
            bestScore = prefs[Keys.BEST_SCORE] ?: 0,
            totalKills = prefs[Keys.TOTAL_KILLS] ?: 0,
            hostIncludeBots = prefs[Keys.HOST_INCLUDE_BOTS] ?: true,
            botDifficulty = BotDifficulty.entries[diffIndex],
            controlMode = ControlMode.entries[ctrlIndex]
        )
    }

    suspend fun ensureInitialIdentity() {
        context.playerDataStore.edit { prefs ->
            if (prefs[Keys.NICKNAME].isNullOrBlank()) {
                prefs[Keys.NICKNAME] = generatedFallbackNickname
            }
            if (!prefs.contains(Keys.SKIN_ID)) {
                prefs[Keys.SKIN_ID] = generatedFallbackSkinId
            }
        }
    }

    suspend fun saveNickname(nickname: String) {
        val clean = nickname.trim().take(16).ifEmpty { generatedFallbackNickname }
        context.playerDataStore.edit { it[Keys.NICKNAME] = clean }
    }

    suspend fun saveSkinId(skinId: Int) {
        context.playerDataStore.edit { it[Keys.SKIN_ID] = skinId.coerceIn(0, WormSkins.all.lastIndex) }
    }

    suspend fun saveHostIncludeBots(includeBots: Boolean) {
        context.playerDataStore.edit { it[Keys.HOST_INCLUDE_BOTS] = includeBots }
    }

    suspend fun saveBotDifficulty(difficulty: BotDifficulty) {
        context.playerDataStore.edit { it[Keys.BOT_DIFFICULTY] = difficulty.ordinal }
    }

    suspend fun saveControlMode(mode: ControlMode) {
        context.playerDataStore.edit { it[Keys.CONTROL_MODE] = mode.ordinal }
    }

    suspend fun recordMatchStats(score: Int, kills: Int) {
        context.playerDataStore.edit { prefs ->
            val currentBest = prefs[Keys.BEST_SCORE] ?: 0
            if (score > currentBest) {
                prefs[Keys.BEST_SCORE] = score
            }
            val currentKills = prefs[Keys.TOTAL_KILLS] ?: 0
            prefs[Keys.TOTAL_KILLS] = currentKills + kills
        }
    }
}
