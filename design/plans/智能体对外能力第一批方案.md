# 方案：智能体对外能力第一批——自定义 HTTP 工具（OpenAPI 导入）+ 调用变量注入

> 2026-11-07 立项，路线图账本「活跃线」同名行指向本文件。批次范围已经用户确认（五项边界项维持排批）。

## 目标

智能体嵌入其他项目（agent-as-a-service）的最小业务闭环：宿主项目带变量调用 → 智能体感知宿主上下文 + 按导入的 OpenAPI 规格调用宿主 API → 结果返回宿主。

## 批次范围

- **F1 自定义 HTTP 工具**：`c_ai_agent` 加 `tool_http INT NULL`（三态）+ `http_tools MEDIUMTEXT`（归一化工具集 JSON，不存原始 spec）；新 `service/httptool/`（OpenApiSpecParser 手写子集解析、HttpToolModels 归一化+JWE 凭据、HttpToolExecutor 静态 JDK HttpClient）；新 `agent/tool/provider/HttpApiToolProvider`（动态回调，照抄 McpToolProvider 结构）；ToolFamily 加 `HTTP_API`（blockedWhenApprovalOff=false）；AgentService toEntity/updateColumns/VERSION_FIELDS 三处；AgentController `POST /agent/http-tools/preview`；ConfigService defaults `httpTool.enabled=true`/`httpTool.allowPrivateNetwork=true`；config-schema.json 两字段；前端 AgentsPage CAPS+接线 + 新组件 `components/agent/HttpToolsEditor.vue`（粘贴导入抄 McpPanel:202-226）+ api.js previewAgentHttpTools。
- **F2 调用变量注入**：ChatRequest/ChatCommand/ChatController 加 `variables`（≤32 项、key `[A-Za-z0-9_]{1,64}`、单值 ≤4096、总量 ≤32KB）；TurnPromptAssembler.userContentWithContext 追加「调用方上下文」段（UntrustedContent 信封消毒、key 排序、空时零变化）；游客链路 `c_ai_agent_share.variables_enabled INT DEFAULT 0` fail-closed（ShareChatRequest/ShareController/AgentShare/AgentShareService）；前端发布弹窗四件套。

## 关键架构口径

- 调用级变量不进 sysPrompt（常驻实例烤死 + 前缀缓存）；配置级 {{var}} 是后续便宜增量。
- 凭据 = JWE 整块加密（ConfigCryptoService），回显 mask，值不变不重加密。
- 工具名 `{集名}_{operationId}` 清洗 `[a-zA-Z0-9_-]≤64`，拒 `wen_qu` 前缀。
- guestAllowed=false；`requiresApproval=工具集.requireApproval`；SideEffect.EXTERNAL。
- 非铺 2xx 作为工具结果返回（模型自行反应），传输错误才抛。
- 上限：每智能体 ≤4 工具集、每集 ≤20 ops、spec ≤512KB、响应读 256KB/给模型 16KB、timeoutMs 1s–60s 默认 10s。

## 边界（不做，依据见会话 2026-11-07）

sysPrompt 每轮模板（架构代价）；HTTP 工具游客放行（等 P3/P4 拍板）；工作流当工具/webhook/agent 级记忆（各自待设计）；管道层计量/scope/CORS/OpenAI 兼容端点（后排批）；专用审计表（v1 结构化日志）。

## 验证

编译走 IDEA 内置 mvn；8091 jar + VITE_PROXY_TARGET 临时栈；模型走千问 token-plan（ce472bef）；/tmp mock 宿主 API。E2E 七条：导入→落库 JWE/回显 mask；聊天触发工具+鉴权头；审批卡；variables 两态+零变化；游客开关两态；版本 diff/回滚；闸门三态。回归：老智能体提示词/工具列表逐字节零变化、ToolRegistry 启动干净、版本四链路、toolDeny、游客不挂载、老会话回显。

## 提交

两笔：`feat(agent): 智能体自定义 HTTP 工具——OpenAPI 导入生成对话工具`；`feat(agent): 调用变量注入——宿主上下文随轮进用户消息（含分享级开关）`。注意并行 WIP：schema.sql/config-schema.json/ConfigDefaults.java/ConfigService.java/api.js 只提自己的 hunk（hash-object + update-index --cacheinfo）。
