# 框架能力核实台账 · AgentScope 2.1.0-BETA1

核实时间：2026-10-18 15:23
核实方式：**解包 `sources.jar` 读源码注释为主，javap 签名为交叉验证**。不抽样、不靠类名推断、不依赖文档目录。

**证据源**（本机 Maven 仓库，非 GitHub）：
- `~/.m2/repository/io/agentscope/agentscope-harness/2.1.0-BETA1/agentscope-harness-2.1.0-BETA1-sources.jar`（294 个 java 文件）
- `~/.m2/repository/io/agentscope/agentscope-core/2.1.0-BETA1/agentscope-core-2.1.0-BETA1-sources.jar`

> 前三轮出过三次判断错误（漏查 `artifact` 子包、误判记忆不可换、误报 `Sandbox` 为 8 方法），
> 故建立本台账并留证。**任何"框架有没有 X"的结论，必须在本文件里有源码出处，否则视为未核实。**

---

## 〇、核实纪律（含本轮新增）

1. 结论必须有源码出处（类注释/方法注释/Javadoc），javap 只作交叉验证
2. **jar 里存在 ≠ 功能可用** —— 还要确认注册入口（builder 方法）与构造器依赖
3. **方法数量要数 default 方法** —— `Sandbox.shutdown()` 是 default，只算 7 个必实现
4. 不看文档目录判断存在性（文档 v2 vs 实际 BETA1 不一致）
5. 独立子包最易漏查（`artifact` 反例），清点覆盖全部包而非按需扫
6. 源码注释常含**未写进文档的行为语义**（见 §1.3 IsolationScope 并发、§4 记忆两层模型）

---

## 一、沙箱

### 1.1 生命周期（`Sandbox.java` 源码注释原文）
```
create/resume → start() → 使用 → stop() → shutdown()
1. stop(): persist snapshot only — safe for both self-managed and user-managed sandboxes
2. shutdown(): destroy backend resources — only called on self-managed sandboxes
注释强调：The distinction between stop() and shutdown() is critical
```
**必实现 7 个**：`start`/`stop`/`isRunning`/`getState`/`exec`/`persistWorkspace`/`hydrateWorkspace`/`close`
（`shutdown()` 是 `default { // no-op by default }`，**不必实现**）

### 1.2 `containerOwned` —— 框架原生支持开发者注入容器（`DockerSandboxState.java` 源码注释原文）
```java
/**
 * Whether the SDK owns the container lifecycle (create/stop/remove).
 * When {@code false}, the container was injected by the developer and will not be removed.
 */
private boolean containerOwned = true;
```
> **问渠 provisioner 正是"开发者注入的容器"** → `containerOwned=false`，框架只 `stop()` 不 `shutdown()`，不会误删容器。

### 1.3 `IsolationScope` 并发语义（源码注释原文，**修正文档理解**）
```
For sandbox mode this is sequential-reuse sharing, not live-instance sharing.
Concurrent calls at the same scope each get their own running container;
they converge on the last persisted snapshot at the end of the call.
```
| 档位 | 语义（源码） |
|---|---|
| `USER` | 同 userId 跨 session 共享，**默认**；userId 缺失降级 `SESSION` |
| `SESSION` | 按 sessionId 隔离；无 session key 时跳过状态查找、新建沙箱 |
| `AGENT` | 同 agent 全用户全 session 共享 |
| `GLOBAL` | 同 workspace/store 实例内全局共享 |

> ⚠️ **这解释了问渠的工作区共用为何不能用 `IsolationScope`**：并发同 scope 各拿独立容器，
> 末次快照收敛——与问渠「同工作区共享一个活容器 + `ProjectTurnMutex` 轮次互斥」语义不同。
> **P1 必须走 `externalSandbox` 自管生命周期。**

### 1.4 `SandboxLifecycleMiddleware` 源码注释（决定 P1 接线方式）
```
Pre-next.apply: 读 SandboxContext → SandboxManager.acquire → start → 绑定到 per-call RuntimeContext
doFinally: release(stop+可选shutdown) → 持久化状态 → 失效本轮绑定
Post-call failures (persist, release) are logged but do not propagate
The sandbox is bound per call on the invocation's RuntimeContext ... (issue #2490)
```

### 1.5 `WorkspaceSpec` 源码（问渠路径语义要映射到这里）
```
root        — 沙箱内工作区路径，默认 /workspace
entries     — 启动时物化的文件/目录，可含 bind_mount 条目
              （bind mounts 由 Docker/Kubernetes store 强制执行，不复制为普通文件）
environment — 注入到每次 exec 的环境变量
```

### 1.6 `SandboxClient` 源码注释
- `create(...)`：返回**未启动**状态，需自行 `start()`
- `deserializeState(json, snapshotSpec)` 是 default，直接委托 `deserializeState(json)`

### 1.7 `BaseSandboxFilesystem` 源码注释（**为什么 exec 是主数据面**）
```
This class provides default implementations for all AbstractFilesystem methods by delegating
to shell commands via execute(). File listing, grep, and glob use standard Unix commands.
Read uses server-side commands for paginated access. Edit uses server-side commands.
子类必须实现 4 个：execute / uploadFiles / downloadFiles / id()
```
> 问渠 `AgentSandboxRuntimeClient` 已有 `/v1/file/upload`、`/v1/file/download`、`/v1/shell/exec`
> → **几乎一一对应**，接入面比预想小。

### 1.8 后端
源码 `impl/` 目录下**只有 `docker`**（5 个文件：`DockerFilesystemSpec`/`DockerSandbox`/
`DockerSandboxClient`/`DockerSandboxClientOptions`/`DockerSandboxState`）。
`DockerSandboxClient` 注释：Creates and manages Docker containers via the **docker CLI**。
文档所述 K8s(agent-sandbox)/Daytona/E2B/AgentRun **在 BETA1 不存在**。

### 1.9 `SandboxContext` 源码
immutable，字段：`client`/`clientOptions`/`workspaceSpec`/`snapshotSpec`/`externalSandbox`/
`externalSandboxState`/`isolationScope`。随 agent 构造，挂到每次 call 的 `RuntimeContext`。

---

## 二、产物交付

| 类 | 核实内容 |
|---|---|
| `ArtifactDeliveryTarget` | `deliver(RuntimeContext, ArtifactDeliveryRequest) -> ArtifactDeliveryResult`，业务无关 SPI |
| `ArtifactDeliveryRequest` | record `(String filePath, byte[] content, String fileName, String description, boolean force)` |
| `ArtifactDeliveryResult` | `success()`/`success(msg)`/`fail(msg)`/`conflict(msg)` |
| `ArtifactDeliveryTool` | `deliverArtifact(ctx, String, String, String, Boolean)`，构造器吃 `AbstractFilesystem` + `ArtifactDeliveryTarget` |

**问渠改造**：写 `WenquArtifactDeliveryTarget` 委托现有 `ArtifactService`，
删 `SandboxTools` 里的 `@Tool`，改 `.artifactDeliveryTarget(...)`。工具名一致，提示词不动。

---

## 三、技能

`AgentSkillRepository` 完整 CRUD：`getSkill`/`getAllSkillNames`/`getAllSkills`/`save(List,boolean)`/
`delete`/`skillExists`/`getRepositoryInfo`/`getSource`/`setWriteable`/`isWriteable`
→ **用户侧增删改可交框架 `enableSkillManageTool`**。
配套：`FileSystemSkillRepository`（现成）、`SkillRegistry`/`SkillBox`/`SkillFilter`/`MarkdownSkillParser`/
`SkillToolFactory`/`DynamicSkillMiddleware`；中间件 `HarnessSkillMiddleware`/`SkillCuratorMiddleware`/
`SkillUsageMiddleware`；curator 包 23 类。
builder：`skillRepositories(List)`/`enableSkillManageTool(boolean|SkillManageConfig)`/`skillFilter`/
`enableSkills(String...)`/`enableSkillCurator`。

---

## 四、记忆（**含源码揭示的问渠没有的设计**）

### 4.1 存储层写死
`MemorySearchTool(WorkspaceManager)` / `MemorySaveTool(WorkspaceManager)` /
`MemoryFlushManager(WorkspaceManager, Model[, String])` —— 三个构造器第一个参数永远是 `WorkspaceManager`。
`MemoryConfig` 8 字段全是行为参数，**无存储后端字段**。

### 4.2 但 filesystem 可注入（`WorkspaceManager` 源码）
构造器 4 重载：`(Path)` / `(Path, AbstractFilesystem)` / `(Path, fs, WorkspaceIndex)` /
`(Path, fs, idx, NamespaceFactory)` → 记忆可挂远程实现，多副本共享。

### 4.3 两层记忆模型（`MemoryFlushManager` 源码注释，**问渠完全没有的设计**）
```
memory/YYYY-MM-DD.md — append-only daily ledger，每次 flush 追加带时间戳的小节，只由本类写
MEMORY.md            — 全局策展、去重、限长的长期记忆，只由 MemoryConsolidator 周期写，
                       在此视作只读上下文
```
`MemorySearchTool` 注释：searches MEMORY.md **and memory/*.md**，**keyword-based**（非向量）。

> **问现状对比**：`c_ai_user_memory` 单表 + `embedding` 向量检索。
> 换框架后 = 日账 + 策展两层 + 关键词检索（**向量能力丢失**）。

---

## 五、子 Agent

`SubagentsMiddleware`/`SubagentEntry`/`DynamicSubagentsMiddleware`/`TeamsMiddleware`（harness）
+ `SubAgentTool`/`SubAgentProvider`/`SubAgentConfig`（core）
+ `subagent/task/TaskRecord`（13 类）
builder：`disableSubagents`/`disableDynamicSubagents`/`externalSubagentTool`/`asLeafSubagent`/`buildSubagentEntries`

---

## 六、上下文管理

`ContextPolicy`（record：maxInputTokens/reservedOutputTokens/estimator/manifest sink）/
`ContextSource`（`load(ContextRequest) -> Mono<List<ContextBlock>>`，**问渠引用角标可走此通道**）/
`ContextBlock`/`ContextManifest`/`ContextRenderer`/`ContextSelectionPolicy`/`ContextTokenEstimator`/
`ContextBudgetExceededException`/`HarnessContextBuilder`/`WorkspaceContextMaterials`/`SourceFailurePolicy`
+ `CompactionMiddleware`/`ToolResultEvictionMiddleware`/`ToolResultEvictionConfig`/`ConversationCompactor`

---

## 七、会话与恢复

`AgentSession`（含 `InputReceipt`/`Task`）/`SessionAnswer`（approval/confirmation/text）/
`LocalSessionStorage`/`SqliteSessionStorage`/`StoreSessionStorage`/`WorkspaceSessionLogStore`
+ core `session` 包 27 类

---

## 八、计划模式

`PlanModeMiddleware`/`PlanModeTools`（plan_enter/plan_write/plan_exit）/ `workspace.plan` 目录
+ builder：`enablePlanMode`/`allowShellInPlanMode`/`planFileDirectory`
**仅单份 plan 文件，无版本链**

---

## 九、检索（八项之外，知悉即可）

`core/rag`：`KnowledgeRetrievalTools.retrieveKnowledge(query, topK, Agent, RuntimeContext)`、
`RAGMode`（GENERIC/AGENTIC/NONE）、`GenericRAGHook`、`Document`/`DocumentMetadata`/`RetrieveConfig`
问渠另有 `HybridRetrievalService`(1103) + `RetrievalPipeline`(785) + `GraphRagService`(839)，**本轮不动**。

---

## 剩余自研项（唯一）

**`run_code`**（1465 行）— 沙盒内脚本 + 回调宿主工具。
`core/tool/coding` 仅 `ShellCommandTool` + `CommandValidator`(Unix/Windows)；
`core/tool/file` 仅 `ReadFileTool`/`WriteFileTool`。**无"沙盒内脚本批量回传"语义。**

⚠️ 依赖 `sandbox.callbackBaseUrl`（容器反向访问宿主）。P1 换后端若网络拓扑变动，**须先验证回调仍通**。

另保留：计划模式多版本链。另可对接：引用角标经 `ContextSource.load()` 接入框架上下文体系。