package com.rhodesisland.terminal.whale.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.whale.WhalePersona
import com.rhodesisland.terminal.whale.voice.WhaleTts
import com.rhodesisland.terminal.whale.voice.WhaleVoiceInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 鲸鱼娘设置页 ViewModel。 */
class WhaleSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as RhodesApp).container
    private val repo = container.whaleSettingsRepository
    private val tts = WhaleTts(app)
    private val voice = WhaleVoiceInput(app)

    private val _settings = MutableStateFlow(WhaleSettings())
    val settings: StateFlow<WhaleSettings> = _settings.asStateFlow()

    /** 已脱敏的 Key 展示（只露前 4 后 4）。 */
    private val _maskedKey = MutableStateFlow("")
    val maskedKey: StateFlow<String> = _maskedKey.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 系统中文语音识别是否可用（设置页状态检测）。 */
    val sttAvailable: Boolean = voice.isAvailable()

    init {
        viewModelScope.launch { repo.flow.collect { _settings.value = it } }
        refreshKeyMask()
    }

    fun refreshKeyMask() {
        val key = repo.apiKey()
        _maskedKey.value = when {
            key.isBlank() -> ""
            key.length <= 8 -> "sk-••••"
            else -> key.take(4) + "••••••••" + key.takeLast(4)
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun saveApiKey(key: String) {
        repo.setApiKey(key)
        refreshKeyMask()
        _notice.value = if (key.isBlank()) "已清空 Key。" else "Key 存好了（加密保存在本机 Keystore）。"
    }

    fun setBaseUrl(v: String) = viewModelScope.launch { repo.setBaseUrl(v) }
    fun setModel(v: String) = viewModelScope.launch { repo.setModel(v) }
    fun setUserName(v: String) = viewModelScope.launch { repo.setUserName(v) }
    fun setTemperature(v: Float) = viewModelScope.launch { repo.setTemperature(v) }
    fun setMaxTokens(v: Int) = viewModelScope.launch { repo.setMaxTokens(v) }
    fun setAutoRead(v: Boolean) = viewModelScope.launch { repo.setAutoRead(v) }
    fun setVoiceRate(v: Float) = viewModelScope.launch { repo.setVoiceRate(v) }
    fun setVoicePitch(v: Float) = viewModelScope.launch { repo.setVoicePitch(v) }
    fun setVoiceEngine(v: String) = viewModelScope.launch { repo.setVoiceEngine(v) }
    fun setSkin(v: String) = viewModelScope.launch { repo.setSkin(v) }
    fun setStickerEnabled(v: Boolean) = viewModelScope.launch { repo.setStickerEnabled(v) }
    fun setMemoryEnabled(v: Boolean) = viewModelScope.launch { repo.setMemoryEnabled(v) }

    /** 试听：按当前引擎念一句固定台词；失败一定有可见提示，不静默。 */
    fun previewVoice() {
        val s = _settings.value
        val sample = "比起复杂的深度推理，先来一碗香喷喷的白米饭吧！"
        if (s.voiceEngine == WhaleSettings.VOICE_CLOUD) {
            viewModelScope.launch {
                runCatching { container.ttsManager.speak(sample, WhalePersona.ID) }
                    .onFailure {
                        _notice.value = "云端语音不可用（原设置里配好火山 API Key 与音色），已用系统语音试听。"
                        tts.speak(sample, s.voiceRate, s.voicePitch)
                    }
            }
        } else {
            val ok = tts.speak(sample, s.voiceRate, s.voicePitch)
            _notice.value = if (ok) null else "系统语音引擎起不来：装一个中文 TTS（如讯飞语记）再试。"
        }
    }

    /** 清空与该角色的全部聊天记录（不动长期记忆）。 */
    fun clearConversation() {
        viewModelScope.launch {
            val conv = runCatching { container.conversationRepository.listByCharacter(WhalePersona.ID) }
                .getOrDefault(emptyList())
            var cleared = 0
            conv.forEach { c ->
                runCatching { container.chatRepository.clearHistory(c.id) }.onSuccess { cleared++ }
            }
            _notice.value = if (cleared > 0) "对话记录清空了（记忆还在）。" else "本来就没有聊天记录。"
        }
    }

    /** 数据目录：所有鲸鱼娘数据都在 App 私有目录，导出路径供用户排查/备份。 */
    fun dataDirHint(): String =
        getApplication<Application>().filesDir.absolutePath + "/whale"

    fun apiKeyIsSet(): Boolean = repo.hasApiKey()

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
        voice.destroy()
    }
}
