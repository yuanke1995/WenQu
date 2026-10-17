# 问渠 × DeepSeek Harness · 对标口径与自研路线（核实版 2026-10-09）

> 对标对象：[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)（2026-08-13 开源，"一切皆插件"，Cordis 插件树，96 个 `ctx.*` seam）。
> 本文只回答两件事：**问渠的 agent 运行时缺什么机制**、**这些差距按什么顺序补**。所有现状结论均可回溯到具体文件行号（2026-10-09 核实）。

## 〇、先读这段：口径与订正

**口径：机制照抄，架构不抄，主场不换。**

DSH 值得照抄的是**机制**（行业已收敛的 agent runtime 共识）：事件日志作真源、turn/step 双层循环、守卫三段链 + 单调 guard、沙盒档位 × 审批策略两个正交 knob、PTC 程序化编排、子 agent 跑完整循环、大输出 spill。这些没有一样是"DSH 特色"。

DSH 不值得抄的是**它的架构由其形态决定**：DSH 是本机单用户开发机 runtime（TS/Cordis 插件树、`dsh web` 起 127.0.0.1:3080、bwrap/Landlock/Seatbelt 沙盒、JSONL 文件即数据库）；问渠是服务端多租户知识平台（MySQL、RBAC、共享范围、provisioner 编排容器、Web/H5、对外 MCP server）。

**订正声明（本次核实推翻了三条直觉判断，别按它们做）**

1. **"沙盒 env 泄漏"是纸面差距**：`ProvisionerSandboxProvider.loadUserAgentEnv:143-145` 恒返回空表 → `SandboxProvisionerClient:85` 传空 env；provisioner `app.py:1174-1179` 只合并服务端拥有的 `sandbox.env`、请求不可覆盖；`/v1/shell/exec` 调用体不含 env；网络隔离反而更强（每沙盒一条专属 docker 网络 `app.py:939`，跨容器不可达）。→ env scrub 不单独立项，挂在 PTC 期当闸门。
2. **事件日志不是四条能力线的共同前置**：它只是 stop / resume / fork 的前置；PTC 的前置是 registry + 档位，子 agent 全循环的前置是 `ChatCommand`。
3. **`historyLine` 是字符串拍平，不是投影**：`RagService.java:5364-5375` 把历史拼成 `role: content\n`，**工具轨迹从不回灌模型请求**。这一跳是本次路线里最容易被低估的成本（见 §五）。

---

## 一、完全对标 DSH 会砸掉什么（三条具体后果）

| 照抄项 | 直接后果 |
|---|---|
| append-only JSONL 会话文件为真源 | 会话从 MySQL 行级锁 + 按 `uid`/`thread` 隔离，退化成宿主文件系统单文件；`ResourceVisibilityService`（`share_config` v2、按 userId 隔离、**无角色全量短路**）与备份运维全部失效，多用户读同一份会话无处安放 |
| Cordis 插件树 + `dsh plugin add` + `node:vm` 动态插件 + 模型自装卸 | 运行期在**共享 JVM** 里执行第三方字节码 = 把供应链风险放进安全边界，一个越权插件即全租户可见。DSH 自己的文档就承认这是"安装时在你机器上执行任意代码、不受任何沙盒约束"，它敢这么设计是因为本机单用户 |
| 67 个开发机工具面（持久 PTY、`job_*`、`lsp`、`create_worktree`、`working_directory`、desktop/Electron/acp/typertGateway） | 问渠沙盒是第三方镜像的**同步一次性 HTTP**（`/v1/shell/exec`），持久 PTY 要先改 provisioner 协议；`lsp`/worktree 服务对象是程序员本机仓库，问渠的用户在查资料和出报告。结果是变成一个"没有本地文件系统的 Claude Code"——形态优势清零，且不带来留存 |

## 二、四个分岔点：同一机制，两种实现

| 机制 | DSH 的做法 | 问渠自研的做法 | 分歧根因 |
|---|---|---|---|
| 会话可回溯 | 本机 `session.vN.jsonl[.zstd]`，`deriveMessages()` 进程内投影 | MySQL `c_ai_session_event` 新表（消息行已是 insert-only）+ 服务端投影、Redis 广播失效（沿用 `ModelRegistryService:55-56` 多实例同步样板）；事件表**一期只服务展示与审计**，不进模型输入 | 多实例、多租户、要能被 RBAC 过滤 |
| 工具守卫 | 进程内 waterfall 扩展点 + 单调 `ToolGuard` | `ToolRegistry` + `pre/execute/post/guard` 链（纯 Java 接口 + 多实现 + 配置选实现，同 `WebSearchService.java:30 List<WebSearchProvider>` 既有样板）；**不做运行期加载**，扩展走编译期 SPI + 配置 | 代码由开发者维护，能力由用户配置 |
| 沙盒 | 本机 bwrap/Landlock/Seatbelt，per-call `SandboxExecutionPolicy` | provisioner 容器（**隔离本就更强**）+ 把三档 policy 透传成容器挂载/权限参数；`virtualPathPrefix` 可写、`/home/gem/skills` 只读，已是 `workspace-write` 的雏形 | 平台代跑，用户不拥有宿主 |
| 交付形态 | CLI/TUI/Electron/SDK/ACP，面向个人开发者 | Web + H5 + 分享页 + 对外 MCP server + 工作流 API：智能体是**可被别人调用的服务**，不是本机进程 | 主场不同 |

---

## 三、差距清单（要补的机制）

### ★★★ P0 · 长任务可控性

借的机制：`SessionEvent` 真源 + inbox `steer` + `todo_write` + `ctx.spillStore`。

| 缺什么 | 用户可感知变化 | 不做后果 | 现状证据 |
|---|---|---|---|
| 服务端停止本轮 | 跑动中能喊停且保留半程 | 点了就不能碰 | `ChatController` 无 stop 端点，只有前端 abort + `clientDisconnected:7260` |
| 运行中插话/转向 | 中途补一句"别查这个了"能生效 | 方向错了要重跑整轮 | 无 inbox，`agent/turn-stopping` 等价物缺失 |
| todo 逐步清单 | 屏幕上待办逐条打勾 | 计划是自由文本，跑 10 步后没人知道还剩几步 | `parsePlanDraft:6624` 批准后整块注入，无 step 状态 |
| 大输出 spill | 超长结果给"预览 + 可续读/搜" | 262144 字节 + 工具侧 2 万字**截断即丢** | `ProvisionerSandboxProvider:184-190`、`SandboxTools:34` |
| 断线重连接流 | 刷新/换设备看到的过程继续长出来 | 半程正文只能等落库 | 无 resume；工作流 `WorkflowRunStreamService` 快照回放是现成样板 |

> **P0 落地状态（2026-10-10 建，2026-10-12 补全为完整版）**：上表五项全部落地。
> 大输出留存 / 过程回放 / 多端接流 在 10-10 是**基础版**，10-12 的九刀把它们补到完整版，并把当时判为「不补/不做」的
> 四条翻案（存量轮身份、分享页接流、工作流接进总线、重启半程落库）。
> 每条能力的**真实边界**、还欠的真机验证、以及这三刀撞出来的四条既有缺陷，记在本节末的
> **「P0 的真实边界与验证账」**（原 `docs/p0-leftovers-2026q4.md` 已于 2026-10-13 整体收编进这里并删除）。
> 其中一条订正的是**本文与旧记录共同写错的事实**：沙盒命令输出的帽有两道（Java 侧按字节 + 镜像侧只回最后约 1 万行），
> 问答走流式读 log 文件所以全文在手，同步 exec 路径拿不到全文且**没有任何被砍标记**。

好消息（已核实）：`SessionService.appendToMysql:948-1007` **本就是 insert-only**（`selectForUpdate` 锁会话行 → `maxSequencePhysical+1` → `insert`，全工作区无内容列 UPDATE），`Session.summaryUntilSeq` 已是 checkpoint 水位，前端 `web/src/chat/projections.js`（836 行）已显式命名投影层 → **半径是加一张事件表，不是重写会话层**。

#### P0 的真实边界与验证账（2026-10-13 收编自 `docs/p0-leftovers-2026q4.md`，那份文件已删）

##### 一、沙盒命令输出的帽有两道（本文与旧记录一起写错过的那处事实）

| 旧结论 | 实测（2026-10-12） |
|---|---|
| 「输出在进 Java 之前就被 provisioner 砍到 262144 字节」 | 262144 那道帽**在 Java 侧**（`ProvisionerSandboxBackend` 同步路径留头、流式路径留尾）。镜像侧的帽是另一件事：`/v1/shell/exec` 只回**最后约 1 万行**（实测 5 万行只回 10,013 行 / 90,116 字节），响应里**没有任何"被砍"标记**，`truncate=false` 也一样 |
| 「要拿全文得改沙盒服务协议」 | 不用。问答走的流式路径读的是沙盒里的 **log 文件**（`/v1/file/read`），实测 10MB 单行、10 万行 11MB 都整份回传无帽——全文本来就在 JVM 手里，缺的只是别在截断时把它扔掉。改法：`ExecuteResponse` 多带 `fullOutput`（砍之前的整份，超 6M 字符不保留），`clip()` 把它交出去留存 |
| 「同步 exec 路径同样能拿全文」 | **不能**，而且是静默的：命令直接经 `/v1/shell/exec` 返回时，第 1 万零一行到第 5 万行在容器里就被丢了，Java 无从补。受影响的只有**没有输出回调**的调用方（子智能体分支、工作流代码节点、沙盒浏览面板跑脚本）；问答主链路必带回调，不受影响。要补得把同步路径也改成「输出重定向进文件再读回」，代价是每条命令多两次往返，未做 |
| 「前端按行筛够用」 | 只对**已读回那一段**够用：一次读回上限 512K 字，一份 300 万字的日志里报错在第 280 万字处，前端筛什么都筛不到。故补服务端检索（扫盘上整份、给行号 + 字符偏移）与按行窗口跳读 |

留存侧的现行形态：截断发生在 `SandboxTools.clip()`（进模型 2 万字符）与卡片展示 8K 两处；超限时整份原文落
`data/tool-spill/<uid哈希>/`，读口 `GET /api/ai/tool-output/{id}` 给三种看法（不带参数=从头一段并回 `next`、
`offset`=按字符续读、`line=`=按行窗口并自动留 20 行上文），`/search?q=` 扫盘上整份。
治理参数：每人配额 `tool.spillMaxMbPerUser`(200MB)、保留期 `cleanup.toolSpillRetentionDays`(7 天)、总开关 `tool.spillEnabled`。

##### 二、刻意不做 / 仍然不做的边界（看到了别当新 bug）

- **进模型的那一份一字未动**：`clip()` 仍砍 2 万字符、`maxOutputBytes` 仍按字节挑一段——模型可见形状一变，
  token 预算、历史压缩口径、评估基线跟着漂移（见本文 §风险）。留存只管给人看的那一份。
- **存量轮身份不猜**：`SessionEventService.backfillTurnIds(dryRun, limit)` 只按「一轮时间窗里恰好只对上一个问题」补，
  对不上整窗跳过；账本上线前的消息留 NULL，前端按「本轮无过程记录」处理。能补的量本来就小
  （账本 2026-10-09 才开写、`c_ai_message.turn_id` 列 10-10 上线、账本留 30 天），所以它是 `trace.turnIdBackfill`
  默认关的**一次性补账开关**（打开→重启跑一次→看日志报「补了几行 / 因二义跳过几窗」→关回），不是长期任务。
  `KEY idx_turn_id` 也没补——至今没有「按 turn_id 查消息」的读口。猜错是比缺失更坏的账本。
- **接流设备不代发起设备续发插话**：那几句话是发起设备上的人说的，换一台机器替他发出去是错的语义，保持原样。
- **游客接流的归属用访客身份**（`share-visitor:{客户端随机}`，为此加的 `ChatCommand.guestUid`），不是发布者 uid——
  用发布者 uid 挂归属等于任何拿到链接的人都能接走别人会话的流。访客标识缺失（老链接没带 visitorId）退回**不登记**，
  绝不退成「按发布者放行」。停止必须有服务端入口：`POST /share/{token}/chat/stop` 先按访客认会话归属、
  再按发布者 uid 叫停（本轮执行身份就是他），否则前端 abort 只掐自己那条通道、后台跑满宽限期白烧发布者额度。
- **工作流接进总线后两处刻意与问答不同**：没有宽限期（观众全走也不中止，任务要跑完才叫结果），
  也不受 `chat.resumeEnabled` 影响（那个开关管的是问答「断开即中止」的旧行为回滚，工作流没有这个旧行为可退）。
- **账本的消费面只到「过程面板 + 对照视图」**：事件真源统一、`deriveMessages()` 投影重建请求、
  历史投影第一次回灌模型输入都还没碰——那是单列的 8–12 人日灰度项，动它要过评估集基线。
- **待校核（原 P0 遗留清单 §二.3，档位落地后一直没回头做）**：留存原文的读口现在只认「目录按 uid 哈希 + meta 里的 uid 再校验一次」
  （`ToolSpillService.read()`），而 P2 权限档位落地后「谁能读哪份原文」多了一层归属判据——
  低档轮次留下的原文被高档轮次读回、或反过来，要不要按档收口是**没判过**的事。别默认它已经跟着档位走了。

##### 三、还欠的验证与已知小缺口

- **生产发布要显式跑 `scripts/db/fix-schema-2026-10-12-tool-approval-text.sql`**：`c_ai_tool_approval.request_args/answer`
  原是 `VARCHAR(2000)`，而计划卡写的是整份计划 JSON（多步常态 2–6K 字、写侧根本没截）。严格模式插入报 1406
  被落库处的 try/catch 吞成一行 warn → 卡片没进库 → 刷新后 `/plan-approval/pending` 查不到，那张正在倒计时的卡**就地消失**；
  非严格模式静默截成半截 JSON，一样重建不出卡。`schema.sql` 已改 TEXT，但本机这两列**早就是 TEXT**（`information_schema` 查得），
  所以这坑在本地从来没咬过；`SchemaMigrator` 的边界是「只补列不改列型」，存量库这一步必须留给人跑。
- **未修，记录在案**：`hydratePendingAsk / Plans / Approvals` 给占位气泡写了 `stage` 说明文案，
  但 PC 模板与 `MobileMsgRow` 都不消费消息级 `stage`（grep 只有赋值点），那颗气泡在屏幕上就是一颗转圈的空泡。
  没动它是因为改动面是「气泡头部要不要多一行状态」，属 UI 口径决定而不是归属判据。
- **没实测的分支**：启动自检的**报警**分支（本机 32 个「默认开」开关全都有配置行，只证明了不误报）；
  思考阶段被叫停的那条留痕消息；工作流分支的消息是否真带 turn_id（代码路径同源，但没各自构造出可观测窗口）。
- **配置布尔读取的默认值口径**：`getBoolean(key, def)` 对空值与缺键都落到形参默认；
  而**一参形式 `getBoolean(key)` 对既无配置行、也未在 `ConfigService.defaults()` 登记的键仍返回 false**——没声明就没默认，属预期。
  连带一条：`snapshot()` 只回显 `defaults()` 里存在过的键，所以**默认关的开关也要登记**，
  否则设置页打开后重开显示「关」，用户读成「我刚才没点上」（`trace.turnIdBackfill` 就是照这条补登记的）。

##### 四、这三刀撞出来的四条既有缺陷（前两条最严重，判据在此）

都不是新机制的欠账，是**早就在、静态检查全绿、只有真跑才露出来**的：

1. **审批=「每次问我」这一档自 P1 第一刀起整个是坏的**。`RagService.toolTurnContext(st)` 那个匿名 `ToolTurnContext`
   写的是 `approvalTimeoutMs() { return approvalTimeoutMs(); }`——调的是自己。`ApprovalGate` 一读等待上限就无限递归，
   `StackOverflowError` 打爆工具线程所在的 scheduler worker，症状是审批卡永不出现、整轮停在「正在生成回答…」、
   屏幕上一点错都看不到（日志只有 `throwIfFatal detected a jvm fatal exception` 加一屏同名栈帧）。
   引入提交 `b377ccf`；同文件 `subCallTurnContext` 写的是 `base.approvalTimeoutMs()`，只有主轮这一处漏了限定符。
   以前没发现：P1 的落地记录自己写了「沙盒默认关所以 ask 分支没在真机上跑到」。修法是补 `RagService.this.`，
   并全仓扫同形态自递归（命中 0 条）。
2. **外溢原文的元信息一直没被删过**：`ToolSpillService.purgeExpired()` 删 meta 时拼的是 `x.txt` + `.json`
   = **`x.txt.json`**，而写的时候落的是 `x.json`——保留期清理只删正文、孤儿元信息永久留在盘上，
   且下一轮扫描跳过 `.json` 连计数都进不去；配额淘汰复用同一个删除动作，会继承这个坑。
   改 `metaOf()` 按 basename 换后缀，并在清理任务里把历史孤儿一次结清打日志。
3. **两项留存治理参数"改了看不出改过"**：`tool.spillCleanupIntervalMs` 与 `cleanup.toolSpillRetentionDays`
   在 schema 里有字段却没登记进 `defaults()`，症状是设置页改成 2 小时、重开又显示 3600000。
4. **占位泡 `stage` 两端都没渲染**（未修，见上节）。

##### 五、真机验证账（2026-10-12，本机 8090 + 5800 + provisioner 8002；行为断言，不是代码推断）

验过并通过的：

- **留存 + 续读 + 服务端检索 + 跳读**：问答里跑 `seq 1 300000` → 盘上落 **1,988,895 字节**（改动前最多 262,144 字节）；
  第一段 524,288 字、`more=true next=524288`，按 `next` 拼回去**与盘上字节数一字不差**；`/search?q=299999`
  给出 `line=299999 offset=1988881`。浏览器点开「查看完整输出」自动进按行模式（`共 300,000 行 · 1,988,895 字`、
  200 行一页），搜「299999」命中 1 处，点命中跳到 299979–300000 且**命中行高亮在第 21 行**（留 20 行上文）。
- **人在回路卡片落终态**：探针智能体（档位=常规 / 审批=每次问我）跑 `echo` → 卡挂起 → `POST /chat/stop`
  → `c_ai_tool_approval.status=STOPPED`、`resolved_at` 写上、pending 清零。用户叫停=`STOPPED`、
  无人查看宽限中止=`INTERRUPTED`（审计要分得开），提问卡答案留空——没答就是没答，不代答。
  顺带查清原判断也不准：`complete()` 之后等待处的 `TimeoutException` 分支**不可达**，那几条记录不是一句「留到超时」，
  而是一直 PENDING 到进程重启被 `purgeStaleApprovals` 收掉。
- **游客分享页**：免登录发一轮 → `/chat/run` 回 `running:true + turnId + viewers:1`；换一个 `visitorId` 打同一会话
  → **403「无权访问该会话」**（连「在不在跑」都不告诉别人）；`/chat/resume` 接上的通道 10 秒内拿到快照，
  之后实到 162 条 token 增量、1004 字并收到 `done`；`/chat/stop` 后探测转 `running:false`。
- **工作流运行页**：start→llm→llm→end 异步跑，两个订阅者**同时**收全帧（旧实现后一个顶掉前一个）；
  第 9 秒接上的快照里 `traces=['a']`（旧实现必是空白，因为 run 行的 `node_traces` 只在收口时写）；第 16 秒接上的回放全部 3 节点。
- **进程重启半程正文落库**：正出字 321 字时 `SIGTERM` → 日志「1 轮在跑的半程正文已按中断态落库」，
  库里那一行尾部是**重启专属**那句「服务在这一轮中途重启」（写成「已停止本轮」会让人以为是自己手滑停的），账本另有一笔留痕。
  走 `ContextClosedEvent` 而不是 `@PreDestroy`：同一 bean 上多个 `@PreDestroy` 不保证顺序，而容器关闭事件先于所有 bean 销毁，
  必须抢在 `pipelineExecutor.shutdownNow()` 前读到状态表、抢在 DataSource 关掉前写完。跨进程**续跑**仍不做。
- **存量轮身份回填**：`[回填] 会话 185 个 · 轮窗口 59 个 · 补 68 行 · 因二义跳过 19 个`，库里 NULL 行 519 → 451（差 68 对得上）；
  一条 2026-10-09 的老回答现在「看这一轮的过程」打得开，9 条账本、4 个工具步与正文卡片同编号。开关跑完已关回。
- **对照视图**：按轮回放默认左正文右账本，工具步与账本终态行按同一编号互点互亮；读口为此把
  `eventItem` 对 `type=tool` 的行解出 `name/status/elapsedMs`（原来只给 240 字摘要，可能正好切在 `status` 之前，前端数不出第几步）。
  事件类型标签补齐 `approval_required`/`steer`/`interrupted`，不再露英文原名。
- **触屏一屏端到端**：`playwright-core` + 系统 Edge，390×844 / hasTouch，13 条断言全过——移动壳确实挂载、
  单轮 sheet 有「看这一轮的过程」、面板取到 9 条事件并默认进对照、事件标签全中文、**横向溢出 0px**。
  第一次没验成是因为 browser-use 那个无头视口 0×0，`isMobile/hasTouch` 不成立、移动壳压根没挂载，看到的其实是 PC 模板。

同一轮抓出并已修的两处判据（都是"跑起来才露出来"的）：**落终态必须排在掐流之前**——`disposeSafe()` 会中断正阻塞在
`future.get()` 上的工具线程，它的 `finally` 顺手把挂起项摘掉，之后就是对着空表落库（日志先出「审批未通过」再出
「解开人工等待 命中=0/0/0」，而叫停接口照样返回成功）；**按行跳读的上文行数会顶穿小窗口**——原先固定留 20 行上文，
`lines < 41` 时窗口整体落在命中行之前（`line=299999&lines=6` 返回 299979–299984，里面没有 299999），
改 `before = min(20, (take-1)/2)`，小窗口断言补进探针（54 → 57 条）。

### ★★★ P1 · seam 化第一刀（不是"运行期替换"）

借的机制：96 个 `ctx.*` seam 的 Definition / Provider / Consumer 三角色。

三件事：**`ToolDescriptor` 单一真源**（name/label/description/schema/sideEffect 级）+ **`ToolRegistry` 与 `pre/execute/post/guard` 三段链**（per-invocation 上下文对象替掉 set/clear 单例）+ **`chat()` 收成 `ChatCommand`**。

它的真实收益不是架构，而是修掉一个并发缺陷和四份复制——这条决定它值 25–35 人日，也决定它不是"为架构而架构"：

- **真并发缺陷**：`KnowledgeRetrievalTool.setSourceRegistrar/setKbScope` 与 `WebSearchTools.setSink`（`RagService:2068-2089`）是**全局单例上的可变字段，按轮 set、finally clear**，靠时间窗而非作用域保证正确性（注释自陈"否则跨会话串号"）。
- **4 份并存的工具执行路径**：`instrumentTools`（唯一带守卫）、`WorkflowEngine.mcpBody:1199-1209` 直接 `target.call(json)` **绕过审批/步数/审计**（`skillBody:1161` 同类）、`McpServerService:317/471` 直调 `ragService.chat`、`enabledToolCallbacks:1789-1807` 游客分支整份复制。`instrumentTools` 调用点只有 2 处（`:2835`、`:6132`），抽出来不扩散。
- **3 份人肉同步的工具中文名副本**：`ToolInventoryService.LABELS:41` ↔ `projections.js TOOL_LABELS` ↔ `AgentsPage BUILTIN_TOOL_OPTIONS`。
- 最高风险：身份快照（`:1937` 入口线程捕获）迁移 → 用一条跨会话并发回归用例守。

> **P1 落地状态（2026-10-10，第一刀）**：seam 骨架已落地并真机验证 —— 新包 `com.wenqu.ai.agent.tool`：
> `ToolDescriptor`（name/family/label/cardLabel/cardHint/sideEffect/gateKey/guestAllowed/requiresApproval）
> + 七个 `ToolFamilyProvider` + `ToolRegistry`（启动期把声明名与 `@Tool` 反射名逐族核对，不一致记 ERROR）
> + 单调 `ToolGuard` 链（`UserStoppedGuard` → `StepBudgetGuard`（放行即占一步）→ `ApprovalGate`）
> + `ToolInvocation`（本次调用独占，含失败信箱）+ `ToolTurnContext`（本轮契约）+ `ToolPipelineFactory`。
> **串号缺陷已修**：`KnowledgeRetrievalTool` 的 REGISTRAR/KB_SCOPE/REFLECTIVE 与 `WebSearchTools.SINK`
> 三对 ThreadLocal 全部删除，改走 `ToolContext` 按次注入（沿用 `SandboxTools` 的 `CTX_*` 既有范式）；
> 对外 MCP server 的 `wenqu_search_knowledge` 直连路径改为**显式传 `KbScope`**（原来是 setKbScope+finally clear，
> 免登录端点漏清一次就是把上一个人的库界留给下一个人）。
> 真机判据（本机 8090，新构建）：启动 `[TOOL-REGISTRY] 已登记 22 个工具，7 个族` 且零 ERROR；
> 两个会话并发问答、调用落在**同一条池化线程**（boundedElastic-13）上先后执行，各自注册进自己的引用序列
> （A 轮【引用7】、B 轮【引用3】，B 的引用面板确实长出了那一条）—— 迁移前那种漏清就会表现为 A 序列续号 + B 面板空；
> 工具卡显示「整理任务清单」，而前端 `TOOL_LABELS` 里根本没有 `writeTodo` 这一行（证明后端名真的下发了）。
> **本刀没做**：`chat()` 十个重载收成 `ChatCommand`（P3a 的前置）；检索/联网/技能/沙盒/MCP 五族的**挂载**仍走
> `enabledToolCallbacks` 的 if 链（只有「审批属性 + 中文名 + 守卫链」改由注册中心供）；工作流 `mcpBody`/`skillBody`
> 两条裸调旁路仍无守卫（`bypassGuard` 标记也未加）；审批闸只做了结构迁移，**沙盒默认关**所以 ask 分支没在真机上跑到；
> 前端 `AgentsPage.BUILTIN_TOOL_OPTIONS` 那份手抄仍在（它要登录用户都读，而 `/api/ai/tools` 按既定口径不开普通用户洞）；
> MCP 动态工具名没有中文来源（前端剥前缀兜底），要等 P4 的 resources/命名一起收。

> **P1 第二刀（2026-10-10，同日）**：**检索 + 联网两族的挂载**迁到注册中心——`ToolMountContext`
> （userId / 游客 / reflective / 全局开关 / `agentFlag(family)` 三态）+ `ToolFamilyProvider.callbacks(mc)`
> （默认返回空 = 本族还没迁，这就是绞杀态的开关）+ `mountFiltered` 样板。收益：
> `guestAllowed` 这个字段终于有了消费方（联网族「不给游客」不再是「游客分支提前 return 掉了」的副作用），
> 知识检索与内置工具在游客/常规两条分支的整份复制消掉，收尾（审批名单 + DSML 白名单 + 挂载日志）提成
> `finishMounts` 两条路径共用——顺带暴露并修掉一个老差异：**游客会话原先连 DSML 剥离白名单都没拿到**
> （复制分支时漏带的，不是设计）。`族 → c_ai_agent 列` 的映射留在问答侧，通用层不认识业务实体。
> 收尾里的同名工具去重 + ERROR 当场抓到东西：第一版把 `webSearch` 的 provider 分支和残留老分支各挂了一次
> （症状是模型工具表里两个同名项，Spring 不报错、各厂商行为不一，从回答里几乎看不出成因）。
> 真机补验（本机 8090）：27 个工具、无重复；`webSearch.enabled` 在本机实际是开的，于是第一刀没跑到的那条
> per-invocation 注入路径有了证据——联网工具挂载一次、执行经新管线，provider 报错时经 `sink.degrade`
> 把「联网搜索不可用」写进运行状态面板（degrade 只在 sink 非空时才调，这条降级出现即证明上下文注入到位）。
> **仍未真机验**：游客分享路径（要一个 share token），那条改动是代码路径推演；审批闸的 ask 分支仍未跑到。
> 下一刀：内置/产物/技能/清单四族挂载；沙盒与 MCP 两族最后迁（MCP 要把降级提示与 `st.mcpToolNames` 一起带进 provider）。

> **P1 第三刀（2026-10-10，同日）**：**内置 / 产物 / 技能 / 任务清单四族挂载**迁完，装配处的 if 链只剩
> 沙盒与 MCP 两段。这一刀多出来的不是"又搬了四段代码"，而是把**判据的所有权**收归 provider：
> `ToolFamilyProvider.exposes(descriptor, mc)` 成为唯一实现，问答侧提示词的「人机协同三入口」段与
> 「已批准计划落成清单」那句提示改为查 `ToolRegistry.exposes / anyExposed`——原先那两处各揣着一份
> `builtinToolExposed` / `todoToolExposed` 私有判据，与装配那份一共三份拷贝，改一处就出现
> 「提示词教模型用某个工具、工具表里却没有它」。`ToolMountContext` 补两样：`masterOn()`（总闸折进
> `gateOnFor`——装配处原本有一条「总闸关→一个都不挂」的提前返回，但提示词侧是直接问「这工具挂不挂」的，
> 总闸不在这条判据里就会有关着闸还在教模型用 askUser 那种事）与 `agentSelection(family)`（智能体
> 「具体项」白名单；与三态是两道独立闸：三态管这一族开不开，白名单管挂哪几个）。
> 技能族必须**按本轮用户现造工具实例**：`holder()` 那个是启动核对用的占位 uid，拿它执行会把
> 「谁的技能」读成「占位账号的技能」，属跨账户读取。装配循环改用 `providersInFamilyOrder()`——
> Spring 的 bean 列表顺序跟类扫描走、不保证稳定，而「本次启用 N 个工具」这条日志是排障时逐字对着看的。
> 顺带删掉 `RagService` 里因此变成死注入的 `webSearchTools` / `presentArtifactTool` 两个字段。
> 真机复测（本机 8090）：挂载集合与迁移前逐字相同（27 个工具）、去重守卫零触发、启动零 ERROR；
> 一轮里内置(hash) + 清单(writeTodo×2) + 知识检索(searchKnowledge) 三族跨 boundedElastic-5/6/7/8
> 四条池化线程并发执行，引用仍正常注册（【引用1】）。
> 仍未迁：沙盒、MCP（MCP 要把降级提示与 mcpToolNames 一起带进 provider，形状和前几族不一样）。
> 仍未做：ChatCommand、工作流 mcpBody/skillBody 两条裸调旁路、游客 share 路径的真机验证、
> 技能目录注入那侧的 `skillOn(agent)` 与迁移后的挂载判据仍有一条差异（它缺全局档，只看智能体三态、
> 否则恒注入——总闸关掉时会出现「目录注入了、readSkill 没挂」，要不要合掉是个产品口径决定，没擅动）。

> **P1 第四刀（2026-10-10，同日）**：**沙盒与 MCP 两族挂载**迁完——`enabledToolCallbacks` 里
> **一族的 if 都不剩了**，八族全部由 provider 装配。两处形状与前几族不同，照抄会错：
> ① 审批=off 的整族禁用是**族级**事实，落在 `ToolFamily.blockedWhenApprovalOff`（沙盒/MCP 为真，
>    **联网搜索刻意不是**——它启停由自身开关决定，纳入 off 档会把整个联网关掉，与设置页和手册的
>    承诺不一致）；判据合进 `ToolFamilyProvider.exposes` 的默认实现，装配处那两处
>    `!approvalOff &&` 从此没有对应代码。
> ② MCP 没有静态清单（工具由用户登记的 server 动态合入），所以 `descriptors()` 为空、
>    `holder()` 为 null；它的审批属性靠 `ToolRegistry.requiresApproval` 对「未登记工具名」一律 true
>    来 fail-closed 兜住，而不是某个描述符上的字段。「本轮哪些工具来自 MCP」这个状态
>    （`st.mcpToolNames`，MCP 结果注册引用来源要用）**由装配循环按族收集**，不让 provider 反向写
>    问答侧的状态对象；个人资产引用的归属解析从 `RagService` 私有静态方法升为
>    `ResourceRefs`（技能与 MCP 同构，provider 侧要用就不能留在问答侧）。
> 真机复测（本机 8090）：27 个工具与迁移前逐字相同、去重守卫零触发、启动与运行期零 ERROR；
> MCP 两个 server 连上后动态合入 5 个工具（context7 2 + deepwiki 3）并真的执行
> （`w_q_resolve_library_id`、`w_q_query_docs` 各拿到真实文档内容）；沙盒 `execute` 跑通
> `echo seam-probe-sandbox`，输出增量与基础设施故障信箱仍是按次注入。
> **一条只有间接证据**：「MCP 结果注册引用来源」——答案里出现 [1]–[4] 四个编号，但我没能打开
> 那一轮的引用面板确认其中哪几条来自 MCP（面板入口在接流后的会话头部会消失），
> 而 `registerMcpCitations` 的载荷尾部被 160 字节日志截断看不到。配置 `tool.mcpCiteEnabled` 默认开、
> 代码路径除「名单改为按族收集」外未变，判定为未受影响，但下次要拿直接证据。
> **（直接证据已补，同日）**：那一轮面板显示「参考 4 段资料」，其中 [3][4] 的内容是 Spring AI 的
> ToolCallingAdvisor 循环与 returnDirect 语义、并带 docs.spring 链接——本轮只有 MCP 结果里有这些，
> 即外部工具结果确实注册进了引用体系。
> **P1 剩余（截至第四刀）**：`ChatCommand`、工作流两条裸调旁路、游客 share 路径真机验证、
> `AgentsPage` 那份中文名手抄、`skillOn` 与挂载判据那条差异的口径决定。

> **P1 第五刀（2026-10-10，同日）**：**`chat()` 的十个 telescoping 重载收成 `ChatCommand`**——
> P1 的三件事（描述符真源 / 注册中心与守卫链 / 命令对象）到此齐。`com.wenqu.ai.agent.chat.ChatCommand`
> 18 个字段 + `@Builder`；`chat(ChatCommand, SseEmitter)` 与 `runChat(ChatCommand, SseEmitter, deepThink)`
> 各留一段同名局部变量解包——流水线体量大（互斥登记、总线开轮、状态构造、异步提交），
> 逐处改成 `cmd.getX()` 的收益是零、风险是把一个写错位置的东西埋进八千行里。两个设计点：
> ① **`SseEmitter` 刻意不进命令对象**：它是这一轮的*出口*不是内容（工作流子 agent 用
>    CollectingSseEmitter，定时任务与对外 MCP 端点各有自己的收集通道），混进同一个参数列表
>    就会出现第 11 个重载；② **不做默认值归一化**：今天有的调用方传 null、有的传空列表，
>    而 null（未提供）与空集合（明确不提供）在下游语义不同，构造时统一会把这个区别抹掉。
> 六个外部调用点全改为具名 builder（ChatController / ShareController / WorkflowEngine.subagentBody /
> McpServerService 两处 / ScheduledJobService），后台三处原本传 `List.of()` 的逐项写回、不靠推断。
> 这组参数里最容易出事的是 `regenerate + replaceMessageId + editVariantGroup`（相邻同类型实参，
> 老签名写反编译器不拦），现在同名字段一一对应，读一遍即可验完。
> 真机：主链路问答正常（工具挂载、searchKnowledge 带范围与身份、引用注册【引用7】、引用自检、
> 零新增 ERROR）。
> **一处判断更正（做「旁路」那项时读到代码才看清）**：工作流的 `mcpBody`/`skillBody` **不是漏了审批**——
> 画布有独立的 approval 节点（挂起-快照-短路重放）和自己的 `node_traces` 落库（args/result 全量）。
> 把「按工具逐个问人」的聊天审批套上去，会让每个 MCP 节点等一张没人点的卡，等于打断在跑的画布。
> 所以这一项只做**旁路可见**：两条节点 trace 里写 `guardBypass=chat-guard-chain`，注释里说清为什么不接、
> 以及 P2 的权限档位要作用到画布时接入口在哪（这正是原文档「此期保持无守卫，只留 bypassGuard 标记」
> 那条判断的依据，我当时把它写成了「安全洞」）。
> **P1 收尾剩余**：游客 share 路径真机验证、`AgentsPage` 那份中文名手抄（要一次端点口径决定）、
> `skillOn` 与挂载判据那条差异的口径决定。`ChatCommand` 到位后，P3a（子智能体真全循环）才有接口：
> 分支继承父轮上下文、depth 硬顶与逐层预算递减都走命令对象加字段。

> **游客（免登录分享）路径已真机验（同日，P1 最后一个证据洞关闭）**：用一条已开启的智能体公开分享，
> 以两个不同 visitorId 对 `POST /ai/api/ai/share/{token}/chat` 并发打两轮，各自新建访客会话
> （session 80f8… / f243…）。三条判据都拿到直接证据：
> ① 服务端日志 `本次启用 12 个工具`——11 个内置 + `searchKnowledge`，沙盒/MCP/技能/产物/清单/联网
>    一个都没挂。这条收窄从前靠「游客分支提前 return」实现，现在由各族描述符的 `guestAllowed`
>    与 MCP provider 自查得出，结果逐字相同；
> ② 引用不串号：A 轮主链路 4 条来源、工具命中落在 [5][6]；B 轮主链路 6 条、工具命中落在 [7][8]——
>    每轮的编号基数等于**它自己的**来源数，且 A 的来源标题全是上传/解析、B 的全是默认库/知识库。
>    若还是老的 ThreadLocal 落点，两轮都跑在池化线程上，漏清一次就会让 B 的命中注册进 A 的列表；
> ③ 工具卡载荷带 `"label":"查知识库资料"`（后端注册中心下发），免登录页同样不再依赖前端那份手抄映射。

> **P1 收尾（同日）：第三份工具中文名手抄消灭**。新增登录用户可读的
> `GET /api/ai/agent/tool-catalog`（`ToolInventoryService.agentCatalog()` 出 family/name/label/cardLabel），
> 前端 `AgentsPage.BUILTIN_TOOL_OPTIONS` 整张表删掉、改为拉名册。**没有新开访问控制规则**：路径落在
> `SecurityConfig.isAgentSelfEndpoint` 已有的「智能体自助端点」分支（`/api/ai/agent/<一段路径>`）里，
> 管理员全景清单 `/api/ai/tools` 仍按既定口径不开普通用户洞；名册也刻意**不出**全局开关键与
> enabled 状态（普通用户不需要知道平台开关长什么样，少一个字段少一处可被猜的面）。
> 拉不到时不再退回旧常量（退回会让人以为平台就这些工具），而是显式「工具名册加载失败 + 重试」。
> 真机：`/agent/tool-catalog` 登录态 200、未登录 401；配置页把「内置高频工具」切到「指定」后
> 既没出空态文案也没出失败条 → 可选项确实来自后端。
> ⚠️ 一条随之而来的行为变化：名册按族过滤，所以**提问卡 `askUser` 现在出现在「指定内置工具」里**
> （旧那张手抄表是 10 项、没有它）。要维持「不可选」就在前端加一行族内排除。
> 顺带记一笔既有形状问题：`isAgentSelfEndpoint` 的正则 `/api/ai/agent/[^/]+` 不区分方法，
> 意味着普通用户对 `/api/ai/agent/<任意单段>` 的写方法也在放行表内（本次未动它，但值得单独收口）。
> **P1 至此只剩一个口径决定**：`skillOn(agent)`（技能目录注入）与迁移后的挂载判据那条差异要不要合。

> **P1 收尾完成（同日）：技能目录注入与挂载判据合源**。`skillOn(agent)` 删除，注入条件改为
> `toolRegistry.anyExposed(SKILL, mountContext(...))`——判据只有 `SkillToolProvider.exposes` 一处。
> 旧写法是「智能体没显式指定就恒注入」，注释自己写了要与 `readSkill` 的开关保持一致但代码没做到，
> 于是两种错配真实存在：① `tool.enabled` 关着时提示词里列着技能清单、模型却没有取正文的工具；
> ② 免登录访客也拿到清单，而访客会话压根不挂 `readSkill`。
> 真机 A/B（同一条构建）：登录态一轮报出「【可用技能】共 9 个」并含管理员私人技能名（会议纪要等），
> 访客那轮明确说「本轮系统提示词里没有独立的技能清单章节」、只列到随检索注入的内置技能背景名——
> 访客的清单错配没了，登录态的注入没有回归。（判据依赖模型自述，不是面板读数；但两条路径在同一条
> 构建上对比，差异正好落在被改的这一个分支。）
> **P1 状态：三件事齐（描述符真源 / 注册中心与守卫链 / ChatCommand）、七族+MCP 挂载全迁、
> 串号缺陷已修并有直接证据。**剩余都不是机制活：`askUser` 进了「指定内置工具」可选项要不要排除、
> `isAgentSelfEndpoint` 正则不区分方法要不要收口、以及下一步 P2 档位与 P3a 子智能体全循环。


### ★★ P2 · 权限档位与安全

借的机制：`SandboxExecutionPolicy` × `ApprovalPolicy` × `ctx.permissionPresets` + 单调 `ToolGuard`。

现状：审批只有 `agent.toolApprovalMode=auto/ask/off`（`RagService:1968-2033`，超时 120s→按拒绝，落 `c_ai_tool_approval`）；沙盒单一"可写 `virtualPathPrefix`"、**无 per-call 模式**；守卫逻辑内联在 `instrumentTools` 匿名类（外部策略插不进来）；**工具/skill 级无授权表**。

DSH 侧的机制值得全盘吸收：沙盒 3 档 `read-only|workspace-write|danger-full-access`（`SandboxEnforcement=full|partial`，受限策略下静默透传永远不合法、`SANDBOX_UNAVAILABLE` fail-closed，policy **per-call 携带**）× 审批 `ask|never`（`ApprovalOutcome=allowed-once|rejected|cancelled|unavailable`，无应答者即 unavailable→deny，全量审计）；两 knob 捆成具名预设；**单调 guard**（返回 reason 即 deny、无 allow 结果，监听器顺序无法翻案）；`tools.restrict{allow,deny}` 让被禁工具"提示词不可见 + 拒绝执行"与不存在不可区分。

自研接法：preset 归属沿用仓库既有**配置四档**口径（平台全局 / 个人覆盖 / 个人专属 / 资源级绑定），落 Agent 配置 + 个人覆盖。⚠️ 必须与 `RoleService`（菜单+API 双绑定、fail-closed）、`ResourceVisibilityService` 对齐，最多补一张最小 `c_ai_agent_tool_grant`，**不另造授权体系**。

可感知：选档是一句话（只读调研 / 常规 / 全自主）；管理员收紧后模型连"有这个能力"都看不到，而不是点了被拒。

> **P2 第一刀落地（2026-10-10）：档位模型 + 装配收窄 + 单调守卫 + 写入收束 + 落库与配置页。**
> 新包 `com.wenqu.ai.agent.permission`：`SandboxMode`（read-only / workspace-write / danger-full-access）、
> `PermissionPreset`（research / standard / autonomous，各带一个审批**建议值**）、`SandboxEnforcement`
> （full / partial，把「这一档拦不住什么」写成字段而不是注释）、`ExecutionPolicy`（装配/守卫/工具三侧共用的判据）、
> `PermissionPolicyResolver`（平台默认档 + 管理员上限钳制 + 游客强制只读）。接缝沿用 P1：
> `ToolMountContext.permissionPolicy/policyAllows`、`ToolTurnContext.permissionPolicy`、
> `SandboxToolProvider.exposes` 追加档位判据、新守卫 `PermissionGuard`（@Order(25)，步数闸之后审批闸之前）。
> **三件形状上的判断，与"照抄 DSH"不同，都要记下来：**
> ① **read-only 档必须连 `execute` 一起摘掉**。DSH 的 read-only 是"能跑命令但文件系统只读"，
> 而 provisioner 的 create 入参只有 sandbox_id/thread_id/uid/env/workdir——**没有任何档位入口**，
> 容器内也没有 per-call 收束命令的机制。"能跑命令"就等于"能写文件"，所以这一档的可兑现形态只有
> "能力面收窄"（不挂）；留着它再逐条拒就是静默透传。
> ② **审批旋钮不新建词表**。`ask|never` 两档在问渠已有载体 `c_ai_agent.tool_approval_mode`（auto/ask/off 三态，
> 落库/界面/手册/审批闸都在它上面）。preset 只声明**建议值**，智能体显式设过就以它为准——
> 否则同一件事出现两份真源，表现是"改了确认档位没反应"或"档位改了审批行为跟着变"。
> `standard` 档的建议值刻意是 null（不动它），这条是"默认档=当前行为"承诺的落点。
> ③ **partial 只出现在一个组合上**：档位允许 execute 又要求路径收束（= workspace-write）。
> 文件类工具（write/edit/deliver）的 path 由平台校验、越界即拒，这是真拦得住；命令内部的写入拦不住，
> 所以 `enforcement=partial` 必须显式落到本轮日志（`[PERM] 本轮权限档位: …`）。
> **一刀里我最初判错的一件事（已改）**：我原以为 `write_file` 的"只能写工作区"此前只是描述、没人判——
> 真机第一轮 autonomous 对账当场打脸：写 `/home/gem/p2-outside.txt` 在**未接档位**时也被拒
> （`permission denied for write`）。追下去发现沙盒层**早就有**这道边界：
> `ProvisionerSandboxBackend.writableRoots()` 恒等于 `[userDataRoot]`，write/edit 都先过 `canWritePath`。
> 所以平台侧那份收束**不是新拦截**，而是把既有边界提前到调用容器之前（省一次容器往返 + 文案可指导模型自纠）；
> `deliver_artifact` 原本手写的那份前缀比较由同一份判据代劳，消掉的是第二份拷贝。
> 由此带出真问题：**autonomous 档若只改平台预检，"全自主"就是空标签**——边界在沙盒层，档位必须能改它。
> 已接：`SandboxService.backend(sessionId, uid, unrestrictedWrites)` → backend 的 `writableRoots()`，
> `SandboxTools.backend()` 从本轮档位取值（danger-full-access → 放开到容器根）。
> 复测同一问题：全自主档下 `/home/gem/p2-outside.txt` 写入成功、常规档仍拒——三档有了真实差异。
> **真机对账（五轮，同一智能体同一问句「execute + 工作区外写 + 工作区内写」）**：
> standard → 27 工具（与迁移前逐字相同），越界拒/区内成功；research → **23 工具**，消失的四个正是
> `deliver_artifact/write_file/edit_file/execute`（模型自述"本轮没有该工具"，全程零工具调用）；
> autonomous → 27 工具，越界**成功**；把平台上限收到 standard 后同一轮被钳回
> （`[PERM] 档位被平台上限钳制: 请求=autonomous 上限=standard 生效=standard`，行为与 standard 逐字一致）。
> 端点 `GET /api/ai/agent/permission-presets` 真机出参齐（含 available/unavailableReason），
> 配置页档位下拉渲染正常，`SchemaMigrator` 自动补列成功。真类层的 26 条行为断言
> （`/tmp/wq-perm-harness`，覆盖显式 ask/off 不被档位改写、可写根为空不放开、档位码认不出返回 null 等）
> 与真机五轮互为补充。
> **顺手撞出一个既有缺陷（本次未修，建议单独收口）**：`AgentService.maintainBuiltinAgent` 是
> `@PostConstruct`，在 SchemaMigrator（ApplicationRunner @Order(0)）**之前**查 `c_ai_agent`——
> 存量库首次带新列重启必报一次 `Unknown column 'permission_preset'`（被 catch 成 WARN，内置维护跳过一轮，
> 下次重启自愈）。SchemaMigrator 的 javadoc 只防了 runner-vs-runner 这个坑，PostConstruct 消费方是另一类。
> **钳制提示的可见性（产品口径待拍）**：`addDegradation` 只在 `chat.retrievalDebugEnabled` 时入列表，
> 所以上限收紧目前只有后端日志 + 配置页置灰两处可见。
> **P2 剩余**：个人覆盖档（配置四档的"个人覆盖"那层，现在只有平台全局 + 资源级绑定）、
> `tools.restrict{allow,deny}` 那套**逐工具**黑白名单（现在只有族级/档位级裁剪，没有用户可编辑的 restrict）、
> 最小 `c_ai_agent_tool_grant`（按需，未建）、`SANDBOX_UNAVAILABLE` 在 provisioner 不可达时的分类文案（现走
> 既有 infra error 信箱，未按"档位要求的能力拿不到"单独定性）。

> **P2 第二刀落地（2026-10-10，提交待做）：个人上限档=配置四档的「个人覆盖」层。**
> 新键 `agent.permissionPreset.personalMax`（schema `personal:true` → 存 `c_ai_user_config`、
> 个人设置页 schema 驱动渲染、保存走 `UserConfigService.save` 的 isPersonal 白名单 + select 值校验，
> 前端零改动）。生效上限 = **min(平台上限, 个人上限)**，钳制说明按"谁收的就说谁"，
> 名册端点 `catalog(uid)` 在请求线程显式 `personalValue(uid, key)` 合并（那里没有 putUserOverrides，
> 不合并会出现"界面上说可选、真跑起来被个人上限钳住"）。
> **两个方向性的取舍，别在下次改动里翻掉**：
> ① 个人值**只能往下收**。若让 personal 值替换全局 max，成员就能靠它把管理员的收紧顶开——
> 那会把「个人覆盖」变成提权通道；所以是取低者而不是覆盖。
> ② 个人值认不出时=**不额外收紧**（与平台 max 认不出时"按默认档收口"刻意相反）。平台上限是管理员替
> 全员决定的收紧，读不出意图时保守收；个人上限是成员自愿的约束，读不出意图时替他选一个更紧的档
> 等于让这个键变成"写错就锁死自己所有智能体"的陷阱。
> 用途口径：成员自我约束——"别在我账号下的任何智能体里跑命令/改文件"，**包括别人建的、
> 它自己显式选了全自主的那些**（第一刀时个人层缺位，这类诉求只能靠管理员全局收，粒度太粗）。
> **已验**：`/tmp/wq-p2-h2` 用真解析器 + 假配置源跑 12 条行为断言全过（未设个人值=第一刀行为逐字不变、
> 个人收到只读时全自主智能体被钳回且工具面真的少四件、个人宽于平台时不放开、同档按平台口径表述、
> 未选档智能体同样受个人上限保护、认不出不收紧、收紧后显式审批仍以它为准、游客恒只读）；
> `node scripts/codegen/gen_config_schema.js` 无新漂移、后端编译 EXIT=0。
> **真机也验了（起前后端各跑一轮）**：`/user/settings` 把新字段按 select 下发（含三个 options 与全局值），
> 个人上限设成 research 后，**显式选了 autonomous 的智能体**被钳回只读——
> `[PERM] 档位被你的个人上限钳制: 请求=autonomous 平台上限=autonomous 个人上限=research 生效=research`，
> 工具表 27→23（execute 与三件写类消失）、本轮零工具调用；名册端点同步置灰并说清是谁收的、去哪儿改
> （`你的个人上限为「只读调研」，本档不可选（在「个人设置 → 回答偏好」里改）`）。
> 探针设置已全部还原（个人覆盖清空=删行回落全局、智能体档位归 null、探针会话删除），复核名册恢复全可选。
> **P2 剩余**：逐工具 `tools.restrict{allow,deny}` 名单、最小 `c_ai_agent_tool_grant`（按需）、
> provisioner 不可达时按"档位要求的能力拿不到"单独定性文案。

> **P2 第三刀落地（2026-10-11）：逐工具黑名单 `c_ai_agent.tool_deny`（DSH 的 `tools.restrict.deny`）。**
> 只做了 deny、**刻意没做 allow**：allow 语义仓库里已有两份（族级三态 + 内置/技能/MCP 的具体项白名单），
> 再加第三份就是"同一个意思三个地方各写一遍"。deny 补的是它们都表达不了的那一种——
> **"其余照旧，只把点名那几个拿掉"**：用白名单做同一件事要手抄一份全量名单，平台新增工具时那份不会自动跟上。
> 它同时是 **MCP 动态工具唯一的粒度**（那族没有静态描述符，档位裁剪不到，只能按名剔）。
> **判据落点这一刀踩了一次坑，记下来**：先把它加在 `SandboxToolProvider.exposes` 上，真机结果是
> 只有 MCP 那个名字生效、`webSearch`/`askUser` 照挂——因为**别的族走的是接口默认 `exposes`**，
> 沙盒族的 override 管不到它们。而判据也不能简单塞进默认 `exposes`：检索族那族的重写是
> `mc.reflectiveRetrieval() || super.exposes(...)`，一条 `||` 就把默认实现整个绕过（反思轮次会无视禁令）。
> 最终落在两个必须同源的汇聚点：**装配样板 `ToolFamilyProvider.mountFiltered`**（所有族的回调都经它）
> 与 **`ToolRegistry.exposes/anyExposed`**（提示词侧判据），两处都调同一个
> {@code ToolMountContext.policyAllows} → {@code ExecutionPolicy.allowsTool}。
> 保存侧在 {@code AgentService.toEntity} 逐个核对名字存在（认不出直接拒），但**放行 MCP 前缀的名字**——
> 外部工具名由 server 自定、本来不在注册中心，那族的语义就是"按你写的名字逐字匹配"。
> **已验（真机）**：存 `webSearch,askUser,w_q_query_docs` → 装配日志 `禁用=[...]` 且工具表 27→24，
> 三个名字全消失、其余四个 `w_q_*` 保留；模型自述与工具表逐字一致（"这三个都没有"）；
> 存不存在的名字被拒并点名（`工具黑名单里有平台不认识的名字：nosuchtool-x`）；
> 清空回落 null、探针会话删除、前端 check 零失败。
> **P2 剩余**：最小 `c_ai_agent_tool_grant`（按需）、provisioner 不可达时按"档位要的能力拿不到"单独定性文案、
> 黑名单的**平台级**版本（现在只有智能体级）。

> **P2 收尾（2026-10-11）：补上平台级黑名单，另两件判定为"不做"。**
>
> **做了的那件**：`tool.deny`（逗号分隔工具名，平台全局）。它填的是粒度空档——族级开关
> （`tool.websearch.enabled`）关的是整族，想停一个工具会连带关掉同族其余能力；智能体级黑名单又要求
> 每个智能体都改一遍。真机：平台停 `hash` + 智能体停 `webSearch` → `禁用=[webSearch, hash]`（并集）、
> 工具表 27→25；名册 `agentCatalog` 加 `platformDenied` 标记，配置页把"平台已停用"写在选项上
> （不禁选——管理员仍可能想在自己智能体上再记一笔，但全局已停这件事必须看得见）。
> 设置页那一项是自由文本，写错只会静默不生效，所以启动期核对名字存在并打 ERROR 点名（只喊不拦：
> 一个笔误不该让整个平台起不来）。刻意**不做成个人可覆盖**：能覆盖自己的黑名单=能绕过管理员的停用。
>
> **不做的那两件，理由要写清楚，免得下次又当成漏项补回来**：
> ① **`c_ai_agent_tool_grant` 不建**。它要解决的问题是"谁能用高能力工具"，而这件事在本仓库已有两层
> 覆盖且各在其位：**能不能改配置**——`RoleService`（菜单+接口双绑定、fail-closed）已经能管住
> `PUT /api/ai/agent/{id}` 与设置页那三项，普通成员能不能给智能体选全自主，是接口权限判定的一部分；
> **这一轮允许干什么**——档位（智能体级）× 平台上限 × 个人上限三层钳制，已在第一、二刀落地。
> 再建一张按人按工具的授权表，等于给同一件事造第四份真源，而且它必然与 `ResourceVisibilityService`
> 的"谁建归谁"打架（技能/MCP 本来就是个人资产，工具授权若按人发牌，会出现"看得到自己的 MCP 却无权用"）。
> 依赖清单：`RoleService` / `ResourceVisibilityService` / `agent.permissionPreset.max` /
> `c_ai_agent.permission_preset`。取消后果：无——四件加起来已覆盖"谁能改"与"改了拦不拦得住"。
> ② **`SANDBOX_UNAVAILABLE` 定性文案不加**。DSH 需要它，是因为沙盒与工具注册表同进程、沙盒起不来时
> 会**静默降级成无沙盒执行**；问渠的沙盒是独立 provisioner 服务，容器起不来时命令根本不会跑
> （`backend()` 拿不到就返回失败文本 + 经 infra error 信箱把那条记录落成红色 error，P0 那刀已做）。
> 也就是"没有静默透传可防"，再补一个错误码只是给同一种故障起第二个名字。
> 真要补的话缺口在别处：没有输出回调的那条路（子智能体分支、工作流代码节点、沙盒浏览面板跑脚本）走的是同步
> `/v1/shell/exec`，拿不到整份输出且没有任何被砍标记——见本文 §三 P0「真实边界与验证账」第一节那张订正表。
>
> **P2 到此收口**：档位三档 × 审批、平台上限、个人上限、逐工具黑名单（智能体级 + 平台级）、
> 游客强制只读、守卫单调否决、沙盒写入边界按档放开/收束。


### ★★ P3a · 子智能体真全循环

借的机制：`ctx.subagents` 的 `spawn-in-process` / `fork-in-process`（继承父历史）。

现状：`SubAgentOrchestrator.runAgent:607` 分支体只做"检索 + 要点提炼"，`digest:704` 显式 `internalToolExecutionEnabled(false)` —— 不跑工具循环、不继承父上下文、只传原问题；并行硬编码 4（`RagService:4731`）。

**通路已在生产**：`WorkflowEngine.subagentBody:1044-1086` = 建临时会话 → `CollectingSseEmitter` → 完整 `ragService.chat(...)` → 跑完删会话。把 `runAgent` 换接这条路即得完整循环，所以它是四条线里唯一"只差接线"的。

硬约束（决定真实工作量）：`chat()` 的 `TURN_IN_FLIGHT` 是**会话级互斥**（`:661`）→ 子 agent 不能借父会话；`chat()` 已有 **10 个 telescoping 重载**（`:524-648`）→ 加第 11 个是错的，必须先对象化（P1 的活）；嵌套**全文无 depth 记录**（只有 `UsageAttr.hold(..., "subagent")` 归属标签）→ 要新增 depth 硬顶（建议 2）+ 每层预算递减。`digest` 降级为"轻量分支模式"开关，不全删（多视角检索场景它更省）。**必须强制继承长期记忆与 `[N]` 来源引用**，否则护城河在主链路有、分支上没有。

> **P3a 第一刀已落地（2026-10-11，真机验完，未提交）**。新包 `com.wenqu.ai.agent.subagent`
> （`BranchMode` digest|full、`BranchRequest`、`BranchReport`、`BranchRunner` 函数式接缝）；
> `ChatCommand` 加 `subagentDepth/parentBrief/parentSessionId`；档位落点是**被咨询那一方**
> （`c_ai_agent.subagent_mode`，NULL=跟随平台默认 `agent.subagentMode`，默认 digest=行为逐字不变），
> 并行度/深度/单支超时/父轮摘要预算四项配置化（`agent.maxParallelSubAgents|subagentMaxDepth|subagentTimeoutMs|subagentBriefChars`）。
> 五条与"照抄 DSH"不同的判断，改这块前先读：
> ① **执行器按轮回传而不是 bean**——RagService→编排器→执行器→RagService 会成三方循环依赖，
>    而深度/父会话/归属本来就是当轮事实；
> ② **有全循环分支就整轮换专属线程池**，不再用那张图：分支要等一整轮问答，挂在
>    `Schedulers.parallel()`（reactor 全 JVM 共享池）上会把无关流一起堵死；
> ③ **分支轮不收"要有人在场"的工具**（askUser/writeTodo）——判据做在描述符新字段 `viewerRequired` 上，
>    过 `ToolMountContext.turnAllows` 这个跨族汇聚点（装配 + `ToolRegistry.exposes` 两处同源，
>    沿用 P2 第三刀的教训：族的 exposes 不是汇聚点）；审批 ask 的分支同样不给沙盒/MCP，
>    且 `approver()` 在分支轮返回 null（走 ApprovalGate 已写明的"无人可确认直接拒"），
>    否则每一支都要白等 120s；
> ④ **引用继承 = 子轮编号按 knowledgeId 续编进父轮**（先占位 `⟦b<i>:r<n>⟧`，父轮资料填充完再回填），
>    父轮没有的补注册为 `origin=SUBAGENT`；**超出本轮库范围的块不注册**，只摘编号并在段末说明——
>    范围是智能体的能力边界，不能因为"分支跑在另一条会话里"就把它绕过来；图片按原始 URL 归并同号；
> ⑤ **产物逐级收编**（`ArtifactService.adoptFromSession`：库里 session_id + 内存清单 + done 汇总三处一起改），
>    否则临时会话一删，用户只剩一句"已生成"点不开。
> 一处自发削减：分支轮**跳过隐式多视角编排**（这一支本身就是被扇出来的，再展开一层是乘数成本；真机每支省 4~6s 与两次调用），
> 显式挂了的子智能体仍按深度上限往下派。
> 真机证据（探针三个智能体成链 A→B→C，用完已删、配置已还原）：`分支执行方式：1 支全循环`；
> 两层嵌套各跑一整轮（B 深度 1、C 深度 2，各自临时会话与 `[PERM]` 档）；`上限收到 1` 后 B 那一轮
> 判成 `0 支全循环 / 1 支检索要点`；分支 25 个工具 vs 主轮 27 个（差的正是 askUser/writeTodo）；
> 回报段回填 `父轮引用 4 条，长度 294→286`，父轮正文 `[1][2]` 指向 `origin=SUBAGENT` 的可点开来源；
> 超时 15s 那一支被子轮 `stopTurn` 叫停、孙辈半程落库退场，**主链路照常作答**且卡片显示 failed；
> 产物 `收编 1 个 → 收编 2 个` 逐级上收到父会话。
> 两个**没直接验到**的：台账 `kind=subagent`（没有按 kind 的查询口，只有代码路径 + 同一判据的日志）；
> 免登录分享会话下的分支（`modeOf` 里 guest 直接判 digest，未跑）。
> **P3a 第二刀 + 第三刀已落地（2026-10-11）**：
> ① **父上下文继承档** `agent.subagentBriefMode` = brief｜recent｜full（默认 brief，逐字保持原行为：
>    最近 4 条、各截 220 字）+ `agent.subagentBriefTurns`（recent/full 带几轮，默认 6）。
>    **A/B 真机证据**（同一条委派问题、同一份 8 行的表放在上一轮）：brief 档分支回报
>    「第 6 项的原文没有随请求传入，也未在知识库检索到，因此不给数值、不做推算」；
>    把档切到 full 再派一次，同一支回报 **「第6项封顶额度 1822 元」并原文照录「第6档封顶 1822 元」**，
>    `日志：[SUBAGENT] 父轮交代：档=full 预算 4000 带 9 条 成文 2735 字`。
>    full 档刻意不压空白（表格换行就是内容，`clipForBrief` 与 `truncateForBrief` 分开）。
>    默认仍留 brief 的理由：分支是按支数乘成本的，带多了还会挤掉子智能体自己那部分资料的预算。
> ② **分支实时过程下钻**：`CollectingSseEmitter.forwardProcess(...)` 把子轮的
>    `tool_status`/`tool_output`/`stage` 原样转给父轮通道（事件名换成 `subagent_step`、带分支序号），
>    前端把它们列在对应分支那一行下面（与扇出清单同一套 `fan-*` 排版，只留最近 30 行）。
>    实时态**不落库**：刷新后看到的仍是「跑了 N 步工具」与回报全文——与 run_code 的进度帧同一条口径。
>    后端侧已验（首帧日志 `[SUBAGENT] 分支实时过程首帧：branch=0 type=stage 父轮接收=true` +
>    `FwdProbe` 断言三类过程事件全转、done/retrieved 不转），**浏览器那一屏也补到了**（15:2x，
>    八条法条逐条查的长分支）：分支行下面实时列出 11 行——「正在检索资料…」「正在生成回答…」
>    「查知识库资料 **在跑×1**」（在途那一条带蓝、跑完换成耗时），随后是 `9/10/11 查知识库资料 0~1ms`
>    这样的三连——那不是bug，是那一支撞上了自己的步数闸（深度 1 预算减半），被挡下的调用就是 0ms，
>    这行信息本身正是实时下钻该露出来的东西。终态一到，done 用 `subagentBranches` 整份替换
>    `msg.subagents`，实时行随之消失（设计上不落库，留下的仍是「跑了 N 步工具」+ 回报全文）。
> 已知边界不是 bug：分支的沙盒工作区是它自己那条临时会话的目录，父会话看不见中间文件
> （真机主模型自己就发现"上次报告的交付并不属实"并重跑了一遍）——只有走 `presentArtifact` 交付出来的文件才随收编上移。
> 真机也抓出两个实现错误并已修：`CollectingSseEmitter` 的 done content 是**双层 JSON**（直接 parseObject
> 会在引号处报错，表现是引用/步数静默为 0）；`appendReplacement` 漏 `appendTail`（一处编号都没命中的回报
> 整段被吞成空串）。见 [[project-p3a-full-loop-branches]]。

### ★ P3b · PTC / `run_code` 程序化编排

借的机制：`tools.mode = native | ptc`。

DSH 侧：ptc 下工具表**只贡献 `run_code{code,description}`** + 注入 schema 生成的 TS `.d.ts`（可选 Python 渲染器）；每段程序跑在全新受管子进程、按该会话沙盒策略约束；**子调用重新进入完整守卫流水线**（`pre-execute→guard→execute→post-execute→tools/result`，带父 token、call id `<parent>:ptc:<n>`，调度事件不进模型历史）；`PtcRunResult={value?,logs[],error?}`（失败是字段不是 reject，kind 正交 budget/abort/backend crash）；`maxParallelSubCalls` 默认 10；失败不隐式重放。

问渠实现分歧最大的一项：DSH 的 `run_code` 与工具注册表**同进程**，子调用是函数调用；问渠代码在 provisioner 容器、工具在 JVM，子调用必须**跨网络回调平台**。要多造三件 DSH 不需要的东西：① 工具分发回调端点 + 每次运行一次性 token；② `ToolDescriptor` → Python/JS stub 生成；③ 往返延迟预算（并行走有界池）。好处是隔离天然比 DSH 强（模型生成的代码在容器里跑，而不是宿主进程），代价是多一跳网络。载体本身已就位：`/v1/shell/exec` + Python/Node + 「落盘脚本 + bash 执行」那套流式改造。

> **P3b 第一刀已落地（2026-10-11，真机验完，Python 一条语言）**。新包 `com.wenqu.ai.agent.runcode`
> （`RunCodeBridge` 按轮回传 / `RunTokenRegistry` 一次性凭据 / `ToolStubRenderer` 生成 Python 客户端 /
> `RunCodeService` 落盘-执行-回收）+ 端点 `POST /api/ai/sandbox/tool-call` + 工具 `run_code`
> （SANDBOX 族 `SideEffect.MUTATE`，`tool.runcode.enabled` 默认关）。
> 四条实现结论，改这块前先读：
> ① **凭据走 `X-Run-Token`，绝不能用 `Authorization: Bearer`**。`UserContextInterceptor` 见到 Bearer 一定要
>    验成 JWT，验不过直接 401——请求根本到不了控制器，现象是"脚本里每次调用都失败"，看着像工具或网络坏了
>    （本机第一轮就是这么死的）。免登录门禁的豁免在 `SecurityConfig.isSandboxCallbackEndpoint`，
>    它免的是"必须登录"，不是免鉴权：认不出凭据照样 401。
> ② **子调用再入 = 同一条 `toolPipeline.wrap`，只换预算那三项**。绕开守卫的 run_code 是后门而不是省来回；
>    共用步数预算则会让扇出 20 次的脚本把后面真正需要工具的几步饿死。所以 `subCallTurnContext` 转发
>    一切（含入口线程捕获的身份快照）、只把 `maxToolSteps/takeStep` 换成 `tool.runcode.maxSubCalls` 那一摞。
>    `appendSteers` 刻意转发原样而不附插话——插话不属于一条子调用的返回值。
> ③ **生成的函数一律"全参数可选 + docstring 标必填"**。按 schema 的 required 生成必填形参，模型漏传 topK 时
>    Python 签名层就抛 TypeError，整段脚本在 ThreadPoolExecutor 里炸掉、一行 print 都没有，报错从
>    "少了哪个参数"退化成"脚本没输出"。必填交给工具自己校验，文案才有意义。
>    同理 Python 关键字：单位换算工具的 `from`/`to` 直接做形参会让整份模块语法错（真机踩过），
>    处理是形参改名 `from_`、下发的键名保持 `from`。
> ④ **「工具执行确认=每次问我」时不提供 run_code**（判据读本轮生效的 `ExecutionPolicy.approvalMode`，
>    不是只读智能体那一列）。一次扇出=同样多次确认卡，人点第一张时预见不了后面 19 次要读什么，
>    这个承诺在脚本形态下兑现不了——那就别给，而不是给一个把人淹掉的东西。
> 真机（探针智能体 + 5 路并发扇出，用完已删、配置已还原）：`子调用 5 次` 一张卡一段 stdout（1329ms），
> 5 次 searchKnowledge 的卡片与 10 条引用来源照常进父轮；单轮步数上限压到 2 时扇出仍跑满（预算独立）；
> 上限设 3 时第 4 次起 `本轮子调用上限 3 已用满` 且脚本自己捕获继续；脚本里 `write_file('/etc/passwd')`
> 被档位拒（拒绝文案原样回到 stdout，证明档位闸对子调用生效）、工作区写与交付成功；`call_tool('askUser'|
> 'run_code'|'deleteAllUsers')` 全被名册拒；坏凭据/空凭据/撤销后/按轮撤销各 401（`NameListProbe` 15 条断言）。
> 已知边界不是 bug：`ok` 是 HTTP/守卫层字段，工具自己返回的拒绝文案仍是 `ok=true + output`（脚本要读文本判断）；
> 子调用的实时输出不另开卡片通道；探针会话的沙盒工作区目录交给空闲回收（不手删，软删会话仍可撤销恢复）。
> 下一刀候选：Node/JS 那侧 stub、脚本内并发与预算的独立面板、子调用过程的下钻、以及 `deliver_artifact`
> 从脚本里交付后产物卡片的时间线位置。（**这批的落点**：前两项见 P3b 第二/三刀，下钻见第二刀；
> 「产物时间线位置」查清了——产物按既有口径沉底，不是缺陷，第三刀补的是卡片上的交付计数。）

> **P3b 第二刀已落地（2026-10-11，同日补齐边界里那三件）**：
> ① **JavaScript（Node）侧 stub** —— `ToolStubRenderer.Lang` 决定文件名/模块格式/命令（`node main.js`），
>    CommonJS + 全局 fetch（容器内 node v22.23.0 实测可用），并发是 Promise 队列信号量：单线程里挡的是
>    "在途请求数"，与 Python 的线程信号量语义对齐后两边扇出规模才可比。JS 侧函数只收一个对象参数、
>    键名就是工具的参数名，所以 Python 那套"关键字形参改名 + 键映射"整层不需要（少一层映射少一处错位）。
>    验证：`node --check` 判语法 + 真机 `Promise.all` 五路扇出 1362ms、used 5。
> ② **子调用过程下钻** —— 子调用不再各占一张顶层卡片（20 次扇出会把过程区刷成一堵墙，也看不出它们属于
>    同一次运行）：`subCallTurnContext.observe` 改为累积进 `st.subCallLog`，run_code 的终态卡带
>    `fanOut{total, items[{name,status,elapsedMs,brief}]}`（40 行为止，超出只报 omitted），PC/H5 点开可见。
>    子调用的 `onOutput` 刻意丢弃——顶层那张卡已经在流式转发脚本自己的 stdout，两路并推会互相打断。
>    同一处把 P3a 的分支回报全文补上：`branches[].report`（截 2000 字，随消息落库）+「看它交回的全文」开关
>    （真机：摘要 121 字 vs 全文 931 字，10 条引用已回填父轮编号）。
> ③ **台账 kind 出口** —— `statKindTokens` → `/stats/usage` 的 `kinds` → 使用统计页「这些用量花在什么地方」。
>    名字表放后端（`UsageStatsService.kindLabel`），认不出的 kind 原样透出、不并进「其他」（新用途该被看见）。
>    真机一屏：对话问答 81% / 子智能体分支 14%（103.8 万 tokens、477 次）/ 自动派遣 1.8% / 压缩 1.2% /
>    引用自检 / 记忆提取 / 检索词改写 / 计划意图识别 / 深度思考 —— P3a 那句"成本归属到分支"从此可查，
>    不再只有代码路径。
> 探针（2 个智能体、2 个会话、一次 JS 扇出、一次全循环委派）已全删，`tool.runcode.enabled` 复原为关，
> 探针沙盒容器与宿主目录（cap-probe）已清。仍未做：游客分享下的全循环（guest 恒 digest）、脚本内并发的独立面板。

> **P3b 第三刀已落地（2026-10-11，真机验完）——补的就是「脚本跑着的时候看不见」这一件**。
> ① **`run_code` 接进实时输出通道**：此前 `CTX_OUTPUT_SINK` 只注入 `execute`（`RagService` 那句
>    `STREAMING_TOOL_NAME.equals(...)`），于是 `run_code` 走同步执行——扇出 30 次就是两三分钟纯转圈，
>    用户读成"卡死了"并按停止。顺带挖出一个**必须 `-u`** 的坑：执行侧是把输出重定向进文件再按行轮询，
>    而 CPython 对**文件**是块缓冲的，不加 `-u` 时 `print` 攒到进程退出才落盘，「实时输出」名存实亡
>    （`ToolStubRenderer.Lang.PYTHON` 的命令因此是 `python3 -u main.py`；Node 写文件描述符是同步的，不需要）。
> ② **并发与预算的面板**：子调用的 `start` 也进观测，在途计数与同名合并（`在跑×6`）、
>    本轮额度 `used/max`、并发上限、脚本交付的产物数一并下发；卡片头一跳一跳显示「子调用 N 次 · 在途 K」，
>    展开有「本轮额度 12/50 · 并发上限 8 · 交付 1 个文件」一行。PC 与移动壳共用 `projections.js`
>    那 5 个函数（`fanHasBoard/fanHead/fanTitle/fanRowState/fanBudget`），两端不许各写一遍判据。
> ③ **发帧点绝不在子调用线程上**（本刀最大的教训）：第一版把进度帧直接在 `observe` 里发，
>    六路并发扇出时卡片停在「子调用 6 次」直到 120s 超时。改成**子调用线程只置脏位**，
>    由 `RUN_PROGRESS_POOL` 每秒最多合并一帧（与心跳/看门狗分池，理由沿用那条「慢客户端的 send 不许连坐」注释）；
>    终态那一份仍挂在 `run_code` 记录里随消息落库，**进度帧不进账本、不入 `toolCalls`**（实时看的，两套真源必打架）。
> ④ **一次运行一份清单**：`subCallLog` 此前按轮累积，同一轮里第二段脚本的卡片会显示第一段做过的事；
>    现在每次 `run_code` 起头复位（`startRunBoard`）。**`subCallCount` 刻意不复位**——那是守卫按轮扣的预算，
>    面板如实标成「本轮额度」。
> ⑤ **脚本里的 `deliver_artifact`**：能交付、产物卡片按既有口径**沉底**（`projections.js:421` 早就决定产物
>    不按生成时刻就地渲染，所以"时间线位置"不是缺陷而是既有产品口径）；本刀补的是卡片上的**交付计数**。
> 真机（同一探针会话三段脚本，用完已删、配置复原为关）：并发 6 路扇出时卡片实时显示「在途 6 / 在跑×6」，
> 脚本 stdout 逐行滚出（`phaseA-concurrent-shell`、`call 2 True 4.4`…）；顺序脚本 `write_file`+`deliver_artifact`
> 成功并把「交付 1 个文件」带上卡片；最终版 12 次子调用 2.19s 跑完、`exit=0`，扇出清单 12 行带各自耗时。
>
> **这一刀明确收窄的一件事（不是完整版，是决定不给）**：**脚本里不能调 `execute`**。
> （刀4 把这条的因果订正了：丢的成因不是"并发占用那条 bash 会话"，见下面刀4 那段。收窄这个动作仍然对，理由要重述。）
> 沙盒容器里只有一条 bash 会话，外层脚本就挂在那条会话上，嵌套命令行会与它互相打断——
> 本机实测：四路并发嵌套 `execute` 时**服务端四条命令都跑完并记进扇出清单，客户端只收回到两三条响应**，
> 剩下的连接永远等不到返回（脚本停在未返回的那次调用上直到外层超时）；平台侧加锁串行化后
> 仍会丢排队最久的那一条。已排除的原因：进度帧的发帧点（去掉后照丢）、容器→宿主的 TCP 空闲被掐
> （实测 idle 1s/8s/20s 都能拿到响应）、后台进程被回收（实测 `sleep 25` 活着）、守卫链阻塞（jstack 无阻塞线程）。
> **机制未定位**，所以做法是切断入口而不是假装它能用：`prepareSubCallPipeline` 按名字摘掉 `execute`，
> 脚本里调它得到「本轮没有挂载工具「execute」」（真机验到该行原样打印在 stdout），
> `run_code` 的工具说明同步写明"要跑命令行请直接调 execute 工具"。文件类（`read_file/write_file/edit_file/ls`）
> 走的是文件 API，不碰那条会话，脚本里照常真并发——12 次扇出 2.19s 就是这条判据的证据。
>
> **仍未做（P3 范围内，如实列）**：
> - ~~游客分享下的全循环~~ **已验（同日补）**：把平台档设成 full、用一条真实分享链接（免登录）问
>    「派子智能体去查第四十三条原文」，日志落在
>    `[SUBAGENT] 分支执行方式：0 支全循环 / 1 支检索要点（深度 0…）` +
>    `[TOOL] 游客分享会话：只挂描述符声明 guestAllowed 的能力`，游客页正常出答案并带 6 条引用。
>    同一时刻登录态那轮是「1 支全循环」（15:27），两边对照说明这道闸按设计生效：**guest 恒 digest
>    不是没做完，是决定不给**（没有观众在场，审批/沙盒/长分支都没有可交付的对象）。
> - `subagent_step` 的浏览器端到端一屏：后端转发已验，前端接线已就位，缺一次目视（见 P3a 第三刀那段）。
> - 嵌套命令的可靠通路：成因已订正到"容器→宿主回程丢长等待的响应"，流式+心跳这条修法已试并回退；
>   下一步是换环境复测（线上 Linux 桥接网络）再决定恢复名册还是改造执行通道，别在没复测前放开。

> **P3b 第四刀已落地（2026-10-11，真机验完）——把"没跑成"与"跑成空"分开，并把实时面板挪出危险区**。
> ① **`run_code` 的结果结构化**：新增 `RunCodeService.Outcome{text, exit, infra, timedOut, ms, calls, maxCalls, truncated}`，
>    随 run_code 终态卡片落 `run{kind,...}`，卡片头直接标「追踪超时：脚本可能还在沙盒里跑」/
>    「沙盒不可用（不是脚本写错）」/「退出码 N：脚本可能没跑完」，正常结束不额外显示。
>    换掉的是那句 `out.startsWith("沙盒不可用")`——文案一改就静默把基础设施故障显示成绿色对勾。
>    配套修了一个真缺口：`mergeDoneToolCalls` 逐字段搬运，不认识 `fanOut`/`run`，
>    后果是「当场看得见、done 一到就没了」——现在两个字段都搬。
> ② **并发水位总览**：进度帧除挂卡片外，同份数据留在消息上（`msg.runBoard`，终态清空），
>    折叠的过程簇/工具组头上显示一行「脚本运行中 · 子调用 7/50 次 · 在途 2」。
>    **卡片展开时不重复显示**（卡片头本来就是同一份数）——所以单工具的那一屏看不到这行，属设计而不是漏。
> ③ **嵌套 `execute` 的成因订正 + 维持不收**：给回调加「平台侧回执日志」后测得很干净——
>    四路并发时**平台四次都把结果写下并交回响应**（4.7/8.2/8.3/9.0s 各一行 `[P3B] 子调用回执`），
>    容器里的脚本只打印到第三条，第四条永远等不到返回。所以丢点在**容器→宿主这条回程**，不是守卫链、
>    不是发帧点、也不是那条 bash 会话。试过把它改成正解：响应改 `StreamingResponseBody` + 每 2s 写一个空格当心跳
>    ——**更糟**（平台四条都写下、容器一条都没收到），已回退。本机 Docker Desktop 的转发路径没法从平台侧证死，
>    因此维持"名册里不给 `execute`"，但**理由要按上面的事实说**：不是"嵌套会互相卡住"，而是
>    "长一点的等待就有响应回不来"。留了一行 `[SUBAGENT] 式`的首帧日志同级物证（`[P3B] 子调用回执`），
>    换环境（线上 Linux 桥接网络）复测时，一眼能分清是没执行还是没送达。

可感知：一句"把这 30 份文档逐一汇总"变成一段脚本扇出，而不是 30 次来回 + 撞 15 步闸。

### ★ P4 · 扩展面零碎差距

- 技能 frontmatter 缺 `whenToUse` / `disable-model-invocation` / `user-invocable`；URL 安装**只拉单个 `SKILL.md`**（`SkillService:262`），DSH 支持目录包（`references/ scripts/ assets/` 按需解析被显式引用的资源、不枚举目录）。渐进披露问渠**已有**（`:202 promptBlock` + `SkillTools.readSkill`），别重复造。
- MCP 客户端只有 streamable/sse 且**只取 tools**；DSH 是 stdio + streamable-http、支持 resources（`list_mcp_resource_templates` / `list_mcp_resources` / `read_mcp_resource`）、prompts 明确不支持 → 补 stdio 与 resources。
- 无对话内 `web_fetch`（有 `webSearch` 但抓不了任意 URL）。

---

## 四、自研路线与优先级

| 阶段 | 闭环 | 人日 | 切片与回滚 |
|---|---|---|---|
| **P0** | `c_ai_session_event` 双写（不改老读路径）+ `POST /chat/stop` + 先只做 `steer`（内存表、步间消费、可否决收尾）+ todo 状态机 | 18–28 | 事件表一期不进模型输入；关开关即回旧行为，**零数据迁移** |
| **P1** | `ToolDescriptor` + `ToolRegistry`/守卫链 + per-invocation 上下文 + `ChatCommand` | 25–35 | 绞杀式按族迁：内置纯函数→检索→联网→技能→沙盒→MCP，一族一个可交付改动，同一时间只有一族在新链路；`instrumentTools` 保留到末族迁完；工作流 mcp/skill 节点此期保持无守卫，只留 `bypassGuard` 标记，不打断在跑的画布 |
| **P2** | 沙盒 3 档 × 审批 2 档 → presets + 单调 guard + `restrict` 替掉 if 链裁剪 + `SANDBOX_UNAVAILABLE` fail-closed | 15–22 | 默认 preset 完全等于当前行为（workspace-write + 现状审批），逐项 opt-in |
| **P3a** | `runAgent` 换接 `subagentBody` 通路，`ChatCommand` 带父 transcript 摘要 + depth 硬顶 + 每层预算递减 + 并行度配置化 | 12–18 | 按智能体开关灰度，一键回落 digest 轻量模式 |
| **P3b** | `run_code` + descriptor→SDK 生成 + 子调用再入守卫链 + 跨网络回调端点 + spill | 30–45（最大单项） | 默认关，只在 `workspace-write`/`danger-full-access` + `never` 档暴露；可一键回落 native |
| 独立 | 历史投影切换：`historyLine` 拍平文本 → 结构化消息列表 | 8–12 | 按会话白名单放量，切换前用 `RetrievalEvaluationService` 跑对照 |

合计约 **110–160 人日**。

**如果只做三件事**：P0、P1、P3a。
- P0 是唯一一项用户当天就能在屏幕上感知的，且双写不改老读路径、不阻塞同仓并行改动。
- P1 是 P2/P3 的承重墙（守卫插槽 + 一次性入参 + 元数据单一真源），顺带修掉那个串号缺陷。
- P3a 单位投入改变能力形态最大：通路已在生产，只差接线。
- PTC（P3b）排在 Top3 之外：它要 P1+P2 齐了才安全，且跨网络回调是三件 DSH 没有的新组件，对当前用户形态收益面最窄。

**关于"带代码的第三方分发"**：唯一正确形态是**进程外 sidecar**（独立身份 + 独立容器 + 资源/网络配额 + 人工上架审核），等价于给第三方一个受限 MCP server；不在本路线内，要做单独立项。可低风险借鉴的只有一件：`cordis_inspect_list/query` 式的**只读运行期内省**（问渠已有 `ToolInventoryController`，可扩成配置项内省）。

---

## 五、问渠的差异化主场（DSH 没有，这才是"自研"要加倍投入的）

| 主场 | 现状 | 为什么 DSH 补不了 |
|---|---|---|
| **知识驱动的子 agent** | 知识库 / 混合检索 / GraphRAG / `[N]` 引用体系齐全 | DSH **完全没有内建记忆、RAG、向量库**，记忆只能挂第三方 MCP（默认关闭）。派子 agent 出去拿回带引用的结论，是本机 coding runtime 做不出的产品形态 |
| **长期记忆** | `UserMemoryService`（每轮异步提取 ≤3 条、向量去重 0.90、注入预算、`hit_count`） | DSH 零实现 → 记忆是既有资产，P3a 只要让分支继承它 |
| **智能体即服务** | 对外 MCP server（`/ai/mcp/{token}` + 平台级 4 元工具 + `c_ai_mcp_call_log` 审计）、分享页免登录 SSE、工作流 `X-Api-Key` 触发、H5 外壳 | DSH 的"扩展"是把代码装进自己的机器；问渠的"扩展"是**别人直接调用你的智能体** |
| **多租户长任务治理** | `UsageLedgerService`（记账在路由出口）、`ModelQuotaGuard`（402 与 429 互斥）、`RateLimitService`、`ResourceVisibilityService` | DSH **无成本核算、无配额、无 RBAC、无多租户**。P0/P2 的"停止/档位"只有接进这套才不构成账单与权限漏洞 |
| **质量治理** | 评估集（recall@k / MRR + LLM 裁判 + 下滑告警）、检索调试链路、trace 采样、官方手册库 | DSH 无评估概念。§五的投影切换正是要靠它兜底 |
| **非程序员交付** | 产物 `ArtifactService`（HMAC 签名 URL、按生成时刻插入时间线）、解析管线 + OCR SPI 三家、附件 fileId、通知中心 14 类 | DSH 的 `present`/`deliverables` 面向本机文件；问渠面向"给老板一份能点开的东西" |
| **跨设备人在回路** | 提问卡 / 审批 / 计划三卡 + `listPendingAsks:7016` 跨设备恢复 + 断线转后台续跑 | 服务端形态天然具备，本机 runtime 反而做不到 |

**自家短板（对标之外、但同源）**：无跨模型 failover、无按用户/部门 token 预算拦截、通知靠前端 30s 轮询、工作流无并行网关、定时任务多副本各自独立调度。

---

## 六、最容易被低估的一处成本

**不是**存量会话数据迁移（消息行本就是粗事件，双写共存即可）。

**是**：一旦事件粒度 + 结构化投影落地，就逼你把 `historyLine` 的拍平文本改成结构化消息列表，**工具轨迹第一次进入模型输入**。模型看到的内容形状当场变化，连带漂移：token 预算（窗口×0.7）、`assembleHistory:5206 compressRatio` 阈值、`historySummary` 摘要口径、`RetrievalEvaluationService` 既有基线、每轮成本。表现为"上线后同问题回答质量变了但说不上为什么"。

对策：P0 的事件表**只服务展示与审计，明确不进模型输入**；"历史投影切换"单独立灰度开关，按会话白名单放量，切换前用现有评估集跑一轮对照。**单独估 8–12 人日，别并进事件表。**

---

## 七、关键落点与收口判据

**关键文件**

| 文件 | 关注行 |
|---|---|
| `src/main/java/com/wenqu/ai/service/RagService.java` | `1778-2131` 装配+守卫链；`5187-5375` 历史投影/`historyLine`；`524-661` chat 重载与轮互斥；`1955/7253` 步数闸；`1968-2033` 审批；`2068-2089` set/clear 单例 |
| `src/main/java/com/wenqu/ai/service/SessionService.java` | `948-1007` 唯一消息写入路径（append-only 起点） |
| `src/main/java/com/wenqu/ai/service/WorkflowEngine.java` | `1044-1086` 全管线子 agent 通路；`1161/1199-1209` 无守卫旁路 |
| `src/main/java/com/wenqu/ai/service/SubAgentOrchestrator.java` | `607-704` 分支体；`RagService:4731` 并行度 |
| `src/main/java/com/wenqu/ai/controller/ChatController.java` | stop / resume 端点落位 |
| `web/src/chat/projections.js` | 投影层 + 工具元数据副本（要删的三份之一） |

**收口判据（真机，不看单测）**

- P0：跑 >5 步任务点停止 → 半程落库、`TURN_IN_FLIGHT` 释放、下一问立刻能发；跑动中发 steer → 下一步生效且过程簇看得见；刷新后过程与 todo 完整重建。
- P1：跨会话并发用例（两会话同时用 `searchKnowledge` + `webSearch`，断言来源不串号）；工作流 mcp 节点新旧链路结果一致。
- P2：三档各跑"写文件 + 执行命令"，断言 `read-only` 档下模型工具表里**看不到**沙盒工具（不是点了被拒）。
- P3a：子 agent 独立完成"查库→跑代码→出产物"，断言用自己的知识库范围、成本归属正确、depth 到 2 被拦、引用与记忆继承到位。
- P3b：`run_code` 扇出 20 个 `read_file`，断言子调用逐个过审批闸、只 print 结果回上下文、token 归父轮。
  （**第三刀后的实测状态**：8 路 `read_file` 扇出 + 交付混在一段脚本里 2.19s 跑完、`exit=0`，扇出清单逐条带耗时，
  print 之外不进上下文，台账 `kind=chat` 归父轮；**嵌套 `execute` 已从名册摘掉**，见 P3b 第三刀那条。）
- 每阶段末跑 `RetrievalEvaluationService` 对照基线，防投影/预算漂移。

---

## 附录 A · DSH 能力事实清单（对照用，均已读仓库原文）

- **循环**：turn/step 双层；waterfall 扩展点 `agent/pre-step`、`agent/request`、`agent/request-error`、`llm/stream`、`tools/pre-execute|execute|post-execute`；serial `agent/turn-stopping`（可被 `steer()` 否决再来一步）；inbox 三档 `followup/steer/inject`；`cancel()` 把 `AgentCancelCause` 持久化进 `turn/end`；`TurnEndReason=completed|aborted|blocked|error|max-tokens|interrupted|forked`；工具并行 `executionMode=parallel|exclusive` + barrier + bounded pool。出处 `docs/architecture.zh.md`、`docs/agent-lifecycle.zh.md`、`docs/subsystems/{core,tools}.zh.md`。
- **会话**：append-only `SessionEvent` 为唯一真源，`deriveMessages()` 投影重建请求；`ctx.sessionProjections`（`turnBoundary` 等纯函数单元）；`session.vN.jsonl[.zstd]` + v0→v4 迁移链 + checkpoint fail-closed；`ctx.sessions.fork(source, boundary?, childId?)` 支持 mid-turn 分叉（Web 有"Branch into a new conversation"）；`ctx.sessionQuery` = SQLite FTS5 跨会话检索 + `session_event_read/search/trace` + `/export` ZIP。出处 `docs/subsystems/session.zh.md`、`docs/session-format-status.zh.md`。
- **工具面**：32 包 / 67 个模型可见名（`docs/tool-catalog.zh.md`）。值得注意：`todo_write`、`create/get/update_goal` + `goal-round-driver` + `ralph`、`terminal_open/close/list/read/send/signal`、`bash/pwsh`（一次性 + 持久）、`run_in_background`→`ctx.jobs`（`job_list/output/kill`）、`schedule_*`（到点以普通 follow-up 回原会话）、`web_search`+`web_fetch`、`str_replace_editor`、`glob/grep`（自带 ripgrep，超上限走 spill）、`read/write/edit/read_image`、`present`+`ctx.workspaceChanges`（git 快照逐文件对比）、`lsp`（刻意只 4 个只读操作，排除 rename/format/diagnostics）、`working_directory`、`create_worktree`、`session_*`、`subagent`/`subagent_fork`/`list_subagent_models`/`send_message`/`interrupt_agent`/`list_agents`、Agent Teams 9 件（`spawn_teammate`/`wait_agent`/`team_task_*`）、`plugin_manager`、`cordis_inspect_list/query`、`mcp` resources 三工具、`run_code`。
- **上下文与压缩**：`ctx.compaction`（`compaction/start|summary|end` 锁、`Trigger=pressure|context-overflow`、`compactNow/compactIfNeeded/compactRegion`）、`ctx.toolResultPruner`（定向修剪旧工具结果）、`ctx.tokenMeter`、`image/offload`；上下文注入单元 `agent-instructions`（AGENTS.md/CLAUDE.md，唯一默认挂载）、`time-context`、`file-reference`、`session-reference`、`tmux-context`。**无内建长期记忆/RAG**（记忆靠第三方 MCP，默认关闭）。
- **安全**：见 §三 P2；另有 `fs/{write,edit}-intent` 门禁与 `fs-observation-policy` read-before-edit、env scrub（spawn 前剥 `*KEY*/*SECRET*/*TOKEN*/*PASSWORD*` 与 `DSH_*`）、spill 文件 0700/随机名/`wx`+0600、`hooks-claude-code`/`hooks-codex` 兼容层、experimental `auto-review`。`SAFETY.md` 本身只是免责与"未经安全审计、不可视为生产可用"声明。
- **技能/MCP/扩展**：技能与 MCP 细节见 §三 P4；扩展四分工 = `bundle`（带 `dsh.bundle.patch` 的 npm 分发单元）/ `profile`（用户 boot 的配置层，`$DSH_HOME/profiles/<name>`，层序 bundle→profile→home→`--patch`，`--dump-config` 可见）/ `preset`（某类 Agent 的工具+prompt+skills 组合）/ `sdk`（进程外 NDJSON-RPC 驱动完整 runtime，TS + Python）。
- **多端**：`apps/cli` 是唯一受支持 Node 启动器（profile=`web|headless|sdk|sdk-minimal|acp|plugin`；`headless` 一次一任务、`--json` 事件流、`--session-id` 续话）；`dsh web` 默认 127.0.0.1:3080、tokenized URL + 签名 cookie 鉴权每条 RPC/WS；desktop = Electron 壳 + `desktop-host`（Node-mode 私有宿主进程）；`ctx.typertGateway` + `@Remote` typed RPC；`acp` 包 = Agent Client Protocol v1（`session/new,list,resume,close,prompt,cancel,set_config_option`）。另有 `browserUse`/`computerUse`（独占 provider，一次只允许一个）、`speech-to-text`、`office-toPdf`、`webhookRuntime`（GitHub webhook 自动建 review 会话）。
- **DSH 明确没有的**（别误学）：内建记忆/RAG/向量库/知识库；成本价格与配额核算；对外 OpenAI-兼容 API；声明式 DAG 可视化工作流；多租户/RBAC；内建 MCP server 侧；已发布的安全审计。

## 关联

- ~~`docs/p0-leftovers-2026q4.md`（P0 能力边界与验证账）~~ **2026-10-13 已整体收进本文 §三 P0 末「真实边界与验证账」并删除该文件**：
  同步 exec 路径拿不到整份输出（镜像只回最后约 1 万行且无标记）、刻意不猜轮次、接流设备不代发起设备续发插话、
  还欠的真机验证，以及那四条"只有真跑才露出来"的既有缺陷判据，现在都在那一节里。

- `docs/roadmap-2026q4.md`（现有功能升级规划）：本文 P0 的 todo/停止/插话 与它的「Agentic RAG 自主多轮检索」「消息编辑重发 + 分支」是同一改造面，落地时应合到同一张 `c_ai_session_event` 底座上，别做两套事件模型。
- `docs/market-gap-analysis-2026-10.md`（市面产品差距清单）：那份按"能不能上生产"排序，本文按"agent 运行时机制"排序；两者对「无 failover、无 token 预算拦截、通知靠轮询」的判断一致。
