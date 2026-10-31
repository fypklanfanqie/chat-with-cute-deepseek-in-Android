package com.rhodesisland.terminal.whale.pet

import com.rhodesisland.terminal.whale.data.WhalePetDao
import com.rhodesisland.terminal.whale.data.WhalePetEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PetActionLog(
    val key: String,
    val at: Long,
    val text: String,
)

/**
 * 小鲸鱼养成仓库。
 *
 * 时间衰减是**惰性结算**：不跑后台定时任务，任何一次读取（打开聊天/养成页、发消息）
 * 都先调 [refresh] 按经过时长补算。手机上比 WorkManager 定时靠谱得多，也不耗电。
 */
class WhalePetRepository(private val dao: WhalePetDao) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observe(): Flow<WhalePetEntity> = dao.observe().map { it ?: defaultState() }

    suspend fun get(): WhalePetEntity = withContext(Dispatchers.IO) { refresh() }

    private fun defaultState(): WhalePetEntity {
        val now = System.currentTimeMillis()
        return WhalePetEntity(lastTick = now)
    }

    /** 读取 + 补算衰减 + 落库。 */
    private suspend fun refresh(): WhalePetEntity {
        val now = System.currentTimeMillis()
        val current = dao.get() ?: defaultState().also { dao.upsert(it) }
        val decayed = WhalePetRules.decay(current, now)
        val settled = WhalePetRules.settleLevel(decayed)
        if (settled != current) dao.upsert(settled)
        return settled
    }

    /** 打开页面时调用：结算离线衰减（幂等，重复调用无副作用）。 */
    suspend fun tick(): WhalePetEntity = withContext(Dispatchers.IO) { refresh() }

    /** 一轮对话完成：经验/好感/饱食度变化，并结算升级。 */
    suspend fun onChatRound(): WhalePetEntity = withContext(Dispatchers.IO) {
        val current = refresh()
        val next = WhalePetRules.onChat(current)
        dao.upsert(next)
        next
    }

    /** 执行养成动作，返回台词与动画 key；同时写入成长记录（最多 40 条）。 */
    suspend fun perform(action: WhalePetRules.Action): WhalePetRules.Outcome = withContext(Dispatchers.IO) {
        val current = refresh()
        val outcome = WhalePetRules.apply(current, action, System.currentTimeMillis())
        if (outcome.rejectedReason == null) {
            val logs = (readActions(current.actionsJson) + PetActionLog(action.key, System.currentTimeMillis(), outcome.message))
                .takeLast(MAX_ACTION_LOG)
            dao.upsert(outcome.state.copy(actionsJson = json.encodeToString(logs)))
        }
        outcome
    }

    suspend fun rename(name: String) = withContext(Dispatchers.IO) {
        val current = refresh()
        dao.upsert(current.copy(name = name.trim().ifBlank { "鲸鱼娘" }))
    }

    fun readActions(raw: String): List<PetActionLog> = runCatching {
        if (raw.isBlank()) emptyList() else json.decodeFromString<List<PetActionLog>>(raw)
    }.getOrDefault(emptyList())

    /** 供 UI 展示的动画 key：按当前状态挑一个，没有动作时待机。 */
    fun idleAnimationFor(state: WhalePetEntity): String = when {
        state.hunger < 10f -> "stretch"
        state.mood < 15f -> "sleep"
        else -> "idle"
    }

    companion object {
        const val MAX_ACTION_LOG = 40
    }
}
