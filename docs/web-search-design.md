# 联网搜索落地方案

> 目标：给问渠加联网搜索能力。
> **状态：已实现（一期）** —— 本文保留为设计说明与实现依据，改动清单见 §6/§8，行号可能随后续提交漂移。

**三项决策结果**：服务商三家全实现可配置切换（tavily / bocha / generic）；审批做成配置项 `webSearch.requireApproval`，**默认自动执行**；generic（自建 SearXNG）包含在一期内。

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 是工具还是技能？ | **工具（`@Tool`）**。不是 skill，也不是第三类东西。 |
| 现状 | **零实现**，无半成品。 |
| 一期范围 | 模型自主调用的 `webSearch` 工具 + 结果进引用体系 + 审批与配额。 |
| 一期**不做** | 主链路自动兜底搜索、`webFetch`（抓正文）、搜索引擎 HTML 抓取。 |

---

## 1. 现状取证

逐条核过，联网搜索在六个层面全部缺失：

| 层面 | 证据 | 结论 |
|---|---|---|
| 工具装配 | `RagService.enabledToolCallbacks()`（RagService.java:1335-1444）6 个分支：知识检索 / 内置 / 技能 / 产物 / 沙盒 / MCP | 无搜索分支 |
| 配置 | `config-schema.json` 中 `panel=tool` 仅 6 个 key；`web.*` 仅 `web.refreshEnabled`、`web.refreshScanIntervalMs`（网页源定时刷新，非搜索） | 无配置项 |
| 智能体 | `c_ai_agent` 有 `tool_knowledge/tool_builtin/tool_skill/tool_artifact/tool_mcp`，无 `tool_websearch` | 无开关 |
| 提示词 | `AppProperties.systemPrompt` 工具清单是封闭枚举「计算器、日期、沙盒命令执行、文件读写、交付文件产物」 | 无口子 |
| 前端 | `ChatPage.vue:795` `TOOL_LABELS`、`AgentsPage.vue:404` `CAPS` | 无条目 |
| 全仓关键词 | `websearch / web_search / bing / tavily / serper / searx / 联网 / 实时信息` 仅 3 处命中，全是「百度千帆」模型供应商预设 | 无痕迹 |

**两个易误判项，都不是联网搜索**：

1. **网页导入**（`parser/WebParser.java`，`DocumentsPage.vue:619`）——把指定 URL 抓下来做成知识库文档（`fileType=url`），属于**入库**，不是检索时搜索。
2. **MCP 通道**——用户可在 `McpPanel.vue` 自登记搜索类 MCP Server，工具以 `w_q_*` 前缀合入（`McpClientService.java:85-95`）。这是**用户侧外部集成**，不是产品内置能力。

补充：默认提示词的兜底话术是「资料未覆盖时如实说明没有找到依据」——这是**主动声明无联网**，不是预留口子。

---

## 2. 形态决策

### 2.1 为什么只能是 tool，不能是 skill

skill 在本项目是**硬约束的纯文本**，没有执行分支：

```java
// SkillService.java:37
技能 = 一段可复用的做法说明（SKILL.md 文本 = YAML frontmatter + Markdown 正文）
// SkillService.java:48
技能只被当纯文本读取，任何脚本内容都不会被执行
```

skill 的唯一出口是 `readSkill` 工具把**文本**回给模型（`SkillTools.java:32-36`）。把联网搜索做成 skill，等于写一份 SOP 教模型怎么搜——但模型手上没有任何能发起 HTTP 请求的东西，这份文档只会让它更擅长**编造搜索结果**，比不做更危险。

> skill 回答「怎么做」，联网搜索需要的是「能做」。

skill 在本方案里的角色是**纪律层**（见 §9）：tool 提供能力，skill 约束用法。

### 2.2 一期只做「模型自主调用」，不做「主链路自动兜底」

| 模式 | 做法 | 一期 |
|---|---|---|
| A. 模型自主调用 | 挂 `webSearch` 工具，模型判断需要时自己调 | ✅ 采纳 |
| B. 主链路兜底 | 知识库未命中 → 自动联网搜 → 结果当 RAG 上下文注入 | ❌ 二期 |

B 不进一期的理由：

1. **会改写检索语义契约**。`retrieval.*` 现有的「未命中 → 如实说明没有依据」是问渠作为 RAG 产品的信任根基，B 会让它变成「未命中 → 网上随便找点东西」，且这个过程对用户不可见。
2. **污染引用体系**。B 注入的上下文会与主链路块共用 `[N]` 编号空间，但摘要质量远低于库内块，引用自检（`citationConsistencyCheck`）的剔除率会显著上升。
3. **延迟与成本不可控**。每轮固定 +1 次外网调用，即使问题与联网无关。

A 模式下「要不要搜」由模型决策、过程在工具卡片里可见、失败可降级，边界清晰。

### 2.3 一期不做 `webFetch`（抓取结果页正文）

理由：成本高、慢、反爬与合规风险，且**引用证据直接用搜索摘要就够**——摘要恰恰是服务商已经提炼过的、与查询最相关的片段，比整页正文更适合做 `[N]` 证据。二期若要支持「打开这个链接看详情」，再单做 `webFetch`，且必须先解决 SSRF 防护（见 §5.3）。

---

## 3. 服务商接入：抽象一层，不绑死一家

### 3.1 接口

```java
public interface WebSearchProvider {
    /** 服务商标识，与配置 webSearch.provider 对应 */
    String id();
    List<WebSearchResult> search(String query, int maxResults, int timeoutMs);
}

public record WebSearchResult(
        String title,        // 结果标题
        String url,          // 结果链接（引用可点达，溯源的锚点）
        String snippet,      // 摘要/正文片段（进引用体系的证据）
        String siteName,     // 站点名（展示用，从 host 兜底）
        String publishedAt,  // 发布时间（服务商不提供则 null）
        Double score         // 服务商相关性分（仅透出，不参与门限）
) {}
```

### 3.2 内置实现（按优先级）

| id | 服务商 | 取舍 |
|---|---|---|
| `tavily` | Tavily Search API | 面向 LLM 设计，返回已提炼的 `content` 片段，最适合做证据；需境外可达 + 付费 |
| `bocha` | 博查 AI 搜索 | 国内直连、合规友好；接入时以官方文档为准 |
| `generic` | 自建 SearXNG / 内部搜索中台 | 无外部依赖、零边际成本；需自部署，响应字段用 JSON path 映射 |

**不内置 HTML 抓取型**（必应/百度网页抓取）：结构易变、反爬对抗、合规风险，且不返回可直接做证据的摘要。

> 具体请求/响应字段**以各家官方文档为准**（可用 Context7 拉取最新版），不要按训练记忆写。

### 3.3 HTTP 客户端

现状：`com.wisesoft.ai` 包内**无任何显式 HTTP 客户端**（无 `WebClient`/`RestTemplate`/`java.net.http` 调用），外部 HTTP 全由 Spring AI / MCP 库内部完成。pom 有 `spring-boot-starter-web`（自带 `RestClient`/`RestTemplateBuilder`）。

方案：**`RestClient.builder().build()`**，独立设置连接/读取超时（默认 8s，对齐 `retrieval.searchTimeoutMs`），复用现有 `jsoup` 只做 HTML 剥标签（摘要清洗），不新增依赖。

---

## 4. 核心设计：搜索结果进引用体系

这是本方案最重要的部分。**联网搜索不上引用体系 = 一台编造引用机**，上线当天就会破坏问渠的可溯源性。

### 4.1 编号空间必须与主链路统一

现有机制：`KnowledgeRetrievalTool.SourceRegistrar`（KnowledgeRetrievalTool.java:32-44）把工具命中注册进 `sources` 并续编 `[N]`；`registerToolSource`（RagService.java:2450-2490）负责落 `ref = sources.size() + 1`。

联网搜索**复用同一编号空间**，新增平行的 `registerWebSource(url, title, snippet, siteName, publishedAt)`：

- 理由①：模型侧只有一个标注规则（「引用了资料就在句末标 [N]」，RagService.java:883），不该按来源类型分两套编号。
- 理由②：前端角标是**按 ref 直接索引数组**（`ChatPage.vue:1541` `sources?.[n-1]`），两套编号必然错位。
- 理由③：引用自检 `citationConsistencyCheck`（RagService.java:3371-3442）遍历 `sources`，统一编号才能一并校验。

去重键用 **URL 归一化**（去 `utm_*`、去尾斜杠、小写 host），同一 URL 二次注册复用首次 ref（与 `findExistingSourceRef` 同口径）。

### 4.2 来源结构扩展

`sources` 是 `List<Map<String,Object>>`，现有字段（`registerToolSource` 处）：`ref / knowledgeId / docId / fileName / title / snippet / images / origin / score / rerankScore`。

web 来源新增/复用：

| 字段 | 值 | 说明 |
|---|---|---|
| `ref` | 续编 | 同上 |
| `origin` | `"WEB"` | 与 `"TOOL"` 并列，前端据此分支 |
| `url` | 结果链接 | 新增，溯源锚点 |
| `siteName` | 站点名 | 新增，展示用（host 兜底） |
| `title` | 结果标题 | 复用 |
| `snippet` | 摘要 | 复用，**引用自检的证据来源** |
| `publishedAt` | 发布时间 | 新增，可空 |
| `score` | 服务商分 | 复用，仅透出 |
| `knowledgeId` / `docId` / `images` | 不填 | 区分于库内来源 |

### 4.3 引用自检天然可用（重要）

`citationConsistencyCheck` 取 `sources.get(n-1).get("snippet")` 作证据判定「句子是否被证据支撑」。web 来源的 `snippet` 就是服务商返回的摘要，**无需改校验逻辑即可对联网引用生效**——超出证据范围的联网断言同样会被剔除编号。

运行时机：`RagService.java:2158` 在 done 前执行（需 `chat.citationCheckEnabled` 且 `sources` 非空）。

### 4.4 图片体系不介入

`images` 是库内文档的图片签名体系（`imageUrlSigner.signSourceImages`，RagService.java:2273），web 来源不参与编号。避免引入外链图片带来的失效与安全问题。

### 4.5 正文截断

单条 `snippet` 截断 600 字符（与 `KnowledgeRetrievalTool.MAX_CONTENT_CHARS` 同口径），单轮最多 5 条（与 `tool.knowledgeRetrieval.maxHits` 对齐）。

---

## 5. 安全边界

### 5.1 纳入 `sensitiveTools`（受审批模式管辖）

现状：只有沙盒（RagService.java:1396）与 MCP（1428）进 `sensitiveTools`；`toolApprovalMode=ask` 时执行前发 `approval_required` SSE 并阻塞等待（1486-1538），落 `c_ai_tool_approval`。

联网搜索**语义同级**：把用户输入（可能含内部信息）发到外网 + 产生外部计费。**必须进 `sensitiveTools`**，与沙盒/MCP 一起受 `tool_approval_mode` 管辖。

### 5.2 游客会话不开放

`st.guestMode` 分支（RagService.java:1346-1362）只保留知识检索与内置工具，注释明确「有外部副作用/个人资产类能力不对匿名访客暴露」。联网搜索会烧平台配额，**归入不开放**，与沙盒/MCP 同口径。

### 5.3 SSRF 防护（二期 `webFetch` 时必须有；一期仅保留接口校验）

一期只调固定服务商 endpoint，SSRF 面很小，但仍需：

- 服务商 `baseUrl` 仅管理员可配，且校验 scheme 为 `https`（防明文泄露 query 与 key）；
- `webFetch`（二期）必须做 DNS 预解析 + 内网/回环/链路本地地址黑名单（`10/8`、`172.16/12`、`192.168/16`、`127/8`、`169.254/16`），跟随重定向时**逐跳校验**。

### 5.4 配额与审计

| 项 | 值 | 说明 |
|---|---|---|
| 单轮搜索次数上限 | `webSearch.maxCallsPerTurn`，默认 2 | 与 `maxToolSteps` 分开计（步数上限是全局的） |
| 单次结果条数 | `webSearch.maxResults`，默认 5 | |
| 超时 | `webSearch.timeoutMs`，默认 8000 | 超时按失败降级，不阻塞 |
| 审计 | 复用 `recordToolStatus` → `c_ai_message.tool_calls` | 入参/摘要/耗时已有链路，无需新表 |

---

## 6. 后端改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `service/websearch/WebSearchProvider.java`（新） | 接口 + `WebSearchResult` record |
| 2 | `service/websearch/TavilySearchProvider.java` 等（新） | 各服务商实现，按 `webSearch.provider` 选择 |
| 3 | `service/WebSearchTools.java`（新） | `@Tool webSearch(query, maxResults)`；内部注册来源、截断、失败返回可理解错误串 |
| 4 | `service/RagService.java:1335` `enabledToolCallbacks` | 新增 `webSearch` 装配分支 + `sensitiveTools.add(name)`；**游客分支不改**（天然不开放） |
| 5 | `service/RagService.java` `AnswerStreamState` | 新增 `registerWebSource(...)`（与 `registerToolSource` 平行，同一 `synchronized (sources)` 块） |
| 6 | `service/RagService.java:1555` 附近 | `WebSearchTools.setSourceRegistrar(st::registerWebSource)` + `finally` 清理（与 `KnowledgeRetrievalTool` 同 ThreadLocal 范式） |
| 7 | `service/ConfigService.java:375` 附近 `defaults()` | 新增 `webSearch.*` 默认值（**只 insert 不 update**，存量库需手工 UPDATE + Redis publish `ai:config:changed`） |
| 8 | `resources/config-schema.json` | 新增 `webSearch` panel 与字段（见 §7） |
| 9 | `resources/schema.sql:349` 附近 | `c_ai_agent` 加 `tool_websearch INT DEFAULT NULL`（三态：1/0/NULL=继承）；SchemaMigrator 会自动补列 |
| 10 | `model/Agent.java:70` 附近 | 加 `toolWebsearch` 字段 |
| 11 | `service/AgentService.java:195/282` | 保存与三态解析（照 `toolArtifact` 同款） |
| 12 | `service/RagService.java:881-893` 规则段 | 补一条：联网来源与知识库来源同用 `[N]`，不得因来自联网就不标 |
| 13 | `config/AppProperties.java:34` | 提示词工具清单枚举补「联网搜索」 |
| 14 | `resources/skills/web-search-usage/SKILL.md`（新） | 纪律层技能（见 §9） |
| 15 | `service/RagService.java` 降级 | 服务不可用/未配置/超时 → `addDegradation(st.degradations, st.degradedCodes, "webSearchUnavailable", ...)` fail-loud |

**关于 6 的线程模型**：`KnowledgeRetrievalTool` 用 `ThreadLocal<SourceRegistrar>`，因为「Spring AI 同步执行工具回调」，用完即清（KnowledgeRetrievalTool.java:46-47, 1555-1570）。`WebSearchTools` 必须**同样用 ThreadLocal**，不能图省事用静态字段——否则跨会话串号。

---

## 7. 配置清单

新增 panel `webSearch`（设置页从 `config-schema.json` 拉取 `PANELS` 渲染并支持 `applyServerSchema`，**前端无需改结构**）。

> 注意两条既有约定：
> - `group` 必须等于 `backendKey` 首段前缀，否则保存被静默丢弃、回显恒默认；
> - `*.apiKey` 后缀自动走 RSA 加密入库（`ConfigService.isSensitiveKey`，ConfigService.java:538），**命名必须是 `webSearch.apiKey`**。

```json
{"backendKey": "webSearch.enabled",           "panel": "webSearch", "section": 0, "group": "webSearch", "key": "enabled",            "path": "webSearch.enabled",           "label": "总开关",           "type": "switch",   "def": false, "vif": "tool.enabled", "tier": 2, "note": "联网搜索依赖工具总开关；关闭后模型无联网能力"}
{"backendKey": "webSearch.provider",          "panel": "webSearch", "section": 0, "group": "webSearch", "key": "provider",           "path": "webSearch.provider",          "label": "服务商",           "type": "select",   "def": "tavily", "options": ["tavily","bocha","generic"], "vif": "webSearch.enabled", "tier": 2}
{"backendKey": "webSearch.baseUrl",           "panel": "webSearch", "section": 0, "group": "webSearch", "key": "baseUrl",            "path": "webSearch.baseUrl",           "label": "服务地址",         "type": "text",     "def": "",     "width": 360, "ph": "留空用服务商默认地址；generic 必填", "vif": "webSearch.enabled", "tier": 3}
{"backendKey": "webSearch.apiKey",            "panel": "webSearch", "section": 0, "group": "webSearch", "key": "apiKey",             "path": "webSearch.apiKey",            "label": "服务 Key",        "type": "password", "def": "",     "width": 320, "vif": "webSearch.enabled", "tier": 2, "note": "RSA 加密入库"}
{"backendKey": "webSearch.maxResults",        "panel": "webSearch", "section": 1, "group": "webSearch", "key": "maxResults",         "path": "webSearch.maxResults",        "label": "单次结果条数",     "type": "number",   "def": 5,  "min": 1, "max": 10, "vif": "webSearch.enabled", "tier": 2}
{"backendKey": "webSearch.maxCallsPerTurn",   "panel": "webSearch", "section": 1, "group": "webSearch", "key": "maxCallsPerTurn",    "path": "webSearch.maxCallsPerTurn",   "label": "单轮搜索次数上限", "type": "number",   "def": 2,  "min": 1, "max": 5,  "vif": "webSearch.enabled", "tier": 2}
{"backendKey": "webSearch.timeoutMs",         "panel": "webSearch", "section": 1, "group": "webSearch", "key": "timeoutMs",          "path": "webSearch.timeoutMs",         "label": "单次超时(ms)",     "type": "number",   "def": 8000, "min": 1000, "max": 30000, "vif": "webSearch.enabled", "tier": 3}
{"backendKey": "webSearch.snippetChars",      "panel": "webSearch", "section": 1, "group": "webSearch", "key": "snippetChars",       "path": "webSearch.snippetChars",      "label": "单条摘要上限(字符)", "type": "number", "def": 600, "min": 200, "max": 2000, "vif": "webSearch.enabled", "tier": 3}
```

`ConfigService.defaults()` 必须**同名同默认值**播种（只 insert 不 update）。

---

## 8. 前端改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `views/ChatPage.vue:795` `TOOL_LABELS` | 加 `webSearch: '联网搜索'` |
| 2 | `views/AgentsPage.vue:404` `CAPS` | 加一张卡片 `{ key:'toolWebsearch', label:'联网搜索', path:['webSearch','enabled'], gate:['tool','enabled'] }` |
| 3 | `views/ChatPage.vue:1498` `openSource` | **`origin === 'WEB'` 分支**：不调 `getKnowledgeDetail`（`knowledgeId` 为空必然失败），改为展示 `siteName + title + snippet` + 「打开原网页」外链 |
| 4 | `views/ChatPage.vue:1536-1556` `showRefTip` | `fileName` 对 WEB 来源取 `siteName`；score 取服务商分并标注来源类型 |
| 5 | 来源面板 `ChatPage.vue:248` | WEB 来源条目渲染站点名 + 外链图标 |
| 6 | `views/exportMd.js:68/107` | 导出时 WEB 来源输出 `- [标题](url)` 而非文档名 |
| 7 | `views/SharedSessionPage.vue:24-26` | 分享页来源渲染同步分支（否则分享出去的联网回答溯源是空的） |

> 3 是**必做**：`openSource` 现在无条件 `getKnowledgeDetail(s.knowledgeId)`，WEB 来源会静默失败只剩 snippet（catch 里空吞），用户点角标得到空白弹窗。

---

## 9. 内置 skill：纪律层

`src/main/resources/skills/web-search-usage/SKILL.md`，frontmatter `description` 触发场景：知识库无依据且问题涉及时效/外部事实。

正文要点（tool 提供能力，skill 约束用法）：

1. **什么时候搜**：知识库明确没有、且问题依赖时效信息（价格/版本/政策/新闻）时才搜；知识库有的以知识库为准，不用联网结论覆盖。
2. **怎么标**：联网来源与知识库来源同用 `[N]`，不得因来自联网就不标，也不得标注工具未返回的编号。
3. **搜不到怎么办**：如实说「未检索到」，不得凭常识补写。
4. **不要做的事**：不引用搜索结果中的图片；不把搜索摘要当原文整段复述。

---

## 10. 验收用例

| # | 场景 | 期望 |
|---|---|---|
| 1 | `tool.enabled=true` + `webSearch.enabled=true`，问「今天上证指数收盘多少」（库内无） | 模型调 `webSearch`，工具卡片显示「联网搜索」，回答带 `[N]` |
| 2 | 点角标 / 来源条目 | 弹窗显示站点名 + 摘要 + 外链，**不是空白** |
| 3 | 引用自检开启，模型写了超出摘要的断言 | 该 `[N]` 被剔除，重编后无断号 |
| 4 | `tool_approval_mode=ask` 的智能体 | 执行前弹 `approval_required`；拒绝后模型收到「未批准」并继续作答，不重复调用 |
| 5 | 未配置 `webSearch.apiKey` | 工具返回明确错误 + degradation 提示，不静默无结果 |
| 6 | 超时/服务商 5xx | 同上，不阻塞整轮 |
| 7 | 游客分享会话 | 模型无 `webSearch` 工具（装配日志可验） |
| 8 | 智能体 `tool_websearch=0`（全局开） | 该智能体不挂搜索；`NULL` 时继承全局 |
| 9 | 历史回看（刷新会话） | sources 含 `origin=WEB`，角标仍可点、外链仍可达 |
| 10 | 导出 Markdown | WEB 来源输出为链接格式 |

---

## 11. 分期

| 期 | 内容 |
|---|---|
| **一期** | §3 抽象 + 一个服务商实现、§4 引用体系接入、§5.1/5.2 审批与游客收口、§6 后端清单、§7 配置、§8 前端清单、§9 skill |
| 二期 | `webFetch`（含 SSRF 防护）、主链路兜底搜索（模式 B）、`deepThink` 多路检索接联网子问题、结果短期缓存 |

---

## 12. 待你决策

1. **默认服务商**：`tavily`（境外、面向 LLM）还是 `bocha`（国内直连）？要我两家都实现、配置切换吗？
2. **审批默认口径**：联网搜索默认进 `sensitiveTools`（受 `ask` 管辖）还是当普通工具（默认自动执行）？我倾向前者，代价是每次搜索都要点确认。
3. **一期是否包含 `generic`（自建 SearXNG）**：自部署零边际成本，但要多写一个 JSON path 映射。
