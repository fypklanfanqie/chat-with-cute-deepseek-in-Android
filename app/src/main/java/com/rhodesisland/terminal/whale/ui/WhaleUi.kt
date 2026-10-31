package com.rhodesisland.terminal.whale.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.rhodesisland.terminal.ui.theme.WhaleBlue
import com.rhodesisland.terminal.ui.theme.WhaleBgBottom
import com.rhodesisland.terminal.ui.theme.WhaleBgTop
import com.rhodesisland.terminal.ui.theme.WhaleInk
import com.rhodesisland.terminal.ui.theme.WhaleTextSecondary

/**
 * 鲸鱼娘模块的视觉基元。
 *
 * 刻意**不复用** `ui/glass` 的 frostedGlass：那套靠采样全局深色背板位图，
 * 在浅色页上会泛灰发浊（详见 Backdrop 的说明）。这里用「高不透明白 + 细白描边 + 蓝调阴影」
 * 自绘一套轻量玻璃，观感干净且不依赖全局背板。
 */

val WhaleShapeLarge: Shape = RoundedCornerShape(22.dp)
val WhaleShapeMedium: Shape = RoundedCornerShape(16.dp)
val WhaleShapeSmall: Shape = RoundedCornerShape(12.dp)

/** 页面底色：浅蓝到更浅的蓝，加两团大范围蓝光斑（不参与重组，静态即可）。 */
@Composable
fun WhaleBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(WhaleBgTop, WhaleBgBottom, Color(0xFFDCEEFF)))),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(WhaleBlue.copy(alpha = 0.10f), Color.Transparent),
                        radius = 900f,
                    )
                )
        )
        content()
    }
}

/** 鲸鱼娘玻璃卡：白色半透明 + 1dp 白描边 + 蓝调弥散阴影。 */
@Composable
fun WhaleCard(
    modifier: Modifier = Modifier,
    shape: Shape = WhaleShapeLarge,
    color: Color = Color.White.copy(alpha = 0.82f),
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .shadow(elevation = 10.dp, shape = shape, ambientColor = WhaleBlue.copy(alpha = 0.20f), spotColor = WhaleBlue.copy(alpha = 0.24f))
            .clip(shape)
            .background(color, shape)
            .border(1.dp, Color.White.copy(alpha = 0.9f), shape)
            .padding(contentPadding),
        content = content,
    )
}

/** 小标签胶囊。 */
@Composable
fun WhalePill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = WhaleBlue.copy(alpha = 0.10f),
    color: Color = WhaleBlue,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** 分区标题。 */
@Composable
fun WhaleSectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = WhaleTextSecondary,
        )
        trailing?.invoke()
    }
}

/** 鲸鱼娘头像（默认用内置立绘；传入自定义人设时用其本地图片）。 */
@Composable
fun WhaleAvatar(
    size: Int = 38,
    modifier: Modifier = Modifier,
    model: Any = WHALE_AVATAR_ASSET,
) {
    AsyncImage(
        model = model,
        contentDescription = "鲸鱼娘",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .border(1.5.dp, Color.White, CircleShape),
    )
}

/** 内置头像资源（asset URI，Coil 直接可读）。 */
const val WHALE_AVATAR_ASSET = "file:///android_asset/whale/character/avatar-256.webp"

/** 强调文字（主蓝）。 */
@Composable
fun WhaleAccentText(text: String, size: Int = 14, weight: FontWeight = FontWeight.SemiBold) {
    Text(text, color = WhaleBlue, fontSize = size.sp, fontWeight = weight)
}

/** 正文。 */
@Composable
fun WhaleBodyText(text: String, size: Int = 14, color: Color = WhaleInk) {
    Text(text, color = color, fontSize = size.sp, lineHeight = (size + 7).sp)
}

/** 空态容器：图标 + 标题 + 说明。 */
@Composable
fun WhaleEmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WhaleAvatar(size = 64)
        Text(
            title,
            modifier = Modifier.padding(top = 14.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = WhaleInk,
        )
        Text(
            subtitle,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
            fontSize = 12.sp,
            color = WhaleTextSecondary,
        )
    }
}

/** 供内部使用的默认内容色（避免各处再写一遍）。 */
@Composable
fun whaleContentColor(): Color = LocalContentColor.current.let { WhaleInk }

/** 主题文字主色快捷方式（供页面引用）。 */
val WhaleOnSurface: Color
    @Composable get() = MaterialTheme.colorScheme.onSurface
