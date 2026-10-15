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
- `TurnRegistry`、`TurnState`、`TurnHandle`、`TurnDeadline`、`TurnChannel`、`TurnToolset`、`TurnRunBoard`、`TurnSourceRegistry`、`TurnRecorder`（暗截断对账 `looksLikeOutputLost` 两边共用）
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
- 其他被保留集引用的服务保留：`ToolAssemblyService`、`PlanModeService`、`DeepThinkService`、`SandboxTools`、`ToolMountContext`、`ChatflowBridge` 等。

## 四、执行前置条件（未满足勿删）
1. **框架引擎 E2E 验证完成**（真实模型跑通主问答/工具/权限/计划/待办/压缩/续跑；本轮单测+SSE 契约已过，但不能替代真机验证）。
2. `git tag pre-agentscope-cutover` 留回滚点（手写 legacy 是兜底基线，真出问题能瞬切回）。
3. 删后回归：`mvn -o test`（agentx 三类用例）+ `node web/scripts/check-sse-contract.mjs` PASS。
4. `ChatService` 清理 runLegacyTurn 时，按引用核实每个私有方法的去留，避免误删共享路径。