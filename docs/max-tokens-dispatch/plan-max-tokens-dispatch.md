# 立项：运行时按模型登记值真下发 max_tokens

- 日期：2026-11-07（当日拍板）
- 结论先行：问渠运行时**从不下发 `max_tokens`**，单次补全输出上限=上游默认值。本案实际观测 8192，
  导致「把大产物内联进工具参数」的打法必然截断、参数残缺。治本=把登记的 `c_ai_model.max_output`
  真正作为 `max_tokens` 随请求下发。

## 1. 案发与根因（2026-11-07 真机）

- 会话 `70878c57d7054172b2b651410f74b979`（线上栈）：模型把整页效果图 HTML 内联进
  `presentArtifact.content`，连续两次工具调用入参都是 `{}`，报
  `Parameter validation failed for tool 'presentArtifact': required property 'content'/'filename'/'description' not found`，
  回喂后重试同打法 → 重复闸拦下（「检测到模型重复调用同一工具」）→ 无进展守卫收轮。
- 事件账本 `c_ai_session_event` usage 序列铁证：每步补全精确顶 8192 output tokens
  （8721→8192、16913→8192、25105→8192、33297→8192、41489→8192，累计每步恰好 +8192）。
- 根因链：上游（千问 token-plan，模型 id 挂 `deepseek-v4.1-flash-1017` 名）单次补全默认输出 8192
  → 输出在工具参数中途截断 → 框架参数流残缺解析成 `{}` → schema 校验拦下。
- 问渠侧事实（已逐点核实）：
  - 全链路查无 `max_tokens` 下发点：`DynamicOpenAiChatModel`（手写侧 Spring AI）、`ChatService`、
    agentx 引擎均无；
  - `c_ai_model.max_output`（本案登记 384000）只用于两处：登记时跨字段校验
    （`ModelRegistryService` 窗口×安全系数−输出）与信息展示；**不下发**；
  - `schema.sql` 中 `max_output` 注释「作为 max_tokens 随请求下发」是过期注释（治本落地后转真）；
  - `context.maxOutputTokens` 配置键已退役（`ConfigService:686`）。
- 旁证：同日会话 `7a2654…` 同模型成功交付 22KB 官网页——模型走了 `write_file` 分段写沙盒 +
  present 引用的正路，每段都在 8k 以内。即 8192 下大产物并非不可交付，而是内联打法必死。

### 1.1 git 考古：能力不是「没建过」，是框架迁移时丢的

| 时间 | 提交 | 事实 |
|---|---|---|
| 2026-08-15 | 5759d68b | 全局 `context.maxOutputTokens` 时代，老链路真实下发 |
| 2026-10-02 | d7c53d4d | 窗口/输出改按模型声明，全局配置降级为兜底 |
| 2026-10-03 | f77ef04a | 全局三项退役；老链路（RagService）改为**按模型行 max_output 下发**（`optionsBuilder.maxTokens(st.effectiveMaxOutput)`）——schema 注释「作为 max_tokens 随请求下发」当时为真 |
| 2026-10-13 | a82ea790 | **P6 刀 5b/5c/5d：RagService 删除，问答搬进 AgentScope 框架引擎**——框架侧从未接线（`git log -S maxTokens` 于 AgentscopeChatEngine/DynamicOpenAiChatModel 全历史为空），下发能力随旧链路连根拔掉 |

25 天无人发现的原因：① 正常问答用不满 8k 输出，症状只在「模型把大产物内联进工具参数」时出现；
② 框架如实上报 usage，8192 看着像模型固有行为，且没有断言「请求体带 max_tokens」的测试/探针；
③ 「两条链同改」这道闸对**删链路**场景失效——被删的那份不是被同步，而是被连根拔掉，schema 注释
（写于退役时）从此与框架链不符，无人回头核。教训：删共享能力所在的链路时，先盘点它独有的
请求参数，逐项在承接链路上补位并留探针。

## 2. 方案要点

1. **下发点两处同步**（两条引擎路径都要，漏一处就是「同一个模型两条链行为不一」）：
   - 手写侧：`DynamicOpenAiChatModel` 请求装配处（Spring AI `ChatOptions.maxTokens`）；
   - 框架侧：agentx（AgentScope）模型构造/请求选项——2.1.0-BETA1 的 GenerateOptions 是否支持
     per-call maxTokens 需先核（框架文档与实现有出入的前科）。
2. **值口径**：`max_tokens = c_ai_model.max_output`；`NULL = 不下发`（保持现状，交厂商默认）。
   不另设全局上限（`context.maxOutputTokens` 退役不复活）。
3. **辅助调用同步带**（同源口径，别只改主链）：压缩/摘要/评判/检索改写/连通性探测
   （`ConnectivityProbeService`）等旁路调用。注意与 [[aux-calls-thinking-budget]] 的既有结论联动：
   默认开思考的模型思考与正文共享 max_tokens，辅助调用继续走 reasoningOffBody 模式
   （关思考字段，关不掉就放宽 maxTokens）——下发真实 max_output 后这条修复模式的
   「放宽」分支口径要跟着核一遍。
4. **计量/台账不回归**：`UsageLedgerService` 的 output/finalOutput 口径、MeteredModel 计费、
   容量面板「输出预留」展示照旧——登记值语义没变（仍是窗口预算预留），只是开始随请求下发。
5. **配一层护栏**（可选、建议同批）：下发前若登记值 > 供应商族已知硬上限，warn 日志提示登记可能虚标，
   不拦截（发现一家收编一家，别建硬表）。

## 3. 风险与兼容性（真机逐家验，不可跳）

- 部分供应商对超范围 `max_tokens` 直接 400（DashScope 族有范围校验前科），有的静默 clamp 到
  模型真实上限，有的忽略——必须逐供应商真机一发验证：
  - 千问 token-plan（本案源头：验 384000 是否被接受、被 clamp 成多少、计费口径）；
  - deepseek 官方、方舟（火山方舟）、OpenAI 兼容网关，按 `c_ai_model` 全量登记值过一遍；
- 兼容旧供应商：`max_output` 为 NULL 的行行为零变化（不下发）；
- 开发阶段拍板沿用 [[startup-migration-policy]]：不发迁移，登记值靠用户在设置页自行修正。
- 提示词缓存影响评估：max_tokens 不进 prompt 前缀，理论上不影响 cached 前缀；真机复核一眼。

## 4. 验证清单（完工闸）

1. token-plan 模型：大 HTML 内联 `presentArtifact` 一发过（内容 >8k token 不再截断、参数完整）；
2. `max_output=NULL` 模型：请求体无 max_tokens 字段（抓包/日志核），行为与改前一致；
3. 辅助调用（压缩/评判/摘要）在开思考模型上正文本空不回归（reasoningOffBody 联动口径）；
4. 用量台账 output/finalOutput 与计费口径不变；
5. 每家供应商连通性探针过（400 型拒绝要在这里先炸出来）。

## 5. 落地前顶一阵的临时手段（本次未实施，用户未选）

- 提示词中心 PUT / presentArtifact 工具描述加一句「大文件先 write_file 分段写沙盒，
  presentArtifact 只做引用交付」；
- 识别「空参 + 上一步输出触顶」给一条人话降级说明，替代难懂的 required property 报错。
- 登记诚实化：token-plan 模型 `max_output` 改 8192（SQL+重启）——若治本落地，此项作废。
