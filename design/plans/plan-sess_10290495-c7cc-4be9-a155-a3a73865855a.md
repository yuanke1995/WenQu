# FormCopilot 自主闭环：建表能力 MCP 工具化 + 游客口子(分享页可控)

## Context

借款申请表复刻要 2 轮且第二轮靠用户手动催，根因是 FormCopilot 的模型在回合内是盲的：画布合并/校验在前端，结果传不回模型。用户已拍板：**打开游客链路 MCP 口子(优先做成分享页可控)+ 直接上方案二**——建表能力服务端化、暴露成 MCP 工具，让模型在问渠侧自主循环「读 draft→补丁→校验回声→再补丁」，前端只做镜像与最终上屏。目标体验：发一次，拿完整画布。

安全红线(保留)：**MCP 工具只操作 draft,永不直接建表/写 c_table**;物理 DDL 仍由用户点「保存」触发;游客 MCP 默认关闭，发布者按 server 粒度显式开启。

## 总体架构

```
用户发消息 → 前端先把画布 packForm() 推到 dynamic-form draft(同源 X-Token 认证)
→ compose 只带 draftId(不再每轮附 schema)
→ 模型(问渠游客轮)调 MCP 工具: form_get_draft / form_apply_patch / form_validate
   每次调用返回校验回声 → 模型自修 → 清单清空后收工
→ done 帧:前端从 dynamic-form 拉 draft 终稿 → 走现有 applyAnswer 管线上屏
→ 用户点保存 → 原有 /form/save 链路建表
MCP 工具不可用时:前端自动回落现有 artifact/form-json 通道(行为不变)
```

---

## P1 dynamic-form 后端：手写 MCP 端点 + draft store

约束：Java 8 + Boot 2.7.18 + fastjson + javax,MCP SDK(Java 17/Boot 3)离线也编不过 → **手写 MCP Streamable-HTTP(JSON-RPC 2.0),零新增依赖**(本地 ~/.m2 有 io.modelcontextprotocol 包但版本不兼容，弃用)。

**新增 `McpFormController`**(参考 FormController 的做法，路径 `POST /mcp/form`,独立于 /api)：
- JSON-RPC 方法:`initialize`(回 protocolVersion/capabilities/serverInfo)、`notifications/initialized`(202 空响应)、`tools/list`、`tools/call`。fastjson 组装报文，~300 行。
- 认证：请求头 `X-Service-Token` 静态比对(application-dev.yml 新键，如 `wenqu.mcp.service-token`),不走 FormJwtUtil(避免 JWT 过期运维)。
- 兼容性风险：问渠客户端 `HttpClientStreamableHttpTransport`(resumableStreams=false)对纯 JSON 响应的接受度——**实现第一步先写好 initialize/tools/list,用问渠设置页已有的 probe(McpClientService.probe:526-561)验通再往下**；若纯 JSON 不行，回退 SSE 响应形态(SseEmitter)。

**新增 `CopilotDraftStore`**(内存 `ConcurrentHashMap<draftId, {schema, updatedAt}>`,TTL 2h、上限 200 份;重启丢失可接受——前端每次发送前重推基线):
- HTTP 端点(给前端,走 X-Token):`PUT /api/form/copilot/draft/{id}`(基线推送/upsert)、`GET /api/form/copilot/draft/{id}`(拉终稿)。
- MCP 工具(对现有 service 薄封装,一行不改 TableParserServiceImpl):
  1. `form_get_draft {draftId}` → 紧凑 schema;
  2. `form_apply_patch {draftId, ops:[add/remove/replace]}` → 机械合并进 draft → `parserForm`+`validate`(TableParserServiceImpl:112-125,含表名正则+查重+列名去重)→ **回声只给摘要**:`{ok, errors[], summary:"新增12移除1替换2", issues[]}`,绝不回整份 schema;
  3. `form_validate {draftId}` → 全量校验 + 派生列预览 `columns:[{label,columnName,dataType}]`(AbstractWidgetParser.getTableColumn:21-46,让模型自查 name↔code 配对)。
- 入参预检(替 AI 挡 NPE):顶层必须有 `components` 数组(FormParserServiceImpl:65 缺键 NPE)、子表 children 带 position(FormParserServiceImpl:114-123);parserForm 全包 try/catch,结构化报错不 500。
- op 语义与前端 patchSchema.js 完全同形(replace 强制保旧 id),add 的新组件服务端补 uuid(与前端 fillMissingIds 同规则)。

## P2 wenqu：游客 MCP 口子(按 server 粒度,分享页可控)

照 guestImagesEnabled 模式端到端抄:
1. `c_ai_agent_share` 加列 `guest_mcp_servers`(varchar(500),CSV server 名,空=关)——schema.sql:138-158 + SchemaMigrator;`AgentShare` 字段、`PublishSpec`/`publish()`(AgentShareService:57-114)、`AgentController` publish 出入参。
2. 发布弹窗(AgentsPage.vue:487-545「开放 MCP 端点」旁)加「游客可用 MCP 工具」勾选组——选项=发布者已登记启用的 server(`GET /api/ai/mcp` 现成接口)，文案注明需智能体审批档≠off(ToolFamily.MCP blockedWhenApprovalOff,ToolFamily.java:35)。
3. **开闸三处(唯一改动点)**：
   - `McpToolProvider.callbacks:60`:guestMode 分支改为——`mc.guestMcpServers()` 非空时以它为 onlyServers 继续(`mc.userId()` 游客轮=发布者 uid,连接池天然解析;ShareController.java:301);空则照旧返回空。
   - `ToolMountContext` 加 `guestMcpServers` 字段与 `isGuestPreapproved(toolName)`;`ChatCommand` 加同名字段,ShareController /chat 从 share 记录填入,经 AgentscopeChatEngine:3469→WenquAgentxTools.frameworkTools:184-236→ToolAssemblyService.mountContext:63 传入。
   - 预批通道:`WenquAgentxTools.mountFrom:433-463` 的 `needsApproval` 追加 `&& !slot/mc.isGuestPreapproved(name)`;`SpringAiToolAdapter.checkPermissions:106-124` 同口径(游客 approvalCanAsk=false 的 deny 只拦未预批工具)。预批集合=仅 guestMcpServers 内 server 的工具，粒度收窄到名。
4. 只读档不拦 MCP(ExecutionPolicy.allowsTool(d==null)==true),无需动;`ToolMountContext.turnAllows:149-156` 对 d==null 放行,无需动。

## P3 dynamic-form-vue：draft 推送 + 接收源切换

1. **基线推送**：新 `src/api/copilotDraft.js`——`pushDraft(draftId, packForm())`/`fetchDraft(draftId)`(走现有 X-Token axios)。`runTurn`(FormCopilot.vue:1431)与 `restoreRunningTurn`(1066)发送前推一次；推送成功才走「瘦 compose」。
2. **compose 瘦身**(FormCopilot.vue:820-916)：MCP 模式下不再每轮附整份 schema,只带头部 `draftId=<sessionId>` + 指引「画布现状在服务端 draft,用 form_get_draft 读取」；推送失败自动回落旧整包形态。
3. **SSE onTool 钩子**(formCopilot.js:281-328 handleFrame 单点)：tool_status 的 content 已被 parse(317-320),新增 `onTool` 回调透传 {name,status,args,result}（runTurn/restoreRunningTurn 各接一处，照 turnArtifacts 累积模式）；用于进度行展示「正在补第 N 区块」。
4. **done 帧接收源切换**(applyAnswer 1650-1664 处)：解析顺序改为 ①artifact/form-json 提取(legacy 优先，保兼容)→ ②无提取结果且有 draftId 时 `fetchDraft` 拉 server 终稿作 candidate。1717 行之后管线(restore→fill→normalize→validate→pinTopLevel→bindFrameData→dryRun→diff→reassign→parseForm)**原样复用**——把 1717-1852 直线代码抽成 `src/core/formSpec/applySchemaToCanvas.js` 纯模块，聊天与 draft 两条路共用(唯一组件私有依赖 pinTopLevel 一并搬)。
5. 服务器校验失败的回喂重试(1665-1691)降级为提示(模型已在工具回声里自修过)；broken 检测保留作 legacy 兜底。
6. 不做逐 op 实时镜像(与今天「回合末上屏」体验一致，零双写风险)；若后续要看画布逐步长出来，用 tool_status args 里的 ops + applySchemaPatch 做轻镜像，列为二期。

## P4 提示词改造

scripts/extract-component-spec.mjs 模板源头改(生成物 agent-prompt.md 不手改)：
- 新增「工具契约」节：工具表里有 form_* 工具时**必须走工具循环**——先 form_get_draft 摸现状(draftId 抄消息头)，按「骨架→区块」逐次 form_apply_patch(每次小补丁)，**校验回声里的错误当轮修掉**，全部区块完成后 form_validate 看列预览自查 name↔code,干净了才收工；正文仍 ≤3 句。
- 原「输出契约」节降级为 fallback(仅工具不可用时走 write_file/form-json)。
- PUT 回问渠智能体(自签 JWT,参考 10-31 流程)。

## 验证

1. **P1 单测**：curl 手打 JSON-RPC(initialize→tools/list→tools/call form_apply_patch)对测试 schema 走通；form_validate 的表名查重/列派生正确。
2. **P2**:probe 连通(问渠设置页 MCP 管理探活)；管理员把服务登记进 c_ai_user_mcp(name=动态表单服务,url=http://localhost:8080/mcp/form,headers 带 X-Service-Token)+表单设计助手智能体 tool_mcp=1、mcps 选中该服务、审批档=auto;发布弹窗勾选游客可用；另建一个未勾选的智能体验证默认仍拿不到 MCP 工具(fail-closed 回归)。
3. **P3+E2E**:8000 栈新建表单→贴借款申请表截图→**一次提问**→观察 tool_status 进度→模型自主多轮补丁(每轮回声自修)→done 后画布完整→dryRun 无错→保存成功；对照纸表逐区核对。回归：推 draft 失败/未登记 server 时回落旧通道,老表单增量补丁照常。
4. 全程 `git show --stat` 核对只含本次改动(防并行 WIP 卷入)。

## 风险与边界

- **SDK 客户端 ↔ 手写服务端兼容**是最大不确定点,P1 第一步就用 probe 验；不行即转 SSE 响应形态。
- 工具回声严格限长(摘要+错误清单,不回 schema),防止回声本身变成输出预算炸弹；form_get_draft 的 2.8 万字 draft 走输入方向，不占输出预算。
- draft 内存态重启即失——前端每次发送前重推基线，天然自愈。
- 模型忘带 draftId→工具报错回声教它补；提示词写死「draftId 抄消息头」。
- 游客口子按 server 粒度+发布者显式勾选，默认关；预批集合仅限勾选 server 的工具，不开「游客全量 MCP」。