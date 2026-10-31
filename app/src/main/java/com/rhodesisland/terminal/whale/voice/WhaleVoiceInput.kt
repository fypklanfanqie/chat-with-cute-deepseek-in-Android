package com.rhodesisland.terminal.whale.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音输入（STT）——直接封装系统 `SpeechRecognizer`。
 *
 * 为什么不用第三方：需求明确要求全部走本地/系统能力、不上传第三方；
 * 系统的中文识别在国产 ROM 上通常已由厂商引擎（讯飞/小米）接管，效果够用且零依赖。
 *
 * 关键约定（与提示词一致）：
 *  - 识别结果**只填进输入框，不自动发送**，让用户改错别字；
 *  - 失败一定有可见文案，不静默；
 *  - 所有回调切回主线程（SpeechRecognizer 必须在有 Looper 的线程创建与调用）。
 */
class WhaleVoiceInput(context: Context) {

    data class UiState(
        val listening: Boolean = false,
        val partial: String = "",
        val rms: Float = 0f,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /** 系统是否具备中文语音识别能力（部分精简 ROM 会缺）。 */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * 开始识别。
     * @param onText 最终识别结果（已去首尾空白）
     */
    fun start(onText: (String) -> Unit) {
        if (!isAvailable()) {
            _state.value = UiState(error = "这台手机没有可用的语音识别服务，去系统设置里看看？")
            return
        }
        stop()
        main.post {
            val rec = SpeechRecognizer.createSpeechRecognizer(appContext)
            recognizer = rec
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _state.value = UiState(listening = true)
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    // rmsdB 典型范围 -2..10，归一到 0..1 供声波动画使用
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _state.value = _state.value.copy(rms = normalized)
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    _state.value = _state.value.copy(listening = false)
                }

                override fun onError(error: Int) {
                    Log.w(TAG, "SpeechRecognizer error=$error")
                    _state.value = UiState(listening = false, error = messageFor(error))
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    if (text.isBlank()) {
                        _state.value = UiState(error = "没听清，再说一次？")
                    } else {
                        _state.value = UiState(listening = false)
                        onText(text)
                    }
                    release()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    _state.value = _state.value.copy(partial = text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            runCatching { rec.startListening(intent) }
                .onFailure {
                    Log.w(TAG, "startListening 失败: ${it.message}")
                    _state.value = UiState(error = "麦克风没能启动，检查一下录音权限？")
                    release()
                }
        }
    }

    /** 停止收音（用户再点一次麦克风 / 页面退出）。 */
    fun stop() {
        main.post {
            runCatching { recognizer?.stopListening() }
            _state.value = _state.value.copy(listening = false)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun destroy() {
        main.post { release() }
    }

    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "没听清，再说一次？"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "等太久啦，我都没听见声音。"
        SpeechRecognizer.ERROR_AUDIO -> "录音出问题了，检查一下麦克风？"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限，去设置里开一下。"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "语音识别要联网，网络好像不太顺。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "我还在听上一句呢，稍等一下。"
        SpeechRecognizer.ERROR_CLIENT -> "识别被中断了，再试一次？"
        else -> "没听清，再说一次？"
    }

    companion object {
        private const val TAG = "WhaleVoiceInput"
    }
}
