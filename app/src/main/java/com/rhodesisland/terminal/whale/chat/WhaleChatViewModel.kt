package com.rhodesisland.terminal.whale.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.data.model.ChatMessage
import com.rhodesisland.terminal.data.model.MessageCompletionState
import com.rhodesisland.terminal.data.remote.ChatMessageDto
import com.rhodesisland.terminal.data.remote.DirectLlmException
import com.rhodesisland.terminal.data.remote.DirectLlmFailure
import com.rhodesisland.terminal.whale.WhalePersona
import com.rhodesisland.terminal.whale.data.WhalePetEntity
import com.rhodesisland.terminal.whale.pet.WhalePetRules
import com.rhodesisland.terminal.whale.settings.WhaleSettings
import com.rhodesisland.terminal.whale.sticker.StickerItem
import com.rhodesisland.terminal.whale.sticker.WhaleStickerStore
import com.rhodesisland.terminal.whale.voice.WhaleTts
import com.rhodesisland.terminal.whale.voice.WhaleVoiceInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条气泡的完整渲染信息。 */
data class WhaleBubble(
    /** Compose key；持久消息用 `m-<rowId>`，流式用 `streaming`。 */
    val key: String,
    val role: String,
    /** 原文（含 [[sticker:xx]] 与 <think>），落库时原样保存。 */
    val raw: String,
    /** 展示正文（已剔除表情标记与思考块）。 */
    val display: String,
    val reasoning: String = "",
    val stickers: List<StickerItem> = emptyList(),
    val images: List<String> = emptyList(),
    val streaming: Boolean = false,
    val failed: Boolean = false,
    /** 已保留到长期记忆时，指向记忆行 id。 */
    val memoryId: Long? = null,
    /** chat_history 行 id（删除/回填用）；流式与错误气泡为 null。 */
    val dbId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 当前生效的人设（内置鲸鱼娘，或用户选择的某个自定义角色）。 */
data class WhaleActivePersona(
    val id: String,
    val name: String,
    val avatarModel: Any,
    val prompt: String?,
    val tagline: String,
)

/**
 * 鲸鱼娘对话 ViewModel。
 *
 * 与主 App 的 ChatViewModel 完全解耦：不加载世界书、不走好感度、不触发 Seedance。
 * 但**复用同一套持久化**（chat_history / conversation，characterId = whale_chan），
 * 因此重启不丢消息、也能被既有的导出/清空逻辑覆盖。
 */
class WhaleChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as RhodesApp).container
    private val chatRepo = container.chatRepository
    private val conversationRepo = container.conversationRepository
    private val settingsRepo = container.whaleSettingsRepository
    private val memoryRepo = container.whaleMemoryRepository
    private val petRepo = container.whalePetRepository
    private val stickerStore: WhaleStickerStore = container.whaleStickerStore
    private val llm = container.directLlmClient

    val voice: WhaleVoiceInput = WhaleVoiceInput(app)
    private val tts = WhaleTts(app)

    private val _bubbles = MutableStateFlow<List<WhaleBubble>>(emptyList())
    val bubbles: StateFlow<List<WhaleBubble>> = _bubbles.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _pendingImages = MutableStateFlow<List<ImportedImage>>(emptyList())
    val pendingImages: StateFlow<List<ImportedImage>> = _pendingImages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _settings = MutableStateFlow(WhaleSettings())
    val settings: StateFlow<WhaleSettings> = _settings.asStateFlow()

    private val _pet = MutableStateFlow(WhalePetEntity())
    val pet: StateFlow<WhalePetEntity> = _pet.asStateFlow()

    /** 一次性提示（Toast / Snackbar）。消费后置 null。 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 该角色下的全部会话（新建/切换用，最近活跃在前）。 */
    private val _conversations = MutableStateFlow<List<com.rhodesisland.terminal.data.model.Conversation>>(emptyList())
    val conversations: StateFlow<List<com.rhodesisland.terminal.data.model.Conversation>> =
        _conversations.asStateFlow()

    /** 当前会话 id（历史对话列表里高亮用）。 */
    private val _currentConversationId = MutableStateFlow(0L)
    val currentConversationId: StateFlow<Long> = _currentConversationId.asStateFlow()

    /** 正在朗读的消息 key（null=没在念）。 */
    private val _speakingKey = MutableStateFlow<String?>(null)
    val speakingKey: StateFlow<String?> = _speakingKey.asStateFlow()

    /** 当前人设：默认内置鲸鱼娘；设置里选了自定义角色则整套替换。 */
    val persona: StateFlow<WhaleActivePersona> =
        kotlinx.coroutines.flow.combine(
            _settings,
            container.characterRepository.characters,
        ) { s, characters ->
            if (s.personaId.isBlank()) {
                WhaleActivePersona(
                    id = WhalePersona.ID,
                    name = WhalePersona.NAME,
                    avatarModel = com.rhodesisland.terminal.whale.ui.WHALE_AVATAR_ASSET,
                    prompt = null,
                    tagline = "住在推理海里的女仆",
                )
            } else {
                val c = characters.firstOrNull { it.id == s.personaId }
                if (c == null) {
                    WhaleActivePersona(
                        id = WhalePersona.ID,
                        name = WhalePersona.NAME,
                        avatarModel = com.rhodesisland.terminal.whale.ui.WHALE_AVATAR_ASSET,
                        prompt = null,
                        tagline = "住在推理海里的女仆",
                    )
                } else {
                    WhaleActivePersona(
                        id = c.id,
                        name = c.name,
                        // 自定义角色的 image 是内部存储绝对路径，必须包成 File 给 Coil，
                        // 直接传字符串会被当成相对 URI 解析失败（头像空白）。
                        avatarModel = if (c.image.isBlank()) {
                            com.rhodesisland.terminal.whale.ui.WHALE_AVATAR_ASSET
                        } else {
                            java.io.File(c.image)
                        },
                        prompt = c.systemPrompt,
                        tagline = c.role.ifBlank { "自定义人设" },
                    )
                }
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            WhaleActivePersona(
                id = WhalePersona.ID,
                name = WhalePersona.NAME,
                avatarModel = com.rhodesisland.terminal.whale.ui.WHALE_AVATAR_ASSET,
                prompt = null,
                tagline = "住在推理海里的女仆",
            ),
        )

    private var conversationId: Long = 0L
    private var streamJob: Job? = null
    private var activeCall: Call? = null

    init {
        viewModelScope.launch {
            runCatching { settingsRepo.bootstrapApiKey() }
                .onFailure { Log.w(TAG, "bootstrapApiKey 失败: ${it.message}") }
            conversationId = ensureConversation()
            _currentConversationId.value = conversationId
            reloadHistory()
            refreshConversations()
        }
        viewModelScope.launch {
            settingsRepo.flow.collect { _settings.value = it }
        }
        viewModelScope.launch {
            _pet.value = runCatching { petRepo.tick() }.getOrDefault(WhalePetEntity())
        }
        viewModelScope.launch {
            memoryRepo.purgeRecycleBin()
        }
    }

    // ==================== 会话与历史 ====================

    private suspend fun ensureConversation(): Long {
        val existing = runCatching { conversationRepo.listByCharacter(WHALE_ID) }.getOrDefault(emptyList())
        return existing.firstOrNull()?.id
            ?: runCatching { conversationRepo.create(WHALE_ID, "和鲸鱼娘的对话") }.getOrDefault(0L)
    }

    private suspend fun reloadHistory() {
        if (conversationId <= 0L) return
        val history = runCatching { chatRepo.getHistory(conversationId) }.getOrDefault(emptyList())
        _bubbles.value = history.map { it.toBubble() }
    }

    // ==================== 会话管理 ====================

    fun refreshConversations() {
        viewModelScope.launch {
            _conversations.value = runCatching { conversationRepo.listByCharacter(WHALE_ID) }
                .getOrDefault(emptyList())
        }
    }

    /** 新建一段对话：立刻清空界面，旧对话仍留在历史里可切回。 */
    fun newConversation() {
        if (_streaming.value) {
            _notice.value = "等这一句说完再开新的吧。"
            return
        }
        viewModelScope.launch {
            val id = runCatching { conversationRepo.create(WHALE_ID, "和鲸鱼娘的对话") }.getOrDefault(0L)
            if (id <= 0L) {
                _notice.value = "新建会话失败了，再试一次？"
                return@launch
            }
            conversationId = id
            _bubbles.value = emptyList()
            _input.value = ""
            _pendingImages.value = emptyList()
            _currentConversationId.value = id
            refreshConversations()
            _notice.value = "开新对话啦。"
        }
    }

    /** 切到某段历史对话。 */
    fun switchConversation(id: Long) {
        if (_streaming.value) {
            _notice.value = "等这一句说完再切吧。"
            return
        }
        if (id == conversationId) return
        viewModelScope.launch {
            conversationId = id
            _currentConversationId.value = id
            reloadHistory()
            refreshConversations()
        }
    }

    private fun ChatMessage.toBubble(): WhaleBubble {
        val parsed = stickerStore.parse(content)
        val (reasoning, body) = splitThink(parsed.text, false)
        return WhaleBubble(
            key = "m-${databaseId ?: timestamp}",
            role = role,
            raw = content,
            display = body,
            reasoning = reasoning,
            stickers = if (_settings.value.stickerEnabled) parsed.labels.mapNotNull { stickerStore.byLabel(it) } else emptyList(),
            images = images,
            dbId = databaseId,
            createdAt = timestamp,
        )
    }

    // ==================== 输入 ====================

    fun updateInput(text: String) {
        _input.value = text
    }

    fun addImages(images: List<ImportedImage>) {
        if (images.isEmpty()) return
        _pendingImages.update { (it + images).take(MAX_IMAGES_PER_MESSAGE) }
    }

    fun removeImage(path: String) {
        _pendingImages.update { list -> list.filterNot { it.path == path } }
    }

    /** 相册选图：压缩落盘后进待发送区（最多 4 张）。 */
    fun importImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val imported = uris.take(MAX_IMAGES_PER_MESSAGE).mapNotNull {
                WhaleImageCodec.import(getApplication(), it)
            }
            if (imported.isEmpty()) {
                _notice.value = "这几张图读不出来，换几张试试？"
            } else {
                addImages(imported)
            }
        }
    }

    /** 删除一条已落库的消息（乐观从列表移除，失败再刷新回来）。 */
    fun deleteMessage(bubble: WhaleBubble) {
        val id = bubble.dbId ?: return
        viewModelScope.launch {
            runCatching { chatRepo.deleteMessage(conversationId, id) }
                .onFailure {
                    _notice.value = "删除失败了，再试一次？"
                    return@launch
                }
            _bubbles.update { list -> list.filterNot { it.key == bubble.key } }
            _notice.value = "删掉了。"
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    /** 表情面板数据源（内置 24 张 + 用户自定义）。 */
    fun stickerItems(): List<StickerItem> = stickerStore.allForPanel()

    fun showNotice(text: String) {
        _notice.value = text
    }

    // ==================== 发送 ====================

    fun send(textOverride: String? = null) {
        if (_streaming.value) return
        val rawText = (textOverride ?: _input.value).trim()
        val images = _pendingImages.value
        if (rawText.isEmpty() && images.isEmpty()) return

        val cfg = _settings.value
        if (!settingsRepo.hasApiKey()) {
            _notice.value = "还没填 API Key 呢，去「设置」里贴一下就能聊了。"
            return
        }

        _input.value = ""
        _pendingImages.value = emptyList()
        _streaming.value = true

        streamJob = viewModelScope.launch {
            var assistantAcc = ""
            var assistantRaw = ""
            var failed = false
            try {
                val historyForApi = buildApiMessages(rawText, images.map { it.path })

                // 用户消息落库 + 乐观气泡
                val userEntity = ChatMessage(
                    role = "user",
                    content = rawText,
                    images = images.map { it.path },
                    timestamp = System.currentTimeMillis(),
                )
                val rowId = chatRepo.addMessage(WHALE_ID, conversationId, userEntity)
                conversationRepo.touch(conversationId)
                val userBubble = WhaleBubble(
                    key = "m-$rowId",
                    role = "user",
                    raw = rawText,
                    display = rawText,
                    images = images.map { it.path },
                    dbId = rowId,
                )
                _bubbles.update { it + userBubble }

                val streamBubble = WhaleBubble(
                    key = STREAMING_KEY,
                    role = "assistant",
                    raw = "",
                    display = "",
                    streaming = true,
                )
                _bubbles.update { it + streamBubble }

                val fullText = llm.chatStream(
                    baseUrl = cfg.baseUrl.trim().ifBlank { "https://api.deepseek.com" },
                    apiKey = settingsRepo.apiKey(),
                    model = cfg.model.trim().ifBlank { "deepseek-flash" },
                    messages = historyForApi,
                    onChunk = { accumulated ->
                        assistantRaw = accumulated
                        val (reasoning, body) = splitThink(accumulated, true)
                        assistantAcc = body
                        _bubbles.update { list ->
                            list.map {
                                if (it.key == STREAMING_KEY) {
                                    it.copy(raw = accumulated, display = body, reasoning = reasoning)
                                } else it
                            }
                        }
                    },
                    onCall = { activeCall = it },
                    deepThinking = cfg.deepThinking,
                    temperature = cfg.temperature,
                    maxTokens = cfg.maxTokens,
                    // DeepSeek V4：不显式关掉会只出 reasoning_content，正文空白
                    reasoningEffort = if (cfg.deepThinking) "high" else "none",
                )
                assistantRaw = fullText
                val (reasoning, body) = splitThink(fullText, false)
                assistantAcc = body
                persistAssistant(fullText, completion = MessageCompletionState.COMPLETE)
                _bubbles.update { list -> list.filterNot { it.key == STREAMING_KEY } }
                reloadHistory()
                if (reasoning.isNotBlank()) Log.d(TAG, "thinking len=${reasoning.length}")
                _pet.value = runCatching { petRepo.onChatRound() }.getOrDefault(_pet.value)
                if (cfg.autoRead) speakText(body)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户点「停止」：保留已生成内容
                if (assistantRaw.isNotBlank()) {
                    persistAssistant(assistantRaw, completion = MessageCompletionState.STOPPED_PARTIAL)
                }
                _bubbles.update { list -> list.filterNot { it.key == STREAMING_KEY } }
                reloadHistory()
                throw e
            } catch (t: Throwable) {
                failed = true
                Log.w(TAG, "send failed: ${t.message}", t)
                val message = humanize(t)
                _bubbles.update { list -> list.filterNot { it.key == STREAMING_KEY } }
                if (assistantRaw.isNotBlank()) {
                    persistAssistant(assistantRaw, completion = MessageCompletionState.STOPPED_PARTIAL)
                    reloadHistory()
                }
                _bubbles.update { it + errorBubble(message) }
                _notice.value = message
            } finally {
                _streaming.value = false
                activeCall = null
                streamJob = null
            }
        }
    }

    /** 停止生成：取消底层 OkHttp Call + 协程，已生成内容保留。 */
    fun stop() {
        activeCall?.cancel()
        streamJob?.cancel()
        _streaming.value = false
    }

    private fun errorBubble(message: String): WhaleBubble = WhaleBubble(
        key = "err-${System.currentTimeMillis()}",
        role = "assistant",
        raw = message,
        display = message,
        failed = true,
    )

    private suspend fun persistAssistant(raw: String, completion: MessageCompletionState) {
        if (raw.isBlank() || conversationId <= 0L) return
        runCatching {
            chatRepo.addMessage(
                WHALE_ID,
                conversationId,
                ChatMessage(
                    role = "assistant",
                    content = raw,
                    timestamp = System.currentTimeMillis(),
                    completionState = completion,
                ),
            )
            conversationRepo.touch(conversationId)
        }.onFailure { Log.w(TAG, "落库失败: ${it.message}") }
    }

    /** 把异常翻译成鲸鱼娘语气的可读提示（规格要求 401/402/429 明确文案）。 */
    private fun humanize(t: Throwable): String = when (t) {
        is DirectLlmException -> when {
            t.statusCode == 401 || t.statusCode == 403 -> "API Key 好像不对欸，去设置里重新填一下吧。"
            t.statusCode == 402 -> "账户余额不足啦…要先充点钱才能继续算。"
            t.statusCode == 429 -> "问得太快啦，缓一缓再说嘛。"
            t.statusCode != null && t.statusCode >= 500 -> "服务器那边有点状况，稍等一会儿再试？"
            t.failure == DirectLlmFailure.NETWORK -> "网络好像断了，检查一下连接？"
            t.failure == DirectLlmFailure.EMPTY_RESPONSE -> "唔…这次我什么都没说出来，再问一次？"
            else -> "请求失败啦（${t.statusCode ?: "未知"}），要不要再试一次？"
        }

        is java.net.SocketTimeoutException -> "等太久了，网络有点慢，再试一次？"
        is java.net.UnknownHostException -> "连不上服务器，检查一下网络？"
        else -> t.message?.take(160)?.takeIf { it.isNotBlank() } ?: "出了点小状况，再试一次？"
    }

    // ==================== 提示词组装 ====================

    private suspend fun buildApiMessages(currentText: String, currentImages: List<String>): List<ChatMessageDto> {
        val cfg = _settings.value
        val memories = if (cfg.memoryEnabled) {
            runCatching { memoryRepo.recall(currentText.ifBlank { "最近聊了什么" }) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val system = WhalePersona.buildSystemPrompt(
            userName = cfg.userName,
            pet = _pet.value,
            memories = memories,
            hungerHint = WhalePetRules.hungerHint(_pet.value),
            customPersona = persona.value.prompt,
        )
        val messages = ArrayList<ChatMessageDto>(34)
        messages.add(ChatMessageDto("system", JsonPrimitive(system)))

        val history = runCatching { chatRepo.getHistory(conversationId) }.getOrDefault(emptyList())
        // 只带最近 30 条；正文截断 1000 字；图片只取最近 4 条消息、最多 4 张
        val window = history.takeLast(MAX_HISTORY_MESSAGES)
        var usedImages = 0
        for ((index, msg) in window.withIndex()) {
            val fromEnd = window.size - index
            val text = stripForModel(msg.content)
            var content: JsonElement = JsonPrimitive(text)
            if (msg.role == "user" && fromEnd <= 4 && usedImages < MAX_IMAGES_PER_REQUEST && msg.images.isNotEmpty()) {
                val encoded = ArrayList<String>()
                for (path in msg.images) {
                    if (usedImages >= MAX_IMAGES_PER_REQUEST) break
                    WhaleImageCodec.encodeFile(path)?.let {
                        encoded.add(it)
                        usedImages++
                    }
                }
                if (encoded.isNotEmpty()) content = multimodal(text, encoded)
            }
            messages.add(ChatMessageDto(msg.role, content))
        }

        // 当前这一轮（可能带图）
        val currentEncoded = ArrayList<String>()
        for (path in currentImages) {
            if (currentEncoded.size >= MAX_IMAGES_PER_MESSAGE) break
            WhaleImageCodec.encodeFile(path)?.let { currentEncoded.add(it) }
        }
        messages.add(
            ChatMessageDto(
                "user",
                if (currentEncoded.isEmpty()) JsonPrimitive(currentText)
                else multimodal(currentText, currentEncoded),
            )
        )
        return messages
    }

    private fun multimodal(text: String, images: List<String>): JsonElement = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }
        )
        images.forEach { b64 ->
            add(
                buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$b64") }
                }
            )
        }
    }

    /** 喂给模型的正文：去掉表情标记与思考块，并截断到 1000 字。 */
    private fun stripForModel(raw: String): String {
        val noSticker = raw.replace(STICKER_REGEX, "")
        val (_, body) = splitThink(noSticker, false)
        return body.take(1000)
    }

    // ==================== 消息操作 ====================

    fun keepMemory(bubble: WhaleBubble) {
        if (bubble.memoryId != null) {
            _notice.value = "这条已经记住了。"
            return
        }
        viewModelScope.launch {
            val result = runCatching {
                memoryRepo.save(
                    text = bubble.display.ifBlank { bubble.raw },
                    role = bubble.role,
                    sessionKey = dayKey(),
                    messageRef = bubble.key,
                )
            }.getOrNull()
            if (result == null || result.id <= 0) {
                _notice.value = "这句没记住，再说一遍？"
                return@launch
            }
            _bubbles.update { list ->
                list.map { if (it.key == bubble.key) it.copy(memoryId = result.id) else it }
            }
            _notice.value = when {
                result.duplicate -> "已经记过一模一样的内容啦。"
                result.similarTo != null -> "记住啦。之前也有一条很像的，要去记忆页看看要不要合并吗？"
                else -> "记住啦，以后会想起来的。"
            }
        }
    }

    fun copyText(text: String) {
        val ctx = getApplication<Application>()
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("鲸鱼娘", text))
        _notice.value = "复制好了。"
    }

    fun speakBubble(bubble: WhaleBubble) {
        if (_speakingKey.value == bubble.key) {
            tts.stop()
            _speakingKey.value = null
            return
        }
        _speakingKey.value = bubble.key
        speakText(bubble.display, onDone = { _speakingKey.value = null })
    }

    private fun speakText(text: String, onDone: (() -> Unit)? = null) {
        val cfg = _settings.value
        val clean = tts.sanitize(text)
        if (clean.isBlank()) return
        if (cfg.voiceEngine == WhaleSettings.VOICE_CLOUD) {
            // 云端：复用原 App 的 TtsManager（火山引擎，凭据在原设置页配置）。
            // 未配凭据时它内部会自动回退系统引擎，不会静默。
            viewModelScope.launch {
                runCatching { container.ttsManager.speak(clean, WhalePersona.ID) }
                    .onFailure {
                        Log.w(TAG, "cloud tts failed: ${it.message}")
                        _notice.value = "云端语音没配好（原设置里填火山 API Key 与音色），先用系统语音吧。"
                        tts.speak(text, cfg.voiceRate, cfg.voicePitch)
                    }
            }
        } else {
            val ok = tts.speak(text, cfg.voiceRate, cfg.voicePitch)
            if (!ok) {
                _notice.value = "这台手机没有可用的中文语音引擎，去装一个（如讯飞语记），或改用云端语音。"
            }
        }
        if (onDone != null) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(estimateSpeechMs(text, cfg.voiceRate))
                onDone()
            }
        }
    }

    fun stopSpeaking() {
        tts.stop()
        runCatching { container.ttsManager.stopAll() }
        _speakingKey.value = null
    }

    private fun estimateSpeechMs(text: String, rate: Float): Long {
        // 中文约 4.5 字/秒，按用户语速折算；仅用于复位「正在朗读」图标
        val chars = tts.sanitize(text).length.coerceAtLeast(1)
        return (chars / (4.5f * rate.coerceAtLeast(0.5f)) * 1000).toLong().coerceIn(1200, 120_000)
    }

    // ==================== 语音输入 ====================

    fun startVoiceInput() {
        voice.start { text ->
            // 只填进输入框，不自动发送
            _input.value = text
        }
    }

    fun stopVoiceInput() = voice.stop()

    fun clearVoiceError() = voice.clearError()

    // ==================== 设置联动 ====================

    fun toggleDeepThinking() {
        viewModelScope.launch { settingsRepo.setDeepThinking(!_settings.value.deepThinking) }
    }

    /** 切换人设：空串 = 内置鲸鱼娘。 */
    fun setPersona(id: String) {
        viewModelScope.launch { settingsRepo.setPersonaId(id) }
    }

    /** 表情面板选一张：作为独立消息直接发送（规格：只发表情也算一条消息）。 */
    fun sendSticker(item: StickerItem) {
        if (_streaming.value) return
        val marker = "[[sticker:${item.label}]]"
        viewModelScope.launch {
            val rowId = runCatching {
                chatRepo.addMessage(
                    WHALE_ID,
                    conversationId,
                    ChatMessage(role = "user", content = marker, timestamp = System.currentTimeMillis()),
                )
            }.getOrDefault(0L)
            _bubbles.update {
                it + WhaleBubble(
                    key = "m-$rowId",
                    role = "user",
                    raw = marker,
                    display = "",
                    stickers = listOf(item),
                    dbId = rowId,
                )
            }
            // 表情消息不触发模型请求，只做养成反馈
            _pet.value = runCatching { petRepo.onChatRound() }.getOrDefault(_pet.value)
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts.shutdown()
        voice.destroy()
        activeCall?.cancel()
    }

    companion object {
        private const val TAG = "WhaleChat"
        const val WHALE_ID = WhalePersona.ID
        const val STREAMING_KEY = "streaming"
        const val MAX_HISTORY_MESSAGES = 30
        const val MAX_IMAGES_PER_MESSAGE = 4
        const val MAX_IMAGES_PER_REQUEST = 4
        private val STICKER_REGEX = Regex("""\[\[\s*sticker\s*:[^\]]+\]\]""")

        fun dayKey(now: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(now))

        /**
         * 拆分思考块与正文。`<think>` 未闭合时（流式中）把剩余部分都当思考，
         * 这样正文只在思考结束后开始渲染，避免半截思考闪进气泡。
         */
        fun splitThink(raw: String, streaming: Boolean): Pair<String, String> {
            if (!raw.contains("<think>")) return "" to raw.trim()
            val start = raw.indexOf("<think>")
            val end = raw.indexOf("</think>")
            if (end < 0) {
                val reasoning = raw.substring(start + 7).trim()
                return reasoning to raw.substring(0, start).trim()
            }
            val reasoning = raw.substring(start + 7, end).trim()
            val body = (raw.substring(0, start) + raw.substring(end + 8)).trim()
            if (streaming && body.isEmpty()) return reasoning to ""
            return reasoning to body
        }
    }
}
