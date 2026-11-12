# 插话 / 待发队列 按 DSH 路线改造

> 目标：把「运行中插话」和「等下一轮再发」从**前端内存 + 按轮把手**，改成 **inbox 式准入 + 服务端持久投影**。
> 借架构不搬代码：实现全部落在现有 `agentscope-java` 框架与问渠服务层。

---

## Context

现状三个症状，均已定位到结构原因（不是偶发）：

1. **目标轮（深度执行的 `goal:…` 续跑轮）期间插话 100% 失败**
   后台续跑轮不登记 `TurnHandle`（唯一登记点在 `ChatService.java:355`；引擎注释见 `AgentscopeChatEngine.java:1540`），
   而 `/chat/steer` 的准入第一行就是取把手（`ChatRunRuntime.java:394`）。即使框架那一跑活得好好的，也一律 `accepted=false`。
   前端已承认这一点（`web/src/chat/sendFlow.js:31` 注释：「后台目标轮没登记 TurnHandle，steer 必然失败」），
   所以主输入框改走「让位一轮」，但「插话调整·不中断」和插话队列还在走 steer → 排队 → 每 2.5s 重试 → 永远失败，
   每点一次「立即发送」多一条 toast（`web/src/chat/steerQueue.js:99`）。

2. **带图插话插不进去**：`/chat/steer` 只收 `text`（`ChatController.java:489` 用 `Map<String,String>`），
   前端因此把带材料的条目整体 defer 到下一轮（`web/src/chat/steerQueue.js:6`）。框架本身支持 `AgentSession.steer(List<Msg>)`，多模态是我们的接口收窄。

3. **刷新丢队列**：队列是纯前端 `ref`，没有任何持久化；「排队中·目标轮开跑时自动送达」在刷新后静默失效。

对照 DSH（本机源码 `~/IdeaProjects/deepseek-harness`）的「基本路线」：

| DSH | 我们现状 | 改造后 |
|---|---|---|
| `agent.steer()` → inbox `next-step`（`packages/core/agent-loop/src/agent.ts:183`） | `AgentSession.steer(Msg)` 已有 | 复用，不动 |
| `agent.followup()` → inbox `next-turn` | 前端本地数组 + 定时重试 | 服务端表 `c_ai_steer_item` + 轮开跑时 drain |
| inbox 是**持久投影**（`packages/core/agent-loop/src/inbox.ts:2`） | 前端 `ref`，刷新即丢 | `GET /chat/steer/queue` 投影 |
| 队列 UI 直接读投影、带 attachment ref（`packages/client/ui-conversation/src/client/queue/QueueDock.tsx:106`） | 本地数组 | 前端改读投影 |
| steer 载荷是 `UserMessage`（天然多模态） | 只收 text | 收 `images[]`，交付 `Msg(TextBlock+ImageBlock)` |
| 准入在 agent 层，不区分轮来源 | 准入绑 `TurnHandle` | 准入 = 会话有活 run（`ChatRunBus`） |
| 停止用 `cancel({kind:'user'},{keepInbox:true})` | 队列项在暂停后仍在重试 | 暂停不清队列，轮开跑自动再送 |

框架侧已经有一半：`SessionInbox` 本身是**持久命令**（`io/agentscope/core/session/SessionInbox.java:34`「Durable command acceptance」），
已送进框架的插话重启后仍会送达。缺的只是「还没送进去的那一段」和「谁有资格送」。

---

## 不变量（任何一批都不许破，已按反例修正）

1. **身份以「活 run」为准，handle 只作同轮补充**（原「handle 第一判据」有 bug）：
   ```
   run = chatRunBus.running(sid); h = TurnRegistry.handle(sid)
   run != null              → 身份 = run(uid, turnId)；stopped 只在 h.turnId==run.turnId 时取 h.stopped
   run == null && h != null → 身份 = h（收集型通道的轮不登记总线）
   都没有                    → 拒
   ```
   反例：用户轮刚收尾、handle 未摘而目标轮已开跑。handle 优先会把目标轮的插话记到已结束的用户轮上，
   用户轮被 stopped 过还会误拒目标轮。
2. **服务端绝不自己起轮**：待发队列只 `steer` 进活着的轮，永不 `submit`；单轮互斥、目标让位不动。
   drain 必须发生在框架 `submit(...)` **之后**（用户轮 `AgentscopeChatEngine.java:781` 之后；目标轮
   `continueGoalTurn` 的 `f.session().submit(...)` 之后），抢锁/登记总线之前 drain 只会空转、延迟一整轮。
   队列项必须有终态收敛（目标取消/会话删除 → DROPPED 或可见的失败行），不留僵尸 PENDING。
3. **先送框架、后落库，图片落盘也算落库**：交付用内存 data URL（`buildMultimodalMsg` 不需要落盘），
   `processDirect/processDisplay` 与消息行都排在 delivery 成功之后——否则拒收会留下孤儿图片文件。
4. **不新增请求路径上的定时器**：队列 drain 由「轮开跑」事件触发。
5. **不静默丢话**：任何失败都要落到可见状态（行内状态或收尾补发）。
6. **收据身份不和把手混用**：收据按 turnId 分桶，handle 的 stopped 不能当作活轮状态。

---

# 批 1：准入 + 收据台账 + 多模态（止血，后端为主）

> 解决症状 1、2。用户轮零变更，可独立上线。

## 1.1 收据台账从 `TurnHandle` 挪到按 turnId

**原因**：`inbox/applied` 时靠把手找那句插话（`AgentscopeChatEngine.java:5032`），收尾 `pendingSteers` 也靠把手（`:5041`）。
目标轮没把手 ⇒ 即使准入放行，时间线 steer 段与收尾对账都会丢。

**新文件** `src/main/java/com/wenqu/ai/agent/chat/SteerReceipts.java`

- `Map<String turnId, CopyOnWriteArrayList<SteerAck>>`（turnId 全局唯一 UUID / `goal:…`，无需会话命名空间）。
- API：`add(turnId, ack)`、`find(turnId, receiptId)`、`unsent(turnId, appliedSet)`、`clear(turnId)`。
- 上限保护：超过 512 个 turnId 按插入顺序淘汰最旧（与 `GoalService.abortedTurns` 同一思路）。

**改点**

- `TurnHandle.java`：删掉 `steerAcks` 组件（构造点只有 `ChatService.java:355` 与便捷构造器；`steerAcks` 只在 4 处被读写）。
- `ChatRunRuntime.steerTurn`：`h.steerAcks().add(...)` → `SteerReceipts.add(turnId, ...)`；并补 `SteerReceipts.clear(turnId)` 的调用点见下。
- `AgentscopeChatEngine`：
  - `translate(SessionEvent, RunChannel, Recording)`（`:3359`）加一个 `String turnId` 形参，调用点 `:3205` 传入（该处在 `followTurn` 内，turnId 在作用域里）；
  - `steerAckOf(sessionId, receiptId)` → `steerAckOf(turnId, receiptId)`；
  - `unsentSteers(handle, turnId, recording)` → `unsentSteers(turnId, recording)`（去掉 handle 判据，turnId 相等即可）；
  - 清理：`followTurn` 发完 done 后、`startFollowing` 的 `finally`、用户轮 pipeline 的 finally 各 `clear(turnId)`。

**验收**：`SteerReceiptsTest`（两轮并发写读不串、清理后读不到）+ 现有 `unsentSteers` 行为不停。

## 1.2 准入口径：无把手时认「会话有活 run」

`ChatRunRuntime.steerTurn` 改为「活 run 定身份、handle 只补 stopped」：

```
TurnHandle h = TurnRegistry.handle(sid)
ChatRunBus.Run run = chatRunBus.running(sid)
actor =
  run != null  -> { turnId: run.turnId(), uid: run.userId(),
                    stopped: (h != null && run.turnId().equals(h.turnId())) ? h.stopped().get() : false }
  h != null    -> { turnId: h.turnId(), uid: h.userId(), stopped: h.stopped().get() }   // 收集型通道
  else         -> null   // 拒绝
拒绝：actor == null || text 空 || actor.stopped || !uid.equals(actor.uid)
```

- 为什么不是「handle 优先」：用户轮刚收尾、handle 未摘而目标轮已开跑时，handle 身份是**已结束的用户轮**，
  会把目标轮的插话记错轮次；用户轮被 stopped 过还会误拒目标轮（反例见 §不变量 1）。
- 落库 `MessageWrite.turnId(actor.turnId())`：目标轮就是 `goal:…`，与助手行 `framedTurnId` 同口径（`AgentscopeChatEngine.java:4819`）。
- `sessionEventService.append(..., actor.turnId(), "steer", ...)` 同步。
- 已知边界：`chat.resumeEnabled=false` 时总线不登记 run，目标轮将无法插话（默认 true）；记为配置约束，不做第二套注册表。

**验收**：准入矩阵单测（见 §9）——用户轮 / 目标轮 / 无轮 / 非本人 / 已停止 / 让位同刻。

## 1.3 多模态插话

**后端**

1. `ChatController.steerChatTurn`：`Map<String,String>` → DTO `SteerRequest { sessionId, text, images[] }`；
   图片校验抽公共方法（从 `ChatController.java:190-208` 抽出 `validateImages`，chat 端点与 steer 共用）。
2. `ChatRunRuntime` 注入 `UserImageService`，顺序必须是「**交付 → 再落盘**」：
   - `steerTurn(sessionId, uid, text, images)`；
   - 交付用**内存 data URL**：`deliver(uid, sid, decoratedText, dataUrls)`（`buildMultimodalMsg` 只吃 dataUrl，不需要先落盘）；
   - delivery 成功后再 `userImages.processDisplay(images)` 落盘拿回显 URL（`processDisplay` = 只落盘不保留 dataUrl，
     模型侧字节已经在交付那一份里），再写 `MessageWrite.images(urls)`；
   - delivery 失败 → 不落盘、不写消息行（否则留孤儿图片文件，违反不变量 3）。
3. `SteerDelivery` 接口：`String deliver(String userId, String sessionId, String modelText, List<String> imageDataUrls)`。
4. `FrameworkSessions.steer(userId, sessionId, modelText, imageDataUrls)`：
   - 取 `Held.modelRef()`，用**注入的** `Predicate<String> visionCapable` 判（在 `AgentxConfiguration` 里以 `modelRegistry::visionCapableOf` 注入，那里已有 registry，见 `AgentxConfiguration.java:20`）；
   - 可读图且有图 → `TurnPromptAssembler.buildMultimodalMsg(text, dataUrls)`（把该方法改成 `static`，它只用 text+dataUrls）→ `session.steer(List.of(msg))`；
   - 返回 null（data URL 异常）或不可读图 → `session.steer(text)`（图片仍按 URL 落库回显，与正常轮一致）。

**前端**

- `web/src/api.js` `steerChatTurn(sessionId, text, images = [])`，body 带 `images`。
- `web/src/chat/steerQueue.js`：`steer(...)` 透传 `item.images`；`steerNeedsNextRound` **去掉 `images`**
  （仍 defer：`atts / skills / mentions / historyRefs / planMode`）。
- `sendSteer` 的纯文字分支条件同步调整。

**已知口径**：回答时间线里的 `steer` 段目前只有文字，图片只在用户消息行可见。若要段内也显图，放到批 3（`steer` 帧加 `images` + 重生成事件契约）。

---

# 批 2：待发队列服务端化（刷新不丢）

> 解决症状 3，并让「等下一轮」不再依赖前端定时器。

## 2.1 表

`src/main/resources/db/migration/V4__steer_queue.sql`（`CREATE TABLE IF NOT EXISTS`；存量库 baseline=1，Flyway 会正常跑到 V4）

```sql
CREATE TABLE IF NOT EXISTS `c_ai_steer_item` (
  `id`         varchar(64)  NOT NULL,
  `session_id` varchar(50)  NOT NULL,
  `user_id`    varchar(64)  NOT NULL,
  `turn_id`    varchar(64)  DEFAULT NULL COMMENT '送达时那一刻的轮（goal:… 或用户轮 id）',
  `text`       text         NOT NULL,
  `images`     text         DEFAULT NULL COMMENT 'JSON 数组：回显 URL',
  `status`     varchar(16)  NOT NULL COMMENT 'PENDING/STEERED/APPLIED/DROPPED',
  `attempts`   int          NOT NULL DEFAULT 0,
  `last_error` varchar(200) DEFAULT NULL,
  `created_at` datetime(3)  DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` datetime(3)  DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_session_status` (`session_id`, `status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 2.2 服务与 API

新增 `SteerQueueService`（mybatis-plus mapper + 状态机）：

| 方法 | 语义 |
|---|---|
| `enqueue(sid, uid, text, images)` | 落 PENDING，回投影 |
| `listPending(sid, uid)` | 队列投影（PENDING/STEERED 两种都回，前端据此显示「排队中 / 已送出等听到」） |
| `claimAndSteer(sid, uid, itemId)` | CAS `PENDING→STEERED` 后调 `steerTurn`；失败回滚 PENDING + attempts+1 |
| `drop(sid, uid, itemId)` | PENDING→DROPPED |
| `drain(sid)` | 按 `created_at` 顺序对 PENDING 逐条 `claimAndSteer`；失败静默留 PENDING（下次轮开跑再试） |
| `markApplied(turnId, receiptId)` | 由 `inbox/applied` 回调，把对应项置 APPLIED |

接口（全部 `@Permission("chat:use")`，归属校验同插话）：

```
POST   /chat/steer/queue            {sessionId,text,images?}  -> item
GET    /chat/steer/queue?sessionId  -> {items:[...]}
POST   /chat/steer/queue/{id}/steer -> {accepted,messageId?}
DELETE /chat/steer/queue/{id}       -> {ok}
```

**drain 时机**（不引定时器，且必须在框架 `submit` 之后）：
1. 用户轮 `session.submit(...)` 之后（`AgentscopeChatEngine.java:781` 的 if/else 之后立即 drain）；
2. 目标轮 `f.session().submit(...)` 之后（`continueGoalTurn` 里、`openGoalTurnRun` 之前或紧随其后）；
3. `inbox/applied` → `markApplied`（收据 → 项）。
4. 终态收敛：目标取消/会话删除（`GoalService.finish`、会话删除入口）把该会话 PENDING 项置 `DROPPED`，
   由 `steer_queue` 帧与 GET 投影把结果交给界面；不允许留下永远排队的僵尸行。

> 为什么不能在「抢到轮锁/登记总线」时 drain：那一刻框架还没 submit，`steer` 必抛
> 「No running task accepts steering」，队列项会原地等到**下一轮**，正好延迟一整轮。

**推送**：每次状态变化用 `chatRunBus.publishMirrorToSession(sid, "steer_queue", json)`（与 `GoalService.emitState` 同一手法，不依赖某个通道）。
新增 `RunEventType.STEER_QUEUE("steer_queue", "onSteerQueue", "插话队列", null, false, true)`，并重生成前端契约。

**多副本**：claim 用状态 CAS；「已 claim 未送达」崩溃窗口 TTL 后回滚重试，代价是极小概率重复一句（框架 `steer` 无幂等键）——记为已知限制，不做假幂等。

## 2.3 前端改投影

- `web/src/api.js`：四个新方法。
- `web/src/chat/steerQueue.js` 重写为投影驱动：
  - 本地只保留「正在编辑的草稿」；列表来自 `GET /chat/steer/queue` + `onSteerQueue` 帧；
  - 入队 / 立即发送 / 删除都走接口，乐观更新失败回滚；
  - 删掉 `pumpPlanAdjust` 那套 2.5s 重试与 `PLAN_ADJUST_TTL`（服务端 drain 接管）；
  - `立即发送` 失败只在行内显示「排队中·等轮开跑」，不再弹 toast（替换 `steerQueue.js:99`）。
- `sessionSwitch`：进入会话时拉一次队列恢复。
- `planActions.js` A 档：`steerPlanAdjustment` 失败 → `queuePlanAdjust` 改为调 `POST /chat/steer/queue`（同步接口，不再依赖 `goalRunning` 判断）。

---

# 批 3：收尾口径

1. **进度不冒充运行**：`web/src/chat/plan.js` `planExecProgress` 在 `running=false` 时把 `doing` 归一为 `wait`
   （快照分支恒为 false）；`useChatEngine.js:623` 的 `running` 加「目标已暂停」门（`sessionGoal.status === 'PAUSED'`）。
   效果：暂停后卡片立刻不转圈、不写「进行中」，不用等轮真正收尾。
2. **可选**：`steer` 帧带 `images`，时间线段内渲染缩略图（需重生成 `eventContract.generated.js`）。
3. **H5 对齐**：`MobileChatPage` 的队列条改读投影；其余走同一 composable，自动生效。

---

## 9. 测试矩阵

**后端**

- `SteerAdmissionTest`：用户轮 / 目标轮（run 在、无 handle）/ 无轮 / 非本人 / stopped /
  **handle 与 run 并存但 turnId 不同（用 run 身份，且不继承 handle.stopped）** / handle 在、run 不在（收集型通道）。
- `SteerReceiptsTest`：两轮并发写读不串；`clear` 后 `find` 为空；超 512 淘汰最旧。
- `SteerMultimodalTest`：1 图 / 多图 / 坏 data URL（退回纯文本）/ 超限 400 / 不可读图模型只送文本 /
  **交付失败时图片目录无新增文件、无消息行**（不变量 3）。
- `SteerQueueTest`：CAS 并发（两副本同时 drain 只送一次）、顺序、失败留 PENDING、`inbox/applied` 置 APPLIED、
  **框架 submit 之前 drain 不消费（仍 PENDING）**、目标取消后 PENDING 项转 DROPPED。
- 现有 `AgentxNativeToolGuardTest` 等不回归。

**前端**

- `steerQueue.test.mjs`：带图不再 deferred；投影恢复；立即发送失败不重复 toast。
- `plan.test.mjs`：非 running 时 `doing→wait`；running 时保持 `doing`。
- `planActions.test.mjs`：A 档失败改为入服务端队列。

---

## 10. 验证（每批都跑）

```bash
# 前端单测
cd web && npm test

# 后端编译 + 指定测试（本机可用 IDEA 自带 maven 或 /private/tmp/apache-maven-3.9.16/bin/mvn）
/private/tmp/apache-maven-3.9.16/bin/mvn -o -q -DskipTests compile
/private/tmp/apache-maven-3.9.16/bin/mvn -o -q -Dtest='SteerAdmissionTest,SteerReceiptsTest,SteerMultimodalTest,SteerQueueTest' test

# 事件契约（改 RunEventType 后）
/private/tmp/apache-maven-3.9.16/bin/mvn -q -Dtest=RunEventContractGenerator -Dwenqu.generateEvents=true test
```

链路冒烟：用 `wenqu-backend-verify` skill 起临时副本（8096 + 临时库，不动 IDEA 里那个实例），
自签 JWT 直连：① 用户轮在跑时 steer ② 目标轮在跑时 steer ③ 暂停后 steer 被拒 ④ 带图 steer ⑤ 刷新后 GET queue 恢复。

---

## 11. 风险 / 已知限制

| 风险 | 对策 |
|---|---|
| 让位交接时收据串轮 | handle 优先、不混用；收据按 turnId 分桶 |
| 崩溃在「已 claim 未送达」窗口 → 极小概率重复一句 | TTL 回滚；记为已知限制，不做假幂等 |
| 目标轮 owner 判据被绕过 | 只认 `run.userId()`，与 `/chat/run` 同源 |
| 图片体积 | 复用 chat 端点同一套数量/体积校验 |
| 消息 turnId 变 `goal:…` 影响历史分组 | 与助手行 `framedTurnId` 同口径；目标页「跳到这一轮」更准 |
| 暂停仍等当前步收尾（你看到的约 10s） | 框架协作式中断，改不了；批 3 只修 UI 口径，不假装能抢占 |
| 重启后旧收据丢失 | 现状已存在；已进框架 inbox 的仍会送达，只是收尾对账覆盖不到 |

---

## 12. 交付顺序（照着做）

- [ ] 批 1-1 `SteerReceipts` + `TurnHandle` 去字段 + 引擎四处读写替换（含 `translate` 加 turnId）
- [ ] 批 1-2 准入三段解析 + 消息 turnId 归属 + 准入矩阵单测
- [ ] 批 1-3 DTO/校验抽取 + `UserImageService` 接入 + `FrameworkSessions` 多模态 + 前端透传 images
- [ ] 批 1 验证：前端 + 后端测试全绿；临时副本冒烟 ①②④
- [ ] 批 2-1 V4 迁移 + `SteerQueueService` + 四个接口 + `STEER_QUEUE` 帧（重生成契约）
- [ ] 批 2-2 drain 三个触发点 + `markApplied` 接线
- [ ] 批 2-3 前端改投影 + 删本地重试 + 会话切换恢复
- [ ] 批 2 验证：冒烟 ③⑤ + 两副本并发 drain
- [ ] 批 3-1 进度口径（`plan.js` + `useChatEngine`）
- [ ] 批 3-2（可选）steer 帧带图 + H5 对齐
- [ ] 全量回归：用户轮插话、目标轮插话、带图插话、刷新恢复、暂停/继续、计划调整不中断

---

## 13. 落地记录（2026-11-12）

### 已完成

**批 1（准入 / 收据 / 多模态）**
- 新增 `agent/chat/SteerReceipts`：收据按 turnId 分桶（512 上限淘汰），`TurnHandle` 删掉 `steerAcks`。
- `ChatRunRuntime.steerTurn` 准入改为「活 run 定身份、handle 只补 stopped」；纯判据抽成
  `resolveSteerActor` 并覆盖 6 个矩阵用例（含「旧 handle + 新目标轮」反例）。
- `/chat/steer` 收 `images`（`SteerRequest` + `validateImages` 与正常提问共用）；
  交付用内存 data URL，**收下之后**才 `processDisplay` 落盘、写消息行（拒收不留孤儿文件）。
- `FrameworkSessions.steer(..., imageDataUrls, visionCapable)`；`TurnPromptAssembler.buildMultimodalMsg` 静态化。
- 前端 `steerNeedsNextRound` 去掉 images；`api.steerChatTurn` 透传图片。

**批 2（服务端队列）**
- `V4__steer_queue.sql`：`c_ai_steer_item`（**必须带 `tenant_id`**——多租户拦截器会往 insert/select 里注入，
  漏了就是运行期 `Unknown column 'tenant_id'`，本次冒烟已抓到这个坑）。
- `SteerItem` / `SteerItemMapper`（claim/release/setReceipt/applied/drop/dropAllPending/reviveStale 全 CAS）。
- `SteerQueueService`：enqueue/list/steerNow/drop/drain/applied/dropAllPending；`SteerQueueHooks` 由引擎在
  **`inbox/opened`（run 真活着）** 时唤醒 drain、在 `inbox/applied` 时按收据置 APPLIED。
- `GoalService.finish`：目标取消/删除时把该会话 PENDING 项收敛为 DROPPED。
- 四个接口：`POST/GET /chat/steer/queue`、`POST /chat/steer/queue/{id}/steer`、`DELETE /chat/steer/queue/{id}`。
- 前端队列改为服务端投影：进/切会话、本轮开跑、目标终态、steer 帧到达时各恢复一次；
  删掉 2.5s 重试与 TTL 兜底；切会话只把「未同步的本地草稿」放回输入框（服务端来的不重复回填）。

**批 3（口径）**
- `planExecProgress`：`running=false` 时 `doing → wait`（含冻结快照里的残留 doing）；
  `planExecProgressOf` 的 running 增加「目标已 PAUSED」门。暂停后卡片立刻不转圈。

### 验证
- 后端：`SteerReceiptsTest`(3) / `SteerAdmissionTest`(6) / `SteerQueueServiceTest`(6) / `ChatRunRuntimeTurnMutexTest`(4)
  / `GoalTurnGuardLocalTest`(1) / `GoalLifecycleLocalTest`(1) 全绿；V4 在全新库上 Flyway 正常跑到 v4。
- 前端：`npm test` 550 通过（含队列投影恢复、带图插话、非 running 归一）。
- 链路冒烟（8097 + 临时库，已清理）：登录 → 建会话 → 入队(PENDING) → 无活轮 steer 返回 `accepted=false`
  且行保持 PENDING（`lastError=这一轮不收插话`）→ 投影可恢复 → 丢弃后投影为空。

### 仍需人验（本机未跑真模型）
- 真实用户轮/目标轮在跑时插话进模型：需要你在自己的实例上点一次（看回答时间线里的 steer 段）。
- 目标轮期间「插话调整·不中断」端到端、刷新恢复的实际观感。
- 注意：V4 会在你下次启动新构建时自动执行（存量库 baseline=1，Flyway 正常补跑）。

### 已知限制
- 崩溃在「claim 之后、送达之前」的窗口里，TTL 后重排可能重复一句（框架 steer 无幂等键）。
- 队列跨设备实时推送未做（进会话/轮开跑/应用帧时拉投影）；多端同时开会各自看到最近一次投影。
- 拖动排序只影响界面，服务端按创建顺序 drain。
- 目标「暂停」不清队列（可继续）；取消/删除才收敛。
