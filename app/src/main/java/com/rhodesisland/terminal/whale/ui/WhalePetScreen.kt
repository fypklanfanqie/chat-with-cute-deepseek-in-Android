package com.rhodesisland.terminal.whale.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rhodesisland.terminal.ui.theme.WhaleBlue
import com.rhodesisland.terminal.ui.theme.WhaleBgTop
import com.rhodesisland.terminal.ui.theme.WhaleInk
import com.rhodesisland.terminal.ui.theme.WhaleSuccess
import com.rhodesisland.terminal.ui.theme.WhaleTextSecondary
import com.rhodesisland.terminal.whale.pet.WhalePetRules
import com.rhodesisland.terminal.whale.pet.WhalePetViewModel
import kotlin.math.sin

/**
 * 小鲸鱼养成页：水族箱舞台 + 状态卡 + 五个动作。
 *
 * 动画取自 `assets/whale/pet/` 下的 webp（由 webm 转码，VP9 alpha）；
 * API < 28 无 ImageDecoder，Coil 只能解首帧——此时靠 Compose 的浮动/呼吸动画兜底，
 * 观感仍是「活的」，不会出现白块或黑屏。
 */
@Composable
fun WhalePetScreen(
    modifier: Modifier = Modifier,
    bottomBarHeight: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    vm: WhalePetViewModel = viewModel(),
) {
    val pet by vm.pet.collectAsState()
    val animation by vm.animation.collectAsState()
    val line by vm.line.collectAsState()
    val busy by vm.busy.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    val logs = remember(pet.actionsJson) { vm.actionsLog() }

    WhaleBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 14.dp, bottom = bottomBarHeight + 16.dp),
        ) {
            // ===== 顶栏 =====
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    Text(
                        "返回",
                        fontSize = 13.sp,
                        color = WhaleBlue,
                        modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pet.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "改名",
                            tint = WhaleTextSecondary,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(14.dp)
                                .clickable { renaming = true },
                        )
                    }
                    Text(
                        "Lv.${pet.level} · ${WhalePetRules.statusLine(pet)}",
                        fontSize = 11.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                WhalePill("🪙 ${pet.coins.toInt()}")
            }

            // ===== 水族箱舞台 =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFFBFE4FF), Color(0xFF6FB2F0), Color(0xFF2F6FBF))
                        )
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.75f), RoundedCornerShape(26.dp)),
            ) {
                AquariumDecor()

                // 桌宠本体：点击 = 摸摸头
                PetStage(
                    animation = animation,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 26.dp)
                        .clickable(enabled = !busy) { vm.pat() },
                )

                // 头顶气泡（不用 AnimatedVisibility：BoxScope 内会与 ColumnScope 的同名重载冲突）
                line?.let { text ->
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp, start = 16.dp, end = 16.dp)
                    ) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.94f))
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text(
                                text,
                                fontSize = 12.sp,
                                color = WhaleInk,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp,
                            )
                        }
                    }
                }

                Text(
                    "点头像可以摸摸她",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                )
            }

            // ===== 状态卡 =====
            WhaleCard(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("等级 Lv.${pet.level}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WhaleInk)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${pet.exp} / ${WhalePetRules.expToNext(pet.level)}",
                            fontSize = 11.sp,
                            color = WhaleTextSecondary,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { WhalePetRules.levelProgress(pet) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(8.dp)
                            .clip(RoundedCornerShape(999.dp)),
                        color = WhaleBlue,
                        trackColor = WhaleBlue.copy(alpha = 0.12f),
                    )
                    Spacer(Modifier.height(14.dp))
                    StatBar("饱食度", pet.hunger, Color(0xFFFFB347))
                    StatBar("心情", pet.mood, Color(0xFF56C8E8))
                    StatBar("好感度", pet.affinity, Color(0xFFFF7DA6))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MiniStat("聊天", "${pet.totalChats}")
                        MiniStat("米饭", "${pet.totalRice}")
                        MiniStat("Token", "${pet.totalTokens}")
                    }
                }
            }

            // ===== 动作按钮 =====
            WineSection()
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                WhalePetRules.Action.entries.forEach { action ->
                    ActionButton(
                        emoji = action.emoji,
                        label = action.label,
                        enabled = !busy,
                        onClick = { vm.perform(action) },
                    )
                }
            }

            // ===== 成长记录 =====
            WhaleSectionTitle("成长记录", modifier = Modifier.padding(top = 16.dp))
            WhaleCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                Column {
                    if (logs.isEmpty()) {
                        Text(
                            "还没有记录。喂她吃碗白米饭试试？",
                            fontSize = 12.sp,
                            color = WhaleTextSecondary,
                        )
                    } else {
                        logs.take(8).forEachIndexed { index, log ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            Row {
                                Text(
                                    WhalePetRules.Action.entries.firstOrNull { it.key == log.key }?.emoji ?: "·",
                                    fontSize = 12.sp,
                                )
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(log.text, fontSize = 12.sp, color = WhaleInk, lineHeight = 17.sp)
                                    Text(
                                        formatTime(log.at),
                                        fontSize = 9.sp,
                                        color = WhaleTextSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        RenameDialog(
            initial = pet.name,
            onDismiss = { renaming = false },
            onConfirm = {
                vm.rename(it)
                renaming = false
            },
        )
    }
}

/** 分隔用的小标题（保持与聊天页一致的分区节奏）。 */
@Composable
private fun WineSection() {
    WhaleSectionTitle("今天想做点什么", modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun StatBar(label: String, value: Float, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 11.sp, color = WhaleTextSecondary, modifier = Modifier.width(48.dp))
        LinearProgressIndicator(
            progress = { (value / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .weight(1f)
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = color.copy(alpha = 0.16f),
        )
        Text(
            "${value.toInt()}",
            fontSize = 10.sp,
            color = WhaleTextSecondary,
            modifier = Modifier.width(30.dp).padding(start = 8.dp),
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
        Text(label, fontSize = 9.sp, color = WhaleTextSecondary)
    }
}

@Composable
private fun ActionButton(emoji: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (enabled) 0.9f else 0.5f))
                .border(1.dp, Color.White, CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
                .alpha(if (enabled) 1f else 0.55f),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 24.sp)
        }
        Text(
            label,
            fontSize = 9.sp,
            color = WhaleTextSecondary,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/** 桌宠：动态 WebP + 呼吸/浮动兜底（低版本解不出动画时仍然"活着"）。 */
@Composable
private fun PetStage(animation: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pet")
    val breath by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val float by transition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse),
        label = "float",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        AsyncImage(
            model = "file:///android_asset/whale/pet/$animation.webp",
            contentDescription = "小鲸鱼",
            modifier = Modifier
                .height(230.dp)
                .width(300.dp)
                .graphicsLayer(scaleX = breath, scaleY = breath, translationY = float),
        )
    }
}

/** 水族箱装饰：沙地、水草、上升气泡。纯 Canvas，无图片依赖。 */
@Composable
private fun AquariumDecor() {
    val transition = rememberInfiniteTransition(label = "aquarium")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "bubbles",
    )
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // 底部沙地
        val sand = Path().apply {
            moveTo(0f, h * 0.86f)
            cubicTo(w * 0.25f, h * 0.80f, w * 0.55f, h * 0.92f, w, h * 0.84f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(sand, Color(0xFFF3E2B8).copy(alpha = 0.85f))

        // 水草（三丛，随时间轻摆）
        val sway = sin(phase * 2f * Math.PI.toFloat()) * 8f
        listOf(0.10f, 0.20f, 0.88f).forEachIndexed { i, fx ->
            val baseX = w * fx
            val topY = h * (0.94f - 0.20f - i * 0.03f)
            val plant = Path().apply {
                moveTo(baseX, h * 0.95f)
                cubicTo(
                    baseX + sway, h * 0.86f,
                    baseX - sway, h * 0.78f,
                    baseX + sway * 0.4f, topY,
                )
            }
            drawPath(plant, Color(0xFF3FA36B).copy(alpha = 0.75f), style = Stroke(width = 6f))
        }

        // 上升气泡
        repeat(14) { i ->
            val seed = (i * 37 % 100) / 100f
            val speed = 0.55f + seed * 0.6f
            val p = ((phase * speed + seed) % 1f)
            val cx = w * (0.06f + seed * 0.88f) + sin((p + seed) * 6.28f) * 6f
            val cy = h * (0.92f - p * 0.78f)
            val r = 2.5f + seed * 4.5f
            drawCircle(Color.White.copy(alpha = 0.42f * (1f - p)), radius = r, center = androidx.compose.ui.geometry.Offset(cx, cy))
        }

        // 顶部光晕
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                startY = 0f,
                endY = h * 0.35f,
            )
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        WhaleCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("给她起个名字", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(12) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(WhaleBlue.copy(alpha = 0.06f))
                        .padding(12.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = WhaleInk),
                    singleLine = true,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(WhaleBlue),
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    Text(
                        "取消",
                        fontSize = 13.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    )
                    Text(
                        "确定",
                        fontSize = 13.sp,
                        color = WhaleBlue,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { onConfirm(text) }
                            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.CHINA).format(java.util.Date(ts))
