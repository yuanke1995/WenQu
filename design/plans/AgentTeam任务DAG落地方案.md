# 问渠 Agent Team / 任务 DAG 落地方案（P0）

> ⚠️ **本文为 2026-11-23 设计快照，自研任务板已退役（V38），任务改由框架 `TeamClient` 承载；实施以《[智能体团队-现状与未完项](./智能体团队-现状与未完项.md)》为准。**

- 日期：2026-11-23
- 状态：方案待拍板（§9 四项）；拍板后按 §6 分期执行
- 范围：首发只做「依赖 + 等待 + 最小看板」；**不含**多用户协作、不启用框架 teamsMode、不改工作流引擎
- 原则：能执行的不写成方案；能复用的不重造；静态 DSL 快照语义不动；保持可逆

---

## 0 总览

| 能力 | 现状 | P0 动作 | 复用率 |
|---|---|---|---|
| 派遣与取结果 | 框架七把工具已接入，问渠有闸、展示名、子对话卡 | 不动 | 100% |
| 直播帧 / 过程账本 | `RunEventType` 35 种事件、`ChatRunBus` 扇出+快照+断线接流、`c_ai_session_event` 可回放 | 加 3 种事件 + 可选字段 | ~85% |
| 长任务运行时壳 | `c_ai_goal` 租约/phase/乐观锁/`WAITING_EVENT`/`next_run_at` + `GoalScheduler.claimReady` | 新建任务表照抄模式，调度器可共用节拍 | ~70% |
| DAG 画布 | `FlowEditor.vue` 已实现节点状态染色 + 流式事件 | 抽成通用组件 | ~70% |
| 共享任务实体 | 无（todo 挂 session、subChat 挂 message、goal 绑 user+session） | **新建** | 0% |
| 依赖边与就绪判定 | 无（工作流边是编译期路由，不是跨任务等待） | **新建** | 0% |
| 变更等待唤醒 | 只有状态占位，无 watcher；MessageBus 被 Noop 顶掉 | **新建** | 30% |

**结论**：底层约 70–80% 工作量，UX 约 20–30%。首发按 §1.2 的原生路线走，不碰框架 teamsMode。

---

## 1 事实链（先纠偏，避免按错误前提设计）

### 1.1 已核实的现成能力

- 框架工具七把：`agent_spawn / agent_send / agent_list / task_list / task_output / task_cancel / wait_async_results`，来源 `agentscope-harness 2.1.0-BETA1`（`pom.xml:32-36`）。问渠侧名单见 `src/main/java/com/wenqu/ai/agentx/AgentxToolSurfaceFilter.java:34-37`，中文名与提示见 `FrameworkToolLabels.java:68-75,103-109`，子对话卡只认 `agent_spawn`（`AgentxTurnRecording.java:69-74`）。
- 事件契约单点真源：`src/main/java/com/wenqu/ai/engine/RunEventType.java:21-62`；前端 `web/src/chat/eventContract.generated.js:1-2` 是自动生成的，生成器命令写在文件头。
- 实时管道：`ChatRunBus`、`SseRunEventChannel`、`RunBusCluster`（Redis 跨副本）；工作流进度已经复用这套（`WorkflowRunStreamService.java`）。画布染色实例：`web/src/views/FlowEditor.vue:1328-1332,1361-1396`。
- 过程账本：`SessionEventService.append/listRecent/listByTurn/listByGoalAfter`（`src/main/java/com/wenqu/ai/service/SessionEventService.java:100-157,203-222`），带自增 id 可作增量游标。
- 任务运行时：`c_ai_goal` 表（`src/main/resources/db/migration/V1__baseline.sql:886-933`）已有 `phase/lease_owner/lease_until/next_run_at/waiting_reason/last_event_id/row_version`；调度节拍 `GoalScheduler.tick()`（`src/main/java/com/wenqu/ai/agent/goal/GoalScheduler.java:83-101`）；就绪 WHERE 含 `WAITING_EVENT`（`GoalMapper.java:25`）。
- 框架原语（**不启用，仅记录**）：jar 内有 `io.agentscope.harness.agent.team.TeamTask(blockedBy, version)`、`TeamClient.createTask(...,blockedBy,...)`、`TeamWakeups`、`TeamsMiddleware`；`HarnessAgent.Builder.teamsMode(TeamClient, TeamContext)` 存在但问渠 builder 未调用（`AgentscopeChatEngine.java:3433-3475`）。

### 1.2 为什么不启用框架 teamsMode

1. `LocalTeamClient` 的持久化落在 `BaseStore`（沙盒文件系统），跨副本/延迟都不合问渠现状；要真共享必须自写 DB 版 `TeamClient`。
2. 框架 jar **没有** Redis 版 `MessageBus`，只有 `WorkspaceMessageBus`；而问渠为省每轮两次沙盒 HTTP 往返已用 `NoopBusComponents` 把 MessageBus 顶成恒空（`src/main/java/com/wenqu/ai/agentx/NoopBusComponents.java:13-39`）。启用 wakeups 要先把这条换回真实现并承担每轮开销。
3. `TeamTool.createTask` 的 `@Tool` 参数只有 `(subject, description, assignee)`，不暴露 `blockedBy`；模型无法经工具声明依赖。
4. 版本是 BETA1，深水区改造 + 与现有子智能体账本双轨，风险高于收益。

**决定**：P0 用问渠原生领域层；框架 teamsMode 留作 P2 评估。

### 1.3 必须遵守的既有约束

- 框架七把工具**不进** `ToolRegistry`（`FrameworkToolLabels.java:8-21`），启动期重名检查抓不到。新增工具名必须避开 `task_list/task_output/task_cancel`。
- 工具族/描述符是单一真源：`ToolFamily.java`、`ToolDescriptor.java`、`TodoToolProvider.java` 是模板；`ToolRegistry.init()` 会做族唯一、名唯一、声明 vs `@Tool` 反射核对（`ToolRegistry.java:55-115`）。
- `wait_async_results` 上限 120s，且与单轮步数/maxIters 是独立预算——长等待不能用它轮询。
- 状态词三套并存：todo `pending/doing/done/failed`、goal `RUNNING/PAUSED/ACHIEVED/CANCELLED`、subChat `done/timeout/failed`。看板必须有唯一口径。

---

## 2 领域模型

### 2.1 新表（迁移 `V35__agent_task_dag.sql`，当前最新为 V34）

**`c_ai_task`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `tenant_id` | VARCHAR(32) | 行级租户过滤，**不进** `GLOBAL_TABLES` |
| `id` | VARCHAR(64) | UUID，稳定任务 ID；对外 `task:{id}` |
| `scope_type` | VARCHAR(16) | `session` / `project`（P0 只实现 session） |
| `scope_id` | VARCHAR(64) | sessionId / projectId |
| `subject` | VARCHAR(500) | 任务标题 |
| `description` | TEXT | 任务说明 |
| `state` | VARCHAR(32) | 见 §2.2 |
| `owner_agent_id` | VARCHAR(50) | 可空；被哪个智能体认领 |
| `owner_user_id` | VARCHAR(64) | 发起人，归属校验 |
| `version` | BIGINT | 乐观锁，CAS 用 |
| `lease_owner` / `lease_until` | VARCHAR(64) / DATETIME(3) | 执行租约，照抄 goal |
| `next_run_at` | DATETIME(3) | 可唤醒时刻；NULL=有执行在飞 |
| `waiting_reason` | VARCHAR(500) | 等待原因（依赖/输入/外部事件） |
| `blocked_count` | INT | 未完成前置数；0 才可被认领 |
| `result` | TEXT | 完成结果摘要 |
| `source_turn_id` | VARCHAR(64) | 创建它的轮次，供账本对账 |
| `created_at` / `updated_at` | DATETIME(3) | — |

索引：`(tenant_id, scope_type, scope_id, state)`、`(tenant_id, state, next_run_at)`、`(tenant_id, lease_until)`。

**`c_ai_task_edge`**：`tenant_id`、`id`、`from_task_id`、`to_task_id`、`created_at`；唯一键 `(tenant_id, from_task_id, to_task_id)`；索引 `(tenant_id, to_task_id)`，`(tenant_id, from_task_id)`。校验：禁止自环、禁止成环（创建时三色 DFS，最多 100 节点/300 边）。

**`c_ai_task_event`**：`tenant_id`、`id` BIGINT AUTO_INCREMENT、`task_id`、`kind`（created/depend_added/blocked/ready/claimed/completed/failed/wait/woke）、`payload` JSON、`created_at`；索引 `(tenant_id, task_id, id)`。这就是节点的增量时间线与断线游标。

### 2.2 状态机与状态词归一

```
PENDING --依赖全满足--> READY --claim--> IN_PROGRESS
   ^                      |                |
   |                  (依赖回退)           +--> COMPLETED / FAILED
   +------ WAITING <------+ (task_wait / 外部事件)
CANCELLED（终态，显式取消）
```

看板列只认上述七个词。写入侧映射：todo `doing`→`IN_PROGRESS`、`done`→`COMPLETED`、`failed`→`FAILED`；goal `PAUSED`→`WAITING`、`ACHIEVED`→`COMPLETED`；subChat `timeout`→`FAILED`（保留原始词在 `result` 里）。**P0 不反向同步**到 todo/goal/subChat，避免双写。

### 2.3 就绪判定与解锁（核心）

1. `task_depend(from, to)` 写边，同时把 `to.blocked_count` 按未完成前置数重算（CAS：`WHERE version=#{v}`）。
2. `task_done(id, result)` 在**同一事务**内：置 `COMPLETED` → 查 `c_ai_task_edge WHERE from_task_id=id` → 对每条边的 `to_task_id` 做 `blocked_count-1` → 归零则置 `READY` + `next_run_at=NOW()`。
3. 每次迁移写一条 `c_ai_task_event`；解锁事件同时经 §3.4 推前端。
4. 失败不解锁下游，下游停在 `PENDING` 并记 `blocked_reason`；由调用方显式 `task_fail` 决策重试或改图。

---

## 3 后端实现

### 3.1 模块清单

- `model/Task.java`、`TaskEdge.java`、`TaskEvent.java`
- `mapper/TaskMapper.java`、`TaskEdgeMapper.java`、`TaskEventMapper.java`（MyBatis-Plus，参照 `GoalMapper`）
- `service/TaskService.java`：创建/依赖/认领/完成/失败/取消/查询/CAS 解锁
- `agent/goal/TaskScheduler.java`：独立节拍（默认 2s，与 goal 调度器错开相位），扫 `READY AND next_run_at<=NOW()` 且租约空的任务
- `controller/TaskController.java`：`GET /api/ai/task/graph?scopeType=&scopeId=`、`GET /api/ai/task/{id}/events`、`POST /api/ai/task/{id}/cancel`
- 归属校验：`tenant_id` 从上下文取，`owner_user_id` 必须等于当前用户；不提供跨用户读口（P0）

### 3.2 工具族与工具

新增 `ToolFamily.TASK("task", "任务编排")`；新增 `agent/tool/provider/TaskToolProvider.java`（照 `TodoToolProvider`），`ToolDescriptor` 声明：

| 工具名 | 用途 | sideEffect | requiresApproval | viewerRequired |
|---|---|---|---|---|
| `task_create` | 建任务（subject/description） | MUTATE | false | false |
| `task_depend` | 加依赖边（from/to） | MUTATE | false | false |
| `task_claim` | 认领 READY 任务（带 version 做 CAS） | MUTATE | false | false |
| `task_done` | 完成并解锁下游 | MUTATE | false | false |
| `task_fail` | 标记失败并记原因 | MUTATE | false | false |
| `task_wait` | 等某任务进入终态/等待外部变更 | PURE（挂起） | false | true |

- 全部 `guestAllowed=false`；子智能体分支是否挂载由 §9 拍板决定（默认不挂，与 writeTodo 同口径）。
- `@Tool` 方法放在 `agentx/TaskTools.java`（holder），经 `SpringAiToolAdapter` 适配，走 `WenquAgentxTools.frameworkTools()` 现有的 `registerWithGroups` 路径（`WenquAgentxTools.java:492-519,903-943`）进入 Toolkit。
- 提示词：在 `ToolAssemblyService` 的能力段加一段「复杂协作先建任务图、依赖未满足不要认领」；不铺开长篇说明。

### 3.3 等待与唤醒（本次最硬的一块）

**主路径（P0）**：`task_wait` 用 `ToolSuspendException` 挂起（参照 `AskUserTool.java:52-57`，恢复链 `SpringAiToolAdapter.java:185`、`AgentscopeChatEngine.java:3503`）。`TaskService` 记一条 `WAITING` 事件与 `waiting_reason`；`TaskScheduler` 订阅 `c_ai_task_event` 的增量游标，条件满足时经 `FrameworkSessions` 的 `session.respond` 路径恢复，并写 `woke` 事件。

**降级路径（若 spike 发现框架 pending 不支持自定义卡）**：`task_wait` 不阻塞，直接返回「已登记等待」；条件满足时不做 `session.respond`，而是沿用 goal 的跨轮续跑——置 `next_run_at=NOW()`，由 `GoalScheduler.claimReady` 起 `goal:{id}:{n}` 续跑，或走 `AgentSession.steer` 注入一条系统消息。代价是等待跨轮而非轮内，对长任务可接受。

两条路径都必须实现：超时（默认 30 分钟，可配）、进程重启后的恢复（从 DB 重建等待表）、同一任务只唤醒一次（`version` CAS）。P0 **不换** `NoopBusComponents`——唤醒走 DB 轮询，不引入框架 MessageBus 的每轮开销。

### 3.4 事件与实时

- `RunEventType` 新增三项（枚举签名见 `RunEventType.java:71-79`）：
  - `TASK_STATE("task_state","onTaskState","任务节点","task",true,true)`
  - `TASK_EDGE("task_edge","onTaskEdge","依赖边","task",true,true)`
  - `TASK_WAIT("task_wait","onTaskWait","等待依赖","card",true,true)`
- **不改事件信封**：`ChatRunBus.frame()` 保持 `{type,content,sessionId,seq,pos}`，`taskId/edgeId/state` 放进 `content` JSON。这样老客户端自动忽略，无需迁移。
- 账本：写 `c_ai_session_event` 一条 `type="task"`，payload 含任务快照；节点完整时间线走 `GET /task/{id}/events`，不在会话账本里堆全量。
- 重新生成前端契约：`mvn -q -Dtest=RunEventContractGenerator -Dwenqu.generateEvents=true test`（命令见 `eventContract.generated.js:2`）。

---

## 4 前端实现

### 4.1 入口与组件

- P0 入口：**对话页右栏新增「任务图」卡**（与「本轮」卡并列），不做独立顶级菜单。点击展开画布 + 节点清单；空态提示「复杂任务可拆成有依赖的节点」。
- 新组件 `web/src/components/TaskDagBoard.vue`：从 `FlowEditor.vue:1328-1332,1361-1396` 抽取节点染色逻辑，输入 `nodes/edges/live`，输出点击节点事件。复用现在的节点/边渲染样式，但**只读**——P0 前端不提供拖拽改边（改动只能由模型经工具发起，避免与后端 CAS 冲突）。
- 节点展示：标题、state 色块、owner、blocked_by 数量、耗时；边用箭头，被阻塞的边置灰。

### 4.2 事件接入

- `web/src/chat/useChatEngine.js:1913-2108` 的 handlers 增加 `onTaskState / onTaskEdge / onTaskWait`；snapshot 分支（`reduceTurnSnapshot` / `replaySnapshotExtras`）补任务图，保证断线重连后画布能重建。
- `web/src/api.js` 加 `getTaskGraph(scopeType, scopeId)`、`getTaskEvents(taskId)`；SSE 订阅沿用现有 `streamChatSse`，不新增通道。
- 状态存 `web/src/views/store.js` 的会话级结构，不进全局单例污染。

### 4.3 验收界面行为

- 任务创建/依赖/完成/失败时画布对应节点实时变色，依赖边解锁时目标节点从灰变亮。
- 刷新、换设备、断线重连后图与节点状态一致（后端 `c_ai_task` 是真源）。
- 点击节点展开右侧时间线，显示该节点的事件序列与来源轮次。

---

## 5 明确不做（P0）

1. 不启用框架 `teamsMode`、不写 `TeamClient`、不换 `MessageBus`。
2. 不改 `WorkflowDsl` / `WorkflowEngine` / `WorkflowService` 的静态 DSL 快照语义；看板与工作流各自独立，后续再由看板冻结一份 DSL 快照去 run（P1）。
3. 不做跨用户/团队共享、多 reviewer、任务级审批——P0 只同用户可见。
4. 不做模型直接改正在跑的图；拓扑变更走「新任务 + 新边」，历史不变。
5. 不把 todo/goal/subChat 状态反向同步回任务表（只做单向映射）。

---

## 6 分期与粗估（一人全职）

| 阶段 | 内容 | 粗估 |
|---|---|---|
| 1 数据层 | V35 迁移、三张表、model/mapper、TaskService 状态机与 CAS 解锁 | 4–5 天 |
| 2 工具与调度 | TASK 族、六把工具、TaskScheduler、等待/唤醒主路径 + spike 降级 | 4–5 天 |
| 3 事件与前端 | RunEventType 三项 + 契约重生成、画布组件、handlers/snapshot、API | 4–5 天 |
| 4 联调与验收 | §7 全部用例、多租户、断线重连、性能回归（每轮首包耗时不回退） | 2–3 天 |

合计约 **2–3 周**；全做完（多用户协作 + 框架 teamsMode）预计 4 周以上且成本主要在权限与一致性。

---

## 7 验收标准

1. 一轮内模型建 3 个任务、加 2 条依赖，完成前置后下游自动 `READY`，画布同步变亮；未完成前置的任务 `task_claim` 被拒。
2. `task_wait` 在依赖完成后恢复（或按降级路径跨轮续跑），等待超时有终态与原因，不出现永久挂起。
3. 进程重启后：任务状态、依赖边、等待中的任务全部可从 DB 恢复；同一任务只被唤醒一次。
4. 断线重连、刷新、换设备：画布与节点时间线一致；SSE 老客户端不因新增事件报错。
5. 权限：普通用户查不到他人任务；租户隔离生效；游客与子智能体分支（若按默认不挂）看不到 TASK 工具。
6. 回归：普通问答首包耗时与改造前持平（`NoopBusComponents` 未换真实现）；工具清单页无重名冲突、TASK 族显示正常；框架七把工具行为逐字不变。
7. 文档：README、`src/main/resources/manual/07-智能体.md`、`03-对话与问答.md` 同步补「任务图」说明，不留宣传与页面错位。

---

## 8 风险与回滚

| 风险 | 缓解 |
|---|---|
| 工具名与框架七把冲突 | 启动期 `ToolRegistry` 只查自己；额外在 `FrameworkToolLabels` 加一份静态断言测试，列出保留名 |
| 状态词漂移 | 看板状态唯一真源在 `c_ai_task.state`，§2.2 映射表写成测试常量 |
| 调度器空转 | `next_run_at` 索引 + 默认 2s 节拍；无任务时日志静默，不逐行扫全表 |
| 等待恢复卡在框架 pending | §3.3 降级路径必须实现并通过 spike 验收后才能进阶段 3 |
| 与现有子智能体账本双轨 | P0 只让任务事件引用 `source_turn_id`，不复制子对话卡内容；节点详情跳回原轮 |
| 线上数据污染 | 新表独立、默认无行；功能由 TASK 族开关控制，关掉即全链路不挂工具，旧会话零影响 |

回滚：关闭 TASK 族开关 + 停 TaskScheduler 即可；新表保留不删，避免历史任务引用断裂。

---

## 9 待拍板

1. **入口形态**：对话页右栏卡片（方案默认）还是独立 `/tasks` 页？前者轻、贴近问答；后者适合大图。
2. **任务作用域**：P0 只做 `session` 级（方案默认），还是一步到 `project` 级？后者要处理工作区归属，但工作流已退役的工作区事项不必恢复。
3. **子智能体分支是否挂 TASK 工具**：默认不挂（与 writeTodo 一致）。挂上才能实现"团队成员领取任务"，但要处理分支临时会话的等待恢复。
4. **等待实现**：`ToolSuspendException` 轮内挂起（方案主路径）vs 跨轮续跑（降级）。建议先做 1 天 spike 再定，不提前写死。

---

## 10 与后续阶段的接口（仅留缝，不实现）

- P1 多用户：在 `c_ai_task` 上加 `visibility/member_acl`，读口从「按 owner_user_id」换成「按成员关系」；事件推送从单会话 SSE 扩到工作区广播。
- P1 工作流打通：看板冻结一份 DSL 快照 → 调 `WorkflowService.runForAgent`；不修改正在跑的 run。
- P2 框架 teamsMode：DB 版 `TeamClient` + Redis `MessageBus` + TeamTool 暴露；与 P0 原生工具二选一迁移，不并存。
