package com.rhodesisland.terminal.whale.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rhodesisland.terminal.ui.theme.WhaleBgTop
import com.rhodesisland.terminal.ui.theme.WhaleBlue
import com.rhodesisland.terminal.ui.theme.WhaleBlueLight
import com.rhodesisland.terminal.ui.theme.WhaleError
import com.rhodesisland.terminal.ui.theme.WhaleInk
import com.rhodesisland.terminal.ui.theme.WhaleText
import com.rhodesisland.terminal.ui.theme.WhaleTextSecondary
import com.rhodesisland.terminal.whale.WhalePersona
import com.rhodesisland.terminal.whale.chat.WhaleBubble
import com.rhodesisland.terminal.whale.chat.WhaleChatViewModel
import com.rhodesisland.terminal.whale.pet.WhalePetRules
import com.rhodesisland.terminal.whale.sticker.StickerItem
import kotlinx.coroutines.launch
import java.io.File

/**
 * 鲸鱼娘对话页（首屏）。
 *
 * 交互对齐规格：
 * - 顶部：头像 + 名字 + 状态 + 深度思考开关 + 更多；
 * - 中部：消息流（用户右蓝底白字 / 助手左白卡），空会话给 4 个推荐问题；
 * - 底部：图片 / 表情 / 语音 / 发送（流式中变停止），可展开表情面板。
 */
@Composable
fun WhaleChatScreen(
    modifier: Modifier = Modifier,
    bottomBarHeight: Dp = 0.dp,
    onOpenPet: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    vm: WhaleChatViewModel = viewModel(),
) {
    val context = LocalContext.current
    val bubbles by vm.bubbles.collectAsState()
    val input by vm.input.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val pendingImages by vm.pendingImages.collectAsState()
    val settings by vm.settings.collectAsState()
    val pet by vm.pet.collectAsState()
    val notice by vm.notice.collectAsState()
    val speakingKey by vm.speakingKey.collectAsState()
    val voiceState by vm.voice.state.collectAsState()
    val persona by vm.persona.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val currentConvId by vm.currentConversationId.collectAsState()
    var showHistory by remember { mutableStateOf(false) }

    var showStickers by remember { mutableStateOf(false) }
    var showAllStickers by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris ->
        if (uris.isNotEmpty()) vm.importImages(uris)
    }

    // 语音权限：首次点麦克风时申请，拒绝后明确告知（不静默失败）。
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startVoiceInput()
        } else {
            Toast.makeText(context, "没有麦克风权限，去系统设置里开一下？", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(notice) {
        notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.consumeNotice()
        }
    }

    LaunchedEffect(voiceState.error) {
        voiceState.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearVoiceError()
        }
    }

    LaunchedEffect(bubbles.size, streaming) {
        if (bubbles.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(bubbles.lastIndex) }
        }
    }

    // 系统栏图标：鲸鱼娘皮肤是浅色底 → 深色图标；PRTS 深色皮肤 → 白色图标。
    com.rhodesisland.terminal.ui.applySystemBarIcons(
        light = !com.rhodesisland.terminal.ui.theme.LocalWhaleSkin.current,
    )

    WhaleBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            WhaleChatTopBar(
                name = persona.name,
                tagline = persona.tagline,
                avatarModel = persona.avatarModel,
                petLine = WhalePetRules.statusLine(pet),
                level = pet.level,
                deepThinking = settings.deepThinking,
                streaming = streaming,
                onToggleThinking = vm::toggleDeepThinking,
                onOpenPet = onOpenPet,
                onOpenMemory = onOpenMemory,
                onOpenSettings = onOpenSettings,
                onNewConversation = vm::newConversation,
                onOpenHistory = {
                    vm.refreshConversations()
                    showHistory = true
                },
                onStopSpeaking = vm::stopSpeaking,
                speaking = speakingKey != null,
            )

            if (bubbles.isEmpty()) {
                WhaleWelcome(
                    modifier = Modifier.weight(1f),
                    personaName = persona.name,
                    personaAvatar = persona.avatarModel,
                    isBuiltin = persona.id == WhalePersona.ID,
                    onPick = { vm.send(it) },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    state = listState,
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(bubbles, key = { it.key }) { bubble ->
                        WhaleMessageRow(
                            bubble = bubble,
                            speaking = speakingKey == bubble.key,
                            onCopy = { vm.copyText(bubble.display.ifBlank { bubble.raw }) },
                            onSpeak = { vm.speakBubble(bubble) },
                            onKeep = { vm.keepMemory(bubble) },
                            onDelete = { vm.deleteMessage(bubble) },
                        )
                    }
                }
            }

            // ===== 待发送图片 =====
            AnimatedVisibility(visible = pendingImages.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pendingImages, key = { it.path }) { img ->
                        Box(Modifier.size(64.dp)) {
                            AsyncImage(
                                model = File(img.path),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x99000000))
                                    .clickable { vm.removeImage(img.path) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }

            // ===== 语音状态 =====
            AnimatedVisibility(
                visible = voiceState.listening || voiceState.partial.isNotBlank(),
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                VoiceHud(
                    rms = voiceState.rms,
                    partial = voiceState.partial,
                    onCancel = { vm.stopVoiceInput() },
                )
            }

            // ===== 表情面板 =====
            AnimatedVisibility(
                visible = showStickers,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                StickerPanel(
                    items = vm.stickerItems(),
                    onPick = {
                        vm.sendSticker(it)
                        showStickers = false
                    },
                    onClose = { showStickers = false },
                    onImport = { showAllStickers = true },
                )
            }

            // ===== 输入栏 =====
            WhaleInputBar(
                value = input,
                personaName = persona.name,
                streaming = streaming,
                onValueChange = vm::updateInput,
                onSend = { vm.send() },
                onStop = vm::stop,
                onPickImage = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onToggleStickers = { showStickers = !showStickers },
                onMic = {
                    when {
                        voiceState.listening -> vm.stopVoiceInput()
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED -> vm.startVoiceInput()

                        else -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                listening = voiceState.listening,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                    .padding(bottom = bottomBarHeight + 8.dp),
            )
        }
    }

    // 表情面板的「全部表情」二级页（简单弹层，避免为一个面板再加路由）
    if (showAllStickers) {
        StickerLibraryDialog(
            items = vm.stickerItems(),
            onPick = {
                vm.sendSticker(it)
                showAllStickers = false
            },
            onDismiss = { showAllStickers = false },
        )
    }

    if (showHistory) {
        ConversationHistoryDialog(
            conversations = conversations,
            currentId = currentConvId,
            onPick = {
                vm.switchConversation(it)
                showHistory = false
            },
            onNew = {
                vm.newConversation()
                showHistory = false
            },
            onDismiss = { showHistory = false },
        )
    }
}

/** 历史对话切换：列出该角色全部会话，点一下切换；底部可直接新建。 */
@Composable
private fun ConversationHistoryDialog(
    conversations: List<com.rhodesisland.terminal.data.model.Conversation>,
    currentId: Long,
    onPick: (Long) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        WhaleCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("历史对话", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                    Spacer(Modifier.weight(1f))
                    Text("${conversations.size} 段", fontSize = 11.sp, color = WhaleTextSecondary)
                }
                Text(
                    "点一下切换。新对话不会删除旧的。",
                    fontSize = 11.sp,
                    color = WhaleTextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(conversations, key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (c.id == currentId) WhaleBlue.copy(alpha = 0.08f)
                                    else Color.Transparent
                                )
                                .clickable { onPick(c.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.title.ifBlank { "和鲸鱼娘的对话" },
                                    fontSize = 13.sp,
                                    color = WhaleInk,
                                    maxLines = 1,
                                )
                                Text(
                                    java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA)
                                        .format(java.util.Date(c.updatedAt)),
                                    fontSize = 10.sp,
                                    color = WhaleTextSecondary,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (c.id == currentId) WhalePill("当前")
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WhaleBlue)
                        .clickable(onClick = onNew)
                        .padding(vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("新建对话", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun WhaleChatTopBar(
    name: String,
    tagline: String,
    avatarModel: Any,
    petLine: String,
    level: Int,
    deepThinking: Boolean,
    streaming: Boolean,
    onToggleThinking: () -> Unit,
    onOpenPet: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenHistory: () -> Unit,
    onStopSpeaking: () -> Unit,
    speaking: Boolean,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable { onOpenPet() }) { WhaleAvatar(size = 42, model = avatarModel) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                    Spacer(Modifier.width(6.dp))
                    WhalePill("Lv.$level")
                }
                Text(
                    "$tagline · $petLine",
                    fontSize = 11.sp,
                    color = WhaleTextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (speaking) {
                IconBadge(Icons.Default.VolumeUp, "停止朗读", onStopSpeaking, active = true)
                Spacer(Modifier.width(6.dp))
            }
            IconBadge(
                icon = Icons.Default.Psychology,
                label = if (deepThinking) "深度思考已开" else "深度思考",
                onClick = onToggleThinking,
                active = deepThinking,
            )
            Spacer(Modifier.width(6.dp))
            // 新建对话：常驻按钮（用户最容易找不到的功能，做成显式入口）
            IconBadge(Icons.Default.Add, "新建对话", onNewConversation)
            Spacer(Modifier.width(6.dp))
            Box {
                IconBadge(Icons.Default.MoreHoriz, "更多", { menuOpen = true })
                // 用 DropdownMenu 而不是自绘浮层：它天然锚定在按钮上、渲染在独立窗口，
                // 不会被顶栏的小 Box 裁掉。
                androidx.compose.material3.DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    MenuItem("新建对话", { menuOpen = false; onNewConversation() })
                    MenuItem("历史对话", { menuOpen = false; onOpenHistory() })
                    MenuItem("小鲸鱼", { menuOpen = false; onOpenPet() })
                    MenuItem("记忆", { menuOpen = false; onOpenMemory() })
                    MenuItem("设置", { menuOpen = false; onOpenSettings() })
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(text, fontSize = 13.sp, color = WhaleInk) },
        onClick = onClick,
    )
}

@Composable
private fun IconBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (active) WhaleBlue.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) WhaleBlue else WhaleTextSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun WhaleWelcome(
    modifier: Modifier = Modifier,
    personaName: String = WhalePersona.NAME,
    personaAvatar: Any = WHALE_AVATAR_ASSET,
    isBuiltin: Boolean = true,
    onPick: (String) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isBuiltin) {
            AsyncImage(
                model = "file:///android_asset/whale/character/portrait.webp",
                contentDescription = "鲸鱼娘立绘",
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(210.dp).fillMaxWidth(),
            )
        } else {
            // 自定义人设没有全身立绘，用其头像做一张圆角卡，避免出现空白占位
            WhaleAvatar(size = 132, model = personaAvatar)
        }
        Text(
            if (isBuiltin) WhalePersona.greetingByHour() else "$personaName，在吗？",
            modifier = Modifier.padding(top = 10.dp),
            fontSize = 14.sp,
            color = WhaleInk,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WhalePersona.SUGGESTIONS.forEach { s ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(WhaleShapeMedium)
                        .background(Color.White.copy(alpha = 0.72f))
                        .border(1.dp, Color.White, WhaleShapeMedium)
                        .clickable { onPick(s) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    Text(s, fontSize = 13.sp, color = WhaleText)
                }
            }
        }
    }
}

@Composable
private fun WhaleMessageRow(
    bubble: WhaleBubble,
    speaking: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onKeep: () -> Unit,
    onDelete: () -> Unit,
) {
    val isUser = bubble.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!isUser) {
            WhaleAvatar(size = 30)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            // 表情包在气泡上方，独立图片、不加气泡底
            bubble.stickers.forEach { sticker ->
                StickerImage(sticker, size = 132)
            }
            if (bubble.display.isNotBlank()) {
                val shape = if (isUser) {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 6.dp)
                } else {
                    RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
                }
                Box(
                    Modifier
                        .clip(shape)
                        .background(
                            if (isUser) SolidColor(WhaleBlue)
                            else SolidColor(Color.White.copy(alpha = 0.92f))
                        )
                        .then(
                            if (isUser) Modifier
                            else Modifier.border(1.dp, Color.White, shape)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column {
                        if (!isUser && bubble.reasoning.isNotBlank()) {
                            var expanded by remember(bubble.key) { mutableStateOf(false) }
                            Row(
                                Modifier.clickable { expanded = !expanded }.padding(bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (expanded) "收起思考" else "思考过程",
                                    fontSize = 10.sp,
                                    color = WhaleBlue,
                                )
                                Icon(
                                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    null,
                                    tint = WhaleBlue,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                            if (expanded) {
                                Text(
                                    bubble.reasoning.take(1200),
                                    fontSize = 11.sp,
                                    color = WhaleTextSecondary,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                            }
                        }
                        Text(
                            bubble.display,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            color = if (isUser) Color.White else WhaleText,
                        )
                    }
                }
            }
            if (bubble.images.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(bubble.images, key = { it }) { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(110.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Color.White, RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            if (bubble.streaming && bubble.display.isBlank()) {
                TypingDots()
            }
            // 操作条：复制 / 朗读 / 保留记忆 / 删除
            if (!bubble.streaming && (bubble.display.isNotBlank() || bubble.stickers.isNotEmpty())) {
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MiniAction(Icons.Default.ContentCopy, "复制", onCopy)
                    if (!isUser) MiniAction(Icons.Default.VolumeUp, if (speaking) "停止" else "朗读", onSpeak)
                    MiniAction(
                        if (bubble.memoryId != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        if (bubble.memoryId != null) "已保留" else "保留记忆",
                        onKeep,
                        enabled = bubble.memoryId == null,
                    )
                    if (bubble.dbId != null) MiniAction(Icons.Default.Delete, "删除", onDelete)
                }
            }
        }
    }
}

@Composable
private fun MiniAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) WhaleTextSecondary else WhaleBlue,
            modifier = Modifier.size(12.dp),
        )
        Text(
            label,
            fontSize = 10.sp,
            color = if (enabled) WhaleTextSecondary else WhaleBlue,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 160, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(WhaleBlueLight.copy(alpha = alpha))
            )
        }
    }
}

@Composable
fun StickerImage(item: StickerItem, size: Int = 120) {
    AsyncImage(
        model = item.model,
        contentDescription = item.label,
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(size.dp),
    )
}

@Composable
private fun VoiceHud(rms: Float, partial: String, onCancel: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "wave")
    WhaleCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(28.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(24) { i ->
                        val phase by transition.animateFloat(
                            initialValue = 0.2f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(420 + i * 12, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse,
                            ),
                            label = "bar$i",
                        )
                        val h = (6 + 20 * phase * (0.4f + rms)).coerceIn(4f, 26f)
                        Box(
                            Modifier
                                .width(3.dp)
                                .height(h.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(WhaleBlue.copy(alpha = 0.55f))
                        )
                    }
                }
                Text(
                    "取消",
                    fontSize = 12.sp,
                    color = WhaleError,
                    modifier = Modifier.clickable(onClick = onCancel).padding(start = 10.dp),
                )
            }
            Text(
                partial.ifBlank { "我在听…说完会自动填进输入框" },
                fontSize = 12.sp,
                color = WhaleTextSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun StickerPanel(
    items: List<StickerItem>,
    onPick: (StickerItem) -> Unit,
    onClose: () -> Unit,
    onImport: () -> Unit,
) {
    WhaleCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("表情包", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = WhaleText)
                Spacer(Modifier.weight(1f))
                Text("管理", fontSize = 11.sp, color = WhaleBlue, modifier = Modifier.clickable(onClick = onImport))
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Default.Close,
                    null,
                    tint = WhaleTextSecondary,
                    modifier = Modifier.size(16.dp).clickable(onClick = onClose),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.heightIn(max = 210.dp).padding(top = 6.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(
                        Modifier
                            .padding(4.dp)
                            .clickable { onPick(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        StickerImage(item, size = 62)
                    }
                }
            }
        }
    }
}

@Composable
private fun StickerLibraryDialog(
    items: List<StickerItem>,
    onPick: (StickerItem) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        WhaleCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Column {
                Text("全部表情", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                Text(
                    "点一下直接发给鲸鱼娘",
                    fontSize = 11.sp,
                    color = WhaleTextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.heightIn(max = 380.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        Column(
                            Modifier.padding(4.dp).clickable { onPick(item) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            StickerImage(item, size = 66)
                            Text(item.label, fontSize = 9.sp, color = WhaleTextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhaleInputBar(
    value: String,
    personaName: String,
    streaming: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImage: () -> Unit,
    onToggleStickers: () -> Unit,
    onMic: () -> Unit,
    listening: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.86f))
            .border(1.dp, Color.White, RoundedCornerShape(24.dp))
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconBadge(Icons.Default.AddPhotoAlternate, "图片", onPickImage)
        Spacer(Modifier.width(4.dp))
        IconBadge(Icons.Default.Mood, "表情", onToggleStickers)
        Spacer(Modifier.width(4.dp))
        IconBadge(Icons.Default.Mic, "语音", onMic, active = listening)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 34.dp, max = 120.dp)
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = WhaleText),
                cursorBrush = SolidColor(WhaleBlue),
                maxLines = 4,
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text("和$personaName 说点什么…", fontSize = 14.sp, color = WhaleTextSecondary)
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (streaming) WhaleError else WhaleBlue)
                .clickable { if (streaming) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (streaming) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                contentDescription = if (streaming) "停止" else "发送",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
