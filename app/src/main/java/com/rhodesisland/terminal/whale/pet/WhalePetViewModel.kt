package com.rhodesisland.terminal.whale.pet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.whale.data.WhalePetEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 小鲸鱼养成页 ViewModel。
 *
 * 动画切换策略：动作执行后播对应动画 [ACTION_ANIMATION_MS]，然后回到待机；
 * 升级时优先播 `levelup`（蓝鲸现世）并停留更久。
 */
class WhalePetViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as RhodesApp).container.whalePetRepository

    private val _pet = MutableStateFlow(WhalePetEntity())
    val pet: StateFlow<WhalePetEntity> = _pet.asStateFlow()

    /** 当前播放的动画资源名（assets/whale/pet/<name>.webp）。 */
    private val _animation = MutableStateFlow("idle")
    val animation: StateFlow<String> = _animation.asStateFlow()

    /** 头顶气泡台词。 */
    private val _line = MutableStateFlow<String?>(null)
    val line: StateFlow<String?> = _line.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var revertJob: Job? = null

    init {
        viewModelScope.launch {
            _pet.value = runCatching { repo.tick() }.getOrDefault(WhalePetEntity())
            _animation.value = repo.idleAnimationFor(_pet.value)
        }
        viewModelScope.launch {
            repo.observe().collect { _pet.value = it }
        }
    }

    fun perform(action: WhalePetRules.Action) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = runCatching { repo.perform(action) }.getOrNull()
            if (outcome != null) {
                _pet.value = outcome.state
                _line.value = outcome.message
                _animation.value = if (outcome.leveledUp) "levelup" else WhalePetRules.animationFor(action)
                scheduleRevert(if (outcome.leveledUp) LEVEL_UP_HOLD_MS else ACTION_ANIMATION_MS)
            }
            _busy.value = false
        }
    }

    /** 点击桌宠 = 摸摸头。 */
    fun pat() = perform(WhalePetRules.Action.PAT)

    fun rename(name: String) {
        viewModelScope.launch {
            runCatching { repo.rename(name) }
            _pet.value = runCatching { repo.tick() }.getOrDefault(_pet.value)
        }
    }

    fun actionsLog(): List<PetActionLog> = repo.readActions(_pet.value.actionsJson).reversed()

    fun dismissLine() {
        _line.value = null
    }

    private fun scheduleRevert(ms: Long) {
        revertJob?.cancel()
        revertJob = viewModelScope.launch {
            delay(ms)
            _line.value = null
            _animation.value = repo.idleAnimationFor(_pet.value)
        }
    }

    companion object {
        const val ACTION_ANIMATION_MS = 2600L
        const val LEVEL_UP_HOLD_MS = 3600L
    }
}
