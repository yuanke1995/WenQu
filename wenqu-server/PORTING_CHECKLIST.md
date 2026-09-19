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
- ✅ 已完成：repositories(24) / models(10) / config(4) / permissions(2) / workspace 前置(3) / common 工具(20) / agents 前置层(10) / knowledge 解析面(17) / storage(minio) / services 30-43 / 3 个 controller（Auth/Document/KnowledgeBase） / RAGFlow 分块家族 12/12（全量直译，见下） / knowledge 根核心 9/9 + knowledge_task_service + workspace_service / knowledge/eval 4/4 / agents/mcp 1/1 / **agents/skills 2/3（service + remote_install）** / **§五 API 层 25/33（routers 18 + utils 5）**
- 🔲 剩余：5 大块，**52 项待办 + 3 项部分完成**（§一 12 / §二 2 / §三 29 / §五 9，另 `[~]`：knowledge_router、mindmap_utils、sample_question_utils）。**检索面 `aquery`、`agents/mcp/service.py`、`agents/skills/{service,remote_install}.py` 三个阻塞均已解除并搬完**；knowledge_router 剩余阻塞是 mindmap/sample_question 高层函数，其余 router 阻塞在 §一 的 agents 服务与 §三 剩余中间件（skills 家族只剩 runtime.py）。

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
- [x] metrics — eval/metrics.py（knowledge/eval/EvalMetrics：RetrievalMetrics / AnswerMetrics / EvaluationMetricsCalculator；json_repair.loads → common/LooseJson 近似，能力差异已标注）
- [x] evaluator — eval/evaluator.py（knowledge/eval/EvalEvaluator：normalize_query_result / build_answer_prompt / generate_answer_if_needed / evaluate_question / aggregate_metrics；检索面走 `kbManager.aquery`）
- [x] benchmark_generation — eval/benchmark_generation.py（knowledge/eval/EvalBenchmarkGeneration：collect_kb_chunks / select_neighbor_chunks_by_kb_query / select_graph_enhanced_chunks（走 MilvusGraphService.queryAndRankChunksByPpr）/ build_benchmark_generation_prompt / iter_generated_benchmark_items；asyncio Queue → 线程池 + 按下标 Future.get 保序）
- [x] service — eval/service.py（knowledge/eval/EvalService：数据集 upload/list/detail/export/delete/generate/resume + 评估 run/list/results/delete + 两个长任务体 generateDatasetTask/runEvaluationTask；任务 Handler 见 EvalTaskService）
### graphs（6）
- [x] extractors/base — graphs/extractors/base.py（extractors/GraphExtractor）
- [x] extractors/factory — graphs/extractors/factory.py（extractors/GraphExtractorFactory）
- [x] extractors/llm — graphs/extractors/llm.py（extractors/LlmGraphExtractor）
- [x] graph_utils — graphs/graph_utils.py（GraphUtils）
- [x] milvus_graph_service — graphs/milvus_graph_service.py（MilvusGraphService）
- [x] milvus_graph_vector_store — graphs/milvus_graph_vector_store.py（GraphVectorStore；后端由 Spring AI VectorStore 承载，能力差异已标注）
> 以上 6 项 + storage/neo4j 由 `688abda` 一次搬完（含 KnowledgeGraphRetrieval ← implementations/milvus.py 的检索融合函数）。
### implementations（4）
- [ ] dify — implementations/dify.py（参数面已搬入 KnowledgeBaseTypeParams；aquery 依赖外部 Dify HTTP 接口，未搬）
- [x] milvus — implementations/milvus.py（执行器由既有 Spring 内核 VectorStore / KnowledgeChunkRepository / HybridRetrievalService 承载；类型参数与检索参数清单 → KnowledgeBaseTypeParams）
- [ ] notion — implementations/notion.py（参数面已搬入 KnowledgeBaseTypeParams；aquery 依赖外部 Notion HTTP 接口，未搬）
- [x] read_only_connectors — implementations/read_only_connectors.py（ReadOnlyConnectors：只读能力判定与报错文案）
### utils 剩余（5，kb_utils/pdf_utils 已搬）
- [~] mindmap_utils — utils/mindmap_utils.py（**部分**：纯函数面已搬入 KnowledgeMindmap；`generate_database_mindmap`/`get_database_mindmap_data`/`get_mindmap_database_files`/`get_mindmap_diff`/`update_mindmap_incremental`/`get_mindmap_databases_overview`/`remove_file_from_mindmap`/`batch_remove_files_from_mindmap` 等**高层 DB/LLM 函数未搬** → 仍挡 knowledge_router）
- [~] sample_question_utils — utils/sample_question_utils.py（**部分**：纯函数面已搬入 KnowledgeSampleQuestions；`generate_database_sample_questions`/`get_database_sample_questions` 未搬）
- [x] security — utils/security.py（KnowledgeSecurity）
- [x] url_fetcher — utils/url_fetcher.py（KnowledgeUrlFetcher）
- [x] url_validator — utils/url_validator.py（KnowledgeUrlValidator；白名单环境变量已去品牌化为 `WENQU_URL_WHITELIST`）

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
- [x] service — mcp/service.py（**全量 671 行**：`McpService`（内置同步 / 客户端装配 `McpClientBundle` / 工具缓存与统计 / 配置 CRUD / 启用开关 / 工具开关 / 统一入口三函数）+ `McpTool`（langchain tool 最小面：name/description/args_schema/可变 metadata/handle_tool_error + 调用）+ `McpServerViews`（`to_dict`/`to_mcp_config`/`serialize_mcp_server` 与 JSON 列反序列化）+ `MCPServerNotFoundException` / `McpBuiltinImmutableException`；`MCPServerRepository` 逐条对位参考实现里出现过的查询；启动组件见 `config/McpStartupInitializer`（对应 lifespan 的 `builtin_mcp_servers`，required=False）。**跨语言对拍**：`to_camel_case` / `json.dumps(sort_keys,ensure_ascii,separators)` / `sha256[:16]` 29/29 行逐字节一致）
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
- [x] remote_install — skills/remote_install.py（**纯函数面全量**：`_normalize_source` / `_normalize_skill_name` / `_clean_cli_output`（ANSI + 控制序列 + 装饰符清洗 + `str.splitlines` 语义）/ `_parse_available_skills` / `_parse_search_skills` / `allowed_hosts`（OptionsService.REMOTE_SKILL_SOURCE_POLICY）；常量 7 项逐字对齐。**能力缺口（已标注）**：三个沙盒入口（list/prepare/search）保留签名与非法来源校验后抛 `SkillRemoteExecutionUnavailableException`（外置 provisioner 沙盒未部署，同 dify/notion 口径）；`search("")` 与「skills 列表为空」等参考实现不依赖沙盒的分支照旧生效）
- [ ] runtime — skills/runtime.py
- [x] service — skills/service.py（**全量 1827 行 → 2699 行 Java**：90 个参考函数 90/90 对位（仅 `_user_skills_file_lock` 因 Java 无上下文管理器而落为回调式 `withUserSkillsFileLock`）；常量（slug 正则 / 26 项文本扩展名白名单 / 内置操作者与管理员角色 / 默认与内置共享配置 / 草稿 TTL 3600 / 个人来源类型 / 存储锁 `0x5958534B`）逐字一致；内置 skill 清单 5 条（image-gen/html-preview/deep-research/knowledge-base/mysql-reporter 的 slug + description + version + 三类依赖）逐字对齐；必要替换 3 类（锁键前缀 `wenqu:skills:user-projection:v1:`、PG 事务级建议锁→MySQL 会话级 `GET_LOCK`/`RELEASE_LOCK`、内置描述品牌词→「问渠」）与能力差异 8 类（AsyncSession 形参 / to_thread / gather / fcntl.flock / threading.Lock / to_dict / 内置目录 classpath 化 / YAML）全部已在类注释标注。**内置 skill 资源**复制到 `src/main/resources/skills/`（9 文件，`__init__.py` 除外））
### toolkits（5，registry.py/utils.py 已搬）
- [ ] buildin/install_skill — toolkits/buildin/install_skill.py
- [ ] buildin/tools — toolkits/buildin/tools.py
- [ ] debug/tools — toolkits/debug/tools.py
- [ ] kbs/tools — toolkits/kbs/tools.py
- [ ] service — toolkits/service.py（ToolkitsService：get_tool_metadata/get_tool_instances_by_category/extract_tool_info/ensure_metadata_loaded 已搬；resolve_configured_runtime_tools 依赖 mcp/service.py 与 skills/runtime.py，未搬）

## 四、storage 后端（3，minio 已搬）
参考路径前缀 `package/<ref>/storage/`
- [x] postgres/manager — postgres/manager.py（Spring/MyBatis 接管，标记为 N/A）
- [x] neo4j/manager — neo4j/manager.py（storage/neo4j/Neo4jConnectionManager，`688abda`）
- [x] redis/manager — redis/manager.py（Spring StringRedisTemplate 接管，标记为 N/A）
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
- [x] external_kb_router — routers/external_kb_router.py（ExternalKbController；`/api/knowledge/databases/external*`，5 端点：列出可见库 / 文件列表 / 检索 / 打开文件 / 文件内查找）
- [x] filesystem_router — routers/filesystem_router.py（FilesystemController；`/api/viewer/filesystem`）
- [x] graph_router — routers/graph_router.py（GraphController；`/api/graph`）
- [x] knowledge_dashboard_router — routers/knowledge_dashboard_router.py（KnowledgeDashboardController；`/api/dashboard/stats/knowledge`）
- [x] knowledge_eval_router — routers/knowledge_eval_router.py（KnowledgeEvalController；`/api/evaluation`，11 端点：数据集 upload/list/detail/download/delete/generate/resume + 评估 run 发起/历史/结果/删除）
- [~] knowledge_router — routers/knowledge_router.py（**部分 11/57 端点**：KnowledgeBaseController 已有 `/knowledge/databases` 系列 CRUD + chunk-presets + query-params + query-test；**剩余挡在 mindmap_utils/sample_question_utils 的高层函数**）
- [x] mcp_router — routers/mcp_router.py（McpController；`/api/system/mcp-servers`，10 端点：列表（普通用户脱敏 5 字段）/新建/详情/更新/删除/连通性测试/启用开关/工具清单/工具刷新/单工具开关；错误码 400/403/404/422/500 与文案逐字对齐，`extra="forbid"` → 422）
- [x] mention_router — routers/mention_router.py（MentionController；`/api/mention`）
- [x] model_provider_router — routers/model_provider_router.py（ModelProviderController；`/api/system/model-providers`，9 端点：列表 / 新建 / 详情 / 更新 / 删除 / 远端模型拉取 / 缓存刷新 / 分组模型 v2 / 连通性状态）＋ providers 数据面全量（`models/providers/{service,cache,builtin,repository}.py` → ModelProviderService / ModelProviderCache / BuiltinProviders（25 家）/ ModelProviderRepository，加 `models/{chat,embed,rerank}.py` 的 spec 选择与连通性测试面 → ModelSelectors；**25 家内置供应商逐字对齐，含注释掉未启用的 anthropic/google 条目亦未收录**）
- [x] project_router — routers/project_router.py（ProjectController；`/api/projects`）
- [ ] scheduled_agent_router — routers/scheduled_agent_router.py
- [x] skill_router — routers/skill_router.py（SkillController；参考实现两个 router（`/system/skills` + `/skills`）合并为一个类（类级无 `@RequestMapping`，逐方法声明完整路径，避免 Spring 多前缀凭空生成路径），对外即 `/api/system/skills` 与 `/api/skills`。**26 端点逐一对应**前端 `web/src/apis/skill_api.js` 的 26 处调用（路由集合对拍 0 差异）；响应体 `{"success":true,"data":…}`（非本产品 ResultJson），卡片/管理列表加 `allowed_access_levels`、草稿确认与批量删除加 `summary`；错误码照搬 `_raise_from_value_error`（含「不存在/无权」→404，否则 400）；请求体必填 → 422、字段级校验 → 422（`loc=["body",字段]`）、**空串放行**（`content:""` 保存空文件、`path:""`/`source:""` 由服务层判 400））
- [x] system_router — routers/system_router.py（SystemController；`/api/system`）
- [x] system_task_router — routers/system_task_router.py（TaskController；`/api/tasks`）
- [x] tool_router — routers/tool_router.py（ToolController；`/api/system/tools`，2 端点）
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
| 2026-09-19 | §五 批次五：routers 1（tool）+ tools/service 数据面 | `a75818c` |
| 2026-09-19 | §五 批次四：routers 1（external_kb） | `62608ad` |
| 2026-09-19 | §五 批次三：workspace_service（9/9 函数）+ auth_router（AuthRouterController 22 端点 + CLI 会话/OIDC/头像上传）+ workspace_router（WorkspaceController 含 knowledge 只读三端点） | `20015b5` |
| 2026-09-19 | §五 批次六：model_provider_router + providers 数据面全量（ModelInfo/BuiltinProviders/ModelProviderCache/ModelSelectors + ModelProviderService 重写 + ModelProviderRepository 补全）；`_normalize_payload` 跨语言对拍 26/26 逐字一致 | `055b57f` |
| 2026-09-19 | 清单滞后项修正（graphs 6 / utils 2 / storage 3 实为已搬） | `55993b0` |
| 2026-09-19 | 批次七（部分）：`knowledge/eval/metrics.py` → EvalMetrics + common/LooseJson；**修正批次六签名偏差** `ChatAdapter.call(message, stream=False)`（参考实现 message 可为 str 或消息列表，返回 GeneralResponse.content） | `766cf2b` |
| 2026-09-19 | §五 批次七（续）：**检索面 aquery 补齐**（`KnowledgeBaseRuntime.aquery`：合并 kwargs→final_top_k/similarity_threshold/search_mode/use_reranker/use_graph_retrieval/recall_top_k/file_name 过滤，vector（VectorStore 超采样 + 内存 kb/file 过滤）/keyword（`KnowledgeChunkRepository.searchByKeywords`）/hybrid（0.7:0.3 加权融合）+ 图谱融合 + hydrateChunkSources + 重排；必要替换与能力差异均已在类注释标注）→ `KnowledgeBaseManager.aquery/retrieve` 改为委托；`knowledge/eval` 3 模块全搬（EvalBenchmarkGeneration / EvalEvaluator / EvalService）+ `EvalTaskService`（dataset_generation / rag_evaluation 两个 Durable Task Handler）+ `EvaluationRepository` 补 `getDatasetForUpdate`/`getRunForUpdate`；`knowledge_eval_router` → KnowledgeEvalController（`/api/evaluation`，11 端点，`{"message":"success","data":...}` 响应体 + `require_evaluation_dataset_read/manage` 依赖等价物） | `0477f43` |

| 2026-09-19 | §五 批次八：`agents/mcp/service.py` 全量 → McpService / McpTool / McpClientBundle / McpServerViews（+ MCPServerRepository 逐条对位、启动组件 McpStartupInitializer ← lifespan 的 `builtin_mcp_servers`）→ **解锁 `mcp_router`** → McpController（10 端点）。跨语言对拍：驼峰化 / Python 风格 JSON dumps / 配置哈希 29/29 行逐字节一致；路由对拍 10/10；mcp_servers 造行 + 三条查询语义 + ensure_builtin 三步终态 + 清理（剩 0 行） | `fa3f111` |
| 2026-09-19 | §三 批次九：`agents/skills/service.py`（1827 行，90 函数 90/90）+ `agents/skills/remote_install.py` + 内置 skill 资源（`src/main/resources/skills/` 5 技能 9 文件）→ **解锁 `skill_router`** → SkillController（26 端点，两个参考 router 合并为一个类）。路由集合对拍 26/26 零差异；函数级覆盖对拍 90/90（唯一差异项 `_user_skills_file_lock` → 回调式）；内置清单/常量逐字对齐 | `待填` |

## 挡在后面的依赖（routers 剩余 7 个的阻塞点）
> 依据：逐 router 提取 `from <ref>.…` 模块清单，与本工程已有类比对。**当前无任何剩余 router 可无阻塞直搬**，全部等下列条目先落地。（`knowledge_eval_router` 的检索面 `aquery`、`mcp_router` 的 mcp/service、`skill_router` 的 skills/{service,remote_install} 三个阻塞均已解除并搬完。）

| router | 阻塞项 | 归属 |
|---|---|---|
| knowledge_router | mindmap_utils/sample_question_utils 的高层 DB/LLM 函数（~~`aquery`~~ 已补齐：`KnowledgeBaseRuntime.aquery` 供 manager.aquery/retrieve 共用） | §二 |
| scheduled_agent_router | `scheduled_agent_service` | §一 |
| chat_router | `chat_service` / `artifact_service` / `context_compression_service` | §一 |
| agent_router | `agent_config_service` / `agent_request_service` / `agent_request_queue_service` / `agent_run_service` / `agents/buildin` | §一 + §三 |
| agent_invocation_call_router | `agent_request_service` | §一 |
| agent_invocation_channel_router | 上列 + `channel_command_service`（已搬）+ `chat_service` | §一 |
| agent_invocation_eval_router | `agent_request_service` / `agent_run_service` | §一 |
| skill_router | ~~`agents/skills/service.py` / `remote_install.py`~~ **均已搬完（批次九）→ skill_router 已解锁并落地** | §三 |
