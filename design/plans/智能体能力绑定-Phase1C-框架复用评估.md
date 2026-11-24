# 智能体能力绑定：Phase 1-C 框架复用评估

- 日期：2026-11-24
- 范围：评估 AgentScope 的 `SkillUsageStore / SkillAuditLog` 与 `ContextSource` 能否直接替换问渠的 `SkillStatsStore` / `TurnPromptAssembler`。
- 结论先行：**这两个不硬接。** 框架实现面向“单工作区/持久资料源”，问渠需要“多租户、按 uid 聚合的管理统计”和“每轮动态附件/@引用”。强行替换会把业务语义做丢，属于为复用而复用。
- 原则：复用框架能力，但先看它解决的是不是同一个问题；不同问题保留业务适配层。

---

## 1. SkillUsageStore / SkillAuditLog 评估

### 框架能力

`javap`（agentscope-harness 2.1.0-BETA1）：

- `SkillUsageStore(AbstractFilesystem)` / `SkillUsageStore(AbstractFilesystem, String)` / `SkillUsageStore.baseStore(BaseStore)`
- `SkillUsageRecord` 字段：`useCount/viewCount/patchCount/lastUsedAt/lastViewedAt/...`、`state`、`pinned`、`sourceSessionId`、`environments`
- `SkillAuditLog(AbstractFilesystem, WorkspaceManager)`，按工作区落审计

### 问渠需要什么

- `SkillStatsStore` 按 **uid** 落文件：`user-data/skill-stats/{uid}.json`
- 管理页需要：某用户全部技能的 7/30 天读取统计、单技能 30 天序列、最近会话
- 多租户/个人资产语义：技能归属 `{uid}/{name}`，统计必须按用户隔离
- 热路径异步写、60 天保留、500 条上限

### 不直接替换的原因

| 维度 | 框架 SkillUsageStore | 问渠 SkillStatsStore | 结论 |
|---|---|---|---|
| 归属键 | 工作区/文件系统，`SkillUsageRecord` 无 uid/tenant 字段 | 按 uid 聚合、跨会话管理页读 | 不兼容 |
| 持久化 | AbstractFilesystem / BaseStore | 用户数据目录，随 user-data 备份 | 可借存储抽象，但要保留 uid 维度 |
| 统计口径 | use/view/patch 活动计数 | 按天计数 + 最近会话 + 30 天序列 | 业务投影不同 |
| 审计 | SkillAuditLog 按 workspace | 管理端需要按用户/技能筛选 | 不兼容 |

**决策**：保留 `SkillStatsStore` 作为业务统计层；如果未来要接框架，只接它的 `SkillUsageRecord` 数据形状作为内部存储格式，**不替换按 uid 聚合与 30 天投影**。这是业务语义，不是重复实现。

---

## 2. ContextSource 评估

### 框架能力

- `ContextSource.load(ContextRequest)`，`ContextRequest(agentId, userId, sessionId, callId, purpose)` 只带标识，不带本轮附件/@引用/历史引用正文
- `ContextBlock.runtime/reference`、`ContextPolicy`、`ContextSelectionPolicy`、`ContextSourceOptions`
- 设计面向**持久/可重建的资料源**（工作区文件、记忆、仓库文档）

### 问渠需要什么

- 每轮临时数据：上传附件正文、`@知识库`/`@文档`、`#历史引用`、调用变量
- 这些数据只存在于本轮 `ChatCommand` 里，下一轮不应自动重放；且需要在用户消息侧注入，不能进 sysPrompt（前缀缓存）
- 现有 `TurnPromptAssembler.userContentWithContext` 已按这个生命周期工作

### 不直接替换的原因

1. `ContextSource` 拿不到 `ChatCommand` 的本轮载荷；用 ThreadLocal 或全局 map 补，会把“本轮数据”藏进跨轮单例，正是仓库反复警告的串轮风险。
2. 框架 ContextSource 适合“可重建的持久资料”，不适合“本轮一次性的附件/提及”。
3. 强迁会把“附件解析失败要说出来”“@引用归属校验在控制器完成”“变量不进 sysPrompt”这些既有语义拆散到框架侧，得不偿失。

**决策**：保留 `TurnPromptAssembler` 作为本轮上下文业务层；只借鉴 ContextSource 的**分层形状**（runtime/reference、优先级、截断、按来源失败），不接入框架生命周期。等框架提供 per-turn payload 扩展点再迁。

---

## 3. 本阶段真正可做的“复用/收薄”

| 项 | 是否做 | 说明 |
|---|---|---|
| SkillStatsStore 换 SkillUsageStore | 否 | 归属维度不兼容，硬换会丢多租户统计 |
| SkillStatsStore 数据形状对齐 SkillUsageRecord | 可选 | 只借字段命名，不换存储/生命周期 |
| TurnPromptAssembler 迁 ContextSource | 否 | 缺 per-turn payload，硬迁会串轮 |
| TurnPromptAssembler 拆分「来源段落」 | 是（后续） | 把附件/@/历史/变量拆成独立 builder，便于将来接 ContextSource；行为不变 |
| SkillAuditLog 接入 | 否 | 与 SkillStatsStore 管理统计重叠，且按 workspace 不是按 uid |

---

## 4. 结论与下一步

- Phase 1-C 的结论不是“再写一堆代码”，而是**明确边界：框架解决单工作区持久资料，问渠保留多租户业务投影与每轮上下文。**
- 下一步应进入 Phase 2：**统一 Agent 解析入口**，而不是继续在统计/上下文层做形式上的替换。
- 该结论会回写 Phase0 盘点文档，避免后人误以为“还没接框架就是欠债”。
