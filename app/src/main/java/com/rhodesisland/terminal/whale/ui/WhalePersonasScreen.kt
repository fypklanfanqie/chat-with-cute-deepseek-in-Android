package com.rhodesisland.terminal.whale.ui

import android.app.Application
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.rhodesisland.terminal.RhodesApp
import com.rhodesisland.terminal.data.model.Character
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.rhodesisland.terminal.ui.theme.WhaleBlue
import com.rhodesisland.terminal.ui.theme.WhaleInk
import com.rhodesisland.terminal.ui.theme.WhaleTextSecondary
import com.rhodesisland.terminal.whale.WhalePersona

/** 人设页 ViewModel：内置鲸鱼娘 + 自定义角色列表 + 当前选择。 */
class WhalePersonaViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as RhodesApp).container
    private val repo = container.whaleSettingsRepository

    val characters: StateFlow<List<Character>> = container.characterRepository.characters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selected = MutableStateFlow("")
    val selected: StateFlow<String> = _selected.asStateFlow()

    init {
        viewModelScope.launch { repo.flow.collect { _selected.value = it.personaId } }
    }

    fun select(id: String) {
        viewModelScope.launch { repo.setPersonaId(id) }
    }

    fun customCharacters(all: List<Character>): List<Character> = all.filter { it.isCustom }
}

/**
 * 人设页。
 *
 * 内置鲸鱼娘是**唯一不可删除**的角色（与主 App 的 isCustom=false 机制同源）；
 * 自定义人设沿用原「角色」页的数据，只在这里做「选用」，编辑仍在原页面完成——
 * 避免两套编辑器各写一半字段。
 */
@Composable
fun WhalePersonasScreen(
    modifier: Modifier = Modifier,
    bottomBarHeight: Dp = 0.dp,
    onOpenLegacyCharacters: () -> Unit = {},
    vm: WhalePersonaViewModel = viewModel(),
) {
    val characters by vm.characters.collectAsState()
    val selected by vm.selected.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    WhaleBackground(modifier) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 14.dp, end = 14.dp, bottom = bottomBarHeight + 24.dp),
        ) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("人设", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = WhaleInk)
                Text(
                    "内置鲸鱼娘不可删除；自定义人设可与她共用这个界面",
                    fontSize = 11.sp,
                    color = WhaleTextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // ===== 内置鲸鱼娘 Hero =====
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF4D6BFE), Color(0xFF6D8BFF), Color(0xFF9FD8FF))
                        )
                    )
                    .clickable { vm.select("") }
                    .padding(16.dp),
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(
                            // 用头像而不是全身立绘：立绘是白底竖图，裁进 96dp 方块会切掉半张脸、
                            // 还带一块白底，看起来像图挂了。
                            model = "file:///android_asset/whale/character/avatar-256.webp",
                            contentDescription = "鲸鱼娘头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(18.dp)),
                        )
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    WhalePersona.NAME,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                )
                                if (selected.isBlank()) {
                                    Spacer(Modifier.width(8.dp))
                                    ActiveBadge()
                                }
                            }
                            Text(
                                WhalePersona.ALIAS,
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tag("萌系女仆")
                                Tag("白米饭品鉴官")
                            }
                            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Tag("推理海")
                                Tag("2023.11.02")
                            }
                        }
                    }
                    Text(
                        if (expanded) "收起设定" else "展开完整设定",
                        fontSize = 11.sp,
                        color = Color.White,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                    if (expanded) {
                        Text(
                            WhalePersona.PERSONA_CARD,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.94f),
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .padding(top = 10.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Black.copy(alpha = 0.16f))
                                .padding(12.dp),
                        )
                    }
                }
            }

            // ===== 自定义人设 =====
            WhaleSectionTitle(
                "自定义人设（${vm.customCharacters(characters).size}）",
                modifier = Modifier.padding(top = 18.dp),
                trailing = {
                    Text(
                        "去新建 / 编辑",
                        fontSize = 11.sp,
                        color = WhaleBlue,
                        modifier = Modifier.clickable(onClick = onOpenLegacyCharacters),
                    )
                },
            )

            val customs = vm.customCharacters(characters)
            if (customs.isEmpty()) {
                WhaleCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Column {
                        Text("还没有自定义人设", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = WhaleInk)
                        Text(
                            "在原「角色」页新建一个人设，这里就能直接选用；选用后对话页的头像、名字、System Prompt 会整套切换。",
                            fontSize = 11.sp,
                            color = WhaleTextSecondary,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    customs.forEach { c ->
                        WhaleCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.select(c.id) },
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WhaleAvatar(
                                    size = 44,
                                    model = if (c.image.isBlank()) WHALE_AVATAR_ASSET else java.io.File(c.image),
                                )
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            c.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = WhaleInk,
                                        )
                                        if (selected == c.id) {
                                            Spacer(Modifier.width(8.dp))
                                            ActiveBadge(dark = true)
                                        }
                                    }
                                    Text(
                                        c.role.ifBlank { "自定义人设" },
                                        fontSize = 11.sp,
                                        color = WhaleTextSecondary,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                                if (selected == c.id) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "使用中",
                                        tint = WhaleBlue,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "提示：切换人设只影响之后的新对话，历史消息不会重写。",
                fontSize = 10.sp,
                color = WhaleTextSecondary,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ActiveBadge(dark: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (dark) WhaleBlue.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.28f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            "使用中",
            fontSize = 9.sp,
            color = if (dark) WhaleBlue else Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Tag(text: String) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, fontSize = 9.sp, color = Color.White)
    }
}
