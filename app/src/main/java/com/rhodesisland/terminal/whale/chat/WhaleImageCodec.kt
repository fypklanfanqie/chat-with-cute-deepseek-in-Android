package com.rhodesisland.terminal.whale.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** 导入结果：私有目录路径（持久化用）+ base64（本次请求用）。 */
data class ImportedImage(
    val path: String,
    val base64: String,
    val mime: String = "image/jpeg",
)

/**
 * 图片编解码：相册/拍照 → 长边 ≤1400px、JPEG q86 → 私有目录 + base64。
 *
 * 为什么先落盘：消息要能在重启后显示缩略图，而 base64 只在当前请求里用；
 * 同时避免把大图整段塞进数据库。规格要求「单条最多 4 张」由调用方控制。
 */
object WhaleImageCodec {

    private const val TAG = "WhaleImage"
    const val MAX_LONG_SIDE = 1400
    const val JPEG_QUALITY = 86

    /** 从相册 Uri 导入：解码 → 降采样 → 压缩 → 落盘。任何一步失败返回 null（UI 提示，不崩溃）。 */
    suspend fun import(context: Context, uri: Uri): ImportedImage? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_LONG_SIDE)
            }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null

            val scaled = scaleToLongSide(bitmap, MAX_LONG_SIDE)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            val bytes = out.toByteArray()

            val dir = File(context.filesDir, "whale/uploads").apply { mkdirs() }
            val file = File(dir, "img-${UUID.randomUUID()}.jpg")
            file.writeBytes(bytes)
            ImportedImage(
                path = file.absolutePath,
                base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }.onFailure { Log.w(TAG, "导入图片失败: ${it.message}") }.getOrNull()
    }

    /** 已落盘的图片重新编码（历史消息重放时用，避免把大图整读进内存）。 */
    suspend fun encodeFile(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (!file.isFile) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_LONG_SIDE)
            }
            val bitmap = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
            val scaled = scaleToLongSide(bitmap, MAX_LONG_SIDE)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.onFailure { Log.w(TAG, "编码历史图片失败: ${it.message}") }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (maxOf(w, h) / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToLongSide(bitmap: Bitmap, target: Int): Bitmap {
        val longSide = maxOf(bitmap.width, bitmap.height)
        if (longSide <= target) return bitmap
        val ratio = target.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
