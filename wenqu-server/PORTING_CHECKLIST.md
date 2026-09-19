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

## 本地启动与实跑验收（2026-09-19 首次跑通）

本机无 maven，用 `wenqu-server/build.sh` 代替 `mvn`（javac + `~/.m2` 依赖 jar 拼 classpath）：

```bash
cd wenqu-server
bash build.sh             # 全量编译到 target/classes（每次先清空，见脚本注释）
bash build.sh start       # 编译后【后台】启动，监听 8095，上下文 /v2（日志 run/server.log）
bash build.sh status      # 端口 / PID / 健康检查
bash build.sh stop        # 停止（按端口找监听者，不依赖 pid 文件）
bash build.sh restart     # stop + start
bash build.sh deps-check  # 对账「pom 依赖闭包」与「源码 import」
bash build.sh run         # 【前台】启动（占住终端，仅调试时用）
```

- **别用 `run` 当常驻服务**：它是前台进程，Ctrl+C、关窗口、或另开终端再跑一次，都会制造
  「Port 8095 already in use」的假故障；而后台实例一旦脱离终端，`ps` 未必看得见，
  表现为「明明没跑却又起不来」。`status` / `stop` 按端口判定，不依赖 pid 文件。
- **工作目录必须是模块根 `wenqu-server/`**：`RuntimePaths` 里 `skill-sources` / `skill-projections` /
  `user-data` / `data` 都是相对路径。IDE 默认 cwd 是项目根，会把运行期目录写到仓库根，
  与 `build.sh` 启动产生的副本分裂成两套。已提供 `.idea/runConfigurations/WenquServerApplication.xml`
  （指定 `WORKING_DIRECTORY=$PROJECT_DIR$/wenqu-server` 与固定密钥）。
- ⚠️ **classpath 有两条互不一致的来源**：`build.sh` 用「`~/.m2` 全量」，IDEA / Maven 用「`pom.xml` 闭包」。
  任何躺在 m2 里却没写进 pom 的 jar，都会造成「命令行编得过、IDEA 报找不到符号」。
  凡改动源码引入新依赖，先 `bash build.sh deps-check` 对账再补 pom。

- 前置：MySQL `127.0.0.1:3306/ai_doc_assistant`、Redis `127.0.0.1:6379` 可用；**不需要** Neo4j
  （连接为按需懒建，`Neo4jAutoConfiguration` 已排除）。
- `build.sh run` 已内置固定密钥（`SERVER_PORT` / `WENQU_JWT_SECRET` / `JWT_SECRET_KEY` / `WENQU_INSTANCE_ID`），
  否则每次重启令牌全失效，联调时表现为「刚登录又被踢回登录页」。
- 管理员账户：首次 `POST /v2/api/auth/initialize`（`{"uid":"admin","password":"..."}`）建号，
  之后 `POST /v2/api/auth/token` 登录取令牌。
- **两套并存的登录契约**：`/api/auth/**`（JWT `sub` = `users.id`）与 `/api/ai/auth/**`
  （`sub` = `c_ai_user.uid`），`config/UserContextInterceptor` 两者都认。
- **前端联调口径**：本仓库 `web/` 是旧版前端（代理 `localhost:8090/ai`，指向旧后端），对不上本服务。
  参考前端的开发服务器按 `^/api` → `VITE_API_URL` 代理，故设 `VITE_API_URL=http://127.0.0.1:8095/v2` 即可联调。
- 已知非阻塞现象：`GET /api/system/ready` 返回 **503**（`checks.worker = WorkerUnavailableError`）——
  run 队列 worker 进程未搬，参考实现在无 worker 时同样 503；前端不消费该端点。
- 运行期派生目录 `skill-sources/`、`skill-projections/`、`user-data/`、`data/`（含启动期生成的
  `data/secret/config-rsa.key`）、`logs/`、`run/` 均由启动流程生成，已 gitignore。
- 依赖清单以 **`pom.xml` 为准**：`build.sh` 的全量 classpath 只是「能编过」的宽松近似。
  当前 `deps-check` 的缺口为 0（223 个闭包 artifact、199 条第三方 import 全部有归属）。

## 进度概览
- ✅ 已完成：repositories(24) / models(10) / config(4) / permissions(2) / workspace 前置(3) / common 工具(20) / agents 前置层(10) / knowledge 解析面(17) / storage(minio) / services 30-43 / 3 个 controller（Auth/Document/KnowledgeBase） / RAGFlow 分块家族 12/12（全量直译，见下） / knowledge 根核心 9/9 + knowledge_task_service + workspace_service / knowledge/eval 4/4 / **knowledge/utils 全 5/5（batch⑩ 补齐 mindmap_utils + sample_question_utils 的高层 DB/LLM 面 → KnowledgeContentService）** / agents/mcp 1/1 / **agents/skills 2/3（service + remote_install）** / **§五 API 层 26/33（routers 19 + utils 5）**
- 🔲 剩余：5 大块，**49 项待办 + 1 项部分完成**（§一 12 / §二 2 / §三 30 / §五 8，另 `[~]`：lifespan）。**knowledge_router 已解锁并全量落地（批次⑩）**；其余 router 阻塞在 §一 的 agents 服务与 §三 的 agents 运行时（middlewares / toolkits / backends / skills/runtime）——**必须先搬 §三 才能解锁 §一，再解锁 §五**。**服务已能实跑**（启动组件 5/11，见「本地启动与实跑验收」）。

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
- [x] mindmap_utils — utils/mindmap_utils.py（纯函数面 KnowledgeMindmap；**高层 DB/LLM 面**全部搬入 `knowledge/KnowledgeContentService`：`listMindmapFilesPage`/`loadMindmapCurrentFiles`/`getMindmapDatabaseFiles`/`getMindmapDiff`/`updateMindmapIncremental`/`generateDatabaseMindmap`/`getMindmapDatabasesOverview`/`getDatabaseMindmapData`/`removeFileFromMindmap`/`batchRemoveFilesFromMindmap`）
- [x] sample_question_utils — utils/sample_question_utils.py（纯函数面 KnowledgeSampleQuestions；**高层面**搬入 `knowledge/KnowledgeContentService`：`generateDatabaseSampleQuestions`/`getDatabaseSampleQuestions`）
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
- [x] knowledge_router — routers/knowledge_router.py（KnowledgeBaseController **全量重写至新知识库宇宙**（`knowledge_bases` 表 + KnowledgeBaseRepository/Manager/Runtime + KnowledgeResponseSerializer），**56/56 端点**（26 GET + 23 POST + 4 PUT + 3 DELETE；脚本对拍参考 56 = Java 56，缺失 0 / 多余 0）。**清单原记「57 个端点」为早期误计，实为 56**，已同步修正类 Javadoc 与 KnowledgeRouteSupport 注释。参考实现单文件里的 9 个模块级函数 + 5 个常量提取到 `service/KnowledgeRouteSupport`（与 ExternalKbController 共享，避免错误码分叉）；mindmap/sample-questions 的 DB+LLM 高点由 `knowledge/KnowledgeContentService` 承载。差异项：①`agent_manager.reload_all()`（§三等 agents 运行时，未搬）不调用——本工程运行时按 kbId 懒解析，无陈旧缓存；②`export` 在参考实现是基类空实现、Milvus 未覆写 → 映射 501；③SSE 用 `StreamingResponseBody` 手写 `data: {json}\n\n` 帧；④前端 `upload-folder`/`process-folder` 在参考后端**不存在**（前端死代码），未实现）
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
- [~] lifespan — utils/lifespan.py（**启动组件已搬 5/11**：`builtin_mcp_servers`（config/McpStartupInitializer，required=false）、`builtin_skills` + `default_agents` + `model_providers` + `model_cache`（config/StartupDataInitializer，四者 required=true 按原顺序串行）+ `ensure_options_in_db`（config/OptionStartupInitializer，参考实现里是裸调用）＋`app.state` 两字段（config/StartupState）。**未搬 3 项**：`security_secrets`（AuthUtils.requireSecuritySecrets 已就绪，但接上会新增「必须配置 JWT_SECRET_KEY / API_KEY_DERIVATION_SECRET / SANDBOX_PROVISIONER_TOKEN 三个 ≥32 位且互不相同的密钥」这一启动前置——参考实现即如此，是否照搬待定）、`knowledge_base`（参考实现按 kb_type 预建共享执行器并在类型不受支持时 fail-fast；本工程 KnowledgeBaseFactory 有 `isTypeSupported`，但运行时是「单一通用执行器按 kbId 懒解析」，无逐类型实例可预建，等价实现方式待定）、`sandbox_provider`（本工程未部署 provisioner 沙盒，无对应实现）。另有 run 队列 worker 进程（services/run_worker.py）未搬，故 `/api/system/ready` 的 `worker` 检查恒为 error → 整体 503，与参考实现在无 worker 时行为一致）

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
| 2026-09-19 | §三 批次九：`agents/skills/service.py`（1827 行，90 函数 90/90）+ `agents/skills/remote_install.py` + 内置 skill 资源（`src/main/resources/skills/` 5 技能 9 文件）→ **解锁 `skill_router`** → SkillController（26 端点，两个参考 router 合并为一个类）。路由集合对拍 26/26 零差异；函数级覆盖对拍 90/90（唯一差异项 `_user_skills_file_lock` → 回调式）；内置清单/常量逐字对齐 | `2916c3f` |
| 2026-09-19 | **首次实跑（服务真起 + 登录 + 打接口）**：修 7 处只在运行期成立的缺陷（PosixPathLite.join 空基越界 → initBuiltinSkills 必抛；javac 残留 .class 致 Mapper bean 冲突；同名 Mapper 跨包 → 全限定 bean 名生成器；MilvusGraphService/TaskService 多构造器缺 @Autowired；Neo4jAutoConfiguration 强制建 Driver；UserContextInterceptor 只认单一令牌契约致参考契约全 401；KnowledgeBaseController 缺 `/api` 前缀）。补齐启动组件至 5/11，运行期派生目录进 .gitignore。冒烟 16/16 + 读写往返 12/12 通过 | `ef528ce` |
| 2026-09-19 | §二 批次⑩：`utils/mindmap_utils.py` + `utils/sample_question_utils.py` 的**高层 DB/LLM 面** → `knowledge/KnowledgeContentService`（10 + 2 函数）→ **解锁 `knowledge_router`** → `KnowledgeBaseController` 全量重写至新知识库宇宙（`knowledge_bases` 表 + Repository/Manager/Runtime）**56/56 端点**；参考实现 9 个模块级函数 + 5 个常量 → `service/KnowledgeRouteSupport`（与 ExternalKbController 共享）。端点集合对拍 56==56 零差异；全量编译 343 源文件 0 错误；实跑冒烟 10/10 只读端点 + 错误码通过（422/404/401/501 均符合预期），建库 400 系参考实现 `manager.py:507` 的 `if not embedding_model_spec: raise` 语义，与参考一致（非缺陷） | `5a81263` |

## 挡在后面的依赖（routers 剩余 7 个的阻塞点）
> 依据：逐 router 提取 `from <ref>.…` 模块清单，与本工程已有类比对。**当前无任何剩余 router 可无阻塞直搬**，全部等下列条目先落地。（`knowledge_eval_router` 的检索面 `aquery`、`mcp_router` 的 mcp/service、`skill_router` 的 skills/{service,remote_install} 三个阻塞均已解除并搬完。）

| router | 阻塞项 | 归属 |
|---|---|---|
| knowledge_router | ~~mindmap_utils/sample_question_utils 的高层 DB/LLM 函数~~ **均已搬完（批次⑩ → KnowledgeContentService）→ knowledge_router 已解锁并全量落地（56/56 端点）** | §二 |
| scheduled_agent_router | `scheduled_agent_service` | §一 |
| chat_router | `chat_service` / `artifact_service` / `context_compression_service` | §一 |
| agent_router | `agent_config_service` / `agent_request_service` / `agent_request_queue_service` / `agent_run_service` / `agents/buildin` | §一 + §三 |
| agent_invocation_call_router | `agent_request_service` | §一 |
| agent_invocation_channel_router | 上列 + `channel_command_service`（已搬）+ `chat_service` | §一 |
| agent_invocation_eval_router | `agent_request_service` / `agent_run_service` | §一 |
| skill_router | ~~`agents/skills/service.py` / `remote_install.py`~~ **均已搬完（批次九）→ skill_router 已解锁并落地** | §三 |
