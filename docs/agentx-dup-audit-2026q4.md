# 问渠 × AgentScope「重复自研」盘点报告

盘点范围：`/Users/wangxi/workspace/WenQu`（盘点时 HEAD = `8bfb7b1`，框架版本 `io.agentscope:*:2.1.0-BETA1`）
框架源码：core / harness 的 sources jar 解压后通读（框架无等价能力的结论都对着源码核过）。

> **本报告是「盘点当时」的快照，据此执行的结果见文末「执行结果」。**
> 报告正文里的 `ChatService` 行号、以及 `runLegacyTurn`/`runChat`/`buildAnswerStream`
> 等方法名，在随后那笔提交里已经不存在了 —— 正文保留原样是为了让当时的判断可追溯，
> 读结论请直接看文末。

---

## 0. 一个必须先说清楚的前提：手写推理循环已经不可达

这是本次盘点最重要的发现，它改变了后面所有条目的「删除风险」判断。

`ChatService.chat()` 里的分派（盘点时 `ChatService.java:831-849`）：

```java
if (workflowBoundOf(cmd.getAgentId())) {
    runLegacyTurn(cmd, dispatchChannel);        // → runChat
} else {
    chatEngines.select(cmd).chat(cmd, dispatchChannel);   // → 恒为 AgentScope 引擎
}
```

而 `runChat` 内部（盘点时 `ChatService.java:993-1009`）在真正进入推理链路**之前**就判了工作流：

```java
if (agent != null && agent.getWorkflowId() != null && !agent.getWorkflowId().isBlank()) {
    ...
    chatflow.runWorkflowChat(...);
    return;                // ← 直接返回，后面 1600 行推理代码一行都跑不到
}
```

`workflowBoundOf(agentId)` 读的正是 `agent.getWorkflowId()` 非空（`ChatService.java:490-499`），
与 `runChat` 里那个分支是**同一个判据**。因此：

> 能走到 `runChat` 的前提（工作流已绑定）与 `runChat` 里提前 return 的条件（工作流已绑定）
> 是同一个条件。**凡进入 `runChat`，必然在第 1008 行 return。**

结论：`runChat` 的第 1010–1803 行（检索编排、工具装配、`buildAnswerStream` 调用）与
`buildAnswerStream` 全体（2400–3145）**当前是不可达死代码**，不是「等着被调用的旁路」。

任务描述里说的「只被工作流旁路调用」需要修正一下措辞：工作流旁路用的是 `runChat` 的**前半段**
（身份装载 + 智能体解析 + 提前 return），推理循环本体并没有被工作流真正使用。

这带来一个重要后果：**下面第 1 条的 1650 行不是「重复自研待替换」，而是「已经死了」**。
删除它不需要先迁移工作流旁路，只需要保留 `runChat` 的 908–1009 行前导段。

---

## 一、完全重复可删（按行数从大到小）

### 1. 手写推理循环本体 —— 当前已是不可达死代码 ★最大一块

| 项 | 内容 |
|---|---|
| **自研件** | `src/main/java/com/wenqu/ai/service/ChatService.java`<br>· `runChat` 的推理段：**1010–1803，约 790 行**<br>· `buildAnswerStream`：**2400–3145，约 745 行**<br>· 合计 **≈1535 行**（另加下方 helper 约 400 行） |
| **框架对应物** | `io.agentscope.core.ReActAgent`（`reasoning()` / `acting()` 循环，`ReActAgent.java:2747` 起）<br>`io.agentscope.harness.agent.HarnessAgent`（3151 行，含 `maxIters` 步数闸）<br>`io.agentscope.harness.agent.session.AgentSession#submit/stream/resume`（`AgentSession.java:125-154`）<br>`io.agentscope.core.event.*`（38 个事件类：TextBlockDelta / ToolCallStart / ToolResultText / ExceedMaxIters…） |
| **是否真重复** | **完全重复可删。** 框架 `ReActAgent` 的 reasoning/acting 循环与问渠 `buildAnswerStream` 的「流式生成 → 工具调用 → 消息回灌 → 再生成」是同一个 ReAct 循环的两次实现；且如第 0 节所述，问渠这份当前**根本不可达**。 |
| **删除风险** | ⚠️ **看似有风险实则没有**，但必须注意：<br>① 工作流旁路依赖的是 `runChat` **908–1009 行前导段**（模型解析、智能体解析、`chatflow.runWorkflowChat` 调用），**这一段必须保留**；<br>② 删除前建议把 `chatflow` 分支从 `runChat` 中上提成一个独立方法（如 `runWorkflowBoundTurn`），让 `chat()` 直接调它，这样 `runChat` 可整体删除，代码意图也更清楚；<br>③ `runChat` 前导段里 `resolvedModel` 的解析（`modelRefFor`）被 `ChatflowBridge` 之外的路径也用到，删前需确认；<br>④ 建议先加一行日志/断言验证「工作流轮确实都在 1008 行返回」再删，避免有 `workflowId` 为空但走了别的分支的漏网路径。 |

**连带可删的 helper**（仅被上述死代码调用，已逐个核对调用点数）：

| helper | 行号 | 行数 | 仅被死代码调用？ |
|---|---|---|---|
| `enabledToolCallbacks` | 1805–1849 | 45 | ⚠️ 是（另 710 行处由 askUser 内部闭包调用，需确认） |
| `finishMounts` | 1851–1901 | 51 | ✅ 是 |
| `applyPermissionTier` | 1903–1913 | 11 | ⚠️ 1684/4334 两处，均在死代码内 |
| `instrumentTools` | 1976–1990 | 15 | ✅ 是（2527 调用点在 `buildAnswerStream` 内） |
| `toolTurnContext` | 1992–2184 | 193 | ✅ 是（1978/2416 均在死代码内） |
| `requestToolApproval` | 2186–2238 | 53 | ✅ 是（2120 在 `toolTurnContext` 内） |
| `stopRunHeartbeat` | 2240–2257 | 18 | ✅ 是（2830/2838 在 `buildAnswerStream` 内） |
| `callWithRetry` | 2259–2332 | 74 | ✅ 是（2141 在 `toolTurnContext` 内） |
| `doAskUserMulti` | 3822–3905 | 84 | ⚠️ 3912 调用点在 `toolContext`（3907，⚠️可能活） |
| DSML/related 标签解析族 | 3146–3392 | ≈247 | ✅ 是（2600-2918 全在 `buildAnswerStream` 内） |
| `rerankIfNeeded` | 218–238 | 21 | ✅ 是（1318/1339/1344 均在死代码内） |
| `rewriteQueryForRetrieval` | 4112–4168 | 57 | ✅ 是（1219 在死代码内） |

> 合计**额外约 800 行**。真正需要逐个确认的只有 `enabledToolCallbacks`（710 行的 askUser 闭包）与
> `doAskUserMulti`/`toolContext`（3912 行）——这两处要读上下文才能定。

---

### 2. 上下文滚动摘要压缩

| 项 | 内容 |
|---|---|
| **自研件** | `src/main/java/com/wenqu/ai/agent/context/HistoryCompactionService.java`（447 行）<br>关键方法：`assemble`(121)、`compactSession`(247)、`summarizeHistory`(405)、`fitFromNewest`(363)、`syncPoolSize`(103) |
| **框架对应物** | `io.agentscope.harness.agent.memory.compaction.ConversationCompactor`（784 行）<br>`io.agentscope.harness.agent.memory.compaction.CompactionConfig`（604 行：`triggerTokens`/`triggerMessages`/`keepTokens`/`keepMessages`/`summaryPrompt`/`pruneConfig`）<br>`io.agentscope.harness.agent.middleware.CompactionMiddleware`（324 行）<br>挂载点：`HarnessAgent.Builder#compaction(CompactionConfig)` / `#disableCompaction()`（`HarnessAgent.java:2040-2058`） |
| **是否真重复** | **部分重复需改造。** 压缩这件事框架已完整提供（阈值触发 + 尾部保留 + 摘要 + 工具结果剪枝），问渠这份是同一件事的第二次实现。**但不能直接删**——它还承担了两件框架不管的事：① 压缩结果落 `c_ai_session.summary` 并有前端面板（`currentSummary`/`summaryBlock`）；② 手动 `/compact` 接口（`ChatController` 注入依赖）。 |
| **删除风险** | ① 前端「历史压缩」面板与手动压缩按钮依赖其落库结果，删了要一并改前端；② `ChatflowBridge:48`、`PlanModeService:84`、`DeepThinkService:53` 三处仍注入它，删前要一并改；③ 框架压缩只活在 agent state 里，问渠的会话表看不到——**两者是「不同存储、不同生命周期」的同一算法**，切过去需要写一个从 `CompactionConfig` 到会话表 summary 字段的桥。 |

> 注：框架侧已经接了（`AgentscopeChatEngine.java:1413` `.compaction(compactionConfig(cmd))`），
> 注释明确说「手动 /compact 在框架引擎下落到下一次建实例先压」——**说明工作区方已经知道这条重复存在，且选择了并存**。

---

### 3. usage / token 统计

| 项 | 内容 |
|---|---|
| **自研件** | `src/main/java/com/wenqu/ai/util/UsageAccumulator.java`（160 行）<br>`src/main/java/com/wenqu/ai/service/DynamicOpenAiChatModel.java`（355 行，出口记账）<br>`TurnRecorder#extractCachedTokens`(669) |
| **框架对应物** | `io.agentscope.core.model.ChatUsage`（312 行：`inputTokens`/`outputTokens`/`cachedTokens`/`cacheCreationTokens`/`reasoningTokens`/`toolUsePromptTokens`/`time`）<br>`io.agentscope.core.model.ChatResponse#getUsage()`<br>`io.agentscope.core.ReActAgent.java:1781-1806`（多轮 `totalReasoning` 累加） |
| **是否真重复** | **部分重复需改造。** `ChatUsage` 的字段集**严格覆盖**问渠 `UsageAccumulator` 记的那几项（且多出 cacheCreation / reasoning / toolUsePrompt 三项）。但 `UsageAccumulator` 挂 在 Spring AI 的 `DynamicOpenAiChatModel` 上，`ChatUsage` 挂在框架的 `OpenAIChatModel` 上——**两条模型出口链不同**，换掉需要重接记账点。 |
| **删除风险** | ⚠️ `UsageAccumulator` 仍被 `UsageLedgerService:53`、`TurnState:194` 引用；`DynamicOpenAiChatModel` 仍被 `DynamicChatClientConfig:45` 注入为 `chatClient` bean，且 `ChatService` 自身构造也注入它。删 `UsageAccumulator` 必须先解掉 `TurnState.roundUsage` 这个字段（`TurnState` 已被标 `[保留]`，改动面比看起来大）。 |

---

## 二、部分重复需改造

### 4. 计划模式（人在回路）

| 项 | 内容 |
|---|---|
| **自研件** | `src/main/java/com/wenqu/ai/agent/plan/PlanModeService.java`（755 行）<br>`approvalGate`(233)、`runPlanPhase`(371)、`startIntentCheck`(148)、`planClarifyQuestions`(606)、`planVersionEntry`(306) |
| **框架对应物** | `io.agentscope.harness.agent.HarnessAgent.Builder#enablePlanMode()`（`HarnessAgent.java:2333-2340`）<br>`io.agentscope.harness.agent.middleware.PlanModeMiddleware`（324 行，`onActing` 拦截非只读工具）<br>`io.agentscope.harness.agent.workspace.plan.PlanModeManager`（`enter`/`exit`/`writePlan`/`planFilePath`）<br>`io.agentscope.harness.agent.tool.PlanModeTools`（`plan_enter`/`plan_write`/`plan_exit`） |
| **是否真重复** | **部分重复需改造。** 框架提供的是「只读设计期 + 计划文件 + 批准后放行」这套骨架；问渠在此之上加了大量业务语义：**计划版本链**（`planVersionEntry`/`findSupersededPlan`，支持"计划被改进"）、**澄清提问轮**、**计划与引用来源的 refsDigest 绑定**、**计划快照落 assistant 消息的 plan 列**。这些框架没有。 |
| **删除风险** | ⚠️ 框架引擎**已经接了** `.enablePlanMode()`（`AgentscopeChatEngine.java:1409`），且 `PlanModeService` 仍被框架引擎注入（`AgentscopeChatEngine.java:62,90`）用于 `awaitIntent`。两侧现在是**同时运行**的（框架挂 plan 工具，问渠跑自己的意图闸）。真要合并，得先决定计划版本链怎么在框架的 plan 文件上表达——这不是删代码能解决的。 |

### 5. 待办清单（todo）

| 项 | 内容 |
|---|---|
| **自研件** | `src/main/java/com/wenqu/ai/service/TodoService.java`（170 行，`@Tool writeTodo`）<br>存 `c_ai_session.todos`，整表覆盖 |
| **框架对应物** | `io.agentscope.core.ReActAgent.Builder#enableTaskList()`（`ReActAgent.java:5887-5900`，注册 `todo_write`）<br>`io.agentscope.core.middleware.TaskReminderMiddleware`（每轮提醒）<br>`io.agentscope.core.middleware.TaskContextProjection`（`todo_write` 结果投影）<br>`io.agentscope.harness.agent.HarnessAgent.Builder#enableTaskList()`（`HarnessAgent.java:1850`） |
| **是否真重复** | **部分重复需改造。** 能力对等（都是"模型写一份可勾选清单 + 每轮提醒"）。差异只在存储：问渠存会话表并有前端面板，框架存 agent state。**框架侧目前没开** `enableTaskList`（`AgentscopeChatEngine` 的 builder 里没有这一行），所以这是"框架有、问渠也有一份、且框架那份没开"——属于可直接切过去的一类，但要先做前端面板的数据源适配。 |

### 6. 子智能体 / 多智能体

| 项 | 内容 |
|---|---|
| **自研件** | `src/main/java/com/wenqu/ai/agent/subagent/`<br>· `SubAgentBranchRuntime.java`（511 行）<br>· `SubAgentConsultService.java`（275 行）<br>· `BranchMode`/`BranchReport`/`BranchRequest`/`BranchRunner`（181 行）<br>合计 **967 行** |
| **框架对应物** | `io.agentscope.harness.agent.middleware.SubagentsMiddleware`（736 行）<br>`io.agentscope.harness.agent.tool.AgentSpawnTool`（1844 行）<br>`io.agentscope.harness.agent.subagent.DefaultAgentManager`（279）、`SubagentDeclaration`（701）、`AgentSpecLoader`（443）<br>`io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository`（979，异步任务）<br>`io.agentscope.core.tool.subagent.SubAgentTool`（587） |
| **是否真重复** | **不重复（问渠特有）+ 一处需决策。** 问渠这套是「**咨询**」语义：父轮问一个指定智能体"你带着自己的知识库跑一条分支，把资料交回来"，人设/工具/权限仍归父轮（见 `ChatService.java:967-969` 注释）。框架那套是「**派生子智能体**」语义：spawn 隔离实例、异步任务、独立 workspace。**语义不同，不能直接替换。**<br>⚠️ **但有个决策点**：问渠代码里 `disableSubagents()` 是**开着**的（`AgentscopeChatEngine.java:1428-1429`，连 `disableDynamicSubagents` 一起），理由是"不关净时模型会自主派子智能体去翻工作区，与问渠自己的体系打架"。也就是说**框架的子智能体能力被主动关闭了**，问渠自研版是当前唯一实现。 |
| **删除风险** | 若想改用框架子智能体，需先解开 `disableSubagents()`，并处理"自主派生 vs 用户指定咨询"的语义冲突——工程量远大于本盘点的其他条目。**建议保持现状。** |

---

## 三、不重复（问渠特有，框架没有）

### 7. 权限判定

| 项 | 内容 |
|---|---|
| **自研件** | `agent/permission/`：`PermissionPolicyResolver`(240)、`ExecutionPolicy`(130)、`PermissionPreset`(83)、`SandboxMode`(81)、`SandboxEnforcement`(48) = **582 行**<br>`agentx/AgentxPermissions.java`（55 行） |
| **框架对应物** | `io.agentscope.core.permission.PermissionEngine`（335）、`PermissionContextState`、`PermissionMode`、`PermissionRule`、`PermissionBehavior`、`PermissionDecision` |
| **是否真重复** | **不重复（问渠特有）。** 框架的 `PermissionEngine` 是**引擎**（allow/deny/ask 三档规则求值）；问渠的 `PermissionPolicyResolver` 是**策略解析器**（从 config + 智能体配置 + 个人上限 + 游客态解出 `ExecutionPolicy`，还带 `catalog()` 给设置页渲染档位表）。两者是**上下游关系，不是同层重复**。<br>`AgentxPermissions` 已经做对了——它就是翻译层（55 行把 `ExecutionPolicy` 翻成 `PermissionContextState`），注释里明确写「翻的是档位语义，不是再造一层判定」。**这是正确范式。** |
| **删除风险** | 无。这几个文件都带 `[保留]` 标记，且被 `AgentxPermissions`/`WenquAgentxTools` 实际依赖。 |

> ⚠️ 但有一个**功能性缺口**值得单列：`SpringAiToolAdapter.checkPermissions`(90-95) 里，
> `approvalCanAsk=false` 时会直接 `deny`——注释说「人在回路还没接框架确认事件之前必须为 false」。
> 框架侧其实**已经有** `AgentSession#respond(requestId, boolean approved)`（`AgentSession.java:191`）和
> `RequireUserConfirmEvent`（`ReActAgent.java:3530`）了。这不是"重复自研"，是**框架能力已就位但还没接上**。

### 8. 工具注册与挂载

| 项 | 内容 |
|---|---|
| **自研件** | `agent/tool/ToolRegistry.java`（219 行，含启动期声明 vs `@Tool` 反射核对 `validate()`）<br>`ToolMountContext`(162)、`ToolTurnContext`(112)、`ToolInvocation`(110)、`ToolDescriptor`(71)、`ToolFamily`(81)、`ToolFamilyProvider`(94)<br>`agentx/SpringAiToolAdapter.java`（126 行） |
| **框架对应物** | `io.agentscope.core.tool.Toolkit`（1282 行：`registerTool`/`registerToolGroup`/`callTool`/`removeTool`）<br>`io.agentscope.core.tool.ToolBase`(387)、`ToolExecutor`(648)、`ToolGroupManager`(590)、`McpClientManager`(460) |
| **是否真重复** | **部分重复需改造。** `ToolRegistry` 的**声明式元数据**（族归属、中文展示名、卡片提示、是否需审批、`startup validate()` 漂移检测）是问渠产品需求，框架没有——框架的 `Toolkit` 只管注册与调用，不管展示与审批元数据。<br>`SpringAiToolAdapter` 是**必要的翻译层**（Spring AI `ToolCallback` → 框架 `ToolBase`），且注释说得很清楚：把九族重写成框架注解会造成两套工具定义漂移，而工具名正是审批/黑名单/权限的共同判据。**这个不该删。** |
| **删除风险** | 删 `ToolRegistry` 会让工具卡退回英文裸名（前端映射表里没有 `consultAgent`/`writeTodo`/`plan_*`）。 |

### 9. 会话状态与流式事件

| 项 | 内容 |
|---|---|
| **自研件** | `agent/chat/`：`TurnChannel`(58)、`TurnRegistry`(112)、`TurnDeadline`(67)、`TurnState`(369)、`TurnRunBoard`(46)、`TurnHandle`、`SseClientGoneException` |
| **框架对应物** | `io.agentscope.harness.agent.session.AgentSession`(521)、`io.agentscope.core.session.SessionLog`/`JournalSessionLog`(417)/`SessionRecorder`(613)/`SessionInteraction`/`SessionProjection`<br>`io.agentscope.core.event.*`（38 个事件类） |
| **是否真重复** | **不重复（问渠特有）。** 关键差异在 `TurnDeadline`：它的注释记录了一个框架没有的约束——「整轮预算只计**机器**时间，人在回路的等待要扣除」，且必须**边等边扣**（否则看门狗会在用户答题那几分钟里把轮次掐了，2026-10-07 实测踩过）。框架 `AgentSession` 没有"预算扣减人工等待"这个概念。<br>`TurnChannel.detached`（断线后台续跑）、`TurnRegistry`（轮级互斥 + 断线接回）也都是问渠的 SSE 传输语义。**且这批文件全部带 `[保留]` 标记，框架引擎与共享服务仍依赖。** |
| **删除风险** | 不适用。 |

### 10. 人在回路裁决（HITL）

| 项 | 内容 |
|---|---|
| **自研件** | `agent/hitl/HitlDecisionService.java`（881 行）——三张卡（提问/审批/计划）共用「挂起—恢复」管道：内存 future 阻塞 + `c_ai_tool_approval` PENDING 记录 + 刷新换设备捞回<br>`model/ToolApproval.java`(59) |
| **框架对应物** | `io.agentscope.harness.agent.session.AgentSession#respond/resume/pending`（`AgentSession.java:154-212`）<br>`io.agentscope.core.event.RequireUserConfirmEvent`、`io.agentscope.core.interruption.*`<br>`io.agentscope.core.ReActAgent` 的 `maybePatchPendingToolCalls`(2160) + `enablePendingToolRecovery` |
| **是否真重复** | **不重复（问渠特有）。** 框架的 `AgentSession` 是**进程内**的会话对象（`pending()` 从 `SessionLog` 取，进程重启即失效）；问渠的 `HitlDecisionService` 是**数据库持久化**的审批记录，能扛服务重启与多副本，还带站内通知。框架的 `enablePendingToolRecovery` 处理的是「工具调用悬空后自动补错误结果让循环继续」，语义比 HITL 窄。<br>**而且框架侧已经开了** `.enablePendingToolRecovery(true)`（`AgentscopeChatEngine.java:1422`），但 `HitlDecisionService` 仍被框架引擎注入（`AgentscopeChatEngine.java`、`FrameworkSessions`）——说明持久化那张表框架没有替代品。 |
| **删除风险** | 不适用。 |

---

## 四、总结

### A. 明确可删的大块（按行数排序）

| 排序 | 自研件 | 行数 | 依据 |
|---|---|---|---|
| **1** | `ChatService.runChat` 推理段 + `buildAnswerStream` 全体 | **≈1535** | 不可达死代码（第 0 节），框架 `ReActAgent` 已覆盖 |
| **2** | 上述死代码的私有 helper（工具挂载/审批/标签解析/重排/改写等） | **≈800** | 已逐个核对调用点，全在 #1 的死范围内；其中 `enabledToolCallbacks` 与 `doAskUserMulti`/`toolContext` 需人工确认 |
| **3** | `HistoryCompactionService` 的压缩算法部分 | **≈250**（全文件 447） | 框架 `ConversationCompactor`+`CompactionConfig` 已覆盖；**但落库+前端面板部分要留** |
| **4** | `UsageAccumulator` | **160** | 框架 `ChatUsage` 字段严格覆盖；**但要重接记账点** |

**1 + 2 合计约 2300 行**，且 **#1/#2 不需要迁移工作流旁路**（旁路用的是 `runChat` 908–1009 前导段），
只需把 `chatflow` 分支上提为独立方法即可让 `runChat` 整体消失。这是本次盘点投入产出比最高的一项。

### B. 灰色地带 —— 建议先问用户

| 事项 | 问什么 | 为什么需要问 |
|---|---|---|
| **① 工作流旁路怎么安置** | 是否接受把 `runChat` 的 908–1009 前导段 + `chatflow` 分支抽成独立方法（如 `runWorkflowBoundTurn`），从而让 2300 行整体删除？ | 技术上可行，但会改动 `chat()` 的分派结构。**这是唯一挡住 2300 行的决策点。** |
| **② 手动 `/compact` 与压缩面板** | 框架压缩只写 agent state，问渠的压缩结果要落 `c_ai_session.summary` 并在前端可见。是接受"框架压缩 + 桥接落库"，还是保留问渠这套并存？ | 工作区方在 `AgentscopeChatEngine.java:1411-1413` 已经选了并存。**需要确认这是长期决定还是过渡态。** |
| **③ 计划版本链** | 问渠的计划支持"生成→澄清→改进→被新计划取代"的多版本链，框架的 `PlanModeManager` 只有单份 plan 文件。是接受功能退化，还是保留问渠的版本链？ | 两侧**现在同时在跑**（框架挂 `plan_*` 工具 + 问渠跑意图闸），需要先定架构再谈删。 |
| **④ 计划模式双跑** | `enablePlanMode()` 已开、`PlanModeService` 也注入着，模型可能同时看到两套计划机制。**这可能是个待修的 bug，不只是重复问题** —— 建议优先排查。 | 属于"框架已提供但接法有问题"，而非"自研待退役"。 |
| **⑤ 框架 HITL 通道** | `SpringAiToolAdapter` 里 `approvalCanAsk=false` 导致需确认的工具被直接 deny（注释说"接框架确认事件之前必须 false"）。但 `AgentSession.respond` 已经存在了。**要不要现在接上？** | 这不是删代码，是补一个缺失接线。补上后审批类工具在框架路径下可用。 |
| **⑥ 遗留文件清理** | `DynamicOpenAiChatModel`(355) + `DeepThinkService`(318) + `ChatRunBus` 等在框架路径下是否还有活的调用者？ | 本次未逐一穷尽（非 `ai/agent` 目录），可能藏着与 #1 同类的死代码。**需要的话我可以再做一轮专项扫描。** |

### C. 建议保持现状（明确不重复）

`agent/permission/`（582 行）、`HitlDecisionService`（881 行）、`agent/chat/` 轮次台账族（≈650 行）、
`ToolRegistry`（219 行）、`SpringAiToolAdapter`（126 行）、`agent/subagent/`（967 行）——
这些要么是框架的上游/下游、要么承载了框架没有的业务语义（人工等待预算扣减、DB 持久化审批、
工具展示元数据、「咨询」语义子智能体）。

**其中 `agent/subagent/` 值得单独强调**：框架的子智能体能力是**被问渠主动关掉的**
（`disableSubagents()`，`AgentscopeChatEngine.java:1428`），且两边语义不同（派生 vs 咨询）。
任何"把子智能体也切到框架"的想法都需要先做语义对齐评估，不属于本次重复自研的范畴。
---

## 五、执行结果（据本报告执行的那笔提交）

工作区方定的方针是「框架有的能力直接用，不保留自研手写；**就算要自研，也要在新框架上自研**」。
据此执行，四条决策点的落定如下：

| 盘点里的决策点 | 落定 | 实际做法 |
|---|---|---|
| ① 工作流旁路怎么安置 | **抽独立方法，整体删除** | `runChat` 前导段（身份/模型/作答方/@咨询/降级）+ chatflow 分支抽成 `runWorkflowBoundTurn`；`runLegacyTurn` 薄壳随之删除。**`ChatService` 4468 → 2101 行**。另外连带删了盘点漏掉的 `runNoKnowledgeChat`（唯一调用者就是被删的 `runChat`）、32 个零调用私有方法、25 个失效 import。 |
| ③ 计划版本链 / ④ 计划模式双跑 | **不是双跑，保留现状** | 核实：框架路径下 `PlanModeService` 只被用 `startIntentCheck`/`awaitIntent`（判这轮要不要先出计划卡），`approvalGate`/`refsDigest` 压根没被调用；框架 `enablePlanMode` 管 `plan_*` 工具 + 计划位拦截。**是分工不是重复**。已在 `enablePlanMode()` 处写注释钉住，防后人当 bug 去"修"。真合并的前置条件是「计划版本链怎么在框架的单份 plan 文件上表达」。 |
| ⑤ 框架 HITL 通道 | **未接，留待后续** | `SpringAiToolAdapter` 里 `approvalCanAsk=false` 仍导致需确认的工具被直接 deny，而 `AgentSession.respond` 已存在。这不是删代码，是补一段缺失接线。 |
| 任务清单（原报告列为"可切框架"） | **存储迁框架，工具面留问渠** | 新增 `service/TodoBridge`：唯一真源改成框架 `AgentState.tasksContext`，问渠 `writeTodo` 不再落 `c_ai_session.todos`（两个真值源迟早对不上）。护栏（≤12 项/单项截断/同时只允许一项 doing）**保留在问渠**——那是呈现约束，框架不管而清单卡是用户直接看的界面。**没开 `enableTaskList()`**：开了会同时注册 `todo_write` 并由 `TaskReminderMiddleware` 每轮教模型 `in_progress/completed`，与问渠工具教的 `doing/done` 冲突。 |

另两件与本报告同批做的：

- **上下文容量分类重划**（`agentx/FrameworkCtxParts`）：手写侧那十类靠「拼装 system 时的分段位置」
  切分，框架引擎拿不到那些标记；改为按 AgentScope 自己的 `MsgRole`（SYSTEM/USER/ASSISTANT/TOOL）
  + 内容块类型分 7 类。顺带删了 `budget` 字段（legacy 检索配额，框架下从无计算）。
- **② 手动 `/compact`**：维持并存（框架压缩 + 问渠落库可见），本批未动。

**C 组「建议保持现状」的六项全部维持不动**，理由见上表与报告正文。

> 未在真机复测（需重启后端）。重点两项：① 工作流驱动的智能体能否正常答（抽方法时动过那条路径）；
> ② 右栏任务清单卡刷新后能否回显（框架 state 跨请求持久化依赖 `chat.agentscopeStateDir` 或 Redis）。
