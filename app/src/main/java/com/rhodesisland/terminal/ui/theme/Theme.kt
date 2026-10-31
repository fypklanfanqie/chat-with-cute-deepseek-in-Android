package com.rhodesisland.terminal.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 全局亮/暗主题标记：替代组件内直接调用 [isSystemInDarkTheme]，保证主题决策单一来源。 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * 鲸鱼娘皮肤开关（全局）。
 *
 * 由 MainActivity 从 `whale_settings.skin` 读取后下发：
 *  - true  → 蓝白海洋肤（[WhaleLightColors] + [WhaleGlassTokens] + 蓝白极光背板）
 *  - false → 原来的 PRTS 深色（或 Iris 亮色，取决于 [LocalDarkTheme]）
 *
 * 之所以用独立的 CompositionLocal 而不是塞进 darkTheme：皮肤本质上不止明暗两态
 * （未来还可能加第三套），而既有代码大量用 `LocalDarkTheme.current` 做二选一判断，
 * 不能破坏那套语义。
 */
val LocalWhaleSkin = staticCompositionLocalOf { false }

/**
 * Chat by your side · Iris 玻璃主题。
 *
 * - 跟随系统亮/暗
 * - 挂 Material3 colorScheme（Iris 冰蓝）+ Iris 字体 + Iris 形状
 * - 通过 [LocalGlassTokens] 提供亮/暗玻璃叠层色
 * - 通过 [LocalDarkTheme] 提供统一明暗标记
 * - 同步系统状态栏 / 导航栏图标明暗
 */
@Composable
fun ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val whale = LocalWhaleSkin.current
    val colorScheme = when {
        whale && !darkTheme -> WhaleLightColors
        darkTheme -> IrisDarkColors
        else -> IrisLightColors
    }
    val glassTokens = when {
        whale && !darkTheme -> WhaleGlassTokens
        darkTheme -> DarkGlassTokens
        else -> LightGlassTokens
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // 安全取 Activity + 整段 runCatching：个别 ROM/主题包装使 Compose 视图 context
            // 非 Activity（ContextThemeWrapper 等），首帧强转曾直接 ClassCastException。
            // 失败仅放弃本次系统栏图标同步，绝不影响首帧渲染。
            runCatching {
                val activity = view.context as? Activity ?: return@SideEffect
                val window = activity.window
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(
        LocalGlassTokens provides glassTokens,
        LocalDarkTheme provides darkTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = IrisTypography,
            shapes = IrisShapes,
            content = content,
        )
    }
}
