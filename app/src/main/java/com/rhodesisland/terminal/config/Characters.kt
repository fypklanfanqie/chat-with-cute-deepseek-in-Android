package com.rhodesisland.terminal.config

import com.rhodesisland.terminal.data.model.Character
import com.rhodesisland.terminal.whale.WhalePersona

/**
 * 角色表（鲸鱼娘版）。
 *
 * 原 384 位明日方舟干员（`ALL_BASE` + `ExtraCharacters`）已按需求**完全移除**：
 * 内置角色只剩一位——鲸鱼娘，不可删除。
 *
 * 保留这一层而不是把调用方全改掉的原因：角色概念被主 App 的会话、群聊、朋友圈、小说、
 * 好感度等模块复用，`Characters.ALL` 是它们唯一的内置来源。这里退回「只有一条」，
 * 所有旧模块仍能拿到一个合法的默认角色，不会因为空表而崩。
 */
object Characters {

    /** 自定义角色 / 无专属问候池时回退的通用问候语。 */
    val GENERIC_GREETINGS: List<String> = listOf(
        "你好呀，很高兴见到你～",
        "你终于来了，我等你很久啦。",
        "嘿，今天想聊点什么？",
        "见到你真开心，我们开始吧。",
    )

    const val WHALE_ID = WhalePersona.ID

    /** 唯一的内置角色。 */
    val BUILTIN_WHALE: Character = Character(
        id = WHALE_ID,
        name = WhalePersona.NAME,
        code = "WHALE",
        role = "推理海的女仆",
        race = "鲸系",
        ttsEnabled = true,
        image = "",
        greetings = listOf(
            WhalePersona.greetingByHour(),
            "比起复杂的深度推理，先来一碗香喷喷的白米饭吧！",
            "饲养员，今天也要好好干活哦（才不是特意等你的）",
        ),
        skills = emptyList(),
        talents = emptyList(),
        systemPrompt = WhalePersona.PERSONA_CARD,
        isCustom = false,
    )

    /** 全量角色表：只有鲸鱼娘。 */
    val ALL: Map<String, Character> = linkedMapOf(WHALE_ID to BUILTIN_WHALE)

    /** 角色卡顺序。 */
    val ORDER: List<String> = listOf(WHALE_ID)

    /** 全量排序（与 [ORDER] 一致，保留旧字段名以免调用方改动）。 */
    val ORDER_ALL: List<String> = ORDER

    /** 默认角色。 */
    const val DEFAULT_CHARACTER_ID = WHALE_ID

    fun get(id: String): Character? = ALL[id]

    fun getOrderedList(): List<Character> = ORDER_ALL.mapNotNull { ALL[it] }
}
