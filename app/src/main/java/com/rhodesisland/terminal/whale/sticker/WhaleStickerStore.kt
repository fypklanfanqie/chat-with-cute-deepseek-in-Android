package com.rhodesisland.terminal.whale.sticker

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StickerDef(
    val id: String,
    val file: String,
    val label: String,
    val tags: List<String> = emptyList(),
)

@Serializable
data class CustomSticker(
    val id: String,
    val path: String,
    val label: String,
)

/** 解析结果：正文（已剔除标记） + 依次出现的表情标签。 */
data class ParsedStickers(
    val text: String,
    val labels: List<String>,
) {
    val isEmpty: Boolean get() = labels.isEmpty()
}

/**
 * 表情包仓库。
 *
 * 内置 24 张从 `assets/whale/stickers` 读取（manifest.json 提供 label 映射）；
 * 用户自定义表情存 App 私有目录 `files/whale/stickers/`，索引落 `custom_stickers.json`。
 *
 * 解析规则与提示词约定一致：`[[sticker:标签]]` 从正文中**整段删除**，按标签找图；
 * 找不到对应图就静默丢弃，绝不把标记原样显示成文字。
 */
class WhaleStickerStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val manifest: List<StickerDef> by lazy { loadManifest() }

    private val customFile: File by lazy {
        File(context.filesDir, "whale").apply { mkdirs() }.resolve("custom_stickers.json")
    }

    private var customCache: List<CustomSticker>? = null

    fun builtin(): List<StickerDef> = manifest

    fun custom(): List<CustomSticker> {
        customCache?.let { return it }
        val list = runCatching {
            if (!customFile.exists()) emptyList()
            else json.decodeFromString<List<CustomSticker>>(customFile.readText())
        }.getOrDefault(emptyList())
        customCache = list
        return list
    }

    /** 面板展示用：内置 + 自定义，统一成 (id, label, model)。 */
    fun allForPanel(): List<StickerItem> =
        manifest.map { StickerItem(it.id, it.label, "file:///android_asset/whale/stickers/${it.file}", false) } +
            custom().map { StickerItem(it.id, it.label, it.path, true) }

    fun byLabel(label: String): StickerItem? {
        val key = label.trim()
        manifest.firstOrNull { it.label == key }?.let {
            return StickerItem(it.id, it.label, "file:///android_asset/whale/stickers/${it.file}", false)
        }
        // 标签不在主 label 上时，回退到 tags 里找（提示词允许用同义标签）
        manifest.firstOrNull { key in it.tags }?.let {
            return StickerItem(it.id, it.label, "file:///android_asset/whale/stickers/${it.file}", false)
        }
        return custom().firstOrNull { it.label == key }?.let { StickerItem(it.id, it.label, it.path, true) }
    }

    /** 从相册导入一张自定义表情（复制到私有目录，避免原图被删后失效）。 */
    fun importCustom(source: File, label: String): CustomSticker? = runCatching {
        val dir = File(context.filesDir, "whale/stickers").apply { mkdirs() }
        val id = "custom-" + System.currentTimeMillis()
        val target = File(dir, "$id.webp")
        source.copyTo(target, overwrite = true)
        val item = CustomSticker(id = id, path = target.absolutePath, label = label.ifBlank { "自定义" })
        val next = custom() + item
        customFile.writeText(json.encodeToString(next))
        customCache = next
        item
    }.onFailure { Log.w(TAG, "导入表情失败: ${it.message}") }.getOrNull()

    fun removeCustom(id: String) = runCatching {
        val current = custom()
        val target = current.firstOrNull { it.id == id }
        val next = current.filterNot { it.id == id }
        target?.let { runCatching { File(it.path).delete() } }
        customFile.writeText(json.encodeToString(next))
        customCache = next
    }.onFailure { Log.w(TAG, "删除表情失败: ${it.message}") }

    /**
     * 解析助手回复里的表情标记。
     * 标记可以出现在句末、单独一行、甚至夹在句中——一律摘掉并保留其余正文。
     */
    fun parse(raw: String): ParsedStickers {
        if (raw.isEmpty() || !raw.contains("[[sticker:")) return ParsedStickers(raw, emptyList())
        val labels = ArrayList<String>(2)
        val cleaned = STICKER_REGEX.replace(raw) { m ->
            val label = m.groupValues[1].trim()
            if (byLabel(label) != null) labels.add(label) else Log.d(TAG, "未知表情标签: $label")
            ""
        }
        return ParsedStickers(cleaned.trim(), labels)
    }

    private fun loadManifest(): List<StickerDef> = runCatching {
        val text = context.assets.open("whale/stickers/manifest.json").bufferedReader().use { it.readText() }
        json.decodeFromString<List<StickerDef>>(text)
    }.onFailure { Log.w(TAG, "读取表情清单失败: ${it.message}") }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "WhaleSticker"
        private val STICKER_REGEX = Regex("""\[\[\s*sticker\s*:\s*([^\]]+)\]\]""")
    }
}

/** 面板条目（内置与自定义同构，便于 LazyVerticalGrid 直接渲染）。 */
data class StickerItem(
    val id: String,
    val label: String,
    val model: Any,
    val isCustom: Boolean,
)
