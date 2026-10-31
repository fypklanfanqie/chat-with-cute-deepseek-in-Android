package com.rhodesisland.terminal.whale.memory

import android.util.Log
import com.rhodesisland.terminal.whale.data.WhaleMemoryDao
import com.rhodesisland.terminal.whale.data.WhaleMemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** 保存结果：命中相似记忆时带回旧条目，供 UI 提示「已有相似记忆」。 */
data class MemorySaveResult(
    val id: Long,
    val similarTo: WhaleMemoryEntity? = null,
    val duplicate: Boolean = false,
)

/**
 * 长期记忆仓库。
 *
 * 写入语义对齐 agentmemory：
 *  - **幂等**：normalized 的 SHA-256 作为天然去重键，同一句话重复保留不会产生两条；
 *  - **相似只提示不合并**：2-gram Jaccard ≥ 0.82 时返回 similarTo，由用户决定是否取代旧版本；
 *  - **取代而非覆盖**：调 [supersede] 时旧行 supersededBy 指向新行，历史可回溯。
 *
 * 容量：500 条活跃记忆；超出按「重要性 → 访问次数 → 时间」淘汰最弱的一条。
 */
class WhaleMemoryRepository(private val dao: WhaleMemoryDao) {

    companion object {
        private const val TAG = "WhaleMemory"
        const val MAX_ACTIVE = 500
        const val MAX_TEXT = 1200
        /** 默认召回预算（token）。 */
        const val RECALL_BUDGET = 1200
    }

    fun observeActive(): Flow<List<WhaleMemoryEntity>> = dao.observeActive()

    suspend fun listActive(): List<WhaleMemoryEntity> = withContext(Dispatchers.IO) { dao.listActive() }

    suspend fun get(id: Long): WhaleMemoryEntity? = withContext(Dispatchers.IO) { dao.getById(id) }

    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.countActive() }

    suspend fun listDeleted(): List<WhaleMemoryEntity> = withContext(Dispatchers.IO) { dao.listDeleted() }

    /** 由消息文本生成稳定引用（用于「已保留」回显）。 */
    fun refOf(sessionKey: String, text: String): String =
        sessionKey + "#" + sha256(text).take(16)

    /**
     * 保存一条记忆。
     *
     * @param pinnedByUser 用户主动点「保留记忆」= true（重要性更高）
     * @return 已存在完全相同内容时 duplicate=true 且 id 指向旧条目
     */
    suspend fun save(
        text: String,
        role: String,
        sessionKey: String,
        messageRef: String,
        tier: String = "semantic",
        sourceChannel: String = "user",
        pinnedByUser: Boolean = true,
        tags: String = "",
    ): MemorySaveResult = withContext(Dispatchers.IO) {
        val trimmed = text.trim().take(MAX_TEXT)
        if (trimmed.isBlank()) return@withContext MemorySaveResult(-1)
        val normalized = WhaleMemoryScorer.normalize(trimmed)
        if (normalized.isBlank()) return@withContext MemorySaveResult(-1)

        dao.findByNormalized(normalized)?.let { existing ->
            Log.d(TAG, "duplicate memory id=${existing.id}")
            return@withContext MemorySaveResult(existing.id, similarTo = existing, duplicate = true)
        }

        val active = dao.listActive()
        val similar = WhaleMemoryScorer.findSimilar(normalized, active)

        val now = System.currentTimeMillis()
        val entity = WhaleMemoryEntity(
            text = trimmed,
            normalized = normalized,
            role = role,
            tier = tier,
            sourceChannel = sourceChannel,
            sessionKey = sessionKey,
            messageRef = messageRef,
            tags = tags,
            importance = WhaleMemoryScorer.heuristicImportance(trimmed, pinnedByUser),
            accessCount = 0,
            lastAccessAt = now,
            createdAt = now,
            pinned = false,
        )
        val id = dao.insert(entity)
        enforceCapacity()
        Log.d(TAG, "saved memory id=$id len=${trimmed.length} similar=${similar?.id}")
        MemorySaveResult(id, similarTo = similar)
    }

    /** 手动添加（记忆页「+」）：内容更短、允许自定义标签。 */
    suspend fun add(text: String, tags: String = ""): MemorySaveResult =
        save(text = text, role = "user", sessionKey = "manual", messageRef = "", tags = tags, sourceChannel = "user")

    suspend fun update(id: Long, newText: String) = withContext(Dispatchers.IO) {
        val old = dao.getById(id) ?: return@withContext
        val trimmed = newText.trim().take(MAX_TEXT)
        if (trimmed.isBlank()) return@withContext
        val normalized = WhaleMemoryScorer.normalize(trimmed)
        // 编辑视为「产生新版本」：旧行标记被取代，新行继承置顶与访问计数
        val entity = old.copy(
            id = 0,
            text = trimmed,
            normalized = normalized,
            createdAt = System.currentTimeMillis(),
            lastAccessAt = System.currentTimeMillis(),
            accessCount = old.accessCount,
            pinned = old.pinned,
        )
        val newId = dao.insert(entity)
        dao.markSuperseded(id, newId)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) { dao.setPinned(id, pinned) }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.softDelete(id, System.currentTimeMillis())
    }

    suspend fun restore(id: Long) = withContext(Dispatchers.IO) { dao.restore(id) }

    /** 清理回收站中超过 30 天的条目。 */
    suspend fun purgeRecycleBin() = withContext(Dispatchers.IO) {
        dao.purgeDeletedBefore(System.currentTimeMillis() - 30L * 86_400_000L)
    }

    /**
     * 召回：按当前输入取相关记忆，返回已按预算截断的列表，并把命中条目的访问计数 +1
     * （访问强化，对应 Ebbinghaus 的"复习"）。
     */
    suspend fun recall(query: String, budgetTokens: Int = RECALL_BUDGET): List<WhaleMemoryEntity> =
        withContext(Dispatchers.IO) {
            val all = dao.listActive()
            if (all.isEmpty()) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val scored = WhaleMemoryScorer.recall(all, query, now, budgetTokens)
            if (scored.isNotEmpty()) {
                runCatching { dao.touch(scored.map { it.entity.id }, now) }
            }
            scored.map { it.entity }
        }

    /** 关键词搜索（记忆页顶部搜索框）：纯字面匹配，不消耗访问计数。 */
    suspend fun search(keyword: String): List<WhaleMemoryEntity> = withContext(Dispatchers.IO) {
        val all = dao.listActive()
        val nq = WhaleMemoryScorer.normalize(keyword)
        if (nq.isBlank()) return@withContext WhaleMemoryScorer.sortForDisplay(all)
        val tokens = WhaleMemoryScorer.tokenize(nq)
        all.filter { item ->
            item.normalized.contains(nq) || tokens.any { item.normalized.contains(it) }
        }.let { WhaleMemoryScorer.sortForDisplay(it) }
    }

    /** 容量控制：超出 500 条时淘汰最弱的一条（非置顶）。 */
    private suspend fun enforceCapacity() {
        while (dao.countActive() > MAX_ACTIVE) {
            val victim = dao.evictionCandidates(1).firstOrNull() ?: break
            Log.d(TAG, "evict memory id=${victim.id} importance=${victim.importance}")
            dao.softDelete(victim.id, System.currentTimeMillis())
        }
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
