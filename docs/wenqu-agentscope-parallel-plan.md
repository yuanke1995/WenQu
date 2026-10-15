# 问渠 AgentScope 平行平移方案（手写运行时保留 + 框架版并行）

> 目标：保留现有手写 agent 运行时（`com.wenqu.ai.agent.*`）不动，另起一份基于 **AgentScope Java 2.0** 的平行实现，通过统一抽象 + 配置开关并存，先试点、再灰度、最后可选切换默认值。
>
> **⚠️ 2026-10-14 Phase 0 已跑完，本篇有四处被实测推翻，读前先读 [wenqu-agentscope-phase0.md](wenqu-agentscope-phase0.md)：**
> ① `AgentSession` 在稳定版 `2.0.4` 里**根本不存在**（jar 内 0 个类），只有 `2.1.0-BETA1` 有——本篇 §6 风险3 与 §9.2 那行只在 BETA 上成立；
> ② per-request 换模型**没有框架入口**，`modelResolver` 只服务子智能体，得走「每请求 `build()` 新实例」；
> ③ SSE 是 **29 个事件名 / 58 处发送点**（不是「60+ 事件点」），前后端当前一一对应无孤儿；
> ④ 框架**有** RAG 扩展模块，只是我们不用它。

---

## 0. 决策结论

- 主线"像工作流"不是框架造成的——它是你们**手搓的一套 agent 运行时**（`agent/chat/*` 的轮次调度 + `agent/tool/*` 工具管线 + `agent/subagent/*` 分支 + `agent/hitl` 审批等）。
- 用户对"平移"的明确定义 = **用 AgentScope 原生能力，照着现有功能完整复刻一份平级的实现**，而不是在部分端点嵌入框架、把手写运行时当壳。
- **复刻式 vs 嵌入式的区别（关键）**：
  - ❌ 嵌入式（已被否）：手写 `runChat` 仍是主编排，框架只在工具/子智能体几处节点替换。
  - ✅ 复刻式（本方案）：新增 `com.wenqu.ai.agentx.*`，由 AgentScope 原生的 `HarnessAgent` 推理循环 + Toolkit + 权限 + 子智能体 + HITL + Plan Mode **完整拥有控制流**，逐条对照 `agent.*` 的功能重新实现。
- 采用 **路线 C（保留 + 并行）**：手写 `agent.*` 与框架 `agentx.*` 双实现并存，门面按配置分派。手写资产全程不删，风险可控。

---

## 1. 为什么能"平行平移"（关键事实）

- AgentScope Java 2.0 已 GA，同 JVM、JDK 17+、Apache-2.0，与现有 Spring Boot / JDK 栈无冲突。
- 它的核心能力（`HarnessAgent` + `Middleware` + `Toolkit`）正好覆盖问渠自研的几大块，重叠度如下：

| 问渠手写模块                                   | 职责                                                                            | AgentScope 对标能力                | 复刻方式（框架原生）                            |
| ---------------------------------------- | ----------------------------------------------------------------------------- | ------------------------------ | ------------------------------------- |
| `agent/permission/*`                     | 静态规则 + 工具类别 + 输入分析的三态权限                                                       | 内置三态权限（allow / approve / deny） | 把现有规则翻译进 AgentScope 权限配置，不回调手写逻辑      |
| `agent/context/HistoryCompactionService` | 历史压缩                                                                          | 结构化上下文压缩（目标/状态/发现/下一步）         | 用 AgentScope 压缩或 agentx 内等价实现         |
| `agent/chat/*`（TurnRuntime）              | 轮次/会话生命周期                                                                     | Runtime + sessionId 跨进程恢复      | 用 HarnessAgent Runtime 重建轮次/会话语义，见 §6 |
| `agent/tool/provider/*`                  | 9 类工具供给（Builtin/Knowledge/Mcp/Sandbox/Skill/SubAgent/Todo/WebSearch/Artifact） | `@Tool` 注解 Toolkit，自动 Schema   | 逐类重写为 Toolkit 方法                      |
| `agent/tool/guard/*`                     | 4 个护栏（ApprovalGate/PermissionGuard/StepBudget/UserStopped）                    | Middleware 扩展通道                | 重写为 Middleware                        |
| `agent/hitl/*`                           | 人工确认/审批决策                                                                     | `human_confirmation` 事件（一等公民）  | 用框架事件替代手写决策流                          |
| `agent/subagent/*`（BranchRunner）         | 子智能体分支执行                                                                      | 声明式子智能体编排                      | 用 HarnessAgent 子智能体重写分支               |
| `agent/plan/PlanModeService`             | 计划模式                                                                          | 内置 Plan Mode                   | 改用框架 Plan Mode                        |
| `agent/think/DeepThinkService`           | 深度思考（提示策略）                                                                    | 引擎无关                           | 移植提示词到 agentx                         |
| `agent/runcode/*`                        | 代码沙箱执行                                                                        | 沙箱（local / Docker / remote KV） | 用框架沙箱，或把手写执行通道包成工具                    |
| `agent/context/RetrievalPipeline`        | RAG 多路检索                                                                      | **无 RAG**                      | **不重造**，作为被调用的检索工具接入                  |
| `agent/context/CitationIntegrityService` | 引用完整性                                                                         | 无                              | 在 agentx 检索后管线重写校验逻辑                  |
| `agent/chatflow/*`                       | 用户自建图（StateGraph）                                                             | 图编排非核心                         | 单独评估，暂不动                              |

---

## 2. 总体架构：并行双实现 + 门面分派

```
                          ┌─────────────────────────────┐
   前端 (60+ SSE 事件点) ←─┤   SSE 适配层 (RunEvent→SSE)  │←─ 不动前端
                          └──────────────┬──────────────┘
                                         │ RunEvent 流（类型化事件）
                          ┌──────────────┴──────────────┐
                          │   ChatService 门面（现有）   │
                          │   chat(ChatCommand, emitter) │  line 707
                          └──────────────┬──────────────┘
                                         │ ChatEngine 接口（新增抽象，仅做分派）
                          ┌──────────────┴──────────────┐
              chat.engine.mode = legacy | agentscope | ab
     ┌───────────────────────────────┐  ┌──────────────────────────────────────┐
     │ LegacyChatEngine (手写, 零改动) │  │ AgentscopeChatEngine (框架原生复刻)      │
     │ 实现：现有 agent.* 全量复用      │  │ 实现：com.wenqu.ai.agentx.*            │
     │ 控制流：手写 runChat 推理循环    │  │ 控制流：HarnessAgent 原生推理循环        │
     │                                 │  │   + Toolkit(工具) + 权限 + 子智能体      │
     │                                 │  │   + HITL + Plan Mode 完整拥有           │
     └───────────────────────────────┘  └──────────────────────────────────────┘
             ↑ 平级、对照、可 A/B，互不共享控制流
```

**核心思想**：抽象层只做"前端 SSE 契约 ↔ 底层引擎"的解耦与分派，**不承载任何业务逻辑**。两份实现是平级的、各自完整拥有控制流：

- `LegacyChatEngine` = 现有 `agent.*` 原样搬入，手写 `runChat` 推理循环一字不改。
- `AgentscopeChatEngine` = 全新 `agentx.*`，用 AgentScope 原生原语**逐条复刻** `agent.*` 的功能，控制流完全由框架接管，不回调手写的轮次/工具/子智能体逻辑。

**什么复刻、什么共享（重要边界）**：

- **复刻（控制流层）**：轮次/会话生命周期、工具调度循环、子智能体分支、HITL、Plan Mode、深度思考、历史压缩、权限判定 —— 这些在 `agentx.*` 里用 AgentScope 原生能力重新写。
- **共享（基础设施层，不重造）**：Redis 向量库 / `RetrievalPipeline` 的检索执行、模型配置与 per-user 模型覆盖、底层 MCP/Sandbox 执行通道。框架版把它们当作**被调用的工具/服务**接入，而不是"在端点嵌入框架"。这不算嵌入，而是复刻后的控制流去调用既有基础设施。

---

## 3. 核心抽象设计（Phase 1，最重要）

新增包 `com.wenqu.ai.engine`：

- **`ChatEngine` 接口**
  ```java
  interface ChatEngine {
      void chat(ChatCommand cmd, RunEventSink sink); // sink 推送 RunEvent
  }
  ```
- **`RunEvent`**：把现有 60+ 个 SSE 事件收敛为 AgentScope 风格的类型化事件（枚举 `EventType` + 负载 POJO）。
  - Legacy 侧：写一层 `LegacyRunEventAdapter`，把原 SSE 事件映射成 `RunEvent`（**原 `ChatService.runChat` 逻辑基本原样搬进 `LegacyChatEngine` + adapter**）。
  - AgentScope 侧：把 `streamEvents()` 的 `text_chunk / tool_call / human_confirmation` 等映射成同一 `RunEvent`。
- **分派**：`ChatEngineFactory` 读 `chat.engine.mode`：
  - `legacy` → 手写引擎（默认，零风险）
  - `agentscope` → 框架引擎
  - `ab` → 按 `sessionId` 哈希分流，用于灰度对比

---

## 4. 模块平移映射（按优先级）

| 优先级 | 功能          | 手写现状                            | AgentScope 复刻方式                | 复刻性质           |
| --- | ----------- | ------------------------------- | ------------------------------ | -------------- |
| P0  | RAG 检索      | `RetrievalPipeline` + Redis 向量库 | 作为被调用的检索工具接入                   | 共享基础设施，**不重造** |
| P0  | 工具系统        | `tool/provider/*` 9 类           | 重写为 `@Tool` Toolkit 方法         | 框架原生复刻         |
| P0  | 流式输出        | `ChatService` SSE               | `streamEvents()` → RunEvent 适配 | 框架原生复刻         |
| P1  | 权限          | `permission/*`                  | AgentScope 原生三态权限，移植现有规则       | 框架原生复刻         |
| P1  | 工具护栏        | `tool/guard/*` 4 个              | 重写为 Middleware                 | 框架原生复刻         |
| P1  | 子智能体        | `subagent/*`(BranchRunner)      | HarnessAgent 声明式子智能体           | 框架原生复刻         |
| P1  | HITL        | `hitl/HitlDecisionService`      | `human_confirmation` 框架事件      | 框架原生复刻         |
| P2  | Plan Mode   | `plan/PlanModeService`          | 框架内置 Plan Mode                 | 框架原生复刻         |
| P2  | 历史压缩        | `context/HistoryCompaction`     | 框架压缩 或 agentx 内等价实现            | 框架原生复刻         |
| P2  | 深度思考        | `think/DeepThinkService`        | 移植提示策略到 agentx                 | 框架原生复刻         |
| P2  | 代码执行        | `runcode/*`                     | 框架沙箱 / 手写执行通道包成工具              | 框架原生复刻         |
| P2  | 引用完整性       | `context/CitationIntegrity`     | agentx 检索后管线重写校验               | 框架原生复刻         |
| P3  | 用户 chatflow | `chatflow/*`(StateGraph)        | 图编排非核心                         | 暂不动，单独评估       |

---

## 5. 分阶段实施（时间盒）

| 阶段                  | 内容                                                                                                                                 | 周期    | 产出                   |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------- | ----- | -------------------- |
| **Phase 0 技术探针**    | 引入 `io.agentscope:agentscope-harness`；验证 JDK17/Spring Boot 兼容；用问渠现有 DashScope/Qwen 模型配置跑通一个最小 `HarnessAgent`                       | 1 周   | 可行性确认 + 依赖清单         |
| **Phase 1 抽象层**     | 定义 `ChatEngine` / `RunEvent` / `RunEventSink`；把现 `runChat` 包成 `LegacyChatEngine` + adapter；实现 `ChatEngineFactory` + 配置开关           | 1–2 周 | 双引擎可在 `legacy` 下无缝切换 |
| **Phase 2 端到端最小路径** | `AgentscopeChatEngine`：模型调用 + RAG 检索工具 + 流式 → 跑通单轮知识问答 happy path                                                                  | 2 周   | 框架引擎首个可运行版本          |
| **Phase 3 工具与权限**   | 9 类 `ToolProvider` → Toolkit；4 个 `guard` → Middleware；权限 Middleware 接入                                                             | 2–3 周 | 工具调用闭环               |
| **Phase 4 高级特性**    | 子智能体编排、HITL、Plan Mode、深度思考、代码执行                                                                                                    | 3–4 周 | 功能对齐手写               |
| **Phase 5 双跑校验**    | 语料回归、RunEvent 流 diff、质量/成本对比                                                                                                       | 持续    | 对齐报告                 |
| **Phase 6 灰度放量**    | `ab` 分流 → 默认切 `agentscope` → 可选弃用 legacy；决策 `spring-ai-alibaba-agent-framework` 去留（主线零依赖，仅 SubAgentOrchestrator/WorkflowEngine 使用） | 按数据定  | 上线决策                 |

---

## 6. 风险与对策

1. **前端 SSE 契约（60+ 事件点）仍是核心粘合工作，但范围已收窄**：v2 文档的 `Channel.sendStream()` + `ChatUiChannel` 已提供 per-session 并发与流式 SSE 骨架，真正剩下的工作是"把 `AgentEvent` 类型化事件（`TEXT_BLOCK_DELTA` / `TOOL_CALL_START` / `REQUIRE_USER_CONFIRM` / `SUBAGENT_EXPOSED` / `AGENT_START`…）逐类翻译成问渠前端已有的 60+ 个自定义 SSE 事件"。建议 Phase 1 冻结/文档化现有事件契约，并建一张 `AgentEvent → 问渠SSE` 映射表。
2. **模型抽象差异（per-user 模型覆盖）**：问渠用 `resolveChatModel`（per-user 模型覆盖）；AgentScope 用 `.model("dashscope:qwen-xxx")` + `agentscope-extensions-model-dashscope`，按 `ModelRegistry` 解析。需验证如何在每请求切换模型——v2 文档的模型页当时未能抓取，列为开放项，Phase 0 探针必须验证。
3. **会话状态权威——已被 AgentSession 大幅消解，不再是主要风险**：`AgentSession` 的 `submit/steer/respond/resume/interrupt` + checkpoint + 重启后 `transcript()` 恢复，原生覆盖了问渠 `TurnRegistry`/`TurnHandle` 的轮次生命周期（后台执行、排队、补充要求、HITL 待办持久化、断线续跑）；`Channel` 还自带 per-session 并发控制，替代问渠自管的轮次串行化。**剩余待对齐**：guest 模式、`TurnDeadline` 超时、heartbeat 保活这几项问渠特有语义，要确认是映射到 AgentSession/Channel 还是保留薄适配层。
4. **成本/性能**：A/B 期间模型调用翻倍，先在内部测试会话跑，别直接上生产流量。
5. **RAG/向量库是共享基础设施，不重造**：RetrievalPipeline + Redis 向量库是问渠核心资产，框架没有等价物。`agentx` 把它作为被调用的检索 `@Tool` 接入即可——这不属于"嵌入框架"，而是复刻后的控制流去调用既有基础设施；引用完整性（CitationIntegrity）在检索工具内部重写。
6. **`spring-ai-alibaba-agent-framework` 去留**：主线零依赖，仅 `SubAgentOrchestrator`/`WorkflowEngine`/`WorkflowService` 用其 `StateGraph`。即便全切 AgentScope，那三处也是独立旁路，可单独评估，不阻塞本方案。

---

## 7. 试点建议（先打样）

选 **"单轮知识问答 + 工具调用"** 作为 Phase 0–2 打样目标：

- 用内部测试会话验证「抽象层 + RunEvent 映射 + RAG 工具 + 流式」端到端跑通；
- 对比同一问题在手写引擎与框架引擎下的输出质量与事件流；
- 验证 OK 后再决定是否扩面到子智能体/HITL/Plan Mode。

---

## 8. 立即可做的一步

注意：接下来的 `agentx.*` 不是"在端点嵌框架"，而是**用 AgentScope 原生原语逐条复刻 `agent.*` 的功能**，手写版始终作为对照基线并存。两件事可并行启动：

- **(a) 写抽象骨架（分派层，零业务风险）**：`ChatEngine` / `RunEvent` / `LegacyChatEngine`（原样包入 `runChat`）+ adapter + `ChatEngineFactory` + 配置开关。先把"双实现可切换"的架子立起来，手写侧零行为变更。
- **(b) Phase 0 探针 + 起手复刻核心循环**：在 `pom` 加 AgentScope 依赖，用问渠现有模型配置跑通最小 `HarnessAgent`，并直接动手写 `agentx` 的**核心推理循环 + RAG 检索工具 + 流式**（Phase 2 的复刻主体），而不是先写一堆中间件。

推荐 **(a)+(b) 并行**：骨架定分派契约，探针+核心循环验证"框架原生复刻"这条路走得通；两者都不碰手写业务。等核心循环在 `agentscope` 模式下跑平 happy path，再逐模块（工具→权限→子智能体→HITL→Plan Mode）展开复刻。

---

## 9. v2 官方文档校验（实读 java.agentscope.io/v2/zh/docs，2026-10-14）

> 结论先说：AgentScope Java 2.0 把问渠手写的整套运行时几乎都做成了**开箱即用的原生能力**。原方案里"复刻=从零重写控制流"的假设偏保守——实际复刻工作量从"重建"降为"配置 + 把问渠领域工具/语义映射到框架原语 + 写事件词汇适配层"。

### 9.1 实读章节

快速开始 / Harness 架构 / Tool / Permission System / 子 Agent / Plan Mode / 会话操作·事件与恢复 / Channel / 上下文管理。（模型页抓取失败，列为开放项。）

### 9.2 逐能力对照（问渠模块 → v2 原生能力 → 是否还需手写）

| 问渠手写模块                              | v2 原生能力（实读确认）                                                                                                        | 复刻结论                                                                        |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| `agent/chat/*`（TurnRegistry/Handle） | `AgentSession`（submit/steer/respond/resume/interrupt）+ checkpoint + 重启恢复；`Channel` per-session 并发                    | **几乎全原生替代**；剩 guest 模式/deadline/heartbeat 薄适配                               |
| 前端 SSE（60+ 事件）                      | `Channel.sendStream()` + `ChatUiChannel`，自带 SSE Controller 示例                                                        | **骨架白送**；剩 `AgentEvent → 问渠SSE` 词汇翻译                                        |
| `tool/provider/*`（9 类）              | `@Tool` 注解 + `Toolkit`；MCP 由 `McpClientBuilder` 内置（STDIO/SSE/HTTP）；`TodoTools` 内置                                    | **直接平移**为 `@Tool`；MCP 一行注册                                                  |
| `tool/guard/*`（4 护栏）                | 权限系统（见下）+ `AgentSession.interrupt` 对应停止；StepBudget 用 middleware/预算                                                   | ApprovalGate/PermissionGuard→权限；UserStopped→interrupt；StepBudget→middleware |
| `permission/*`                      | `PermissionMode`（DEFAULT/ACCEPT_EDITS/EXPLORE/BYPASS/DONT_ASK）+ `PermissionRule` + `ToolBase#checkPermissions`（不可绕过） | **原生替代**，把现有规则翻译进 `PermissionContextState`                                  |
| `hitl/*`                            | 权限 ASK → `PERMISSION_ASKING` + `RequireUserConfirmEvent`；`AgentSession.pending()/respond()` 持久化                      | **原生替代**，HITL 待办可跨重启恢复                                                      |
| `subagent/*`（BranchRunner）          | `agent_spawn/agent_send` + `SubagentDeclaration`；同步/后台；自动反向通知；`expose_to_user`=分支对话                                  | **原生替代**，分支对话=SubagentExposedEvent + sendToSubagent                         |


| `plan/PlanModeService` | `.enablePlanMode()` + plan_enter/plan_write/plan_exit（HITL 退出）；状态持久化；可程序化进出 | **原生替代** |  
| `context/HistoryCompaction` | `.compaction(CompactionConfig)` + `.toolResultEviction(80K)` + `contextSource`（动态业务信息） | **原生替代**；`contextSource` 对应问渠动态注入 |  
| `context/RetrievalPipeline` + Redis | 无 RAG | **不重造**，包成检索 `@Tool` |  
| `context/CitationIntegrity` | 无 | 在检索 `@Tool` 内部重写 |  
| `runcode/*` | `filesystem(...)` 沙箱（本机/Docker/远端）+ 隔离恢复 | 用框架沙箱 或 接入手写执行通道 |  
| `think/DeepThinkService` | 引擎无关，提示策略 | 移植提示词 |  
| `agent/chatflow/*`（StateGraph） | `AgentSession` 任务编排；Service 的 Workflow（预览） | 暂不动，单独评估 |

### 9.3 对原方案的实质修正

1. **§2「什么复刻/什么共享」需重写**：权限、HITL、Plan Mode、子智能体、上下文压缩、MCP、会话生命周期——这些不再是"复刻后重写"，而是**直接启用框架原生能力**；真正要写的问渠特有部分收敛到：① 领域工具（`@Tool`：知识检索/内置工具/Skill/Artifact/WebSearch）；② `AgentEvent → 问渠SSE` 词汇适配；③ per-user 模型覆盖；④ guest/deadline/heartbeat 薄适配；⑤ 引用完整性。
2. **SSE 适配层工作量减半**：`Channel`/`ChatUiChannel` 已解决会话路由 + per-session 并发 + SSE 发射，剩下纯翻译。
3. **会话状态冲突风险基本消除**：`AgentSession` + `Channel` 原生覆盖轮次生命周期，原 §6 风险 3 从"主要风险"降级为"语义对齐校验"。
4. **MCP 集成变简单**：问渠 `McpToolProvider` → `McpClientBuilder.create(...).stdioTransport(...)` 注册，无需自研协议适配。
5. **依赖坐标确认**：`io.agentscope:agentscope-harness` + `agentscope-extensions-model-dashscope`（对齐问渠现有 DashScope/Qwen），JDK 17+，Spring Boot 用 `Mono.using` 管理实例生命周期。

### 9.4 仍待验证的开放项

- **模型页未抓取**：per-user 模型覆盖在 AgentScope 下如何按请求切换（`ModelRegistry` / `RuntimeContext` 传参 / 多 Builder）——Phase 0 探针必验。
- **guest 模式 / TurnDeadline / heartbeat** 三项问渠特有语义在 AgentSession/Channel 下的等价实现。
- **分布式存储**：问渠多副本部署时，AgentScope 的 `distributedStore` / `sessionLogStore` 后端（Redis）对接方式，需对照问渠现有 Redis 用法验证。

## 10. Phase 1 + 框架引擎首跑落地（2026-10-14 实测）

依赖已进主线 pom（`agentscope.version=2.1.0-BETA1`，选型依据见 [wenqu-agentscope-phase0.md](wenqu-agentscope-phase0.md) §7），框架引擎在 `agentscope` 模式下**真机跑通一轮问答**。

**框架原生持有 / 问渠被迫自研的分账**

| 位置 | 谁在做 | 说明 |
| --- | --- | --- |
| 推理循环、模型调用、工具派发、会话状态与 checkpoint | **框架**（`HarnessAgent` + `streamEvents` + `JsonFileAgentStateStore`） | `agentx/AgentscopeChatEngine` 里没有一行自研轮次循环 |
| 权限三态 / Plan Mode / 上下文压缩 / 子智能体 / MCP | **框架原生开关**（Phase 3–4 启用） | 不另写等价物：`permissionContext`、`.enablePlanMode()`、`.compaction(...)`、`.subagent(...)` |
| 每请求模型与自带 Key | **框架 API**（`OpenAIChatModel.builder()` + 每请求 `build()`/`close()`） | Phase 0 实测换来的写法；路由数据仍复用问渠 `ModelRegistryService.chatRoute` |
| 前端 29 个事件名 | **问渠自研适配**（`engine/RunEventType` + `agentx/AgentEventTranslator`） | 框架有 30 类事件但词汇不同，前端不许改，这层非写不可 |
| RAG 检索链 | **问渠资产，包成框架工具**（下一刀） | 框架无问渠的多路召回；当前框架引擎还没挂知识库工具 |

**落点**：`pom.xml`（依赖 + BOM）、`com.wenqu.ai.engine`（`ChatEngine`/`RunEvent`/`RunEventType`/`RunChannel`/`SseRunEventChannel`/`LegacyChatEngine`/`ChatEngineFactory`）、`com.wenqu.ai.agentx`（`AgentscopeChatEngine`/`AgentEventTranslator`/`AgentxConfiguration`）、`ChatService` 只在流水线提交处加一个分派点、`ConfigService` 四个默认键、`config-schema.json` 三个 debug 字段、`web/scripts/check-sse-contract.mjs` 契约护栏（已串进 `npm run check`）。

**两处与 §2 的图不同，是刻意偏离**

1. `LegacyChatEngine` 没有搬 `runChat`：它注册的是 `ChatService::runLegacyTurn` 这个函数（与 `BranchRunner`、`PlanTurnHooks` 同套路数，避免构造环）。把 3600 行私有方法换个包名、语义一字不改，风险全在下刀那一下而收益为零。
2. 分派点落在**轮次外壳之内**而不是最外层：会话轮级互斥、turnId 登记、断线总线、看门狗、身份与个人覆盖装载是「这一轮」的外壳，两种引擎都要；被替换的只是外壳里面那段推理循环。因此 §9.3 那条「`Channel` 白送 SSE 骨架」刻意不用——改用框架 `Channel` 等于把 `ChatRunBus` 的账本/seq/宽限再实现一遍。

**真机验收（8099 独立实例，不碰 8090）**

- `chat.engineMode=legacy`：8 类事件正常出流（token/stage/plan/retrieved/usage/subagent/tool_status/done），帧带 `seq`，走的还是包前那个收口点。
- `chat.engineMode=agentscope`：`stage → thinking×62 → token×8 → done`，正文「1+1 等于 2。」，事件同样带 `seq`；框架会话状态落在 `user-data/agentscope/<uid>/`，`~/.agentscope` 为空——默认目录被 `stateStore` 换掉这条实测生效。
- 验收后 `chat.engineMode` 已改回 `legacy` 并回查 DB 实值为 `legacy`；8099 实例已停。
- **未验**：29 个事件的逐场景全覆盖冒烟（审批/ask_user/计划卡/todo/产物/子智能体分支/插话/停止/断线重连/游客分享/MCP 与工作流内部调用各跑一遍比对帧序），以及知识库工具接入。

## 11. Phase 2 第二刀：引用池与库界抽出（2026-10-14）

框架引擎要挂知识检索工具，前置是两处只属于 `ChatService` 的私有状态，本轮把它们抽成可复用件，**没有**在框架侧复制第二份实现：

| 抽出物 | 落点 | 原处如何处置 |
| --- | --- | --- |
| 库界与文档范围判定（`scopeKbIdsOf`/`builtinScopeKbIds`/`builtinDefaultKbId`/`resolveScopeDocIds`） | 新 `@Service agent/context/RetrievalScopeService`，代码原样搬、语义一字未改 | `ChatService` 保留两个同名私有方法做转发，四处调用点不动 |
| 本轮引用池 | 新 `agentx/WenquSourcePool`：按轮组装 `TurnSourceRegistry` 并交出 `SourceRegistrar` | 编号逻辑仍只有 `TurnSourceRegistry.registerToolSource` 一处 |

框架侧工具挂载＝`WenquAgentxTools`（`@Component`）按轮产出 `@Tool` holder，直接委托现有 `KnowledgeRetrievalTool.searchKnowledge(query, topK, scope, registrar, reflective)`；`done` 帧的 `sources`/`degradations` 也取自同一个池，前端引用面板不为框架引擎另写渲染。

**一条从记忆坑位里预埋伏的雷，实测踩实了**：框架把工具派到自己的弹性线程，`RequestUser` 与 `retrieval.*` 覆盖不会跟过去 → 检索恒空且一声不响。修法与主链路工具线程一致：发起线程取 `RequestUser.snapshot()` + `currentOverrides()`/`currentUserOverrides()`，工具内装回、`finally` 复原（`putOverrides` 是合并语义，必须先清再装）。

**真机账（8099，测后已把 `chat.engineMode` 改回 legacy 并回查 DB 实值）**

- 框架引擎确实调到知识工具：`tool_status` 帧 32 条（legacy 同问 12 条），`stage/thinking/token/done` 齐。
- **两侧同一问题都返回 0 引用**——legacy 主链路同样 `retrieved.refs=0`、`sources:[]`，所以这是本机检索底座的事实而非 agentx 缺陷：本机 `c_ai_knowledge_child` 为空表（关键词路没有 MySQL 文本），19 篇 `chunk_count>0` 的文档内容只在 Redis/Milvus 侧。
- **因此「非空命中 → 引用编号 → 前端角标」这条链在框架引擎上仍未证成**。收尾动作：换一台有正常关键词索引的环境（或本机重跑一次解析入库），用同一问题双引擎比对 `sources` 与编号。任务 #12 保持未完成，判据写在其描述里。
- 编译与既有校验：`mvn -B verify` 通过、`npm run check`（含 SSE 契约护栏）通过。

### 11.1 引用链已证成（同日补测）

之前两侧 0 引用是**本机语料事实**：`c_ai_knowledge_child` 为 0 行、现存文档内容与探针问题不相关，不是 agentx 缺陷。为把它证成，向管理员默认库临时上传一篇自有内容的验证文档（`青竹运维规程.md`，含「熔断阈值 3200 次/分钟」「恢复命令 qingzhu-recover」「备份窗口周三 03:00–04:30」这类不可能猜中的事实），检索链路分步面板确认底座通了（关键词命中 2、向量命中 92、最终上下文 2），随后同一问题双引擎对照：

| 引擎 | 出流事件 | `done.sources` 引用编号 | 正文 |
| --- | --- | --- | --- |
| legacy | done/plan/retrieved/stage/subagent/token/usage | 1 条，ref=[1] | 「阈值为每分钟 3200 次请求…调用 qingzhu-recover 命令[1]」 |
| agentscope | done/stage/thinking/token/tool_status | 2 条，ref=[1,2] | 「阈值固定为每分钟 3200 次请求…调用 qingzhu-recover 命令。[1]」 |

两侧编号体系、`done.sources` 形状、正文角标写法完全一致——**框架引擎走的是同一个 `TurnSourceRegistry`，前端不需要为它另写引用渲染**，且框架侧确实靠工具检索拿到依据（不是预先塞上下文）。

验收后：`chat.engineMode` 已改回 `legacy` 并回查 DB 实值；验证文档已删（`deleted=0` 计数为 0）；8099 实例已停。

**仍未做**（不留模糊）：框架引擎没有问渠的「预检索」这一步，`retrieved`/`usage`/`plan` 三帧在框架路径上不发（前端会缺引用预览与用量卡）；引用完整性自检（`CitationIntegrityService`）也还没接。这两项属 #17/#18 的范围。

## 12. Phase 3 前置与权限半边（2026-10-14）

**装配判据抽出（#13 的前置，已落）**：`com.wenqu.ai.service.ToolAssemblyService` 成为本轮工具装配判据的唯一实现（`mountContext` / `agentFlagFor` / `agentSelectionFor` / `resolvePermissionPolicy` / `scopeOf`）。`ChatService` 那五个私有方法改成转发、调用点零改动。「族 → `c_ai_agent` 哪一列」按仓库既有口径仍留在 service 层——通用装配层 `agent/tool` 不认识业务实体。框架侧与问答侧现在问的是同一份判据，游客收窄与档位裁剪不存在第二份实现。

**权限档 → 框架权限系统（#14 第一半，已落）**：`agentx/AgentxPermissions` 只做语义翻译，不另立规则——档位仍由 `PermissionPolicyResolver` 一处解析。映射刻意不选 `BYPASS`：

| 问渠档位 | 框架模式 | 理由 |
| --- | --- | --- |
| `research` 只读调研 | `EXPLORE` | 有副作用的工具在这一档里必须与「不存在」不可区分 |
| `standard` 常规 | `DEFAULT` | 按工具自报性质决定是否问人 |
| `autonomous` 全自主 | `DONT_ASK` | 不再问人，但 `checkPermissions` **照跑**；`BYPASS` 会连检查一起跳过，等于把管理员已禁掉的能力放回给模型 |

按名黑名单落成框架 `denyRules`（MCP 那族只有按名这一层粒度）。工具级不可绕过的判定落在 `SpringAiToolAdapter`（改为继承 `ToolBase`）的 `checkPermissions`：**没解析出档位一律拒**（fail-closed）、黑名单拒、只读档拒副作用工具、登记需审批且档位允许问人时返回 `ask`。

**验证**：`mvn verify` 10/10 绿（新增 3 条权限断言：三档翻译、黑名单成规则、无档位/黑名单/问人三条自检路径）。真机 8099 在 `agentscope` 模式下接了 `permissionContext` 后仍正常出流（`stage→thinking×68→token×8→done`，正文「3×7 等于 21。」），无 `error`/`warn` 帧；测后 `chat.engineMode` 已回 `legacy` 并回查 DB，8099 已停。

**未落，且刻意不抢跑**：#13 的挂载半边等这条权限链验完再挂有副作用的族（现在挂进去会绕开问答侧四道闸）；`ask` 分支要等 #16 把框架 `AgentSession.respond()` 接上才不会悬到超时——这也是为什么只读族先挂、副作用族后挂；`StepBudget` 护栏尚未落成 middleware。

## 13. 只读族挂载 + 审批 fail-closed（2026-10-14）

框架引擎本轮的工具面由 `WenquAgentxTools.frameworkTools` 装配：判据问 `ToolAssemblyService`（与问答侧同一份），联网族经 `SpringAiToolAdapter` 复用**现有工具定义**挂进框架 `Toolkit`，落点（引用池 sink、库界、注册器）随本次调用的 `ToolContext` 传入；知识族仍走问渠自己的 `@Tool`。其余各族（内置/产物/技能/待办/沙盒/子智能体）本轮不挂，并把原因汇成**一条** warn 帧说出——静默少挂会被读成「配了没生效」，而逐族一条会把提示区刷满。

真机跑出一个必须当场收口的形状：**联网族登记为「执行前需确认」，框架侧返回 `ask` 后挂出一张没人能批的卡，整轮悬到超时**（无正文、无终态）。这不是框架缺陷，是 #16（人在回路接 `AgentSession.respond`）尚未落地时的时序问题。收口方式：适配器多一个 `approvalCanAsk`，引擎在没有应答通道之前传 `false`，权限判定由 `ask` 改为 **fail-closed 拒绝并给出可读原因**（模型据此改道，用户看得到为什么没搜）。#16 接上后把这一位翻成 `true`，判定本身不改。

同一问题（「联网搜索问渠是做什么的」）改前改后各跑一遍：

| | 帧 | 结局 |
| --- | --- | --- |
| 改前 | stage/thinking/tool_status×1/**approval_required**/done | 正文为空，整轮靠超时收 |
| 改后 | stage/thinking×43/**token×86**/tool_status×4/warn×1/done | 正常收尾，正文给出项目定位一句话 |

`mvn verify` 10/10 绿（新增断言：无人可答时必须 DENY 而不是挂悬卡）。测后 `chat.engineMode` 回 `legacy` 并回查 DB，8099 实例已停。

## 14. 框架人在回路与附着式接续（2026-10-14）

引擎的执行形态从「每请求 `streamEvents`+close」换成**常驻会话实例 + `AgentSession`**（`FrameworkSessions` 按 `(userId, sessionId)` 持有；模型换了就重建实例并接管同一条会话——会话能接续不依赖实例永生，Phase 0 实测换新实例同一身份读得到完整历史）。这一换带来的都是框架原生件：待办 `pending()`、答复 `respond(requestId, …)` 由框架自己找回原任务与原工具调用并接续执行（应用不拼工具 ID、不再调 `resume`）、增量事件按 `seq` 游标读。

三处实测换来的口径（都在代码注释里钉住）：

1. **载荷键名不能猜**：`tool/requested` 的工具名在 `call.name` 而不是顶层，`tool/decision` 的字段是 `decision`；按顶层取就是空白工具卡与空工具名（实测踩过）。
2. **出确认卡时不能提前收尾**：框架在提出确认时就把那条任务判成已结算。所以等待分三段——跑到出卡/终态/叫停；卡在用户手上时只等答复（超时不代答，既有口径）；答复后**接着读到比出卡时更晚的 `run/end`/`turn/completed`** 才收尾。只看 `task.settled()` 就是上一刀的「批准了却没下文」。
3. **弹性线程重放**：身份与本轮检索参数覆盖必须在发起线程取快照、在工具内装回并复原，否则检索恒空且不报错。

真机回路（8099，联网搜索触发确认）：`approval_required`（`approvalId=call_00_w5Yx…`、`tool=webSearch`）→ `POST /tool-approval/{id}` 返回「已批准」→ 框架接续执行该工具（第二条 `tool_status`）→ 正文流式 79 帧 → `done`。答复端点先走 `AgentSession.respond`，不属于框架会话的审批号才回落旧的 `HitlDecisionService` 通道，两边不双写同一个号。

`mvn verify` 10/10、`npm run check` 全过；测后 `chat.engineMode` 回 `legacy` 并回查 DB。

**仍未收口**：`ask` 之外的两条中断缺口（协作式中断、框架在中断时写英文兜底句）与跨重启恢复待办（需把 `sessionId↔requestId` 落库）；`ask_user` 提问卡与计划批准卡尚未走框架待办（只有工具确认走了）；`StepBudget` 尚未落成框架 middleware。

## 15. 只读族挂载与多轮人在回路（2026-10-14）

**工具面**：九族里除「沙盒执行」「智能体咨询」外全部挂进框架 `Toolkit`（内置/知识/联网/产物/技能/任务清单/MCP），全部复用问渠现有定义，一处 `SpringAiToolAdapter` 翻译。未挂的两族各有一条具体原因并**作为 warn 帧发给前端**（沙盒缺本轮落点与 `run_code` 回程；子智能体分支仍在手写运行时，见 #15 任务）——静默少挂会被读成「配了没生效」。

**提问卡换成框架原生**（`AskUserTool extends ToolBase`）：内置那个 `askUser` 靠 `ToolContext` 里的函数阻塞在工具线程等 future；实测 Spring AI 会把工具方法抛出的异常包掉，挂起信号根本到不了框架（表现是模型看到「工具失败」就自己编了答案）。框架内只留一套「等人」：工具返回 `Mono.error(ToolSuspendException)` → 任务 `status=suspended`、`pending()` 出一条 `interaction/requested`（实测 `kind=external_execution`，确认类是 `confirmation`）→ 界面答完 `respond(requestId, SessionAnswer.text(...))`。

**一轮里可以出好几次卡**——这是这一刀真正的修正点。原先按「等一次答复」写：批准之后模型再要一次确认就永远发不出第二张卡，正文也是空的（真机复现：批准联网后 `token=0`）。改成跟随会话现状：有待办就等人（用户等待不计机器耗时，但按确认超时封顶）；没待办就跟到本轮最后一条任务；任务结算后再让出一拍复查有没有新卡或接续任务，两者都无才算跑完。中间那版还踩过一个竞态——`respond` 返回后才登记交接位，而引擎判出「待办已空」正是 `respond` 的效果，先看到清空就收尾了。

真机账（8099，两条 HITL 各跑完整回路）：

| 场景 | 结果 |
| --- | --- |
| 联网搜索需确认 | 两张确认卡依次批准 → 工具执行 4 条 `tool_status` → 正文 84 帧（含 `[1]` 引用）→ `done` |
| `askUser` 提问 | 卡片带 `topic/question/options` → 答「A：蓝绿部署」→ 接续 20 帧正文「你选择了 **A（蓝绿发布）**…」→ `done` |
| 内置工具直调 | `currentDateTime` 工具卡 running/done 名字正确（此前因为取 `message.name` 拿到智能体名 `wenqu`，已修） |

`mvn verify` 10/10、`npm run check` 全过；测后 `chat.engineMode` 回 `legacy` 并回查 DB，框架状态目录 `user-data/agentscope` 已清空，8099 已停。

## 16. 计划模式走框架原生（2026-10-14）

计划模式不再自研：`HarnessAgent.builder().enablePlanMode()` 常开，**按「这一轮」进出**（`enterPlanMode/exitPlanMode`）——实例是按会话常驻的，不能在 build 期定死，跑完必须退出否则下一条不想要计划的轮会继承它。框架的 `plan_write/plan_exit` 落在 Session Log 里，退出确认（`kind=confirmation` 且工具名以 `plan_` 开头）翻成问渠的 **`plan_approval` 计划卡**（不是普通工具审批卡：界面上是两张卡，计划卡要让用户看到整份计划）；`/plan-approval/{id}` 先走框架待办——批准=approve，退回重出/继续对话/拒绝=reject 并把用户意见作为理由送回模型。

**一个自己造的账算错，已修**：等待预算原先从「轮开始」起算，于是计划卡在出现后 55 秒被批准、却因总预算 120 秒从开轮起算而被判成「确认等待超时」，接续执行白跑（框架这边其实照常跑完了 `plan_exit`）。问渠的 `TurnDeadline` 早就把机器耗时与人工等待分开记，这里必须同一口径：**预算从卡出现起算**，且计划卡用 `chat.planTimeoutMs`、工具确认卡用 `chat.approvalTimeoutMs`（产品里本来就是两份不同的等待预算）。

真机账（8099，`planMode=true`）：`plan_write → plan_exit` 出计划卡（含整份计划文本）→ 20 秒后批准 → 框架退出计划模式并**接续输出 242 帧正文**（第一步该干什么）→ `done`；全程只有「未接入沙盒/子智能体」这一条预期内 warn。

上下文预算也换成框架件：`.maxContextTokens(...)`——用户这一轮显式选过的窗口优先，未选走 `chat.agentscopeMaxContextTokens`（默认 0＝框架默认）。深度思考在框架侧是白送的：`model/chunk` 里 `type=thinking` 的块直接翻成 `thinking` 帧（实测每轮都有）。

`mvn verify` 10/10、`npm run check` 全过；测后 `chat.engineMode` 回 `legacy` 并回查 DB、`user-data/agentscope` 清空、8099 已停。

**未做**：问渠 `DeepThinkService` 的提示策略与「已压缩上下文」分隔线口径（框架压缩落在 Session Log，问渠的用户可见留痕仍按消息行 `tokens.summaryUntil` 判定，两边要对齐是 #17 剩余部分）；手动 `/session/{id}/compact` 尚未接到框架压缩。

> 这两项在 §28 收口（2026-10-15）：思考参数按厂商方言下发、`/compact` 按引擎分家；分隔线**决定不在框架引擎下画**，理由与实测都记在那一节。

## 17. 步数预算护栏 + 事件词汇补齐（2026-10-14）

**步数预算落成框架 middleware**（§12/§14 那条「尚未落成」收口）：`agentx/AgentxStepBudget` 挂在 `MiddlewareBase#onActing`，超限的那几把工具按框架自带 `PlanModeMiddleware` 的同一种形状收——写一条 `ToolResultState.DENIED` 的结果进会话上下文并发出 start/delta/end 三事件，模型据此改道直接作答，而不是把工具调用静默吃掉。

- **为什么不能用 `maxIters` 顶**：`maxIters` 数的是推理轮数，这一闸数的是**工具调用次数**，一轮里模型可以一次要 5 把工具。它同时是账单闸（问渠那条注释写得很明白：失控循环能把整轮 token 预算花光）。
- **放行即记账**照抄 `StepBudgetGuard`：被拒的那一步同样占预算。计数点与判定点一分家，实测表现就是上限 15 步的会话跑了三十几次工具调用。
- **实例常驻、预算按轮**：middleware 只能 build 期登记进实例，所以 `FrameworkSessions` 随实例存一份闸、引擎开轮时 `beginTurn(turnId, max, onDeny)` 归零。不归零就是上一轮的步数吃掉这一轮的。
- **上限解析抽出**：`resolveMaxToolSteps` 从 `ChatService` 搬进 `ToolAssemblyService#maxToolSteps`（与装配判据同一处），`ChatService` 改为转发。分支逐层减半、下限 3、0=不限制这几条语义一字未改。

**事件词汇补齐**（§11.1 那条「`retrieved`/`usage` 不发」收口）：

| 帧 | 框架侧数据源 | 时机 |
| --- | --- | --- |
| `usage` | 会话记录里**本轮新增消息**的 `ChatUsage` 求和（网关实测值，`outputIsReal=true`） | 收尾前发一帧，与 `done.tokens` 同源 |
| `retrieved` | 引用池 `sources.size()` + `KeywordExtractor.extractMain(问题)` | 跟着引用池增长发（框架没有预检索那一步，只有工具注册进来才知道命中了几段） |
| `todo` | `TodoService` 写入器：归一 → 整表落库 → 账本留痕 → 发帧 | 模型每次写清单 |
| `done` 载荷 | 补 `tokens`/`toolCalls`/`artifacts`/`thinking` 四组字段 | 收尾 |

分类用量 `parts` 与预算 `budget` 框架给不出对等物，**刻意不填估算值**——前端对缺字段是「不显示该行」，填了就是造假数据。

**两处只有真机能抓到的形状**（都已在代码注释里钉住）：

1. **清单写入器挂错族**：先当它是内置族的能力，挂进 `BUILTIN` 的落点。实测 `writeTodo` 返回 `result_len=14`——正是那句「本轮未启用任务清单」。`writeTodo` 属**任务清单族**，而主链路 `ChatService.toolContext` 本来就是全族共用的一张表。改挂到每次调用的共用落点上，清单才真的落库并出帧。
2. **`channel=null` 的静默**：`mountFrom` 有个不带 `RunChannel` 的重载，委托时把 channel 传成 null。于是修复 1 之后清单**落库成功却一条 `todo` 帧都不发**（右栏空白、模型侧却看到成功文本）——这种「后端做了、界面没动静」是最难归因的一类。删掉该重载，channel 全族必传。

**真机账（8099 独立实例，`chat.engineMode=agentscope`，`agent.maxToolSteps` 临时压到 2 以便撞闸）**

- 帧统计：`thinking×336 / token×76 / tool_status×6 / warn×3 / usage×1 / todo×1 / retrieved×1 / stage×1 / done×1`。
- 超限可见：warn「本轮工具调用已达上限（2 步），后续工具未执行」，且模型在正文里如实说明了「恢复命令那一次因达上限未执行」。
- `usage` 是网关实测值：`prompt=23397 / output=622 / cached=19456 / context=8058`；`retrieved.refs=0` 仍是本机关键词索引为空那条语料事实（§11.1 同口径），不是 agentx 缺陷。
- `todo` 帧载荷含 `turnId` 与 3 条 `items`，形状与 legacy 完全一致（前端 `onTodo` 认 `j.items`）。
- 测后复原并回查：`chat.engineMode=legacy`、`agent.maxToolSteps=50`（本机原值）；三条探针会话按 `deleted=0` 计数为 0；`user-data/agentscope` 已删；8099 已停；8090 未碰。
- 顺带把 §18 那条「只编译过、未真机验」的落库补上了证据：三条探针会话在删除前各有 user+assistant 两行消息在册（`c_ai_message` 计数 6），`done` 帧也带着落库返回的 `messageId`——框架引擎确实与手写侧共用同一条 `SessionService` 落库入口。


**校验**：`mvn -B verify` 15/15 绿（新增 5 条步数预算断言：超限切分、跨派发累计、开轮归零、0=不限制、放行与空派发不许说话）；`npm run check` 与 SSE 契约三处对齐通过。

**仍未做**（不留模糊）：`thinking_done` 没发（框架把思考当流内块、没有「思考阶段收尾」这个事件，要对齐得自己在 thinking→text 切换处补一帧）；智能体咨询族（`SUBAGENT`）与 `run_code` 子调用回程仍未挂，各有一条 warn 说给前端；引用完整性自检（`CitationIntegrityService`）、`DeepThinkService` 提示策略、手动 `/compact` 接框架压缩、待办跨重启落库（`sessionId↔requestId`）都还在。`ab` 分流的占比这一刀之后做成了配置项，见 §20。

## 20. ab 灰度占比做成配置项（2026-10-14）

`ChatEngineFactory` 里那个 `AB_FRAMEWORK_PERCENT = 0` 是编译期常量——Phase 6 要的「放量」得改代码重启，等于没有放量通道。改成 `chat.engineAbPercent`（0~100，默认 0）：

- **默认 0 是刻意的**：选了 `ab` 却没配比例时，一条会话都不许走框架。反过来（比如默认 50）就是「用户以为在做双跑、实际随机派了一半流量」。
- **判定口径不变**：`Math.floorMod(sessionId.hashCode(), 100) < 占比`，同一条会话恒定落同一侧——分流要的是「一个会话一套行为」，不是「一轮一套」。
- **越界夹到 0~100**：填 150 或负数时行为必须还是能复算的那种，而不是「看起来像全走框架/全不走框架但代码里其实另一种」。
- 落点判定抽成 `ChatEngineFactory#hitsFramework(sessionId, percent)`（不依赖 Spring 与设置页），新增 6 条断言：默认 0 不派、100 全派、同一会话 50 次判定不跳边、占比 35 时 4000 条会话实测落在 30%~40%、越界夹住、空会话号不参与。

**验证账**：`mvn -B verify` 21/21 绿；`npm run check` 与 SSE 契约三处对齐通过；`node scripts/gen_config_schema.js` 报「字段定义与 defaults() 一致」。**未做真机 PUT**——这条只过了编译+单测+schema 一致性，设置页那一格的实际保存与生效没打实例验过（下一刀接真机时一并补）。

## 21. 智能体咨询族挂进框架（2026-10-14，#15 收口）

九族里的最后一族（`SUBAGENT`）挂上了，`WenquAgentxTools.UNMOUNTABLE` 那套「未接入能力汇总 warn」机制随之删掉——现在只剩 `run_code` 一把工具还缺，走自己那条降级。框架侧没重写任何咨询语义：

| 件 | 来源 |
| --- | --- |
| `consultAgent` 工具本体（名字/参数/说明） | 经 `SpringAiToolAdapter` 复用现有 `SubAgentTools` 定义 |
| 预算·去重·回报转文本 | 同一个 `SubAgentConsultService.consult(ledger, ctx, runner, sink, …)` |
| 子轮执行 | 同一个 `SubAgentBranchRuntime.branchRunner(...)`：临时会话 + 收集型通道 + **再起一整轮** |
| 子轮查到的资料编号 | 同一个 `TurnSourceRegistry.registerConsultedSources`（父轮门与配额，不另立编号体系） |
| 「可咨询助手」提示段 | 正文抽进 `AgentService#consultGuideSection`，问答侧与框架侧共用一份；挂不挂的判据仍问同一份 `ToolRegistry#exposes` |
| 卡片上的「一问一答」 | 同一个 `TurnRecorder.consultCardSubChat`（长度尺与「只挂终态那一份」也同源） |

子轮入口与模型解析链都按**函数**注册（`AgentxConfiguration` 把 `ChatService::chat`、`ChatService::modelRefFor` 交给 agentx），agentx 不认 `ChatService` 这个 bean——与 `bindLegacy` 同套路数，避免构造环，也避免「框架引擎依赖手写运行时」这件事出现在依赖图上。

**这一刀真机抓到六条，都不是框架缺陷，是我把两套语义缝起来时的裂缝**

1. **弹性线程没身份（第三次踩同一坑）**：`AgentService#get` 内部带登录户可读校验，工具线程上读出 anonymous 就返回 null，于是咨询对用户报成「查无这位助手：id 不在可咨询清单里」——把**权限问题报成了 id 问题**，排查方向直接被带偏。修法：`TurnHooks` 带发起线程的 `RequestUser` 快照 + 本轮 `retrieval.*` 覆盖，整次咨询（连子轮）套在 `asCaller` 里跑。
2. **分支轮被当普通轮装配**：`mountContext(...)` 的 `branchTurn` 我写死 `false`。后果是分支里挂上了「要有人在场」的工具，而那条子会话没有观众——它会悬着等一个看不见它的人。改传 `cmd.branchTurn()`（`subagentDepth > 0` 是仓库里唯一判定点）。
3. **框架对单把工具有 5 分钟自带超时**（`ExecutionConfig.TOOL_DEFAULTS`）。咨询子轮跑到第 300 秒被框架判成工具失败（`Tool execution timeout after PT5M`），父轮只收到一句错误——**手写运行时没有这一层**。这是典型的隐形第二天花板。修法：`.toolExecutionConfig(timeout=本轮墙钟)`，并把 `chat.agentscopeTurnTimeoutSeconds` 默认从 300 改成 **0=沿用 `chat.sseTimeoutMs`**，让两套运行时共用同一个墙钟。
4. **嵌套轮的模型引用为 null 直接 NPE 打死流水线线程**：引擎原先只读 `cmd.getModelOverride()`，又把 `route.modelId()`（裸模型名）当作分支的 `modelRef` 传下去；子轮拿它再解析时 `chatRoute` 返回 null → `route.modelId()` NPE，整轮连错误帧都没有。修法：解析链（请求覆盖 > 会话固定 > 个人默认）从 `ChatService` 抽成 `modelRefFor` 两侧共用一份，分支**沿用父轮那一份引用串**；开局判不出模型时按问答侧同一条 fail-loud 走 `error` + 最小 `done`（没有 done 时前端会把它读成「连接被提前关闭」）。
5. **框架自带另一套记忆**：`memory_search`/`session_search`/`memory_get` 默认注册，读的是框架工作区里那份 `MEMORY.md`——用户在问渠界面既看不见也管不着。实测一轮里白占三步工具预算（`session_search`×2、`memory_search`×1）。加 `.disableMemoryTools()`；问渠的长期记忆仍归问渠自己那套。
6. **子对话卡没挂上**：`lastToolInput` 我用 `String.valueOf(map)` 存成了 `{agentId=xxx}` 这种 Java 形状，`consultTarget` 按 JSON 解析必然失败 → 卡片静默少一份问答。改 `fastjson2` 序列化。

**真机账（8099 独立实例，`chat.engineMode=agentscope`，智能体=知识库智能体，被咨询方=法律子智能体）**

- 完整回路：`stage → thinking → approval_required(consultAgent) → 批准 → 分支实跑（`subagent_step`×5，日志「全循环分支完成 branch[法律子智能体 @1]」，46 秒、6 步检索、回报 1891 字）→ `agent_consulted{id,name}` → 父轮正文 479 帧 → `usage`/`retrieved`/`done`。
- `done.toolCalls` 里 consultAgent 那张卡带 `subChat{agentId,agentName,question,answer(1345 字),artifacts,elapsedMs}`——刷新后那一问一答仍点得开（与手写侧同一形状同一长度尺）。
- 正文如实转述了《劳动法》第 41/44 条的口径，并说明是「咨询来的结论」；禁用记忆工具后本轮只剩 2 张工具卡（此前 8 张里有 3 张是框架自带记忆在空转）。
- `sources=0` 一条照旧是本机语料事实（§11.1），且日志显示咨询来源被**父轮相关度门**滤掉 6 条——门在按父轮问题判，这是既有语义，不是 agentx 缺陷。
- 测后复原并回查 DB：`chat.engineMode=legacy`、`chat.agentscopeTurnTimeoutSeconds=0`、`chat.sseTimeoutMs=300000`、`agent.consultTimeoutMs=120000`、`agent.subagentTimeoutMs=240000`、`chat.approvalTimeoutMs=120000`、`agent.maxToolSteps=50`；八条探针会话按 `deleted=0` 计数为 0；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰。

**校验**：`mvn -B verify` 21/21 绿、`npm run check` 与 SSE 契约三处对齐、schema 与 `defaults()` 一致。

**仍未做**：`run_code` 的脚本内子调用回程；`thinking_done`；引用完整性自检（`CitationIntegrityService#check` 绑 `TurnState`，要先做一次不伤主链路的抽取）；`DeepThinkService` 提示策略与「已压缩上下文」留痕对齐；手动 `/compact` 接框架压缩；待办跨重启落库；Phase 5 双跑逐场景冒烟。

## 22. 引用完整性防线接进框架（2026-10-14，§11.1 那条「CitationIntegrity 未接」收口）

四道检查（图片错配剔除、有来源却没标 `[N]` 的 fail-loud、越界角标剔除、`[图片N]` 越界剔除，外加可选的 LLM 语义一致性自检）历来只认 `TurnState`。框架引擎没有那份状态袋，于是把它真正需要的东西抽成一个 `Ctx`：

- `CitationIntegrityService.Ctx`：会话号/用户/本轮问题/生效模型 + 引用池（sources、imgIndex、imgDescIndex）+ 降级登记（degradations、degradedCodes）+ 分段计时（stageMs、startTime）。
- **手写侧留一个转发口**：`check(String, TurnState)` 原样搬字段调新方法，`ChatService` 那两处调用点一行没动——抽取的风险只落在下刀那一下，不落在行为上。
- 框架侧在 `finish()` 落库**之前**跑检查，然后 `pool.applyCitationOutcome(outcome)` 回写：**语义自检会裁来源并重编编号**，不回写就出现「面板显示 [3]、正文指的是 [2]」这种错位。
- 停止轮不跑检查：那句「本轮已停止」不是答案，跑了就是对一次中断白报一条「回答未标注引用来源」。

**双引擎真机对照（同一台 8099、同一问题、中途 PUT 切 `chat.engineMode`，无需重启）**

问题被特意写成「每句话后面紧跟 `[1]`、`[2]`」——本机引用池为空（`c_ai_knowledge_child` 无行，§11.1 那条语料事实），所以这两个角标必然悬空，正好验防线在不在位。

| | 帧序 | 正文 | `degradations` |
| --- | --- | --- | --- |
| agentscope | stage/warn/thinking×84/token×25/usage/retrieved/done | 「1+1 等于 2。 这是一个最基础的算术事实…」——**两个角标都没了** | `invalidCitation`「已移除 2 处无效的引用标注（编号超出来源范围）」 |
| legacy | plan/stage×4/subagent×4/retrieved/usage/token×18/done | 「1+1 等于 2。这是一个最基本的加法运算结果。」 | 同一条 `invalidCitation`（外加 `keywordDegraded`、`noHit` 两条语料事实） |

两侧 `retrieved` 的 `terms/keywords/refs` 完全一致，`usage` 都在，降级 code 与措辞逐字相同——**这道答案质量防线不再因为换运行时而缺席**。

**对照里看到的一条真实差异（记下来，留给 Phase 5）**：legacy 这一轮还发了 `plan`×1 与 `subagent`×4，框架侧没有——那是手写侧的「按消息意图自动开启计划」与编排分支计数，与本轮的防线无关，属 #7 双跑要逐项判定「该不该平移」的范围；这一刀刻意没动它。

**校验与复原**：`mvn -B verify` 全绿；探针会话（含两条 cite-*）按 `deleted=0` 计数为 0；`chat.engineMode=legacy` 等七项配置回本机原值并回查；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰。

**仍未做**：`DeepThinkService` 提示策略与「已压缩上下文」留痕对齐（框架自带压缩写的是 Session Log，问渠的分隔线认的是消息行 `tokens.summaryUntil`——两套摘要体系的归属要先定，手动 `/compact` 接哪一边取决于这个决定）；`thinking_done`；`run_code` 子调用回程；待办跨重启落库；Phase 5 双跑逐场景冒烟。





## 18. 落库这一轮（写了，未验，用户放行）

发现：框架引擎此前**只发帧不落库**——`c_ai_message` 里一条都没有，界面上点进去是空会话。已按 `SessionService.appendMessage` 同一入口补上：问题即时落库、结束时落助手消息（正文/思考/引用来源/工具调用清单/模型/turnId），停止时带 `TurnState.STOP_SUFFIX`；`done` 帧回带 `messageId/userMessageId/turnId`。

**这一条只做到"编译通过"，没有真机验过**（用户明确放行：会话就这样）。所以 §15/§16 那些验收表里"帧跑通"是实测，"库里能看到"不是。

## 19. 一处施工须知：新配置键要打到带新 schema 的实例

`chat.engineMode` 这类新键，跑着旧代码的实例（本机 8090）会整笔拒绝保存（`这些项系统不认`）——于是"我把开关改回去了"这件事**在旧实例上做 API 调用是做不成的**，最后只能直接改库。留档：验证开关类改动，必须打到加载了新 `config-schema.json` 的实例（我起的 8099），或直接查 `c_ai_config` 实值。

当前 DB 实值 `chat.engineMode=legacy`（2026-10-14 22:52），8099 已停。

## 23. run_code 与脚本子调用回程（2026-10-14，工具九族收口）

`RunCodeSubCallRuntime` 原先只认 `TurnState`，所以框架挂不上这把工具——不是脚本跑不了，而是「可调用清单、扇出面板、身份与档位」这些本轮事实没有地方放。抽出 **`RunCodeTurn`**（本轮身份三元组 + `TurnRunBoard` + 可调用清单 + 当前通道 + 基础工具上下文），两侧各造一份、**共用同一条子调用管线**：

- `prepareSubCallPipeline / runCodeBridge / startRunBoard / buildFanOut / stopRunBoardTicker` 改吃 `RunCodeTurn`，`TurnState` 版全部留成薄转发（问答侧调用点一行没动）。
- 框架侧新增 `AgentxTurnContext implements ToolTurnContext`：脚本子调用走的仍是问渠那条守卫链（`ToolPipelineFactory.wrap`），所以叫停闸、权限档位、引用登记、MCP 来源注册、输出增量都在它里面；`execute` 外罩身份与本轮检索覆盖的重放。
- 引擎在 run_code 转 running 时开面板、转终态时停面板并把 `fanOut` 与 `run` 挂上卡片——与手写侧同一份数据、同一个作用域（一次运行，不是整轮）。
- 至此九族**全部**挂完，`engine.sandbox.run_code` 那条降级也删掉了。

**三条只有真机能教我的事**

1. **这把工具默认就是关的**：`tool.runcode.enabled=false`，本族还有一道「审批档=每次问我时不挂」（脚本扇出 20 次就是 20 张卡，"逐项确认"这个承诺在脚本形态下兑现不了）。要验它必须先开开关、并把那一位智能体的 `tool_approval_mode` 设成 `auto`——否则框架 Toolkit 里压根没有 `run_code`，看到的"没接入"其实是"没启用"。
2. **我把「会不会问人」判错了对象**：第一版按框架的 `PermissionMode` 判（`DEFAULT ≠ DONT_ASK` 就算会问人），于是把每一把登记为「执行前需确认」的工具都从脚本清单里剔掉——**清单直接空了**：`calls=0`、卡片 `fanOut.items=[]`、模型老实说「本轮沙盒侧没有挂载任何工具」。判据应该是**问渠那条审批档**（`auto/ask/off`）；而 ask 档在装配前就被本族拦掉，所以这里正常情况下只是兜底。改完清单立刻变成 25 把。
3. **弹性线程这条坑第四次出现**：脚本的子调用是容器打回平台回调线程的，`RequestUser` 与本轮 `retrieval.*` 覆盖不在那里——不重放就是「子调用查不到东西且不报错」。`AgentxTurnContext#execute` 用同一个 `asCaller` 罩住。

**真机账（8099；`chat.engineMode=agentscope`、`tool.runcode.enabled=true`、智能体审批档临时 `auto`、`sandbox.callbackBaseUrl` 指向 8099；沙盒 provisioner 在 8002 起着）**

- 装配侧：`[P3B] 本轮子调用通道就绪：25 个工具，上限 50 次`。
- 脚本侧：一段 `run_code` 调 `write_file` 写 `note.txt`（「熔断阈值 3200 次/分钟」）→ `read_file` 读回 → 打印 OK 与字符数。
- 面板与卡片：`runcode_progress` 帧 ×2（实时扇出）；终态卡片带 `fanOut{total:2, used:2, items:[write_file done, read_file done]}` 与 `run{kind:ok, exit:0, ms:2065, calls:2, maxCalls:50}`；日志 `run_code 完成 exit=0 子调用 2 次 耗时 2065ms`。
- 正文如实复述读回内容与 16 个字符，没有编造。
- 复原并回查：`tool.runcode.enabled=false`、`sandbox.callbackBaseUrl=http://host.docker.internal:8090/ai`、`chat.engineMode=legacy`、智能体 `tool_approval_mode=NULL`（原值），其余超时项与 `agent.maxToolSteps` 均为本机原值；`rc*`/`probe*`/`cite-*` 探针会话按 `deleted=0` 计数为 0；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰。

**校验**：`mvn -B verify` 21/21 绿、`npm run check` 与 SSE 契约三处对齐。

**仍未做**（不留模糊）：`DeepThinkService` 提示策略移植；压缩留痕归属与手动 `/compact` 接框架压缩；待办跨重启落库；Phase 5 双跑逐场景冒烟与 Phase 6 放量。（`thinking_done` 与计划定格帧在 §24 收口，同一天。）

## 24. 收通道这条漏项 + `thinking_done` 与计划定格帧（2026-10-14）

**最要紧的一条不是新帧，是老 bug：框架轮发完 `done` 从不收通道。**
`RunChannel#complete()` 一直没被调用——手写侧是 `runChat` 自己 `completeEmitter`，框架引擎忘了。表现很骗人：**帧全都到了、正文也齐了，但连接挂着不看门狗 300 秒超时不收**（真机日志：`done` 在 01:20:32，通道 01:25:20 才判「本轮问答超时截断」）。修完同一问题同一实例：**整轮 9.8 秒收流**。
这条也说明前面几刀的验收表都被它遮蔽过：那些「流结束」的时刻其实是 `curl --max-time` 或看门狗掐的，不是干净收流——帧序对得上，但「这一轮什么时候算完」一直是错的。前端等五分钟才把气泡从「进行中」落地，用户读到的是卡死。

**两条补齐的留痕**

- `thinking_done`：框架把思考当流内块，**没有「思考阶段结束」这件事**，而前端这一帧的职责是「停转圈 + 折叠 + 定稿那份思考全文」。只能在两处补：第一个正文块到来时、或整轮收尾时（只思考没出正文/被叫停那种），载荷 `{"thinking": 全文}` 与手写侧同形。
- `plan_cancelled` / `plan_superseded`：框架只认 approve/reject 两态，而问渠界面上是四种终态（批准 / 退回重出 / 继续对话-本版作废 / 取消本轮）。做法：`FrameworkSessions.respondPlanApproval` 把界面终态按会话留下一份，引擎收尾 `takePlanOutcome` 取走补对应帧，并在 `done` 上挂 `planCancelled` / `planSuperseded`——**不补的表现为用户已经拒了计划，那张卡还挂着「等待中」**。控制流仍归框架判，这里只补留痕与终态标记。

**真机账（8099，`chat.engineMode=agentscope`）**

| 场景 | 结果 |
| --- | --- |
| 普通轮 | 9.8s 干净收流；帧 `stage/thinking×73/thinking_done×1/token/usage/retrieved/done` |
| 计划轮·拒绝 | `plan_approval` → `POST /plan-approval{approved:false}` → 帧里出现 **`plan_cancelled`×1**，`done.planCancelled=true` ✓ |
| 计划轮·继续对话 | 代码路径同一套（outcome=supersede → `plan_superseded` + `done.planSuperseded`）——**这一支本轮没实跑出来**，见下条 |

**一条没验到、必须写清的事实**：`planMode=true` 并不保证这一轮真走计划路径。第一次尝试（普通措辞的「帮我规划三天盘点」）模型直接正文作答，`plan_write/plan_exit` 一个没调、计划卡也没出（帧里 `plan/token` 齐但无 `plan_approval`）；换成显式指令「先用 plan_write 写出计划，再 plan_exit 交我批准，批准后一条都别执行」才出计划卡。**框架侧计划的触发因此是不稳的**，属 #5「提示策略与口径对齐」的范围（手写侧另有计划意图识别与触发判据），这一刀没动它，不能当成已通。

**校验与复原**：`mvn -B verify` 全绿、`npm run check` 与 SSE 契约三处对齐；18 条探针会话（`probe*`/`cite-*`/`rc*`/`td-*`/`t2*`/`t3-*`/`pc-*`/`ps-*`）按 `deleted=0` 计数为 0；`chat.engineMode=legacy`、`tool.runcode.enabled=false`、`agent.maxToolSteps=50` 等均为本机原值；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰；未提交。

## 25. 计划触发口径对齐（2026-10-14，§24 那条「planMode=true 不保证真出卡」的正面回答）

框架引擎原先只认用户那个开关（`sessions.setPlanMode(cmd, cmd.isPlanMode())`），所以手写侧「用户没开开关、按消息意图自动开计划」这条能力在框架下是**空的**。补法是不另立判据：问的是同一个 `PlanModeService#startIntentCheck / awaitIntent`——同一个判断器、同一条提示词、同一个 1.5s 超时（超时按不开），并且**在装配框架实例之前发起**，让这次短判定与建实例、装工具并行跑。

卡片上补 `auto: true`（与手写侧同一个字段名）：按意图自动开启的那一轮，界面上要标出「自动开启」，否则用户会以为是自己的开关点错了。

**双引擎真机对照（同一台 8099、中途 PUT 切引擎、同一问题「做一份季度巡检方案，方向我还没定」，`planMode=false`）**

| | 计划卡 | `auto` | 拒绝后 | `done` 标记 | 收流耗时 |
| --- | --- | --- | --- | --- | --- |
| legacy | `plan_approval` ✓ | **true** | `plan_cancelled` ✓ | `planCancelled=true` | 21.1s |
| agentscope | `plan_approval` ✓ | **true** | `plan_cancelled` ✓ | `planCancelled=true` | 54.6s |

两侧「该不该进计划」「卡片标不标自动开启」「拒了怎么定格」这三件事行为一致。

**顺带修了一条迟到待办的悬卡路径（改法清楚，但没再复现，按推断记账）**
上一轮对照里框架侧收尾时出了 `ask_user`×1 + warn「有工具在等你确认，答完会自动接着跑」，紧接着 `done` ——实际是**轮已经收卡还挂着**：用户答了没有活的循环去接续。根因是任务判「结算」与待办出现之间没有原子边界，而收尾前只复查了 150ms 那一次。改法：待办非空就不许 `break`，再等一拍（等下去也有 `approvalTimeoutMs` 封顶，不会无限挂）。重跑这一轮没再出现 `ask_user`，所以**「这条路径已经关掉」是按构造推出来的，不是实测到的**——留话在这里，Phase 5 双跑时专门造一次末尾提问来验。

**两条已知没平的帧（写清不藏）**

- `plan_delta`：手写侧计划是一段段流式写出来的（实测 235 帧），框架侧的计划全文来自 `plan_write` 的入参、一次性给——卡片内容一致，但**没有「逐字写出计划」那个过程感**。要平它得看框架是否有 plan 增量事件（30 类里没有 `plan/delta`），否则只能在 `plan_approval` 出现时补一帧全文——那会让正文重复显示，刻意没做。
- `plan`：`ChatService:1074` 那帧是**工作流步数**列表（另一个概念），框架侧没有对应物，不发。

**另一条实测差异（进 Phase 5 的成本对账）**：同一问题 legacy 21.1s / 框架 54.6s（另一次 172s，思考帧 3234）。框架侧的 thinking 全文都算进网关输出 token，对账时必须计入，否则轮轮误报（与 `deepseek-flash 恒思考` 那条同口径）。

**校验与复原**：`mvn -B verify` 全绿、`npm run check` 与 SSE 契约三处对齐；`at-*` 等探针会话按 `deleted=0` 计数为 0；`chat.engineMode=legacy`、`chat.planAutoIntent=true`、`tool.runcode.enabled=false`、`agent.maxToolSteps=50` 均为本机原值；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰；未提交。

## 26. 框架待办落库与恢复端点（2026-10-14，#6 的前半；后半仍未做）

**做完的那半**：框架引擎的三类待办（工具确认 / 提问卡 / 计划卡）现在落 `c_ai_tool_approval`，与手写侧同一张表、同一套 `tool_name` 词（确认填真实工具名、提问填 `askUser`、计划填 `planApproval`），答复或超时都回写终态。

不落的后果是一条看得见的能力缺失：三个恢复端点（`/tool-approval/pending`、`/ask-user/pending`、`/plan-approval/pending`）都是按这张表列卡的，**框架模式下刷新页面就再也取不到那张卡**——卡还在等，界面上却什么都不剩。

`live` 的归属判据也补了：恢复端点原先按旧通道的内存表判（框架待办的唤醒句柄在 `AgentSession` 上），不改就会把框架卡一律标成「卡片在但没人能唤醒」。控制器现在用 `FrameworkSessions#isLive` 把属于框架会话的那些 id 改回 `live:true` 并带 `engine:"agentscope"`，不属于框架的原样不动（两条通道不双写同一个号，判归属也一样）。

提问卡的 `requestArgs` 直接复用下发载荷——它与手写侧 `askArgsJson` 同形（都按 `{"questions":[…]}` 解析），恢复端点不用改就能读。

**真机账（8099，`chat.engineMode=agentscope`）**

| 验的事 | 结果 |
| --- | --- |
| 出卡后查恢复端点 | `items` 列出该卡，`live=true`、`engine="agentscope"`、`turnId` 带上（**这条改动前是空列表**） |
| 批准后 | 端点回执「已批准」，DB 行 `status=APPROVED` + `resolved_at` 落时刻 |
| 等待超时（把 `chat.approvalTimeoutMs` 压到 15s 专测） | 20s 内出 `warn「确认等待超时」` + `done` 收流（不挂轮）；DB 行 `status=TIMEOUT`；**四条历史卡全部落到终态，收尾时 `status='PENDING'` 的框架行为 0** |
| 超时后不再谎报 | 顺带抓到一条：超时那一支已经把待办落成终态，收尾却还说「有工具在等你确认，答完会自动接着跑」——那是一件不会发生的事。已按 `timedOut` 关掉这句 |

**没做到的那半（不拿它当已完成）**：#6 要的是「进程重启后还能把这张卡续上」。现在只做到**卡片与归属重启后仍在**（表里有行、`live` 如实为 false），**答复无处续跑**——重建 `HarnessAgent` 需要一条「按会话再起来一轮」的入口（本轮的模型引用、工具装配、引用池、通道都得现造），这块还没写。专测重启那一跑也没测准：脚本 `kill` 之后 5 秒就“登录成功”，而那次作答返回的是「已批准」（框架路径才可能返回的成功），说明进程其实没死透——**这一跑对「重启后行为」不构成证据**，留到这里如实记着，下一刀要重做：先确证端口与进程真的没了，再验恢复端点列卡 + 作答必须明确失败（不许假装成功）。

> 这一条已经在 §27 收口（重做的那跑确证了端口清空，续跑与落库真机验过）。当时留下的「作答返回已批准却什么都没发生」正是 §27 第二跑来路要补的洞。

**校验与复原**：`mvn -B verify` 全绿、`npm run check` 与 SSE 契约三处对齐；`pv-*`/`at-*` 等探针会话按 `deleted=0` 计数为 0，框架待办行无残留 PENDING；`chat.engineMode=legacy`、`chat.approvalTimeoutMs=120000` 回本机原值；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰；未提交。

## 27. 续跑的两条来路：重启接回 + 答完没人跟（2026-10-15，#6 收口）

§26 留的那半句现在补上了。框架的会话状态本来就落在 stateStore（`user-data/agentscope/<user>/.agentscope-runtime/.../commits`），待办归属也已经落 `c_ai_tool_approval`（§26），所以「进程没了还能不能把那张卡跑完」不是做不到的事，只是缺一个入口。做完的其实是**两条**来路，第二条是第一条测出来才看见的。

**① 重启接回**（`FrameworkSessions#resume` → `AgentscopeChatEngine#resume`）：按表里的 `(userId, sessionId, turnId)` 重建常驻实例 → `respond` 同步做（控制器要当场知道这次裁决有没有接上）→ 续跑异步做（单线程 `agentx-resume`，可能跑几分钟，不能占住那次 HTTP 请求）。通道挂在收集型 emitter 上：没人读那份响应，但事件仍走同一条 `sendSseEvent`，账本、总线、落库一律不变——**不给续跑做第二套出口**。

**② 答完没人跟**（`FrameworkSessions#ensureFollowing` → `AgentscopeChatEngine#follow`）：跟随循环有墙钟上限（`chat.agentscopeTurnTimeoutSeconds`，0=沿用 `chat.sseTimeoutMs`），到点就退；退了之后卡片**仍可答复**，框架也确实会重新起一轮执行——但那时没有人在读事件、也没有人落库。三条答复通道（确认 / 提问 / 计划）答完都问一声「有人跟着吗」，没人跟就补起一个。占坑是原子的（`reserveFollower` 用 `putIfAbsent`），同一条会话不会出现两个循环对着跟（那会各落一条助手消息）；新一轮问答走 `claimFollower`（提问者是权威，不能让上一个续跑占着位）。

**这一刀顺带照出来的两个真 bug**（都不是续跑自己要修的，是它把老代码照亮了）：

1. **每一轮都把整条 Session Log 重播一遍**：`drain` 的游标写死从 0 起读，而框架的日志跨轮常驻——第二轮会把第一轮的正文与工具卡当增量再发一次。原先没抓到是因为探针每次都用新会话。修法是把**读取起点**与**播报起点**分开：读取起点按框架自己的 turnId 找本轮第一条事件（续跑那一跑里，批准之前跑掉的工具同属这一轮，从接回时刻起读会让落库的工具卡只剩半截）；播报起点是开轮/接回那一刻，历史帧只记不发（走 `SILENT_CHANNEL`）。
2. **空轮落库**：正文为空照样 `appendMessage`，历史里多一条空白助手消息（真机抓到：`char_length=0`）。按手写侧 `persistPartialAnswer` 的口径改成三段——有正文落正文；没正文但有工具卡/思考落一条带标记的半程轮（停止/超时/中断三种成因各一句实话）；两样都没有就不落。另加一条：卡片还开着、又没被判超时的时候**不落**「已中断」——那句话说的是假话（这轮并没结束），等真正的答案来了历史里还多出一条气泡。

**真机账（8099，`chat.engineMode=agentscope`）**

| 验的事 | 结果 |
| --- | --- |
| A 同会话连跑两轮（游标修复） | 第一轮 84 字、第二轮 83 字，第二轮正文里不含第一轮开头 → **没有重播**（修复前必然 ≥ 两轮之和） |
| B 出卡 → `kill -9` → **端口确证清空** → 重启 → 恢复端点列卡 | `1 条 live=[false]`（如实：进程内已无主） |
| B 答复 → 续跑 → 落库 | 10 秒内落 **1 条**助手消息、正文 131 字真实内容；卡片 `APPROVED`（§26 那一跑是 0 字） |
| C 跟随循环撞墙钟退出（`agentscopeTurnTimeoutSeconds=40`）后再答复 | 退出那一刻账本 `warn/usage/retrieved` 齐、助手消息数 **0**（不落空轮）；答复后 6 秒内**又出两张新卡并被跟随者登记**（`live` 端点取得到）；第三张答完落 **1 条** 276 字正文，三张卡全部 `APPROVED` |
| C 中途踩到的坑 | 第一次跑 C 用 `chat.sseTimeoutMs=45000` 压墙钟，但该键不在可写组里（DB 回查仍是 300000）→ 那一跑实际是「循环还活着时答复」，不构成 C 的证据。改用本轮自己新增的 `chat.agentscopeTurnTimeoutSeconds` 才压住 |

**已知边界（不吹）**

- 续跑那一跑**没有实时观众**：帧进账本与运行总线，用户要重连才接着看得见；正文一定落库，刷新就能看到。
- 多副本部署下「谁来接回」没有裁决——归属表只记会话键不记实例，两个副本同时接回会各跑一轮各落一条。今天是单机，这一条留给 #20 真上多副本时处理。
- C 那跑验的是确认卡通道的 `follow`；提问卡与计划卡走同一个 `ensureFollowing`，按构造一致，**未各自真机验**。
- 框架侧「重启后 `respond` 自己会不会把那一轮跑完」这件事：实测**会**（`PRE_CALL → PRE_ACTING → POST_ACTING → PRE_REASONING` 全在日志里），`respond` 本身就是执行命令（`SessionInbox.Command#execution()` 认 `submit/resume/respond`）。所以问渠这边补的只是「有人读它的输出并落库」，没有替框架重跑控制流。

**校验与复原**：`mvn -o -B verify` 全绿（21 tests）；`npm run check` 与 SSE 契约三处对齐（29/29/24）；`mx-*`/`rz-*`/`fw-*`/`fc-*` 探针会话的消息、事件账、待办行已删净，`deleted=0` 计数 0、全库 `status='PENDING'` 计数 0；`chat.engineMode=legacy`、`chat.approvalTimeoutMs=120000`、`chat.agentscopeTurnTimeoutSeconds=0` 回本机原值（`chat.sseTimeoutMs=300000` 未改过）；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰；未提交。

## 28. 深度思考真的下发了 + 压缩归属定案（2026-10-15，#5 收口）

§24 之后一直挂着的两项（`DeepThinkService` 提示策略、压缩留痕归属与手动 `/compact`）在这一刀收口。**两件都不是「框架自带就不用写」，各自都有一处不写就是假的**。

### 深度思考：开关在框架引擎下原先是假的

框架把思考当作模型自己会给的东西（`model/chunk` 里 `type=thinking` 的块），于是引擎侧一行思考参数都没下发——但 qwen／GLM 这一族**不显式打开就根本没有思考块**。表现就是用户勾了「深度思考」，框架引擎里什么也没发生。

补的是 `AgentscopeChatEngine#generateOptions`，判据逐条抄手写主链路（`ChatService` 生成段），不另起一套：档位取「本轮用户选的 > 模型登记的默认档」；`thinkingOn = 本轮开关 || 模型登记为 always`；开着就按厂商方言下发 `reasoning_effort` 与 extra body，且**不下发 temperature**（主流网关在思考开启时静默忽略，设了会被读成「温度已按配置生效」）；登记了档位却无方言时照手写侧同一句 fail-loud + 降级说明。框架侧另有一句踩过的坑：`reasoning_effort` 必须走 `GenerateOptions` 的原生 setter，塞进 `additionalBodyParams` 会与内部字段序列化重复，网关按重复字段拒（DeepSeek 422，手写侧实测过）。

**没移植的那半（写清楚，不当已完成）**：`DeepThinkService` 的 **prompt 模式检索计划**（先流式思考、再从思考尾部提取 `<search>` 里的精化 query 与子问题、由调用方做多路检索）**不移植**。原因不是懒：框架引擎**没有「先检索再生成」那一步**——预检索在框架里根本不存在，多路召回是模型自己按需要调 `search_knowledge`/`webSearch` 做出的（§13 起就是这个形态）。把那条提示词原样搬过来，只会让模型在正文里吐一个 `<search>` 块而没有任何人消费它。思考本身（`type=thinking` 的块 → `thinking` 帧 + `thinking_done`）框架是白给的，已经对齐。

### 压缩归属：框架那份是权威，问渠那份在这条路上没人读

先纠正一处当时的误判：`HarnessAgent` 的压缩**默认就开着**（`CompactionConfig.builder().build()`＝50 条消息或动态 token 阈值触发、逐字保留最近 20 条，`CompactionMiddleware` 挂在 `onReasoning`），所以框架引擎不是「没有压缩」，而是**一直在那儿压**——只是它的边界是框架自己的消息序列（含工具消息），而问渠那份摘要（`c_ai_session.history_summary` + `summaryUntilSeq`）在这条路上没有人读。两套各算就是两份「已压缩上下文」互相冒充。

定案：

- **滚动压缩归框架**（默认阈值 + 新增 `chat.agentscopeCompactTriggerMessages` 可调，0＝框架默认）。
- **手动 `/compact` 分家**：`ChatEngineFactory#frameworkOwns(sessionId)` 按与 `select()` 一字不差的判据分派。框架那一路不再写问渠摘要，改为 `FrameworkSessions#requestCompact`（登记强制压缩 + 丢掉常驻实例，下一轮带 `triggerMessages(1)` 重建）并返回 `{engine:"agentscope", scheduled:true}`；前端 `compactContext` 认出 `scheduled` 就只说一句「已安排在下一次提问前折一次早期对话」，不画分隔线、不报「本次腾出多少」。为什么是「下一次提问前」：框架的压缩长在推理循环之前（没有「就地压一次」的入口），而 `/compact` 要的结果本来就是「下一次提问别再背着那些旧对话」——同一件事，晚一个时刻发生。
- **分隔线在框架引擎下不画**（这就是 §24 说的「归属要先定」那个决定）：问渠那条线靠消息行的 `summaryUntilSeq` 定位置，框架的压缩边界对不上这个序号；硬映射会画在错的位置，而前端自己的注释写着「线放错位置比不放更坏」。框架压缩对用户因此是**看不见的家务活**——`/compact` 那句提示是它唯一被说出来的地方。

### 真机账（8099，`chat.engineMode=agentscope`）

| 验的事 | 结果 |
| --- | --- |
| 思考参数下发 | 日志 `[REASONING] 思考强度已下发（框架引擎）: model=…/deepseek-v4-pro level=medium effort=high extra=[thinking]`（改动前这条整条不存在） |
| 勾与不勾的对照（同会话四次） | 思考落库字数 `deepThink=on`：296 / 52；`off`：62 / 无（`thinking` 列为 NULL）。方向对，但不单调——这一族关着也自己思考，与 `deepseek-flash 恒思考` 那条同口径，**对账时必须把这段思考算进输出 token**，否则轮轮误报 |
| `/compact` 在框架那一路 | 回执 `{"engine":"agentscope","scheduled":true,"compressedTurns":0}` + 「已排到下一次提问前压缩」；`c_ai_session.history_summary` 前后都是 0 字（**没有假装压过**） |
| 下一次提问是否真压了 | 会话状态里出现 `compaction/end` 事件文件（`…/sessions/s_YWRtaW4/s_Y3At…/commits/*.json`） |

**校验与复原**：`mvn -o -q compile` 通过、`npm run check` 全绿；`dt-*`/`cp-*`/`ev-*` 探针会话的消息与事件已删净、`deleted=0` 计数 0、全库无 `PENDING` 待办；`chat.engineMode=legacy`、`chat.approvalTimeoutMs=120000`、`chat.agentscopeTurnTimeoutSeconds=0` 为本机原值，`chat.agentscopeCompactTriggerMessages` 未落库（走默认）；`user-data/agentscope` 与探针脚本已删；8099 已停；8090 未碰；未提交。

## 29. Phase 5 双跑逐场景冒烟与对齐报告（2026-10-15，#7）

同一件事在两条运行时各跑一遍（`chat.engineMode` 运行时切换，同一进程、同一模型、同一智能体），每轮记：事件名集合、正文长度、思考长度、工具卡、来源数、产物数、降级码、耗时。跑了两批共 18 轮。

### 抓到并修掉的三个真 bug（都是双跑才照得出来的）

1. **auto 档也在弹确认卡**（过度问询）。框架侧挂工具时只看了「这把工具有没有副作用」，漏了问渠那条**双条件**（`ApprovalGate` 是「智能体审批档 = ask」**且**「工具登记为有副作用」才问）。表现：`write_file`、MCP 只读工具在框架引擎下都要人点，而 legacy 同一智能体同一问题一句没问；卡片没人答的代价不是「多问一句」，是**整轮悬到确认超时、正文什么都没有**（实测框架那几轮 130–135s 全耗在等卡）。修法：判据收成 `WenquAgentxTools#asksHuman(policy)` 一处，与 `run_code` callable 名单那条同源。修后：`todo`/`mcp` 框架轮 0 张卡、14.1s/12.1s 出正文 304/156 字。
2. **引用面板被回写清空**（`WenquSourcePool#applyCitationOutcome` 的同对象别名）。引用完整性自检没裁剪来源时把**同一个 List** 原样返回，而回写做的是 `sources.clear(); sources.addAll(自己)` → 面板全空。表现：框架引擎每一轮 `sources=[]`（`plan` 轮 10 次 `search_knowledge` 命中并返回 504–672 字，引用面板却是 0 条；legacy 同一问题是 17 条），正文角标没有出处、`retrieved` 的 `refs` 恒为 0。修法：先 `new ArrayList<>(outcome.sources())` 拷一份再换。修后真机复验：框架引擎 `正文=113 来源=2`（修前同问题 `来源=0`）。
3. **跑失败只剩一条空正文的 done**。框架那一跑的失败痕迹（`AgentSession#executionError`、被拒的命令）原先不读也不发，用户看到的是「它答了个空」。上游 DeepSeek 额度耗尽（HTTP 402）把这条正好照了出来：同一问题 legacy 发 `error` 帧，框架发的是 `done + 空正文`。修法：正文为空且本轮不是「停止/等卡」时读 `runFailure(session, task)`，发 `error` + 记一条 `engine.runFailed` 降级。修后真机复验：框架侧 `事件=['done','error','retrieved','stage']`、error 帧带 402 原文，legacy 侧同样出 error。

### 对齐账（双跑观测到的词汇与行为差异）

| 项 | legacy | agentscope | 判定 |
| --- | --- | --- | --- |
| `stage`/`retrieved`/`usage`/`token`/`tool_status`/`done` | ✓ | ✓ | 一致 |
| `thinking`/`thinking_done` | 思考关时不发 | 每轮都有（网关自己思考） | **保留差异**：这一族关着也思考（与 `deepseek-flash 恒思考` 同口径），代价见下面的成本账 |
| `todo`/`artifact`/`ask_user`/`approval_required`/`plan_approval` | ✓ | ✓ | 一致（三张卡都能出、能答、能续） |
| `plan`（工作流步数） | 每轮发 | 不发 | 刻意不发：框架没有那个概念（§24） |
| `plan_delta`（计划逐字流） | ✓ | 不发 | 未移植（计划全文走 `plan_approval` 载荷给用户看）——列入遗留 |
| `process`（过程独白） | ✓ | 不发 | 未移植：那是 prompt 模式思考的产物，框架侧走原生思考（§28） |
| `subagent`/`subagent_route`/`subagent_step` | 发（本次连「写一篇文章」都走了子智能体路由） | 不发空帧 | 判据不同：问渠的拆智能体路由在框架侧已被 `consultAgent` 咨询取代（§15/P5），只在实际咨询时才有子对话留痕 |
| `error` | ✓ | **原先没有**（已修，见上第 3 条） | 修复后一致，但文案不如 legacy 友好（legacy 把 402 映射成「模型服务额度不足：请到「模型供应商」充值，或改用其他模型」）——**遗留项** |
| 降级码 | `keywordDegraded`/`noHit`（预检索副产物） | 无这两条 | 一致口径：框架引擎没有预检索这一步，关键词路与「恒空」判定都不存在（§13） |

### 成本账（同一问题，两批各跑）

框架侧明显更贵更慢，且贵在第 1 项那个差异上：`plan` 轮框架思考 **23911 字**（legacy 0，因为思考没开），耗时 120.9s vs legacy 166.7s（这轮框架反而快，因为 legacy 在 20 次检索上花了时间）；`S1-plain` 框架 4.0s/思考 363 字 vs legacy 10.0s。**放量前必须把思考字数算进每轮账单**，否则 Phase 6 的用量对账会轮轮误报（与 §26 那条同一口径）。

### 这批没验成的（如实记，不拿别的轮次冒充）

- **`stop`（叫停）**：两侧都在 14s 收到停止（日志 `用户停止本轮（前置阶段）` / `客户端断开或已停止，终止本轮问答（生成前）`），但探针自己在 240s 内没在同一条流里读到终结帧——**观测形态问题，不是行为结论**；P0 那一刀的真机停止账仍算数。
- **`detach`（断线重连）**：legacy 侧接流看到了 `snapshot`/`usage` 但没有 `done`；框架侧在断线后 2s 就看到 `done`（正文 0，因为那一跑被宽限中止）。**这条要单独补一刀专测**，不算已验。
- **`consult`（咨询另一位助手）**与 **`run_code`**：这两轮正好赶上 **DeepSeek 上游 402 额度耗尽**，两侧都没有可用回答——**用例作废**，不是引擎差异。咨询与 run_code 的既有真机账在 §21/§23，不重复计。
- **免登录分享、工作流对外端点**：这一批没排进去，仍是空白。

### 补账（同日第二轮，改用另一家供应商跑）

DeepSeek 之后换 `5fc11a01…/glm-5.3`（阿里百炼）继续跑，结果分三段：

| 用例 | 结果 |
| --- | --- |
| 402/403 错误文案 | 框架侧现在与 legacy 同一组判据与文案：额度耗尽发 `模型服务额度不足：请到「模型供应商」充值，或改用其他模型` + 降级码 `modelQuotaExhausted`（DeepSeek 402 与百炼 403 `Free quota exhausted` 两种现场都实测到）。为此给 `ModelQuotaGuard` 补了英文文案（`free quota exhausted`/`quota exhausted`/`free quota`）——**这条在框架侧生效、legacy 侧仍出通用句**：Spring AI 的异常里不带网关响应体原文，`allTextOf` 收不到那句话。也就是说这是 **legacy 侧的既有缺口**，不是本次平移引入的，留作问答侧单独一刀 |
| `consult` 咨询 | 两侧都跑通：legacy 正文 228 字（帧 `agent_consulted`/`subagent`/`subagent_step`），框架 288 字（帧 `agent_consulted`/`subagent_step`）。差异只剩一帧：`subagent` 那一帧 legacy 每轮都发（连「只回一句话」那轮都发），框架侧不在没有子对话的轮发空帧——按 §29 前面的判定保留差异 |
| `run_code` | 两侧都跑通：`runcode_progress` + `tool_output` + `tool_status` 三帧齐，正文 125/172 字。**上一批「被 402 作废」的那两条结论就此补上，§21/§23 的账不再是孤证** |

### 第三轮：`stop` 与 `detach` 收口（同日，造了个假慢上游）

真实供应商要么秒答、要么没额度，拿不到「一整轮还在跑」这个观测窗口，于是按 `scripts/fake_upstream_no_tools.py` 的同一路子临时造了一个**慢流式假上游**（`127.0.0.1:7998`，250 块 × 120ms ≈ 30 秒整轮），并临时登记成一个供应商与 chat 模型（`tool_capable=0`，跑完即删）。断言一律走两处：**那条流里看到了什么** + **库里落了什么**（上一批只盯流，才把「停止」误读成没验成）。

| 用例 | legacy | agentscope |
| --- | --- | --- |
| `stop`（20s 时叫停） | 回执 `stopped:true`；落库 1 行 61 字，尾部 `⏹ 已停止本轮，以上为已生成的部分` | 修复前：**库里 0 行**（问出去了却什么都没有）；修复后同一形状——175 字半程正文 + 同一句停止标注 |
| 叫停后不发 `done` | 流尾是 `usage`，没有 done | 同样没有 done |
| `detach`（10s 掐连接） | 后台跑完并落库 250 字；`/chat/resume` 接回 197 帧（`snapshot`/`token`/`done`） | 后台跑完并落库 250 字；接回 191 帧（`snapshot`/`token`/`done`/`retrieved`） |

**这条修的是什么**（真 bug，非观测问题）：框架引擎的落库正文原先取 `lastAssistantText(session)`——「会话里最后一条有正文的助手消息」。被叫停的那一跑在 Transcript 里**没有 committed text**，这一取在单轮会话上是空（库里 0 行，实测到的形状），在多轮会话上就是**拿上一轮的答复给这一轮存档**。修法：`Recording` 顺手累计本轮实际流出的正文（与发出的 `token` 帧同源），落库优先取这一份、为空才回退到会话那一条；半程轮的标注按成因分三句（停止／等待超时／中断），且**追加**在半程正文之后而不是顶替它。顺带两处收紧：等待超时与叫停的半程轮不再跑引用完整性自检（那句标注不是答案，跑了对它白报一条「未标注引用」）。

**回归确认**：同一会话先正常跑一轮（250 字，尾部是正文不是标注），第二轮叫停（175 字 + 停止标注）——两轮内容各自独立，没有串轮。叫停后不发 `done` 与 legacy 一致，且是问答侧的既定设计（前端「停止生成」先 abort 再调 `/chat/stop`，`stopTurn` 补 `completeEmitter` 收通道），**不当 bug 补**（见 P0 那一刀的三条坑）。

### 下一刀的前置（Phase 6 放量门槛）

`chat.engineMode=ab` + `chat.engineAbPercent` 已经做成配置项（§20）。放量前还剩两件（错误文案与 stop/detach 都已收口）：① 免登录分享与工作流对外端点还没排进双跑；② 思考字数进用量台账（否则灰度一开，账单对不上会被读成「框架引擎在偷烧钱」）。另有一条**问答侧既有缺口**顺手记在这里：legacy 的流错误分类看不到网关响应体原文（Spring AI 包装后文案丢失），所以百炼 403 在 legacy 仍是通用句——不属于本次平移引入，留给问答侧单独一刀。

## 30. Phase 5 收尾：游客入口、问答台账、工作流那条路（2026-10-15）

§29 还剩三件，这一刀把它们一次排掉。上游两家的额度都空着，所以入口类用例改用**假慢上游**（同 §29 第三轮的办法：临时登记 provider+模型，跑完删）。

### 免登录分享（游客入口）双跑：通

同一份分享（临时把它的 `model_ref` 指向假上游，跑完还原），两侧各跑两轮（第二轮沿用同一 `sessionId`，验访客身份那条线）：

| 观测点 | legacy | agentscope |
| --- | --- | --- |
| 首轮流帧 | 72 帧，事件 `stage/plan/token/usage/retrieved/subagent/done` | 72 帧，同集合（`plan/subagent` 那两帧是 legacy 每轮都发的空帧，框架不发——§29 已判定保留） |
| `done.finalContent` | 60 字 | 60 字 |
| 会话落库 | 2 行／71 字 | 2 行／71 字 |
| 同会话第二轮（访客续问） | done ✓ | done ✓ |
| `/share/{token}/history?visitorId=…` | 4 条 | 4 条 |

也就是说：访客 uid 派生（`share-visitor:{随机}`）、发布者身份装载、能力白名单收窄、访客接流归属这些**入口侧的东西与运行时无关**，切引擎不掉任何一环。

### 问答台账：分派切了运行时，运营侧的数会悄悄少一半（已修）

`c_ai_qa_log` 那行是看板、坏例列表、「未命中问题」三处的数据源，原先只在手写侧的 `doOnComplete` 里写（`ChatService:3034`）。框架引擎没接——不报错、不空屏，只是**框架那部分流量整批不进台账**。这种缺失的形状决定了它必须修：运营看到「坏例变少了」会以为是好事。

接在同一处时机（`finish()` 落库之后、`done` 之前），字段按 legacy 那份口径给齐（命中 docId 列表、有无引用、耗时、深度思考、messageId、agentId）；`rewrittenQuery` 框架侧没有查询改写这一层，按原问题填——留空会让「未命中问题」列表里这一类条目显示成空白，填原问题至少读得通。半程轮不记（那是中断轮的收尾语义，与 legacy 一致）。

真机账：游客入口那一跑两侧各落 1 行台账（`c_ai_qa_log` 按会话回查 id 在），修前框架侧为 0 行。

### 工作流那条路：绑了图的智能体不该进任何一条推理循环（已修）

读代码照出来的，不是观测出来的：`ChatflowBridge` 的口径是「绑定了工作流的智能体整条不走常规检索/生成，回答由 end 节点出参给，人设/库界/工具都不参与」。而分派点只看「引擎选哪个」——于是 `chat.engineMode=agentscope` 一开，这张图被整个跳过，用户配的是「图的答案」，拿到的是模型自己答的一句。

修法是把这件事实在分派点之前：本轮的智能体绑了图就直接走手写侧执行体（那条路里才有 `chatflow.runWorkflowChat(...)` 那一段），没绑图的才按配置选引擎。判据读取失败时按「没绑」处理并 fail-loud——猜错的两种后果里，「图被跳过」是看得见的错，「该灰度却没灰度」只是少一半流量。

真机账（`engineMode=agentscope`，把一个智能体临时绑到一个不存在的 workflow id 上）：流里出现 `stage:正在执行工作流…` 与 `error:工作流不存在`，日志落在 `[WORKFLOW-CHAT] 工作流执行被拒绝`，而同一时间窗内 **`[agentx]` 行数=0**——框架引擎一行没跑，这条分派确实被绕开了。绑定跑完还原（`workflow_id` 回 NULL）。

**校验与复原**：`mvn -o -B verify` 全绿（21 tests）、`npm run check` 与 SSE 契约三处对齐（29/29/24）；假供应商与模型两行、临时绑定的 `workflow_id`、临时改过的 `share.model_ref` 全部还原并回查原值（`wf_binding=NULL`、`share_ref` 回 `5fc11a01…/qwen3.8-omni-flash`）；探针会话（含两枚游客会话）的消息/事件/台账行删净、`deleted=0` 计数 0、`qa_probe_rows=0`；`chat.engineMode=legacy`；8099 与 7998 端口清空；`user-data/agentscope` 与探针脚本已删；8090 未碰；未提交。










## 31. 生成量对账与思考字数进台账（2026-10-15，Phase 6 前置的最后一件功能项）

§29 的成本账说过框架侧的思考量「必须计入否则轮轮误报」——这一刀把那句话变成代码。做的时候顺手把落库的**空列**一起补了，因为它们和这条对账共用同一批数据。

**① 暗截断对账接到框架侧**。判据不复制第二份：`TurnRecorder` 里那两个常量（`OUTPUT_LOSS_MIN_TOKENS=150`、`OUTPUT_LOSS_CHAR_PER_TOKEN=0.15`）原先锁在私有方法里，收成 `TurnRecorder.looksLikeOutputLost(finalRoundOutput, accountedChars, realUsage)` 一处公开判据，手写侧自己改走它，框架侧调同一个方法。「已交代字符数」= 正文 + **思考字符** + **工具入参字符**——少算思考，deepseek 那族关着思考也回 reasoning 就会轮轮误报；少算工具入参，「写文件动辄几百 token」的正常轮会被判成内容丢失。

**② 思考字数进台账**：`tokens.thinkingChars`。放量时「框架引擎多烧的那一截」到底是思考还是正文，就看这一列；不列出来就只能靠猜，而猜错的方向是「以为框架在偷烧钱」。

**③ 落库的空列补齐**（读代码照出来的，与 ① ② 同一批数据）：框架的 `appendMessage` 在 `retrieved`、`artifacts`、`tokens` 三列上一直传 `null`。后果是三件各自安静的事——刷新后「搜索 N 个关键词，参考 M 段资料」那行整块消失；产物卡片随消息落库那半没有了；这一轮的 token 账没有了（容量面板与对账都读它）。另外工具条目此前不带 `args`：前端刷新后的工具卡、咨询卡取「被咨询那一位的名字」、以及 ① 里的工具入参字符数都读这一列，缺它等于三处一起退化。

### 真机账：造了个「可控假上游」把判定钉死

`127.0.0.1:7997`（临时登记 provider + 三个模型，跑完删）。三个模型名分别把 `usage.completion_tokens` 报成诚实值、虚高值、以及第一轮只吐 `tool_calls`——这样「该报的必须报、不该报的不能误报」就不是碰运气，而是被造出来的现场。每条都同时看**流里**与**落库那一行**：

| 现场 | agentscope | 判读 |
| --- | --- | --- |
| `fake-ok`（流 11 字、usage 报 30 tok） | 降级 `[]`，`tokens={…output:30, finalOutput:30, thinkingChars:9…}`，`retrieved={refs:0,keywords:1,…}` | 不误报 ✓，两列都落上了 ✓（修前三列 `null`） |
| `fake-lost`（流 16 字、usage 报 4000 tok） | 降级 `[outputLost]`，正文尾部 `⚠️ 这次回答不完整，有内容在生成过程中丢失，建议重新生成` | 该报的报了 ✓ |
| `fake-lost` 同一现场走 legacy | 降级含 `outputLost`，**尾部标注逐字相同** | 两条运行时同一把尺 ✓（这才叫判据收成一处） |
| `fake-tool`（第一轮 `tool_calls`） | 工具卡落库 `tc=[{name:currentDateTime,…,"args":"{\"format\":\"yyyy-MM-dd\"}"}]`，`thinkingChars:18`（两轮各 9），不误报 | `args` 进列 ✓，思考跨轮累计 ✓ |

顺带一条如实记下的观察：`fake-tool` 那轮工具状态是 `error`——假模型给的参数结构与真实工具对不上，属夹具本分，不影响这轮的三条断言。

**没做的**（不留模糊）：`timeline` 与 `related` 两列框架侧仍是 `null`（时间线快照与「接下来可以」的随消息落库）——它们是另一件独立的事，排在放量之后；`plan` 列（已裁决的计划卡随消息落库）同理，§24 只收了实时帧那一半。

## 32. 推荐块 <related> 不再印在回答里（2026-10-15，任务 #9 的第一半）

起因是排「助手消息还有几列是 null」这件事时读出来的一条更重的：主链路的系统提示词里带一句「回答末尾用 `<related>建议1|建议2|建议3</related>` 输出 N 条下一步建议」（`ChatService.relatedPromptLine()`，条数按 `retrieval.relatedCount`）。模型照做之后，主链路那侧有一套跨 token 的流式剥离协议把它从正文里剥出来变成前端那排「接下来可以」。**框架引擎既没提示也没剥**——两头都不占，于是「相关推荐」这条能力在框架档下整块不存在；而如果只补提示不补剥离，用户读到的就是标签原文印在回答里。

三处改动：

1. **提示词出处收成一处**：`RelatedPrompt.line(n)`（`agent/chat`），`ChatService.relatedPromptLine()` 改为委托它，框架侧 `sysPrompt` 追加同一句。谁提示了就得谁负责剥，两处各抄一份必然一份先漂。
2. **`RelatedStreamFilter`（agentx）**：状态机吃正文增量、返回此刻可下发的部分，跨 chunk 切开的开标签（实测把 `<rel` / `ated>` 分在两块里）与闭合标签都能接住；扣留规则与手写侧同一条——**只扣「确实是标签真前缀」的尾巴**（最长 9 字符），其余立即下发。老写法固定扣 19 字符会让正文恒滞后一个标签长度，而工具卡/产物这些旁路事件即时下发，实时时间线就错序断词，这个坑不复现。`<process>` 块一并剥进过程列。
3. **落库与 done 补上 `related`/`processText`/`agentName` 三列**（`agentName` 取不到就回 null 不编名字：续跑线程没有请求身份，归属校验会把智能体判成不可读）。护栏同手写侧一条：推荐块里的长条目按正文并回回答并记 `relatedHoldsAnswer`——实测过模型把整段正文写进 `<related>` 里，"剥掉推荐块"等于把回答吃掉。

### 真机账（可控假上游 7996，模型名 `fake-rel`）

现场刻意把开标签切成两块（`"…<rel"` + `"ated>怎么共享知识库|产物能下载吗|…"`），并留一段尾巴在闭合标签之后：

| 观测点 | agentscope | legacy（同一现场） |
| --- | --- | --- |
| 流里是否出现标签原文 | **否**，正文＝`问渠是个 AI 平台。还有尾巴吗` | 否，逐字相同 |
| `done.related` | `["怎么共享知识库","产物能下载吗",…]` | 同集合 |
| 消息行 `related` 列 | 落库 ✓（修前为 null） | 落库 ✓ |
| 闭合标签之后的尾巴 | 归回正文 ✓ | 同 |

一条如实的保留：那道「长条目并回正文」的护栏这次**没被触发**——夹具里那条"长条目"实际没过 80 字阈值，两侧都当芯片处理（两侧行为一致这点本身是对齐的证据，但护栏那一支等于没验，不能声称已验）。

**没做的**：`plan` 列这一刀写了代码但**未验成**（见 §33）；`timeline` 仍是 null。

## 33. plan 列（已裁决计划卡随消息落库）——代码到位、真机未验（2026-10-15）

框架侧现在的动作：`announcePending` 出计划卡时把那一版计划草稿按序记进 `Recording.planVersions`，收尾取到裁决（approve/supersede/reject）后组一份 `{plan,status,versions,auto}`（字段与手写侧 `PlanModeService.planSnapshot` 同名）随助手消息落 `plan` 列，并补进 `done` 的 `planRecord`。未裁决（卡还挂着）时不落，仍由待办恢复端点重建那张等着的卡。

**为什么记作未验**：真实供应商两家额度都空着，改用可控假上游造 `plan_exit` 调用时，框架的流式解析把假响应判成 `empty completion (no text, no tool call)`（试了两轮 delta 形状都没让假网关吐出框架认可的
工具调用增量），计划卡压根没出现，于是这条落库路径没被走到。`mvn -o -q compile` 通过 ≠ 验过——留着下一步用有额度的模型跑一轮计划模式，回查该会话助手消息行的 `plan` 列与刷新后的卡片。

**这一条又试了三次，都停在同一个点，把死路记下来省得下回再走**（2026-10-15 同日续）：假上游分别用 ① `plan_exit` 单块带 `index`、② 同上去掉 `index`、③ 先 `plan_enter` 再 `plan_write` 再 `plan_exit` 的三轮剧本，三次框架都是 `POST_REASONING | empty completion (no text, no tool call) — ReAct loop will terminate`。

对照证据：§31 那个假上游用**同一套 delta 形状**调 `currentDateTime` 是成功的（工具卡与 `args` 都落库了）。所以形状不是问题，**问题在 `plan_*` 这一族**：`HarnessAgent` 把 `plan_enter/plan_write/plan_exit` 注册进了 Toolkit（日志可见），但 `planMode=true` 那一轮模型真去调它们时被丢成空补全——两条运行时在这一族上行为不同（手写侧没有这三个工具，计划卡走问渠自己的 `PlanModeService`），框架侧要么计划模式没真的进入、要么这三个工具在计划位之外被拒。

**下一刀从这里接**（两条路任选，都比继续调假上游便宜）：
1. 用有额度的模型跑一轮 `planMode=true`，看 `AgentTraceMiddleware` 里有没有 `PRE_ACTING | name=plan_*`——有则卡片会出现，直接回查 `plan` 列收口；没有则要去核 `sessions.setPlanMode` 与框架 `enterPlanMode(userId, sessionId)` 的键是否对得上（`RuntimeContext` 用 `"guest"` 兜底的那处不一致是首要嫌疑）。
2. ~~若确认框架侧计划模式在常驻实例上根本进不去~~ —— **这条已被证否**（同日第四跑，加了 `FrameworkSessions#setPlanMode` 之后的 `isPlanModeActive` 留痕）：
   - 日志 `[agentx] 计划模式已开启？key=admin|p3-32763 active=true` → 计划模式**真的进了**；
   - 假上游看到的工具 schema 里 `plan_enter/plan_write/plan_exit` **三个都在**；
   - 但模型（假的）返回 `plan_write` 的 `tool_calls` 增量后，框架仍是 `empty completion`，且**没有第二次带工具结果的模型调用**——工具调用在解析/中间件这一层就被丢了，不是「没进计划模式」也不是「工具没挂」。
   
   收窄后的下一步（比继续调假上游便宜的路）：拿有额度的真模型跑一轮 `planMode=true`。真模型走的是框架自己下发的 schema（参数名一定对得上），若 `PRE_ACTING | name=plan_write` 出现而假上游不出现，那这就是**假上游的参数名/形状不被 harness 内建工具接受**（内建工具的 schema 校验比我们的适配器严），属夹具边界而不是引擎缺陷；反之若真模型也丢，才要去核 `PlanModeMiddleware` 对工具调用的过滤。

顺带一条已核过的安心账：`appendMessage` 的 22 个位置参数已逐位对齐（20=plan、21=turnId），落错列这种静默事故排除；`planRecord` 的构造与 `done.planRecord` 也在同一处，真机一到就能一并验。

`timeline` 是主链路在流式过程中按锚点切段的快照列，框架侧要补得把同一套切段在建流时重建一遍（前端「这一条回答怎么跑出来的」另有账本按 `turn_id` 回放那条路，所以缺它不致于点不开，但刷新视图与实时视图的切法会不一致）；`plan` 列是「已裁决的计划卡随消息落库」，§24 只收了实时帧那一半。两条都排在这里，不留成静默 null。

### 补的回归网（`RelatedStreamFilterTest`，6 例）

`plan` 列还差一次真机，但推荐块这条已经有常驻回归网了：开/闭标签各被切成两块、普通一字不扣、开标签来了闭标签没来、长条目并回正文、`<process>` 独立成列。

写第一版时就抓到一件事值得记：**「长条目」那条夹具本身只有 78 字，测的是夹具而不是护栏**——与 §32 真机那次「没过 80 字阈值」是同一个错法。现在断言里把长度钉死（`assertTrue(longIdea.length() > 80)`），下次谁把夹具改短，测试当场告诉他测了件别的事。

`plan` 列的位置参数对齐也按签名逐位核过（`appendMessage` 22 参：…17=related、18=mentions、19=historyRefs、20=**plan**、21=turnId），落错列这种静默事故先排除掉；剩下的只是「真机跑一轮计划模式」那一步。

**校验与复原**：`mvn -o -B verify` 全绿（**27 tests**，含新增 `RelatedStreamFilterTest` 6 例）、`npm run check` 与 SSE 契约三处对齐（29/29/24）；`fake-rel` 与 `fake-plan` 的 provider+模型行全部删除并回查 `0`，探针会话（`rl-*`/`pl-*`）的消息/事件/台账删净、`deleted=0` 计数 0、全库无 `PENDING`；`chat.engineMode=legacy`；7995/7996 与 8099 端口清空；`user-data/agentscope` 与脚本删净；8090 未碰；未提交。


---

### 交接清单（暂停时的全部未闭项，按「一句就能做」排序）

| # | 未闭项 | 卡点 | 下一步动作（含判据） |
| --- | --- | --- | --- |
| 1 | `plan` 列（已裁决计划卡随消息落库）代码到位但**未真机验** | DeepSeek 402、百炼 403——两家真模型都无额度；假上游的 `plan_*` 调用被 harness 丢成空补全（`isPlanModeActive=true` 已证否「没进计划模式」） | 换上有额度的模型跑一轮 `planMode=true`：日志出 `PRE_ACTING \| name=plan_write` → 批准卡片后回查该助手消息行 `plan` 列 + 刷新后计划卡；仍不出 → 才查 `PlanModeMiddleware` 的工具过滤 |
| 2 | `timeline` 列在框架侧为 null | 要在建流时重建锚点切段（主链路那份是流式中算出来的） | 先定值不值得：前端「这条回答怎么跑出来的」另有按 `turn_id` 回放账本那条路，缺它点得开，但实时/刷新切法会不一致 |
| 3 | 「长条目并回正文」护栏的**真机**那一支 | 单测已覆盖（`RelatedStreamFilterTest` 6 例），真机夹具当时没过 80 字阈值 | 顺手带：任何一次真模型计划/推荐轮里，造一条 >80 字的推荐条目看是否并回正文并记 `relatedHoldsAnswer` |
| 4 | `stop` 后不发 `done`、两侧一致 | —— | **不是缺陷**：主链路既定设计（前端先 abort、`stopTurn` 补 `completeEmitter`），别当 bug 补 |
| 5 | Phase 6 放量（抬 `chat.engineAbPercent`） | 是产品决策，不是代码缺口 | 用 `tokens.thinkingChars` 先算清灰度预算再定比例；两批双跑账在 §29 成本账一节 |

已核过无需重做的：九族工具、四道护栏、HITL 三卡与两条续跑来路（§26–§28）、深度思考参数与压缩归属（§28）、游客分享入口与问答台账与工作流分派（§30）、生成量对账与思考字数台账（§31）、推荐块剥离与三列落库（§32 + 单测）。
