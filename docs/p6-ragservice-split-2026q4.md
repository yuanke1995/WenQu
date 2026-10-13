# P6 · 拆 RagService：它不是检索器，是整轮问答运行时

> 立项卡 · 2026-10-12 · 状态：**刀 1、刀 0（两步）、刀 2、刀 3（3a+3b）、刀 4（四族）、刀 5a（引用面）、刀 5b（上下文装配）已落地并真机验通；刀 5c（落库与记录族）已落地并真机验通（`TurnRecorder`，见 §二·十四·补）；刀 5d（入口门面与改名）已落地并过编译级验证（2026-10-13，见 §二·六 ~ §二·十五）**，`RagService` 10118 → **4502 行，末刀起更名 `ChatService`**；刀 5 四子刀至此执行完毕，§五-1 的 ≤1500 **未达成**（立项估计偏差约 5 倍，真实剩余构成与后续切面见 §二·十五）
> 起因：用户一句话点破——「Rag 是知识库检索啊，只有知识库相关的才走 RagService，应该有一个 ChatService 去使用 RagService 吧？」
> 实测结论：这个判断成立，而且比"混了点代码"严重——它已经不是检索器了，检索只是它 13 个章节里的一个。

## 〇、现状实测（数字，不是观感）

| 指标 | 值 | 对照 |
|---|---|---|
| 行数 | **10029** | 仓库第二名 `DocumentService` 2458，差 4 倍 |
| 注入依赖（`private final`） | **39** | — |
| 方法总数 | **249**（public 18 / 私有 231） | — |
| 内联章节（`// ====` 分段） | **13** | 见 §二 的归属表 |
| SSE 事件下发点 | **60** 处 `sendSseEvent(` | 全在一个类里 |
| 本轮状态口袋 `st.` 引用 | **728** 处 | 见 §一 拦路点 1 |
| 调用方 | **25 处**：`ChatController` 16、`ShareController` 5、`ScheduledJobService` 1、`WorkflowEngine` 1、`McpServerService` 2 | 入口统一是 `ragService.chat(ChatCommand, SseEmitter)` |
| 检索本身 | 早已外移：`HybridRetrievalService` 1103 行、`RerankService`、`KeywordIndexService` | 所以类名是历史遗留 |

**它现在装着的 13 件事**（行号为 2026-10-12 立项时的章节起点）：P3b 子调用回程 2289、工具执行心跳 2832、整轮存活看门狗 2920、MCP 结果引用注册 5087、联网搜索 sink 5245、DSML 工具文本剥离 5465、P3a 子智能体全循环 5972、历史预算与滚动压缩 6811、深度思考 7349、计划模式 7787、计划意图识别 7837、断线重连接流 9256、智能体绑定工作流 9646。

**2026-10-13 收口后还剩 6 处章节位**：P3b 子调用回程与 P3a 分支（都只剩"按轮簿记 + 交回函数"的入口，本体在 `agent/runcode` 与 `agent/subagent`）、工具执行心跳（一行转调 `runtime.stopHeartbeat` + 撤面板 ticker）、DSML 剥离、断线重连接流的读口（`resumeSnapshot` / `listSessionEvents`）；原「整轮存活看门狗」这个标题下面已经不换门狗了，是工具调用的瞬时故障重试——横幅已按内容改直。**剩下的检索编排、上下文填充与时间线/落库就是刀 5 的对象。**

## 一、两个真拦路的（不解决就是搬家不是拆分）

1. **`AnswerStreamState` 是非静态内部类**（`:4679`，仅 2 处 new，`st.` 引用 728 处；4680–5090 区间内声明行约 95 处——粗算，含方法签名，字段真实数按此上界看）。它隐式持有 RagService 实例，且装着本轮几乎所有可变状态：`degradations`/`degradedCodes`（32/30 处）、`emitter`(28)、`model`(29)、`timeline`(13)、`emitBuf`(12)、`permissionPolicy`、`toolScopeKbIds`、`subCallTools`、`dsmlToolNamesLc`、`reflectiveRetrieval`…
   → **不先把它变成可跨类传递的对象，任何"抽服务"都会变成多个类共享同一个可变口袋**，比现在更难追。这是本卡最容易被低估的成本。
   **已清（2026-10-13 刀 0 第一步，见 §二·七）**：现在它是 `agent/chat/TurnState`。账要改一句口径——实测**耦合薄、口袋厚**：隐式外层引用只有 **7 个符号 / 10 处**，收成 `Deps` 的 6 个组件就断了；真正大的是那 101 个字段。**刀 0 第二步（§二·十一）已把 43 个字段连行为一起沉进四个子对象**（引用池 15 / 通道与计时 7 / 工具与档位 13 / 脚本面板 8），剩 67 个（身份、生成与时间线缓冲、用量、人在回路与落库归属）等刀 5 的门面成形。
2. **`BranchRunner` 刻意不是 bean**：它是"父轮回传的函数"，理由写在 `agent/subagent/BranchRunner.java:6-11`——做成 bean 就成了 RagService ↔ 编排器 ↔ 执行器的三方循环依赖，只能靠 `ObjectProvider` 或字段注入解开，而那种解法把编译期可见的接错推迟成运行期 null。
   → 拆分时必须先定"新运行时谁是父"，否则这条接缝会反复挡住你（见 §二 刀 3）。

## 二、刀法（六刀，一刀一个 commit，行为零变化）

| 刀 | 切什么（public 方法行号为证） | 落点 | 估 |
|---|---|---|---|
| **刀 1 · 人在回路族**（**已落地 2026-10-13，见 §二·六**） | 三个登记表 + 两个释放族 + 9 个 public：`releaseHumanWaits:823`、`markHumanWaitsTerminal:846`、`resolvePlanApproval:8498`、`listPendingPlans:8569`、`resolveAsk:8819`、`listPendingAsks:8854`、`listPendingApprovals:8901`、`resolveAskIgnore:8946`、`getApproval:9086`、`resolveApproval:9098`、`purgeStaleApprovals:9138`（连带 `markApprovalResolved`/`markAskResolved`/`buildCombinedAnswer`/`parseQuestions`/`parsePlanDraft`/`PlanDecision`/`QItem`/两个超时读值） | `HitlDecisionService`；**登记表从 `static` 变实例字段** | 2–2.5d |
| **刀 0 · TurnState 提取**（**第一步 2026-10-13 见 §二·七；第二步同日见 §二·十一**） | 第一步 `AnswerStreamState` → 顶层 `TurnState`（静态、显式依赖、去掉对外层实例的隐式引用）；第二步把三簇字段按归属沉进子对象（引用池 / 通道与计时 / 工具与档位，另加顺带的第四簇 run_code 面板） | `agent/chat/TurnState.java` + `TurnSourceRegistry` / `TurnChannel` / `TurnToolset` / `TurnRunBoard` | 3–4d（第一步实际 <1d，第二步 <1d） |
| **刀 2 · 轮次控制与存活**（**已落地 2026-10-13，见 §二·八**） | `chat:625`、`stopTurn:755`、`steerTurn:7640`、`runStatus:9275`、`resumeTurn:9300` + 章节 2832 心跳 / 2920 看门狗 / 9256 断线重连 | `ChatRunRuntime`（`ChatController` 改叫它） | 3–4d |
| **刀 3 · 分支运行时**（**拆两刀：3a run_code 子调用与面板已落地 2026-10-13，见 §二·九**；3b 分支簿记待做） | 章节 5972 P3a + 2289 P3b 子调用回程 + `branchRunner:6056`（构造点 `:1366`） | `SubAgentBranchRuntime`；`BranchRunner` 此时才适合升为 bean 方法（运行时是父、编排器是子） | 2–3d |
| **刀 4 · 上下文与生成辅助**（**已落地 2026-10-13，四刀一起见 §二·十二**） | 章节 6811 + `compactSession:6908`、`resolveChatModel:7002`；章节 7349；章节 7787+7837；章节 9646 | 四个类：`HistoryCompactionService`（`agent/context`） / `DeepThinkService`（`agent/think`） / `PlanModeService`（`agent/plan`） / `ChatflowBridge`（`agent/chatflow`） | 4–6d（实际一日四刀） |
| **刀 5 · 剩下的才是检索**（**5a 引用面已定型 2026-10-13，见 §二·十三**；5b 上下文装配已落地见 §二·十四；5c 落库与记录族已落地见 §二·十四·补；**5d 门面改名已落地 2026-10-13，见 §二·十五**） | 引用池（章节 5087、5245 + `registerToolSource`）先长成 `TurnSourceRegistry`；其余检索编排 | `RetrievalPipeline`（由门面持有；原先写"由 `ChatRunRuntime` 持有"已作废——方向必须单向，见 §二·八 第 3 条） | 2–3d（已扩成 5a~5d 四子刀） |

### 二·五、刀 1 的实测结论（2026-10-12 已核，顺序就此定下）

原来那句"前提要先实测"已经跑完，结论是**刀 1 先做成立，但形状不是"9 个 public 方法"，而是"三个登记表 + 两个释放族 + 9 个 public"**。

**裁决侧完全不碰本轮状态。** 三个挂起登记表是 `RagService` 的 `private static final ConcurrentHashMap`（`:7750` 审批 / `:7763` 提问 / `:7805` 计划），值对象只装 id 与 future：`PendingApproval(sessionId, userId, toolName, future)`（`:7746`）、`PendingAsk(sessionId, userId, future)`（`:7759`）、`PendingPlan(sessionId, userId, future)`（`:7791`）。
`resolveApproval:9098`、`resolveAsk:8819`、`resolvePlanApproval:8498` 三者的实现都是同一形状：**落 DB 终态 → 查表 → `future.complete(...)`**，零 `st.` 引用、零 emitter 引用。`listPending*:8569/8854/8901` 只多一个 `containsKey` 算 `live` 标志（`:8624`、`:8882`、`:8931`）。

**但有一条耦合必须一起搬**：`stopTurn` 路径上的 `releaseHumanWaits:823`（解开三张卡挂着的 future，否则流水线线程一直挂在 `future.get(timeout)` 上、会话互斥要等超时才释放）与 `markHumanWaitsTerminal:846`（把卡片落成 STOPPED/INTERRUPTED 终态）。它们扫的就是那三个 Map。
→ 所以刀 1 的落点形状是：**登记表随族搬进 `HitlDecisionService` 并从 `static` 改为实例字段**，RagService 侧只留三类调用：
1. 登记：`PENDING_*.put(...)`（`:2776`、`:8264`、`:8746`）→ `hitl.registerApproval/registerAsk/registerPlan(...)` 返回 future，`finally` 里的 `remove`（`:2820`、`:8317`、`:8790`）→ `hitl.unregister(id)`。
2. 释放：`releaseHumanWaits` / `markHumanWaitsTerminal` → `hitl.releaseSessionWaits(sessionId, endReason)` / `hitl.markSessionWaitsTerminal(sessionId, endReason)`。
3. 裁决与列表：9 个 public 方法整体搬走，`ChatController` 那 9 处调用改指向新 bean。

**收益不只是搬家**：跨类共享的静态 Map 变成"单一持有者 + 方法调用"，正是 §一 拦路点 1 要避免的那种口袋被消掉一块；`purgeStaleApprovals` 的 `@PostConstruct` 也跟着落到新 bean，启动清理职责不再寄居在运行时里。

**一条不能破的顺序约束**（写代码时最容易忽略，且已有真机事故）：`markHumanWaitsTerminal` **必须在 `disposeSafe()` 掐流之前调**——掐流会中断那根阻塞在 `future.get()` 上的工具线程，它走 `InterruptedException` 分支既不落库、`finally` 还要把挂起项摘掉，放在掐流之后就是对着空表落库、记录永远停在 PENDING（`:840-843` 记的 2026-10-12 真机抓取）。刀 1 把两个方法拆到同一个服务里，反而让这条约束有地方被写成一次调用（`hitl.terminateSessionWaits(sessionId, endReason)` 内部先落库再解 future），**建议就这么合**，别留两个方法让调用方记顺序。

## 二·六、刀 1 已落地（2026-10-13，编译 + 真机全绿）

**结果数字**：`RagService` **10118 → 9472 行（−646）**，`st.` 引用 728 → 609，注入依赖少一个（`ToolApprovalMapper` 随族搬走）。新类 `agent/hitl/HitlDecisionService.java` **765 行 / 3 个注入依赖**（ConfigService、ToolApprovalMapper、ToolRegistry，判据 ≤10 达成）。`ChatController` 8 处调用改指 `hitl`。`src/test` 3/3 绿、`npm run check` 0 FAIL（前端零改动）。

**实现形状与 §二·五 的三处偏离，都比原方案更紧**
1. **`openApproval/openAsk/openPlan` 把「落 PENDING 记录 + 登记唤醒句柄」合成一次调用**，而不是只搬 `PENDING_*.put`。理由：卡片落库与登记本来就必须同生同死，分开写就是让调用方再记一次顺序（§二·五 那条事故正是顺序问题）。SSE 下发与站内通知仍留在运行时——那是通道的出口，不是卡片的内容。
2. **返回的 `WaitHandle<T>` 是 `AutoCloseable`**，三个等待段改 `try (var wait = hitl.openX(...))`，`close()` 就是摘登记。原先 `finally { PENDING_*.remove(id) }` 那种「记着摘哪张表」的活儿没有了；顺带把「通知写入抛异常 → 挂起项永久漏在表里」这条老路径堵掉（审批段的通知原本在 try/finally 之外）。
3. **登记表从 `static` 改实例字段**（§二·五 的收益兑现）。真机直接证据：卡挂着的瞬间 `/tool-approval/pending` 回 `live=true`（三处 `live` 读的都是这张实例表）。

**三个超时读值（approval/ask/plan）一并搬进新服务**，`planTimeoutMs` 原来不在 §二·五 的清单里，但 `listPendingPlans` 的倒计时与运行时的阻塞窗口必须同源，留在两边就是两份口径。`RagService` 侧只在 `ToolTurnContext#approvalTimeoutMs` 那个匿名类里转调 `hitl.approvalTimeoutMs()`——注释里那条「裸写自己的方法名 = 无限递归打爆工具线程栈」的坑原样保留并点名了新服务。

**顺带消掉一处手抄**：`ToolRegistry.putDisplayFields(payload, name)` 新增，`RagService.putToolDisplayName` 退为一次转调。恢复出来的待批列表也要下发中文名，不收口的话就是同一份映射的第二份拷贝（P1 已经为这件事收过三次口）。

**释放时机的刻意改动（读代码时最容易当成 bug）**：`stopTurn` 里 `releaseHumanWaits` 原来排在 `disposeSafe()` 之后，现在随合并提到之前（早约 18 行）。判断依据：`disposeSafe()` 本身就会把等待线程以 `InterruptedException` 叫醒并让它继续往下走，所以「提前解开」并没有新增窗口；而「先落终态占住幂等闸、流水线随后那次 TIMEOUT 写入落空」这条不变量在两种排法里完全一样。`abortTurnWithoutViewer` 里提问/计划在合并调用点之上已被 `askWaits/planWaits` 豁免，合并只影响审批一类，与原来末尾那次释放的实效相同。

**真机验收（2026-10-13 12:00–12:20，新字节码，默认智能体「问渠」的工具执行确认临时调成 ask 跑完后已还原 NULL；11 条探针会话与其消息/账本/审批行已硬删，`probe-*` 审批行清零）**
- §五-5 流式问答：正常出正文 + `done`。
- §五-7 审批卡裁决唤醒：批准 → DB `APPROVED`，工具**真执行**（正文原样回显 `HITL-P6-K1`）。
- **§五-7b 叫停顺序断言（本刀的回归靶）**：卡挂着时 `pending` 命中 1 条 `live=true` → `POST /chat/stop` → 1 秒后 DB `status=STOPPED`、`pending` 命中 0 条。
- §五-6 审批挂起时断线：掐掉那条通道 → `/chat/run` 仍报 `running=true` → `/chat/resume` 接回（先 `snapshot` 再增量，含那张 `approval_required`）→ 在恢复通道上批准 → 正文照样拿到回显、DB `APPROVED`。
- §五-7 提问卡：作答 → DB `APPROVED`、正文采纳所选项；**忽略** → DB 按设计落 `TIMEOUT`（不替用户选），工具结果文本里是「未回答这一题（点了忽略）」，直接查落库的 `tool_calls` 取证。
- §五-7 计划卡：批准 → `APPROVED` 后按计划出正文；**退回重出** → 第一版落 `REVISED`、第二版卡出、`/plan-approval/pending` 的版本链重建为 **2 版**、批准第二版后收正文。
- §五-7c 启动清理：重启前留一条 1 小时前的 PENDING → 启动后收成 `TIMEOUT`（`purgeStaleApprovals` 的 `@PostConstruct` 已落到新 bean）；另留一条窗口内的 PENDING → 重启后 `pending` 回 `live=false`，对它提交批准不静默失败（落库 `APPROVED`、卡片从待批列表消失）。

**没跑到的三条，按推断记**
1. §五-8 的定时任务 / 工作流 / 对外 MCP / 免登录分享页：本机没有已开启的智能体分享（`c_ai_agent.share_config` 全空），不为验收新建对外可见入口。判据：这四条只经 `chat(ChatCommand, SseEmitter)`（签名未动），且被搬走的 9 个 public 在全仓只有 `ChatController` 一个调用方（grep 为证）。
2. §五-10 H5：前端一行未改，只跑了 `npm run check`。
3. 运行中插话（steer）：本刀没跑；同日刀 0 验收里跑了并留证（见 §二·七 第 4 条）。

**一条验收方法上的坑（下次别再踩）**：用 `http.client` 读这条 SSE 时 `resp.read(2048)` 会**攒满 2048 字节才返回**，卡片事件因此在客户端晚了 2 分钟——第一次跑 7b 拿到的 `status=TIMEOUT` 就是这个假象（审批自己 120s 到点），不是叫停失败。改 `read1(4096)` 后事件与卡片同步，断言才是真断言。**客户端的时间戳必须打在每一步上**，否则「行为不对」和「我读晚了」分不开。

## 二·七、刀 0 第一步已落地（2026-10-13，编译 + 真机全绿）

**结果数字**：`RagService` **9472 → 8678 行（−794）**，私有内部类残留 0。新增 `agent/chat/` 四个文件：`TurnState` 816 行、`TurnRegistry` 110、`TurnDeadline` 65、`TurnHandle` 28。`st.` 引用 **609 处一行未动**——第一步刻意只做「提顶层 + 显式依赖 + 改可见性」，字段袋的拆分是刀 2/3/5 的活（§四 第 2 步）。

**§一 拦路点 1 的账要改口径**：原先写「这是最容易被低估的一处成本」，实测下来**耦合很薄、口袋很厚**。把类一行改成 `static` 让编译器照出来的隐式外层引用只有 **7 个符号 / 10 处**：

| 用什么 | 几处 | 落地方式 |
|---|---|---|
| `configService` | 3 | `TurnState.Deps` 直接交服务 |
| `subAgentConsult` | 1 | 同上（台账改在构造体第一句建，见下方陷阱） |
| `chatRunBus` | 1 | 同上（`holdsRun` 是断线不掐流的判据） |
| `rerankService` / `imageUrlSigner` | 各 1 | 同上（MCP 来源批量重排、工具来源图片签名） |
| `sendSseEvent` / `addDegradation` / `persistPartialAnswer` | 各 1 | `TurnState.TurnRuntime` 的三个方法，匿名实现交出去 |

`Deps` 一共 **6 个组件**（5 个无状态服务 + 1 个本轮动作面）。§五-3 那条「`private final` ≤ 15」是给刀 2 的 `ChatRunRuntime` 定的；刀 0 的判据换成「依赖清单的组件数可数、且没有一个是大对象」。

**四条形状决定，都会影响后面每一刀**
1. **三张登记表进 `TurnRegistry`，map 是 private，外面只有 13 个具名方法**（`state/registerState/unregisterState/handle/registerHandle/unregisterHandle/removeHandle/deadline/registerDeadline/stopDeadline/beginHumanWait/endHumanWait/hasRunning/runningStates`）。没走「`public static final Map` 挪个家」那条省事的路：那等于把可变口袋从 god class 搬到 globals，与刀 1 把 `static` 收成实例字段的口径自相矛盾。
2. **`TurnRuntime` 交匿名实现，`RagService` 不 implements 它**——否则 `RagService` 的 public 面凭空多三个方法。刀 2 把这三个动作收进运行时对象时，换的是实现方，字段类型与调用点都不动。
3. **`TurnHandle` / `TurnDeadline` 必须跟着走**：`TurnDeadline.state` 指着本轮状态、状态的 `claimTerminal()` 摘把手，互指关系留在两个包里就只能靠 public 硬撑。这也是刀 2 的落点已经成形的一部分。
4. **`TRUNCATION_SUFFIX` / `STOP_SUFFIX` / `IMG_PLACEHOLDER_PATTERN` 随类走**，`RagService` 侧改成引用 `TurnState.X`（单源；`RESTART_SUFFIX`/`STREAM_ERROR_SUFFIX`/`LENGTH_TRUNCATED_SUFFIX` 只有问答侧用，留在原地）。

**「原样提出」这句话里唯一的真陷阱**：`consultLedger` 原先是字段初始化 `= subAgentConsult.newLedger()`。提顶层后**字段初始化跑在构造体之前**，那时 `deps` 还没赋值，照搬就是每轮必 NPE。已改成声明 + 构造体第一句赋值（原来那段「不能懒建，并发会造出两份台账」的理由原样保留）。

**方法记一条**：正则探针不可信。第一版探针（扫外层成员名在内层体内的裸用）报了 14 个符号，其中 6 个是注释里的词，还漏了 `sendSseEvent`/`addDegradation`/`persistPartialAnswer` 三个真耦合。**改成把类一行翻成 `static` 让 javac 报错**，十条以内就把耦合面照得一个不差——这类结构迁移让编译器当探针。

**真机验收（2026-10-13 12:53–13:12，最终字节码；默认智能体审批档临时调 ask 后已还原 NULL，`chat.sseTimeoutMs` 试过 60000 后已复原 300000，全部探针会话与消息/账本/审批行清零）**
1. 流式问答正常（`plan/stage/subagent/retrieved/usage/process/tool_status/token/done` 全序列）。
2. 审批卡批准唤醒：工具真执行，正文原样回显探针串，DB `APPROVED`。
3. **7b 叫停顺序**（刀 1 的回归靶，本刀重跑）：`live=true` → `/chat/stop` → 1 秒内 DB `STOPPED`、pending 命中 0。
4. **插话随下一个工具步送达**：挂在审批卡上时发 `/chat/steer` → `accepted=true`；日志两条都在（`[STEER] 插话已入队` → `[STEER] 已随工具结果送达模型: 条数=1 字数=14`），落库 `tool_calls` 里含【用户中途补充】。`TurnHandle.steers()` 换包后仍然通。
5. 审批挂起时断线重连：`/chat/run` 报 `running=true`，`/chat/resume` 先 `snapshot` 再接增量，恢复通道上批准 → 正文到手。
6. **半程正文落库走新 seam**：出字 4 个 token 后叫停 → 落库 1 条助手消息（幂等闸没重复落），尾部是 `TurnState.STOP_SUFFIX` 的「已停止本轮」。
7. 提问卡作答（`live=true` → `APPROVED` → 正文采纳所选项）与忽略（按设计落 `TIMEOUT`，不替用户选）。
8. 计划卡批准（`live=true`、版本链 1）与退回重出（第一版 `REVISED` → 第二版 → 版本链 2 → 批准收正文）。
9. **看门狗到点截断**：预算调到下限 60000，跑满后日志 `[FAIL-LOUD] 本轮问答超时截断: 机器耗时 60019ms / 预算 60000ms（人工等待 0ms 不计入）`，落库 1204 字 + 尾部「回答在此处被中断」——`TurnDeadline.machineMs/fired/watchdog` 与 `TurnRegistry.stopDeadline` 全线通。
10. **人工等待不计入机器预算**：同一 60s 预算下，审批卡挂 95 秒没被掐（`pending` 仍 1 条 `live=true`，期间无 `warn`），批准后照常跑完——`beginHumanWait/endHumanWait` 换到 `TurnRegistry` 后扣除逻辑一字未变。

**没验的（按推断记）**：四条非对话入口（同刀 1 的理由）、H5（前端零改动，`npm run check` 80 项全绿）、工作流那类收集型通道的看门狗（`TurnDeadline.state` 为 null 那条分支）、§五-9 的账本回放（这一刀只在 `sendSseEvent` 同一条实现前多接了一跳 `TurnRuntime`，没改写入判据）。

**字节码血缘（免得日后对不上）**：第 3–10 条跑在 12:52 那次全量构建的进程上；之后只剩注释、javadoc 链接与构造参数缩进的改动，13:09 重新全量编译并重启进程（`Started WenQuApplication`、`[TOOL-REGISTRY] 已登记 24 个工具，8 个族` 照旧），重启后复跑了第 1、2 条都在。**所以「最终树」的直接证据只有 1、2 两条，其余 8 条的证据在只差注释的同一份代码上**。**配置下限顺手量到一条**：`chat.sseTimeoutMs` 有 `≥ 60000` 的整笔校验，想验看门狗只能跑满 60 秒机器时间，不能靠调小预算走捷径。

**刀 2 现在能直接拿走的落点**：`ChatRunRuntime` = `TurnRuntime` 的三个动作 + `TurnRegistry`（并进它）+ 心跳/看门狗调度**（刀 2 落地时回看：`TurnRegistry` 没并，`chat()` 也没搬——理由见 §二·八 第 1、3 条）**；`TurnState` 里等着被收编的三簇字段已经在类型上分开了——引用池（`sources`/`toolRefImages`/`webRefByUrl`/`docFileNames`/`imgIndex`）、通道与计时（`emitter`/`emitBuf`/`heartbeat`/`disposableRef`/`stageMs`）、工具与档位（`sensitiveToolNames`/`mcpToolNames`/`dsmlToolNamesLc`/`permissionPolicy`/`toolScopeKbIds`/`subCallTools`/`consultCtx`/`consultLedger`）。

## 二·八、刀 2 已落地（2026-10-13，编译 + 真机全绿）

**结果数字**：新 bean `agent/chat/ChatRunRuntime.java` **646 行 / 6 个注入依赖**（ConfigService、ChatRunBus、SessionEventService、SessionService、HitlDecisionService、RunTokenRegistry）。`RagService` **8678 → 8149 行（−529）**，`runtime.` 调用点 174 处，`st.` 引用 609 → 576。搬走的 36 个成员：SSE 投递与账本留痕（`sendSseEvent`/`sendFrame`/`markSseDead`/`textDeltaPos`/`LEDGER_SSE_TYPES`）、通道登记与断开判定（`ACTIVE_SSE`/`trackChannel`/`clientDisconnected`/`turnAborted`/`forgetSseChannel`/`releaseSseChannel`/`completeEmitter`）、心跳与存活看门狗（`scheduleKeepalive`/`startRunHeartbeat`/`stopHeartbeat`/`startTurnWatchdog`/`turnMachineBudgetMs` 与那两个池）、轮次控制入口（`stopTurn`/`steerTurn`/`runStatus`/`resumeTurn`/`pendingWaitHint`/`abortTurnWithoutViewer`）、插话簿记（`drainSteers*`/`attachLeftoverSteers`/两个上限常量）、`turnIdOf`/`turnStopRequested`/`addDegradation`。

**方向必须单向，这是本刀全部的难点**：`ChatRunRuntime` 一个字都不认识流水线。于是——

1. **`chat()` 留在 `RagService`**（卡里原写「刀 2 把 `chat` 也搬走、`ChatController` 改叫它」）。它要提交流水线，搬走就得让运行时回头叫 `runChat`，那是又一根循环依赖。入口这 118 行的其余部分（互斥、把手、总线开轮、台账、看门狗、通道回调）本来就都在运行时身上；等刀 5 把流水线搬进门面时一起走，那时 §八 D2 的命名才有对象。
2. **原先四处直接摸登记表的写法改走具名闸口**：`TURN_IN_FLIGHT.putIfAbsent(...)` → `runtime.tryBeginTurn()`、`TURN_IN_FLIGHT.remove` → `endTurnMutex`、`ACTIVE_SSE.put(emitter, new AtomicBoolean())` → `trackChannel`、`BARE_ABORTS.remove(sid, tid)` → `takeBareAbort`。三张表仍然只有运行时自己看得见（与刀 1 把 `static` 收成实例字段是同一条口径）。
3. **`TurnRegistry` 没有并进 `ChatRunRuntime`**（卡里原写「并进它」）。它已经是「私有 map + 13 个具名方法」的单持有者，而 `TurnState` 的构造与 `claimTerminal()` 都在静态地用它；合并只会换来「运行时反过来要状态、状态又要表」的来回引用。真要合，等刀 5 门面成形后一次做。
4. **`stopRunHeartbeat` 留在问答侧**，改成一行转调 `runtime.stopHeartbeat(st)` + 停 run_code 面板 ticker：那个 ticker 是 P3b 的展示层，属刀 3/4，不该混进「存活」。
5. **`TurnRuntime` 三个动作里两个转交给了运行时**，只剩 `persistPartial` 还在 `RagService` 的匿名实现里（半程正文要动消息表与时间线快照）。这条尾巴就记在卡里，刀 5 收走。

**外部调用点**：`ChatController` 四个入口（stop / steer / run / resume）与 `ShareController` 三个入口（免登录分享页的 run / resume / stop）改叫 `runtime`；`McpServerService`×2、`ScheduledJobService`、`WorkflowEngine` 的收集型通道收尾改叫 `ChatRunRuntime.forgetSseChannel`；`ChatRunBus.Run` 加 `userId()`/`beganAt()` 两个读口（字段仍不外露）。

**真机验收（2026-10-13 13:30–13:51，最终字节码；`chat.sseTimeoutMs` 试过 60000 后已复原 300000，默认智能体审批档临时 ask 后已还原 NULL，探针会话及其消息/账本/审批行清零，别人那条分享的开关未动）**
1. 启动无环：`Started WenQuApplication in 2.24s`、`[TOOL-REGISTRY] 已登记 24 个工具，8 个族`（RagService ↔ ChatRunRuntime 单向）。
2. 流式问答：`plan/stage/subagent/retrieved/usage/process/tool_status/token/done` 全序列，正文 256 字。
3. 审批卡批准唤醒（工具真执行、正文回显探针串、DB `APPROVED`）与 **7b 叫停顺序**（`live=true` → stop 0.01s → DB `STOPPED`、pending 命中 0）——两条都走 `sendSseEvent` 的新位置。
4. **插话**：挂在审批卡上时 `/chat/steer` `accepted=true`，日志 `[STEER] 插话已入队` → `[STEER] 已随工具结果送达模型`，落库 `tool_calls` 含【用户中途补充】（`steerTurn`/`drainSteers`/`TurnHandle.steers` 整条链路已在新 bean）。
5. **审批挂起时断线重连**：掐通道 → `/chat/run` `running=true` → `/chat/resume` 先 `snapshot` 再接增量（含那张 `approval_required`）→ 在恢复通道上批准 → 正文到手。
6. 提问卡作答、计划卡批准（两张卡都 `live=true` → `APPROVED`）。
7. **看门狗到点截断（截断发生在出字之后）**：日志 `[FAIL-LOUD] 本轮问答超时截断: 机器耗时 60018ms / 预算 60000ms（人工等待 0ms 不计入）`，**类名已是 `com.wenqu.ai.agent.chat.ChatRunRuntime`**；落库 1 条助手消息、尾部「回答在此处被中断」。
8. **人工等待不计入预算**：60s 预算下审批卡挂 95 秒没被掐（`live=true`、无 `warn`），批准后跑完。
9. **免登录分享页那三个入口（本刀换 bean 之处）**：访客出字中 `GET /share/{token}/chat/run` → `running=true` 带 turnId/viewers；`GET .../chat/resume` → 200 且收到 `snapshot`；`POST .../chat/stop` → 「已停止本轮」，再探测 `running=false`，访客会话历史里那半程正文尾部就是「⏹ 已停止本轮」。
10. 后端测试 3/3、`npm run check` 80 项全 PASS（前端零改动）。

**顺手量到三条，省下次时间**：分享页 `POST /share/{token}/chat` 的入参字段是 **`message`** 而不是 `question`（写错会 500 抛「请输入内容」）；游客问答限频按 **IP**，连打会 429 并回「N 秒后重试」；`chat.sseTimeoutMs` 有 `≥ 60000` 的整笔校验。另记一条**不是本刀引入**的既有语义：分享页叫停是「先按访客验归属、再按发布者 uid 停」——免登录那一轮的身份本来就登记在发布者名下（按发布者检索），这个不对称是设计，注释里写着。

**没验的**：定时任务与工作流那两条只改了一处静态调用名（`forgetSseChannel`，实现同一份）；子智能体分支用的收集型通道在第 2、4 条里已实跑（事件流里能看到 `subagent`）；H5 未跑（前端零改动）。

## 二·九、刀 3a 已落地（2026-10-13，编译 + 真机全绿）；刀 3 拆成两刀

**为什么拆**：卡里那一行把 P3a 与 P3b 两章（1370 行）算成一刀，落地前细看才发现**两族的父子关系根本不同**——脚本子调用不需要回头调问答入口，能做成 bean；智能体分支必须跑一整轮问答，"跑整轮"只能是传进来的函数。混在一个类里等于同时表达两种形状。于是：**3a = run_code 子调用回程与实时面板（本刀）；3b = P3a 分支簿记（下一刀）**。

**结果数字**：新 bean `agent/runcode/RunCodeSubCallRuntime.java` **494 行 / 4 个注入依赖**（ConfigService、ToolRegistry、ToolPipelineFactory、ChatRunBus）。搬走 14 个成员：`prepareSubCallPipeline`/`runCodeBridge`/`specOf`/`subCallTurnContext`/`recordSubCall`/`startRunBoard`/`stopRunBoardTicker`/`markSubCallInFlight`/`buildFanOut`/`publishRunProgress` + 进度池与 4 个常量。`RagService` **8149 → 7738 行（−411）**，只剩 6 处 `subCall.` 调用点。只有本类外面要调的 5 个方法升 `public`，其余保持 `private`（与刀 0「全袋 public」的第一步不同，这一族的边界本来就干净）。

**拦路点 2 的答案要改口径**（原文：「刀 3 时 `BranchRunner` 才适合升为 bean 方法」）。实测出的判据是：**能不能做 bean，取决于这一族要不要回头调用问答入口**。run_code 这一族不回头，所以它是 bean；分支族要 `chat()`，所以 3b 搬走的只能仍是簿记——「跑一整轮」作为参数交进来。这条判据比原预测更硬，写在这里免得下次又按「时机到了就能升 bean」规划。

**一处形状细节**：`publishRunProgress` 是「先走运行总线 `publishRaw`、失败退直发 emitter」，所以它依赖的是 `ChatRunBus`，**不是**刀 2 的 `ChatRunRuntime`——两个运行时 bean 之间零引用，这一点是故意的（否则 bean 图上又长一条边）。

**真机验收（2026-10-13 14:48–14:49，最终字节码；`tool.runcode.enabled` 临时开、测完关回 false，默认智能体审批档已复原 NULL，探针会话清零）**
1. 启动无环（`Started WenQuApplication` 3.0s 内监听），24 工具 / 8 族照旧。
2. 一段真扇出的脚本（`call_tool("searchKnowledge", …)` 两次）：流里收到 **5 帧 `runcode_progress`**，末帧 `fanOut={total:2, inFlight:0, items:[searchKnowledge done 1144ms, searchKnowledge done 849ms], used/max…}`。
3. **子调用没各开顶层工具卡**：卡片行只有 `run_code` 那几条（扇出的两次检索记在它的清单里）——这条是本族最容易在搬迁中丢的语义。
4. **终态那一份随消息落库**：`tool_calls` 里含 `fanOut` 与 `run` 段（刷新后卡片仍点得开）。
5. 同一轮里主链路的检索、子智能体分支、正文（674 字，基于两次真实检索）照常——零回归。
6. 后端测试 3/3、`npm run check` 80 项全 PASS（前端零改动）。

**没验的**：Node 那条 stub（`module.exports = { callTool, callsUsed }`）本次只跑了 Python；并发扇出（`maxParallel`=8 的六路那一档）也没重跑——这两条都是脚本侧的通道，本刀一行未改其协议，但证据不在本轮里，别当已验。

## 二·十、刀 3b 已落地（2026-10-13，编译 + 真机全绿）——刀 3 到此收口

**结果数字**：新 bean `agent/subagent/SubAgentBranchRuntime.java` **510 行 / 5 个注入依赖**（ConfigService、SessionService、ArtifactService、DocumentMetaCache、ChatRunRuntime）。搬走 13 个成员，只有外面要调的 4 个升 `public`（`branchBrief`/`branchRunner`/`branchReportsText`/`resolveBranchReportRefs`），其余 9 个（`clipForBrief`/`truncateForBrief`/`rawUrlsOf`/`placeholderize`/`fillTokens`/`findSourceRef`/`registerBranchSource`/`parentImageSeq`/`intOf`）留在类内 `private`。`RagService` **7711 → 7274 行（−437）**，`st.` 引用 534（本刀一处未动，因为这一族的成员本来就靠参数拿 `sources`/`imgIndex`，不摸本轮状态袋）。

**形状**：`branchRunner(TurnRunner turn, …)`——「跑一整轮」是**交进来的函数**（调用点写成 `branch.branchRunner(this::chat, …)`），新 bean 不认识问答服务、没有反向引用。这就是拦路点 2 在 §二·九 修正后的判据落到实处的样子：**要回头调问答入口的一族，能搬的只有簿记**。`SubAgentOrchestrator.hitFromChildSource` 为这次搬迁从包私有升为 `public static`（编排侧与簿记侧要同一条判重口径，不许再抄一份）。

**施工事故（值得留在卡里）**：机械脚本 `fieldDecl` 的语义是 `replace(anchor, ins)` 而不是 `anchor + ins`——本刀的字段声明锚点正是**上一刀（3a）留下的那行**，于是 `subCall` 字段连同它的 javadoc 一起被替换掉，编译报 6 处「变量 subCall 找不到符号」。修法已固化进脚本：追加而非替换，并在改写后断言锚点仍在原地一次。**教训**：一刀接一刀地改同一个成员区，脚本必须把「锚点是上一刀的产物」当成常态来防，而不是只看这一刀写进去的那几行。

**真机验收（2026-10-13 15:08–15:35，最终字节码；夹具＝三个临时智能体，测完连同 12 条探针会话、2 条产物一起删净；`agent.subagentTimeoutMs` 临时 15000 测超时后复原 240000）**

1. **派支与回报进父轮**：@ 强制咨询「法律子智能体」（`subagentMode=full`）→ `subagent` 事件 `mode=full status=done steps=4`，日志 `全循环分支完成 … 正文 1239字 引用 1 条 工具步 3`，父轮正文 698 字基于分支结论。
2. **实时过程**：同一轮收到 **17 帧 `subagent_step`**（`{i, agent, type, content}`），首帧日志 `分支实时过程首帧：… 父轮接收=true`——这条是本族在搬迁中最容易断的（它走 `ChatRunRuntime.sendSseEvent` 而非本轮通道）。
3. **引用回填**：分支来源补注册进父轮，`done.sources` 里出现 `origin=SUBAGENT`、`agent=法律子智能体`、`fileName=中华人民共和国劳动法.pdf` 的条目并拿到父轮编号；日志 `回报段编号回填完成：父轮引用 3 条，长度 872→852`；正文里出现的编号全在 `1..maxRef` 内（无一指不回出处）。
4. **产物收养**：临时造一个「只留产物族」的子智能体，它交付 `k3b-branch-artifact.md` → 日志 `产物 1 个`，产物表里两条记录的 `sessionId` 都已是**父会话**（子会话跑完即删，留不住产物）。
5. **范围外摘号**（这条最难碰）：三层扇出 `顶层父 → 限库中间层（分支轮＝有墙的轮）→ 孙辈（另一库，强制逐条带 [N]）`。日志 `分支回报中 3 处引用超出本轮资料范围（未编号）` + `回报段编号回填完成：父轮引用 0 条，长度 1360→1314`（编号被摘掉而非留着指空），同时 `知识库范围约束：命中 32 → 范围内 30 块` 证明墙本身在生效。**顺带查明**：登录页的父轮 `scopeDocIds` 恒为 null（`resolveScopeDocIds` 里 `if (!walled) return null`），所以这条只有分支轮与游客轮能走到——验收夹具必须按这个条件搭，否则测的是另一条路。
6. **超时叫停**：`agent.subagentTimeoutMs=15000`（配置校验下限 15s）+ 一条实测要 16.8s 的法条问题 → `subagent` 事件 `status=failed`、父轮照常答完 1783 字，且分支那条临时会话查不到（`会话不存在或已被删除`）——`stopTurn` + `finally` 清通道/删会话都在新 bean 里跑通。
7. **继承档与逐层递减**：两条 `父轮交代：档=brief 预算 1200` / `预算 600` 的日志（深度 0 / 深度 1），孙辈预算正是 `/nextDepth` 的结果。
8. 后端编译 + 测试 3/3、`npm run check` 全 PASS（前端零改动）。

**没验的（按推断记）**：游客分享页上的分支（与分支轮同走 `walled`，本刀未改该判定）；一支超时/失败时其余支并行的那一档（`subagentMaxParallel=4` 只跑到过多支同轮、未逐支叫停）；H5 与定时任务/工作流入口（前端零改动、这两条入口不经本族）。

## 二·十一、刀 0 第二步已落地（2026-10-13，编译 + 真机全绿）：三簇字段按归属下沉

**结果数字**：`TurnState` **816 → 385 行**、实例字段 **101 → 67**。四个子对象落在 `agent/chat/`：

| 子对象 | 行数 / 字段 | 装什么 | 归谁 |
|---|---|---|---|
| `st.refs` = `TurnSourceRegistry` | 440 行 / 15 | sources、imgIndex/imgDescIndex、toolRefImages、webRefByUrl、docFileNames、docMetaCache、mcpCiteCount、webSearchCalls + **注册方法本体** | **刀 5 直接收编这个类** |
| `st.channel` = `TurnChannel` | 56 行 / 7 | emitter、startTime、emitBuf、stageMs、disposableRef、heartbeat、detached | 刀 2 的 `ChatRunRuntime` 操作的就是这一份 |
| `st.tools` = `TurnToolset` | 83 行 / 13 | 权限档位与审批档、敏感/MCP/DSML 名单、检索库界、subCallTools、enabledCallbacks、consultCtx、consultLedger、maxToolSteps/toolStepCount | 刀 3 / P5 的装配面与守卫面 |
| `st.board` = `TurnRunBoard` | 44 行 / 8 | subCallLog/Running/InFlight/Dirty/Count、runProgress、runBoardActive、runOutcome | 刀 3a 的 `RunCodeSubCallRuntime` |

机械改点 **174 行**（RagService 123 / RunCodeSubCallRuntime 38 / ChatRunRuntime 12 / TurnDeadline 1），`RagService` 行数一字未降（7276，只多了两行注释）——这一刀改的是**归属**不是引用量，`st.` 仍 536 行（与刀 3b 的 534 同一量级），只是每条访问都多了一节名字。

**四条形状决定**
1. **沉了四簇而不是卡里写的三簇**：run_code 面板那八个字段卡里没列，但归属判据是同一条——刀 3a 早就把行为收进 bean 了，状态还平铺在袋里。留着它，这一袋就还是"三簇整、其余乱"。作用域在注释里写清是**一次运行**（每次 run_code 起头复位），而对象仍挂在轮上：面板 ticker、子调用线程、终态卡片要在三个没有共同闭包的位置交接同一个计数袋。
2. **引用池连方法一起搬，不只搬字段**（`registerToolSource` / `registerMcpCitations` / 联网 `register` / 配额 / 图片编号并入 / 子智能体来源转命中块）：编号、去重键、图片编号三者必须原子地一起变，`synchronized (sources)` 就是这条不变量的实现。拆开只搬字段等于把口袋换个地方存。收益直接落到刀 5：那个类接进门面即可，`RagService` 侧引用相关的调用点一个都不必再动。
3. **`TurnState` 不再 implements `WebSearchTools.WebSearchSink`**，sink 落点改交 `st.refs`——工具经 `ToolContext` 拿到的是"本轮的引用注册器 + 配额计"，不是整个状态袋。
4. **子对象构造顺序有硬约束**：`channel` 先建（`refs` 的图片事件要用本轮通道），`tools` 的 `consultLedger` 仍按刀 0 第一步那条口径在建轮第一句建（懒建会在并发下造出两份台账）。

**本刀唯一的静默坑（下次做同类迁移必须记得）**：`ctx.put(WebSearchTools.CTX_SINK, st)` 的值是 `Object`，接口搬家**编译器不报错**——运行时 `v instanceof WebSearchSink` 判空，症状是"联网搜索正常执行但一条来源都注册不上"，从回答里几乎看不出成因。找回来靠把 `RagService.toolTurnContext` 里所有 `CTX_*` 注入点逐个核一遍类型。**口径：结构迁移完成后，凡"按 Object 传进去、靠 instanceof 取出来"的接缝都要人过一遍**，javac 在这条缝上没有投票权。

**两条命名教训**：注入字段用短族名会撞上问答侧的局部量——`history` 撞 `runChat` 里的 `String history` 与两处方法形参，`deepThink` 撞 `final boolean deepThink` 与 `runChat(..., boolean deepThink)` 形参。前者当场写出过 `history = history.buildHistoryText(...)` 这种**自赋值**（编译能过、语义已坏），后者是 `无法取消引用 boolean`。定名口径定下来：**本类字段用完整族名**（`historyCompaction` / `deepThinkService` / `chatflow`；`plan` 例外，因为问答侧没有同名局部量，11 处引用读起来比 `planMode` 更顺）。

## 二·十二、刀 4 已落地（2026-10-13，一日四刀，编译 + 真机全绿）：上下文与生成辅助四族

| 新 bean | 行数 / 注入 | 搬走的成员 | 留在问答侧的（连同理由） |
|---|---|---|---|
| `agent/context/HistoryCompactionService` | 418 / 6 | 15 个：`assembleHistory`（更名 `assemble`）、`compactSession`、`summarizeHistory`、`buildHistoryText`、`summaryBlock`、`historyLine/historyText/fitFromNewest/seqOf/stripImageMarks/compactChunkSize/compactFailReason`、`HistoryBundle`、5 个常量、**compressExecutor 池** | `resolveChatModel`——它要 `loadPrefUser`，而主链路也用那份个人偏好；搬过去就是两处各读一遍用户行。池联动改走 `historyCompaction.syncPoolSize(n/4)`（方向仍是问答侧→它） |
| `agent/think/DeepThinkService` | 318 / 6 | 8 个：`runDeepThinking`（更名 `run`）、`finishDeepThinking`、`extractSearchPlan`、`stripSearchBlock`、`extractThinkingDelta`、`DeepThinkResult`/`SearchPlan`、`ThinkingCappedException`、`persistThinkingOnlyOnStop` + 留痕下限常量 | `SseClientGoneException` **提成 `agent/chat` 共享类型**（深度思考与计划两处都在同步消费流里靠它中断，留内部类就是两处各一份） |
| `agent/chatflow/ChatflowBridge` | 276 / 7 | 4 个：`runWorkflowChat`、`buildWorkflowInputs`、`workflowAnswerOf`、`workflowTokensOf` | 长期记忆提取用的模型改由调用方传入 `memoryModel`（那一轮已经取过一次个人偏好，同一行不查第二次）；**`RagService` 顺带摘掉 `workflowServiceProvider` 依赖** |
| `agent/plan/PlanModeService` | 755 / 8 + **一份四方法的回call接口** | 21 个：`planApprovalGate`（更名 `approvalGate`）、`runPlanPhase`、`planClarifyQuestions`、`parseClarifyQuestions`、`formatClarifyAnswers`、`stripPlanNoise`、`findSupersededPlan`、`planVersionEntry`、`planSnapshot`、`approvedPlanBlock`、`buildRefsDigest`（更名 `refsDigest`）、`startPlanIntentCheck`/`awaitPlanIntent`（更名 `startIntentCheck`/`awaitIntent`）、`planGenTimeoutMs`、`PlanPhaseOutcome`、`PLAN_MAX_CHARS`、`PLAN_INTENT_EXECUTOR` | `persistPlanRejected/Superseded/Closeout` 与 `planRecordJson`、`doAskUserMulti`——**落库要动消息表与时间线快照，那是刀 5 的持久化缝**；搬过来要么拖上整族时间线，要么加第五个回call，两条都比现状差 |

**拦路点 2 的判据第三次坐实**：计划族要「按与生成轮完全同一条口径」装配一次工具集（能力清单喂模型 + 缓存给生成轮复用，MCP 不二次建连）、要看「清单落成那句提示」的门控、要把计划前那张提问卡记进本轮的工具卡管道并等用户答完。这四件事都在问答侧，于是以 `PlanModeService.PlanTurnHooks`（`mountTools` / `todoExposed` / `recordToolStatus` / `askUser`）**按调用传入**——`RagService` 在构造体里建一个匿名实现存成 `planHooks` 字段（无本轮状态，只是把那四个方法按接口签名摆出来），调用点写成 `plan.approvalGate(..., planHooks)`。这个 bean 不认识问答服务，也没有反向引用。

**顺手收口一处手抄**：提问卡的三档限制（`MAX_ASKS_PER_TURN` / `MAX_QUESTIONS_PER_CARD` / `MAX_ASK_OPTIONS`）从问答侧提到 `HitlDecisionService`（`public static final`）。执行期 askUser 与计划前澄清必须同一份口径——留在 `RagService` 就得让 `PlanModeService` 抄一份候选上限，那正是 P1 为中文名收过三次口的那种毛病。

**真机验收（2026-10-13 16:10–18:10，最终字节码；配置全部复原：`chat.sseTimeoutMs` 300000、`tool.runcode.enabled` false；默认智能体「问渠」`tool_approval_mode` 已回 NULL 并经 DB 复核；探针资源硬删：17 条会话及其 35 条消息、250 条账本事件、15 条审批/计划记录、1 个探针智能体、1 个探针工作流；库里 PENDING 人在手记录 0 条）**

1. **启动无环**：`Started WenQuApplication in 2.453 seconds`、`[TOOL-REGISTRY] 已登记 24 个工具，8 个族`（RagService ↔ 四个新 bean 单向）。
2. **流式问答 + 引用池**：联网搜索一轮注册 **14 条 `origin=WEB`** 来源、编号连续；同轮 MCP 结果注册 `origin=MCP`——两条都走 `st.refs` 的新落点（第 3 条是它的反证）。
3. **chatflow（`ChatflowBridge`）**：造一个临时工作流（start→template→end，end 出参 `answer`）+ 临时智能体绑定 → 事件序列 `plan(["执行工作流"]) / stage / done`，`finalContent` = 「CHATFLOW-P6-K4 回显：…」，`workflowRun` 带 `runId/version/status=success/durationMs`，`messageId/turnId/agentId/agentName` 齐全；日志类名已是 **`c.w.ai.agent.chatflow.ChatflowBridge`**。夹具用完删除。
4. **历史压缩（`HistoryCompactionService`）**：五轮长问答后 `POST /session/{id}/compact` → `compressedTurns=3 compressedMessages=6 keepRecent=4 summaryTokens=548`，摘要正文按"每轮功能名"保留；日志类名已是 **`c.w.a.a.c.HistoryCompactionService`**。（自动压缩那条阈值这五轮没触发，属"未跑到"而非"跑错"，见下面第 12 条。）
5. **深度思考（`DeepThinkService`）**：`deepThink=true` 一轮 → `thinking` 增量帧 + `thinking_done`；日志 **`DeepThinkService`** 打出「思考长度达上限截断（2995 字），用已收集内容继续」→「思考结束 2714 字, ok=true, refined=…」，检索计划交回问答侧执行、正文照常出。
6. **计划模式批准**：`planMode=true` → `plan_delta` 63 帧 → `plan_approval` 卡（`live=true`）→ 批准 → 按计划执行（同轮出现 `todo`/`artifact`/`tool_status`），正文 1059 字；消息行 `plan` 列 `status=approved`、`versions` 长度 1；审批行 `APPROVED` 且带 `turn_id`。
7. **计划退回重出**：第一版卡上退回并给意见 → 第二版卡 → 批准第二版 → 审批行 **v1 `REVISED` / v2 `APPROVED`（同一 turnId）**，消息行 `plan` 列 `status=approved`、**`versions` 长度 2**。
8. **计划「继续对话」取代**：挂着的卡提交 `supersede` → 审批行 `SUPERSEDED`，本轮按取代口径收尾（不落「未批准」）。
9. **计划前要素澄清（`PlanTurnHooks.askUser` + `recordToolStatus`）**：一句极含糊的计划轮 → 弹 `ask_user` 卡（2 题）→ 作答 `已提交` → 日志「askUser 调用完成 result=[…]」→ 模型带着答案继续出计划（`plan_approval` 卡随后挂上）。这条就是四号回call走通的直接证据。
10. **审批档三张回归靶（tools/channel 下沉后）**：① 批准唤醒——工具真执行、正文回显 `K4E-STEER`、DB `APPROVED`；② **7b 叫停顺序**——挂卡时 `live=true` → `/chat/stop` → 1 秒内 DB `STOPPED`、pending 命中 0；③ **审批挂起时插话**——`accepted=true`，日志两条齐全（`[STEER] 插话已入队` → `已随工具结果送达模型 条数=1 字数=12`）。
11. **审批挂起时断线重连**：通道按夹具提前关闭 → `/chat/run` 回 `running=true` 带 turnId/viewers → `/chat/resume` 回 `snapshot` → **在恢复通道上批准** → 那一轮照常跑完并落库（165 字助手消息含沙盒回显）。
12. **看门狗到点截断（channel/TurnDeadline）**：`chat.sseTimeoutMs=60000` → 日志 `[FAIL-LOUD] 本轮问答超时截断: 机器耗时 60021ms / 预算 60000ms`（`turn-watchdog` 线程、类名 `ChatRunRuntime`），`warn` 事件到手，落库那条助手消息尾部是 `TurnState.TRUNCATION_SUFFIX` 的「回答在此处被中断」。
13. **半程正文（STOP_SUFFIX）**：出字后叫停 → 只落 1 条助手消息、尾部「⏹ 已停止本轮，以上为已生成的部分」。
14. **子智能体分支（tools 簇 consultCtx/consultLedger + refs 回填）**：@ 强制咨询「法律子智能体」→ `subagent` 事件 running→done（`mode=full`、hits=3）、**7 帧 `subagent_step` 实时过程**、done.sources 里 **5 条 `origin=SUBAGENT`** 带 `agent`/`fileName=中华人民共和国劳动法.pdf` 并拿到父轮编号，正文 1413 字。
15. **run_code 面板（`TurnRunBoard` 下沉）**：`tool.runcode.enabled` 临时开 → 顶层工具卡**只有 `run_code` 两条**（扇出的两次检索没各开顶层卡）、**5 帧 `runcode_progress`**、终态卡片带 `fanOut={total:2, inFlight:0, items:[searchKnowledge done 848ms, searchKnowledge done 845ms], used:2, max:50, parallel:8}` 与 `run` 段；配置测完关回 false。
16. `mvn test` 3/3、`npm run check` 全部通过（前端一行未改）。

**踩到的三条，下次直接照抄避法**
1. **`PUT /api/ai/config` 的 body 形状是 `{组名: {"点号键去掉组前缀": "字符串"}}`**（`{"tool":{"runcode.enabled":"true"}}`）。我按点号拆成三层嵌套 → Jackson 反序列化失败回 500「系统繁忙」，看着像服务端坏了；把内层键写成整条点号键（`tool.tool.runcode.enabled`）则是 400「这些项系统不认」。
2. **run_code 在「工具执行确认=每次问我」时按设计不挂**（`SandboxToolProvider.runCodeAllowed`：脚本子调用要逐项过闸，一次扇出几十张卡，"逐项确认"这个承诺兑现不了）。我第一轮因此"面板没帧"，其实是**夹具状态没还原**：探针把默认智能体临时调成 ask，脚本异常退出没走还原那行。两条口径——① 夹具改过的用户设置要放在 finally 里还原；② 判"已还原"要回查 **DB 列**，`GET /api/ai/agent/{id}` 不回这个字段，读接口判会误判成已还原。
3. **提取成员到新 bean 时，区间要以「声明」为界，不能以「下一段注释的文字」为界**——我按文字行切 chatflow 区间，把下一个成员的 javadoc 首行 `/**` 一起带走，新文件以悬空注释结尾，编译报「未结束的注释」。

**没验的（按推断记）**
- **自动压缩阈值那条路**（`assemble()` 里 `totalTokens > threshold` 那一段）：本批只验了手动 `/compact` 与近期历史文本，两条共用同一个 `summarizeHistory`/`historyLine` 实现；自动触发要「摘要 + 积压 > 检索预算 × compressRatio」，本轮五轮问答没够到阈值。别当已验。
- **提问卡的「忽略」分支**：本批只验了作答；忽略与作答共用 `doAskUserMulti` 同一段等待/收尾代码（该代码只改了 `st.channel.emitter` 一处），刀 1/刀 0 已验过忽略落 `TIMEOUT`。
- **Node stub 与并发扇出**：本批只跑 Python、两次子调用，与刀 3a 同一条未验清单。
- **H5**（前端零改动，`npm run check` 全绿）、**四条非对话入口**（`chat(ChatCommand, SseEmitter)` 签名一字未动；`ragService.` 的外部调用现在只剩 8 处：`chat` ×6、`listSessionEvents`、`resolveChatModel`）、**免登录分享页**（`ShareController` 一行未改，刀 2 已验那三个入口）。

**刀 5 现在能直接拿走的落点**
1. **引用池已经是一个类**：`TurnSourceRegistry` 里字段与方法同家（编号、去重键、图片编号的原子性写在实现里），刀 5 只需把它从"`TurnState` 的子对象"改成"门面按轮持有并传入"，`RagService` 侧那 5 处 `st.refs.` 调用点的语义一字不用改。§五-2 说的"`st.` 降到 0"就是从这一族开始降。
2. **持久化缝的四处尾巴要一起收**（都动 `c_ai_message` 与时间线，属同一个去向，拆开必然互调）：`persistPartialAnswer` + `TurnRuntime.persistPartial`（半程正文）、`buildTimelineSnapshot`/`recordToolStatus`/`flushTimeline*`（时间线与工具卡）、`persistPlanRejected/Superseded/Closeout` + `planRecordJson`（计划收尾，刀 4 刻意留下的）、`buildUsageSnapshot`/`calibrateParts`（用量与容量校准）。
3. `chat()` / `runChat()` 的提交与编排仍在问答侧（刀 2 就为这条不肯搬走 `chat`）；等流水线进门面，§八 D2 的命名才有对象。
4. 剩下最大两块是**上下文填充**（要 `refs`，已就位）与**提示词装配**（要挂载判据，`PlanTurnHooks` 那条口径已就位）——刀 5 里谁先动取决于谁回头调谁，别让填充反过来叫提示词。

## 二·十三、刀 5a 已落地（2026-10-13，编译 + 真机全绿）：引用面定型

刀 5 太大（剩下的上下文装配、提示词装配、生成流、落库缝、入口门面），先按可独立停手切第一刀——**把"本轮引用面"整族定成一件事**：池自己拿依赖、校验只有一个入口。

**结果数字**：`RagService` **5895 → 5696 行（−199）**。新 bean `agent/context/CitationIntegrityService` **292 行 / 5 个注入依赖**（ConfigService、ChatClient、ImageFilterService、AppProperties、ChatRunRuntime），搬走 8 个成员（`CITE_PATTERN`/`CODE_SEGMENT`/`codeRanges`/`inCodeSegment`/`citationConsistencyCheck`/`CitationCheckResult`/`IMG_NUMBER_PATTERN`/`IMG_NUM_DIGITS_PATTERN`）+ 流收尾里散着的**四道校验**（图片相关性兜底、未标注引用、引用编号越界、图片标记越界、语义自检——五段共 100 行，收成一个 `check(answer, st)` 返回 `Outcome(text, sources, images)`）。

**`TurnState.Deps` 从 6 个组件缩到 2 个**（`runBus` + `runtime`），`TurnRuntime` 从三个动作缩到**一个**（`persistPartial`）：
- 池原先借 `deps` 拿 config/rerank/signer 与 `sendSse`/`addDegradation`——那六件里只有"投递与降级登记"勉强算本轮动作，其余都是无状态服务，让状态袋替它们转一手，袋就得认识重排与图片签名这两件与「本轮跑到哪」无关的事。现在 `TurnSourceRegistry` 的构造点在流水线（`RagService.newSourceRegistry(...)`，主链路与无知识库分支两处），直接吃那三件服务 + `ChatRunRuntime`。
- `TurnToolset` 原先也吃 `deps`，只为 `consult().newLedger()`；改吃 `SubAgentConsultService` 本体。台账"建轮第一句就建、不能懒建"那条口径原样保留（注释跟着走）。
- 于是 `Deps` 剩下的两件才是它名字该有的意思：**本轮还挂在运行总线上吗**、**半程正文怎么落**。这两个都是"问答侧还要替本轮做的事"，不是无状态服务清单。

**收口时抓到一条真缺陷（照搬不改才会露出来）**：语义自检原本**在越界剔除之后**自己重算一次 `codeRanges(answer)`。我收口时想省一次计算，把剔除前算好的范围传进去——越界剔除会删字符、偏移全变，复用旧范围等于用错位的一把尺去量"这段是不是代码字面量"，代码里的 `[0]` 会被当成正文引用参与判定。已改回**按当前这份正文重算**（`consistencyCheck` 里第一行就是它）。**教训：把两段"看起来一样"的计算收成一次调用前，先确认它们的输入是同一份——收口不是无脑去重。**

**顺带一处手抄**：`[图片N]` 这个标记语法原先在上下文填充与终态校验里各写了一份字面量正则，提成 `TurnState.IMG_NUMBER_PATTERN` 单源（与刀 0 第一步的 `IMG_PLACEHOLDER_PATTERN` 并排）。

**真机验收（2026-10-13 19:44–19:56，最终字节码；探针会话与消息/账本已删净、库里 PENDING 0 条）**
1. **主链路 + 工具命中注册**（池脱袋后）：一轮知识库问答拿到 8 条来源（`RETRIEVAL` ×1 + `TOOL` ×7，编号连续），正文 1381 字；期间 **3 帧 `image` 事件**由池重发全量签名清单（`assignToolImages` 走的是池自己拿的 `ChatRunRuntime`，不再是 `deps.runtime().sendSse`）。
2. **越界引用剔除**（新类直接证据）：指令式让正文原样写出 `[7]` 与 `[图片9]`，本轮无可溯源来源 → 日志 `c.w.a.a.c.CitationIntegrityService: [CITE-CHECK] 剔除越界引用 1 处 (maxRef=0)` + `[IMG-CHECK] 剔除无效图片标记 1 处 (maxImg=0)`；**落库/done 的正文里两个标记都不残留**，degradations 出 `invalidCitation` + `invalidImageRef`（`maxRef=0` 也执行，正是那条"来源为空照样拦"的口径）。
3. **语义自检每轮在跑**：`[CITE-CHECK] 自检完成: 剔除 0 处, 耗时 1040~1306 ms` 出现在每一轮（本机 `chat.citationCheckEnabled=true`、`imageFilter.enabled=true`）。
4. **`Deps` 只剩一条动作后回归两条**：出字后叫停 → 只落 1 条助手消息、尾部「⏹ 已停止本轮」（`persistPartial` 那条匿名实现照常）；跑着时断线 → `/chat/run` 回 `running=true`（`runBus.holdsRun`）→ `/chat/resume` 先 `snapshot` 再接 247 帧 → 该轮完整落库 1515 字。
5. `mvn test` 3/3；前端零改动。

**没验的（按推断记）**：**语义自检真的剔除那一支**（造不出"编号合法但语义不支撑"的复现，只验到它在跑与耗时可见——代码逐字搬，与原文归一化后只剩 5 行机械差异）；Node stub、自动压缩阈值、提问卡忽略分支、H5、四条非对话入口仍沿用刀 4 那条未验清单。（**图片相关性兜底 `imgFilterDropped` 那一支已在刀 5b 第 5 条补成已验**：真剔除 3 张。）

**刀 5 剩下的三子刀（下一批）**：5b 上下文装配与提示词装配（`runChat` 里那两段最长的 inline，配 `RetrievalPipeline`）；5c 落库与用量缝（`persistPartialAnswer`+`buildUsageSnapshot`+时间线三件+`recordToolStatus`，做完能把 `TurnRuntime` 最后那条动作也消掉）；5d 入口门面与改名（`chat()`/`runChat()`/`runNoKnowledgeChat()` 进 `ChatService`，8 处外部调用点同步）。

## 二·十四、刀 5b 已落地（2026-10-13，编译 + 真机全绿）：上下文装配

**结果数字**：`RagService` **5696 → 5084 行（−612）**。新 bean `agent/context/RetrievalPipeline` **783 行 / 7 个注入**（ConfigService、KeywordExtractor、RerankService、ImageFilterService、DocumentMetaCache、ModelRegistryService、KnowledgeMapper）。搬走的是一条完整职责链：**@ 引用块前置 → 子智能体命中强制重排后并入 → 相邻块合并 → 分域相关度门与单文档/单库配额 → 信息增益去冗余 → 块内片段截取 → 图片占位换全局编号 → 产出 `sources` 与 `context`**。搬法是把 `runChat` 里那段 309 行 inline **整段抽出**（含 `MentionScope`/`CtxBudget`/`resolveContextBudget`/`effectiveMaxOutputOf`/`applyScope`/`loadMentionChunks`/`buildMentionText`/`snippet`/`extractHitSnippet`/`truncateChars`/`isRedundantHit`/`mergeAdjacentHits` 与三个平台常量），**不重写逻辑**。

**接缝形状**（两处设计决定会影响后面每一刀）
1. 入参是一个 `Request` 记录、出参是一个 `Assembled` 记录，**降级登记经 `BiConsumer onDegrade` 交回调用方**——本类不认识 `TurnState`，也就没有"新 bean 反过来摸状态袋"这条回头路。`systemText`/`userQuestionText` 只以字符串进来算 `fixedTokens`，装配不回写提示词（回写是本类后续的编排活儿）。
2. **`Assembled` 交出的 `sources`/`imgIndex`/`imgDescIndex`/`fileNames` 是同一批可变容器，不是副本**：调用方紧接着还要拿它们做分支编号回填、建 `TurnSourceRegistry`、发 `image` 事件。返回副本会让回填丢进一个没人看的列表里——那种 bug 表现是"子智能体查到的资料点不开来源"，排查成本极高。
3. `hitsUsed` 与 `sources.size()` 是两件事：主链路填充块数（`docNo-1`）与引用表长度在分支回填后会不相等，`retrieved` 事件的 `refs` 用前者。这条我特意在类里写死了注释，免得下次有人"顺手改成 size()"。

**逐字等价的查法**：从 `git show HEAD` 取原 inline 段，与 `assemble` 体做归一化 diff（去注释、去缩进、把三处必然的机械改动归一：`runtime.addDegradation(degradations, degradedCodes,` → `onDegrade.accept(`、`loadMentionChunks(mentionScope, …)` 参数、`subOutcome.hits()` → 入参 `subHits`）——**结果只剩新加的 `return` 与收尾 3 行**。这比"读一遍确认没改错"可靠得多（刀 5a 那条真缺陷就是这么露出来的）。

**真机验收（2026-10-13 20:22–20:40，最终字节码；日志类名已是 `c.w.ai.agent.context.RetrievalPipeline`）**
1. **正常一轮**：`[CTX] 上下文填充 1 块`，来源编号 `[1..9]` 连续（`RETRIEVAL`+`TOOL` 混排）、`retrieved` 事件 `{"refs":1,...}` 与之一致，正文 900+ 字。
2. **@ 引用文档前置**：3 块全 `origin=MENTION`（`07-工作流.md`），`上下文填充 3 块`——显式指定不走门与配额那条口径保住了。
3. **子智能体分支并入与回填**：主链路 0 块的一轮里，来源仍有 `[1..5]` 且 `origin=SUBAGENT` ——**分支回报走的仍是同一批容器**（Assembled 不返副本这条决定就是在保护它）。
4. **门与配额**：一条与知识库无关的问题（量子纠缠）→ 主链路 0 块、无 `RETRIEVAL` 来源，15 条来源全来自工具与联网路径。
5. **图片全局编号 + 相关性预筛（顺带把刀 5a 的一条"没验"补成已验）**：「智报平台怎么新建报表」一轮 → 19 条带图来源、**16 帧 image 事件**（末帧签名清单 26 张）、正文实际引用 `[图片1..3]`；同轮降级里出现 **`imgFilterDropped`「已剔除 3 张与回答内容不匹配的图片引用」**——这正是刀 5a 记为"造不出复现"的那一支。
6. **无知识库分支**：临时建 `knowledgeDisabled=1` 的智能体 → 不装配也正常答完 212 字、来源 0 条，夹具用完删除。
7. `mvn test` 3/3、`npm run check` 全绿；`RagService` 侧只剩编排与提示词装配。

**本轮一条自伤事故（教训要留）**：清理探针会话时我图省事用了**按标题前缀硬删**（`title like '智报平台里怎么新建报表%'`），把 6 条用户自己的历史会话（最早 2026-10-03）连带删了。本机 MySQL 开着 ROW binlog，按 `### DELETE FROM` 的 WHERE 镜像重建 INSERT 全量复原（6 会话 + 25 消息 + 149 账本事件 + 1 审批记录），并用应用自己的读口复核：`GET /api/ai/session/{id}` 消息数、来源数、工具卡数、`turnId` 都在，`/api/ai/sessions` 的 `total` 回到事故前的 154。**口径：探针资源一律只删自己收集到的 id，禁止按标题/内容模糊匹配删。** 已写进 [[project-probe-fixture-restore]]。

**刀 5 剩下的**：5c 落库与用量缝（`persistPartialAnswer`+时间线三件+`recordToolStatus`+`persistPlan*`+`buildUsageSnapshot`/`calibrateParts`，做完能消掉 `TurnRuntime` 最后那条动作）；5d 入口门面与改名（`chat()`/`runChat()` 剩下那 ~700 行编排 + 提示词装配，8 处外部调用点同步）。
→ 两刀都已在 2026-10-13 落地：5c 见下面这节，5d 见 §二·十五。

## 二·十四·补、刀 5c 已落地（2026-10-13，编译 + 真机全绿）：落库与记录族

**结果数字**：`RagService` **5084 → 4501 行（−583）**。新 bean `agent/chat/TurnRecorder` **697 行 / 8 个注入**（SessionService、ArtifactService、ChatRunRuntime、SessionEventService、ToolRegistry、ToolSpillService、RunCodeSubCallRuntime、ChatRunBus）。`TurnState.Deps` 收到**两件**（`runBus` + `BiConsumer<TurnState,String> persistPartial`），**`TurnRuntime` 动作接口整体退场**——门面用 `recorder::persistPartialAnswer` 方法引用交进去（`ChatService:695`）。

**收进来的成员**：`recordToolStatus`（工具卡片留痕，start/done/error 三态 + 扇出 + spill）、`recordToolOutput`、`routeProcessText`、`flushTimelineText`/`flushTimelineProcess`/`pushTimelineArtifact`/`buildTimelineSnapshot`/`timelineSnapshot`、`persistPartialAnswer`（半程落库）、`buildUsageSnapshot`（含私有 `calibrateParts`）、`truncationSuffix`、`planRecordJson`/`persistPlanRejected`/`persistPlanSuperseded`/`persistPlanCloseout`、`extractCachedTokens`、`truncBrief`，以及四条终态后缀常量（`RESTART_SUFFIX`/`STREAM_ERROR_SUFFIX`/`LENGTH_TRUNCATED_SUFFIX`/`OUTPUT_LOST_SUFFIX`，改由 `TurnRecorder.` 前缀引用）。

**接缝形状**：`TurnRecorder` 是唯一被 `TurnState.Deps` 够到的落库方——**它不回头调问答入口**（这条仍是本卡每一刀的铁律）。时间线段的产出顺序、`toolCalls` 下标锚点、spill 读口这些"记录几何"全在这一侧，门面只管"什么时候该记一笔"。

**逐字等价的查法**：同 5b——从 `git show HEAD` 取原 inline 段与 `TurnRecorder` 各方法体做归一化 diff（去注释/缩进，把 `st.` 前缀与 `recorder.` 转调归一）。8 个被搬方法 **7 个逐字一致**，唯一差异是 `truncationSuffix` 那处的注释重排与包装改名。

**真机验收（2026-10-13 21:0x，最终字节码；日志类名 `c.w.ai.agent.chat.TurnRecorder`）**
1. **正常一轮**：`done` 事件 tokens 键集 `budget/cached/context/hits/output/outputIsReal/parts/prompt/total/window/windowSource` 齐；工具卡带中文展示名；时间线 6 段（`process`/`tool` 混排）；落库行的 `tokens`/`timeline`/`toolCalls` 三列都在——**刷新后卡片与时间线还能复原**，这条是 5c 的主验收点。
2. **停止本轮 → 半程落库**（经 `Deps.persistPartial` 这条新缝）：助手消息 1 条、正文 22 字、尾部 `⏹ 已停止本轮，以上为已生成的部分`、`tokens` 与 `turnId` 在。
3. **计划否决**：正文落 `（计划未批准，本轮已停止）`，计划快照与 versions 一并落库（`persistPlanRejected` 走的是记录族，不是门面）。
4. **run_code 扇出**：顶层卡片只有 `run_code`，`fanOut` 与 `run` 终态字段**在 done 事件里也在落库行里**（刷新后点得开），另有 2 帧 `tool_output`。
5. `mvn test` 全绿（当时 3/3；含 P7 刀 1 护栏后 7/7）、`npm run check` 全绿。
6. **两条探针假阴性要记账**（不是回归）：其一 R2 首跑没在超时前收到 token（那一轮正被子智能体占着），换轻量流式提示词后落库正常；其二 `resume()` 读口没剥 `{"type","content"}` 信封，误判成"接流丢内容"。都是探针写法问题，代码未改。

**没验（留给后面的口子）**：进程被杀后的 `RESTART_SUFFIX` 路径、Node 脚本 stub 与并发扇出、历史自动压缩阈值触发、提问卡「忽略」终态的落库形状、H5 外壳下的卡片渲染、四条非对话入口（分享页 / 定时任务 / 工作流节点 / MCP server）走同一落库缝。

**偏离卡的地方**：卡上写"落库与用量缝"，实际落点扩成**落库 + 时间线 + 工具卡片留痕 + 计划快照**四件——它们共用同一批私有件（`truncBrief`、spill 记账、timeline flush、`planRecordJson`），按卡上口径拆成两三个 bean 会让彼此互够私有方法，等于没拆。另一处：卡上只说"消掉 `TurnRuntime` 最后那条动作"，实际是**整个接口删掉**，只留一个方法引用——留着接口就等于给门面留了一个"往 TurnState 上挂行为"的合法入口。


## 二·十五、刀 5d 已落地（2026-10-13，编译级验证全绿）：入口门面与改名

**结果数字**：`RagService` 4501 → **`ChatService` 4502 行**（+1 为类 javadoc 重写；本刀纯机械改名，逻辑零改动）；`grep -rn "ragService\." src/main/java` **8 → 0**；`st.` 引用 360 行（5c 之后的值，本刀不动）。

**改了什么**
1. **文件与类**：`service/RagService.java` → `service/ChatService.java`（`git mv`），类名、构造器、两处 `RagService.this`（planHooks 匿名实现里）同步；类 javadoc 从「RAG 问答服务」重写为门面定位——持有 `RetrievalPipeline`、落库归 `TurnRecorder`、检索执行在 `HybridRetrievalService`/`RerankService`、轮次活在 `ChatRunRuntime`。§八 D2 的终态命名到此落地。
2. **外部调用点 8 处同步改指 `chatService.`**：`ChatController` ×3（`chat`/`listSessionEvents`/`resolveChatModel`；Lombok `@RequiredArgsConstructor` 随字段类型走）、`ShareController`、`ScheduledJobService`、`WorkflowEngine`、`McpServerService` ×2（后三个是显式构造器，参数名一并从 `ragService` 改 `chatService`）。另有 `KnowledgeBaseController` 两处**全限定**引用 `RagService.AGENT_QUERY_PARAM_KEYS`（公开常量随类迁址，该文件无 import 可改）。
3. **全仓 67 个 java 文件的注释/javadoc 引用同步**（词边界替换）。唯一的机械风险是子串撞名：`GraphRagService`/`graphRagService` 里含着 "RagService"——本机 `sed` 是 toybox，`\b` 不生效（静默不替换），改用 `[[:<:]]`/`[[:>:]]` 并用夹具先行验证（`GraphRagService graphRagService` 原样、`RagService#chat` 正确替换）后才批量。`ChatService` 类 javadoc 里「由 {@code RagService} 更名」一句**刻意保留**旧名（它是这段历史的说明，不是活引用）。`README.md` 目录说明同步。
4. **前端 5 处注释与 1 处夹具脚本注释按"方法现在的归属"改准**（不是简单换类名——这些注释引用的方法在早前各刀就已迁走，简单替换会写成新错误）：`store.js` 的 `sendSseEvent` → `ChatRunRuntime`（刀 2 迁走）、`api.js` 的 `persistPartialAnswer` → `TurnRecorder`（刀 5c）、`projections.js` 的 denied 标记 → `TurnRecorder.recordToolStatus`、`useChatEngine.js` 的 `compactSession` → `HistoryCompactionService`（刀 4）、`check-ask-restore.cjs` 的 `listPendingAsks` → `HitlDecisionService`（刀 1）、`fake_upstream_no_tools.py` 的 `looksLikeToolsRejected` → `ChatService`（从未迁走）。改完 `npm run check` 四脚本全过。
5. **顺手改直两处刀 2 之后就已陈旧、改名会变成新错误的注释**：`ChatController`/`ShareController` 的「存活看门狗」归属改为 `ChatRunRuntime`（本体刀 2 已迁走，原文挂在已不存在的类上）；`ScheduledJobService`/`McpServerService` 的「ACTIVE_SSE 残留」归属改为 `ChatRunRuntime`（这两处的下一行本来就是 `ChatRunRuntime.forgetSseChannel`）。

**验证（编译级；未跑真机）**
- 全量编译 **359 个源文件**（javac `-parameters -implicit:none`，~/.m2 全量 classpath + `target/classes`）**7 秒通过**，仅 deprecation 提示；产物中 `ChatService.class`（含 5 个匿名内部类）在场、`RagService.class` 为 **0**。
- 构造器签名核对（`javap`）：`ChatController`/`ShareController` 的 Lombok 构造器与三个显式构造器入参均为 `ChatService`；`javap -v` 确认 `ChatService` **82 处 `MethodParameters`**（`-parameters` 生效，Spring 按形参名兜底注入不受影响——漏这个参数的假通过坑见 wenqu-backend-verify）。
- 装配环静态扫描：**173 bean / 含环分量 0**（改名不改变依赖图，但要防手滑）。
- `src/test` 唯一用例 `ConfigServiceUpdateTest`（与改名零引用）用 JUnit platform launcher 直跑 **3/3**。
- 前端仅动 5 处注释（无代码、无契约），`npm run check` 四脚本（verify-sfc / check-guard / check-h5 / check-engine-imports）**全部通过**。
- **未做真机验收**：改名不含逻辑改动，「编译过 + 构造器签名对 + 无环 + 单测绿」即等价证据；§五 行为等价清单（流式问答/停止/接流/四入口）留待下一次带真机的刀或用户重启时顺带覆盖。

**⚠️ §五-1 的 ≤1500 未达成——立项估计错了约 5 倍，要说清**
计划估计「刀 5 后剩 ~700 行编排 + 150 行提示词装配」，实际 4502 行。差额在于估计把 `buildAnswerStream`（~740 行生成流与终态回调）、工具装配与执行接缝（~660 行）、流文本过滤静态件（~260 行）、检索范围与参数覆盖（~120 行）、会话事件读口（~120 行）、模型/身份解析（~70 行）、智能体解析与查询改写（~155 行）都当成了已抽走——这些族**一刀都没被安排过**（§〇 的 13 件事清单里也没有它们）。按计划对门面的定义（编排 + 提示词 + 工具接缝 + 读口），现实下限约 **3300 行**：≤1500 从立项时就不可达，不是本刀偷工。

**剩余可切族（下一刀候选，按风险从低到高）**

| 族 | 约计行数 | 成员 | 风险 |
|---|---|---|---|
| 流文本过滤 | ~260 | `tagPrefixSuffixLen`/`containsUnclosedRelated|Process`/`containsUnclosedDsml`/`stripDsmlToolText`/`dropUnclosedDsmlTail`/`extractRelated` 等 15 个纯静态件 + 4 个 Pattern + `HTML_TAG_NAMES` | **零**（无实例状态、无注入，可逐字搬；5 个 package-private 静态件的 javadoc 本就写着"供单元测试直接验证"，搬完正好补上这些单测） |
| 检索范围与参数覆盖 | ~120 | `scopeKbIdsOf`/`resolveScopeDocIds`/`builtinScopeKbIds`/`builtinDefaultKbId`/`applyQueryOverrides`/`resolveQueryParamKbs`/`parseQueryParams`/`AGENT_QUERY_PARAM_KEYS` | 低（只依赖 `KnowledgeBaseService`+`ConfigService`；公开常量迁址要同步 `KnowledgeBaseController` 两处） |
| 会话事件读口 | ~120 | `listSessionEvents`/`eventItem`/`turnGroup`/`resumeSnapshot` | 低（纯读 + SSE 快照；计划原话"留在问答侧"，迁不迁是口径选择） |
| 工具装配与暴露判据 | ~660 | `enabledToolCallbacks`/`finishMounts`/`mountContext`×2/`agentFlagFor`/`agentSelectionFor`/`applyPermissionTier`/四个 `*Exposed`/`humanLoopGuide`/`consultGuide`/`warmUpToolSchemas` | 中（匿名 `ToolMountContext`、写 `st.tools.*`、同时喂装配与提示词两份判据；`TurnRecorder` 已有"bean 吃 TurnState"先例，但它值得单独一张卡） |

建议下一刀先切**流文本过滤**（零风险、当天可收、直接消灭一类"正则散在门面里"的坏味），再议检索范围族；工具装配族单独排卡。

**施工状态提醒（提交前必读）**：本刀开始时 **5b/5c 尚未提交**（`RetrievalPipeline`/`TurnRecorder`/`TurnState` 与 `RagService` 的对应删减都在工作区），5d 叠在其上。提交时须拆三刀（5b → 5c → 5d），且 **5c 的落地记录已补**（§二·十四·补：`TurnRecorder` 697 行 / 8 注入 + 真机四条验收 + 没验清单）。

## 三、施工约束

- **不许在拆分 commit 里夹带功能或调默认值**（提示词、检索参数、门槛都不改）。
- **不许把 39 个依赖整体抄进新类**：每刀只带该族真用到的依赖。这是搬方法时最容易犯的错，会让新类一出生就是第二个运行时。判据：`ChatRunRuntime` 的 `private final` ≤ 15。
- **不许提前建 `ChatService` 门面**：门面早于切面 = 空转壳。等刀 2 完成再谈命名（§八 D2）。
- `chat(ChatCommand, SseEmitter)` 签名不许动；要动就在刀 2 一次改完并同步全部 25 处调用方。
- 每刀合入前跑：后端编译 + `npm run check` + §五 行为等价清单。
- **任何一刀超过 5 天没合，说明切面选错了**，回去找更小的缝，别硬推。

## 四、TurnState 的具体做法（刀 0 单独说，因为它决定后面每一刀的形状）

1. 第一步**原样提出**：`AnswerStreamState` → 顶层 `static class TurnState`，构造参数显式接收原先从外层实例拿的东西（`sendSseEvent`、`addDegradation`、`configService` 等）。行为零变化，只改可见性。
2. 第二步才按归属下沉子结构：**已做（2026-10-13，见 §二·十一）**。落点比原计划多了第四簇——`TurnState.refs`（`TurnSourceRegistry`，引用池连注册方法一起搬，刀 5 收编它即可）、`TurnState.channel`（`TurnChannel`，刀 2 操作的状态）、`TurnState.tools`（`TurnToolset`，刀 3 与 P5 的装配/守卫面）、`TurnState.board`（`TurnRunBoard`，卡里没列但归属同一条：刀 3a 的行为早已在 bean 上，状态还平铺着）。剩下 67 个字段是本轮身份、生成与时间线缓冲、token 与容量用量、人在回路与落库归属——它们要等刀 5 的门面成形才各有去处。
3. 禁止的走法：给 TurnState 加 `Object getBean(...)` 或反向持有 RagService 引用——那等于把隐式循环依赖换成显式的，比现在更糟。

## 五、验收

> 刀 1 已跑的一批记在 §二·六（含没跑到的三条），下面的清单留给后面每一刀照抄。

**结构断言（可 grep；括号里是 2026-10-13 刀 4 收口时的实测）**
1. 每一刀后 `RagService.java` 行数单调下降，刀 5 结束时 ≤ 1500 行。**现状：10118 → 9472（刀 1）→ 8678（刀 0 第一步）→ 8149（刀 2）→ 7738（刀 3a）→ 7274（刀 3b）→ 7276（刀 0 第二步，只多注释：这一刀改归属不改引用量）→ 5895（刀 4 四族）→ 5696（刀 5a 引用面定型）→ **5084（刀 5b 上下文装配）→ 4501（刀 5c 落库与记录族）→ 4502（刀 5d 更名 ChatService，+1 为类 javadoc 重写）**。⚠️ **≤1500 未达成**：立项时估计"刀 5 后只剩 ~700 行编排"少了约 5 倍——剩余 4502 行的真实构成与后续切面见 §二·十五。
2. `grep -c "private final class AnswerStreamState"` = 0（**已达**）；`st.` 引用从 728 降到 0（**现状 360 行有引用**——5b 时 652，5c 把落库缝搬进 TurnRecorder 后降至此；刀 5d 更名不动引用量）。
3. 新类各自的 `private final` 数：`ChatRunRuntime` ≤ 15（**6**），其余 ≤ 10（**TurnSourceRegistry 6 注入 + 6 本轮上下文、HistoryCompactionService 6、DeepThinkService 6、ChatflowBridge 7、PlanModeService 8 + 一份回call接口、TurnRecorder 8**）。`ChatService`（原 RagService）侧注入 46——**每一刀都带进一个新 bean、同时摘掉真用完的依赖**（刀 4 摘了 `workflowServiceProvider`），这条不是回归，是"依赖清单没变少、但每条都变小了"：它现在装的是 5 个无状态服务 + 一批按族切出来的 bean，而原先那些 bean 的职责全在它自己身上。
4. `grep -rn "ragService\." src/main/java` 只剩刀 5 之后仍合理的调用。**刀 5d 起为 0 处**：原 8 处（`chat` ×6、`listSessionEvents`、`resolveChatModel`）连同字段与构造参数一并改指 `chatService.`；`resolveChatModel`/`listSessionEvents` 仍在问答侧（后者要 `loadPrefUser`，见 §二·十二 那张表的"留在问答侧"一列）。

**行为等价断言（每刀跑一遍，真机）**
5. 流式问答正常；停止本轮生效；运行中插话在下一个工具步送达。
6. 断线重连能接上流（含审批挂起时断线，恢复后裁决仍可提交）。
7. 三类人在回路卡片（审批 / 提问 / 计划）裁决后能唤醒等待中的那一轮。
7b. **叫停顺序断言（刀 1 的回归靶）**：挂起一张审批卡 → 点停止 → 该记录必须落 `STOPPED` 终态且 `/tool-approval/pending` 不再返回它；若它停在 PENDING，说明"先落库再掐流"的顺序被拆散了（见 §二·五）。
7c. 进程重启后：遗留 PENDING 被 `purgeStaleApprovals` 收成 TIMEOUT，卡片 `live=false` 且作答不静默失败。
8. 定时任务、工作流触发、对外 MCP、免登录分享页四条非对话入口全部照旧。
9. 过程面板与账本回放：刷新后每条气泡归属、工具步、耗时、引用编号仍在原位。
10. H5 全流程（`npm run check` + 412×916 真机一轮问答）。

## 六、不做什么

- 不改任何用户可见行为、文案、默认值。
- 不动数据库、不动配置键。
- 不顺手拆 `ChatController`（914 行，比 RagService 小一个数量级，且刀 2 会自然把它的 16 处调用改指向新门面——先让它挡一次路再说）。
- 不引入新框架（不加 reactor、不改 SSE 通道模型）。

## 七、与 P5 的关系和顺序

**P5 先，P6 后，别倒过来。** 三条理由：
1. P6 无用户价值、周期长（16–22 人日），还会跟 P5 抢同一批正待删除的代码（`resolveSessionAgent`、`resolveAgent`、派遣事件）。
2. P5 是**净删约 200 行**（见 `docs/p5-agent-as-tool-2026q4.md` §一 删除清单），先做能让刀 0 少搬一截。
3. P5 的 `consultAgent` 台账与预算逻辑，落点正好是刀 3 的 `SubAgentBranchRuntime`——所以 P5 期间把它写成独立类（P5 §二·五 的约束），刀 3 时直接收编，不返工。

## 八、分岔

- ~~**D1 刀 1 还是刀 0 先**~~ **已定（2026-10-12 实测）**：刀 1 先，形状见 §二·五。裁决侧零 `TurnState` 依赖，但要把 `stopTurn` 用的两个释放方法一起搬，并把登记表从 `static` 收成实例字段。
- **D2 终态命名**：(a) `ChatRunRuntime` + `RetrievalPipeline`（承认现状，名字跟着职责走）；(b) 你原话的形态——`ChatService` 持有 `RagService`，`RagService` 退回纯检索。
  推荐 (b) 的**语义**、(a) 的**过程**：先把运行时切出来（此时它还叫 RagService 也无妨），最后一刀再改名成 `ChatService` + `RetrievalPipeline`，避免中间期出现"同名两类互相调用"的空转壳。
- **D3 要不要给拆分排整块时间**：它不产出用户可感知的变化，排期上永远会被功能挤掉。建议按刀排（每刀 1–4 天，独立可停），不排"重构冲刺期"。

---

## 关联

- `docs/p5-agent-as-tool-2026q4.md`：§二·五 施工约束（P5 新代码不进 RagService）是为这张卡留的。
- `docs/p7-architecture-guardrails-2026q4.md`：这张卡收口后的下一段——P6 的验收口径（拆出去的族不回头抓平铺 Service）已经在那边写成了可执行的架构护栏。
- `docs/agent-runtime-gap-2026q4.md`：P1 seam 化那一章讲过"族是收口的单位"（`ToolFamily.java:4-15` 的注释就是本卡刀法的先例——同一批毛病当年在工具装配处收口过一次）。
- 记忆：`project-ragservice-god-class-p6`、`project-agent-routing-teardown`、`project-p3a-full-loop-branches`、`project-server-side-stop-turn`、`project-detach-resume-run-bus`。
