# MCP 外部工具页升级方案

> 2026-11-07 立项。范围经用户拍板：UI/UX 打磨全做 + 功能升级三项（试调用 Playground / 个人调用统计 / 定时健康巡检）；「常用服务模板」不走轻量预设，另立项市场三期 MCP 目录（见 `plan-market-mcp-directory.md`，本方案不含其实施）。
>
> 本文档同时是**效果图制作输入**：§1 给设计语言与现状结构，§3 每项改动给交互规格。效果图要求见 §1.3。

---

## 1. 效果图制作输入

### 1.1 页面现状结构（真实截图描述）

- 应用壳：左侧 208px 白色侧边栏（问渠 logo、新建对话、智能体/市场/知识库/…导航、搜索会话、会话列表），主区顶部横向 Tab：模型供应商 / 智能体 / 技能 Skills / **MCP 外部工具**（选中，蓝色下划线）/ 工作流 / 定时任务。
- 页头一行：`共 N 个服务 · 已连接 N · 更于 HH:mm:ss` 灰色小字 + 说明文案「接入外部 MCP 服务，它的工具自动注册给模型，与内置工具一样可被调用」；右侧一组：搜索框（200px）→ 刷新图标钮 → 「全部重连」ghost 钮 → 「添加服务」主色钮 → 「批量管理」ghost 钮。
- 服务卡（`.app-card mcp-card`）：白底圆角 10px 阴影浅；第一行 `状态点(7px) + 名称(13px/500) + [平台 pill] + 状态文字(12px)`；第二行 `传输类型 pill + URL(等宽 11.5px, 截断)`；右侧操作 `开关(small) + 重连 + 编辑 + 删除`（平台卡无删除）。
- 已连接卡**点击整行展开工具清单**（行内、虚线上分隔）：`工具名 code 底 + 可选中文名 + 灰色描述 + 「改名/起中文名」小字钮`。
- 添加/编辑弹窗（580px）：名称 / 服务地址 / 传输类型 radio（streamable 推荐 · SSE）/ 折叠「认证与请求头」（已存键名 chips + 替换/清除 + 键值行编辑 + Bearer/X-Api-Key 预设）/ 底部「测试连接」ghost + 「保存」主色；测试结果内联展示（成功列出工具清单）。已有「粘贴 JSON 导入」独立弹窗（Claude/Cursor mcpServers 片段、解析预览、逐条跳过原因）。

### 1.2 设计语言速查（真实 token，出自 `web/src/views/app.css`）

| token | 值 | 用途 |
|---|---|---|
| `--app-bg` | `#f7f8fa` | 页面底 |
| `--app-panel` | `#ffffff` | 卡片/面板底 |
| `--app-panel-2` | `#fafbfc` | 次级底（表头/hover/内嵌块） |
| `--app-border` / `-strong` | `#e6e9ed` / `#d8dde3` | 常规/强调描边 |
| `--app-text` / `-2` / `-3` | `#1f2329` / `#5f6570` / `#98a0aa` | 主/次/弱文本 |
| `--app-accent`（hover） | `#2e6be6`（`#1f5bd8`） | 主色 |
| `--app-accent-weak` | `#eef2f7` | 主色弱底（选中/hover） |
| `--app-danger` | `#d4552e`（weak 底 `#fff2f0`） | 失败/危险 |
| `--app-ok` | `#3b8f4e`（weak 底 `#eaf5ec`） | 成功/已连接 |
| `--app-warn` | `#c77c14`（weak 底 `#faf3e6`） | 连接中/警示 |
| 圆角 | sm 6 / md 10 / lg 14 px | 控件→卡片 |
| 控件高 | sm 26 / md 32 / lg 38 px | 按钮/输入 |
| 字号 | xs 11 / sm 12 / md 13 / lg 15 / xl 17 px | 正文 13 |
| 阴影 | `0 1px 2px rgba(16,24,40,.04)` 起 | 卡片用 sm 级 |
| 间距 | 4 / 8 / 12 / 16 / 20 / 28 | — |

组件基调：ant-design-vue，克制扁平、细描边、浅阴影、无重底色块；按钮用现有 `.app-btn` / `.app-link-btn` 体系。

### 1.3 效果图要求（用户已反馈第一版「太闹」，必须遵守）

1. **不加任何「新」角标/标注**——改动点用文档 §3 的文字说明，不画进 UI。
2. **默认状态 = 收起**：效果图主页里只展开 1 张失败卡（展示错误详情）+ 1 张已连接卡（展示工具与试调用入口），其余全部收起——平时页面与现状几乎一致。
3. **弱化一切新增元素**：统计入口是灰色小字（hover 变主色）不是徽标胶囊；「试一下」是灰色小字不是按钮；错误详情主体是「一行人话原因 + 一个折叠项」，不搞大色块横幅。
4. 需绘制：主页全景（6 卡覆盖 失败展开 / 工具展开 / 统计小字 / 连接中+凭据键名 / 已停用 / 平台）+ 4 弹层（试调用弹窗、统计抽屉、编辑弹窗、导出弹窗，规格见 §3）。

---

## 2. 现状与缺口（实现对照用）

**前端**（`web/src/views/McpPanel.vue`，736 行）：页头统计/搜索/刷新/全部重连/添加/批量（全选+启停+停用+删除，结果逐条汇报）；卡片区（启停/单重连/编辑/删除、connected 卡点击展开工具+中文名改名、连接中 2s 轮询收敛上限 90 发）；添加/编辑弹窗（probe 测试连接、认证头键值编辑 JWE 不回显、编辑态三支路：不动=保持/填写=整块替换/显式清除）；JSON 导入弹窗。**缺口**：错误状态文字 320px 截断直出 Java 原文；失败卡不可展开；`headerKeys` 后端已返回但前端未展示；无导出；URL 不自动识别类型；无试调用与统计入口。

**后端**（`McpController.java` / `McpClientService.java` 1117 行）：

- `/status`（serverStatuses 317-419）返回 `id/name/scope/ref/url/type/hasHeaders/headerKeys/enabled/state/connected/tools[name,label,description]/toolCount`；**tools 不含 inputSchema**；`connected` 粘性，仅 verifyOnline 时翻红；无定时巡检。
- 连接池按 uid 分池；失败退避重试 `RETRY_DELAYS_SECONDS = {30,60,120,300,600}`（scheduleRetry）；空闲池 6h 回收；错误经 `rootMessage` 取最深层 cause message。
- `toolCallbacks(uid, onlyServers, agentId)`（182 行）返回已包 `McpOutboundAuditCallback` 的 callbacks——复用它=审计白得；工作流 `WorkflowEngine.mcpBody` 有「按裸名匹配 callback」先例。
- 审计表 `c_ai_mcp_call_log`（channel=outbound、owner_uid、credential_ref=`mcp:{uid}/{服务名}`、tool_name 带前缀 `wen_qu*`、duration_ms、success、error_msg 截 500）；现有 `/audit/logs`、`/audit/summary` **仅管理员**（ApiKeysPanel 折叠块展示）。
- probe 测试连接已有；无单工具执行端点；无 per-tool schema 端点。
- 调度：`@EnableScheduling` 已开，先例 `DirectorySyncService:71`（60s 一跳内部节流）、`UserRoleService:274`。

---

## 3. 改动清单（交互规格）

### 3.1 失败卡可展开 + 错误详情

- 展开判据从 `s.connected` 放宽为 `s.connected || s.state.startsWith('failed:')`；展开箭头提示照旧。
- 展开内容（自上而下）：
  1. **人话原因**一行（12.5px，`--app-danger` 文字，无底色横幅）——按 §3.1-a 映射表把 Java 原文翻译；映射不到时显示原文截 120 字；
  2. **折叠项**「错误原文与排查建议」（`a-collapse ghost` 或原生 details 风格，summary 12px `--app-text2`）内含：等宽原文块（`--app-code-bg` 底、可复制）+ 3 条以内排查建议列表（12px `--app-text2`）；
  3. **底部操作行**：左侧灰色小字退避提示「第 N 次退避重试 · 约 X 秒后自动再试」（数据见 §5.4，可选）；右侧「重连」ghost 小钮 + 「测试连接」ghost 小钮（用该服务当前配置直接 probe，结果 toast/内联）。
- 状态行截断保留（hover title 已有全量）；点开即见全文，复制按钮满足排障需要。

**§3.1-a 错误文案映射表**（对 `state` 去 `failed:` 前缀后做子串匹配，大小写不敏感；命中即用人话，原文进折叠）：

| 原文子串 | 人话 | 建议第一条 |
|---|---|---|
| `header parser` / `Client failed to initialize` | 远端返回的不是有效的 MCP 响应——地址可能不对，或被网关/代理拦截 | 确认地址是 MCP 端点本体（streamable 默认路径 /mcp） |
| `timeout` / `TimeoutException` | 连接超时——网络不通或远端无响应 | 检查网络可达性，稍后用「测试连接」复测 |
| `terminated the handshake` / `SSL` / `certificate` | TLS 握手被终止——https 证书或端口不对 | 确认该地址是否应使用 http |
| `401` / `403` / `Unauthorized` | 鉴权未通过——检查认证请求头 | 在「编辑 → 认证与请求头」补/更新凭据 |
| `404` | 端点不存在——检查地址路径 | streamable 默认 /mcp、SSE 默认 /sse |
| `Connection refused` | 拒绝连接——服务未启动或端口不对 | 确认服务已启动、端口与地址一致 |
| `UnknownHost` | 域名无法解析 | 检查域名拼写与 DNS |

### 3.2 工具试调用 Playground

- **入口**：工具清单每行行尾加「试一下」小字钮（12px，`--app-text3`，hover `--app-accent`），与现有「改名」并列右侧。
- **弹窗**（660px，`a-modal` footer=null 自绘底栏）：
  - 头部：`试调用 · {工具显示名}` + 副行「服务：{服务名}」+ 右侧灰字「真实执行一次调用 · 计入审计与统计」。
  - 主体两栏 grid（左 1fr / 右 216px，窄屏叠放）：左=入参 JSON `a-textarea`（等宽 11.5px，占位给 `{}`），上方 label「入参（JSON）」；右=schema 提示面板（`--app-panel-2` 底圆角 8）：逐字段 `字段名(等宽) + 类型·必填/可选(11px, 必填用 --app-warn) + 描述(11.5px 灰)`，来自 §5.1 端点。
  - **结果区**（执行后出现，虚线上分隔）：元信息行「执行成功/执行失败」小胶囊（ok/danger weak 底）+ 「耗时 N ms」灰字 + 右侧「复制结果」小钮；下方等宽结果块（成功=返回 JSON pretty；失败=错误原文，`--app-danger` 文字），max-height 150px 内滚，超长尾部「…」+ 复制拿全量。
  - 底栏：关闭 ghost + 「执行试调用」主色（执行中 loading 文案「执行中…」禁双击）。
- 打开弹窗即拉 schema（§5.1）；失败则右栏显示「无法获取入参说明：{原因}」，左栏照常可填。
- 参数非 JSON / 数组 / 顶层数组时执行前置校验 toast，不发请求。
- 执行走 §5.2；与对话同通道、审计照落；结果**不持久化**（会话级展示）。

### 3.3 个人调用统计

- **页头入口**：页头统计行后追加灰色小字链「近 7 天调用 N 次（成功率 P%）」，hover 变主色，点击开抽屉；无任何调用时显示「近 7 天暂无调用」（同灰字，不可点或点了给空态抽屉）。
- **卡片入口**：已连接/启用卡第二行 URL 之后加灰色小字「近 7 天 N 次 · P%」（仅当该服务有调用记录），点击同开抽屉（并定位到该服务行，简单做：仅打开抽屉即可）。
- **抽屉**（`a-drawer` 460px 右侧，标题「近 7 天调用统计」+ 副行日期区间）：
  1. 三块汇总（并排小卡，`--app-panel-2` 底）：总调用 / 成功率 / 平均耗时；
  2. 「按服务」表：服务名（平台服务带「（平台）」）/ 调用 / 成功率 / 平均耗时；数字右对齐 tabular-nums；
  3. 「按工具（Top 20）」表：显示名（已起中文名优先）+ 原名灰字 / 调用 / 失败 / 平均耗时；
  4. 底部灰字注：「统计口径：本人在对话与工作流中的真实调用（试调用计入）；已删除服务的调用记录保留原样显示。」
- 数据 §5.3；工具名去 `wen_qu` 前缀逻辑与 `web/src/chat/projections.js` 的 `MCP_CLIENT_PREFIX` 同口径（前端本地实现小函数，不引 chat 模块）。

### 3.4 定时健康巡检（后端行为，前端零新增控件）

- 行为见 §5.4。用户可感知的变化只有：远端挂掉后状态**自动**翻红（不再粘绿），退避重试期间状态行可显示 §3.1 的退避提示（可选）；恢复后自动回绿。
- 文档口径：设置页 `mcp.patrolIntervalMs`（默认 300000，0=关）。

### 3.5 卡片信息补全

- **凭据键名**：第二行 URL 后渲染 `headerKeys` 为小 pill（等宽 11px，`--app-accent-weak` 底 `--app-accent` 字）；无凭据不渲染。
- **连接中**：状态点加呼吸动效（CSS opacity 动画，尊重 `prefers-reduced-motion`）；「重连」钮文案置灰「重连中…」（现状已有 disabled，补文案）。

### 3.6 添加/编辑弹窗升级

- **URL 失焦自动识别传输类型**：blur 时若用户未手动改过 type，按现有 `guessType` 逻辑（URL path 以 `/sse` 结尾→sse，否则 streamable）回填 radio 并在其下显示 12px `--app-ok` 提示「已按地址自动识别传输类型：streamable」；用户手动改过则不再自动覆盖（记 `typeTouched` 标记）。
- **传输类型说明**：radio 下方常驻 11.5px 灰字「streamable：标准 MCP 传输，默认端点 /mcp；SSE：旧版传输，默认端点 /sse」。
- **名称即时查重**：名称输入 blur / 提交前与现有 `servers` 列表查重（编辑态排除自身），重名时输入框下 12px `--app-danger` 提示「已存在同名服务」，拦截提交（后端 uk_uid_name 兜底不变）。

### 3.7 导出

- 页头「批量管理」左侧加「导出」ghost 钮（图标 download）。
- 点击开确认弹窗（470px）：文案「将把 N 个服务导出为 mcpServers JSON（Claude/Cursor 兼容格式），可直接粘贴回问渠或分享给同事。」+ 警示行（`--app-warn` 文字）「请求头的值不会导出：带凭据的服务导入后需重新填写请求头。」+ 底栏「取消」/「导出全部」主色。
- 导出实现：纯前端构造 `{ mcpServers: { name: { url, type } } }`（有凭据的服务加 `"_headersNote": "该服务在问渠中配置了请求头，导入后需重新填写"`——**不进** mcpServers 标准 key，放同级 `x-wenqu-notes` 或直接弹窗列名单，取简单者）；`Blob` + `a.download='wenqu-mcp-servers.json'` 下载。

### 3.8 批量重连

- 批量模式按钮组「启用/停用/删除」之间加「重连」ghost 钮；循环对选中项调现有单服务 reconnect，结果汇报复用 `reportBatch` 风格（全成 toast / 有失败 Modal 列原因）。

---

## 4. 后端改动规格

### 4.1 工具 schema 端点

```
GET /api/ai/mcp/servers/{id}/tools/{toolName}/schema
→ 200 { success, data: { name, label, description, inputSchema } }   // inputSchema 为 JSON Schema 对象原样
→ 404 服务不可见 / 工具不存在
```
- 实现：`McpClientService` 新增方法——从该用户池取已连 client（或 toolsCache）找 `McpSchema.Tool`；未连接时现场 `client.listTools()` 一次（复用 REQUEST_TIMEOUT）。
- 权限：`@Permission("mcp:read")`；平台行按 visible（全员可读），个人行限本人（复用 `McpController.visible()` 口径）。

### 4.2 工具试调用端点

```
POST /api/ai/mcp/servers/{id}/tools/{toolName}/invoke
body: { args: {...} }        // 对象，缺省 {}
→ 200 { success, data: { ok: true, resultText, durationMs } }        // resultText 截 50KB
→ 200 { success, data: { ok: false, error, durationMs } }            // 工具执行失败（业务失败不 500）
→ 4xx/5xx 参数非法 / 服务不可见 / 上一个试调用未结束
```
- 实现：`toolCallbacks(uid, Set.of(服务名), null)` 按裸名匹配（照 WorkflowEngine.mcpBody）→ `target.call(JSON.toJSONString(args))`；审计回调自动生效（channel=outbound、agentId 空）。工具名匹配失败=404 语义。
- **限频**：每 uid 并发 1（`ConcurrentHashMap<uid, Object>` 简单互斥或 Semaphore(1)），占用中返回明确错误「上一个试调用还在执行，请稍候」。
- 权限：`@Permission("mcp:write")`；平台行 visible 即可试（与模型运行期口径一致），个人行限本人。
- 超时：沿用 `REQUEST_TIMEOUT`（60s）；HTTP 层无需加长。

### 4.3 个人调用统计端点

```
GET /api/ai/mcp/stats/self?days=7        // days 1..30，默认 7
→ 200 { success, data: {
    total, success, failed, avgMs,
    byServer: [ { server, calls, failed, avgMs } ],     // 按 credential_ref 分组，解析 mcp:{uid}/{name} 取 name
    byTool:   [ { tool, calls, failed, avgMs } ]        // 按 tool_name 分组 top20
} }
```
- SQL：`channel='outbound' AND owner_uid=? AND created_at>=?`；聚合查询用 **mapper 注解 `@Select`**（`McpCallLogMapper`），**不建 mapper XML**（XML 只扫 resources/mapper/** 有坑账）。idx_owner / idx_tool 索引现成。
- 权限：`@Permission("mcp:read")`，仅本人数据；管理员现有 `/audit/*` 不动。

### 4.4 定时健康巡检

- `McpClientService` 新增 `@Scheduled(fixedDelay = 60_000, initialDelay = 120_000)` 巡检跳（照 DirectorySyncService 先例），**内部节流**：距上次执行 ≥ `mcp.patrolIntervalMs` 才真正巡检；配置 0 = 永不执行。配置键三层口径：`ConfigDefaults` 常量 + `config-schema.json` 登记（设置页可见、可改）+ 无参 getter 兜底走 `defaults()`。
- 巡检逻辑（复用现有件）：
  1. 遍历**非空闲**池（复用 `evictIdlePools` 的 IDLE_EVICT 新近度判据）；
  2. 跳过 `connecting` / `disabled` 态服务；
  3. 对 `connected` 服务 `listTools()`：失败 → 状态翻 `failed:{原因}` + `scheduleRetry`（既有退避链）；成功 → `putCacheIfCurrent` 回填工具缓存；
  4. **锁纪律照抄 serverStatuses**：池锁内只取快照，远程 IO 在锁外，翻转回写时校验 client 引用未变。
- 退避提示数据（§3.1 可选项）：`UserPool` 增加 per-server `retryAttempt/nextRetryAtMillis`（内存），`/status` 输出 `retry: { attempt, nextRetryInSeconds } | null`；不做也不阻塞本期（降级为固定文案「失败后会自动按退避间隔重试」）。

### 4.5 不做的事（边界）

- 不改 `/status` 既有字段语义（新增字段向后兼容）；不建新表（**不动 GLOBAL_TABLES**）；不做 OAuth / stdio；不改审批策略；试调用不进「审批挂起」通道（个人面板直执行，审计已覆盖）。

---

## 5. 前端改动落点

| 文件 | 改动 |
|---|---|
| `web/src/views/McpPanel.vue` | 主战场：§3 全部交互；新增内联弹窗（试调用、导出）与 `a-drawer` 统计抽屉（若超 1500 行则抽 `McpInvokeModal.vue` / `McpStatsDrawer.vue` 两个子组件） |
| `web/src/api.js` | 新增 `getMcpToolSchema(id, tool)`、`invokeMcpTool(id, tool, args)`、`getMcpSelfStats(days)` |
| `web/src/views/app.css` | 无需改（复用既有 token/按钮体系；确需微调只加 MCP 页 scoped 样式） |

状态管理沿用组件内 ref；试调用/统计不进 pinia。

---

## 6. 受影响面回归清单（第一道闸盘点）

1. MCP 页既有操作全量：增删改 / 启停 / 单重连 / 全部重连 / 批量三件套 / JSON 导入 / probe 测试连接 / 工具中文名改名 / 连接中轮询收敛。
2. `/status` 共享消费方三处不回归：智能体编辑页 mcpOptions（`AgentsPage.vue:1652`）、工作流画布 mcp 节点工具列表（withTools 档）、发布弹窗游客 MCP 勾选（`myMcpServerChoices`）。
3. `ApiKeysPanel` 审计折叠块（管理员 `/audit/*`）不受统计端点影响。
4. 巡检与问答线并发：巡检不拖慢 `awaitConnect`（有界 1.5s）与 `toolCallbacks`；池锁临界区纪律不变。
5. 空闲回收 / 退避重试 / 配置指纹增量重连（reconcile）语义零变化。

## 7. 验证方案（真机）

1. 编译：IDEA 内置 maven（`-pl` 带 `-am`）；起 8091 jar + 临时 vite 栈（代理指 8091，起完核验指向）；8090 勿杀。
2. 试调用：deepwiki / 本地统计真实执行（成功 + 参数错 + 工具失败三路径）；查 `c_ai_mcp_call_log` 新增 outbound 行（credential_ref / duration / success 正确）；连点验证 per-uid 互斥文案。
3. 统计：真实调用数轮后核对 self 端点数字与分组；删除一个服务后确认其历史调用仍按名显示。
4. 巡检：`mcp.patrolIntervalMs` 临时调 10s——停本地统计容器→自动翻红→走退避；恢复容器→巡检回填翻绿；验证 0=关；调回 5min。
5. UI/UX 逐条真机走查：失败卡展开/复制/映射文案、URL 失焦识别、名称查重拦截、凭据键名 pill、导出文件内容与名单、批量重连汇报、统计小字与抽屉。
6. 回归清单 §6 逐条过；iab 验收按 hazards「假绿」节（以 DOM 为准、防过期合成帧）。
7. 提交后 `git show --stat` 核对未卷入并行 WIP；账本回写 commit hash。

## 8. 实施顺序建议

> **撞车检查（2026-11-07）**：账本「智能体对外能力」线（`plan-agent-http-tools-and-variables.md`，并行会话）后续批次会动开放能力域，与本方案同域不同面——本方案只动 MCP 出站管理面（McpController/McpClientService/面板 UI），实施前先看那条线进展，若其第一批已动 `McpController` 需先 rebase 口径。

1. **后端四件**：schema 端点 → invoke 端点（含限频）→ stats/self 端点 → 巡检 + 配置键（依赖最少的先合）。
2. **前端一版**：api.js → 弹窗与抽屉组件 → 卡片/页头接入（可按 3.6→3.5→3.7→3.8 小步提交）。
3. 联调 + §7 验证 + 账本回写。

> 提交纪律：用户要求时才提交；中文 conventional commit；只提自己的 hunk。
