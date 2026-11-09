# 问渠 MCP 功能建设与完善方案

> 结论先行：问渠的 MCP 已是**双向成型**——客户端侧（接入外部 MCP Server，工具注册进问答）与
> 服务端侧（把智能体/平台能力暴露为 MCP 端点）都有完整实现。当前最大的缺口在**客户端侧的
> 接入能力**：登记一个服务只有名称/地址/传输类型三样，**带凭据的官方 MCP 服务一个都接不进来**。
> 本文按四个缺口给出分期建设方案（P1 鉴权+JSON 导入 → P2 schema 缓存 → P3 出站审计 →
> P4 暂缓项），每期含改动点、涉及文件、风险与验收。

---

## 1. 现状盘点（改造必须沿用的既有结论）

### 1.1 客户端侧（接入外部服务）

| 层 | 位置 | 现状 |
|---|---|---|
| 表 | `c_ai_user_mcp`（`UserMcp.java`） | `id / uid / name / url / type / enabled / createTime / updateTime`，按 uid 隔离，≤10 个/人 |
| 连接 | `McpClientService` | 按 uid 分池（`UserPool`）；streamable（默认）/ SSE 两种传输（`buildTransport` L692）；后台建连 + 退避重试（30/60/120/300/600s）+ 6h 空闲回收 |
| 消费 | `McpToolProvider.java` L94（问答）、`WorkflowEngine.java` L1236（工作流 mcp 节点） | 每次取工具：`awaitConnect` 收敛 → **逐服务同步 `listTools()`**（请求超时 60s）→ 包 `SyncMcpToolCallback` |
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

`HttpClientStreamableHttpTransport.Builder` 与 `HttpClientSseClientTransport.Builder` **均提供**
`httpRequestCustomizer(McpSyncHttpClientRequestCustomizer)`——接口签名
`customize(HttpRequest.Builder, method, uri, userAgent, ctx)`，是按请求注入 header 的现成落点。
**P1 无需升级 SDK、无需自写传输层。**

---

## 2. 缺口与分期总览

| 期 | 缺口 | 性质 | 改动面 |
|---|---|---|---|
| **P1** | 接入无鉴权配置 + 无 JSON 导入 | **能不能用**（带凭据的官方 MCP 全接不进来） | 中 |
| **P2** | 工具 schema 每轮远程拉取 | **好不好用**（问答首轮被远端 listTools 拖住） | 小 |
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

### 3.2 传输层注入（`McpClientService.buildTransport`）

两个 builder 分支都挂同一个 customizer：

```java
Map<String, String> hs = parseHeaders(headersJson); // 解密 → 解析，失败 = 空 map + warn
McpSyncHttpClientRequestCustomizer customizer =
        (reqBuilder, method, uri, ua, ctx) -> hs.forEach(reqBuilder::header);
// streamable: .httpRequestCustomizer(customizer)
// sse:        .httpRequestCustomizer(customizer)
```

- `connect()` / `probe()` 签名加 `headersJson` 参数（probe 也要能带凭据测，否则「先测再存」
  对带凭据的服务失效）；
- **headers 永不进日志**：现有失败日志只打 `rootMessage(e)`，天然不泄露；新增代码不得打印
  header 键值（键名可以，值一律不行）。

### 3.3 脱敏与回显（对齐模型供应商口径）

- `/status`、编辑回显：headers **不下发明文**，返回 `{hasHeaders: true, headerKeys: ["Authorization"]}`；
- 编辑保存：前端 headers 表单**留空 = 不修改**，填了 = 整块替换；
- 删除凭据 = 显式清空（前端给「清除」按钮）。

### 3.4 前端弹窗（`McpPanel.vue`）

- 三个现有字段保持不动；下方加 ghost 折叠区 **「认证与请求头…一般保持默认即可」**
  （对齐 2026-10-23 认证源表单瘦身的定稿形态：placeholder 只放短示例，解释进 `extra` 小字）；
- 折叠区内：KV 编辑行（键 + 值 + 删除行）+「添加一行」；常见预设快捷键
  （选 `Bearer Token` 自动填键名 `Authorization`、值前缀 `Bearer `）；
- JSON 导入：弹窗标题行加「粘贴 JSON 导入」页签/按钮 → textarea 粘贴
  Claude/Cursor 形态的 `{"mcpServers": {name: {url|command, headers, ...}}}` 片段 →
  解析预览（可导入 N 个 / 跳过 M 个 stdio 型并说明原因）→ 确认后逐条走现有 `add` 接口。
  - 仅导入 `url` 为 http(s) 的条目；`command`（stdio）型跳过并在预览里写明
    「平台暂不支持本地进程型服务」；
  - `type` 猜测：url 以 `/sse` 结尾 → `sse`，否则 `streamable`；
  - 导入的 headers 同样整块 RSA 加密落库。

### 3.5 涉及文件

`schema.sql`、`UserMcp.java`、`McpClientService.java`（connect/probe/buildTransport）、
`McpController.java`（add/update/probe 透传 headers + 回显脱敏）、`McpPanel.vue`（表单 + 导入）、
`api.js`（probe 参数）。

### 3.6 风险

| 风险 | 处置 |
|---|---|
| 凭据明文落库 | 整块 RSA 加密；库里只允许 `RSA:` 前缀密文 |
| 凭据泄露到前端/日志 | /status 与编辑回显只给 `hasHeaders/headerKeys`；日志禁打值 |
| 加密密钥缺失/解密失败 | 解密失败按「无凭据」连接 + warn（连接会 401，状态可见），不抛异常打断建连 |
| 用户把 `Host/Content-Length` 等受控头塞进 headers | 注入前丢弃危险键（`host`、`content-length`、`connection`、`mcp-session-id`、`mcp-protocol-version`），小写比对 |

### 3.7 验收

带 Bearer token 的真实 MCP 服务（如官方 GitHub MCP 的 HTTP 端点或任一自建带鉴权服务）：
测试连接 → 添加 → 状态绿点 → 问答中模型实际调用其工具；库里该行 headers 为 `RSA:` 密文；
编辑回显无明文；JSON 粘贴导入含 1 个 stdio 条目时正确跳过并说明。

---

## 4. P2 工具 schema 缓存（问答链路提速）

### 4.1 问题

`toolCallbacks()`（`McpClientService` L139）每次被消费（每轮问答、每个工作流 mcp 节点执行）
都对每个已连接服务**同步发一次 `listTools()`**——远端响应 1~3.5s+（context7 实测），
远端挂起要吃满 60s 超时。SDK 仅在 initialize 阶段预热过一次工具清单，显式 `listTools()`
每次都是远程往返。用户感知：**开几 MCP 服务后，问答首轮「开始思考」前有一段固定等待**。

### 4.2 方案：UserPool 层加 TTL 缓存

- `UserPool` 加 `Map<String, CachedTools>`（`name → 工具清单 + fetchedAt`）；
- `toolCallbacks()` 先读缓存：命中且未过 TTL（默认 **300s**，配置键 `mcp.client.toolsCacheTtl`，
  0=禁用缓存保持现行为）直接包 `SyncMcpToolCallback` 返回；
- 未命中/过期才发 `listTools()`，成功后回写缓存；**失败时若有过期缓存则降级用旧清单**
  （stale-while-error：问答拿到略旧的工具列表，好过拿不到工具）；
- 失效路径：配置重建（`detachPool` 随池清空）、TTL 到期、`reload()`（清指纹即触发重建）；
- 三个消费方（问答 `McpToolProvider`、工作流 `WorkflowEngine`、`/status?withTools` 档）中，
  **前两者走缓存**；`/status` 的 `verifyOnline/withTools` 两档**保持现有远程语义不变**——
  它们的职责就是「此刻真实状态/工具清单」，显式选择付远程 IO 的代价。

### 4.3 涉及文件与风险

仅 `McpClientService.java` + `ConfigDefaults`（新配置键默认值）。

| 风险 | 处置 |
|---|---|
| 服务端加了工具，TTL 内看不到 | TTL 默认 5 分钟可接受；「刷新」按钮触发 reload 强制失效；文档写明 |
| 过期缓存含已下线工具，模型调用报错 | 工具调用失败本就有当轮错误呈现（与远端抖动同径），可接受；不自动重试拉新 |

### 4.4 验收

同一会话连续两轮问答，第二轮不再出现 `listTools` 远程请求（日志可证）；首轮问答等待时间
对比可测量下降；TTL 过期后恢复远程拉取。

---

## 5. P3 出站调用审计

### 5.1 问题

入站（外部客户端调问渠端点）有 `c_ai_mcp_call_log` 全量审计；出站（模型调用用户接入的
外部 MCP 工具）只有当轮对话里的工具步展示，**没有跨会话的审计/统计面**——「谁在哪个
智能体里、调了哪个外部服务、成没成功、花了多久」无处可查。

### 5.2 方案：复用同表 + channel='outbound' + 包装 ToolCallback

- `c_ai_mcp_call_log` 直接复用（字段语义对得上：`credential_ref` 存 `{uid}/{name}` 服务引用、
  `agent_id` 存调用方智能体、`tool_name` 存前缀化工具名），加一行 channel 值 `outbound`；
- 落库点：`McpToolProvider`（`agent/tool/provider/McpToolProvider.java` L94）把
  `SyncMcpToolCallback` 包一层**审计 delegate**（`call` 计时 → 放行 → 落一行），
  与 `McpServerService.audit` 同构（日志写失败只告警不外抛）；
- **参数与结果不落库**（只落工具名/耗时/成败/错误摘要 ≤500 字）——外部服务的业务数据
  不该进审计表，这条与入站审计口径一致；
- agentId 上下文：`ToolMountContext` 现无 agent 维度，加 `agentId()` 方法（装配处传入，
  默认空串兼容既有实现）；
- 前端：现有审计查询端点已支持 `channel` 筛选，渠道选项加「出站（外部工具调用）」。

### 5.3 涉及文件与风险

`McpToolProvider.java`、`ToolMountContext.java`（+ 两处装配调用点）、`McpPanel.vue` 或审计页
渠道选项；风险小：审计是旁路，写失败不影响调用。

### 5.4 验收

模型调用一次外部 MCP 工具后，审计表新增 `channel=outbound` 一行；管理页按渠道筛出；
汇总接口的 byTool 分组包含出站工具。

---

## 6. P4 暂缓项与理由

| 项 | 结论 | 理由 |
|---|---|---|
| **stdio 传输**（本地进程型 MCP） | **暂缓**，除非自部署出现强需求 | 服务端形态下要管子进程生命周期；「用户指定启动命令」等于开了远程代码执行面。远程 HTTP 型已覆盖主流场景 |
| **OAuth 2.1 授权流**（RFC 8414 + 动态客户端注册） | 暂缓 | 工作量大；P1 的静态 header 已覆盖 bearer token 型服务。待真实需求出现再立项 |
| per-agent 端点工具名可读性 | 顺手项，可并入 P1 | 中文名智能体退化成 `ask-agent-{id前8位}` 不可预测；可在公开发布面板加可选「MCP 工具名」（ASCII slug，留空走现规则） |
| tools/list_changed 订阅 | 观察 | P2 的 TTL 已覆盖变更感知；SDK 对该通知的客户端支持稳定后再评估 |

---

## 7. 待拍板决策点

1. **headers 加密粒度**：整块 JSON 一次加密（本文推荐：实现简单、回显脱敏一个动作）
   vs 逐 key 加密（对齐 provider 逐字段掩码，但 headers 是任意 map，逐 key 处理繁琐）。
2. **编辑回显**：掩码显示（只给 headerKeys，留空=不修改，本文推荐）vs 解密回填明文。
3. **JSON 导入入口**：添加弹窗内页签（本文推荐，与「先测再存」同一容器）vs 页头独立「导入」按钮。
4. **P2 TTL 默认值**：300s（本文推荐）？是否需要暴露成系统设置项（`mcp.client.toolsCacheTtl`），
   还是只做 `ConfigDefaults` 常量。
5. **P3 落库点**：包装 ToolCallback（本文推荐，一处包住问答与工作流两路）vs 在
   `McpClientService.toolCallbacks` 内包（工作流路径复用同一函数，也能盖住，但语义上
   审计挂在装配层更干净）。

---

## 8. 分批实施建议

- **批次一（P1 后端）**：schema 列 + 实体 + 加解密边界 + 注入 + probe——可独立验证（curl 带头）；
- **批次二（P1 前端）**：弹窗折叠区 + 脱敏回显 + JSON 导入；
- **批次三（P2）**：UserPool 缓存 + 配置键 + 日志验证；
- **批次四（P3）**：ToolMountContext.agentId + 审计 delegate + 前端渠道选项；
- 每批独立提交，P1/P2/P3 之间无依赖，P2/P3 可并行。未实施前本文档为唯一口径，
  决策点（§7）拍板后动工。
