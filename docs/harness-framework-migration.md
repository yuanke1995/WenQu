# 问渠 Harness 能力全框架化 · 迁移清单

决策：**八项能力全部换成 AgentScope 框架**，不留老代码、不做兼容、不双轨、删表删面板。
保留：引用来源角标（`TurnSourceRegistry` + `CitationIntegrityService`）。

目标版本：`agentscope 2.1.0-BETA1`（已解包核对，非文档所述 v2）。
**框架能力存在性的唯一依据：`docs/harness-framework-audit.md`（源码级核实台账）。**
本清单里凡涉及"框架有没有某能力"的判断，均以该台账为准；台账里没有的，视为未核实。

---

## 0. 前置事实（已核实，不需再查）

| 项 | 事实 |
|---|---|
| 沙箱后端 | 源码 `impl/` 下**只有 `docker`**（5 个文件），文档里的 K8s/Daytona/E2B/AgentRun 在 BETA1 均不存在 |
| 接入点 | `SandboxClient` 6 方法 SPI + `SandboxContext.Builder.externalSandbox(...)`，自建后端是官方支持的路径 |
| 容器所有权 | `SandboxState.containerOwned=false` 表示"开发者注入"，框架只 `stop()` 不 `shutdown()` → 不会误删 provisioner 容器 |
| 技能 | `AgentSkillRepository` 有完整 CRUD（`save`/`delete`/`isWriteable`），`FileSystemSkillRepository` 现成 |
| 子 Agent | 主线**零实现**（grep 零命中），开启即得，无迁移成本 |
| 角标 | `TurnSourceRegistry` 仅耦合检索链路（6 文件），与沙箱/技能无交集，保留零成本 |

---

## 执行状态（2026-10-18 复核 + 补完）

**结论**：P0 / P1 / P1-c / P2 / P4 已落地；P3 首轮只做完一半，本轮补完并修掉三处回归。

两处**前提订正**（照原话执行会漏刀）：

1. 「`schema.sql` 里没有技能表/记忆表的建表语句」——**错**。四张表的 `CREATE TABLE` 都在 `schema.sql`
   里（`c_ai_user_memory` 原 :150，技能三张原 :542/:559/:566）。后果实测过：16:45 手工 `DROP` 之后
   16:47 重启，四张表被 schema.sql 原样重建（0 行挂着当僵尸表）。本轮已删净四条建表语句，并在文件
   末尾加「退役清理」段写显式 `DROP TABLE IF EXISTS`——本文件由 `spring.sql.init` 每次启动逐条执行，
   所以幂等，新老库一起干净。
2. 「`containerOwned=false` 必设」——**做不到，也不必要**。该字段只存在于 `DockerSandboxState`，
   抽象 `SandboxState` 没有它。框架不删容器的实际依据是 `Sandbox#shutdown()` 为 default no-op 且
   自建实现**不覆写**——本轮即按此实现（`delete()` 同样只记日志不真删）。

三处**与原清单写法不同**的落地方式（都有源码或真机依据，非偷工）：

| 项 | 清单写法 | 实际落地 | 依据 |
|---|---|---|---|
| P0-3 | 去掉 `disableDefaultWorkspaceSkills` | **保留该开关** | 框架注释：它只跳过 Layer-4「工作区默认技能集」，与问渠技能体系无关；去掉等于让两套清单并存 |
| P1-5 | 改 `.filesystem(...)` | 改 `.abstractFilesystem(...)` | 真机踩出来：框架文件工具在 **build 期**绑定文件系统，往 `RuntimeContext` put 无效（`execute("pwd")` 返回宿主目录＝隔离静默失效） |
| P3-a6 | 内置技能从 classpath 迁到 workspace 目录 | 启动时把 `classpath:skills/**` **落盘**到 `user-data/skills-builtin/` | 原写法在容器部署下读不到（`Dockerfile` 运行阶段只有 `app.jar`，技能正文打在 jar 里）；落盘是同一目标的最小实现 |

本轮补完的**回归与新发现**（真机验收见本节末）：

1. 智能体配置页整页打不开（P3-a4/a5 删了后端没解前端引用）→ 新增只读 `GET /api/ai/skill/available`，
   `api.js` 九个写端点导出删除，三处调用点改用只读端点并让技能那一路非致命。
2. 「本轮指定技能」选了不生效（`ChatCommand.skills` 零消费者）→ 引擎按点名注入正文，与工作流技能节点
   共用 `skill.maxFileChars` 同一条截断口径。
3. 容器部署下内置技能会静默为空 → 上一条的落盘方案；同时引擎不再自己 `new` 仓库（那会绕过
   `wenqu.skills.*` 配置），改注入 Spring bean。
4. 「自动提炼记忆」开关变哑（写库无人读）→ 建实例时按「平台总闸 AND 个人开关」落
   `MemoryConfig.flushTrigger(never)`，改偏好后标脏常驻实例。
5. 记忆与技能面板留下的死配置一并删除：设置页去掉 `memory.maxPerUser` / `maxInjectCount` /
   `injectBudgetChars` / `dedupThreshold` / `useSemanticInject` / `platformEmbeddingRef` /
   `skill.injectMaxChars` / `skill.remoteAllowedHosts` 八项（`memory.enabled` 与
   `skill.maxFileChars` 保留并接上真实消费者），本地库的孤儿行同步删除。
6. `run_code` 回调可达性（P1-11）实测通过，并订正其工具说明与手册：脚本模块里**没有**沙盒文件读写
   与产物交付（那几把已交框架、不在问渠工具表里），能并发扇出的只有检索/联网/算数这类。

**遗留收口（2026-10-18 19:35）**：上一版这里挂的三条（技能范围死配置、记忆落点随沙箱、
`skill-projections` 无写方）已按「接上而不是退役」处理完，改法与边界见 §6。

---

## P0 · 子 Agent（先做，验证框架开关行为）

**为什么排第一**：改动最小（删 2 行）、无迁移成本、且是后续两项的验证底座——先确认「框架默认开启的旁路能力」在问渠的提示词与工具表下不会打架。

| # | 动作 | 文件 |
|---|---|---|
| P0-1 | 删 `.disableSubagents()` `.disableDynamicSubagents()` | `AgentscopeChatEngine.java:2166-2167` |
| P0-2 | 保留 `.disableDynamicSkills()`（技能在 P3 才换，此处先锁死避免半开） | 同上 `:2168` |
| P0-3 | 去掉 `disableDefaultWorkspaceSkills` / `disableWorkspaceContext` 的**同时**核对提示词：框架 PLAN banner 会主动把沙箱路径喂给模型 | 同上 `:2169-2170` |

**验收**：起一轮对话，模型不再自主派子智能体翻工作区；问渠自研技能体系未被框架的自动加载污染。
**回滚点**：单文件单行，恢复成本≈0。

---

## P1 · 沙箱接 SandboxClient SPI（工作量最大，依赖 P0）

**现状**：`com.wenqu.ai.sandbox` 4300 行 + `service/SandboxTools` 525 行，框架侧 `.disableFilesystemTools()` `.disableShellTool()` 已关。

**做法**：不删自研内核，把它包成框架的自建后端。

| # | 动作 | 说明 |
|---|---|---|
| P1-1 | 新建 `ProvisionerSandboxClient implements SandboxClient<ProvisionerSandboxClientOptions>` | 6 方法：`create`（返回**未启动**状态）/`resume`/`delete`/`serializeState`/`deserializeState`(+重载)。结构照抄 `DockerSandboxClient` |
| P1-2 | 新建 `ProvisionerSandbox implements Sandbox` | **必实现 7 个**：`start`/`stop`/`isRunning`/`getState`/`exec`/`persistWorkspace`/`hydrateWorkspace`/`close`。`shutdown()` 是 default no-op，**不实现** |
| P1-2b | `ProvisionerSandboxState extends SandboxState`，**`containerOwned=false`** | 框架注释：*"When false, the container was injected by the developer and will not be removed"* → 框架只 `stop()` 不 `shutdown()`，不会误删 provisioner 建的容器 |
| P1-3 | `ProvisionerSandboxClientOptions extends SandboxClientOptions` | `getType()` 返回 `"provisioner"`，`createClient()` 返回上面那个 |
| P1-4 | `SandboxState.sessionId` 位存 `sha256(uid:scopeId)` | 替代框架默认的 UUID sessionId |
| P1-5 | `AgentscopeChatEngine` 去掉 `disableFilesystemTools`/`disableShellTool`，改 `.filesystem(...)` | |
| P1-6 | **不使用 `isolationScope`** —— 走 `SandboxContext.externalSandbox` 自管生命周期 | 框架注释：*"sequential-reuse sharing, **not live-instance sharing**; concurrent calls at the same scope each get their own running container"*，与问渠「同工作区共享一个活容器 + `ProjectTurnMutex` 轮次互斥」语义冲突 |
| P1-7 | `persistWorkspace`/`hydrateWorkspace` 映射到现有 bind mount 语义 | 框架默认 tar 快照与现有「宿主目录 bind，容器销毁文件仍在」不同，**不要**引入快照上传 |
| P1-8 | `exec` 补 `cd /home/gem/user-data &&` 前缀 | `ProvisionerSandboxBackend.inWorkspace()`（:615）已存在，迁移时必须保留，否则相对路径文件丢 |
| P1-9 | `SelfHealingRuntimeClient` 自愈信号并入 `exec` 异常路径 | 认 `sandbox not found`/connection refused，摘缓存重建重试一次 |
| P1-10 | `WorkspaceSpec.root` 设为 `/home/gem/user-data` | 框架默认 `/workspace`；问渠的虚拟前缀与授权根要映射到这个 root |
| P1-11 | **验证 `run_code` 回调仍通** | 依赖 `sandbox.callbackBaseUrl`（容器反向访问宿主）。换后端若网络拓扑变动，run_code 会表现为"每个子调用超时" |

> `BaseSandboxFilesystem` 注释：子类只需实现 `execute`/`uploadFiles`/`downloadFiles`/`id` 四个，
> 且文件列表/grep/glob 走标准 Unix 命令、read/edit 走服务端命令 → 问渠 `AgentSandboxRuntimeClient`
> 已有 `/v1/file/upload`、`/v1/file/download`、`/v1/shell/exec`，**接入面比预想小**。

**保留不换**（框架确无对应，决策保留自研）：
- `run_code` — 沙盒内批量跑脚本 + 回调宿主工具，框架无此协议。见 §5.2。
  ⚠️ 依赖 `sandbox.callbackBaseUrl` 反向可达，**P1 换沙箱后端时须一并验证回调仍通**。
- 计划模式多版本链 — 见 §5.2

**已改用框架**：`deliver_artifact` — 初版误判框架没有，实为 `ArtifactDeliveryTarget` SPI，见 §5.1 / P1-c。

**验收**：`execute`/`read_file`/`write_file`/`edit_file`/`ls` 五把走框架路径；跨会话共用工作区仍成立；容器带外删除能自愈；相对路径执行不丢文件。
**风险点**：P1-7 弄错会丢工作区文件；P1-8 弄错相对路径落容器内。

---

## P2 · 引用角标（**核实后取消：前提不成立**）

原计划两条（覆盖框架新工具名、补沙箱文件类引用）**已核实为伪需求**，2026-10-18 16:32撤销。

核实结果：问渠角标只有三路来源，注册入口仅三个——
`TurnSourceRegistry#register(WebSearchResult)` 联网搜索、
`#registerMcpCitations(toolName, result)` MCP 结果、
以及库内检索（`RetrievalPipeline` 那条）。
**沙箱工具从来就不产生角标**：沙盒产物走产物区（`ArtifactService` + `deliver_artifact`），
不进引用池。P1 把沙箱工具换成框架实现后这一点没有任何变化。

因此「角标工具名映射需覆盖 grep_files/glob_files/list_files」不成立——
那三把工具本来就没有角标可渲染；「补沙箱文件类引用」同样不成立——
角标编号空间里从来没有这类引用可补。

**保留 `TurnSourceRegistry` + `CitationIntegrityService` 原样不动**（用户口述要保留的那一项），
它与沙箱/技能/记忆三条线均无耦合。

**验收**：引用角标在沙箱工具产出答案时仍正确显示来源，可点开。

---

## P3 · 技能与记忆全换文件系统（删四表删两个面板）

### P3-a 技能

| # | 动作 |
|---|---|
| P3-a1 | `.disableDynamicSkills()` 删除，`.enableSkills(...)` 或 `skillRepositories(...)` 接入 |
| P3-a2 | 用 `FileSystemSkillRepository` 落到 workspace 目录，**删** `c_ai_user_skill` / `c_ai_builtin_skill_override` / `c_ai_skill_disabled` 三表及其 mapper/model |
| P3-a3 | 用户侧增删改交框架：`.enableSkillManageTool(true)`（`AgentSkillRepository` 自带 CRUD 与 `isWriteable`） |
| P3-a4 | **删** `SkillService`(646) / `SkillController`(235) / `SkillTools`(37) / `SkillToolProvider`(59) |
| P3-a5 | **删** `web/src/views/SkillPanel.vue` 与 `api.js` 技能相关导出 |
| P3-a6 | 内置技能从 classpath `resources/skills` 迁到 workspace 目录 |

### P3-b 记忆

框架落点：`MemorySearchTool`/`MemorySaveTool`/`MemoryFlushManager` 构造器吃`WorkspaceManager`，
后者构造器 `(Path, AbstractFilesystem)` —— **文件系统可注入**，多副本可挂远程实现。

| # | 动作 |
|---|---|
| P3-b1 | 去掉 `.disableMemoryTools()`，`.disableMemoryHooks()` 视自动提炼需求定|
| P3-b2 | `WorkspaceManager` 指向按 uid 隔离的 workspace 根，`readMemoryMd(ctx)` 承担注入（替代 `UserMemoryService.injectText`） |
| P3-b3 | 自动提炼走 `MemoryConfig.flushTrigger()` + `MemoryFlushManager`（替代 `UserMemoryService.maybeExtract`，:244） |
| P3-b4 | **删** `UserMemoryService`(410) / `UserMemoryMapper`(12) / `UserMemory`(55) / `MemoryController`(64) |
| P3-b5 | 清`ChatflowBridge.java:47` 的 `userMemoryService` 注入与相关调用 |
| P3-b6 | **删表** `c_ai_user_memory`（复核订正：`schema.sql` 里**有**建表语句，必须连着删语句 + 写显式 `DROP TABLE`，只删数据或只删语句都会下次启动复活） |
| P3-b7 | **删前端**「长期记忆」面板：`ProfilePage.vue:120/290/332/542/754/766` + `api.js:1206-1214` 四个 API |

**已接受的三处降级**（换框架的必然结果，不是缺陷）：
1. **向量检索丢失** —— 现有 `embedding` 字段做语义去重；框架 `MemorySearchTool` 注释明确是 **keyword-based**，
   搜 `MEMORY.md` + `memory/*.md`。另框架是**两层模型**（`memory/YYYY-MM-DD.md` 日账 append-only +
   `MEMORY.md` 策展去重限长），问渠是单表，结构也变了。
2. 存储形态从「按 uid 隔离的库」变为「按 uid 隔离的文件」（隔离性仍在，走 RuntimeContext）
3. ~~用户失去手动管理界面~~ —— **P5 已补回**：`/profile → 我的记忆` 支持查看/编辑/清空 `MEMORY.md`、
   按天查看/删除日账、按上限裁剪；存储仍是框架文件，不建表、不双写。日账只读。

**P5 记忆收口（2026-10-19，本次补）**：
- 游客/分享/MCP：`buildAgent` 按 `guestMode` 同时 `disableMemoryHooks + disableMemoryTools`，不注入也不写入发布者记忆；
- 子智能体/咨询轮：`disableMemoryHooks`（只读），不 flush、不跑后台合并，事实由主轮统一提炼；
- `memory.enabled` 等平台配置变更 → `FrameworkSessions#markAllConfigStale`，常驻实例下一轮重建；
- `MEMORY.md` 硬上限落在 `MemoryCappedFilesystem`（写入落盘时裁剪），面板可手动「按上限裁剪」；
- 框架主模型/记忆/压缩统一走 `MeteredModel` 记 `c_ai_usage_log`（kind=chat/subagent/memory/compress）；
- 账号删除时清理 `user-data/agentscope/{uid}`；多副本接 `RedisDistributedStore` 管节流/watermark，
  记忆文件仍需共享卷。

**验收**：技能与记忆面板消失；技能经框架工具读取正常；问答后记忆自动沉淀到 `MEMORY.md` 且跨会话读得到；不同 uid 之间不串。
**风险点**：P3-a6 / P3-b7 数据迁移失败 = 用户资产全丢，**动手前必须先备份数据库**。

---

## P4 · 上下文管理补齐（收尾）

| # | 动作 |
|---|---|
| P4-1 | 手动 `/compact` 接到框架 `ConversationCompactor`（现注释写明「手动 /compact 落到下一次建实例先压」，非真压缩） |
| P4-2 | `maxContextTokens` / `compaction` 阈值回归框架默认口径，删本地偏离配置 |

### P4-a 手动 /compact 改为就地压一次（已实施并真机验证）

`FrameworkSessions#compactNow(userId, sessionId)`；`ChatController#compactSession` 框架分支改调它。

两条路：
- **进程内持有活实例**：`agent.getCompactionHook()` 那条链上的压缩器就地压 → `ReActAgent#updateAgentState(ctx, "manual_compact", mutation)` 写回。native 模式走 `SessionStateAccess.update`（writer lease + `context/replaced` 语义）。
- **无活实例**（进程重启 / 从未在本进程答过）：回落 `requestCompact` → 排到下一次 open 时带 `triggerMessages(1)` 强制压。

**修正一条此前的错误判断**：本文件早先写的「框架没有把已有会话就地压一次的入口」是错的。框架自己在 `HarnessAgent#forceCompactAndRetry` 里就是「取 state 上下文 → `compactIfNeeded(forceConfig)` → 写回 `contextMutable()`」，且 `CompactionMiddleware` / `ConversationCompactor` / `TokenCounterUtil` 全部 public。

返回字段与手写侧同形（`compressedTurns` / `freedTokens` / `summary` / `summaryTokens`），前端 `compactContext` 无需改结构；`scheduled=true` 仍表示回落分支。两个「没得压」分支（上下文为空 / 压不动）统一走 `noneCompacted()`，字段齐全不缺失。

**真机验证（8091）**：
- 有活实例 + 5 轮历史 → `{"msg":"压缩完成","scheduled":false,"compressedTurns":1,"freedTokens":459,"summaryTokens":340,"summary":"<810 字摘要>"}`；压缩后追问「前面聊过什么」，模型准确复述第 2–6 类，证明摘要被正确接受。
- 重启进程后再压 → `{"msg":"已排到下一次提问前压缩","scheduled":true}`，回落分支正确。

### P4-b 用户窗口接对线（已实施并真机验证）

**这处不是「删多余配置口」，是接错线**：

| 项 | 改前 | 改后 |
|---|---|---|
| 用户选的窗口（`contextWindow` 档位） | 灌进 builder `.maxContextTokens(...)` | 落到 `OpenAIChatModel.builder().contextWindowSize(n)`（`>0` 才设） |
| `maxContextTokens` | 被当成「压缩阈值」 | 退役：它其实是 `WorkspaceContextMiddleware` 的**工作区材料注入预算**（AGENTS.md + MEMORY.md + knowledge 固定段，框架默认 8000），与压缩无关 |
| 压缩阈值真源 | 没人接 | `CompactionConfig.triggerTokens(0=动态)`，动态态 = `model.getContextWindowSize() - reserved(20k)` |
| 输入上限 | 没人接 | `HarnessContextBuilder#inputLimit` = `window - output - safety` |
| 配置 `chat.agentscopeMaxContextTokens` | 引擎读它 | 退役（语义已消失；注释同步改为说明为何不再使用） |

`modelContextWindow(cmd)` 只读 `cmd.getRequestedContextWindow()`，这是对的：前端对**可调模型每轮都显式下发**窗口（含默认上限），为空即「该模型不可调」→ 返回 0 不设，框架按模型名查 `ModelContextWindows` 内置表。**不能把 0 显式塞给模型**——builder 只在 `>=0` 时用显式值，0 会被当成「窗口=0」导致每轮都压。

**真机验证**：
- `contextWindow:32768` → usage 返回 `"window":32768,"windowSource":"framework","parts":{"input":38,"toolSchema":13047}`，窗口真的是用户档位（改前恒为模型登记值 131072）。
- 反证：故意给 `contextWindow:12000` → `ContextBudgetExceededException: estimated=19819, limit=10260`。`10260 = 12000 - 输出预算 - 安全余量`，证明确实按用户窗口算预算。**同时暴露一个真实边界**：工具 schema 约 13k token，窗口小于 ~16k 时必然超预算；线上可调档位最小 32768，余量够，故不额外加护栏（若要支持更小档位，得先压工具 schema）。

### P4-c 修 P3 漏洞：去掉 `disableWorkspaceContext()`（已实施并真机验证）

P3 把记忆换成框架（去掉 `disableMemoryTools`，注册 `memory_search` / `memory_get` / `memory_save` / `session_search`），但 `.disableWorkspaceContext()` 还挂着——它是 P0 时代为「关掉框架旁路能力」加的遗留开关。框架侧 `HarnessAgent:2706 if (!disableWorkspaceContext) { new WorkspaceContextMiddleware(...) }`，而这个 middleware 承担两件 P3 依赖的事：

1. `readMemoryMd(rc)` 把 MEMORY.md 注入 HARNESS_CONTEXT（else 分支**不注入**）；
2. 注入 Memory Recall / Memory Persistence 使用指引（否则模型不知道四把记忆工具何时用、怎么用）。

后果：P3 验收「问答后记忆自动沉淀且跨会话读得到」只剩「写」通（flush hook 在 `!disableMemoryHooks` 时照常跑），「读回来」这条路断了。

**真机验证（去掉开关后）**：
- 会话 A：「请记住…PINEAPPLE-7391…用记忆工具存下来」→ 模型主动调 `memory_save`（2286ms 成功），再调 `memory_search` 回查两次确认命中；回答里明确说明「用的是 `memory_save`，会同时写长期记忆文件和当天记录」。**改前模型不知道这些工具存在**。
- 会话 B（全新会话）：「我的常用排版字号是多少？代号是什么？不知道就只用记忆工具查」→ 模型调用 `memory_get` + `memory_search`，准确答出「11pt」「PINEAPPLE-7391」。**跨会话读回通过**。

副作用核查：该 middleware 还会注入 Runtime Environment（含 `The workspace directory is: <stateDir>`）与 `## Workspace` 段落（沙箱分支写 `Sandbox root`）。问渠「正文/计划/清单不写沙箱路径」的约束由引擎侧 1975 行的路径回收兜底（正文出现沙盒路径就收成文件名）覆盖，实测回答中未出现内部路径。

---

## 5. 已接受的能力取舍

### 5.1 产物交付改用框架（更正）

**初版判断有误**：曾以为框架无产物交付能力，实则 jar 内有 `io.agentscope.harness.agent.artifact` 三类
+ `ArtifactDeliveryTool`，契约与问渠严丝合缝：

| 问渠现状 | 框架 |
|---|---|
| `@Tool deliver_artifact(path,...)`（`SandboxTools`） | `ArtifactDeliveryTool.deliverArtifact(ctx, filePath, fileName, description, force)` |
| `ArtifactService` 存产物 | `ArtifactDeliveryTarget.deliver(ctx, ArtifactDeliveryRequest)` —— 业务无关 SPI，逻辑自实现 |
| 产物冲突处理 | `ArtifactDeliveryResult.success / conflict / fail`（比现有实现更完整） |

| # | 动作 |
|---|---|
| P1-c1 | 新建 `WenquArtifactDeliveryTarget implements ArtifactDeliveryTarget`，内部委托现有 `ArtifactService`（业务逻辑、产物表、前端下载面板全不动） |
| P1-c2 | 删 `SandboxTools` 里的 `deliver_artifact` `@Tool` 方法，改由 `.artifactDeliveryTarget(...)` 注册 |
| P1-c3 | 工具名一致（框架同名 `deliver_artifact`），**提示词与技能文档无需改动** |

### 5.2 框架确无等价物、决策保留自研

**`run_code`** — 让模型在沙盒里写一段脚本一次做完一批事，只把 `print` 出来的结果带回上下文。
与 `execute` 的分水岭：**脚本可回调平台已挂载的工具**（`ToolStubRenderer` 把本轮工具生成成 Python/JS 客户端，
工具没挂上就不出现在模块里，脚本也就调不到——脚本能力面恒等于本轮真实挂载能力）。
支撑组件：`RunCodeService`(207 执行) / `RunCodeSubCallRuntime`(498 回程) / `ToolStubRenderer`(470 客户端生成) /
`RunTokenRegistry`(113 单次运行凭据) / `RunCodeBridge`(72) / `RunCodeTurn`(88)，共 1465 行。
全量核实：`core/tool/coding` 仅 `ShellCommandTool` + `CommandValidator`(Unix/Windows)，
`core/tool/file` 仅 `ReadFileTool`/`WriteFileTool`，**无"沙盒内脚本 + 回调宿主工具"语义**。

> ⚠️ **与 P1 的耦合**：`run_code` 依赖 `sandbox.callbackBaseUrl`（容器内反向访问问渠后端）。
> 未配时 `RunCodeService:69-76` 明确不猜地址、直接返回失败文本。**P1 换沙箱后端时网络拓扑若变动，
> 必须先验证回调仍可达**，否则 run_code 会在容器内表现为"每个子调用超时"。

**计划模式** — 2026-10 收口：问渠 `PlanModeService`（意图预判）已删除，计划模式只由用户开关或模型调用框架 `plan_enter` 决定；`enablePlanMode()` 提供 `plan_enter/write/exit` 与只读拦截，历史计划卡的版本展示仍由落库的 `versions` 承担。

---

## 6. 遗留收口（2026-10-18 晚，三项全部处理）

### 6.1 智能体换绑/改配置即重建 + 「技能范围」真正生效

**先修的是一个活回归，不只是把死配置接上**：界面允许在会话内换智能体（`useChatEngine#pickAgent`，
提示语是「下一问起由它作答」），而常驻实例的复用判据只有 `modelRef`。于是**两个用同一模型的智能体
之间换人，旧实例会继续用上一个人的提示词与工具表作答**——`sysPrompt(rolePrompt(...))`、
`tools.frameworkTools(...)`、技能白名单全是 build 期固化的，日志上却一切正常。

改法：

| 位置 | 动作 |
|---|---|
| `FrameworkSessions#open` | 多收一个 `bindingKey`，`Held` 记它；复用要同时满足 `modelRef` 与 `bindingKey` |
| `AgentscopeChatEngine#bindingOf` | 产出 `AgentBinding(行, key, SkillFilter)`：`key = agentId@updateTime`，一次解析三处共用（人设 / 判据 / 白名单），DB 读取次数不变 |
| `.skillFilter(...)` | 智能体 `tool_skill` 三态 → 滤镜：`null`=跟随全局（`all()`）、`0`=关闭（`none()`）、`1`=指定（`only(名单)`；名单为空即一条都不给） |
| `pickedSkillsText` | 受同一滤镜约束：点名不能绕过能力边界，越界时写一句可读回绝让模型如实说 |
| `openForContinuation` | 续跑显式带上 `c_ai_session.agent_id`——此前续跑会退回平台默认人设 |

**依据（源码级）**：框架 `.skillFilter(SkillFilter)` → `HarnessSkillMiddleware.effectiveFilter`
（`builderFilter.overlay(ctx.get(SkillFilter.class))`）→ `applySkillFilter` 裁目录，被滤掉的不进提示词、
也不注册 load 工具。**不能走「每轮往 ctx 里 put」那条路**：`AgentSession:84` 构造时把
`RuntimeContext` 复制了一份，事后改原对象影响不到已建实例。

`AgentService#update` 每次都刷新 `updateTime`，所以「改配置」与「换人」一样会触发重建；会话状态在
`stateStore` 里，重建不丢历史（与换模型同一套依据）。

### 6.2 记忆钉回 uid 级（不再随沙箱作用域）

`SandboxService#frameworkFilesystem` 现在返回

```
RoutedSandboxFilesystem(沙箱文件系统, { "/MEMORY.md" → LocalFilesystem(stateDir/{uid}),
                                        "/memory/"   → LocalFilesystem(stateDir/{uid}/memory) })
```

其余路径（工作区文件、`skills/`）照旧落沙箱，`execute`/`id` 恒走主沙箱。

> **两条前缀必须各给一个根**：路由会把命中的前缀**剥掉**再交给后端
> （`CompositeFilesystem#routeForPath`：`memory/2026-10-18.md` → 后端 `/2026-10-18.md`）。
> 共用一个根时日账会落到 `{uid}/2026-10-18.md`，与老布局 `{uid}/memory/2026-10-18.md` 错位
> （最小用例实测踩过：写进去的文件不在 `memory/` 子目录里）。分开后剥完前缀正好各归各位。
> 同一用例还验过：`work/`、`skills/` 不落记忆根，`MEMORY.md` 带不带前导斜杠两种写法都读得回，
> `glob("*.md", "memory")` 命中，存量 `memory/` 下的老日账直接可读。

**为什么是 `RoutedSandboxFilesystem` 而不是 `CompositeFilesystem`**：后者不实现 `execute`，而框架
只在 `filesystem instanceof AbstractSandboxFilesystem` 时才注册 `ShellExecuteTool`——直接套 composite
会把 shell 工具整个弄哑。前者的类注释原文就是为这件事造的：
*"Prefix-routed filesystem that preserves shell execution on the primary sandbox backend … mount … memory-stores/ routes alongside a SandboxBackedFilesystem without losing shell_execute"*。

**结果**：

- 记忆落在 `user-data/agentscope/{uid}/MEMORY.md` 与 `memory/*.md`——正是 P3 之前的老位置，
  **存量老记忆不用迁移就能读回来**；
- 跨会话、跨工作区一致（不会再出现「关掉 `projectWorkdirEnabled` 就每会话一份」）；
- 与 `WorkspaceManager#getMemoryDir` 的 Path 口径（`workspace.resolve(uid).resolve("memory")`）重新重合。

**代价（如实记录）**：模型用 shell `cat MEMORY.md` 看不到内容（shell 走沙箱，记忆走宿主）——记忆的
受支持入口本来就是 `memory_search` / `memory_get` / `memory_save`，注入由 `WorkspaceContextMiddleware`
读同一份文件完成，日常问答不受影响。

**存量捞回（一次性；先把 `user-data/` 整份备份）**：P3 之后、本刀之前写进沙箱 bind 目录的日账在
`user-data/shared/{uid}/workspace/{sessions/*|projects/*|}/memory/` 下，按下面这段并回 uid 级目录。
重复执行会把同一天追加两次，故只跑一次，跑完用 `diff`/`git status` 核对：

```bash
# 仓库根执行；先备份 user-data
for d in user-data/shared/*/workspace user-data/shared/*/workspace/sessions/* \
         user-data/shared/*/workspace/projects/*; do
  [ -d "$d/memory" ] || continue
  uid=$(printf '%s' "$d" | cut -d/ -f3)
  mkdir -p "user-data/agentscope/$uid/memory"
  for f in "$d"/memory/*.md; do
    [ -e "$f" ] || continue
    cat "$f" >> "user-data/agentscope/$uid/memory/$(basename "$f")"
  done
done
```

`MEMORY.md` 是策展去重的那一层，**不自动并**：需要的话人工比对后再合，避免把同一条偏好重复写进去。

### 6.3 `skill-projections/{uid}/` 的定位，以及「技能可审」

这条不是 bug，是**没定作用域**，现已明确：

- `skill-projections/{uid}/` = **冻结的历史用户级技能库**：不再有写入方，读侧照旧解析（存量资产不丢）。
- 模型在对话里用 `skill_manage` 沉淀的技能落**工作区** `skills/`（`shared/{uid}/workspace/…`），随工作区/会话走。
- **2026-10-19 补回用户自助安装**：`user-data/skills-users/{uid}/`（宿主、跨会话、不依赖沙箱），
  由 `SkillController` 提供新建/zip/URL 安装、编辑、删除、个人停用与下载。它与上面两路并存：
  内置（随版本）→ 历史投影（只读）→ 沙盒工作区（随会话）→ 用户自装（最高优先级）。
  详见 [技能系统](skills.md)。

读侧已经补到「可审 + 可管」：

- `GET /api/ai/skill/available`（可带 `sessionId`）/ `/list` / `/detail` / `/content`：
  带会话且沙箱在场时才并工作区那一路——**看一眼清单不会把容器拉起来**（与沙箱文件浏览端点同一条判据）；
- 停用分两层：平台级 `skill.disabled`（管理员，全平台生效）+ 个人级 `.disabled`（成员自己停，只影响自己）；
  技能表与面板仍然退役，不复活第二套 DB 存储（用户技能落宿主文件系统）；
- 边界：工作区技能在界面上 `selectable=false`——**只给看、不给点名**。它随会话走，钉进智能体配置会存下
  一个换个会话就解析不出来的名字。用户自装与历史投影可点名（`ref = {uid}/{name}`），内置用裸名。

---

## 7. 执行纪律

- 逐项落地，每项单独验证，**单次验证不超 5 分钟**，验证脚本/临时文件用完即删
- 只提交本次改动，不夹带其他变更
- 每项完成后更新 `.workbuddy/memory/`
- **P1与 P3 是高风险项**：P1-7（工作区文件）、P3-a6/P3-b7（用户技能与记忆数据）动手前先备份数据库
- 复核订正：`schema.sql` **有**这四张表的建表语句 → 删表必须「删语句 + 写显式 `DROP TABLE`」两件事一起做，否则启动即复活（已实测踩过）