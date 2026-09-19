# 参考实现 → 问渠 移植清单（分支 feature/platform-alignment）

> 参考实现位于本机 `~/Downloads/<参考实现>-main/backend`（完整绝对路径见 `.workbuddy/memory/`），分两大层：
> - `package/<ref>/`（业务逻辑，services/knowledge/agents/repositories…）
> - `server/`（API 层：FastAPI 入口 + 25 个 router + server utils）
>
> 本清单条目与参考文件 **1:1 对应**，路径均以参考根为基准（`<ref>` 为参考实现包名占位）。

## 用法（重要）
- 每搬完 **一个条目**，把行首 `[ ]` 改成 `[x]`，并在文末「移植记录」表追一行（日期 + 条目 + commit 短哈希）。
- 严格照搬参考实现：必要替换（引擎版本 / MySQL 方言 / 缓存 key 前缀 / 品牌前缀）与能力差异（如 LangGraph→StateGraphPort）才允许改动，且必须标注。
- 参考实现产品名、包名等标识词在代码与注释中一律清除（第三方库名如 pypinyin 不算）。
- 参考 `services/viewer_filesystem_service.py`（批次⑳）为对拍范本。

## 进度概览
- ✅ 已完成：repositories(23) / models(10) / config(3) / permissions(2) / workspace 前置(3) / common 工具(20) / agents 前置层(10) / knowledge 解析面(17) / storage(minio) / services 30-43 / 3 个 controller（Auth/Document/KnowledgeBase） / RAGFlow 分块家族 12/12（全量直译，见下） / knowledge 根核心 9/9 + knowledge_task_service + workspace_service / **§五 API 层 17/33（routers 12 + utils 5）**
- 🔲 剩余：6 大块，约 **84** 个条目（见下）。§五 剩余 16 项中，**仅 external_kb_router 无阻塞**，其余 15 项均等 §一/§二/§三 先落地（见文末「挡在后面的依赖」）。

---

## 一、services 剩余（13）
参考路径前缀 `package/<ref>/services/`
- [ ] agent_config_service — agent_config_service.py
- [ ] agent_request_service — agent_request_service.py
- [ ] agent_request_queue_service — agent_request_queue_service.py
- [ ] agent_run_service — agent_run_service.py
- [ ] agent_run_manifest_service — agent_run_manifest_service.py
- [ ] subagent_run_service — subagent_run_service.py
- [ ] scheduled_agent_service — scheduled_agent_service.py
- [ ] run_worker — run_worker.py
- [ ] arq_worker — arq_worker.py
- [x] knowledge_task_service — knowledge_task_service.py（KnowledgeTaskService：5 个任务处理函数 + 失败钩子）
- [ ] chat_service — chat_service.py
- [ ] context_compression_service — context_compression_service.py
- [ ] artifact_service — artifact_service.py
- [x] workspace_service — workspace_service.py（WorkspaceService：9/9 公开函数全搬 —— search/list_tree/read_bytes/read_content/write_content/delete/create_directory/upload/download）

## 二、knowledge 核心（约 40，parser 子包与 kb_utils/pdf_utils 已搬）
参考路径前缀 `package/<ref>/knowledge/`
### 根核心（9）
- [x] base — base.py（KnowledgeBaseRuntime + KnowledgeBaseException）
- [x] cache — cache.py（repositories/KnowledgeBaseCache，本会话前已搬）
- [x] factory — factory.py（KnowledgeBaseFactory，区别于 parser/factory.py）
- [x] manager — manager.py（KnowledgeBaseManager）
- [x] preview — preview.py（KnowledgeFilePreviewService）
- [x] read_models — read_models.py（KnowledgeBaseConfig / KnowledgeBaseSummary / KnowledgeBaseDetail）
- [x] runtime — runtime.py（KnowledgeBaseRegistrar，Spring 启动时注册）
- [x] schemas — schemas.py（KbToolSchemas）
### chunking/ragflow_like（12）
- [x] dispatcher — chunking/ragflow_like/dispatcher.py（RagflowChunkDispatcher）
- [x] nlp — chunking/ragflow_like/nlp.py（RagflowNlp）
- [x] presets — chunking/ragflow_like/presets.py（service/ChunkPresets，本会话前已搬）
- [x] parsers/book — chunking/ragflow_like/parsers/book.py（BookChunkParser）
- [x] parsers/general — chunking/ragflow_like/parsers/general.py（GeneralChunkParser）
- [x] parsers/laws — chunking/ragflow_like/parsers/laws.py（LawsChunkParser）
- [x] parsers/qa — chunking/ragflow_like/parsers/qa.py（QaChunkParser）
- [x] parsers/semantic — chunking/ragflow_like/parsers/semantic.py（SemanticChunkParser；markdown-it→RagflowMdTokenizer 纯扫描近似，bs4→RagflowTableUtils 纯字符串解析，embed_fn 降级按 token 合并）
- [x] parsers/separator — chunking/ragflow_like/parsers/separator.py（SeparatorChunkParser）
- [x] utils/md_parser — chunking/ragflow_like/utils/md_parser_utils.py（RagflowMdParserUtils）
- [x] utils/semantic — chunking/ragflow_like/utils/semantic_utils.py（RagflowSemanticUtils；聚类用 sklearn → 降级按 token 合并）
- [x] utils/table — chunking/ragflow_like/utils/table_utils.py（RagflowTableUtils；bs4 → 纯字符串解析）
### eval（4）
- [ ] benchmark_generation — eval/benchmark_generation.py
- [ ] evaluator — eval/evaluator.py
- [ ] metrics — eval/metrics.py
- [ ] service — eval/service.py
### graphs（6）
- [ ] extractors/base — graphs/extractors/base.py
- [ ] extractors/factory — graphs/extractors/factory.py
- [ ] extractors/llm — graphs/extractors/llm.py
- [ ] graph_utils — graphs/graph_utils.py
- [ ] milvus_graph_service — graphs/milvus_graph_service.py
- [ ] milvus_graph_vector_store — graphs/milvus_graph_vector_store.py
### implementations（4）
- [ ] dify — implementations/dify.py（参数面已搬入 KnowledgeBaseTypeParams；aquery 依赖外部 Dify HTTP 接口，未搬）
- [x] milvus — implementations/milvus.py（执行器由既有 Spring 内核 VectorStore / KnowledgeChunkRepository / HybridRetrievalService 承载；类型参数与检索参数清单 → KnowledgeBaseTypeParams）
- [ ] notion — implementations/notion.py（参数面已搬入 KnowledgeBaseTypeParams；aquery 依赖外部 Notion HTTP 接口，未搬）
- [x] read_only_connectors — implementations/read_only_connectors.py（ReadOnlyConnectors：只读能力判定与报错文案）
### utils 剩余（5，kb_utils/pdf_utils 已搬）
- [ ] mindmap_utils — utils/mindmap_utils.py
- [ ] sample_question_utils — utils/sample_question_utils.py
- [x] security — utils/security.py（KnowledgeSecurity）
- [ ] url_fetcher — utils/url_fetcher.py
- [ ] url_validator — utils/url_validator.py

## 三、agents 运行时（约 32，仅前置层已搬）
> 已搬：context.py→BaseContext / state.py→AgentState / tool_approval.py→ToolApproval / backends/paths.py→BackendPaths / chatbot/prompt.py→ChatbotPrompt / skills/repository.py→SkillRepository / toolkits/registry.py→ToolkitsRegistry / toolkits/utils.py→ToolkitsUtils
参考路径前缀 `package/<ref>/agents/`
### root（1）
- [ ] base — base.py（Agent 基类）
### backends（6，paths.py 已搬）
- [ ] composite — backends/composite.py
- [ ] knowledge_base_backend — backends/knowledge_base_backend.py
- [ ] sandbox/backend — backends/sandbox/backend.py
- [ ] sandbox/download — backends/sandbox/download.py
- [ ] sandbox/provider — backends/sandbox/provider.py
- [ ] sandbox/provisioner_client — backends/sandbox/provisioner_client.py
### buildin/chatbot（3，prompt.py 已搬）
- [ ] context — buildin/chatbot/context.py
- [ ] graph — buildin/chatbot/graph.py
- [ ] state — buildin/chatbot/state.py
### buildin/subagent（2）
- [ ] context — buildin/subagent/context.py
- [ ] graph — buildin/subagent/graph.py
### callbacks（1）
- [ ] model_request_timing — callbacks/model_request_timing.py
### mcp（1）
- [ ] service — mcp/service.py
### middlewares（10）
- [ ] context — middlewares/context.py
- [ ] dynamic_tool — middlewares/dynamic_tool.py
- [ ] memory — middlewares/memory.py
- [ ] model_input — middlewares/model_input.py
- [ ] network_retry — middlewares/network_retry.py
- [ ] skills — middlewares/skills.py
- [ ] steer — middlewares/steer.py
- [ ] subagent_task — middlewares/subagent_task.py
- [ ] summary — middlewares/summary.py
- [ ] token_usage — middlewares/token_usage.py
### skills（3，repository.py 已搬）
- [ ] remote_install — skills/remote_install.py
- [ ] runtime — skills/runtime.py
- [ ] service — skills/service.py
### toolkits（5，registry.py/utils.py 已搬）
- [ ] buildin/install_skill — toolkits/buildin/install_skill.py
- [ ] buildin/tools — toolkits/buildin/tools.py
- [ ] debug/tools — toolkits/debug/tools.py
- [ ] kbs/tools — toolkits/kbs/tools.py
- [ ] service — toolkits/service.py

## 四、storage 后端（3，minio 已搬）
参考路径前缀 `package/<ref>/storage/`
- [ ] postgres/manager — postgres/manager.py（Spring/MyBatis 接管，可标记为 N/A）
- [ ] neo4j/manager — neo4j/manager.py（知识图谱）
- [ ] redis/manager — redis/manager.py
> 注：`storage_migrations/`（5）由 actable/Spring 接管，可不做。

## 五、server/API 层（33）
参考路径前缀 `package/../server/`（即 `backend/server/`）
### 入口（2）
- [ ] main — main.py（FastAPI app 入口 + 路由装配；WenquServerApplication 已存在但无路由）
- [ ] worker_main — worker_main.py（ARQ worker 入口）
### routers（25）
- [ ] agent_invocation_call_router — routers/agent_invocation_call_router.py
- [ ] agent_invocation_channel_router — routers/agent_invocation_channel_router.py
- [ ] agent_invocation_eval_router — routers/agent_invocation_eval_router.py
- [ ] agent_router — routers/agent_router.py
- [x] auth_dept_router — routers/auth_dept_router.py（DepartmentController；`/api/departments`）
- [x] auth_router — routers/auth_router.py（AuthRouterController；`/api/auth`，与既有 `/api/ai/auth` 并存。22 个端点：token/cli 会话 4 个/check-first-run/initialize/me/profile/users 4 个/access-options/validate-username/check-uid/upload-avatar/impersonate/oidc 4 个）
- [ ] chat_router — routers/chat_router.py
- [x] dashboard_router — routers/dashboard_router.py（DashboardController；`/api/dashboard`）
- [ ] external_kb_router — routers/external_kb_router.py
- [x] filesystem_router — routers/filesystem_router.py（FilesystemController；`/api/viewer/filesystem`）
- [x] graph_router — routers/graph_router.py（GraphController；`/api/graph`）
- [x] knowledge_dashboard_router — routers/knowledge_dashboard_router.py（KnowledgeDashboardController；`/api/dashboard/stats/knowledge`）
- [ ] knowledge_eval_router — routers/knowledge_eval_router.py
- [ ] knowledge_router — routers/knowledge_router.py
- [ ] mcp_router — routers/mcp_router.py
- [x] mention_router — routers/mention_router.py（MentionController；`/api/mention`）
- [ ] model_provider_router — routers/model_provider_router.py
- [x] project_router — routers/project_router.py（ProjectController；`/api/projects`）
- [ ] scheduled_agent_router — routers/scheduled_agent_router.py
- [ ] skill_router — routers/skill_router.py
- [x] system_router — routers/system_router.py（SystemController；`/api/system`）
- [x] system_task_router — routers/system_task_router.py（TaskController；`/api/tasks`）
- [ ] tool_router — routers/tool_router.py
- [x] user_router — routers/user_router.py（UserController；`/api/user`）
- [x] workspace_router — routers/workspace_router.py（WorkspaceController；两个同前缀 router 合并为 `/api/workspace`，含 knowledge 只读三端点）
### server/utils（6）
- [x] access_log_middleware — utils/access_log_middleware.py（config/AccessLogFilter）
- [x] auth_middleware — utils/auth_middleware.py（config/AuthGuards）
- [x] common_utils — utils/common_utils.py（setup_logging → resources/logback-spring.xml）
- [x] knowledge_permissions — utils/knowledge_permissions.py（permissions/KnowledgePermissions）
- [x] knowledge_response — utils/knowledge_response.py（common/KnowledgeResponseSerializer）
- [ ] lifespan — utils/lifespan.py（仅 app.state 两个字段由 config/StartupState 承载，其余未搬）

---

## 已落地（本会话前，供追溯，不计入待办）
- 批次①~⑳：services 层 29/43（见上「已完成」）+ 基础设施（repositories/models/config/permissions/workspace 前置/common/agents 前置/knowledge 解析面/storage-minio）
- 详见 `.workbuddy/memory/2026-09-19.md` 逐批记录

## 移植记录
| 日期 | 条目 | commit |
|------|------|--------|
| 2026-09-19 | （清单建立，无新增搬移） | — |
| 2026-09-19 | RAGFlow 分块家族 9/12：dispatcher/nlp/presets/book/general/laws/qa/separator/md_parser_utils/semantic_utils | `ec74d88` |
| 2026-09-19 | RAGFlow 分块家族补齐 3/12 + 预设真正分流：semantic/table_utils + ChunkPresets.mapToInternalParserId 修正 | `9299405` |
| 2026-09-19 | DocumentService 接入 RAGFlow 预设打通闭环 + RagflowNlp 正则 `{,2}`→`{0,2}` 方言修复 | `dddf719` |
| 2026-09-19 | knowledge 根核心 9/9（base/manager/factory/runtime/read_models/schemas/preview/cache/security）+ implementations/milvus/read_only_connectors + KnowledgeTaskService | `77bbe8f` |
| 2026-09-19 | §五 批次一：server/utils 5/6（access_log/auth_middleware/common_utils/knowledge_permissions/knowledge_response）+ routers 7（mention/project/system_task/knowledge_dashboard/dashboard/graph/filesystem）+ 平台差异载体（GlobalExceptionHandler/ApiHttpException/AuthGuards/CorsConfig/LoginRateLimitFilter） | `bac62c1` |
| 2026-09-19 | §五 批次二：routers 3（user/auth_dept/system）+ storage/MinioUploads（upload_image_to_minio）+ config/LogPaths + config/StartupState + common/AppVersion + info.template.yaml | — |
| 2026-09-19 | §五 批次三：workspace_service（9/9 函数）+ auth_router（AuthRouterController 22 端点 + CLI 会话/OIDC/头像上传）+ workspace_router（WorkspaceController 含 knowledge 只读三端点） | 本次 |

## 挡在后面的依赖（routers 剩余 13 个的阻塞点）
> 依据：逐 router 提取 `from <ref>.…` 模块清单，与本工程已有类比对。**当前仅 `external_kb_router` 无阻塞**。

| router | 阻塞项 | 归属 |
|---|---|---|
| external_kb_router | 无（base/read_models/runtime 均已搬） | — |
| knowledge_router | mindmap_utils/sample_question_utils 的高层函数（workspace_service 已解除） | §二 |
| model_provider_router | `models/providers/service.py` + `cache.py` 全量（现有 ModelProviderService 仅 3 个方法） | §二 |
| scheduled_agent_router | `scheduled_agent_service` | §一 |
| knowledge_eval_router | `knowledge/eval/service.py` + `benchmark_generation.py` | §二 |
| chat_router | `chat_service` / `artifact_service` / `context_compression_service` | §一 |
| agent_router | `agent_config_service` / `agent_request_service` / `agent_request_queue_service` / `agent_run_service` / `agents/buildin` | §一 + §三 |
| agent_invocation_call_router | `agent_request_service` | §一 |
| agent_invocation_channel_router | 上列 + `channel_command_service`（已搬）+ `chat_service` | §一 |
| agent_invocation_eval_router | `agent_request_service` / `agent_run_service` | §一 |
| mcp_router | `agents/mcp/service.py` | §三 |
| skill_router | `agents/skills/service.py` / `remote_install.py` | §三 |
| tool_router | `agents/toolkits/service.py` | §三 |
