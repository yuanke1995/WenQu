# API 速查

完整契约以 Swagger 为准（`SPRINGDOC_ENABLED=true` 时访问 `/ai/swagger-ui/index.html`；在线调试需先登录，在 Authorize 填 `Bearer <登录令牌>`）。API 前缀 `/api/ai/*`，context-path 为 `/ai`。

当前共 **44 个控制器、379 个 REST 端点**，另有两条 MCP 路由不在 `/api` 之下。本篇按域列出常用端点，逐条参数以 Swagger 为准；对外集成场景见《对接与集成》。

## 问答与会话

| 端点 | 说明 |
|------|------|
| `POST /api/ai/chat` | SSE 流式问答（事件见下表） |
| `POST /api/ai/chat/stop`、`POST /api/ai/chat/steer` | 停止本轮 / 运行中插话 |
| `GET /api/ai/chat/run`、`GET /api/ai/chat/resume` | 探测是否有轮在跑 / 断线重连接流（SSE） |
| `POST /api/ai/chat/attachment`、`/attachment/live` | 附件上传换 fileId / 批量校验附件是否仍可重发 |
| `POST /api/ai/tool-approval/{approvalId}`、`GET /tool-approval/pending` | 工具执行确认裁决 / 待裁决卡恢复 |
| `POST /api/ai/ask-user/{askId}`、`GET /ask-user/pending` | 回答智能体提问 / 待答提问卡恢复 |
| `POST /api/ai/plan-approval/{planApprovalId}`、`GET /plan-approval/pending` | 执行计划裁决（批准可带修改后全文 / 取消 / `supersede` 继续对话）/ 待批准计划恢复 |
| `POST /api/ai/session/{id}/compact`、`GET /session/{id}/history-summary` | 手动压缩上下文（可带摘要要求）/ 当前生效摘要 |
| `GET /api/ai/session/{id}/events`、`GET /session/{id}/todos` | 会话事件账本（按轮过程回放）/ 任务清单快照 |
| `GET /api/ai/tool-output/{spillId}`、`GET /tool-output/{spillId}/search` | 工具大输出留存原文读回 / 在原文中检索 |
| `GET /api/ai/sessions`、`POST /session/new`、`GET /session/{id}`、`PUT /session/{id}/rename\|pin\|favorite\|model\|project`、`DELETE /session/{id}`、`DELETE /sessions`、`POST /sessions/batch-delete` | 会话列表（游标分页 + 搜索）/ 新建 / 历史恢复 / 重命名 / 置顶 / 收藏 / 模型与窗口档位 / 移动工作区 / 删除 / 清空 / 批量删除 |
| `GET+POST+DELETE /api/ai/session/{id}/share`、`GET /session-shares`、`DELETE /session-shares/{sessionId}[/record]` | 会话只读分享：查询 / 开启 / 关闭 / 管理 |
| `DELETE /api/ai/message-group/{assistantMessageId}`、`POST /message-group/undo`、`POST /message-group/switch` | 删除本轮 / 撤销删除 / 切换回答版本 |
| `POST /api/ai/chat/goal`、`POST /chat/goal/{goalId}`、`GET /chat/goal/pending`、`GET /chat/session/{id}/goal[/events]` | 目标模式：创建 / 动作（`pause`·`resume`·`edit`·`clear`）/ 待处理恢复 / 目标视图与过程账本 |
| `GET /api/ai/auth/me` | 当前身份、权限与菜单树 |
| `POST /api/ai/feedback`、`GET /analytics/summary`、`GET /analytics/badcases` | 赞踩反馈 / 看板聚合 / 差评样本 |

## 知识库、文档与检索

| 端点 | 说明 |
|------|------|
| `GET+POST+PUT+DELETE /api/ai/kb`、`GET /kb/{id}/export`、`POST /kb/{id}/import`、`PUT /kb/doc/{docId}`、`GET /kb/param-defaults`、`/kb/param-tips` | 知识库 CRUD、整库导出导入、移动文档、参数模板与说明 |
| `POST /api/ai/document/upload`、`/upload/batch`、`/import-url` | 上传 / 批量上传 / 网页导入 |
| `GET /api/ai/document/list`、`/queue/stats`、`/stats`、`/health`、`/{id}/source`、`/{id}/vector-diagnose` | 列表 / 队列状态 / 统计 / 解析健康 / 源文件 / 向量诊断 |
| `POST /api/ai/document/{id}/reparse`、`/batch/reparse`、`/{id}/rollback`、`/{id}/backfill-descriptions`、`/rebuild-kb`、`/{id}/restore`、`/{id}/purge` | 重解析 / 批量重解析 / 版本回滚 / 补图片描述 / 整库重建 / 恢复 / 彻底删除 |
| `GET /api/ai/document/{id}/versions`、`/recycle`、`PUT /{id}/status\|share\|refresh-config\|tags`、`DELETE /{id}` | 版本历史 / 回收站 / 启停用 / 共享范围 / 网页自动刷新配置 / 标签 / 删除 |
| `GET /api/ai/knowledge/list?docId=`、`/knowledge/search`、`/knowledge/{id}`、`/knowledge/unmatched`、`POST /knowledge`、`PUT /knowledge/{id}[/status]`、`DELETE /knowledge/{id}` | 知识块预览 / 跨文档搜索 / 详情 / 无命中问题 / 手动建块 / 编辑（自动重向量化）/ 启停用 / 删除 |
| `POST /api/ai/debug/retrieval` | 检索链路分步调试 |
| `POST /api/ai/eval/generate`、`/eval/run`、`/eval/run-auto`、`/eval/case`、`GET /eval/set`、`/last-report`、`/answer/report`、`POST /eval/answer/run` | 评估集生成 / 参数组对比 / 自动体检 / 加用例 / 评估集与体检报告 / 答案质量评测 |
| `POST /api/ai/graph/{kbId}/build`、`GET /graph/{kbId}/status\|view\|triples\|search\|entity/{id}\|neighbor/{id}\|chunk/{id}`、`DELETE /graph/{kbId}` | GraphRAG 构建、状态、图谱视图与查询、清空 |
| `GET /api/ai/search-index/stats`、`POST /search-index/reindex`、`DELETE /search-index`；`GET /desc-cache/stats`、`POST /desc-cache/prune`、`DELETE /desc-cache`；`GET /api/ai/ocr/health` | 关键词索引运维 / 图片描述缓存运维 / PDF 深度解析引擎逐引擎探活 |

## 智能体、技能、市场与 MCP

| 端点 | 说明 |
|------|------|
| `GET /api/ai/agent/list`、`/available`、`/consultable`、`/sub`、`/config-defaults`、`/tool-catalog`、`/permission-presets`、`/{id}/versions`；`POST /agent`、`/{id}/rollback`、`/{id}/default`、`/batch-delete`；`PUT /agent/{id}`、`/{id}/share`；`DELETE /{id}` | 智能体 CRUD、对话页与委派用列表、工具目录与权限档位选项、配置版本与回滚、设默认、共享范围 |
| `GET+POST+DELETE /api/ai/agent/{id}/publish` | 公开发布：查询 / 发布（免登录对话与 MCP 端点）/ 撤销 |
| `GET /api/ai/share/{token}/info`、`/history`、`/artifact`、`POST /share/{token}/chat`（SSE）、`/attachment`、`/feedback`、`/chat/stop`、`GET /chat/run`、`/chat/resume` | 游客通道（免登录、按 IP 限频、工具白名单收窄、不携带个人记忆） |
| `GET /api/ai/skill/available`、`/list`、`/detail`、`/content`、`/file`、`/download`；`POST /skill`、`/install`、`/install/zip`；`PUT /skill/{name}`、`/{name}/disabled`；`DELETE /skill/{name}` | 技能：选用清单 / 管理列表 / 详情与正文 / 附属文件 / 下载 / 新建 / URL 安装 / zip 安装 / 编辑 / 个人停用 / 删除 |
| `GET /api/ai/market/list`、`/{id}`、`/{id}/download`；`POST /market/publish`、`/{id}/install`；`PUT /{id}/status`；`DELETE /{id}` | 市场：浏览 / 详情 / 下载物品包 / 上架 / 安装 / 上下架 / 删除 |
| `GET /api/ai/mcp/status`、`/audit/logs`、`/audit/summary`；`POST /mcp/servers`、`/servers/import`、`/servers/batch-delete`、`/servers/batch-enabled`、`/servers/{id}/enabled`、`/reload`、`/probe`；`PUT /mcp/servers/{id}`、`/{id}/tool-label`；`DELETE /mcp/servers/{id}` | MCP Client：状态 / 调用审计与汇总 / 登记 / JSON 批量导入 / 批量与单个启停删除 / 整体重连 / 连通探测 / 编辑 / 工具中文名 |
| `GET /api/ai/tools` | 平台已注册工具全景清单（按族分组，含展示名与说明） |
| `POST /ai/mcp/{token}`；`POST /ai/mcp` | MCP Server：智能体端点（token 即凭据）/ 平台级入口（API Key + MCP 授权） |
| `GET+POST+PUT+DELETE /api/ai/scheduled`、`PUT /{id}/enabled`、`POST /{id}/run`、`GET /{id}/runs`、`/batch-delete`、`/batch-enabled` | 定时任务 CRUD、启停、立即执行与执行记录 |

## 工作流、凭据与对外触发

| 端点 | 说明 |
|------|------|
| `GET /api/ai/workflow/list`、`/templates`、`/config-defaults`、`/{id}`、`/{id}/export`、`/{id}/versions`；`POST /workflow`、`/import`、`/validate`、`/{id}/publish`、`/{id}/unpublish`、`/{id}/rollback/{version}`、`/batch-publish`、`/batch-unpublish`、`/batch-delete`、`/from-agent/{agentId}`；`PUT /workflow/{id}`、`POST /{id}/share`；`DELETE /{id}` | 工作流定义 CRUD、DSL 校验与编译 dry-run、发布下线回滚、批量操作、模板与导入导出、由智能体转工作流 |
| `POST /api/ai/workflow/{id}/run`、`/run/{runId}/cancel`、`/run/{runId}/resume`、`/schedule/run-now`；`GET /{id}/run/list`、`/{id}/run/{runId}`、`/{id}/run/{runId}/stream`（SSE）、`GET+POST /{id}/run/{runId}/approval`；`GET+PUT /{id}/automation` | 运行与取消续跑、运行历史与详情、实时进度流、人工审核读取与裁决、定时与回调配置 |
| `GET+POST+PUT+DELETE /api/ai/credential` | 工作流凭据（值加密落库、出参脱敏，运行时按运行发起人解密填入） |
| `POST /api/ai/v1/workflows/{id}/run`、`GET /api/ai/v1/workflows/{id}/run/{runId}` | 对外触发：持 API Key（`X-Api-Key`）跑**已发布**版本 / 按 runId 查结果 |
| `GET /api/ai/api-key/list`、`POST /api-key`、`PUT /{id}`、`/{id}/disabled`、`/{id}/mcp`、`/{id}/share`、`DELETE /{id}` | 对外 API Key：列表 / 签发（明文仅一次）/ 编辑 / 吊销 / MCP 授权 / 共享范围 / 删除 |

## 工作区、沙盒与产物

| 端点 | 说明 |
|------|------|
| `GET /api/ai/project/list`、`/{id}`、`/{id}/files`、`/{id}/file`、`/{id}/file/content`、`/{id}/export`、`/{id}/workflows`、`/{id}/runs`、`/{id}/instruction-history`、`/{id}/todos`、`/{id}/turn-guard`；`POST /project`、`/{id}/files`、`/{id}/dir`、`/{id}/files/batch`、`/{id}/todos`；`PUT /project/{id}`、`/{id}/status`、`/{id}/file`、`/{id}/todos/{todoId}`；`DELETE /{id}`、`/{id}/todos/{todoId}` | 工作区容器、目录单层列表、上传下载、改名移动、批量、打包导出、约定历史、事项、归档与删除、轮次占用检查 |
| `GET /ai/project-files/{token}` | 工作区文件签名预览（可直接在浏览器打开的类型；`/api` 之外，token 带过期） |
| `GET /api/ai/sandbox/state`、`/tree`、`/files`、`/download`、`/files/download`、`/procs`、`/procs/live`；`POST /sandbox/files/artifact`、`/run`、`/procs/kill`；`POST /sandbox/tool-call` | 沙盒：容器状态、目录与文件、存为产物、执行 python/node/bash、后台进程清单与停止、脚本回程调用 |
| `GET /api/ai/artifact/list`、`/{id}/content`；`PUT /artifact/{id}/project`；`POST /refresh-sign`、`/batch-delete`；`DELETE /{id}` | 我的产物：列表 / 读文本原文 / 移动工作区 / 换发新鲜签名下载地址 / 批量与单条删除 |

## 身份、配置与运营

| 端点 | 说明 |
|------|------|
| `GET /api/ai/auth/first-run`、`POST /auth/initialize`、`/auth/login`、`/auth/logout`、`/auth/password` | 首启判定、创建管理员、登录登出、改密 |
| `GET /api/ai/oidc/list`、`POST /oidc`、`PUT /oidc/{id}`、`/{id}/enabled`、`DELETE /oidc/{id}`、`DELETE /user/{uid}/oidc-binding`；`GET /auth/oidc/config`、`/login-url`、`/callback`、`POST /auth/oidc/exchange-code` | OIDC 认证源管理（多 IdP）与授权码流程四端点 |
| `GET /api/ai/user/list`、`/preference`、`/settings`；`POST /user`；`PUT /user/{uid}`、`/{uid}/password`、`/preference`、`/profile`、`/settings`；`DELETE /user/{uid}`、`/{uid}/oidc-binding` | 成员管理与个人偏好（默认模型、记忆开关） |
| `GET /api/ai/role/*`、`/menu/*`、`/department/*`、`/api/list` | 角色（含 `/{code}/permissions` 统一权限读写）、菜单与个人侧栏布局、部门树、RBAC 接口清单 |
| `GET+PUT /api/ai/config`、`GET /config/schema`、`/public`、`/rerank/check`、`/keyword/check`、`POST /config/probe`、`/reset` | 配置读写（脱敏）/ schema 下发 / 免登录公开项 / 重排与关键词连通检查 / 先测后存 / 恢复分组默认 |
| `GET /api/ai/provider/*` | 模型供应商登记、模型管理与引用清单 |
| `GET+PUT /api/ai/memory`、`GET /memory/content`、`POST /memory/compact`、`DELETE /memory`、`/daily` | 个人长期记忆面板：`MEMORY.md` 与日账 |
| `GET /api/ai/notification/list`、`/unread-count`、`/counts`、`/preferences`；`POST /read`、`/read-all`、`/delete`、`/clear`；`PUT /preferences` | 站内通知：列表与计数 / 静音偏好 / 已读与清理 |
| `GET /api/ai/stats/usage`、`GET /api/ai/stats/cost` | 个人使用统计与费用口径 |
| `GET /api/ai/trace/list`、`/chat/{id}`、`/workflow/{runId}`、`/pool`、`/stats`；`POST /trace/pool/{id}/label`、`/pool/{id}/dismiss`、`/sample/run` | 执行 Trace 与采样池标注回流 |
| `GET /api/ai/schedule/tasks`、`/runs`；`POST /schedule/tasks/trigger`；`DELETE /schedule/runs` | 调度中心运行状态快照、执行日志、手动触发与清理 |
| `GET /api/ai/manual/documents`、`/documents/{id}/content`；`POST /manual/sync` | 帮助中心数据源：篇目列表 / Markdown 原文 / 管理员手动同步官方手册 |

## SSE 事件（`POST /api/ai/chat`）

| 事件 | 时机 |
|------|------|
| `token` | 流式生成逐片 |
| `plan` | 轮次开始，按当前配置确定会跑的步骤（仅实时下发） |
| `retrieved` | 检索 + 重排 + 上下文完成（关键词与命中概览） |
| `thinking` / `thinking_done` | 深度思考链增量 / 结束（完整链在 `done.thinking` 给出） |
| `process` | 工具模式下过程独白增量 |
| `tool_status` / `tool_output` | 工具状态 / 沙盒命令输出实时流 |
| `approval_required` | 有副作用工具等待确认（批准与拒绝经 `tool-approval` 端点） |
| `plan_delta` / `plan_approval` | 计划文本增量 / 计划待确认（含 `planApprovalId`、计划全文与超时） |
| `plan_cancelled` / `plan_superseded` | 计划未批准（取消或超时）/ 被「继续对话」取代 |
| `subagent_step` / `agent_delegated` / `agent_consulted` | 被委派分支的实时步骤 / 本轮由谁作答 / 本轮咨询过谁 |
| `artifact` | 产物交付落盘（卡片字段） |
| `usage` | prompt 侧用量预下发，`done` 时以网关实测覆盖 |
| `image` | 命中图片 URL 列表 |
| `warn` | 非致命降级提示 |
| `done` | 完成：`sources`、`related`、`timeline`、`tokens`、`artifacts`、`toolCalls`、`degradations`、编排终态等终态载荷 |
| `error` | 处理异常 |

> `GET /api/ai/chat/resume`、`GET /api/ai/share/{token}/chat/resume` 与 `GET /api/ai/workflow/{id}/run/{runId}/stream` 复用同一套事件语义。
