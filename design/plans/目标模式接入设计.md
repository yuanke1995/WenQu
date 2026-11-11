# 目标模式（Goal Mode）接入设计稿

> **2026-10-22 更新**：本文描述的是「验收标准 + 人工确认 + 逐条核验」旧范式，已随
> `bbc4b861` 退役；当前实现是**任务运行时**（租约调度 + Continuation + 证据核验），
> 以 `docs/goal-task-runtime.md` 为准。保留本文仅作决策考古。

取证基线：AgentScope `2.1.0-BETA1`（拆 `agentscope-core` / `agentscope-harness` 的 sources jar + 官方 zh 文档），问渠工作区 `master@a98ba4e3`（2026-10-20）。
所有「框架能/不能」的判断都有 `path:line` 或类名可复跑；未经实测的假设单独标注。

---

## 一、先作废的三条约定（不改这三条，目标模式建不起来）

| 作废项 | 现状出处 | 为什么必须作废 | 补位动作 |
| --- | --- | --- | --- |
| **模型侧完全没有「要求/验收」这一层** | 全仓 grep 无 `.taskContext(...)`、无 `TaskContextOptions`、无 requirements 工具（工作区核实） | `TASK_STATE` 里的目标（`Scope`）、`REQUIREMENTS`、`VERIFICATION` 三个块在 harness 里**只由 `TaskContextOptions` 决定**（`HarnessContextBuilder:511-512` → `TaskContextProjection.project(..., requirements, verificationResults)`）。不给这个参数，模型每一轮看到的仍然只有清单，**目标与验收标准根本进不了模型输入** | build 段（`AgentscopeChatEngine:2668` 那条链）加 `.taskContext(TaskContextOptions.builder()…)`；工具面继续用问渠的 `writeTodo`，`enableTaskList` 保持关 |
| **「目标确认复用计划卡那 600 秒窗口」** | `chat.planTimeoutMs`（默认 600000） | 目标确认卡与计划卡是两回事：计划的批准窗口收紧是有意的（P0 那批实测拖满会「按未批准收尾」），目标确认卡不该跟着改。目标卡挂在等待上时**已天然豁免宽限**（`TurnDeadline.beginWait/endWait` + 挂人在回路的轮不掐），所以只需要一个新键，不该动老键 | 新增 `chat.goalTimeoutMs`（默认 600000），走既有 machineMs 扣等待那套，`planTimeoutMs` 一个字节不改 |
| **三入口分工只有计划卡/清单/提问卡** | 现有 `AskUserTool` / `PlanCard.vue` / 右栏清单卡 | 这三张卡管的是「这一轮怎么做」，没有任何一张管「这件事整体做到什么算完」。目标模式是第四张卡，不能靠 askUser 连环反问替代 | 新增 `goal` 卡族；同步补一句路由纪律到智能体提示词，判据必须与工具挂载同源（避免「教了模型一件它没有的能力」这条老坑） |

---

## 二、心智模型：目标模式补的是哪一层

框架自己的三档控制模型（官方 zh《如何构建 Agent Harness（二）》§2.2.1）：

| 档 | 表示 | 适用 | 问渠现状 |
| --- | --- | --- | --- |
| 轻量 Todo | 有序待办列表 | 目标明确、步骤少、反馈快 | ✅ `writeTodo` + 框架 `tasksContext` 存储 |
| Plan Mode | 只读探索→写计划→HITL 批准→执行 | 影响面大、需审阅 | ✅ `enablePlanMode()`（`AgentscopeChatEngine:2701`） |
| **目标 + 阶段门禁 + 证据验收** | 权威任务对象：目标、已确认要求、验证结果、剩余预算 | 长任务、跨轮、「做没做完」不能由模型自报 | ❌ **本稿要补的这一层** |

一句话区分：**计划卡说「打算怎么做」，清单说「现在做到哪」，提问卡问「这一步你拍不拍板」，目标卡定「这件事什么算完、还剩多少预算」。** 四张卡不许互相顶班。

用户心智（界面只说人话，不露框架机制）：
- 一个会话同一时刻**至多一个进行中的目标**（与框架 `beginTask` 的语义天然一致：换目标会清掉旧的清单/要求/验证）。
- 目标由**用户**设定和结束，模型不能自己立目标、也不能改写目标（框架文档 §2.2.1 明写「不能通过改写目标掩盖失败」）。
- 「完成」是**判定**出来的，不是模型宣布的：验收标准全部有当前有效的验证结果才算达成。

---

## 三、框架原生就有的部分（一律不重写）

| # | 能力 | 框架出处（已拆 jar 核实） |
| --- | --- | --- |
| 1 | 目标域模型：`Scope(taskId, objective≤4000 字, sourceRef≤2000 字)` | `core/state/TaskContextState.java:43-49` |
| 2 | 验收要求：`TaskRequirement(id, kind=CONSTRAINT\|ACCEPTANCE_CRITERION, text≤2000, status=CANDIDATE\|CONFIRMED\|REJECTED, decision)`，上限 **64 条且 id 唯一** | `core/state/TaskRequirement.java` |
| 3 | 权威分离：模型只能 `propose`（候选），**确认/拒绝只能由可信应用代码调 `decide()`**，javadoc 原文「Not registered as a model-callable tool」 | `TaskContextState.java:146-198`、`tool/builtin/RequirementTools.java` |
| 4 | 验证结果：`TaskVerification(id, requirementId, actionId, verifierId, outcome=PASSED\|FAILED\|UNKNOWN\|ERROR, reason, binding, recordedAt)`，每个标准只留最新一份 | `core/state/TaskVerification.java`、`TaskContextState.java:252-273` |
| 5 | 证据绑定与失效：`EvidenceBinding(taskId, contractVersion, subjectVersion)`；改目标/改要求/换工件版本 → `contractVersion++`，旧验证在投影里显示 `STALE`；`recordVerification` 会因绑定变化抛 `ConcurrentModificationException` | `TaskContextState.java:226-250`、`TaskContextProjection.java:195-221` |
| 6 | 乐观并发：所有写都要 `expectedRevision`，冲突抛 `ConcurrentModificationException` | `TaskContextState.java:275-278` |
| 7 | **每轮自动投影给模型**：`<TASK_STATE>` 内含 `Task/Objective/Objective source` + `<REQUIREMENTS>` + `<VERIFICATION>`；是 transient 合成消息（`metadata SYNTHETIC=true`），**不污染持久历史**；旧 todo 回执自动降级成 receipt 防止模型读过期状态 | `core/middleware/TaskContextProjection.java:121-159` |
| 8 | 模型侧候选提议工具 `task_requirement_propose`（opt-in） | `RequirementTools.java` |
| 9 | 验证器：`DeterministicVerifier`（javadoc：**Trusted, synchronous, bounded, side-effect-free; never an LLM or command runner**）+ 两个现成实现 `ShellExitCodeVerifier`、`JsonResultSchemaVerifier` + 审计留存 `StoredVerification`（换目标后仍留档） | `harness/agent/verification/*` |
| 10 | 证据账本：**每一把工具的每次执行**都会被记成 `action/start`·`action/end` 事件（含 `ActionObservation` 与 result），沙盒工具也走这条路；`VerificationService` 就是拿 `actionId` 回捞这份证据 | `core/tool/ToolExecutor.java:185,533`、`core/observation/ActionObservations.java:76`、`hsrc/agent/HarnessAgent.java:2619` |
| 11 | 目标态变更自动进会话日志：`tasks_context` 变化即追加 `task/changed` 事件（整份状态），故目标/要求/验证的每次改动**天然可回放** | `core/session/SessionRecorder.java:370-380`、`SessionEventTypes.java:71` |
| 12 | 验证结果可跨重启恢复：`SessionProjection` replay `verification/result` 并重放 `recordVerification` | `core/session/SessionProjection.java:52-57,104-107` |
| 13 | 跨轮续跑的机制：`AgentSession.submit(idempotencyKey, input)`（**turnId 由 key 派生** → 重试不会重复起轮）、`resume(turnId)`（只允许最新未结束轮、须先清待办与不确定工具）、`await(task)`、`interrupt(runId)` | `harness/agent/session/AgentSession.java:137-176,305-355` |
| 14 | 轮终态可机读：`run/end {status, reason}`，`status ∈ completed/cancelled/failed/suspended/interrupted` | `SessionRecorder.java:470-494` |
| 15 | 同会话串行由框架持有：`ReActAgent.callSerializationKey = slotKey(userId, sessionId)` | `core/ReActAgent.java`（既有实测结论） |

**框架明确没有、必须由问渠自己写的两件事**（不写进方案就是幻觉承诺）：
- 运行级预算（`max_model_calls` / `max_total_tokens`）：那是 **AgentScope Service 托管控制面的 HTTP API**（官方 zh《会话、任务与预算》/budget）。三个本地 jar 里 `find -iname "*budget*"` 只有 `ContextBudgetExceededException`（**单次模型输入**超窗，`HarnessContextBuilder:289`），没有跨轮用量闸。
- 验收起动与目标设定：`grep new VerificationService` 在 harness/core 里**零命中**；`beginTask`/`setSubjectVersion` 在框架内部也**零调用点**。官方文档原话：「这两个开关默认关闭，彼此独立……并不会自动从用户消息中提取要求或自动执行验证」。

---

## 四、状态机（10 态，含每态允许的下一步）

按官方 §2.1.2 的状态表收窄成问渠能落地的 10 态。落库列 `c_ai_goal.status`，界面文案见第七节。

| 状态 | 进入条件 | 允许的下一步 | 框架侧动作（同步点） |
| --- | --- | --- | --- |
| `DRAFT` | 用户点「设为目标」/模型提议后由用户发起 | → `CLARIFYING` \| → `RUNNING` \| → `CANCELLED` | 无（只在问渠库） |
| `CLARIFYING` | 必备要素缺失且拿不准 | 复用**提问卡**；→ `DRAFT`（用户弃答） | 无 |
| `AWAITING_CONFIRMATION` | 目标+标准草稿已成形，等用户拍板 | → `RUNNING` \| → `CANCELLED`；改意见走「继续对话」 | `beginTask(Scope, rev)` 只在这一步之后做 |
| `RUNNING` | 用户确认，标准已 `CONFIRMED` | → `VERIFYING` \| → `WAITING_INPUT` \| → `PAUSED` \| → `BUDGET_EXCEEDED` \| → `FAILED` \| → `CANCELLED` | 每轮 `submit` 前覆盖写：`beginTask` + `decide(...)` + `setSubjectVersion(...)` |
| `VERIFYING` | 本轮正文收口或模型自报完成 | → `ACHIEVED` \| → `RUNNING`（生成修复项）\| → `FAILED` | 轮外调 `VerificationService.verify(...)` |
| `WAITING_INPUT` | 挂在提问卡/审批卡上 | 答完 → `RUNNING`；超时/忽略 → 不代答（沿用现口径） | 无 |
| `PAUSED` | 用户点暂停 / 会话宽限中止 / 服务重启 | → `RUNNING`（`resume(turnId)`）\| → `CANCELLED` | 恢复时**重判**：目标、权限档位、沙盒工件版本、剩余预算 |
| `BUDGET_EXCEEDED` | 预算任一条撞闸 | → `RUNNING`（用户加预算）\| → `ENDED_INCOMPLETE` \| → `CANCELLED` | 停止续跑，产出「未完成清单」 |
| `ACHIEVED` | 每条已确认标准都有**当前有效** PASSED | 终态（可「另起目标」） | 保留验证记录；`invalidateSubject` 只在换工件时调 |
| `ENDED_INCOMPLETE` / `FAILED` / `CANCELLED` | 预算内主动收尾 / 判定不能继续 / 用户取消 | 终态，保留审计事实 | 不删 `tasksContext`，等下一次 `beginTask` 自然清 |

两条硬约束（照抄框架语义，别自创）：
1. **`todo 全 done ≠ 目标达成`**。框架把它写进了喂给模型的提示词（`TaskReminderMiddleware`：`Never infer task acceptance from all todos being completed.`），问渠的达成判定只看 `verifications`。
2. **换目标必须换新 `taskId`**，同 id 改目标文本框架直接抛 `IllegalArgumentException`（`TaskContextState.java:124-127`）。所以「改目标」在产品上是「结束旧目标 + 起新目标」，历史与旧验证留在 `c_ai_goal` 的旧行里。

---

## 五、数据契约

### 5.1 新表 `c_ai_goal`（MySQL 是**产品面权威**，框架 `tasksContext` 是**模型视图**，单向同步）

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | varchar(64) PK | = 框架 `Scope.taskId`，一次目标一个，永不复用 |
| `session_id` / `user_id` | varchar | 归属；同会话至多一条 `status` 非终态 |
| `objective` | varchar(4000) | 逐字进 `Scope.objective`（框架帽就是 4000，写超在 Controller 就拦） |
| `criteria_json` | json | `[{id,kind,text,status,decisionSource,verifierId,verifierParams}]`，id 用框架返回的 requirementId |
| `constraints_json` | json | 同结构，kind=CONSTRAINT |
| `subject_version` | varchar(2000) | 工件指纹（见 §八） |
| `verifications_json` | json | 每标准最新一份 `{requirementId,outcome,verifierId,reason,actionId,binding,recordedAt}` |
| `status` | varchar(32) | §四 的 10 态 |
| `budget_turns` / `budget_tokens` / `budget_tool_calls` / `budget_minutes` | int / bigint / int / int | 0=不限（问渠既有口径） |
| `used_turns` / `used_tokens` / `used_tool_calls` / `used_ms` | int / bigint / int | 累计，撞闸判据；口径见 §九 |
| `contract_version` / `state_revision` | bigint | 同步用的乐观锁水位（框架值），不一致就本轮重读再写 |
| `first_turn_id` / `last_turn_id` | varchar | 目标覆盖的轮区间，过程面板按它聚合 |
| `end_reason` | varchar(32) | `achieved`/`budget`/`cancelled`/`failed`/`incomplete` |
| `created_at` / `started_at` / `ended_at` | datetime(3) | |

`schema.sql` 建表 + `SchemaMigrator` 自动补；`c_ai_message.turn_id`（`schema.sql:224`）与 `c_ai_session_event.turn_id`（`:935-946`）已存在（P0 那批），目标只是多带 `goal_id`：`c_ai_session_event` 加一列 `goal_id`（账本按目标聚合，不新建表）。

**确认卡不落新表**：`AWAITING_CONFIRMATION` 这张人在回路卡直接复用 `c_ai_tool_approval`（`:687-700`）——它已经是「带轮身份的持久待办 + 终态 + 答复审计」的通用表，status 现有取值（PENDING/APPROVED/REJECTED/TIMEOUT/STOPPED/INTERRUPTED/REVISED/SUPERSEDED）够用，只加 `kind=goal_confirm` 一档；再新立一张 approval 表就是第四份平行真值源。

### 5.2 回显兜底（这条是本仓最贵的坑）

框架会话状态物理落宿主 `~/.wenqu/agent-journal`（`SandboxService.frameworkFilesystem` 走两参构造 → `journalRoot==null` 回落 user.home，已实测）。**进程重启、多副本换副本，`tasksContext` 就不在这个进程里了**。所以：
- 界面显示与预算判定**只读 `c_ai_goal`**，不读框架；
- 框架侧写永远走「本轮开跑前覆盖写」：`agent.updateAgentState(ctx, "goal_sync", state -> …)`（与 `activated_groups` 每轮覆盖写同一条纪律），**不做增量、不做只 build 期写一次**；
- 目标面板要能显示「与模型看到的一致吗」：读回比对 `state_revision`，不一致就在面板上出声（不许静默）。

---

## 六、同步桥（照 `TodoBridge` 的形状写，别自创第二套）

新类 `com.wenqu.ai.agentx.GoalBridge`：

```java
// 三个写入点，全部按 expectedRevision 乐观锁，冲突即本轮重读重试一次
SyncResult beginOrUpdate(AgentState state, GoalRow goal)   // Scope(taskId=goal.id, objective, sourceRef="wenqu-goal:"+id)
SyncResult confirmCriteria(AgentState state, GoalRow goal) // decide(id, CONFIRMED|REJECTED, new Decision(USER|CALLER, sourceRef), rev)
SyncResult pinSubject(AgentState state, String version)    // setSubjectVersion(version, rev)
```

三条必须写进类注释的实测事实（都是 `TodoBridge:36-41` 已经踩过的）：
1. **写活状态，不写 `session.inspect()` 那份快照**——后者每次从日志重放出新对象，改完即丢，症状是「写入成功、回读为空」。轮外唯一的写入口是 `updateAgentState(ctx, reason, consumer)`：它会抢 writer lease、**有未结算工具调用直接抛**。
2. **`decide` 的 `Decision.authority` 只允许 `USER`/`CALLER`**，`sourceRef` 必填非空 ≤2000 字。用户在卡片上点批准 = `USER` + `"goal-card:"+goalId`；平台自动放行一律禁止（不许出现「系统替用户确认验收标准」这条路）。
3. `recordVerification` 的 `binding` 必须**逐字等于** `state.getEvidenceBinding()`，且该标准必须是 `ACCEPTANCE_CRITERION` + `CONFIRMED`，否则抛。所以顺序是死的：`beginTask → decide → setSubjectVersion →（这一轮工具执行，action/end 落账）→ 轮外 verify()`。**同一轮内不能验证刚发生的动作**（证据还没结算），这必须是设计而不是 bug。

投影参数装配（build 段 `AgentscopeChatEngine:2668` 那条链上补一段）：

```java
.taskContext(TaskContextOptions.builder()
        .includeRequirements()            // 让模型看到目标 + 已确认/候选要求
        .includeVerificationResults()     // 让模型看到验证结果与 STALE 标记
        .allowRequirementProposals()      // 注册 task_requirement_propose（分岔 2）
        .build())
```
> ⚠️ 待实测（M1 第一条）：`TaskContextOptions` 是否在 `enableTaskList(false)` 下仍能独立投影。静态证据支持（harness 侧由 `HarnessContextBuilder:511-512` 独立驱动），但未真机验过；若必须搭配 `enableTaskList(true)`，就会把框架 `todo_write` 与 `in_progress/completed` 词面重新引入模型上下文，与问渠 `writeTodo` 的 `doing/done` 撞车——那时先只开 `includeVerificationResults`，把目标写进 `sysPrompt` 拼接（与 `PlanCopy.guidance()` 同一条），再单独裁决。

---

## 七、界面与交互（逐态）

### 7.1 入口（不新开顶级入口，收在既有三处）
1. **会话页顶栏/输入框工具条**：一颗「设为目标」按钮（与深度思考、计划模式同排）。点了不弹窗直接开卡，弹窗只用于「编辑目标」。
2. **回答气泡「更多 → 设为目标」**：把刚生成的那份正文里的承诺转成目标草稿（沿用计划卡「改一句就解决」的编辑体验）。
3. **模型提议**：`task_requirement_propose` 的候选进「待确认」区，用户勾一下才算数。
> 不做的事：不在智能体配置页做「默认目标模板」（那是另一套语义，先不掺）；不做「目标模式自动开启」（计划自动开启刚被收紧过，同一时期再上一张拦路的卡必被投诉）。

### 7.2 目标卡（第五张人在回路卡，复用下沉输入框那套形）
状态与形态（`GoalCard.vue`，与 `PlanCard.vue`/`AskRecordCard.vue` 同族，走「裁决区整块顶替打字框」的既有契约）：

| 态 | 顶部 | 主体 | 操作 |
| --- | --- | --- | --- |
| 起草 | 「定个目标」 | 目标输入（4000 字帽 + 计数）；标准默认折叠成一行摘要 +「改」链接（新手主路径最小化） | 「开始」／「取消」 |
| 待确认 | 「等你确认验收标准」 | 逐条标准：`□ 采纳 / □ 不要`，每条显示「谁提的」（用户 or 助手）+ 验证方式（命令退出码 / JSON 结构 / 人工勾选） | 「确认并开始」 |
| 进行中 | 「进行中 · 第 3/8 轮 · 用了 4.2 万 token」 | 清单（复用右栏那份）+ 标准逐条状态（未验/通过/失败/已过期） | 「暂停」／「改目标」(=结束旧+起新)／「叫停」 |
| 等确认 | 「等你回答」 | 内嵌提问卡内容 | 答／忽略（超时不代答） |
| 验收中 | 「正在核验 N 条标准」 | 每条标准的验证器名 + 正在跑的第 i 条 | 无（自动，≤60s 一条，超时报 ERROR） |
| 达成 | 「已达成 · 全部 N 条有证据」 | 每条标准的 PASSED 摘要 + 证据入口（看那一轮的过程） | 「另起目标」 |
| 预算用满 | 「预算用满，还差 2 条」 | **未完成清单**（框架文档要求「不是悄然截断」）+ 剩余项 | 「加预算继续」／「就此收尾」 |
| 已结束 | 「已取消 / 未达成收尾」 | 定格只读记录，气泡里留痕 | 无 |

### 7.3 右栏「目标」面板（与清单卡同栏，切换不并排）
落点：右栏 `aside.right-panel`（`web/src/views/ChatPage.vue:990-1139`）里现成的「任务清单」卡位置（`:1004-1025`）扩成一个两档分段 `任务清单 | 目标`，状态仍由 `useChatEngine.sessionTodos` 那一族同源提供（`web/src/chat/useChatEngine.js:833-835`），新增同形状的 `sessionGoal`。切到哪档就只拉那档的数据，**拉不到必须报错 + 重试**（不许空态掩盖失败）。面板内容：目标原文、四条预算的「已用/上限」条、标准逐条结果、`STALE` 标记、暂停/继续/叫停三键、目标区间轮次跳转（按 `first_turn_id..last_turn_id` 定位气泡，逐气泡过程入口沿用 `SessionEventsPanel.vue`）。

### 7.4 H5
`mobileShell` 下：目标卡同样顶替打字框（与 PC 同契约）；右栏面板换成会话 sheet 里的一档，复用同一组件不重写（`SessionEventsPanel.vue` 那套「渲染与取数共用、外壳各端一份」的做法）。

---

## 八、验收怎么跑（这是目标模式的命门）

### 8.1 三类 verifier
| 类型 | 落点 | 实现 | 限制 |
| --- | --- | --- | --- |
| 退出码 | 沙盒里一条检查命令（测试、lint、`curl -sf`、脚本断言） | 框架 `ShellExitCodeVerifier` | 只能在轮外发起，走沙盒结构化端点（`/v1/file`·`/v1/shell`，避开 runtime 吞制表符那条坑） |
| 产物结构 | 交付物 JSON/HTML 的字段完整性 | 框架 `JsonResultSchemaVerifier`；产物从沙盒按 `artifactDeliveryTarget` 下载回读 | 需要 `actionId` 是「写过这个文件的那次工具执行」 |
| 人工勾选 | 观感、语气、口径这类无法机器判定的标准 | 问渠自建 `ManualCheckVerifier implements DeterministicVerifier`，读 `c_ai_goal` 里用户点的勾选 | 用户在卡片上点「达成这条」之前，outcome 一律 `UNKNOWN` |

verifier 由 `GoalVerificationRegistry` 按 `criteria_json.verifierId` 装配；**验证明显不许进模型工具面**（框架 javadoc：`Never registered as a model tool`），否则模型会自己给自己发合格证。

### 8.2 触发点（都是轮外）
1. 本轮 `run/end status=completed` 且模型正文含完成宣称 → 触发一次全量验证；
2. 目标预算告警（80%）时先验一遍，好让收尾有依据；
3. 用户点「现在验收」；
4. 每条标准的验证彼此独立：一条 ERROR 不把其它条打回。

### 8.3 工件指纹（`subjectVersion`）怎么算
`sha256(目标区间内最后一次写类工具触及的文件路径集合 + mtime + size)`，落在沙盒工作区目录（`sessions/{thread_id}` 挂载源）。
- **只有目标带工件类标准时才钉** `setSubjectVersion`；纯检索/纯写作类目标不钉，否则会因「没有工件」而永远验不了（`recordVerification` 拿不到 binding 直接抛 `IllegalStateException`）。
- 工件被改 → `invalidateSubject(rev)`，投影里旧结论显示 `STALE`，`ACHIEVED` 必须回落到 `RUNNING`（框架文档原话：「代码之后再次变化，旧的验证结论也可能不再适用」）。

---

## 九、预算账（框架没有，问渠自建，四条形同 `AgentxStepBudget`）

| 维度 | 已用取数 | 上限来源 |
| --- | --- | --- |
| 轮数 | `count(c_ai_message where turn_id ∈ 目标区间)` | 平台默认 + 个人上限（只往下收，与 P2 档位同形） |
| token | `c_ai_usage_log` 按 `uid+sessionId+kind=chat` 聚合，**含 `kind=compress` 与 `kind=subagent`**（deepseek-flash 恒思考那条对账纪律） | 同上 |
| 工具调用数 | `AgentxStepBudget` 已经在 `onActing` 逐次计数（`AgentxStepBudget.java:36-132`，上限 `agent.maxToolSteps` 默认 15、分支逐层减半），按目标区间累计 | 同上 |
| 墙钟 | **只数机器时间**：`TurnDeadline.machineMs` 已把人在回路等待从本轮墙钟里扣掉（`TurnDeadline.java:21-57` + `TurnRegistry.beginHumanWait/endHumanWait`），目标累计取每轮 `c_ai_message.tokens` 里那份机器毫秒，不把用户发呆的时间烧进预算 | 同上，0=不限 |

工作区核实：今天的闸**全是按轮或按调用**（`maxIters`=12、单工具超时=整轮 `chat.sseTimeoutMs`、步数=本轮、宽限=本轮、HITL 等待各自一键），**按会话跨轮的聚合预算在前后端与库里都不存在**——这四条账是新东西，但计数点一律复用，不新建第二处。

撞闸行为（框架文档 §2.1.2 明确要求，不能「悄然截断」）：
1. **80% 预警**：随 `done` 帧下发，面板上黄条 + 一条 `stage` 级提示；同时喂给模型一条 runtime 上下文块（走 `builder.contextSource("goal-budget", …)`，`ContextBlock.runtime`），让它下一轮自己**收敛范围、停新委派、优先可交付部分**；
2. **100% 撞闸**：不再 `submit` 新轮，状态 → `BUDGET_EXCEEDED`，界面出未完成清单；
3. 叫停/宽限中止时**必须自己结算**：`ended()` 只认 completed/cancelled，`failed` 与挂在提问卡上的 `suspended` 都不算结束（本仓第四次踩这条），所以目标循环退出前要把 goal 行写终态 + 发目标卡终态帧，不能指望框架兜。

---

## 十、跨轮续跑循环（控制流归框架，问渠只数预算）

```
用户确认目标 → goalService.start() → c_ai_goal(AWAITING_CONFIRMATION→RUNNING)
   └ 每轮：goalSyncBridge 覆盖写框架状态 → session.submit("goal:{goalId}:{n}", 该轮输入) → 事件照常上总线
        轮结束（await / run/end）→ 预算闸判定 → 验收判定（若该验）
             ├ 未达成且预算有余 → 组下一轮输入（见下）→ 继续 submit
             ├ 达成 → VERIFYING → ACHIEVED
             └ 撞闸 → BUDGET_EXCEEDED（停）
```

- **下一轮输入怎么组**：不带「请你继续」这类空话，带**当前目标 + 未通过/未验证的标准 + 剩余预算**三件套（`GoalCopy.continuation(goal)` 唯一出口，与计划文案同规矩：正文不写沙盒路径、工具名、命令）。
- **幂等**：`idempotencyKey = "goal:{goalId}:{n}"`，`turnId` 由框架 `UUID.nameUUIDFromBytes` 派生，进程重启后重发同一 key 不会重复起轮。
- **恢复**：优先 `resume(turnId)`（只允许最新未结束轮，且要求先答完待办、先结算不确定工具调用——这两条前置正好复用现有 HITL 恢复路径）；不满足就等新轮。
- **观众不在时谁发起下一轮**：后台轮没有浏览器通道，复用既有出口 `CollectingSseEmitter`（`CountDownLatch settled`，等 `done`/`error` 归零，定时任务/工作流子 agent/MCP 对外端点已经在用这条路），**不新造收集器**。调度复用既有单线程 `RESUME_POOL` 那套「答完没人跟→补一个跟随者」的机器（`FrameworkSessions.ensureFollowing` → `ResumeHandler.follow`，`AgentscopeChatEngine:1102-1168`），避免再造一个线程池把会话互斥绕过去。
- **接流**：跨轮时下发 `goal_turn` 帧，前端据此自动 `GET /chat/run` 探测 + `GET /chat/resume` 接上下一轮（`ChatRunBus.attach` 的 lock(快照+订阅) 与 seq/pos 去重都是现成的）。宽限豁免只给**挂在人在回路上的轮**；纯计算轮仍按 `chat.detachGraceMs`（180000）掐，且掐了要把 goal 打成 `PAUSED`（不是 `FAILED`），回来可续。
- **`session.resume(turnId)` 与 `inject(...)` 在全仓从未被调用过**（grep 核实）——它们是这条循环的现成机制但一次没真机跑过，M3 第一条就是把它俩各跑一轮：`resume` 前置两条（只允许最新未结束轮、须先清待办与不确定工具）都要拿到真实报错，别按 javadoc 当已验。
- 会话互斥：同会话串行由框架持有；目标循环期间用户插一句话走既有 steer/新轮路径，**目标循环让位**（用户优先），让位时 goal→`PAUSED` 并在界面说明「已暂停，可继续」。

---

## 十一、API / 事件契约

REST（与 `tool-approval` / `ask-user` / `plan-approval` 三族逐字同形）：

| 方法 | 路径 | 载荷 / 返回 |
| --- | --- | --- |
| POST | `/api/ai/chat/goal` | `{sessionId, objective, criteria:[{kind,text,verifierId,verifierParams}], budget:{turns,tokens,toolCalls,minutes}, action:"start"}` → `{goalId, status, revision}` |
| POST | `/api/ai/chat/goal/{goalId}` | `{action:"confirmCriteria"|"pause"|"resume"|"extendBudget"|"verifyNow"|"finish", criteria:[{id,status}], extraTurns, extraTokens}` → `{goalId, status, stateRevision, syncWarn}` |
| GET | `/api/ai/chat/goal/pending?sessionId=` | `{goal, live, remainingMs, criteria:[…], verifications:[…], budget:{used,max}}`（重启/换副本回 `live:false` + DB 那份） |
| GET | `/api/ai/chat/session/{id}/goal` | 当前/最近一条目标（右栏回显，读 DB 不读框架） |
| GET | `/api/ai/chat/session/{id}/goal/events` | 按 `goal_id` 聚合的账本（过程面板深链） |

SSE 事件走既有事件词汇表 `com.wenqu.ai.engine.RunEventType`（现 28 条，含 `todo`/`plan_approval`/`ask_user`/`usage`/`done`），新增两条并**三处同步**——枚举、`web/src/api.js:93-111 CHAT_EVENT_CB`、镜像/账本落点，多一条、少一条、拼错都让前端静默收不到：

| 新增枚举 | sseName | 载荷 | 是否进镜像/账本 |
| --- | --- | --- | --- |
| `GOAL_STATE` | `goal_state` | `{goalId,status,objective,criteria[],verifications[],budget:{used,max},turnIndex,syncWarn}` | **进** `ChatRunBus.MIRROR_TYPES`（状态型，断线重连要能重建）+ 进 `ChatRunRuntime.LEDGER_SSE_TYPES` 白名单落 `c_ai_session_event` |
| `GOAL_TURN` | `goal_turn` | `{goalId,n,turnId,startedAt,reason}` | 不进镜像（帧），进账本 |

> 新增事件名的三处登记缺一不可：`RunEventType` ⇄ `api.js CHAT_EVENT_CB` ⇄（要落账本时）`LEDGER_SSE_TYPES`。漏第二处＝前端收不到且不报错；漏第三处＝过程面板里那段历史凭空消失。

复用不新写：`tool_status`、`todo`、`usage`、`done`。**`done` 语义不变**（仍是「这一轮结束」），跨轮不靠 done 骗前端；目标续跑由 `goal_turn` + 前端自动接下一轮驱动。

验收事件进账本：`type=goal_verify`，payload 带 `requirementId/outcome/verifierId/actionId/binding`（框架 `VerificationService` 的 `CustomEvent("task_verification", …)` 已带这五个字段，照搬不另立）。

---

## 十二、配置归属（按四档口径落，别写代码例外）

| 键 | 档 | 默认 | 说明 |
| --- | --- | --- | --- |
| `chat.goalMode.enabled` | 平台全局 | **false** | 总闸；关时工具面不挂、投影不开、卡片不出现 |
| `chat.goalMode.defaultTurns` | 平台默认 + 个人上限 | 12 | 单目标轮数上限，个人只能往下收 |
| `chat.goalMode.defaultTokens` | 平台默认 + 个人上限 | 1000000 | 同上 |
| `chat.goalMode.defaultToolCalls` | 平台默认 + 个人上限 | 120 | 同上 |
| `chat.goalMode.defaultMinutes` | 平台默认 + 个人上限 | 30 | 同上 |
| `chat.goalMode.verifyTimeoutSeconds` | 平台全局 | 60 | 单条验证器超时→`ERROR` |
| `chat.goalMode.requirementProposals` | 平台全局 | true | 是否注册 `task_requirement_propose` |
| `goalEnabled`（`c_ai_agent` 列） | 资源级三态 | off | 与工具挂载同源判据 |

三条纪律（都是本仓点过名的坑）：
- `config-schema.json` 里 `group + "." + key` 必须**逐字等于** `backendKey`，否则 `ConfigSchemaService` 构造期 fail-fast、应用起不来。
- 默认开的开关**必须登记进 `ConfigService.defaults()`**（`ConfigService.java:281+`），否则 `snapshot()` 不回显、设置页改完看着像没生效；启动自检 `auditDefaultOnSwitches` 会点名漏落点的键。
- `defaults()` / `config-schema.json` / `schema.sql` 清理名单**三处清单两两对账**；改 section 后跑越界自检（section 是 0-based 下标，越界不报错但设置页里消失）。**新键一律不进** `schema.sql:969-1058` 那份 `DELETE FROM c_ai_config` 退役名单。
- **个人上限那四条不能只加 `personal: true`**：工作区核实当前 schema 里**零个** `personal` 字段（只有 `config-schema.json:1257` 一个 `personalOnly`），这条路没有先例可抄。按既有先例走 `c_ai_user` 加列那一档（记忆自动沉淀 `schema.sql:413` + `OrgService.memoryFlushEnabled:221-224`），并且**必须同时挂 `FrameworkSessions.markConfigStale`（`:784-790`）重建常驻实例**——否则改了个人上限对在跑的会话无效，症状是「改了没生效」。

---

## 十三、用户可见文案（只讲用户结果，不写内部机制）

| 场景 | 文案 |
| --- | --- |
| 入口按钮 | 设为目标 |
| 面板标题 | 目标 |
| 进行中 | 进行中 · 第 {n}/{max} 轮 · 已用 {tokens} token |
| 预警 | 预算快用完了，我先把能交付的部分收口，还差 {k} 条没验过。 |
| 撞闸 | 预算用满，这件事还没做完。<br>已完成：…<br>还差：…<br>要继续的话给我加点预算。 |
| 达成 | 已完成，{N} 条验收标准都有凭据。 |
| 过期 | 这条结论是在旧版本产物上验的，需要重新验。 |
| 暂停 | 已暂停，随时可以继续。 |
| 同步异常 | 目标和助手看到的不一致，本轮以这份为准重新同步。（不许静默） |
| 手册（`manual/03-对话与问答.md`） | 新增「目标模式」一节：目标≠计划、什么算完成、预算用满会怎样、验收凭据哪来的 |

禁止出现：`TASK_STATE`、`scope`、`beginTask`、`verifier`、`evidenceBinding`、`revision`、框架名。

---

## 十四、本仓已知坑 × 目标模式的落点（提前排掉）

| 坑 | 会咬到哪 | 处置 |
| --- | --- | --- |
| 弹性线程没重放 RequestUser/检索覆盖 → 静默空 | 验收在轮外跑、`updateAgentState` 的调用线程都不是流水线线程 | 跨线程显式带 uid/sessionId，不靠 ThreadLocal |
| `inspect()` 只是只读快照 | 面板实时性 | 实时走 `goal_state` 帧，快照只兜重启 |
| 轮终态语义（`failed`/`suspended` 不算结束） | 循环不退出、白烧预算 | 判定用 `run/end status` 原文 + 自己结算 |
| `fastjson` 不认 record | `TaskVerification`/`TaskRequirement` 落库变 `{}` | 一律手工组 Map（`TodoBridge.docJson` 那套形状），或走 Jackson |
| 中文 SQL 静默不匹配 / 保留字 | 建表与查询 | 走 `SchemaMigrator`，列名避 `usage` 等保留字 |
| runtime 吞制表符 | 退出码类验证器跑脚本 | 走 `/v1/file`·`/v1/shell` 结构化端点 |
| Vue 图标 kebab 静默失配 | 目标卡图标 | 用已导入名，改完真机看一眼 |
| antdv `popupMatchSelectWidth` 类静默失配 | 目标预算下拉 | 只写 4.x 存在的 prop |
| SSE 事件契约三处同步 | 新增 `goal_state`/`goal_turn` 漏登 `api.js CHAT_EVENT_CB` | 前端静默收不到且不报错；改完人工对账枚举、事件回调、落点三处清单 |
| 改了配置不重建常驻实例 | 个人预算上限、`requirementProposals` 投影开关 | 都要挂 `FrameworkSessions.markConfigStale`（`:784-790`）；投影参数是 build 期装配，对在跑的会话无效 |

---

## 十五、分期与工作量（完整版三刀，不是先交半版）

| 刀 | 内容 | 验收判据 | 人日 |
| --- | --- | --- | --- |
| **M1 目标进模型 + 卡片（不自动续跑）** | 投影参数装配（含那条待实测的假设验伪）、`c_ai_goal` 建表、`GoalBridge` 三个写口、第五张卡（起草/待确认/进行中/暂停/叫停）、`goal_state` 帧与镜像、右栏面板 + H5、配置八项与三处对账、手册一节 | 真机：设目标后跑一轮，`model/dispatch` 事件的模型输入里能看到 `<TASK_STATE>` 的 Objective 与 `<REQUIREMENTS>`；确认标准后 `decide` 落进框架状态；重启浏览器目标还在 | 14–18 |
| **M2 验收与证据** | `GoalVerificationRegistry`、三类 verifier（含 `ManualCheckVerifier`）、`subjectVersion` 指纹与 `STALE`、`verification/result` 落 `c_ai_session_event`、验收中/达成/过期三态、`ACHIEVED` 判定只看验证 | 真机：让它在沙盒生成 `report.json` + 一条 `JsonResultSchemaVerifier` 标准 → 达成；再改一次文件 → 结论转 `STALE`、状态回 `RUNNING` | 10–14 |
| **M3 预算与跨轮续跑** | 四条预算账与取数、80% 预警（`contextSource("goal-budget")`）、撞闸停轮与未完成清单、`submit(idempotencyKey)` 续跑循环 + `CollectingSseEmitter` 后台轮、**首件把全仓零调用点的 `session.resume(turnId)` 与 `inject()` 各真机跑一遍（两条前置约束都要拿到真实报错）**、`goal_turn` 帧与前端自动接流、宽限/暂停/让位结算 | 真机：设 3 轮上限的多轮目标，看到它自己起第 2、3 轮；第 4 轮不起、界面出未完成清单；中途关页面再开能接上；`/usage` 对得上 | 12–16 |

合计 36–48 人日。M1 独立可用（用户手动一句句推也能跑），M2/M3 各自可独立验收——**不做「先只出卡片」的半成品**，三刀都在本设计范围内。

---

## 十六、真机验收夹具（跑法沿用本仓探针纪律）

1. **投影真到了**：开 `SessionLogArchive`/`request/prepared` 取证，断言模型输入含 `Objective: <用户原文>`；只看 `task/changed` 事件不算（那只证明状态变了）。
2. **权威分离**：让模型「宣布目标已达成」，断言 `status` 仍非 `ACHIEVED`（判据只看 verifications）。
3. **候选≠批准**：让模型调 `task_requirement_propose`，断言投影里那条是 `CANDIDATE`，用户点采纳后才变 `CONFIRMED`。
4. **同轮不能验自己**：断言一条标准不可能被同一轮内的动作验过（`action/end` 未结算时 verify 抛「Settled action evidence unavailable」是预期）。
5. **换副本/重启**：`kill` 后端重启，断言右栏目标与标准回来（读 DB），且下一轮开跑前重新同步到框架（比对 `state_revision`）。
6. **预算对账**：与 deepseek-flash 恒思考那条一起算，思考 token 计入 used；断言 80% 帧在预警轮出现、100% 轮不再 `submit`。
7. **双跑探针三条自坑**：先查上游额度、临时改的设置 finally 还原并回查 DB 列、断言走「流里 + 库里」。
8. **投影参数装配那条待实测假设（M1 第一条必跑）**：拿 `request/prepared`/归档看 `<TASK_STATE>` 是否出现。若 `TaskContextOptions` 必须搭配 `enableTaskList(true)` 才生效，立刻按 §六 那条备选（目标写进 `sysPrompt` 拼接）收口，**不要顺手把框架 `todo_write` 挂上**——它会与问渠 `writeTodo` 的 `doing/done` 词面在同一个上下文里撞车（`AgentscopeChatEngine:2702-2708` 那条注释就是这个原因）。
9. **不静默判据**：把 `chat.goalMode.enabled` 关掉，断言工具面不挂、投影无目标、界面无入口三件事同时成立（关掉还喂目标进模型 = 又造出一处「提示词教了、能力没挂」的漂移）。

---

## 十七、分岔（附推荐）

**分岔 1：目标跨轮之间要不要拦用户一下？**
- A｜全自动续跑，撞闸或达成才停（推荐）。理由：这是「交给它一件事」的全部价值；宽限与预算已兜住失控成本；暂停/叫停/加预算三颗键常驻面板。
- B｜每轮之间要用户点「继续」。理由：更保守。后果：目标模式退化成「带上下文的多轮问答」，用户离开页面就不推进——B 治不了「长任务要人盯」这个当前症状，不作为方案并列。

**分岔 2：要不要给模型 `task_requirement_propose`？**
- A｜给（推荐）。模型能提「我理解这条标准是…」，用户在卡上采纳，比让用户从零想验收标准省力；框架保证「提议不等于批准」。
- B｜不给，标准只能用户写。代价：小白写不出可验证的标准，目标模式落地率低。注意 `allowRequirementProposals()` 会连带开启 requirements 投影（框架 builder 里有这个耦合）。

**分岔 3：`ACHIEVED` 是否必须有机器证据？**
- A｜人工勾选也算一种 verifier（推荐）。理由：问渠大量目标是观感类（文案、口径），只要走 `recordVerification` 就有凭据、有 STALE 语义，不破坏「完成由证据决定」。
- B｜只认机器验证。代价：观感类目标永远进不了 `ACHIEVED`，只能 `ENDED_INCOMPLETE`。

**分岔 4：目标与计划卡的关系？**
- A｜并存，目标优先（推荐）：已有目标时计划卡只在目标内部需要拍板方案时出，由提示词纪律约束（「目标已定 → 别再开计划轮」）。
- B｜有目标就禁计划模式。代价：不易回头的大动作失去事前对齐，与 P2 档位「危险动作要审」的口径冲突。

---

## 十八、和既有资产的关系（复用清单）

| 既有 | 目标模式怎么用 |
| --- | --- |
| `TodoBridge` | 形状直接照抄（护栏在问渠、存储在框架、读回同源）；清单继续是目标内部的执行进度，不动 |
| `AgentxStepBudget` | 工具调用计数复用，目标维度做累计口径，不新建计数点 |
| `ChatRunBus` + `c_ai_session_event` | 跨轮观众接流与过程回放，一帧都不新写传输层 |
| 人在回路下沉输入框契约 | 第五张卡沿用同一形态与「超时不代答」口径 |
| `c_ai_usage_log` + `MeteredModel` | token 预算取数唯一来源（`kind=chat\|compress\|subagent`） |
| `config-schema.json` / `defaults()` / `schema.sql` 三处对账 | 八项配置按既有纪律落，含启动自检 |
| `ArtifactToolProvider` / 沙盒工作区 | `subjectVersion` 指纹与 `JsonResultSchemaVerifier` 的证据源 |
| 子智能体 `agent_spawn` | 预算接近阈值时停新委派（框架文档要求），由 `contextSource` 提醒，不改框架递归闸 |
