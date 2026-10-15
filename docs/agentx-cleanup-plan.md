# AgentScope 单引擎化：清理 / 保留清单（手写 legacy 退役）

> 目标终态：**只保留 AgentScope 框架引擎**。手写 legacy 运行时退役，`legacy`/`ab` 分支删除。
> 原则：**凡是框架平移后已由 AgentScope 原生接管的手写控制流 → 删除；问渠个性化能力（框架不提供等价物、框架引擎仍在用）→ 保留并标注 `[保留]`**。
> 本文件是删除前的权威边界。代码里每个保留文件顶部已有 `// [保留] ...` 注释标记（幂等补标，2026-10-15）。

## 一、删除清单（🗑️ 框架已接管的手写控制流）

| # | 文件 / 方法 | 说明 |
|---|---|---|
| 1 | `engine/LegacyChatEngine.java`（整文件） | 手写引擎薄封装；真正循环在 `ChatService.runLegacyTurn`。框架原生接管后无存在意义。 |
| 2 | `ChatService.runLegacyTurn(ChatCommand, RunChannel)` | **手写推理循环本体**（推理/工具派发/会话状态/压缩/HITL 接续），全部由 AgentScope 接管。 |
| 3 | `ChatService` 中仅供 `runLegacyTurn` 使用的私有辅助方法 | 执行时按引用逐个核（只有 runLegacyTurn 用、框架引擎不用的一并删）。 |
| 4 | `ChatService:481` `chatEngines.bindLegacy(this::runLegacyTurn)` | 去掉 legacy 绑定。 |
| 5 | `ChatService:839` 直接 `runLegacyTurn(cmd, dispatchChannel)` 分派 | 改为统一走框架引擎。 |
| 6 | `engine/ChatEngineFactory` 的 legacy/ab 分支 | 删 `legacy` 字段、`bindLegacy()`、`legacy()`、`hitsFramework()`、`frameworkHit()`、`abPercent()`、`AB_PERCENT_UNSET`；`select()`/`frameworkOwns()` 恒返回框架引擎。 |
| 7 | `config-schema.json` → `chat.engineMode`（~L529-544） | 去掉 `legacy`/`ab` 选项，默认改 `agentscope`，note 改单引擎口径。 |
| 8 | `config-schema.json` → `chat.engineAbPercent`（~L549+） | 整条目删除（仅 ab 用）。 |
| 9 | `ConfigService.java:260` `chat.engineMode` 默认 `legacy`→`agentscope`；`:262` `chat.engineAbPercent` 默认删 | 默认值收敛。 |
| 10 | `src/test/java/com/wenqu/ai/engine/ChatEngineFactoryTest.java` | 只测 `hitsFramework()`（ab 哈希分流），逻辑废弃后删除。 |

## 二、保留清单（🔒 问渠个性化共享底盘，已在代码顶部标注 `[保留]`）

框架不提供等价能力、框架引擎(agentx)/共享服务仍在用，**不得删除**。

### 引擎抽象与前端事件契约
- `engine/ChatEngine`、`RunChannel`、`RunEvent`、`RunEventType`、`SseRunEventChannel`
  —— 框架引擎经此向问渠前端发事件，前端只认这套事件名与契约。

### 问渠轮次台账 / 个性化提示词（agent.chat）
- `ChatCommand`（共享命令 DTO）、`ChatRunRuntime`
- `RelatedPrompt`（`<related>` 个性化提示词）
- `TurnRegistry`、`TurnHandle`、`TurnDeadline`、`TurnRunBoard`、`TurnSourceRegistry`、`TurnRecorder`（暗截断对账 `looksLikeOutputLost` 两边共用；已纯静态化，去掉实例态与 `TurnState` 耦合）
- `SseClientGoneException`

### 问渠检索 / 上下文个性化（agent.context）
- `RetrievalPipeline`（问渠多路召回管线）、`RetrievalScopeService`（库界收窄）
- `CitationIntegrityService`、`HistoryCompactionService`

### 问渠权限与沙箱模型（agent.permission）
- `ExecutionPolicy`、`PermissionPolicyResolver`、`PermissionPreset`
- `SandboxEnforcement`、`SandboxMode`
  —— 框架引擎经 `AgentxPermissions`/`WenquAgentxTools` 直接依赖。

### 框架侧核心资产（新增，留）
- `agentx/` 全包 + `AgentscopeChatEngine`（框架版运行时本体）。

## 三、共享编排/服务（保留，但含待清 legacy 分支）
- `ChatService`：共享编排器**不整体删**，但其中 legacy 专属分支（runLegacyTurn 及其辅助）按上表清理。
- `ChatController`：保留；`/compact` 的 `frameworkOwns` 分派单引擎后恒真，可简化（可选）。
- 其他被保留集引用的服务保留：`ToolAssemblyService`、`PlanModeService`（计划闸门链已退场，仅留意图识别 `startIntentCheck`/`awaitIntent`）、`SandboxTools`、`ToolMountContext`、`ChatflowBridge` 等。

## 四、执行前置条件（未满足勿删）
1. **框架引擎 E2E 验证完成**（真实模型跑通主问答/工具/权限/计划/待办/压缩/续跑；本轮单测+SSE 契约已过，但不能替代真机验证）。
2. `git tag pre-agentscope-cutover` 留回滚点（手写 legacy 是兜底基线，真出问题能瞬切回）。
3. 删后回归：`mvn -o test`（agentx 三类用例）+ `node web/scripts/check-sse-contract.mjs` PASS。
4. `ChatService` 清理 runLegacyTurn 时，按引用核实每个私有方法的去留，避免误删共享路径。

## 五、本轮补充清理（2026-10-15，平移完成后的 residue）

> 最初计划把 `TurnState`/`TurnChannel`/`TurnToolset`/`DeepThinkService` 列为「保留」（问渠个性化台账与深度思考）。
> 平移跑通后复核发现它们已是**根已死**的残留：引用方全在 legacy 手写链上，框架引擎路径下无任何活调用。
> 经确认，本轮**真删除**而非留开关，并顺手瘦身仍被用到的台账件。

### 删除（🗑️ 根已死残留，真删）
| # | 文件 | 说明 |
|---|---|---|
| 1 | `agent/chat/TurnState.java` | 手写轮次状态袋；`TurnRegistry`/`TurnRecorder` 等仍活的部分已改为不依赖它（状态表恒 null）。 |
| 2 | `agent/chat/TurnChannel.java` | 手写 SSE 通道；框架引擎走自己的事件契约。 |
| 3 | `agent/chat/TurnToolset.java` | 手写工具集装配；框架经 `ToolMountContext`/`ToolRegistry` 装配。 |
| 4 | `agent/think/DeepThinkService.java` | 手写深度思考编排；框架平移后无调用方。 |

### 瘦身（🔧 仍被问渠共用，去掉与 `TurnState` 的耦合）
- `agent/chat/TurnRecorder.java`：去实例态与 `TurnState` 依赖，转纯静态；保留 `consultCardSubChat` / `looksLikeOutputLost` / `OUTPUT_LOST_SUFFIX`（暗截断对账两边共用）。
- `agent/chat/ChatRunRuntime.java`：摘除 5 处 `TurnState` 分支；`startRunHeartbeat` 改静态签名；去掉 `HitlDecisionService`/`RunTokenRegistry` 注入。
- `agent/chat/TurnRegistry.java`：去恒空状态表（`state()` 恒 null）。
- `agent/chat/TurnDeadline.java`：去 `volatile TurnState state` 字段。
- `agent/chat/TurnHandle.java` / `TurnRunBoard.java` / `TurnSourceRegistry.java`：去 `TurnState` 引用与 `IMG_PLACEHOLDER_PATTERN` 等迁移到本地定义。
- `agent/plan/PlanModeService.java`：计划闸门链整体退场（出计划→批准卡→执行/退回/取代），仅留意图识别 `startIntentCheck`/`awaitIntent`；计划闸门由框架 `enablePlanMode()` 接管。
- `service/ChatService.java`：删 ~950 行死方法（`toolTurnContext`/`requestToolApproval`/`callWithRetry`/`doAskUserMulti`/`toolContext`/`enabledToolCallbacks`/`finishMounts`/`toolMountContext`/`planHooks` 等）及 32 个死字段；`interruptMarker` 相关常量在 `AgentscopeChatEngine` 改本地定义。
- `agentx/AgentscopeChatEngine.java`：`interruptMarker` 本地常量替代 `TurnState.STOP_SUFFIX`/`TRUNCATION_SUFFIX`。
- `ChatflowBridge` / `CitationIntegrityService` / `RetrievalPipeline` / `FrameworkCtxParts` / `ToolTurnContext` / `ChatRunBus` / `AgentxHumanWaitLedgerTest`：去 `TurnState` 引用与 import。
- `agentx/SpringAiToolAdapter.java`：过期文案——deny 信息去掉「或切回 legacy 运行时」。

### 验证
- 全量编译 `src/main/java` 全部源码 + 测试源码：主码 0 错误、测试 0 错误（Corretto 17，`-implicit:none -parameters`，Lombok 作注解处理器）。
- 删除面逐个核过调用点；`TurnRegistry.state()` 等静态常量仍活、已迁移而非整删。
