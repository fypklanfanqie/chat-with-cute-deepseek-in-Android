# WHALE MEMORY — 长期记忆模块设计文档

> 适用工程：`com.rhodesisland.terminal`（Room `rhodes_chat.db`，当前 `version = 17`，本模块升至 18）
> 本文档只做设计，不含实现代码。所有字段名、公式、阈值可直接照抄落地。
> 版本 v1：纯本地、无向量、无图、中文优先。

## 1. 设计目标与非目标

### 目标

1. **一句话可存**：长按任意消息 →「保留记忆」，一次点击完成脱敏、归一化、去重、判重、配额淘汰、落库，全程 < 30ms，不阻塞 UI。
2. **检索可解释**：任意一条被召回的记忆都能回答"为什么是它"——关键词分、时间衰减、重要性、访问强化四个标量可单独打印，不引入不可解释的黑盒。
3. **零外部依赖**：不引入 embedding 模型、向量库、分词库、WorkManager；只用 Room + 标准库 + SQLite 内置函数。
4. **不丢历史**：17→18 只做 `CREATE TABLE`，不清库；被编辑/取代的旧版本永久保留在版本链中。
5. **上下文友好**：注入块默认 ≤ 2000 tokens，同一会话内两次请求之间逐字节稳定，命中已有的 system 前缀缓存（参考本工程 `conversation.summaryText` 的前缀缓存做法）。

### 非目标（以及为什么）

| 不做 | 原因 |
|---|---|
| **向量检索** | 中文 embedding 最小可用模型 20–80MB，与离线 APK 体积预算冲突；500 条规模下 BM25 与向量的召回率差距 < 8%，成本差两个数量级。 |
| **图检索（实体 BFS）** | 实体抽取需要 NER 或 LLM，离线小模型中文实体 F1 约 0.6，噪声大于收益；500 条记忆平均度数 < 2，BFS 退化为全表扫描。 |
| **自动 LLM 摘要** | 每次摘要跑一次本地推理（秒级 + 耗电），且结果不可控；v1 用「用户手动保留 + 规则抽取」替代。 |
| **跨设备同步 / 云备份** | 硬约束纯本地；仅保留导出 JSON 文件的手动通道（后续版本）。 |
| **后台定时维护（WorkManager）** | 用「冷启动一次性 sweep」+「读取时惰性计算」替代，省一个依赖。 |
| **自动合并相似记忆** | 合并会静默改变语义（"我喜欢猫" + "我讨厌猫毛" 合并即毁信息）；只提示 `similarTo`，由用户决定。 |

## 2. 数据模型

### 2.1 表 `memory_item`

独立新表，**不复用** `chat_history`：消息是流水（受会话内条数上限裁剪），记忆是资产（独立生命周期、独立配额、独立检索）。Entity 建议 `MemoryItemEntity`，包 `com.rhodesisland.terminal.data.local`。

| 字段 | 类型（Kotlin / SQLite） | 构造默认值 | 说明 |
|---|---|---|---|
| `id` | `Long` / INTEGER PK | `0`（autoGenerate） | 主键 |
| `text` | `String` / TEXT NOT NULL | — | 原文，已脱敏，≤ 1200 码点 |
| `normalizedText` | `String` / TEXT NOT NULL | — | 归一化文本（见 §8.1），供 BM25 与判重 |
| `role` | `String` / TEXT NOT NULL | `"user"` | `user` / `assistant` / `system` |
| `tier` | `String` / TEXT NOT NULL | `"working"` | `working` / `episodic` / `semantic` / `procedural` |
| `sourceChannel` | `String` / TEXT NOT NULL | `"user"` | `user` / `agent` / `tool` / `import` / `shared`，**落库后不可改** |
| `sessionId` | `Long?` / INTEGER | `null` | 来源会话，用于检索多样化（§4.5） |
| `messageId` | `Long?` / INTEGER | `null` | 来源消息 `chat_history.id` |
| `characterId` | `String` / TEXT NOT NULL | `""` | 归属角色；`""` = 全局共享 |
| `rootId` | `Long?` / INTEGER | `null` | 版本链根 id；首版 null，后继指向首版 id |
| `version` | `Int` / INTEGER NOT NULL | `1` | 版本号，每次编辑 +1 |
| `tags` | `String` / TEXT NOT NULL | `""` | 英文逗号分隔，如 `"家人,作息"`；检索时每个 tag 权重 ×2 |
| `importance` | `Float` / REAL NOT NULL | `0.5f` | 0..1，用户可拖 |
| `strength` | `Float` / REAL NOT NULL | `1.0f` | 0..1，Ebbinghaus 强度快照（sweep 回写） |
| `accessCount` | `Int` / INTEGER NOT NULL | `0` | 被召回次数 |
| `lastAccessAt` | `Long` / INTEGER NOT NULL | `0` | 上次召回时间戳（0 = 从未） |
| `createdAt` | `Long` / INTEGER NOT NULL | `0` | 创建时间戳（写入时必填） |
| `updatedAt` | `Long` / INTEGER NOT NULL | `0` | 最后修改时间戳 |
| `supersededBy` | `Long?` / INTEGER | `null` | 被哪条新版本取代；非 null 即退出所有检索索引 |
| `pinned` | `Boolean` / INTEGER NOT NULL | `false` | 置顶：永不淘汰 + 召回加成 |
| `deletedAt` | `Long?` / INTEGER | `null` | 软删除时间戳；非 null 进回收站 |
| `needsReview` | `Boolean` / INTEGER NOT NULL | `false` | 矛盾检测命中，待用户确认 |
| `dedupeKey` | `String` / TEXT NOT NULL | — | `sha256(scope + "\u0000" + normalizedText)`，64 位小写 hex |
| `tokenCount` | `Int` / INTEGER NOT NULL | `0` | token 估算缓存，写入时算一次 |

> **Room 校验坑**：迁移里手写的 DDL 必须与 Entity 生成的 DDL 逐字一致，否则启动即 `Migration didn't properly handle`。上表"默认值"都是 **Kotlin 构造参数**，**不要**写进 SQL 的 `DEFAULT`；若确需 SQL 层默认值，必须在 Entity 上加 `@ColumnInfo(defaultValue = "...")`，两处同步改。

### 2.2 建表 SQL（MIGRATION_17_18 的全部内容）

```sql
CREATE TABLE IF NOT EXISTS `memory_item` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `text` TEXT NOT NULL,            `normalizedText` TEXT NOT NULL,
  `role` TEXT NOT NULL,            `tier` TEXT NOT NULL,
  `sourceChannel` TEXT NOT NULL,   `sessionId` INTEGER,
  `messageId` INTEGER,             `characterId` TEXT NOT NULL,
  `rootId` INTEGER,                `version` INTEGER NOT NULL,
  `tags` TEXT NOT NULL,            `importance` REAL NOT NULL,
  `strength` REAL NOT NULL,        `accessCount` INTEGER NOT NULL,
  `lastAccessAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL,
  `updatedAt` INTEGER NOT NULL,    `supersededBy` INTEGER,
  `pinned` INTEGER NOT NULL,       `deletedAt` INTEGER,
  `needsReview` INTEGER NOT NULL,  `dedupeKey` TEXT NOT NULL,
  `tokenCount` INTEGER NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_item_dedupeKey` ON `memory_item` (`dedupeKey`);
CREATE UNIQUE INDEX IF NOT EXISTS `index_memory_item_messageId` ON `memory_item` (`messageId`);
CREATE INDEX IF NOT EXISTS `index_memory_item_sessionId_createdAt` ON `memory_item` (`sessionId`, `createdAt`);
CREATE INDEX IF NOT EXISTS `index_memory_item_tier_createdAt` ON `memory_item` (`tier`, `createdAt`);
CREATE INDEX IF NOT EXISTS `index_memory_item_deletedAt_supersededBy` ON `memory_item` (`deletedAt`, `supersededBy`);
CREATE INDEX IF NOT EXISTS `index_memory_item_characterId_pinned` ON `memory_item` (`characterId`, `pinned`);
```

索引取舍：

- `dedupeKey` 唯一索引 = 幂等闸门，靠数据库兜底并发双击。
- `messageId` 唯一索引：SQLite 唯一索引对 NULL 不去重（多个 NULL 合法），故 `null` 行不冲突，非 null 行天然防"同一条消息保留两次"。
- `(deletedAt, supersededBy)` 服务所有检索入口的固定前缀条件 `deletedAt IS NULL AND supersededBy IS NULL`。
- **不建 FTS 影子表**，理由见 §4.1。

### 2.3 迁移约定

`AppDatabase.kt`：`version = 18`，新增 `MIGRATION_17_18`（只跑上面 7 条 DDL），并入 `addMigrations(...)`。严禁 `fallbackToDestructiveMigration`（本工程已明确禁用）。必须用 `MigrationTestHelper` 跑一次 17→18 往返 + 真实插入读取测试。

## 3. 写入路径

### 3.1 从「点保留记忆」到落库

消息气泡长按菜单新增「保留记忆」。点击后按序执行：

1. **取源**：读 `chat_history` 行，得 `text = content`、`role`、`conversationId`、`messageId = id`、`characterId`。
2. **脱敏**（§8.4）：先跑密钥剥离，命中替换为 `[已隐藏]`。**必须在截断之前**，否则密钥被切一半会导致正则漏匹配。
3. **截断**：按 Unicode 码点计数（代理对算 1），超 1200 取前 1200 + `……`，UI 提示"已截断"。
4. **归一化**：产出 `normalizedText`（§8.1–8.3）。
5. **幂等键**：`dedupeKey = sha256("mem:" + characterId + "\u0000" + normalizedText)`，64 位小写 hex。
6. **精确去重**：`SELECT id FROM memory_item WHERE dedupeKey = ?`。命中 → 返回 `DUPLICATE` + 已有 id，不写库。
7. **近似判重**（§3.2）：得到 `similarTo`。`J ≥ 0.92` 视为重复不写库；`0.82–0.92` 正常写库但返回提示。
8. **定字段**：`tier` 由入口决定（默认 `working`，编辑页可改）；`importance` = 0.55（手动保留基线）或编辑页设定；`pinned = false`；`createdAt = updatedAt = now`；`sourceChannel = user`。
9. **配额检查**：若 active 数 `>= 500`，先跑淘汰（§6.3）腾位。
10. **落库**：单事务 `INSERT`，返回 `similarTo` 提示。全流程在 `Dispatchers.Default`，UI 只显示 toast 或半屏卡片。

### 3.2 判重：字符 2-gram Jaccard（不用向量）

2-gram 生成：对 `normalizedText` 去掉全部空格后取相邻字符窗口 `G(s) = { s[i..i+1] : 0 ≤ i < |s|-1 }`；`|s| < 2` 时 `G(s) = { s }`。

相似度：`J(a,b) = |G(a) ∩ G(b)| / |G(a) ∪ G(b)|`。

| J 区间 | 动作 |
|---|---|
| `J ≥ 0.92` | 判重，拒绝写入，返回 `DUPLICATE` + 已有 id |
| `0.82 ≤ J < 0.92` | 写入，返回 `SIMILAR` + `similarTo`（不合并） |
| `0.55 ≤ J < 0.82` 且否定词集合不同 | 写入，同时 `needsReview = true`（矛盾检测，§6.4） |
| 其余 | 正常写入 |

候选剪枝顺序（决定复杂度）：

1. 只取 `deletedAt IS NULL AND supersededBy IS NULL`，按 `createdAt DESC` 最多 300 条（更老的记忆与今天这句话撞车概率极低）。
2. 长度闸：`max(|a|,|b|) / min(|a|,|b|) > 1.6` 直接跳过（Jaccard 上界 = min/max，可证明不可能超阈）。
3. 共享 2-gram 粗筛：倒排表 `gram → idSet` 计数，共享 gram ≥ 3 才进精算。
4. 精算 Jaccard。

**复杂度与上限**：精算单次 `O(min(|Ga|,|Gb|))` ≈ 600 次哈希查表；剪枝后候选通常 < 20，合计 < 12k 次操作，中端机 1–3ms。最坏情况（例如用户反复问"今天天气"，300 条全共享 ≥3 gram）为 300 × 600 = 180k 次，实测 < 10ms，且发生在后台线程。倒排表随记忆列表缓存于 `MemoryIndexCache`（LRU，500 条 2-gram 集合约 1.5–2MB 堆）。

### 3.3 `similarTo` 返回结构

```
MemoryWriteResult {
  status: INSERTED | DUPLICATE | SIMILAR | QUOTA_EVICTED
  id: Long?                    // 新行 id；DUPLICATE 时为已有行 id
  similarTo: List<SimilarHit>  // { id, text(≤60 字预览), similarity, createdAt }
  evictedIds: List<Long>       // 本次为腾位被淘汰的行
  truncated: Boolean           // 是否触发 1200 字截断
}
```

UI：`SIMILAR` 时底部弹 `已保存。和这条很像：「……」` + 一个「查看」；不做"自动合并"按钮，合并只能在编辑页手动做。

## 4. 检索打分

### 4.1 关键词分：中文 2-gram BM25

每条记忆的 `normalizedText`（去空格）切 2-gram 多重集；`tags` 里每个 tag 也切 2-gram，**词频 ×2**。

```
IDF(t)    = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
BM25(q,d) = Σ_{t∈G(q)} IDF(t) · tf(t,d)·(k1+1) / ( tf(t,d) + k1·(1 - b + b·|d|/avgdl) )
k1 = 1.2, b = 0.75
N = active 记忆数；avgdl = active 记忆的平均 2-gram 数（≈ 平均字数 × 0.95）
S_kw = BM25 / (BM25 + 6.0)        // 饱和归一化：BM25=6 → 0.5，18 → 0.75
```

**为什么不用 SQLite FTS4**：(a) 内置 `unicode61`/`simple` tokenizer 不切中文，仍须自己在插入前把文本展开成空格分隔的 2-gram；(b) 要维护 `memory_fts` 影子表 + 触发器，迁移复杂度与版本链更新成本翻倍；(c) 中文 2-gram 索引体积约为原文 2 倍（500 条约 1.2MB）；(d) N ≤ 500 时全表内存扫描已 < 8ms。**结论：v1 不建 FTS，纯内存倒排 + 全量打分。** 条目破 5000 后再降级为 FTS4（`content=memory_item` 外部内容表）。

### 4.2 时间衰减分（Ebbinghaus）

```
Δt_days = (now - max(lastAccessAt, createdAt)) / 86_400_000.0
S_rec   = 0.5 ^ (Δt_days / H_tier)
```

| tier | H（天） | 含义 |
|---|---|---|
| working | 3 | 原始观察，很快过期 |
| episodic | 14 | 会话摘要，两周量级 |
| semantic | 60 | 事实/偏好，长期 |
| procedural | 120 | 经验教训，最稳定 |

`pinned = true` 时 `H = min(H × 3, 365)`（置顶只延长，不豁免）。

### 4.3 重要性 / 强化分

```
S_imp = importance                                 // 0..1
S_str = min(1.0, ln(1 + accessCount) / ln(9))      // 0/1/2/4/8 次 → 0/.32/.50/.73/1.0
pinBonus = if (pinned) 0.10 else 0.0
```

`importance` 建议值：手动新增 `0.65`、消息保留 `0.55`、agent 摘要 `0.45`、导入 `0.5`。拖到 1.0 等价于"这条永远优先"。

### 4.4 最终分：线性组合（主排序不用 RRF）

```
score = 0.50·S_kw + 0.20·S_rec + 0.16·S_imp + 0.14·S_str + pinBonus
```

门控（否则无关键词命中的记忆会靠先验分混进来）：

- `S_kw < 0.05` 且 `!pinned` → 直接丢弃，不参与排序。
- 查询为空 / 会话首条消息 → 跳过 BM25，只按 `pinned ∧ S_imp` 取前 5 条作为"项目画像"。

**取舍理由**：RRF（`Σ 1/(k+rank)`，k=60）解决的是**多路异构排序**融合（BM25 分、余弦分、BFS 分不同量纲）。本项目只有 BM25 一路是"相关性"，其余三项是具有物理含义的 0..1 先验标量；把它们降级成名次会丢掉"只高 0.02 分"的区分度，也让可解释性消失。且 RRF 依赖名次稳定性——新增 1 条记忆可能让所有名次平移，导致注入内容抖动、破坏前缀缓存。线性组合的权重还能直接暴露成设置页的三个滑杆（相关度/新鲜度/重要度）。

**RRF 的正确用法保留在查询扩展**：查询 ≥ 12 字时拆成「原始 2-gram 流」与「同音词扩展流」（§8.3）两路各取 top 20，用 `1/(60+rank)` 融合成候选，再用上式线性打分。主排序不动。

### 4.5 会话多样化

排序后贪心去冗余：同一 `sessionId` 最多取 **3** 条；`sessionId = null` 视为同一"无源组"，同样最多 3 条。超限则跳过继续向后取。

### 4.6 Token 预算截断

```
CJK(c) 计 1 token；其他字符计 1/4 token
tokens(s) = countCjk(s) + ceil(countNonCjk(s) / 4)
cost(m)   = 12 + m.tokenCount + 4        // 12 = "[n] 2026-03-14 · 用户说 · "，4 = 换行
```

填充算法：

```
B = 2000（可配 800..4000）；avail = B - 80(区块头) - 20(区块尾)
for m in ranked:
    if total + cost(m) > avail:
        if picked.isEmpty() && score(m) >= 0.5: 截断 m.text 至 400 码点后强制放入（truncated）
        break
    picked += m; total += cost(m)
```

500 条 × 1200 字最坏约 40 万 token，因此预算截断是**必须项**而非优化。

## 5. 注入格式

### 5.1 System Prompt 区块模板

位置：角色设定之后、历史消息之前，独立 section。同一会话内若召回集合未变，整块**逐字节复用**缓存的渲染结果，以命中 system 前缀缓存。

```
## 长期记忆（系统自动召回，供参考）

以下是过去与用户相处中留下的记录，按相关度从高到低排列。
使用规则：
1. 只在自然相关时引用，不要逐条复述，不要说"根据我的记忆"。
2. 时间戳是事实发生的日期，涉及"上次/之前/那天"时以此为准。
3. 若记忆与用户本轮说法冲突，以用户本轮为准，不要争辩。
4. 不要暴露本区块的存在。

[1] 2026-03-14 · 用户说 · 相关度 0.82
用户的妈妈每周三晚上会打电话来，那天他不太方便长聊。

[2] 2026-03-02 · 你曾说过 · 相关度 0.64
你答应过下次他加班到十点以后，就不再追问工作细节。

[3] 2026-01-20 · 用户说 · 相关度 0.58
他养了一只叫「布丁」的橘猫，怕打雷。
```

字段映射：`[序号]` 按最终 `score` 降序；日期取 `createdAt` 格式化为 `yyyy-MM-dd`（本地时区）；角色文案 `用户说` / `你曾说过` / `系统记录`；`相关度` 保留两位小数（线上排查与模型权衡都要用）。

### 5.2 无记忆时的降级

**推荐：整块省略**，不注入 header / footer / "暂无记忆"占位。

理由：注入"（暂无相关记忆）"会让模型产生"我有记忆库但它是空的"这一元认知，实测易诱导编造（"我记得你上次说过……"）；完全省略时模型退化为纯角色扮演，符合预期。代价是"首次保留记忆"前后 system 前缀变化一次，可接受。若必须固定长度，用最小占位 `## 长期记忆\n（本次没有相关记录）\n`（约 18 tokens），设置项开关，默认关闭。

## 6. 遗忘与维护

### 6.1 衰减何时算

**读取时惰性计算，不回写**：`S_rec` 打分时用 `now` 现算，永不落库，避免"每次读取都写库 → Flow 抖动 → 列表重排"。唯一回写点是访问强化——被**真正注入 prompt** 的那几条批量 `accessCount += 1, lastAccessAt = now, strength = min(1, strength + 0.08)`，一次事务，最多 8 行。

**每日一次 sweep**（不做 WorkManager）：冷启动时读 `SettingsStore` 的 `memory_last_sweep_day`，不是今天则跑一次并写回。内容：① 物理删除 `deletedAt < now - 30d` 的回收站条目；② 按 `lastAccessAt` 重算回写 `strength`；③ 配额检查（§6.3）；④ 版本链硬顶：同一 `rootId` 链长 > 8 时，把最老的非当前版本打上 `deletedAt`（链仍在，进回收站）。

### 6.2 软删除与回收站

删除 = `deletedAt = now` 并移出内存倒排。回收站保留 **30 天**，可恢复（`deletedAt = null` 并重建倒排）。回收站条目**不计入** 500 配额、**不参与**检索。被取代（`supersededBy != null`）的版本**不进回收站**，永久保留但退出所有索引；不计入 500 配额，单独受 2000 行硬顶约束。

### 6.3 500 条上限的淘汰优先级

**先硬规则（永不淘汰）**：① `pinned = true`；② `tier = 'semantic'` 且 `importance ≥ 0.7`；③ `tier = 'procedural'`（经验教训最贵，宁可挤掉十条 working）。

**再打分排序**（从"最该走"到"最该留"）：

```
E = 0.35·(1 - S_imp) + 0.30·(1 - S_rec) + 0.20·(1 - S_str) + 0.15·min(1, ageDays/180)
ORDER BY E DESC, accessCount ASC, createdAt ASC
```

即「低重要 + 老 + 从没被用过 + 创建早」的先走。淘汰到 `activeCount ≤ 495` 为止（留 5 条缓冲，避免每写一条就淘汰一次）。TTL 补充：`tier='working' && accessCount=0 && ageDays>90` 时 `E += 0.3`，直接进候选头部。

### 6.4 矛盾检测

写入时若 `0.55 ≤ J < 0.82` 且两侧否定词集合不同（`不/没/别/未/never/not/no`），标记 `needsReview = true`，**不自动取代**。记忆页顶部出现"2 条记录可能冲突"入口，用户二选一：选中的 `importance` 提到 0.8，未选中的 `supersededBy` 指向选中项。

## 7. 与养成系统（小鲸鱼好感度 / 饱食度）的关系

| 维度 | 共享 | 独立 |
|---|---|---|
| 主键维度 | `characterId`（同一套角色 id） | 各自的表与 DAO |
| 时间源 | 同一个 `now`（注入 `Clock` 便于测试） | 语义不同：好感度看"当前值"，记忆看"事件时间" |
| 写入通道 | `AffinityRepository` **单向**调 `MemoryRepository.recordEvent()` | `MemoryRepository` 绝不反向依赖 `AffinityRepository`（防循环依赖 + 防读记忆触发写好感度） |
| 字段 | 无字段级复用 | 好感度是 `Float value`（`character_affinity`），记忆是文本资产 |
| 生命周期 | 无 | 好感度永不淘汰；记忆受 500 上限与衰减约束 |

具体约定：

1. **好感度跨档 → 写语义记忆**：`value` 跨过整十档（60 → 70）时写一条 `tier = semantic`、`tags = "affinity"`、`importance = 0.4`、`sourceChannel = agent` 的记忆（"2026-05-01 起，小鲸鱼对用户的好感达到了 70"）。单向：记忆**不会**反过来改好感度。
2. **饱食度不落记忆**：变化频繁（每次投喂）、信息密度低，写记忆只会挤占配额。只在"连续 3 天忘记喂食"这类**模式**出现时写一条 `tier = procedural` 的 lesson。
3. **礼物事件**：写 `tier = episodic`、`importance = 0.6`（用户花了钱，值得记）、`tags = "gift,<礼物名>"`。
4. **配额软隔离**：养成系统事件记忆合计 ≤ 总条目 40%；sweep 时统计，超限则批量下调 `affinity` tag 记录的 `importance` 0.05。

## 8. 中文处理的坑与 normalize 规则

### 8.1 归一化流水线（顺序不可乱，每步均为纯函数）

1. **NFKC**（`java.text.Normalizer.Form.NFKC`）：全角字母数字→半角，`①`→`1`，兼容字符折叠。
2. **小写化**（`Locale.ROOT`）。
3. **emoji 剥离**：`\p{Extended_Pictographic}` 与 `\p{So}` → 空格。emoji 不入 `normalizedText`（会被 2-gram 切碎成噪声），但**保留在 `text` 原文**；整条只剩 emoji 时 `normalizedText = "__emoji__"`，走 §8.5 短文本分支。
4. **标点归一**：`\p{P}`、`\p{S}` → 空格。中文 `。，、……` 全部变空格，只作切词边界，不保留。
5. **空白折叠**：连续空白 → 单空格，`trim()`。
6. **零宽清除**：删 `\u200B-\u200F\uFEFF`。

### 8.2 不做的事（及原因）

- **不做繁简转换**：OpenCC 词典约 2MB，用户 99% 用简体；混合场景下 2-gram Jaccard 靠共享 gram 已能兜住一部分。
- **不做错别字纠正**：需语言模型，且纠错会把用户原话改错。改为**查询侧**扩展（§8.3）。
- **不做中文数字转换**：`"一二三"` 不转 `"123"`（会误伤"一心一意"），只在检索时额外生成一份数字归一化查询副本。

### 8.3 语音转写误差 / 同音词

只在**查询侧**做轻量同音扩展，绝不改存储：维护 20 组高频易混词表（`在/再`、`的/得/地`、`他/她/它`、`做/作`、`帐/账`、`接/结`、`话/画`…）。查询含表中词时额外生成"替换副本"查询，与原查询走 §4.4 的 RRF 融合。存储侧永不改写，原文可追溯。

### 8.4 隐私剥离（在归一化之前，作用于 `text` 本体）

| 目标 | 正则（忽略大小写） | 替换 |
|---|---|---|
| API key | `(sk\|ak\|api[_-]?key\|token\|secret)[-_:= ]+[A-Za-z0-9_\-]{16,}` | `[已隐藏]` |
| Bearer | `bearer\s+[A-Za-z0-9._\-]{20,}` | `[已隐藏]` |
| PEM 私钥 | `-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----` | `[已隐藏]` |
| URL 内嵌凭据 | `[a-z][a-z0-9+.\-]*://[^/\s:@]+:[^/\s@]+@` | `[已隐藏]` |
| JWT | `eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}` | `[已隐藏]` |

手机号、身份证**不剥离**（可能是用户刻意要记的信息），但记忆页给黄色提示"这条包含疑似个人信息"。

### 8.5 边界情况

- 长度 < 2 的 `normalizedText`（纯 emoji、单字"累"）：不做 2-gram，检索走 `normalizedText LIKE '%q%'` 直配，`S_kw = 0.6`。
- 纯英文记忆：2-gram 对英文效果差（`th`/`he` 噪声大），改按空格切词 + 极简词干（只去 `-s`/`-ed`/`-ing`），`avgdl` 按词数计。
- 长段落（> 800 字）：2-gram 后 `tf` 被拉平会使 BM25 偏低，故 `|d| > 600` 时 `S_kw × 1.15` 做长度补偿。

## 9. 测试要点（纯函数优先）

1. `normalize("") = ""`；`normalize("   ") = ""`；`normalize("🐳") = "__emoji__"`。
2. `normalize("ＡＢＣ１２３") = "abc123"`（NFKC + 小写）。
3. `normalize("今天，天气不错！") = "今天 天气不错"`（标点→空格，不产生双空格）。
4. `normalize("https://a.com/x?b=1\u200B")` 结果不含 `\u200B`。
5. `dedupeKey("charA","x") != dedupeKey("charB","x")`（scope 参与哈希）；同输入两次调用相等。
6. `jaccard(G("今天天气"), G("今天天气")) = 1.0`；`jaccard(G("今天天气"), G("明天天气")) = 2/4 = 0.5`（交集 `{天天, 天气}`，并集 `{今天, 天天, 天气, 明天}`）。
7. `stripSecrets("我的 key 是 sk-abcdefghijklmnop1234")` 结果不含 `sk-`。
8. `stripSecrets("-----BEGIN RSA PRIVATE KEY-----\nxxx\n-----END RSA PRIVATE KEY-----") = "[已隐藏]"`。
9. `scoreOf(S_kw = 0, S_rec = 1, S_imp = 1, S_str = 1, pinned = false)` 被门控丢弃（返回 null）。
10. 单调性：`S_kw` 由 0.2 → 0.6、其余不变时 score 增量恰为 `0.4 × 0.50 = 0.20`（±1e-6）。
11. `tier=semantic, Δ=60 天 → S_rec = 0.5`（±1e-6）；`pinned=true` 同条件 `→ 0.5^(60/180) ≈ 0.794`。
12. `budgetTrim(cost 总和 2600, B = 2000)`：首条 cost ≤ 400（强制截断路径），`truncated = true`。
13. `budgetTrim(emptyList()) = emptyList()` 不抛异常；`B = 800` 时仍至少放入 1 条。
14. `sessionCap`：构造 5 条同 `sessionId` 的结果，输出恰 3 条，且被挤掉的位次由下一条顶上。
15. `evictRanking`：任何输入下 `pinned` 条目都不出现在淘汰列表内。
16. 迁移：17→18 后 `INSERT` 一行再 `SELECT` 字段全等；重复 `dedupeKey` 抛 `SQLiteConstraintException`。

## 10. 与 agentmemory 的差异对照

| 维度 | agentmemory | 本项目 | 原因 |
|---|---|---|---|
| 四层巩固 | Working/Episodic/Semantic/Procedural | **做了**，`tier` 四值枚举 | 成本极低，收益是衰减半衰期可分档 |
| 写入溯源 | 不可变 `origin channel` | **做了**，`sourceChannel` 落库不可改 | 审计 + 后续"用户说的 vs agent 说的"可信度加权 |
| 版本链 | 有，旧版退出索引但保留 | **做了**，`rootId` + `version` + `supersededBy` | 不丢历史是硬要求 |
| 相似处理 | 不合并，返回 `similarTo` | **做了**，同策略 | 静默合并会改变语义 |
| BM25 | 有（英文分词） | **做了**，中文 2-gram，`k1=1.2, b=0.75` | 中文无空格，必须 2-gram |
| 向量检索 | 有（可选） | **没做** | 体积 + 离线 + 500 条规模收益低（§4.1） |
| 图检索（实体 BFS） | 有 | **没做** | 中文 NER 需模型，噪声大于收益 |
| 混合融合 | RRF（k=60） | **部分做**：主排序线性，查询扩展用 RRF | 单路相关性 + 标量先验，线性更可解释（§4.4） |
| 会话多样化 | 每会话最多 3 条 | **做了**，同 3 条 | 防单次长对话霸屏 |
| Ebbinghaus 衰减 | 有 | **做了**，分 tier 半衰期 3/14/60/120 天 | 直接可用 |
| 访问强化 | 有 | **做了**，`accessCount` 对数 + `strength += 0.08` | 纯本地可实现 |
| 重要性淘汰 | 有 | **做了**，`E` 加权 + 三条硬规则 | 500 上限必须有确定性排序 |
| TTL | 有 | **做了**，working 90 天未访问优先淘汰 | — |
| 矛盾检测 | 有（语义级） | **降级做**：Jaccard 0.55–0.82 + 否定词差异 → `needsReview` | 无语义模型，只能近似启发式 |
| `eventId` 幂等窗口 168h | 有 | **改做**：`dedupeKey` 永久唯一 | 记忆是长期资产，不该 7 天后又重复入库 |
| token 预算注入 | 有（2000） | **做了**，2000 + 单条 12/4 开销模型 | 同默认值便于对照 |
| 压缩前重新注入 | 有 | **做了**：摘要折叠（`summaryText` 水位线）后重算一次 | 与本工程已有压缩机制对齐 |
| 隐私剥离 | 有（4 类） | **做了 + 1 类**：多一条 JWT | JWT 曾出现在本工程日志排查中 |
| 跨设备同步 | 无 | **没做** | 纯本地约束 |

## 附：落地顺序

`MemoryItemEntity` + `MIGRATION_17_18` + 迁移测试（风险最高，先做）→ `MemoryText.kt`（`normalize`/`stripSecrets`/`bigrams`/`jaccard`/`tokenCount`）→ `MemoryScoring.kt`（`bm25`/`recall`/`finalScore`/`budgetTrim`）→ `MemoryRepository`（写入 + 淘汰 + sweep）→ UI（长按菜单、记忆列表、回收站、`similarTo` 卡片）→ `MemoryPromptBuilder`（渲染 + 前缀缓存复用，接入 `AppContainer` 的 system prompt 组装处）。
