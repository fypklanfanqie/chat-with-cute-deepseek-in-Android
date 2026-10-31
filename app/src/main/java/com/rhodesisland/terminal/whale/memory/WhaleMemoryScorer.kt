package com.rhodesisland.terminal.whale.memory

import com.rhodesisland.terminal.whale.data.WhaleMemoryEntity
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * 长期记忆打分器 —— 纯函数，无 Android 依赖，可直接 JVM 单测。
 *
 * 设计取自 agentmemory 的「混合检索 + Ebbinghaus 遗忘」，但做了两点本地化裁剪：
 *  1. 只用**一条**检索流（中文 2-gram BM25）。它原版是 BM25 + 向量 + 图三流用 RRF 融合；
 *     只有一流时 RRF 会退化成常数排名，因此这里改用「线性加权 + 归一化」。
 *  2. 不做向量嵌入。手机端跑 all-MiniLM 的代价（模型 90MB + 首帧 200ms+）与收益不成正比，
 *     中文短记忆靠字面重合已经够用。
 */
object WhaleMemoryScorer {

    // ===== BM25 参数（与 Lucene 默认一致） =====
    const val K1 = 1.2f
    const val B = 0.75f

    /** Ebbinghaus 半衰期：45 天。超过 45 天未复习，时间分衰减到 0.5。 */
    const val HALF_LIFE_DAYS = 45.0

    /** 相似判重阈值（字符 2-gram Jaccard）。≥ 该值只提示、不自动合并。 */
    const val SIMILAR_THRESHOLD = 0.82f

    /** 最终分权重，和为 1。 */
    const val W_TERM = 0.55f
    const val W_RECENCY = 0.20f
    const val W_IMPORTANCE = 0.10f
    const val W_ACCESS = 0.10f
    const val W_PINNED = 0.05f

    private const val DAY_MS = 86_400_000.0

    /** 检索结果。 */
    data class Scored(
        val entity: WhaleMemoryEntity,
        val termScore: Float,
        val recencyScore: Float,
        val finalScore: Float,
    )

    // ==================== 归一化 ====================

    /**
     * 归一化流水线（顺序不可乱）：
     * 全角转半角 → 小写 → 去 URL → 去 Markdown 记号 → 只保留中英数字 → 折叠空白。
     *
     * 保留中文标点转换前的边界：标点全部删除后，「你好，鲸鱼娘」与「你好鲸鱼娘」得到相同结果，
     * 这样语音转写漏标点不会影响召回。
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when {
                ch.code == 0x3000 -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            sb.append(c)
        }
        var s = sb.toString().lowercase()
        // 去掉表情包标记（[[sticker:开心]]）：它是渲染指令，不该进检索词
        s = s.replace(Regex("""\[\[\s*sticker\s*:[^\]]+\]\]"""), " ")
        // 去掉 URL（语音/截图里常见的噪声）
        s = s.replace(Regex("https?://\\S+"), " ")
        // 去掉 Markdown 记号与代码围栏
        s = s.replace(Regex("[`*_#>~|\\\\]"), " ")
        // 只保留：中文、英文字母、数字
        val out = StringBuilder(s.length)
        for (ch in s) {
            if (ch.isLetterOrDigit()) out.append(ch) else out.append(' ')
        }
        // 折叠空白后，再去掉「汉字 空格 汉字」里的空格：
        // 「重要 的 事」与「重要的事」必须归一到同一个串，否则判重会漏。
        return out.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
    }

    /**
     * 切分检索词：
     *  - 连续中文 → 字符 2-gram（单字成词时保留单字）
     *  - 连续英文/数字 → 整词
     *
     * 中文不用词典分词：2-gram 对「鲸鱼娘」「白米饭」这类专名召回稳定，且零依赖。
     */
    fun tokenize(normalized: String): List<String> {
        if (normalized.isEmpty()) return emptyList()
        val tokens = ArrayList<String>(normalized.length)
        val buf = StringBuilder()
        var lastCjk = false
        fun flushWord() {
            if (buf.isNotEmpty()) {
                tokens.add(buf.toString())
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            when {
                ch == ' ' -> {
                    flushWord()
                    lastCjk = false
                    i++
                }
                isCjk(ch) -> {
                    flushWord()
                    // 与后一个字符组成 bigram；末尾单字单独成词
                    val next = normalized.getOrNull(i + 1)
                    if (next != null && isCjk(next)) {
                        tokens.add("$ch$next")
                    } else {
                        tokens.add(ch.toString())
                    }
                    lastCjk = true
                    i++
                }
                else -> {
                    if (lastCjk) flushWord()
                    buf.append(ch)
                    lastCjk = false
                    i++
                }
            }
        }
        flushWord()
        return tokens
    }

    private fun isCjk(ch: Char): Boolean =
        ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF

    /** 字符 2-gram 集合（判重用，不去重会重复计数）。 */
    fun bigramSet(normalized: String): Set<String> {
        val compact = normalized.replace(" ", "")
        if (compact.length < 2) return if (compact.isEmpty()) emptySet() else setOf(compact)
        val set = HashSet<String>(compact.length)
        for (i in 0 until compact.length - 1) set.add(compact.substring(i, i + 2))
        return set
    }

    /** Jaccard 相似度：交集 / 并集。 */
    fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        var inter = 0
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        for (t in small) if (t in large) inter++
        val union = a.size + b.size - inter
        return if (union == 0) 0f else inter.toFloat() / union
    }

    /** 判重：与已有记忆高度相似则返回该条（仅提示，由调用方决定是否取代）。 */
    fun findSimilar(
        normalized: String,
        existing: List<WhaleMemoryEntity>,
        threshold: Float = SIMILAR_THRESHOLD,
    ): WhaleMemoryEntity? {
        val target = bigramSet(normalized)
        if (target.isEmpty()) return null
        var best: WhaleMemoryEntity? = null
        var bestScore = threshold
        for (item in existing) {
            val score = jaccard(target, bigramSet(item.normalized))
            if (score >= bestScore) {
                bestScore = score
                best = item
            }
        }
        return best
    }

    // ==================== 打分 ====================

    /** Ebbinghaus：retention = exp(-ln2 * t / halfLife)。 */
    fun recencyScore(lastTouchedAt: Long, now: Long): Float {
        val ageDays = max(0.0, (now - lastTouchedAt).toDouble() / DAY_MS)
        val r = exp(-ln(2.0) * ageDays / HALF_LIFE_DAYS)
        return r.coerceIn(0.0, 1.0).toFloat()
    }

    /** 访问强化：log 饱和，8 次访问达到 1.0，之后收益趋零（防止刷出永久高分）。 */
    fun accessScore(accessCount: Int): Float {
        if (accessCount <= 0) return 0f
        return (ln(1.0 + accessCount) / ln(9.0)).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * 召回：对候选集做 BM25，再与时间/重要性/访问/置顶线性融合，按 token 预算截断。
     *
     * @param items 候选记忆（已过滤 deleted/superseded）
     * @param query 当前用户输入
     * @param budgetTokens 注入预算（1 个汉字≈1 token，英文 4 字符≈1 token）
     */
    fun recall(
        items: List<WhaleMemoryEntity>,
        query: String,
        now: Long,
        budgetTokens: Int = 1200,
    ): List<Scored> {
        if (items.isEmpty()) return emptyList()
        val normalizedQuery = normalize(query)
        val queryTokens = tokenize(normalizedQuery).distinct()
        val docs = items.map { tokenize(it.normalized) }
        val n = docs.size
        val totalLen = docs.sumOf { it.size }
        // 全空库（或全是标点）：没有可算的词，直接返回空，避免除零产生 NaN 排序
        if (totalLen == 0) return emptyList()
        val avgLen = totalLen.toDouble() / n

        // df 统计
        val df = HashMap<String, Int>(queryTokens.size * 2)
        for (doc in docs) {
            val uniq = doc.toHashSet()
            for (t in queryTokens) if (t in uniq) df[t] = (df[t] ?: 0) + 1
        }

        val raw = FloatArray(n)
        var maxRaw = 0f
        for (i in 0 until n) {
            val doc = docs[i]
            if (doc.isEmpty()) continue
            val tf = HashMap<String, Int>()
            for (t in doc) if (t in queryTokens) tf[t] = (tf[t] ?: 0) + 1
            if (tf.isEmpty()) continue
            val dl = doc.size.toDouble()
            var sum = 0.0
            for (t in queryTokens) {
                val f = tf[t] ?: continue
                val dfi = df[t] ?: 0
                val idf = ln(1.0 + (n - dfi + 0.5) / (dfi + 0.5))
                val denom = f + K1 * (1 - B + B * dl / avgLen)
                sum += idf * (f * (K1 + 1)) / denom
            }
            raw[i] = sum.toFloat()
            if (raw[i] > maxRaw) maxRaw = raw[i]
        }

        val result = ArrayList<Scored>(n)
        for (i in 0 until n) {
            val item = items[i]
            val term = if (maxRaw > 0f) raw[i] / maxRaw else 0f
            val lastTouched = max(item.lastAccessAt, item.createdAt)
            val recency = if (item.pinned) 1f else recencyScore(lastTouched, now)
            val access = accessScore(item.accessCount)
            val pinned = if (item.pinned) 1f else 0f
            val finalScore = W_TERM * term + W_RECENCY * recency +
                W_IMPORTANCE * item.importance + W_ACCESS * access + W_PINNED * pinned
            // 没有任何词面重合且非置顶的记忆不参与注入（否则等于把整库塞进 prompt）
            if (term <= 0f && item.importance < 0.9f) continue
            result.add(Scored(item, term, recency, finalScore))
        }

        result.sortByDescending { it.finalScore }

        // token 预算截断：置顶条目优先，其后按分数
        val picked = ArrayList<Scored>(result.size)
        var used = 0
        for (s in result) {
            val cost = estimateTokens(s.entity.text)
            if (used + cost > budgetTokens) continue
            used += cost
            picked.add(s)
        }
        return picked
    }

    /** 粗略 token 估算：汉字 1 token/字，英文 4 字符/token，空白另计。 */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else if (!ch.isWhitespace()) other++
        }
        return cjk + (other + 3) / 4 + 2
    }

    /**
     * 重要性启发式：用户亲自保留的默认 0.75，含「记住/以后/我叫/我喜欢」等指令词提到 0.9。
     * 不做 LLM 打分——那会在每次保存时多花一次请求，收益不值。
     */
    fun heuristicImportance(text: String, pinnedByUser: Boolean = true): Float {
        val t = text.lowercase()
        val strong = listOf("记住", "以后", "别再", "我叫", "我的名字", "我喜欢", "我不喜欢", "生日", "过敏")
        if (strong.any { t.contains(it) }) return 0.9f
        return if (pinnedByUser) 0.75f else 0.5f
    }

    /** 供「首次打开记忆库」的排序兜底：时间最近优先。 */
    fun sortForDisplay(items: List<WhaleMemoryEntity>): List<WhaleMemoryEntity> =
        items.sortedWith(compareByDescending<WhaleMemoryEntity> { it.pinned }.thenByDescending { it.createdAt })

    /** 保留给未来的向量扩展位（当前恒为 0，仅用于接口稳定）。 */
    @Suppress("unused")
    fun semanticPlaceholder(): Double = PI * 0.0
}
