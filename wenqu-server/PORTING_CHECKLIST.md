# 语析 → 问渠 移植清单（分支 feature/platform-alignment）

> 参考实现位于 `/Users/yuki/Downloads/Yuxi-main/backend`，分两大层：
> - `package/yuxi/`（业务逻辑，services/knowledge/agents/repositories…）
> - `server/`（API 层：FastAPI 入口 + 25 个 router + server utils）
>
> 本清单条目与参考文件 **1:1 对应**，路径均以参考根为基准。

## 用法（重要）
- 每搬完 **一个条目**，把行首 `[ ]` 改成 `[x]`，并在文末「移植记录」表追一行（日期 + 条目 + commit 短哈希）。
- 严格照搬语析：必要替换（引擎版本 / MySQL 方言 / 缓存 key 前缀 / 品牌前缀）与能力差异（如 LangGraph→StateGraphPort）才允许改动，且必须标注。
- 禁词 `yuxi / 语析 / yxkey / yxcli` 在代码与注释中一律清除（第三方库名如 pypinyin 不算）。
- 参考 `services/viewer_filesystem_service.py`（批次⑳）为对拍范本。

## 进度概览
- ✅ 已完成：repositories(23) / models(10) / config(3) / permissions(2) / workspace 前置(3) / common 工具(20) / agents 前置层(10) / knowledge 解析面(17) / storage(minio) / services 29-43 / 3 个 controller（Auth/Document/KnowledgeBase） / RAGFlow 分块家族 9/12（零依赖直译，见下）
- 🔲 剩余：6 大块，约 **113** 个条目（见下）

---

## 一、services 剩余（14）
参考路径前缀 `package/yuxi/services/`
- [ ] agent_config_service — agent_config_service.py
- [ ] agent_request_service — agent_request_service.py
- [ ] agent_request_queue_service — agent_request_queue_service.py
- [ ] agent_run_service — agent_run_service.py
- [ ] agent_run_manifest_service — agent_run_manifest_service.py
- [ ] subagent_run_service — subagent_run_service.py
- [ ] scheduled_agent_service — scheduled_agent_service.py
- [ ] run_worker — run_worker.py
- [ ] arq_worker — arq_worker.py
- [ ] knowledge_task_service — knowledge_task_service.py
- [ ] chat_service — chat_service.py
- [ ] context_compression_service — context_compression_service.py
- [ ] artifact_service — artifact_service.py
- [ ] workspace_service — workspace_service.py

## 二、knowledge 核心（约 40，parser 子包与 kb_utils/pdf_utils 已搬）
参考路径前缀 `package/yuxi/knowledge/`
### 根核心（9）
- [ ] base — base.py
- [ ] cache — cache.py
- [ ] factory — factory.py（KB 工厂，区别于 parser/factory.py）
- [ ] manager — manager.py
- [ ] preview — preview.py
- [ ] read_models — read_models.py
- [ ] runtime — runtime.py
- [ ] schemas — schemas.py
### chunking/ragflow_like（12）
- [x] dispatcher — chunking/ragflow_like/dispatcher.py（RagflowChunkDispatcher）
- [x] nlp — chunking/ragflow_like/nlp.py（RagflowNlp）
- [x] presets — chunking/ragflow_like/presets.py（service/ChunkPresets，本会话前已搬）
- [x] parsers/book — chunking/ragflow_like/parsers/book.py（BookChunkParser）
- [x] parsers/general — chunking/ragflow_like/parsers/general.py（GeneralChunkParser）
- [x] parsers/laws — chunking/ragflow_like/parsers/laws.py（LawsChunkParser）
- [x] parsers/qa — chunking/ragflow_like/parsers/qa.py（QaChunkParser）
- [ ] parsers/semantic — chunking/ragflow_like/parsers/semantic.py（下批；依赖 markdown-it token 流 → 需能力替换）
- [x] parsers/separator — chunking/ragflow_like/parsers/separator.py（SeparatorChunkParser）
- [x] utils/md_parser — chunking/ragflow_like/utils/md_parser_utils.py（RagflowMdParserUtils）
- [x] utils/semantic — chunking/ragflow_like/utils/semantic_utils.py（RagflowSemanticUtils；聚类用 sklearn → 降级按 token 合并）
- [ ] utils/table — chunking/ragflow_like/utils/table_utils.py（下批；依赖 bs4 → 需能力替换）
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
- [ ] dify — implementations/dify.py
- [ ] milvus — implementations/milvus.py
- [ ] notion — implementations/notion.py
- [ ] read_only_connectors — implementations/read_only_connectors.py
### utils 剩余（5，kb_utils/pdf_utils 已搬）
- [ ] mindmap_utils — utils/mindmap_utils.py
- [ ] sample_question_utils — utils/sample_question_utils.py
- [ ] security — utils/security.py
- [ ] url_fetcher — utils/url_fetcher.py
- [ ] url_validator — utils/url_validator.py

## 三、agents 运行时（约 32，仅前置层已搬）
> 已搬：context.py→BaseContext / state.py→AgentState / tool_approval.py→ToolApproval / backends/paths.py→BackendPaths / chatbot/prompt.py→ChatbotPrompt / skills/repository.py→SkillRepository / toolkits/registry.py→ToolkitsRegistry / toolkits/utils.py→ToolkitsUtils
参考路径前缀 `package/yuxi/agents/`
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
参考路径前缀 `package/yuxi/storage/`
- [ ] postgres/manager — postgres/manager.py（Spring/MyBatis 接管，可标记为 N/A）
- [ ] neo4j/manager — neo4j/manager.py（知识图谱）
- [ ] redis/manager — redis/manager.py
> 注：`storage_migrations/`（5）由 actable/Spring 接管，可不做。

## 五、server/API 层（33，零搬）
参考路径前缀 `package/../server/`（即 `backend/server/`）
### 入口（2）
- [ ] main — main.py（FastAPI app 入口 + 路由装配；WenquServerApplication 已存在但无路由）
- [ ] worker_main — worker_main.py（ARQ worker 入口）
### routers（25）
- [ ] agent_invocation_call_router — routers/agent_invocation_call_router.py
- [ ] agent_invocation_channel_router — routers/agent_invocation_channel_router.py
- [ ] agent_invocation_eval_router — routers/agent_invocation_eval_router.py
- [ ] agent_router — routers/agent_router.py
- [ ] auth_dept_router — routers/auth_dept_router.py
- [ ] auth_router — routers/auth_router.py
- [ ] chat_router — routers/chat_router.py
- [ ] dashboard_router — routers/dashboard_router.py
- [ ] external_kb_router — routers/external_kb_router.py
- [ ] filesystem_router — routers/filesystem_router.py
- [ ] graph_router — routers/graph_router.py
- [ ] knowledge_dashboard_router — routers/knowledge_dashboard_router.py
- [ ] knowledge_eval_router — routers/knowledge_eval_router.py
- [ ] knowledge_router — routers/knowledge_router.py
- [ ] mcp_router — routers/mcp_router.py
- [ ] mention_router — routers/mention_router.py
- [ ] model_provider_router — routers/model_provider_router.py
- [ ] project_router — routers/project_router.py
- [ ] scheduled_agent_router — routers/scheduled_agent_router.py
- [ ] skill_router — routers/skill_router.py
- [ ] system_router — routers/system_router.py
- [ ] system_task_router — routers/system_task_router.py
- [ ] tool_router — routers/tool_router.py
- [ ] user_router — routers/user_router.py
- [ ] workspace_router — routers/workspace_router.py
### server/utils（6）
- [ ] access_log_middleware — utils/access_log_middleware.py
- [ ] auth_middleware — utils/auth_middleware.py
- [ ] common_utils — utils/common_utils.py
- [ ] knowledge_permissions — utils/knowledge_permissions.py
- [ ] knowledge_response — utils/knowledge_response.py
- [ ] lifespan — utils/lifespan.py

---

## 已落地（本会话前，供追溯，不计入待办）
- 批次①~⑳：services 层 29/43（见上「已完成」）+ 基础设施（repositories/models/config/permissions/workspace 前置/common/agents 前置/knowledge 解析面/storage-minio）
- 详见 `.workbuddy/memory/2026-09-19.md` 逐批记录

## 移植记录
| 日期 | 条目 | commit |
|------|------|--------|
| 2026-09-19 | （清单建立，无新增搬移） | — |
| 2026-09-19 | RAGFlow 分块家族 9/12：dispatcher/nlp/presets/book/general/laws/qa/separator/md_parser_utils/semantic_utils | `ec74d88` |
