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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
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
import com.rhodesisland.terminal.ui.theme.WhaleInk
import com.rhodesisland.terminal.ui.theme.WhaleTextSecondary
import com.rhodesisland.terminal.whale.data.WhaleMemoryEntity
import com.rhodesisland.terminal.whale.memory.WhaleMemoryRepository
import com.rhodesisland.terminal.whale.memory.WhaleMemoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆页：搜索 / 卡片列表 / 手动添加 / 编辑 / 删除 / 置顶。
 *
 * 每条卡片左边缘一条蓝色渐变竖线（规格要求），置顶条目额外加一枚图钉。
 */
@Composable
fun WhaleMemoryScreen(
    modifier: Modifier = Modifier,
    bottomBarHeight: Dp = 0.dp,
    onBack: (() -> Unit)? = null,
    vm: WhaleMemoryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    val query by vm.query.collectAsState()
    val notice by vm.notice.collectAsState()

    var editing by remember { mutableStateOf<WhaleMemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<WhaleMemoryEntity?>(null) }

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
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 14.dp),
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
                Column(Modifier.weight(1f)) {
                    Text("她记住的事", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                    Text(
                        "${items.size} 条 · 上限 ${WhaleMemoryRepository.MAX_ACTIVE} 条",
                        fontSize = 11.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(WhaleBlue)
                        .clickable { adding = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Add, "添加记忆", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            // 搜索框
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.82f))
                    .border(1.dp, Color.White, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = WhaleTextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        value = query,
                        onValueChange = vm::updateQuery,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 13.sp, color = WhaleInk),
                        cursorBrush = SolidColor(WhaleBlue),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("搜记忆里的关键词…", fontSize = 13.sp, color = WhaleTextSecondary)
                            }
                            inner()
                        },
                    )
                }
            }

            if (items.isEmpty()) {
                WhaleEmptyState(
                    title = if (query.isBlank()) "她还没记住什么" else "没搜到相关的记忆",
                    subtitle = if (query.isBlank()) "聊天时点「保留记忆」，重要的事就会留在这里。" else "换个关键词试试？",
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = bottomBarHeight + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        MemoryCard(
                            item = item,
                            onPin = { vm.togglePin(item) },
                            onEdit = { editing = item },
                            onDelete = { deleting = item },
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        MemoryEditDialog(
            title = "记一条新的事",
            initial = "",
            onDismiss = { adding = false },
            onConfirm = {
                vm.add(it)
                adding = false
            },
        )
    }
    editing?.let { item ->
        MemoryEditDialog(
            title = "修改这条记忆",
            initial = item.text,
            onDismiss = { editing = null },
            onConfirm = {
                vm.update(item.id, it)
                editing = null
            },
        )
    }
    deleting?.let { item ->
        ConfirmDialog(
            title = "删掉这条记忆？",
            message = item.text.take(60),
            confirmText = "删除",
            onDismiss = { deleting = null },
            onConfirm = {
                vm.delete(item.id)
                deleting = null
            },
        )
    }
}

@Composable
private fun MemoryCard(
    item: WhaleMemoryEntity,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    WhaleCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(0.dp),
    ) {
        Row {
            // 左边缘蓝色渐变竖线
            Box(
                Modifier
                    .width(3.dp)
                    .height(88.dp)
                    .background(Brush.verticalGradient(listOf(WhaleBlue, Color(0xFF9FD8FF))))
            )
            Column(Modifier.weight(1f).padding(14.dp)) {
                Text(
                    item.text,
                    fontSize = 13.sp,
                    color = WhaleInk,
                    lineHeight = 19.sp,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatDate(item.createdAt),
                        fontSize = 10.sp,
                        color = WhaleTextSecondary,
                    )
                    if (item.accessCount > 0) {
                        Text(
                            " · 想起过 ${item.accessCount} 次",
                            fontSize = 10.sp,
                            color = WhaleTextSecondary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SmallIcon(
                        Icons.Default.PushPin,
                        if (item.pinned) "取消置顶" else "置顶",
                        onPin,
                        active = item.pinned,
                    )
                    SmallIcon(Icons.Default.Edit, "编辑", onEdit)
                    SmallIcon(Icons.Default.Delete, "删除", onDelete)
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    Icon(
        icon,
        contentDescription = label,
        tint = if (active) WhaleBlue else WhaleTextSecondary,
        modifier = Modifier
            .padding(start = 10.dp)
            .size(15.dp)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun MemoryEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        WhaleCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                Text(
                    "写短一点更容易被想起来，最多 ${WhaleMemoryRepository.MAX_TEXT} 字。",
                    fontSize = 11.sp,
                    color = WhaleTextSecondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(WhaleBlue.copy(alpha = 0.05f))
                        .border(1.dp, WhaleBlue.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { if (it.length <= WhaleMemoryRepository.MAX_TEXT) text = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = TextStyle(fontSize = 13.sp, color = WhaleInk, lineHeight = 19.sp),
                        cursorBrush = SolidColor(WhaleBlue),
                        decorationBox = { inner ->
                            if (text.isEmpty()) {
                                Text("例如：我叫丁锦程，在内蒙古工业大学读智能制造。", fontSize = 13.sp, color = WhaleTextSecondary)
                            }
                            inner()
                        },
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    Text(
                        "取消",
                        fontSize = 13.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    )
                    Text(
                        "保存",
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

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        WhaleCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                Text(
                    message,
                    fontSize = 12.sp,
                    color = WhaleTextSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                    lineHeight = 18.sp,
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                    Text(
                        "取消",
                        fontSize = 13.sp,
                        color = WhaleTextSecondary,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    )
                    Text(
                        confirmText,
                        fontSize = 13.sp,
                        color = Color(0xFFD64545),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable(onClick = onConfirm)
                            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
            }
        }
    }
}

private fun formatDate(ts: Long): String =
    SimpleDateFormat("M 月 d 日 HH:mm", Locale.CHINA).format(Date(ts))
