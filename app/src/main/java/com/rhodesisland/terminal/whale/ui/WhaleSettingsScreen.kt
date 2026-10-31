package com.rhodesisland.terminal.whale.ui

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rhodesisland.terminal.ui.theme.WhaleBlue
import com.rhodesisland.terminal.ui.theme.WhaleError
import com.rhodesisland.terminal.ui.theme.WhaleInk
import com.rhodesisland.terminal.ui.theme.WhaleSuccess
import com.rhodesisland.terminal.ui.theme.WhaleTextSecondary
import com.rhodesisland.terminal.whale.settings.WhaleSettings
import com.rhodesisland.terminal.whale.settings.WhaleSettingsViewModel

/**
 * 鲸鱼娘设置页。
 *
 * 分组：外观 / 模型与 Key / 语音 / 数据。
 * Key 只显示脱敏串，输入框留空即「不改动」——避免进页面就被空值覆盖。
 */
@Composable
fun WhaleSettingsScreen(
    modifier: Modifier = Modifier,
    bottomBarHeight: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    onOpenLegacySettings: () -> Unit = {},
    vm: WhaleSettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val s by vm.settings.collectAsState()
    val masked by vm.maskedKey.collectAsState()
    val notice by vm.notice.collectAsState()

    var keyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var userName by remember(s.userName) { mutableStateOf(s.userName) }
    var baseUrl by remember(s.baseUrl) { mutableStateOf(s.baseUrl) }
    var model by remember(s.model) { mutableStateOf(s.model) }

    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeNotice()
        }
    }

    WhaleBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 14.dp, bottom = bottomBarHeight + 20.dp),
        ) {
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
                Column {
                    Text("设置", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                    Text(
                        "所有数据只留在本机",
                        fontSize = 11.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            // ===== 外观 =====
            WhaleSectionTitle("外观")
            WhaleCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("皮肤", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = WhaleInk)
                    Text(
                        "整 App 切换：蓝白海洋是鲸鱼娘的样子，PRTS 深色是原来的终端。",
                        fontSize = 11.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkinChip(
                            label = "蓝白海洋",
                            selected = s.skin == WhaleSettings.SKIN_WHALE,
                            modifier = Modifier.weight(1f),
                        ) { vm.setSkin(WhaleSettings.SKIN_WHALE) }
                        SkinChip(
                            label = "PRTS 深色",
                            selected = s.skin == WhaleSettings.SKIN_PRTS,
                            modifier = Modifier.weight(1f),
                        ) { vm.setSkin(WhaleSettings.SKIN_PRTS) }
                    }
                }
            }

            // ===== 模型与 Key =====
            WhaleSectionTitle("模型与 Key", modifier = Modifier.padding(top = 16.dp))
            WhaleCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("API Key", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = WhaleInk)
                    Text(
                        if (masked.isBlank()) "还没配置，去 platform.deepseek.com 建一个 Key 吧。"
                        else "当前：$masked",
                        fontSize = 11.sp,
                        color = if (masked.isBlank()) WhaleError else WhaleTextSecondary,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    WhaleField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        placeholder = if (masked.isBlank()) "粘贴 sk-... 开头的 Key" else "留空则不修改",
                        singleLine = true,
                        obscured = !keyVisible,
                        trailing = {
                            Text(
                                if (keyVisible) "隐藏" else "显示",
                                fontSize = 11.sp,
                                color = WhaleBlue,
                                modifier = Modifier.clickable { keyVisible = !keyVisible }.padding(start = 8.dp),
                            )
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(WhaleBlue)
                                .clickable {
                                    if (keyInput.isNotBlank()) {
                                        vm.saveApiKey(keyInput.trim())
                                        keyInput = ""
                                    } else {
                                        Toast.makeText(context, "先粘贴一个 Key 吧。", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Text("保存 Key", color = Color.White, fontSize = 12.sp)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Base URL", fontSize = 12.sp, color = WhaleTextSecondary)
                    WhaleField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        placeholder = "https://api.deepseek.com",
                        singleLine = true,
                        onCommit = { vm.setBaseUrl(baseUrl) },
                    )

                    Spacer(Modifier.height(10.dp))
                    Text("模型名", fontSize = 12.sp, color = WhaleTextSecondary)
                    WhaleField(
                        value = model,
                        onValueChange = { model = it },
                        placeholder = "deepseek-flash",
                        singleLine = true,
                        onCommit = { vm.setModel(model) },
                    )
                    Text(
                        "深度模式另用 deepseek-v4-pro（设置里切换「深度思考」后自动带 reasoning_effort）。",
                        fontSize = 10.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Spacer(Modifier.height(10.dp))
                    Text("用户称呼（她怎么叫你）", fontSize = 12.sp, color = WhaleTextSecondary)
                    WhaleField(
                        value = userName,
                        onValueChange = { userName = it },
                        placeholder = "留空则叫「饲养员」",
                        singleLine = true,
                        onCommit = { vm.setUserName(userName) },
                    )

                    Spacer(Modifier.height(6.dp))
                    SliderRow(
                        label = "温度",
                        value = s.temperature,
                        range = 0f..1.5f,
                        steps = 14,
                        valueText = String.format("%.2f", s.temperature),
                        onChange = vm::setTemperature,
                    )
                    SliderRow(
                        label = "最大回复长度",
                        value = s.maxTokens.toFloat(),
                        range = 512f..4096f,
                        steps = 13,
                        valueText = "${s.maxTokens}",
                        onChange = { vm.setMaxTokens(it.toInt()) },
                    )
                }
            }

            // ===== 语音 =====
            WhaleSectionTitle("语音", modifier = Modifier.padding(top = 16.dp))
            WhaleCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("朗读引擎", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = WhaleInk)
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        SkinChip(
                            label = "系统语音（离线）",
                            selected = s.voiceEngine == WhaleSettings.VOICE_SYSTEM,
                            modifier = Modifier.weight(1f),
                        ) { vm.setVoiceEngine(WhaleSettings.VOICE_SYSTEM) }
                        SkinChip(
                            label = "火山云端",
                            selected = s.voiceEngine == WhaleSettings.VOICE_CLOUD,
                            modifier = Modifier.weight(1f),
                        ) { vm.setVoiceEngine(WhaleSettings.VOICE_CLOUD) }
                    }
                    Text(
                        "云端音质更好，但要先在「原有设置 → 语音」里配好火山 API Key 与音色；" +
                            "没配会自动回退系统语音，不会没声音。",
                        fontSize = 10.sp,
                        color = WhaleTextSecondary,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    SwitchRow(
                        title = "自动朗读回复",
                        subtitle = "每条回复生成完自动念出来",
                        checked = s.autoRead,
                        onChange = vm::setAutoRead,
                    )
                    Spacer(Modifier.height(6.dp))
                    SliderRow(
                        label = "语速",
                        value = s.voiceRate,
                        range = 0.6f..1.6f,
                        steps = 9,
                        valueText = String.format("%.2f", s.voiceRate),
                        onChange = vm::setVoiceRate,
                    )
                    SliderRow(
                        label = "音调",
                        value = s.voicePitch,
                        range = 0.7f..1.4f,
                        steps = 13,
                        valueText = String.format("%.2f", s.voicePitch),
                        onChange = vm::setVoicePitch,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(WhaleBlue.copy(alpha = 0.10f))
                                .clickable { vm.previewVoice() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("试听", color = WhaleBlue, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (vm.sttAvailable) "语音识别可用" else "系统没有中文语音识别",
                            fontSize = 11.sp,
                            color = if (vm.sttAvailable) WhaleSuccess else WhaleTextSecondary,
                        )
                    }
                }
            }

            // ===== 数据 =====
            WhaleSectionTitle("数据", modifier = Modifier.padding(top = 16.dp))
            WhaleCard(Modifier.fillMaxWidth()) {
                Column {
                    SwitchRow(
                        title = "启用表情包",
                        subtitle = "允许她在回复里夹带 [[sticker:标签]]",
                        checked = s.stickerEnabled,
                        onChange = vm::setStickerEnabled,
                    )
                    SwitchRow(
                        title = "启用长期记忆",
                        subtitle = "关闭后不再把记忆注入对话（数据仍保留）",
                        checked = s.memoryEnabled,
                        onChange = vm::setMemoryEnabled,
                    )
                    ActionRow("清空对话记录", "只删聊天，不动记忆") { vm.clearConversation() }
                    ActionRow("数据目录", vm.dataDirHint()) {
                        Toast.makeText(context, vm.dataDirHint(), Toast.LENGTH_LONG).show()
                    }
                    ActionRow("原有设置（模型 / 世界书 / 音乐）", "进入原 App 的完整设置页") {
                        onOpenLegacySettings()
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "鲸鱼娘 v1.0 · 人设卡与输出规则固化在代码里，改不了（故意的）。",
                        fontSize = 10.sp,
                        color = WhaleTextSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkinChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) WhaleBlue.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.6f))
            .border(1.dp, if (selected) WhaleBlue else Color.White, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) WhaleBlue else WhaleTextSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun WhaleField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    obscured: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onCommit: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(WhaleBlue.copy(alpha = 0.05f))
            .border(1.dp, WhaleBlue.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 12.sp, color = WhaleTextSecondary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 12.sp, color = WhaleInk),
                singleLine = singleLine,
                cursorBrush = SolidColor(WhaleBlue),
                visualTransformation = if (obscured) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
            )
        }
        trailing?.invoke()
    }
    if (onCommit != null) {
        // 失焦提交：用一次点击区域外的简单策略——用户点「保存 Key」或离开页面时生效
        LaunchedEffect(value) {
            kotlinx.coroutines.delay(600)
            onCommit()
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = WhaleTextSecondary)
            Spacer(Modifier.weight(1f))
            Text(valueText, fontSize = 11.sp, color = WhaleBlue)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = WhaleBlue,
                activeTrackColor = WhaleBlue,
                inactiveTrackColor = WhaleBlue.copy(alpha = 0.16f),
            ),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = WhaleInk)
            Text(subtitle, fontSize = 10.sp, color = WhaleTextSecondary, modifier = Modifier.padding(top = 1.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = WhaleBlue,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFC9D6F2),
            ),
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = WhaleInk)
            Text(
                subtitle,
                fontSize = 10.sp,
                color = WhaleTextSecondary,
                modifier = Modifier.padding(top = 1.dp),
                maxLines = 1,
            )
        }
        Text("›", fontSize = 16.sp, color = WhaleTextSecondary)
    }
}
