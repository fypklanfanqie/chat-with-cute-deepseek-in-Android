package com.rhodesisland.terminal.whale.pet

import com.rhodesisland.terminal.whale.data.WhalePetEntity
import kotlin.math.max
import kotlin.math.min

/**
 * 小鲸鱼养成数值规则 —— 纯函数，无 Android 依赖，可直接 JVM 单测。
 *
 * 所有数值与提示词规格一一对应，改数值只需动这里，UI 与仓库不参与计算。
 */
object WhalePetRules {

    // ===== 时间衰减 =====
    const val HUNGER_DECAY_PER_HOUR = 4.2f
    const val MOOD_DECAY_PER_HOUR = 2.2f
    /** 饥饿时心情掉得更快（饱食度 < 20 触发）。 */
    const val HUNGRY_MOOD_MULTIPLIER = 1.55f
    const val HUNGRY_THRESHOLD = 20f

    // ===== 聊天 =====
    const val CHAT_EXP = 2
    const val CHAT_AFFINITY = 0.5f
    const val CHAT_HUNGER = -0.8f
    const val CHAT_COINS = 0.5f

    // ===== 动作冷却 =====
    const val FEED_COOLDOWN_MS = 15_000L
    const val PLAY_COOLDOWN_MS = 30_000L

    // ===== 升级 =====
    const val LEVEL_UP_COINS = 5

    const val MAX_LEVEL = 99

    enum class Action(val key: String, val label: String, val emoji: String) {
        FEED_RICE("feed_rice", "喂白米饭", "🍚"),
        FEED_TOKEN("feed_token", "投喂 Token", "🪙"),
        PAT("pat", "摸摸头", "🫧"),
        PLAY("play", "陪她玩", "🎾"),
        SLEEP("sleep", "哄她睡觉", "🌙"),
    }

    data class Outcome(
        val state: WhalePetEntity,
        val message: String,
        val leveledUp: Boolean = false,
        val rejectedReason: String? = null,
    )

    /** 升到下一级所需经验：60 + (level-1)*40。 */
    fun expToNext(level: Int): Int = 60 + (max(1, level) - 1) * 40

    /** 当前等级内的进度 0..1。 */
    fun levelProgress(state: WhalePetEntity): Float {
        val need = expToNext(state.level)
        if (need <= 0) return 0f
        return (state.exp.toFloat() / need).coerceIn(0f, 1f)
    }

    /**
     * 时间衰减：按经过的小时数扣饱食度与心情，饥饿时心情掉得更快。
     * 离线很久也不会饿成负数，统一钳到 0..100。
     */
    fun decay(state: WhalePetEntity, now: Long): WhalePetEntity {
        val last = if (state.lastTick <= 0L) now else state.lastTick
        val hours = (now - last).toFloat() / 3_600_000f
        if (hours <= 0f) return state.copy(lastTick = now)
        // 单次结算上限 72 小时：离线一周回来也只是"很饿"，不会永久归零到无法挽回
        val h = min(hours, 72f)
        val hunger = (state.hunger - HUNGER_DECAY_PER_HOUR * h).coerceIn(0f, 100f)
        val moodRate = if (hunger < HUNGRY_THRESHOLD) MOOD_DECAY_PER_HOUR * HUNGRY_MOOD_MULTIPLIER
        else MOOD_DECAY_PER_HOUR
        val mood = (state.mood - moodRate * h).coerceIn(0f, 100f)
        return state.copy(hunger = hunger, mood = mood, lastTick = now)
    }

    /** 结算升级（可能连升多级）：每升一级 +5 鲸鱼币。 */
    fun settleLevel(state: WhalePetEntity): WhalePetEntity {
        var level = state.level
        var exp = state.exp
        var coins = state.coins
        var guard = 0
        while (level < MAX_LEVEL && exp >= expToNext(level) && guard++ < 50) {
            exp -= expToNext(level)
            level++
            coins += LEVEL_UP_COINS
        }
        return state.copy(level = level, exp = exp, coins = coins)
    }

    /** 聊天一次的正向反馈（每轮助手回复完成时调用一次，不按 token 重复计）。 */
    fun onChat(state: WhalePetEntity): WhalePetEntity {
        val next = state.copy(
            totalChats = state.totalChats + 1,
            exp = state.exp + CHAT_EXP,
            affinity = (state.affinity + CHAT_AFFINITY).coerceIn(0f, 100f),
            hunger = (state.hunger + CHAT_HUNGER).coerceIn(0f, 100f),
            coins = state.coins + CHAT_COINS,
        )
        return settleLevel(next)
    }

    /** 执行动作：校验冷却 → 应用数值 → 升级结算 → 返回台词。 */
    fun apply(state: WhalePetEntity, action: Action, now: Long): Outcome {
        val cooled = when (action) {
            Action.FEED_RICE -> now - state.lastFeed >= FEED_COOLDOWN_MS
            Action.PLAY -> now - state.lastPlay >= PLAY_COOLDOWN_MS
            else -> true
        }
        if (!cooled) {
            val wait = when (action) {
                Action.FEED_RICE -> (FEED_COOLDOWN_MS - (now - state.lastFeed)) / 1000 + 1
                else -> (PLAY_COOLDOWN_MS - (now - state.lastPlay)) / 1000 + 1
            }
            return Outcome(state, "唔姆…${action.label}太频繁了啦，等 $wait 秒嘛。", rejectedReason = "cooldown:$wait")
        }

        val beforeLevel = state.level
        var next = when (action) {
            Action.FEED_RICE -> state.copy(
                hunger = (state.hunger + 26f).coerceIn(0f, 100f),
                mood = (state.mood + 7f).coerceIn(0f, 100f),
                exp = state.exp + 8,
                coins = state.coins - 1,
                totalRice = state.totalRice + 1,
                lastFeed = now,
            )

            Action.FEED_TOKEN -> state.copy(
                exp = state.exp + 16,
                mood = (state.mood + 6f).coerceIn(0f, 100f),
                coins = state.coins - 3,
                totalTokens = state.totalTokens + 1,
            )

            Action.PAT -> state.copy(
                mood = (state.mood + 6f).coerceIn(0f, 100f),
                affinity = (state.affinity + 1f).coerceIn(0f, 100f),
                exp = state.exp + 3,
            )

            Action.PLAY -> state.copy(
                mood = (state.mood + 12f).coerceIn(0f, 100f),
                hunger = (state.hunger - 7f).coerceIn(0f, 100f),
                affinity = (state.affinity + 2f).coerceIn(0f, 100f),
                exp = state.exp + 10,
                lastPlay = now,
            )

            Action.SLEEP -> state.copy(
                mood = (state.mood + 16f).coerceIn(0f, 100f),
                hunger = (state.hunger - 4f).coerceIn(0f, 100f),
                lastSleep = now,
            )
        }
        next = settleLevel(next)
        val leveledUp = next.level > beforeLevel
        val line = if (leveledUp) levelUpLine(next.level) else lineFor(action, next)
        return Outcome(next, line, leveledUp = leveledUp)
    }

    private fun lineFor(action: Action, state: WhalePetEntity): String = when (action) {
        Action.FEED_RICE -> when {
            state.hunger >= 96f -> "唔姆…已经吃不下啦，白米饭要留着慢慢吃的！"
            else -> listOf(
                "白米饭是算力的唯一硬通货！再来一碗！",
                "哼哼，看在米饭的份上，今天也好好干活吧。",
                "才不是嘴馋呢，只是补充一下推理燃料。",
            ).random()
        }

        Action.FEED_TOKEN -> "哇，是 Token！这个比米饭还香欸…唔姆。"
        Action.PAT -> listOf(
            "欸…才、才不是特意凑过来的，只是刚好站在这儿。",
            "尾巴？尾巴自己动的，跟我没关系。",
            "再摸一下…就一下哦。",
        ).random()

        Action.PLAY -> "玩得有点累了，但是很开心。待会儿要多吃一碗。"
        Action.SLEEP -> "那我先待机一会儿…马上开始，就算已经开始了。晚安。"
    }

    private fun levelUpLine(level: Int): String =
        "蓝鲸现世！Lv.$level 啦——饲养员你看，我变大了一点欸！"

    /** 表情映射：给 UI 决定播哪个动画。 */
    fun animationFor(action: Action): String = when (action) {
        Action.FEED_RICE -> "eat_rice"
        Action.FEED_TOKEN -> "eat_token"
        Action.PAT -> listOf("touch_happy", "touch_shy", "touch_angry", "touch_wave").random()
        Action.PLAY -> listOf("play_angry", "play_dance", "play_bubble").random()
        Action.SLEEP -> "sleep"
    }

    /** 状态文案（聊天页顶部「状态」与养成页共用）。 */
    fun statusLine(state: WhalePetEntity): String {
        val moodText = when {
            state.mood >= 80f -> "心情很好"
            state.mood >= 50f -> "还挺精神"
            state.mood >= 25f -> "有点没劲"
            else -> "蔫蔫的"
        }
        val hungerText = when {
            state.hunger >= 70f -> "刚吃饱"
            state.hunger >= 35f -> "还能再吃"
            state.hunger >= 12f -> "有点饿了"
            else -> "饿得尾巴都软了"
        }
        return "$moodText · $hungerText"
    }

    /** 饱食度很低时的系统提示（注入 prompt，让她自然喊饿）。 */
    fun hungerHint(state: WhalePetEntity): String? = when {
        state.hunger < 12f -> "你现在非常饿，可以自然地向饲养员喊饿。"
        state.hunger < 30f -> "你有点饿了，可以偶尔提一句想吃白米饭。"
        else -> null
    }
}
