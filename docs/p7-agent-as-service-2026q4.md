# P7 · agent-as-service：把答案交出去，而不是留在对话框里

> 立项卡 · 2026-10-16 ｜ 状态：**D1 `c619bcc`、D2 `2d8ebb6`、D3 `a8743c4` 均已落地验证**；
> 立项时两项判断被核实推翻（`tools/list` 本来就有；审计缺的不是提问内容而是一根关联键），已改判并留痕。
> 上游：`docs/positioning-2026q4.md` §四-2「主场 · 智能体即服务」——定位稿把这条列为
> **投入最小、外部感知最快**的一条。本卡把它从"判断"落成"可交付"。
> 姿态：不改定位、不加新赛道，只把已有的四个出口接完整。

## 〇、一句话结论

**这条不是"没做"，是"做完了但没接出来"。** 底座比预期扎实——MCP 双向、逐次调用审计表、
IP 优先限频、游客工具白名单收窄、会话续接、平台级四个元工具、工作流 API Key 触发**全都在**。
缺的是两件：**把答案的价值一起交出去**，以及**让人找得到、接得上**。

## 一、盘清的现状（2026-10-16 核实，非推测）

| 出口 | 端点 | 现状 |
|---|---|---|
| MCP · per-agent | `POST|GET /ai/mcp/{token}` | token 即凭据（与分享同源，停用即失效）；工具名 `ask-{slug}`；`sessionId` 入参续接上下文 |
| MCP · 平台级 | `POST|GET /ai/mcp` | `X-Api-Key`；四个元工具 `wenqu_search_knowledge` / `wenqu_ask` / `wenqu_list_knowledge_bases` / `wenqu_list_agents`；可见范围与 Key 创建者网页所见一致 |
| 分享页 | `/s/{token}`（10 端点） | 免登录 SSE；引用区完整（`ShareChatPage.vue:83-89` 可点开原文）；会话历史/产物/stop/resume 都有 |
| 工作流 API | `POST /api/ai/v1/workflows/{id}/run` | `X-Api-Key` 跑已发布版本；**人工审核节点一律拒绝**（这条口径正确） |
| 调用审计 | `GET /api/ai/mcp/audit*` | 每次外部调用一行：channel/tool/owner/credentialRef/apiKeyId/agentId/IP/耗时/成功与否 |

## 二、D1 · MCP 随附来源清单（**已落地 `c619bcc`**）

### 问题
两个入口此前都只回 `addTextContent(answer)` + `structuredContent({sessionId})`，而正文里带着
`[1][2]` 角标——外部客户端拿到「带角标的无源回答」。**「答案可溯源」是本产品唯一承诺的东西，
网页与分享页都兑现了，只有 MCP 这一路整个丢掉。**

### 关键事实（决定了这是低成本修补而非重构）
`CollectingSseEmitter:93-96` 早已把整个 `done` 载荷原文存进 `donePayload`，里面就有
`sources`/`finalContent`/`degradations`/`tokens`。**数据一直在手里，只是从没人读过它。**

### 落地
- `sourcesOf(donePayload)`：投影来源清单，字段白名单 `ref/origin/fileName/siteName/url/title/score`
  （有重排分用重排分），**块全文 snippet 与块内 images 不外发**——与分享页
  `SessionShareService.projectMessages` 同一口径（那是库内内容，且 snippet 数百字会撑爆响应）。
- `appendSourcesBlock`：拼可读文本附在正文后。不只放结构化输出，因为不是所有 MCP 客户端都读
  结构化输出，而「[1] 是哪份文档」必须对最笨的客户端也成立。
- `MAX_SOURCES_OUT = 20`，超出写「另有 N 条来源未列出」——截断而不说，客户端会以为引用只有这些。
- 降级：取来源失败只记 WARN 并按无来源外发，**绝不把一个已经拿到的回答变成错误**。

### 验证（含一次"以为通过了、其实没通过"的教训）
编译与对拍台：380 源文件全量编译 0 错误；反射对拍台 **24 项全过**（夹具是库里真实落库的
`sources` JSON）：白名单/不外发/重排分优先/联网形态渲染/25→20 截断交代/边界 6 项全绿。

**但真机端到端第一次就发现问题**：MCP 回答正文带着 `[1][2]` 角标，`sources` 却是空数组。
日志 `解析 done 载荷取来源失败: illegal input, offset <len>, char U+001A`。

打点（`describeFailureWindow`）给出的决定性事实：**载荷里非标准控制字符 = 0**、
结尾结构完整、逐字段都是合法 JSON，而 fastjson2 的 `parseObject` 与 `parse` 两个入口
**都在 EOF 处抛同一个报错，并报出一个输入里根本不存在的字符**。

处置：**不再让「可溯源」依赖解析这份载荷**——改为按会话回读消息行的 `sources` 列
（与 done 同轮落库，前端刷新回显读的也是它）。载荷投影降为附加路径。见 `425ab2e`。

真机复验：单轮来源 5 条、正文清单完整、多轮续接 sessionId 正确透传。
（续接轮某次 0 条经核实是**正确行为**：该轮 `retrieved.refs=0`，检索结果全低于重排门 0.6
被丢弃 → 模型转用自己的知识作答。没有来源时不该编出来源。）

### 仍未定案（建议单独立项）
`done` 载荷为何让 fastjson2 在 EOF 处解析失败——载荷本身合法，用户侧已不受影响，
但载荷构造仍是手工拼字符串起家的路径（`AgentscopeChatEngine`）。**该处的 `JSONObject`
化改造已写好并验证等价（4/4 对拍），但未提交**：同一文件里有并行会话的在飞改动，
不做有风险的拼接。

## 三、D2 · 接入点可视化（**已落地 `2d8ebb6`**）

### 开工前的判断修正（重要）
立项时写的是"界面上任何地方都查不到接入点"。**核实后这句只对了一半**：
智能体「公开发布 → MCP 端点」页签**早就有**地址、可粘贴的 `mcp.json`、复制按钮与凭据失效说明
（`AgentsPage.vue:528-556`，`mcpUrl`/`mcpJson` 在 `:1120-1122`）。所以本仓库**只补平台级那一半**，
不重做已经对的东西——这条修正本身是本次立项最大的收获：先核实再动手，省掉一次返工。

### 真正缺的
「API 密钥」页只有一个 MCP 授权开关 + 一句说明，**地址从没露过面**：平台级入口 `/ai/mcp`
界面上查不到，开关拨开也不知道该往客户端里填什么。

### 落地（`SettingsPage.vue`，纯新增 55 行）
- 「如何使用」里新增「MCP 接入点（平台级）」：地址 + 一键复制 + 可粘贴的 `mcp.json`，
  凭据头用真 Key 前缀 + `...`，让人一眼知道替换哪一段。
- 四条边界（都是读码/真机核出来的）：
  ① 该 Key 要打开「MCP」开关；
  ② 管理员总开关 `mcp.server.enabled` **同时约束平台级与 per-agent 两类端点**
     （`handle` 与 `handlePlatform` 两处都先查 `enabled()`）；
  ③ **对外域名访问必须把前端域名填进 `mcp.server.allowedOrigins`**，否则浏览器型客户端 403
     （无 Origin 的本地进程客户端不受影响）——不写出来，线上用户接不通且看不出原因；
  ④ 与 `/ai/mcp/{token}` 的区分（凭据是分享 token，不是 Key）。
- **刻意不加「有 Key 才显示」的门**：地址与有没有 Key 无关，而还没建 Key 的人恰恰最该先看清
  "接哪、怎么接"。这条是验证时被真数据逼出来的——库里 0 个 Key，加了门就整块藏没。

### 验证
新增 `web/scripts/check-mcp-entry.cjs`（真浏览器 + 真后端，非 mock）：admin 登录取真令牌 →
设置页 → **高级设置**（`apiKey` 面板不在 `corePaths`，基础模式看不到）→ API 密钥 →
展开「如何使用」，**16 项全过**；`npm run check` 六套静态校验全过；截图目视确认排版。

> 踩坑记录：`innerText` 在 `<strong>` 边界会插空白，断言「MCP 开关」永远不匹配——
> 文案断言别跨内联标签。这条写进脚本注释，防下一个人重踩。

## 四、D3 · 集成指南 + 端点发现 + 审计（**前两项已落地 `a8743c4`；第三项改判为独立小刀**）

### 已落地：集成指南（新增内置手册《15-对接与集成》，157 行）

**为什么这是本卡最高性价比的一项**：仓库里此前**没有任何面向外部集成方的材料**——手册 09
只有 31 行且站在平台使用者视角，Swagger 生产默认关（`SPRINGDOC_ENABLED=false`），对外等于没有契约。
写它是纯文档成本，却直接决定"别人接不接得上"。放进手册是因为手册**随包分发并进官方内置库**
（`ManualSeedService.loadBundledFiles()` 扫 classpath），是唯一能送达所有实例的载体。

写进去的都是核过的实现细节，不是宣传话：无状态 Streamable HTTP 无需 initialize；
`wenqu_ask` 返回可读来源清单 + 机器可读 `sources`（`origin=TOOL/WEB/MCP`、上限 20、超出写明）；
per-agent「分享不存在/已停用/未开 MCP/token 无效一律 404」是刻意的不泄露存在性；
工作流失败不抛 500 而返回 `status=failed` 的 run、含人工审核节点一律 400；
限频与网页共用「问答」配额按 IP 优先；等待超时默认 180s 且 `tools/call` 无进度反馈 ⇒ 复杂问题要拆小；
对外域名必须配 `allowedOrigins` 否则浏览器型客户端 403。

> **两处先写错、核正后留痕**：`/ai/mcp` 路径上同时挂了 GET（不是 405）；per-agent 凭据无效是 404 而非 401。

### 已落地：`wenqu_list_agents` 标出已开放独立端点的智能体

平台级入口能问任何智能体（`wenqu_ask`+`agentId`），但想让某个智能体**单独**接进客户端，
得去它的「公开发布 → MCP 端点」拿地址——此前这一步只能靠人在网页上翻，协议里看不到。
现在已开放的会多一个 `mcpEndpoint` 字段（只标 `mcpEnabled=1 且未停用`，否则等于给死链）。

### 立项判断修正一：`tools/list` 本来就有（不做）

立项时写"平台级入口缺 `tools/list` 自省"。**核实后不成立**：SDK 从 `.tools(platformTools())`
自动生成 `tools/list`，四个元工具本来就能被发现；per-agent 端点同理。真正的发现缺口是
"哪个智能体另有独立端点"，已由上面那条补掉。

### 立项判断修正二：审计那项改判为独立小刀

立项时写"`McpCallLog` 没有提问文本，导致只知被调用了一次"。**核实后这句要拆成两半**：

- **内容早就在库里**：MCP 两端点都走 `chatService.chat`，主链路在
  `AgentscopeChatEngine:2225` 的 `persist && realAnswer` 分支调
  `qaLog.logAsync(sessionId, question, …)`——所以**每个 MCP 调用都已在 `c_ai_qa_log`
  留下一行（含提问原文、sessionId、agentId）**。
- **缺的只是一根关联键**：审计表 `c_ai_mcp_call_log` 没有 `session_id`（库里 12 列已核），
  所以从审计行回不到那一问。

**为什么不当场做**：它要 (a) 加列 + `SchemaMigrator` 自愈、(b) 改 `audit(...)` 签名把 sessionId
从 body 带出来（两处 body 各自持有 sid，不做 holder 就得改签名）、(c) 在**运行中的真库**上执行
ALTER，并遵守"启动期动数据的代码放 `com.wenqu.ai.startup`、不许调模型"的铁律。三件都不难，
但它是**数据库变更**，与本卡其余项性质不同；在有并行会话在飞时仓促落一个线上迁移不划算。
**建议单独立项**，并且刻意**不**把提问原文复制进审计表（那是用户内容，复制会制造一份保留期与
问答台账冲突的副本）。

### 判据（已达成两项）
把指南发给一个没接触过问渠的集成方，他能自己接通并拿到带来源的回答。

## 五、明确不做（挡住范围膨胀）

| 不做 | 理由 |
|---|---|
| MCP `resources` / `prompts` | Claude Desktop 能浏览知识库文档确实更漂亮，但外部客户端如今 90% 的用法是 tools。留 P2 |
| stdio 传输 | 给 Claude Desktop 本地起进程用。`mcp-remote` 这类桥接是免费的，投入产出不划算 |
| 游客访问统计 / 分享页工具状态 | 分享页已可用，属 P1 打磨，不是本卡该碰的 |
| 改 `mcp.server.enabled` 默认值 | 对外暴露能力默认关是**安全决策**，保留；改由 D2 的"开通引导"解决可发现性 |

## 六、待定项

- **超时对客户端不透明**：`mcp.server.timeoutMs` 服务端可配（默认见 `ConfigDefaults`），但客户端
  不知道上限；MCP 的 `tools/call` 是请求-响应语义，**没有进度反馈**——长问答在外部表现为"卡住"，
  与网页的流式体验落差最大。**是否要在工具描述里写明超时上限**（让调用方自己决定要不要拆问题），
  留待 D3 一并定。

## 七、关联

- 定位稿：`docs/positioning-2026q4.md` §四-2（差异化分层）、§六-D3（下一步投哪边）
- 审计与口径：`McpServerService`（756 行）、`SessionShareService.projectMessages`（外发字段白名单先例）
- 上游设计：`docs/agent-runtime-gap-2026q4.md` §五「智能体即服务 · 多租户长任务治理」
