# 问渠 MCP 功能建设与完善方案

> 结论先行：问渠的 MCP 已是**双向成型**——客户端侧（接入外部 MCP Server，工具注册进问答）与
> 服务端侧（把智能体/平台能力暴露为 MCP 端点）都有完整实现。当前最大的缺口在**客户端侧的
> 接入能力**：登记一个服务只有名称/地址/传输类型三样，**带凭据的官方 MCP 服务基本接不进来**。
> 本文按四个缺口给出分期建设方案（P1 鉴权+JSON 导入 → P2 工具清单缓存 → P3 出站审计 →
> P4 暂缓项），每期含改动点、涉及文件、风险与验收。
>
> **2026-10-23 复核修订**：P2 的根因描述、P3 的落库点、SDK customizer 签名三处原稿有误，已按
> 源码取证改正；§7 的五个决策点已按推荐口径落定。

---

## 1. 现状盘点（改造必须沿用的既有结论）

### 1.1 客户端侧（接入外部服务）

| 层 | 位置 | 现状 |
|---|---|---|
| 表 | `c_ai_user_mcp`（`UserMcp.java:21`） | `id / uid / name / url / type / enabled / tool_labels / createTime / updateTime`，按 uid 隔离，≤10 个/人 |
| 连接 | `McpClientService` | 按 uid 分池（`UserPool`）；streamable（默认）/ SSE 两种传输（`buildTransport` L708）；后台建连 + 退避重试（30/60/120/300/600s）+ 6h 空闲回收 |
| 消费 | `McpToolProvider.java:94`（问答）、`WorkflowEngine.java:1236`（工作流 mcp 节点） | 每次取工具：`awaitConnect` 收敛 → **逐服务同步 `listTools()`**（请求超时 60s）→ 包 `SyncMcpToolCallback` |
| 筛选 | `ToolAssemblyService.agentSelectionFor` | `agent.mcps` 列做智能体级「具体项」选择（null=全挂 / 空=不挂 / 非空=白名单） |
| 前端 | `McpPanel.vue` | 添加/编辑弹窗只有 **名称、服务地址、传输类型** 三个字段 + 测试连接（`/probe`）；状态轮询收敛 `connecting` |

**必须沿用的三条硬纪律**（`UserPool` 类注释）：① `synchronized (pool)` 只保护内存结构，
一切远程 IO（initialize / listTools / closeGracefully）锁外做；② 锁外回写必须校验代次（`gen`）
或比对 client 引用；③ MCP 故障绝不影响问答主链路（失败跳过 + 后台重试）。

### 1.2 服务端侧（对外暴露）

- per-agent 端点 `/ai/mcp/{token}`（token 即凭据，与游客分享同源）+ 平台级端点 `/ai/mcp`
  （API Key + 固定元工具 ×4），无状态 Streamable HTTP，Origin 校验、限频齐全；
- 入站审计 `c_ai_mcp_call_log`（`McpServerService.audit` L553 是唯一写点，五个工具 handler 全覆盖），
  管理端点 `/api/ai/mcp/audit/logs|summary` 已按 `channel/tool/success` 筛选。

### 1.3 SDK 能力确认（MCP Java SDK 0.18.3，`mcp-core`）

拆 sources jar 实证：

- `HttpClientStreamableHttpTransport.Builder`（L783）与 `HttpClientSseClientTransport.Builder`（L303）
  **均提供** `httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)`；
- 接口签名是 `customize(HttpRequest.Builder builder, String method, URI endpoint,
  @Nullable String body, McpTransportContext ctx)`——**第 4 参是 body，不是 userAgent**；
- customizer 覆盖 streamable 的 POST/GET/DELETE 与 SSE 的 GET/POST，是按请求注入 header 的现成落点；
- `HttpClient` 默认不跟随重定向（`Redirect.NEVER`），不存在「跳转到别的 host 仍带凭据」的泄露面。

**P1 无需升级 SDK、无需自写传输层。**

另需更正的 SDK 认知：`initialize` **不会**预热工具清单；`enableCallToolSchemaCaching`
（默认 false，我们未开启）只在 initialize 后拉一次 `listTools`，且只缓存 **outputSchema**，
不缓存工具清单。`listTools()` 每次都是真远程往返，且按 cursor 分页。

---

## 2. 缺口与分期总览

| 期 | 缺口 | 性质 | 改动面 |
|---|---|---|---|
| **P1** | 接入无鉴权配置 + 无 JSON 导入 | **能不能用**（带凭据的官方 MCP 全接不进来） | 中 |
| **P2** | 工具清单每轮远程拉取 | **好不好用**（问答每轮被远端 listTools 拖住） | 小 |
| **P3** | 出站调用无审计 | 可观测性（外部 MCP 工具调用缺跨会话统计面） | 小 |
| **P4** | stdio / OAuth / 其他 | 规范补全 | 暂缓（见 §6） |

---

## 3. P1 接入鉴权 + JSON 粘贴导入

### 3.1 数据模型

`c_ai_user_mcp` 加一列（`SchemaMigrator` 启动自动补列，零手工 DDL）：

```sql
`headers` TEXT DEFAULT NULL COMMENT '自定义请求头（JSON 对象，整块 RSA 加密存储，RSA: 前缀）'
```

- 存储格式：`{"Authorization":"Bearer xxx","X-Api-Key":"yyy"}`，**整块 JSON 序列化后走
  `ConfigCryptoService` RSA 加密**（沿用 `ModelRegistryService` 对 api_key 的既有做法）；
- `UserMcp.java` 加 `headers` 字段；**实体层只存密文**，加解密只在 service 层进出边界发生。

**加密失败的口径必须说清**：`ConfigCryptoService.encrypt()` 在密钥未初始化时是 fail-open
（明文返回 + error 日志），风险表不能写「库里只允许密文」。P1 采用与 API Key 一致的既有口径：
**密钥缺失时明文入库并 fail-loud 告警**；若要求硬保证，保存时断言 `isEncrypted` 并拒绝——
本期选前者（与全平台一致，避免 MCP 一处特例让用户保存不了）。

### 3.2 传输层注入（`McpClientService.buildTransport`）

两个 builder 分支都挂同一个 customizer：

```java
Map<String, String> hs = McpHeaders.parse(decrypt(headersJson)); // 解密失败/非法 JSON = 空 map + warn
McpSyncHttpClientRequestCustomizer customizer =
        (reqBuilder, method, uri, body, ctx) -> hs.forEach(reqBuilder::header);
// streamable: .httpRequestCustomizer(customizer)
// sse:        .httpRequestCustomizer(customizer)
```

- `connect()` / `probe()` 签名加 header 参数（probe 也要能带凭据测，否则「先测再存」对带凭据的服务失效）；
- **注入前丢弃危险键**（小写比对）：`host`、`content-length`、`connection`、`transfer-encoding`、
  `mcp-session-id`、`mcp-protocol-version`；
- **保存时就校验**（不要只在注入时过滤）：header 名须符合 RFC 7230 token 字符集、值禁止 CR/LF、
  最多 32 条、序列化后 ≤8KB——非法名会在 `HttpRequest` 构建时抛 `IllegalArgumentException`，
  用户只会看到一句莫名的连接失败；
- **headers 永不进日志**：现有失败日志只打 `rootMessage(e)`，天然不泄露；新增代码不得打印
  header 键值（键名可以，值一律不行）；
- **配置指纹要纳入 headers**：`fingerprintOf`（L688）当前只串 name/url/type/enabled，
  不完整。追加一段 headers 摘要（密文 SHA-256 前 16 位即可），保证任何写入路径都能触发重建。

### 3.3 脱敏与回显（对齐模型供应商口径）

- `/status`、编辑回显：headers **不下发明文**，返回 `{hasHeaders: true, headerKeys: ["Authorization"]}`；
- 编辑保存：前端 headers 表单**不动 = 不修改**，动了 = 整块替换；
- 删除凭据 = 显式清空（前端给「清除」按钮，请求带 `clearHeaders: true`）；
- **测试连接要用已存凭据**：编辑态下前端拿不到明文，`/probe` 增加可选 `serverId`——
  只校验是本人的记录，取库里的密文解密后使用；请求里显式带了 `headers` 时以请求为准
  （「先测再存」的新凭据优先）。

### 3.4 前端弹窗（`McpPanel.vue`）

- 三个现有字段保持不动；下方加 ghost 折叠区 **「认证与请求头（一般保持默认即可）」**
  （对齐 `OidcProvidersPage.vue` 的定稿形态：placeholder 只放短示例，解释进 `extra` 小字）；
- 新增态：KV 编辑行（键 + 值 + 删除行）+「添加一行」；常见预设快捷键
  （选 `Bearer Token` 自动填键名 `Authorization`、值前缀 `Bearer `）；
- 编辑态：只显示已保存的键名 chip +「清除」+「替换为新的请求头」开关；
  开关打开才出现空白 KV 行，保存时**整块替换**。不做「逐行留空保留原值」——
  掩码值回填不了，逐行合并只会让用户误以为改了一个、其实整块没变；
- JSON 导入：添加弹窗标题行加「粘贴 JSON 导入」入口 → textarea 粘贴
  Claude/Cursor 形态的 `{"mcpServers": {name: {url|command, headers, ...}}}` →
  本地解析预览（可导入 N 个 / 跳过 M 个并说明原因）→ 确认后**一次**批量导入。
  - 仅导入 `url` 为 http(s) 的条目；`command`（stdio）型跳过并写明「平台暂不支持本地进程型服务」；
  - `type` 优先取条目自带的 `type`（`http`/`streamable` → streamable，`sse` → sse），
    没有才按 `url` 是否以 `/sse` 结尾猜；
  - 预览要提前算好三条硬约束：**剩余配额（10 - 已有数）**、**重名**、**名称含 `/` 或超 40 字**；
  - 不能逐条调 `add`（每条都会 `reload` 一次整池，5 条就重建 5 次连接）。

### 3.5 批量导入端点

`POST /api/ai/mcp/servers/import`，body `{servers:[{name,url,type?,headers?}]}`：

- 逐条按单条 `add` 的校验执行（名称/地址/重名/配额），但**只 reload 一次**；
- 返回 `{added:[{id,name}], skipped:[{name,reason}]}`，部分成功是批量的固有语义；
- headers 同样整块 RSA 加密落库。

### 3.6 涉及文件

`schema.sql`、`UserMcp.java`、`McpHeaders.java`（新增：解析/校验/危险键/digest）、
`McpClientService.java`（connect/probe/buildTransport/fingerprint/serverStatuses）、
`McpController.java`（add/update/probe/import + 回显脱敏）、`McpPanel.vue`（表单 + 导入）、
`api.js`（probe/add/update/import 参数）。

### 3.7 风险

| 风险 | 处置 |
|---|---|
| 凭据明文落库 | 整块 RSA 加密；密钥缺失沿用既有 fail-open（明文 + fail-loud 日志），文档如实写明 |
| 凭据泄露到前端/日志 | /status 与编辑回显只给 `hasHeaders/headerKeys`；日志禁打值 |
| 加密密钥缺失/解密失败 | 解密失败按「无凭据」连接 + warn（连接会 401，状态可见），不抛异常打断建连 |
| 用户把受控头塞进 headers | 保存时校验 + 注入前按小写丢弃危险键 |
| 非法 header 名/值 | 保存即拒绝并给具体键名，不让异常拖到建连时才炸 |
| 导入把配额/重名/命名规则撞穿 | 预览先算、后端再验，均逐条给原因 |

### 3.8 验收

带 Bearer token 的本地 MCP 测试服务：测试连接（编辑态不重填凭据也能测）→ 添加 → 状态绿点 →
问答中模型实际调用其工具；库里该行 headers 为 `RSA:` 密文；编辑回显无明文；
JSON 粘贴导入含 1 个 stdio 条目时正确跳过并说明。

---

## 4. P2 工具清单缓存（问答链路提速）

### 4.1 问题

`toolCallbacks()`（`McpClientService` L144）每次被消费（每轮问答、每个工作流 mcp 节点执行）
都对每个已连接服务**同步发一次 `listTools()`**——远端响应 1~3.5s+（context7 实测），
远端挂起要吃满 60s 超时；`listTools()` 还会按 cursor 分页，慢服务可能多轮往返。
SDK 没有任何工具清单缓存（见 §1.3 的更正）。用户感知：**开几 MCP 服务后，每轮问答都有一段固定等待**。

### 4.2 方案：UserPool 层加 TTL 缓存 + 连接时预热

- `UserPool` 加 `Map<String, CachedTools>`（`name → 工具清单 + fetchedAt`）；
- **预热**：`connectOne` 本来就为「数工具数 / 触发中文名翻译」发一次 `listTools()`
  （`McpClientService.java:582`），把这次结果直接种进缓存——连接后的首轮不再多付一次远程；
- `toolCallbacks()` 先读缓存：命中且未过 TTL（**300s 常量**，`McpClientService` 内静态常量，
  不新增系统设置键）直接包 `SyncMcpToolCallback` 返回；
- **过期走 stale-while-revalidate**：立即返回旧清单，后台单飞刷新（每 server 同时只允许一个刷新任务），
  下一轮拿到新清单——避免每 5 分钟固定卡一轮 1~3.5s；
- 拉取失败且有过期缓存 → 降级用旧清单（stale-while-error），与「MCP 故障不影响主链路」同口径；
- 失效路径：配置重建（`detachPool` 随池清空）、`reload()`、TTL 到期、`connectOne` 写入新连接结果；
- 三个消费方中，问答与工作流**走缓存**；`/status` 的 `verifyOnline/withTools` 两档
  **保持现有远程语义不变**（职责就是「此刻真实状态/工具清单」），但拿到的结果顺手回填缓存。

### 4.3 涉及文件与风险

仅 `McpClientService.java`。

| 风险 | 处置 |
|---|---|
| 服务端加了工具，TTL 内看不到 | 300s 上限；「刷新」按钮触发 reload 强制失效；文档写明 |
| 过期缓存含已下线工具，模型调用报错 | 工具调用失败本就有当轮错误呈现（与远端抖动同径），可接受；不自动重试拉新 |
| 后台刷新与配置重建竞态 | 回写前比对 client 引用（沿用既有纪律），换代后的结果直接丢弃 |

### 4.4 验收

同一会话连续两轮问答，第二轮不再出现 `listTools` 远程请求（日志可证）；TTL 过期时本轮不阻塞
（旧清单先回、后台刷新）；`/status?verifyOnline=true` 仍是实时远程。

---

## 5. P3 出站调用审计

### 5.1 问题

入站（外部客户端调问渠端点）有 `c_ai_mcp_call_log` 全量审计；出站（模型调用用户接入的
外部 MCP 工具）只有当轮对话里的工具步展示，**没有跨会话的审计/统计面**——「谁在哪个
智能体里、调了哪个外部服务、成没成功、花了多久」无处可查。

### 5.2 方案：复用同表 + channel='outbound' + 在 toolCallbacks 内包装

- `c_ai_mcp_call_log` 直接复用，加一行 channel 值 `outbound`；
  - `owner_uid` 存服务归属人（出站时即调用者）；
  - `credential_ref` 存服务指代 `mcp:{uid}/{name}`（不存任何凭据）；
  - `agent_id` 存问答侧智能体（工作流节点没有智能体维度，留空）；
  - `tool_name` 存**前缀化工具名**（`getToolDefinition().name()`，与服务卡片/模型看到的一致）；
  - 参数与结果不落库（只落工具名/耗时/成败/错误摘要 ≤500 字），与入站口径一致；
- **落库点必须在 `McpClientService.toolCallbacks`**：
  - `McpToolProvider` 只覆盖问答，工作流直接调 `toolCallbacks`（`WorkflowEngine.java:1236`），
    在原稿推荐的 provider 层包装**漏掉工作流**；
  - 但普通 delegate 会踩工作流的裸名匹配：`WorkflowEngine.originalToolName` 靠
    `instanceof SyncMcpToolCallback`（L1274）。因此包装类必须是 **`SyncMcpToolCallback` 子类**
    （该类非 final），override `call(String, ToolContext)` 计时 + 落库后 `super.call(...)`，
    `call(String)` 由基类虚调用自动走到 override，且 `getOriginalToolName()` 原样保留；
- agentId 上下文：`ToolMountContext` 加 `agentId()`（默认空串）；实现点只有
  `ToolAssemblyService.mountContext` 一处（匿名类，`agent` 直接可取 id）；
  `McpToolProvider` 调 `toolCallbacks(uid, onlyServers, mc.agentId())`，工作流传 null；
- 落库实现抽一个小服务/静态 helper（与 `McpServerService.audit` 同口径：写失败只告警不外抛），
  避免两处复制截断逻辑；
- 前端：审计页渠道选项加「出站（外部工具调用）」；**渠道 chip 的映射也要改**
  （`SettingsPage.vue:296` 现在是二元三元表达式，outbound 会显示成「平台级」）。
- 表注释与字段注释、`McpCallLog` 类注释同步改为「双向审计」。

### 5.3 涉及文件与风险

`McpClientService.java`、`McpOutboundAuditCallback.java`（新增）、
`ToolMountContext.java`、`ToolAssemblyService.java`、`McpToolProvider.java`、
`SettingsPage.vue`、`schema.sql`/`McpCallLog.java`（注释）。

风险小：审计是旁路，写失败不影响调用；工作流侧 agent 为空是已知取舍（`WorkflowRunCtx` 无此维度）。

### 5.4 验收

问答里模型调用一次外部 MCP 工具后，审计表新增 `channel=outbound` 一行；
工作流 mcp 节点调用同样落一行；管理页按渠道筛出；汇总接口的 byTool 分组包含出站工具。

---

## 6. P4 暂缓项与理由

| 项 | 结论 | 理由 |
|---|---|---|
| **stdio 传输**（本地进程型 MCP） | **暂缓**，除非自部署出现强需求 | 服务端形态下要管子进程生命周期；「用户指定启动命令」等于开了远程代码执行面。远程 HTTP 型已覆盖主流场景 |
| **OAuth 2.1 授权流**（RFC 8414 + 动态客户端注册） | 暂缓 | 工作量大；P1 的静态 header 已覆盖 bearer token 型服务。待真实需求出现再立项 |
| per-agent 端点工具名可读性 | 顺手项，可并入 P1 | 中文名智能体退化成 `ask-agent-{id前8位}` 不可预测；可在公开发布面板加可选「MCP 工具名」（ASCII slug，留空走现规则） |
| tools/list_changed 订阅 | 观察 | P2 的 TTL 已覆盖变更感知；SDK 对该通知的客户端支持稳定后再评估 |

---

## 7. 已拍板决策点

1. **headers 加密粒度**：整块 JSON 一次加密（实现简单、回显脱敏一个动作）。
2. **编辑回显**：只给 `headerKeys`；不动=不修改、动了=整块替换、删除走显式「清除」。
3. **JSON 导入入口**：添加弹窗内页签式入口；后端走**批量导入端点**（单次 reload），
   预览先算配额/重名/命名合法性。
4. **P2 TTL**：300s 静态常量，不进系统设置页（避免新增配置三处对账）。
5. **P3 落库点**：`McpClientService.toolCallbacks` + `SyncMcpToolCallback` 子类
   （覆盖问答与工作流、保留裸名匹配）。

---

## 8. 分批实施建议

- **批次一（P1 后端）**：schema 列 + 实体 + 加密边界 + 注入/校验 + probe（含 serverId）+ 指纹 + 批量导入——可独立验证（curl 带头）；
- **批次二（P1 前端）**：弹窗折叠区 + 脱敏回显 + 测试连接带凭据 + JSON 导入；
- **批次三（P2）**：UserPool 缓存 + 连接时预热 + stale-while-revalidate + 日志验证；
- **批次四（P3）**：toolCallbacks 包装（子类）+ ToolMountContext.agentId + 审计页渠道；
- 每批独立提交，P1/P2/P3 之间无依赖，P2/P3 可并行。手册 `10-MCP与开放能力.md` 随 P1/P3 同步。
