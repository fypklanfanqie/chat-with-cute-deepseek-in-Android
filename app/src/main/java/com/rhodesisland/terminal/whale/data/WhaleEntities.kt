package com.rhodesisland.terminal.whale.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 长期记忆条目（鲸鱼娘模块）。
 *
 * 参考 agentmemory 的四层巩固模型，但落在**单表 + tier 字段**上：
 *  - [TIER_WORKING]    原始观察（自动捕获，未整理）
 *  - [TIER_EPISODIC]   会话摘要（"发生了什么"）
 *  - [TIER_SEMANTIC]   事实与偏好（"我知道什么"）——「保留记忆」默认写这一层
 *  - [TIER_PROCEDURAL] 经验与做法（"该怎么做"）
 *
 * 版本链：[supersededBy] 指向取代它的新记忆。被取代的旧条目不删除、不出现在检索里，
 * 与 agentmemory 的 supersession 语义一致（可回溯，不丢历史）。
 */
@Entity(
    tableName = "whale_memory",
    indices = [
        Index("createdAt"),
        Index("tier"),
        Index("pinned"),
        Index(value = ["normalized"], unique = false),
    ],
)
data class WhaleMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 原始文本（展示用，保留用户原话与标点）。 */
    val text: String,
    /** 归一化文本（检索用：去标点/空白/emoji、全角转半角、小写）。 */
    val normalized: String,
    /** user / assistant */
    val role: String = "user",
    /** working / episodic / semantic / procedural */
    val tier: String = "semantic",
    /** 不可变写入溯源：user / agent / import */
    val sourceChannel: String = "user",
    /** 来源会话（鲸鱼娘模块内为日期串或会话 id） */
    val sessionKey: String = "",
    /** 来源消息标识（用于「已保留」标记回显） */
    val messageRef: String = "",
    /** 逗号分隔标签 */
    val tags: String = "",
    /** 人工/自动判定的重要性 0..1 */
    val importance: Float = 0.5f,
    /** 被检索命中次数（访问强化，Ebbinghaus 曲线的"复习"） */
    val accessCount: Int = 0,
    val lastAccessAt: Long = 0,
    val createdAt: Long = 0,
    /** 非空表示已被新版本取代（不参与检索，但保留可回溯） */
    val supersededBy: Long? = null,
    /** 置顶记忆永不淘汰、永不衰减 */
    val pinned: Boolean = false,
    /** 软删除时间戳（回收站），非空即不可见 */
    val deletedAt: Long? = null,
)

/** 小鲸鱼养成状态（单例行，id 恒为 1）。 */
@Entity(tableName = "whale_pet")
data class WhalePetEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val name: String = "鲸鱼娘",
    val level: Int = 1,
    val exp: Int = 0,
    val affinity: Float = 12f,
    val hunger: Float = 72f,
    val mood: Float = 78f,
    val coins: Float = 12f,
    val totalChats: Int = 0,
    val totalRice: Int = 0,
    val totalTokens: Int = 0,
    /** 上次结算时间衰减的时间戳 */
    val lastTick: Long = 0,
    val lastFeed: Long = 0,
    val lastPlay: Long = 0,
    val lastSleep: Long = 0,
    /** 最近动作日志（JSON 数组，最多 40 条） */
    val actionsJson: String = "",
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

@Dao
interface WhaleMemoryDao {

    /** 记忆页列表：未删除、未被取代，按置顶 + 时间倒序。 */
    @Query(
        "SELECT * FROM whale_memory WHERE deletedAt IS NULL AND supersededBy IS NULL " +
            "ORDER BY pinned DESC, createdAt DESC"
    )
    fun observeActive(): Flow<List<WhaleMemoryEntity>>

    /** 检索池：一次取全量活跃记忆（上限 500 条，内存内算分，避免给 SQLite 塞分词器）。 */
    @Query(
        "SELECT * FROM whale_memory WHERE deletedAt IS NULL AND supersededBy IS NULL " +
            "ORDER BY createdAt DESC LIMIT 500"
    )
    suspend fun listActive(): List<WhaleMemoryEntity>

    @Query("SELECT * FROM whale_memory WHERE id = :id")
    suspend fun getById(id: Long): WhaleMemoryEntity?

    @Query("SELECT * FROM whale_memory WHERE normalized = :normalized AND deletedAt IS NULL LIMIT 1")
    suspend fun findByNormalized(normalized: String): WhaleMemoryEntity?

    @Query("SELECT COUNT(*) FROM whale_memory WHERE deletedAt IS NULL AND supersededBy IS NULL")
    suspend fun countActive(): Int

    /** 淘汰候选：非置顶、非删除，按「置顶 > 重要性 > 访问次数 > 时间」升序（最该淘汰的在前）。 */
    @Query(
        "SELECT * FROM whale_memory WHERE deletedAt IS NULL AND supersededBy IS NULL AND pinned = 0 " +
            "ORDER BY importance ASC, accessCount ASC, createdAt ASC LIMIT :limit"
    )
    suspend fun evictionCandidates(limit: Int): List<WhaleMemoryEntity>

    /** 时间衰减淘汰候选：太久没被访问且重要性低。 */
    @Query(
        "SELECT * FROM whale_memory WHERE deletedAt IS NULL AND supersededBy IS NULL AND pinned = 0 " +
            "AND importance < :maxImportance AND lastAccessAt < :before ORDER BY lastAccessAt ASC LIMIT :limit"
    )
    suspend fun staleCandidates(before: Long, maxImportance: Float, limit: Int): List<WhaleMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WhaleMemoryEntity): Long

    @Update
    suspend fun update(entity: WhaleMemoryEntity)

    @Query("UPDATE whale_memory SET accessCount = accessCount + 1, lastAccessAt = :now WHERE id IN (:ids)")
    suspend fun touch(ids: List<Long>, now: Long)

    @Query("UPDATE whale_memory SET deletedAt = :now WHERE id = :id")
    suspend fun softDelete(id: Long, now: Long)

    @Query("UPDATE whale_memory SET deletedAt = NULL WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("UPDATE whale_memory SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE whale_memory SET supersededBy = :newId WHERE id = :oldId")
    suspend fun markSuperseded(oldId: Long, newId: Long)

    @Query("DELETE FROM whale_memory WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long)

    @Query("SELECT * FROM whale_memory WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    suspend fun listDeleted(): List<WhaleMemoryEntity>
}

@Dao
interface WhalePetDao {

    @Query("SELECT * FROM whale_pet WHERE id = ${WhalePetEntity.SINGLETON_ID}")
    fun observe(): Flow<WhalePetEntity?>

    @Query("SELECT * FROM whale_pet WHERE id = ${WhalePetEntity.SINGLETON_ID}")
    suspend fun get(): WhalePetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WhalePetEntity)

    @Query("UPDATE whale_pet SET hunger = :hunger, mood = :mood, lastTick = :now WHERE id = ${WhalePetEntity.SINGLETON_ID}")
    suspend fun applyDecay(hunger: Float, mood: Float, now: Long)
}
