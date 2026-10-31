package com.rhodesisland.terminal.whale

import com.rhodesisland.terminal.whale.data.WhaleMemoryEntity
import com.rhodesisland.terminal.whale.data.WhalePetEntity
import com.rhodesisland.terminal.whale.memory.WhaleMemoryScorer
import com.rhodesisland.terminal.whale.pet.WhalePetRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 长期记忆打分器纯函数测试（无 Android 依赖，JVM 直接跑）。 */
class WhaleMemoryScorerTest {

    private fun memory(
        id: Long,
        text: String,
        createdAt: Long = 0,
        importance: Float = 0.5f,
        pinned: Boolean = false,
        access: Int = 0,
    ) = WhaleMemoryEntity(
        id = id,
        text = text,
        normalized = WhaleMemoryScorer.normalize(text),
        createdAt = createdAt,
        lastAccessAt = createdAt,
        importance = importance,
        pinned = pinned,
        accessCount = access,
    )

    @Test
    fun `normalize 去标点、全角转半角、小写`() {
        assertEquals("你好鲸鱼娘 abc 123", WhaleMemoryScorer.normalize("你好，鲸鱼娘！ ＡＢＣ 123"))
    }

    @Test
    fun `normalize 去掉表情与 markdown`() {
        assertEquals("重要的事", WhaleMemoryScorer.normalize("**重要** 的 事 [[sticker:开心]]"))
    }

    @Test
    fun `tokenize 中文切成 2-gram`() {
        val tokens = WhaleMemoryScorer.tokenize("鲸鱼娘")
        assertEquals(listOf("鲸鱼", "鱼娘", "娘"), tokens)
    }

    @Test
    fun `tokenize 英文按词切分`() {
        val tokens = WhaleMemoryScorer.tokenize("deepseek v4 flash")
        assertEquals(listOf("deepseek", "v4", "flash"), tokens)
    }

    @Test
    fun `jaccard 相同文本为 1 完全不同为 0`() {
        val a = WhaleMemoryScorer.bigramSet(WhaleMemoryScorer.normalize("住在内蒙古"))
        val b = WhaleMemoryScorer.bigramSet(WhaleMemoryScorer.normalize("住在内蒙古"))
        assertEquals(1f, WhaleMemoryScorer.jaccard(a, b), 0.001f)
        val c = WhaleMemoryScorer.bigramSet(WhaleMemoryScorer.normalize("今天天气不错"))
        assertEquals(0f, WhaleMemoryScorer.jaccard(a, c), 0.001f)
    }

    @Test
    fun `findSimilar 命中高度相似的旧记忆`() {
        val existing = listOf(memory(1, "我叫丁锦程，在内蒙古工业大学读智能制造"))
        val hit = WhaleMemoryScorer.findSimilar(
            WhaleMemoryScorer.normalize("我叫丁锦程，在内蒙古工业大学读智能制造。"),
            existing,
        )
        assertEquals(1L, hit?.id)
    }

    @Test
    fun `recall 把相关记忆排在前面`() {
        val items = listOf(
            memory(1, "今天想喝奶茶", createdAt = 1_000),
            memory(2, "我叫丁锦程，学号2510110434", createdAt = 1_000),
            memory(3, "喜欢打羽毛球", createdAt = 1_000),
        )
        val result = WhaleMemoryScorer.recall(items, "我叫什么名字", now = 10_000)
        assertTrue(result.isNotEmpty())
        assertEquals(2L, result.first().entity.id)
    }

    @Test
    fun `recall 无词面重合时不注入`() {
        val items = listOf(memory(1, "今天想喝奶茶", createdAt = 1_000))
        val result = WhaleMemoryScorer.recall(items, "量子力学", now = 10_000)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `recall 按 token 预算截断`() {
        val long = "很长的一段记忆".repeat(60) // ≈ 420 字
        val items = (1L..5L).map { memory(it, "$long 第${it}条", createdAt = 1_000) }
        val result = WhaleMemoryScorer.recall(items, "很长", now = 10_000, budgetTokens = 500)
        assertTrue("预算 500 不该塞进两条 420 字的记忆", result.size <= 1)
    }

    @Test
    fun `置顶记忆即使不相关也会被召回`() {
        val items = listOf(memory(1, "饲养员的生日是 3 月 14 日", createdAt = 1_000, pinned = true, importance = 0.95f))
        val result = WhaleMemoryScorer.recall(items, "量子力学", now = 10_000)
        assertEquals(1, result.size)
    }

    @Test
    fun `recency 半衰期约 45 天`() {
        val day = 86_400_000L
        val now = 100L * day
        val fresh = WhaleMemoryScorer.recencyScore(now, now)
        val half = WhaleMemoryScorer.recencyScore(now - 45 * day, now)
        assertEquals(1f, fresh, 0.01f)
        assertEquals(0.5f, half, 0.02f)
    }

    @Test
    fun `token 估算中文按字`() {
        assertEquals(12, WhaleMemoryScorer.estimateTokens("一二三四五六七八九十"))
    }
}

/** 养成数值规则测试。 */
class WhalePetRulesTest {

    private fun state(
        level: Int = 1,
        exp: Int = 0,
        coins: Float = 10f,
        hunger: Float = 50f,
        mood: Float = 50f,
        affinity: Float = 50f,
        lastTick: Long = 0,
    ) = WhalePetEntity(
        level = level,
        exp = exp,
        coins = coins,
        hunger = hunger,
        mood = mood,
        affinity = affinity,
        lastTick = lastTick,
    )

    @Test
    fun `升级所需经验按 60 加 40 递增`() {
        assertEquals(60, WhalePetRules.expToNext(1))
        assertEquals(100, WhalePetRules.expToNext(2))
        assertEquals(140, WhalePetRules.expToNext(3))
    }

    @Test
    fun `经验溢出会连升并给币`() {
        val s = state(level = 1, exp = 60, coins = 0f)
        val after = WhalePetRules.settleLevel(s)
        assertEquals(2, after.level)
        assertEquals(0, after.exp)
        assertEquals(5f, after.coins, 0.01f)
    }

    @Test
    fun `时间衰减按小时扣饱食度与心情`() {
        val hour = 3_600_000L
        val now = 10 * hour
        // 2 小时、非饥饿区间：饱食 -4.2/h、心情 -2.2/h
        val s = state(hunger = 60f, mood = 60f, lastTick = now - 2 * hour)
        val after = WhalePetRules.decay(s, now)
        assertEquals(60f - 4.2f * 2, after.hunger, 0.01f)
        assertEquals(60f - 2.2f * 2, after.mood, 0.01f)
    }

    @Test
    fun `首次结算不衰减`() {
        // lastTick = 0 表示新养成刚创建，不能一进来就饿死
        val after = WhalePetRules.decay(state(hunger = 50f), 99_999L)
        assertEquals(50f, after.hunger, 0.01f)
        assertEquals(99_999L, after.lastTick)
    }

    @Test
    fun `饥饿时心情掉得更快`() {
        // lastTick 必须 > 0，否则 decay 会按「首次结算」跳过衰减
        val now = 3_600_001L
        val hungry = state(hunger = 5f, mood = 50f, lastTick = 1L)
        val after = WhalePetRules.decay(hungry, now)
        assertTrue("饥饿时心情应低于正常速率", after.mood < 50f - 2.2f)
    }

    @Test
    fun `喂饭有 15 秒冷却`() {
        val now = 5_000L
        val s = state().copy(lastFeed = now - 3_000)
        val outcome = WhalePetRules.apply(s, WhalePetRules.Action.FEED_RICE, now)
        assertTrue(outcome.rejectedReason != null)
    }

    @Test
    fun `喂饭提升饱食度并扣一枚币`() {
        val s = state(hunger = 40f, coins = 10f).copy(lastFeed = 0)
        val outcome = WhalePetRules.apply(s, WhalePetRules.Action.FEED_RICE, 60_000)
        assertNull(outcome.rejectedReason)
        assertEquals(66f, outcome.state.hunger, 0.01f)
        assertEquals(9f, outcome.state.coins, 0.01f)
        assertEquals(1, outcome.state.totalRice)
    }

    @Test
    fun `聊天加经验好感并扣一点饱食`() {
        val after = WhalePetRules.onChat(state())
        assertEquals(1, after.totalChats)
        assertEquals(2, after.exp)
        assertEquals(50.5f, after.affinity, 0.01f)
        assertEquals(49.2f, after.hunger, 0.01f)
    }
}
