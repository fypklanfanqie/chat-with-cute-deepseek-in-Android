# 鲸鱼娘 · DeepSeek 陪伴终端（Android）

把 DeepSeek 的「鲸鱼娘（大肥鱼）」做成手机陪伴 App 的开源实现：聊天、长期记忆、养成、语音、表情包，所有数据只留在本机。

> 本项目由原「罗德岛终端」安卓工程改造而来：384 位干员与角色卡片流已移除，只保留鲸鱼娘一位角色。改造前的说明见 [README_ARKNIGHTS.md](README_ARKNIGHTS.md)。

## 功能

- **对话**：SSE 流式输出、停止生成、深度思考开关、多会话（新建 / 切换历史）、图片多模态输入、消息复制 / 朗读 / 保留记忆 / 删除。
- **长期记忆**：点「保留记忆」把一条消息存进记忆库；发消息时按相关度召回并注入 System Prompt。设计参考 [agentmemory](https://github.com/rohitg00/agentmemory) 的四层巩固与遗忘曲线，落地为纯本地方案（中文 2-gram BM25 + Ebbinghaus 衰减 + 重要性/访问强化），不依赖向量库。详见 [docs/WHALE_MEMORY_DESIGN.md](docs/WHALE_MEMORY_DESIGN.md)。
- **小鲸鱼养成**：等级 / 经验 / 好感度 / 饱食度 / 心情 / 鲸鱼币，五个动作（喂白米饭、投喂 Token、摸摸头、陪玩、哄睡），18 个透明底动画，时间衰减按小时惰性结算。
- **表情包**：24 张内置表情，模型用 `[[sticker:标签]]` 夹带；表情消息只显示图片、不加气泡。
- **语音**：系统 TTS（离线，可调语速 / 音调）与火山云端 TTS 双引擎，失败自动回退；语音输入走系统 `SpeechRecognizer`，识别结果只填进输入框、不自动发送。
- **人设**：内置鲸鱼娘不可删除；可选用自建人设，切换后对话页的头像、名字、System Prompt 整套跟随。
- **皮肤**：蓝白海洋 / PRTS 深色，整 App 一键切换。
- **隐私**：API Key 用 `EncryptedSharedPreferences`（Android Keystore）加密保存，界面只显示脱敏串；无统计、无第三方上报。

## 构建

要求：JDK 17、Android SDK 35。

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 工程目录含中文时，Kotlin 编译守护进程会把路径转义成 `\uXXXX`，报「source file or directory not found」。
> 本项目在 `gradle.properties` 里改用进程内编译（`kotlin.compiler.execution.strategy=in-process`）规避。

首次运行到「设置 → 模型与 Key」填入 DeepSeek API Key（[platform.deepseek.com](https://platform.deepseek.com)）。

默认请求参数：

```json
{
  "model": "deepseek-flash",
  "temperature": 0.9,
  "max_tokens": 2048,
  "reasoning_effort": "none"
}
```

`reasoning_effort` 必须显式传 `"none"`：该系列默认走思考模式，只输出 `reasoning_content`，正文会是空的。打开「深度思考」后改为 `"high"`，思考内容折叠展示。

## 目录结构

```
app/src/main/java/com/rhodesisland/terminal/
├── whale/                  # 鲸鱼娘模块（独立于原角色体系）
│   ├── chat/               # 对话 ViewModel、图片编解码
│   ├── memory/             # 记忆仓库 + 打分器（纯函数，可单测）
│   ├── pet/                # 养成规则 + 仓库
│   ├── sticker/            # 表情包清单与解析
│   ├── voice/              # TTS / STT 封装
│   ├── settings/           # 独立 DataStore + 加密 Key 存储
│   └── ui/                 # 对话 / 小鲸鱼 / 记忆 / 人设 / 设置 五页
├── data/                   # Room（v18：新增 whale_memory / whale_pet）、DataStore、网络
└── ui/                     # 原工程的导航、玻璃组件、主题
```

## 数据与隐私

全部本地：Room（`rhodes_chat.db`）+ DataStore（`whale_settings`）+ 私有目录 `files/whale/`（图片、自定义表情）。卸载即清除，不做云端同步。

对话会发送到你自行配置的模型服务商（默认 DeepSeek 官方），除此之外不向任何第三方上传数据。

## 致谢

- 长期记忆的设计参考 [rohitg00/agentmemory](https://github.com/rohitg00/agentmemory)（Apache-2.0）。
- 角色「鲸鱼娘」相关形象版权归 DeepSeek 及原画作者所有，本项目仅作个人学习与非商业用途。

## 许可

见 [LICENSE](LICENSE)。
