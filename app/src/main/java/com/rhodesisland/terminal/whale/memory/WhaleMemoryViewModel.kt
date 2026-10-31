package com.rhodesisland.terminal.whale.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.whale.data.WhaleMemoryEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 记忆页 ViewModel：列表 / 搜索 / 增删改 / 置顶。 */
class WhaleMemoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as RhodesApp).container.whaleMemoryRepository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val items: StateFlow<List<WhaleMemoryEntity>> =
        combine(repo.observeActive(), _query) { list, q ->
            if (q.isBlank()) list else filter(list, q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateQuery(q: String) {
        _query.value = q
    }

    fun consumeNotice() {
        _notice.value = null
    }

    fun add(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val r = runCatching { repo.add(text) }.getOrNull()
            _notice.value = when {
                r == null || r.id <= 0 -> "这条没存进去，再试一次？"
                r.duplicate -> "已经记过一模一样的内容啦。"
                r.similarTo != null -> "存好了，之前有一条很像的，要不要手动合并一下？"
                else -> "记住了。"
            }
        }
    }

    fun update(id: Long, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.update(id, text) }
            _notice.value = "改好了，旧版本还留着（可回溯）。"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repo.delete(id) }
            _notice.value = "删掉了，30 天内还能恢复。"
        }
    }

    fun togglePin(item: WhaleMemoryEntity) {
        viewModelScope.launch { runCatching { repo.setPinned(item.id, !item.pinned) } }
    }

    /** 本地过滤：先按原文包含，再按 2-gram 词元命中（与检索口径一致）。 */
    private fun filter(list: List<WhaleMemoryEntity>, q: String): List<WhaleMemoryEntity> {
        val nq = WhaleMemoryScorer.normalize(q)
        if (nq.isBlank()) return WhaleMemoryScorer.sortForDisplay(list)
        val tokens = WhaleMemoryScorer.tokenize(nq)
        return list.filter { item ->
            item.normalized.contains(nq) || tokens.any { item.normalized.contains(it) }
        }.let { WhaleMemoryScorer.sortForDisplay(it) }
    }
}
