# P5 · agent-as-tool：拆掉派遣路由，智能体降级为可调用工具

> 立项卡 · 2026-10-12 · **姿态：不兼容、不灰度、不双写**。现存的会话锁定与自动派遣按 §三 的清单删除，缺口由 §四 的补位填。
> 唯一需要拍板的开放项在 §六（不支持工具调用的模型怎么办），其余可直接开工。

## 〇、为什么现在的路由必须拆（查实结论，非推测）

### A 级：实现缺陷（可当场证伪）

1. **"派中了"和"派丢了"在观测上不可区分。**
   模型输出里没有 `{...}` 时，代码按候选顺序做子串匹配猜一个（`AgentDispatchService.java:144-147`）；而候选顺序是 `is_default DESC, create_time DESC`（`AgentService.java:239-243`）。
   → 回声型/解释型输出（弱模型常把候选清单复述一遍）**必然命中默认智能体**。于是日志里的「路由命中」和「回落默认」指向同一个对象，你无法判断这套判路到底在不在工作。
2. **判路用当轮的回答模型**（`:56`、`:88-96`），8 秒超时（`ConfigDefaults.java:61`），static 池只有 2 线程（`:36`）。
   deepseek-flash 这类恒思考模型关着思考也回约 2000 字 reasoning → 路由这一笔既慢又贵；`routeFuture.cancel(true)` 能否真中断 WebClient 底层调用是注释里的**假设**（"WebClient block 可被 interrupt 唤醒"），未实测。假设破了就是 2 个线程被慢路由长期占住 → 全平台派遣集体超时回落，而这条路径的失败表现恰好是"看起来像命中了默认智能体"（见第 1 条）。
3. **零测试。** `src/test/java/com/wenqu/ai/` 下没有任何 `AgentDispatchService` / `parseDispatchResult` 的引用。一个带 4 条判定规则、一个 JSON 契约、一个兜底猜测分支的判路器，全靠线上表现。
4. **判路输入只有名称+描述**（`:63-67`），而描述是给人看的宣传句。现库三条里「问渠主智能体：知识库问答的默认入口…」与「知识库智能体：依据知识库资料准确回答问题，适合查询产品文档与各类资料」语义几乎无差别；prompt 规则 2 要求模型"别按词面相似判"，但它手上只有词面。
5. **唯一一次判路发生在首问**（`RagService.java:5681-5683`），首问常是试探性的一句。用信息量最低的一问做唯一决策，然后锁死整会话（`SessionService.java:1098-1112` 的 `isNull` 单次写）。

### B 级：结构错误（不是 bug，是层级选错了）

6. **winner-take-all**：一次调用把 N 个资料域压成 1 个。跨主题问题（"对比智报平台的报表功能和劳动法里的工时规定"）在数学上不存在正确答案——它需要两个域，路由只能给一个。
7. **路由层级错**：用户问的是"该查哪几块资料"，系统路由的是"换哪个角色"。角色一换，提示词/工具/权限档/库范围全跟着换，所以**路由越准、副作用越大**——这正是"改成每轮重派"必须额外加权限收口的原因。把"换角色"这件事整个取消，副作用面直接消失，不需要收口。

## 一、删除清单（不是加开关，是删代码）

| 删什么 | 位置 |
|---|---|
| 派遣服务整个类 | `service/AgentDispatchService.java`（含 static 池、prompt、`parseDispatchResult`） |
| `"auto"` 分支 | `RagService.java:5619-5648` `resolveAgent` 的 auto 路径与 `emitDispatched` |
| 会话锁定 | `RagService.java:5659-5706` `resolveSessionAgent` 的绑定/回显/`emitAgentBound`；`SessionService.java:1098-1134` `bindAgent`/`getAgentBinding`/`AgentBinding` |
| 两列数据 | `c_ai_session.agent_id` / `agent_name`（`schema.sql`；一次迁移删列，存量不补） |
| 三个配置键 | `agent.autoDispatch`、`agent.routeTimeoutMs`（派遣侧用途；子智能体路由若仍需要则改名归位）、`agent.dispatchNarrowScope` + `config-schema.json` 两处文案 + `ConfigService.java:592-595` 播种 |
| 前端锁定态 | `useChatEngine.js:202-279` 的 `agentMap`/`sessionAgent`/`boundAgentOf`/`agentLocked`/`pickAgent` 锁定分支；`ChatPage.vue:980-1037` 的 agent-menu 整块 |
| 两个事件 | `agent_dispatched`、`agent_bound` + `SessionEventsPanel.vue:182-183` 标签 + `UsageStatsService.java:98` 的 `dispatch` 名称 + 用量台账里那一栏 |
| 三处口径 | `README.md:201/367/447`、`manual/06-智能体.md:18` |
| 库墙 | `RagService.java:6629` `applyScope` 的"按智能体库过滤"语义、`:2593-2595` 工具侧同库界注入（游客链路另说，见 §四-3） |

**注意**：2026-10-12 刚改的 5 处文案（`ChatPage.vue:986/998/1005/290`、`MobileModelSheet.vue:12`）随 picker 一起删，不是返工是收尾。

## 二、保留清单（这些不是路由，别一起砍）

- `SubAgentOrchestrator` 的**分支执行/聚合/引用回填/实时面板**（`:675-706`、`:483`、`:786`）——这是执行体，不是判路器。
- `BranchRunner`（`agent/subagent/BranchRunner.java:24-27`，实现 `RagService.java:6056`，构造点 `:1366`）与 `BranchRequest`（`:29-32` 已带父会话/父轮/深度/超时/访客/模型）。
- `@` 提及的入口（前端 chips、后端 mentions 校验、`ChatPage.vue:298-304` 常显的委派卡片）——语义换芯，入口不动。
- 工具族框架：`ToolFamily`/`ToolFamilyProvider`/`ToolDescriptor`/`ToolEnrichment`（模板 `KnowledgeToolProvider.java:44-46`，按轮注入见 `RagService.java:2575-2598`）。
- 分享页、定时任务（`ScheduledJobService.java:271`）、工作流（`WorkflowEngine.java:1062`）、对外 MCP（`McpServerService.java:321/478`）的**显式 agentId**：那是"发布态整包"，与被删的"会话级锁定"是两回事。

## 二·五、施工约束：P5 的新代码一行不进 `RagService.java`

该文件 **10029 行、39 个注入依赖、257 个方法、18 个 public 入口**，早就是"整轮问答运行时"而不是检索器（检索本身已外移：`HybridRetrievalService` 1103 行）。它的拆分是另一张卡：`docs/p6-ragservice-split-2026q4.md`。

- `consultAgent` 落 `agent/tool/provider/SubAgentToolProvider.java` + `agent/tool/SubAgentTools.java`（模板见 `KnowledgeToolProvider.java:44-46`）。
- 可调用清单由 `AgentService` 出方法给，不在 RagService 里拼字符串。
- 回报转文本、每轮咨询台账与去重 → 独立类，不写成 RagService 的私有方法。
- RagService 里**只允许两类改动**：§一 的删除；以及 ToolEnrichment 注入表加一行（`RagService.java:2575-2598`——那张表本身就是 P6 第一刀的搬迁对象）。
- 收口判据：P5 完成时 `RagService.java` 行数必须**下降**（预计 −200 行以上）。若上升，说明有东西写错了地方。

## 三、补位（删完必须有，否则是残废不是简化）

1. **回答者固定**：内置「问渠」（`isDefault` 那一个，其范围运行时解析为"使用者默认库＋官方手册库"，见 `RagService.java:6447-6470`）。会话里不存在任何"选谁"的动作。
2. **`consultAgent(agentId, question)` 工具**：新族 `SUBAGENT("subagent", "智能体咨询", true)`（审批 off 档整族不挂，与 SANDBOX/MCP 同类）。
   - 工具描述注入本轮可调用清单 `- id | 名称：职责描述`，来源＝可读 + 非自身 + 非调用链上的人；**不再**读 `agent.getSubAgentIds()` 那份手工名单（`RagService.java:5940-5965`）。
   - 上下文按轮注入 `CTX_BRANCH_RUNNER`/`CTX_PARENT_SCOPE`/`CTX_CONSULT_LEDGER`。`BranchRunner` 是父轮回传的函数而非 bean（理由见 `BranchRunner.java:6-11`），所以只能走 ToolEnrichment，不许再造 ThreadLocal。
   - 返回：回报正文（截断沿用 `agent.digestMaxChars` 口径）+ 已登记的引用编号；失败分类型回报，不外抛。
3. **主检索范围＝使用者可见库**，配**每域保底名额**（否则大库把小库挤出上下文名额，这是"什么都检索不到"的另一种形态）。游客分享页与对外 MCP 的库界**保持原样**——那是免登录读资料的唯一权限墙（`KnowledgeRetrievalTool.java:70-78` 是 2026-09-26 的越权修复成果）。
4. **@ 智能体换芯**：从"整轮换成它作答"（`RagService.java:931-951`）改成"本轮**必须调用**它一次"，内部实现就是强制一次 `consultAgent`。回答者不变，人设不换。
5. **个人设置加"默认助手"**：给法律部门那种高频场景，替代被删的会话锁定。

## 四、四条规则

1. **委派不提权**：子轮档位取 `min(被调方档, 父轮档)`。个人上限（`agent.permissionPreset.personalMax`）本来就收着跨用户风险，这里要防的是"父轮以为在 standard 只写工作区，实际有一支在 autonomous 写全盘"——审计口径与用户预期不符。
2. **预算**：每轮最多 `agent.consultMaxPerTurn`（默认 3）支、同一智能体不重复咨询（重复直接返回上次回报）、深度与并行复用 `agent.maxParallelSubAgents`。台账里子智能体分支已占 14%（103.8 万 tokens / 477 次），且 `agent.enabled` 默认还是关的（`ConfigService.java:544`）——默认开之前这一栏必须有数。
3. **可见性**：清单＝本轮使用者的可读范围；游客/对外链路只允许该分享智能体自己声明的子集；不得调用调用链上的人（防 A→B→A）。
4. **看得见**：气泡上「本轮咨询了「A」「B」」常显（派遣信息现在藏在 `ChatPage.vue:292` 的 `debugDisplayVisible` 后面）。自主委派不显示＝无法归因。

## 五、验收断言（真机行为）

1. 同一会话连问「智报平台怎么建仪表板 → 劳动纠纷怎么解 → 帮我写周报」，不 @ 任何东西：第二轮能点开**法律库**的引用；第三轮不产生任何咨询调用。
2. 断言"不重复咨询"：一轮里让它问同一个智能体两次，第二次直接拿上次回报，分支数不增。
3. 断言"委派不提权"：父轮 standard、被调方 autonomous → 过程面板出现降级提示，沙盒写入仍落在工作区。
4. 断言游客链路没被放宽：免登录分享页问超出该智能体库范围的主题，拿不到别的库内容。
5. 断言路由代码真的没了：`grep -rn "AgentDispatchService\|autoDispatch\|agent_dispatched" src web/src` 为空（README/手册同步后）。
6. 断言 §六 的降级：造一次上游对带 `tools` 的请求返回 400（接一个不认 tools 的端点即可）→ 页面出现"本轮未能咨询其他智能体"的降级条，回答仍带得上多域并集检索的资料，**不是裸答**。（2026-10-13 08:47 用 `scripts/fake_upstream_no_tools.py` 实测通过：换路后正文到手、`done` 里 `toolsRejected` 降级登记、`toolSchema` 6583→0 而提示词内容不变。**两点修正**：一是当初实现判据取不到响应体、根本不会触发（已修，见 §七）；二是当时降级条只在本轮流式里可见，**刷新后消失——已随 #18 持久化并实测刷新后仍在**。）
7. 断言回显：管理页模型列表看得出"这个模型实际会不会被下发 tools"，且与 `toolCapable` 原值（未登记＝默认放行）不矛盾。
8. 回归：定时任务、工作流触发、断线重连回放（每条气泡归属仍对）、H5 全流程、`npm run check` 全绿。

## 六、开放项已定（2026-10-12 实测后改写，原判据不成立）

**"不支持工具调用的模型"在问渠里不是一个会自然出现的类别。** 判据是**默认放行**：`ModelRegistryService.java:362-367`——非 chat/omni 直接 false；chat/omni 若管理员显式登记过按登记值；**没登记就 true**。本机实测：4 个供应商、10 个模型、7 个 chat/omni 的 `toolCapable` 全为 `null`，即全部按"能调工具"处理。只有管理员显式登记 0、或模型引用解析不出来（`:370-373` 返回 false）才会挂不上。

所以风险换了位置，变成这两条：
- **默认放行＝网关可能 400**。私有化小模型（vLLM/Ollama 裸部署）常不支持 tools，管理员没登记 0 → 平台照发 tools → 上游报错，表现是**整轮失败**而不是"退化成不咨询"。P5 之后 `consultAgent` 进了工具清单，这条会更常踩。
- omni/vision 模型带 tools 的能力通常更弱，判据同样默认放行。

**定案**（原 (a)(b)(c) 三选作废，尤其 (b)——它保的是已判定为结构性错误的东西）：
1. **给工具调用失败一条可见的降级路径**：上游对带 tools 的请求返回 4xx 或明确"不支持"时，`addDegradation` 登记 + 本轮改用**多域并集检索**兜底（每域保底名额，即 §三-3 的默认行为本身），气泡上明说"本轮未能咨询其他智能体"。不许静默裸答。
2. **补一列回显**：`/provider/{id}/models` 现在只出 `toolCapable`（原值），没有解析结果——对照 `visionResolved` 的做法缺一个 `toolCapableResolved`。补上并在管理页显示"这个模型实际会不会被下发 tools"，否则默认放行是看不见的地基。
3. 估时并入 P5a：+0.5d（降级登记）、+0.5d（回显列与管理页一行）。

## 七、已知缺口与当前进度（2026-10-12 施工中断点，接手从这里开始）

**当前是"两套机制并存"的中间态，不可发布**：咨询工具已挂上（`tool.subagentConsult.enabled` 默认开），而 §一 的删除清单**一行未删**——首问锁定与自动派遣照旧。重启后端即生效为"锁定 + 可咨询"，这是刻意的施工顺序，但别当成收口。

### 已完成（编译通过、`config-schema.json` 校验通过）
- 新族 `ToolFamily.SUBAGENT`（审批 off 档整族不挂）+ `SubAgentToolProvider`（分支轮不挂、游客不挂）+ `SubAgentTools.consultAgent(agentId, question)`。
- `SubAgentConsultService`：按轮台账（同一位只咨询一次、命中直接回上次回报）、每轮上限、超时与失败都回文本不外抛；回报文本**剥掉子轮编号**再把子轮资料注册成父轮引用（避免 `BranchReport` 注释点名的"两套编号互相指错"）。
- `AgentService.consultCandidates/consultCatalog`（可读全部助手 + 排除自己，含子智能体；描述为空的如实标"未填职责描述"）。
- 系统提示新增「可咨询助手」一节（判据与装配同源，都问 `ToolRegistry.exposes`；挂不上就不写这一节）。
- 按轮接线：`AnswerStreamState.consultCtx/consultLedger`、`registerConsultedSource`、`toolContext` 注入执行器。
- 配置三键（`agent.consultMaxPerTurn`=3、`agent.consultTimeoutMs`=120000、`tool.subagentConsult.enabled`=true）已进 `ConfigService` 播种 + schema 的 fields/tips/editable 三处。

### 未做（2026-10-12 23:10 更新：#8 删除清单已执行完）

**已删干净**（`grep -rn "AgentDispatchService|autoDispatch|agent_dispatched|agent_bound|dispatchNarrowScope|AUTO_AGENT|agentLocked" src web/src` 应为 0）：
- `AgentDispatchService` 整类；`resolveAgent` 的 auto 分支与 `resolveSessionAgent` 的首问锁定；`emitDispatched`/`emitAgentBound` 与两个 SSE 事件（含 `RagService` 事件白名单、`api.js` 映射、`SessionEventsPanel` 标签、`UsageStatsService` 的 dispatch 名称）。
- 前端 `AUTO_AGENT`/`sessionAgent`/`boundAgentOf`/`agentLocked` 与「切换=开新会话」分支、下拉里的「自动派遣」那一行、锁图标、`m.dispatched` 排障徽标（PC + H5 各一处）。
- 配置两键 `agent.autoDispatch`、`agent.dispatchNarrowScope`（播种 + schema 的 fields/tips/editable/corePaths 四处）与「委派收窄主检索范围」那段分支逻辑。
- `AgentService.dispatchCandidates()`；README 三处与手册 `06-智能体.md` 两处口径重写（新增「谁来答这一问」一节）。

**一处偏离原计划，是实测逼出来的**：`c_ai_session.agent_id/agent_name` **没有删列**。查读者发现它还撑着两件事——分享页按智能体列访客会话（`listSessionsForAgent`）与访客闲置会话按智能体回收（`deleteSessionsOfVisitor`），这两条属于 P5 明确保留的"发布态"链路。所以**改语义不删列**：`SessionService.recordTurnAgent()` 每轮记一笔「最近作答方」，同时删掉 `findEmptySession` 里那条 `isNull(agent_id)`（旧锁定残留的"空会话不可复用"判据）。列名与实体字段将来该正名为"最近作答方"，那是一次独立的小迁移，不在本卡。

### §三-4 @ 换芯已完成（2026-10-12 23:43）
@ 智能体不再换作答方：`agent = resolveTurnAgent(...)`，被 @ 的那位作为**强制咨询目标**注入本轮编排（排在候选最前、`agent.enabled=false` 也照跑、平台路由没挑它也补进去）。归属仍是作答助手，可见标记改为「本轮已咨询 X，采纳它查到的资料」（PC + H5 + @ 面板 hint 同步）。@ 的就是作答方时如实登记一条降级，不静默吞掉用户指令。
**顺手修掉一个我自己造出来的缺陷**：@ 强制咨询之后，模型还会自己调一次 `consultAgent` 问同一位 → 同一条子轮跑两遍（23:41 实测两条分支）。修法是把被强制咨询过的 id 带进咨询上下文（`Ctx.preConsultedAgentId`），工具侧命中就 4ms 返回"已咨询过、结论在上下文里"，不再开第二条分支（23:43 实测确认）。
命名债：事件名 `agent_delegated`、前端字段 `m.delegated`、`st.delegatedAgentId` 都还叫"委派"，语义已是"强制咨询"。改名要动事件白名单 + 前端 + 手册，留作独立小 PR。

### §三-5 个人默认助手 + @ 放开子智能体 已完成（2026-10-13 05:27）
- 走**个人覆盖配置**那一档（`agent.personalDefault`，schema 标 `personal`，存 `c_ai_user_config`），不为它新开偏好接口；`SchemaField.vue` 加了 `type: "agent"` 控件（候选取 `/agent/available`，清空=跟随平台默认），个人设置页 schema 驱动自动出现这一项。
- 解析顺序：请求带 id → 个人默认助手 → 平台默认智能体 → 全局配置。个人默认失效（被删/不再共享）时**明确说一句**再回落，不让人以为自己的设置还生效。
- 实测：设成智报助手后空 agentId 的一轮 `本轮使用智能体 = 智报平台操作手册智能体`；测试数据已从库里清掉。
- `ChatController` 不再禁止 @ 子智能体：@ 已是"强制咨询"，子智能体恰是挂得最规整的资料域；它仍不能当问答角色（对话页下拉照旧剔除）。
- **一条施工教训**：`config-schema.json` 是 classpath 资源，改完只跑前端校验不算数——`target/classes` 里还是旧 schema，`schema.isPersonal()` 判假、个人覆盖被静默丢弃，表现成"设置没生效"。改资源必须重新 `mvn compile` 再重启。

### §六 降级路径实测（#16，2026-10-13 08:47）：判据当年根本触发不了，另外抓出一条无限重试

**先补上造不出来的触发条件**：`scripts/fake_upstream_no_tools.py`（OpenAI 兼容假上游，监听 127.0.0.1:7999）——带 `tools` 的请求一律 400 且把 "does not support tools" 写在**响应体**里；两个模型分别演「不带 tools 就正常流式」和「不带 tools 也 500」。登记成个人供应商 + 两个 chat 模型（`toolCapable` 留空＝默认放行，正好复刻 §六 说的那类私有化网关），会话级切过去打真实一轮。**验完供应商、模型、测试会话都已删干净**。

**实测结果把「已实现但没验证」的两条都推翻了**：

1. **判据看不见上游返回体，换路一次都不会触发。** 根异常是
   `WebClientResponseException$BadRequest: 400 Bad Request from POST http://…`，
   Spring 6 这条消息里**没有响应体**——上游写的 "does not support tools" 只在
   `getResponseBodyAsString()` 里。原实现只看 `root.getMessage()`，于是表现照旧是「AI 回复失败，请稍后重试」的整轮失败，
   与 P5 之前的唯一区别是多了一条日志。修法：判据改取**整条异常链 + HTTP 响应体**（限长、限深防自引用死循环），
   并把入参从 `root` 换成 `error`。
2. **换路闸门会把重试次数上限短路，再失败就无限重建死流。** 原条件
   `if ((st.toolsDropped || (retryable && retried++ < 上限)) && noTokenYet)`——`toolsDropped` 一旦为真就一直是真，
   `||` 短路易让 `retried` 永不自增，换路后若再次无 token 失败会**无限**重跑同一条流。
   修法：拆成一次性闸门 `toolsRetryQueued`（置位后消费即清），`toolsDropped` 只作"本轮已改无工具"的状态标记；
   通用重试仍走 `chat.streamRetryCount` 的额度，两条互不借道。

**修后真机行为（认行为不认数值）**：
- `fake-no-tools` 一轮：假上游收到 `withTools=true`（28 个工具）→400，随后 `withTools=false`→200，
  气泡正文到手（假上游那句「本轮没挂工具也照常作答」），`done` 里登记
  `{"code":"toolsRejected","msg":"当前模型不接受工具调用，本轮已改为不带工具回答：不会咨询其他助手，也不会补充检索"}`；
  两次 `usage` 的 `toolSchema` 从 6583（+mcpSchema 1201）掉到 **0**，正文与资料段不变——换路只摘工具、不重拼提示词。
- `fake-no-tools-then-500` 一轮：假上游请求序列＝`[带 tools→400, 不带→500, 不带→500]`，
  即**只换路一次 + 额度内重试一次**后收尾报错，整轮 3 秒结束，不再无限重建。

**顺带记两条不是本轮缺陷的观察**：
- ~~降级条只在**本轮**流式里可见~~（当时 `c_ai_message` 无 degradations 列、`GET /session/{id}` 也不回吐，刷新后条子消失）→ **已改：#18 持久化并实测刷新后仍在**。
- 换路那两轮主链路 `[CTX] 上下文填充 0 块`（检索命中 96/113，分支视角各 3 条），与 #14 的既有分布一致，
  与工具摘除无关——提示词只减 schema 不改内容，这条实测反而再次印证了 #14 的结论。

**P5 的 #16 遗留到此结清**：脚本留在仓库里，以后任何"上游不认 tools"的回归都能当场重放。

**一次没复现出来的异常（不当缺陷记）**：假上游那次 `PUT /provider/{id}/models` 存了 2 个模型后，`GET /provider/{id}/models` 一直返回空、`/provider` 的 `modelCount` 也是 0，重启后端才读到（库里的行一直在）。同一套动作重做一遍（建探针供应商→存 1 个模型→当场读）则正常，`changed()→reload()` 生效。唯一差别在我这边多了一次「缺 contextWindow 被 400 拒掉」的失败保存在前。暂不立项；再撞到就先查那次失败留了什么。

### §六 定案两项（2026-10-13 05:35 写出，其中一项当日 08:47 才真正实测——见上一节）
- **`toolCapableResolved` 回显**：`/provider/{id}/models` 现在带解析结果（实测 `raw=null → resolved=true`，即"没登记=默认放行"这个地基第一次看得见），管理页速览只在判为**不能调工具**时标「无工具」——对话类模型默认都能调，逐个标「工具」是噪声。
- **工具被上游拒时的换路重试**：`doOnError` 里识别 `looksLikeToolsRejected`（必须同时出现「tool/function」与「不支持/无效/未知」两类字样，宁可漏判不误判）→ 置 `st.toolsDropped`、`st.enabledCallbacks=List.of()` 重跑一次不挂工具的同轮，并登记降级「当前模型不接受工具调用，本轮已改为不带工具回答：不会咨询其他助手，也不会补充检索」。
- **当时的未实测声明**：触发需要一个不认 `tools` 的上游端点（本机所有模型都放行），本机造不出来；判据、重试路径、降级文案只经过代码走查。**08:47 用假上游实测后，这一条的判据实现被证明是失效的，已修**——见上一节，别把这里的历史描述当现状读。

### §四-4 咨询归属常显已完成；白名单决定不做（2026-10-13 07:55）
- 新事件 `agent_consulted`（咨询**跑成功**才发，失败的咨询没交出东西，标成"咨询过"等于虚报工作量）；前端把 @ 强制咨询与模型自主咨询按 id 合并成一条常显徽标：「本轮已咨询 「智报平台操作手册智能体」、「法律子智能体」 · 采纳它们查到的资料」。
- **刷新后仍在**：从落库的 `consultAgent` 工具入参还原。为此新增 `GET /agent/consultable`（可读助手的 id+名称，**含子智能体**）——`/agent/available` 刻意剔除子智能体，只登记那份就会出现"徽标上挂着一串裸 id"（实测踩过，已修）。
- 实测：PC 与 412×916 H5 两侧都看到合并后的两位助手名；`npm run check` 0 FAIL。
- **白名单不做**（用户拍板）：可调用清单＝使用者可读的全部助手，"这个会话里别派给某位"属小众需求，不值得再开一层配置。picker 因此保持现状：选一位作答 / 默认助手。

**仍未做**：
1. 可选：0.6 相关性门是否适配你这台机器的 CPU 重排服务——现在有诚实的调试面板可以量了，动之前先取分布，别照本卡的历史数字调参。
2. ~~等你拍板的两条小改动~~ → **两条都已做完并实测（2026-10-13 09:05，见下两节）**。

### #17 重排窗口两键登记进设置页（2026-10-13 09:05）

`rerank.minHits` / `rerank.maxHits` 进了 `config-schema.json`（检索设置面板·重排区，与「启用重排」同一节），并在 `ConfigService.defaults()` 登记——`node scripts/gen_config_schema.js` 的「字段 ↔ defaults」一致性检查通过。库里那两行是 8-27 就存在的旧行（remark 写着「只读配置」），顺手把 remark 改成了实际说明；新环境首次插入时用 schema 的 help 文案。

实测（真机）：
- `GET /config/schema` 下发两个字段（label「少于几条不重排」def 6 / 「重排窗口」def 15）；`GET /config` 快照 `editable=true`、值 6/15。
- 接口 PUT `{"rerank":{"maxHits":"20"}}` → 落库 20 → `POST /api/ai/debug/retrieval` 触发一次检索，日志出现 **`[Rerank] 候选 98 条：top 20 进入重排，超出 78 条保持融合分序`**，**没重启**。
- 设置页 UI 也走通：改 18 → 保存按钮从灰变亮 → 点保存 → 库里 18（随后复原 15）。
- 手册同步：`05-检索原理与调参.md` 补「重排有两个窗口旋钮」一段，把「窗口外的候选借不了融合门入场」这条因果写清楚（这正是 #14 里被误诊成堵点的那件事）。

**一次自打自的收获**：这两键登记之后，`ConfigServiceUpdateTest` 里拿 `rerank.maxHits` 当"未知键"夹具的两条断言当场变红——它不再是未知键了。夹具换成 `rerank.failCooldownMs`（运行时会读、清单里确实没有），3/3 转绿。这条正好证明 #15 那套断言在守门：**改 schema 时测试会替你想一遍影响面**。

### #18 降级条持久化（2026-10-13 09:05）

`c_ai_message` 加 `degradations` 列（schema.sql + 实体，重启时 SchemaMigrator 自动补列，本机已补上）。写入走 `RagService.persistDegradations(messageId, 列表)` → `SessionService.saveMessageDegradations`：**只在真有降级时**补发一条 UPDATE，正常轮次零额外往返；不进那个 21 参数的 `appendMessage` 签名（它已是 P6 的搬迁对象），而且降级列表在落库时点之后还会增长（换路重试、工具链路都在之后登记）。四个回答落库点全部接上：正常轮、中断半程轮、计划收尾轮、工作流轮。历史接口 `GET /session/{id}` 回吐 `degradations`，前端历史映射带上该字段（`ChatPage` 的 degradation-bar 与 H5 的 warn-bar 本就按它渲染，无需改动）。

实测（用 #16 那个假上游造一次真实降级）：
- DB 行存了 UTF-8 原文两条（`toolsRejected` + `noHit`），`GET /session/{id}` 原样回吐；
- 浏览器**新开导航**进该会话（等价于刷新）：气泡下方两行提示都在——「当前模型不接受工具调用，本轮已改为不带工具回答：不会咨询其他助手，也不会补充检索」「未检索到相关资料，回答可能缺乏依据」；
- 显示口径不变：降级列表本身仍由 `chat.retrievalDebugEnabled`（设置页「检索调试入口」）决定，关着就一条都不登记，也就不会往库里写空提示。
- 测试供应商/模型/会话与假上游进程已清理，`rerank.maxHits` 复原 15。

**H5 未单独核实**：MCP 浏览器视口没收窄（≤1024 才走 mobileShell），`/m/chat?sid=` 当时渲染的是桌面壳。数据源与渲染分支同源，但这条没截图证据。

### #15 结案（2026-10-13 08:15）：未知键的假成功改为整笔拒绝

`ConfigService.update()` 原先把不在 `config-schema.json` 里的键静默扔掉，然后照样保存其余项并返回「配置已保存并生效」——界面显示成功、库里没动，照着这个结果做的 A/B 全是错的。现在遇到不认识项就在**任何写入之前**整笔拒绝，错误消息点名到具体键，与家里另外两条口径一致（`UserConfigService.save` 的「不支持个人设置的配置项：xxx」、`resetDefaults` 的「不支持恢复默认的分组: xxx」）。

顺带解释了 #14 的过程疑点：23:30 那次 `rerank.maxHits` 15→60 是直接改库才生效的。库里这行存在（现值 15，与代码默认一致），但它不在 schema 的 227 个字段里，所以设置页看不见、PUT 改不动。**这条已收口（#17，2026-10-13 09:05）**：两键登记进设置页，调窗口不再需要动库。

真机验证（重启后端加载新字节码后打接口）：
- 只提交未知键 → 400，消息点名 `rerank.maxHits`；
- 混提交（合法 `context.maxBlocksPerDoc=2` + 两个未知键）→ 400，且库里 `context.maxBlocksPerDoc` **仍是 3**（没写一半）；
- 只提交合法键 → 200 并落库（测完已复原为 3）。

断言测试落在 `src/test/java/com/wenqu/ai/service/ConfigServiceUpdateTest.java`（未知键点名且不碰 DB / 混提交不写一半 / 拼错的键不被当成别的键收下）。为此在 `pom.xml` 加了 `spring-boot-starter-test`（test scope，本机 `~/.m2` 已有全部传递依赖，离线可跑）——这是仓库第一个测试目录，CI 的 `mvn -B verify` 会跑到它。

**P5 主线到此收口**：机制已替换（派遣路由与会话锁定删除、解绑、agent-as-tool、@ 换芯、个人默认助手、归属可见），补丁与取证也已做完（#15 假成功、#16 换路实测、#17 窗口两键入设置页、#18 降级条持久化）。唯一留着的是那条**调参选择题**：0.6 相关性门在这台机器的 CPU 重排服务下每轮只放 1~4 块进上下文，要不要动由你按调试面板的实测分布决定——不是缺功能。

**当前状态**：不再是"两套并存"，P5 的机制替换已完成并真机验通——23:05 新会话里默认助手作答，**同一轮把两问分别派给「智报平台操作手册智能体」与「法律子智能体」**，两问分开答对、24 处角标、31.2 秒。
`RagService` 10029 → 9996 行（净 −33：删掉派遣与锁定 107 行，但咨询提示词一节、按轮台账接线、每库配额等新增抵掉了大半）。**没达到 §二·五 写的 −200 判据**，那一条当时是按"只删不加"估的，实际 P5 的补位本身就带代码量；判据修正为"派遣与锁定相关行数归零"，已达成。（2026-10-13 #16 实测修的两处——判据改取异常链+响应体、换路一次性闸门——又加回约 25 行，现 10097 行；仍是"删的那部分归零"，P6 的拆分不受影响。）
### 解绑后暴露的真问题：主链路上下文常常只填 0~1 块（结论修正版 2026-10-12 22:47）

**解绑本身是成的**：同一会话（锁定在「问渠」，旧墙下只能查默认库＋官方手册）问"智报怎么新建报表"，答出正确的 8 步操作、19 条来源全是《智报平台操作手册》的文档；再问复合题"智报怎么新建报表 + 劳动法加班工资怎么规定"，**两问都答对**（13 条来源横跨智报库与法律库、21 处角标，含《劳动法》第四十四条 150/200/300% 那张表）。用户最初那句"下一轮问别的主题就什么都查不到"已经消失。

**但主链路填充量低得反常**：候选 108~118 条，`[CTX] 上下文填充` 只有 0~1 块（复合题 0 块、单主题 1 块）。**三个嫌疑项逐个 A/B，全部排除**：

| 试的变量 | 怎么试 | 结果 |
|---|---|---|
| 每库上下文配额 | `context.maxBlocksPerKb` 设 0 再问同一句 | 仍 1 块 → 不是配额 |
| 重排相关性门 | `retrieval.minContextScore` 0.6 → 0.45 再问同一句 | 仍 1 块 → 不是门（同期工具路 DIAG 显示 114→13 条过门，门在工具路径上是放行的） |
| 重排窗口独占 | 加"按库轮转"改 `RerankService.java:201` 的 head 顺序 | 仍 1 块 → **不是窗口**；该改动已**回退**（A/B 无收益的机器不进热路径） |

**已排除的环境因素**：本机 Meilisearch 是活的（`:7700 health=available`，22:25 起索引初始化完成），22:01 那条"关键词引擎不可用已降级 MySQL"是冷启动瞬态。所以 0~1 块是真实管线行为，不是环境噪声。

### #14 结案（2026-10-13 08:05）：没有堵点，是**调试面板在说谎**

用 `POST /api/ai/debug/retrieval` 取到真实分布，结论把前面所有假设都推翻了：

| 问题 | 候选 | 过 0.6 门 | 过门块的融合分名次 | 分数 |
|---|---|---|---|---|
| 问渠文档解析失败原因 | 96 | **2** | 0、2 | 0.99、0.88（其余 ≤0.13） |
| 智报怎么新建报表 | 118 | **4** | 0、1、2、3 | 0.89、0.87×3 |

分布是**双峰**的：一两块极相关，其余断崖到 0.1 以下。所以"上下文只填 1~2 块"是当前重排模型与该语料下的**正确结果**，不是链路堵了。配额、门限、窗口三组 A/B 全是空转，因为根本没有那个堵点。

**真缺陷在诊断侧**：`RetrievalDebugController` 的 `finalContext` 是"重排后取前 8"，**不过相关性门、也不复刻"重排已执行则未重排不得入场"** → 面板报 8、实际进 2。正是这个 8 让我相信"链路里有东西把 6 块拦掉了"，连着找了三个不存在的嫌疑人。已修：面板改用与生产同一道门 + 同一条未重排禁入规则，并把两处仍存在的差别写进 `contextGate.note`（面板对全部候选重排、不复刻配额与去冗）。修后面板报 2 与 4，与实测一致。

**同时收回我自己写进卡里的一句错话**：前文"解绑把 4 块放大成 0~1 块"不是控制变量的对比——那是两句不同问题、不同时间的数字。撤回。解绑没有造成这个回归。

**留给调参的真问题（不是 bug，要不要动由你定）**：0.6 门在这台机器的 CPU 重排服务下，每轮只放 1~4 块进上下文。线上若用不同的重排模型，分布会不同——现在有诚实的面板可以量了。

### 曾经的头号嫌疑（保留作过程记录）
**（历史推断，已被上面的实测推翻）**剩下的头号嫌疑**：能进上下文的天花板本来就只有重排窗口那 15 条——`RerankService.rank` 只重排前 15，而"重排已执行 → 未重排的候选不得借融合门入场"（`RagService` 填充循环 :1714 附近）把其余 90+ 条整片拦掉。下一步要用 `RetrievalDebugController`（`/api/ai` 检索链路分步调试）把窗口成员的 rerank 分实际分布打出来，判清是"窗口里就没几条真相关"还是"过滤链某处在静默丢块"（`dedupThreshold=0.35` 的去冗余与 `adjacentMerge` 都还没被排除）。**别再重复上面三个 A/B。**

**为什么今天答案仍然对**：模型自己烧了 `searchKnowledge`／咨询分支把资料捞回来——**靠 agent 的自主性在替检索管线兜底**，代价是每轮多花几步工具与一条 26 秒的子轮。这不是可接受的稳态。

**23:17 追加一条，严重性上调**：解绑后连**本域问题**也填 0 块——新会话问"问渠里文档解析失败一般是什么原因"（作答方=问渠，资料就在它自己勾的官方手册库里），`检索命中 96 块 → 上下文填充 0 块`，回答仍正确（985 字、14 处角标）但全靠 3 次工具调用现查。解绑前这种轮次通常填 4 块左右。
所以 #14 不是"与 P5 无关的既有问题"，而是**被解绑放大的回归**：候选池从约 20 涨到近百，而重排窗口只有 15、窗口外一律不得入场，于是本域资料也可能整片落在窗口外。优先级排在 #10/#11 之前。
上面"三个嫌疑全部排除"的准确说法是：**这三个旋钮都不是有效的修复点**。

**23:30 又追加两项 A/B，同样排除**：
- 关掉 `context.dedupEnabled` 与 `context.adjacentMergeEnabled` → 仍 1 块（去冗余与相邻合并不是原因）。
- 把重排窗口 `rerank.maxHits` 15 → 60（`检索命中 96 → top 60 进入重排`）→ **反而 0 块**。窗口大小不是原因。

**当前最像的解释（未证实，别再猜着改）**：同一道 0.6 门，工具路（`rankForced`，模型自己写的窄查询）能放行 13 条，主路（原问题整句去重排）放行 0 条。差别要么在**查询形态**（整句 vs 关键词），要么在**本机重排服务的分数分布**（`rerank.baseUrl=http://localhost:7997`，CPU 服务，可能整体给分偏低）。下一步唯一该做的是用 `RetrievalDebugController` 把这两种查询下的 rerank 分直方图各打一份，看 0.6 门切在分布的哪里——**在此之前不要再动任何参数**。
另记一条真缺陷：`PUT /api/ai/config` 对不在 schema 里的键（如 `rerank.maxHits`）返回"配置已保存并生效"但实际什么都没写——假成功，与"设置页不许有改了不动的假开关"是同一条底线，值得单开小 PR。**（已结案：2026-10-13 08:15 改为整笔拒绝，见上文 #15）**

### 下一步的顺序（2026-10-12 22:50 修正）
解绑（#9）已落地并实测有效，剩下的按 **#8 删除清单（纯删，收口语义）→ #12 前端与手册 → #10 @ 换芯/个人默认 → #11 降级与回显** 走。
**上下文只填 0~1 块那件事（#14）单独立项**：三个 A/B 已证明它不是 P5 引入的（配额、门、窗口全不是原因），它是解绑**暴露**出来的既有检索调优问题，取证要靠 `RetrievalDebugController` 看分数分布，和 P5 的收口互不阻塞。
不要因为 #14 没结就停 #8——删除清单不做，"会话锁定 + 自主咨询"两套并存的中间态会一直留着。

## 关联

- `docs/p6-ragservice-split-2026q4.md`：**P5 之后**做。本卡的咨询台账与预算逻辑要写成独立类，正是为了 P6 刀 3 直接收编进 `SubAgentBranchRuntime` 而不返工。
- `docs/agent-runtime-gap-2026q4.md`：P3a（子智能体真全循环）、P3b（`run_code`）是本卡两块地基；本卡是 `BranchRunner` 那条接缝的第二次复用。
- 记忆：`project-agent-binding-plan-b`（Plan B 交互稿随本卡作废）、`project-p3a-full-loop-branches`、`project-legal-subagent-rerank-gate`、`project-default-kb-positioning`、`project-detach-resume-run-bus`（删 `agent_bound` 事件要连回放链路一起改）。
