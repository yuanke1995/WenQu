# API 速查

完整契约以 Swagger 为准（`SPRINGDOC_ENABLED=true` 时访问 `/ai/swagger-ui/index.html`；在线调试需先登录，在 Authorize 填 `Bearer <登录令牌>`）。API 前缀 `/api/ai/*`。

## 核心 REST 端点

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（事件见下表） |
| `POST /api/ai/tool-approval/{approvalId}` | 工具执行确认：批准/拒绝有副作用的沙盒/MCP 工具调用 |
| `GET /api/ai/auth/me` | 当前身份、权限与菜单树 |
| `POST /api/ai/auth/login` | 本地登录；OIDC 四端点见 `/api/ai/auth/oidc/*` |
| `GET /api/ai/sessions` 等 | 会话列表/新建/重命名/置顶/收藏/删除/批量删除 |
| `GET/POST/PUT/DELETE /api/ai/kb` | 知识库 CRUD；`GET /kb/list` 列表、`GET /kb/param-defaults` 参数模板 |
| `POST /api/ai/document/upload`、`/upload/batch`、`/import-url` | 上传（异步解析）/ 批量上传 / 网页导入 |
| `GET /api/ai/document/list`、`DELETE /{id}`、`POST /{id}/reparse`、`GET /{id}/versions`、`POST /{id}/rollback` | 文档列表/删除/重解析/版本/回滚 |
| `GET /api/ai/knowledge/list?docId=`、`PUT /api/ai/knowledge/{id}`、`POST /api/ai/knowledge` | 知识块预览/编辑（重向量化）/手动建块 |
| `GET/PUT /api/ai/config`、`GET /api/ai/config/schema`、`POST /api/ai/config/reset` | 配置读写（脱敏）/ schema 下发 / 恢复分组默认 |
| `GET /api/ai/provider/*` | 模型供应商登记/启停/判权 |
| `GET/POST/PUT/DELETE /api/ai/agent*` | 智能体 CRUD / `GET /agent/available` 对话页精简列表 / 发布分享 |
| `GET/POST /api/ai/skill/*` | 技能列表/新建/URL 安装/启停/删除 |
| `GET /api/ai/mcp/status`、`POST /api/ai/mcp/reload`、`POST /api/ai/mcp/probe` | MCP Client 状态/重连/探测 |
| `POST /ai/mcp/{token}` | MCP Server·per-agent 端点（不在 /api 之下，token 即凭据） |
| `POST /ai/mcp` | MCP Server·平台级入口（凭据 API Key + MCP 开关） |
| `GET/POST /api/ai/scheduled/*`、`POST /api/ai/scheduled/{id}/trigger` | 定时任务与立即触发 |
| `GET/POST/PUT/DELETE /api/ai/workflow*` | 工作流 CRUD / 校验 / 发布 / 版本 / 回滚 |
| `POST /api/ai/v1/workflows/{id}/run` | 对外工作流触发（`X-Api-Key`，仅已发布版本） |
| `GET /api/ai/artifacts`、`DELETE /api/ai/artifacts/{id}` | 产物列表/删除（下载走签名 URL） |
| `GET/POST /api/ai/api-key*` | 对外 API Key：列表/签发（明文仅一次）/吊销/MCP 授权 |
| `GET /api/ai/manual/documents`、`GET /api/ai/manual/documents/{id}/content` | 内置手册：篇目列表 / 单篇内容（帮助中心数据源） |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试 |
| `POST /api/ai/eval/generate`、`POST /api/ai/eval/run` | 检索评估集生成与多参数组评测 |

## SSE 事件（`POST /api/ai/chat`）

| 事件 | 时机 |
|------|------|
| `token` | 流式生成逐片 |
| `stage` / `plan` | 阶段推进 / 本轮执行计划 |
| `retrieved` | 检索+重排+上下文完成（关键词/命中概览） |
| `thinking` / `thinking_done` | 深度思考链增量 / 结束（完整链在 done 给出） |
| `process` | 工具模式下过程独白增量 |
| `tool_status` / `tool_output` | 工具状态 / 沙盒命令输出实时流 |
| `approval_required` | 有副作用工具等待确认（批准/拒绝经 tool-approval 端点） |
| `artifact` | 产物交付落盘（卡片字段） |
| `subagent` / `subagent_route` / `agent_dispatched` / `agent_bound` | 编排分支状态 / 路由理由 / 自动派遣 / 首问绑定 |
| `image` | 命中图片 URL 列表 |
| `warn` | 非致命降级提示 |
| `done` | 完成：sources、timeline、tokens 等终态载荷 |
| `error` | 处理异常 |
