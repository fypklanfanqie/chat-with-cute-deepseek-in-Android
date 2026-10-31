package com.rhodesisland.terminal.whale.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * 鲸鱼娘朗读（系统 TTS）。
 *
 * 与主 App 的 TtsManager 是两套东西：那套按角色音色映射走火山云端，需要凭据；
 * 鲸鱼娘固定中文女声、零配置，直接用系统引擎，语速 1.06 / 音调 1.02（规格值）。
 *
 * 生命周期：构造即初始化（不阻塞），退出模块时 [shutdown]。初始化失败只降级为「不能朗读」，
 * 不抛异常——部分精简 ROM 根本没有 TTS 引擎。
 */
class WhaleTts(context: Context) {

    private val appContext = context.applicationContext

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var pending: String? = null

    /** 当前是否正在朗读（供 UI 显示停止/播放图标）。 */
    @Volatile
    var speaking: Boolean = false
        private set

    init {
        runCatching {
            engine = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    runCatching {
                        engine?.language = Locale.CHINESE
                        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                speaking = true
                            }

                            override fun onDone(utteranceId: String?) {
                                speaking = false
                            }

                            override fun onError(utteranceId: String?) {
                                speaking = false
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                speaking = false
                                Log.w(TAG, "tts error code=$errorCode")
                            }
                        })
                    }
                    pending?.let { text ->
                        pending = null
                        speakNow(text, DEFAULT_RATE, DEFAULT_PITCH)
                    }
                } else {
                    Log.w(TAG, "系统 TTS 初始化失败 status=$status")
                }
            }
        }.onFailure { Log.w(TAG, "创建 TTS 引擎失败: ${it.message}") }
    }

    /**
     * 朗读一段文本。会先 [stop] 再入队（QUEUE_FLUSH），避免两段叠着念。
     * 引擎未就绪时暂存最后一句，初始化完成后补播。
     *
     * @return false 表示连引擎都没起来（ROM 没有 TTS / 初始化失败），调用方应给出可见提示
     */
    fun speak(text: String, rate: Float = DEFAULT_RATE, pitch: Float = DEFAULT_PITCH): Boolean {
        val clean = sanitize(text)
        if (clean.isBlank()) return true
        stop()
        if (!ready) {
            pending = clean
            // 引擎还没回调 onInit：可能是初始化中（会补播），也可能是彻底失败（engine 为空）
            return engine != null
        }
        speakNow(clean, rate, pitch)
        return true
    }

    private fun speakNow(text: String, rate: Float, pitch: Float) {
        runCatching {
            engine?.setSpeechRate(rate)
            engine?.setPitch(pitch)
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            speaking = true
        }.onFailure { Log.w(TAG, "speak 失败: ${it.message}") }
    }

    fun stop() {
        runCatching { engine?.stop() }
        speaking = false
    }

    fun shutdown() {
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    /**
     * 朗读前清洗：去掉表情标记、Markdown 记号、代码围栏、URL。
     * 情绪词/动作不会被念出来（规格要求），但保留正文的中文标点以便断句。
     */
    fun sanitize(raw: String): String {
        var s = raw
        s = s.replace(Regex("""\[\[\s*sticker\s*:[^\]]+\]\]"""), "")
        s = s.replace(Regex("(?s)```.*?```"), "，代码略过，")
        s = s.replace(Regex("`([^`]*)`"), "$1")
        s = s.replace(Regex("""https?://\S+"""), "")
        s = s.replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
        s = s.replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        s = s.replace(Regex("""(?m)^\s{0,3}#{1,6}\s*"""), "")
        s = s.replace(Regex("""(?m)^\s*[-*+]\s+"""), "")
        s = s.replace(Regex("""[*_~>|]"""), "")
        // 表情符号与杂符号：只保留中日韩、字母数字和常用标点
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val keep = ch.isLetterOrDigit() || ch in "，。！？、；：“”‘’（）《》…—,.!?;:'\"()-"
            if (keep || ch.isWhitespace()) sb.append(ch)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private const val TAG = "WhaleTts"
        private const val UTTERANCE_ID = "whale_tts"
        const val DEFAULT_RATE = 1.06f
        const val DEFAULT_PITCH = 1.02f
    }
}
