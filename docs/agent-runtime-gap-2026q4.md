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

> **P0 落地状态（2026-10-10）**：上表五项全部落地并真机验证；但**大输出留存、过程回放、多端接流都做成了基础版**，
> 未覆盖的部分、未做的部分与已知小缺口单独记在 **`docs/p0-leftovers-2026q4.md`**——后续完善从那份文件起手，别按本文的表格以为已经齐了。

好消息（已核实）：`SessionService.appendToMysql:948-1007` **本就是 insert-only**（`selectForUpdate` 锁会话行 → `maxSequencePhysical+1` → `insert`，全项目无内容列 UPDATE），`Session.summaryUntilSeq` 已是 checkpoint 水位，前端 `web/src/chat/projections.js`（836 行）已显式命名投影层 → **半径是加一张事件表，不是重写会话层**。

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
> **P1 剩余**：`ChatCommand`（十个重载）、工作流 `mcpBody`/`skillBody` 两条裸调旁路接守卫、
> 游客 share 路径真机验证、`AgentsPage` 那份中文名手抄、`skillOn` 与挂载判据那条差异的口径决定。

### ★★ P2 · 权限档位与安全

借的机制：`SandboxExecutionPolicy` × `ApprovalPolicy` × `ctx.permissionPresets` + 单调 `ToolGuard`。

现状：审批只有 `agent.toolApprovalMode=auto/ask/off`（`RagService:1968-2033`，超时 120s→按拒绝，落 `c_ai_tool_approval`）；沙盒单一"可写 `virtualPathPrefix`"、**无 per-call 模式**；守卫逻辑内联在 `instrumentTools` 匿名类（外部策略插不进来）；**工具/skill 级无授权表**。

DSH 侧的机制值得全盘吸收：沙盒 3 档 `read-only|workspace-write|danger-full-access`（`SandboxEnforcement=full|partial`，受限策略下静默透传永远不合法、`SANDBOX_UNAVAILABLE` fail-closed，policy **per-call 携带**）× 审批 `ask|never`（`ApprovalOutcome=allowed-once|rejected|cancelled|unavailable`，无应答者即 unavailable→deny，全量审计）；两 knob 捆成具名预设；**单调 guard**（返回 reason 即 deny、无 allow 结果，监听器顺序无法翻案）；`tools.restrict{allow,deny}` 让被禁工具"提示词不可见 + 拒绝执行"与不存在不可区分。

自研接法：preset 归属沿用仓库既有**配置四档**口径（平台全局 / 个人覆盖 / 个人专属 / 资源级绑定），落 Agent 配置 + 个人覆盖。⚠️ 必须与 `RoleService`（菜单+API 双绑定、fail-closed）、`ResourceVisibilityService` 对齐，最多补一张最小 `c_ai_agent_tool_grant`，**不另造授权体系**。

可感知：选档是一句话（只读调研 / 常规 / 全自主）；管理员收紧后模型连"有这个能力"都看不到，而不是点了被拒。

### ★★ P3a · 子智能体真全循环

借的机制：`ctx.subagents` 的 `spawn-in-process` / `fork-in-process`（继承父历史）。

现状：`SubAgentOrchestrator.runAgent:607` 分支体只做"检索 + 要点提炼"，`digest:704` 显式 `internalToolExecutionEnabled(false)` —— 不跑工具循环、不继承父上下文、只传原问题；并行硬编码 4（`RagService:4731`）。

**通路已在生产**：`WorkflowEngine.subagentBody:1044-1086` = 建临时会话 → `CollectingSseEmitter` → 完整 `ragService.chat(...)` → 跑完删会话。把 `runAgent` 换接这条路即得完整循环，所以它是四条线里唯一"只差接线"的。

硬约束（决定真实工作量）：`chat()` 的 `TURN_IN_FLIGHT` 是**会话级互斥**（`:661`）→ 子 agent 不能借父会话；`chat()` 已有 **10 个 telescoping 重载**（`:524-648`）→ 加第 11 个是错的，必须先对象化（P1 的活）；嵌套**全文无 depth 记录**（只有 `UsageAttr.hold(..., "subagent")` 归属标签）→ 要新增 depth 硬顶（建议 2）+ 每层预算递减。`digest` 降级为"轻量分支模式"开关，不全删（多视角检索场景它更省）。**必须强制继承长期记忆与 `[N]` 来源引用**，否则护城河在主链路有、分支上没有。

### ★ P3b · PTC / `run_code` 程序化编排

借的机制：`tools.mode = native | ptc`。

DSH 侧：ptc 下工具表**只贡献 `run_code{code,description}`** + 注入 schema 生成的 TS `.d.ts`（可选 Python 渲染器）；每段程序跑在全新受管子进程、按该会话沙盒策略约束；**子调用重新进入完整守卫流水线**（`pre-execute→guard→execute→post-execute→tools/result`，带父 token、call id `<parent>:ptc:<n>`，调度事件不进模型历史）；`PtcRunResult={value?,logs[],error?}`（失败是字段不是 reject，kind 正交 budget/abort/backend crash）；`maxParallelSubCalls` 默认 10；失败不隐式重放。

问渠实现分歧最大的一项：DSH 的 `run_code` 与工具注册表**同进程**，子调用是函数调用；问渠代码在 provisioner 容器、工具在 JVM，子调用必须**跨网络回调平台**。要多造三件 DSH 不需要的东西：① 工具分发回调端点 + 每次运行一次性 token；② `ToolDescriptor` → Python/JS stub 生成；③ 往返延迟预算（并行走有界池）。好处是隔离天然比 DSH 强（模型生成的代码在容器里跑，而不是宿主进程），代价是多一跳网络。载体本身已就位：`/v1/shell/exec` + Python/Node + 「落盘脚本 + bash 执行」那套流式改造。

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

- `docs/p0-leftovers-2026q4.md`（P0 收尾遗留清单）：本文 §三 P0 落完之后**实际还差什么**，含大输出留存的三段未覆盖、逐轮回放缺 `turn_id` 列、以及明确排在后面的项。

- `docs/roadmap-2026q4.md`（现有功能升级规划）：本文 P0 的 todo/停止/插话 与它的「Agentic RAG 自主多轮检索」「消息编辑重发 + 分支」是同一改造面，落地时应合到同一张 `c_ai_session_event` 底座上，别做两套事件模型。
- `docs/market-gap-analysis-2026-10.md`（市面产品差距清单）：那份按"能不能上生产"排序，本文按"agent 运行时机制"排序；两者对「无 failover、无 token 预算拦截、通知靠轮询」的判断一致。
