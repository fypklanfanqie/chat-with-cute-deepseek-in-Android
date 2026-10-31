package com.rhodesisland.terminal.whale.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rhodesisland.terminal.data.model.ApiConfig
import com.rhodesisland.terminal.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** 鲸鱼娘模块的全部可调设置。 */
data class WhaleSettings(
    val userName: String = "",
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-flash",
    val temperature: Float = 0.9f,
    val maxTokens: Int = 2048,
    val autoRead: Boolean = false,
    val voiceRate: Float = 1.06f,
    val voicePitch: Float = 1.02f,
    /** 朗读引擎：system = 手机自带 TTS（离线）；cloud = 火山云端（复用原 App 的 TTS 凭据）。 */
    val voiceEngine: String = VOICE_SYSTEM,
    val deepThinking: Boolean = false,
    /** whale = 蓝白海洋皮肤；prts = 原深色终端皮肤 */
    val skin: String = SKIN_WHALE,
    val stickerEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    /** 当前使用的人设：空串 = 内置鲸鱼娘；否则为自定义角色 id。 */
    val personaId: String = "",
) {
    companion object {
        const val SKIN_WHALE = "whale"
        const val SKIN_PRTS = "prts"
        const val VOICE_SYSTEM = "system"
        const val VOICE_CLOUD = "cloud"
    }
}

/**
 * API Key 加密存储。
 *
 * 用 EncryptedSharedPreferences（AES-256-GCM，密钥托管在 Android Keystore）。
 * 任何 Keystore 异常（换机恢复备份、ROM 魔改）都退化为「未配置」，绝不抛到启动路径——
 * 这是本工程既有代码反复强调的容错原则（见 DataStoreCorruption）。
 */
class WhaleSecretStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                "whale_secrets",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure { Log.w(TAG, "EncryptedSharedPreferences 初始化失败，降级为未配置: ${it.message}") }
            .getOrNull()
    }

    fun getApiKey(): String = runCatching { prefs?.getString(KEY_API_KEY, "").orEmpty() }
        .getOrDefault("")

    fun setApiKey(value: String) {
        runCatching { prefs?.edit()?.putString(KEY_API_KEY, value.trim())?.apply() }
            .onFailure { Log.w(TAG, "写 API Key 失败: ${it.message}") }
    }

    companion object {
        private const val TAG = "WhaleSecretStore"
        private const val KEY_API_KEY = "deepseek_api_key"
    }
}

private val Context.whaleDataStore: DataStore<Preferences> by preferencesDataStore(name = "whale_settings")

/**
 * 设置仓库：独立 DataStore（`whale_settings`），与主 App 的 `rhodes_settings` 互不干扰，
 * 避免为了一个模块去改 1300 行的 SettingsStore。
 */
class WhaleSettingsRepository(
    context: Context,
    /** 主 App 的设置仓库：首次进入鲸鱼娘时，若本模块未配 Key 则复用主设置里的 DeepSeek Key。 */
    private val hostSettings: SettingsRepository? = null,
) {
    private val store = context.applicationContext.whaleDataStore
    private val secrets = WhaleSecretStore(context)

    private object Keys {
        val USER_NAME = stringPreferencesKey("user_name")
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val MAX_TOKENS = intPreferencesKey("max_tokens")
        val AUTO_READ = booleanPreferencesKey("auto_read")
        val VOICE_RATE = floatPreferencesKey("voice_rate")
        val VOICE_PITCH = floatPreferencesKey("voice_pitch")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val DEEP_THINKING = booleanPreferencesKey("deep_thinking")
        val SKIN = stringPreferencesKey("skin")
        val STICKER = booleanPreferencesKey("sticker_enabled")
        val MEMORY = booleanPreferencesKey("memory_enabled")
        val PERSONA_ID = stringPreferencesKey("persona_id")
        val INIT_DONE = booleanPreferencesKey("init_done")
    }

    val flow: Flow<WhaleSettings> = store.data.map { p ->
        WhaleSettings(
            userName = p[Keys.USER_NAME].orEmpty(),
            baseUrl = p[Keys.BASE_URL] ?: "https://api.deepseek.com",
            model = p[Keys.MODEL] ?: "deepseek-flash",
            temperature = p[Keys.TEMPERATURE] ?: 0.9f,
            maxTokens = p[Keys.MAX_TOKENS] ?: 2048,
            autoRead = p[Keys.AUTO_READ] ?: false,
            voiceRate = p[Keys.VOICE_RATE] ?: 1.06f,
            voicePitch = p[Keys.VOICE_PITCH] ?: 1.02f,
            voiceEngine = p[Keys.VOICE_ENGINE] ?: WhaleSettings.VOICE_SYSTEM,
            deepThinking = p[Keys.DEEP_THINKING] ?: false,
            skin = p[Keys.SKIN] ?: WhaleSettings.SKIN_WHALE,
            stickerEnabled = p[Keys.STICKER] ?: true,
            memoryEnabled = p[Keys.MEMORY] ?: true,
            personaId = p[Keys.PERSONA_ID].orEmpty(),
        )
    }

    /** 皮肤（全局）：MainActivity 在启动时同步读取，决定整 App 明暗。 */
    suspend fun skinNow(): String = withTimeoutOrNull(2000) {
        runCatching { flow.first().skin }.getOrNull()
    } ?: WhaleSettings.SKIN_WHALE

    suspend fun settingsNow(): WhaleSettings = withTimeoutOrNull(2000) {
        runCatching { flow.first() }.getOrNull()
    } ?: WhaleSettings()

    suspend fun setSkin(skin: String) = store.edit { it[Keys.SKIN] = skin }

    suspend fun setUserName(v: String) = store.edit { it[Keys.USER_NAME] = v.trim() }

    suspend fun setBaseUrl(v: String) = store.edit {
        it[Keys.BASE_URL] = v.trim().ifBlank { "https://api.deepseek.com" }
    }

    suspend fun setModel(v: String) = store.edit { it[Keys.MODEL] = v.trim().ifBlank { "deepseek-flash" } }

    suspend fun setTemperature(v: Float) = store.edit { it[Keys.TEMPERATURE] = v.coerceIn(0f, 2f) }

    suspend fun setMaxTokens(v: Int) = store.edit { it[Keys.MAX_TOKENS] = v.coerceIn(256, 8192) }

    suspend fun setAutoRead(v: Boolean) = store.edit { it[Keys.AUTO_READ] = v }

    suspend fun setVoiceRate(v: Float) = store.edit { it[Keys.VOICE_RATE] = v.coerceIn(0.5f, 2f) }

    suspend fun setVoicePitch(v: Float) = store.edit { it[Keys.VOICE_PITCH] = v.coerceIn(0.5f, 2f) }

    suspend fun setVoiceEngine(v: String) = store.edit {
        it[Keys.VOICE_ENGINE] = if (v == WhaleSettings.VOICE_CLOUD) WhaleSettings.VOICE_CLOUD else WhaleSettings.VOICE_SYSTEM
    }

    suspend fun setDeepThinking(v: Boolean) = store.edit { it[Keys.DEEP_THINKING] = v }

    suspend fun setStickerEnabled(v: Boolean) = store.edit { it[Keys.STICKER] = v }

    suspend fun setMemoryEnabled(v: Boolean) = store.edit { it[Keys.MEMORY] = v }

    /** 切换人设：空串 = 内置鲸鱼娘。 */
    suspend fun setPersonaId(v: String) = store.edit { it[Keys.PERSONA_ID] = v.trim() }

    // ===== API Key =====

    /**
     * 首次进入本模块时，把主设置里已配好的 Key 迁移过来。
     * 只在 `init_done` 未置位时执行一次，之后以本模块的 Key 为准（用户可能想用别的账号）。
     */
    suspend fun bootstrapApiKey() {
        val done = runCatching { store.data.first()[Keys.INIT_DONE] }.getOrNull() == true
        if (done) return
        if (secrets.getApiKey().isBlank()) {
            val host = runCatching { hostSettings?.getApiConfigNow() }.getOrNull()
            if (host != null && host.apiKey.isNotBlank()) {
                secrets.setApiKey(host.apiKey)
                Log.i(TAG, "已从主设置迁移 API Key")
            }
        }
        store.edit { it[Keys.INIT_DONE] = true }
    }

    fun apiKey(): String = secrets.getApiKey()

    fun setApiKey(v: String) = secrets.setApiKey(v)

    /** 是否已配置可用的 Key（免费代理端点允许空 Key，但本模块默认直连官方）。 */
    fun hasApiKey(): Boolean = secrets.getApiKey().isNotBlank()

    /** 主设置里的当前配置快照（仅在 Key 迁移时用）。 */
    suspend fun hostApiConfig(): ApiConfig? = runCatching { hostSettings?.getApiConfigNow() }.getOrNull()

    companion object {
        private const val TAG = "WhaleSettings"
    }
}
